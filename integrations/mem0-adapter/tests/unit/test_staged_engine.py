"""固定SDK必须经过真实调用点，不能由理想化Store替代。"""

import hashlib
import importlib.metadata
import inspect
import json
import os
from pathlib import Path

import sdk_build
import pytest
from copy import deepcopy
from dataclasses import replace

from oryx_mem0.engine.staging import Code, EngineError, MAX_TEXT_BYTES


def test_sdk_source_and_private_call_shape_are_pinned():
    from mem0.memory import main

    manifest = json.loads((Path(__file__).parents[1] / "fixtures/sdk_fingerprint.json").read_text())
    assert importlib.metadata.version("mem0ai") == manifest["sdk_version"]
    source = sdk_build.verify_installed()
    assert source["git_commit"] == manifest["git_commit"]
    assert source["upstream_version"] == manifest["upstream_version"]
    root = Path(main.__file__).parents[1]
    for relative, expected in manifest["files"].items():
        # Git在Windows转换行尾不改变Python语义，除此之外不规范化源码。
        content = (root / relative).read_bytes().replace(b"\r\n", b"\n")
        assert hashlib.sha256(content).hexdigest() == expected, relative
    assert list(inspect.signature(main.Memory._add_to_vector_store).parameters) == [
        "self", "messages", "metadata", "filters", "infer"
    ]


def test_sdk_import_isolated_before_first_import():
    from mem0.memory import setup, telemetry

    assert telemetry.MEM0_TELEMETRY is False
    assert telemetry.client_telemetry.posthog is None
    root = Path(os.environ["MEM0_DIR"])
    assert Path(setup.mem0_dir).resolve() == root.resolve()
    assert sorted(item.name for item in root.iterdir()) == ["config.json"]


def test_real_sdk_only_stages_add_without_storage_constructors(engine_factory, monkeypatch):
    from mem0.memory.main import Memory, SQLiteManager, VectorStoreFactory, GraphStoreFactory

    def forbidden(*args, **kwargs):
        raise AssertionError("不允许初始化原版真实存储或公共推理路径")

    monkeypatch.setattr(Memory, "__init__", forbidden)
    monkeypatch.setattr(Memory, "add", forbidden)
    monkeypatch.setattr(Memory, "from_config", forbidden)
    monkeypatch.setattr(SQLiteManager, "__init__", forbidden)
    monkeypatch.setattr(VectorStoreFactory, "create", forbidden)
    monkeypatch.setattr(GraphStoreFactory, "create", forbidden)
    system = engine_factory([{"facts": ["项目使用Java21"]}, {"memory": [{"event": "ADD", "text": "项目使用Java21"}]}])
    assert isinstance(system.engine, Memory)
    result = system.engine.infer("原始输入，项目已升级Java21")
    assert result.outcome == "CHANGED"
    assert result.fact_count == 1
    assert len(result.changes) == 1
    assert result.changes[0].event == "ADD"
    assert result.changes[0].new_content == "项目使用Java21"
    assert result.changes[0].old_content is None
    assert system.snapshot.points == {}
    assert system.snapshot.write_count == 0
    assert all(call["state"] == "COMPLETED" for call in system.audit.calls)


@pytest.mark.parametrize("event", ["UPDATE", "DELETE"])
def test_updates_and_deletes_preserve_old_snapshot_and_history(engine_factory, point_factory, event):
    point = point_factory()
    original = deepcopy(point)
    content = "项目使用Java21" if event == "UPDATE" else point.payload["data"]
    system = engine_factory([{"facts": [content]}, {"memory": [{"event": event, "id": "0", "text": content, "old_memory": point.payload["data"]}]}], [point])
    result = system.engine.infer("明确更新或删除归档事实")
    assert len(result.changes) == 1
    change = result.changes[0]
    assert (change.event, change.memory_id, change.old_content) == (event, point.id, point.payload["data"])
    assert change.new_content == (content if event == "UPDATE" else None)
    assert change.baseline_version_id == point.version_id
    assert system.snapshot.points[point.id] == original
    assert system.snapshot.write_count == 0


def test_none_is_noop_with_metadata_evidence_and_no_content_history(engine_factory, point_factory):
    point = point_factory()
    system = engine_factory([{"facts": [point.payload["data"]]}, {"memory": [{"event": "NONE", "id": "0", "text": point.payload["data"]}]}], [point])
    result = system.engine.infer("重复的原始输入")
    assert result.outcome == "NOOP"
    assert result.changes == ()
    assert len(result.noops) == 1
    assert result.noops[0].memory_id == point.id
    assert result.noops[0].previous_updated_at == point.payload["updated_at"]
    assert system.engine.db.events == []
    assert system.snapshot.points[point.id] == point


def test_legitimate_empty_facts_do_not_call_embedding_or_action_model(engine_factory):
    system = engine_factory([{"facts": []}])
    result = system.engine.infer("无新增事实的问候")
    assert result.outcome == "NOOP"
    assert result.fact_count == 0
    assert result.changes == result.noops == ()
    assert len(system.transport.requests) == 1
    assert system.snapshot.reads == []


@pytest.mark.parametrize("bad", ["not json", "", {}, {"facts": [""]}, {"facts": ["before\x00after"]},
                                 {"facts": [1]}, {"facts": "错误类型"}, '{"facts":[],"facts":[]}'])
def test_invalid_facts_never_become_noop(engine_factory, bad):
    system = engine_factory([bad])
    with pytest.raises(EngineError):
        system.engine.infer("原始输入")


def test_nul_fact_is_rejected_before_action_model_or_storage(engine_factory):
    system = engine_factory([{"facts": ["before\x00after"]}])
    with pytest.raises(EngineError):
        system.engine.infer("原始输入")
    assert len(system.transport.requests) == 1
    assert system.llm.facts is None
    assert system.context.failure is not None
    assert system.audit.calls[-1]["state"] == "FAILED"


@pytest.mark.parametrize("bad", ["not json", {}, {"memory": []}, {"memory": [{"event": "UNKNOWN", "text": "内容"}]}, {"memory": [{"event": "ADD", "text": " "}]}, {"memory": [{"event": "UPDATE", "id": "99", "text": "内容"}]}])
def test_bad_or_swallowed_action_failures_are_fatal(engine_factory, bad):
    system = engine_factory([{"facts": ["内容"]}, bad])
    with pytest.raises(EngineError):
        system.engine.infer("原始输入")
    assert system.context.failure is not None
    assert system.snapshot.write_count == 0


def test_swallowed_history_failure_cannot_produce_valid_result(engine_factory, monkeypatch):
    system = engine_factory([{"facts": ["内容"]}, {"memory": [{"event": "ADD", "text": "内容"}]}])
    def fail(*args, **kwargs):
        raise RuntimeError("合成历史异常")
    monkeypatch.setattr(system.engine.db, "add_history", fail, raising=False)
    with pytest.raises(EngineError):
        system.engine.infer("原始输入")
    assert system.context.failure is not None
    assert system.snapshot.write_count == 0


@pytest.mark.parametrize("bad_scope", [{"scope": "CORE"}, {"user_id": "55555555-5555-4555-8555-555555555555"}, {"agent_id": "CORE"}])
def test_snapshot_cannot_leak_core_or_other_workspace(engine_factory, point_factory, bad_scope):
    system = engine_factory([{"facts": ["内容"]}], [point_factory(**bad_scope)])
    with pytest.raises(EngineError) as error:
        system.engine.infer("原始输入")
    assert error.value.code == Code.ACCESS_DENIED
    assert system.snapshot.write_count == 0


def test_core_is_rejected_before_any_inference(engine_factory, run_context):
    with pytest.raises(EngineError) as error:
        engine_factory([], context=replace(run_context, scope="CORE"))
    assert error.value.code == Code.ACCESS_DENIED


@pytest.mark.parametrize("facts,actions", [
    (["内容"] * 65, None),
    (["内容"], [{"event": "ADD", "text": "内容"}] * 129),
    (["x" * (MAX_TEXT_BYTES + 1)], None),
    (["内容"], [{"event": "ADD", "text": "x" * (MAX_TEXT_BYTES + 1)}]),
])
def test_generated_limits_fail_before_any_snapshot_write(engine_factory, facts, actions):
    responses = [{"facts": facts}]
    if actions is not None:
        responses.append({"memory": actions})
    system = engine_factory(responses)
    with pytest.raises(EngineError) as error:
        system.engine.infer("原始输入")
    assert error.value.code == Code.LIMIT_EXCEEDED
    assert system.snapshot.write_count == 0


def test_maximum_legal_action_count_is_not_silently_dropped(engine_factory):
    system = engine_factory([{"facts": ["内容"]}, {"memory": [{"event": "ADD", "text": "内容"}] * 128}])
    result = system.engine.infer("原始输入")
    assert len(result.changes) == 128
    assert len({change.memory_id for change in result.changes}) == 128
    assert system.snapshot.write_count == 0


def test_maximum_legal_fact_count_and_text_size(engine_factory):
    value = "x" * MAX_TEXT_BYTES
    system = engine_factory([{"facts": [value]}, {"memory": [{"event": "ADD", "text": value}]}])
    assert system.engine.infer("原始输入").changes[0].new_content == value


def test_sixty_four_facts_are_accepted(engine_factory):
    system = engine_factory([{"facts": ["内容"] * 64}, {"memory": [{"event": "ADD", "text": "内容"}]}])
    result = system.engine.infer("原始输入")
    assert result.fact_count == 64
    assert len([call for call in system.audit.calls if call["kind"] == "EMBEDDING"]) == 64


def test_overlay_is_readable_without_mutating_snapshot(engine_factory, point_factory):
    point = point_factory()
    system = engine_factory([], [point])
    vectors = system.engine.vector_store
    before = vectors.get(point.id)
    payload = dict(before.payload, data="暂存新值")
    vectors.update(point.id, vector=[0.5, 0.5], payload=payload)
    loaded = vectors.get(point.id)
    assert loaded.payload["data"] == "暂存新值"
    loaded.payload["data"] = "调用方修改不能污染暂存"
    assert vectors.get(point.id).payload["data"] == "暂存新值"
    hits = vectors.search("新值", [1, 0], filters={"user_id": system.context.workspace_id, "agent_id": "ARCHIVAL"})
    assert hits[0].payload["data"] == "暂存新值"
    vectors.delete(point.id)
    assert vectors.get(point.id) is None
    assert vectors.search("新值", [1, 0], filters={"user_id": system.context.workspace_id, "agent_id": "ARCHIVAL"}) == []
    assert system.snapshot.points[point.id] == point
    assert system.snapshot.write_count == 0


@pytest.mark.parametrize("method", ["reset", "delete_col", "create_col", "list", "list_cols", "col_info"])
def test_unapproved_store_operations_are_rejected(engine_factory, method):
    system = engine_factory([])
    with pytest.raises(EngineError) as error:
        getattr(system.engine.vector_store, method)()
    assert error.value.code == Code.ACCESS_DENIED
    assert system.context.failure == Code.ACCESS_DENIED


def test_noop_cannot_change_content(engine_factory, point_factory):
    point = point_factory()
    system = engine_factory([], [point])
    with pytest.raises(EngineError):
        system.engine.vector_store.update(point.id, vector=None, payload=dict(point.payload, data="偷偷改写"))
    assert system.context.failure is not None
    assert system.snapshot.points[point.id] == point


def test_stage_mutation_budget_includes_noops(engine_factory, point_factory):
    point = point_factory()
    system = engine_factory([], [point])
    for _ in range(128):
        system.engine.vector_store.update(point.id, vector=None, payload=point.payload.copy())
    with pytest.raises(EngineError) as error:
        system.engine.vector_store.update(point.id, vector=None, payload=point.payload.copy())
    assert error.value.code == Code.LIMIT_EXCEEDED


def test_staged_engine_is_single_use_even_after_noop(engine_factory):
    system = engine_factory([{"facts": []}])
    system.engine.infer("第一次")
    with pytest.raises(EngineError):
        system.engine.infer("不得重放")
    assert len(system.transport.requests) == 1


def test_none_with_inconsistent_model_text_is_not_success(engine_factory, point_factory):
    point = point_factory()
    system = engine_factory([{"facts": ["新事实"]}, {"memory": [{"event": "NONE", "id": "0", "text": "错误的NONE正文"}]}], [point])
    with pytest.raises(EngineError):
        system.engine.infer("原始输入")


def test_private_sdk_logs_never_emit_memory_content(engine_factory, caplog):
    content = "private-memory-probe-for-log"
    with caplog.at_level("DEBUG"):
        system = engine_factory([{"facts": [content]}, {"memory": [{"event": "ADD", "text": content}]}])
        system.engine.infer(content)
    assert content not in caplog.text


def test_wrong_id_from_snapshot_get_fails_closed(engine_factory, point_factory, monkeypatch):
    original = point_factory()
    other = point_factory(memory_id="77777777-7777-4777-8777-777777777777")
    system = engine_factory([], [original])
    monkeypatch.setattr(system.snapshot, "get", lambda memory_id: other)
    with pytest.raises(EngineError):
        system.engine.vector_store.get(original.id)
    assert system.context.failure == Code.INVALID_RESULT


def test_multiple_updates_use_overlay_for_old_content_and_keep_baseline_id(engine_factory, point_factory):
    point = point_factory()
    system = engine_factory([{"facts": ["第一次更新", "第二次更新"]}, {"memory": [
        {"event": "UPDATE", "id": "0", "text": "第一次更新"},
        {"event": "UPDATE", "id": "0", "text": "第二次更新"},
    ]}], [point])
    result = system.engine.infer("两步明确归档更新")
    assert [change.old_content for change in result.changes] == [point.payload["data"], "第一次更新"]
    assert [change.new_content for change in result.changes] == ["第一次更新", "第二次更新"]
    assert all(change.baseline_version_id == point.version_id for change in result.changes)
    assert system.snapshot.points[point.id] == point


def test_json_escaping_can_exceed_io_limit_even_when_each_fact_is_legal(engine_factory):
    system = engine_factory([{"facts": ['"' * MAX_TEXT_BYTES] * 9}])
    with pytest.raises(EngineError) as error:
        system.engine.infer("原始输入")
    assert error.value.code == Code.LIMIT_EXCEEDED
    assert system.llm.facts is None
    assert len(system.transport.requests) == 1
    assert system.snapshot.write_count == 0


def test_invalid_unicode_fact_is_fatal(engine_factory):
    system = engine_factory(['{"facts":["\\ud800"]}'])
    with pytest.raises(EngineError) as error:
        system.engine.infer("原始输入")
    assert error.value.code == Code.INVALID_RESULT


def test_cached_sdk_import_does_not_bypass_environment_guard(monkeypatch):
    from oryx_mem0.engine import staged_memory
    monkeypatch.delenv("MEM0_TELEMETRY")
    with pytest.raises(EngineError) as error:
        staged_memory._sdk_memory_class()
    assert error.value.code == Code.INVALID_CONFIG


def test_sdk_digest_mismatch_fails_before_loading_engine(monkeypatch):
    from oryx_mem0.engine import staged_memory
    monkeypatch.setattr(staged_memory, "SDK_DIGEST", "0" * 64)
    with pytest.raises(EngineError):
        staged_memory._sdk_memory_class()
