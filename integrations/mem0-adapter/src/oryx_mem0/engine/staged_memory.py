"""固定SDK只作用请求暂存；此结果不是数据库提交凭据。"""

import hashlib
import importlib.metadata
import importlib.util
import logging
import os
from pathlib import Path
from types import SimpleNamespace

from oryx_mem0.engine.providers import AuditedEmbedding, AuditedLlm
from oryx_mem0.engine.staging import Change, Code, EngineError, Noop, StagedHistory, StagedResult, StagedVectorStore, text_value

SDK_VERSION = "1.0.11+oryx.1"
SDK_DIGEST = "343b8c0bd52a9054456207ef1cfd491e2d9d2d03f062dba5b8c8ffc164613484"


def _sdk_memory_class():
    """先校验导入环境与全部源码，再加载可能产生非业务配置的SDK。"""
    try:
        root = Path(os.environ.get("MEM0_DIR", ""))
        if os.environ.get("MEM0_TELEMETRY", "").lower() != "false" or not root.is_absolute() or not root.is_dir():
            raise EngineError(Code.INVALID_CONFIG)
        distribution = importlib.metadata.distribution("mem0ai")
        package = Path(distribution.locate_file("mem0")).resolve()
        spec = importlib.util.find_spec("mem0")
        if distribution.version != SDK_VERSION or spec is None or Path(spec.origin).resolve() != package / "__init__.py":
            raise EngineError(Code.INVALID_CONFIG)
        records = {}
        for file in package.rglob("*"):
            if not file.is_file() or "__pycache__" in file.parts:
                continue
            if file.suffix != ".py" or not file.resolve().is_relative_to(package):
                raise EngineError(Code.INVALID_CONFIG)
            records["mem0/" + file.relative_to(package).as_posix()] = hashlib.sha256(file.read_bytes()).hexdigest()
        combined = "".join(f"{name}\0{digest}\n" for name, digest in sorted(records.items()))
        if len(records) != 149 or hashlib.sha256(combined.encode("utf-8")).hexdigest() != SDK_DIGEST:
            raise EngineError(Code.INVALID_CONFIG)
        from mem0.memory import main, setup, telemetry
        if (main.MEM0_TELEMETRY is not False or telemetry.MEM0_TELEMETRY is not False
                or telemetry.client_telemetry.posthog is not None or Path(setup.mem0_dir).resolve() != root.resolve()):
            raise EngineError(Code.INVALID_CONFIG)
        # SDK原文日志不是业务审计，避免模型事实进入普通日志管道。
        logging.getLogger("mem0.memory.main").disabled = True
        return main.Memory
    except EngineError:
        raise
    except Exception:
        raise EngineError(Code.INVALID_CONFIG) from None


_Memory = _sdk_memory_class()


class StagedMemory(_Memory):
    def __init__(self, context, snapshot, llm, embedding):
        if context.scope != "ARCHIVAL":
            context.fail(Code.ACCESS_DENIED)
        if not isinstance(llm, AuditedLlm) or not isinstance(embedding, AuditedEmbedding) or llm.context is not context or embedding.context is not context:
            context.fail(Code.INVALID_CONFIG)
        # 不调用super构造器，否则SDK会先初始化真实向量和历史存储。
        self.context = context
        self.vector_store = StagedVectorStore(context, snapshot)
        self.db = StagedHistory(context)
        self.llm = llm
        self.embedding_model = embedding
        self.config = SimpleNamespace(custom_fact_extraction_prompt=None, custom_update_memory_prompt=None)
        self.api_version = "v1.1"
        self._used = False

    def infer(self, content):
        state = self.context
        state.check()
        if self._used:
            state.fail(Code.INVALID_RESULT)
        self._used = True
        text_value(state, content)
        metadata = {"user_id": state.workspace_id, "agent_id": "ARCHIVAL", "scope": "ARCHIVAL"}
        filters = {"user_id": state.workspace_id, "agent_id": "ARCHIVAL"}
        try:
            returned = _Memory._add_to_vector_store(self, [{"role": "user", "content": content}], metadata, filters, True)
            state.check()
            return self._reconcile(returned)
        except EngineError as error:
            state.fail(error.code)
        except Exception:
            state.fail(Code.INVALID_RESULT)

    def _reconcile(self, returned):
        state = self.context
        state.check()
        if not isinstance(returned, list) or self.llm.facts is None:
            state.fail(Code.INVALID_RESULT)
        journal = self.vector_store.journal
        changes = tuple(entry for entry in journal if isinstance(entry, Change))
        noops = tuple(entry for entry in journal if isinstance(entry, Noop))
        if not self.llm.facts:
            if self.llm.actions is not None or journal or self.db.events or returned:
                state.fail(Code.INVALID_RESULT)
        else:
            actions = self.llm.actions
            if actions is None or len(actions) != len(journal) or len(returned) != len(changes):
                state.fail(Code.INVALID_RESULT)
            history = [(change.event, change.memory_id, change.old_content, change.new_content) for change in changes]
            if self.db.events != history:
                state.fail(Code.HISTORY_FAILURE)
            result_index = 0
            for action, evidence in zip(actions, journal, strict=True):
                if action["event"] == "NONE":
                    if not isinstance(evidence, Noop) or evidence.content != action["text"]:
                        state.fail(Code.INVALID_RESULT)
                else:
                    if not isinstance(evidence, Change) or evidence.event != action["event"]:
                        state.fail(Code.INVALID_RESULT)
                    expected_text = evidence.old_content if evidence.event == "DELETE" else evidence.new_content
                    if action["text"] != expected_text:
                        state.fail(Code.INVALID_RESULT)
                    if action.get("old_memory") is not None and action["old_memory"] != evidence.old_content:
                        state.fail(Code.INVALID_RESULT)
                    expected = {"id": evidence.memory_id, "memory": expected_text, "event": evidence.event}
                    if evidence.event == "UPDATE":
                        expected["previous_memory"] = action.get("old_memory")
                    if returned[result_index] != expected:
                        state.fail(Code.INVALID_RESULT)
                    result_index += 1
                if action["event"] != "ADD":
                    reference = int(action["id"])
                    seen = self.vector_store.seen_ids
                    if reference >= len(seen) or seen[reference] != evidence.memory_id:
                        state.fail(Code.INVALID_RESULT)
        state.check()
        return StagedResult(state.workspace_id, state.operation_id, state.revision, changes, noops, len(self.llm.facts))

    def _forbidden(self, *args, **kwargs):
        self.context.fail(Code.ACCESS_DENIED)

    add = update = delete = delete_all = reset = search = get = get_all = history = _forbidden

    @classmethod
    def from_config(cls, config):
        raise EngineError(Code.ACCESS_DENIED)

    def close(self):
        self.vector_store.close()
        self.db.close()
