"""版本、当前投影、revision与成功凭据在同一短事务中提交。"""

from datetime import timezone
from contextlib import contextmanager
import math
from uuid import uuid4

import psycopg
from psycopg.types.json import Jsonb

from oryx_mem0.contracts import (
    ActionCounts, ContractError, FailureReceipt, OperationIdentity, RecallReceipt, SaveReceipt,
    check_receipt_budget,
)
from oryx_mem0.engine.staging import Code, EngineError, RunContext, StagedResult, valid_uuid
from oryx_mem0.storage.operations import OperationLease, WriteConflict


class HistoryUnavailable(RuntimeError):
    """追加历史未能与业务投影一起提交。"""


class AuditUnavailable(RuntimeError):
    """内部审计不完整，禁止提交业务投影。"""


class ResultLimitExceeded(RuntimeError):
    """成功凭据无法放入协议响应预算。"""


class InvalidGeneratedResult(RuntimeError):
    """模型生成内容不符合可持久化边界。"""


class MemoryTransactionError(RuntimeError):
    """未分类数据库异常不携带原始正文。"""


class MemoryTransactionStore:
    def __init__(self, connection_factory, faults=None, embedding_model=None):
        self.connection_factory = connection_factory
        self.faults = faults
        self.embedding_model = embedding_model

    @contextmanager
    def read_snapshot(self):
        """推理读取固定为只读REPEATABLE READ，连接生命周期不跨请求。"""
        connection = self.connection_factory()
        try:
            with connection.transaction():
                connection.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY")
                yield connection
        except psycopg.Error:
            raise MemoryTransactionError() from None
        finally:
            connection.close()

    def fail(self, lease, error_code):
        """推理阶段已确定失败时，保留原文并写无业务效果的凭据。"""
        self._fail(lease, error_code)

    def complete_recall(self, lease, context, receipt):
        # 只读操作的快照 revision 允许晚于认领时的 baseline（并发 SAVE 不使 RECALL 伪失败），
        # 但不许后退；操作行的 baseline 不变性仍由 UPDATE 的 baseline_revision 守卫保证。
        if (type(lease) is not OperationLease or type(context) is not RunContext
                or type(receipt) is not RecallReceipt or receipt.identity != lease.identity
                or receipt.revision < lease.baseline_revision or context.failure is not None):
            raise InvalidGeneratedResult()
        connection = self.connection_factory()
        try:
            with connection.transaction():
                updated = connection.execute(
                    "UPDATE oryx_memory.memory_operations SET state='COMMITTED',committed_revision=%s,"
                    "result_json=%s,error_code=NULL,completed_at=clock_timestamp() "
                    "WHERE workspace_id=%s AND operation_id=%s AND state='RUNNING' AND owner_token=%s "
                    "AND baseline_revision=%s AND deadline_at>clock_timestamp()",
                    (receipt.revision, Jsonb(receipt.to_dict()), lease.identity.workspace_id,
                     lease.identity.operation_id, lease.owner_token, lease.baseline_revision),
                ).rowcount
                if updated != 1:
                    raise WriteConflict()
            return receipt
        except WriteConflict:
            self._fail(lease, "WRITE_CONFLICT")
            raise
        except psycopg.Error:
            self._fail(lease, "SERVICE_FAILURE")
            raise MemoryTransactionError() from None
        finally:
            connection.close()

    def commit(self, lease, context, staged_result):
        try:
            _validate_envelope(lease, context, staged_result)
        except AuditUnavailable:
            self._fail(lease, "AUDIT_UNAVAILABLE")
            raise
        except InvalidGeneratedResult:
            self._fail(lease, "ENGINE_INVALID_RESULT")
            raise
        except ResultLimitExceeded:
            self._fail(lease, "ENGINE_LIMIT_EXCEEDED")
            raise
        except WriteConflict:
            self._fail(lease, "OPERATION_DEADLINE")
            raise

        connection = self.connection_factory()
        phase = "lock"
        committed = False
        try:
            with connection.transaction():
                operation = connection.execute(
                    "SELECT state,owner_token::text,deadline_at>clock_timestamp(),baseline_revision,kind,scope,request_hash "
                    "FROM oryx_memory.memory_operations WHERE workspace_id=%s AND operation_id=%s FOR UPDATE",
                    (lease.identity.workspace_id, lease.identity.operation_id),
                ).fetchone()
                namespace = connection.execute(
                    "SELECT revision FROM oryx_memory.memory_namespaces WHERE workspace_id=%s FOR UPDATE",
                    (lease.identity.workspace_id,),
                ).fetchone()
                _validate_lock(operation, namespace, lease, context, staged_result)
                revision = namespace[0] + 1
                changed_at = connection.execute("SELECT clock_timestamp()").fetchone()[0]
                actions = _plan(connection, lease.identity, staged_result, revision, changed_at)
                counts = ActionCounts(
                    sum(action["event"] == "ADD" for action in actions),
                    sum(action["event"] == "UPDATE" for action in actions),
                    sum(action["event"] == "DELETE" for action in actions),
                )
                affected = tuple(dict.fromkeys(action["memory_id"] for action in actions))
                receipt = SaveReceipt(
                    lease.identity, _utc_text(changed_at), revision, staged_result.outcome,
                    counts, affected, True,
                )
                try:
                    check_receipt_budget(receipt)
                except ContractError:
                    raise ResultLimitExceeded() from None
                context.check()

                for action in actions:
                    phase = "history"
                    _fault(self.faults, "history_insert")
                    _insert_version(connection, lease.identity, action, revision, changed_at)
                    phase = "current"
                    _fault(self.faults, "current_write")
                    _apply_current(
                        connection, lease.identity.workspace_id, lease.identity.scope, action, revision,
                        changed_at, self.embedding_model,
                    )
                phase = "namespace"
                updated = connection.execute(
                    "UPDATE oryx_memory.memory_namespaces SET revision=%s "
                    "WHERE workspace_id=%s AND revision=%s",
                    (revision, lease.identity.workspace_id, lease.baseline_revision),
                ).rowcount
                if updated != 1:
                    raise WriteConflict()
                phase = "receipt"
                _fault(self.faults, "receipt_write")
                updated = connection.execute(
                    "UPDATE oryx_memory.memory_operations SET state='COMMITTED',committed_revision=%s,"
                    "result_json=%s,error_code=NULL,completed_at=%s "
                    "WHERE workspace_id=%s AND operation_id=%s AND state='RUNNING' AND owner_token=%s",
                    (revision, Jsonb(receipt.to_dict()), changed_at, lease.identity.workspace_id,
                     lease.identity.operation_id, lease.owner_token),
                ).rowcount
                if updated != 1:
                    raise WriteConflict()
                _fault(self.faults, "before_commit")
            committed = True
            _fault(self.faults, "after_commit_before_response")
            return receipt
        except WriteConflict:
            self._fail(lease, "WRITE_CONFLICT")
            raise
        except InvalidGeneratedResult:
            self._fail(lease, "ENGINE_INVALID_RESULT")
            raise
        except ResultLimitExceeded:
            self._fail(lease, "ENGINE_LIMIT_EXCEEDED")
            raise
        except AuditUnavailable:
            self._fail(lease, "AUDIT_UNAVAILABLE")
            raise
        except EngineError as error:
            failure, code = _engine_failure(error)
            self._fail(lease, code)
            raise failure from None
        except psycopg.Error:
            if phase == "history":
                self._fail(lease, "HISTORY_UNAVAILABLE")
                raise HistoryUnavailable() from None
            self._fail(lease, "SERVICE_FAILURE")
            raise MemoryTransactionError() from None
        except RuntimeError:
            if committed:
                raise
            if phase == "history":
                self._fail(lease, "HISTORY_UNAVAILABLE")
                raise HistoryUnavailable() from None
            self._fail(lease, "SERVICE_FAILURE")
            raise MemoryTransactionError() from None
        finally:
            connection.close()

    def _fail(self, lease, error_code):
        connection = self.connection_factory()
        try:
            with connection.transaction():
                row = connection.execute(
                    "SELECT clock_timestamp() FROM oryx_memory.memory_operations "
                    "WHERE workspace_id=%s AND operation_id=%s AND state='RUNNING' AND owner_token=%s FOR UPDATE",
                    (lease.identity.workspace_id, lease.identity.operation_id, lease.owner_token),
                ).fetchone()
                if row is None:
                    return
                receipt = FailureReceipt(lease.identity, "FAILED", _utc_text(row[0]), error_code, False)
                connection.execute(
                    "UPDATE oryx_memory.memory_operations SET state='FAILED',result_json=%s,error_code=%s,"
                    "completed_at=%s WHERE workspace_id=%s AND operation_id=%s AND state='RUNNING' AND owner_token=%s",
                    (Jsonb(receipt.to_dict()), error_code, row[0], lease.identity.workspace_id,
                     lease.identity.operation_id, lease.owner_token),
                )
        except psycopg.Error:
            raise MemoryTransactionError() from None
        finally:
            connection.close()


def _validate_envelope(lease, context, staged):
    if (type(lease) is not OperationLease or type(context) is not RunContext
            or type(staged) is not StagedResult or type(lease.identity) is not OperationIdentity):
        raise InvalidGeneratedResult()
    identity = lease.identity
    if (identity.kind != "SAVE" or identity.workspace_id != context.workspace_id
            or identity.operation_id != context.operation_id or identity.scope != context.scope
            or staged.workspace_id != identity.workspace_id or staged.operation_id != identity.operation_id
            or staged.revision != lease.baseline_revision or context.revision != lease.baseline_revision
            or len(staged.changes) > 128):
        raise InvalidGeneratedResult()
    if context.failure == Code.AUDIT_FAILURE:
        raise AuditUnavailable()
    try:
        context.check()
    except EngineError as error:
        failure, _ = _engine_failure(error)
        raise failure from None
    for change in staged.changes:
        if change.event not in {"ADD", "UPDATE", "DELETE"} or not valid_uuid(change.memory_id):
            raise InvalidGeneratedResult()
        for value in (change.old_content, change.new_content):
            if value is not None:
                _text(value)
        if identity.scope == "CORE":
            if change.event != "ADD" or change.vector is not None:
                raise InvalidGeneratedResult()
        elif change.event == "DELETE":
            if change.vector is not None:
                raise InvalidGeneratedResult()
        else:
            _vector(change.vector, context.dimensions)


def _validate_lock(operation, namespace, lease, context, staged):
    if operation is None or namespace is None:
        raise WriteConflict()
    identity = lease.identity
    if (operation[0] != "RUNNING" or operation[1] != lease.owner_token
            or operation[3] != lease.baseline_revision or operation[4:] != (
                identity.kind, identity.scope, identity.request_hash)
            or namespace[0] != lease.baseline_revision or staged.revision != namespace[0]
            or operation[2] is not True):
        raise WriteConflict()


def _plan(connection, identity, staged, revision, changed_at):
    states = {}
    originals = {}
    actions = []
    for index, change in enumerate(staged.changes):
        if change.memory_id not in states:
            row = connection.execute(
                "SELECT scope,content,version_id::text,created_revision FROM oryx_memory.memory_current "
                "WHERE workspace_id=%s AND memory_id=%s FOR UPDATE",
                (identity.workspace_id, change.memory_id),
            ).fetchone()
            state = None if row is None else {
                "scope": row[0], "content": row[1], "version_id": row[2], "created_revision": row[3],
            }
            states[change.memory_id] = state
            originals[change.memory_id] = None if state is None else state["version_id"]
        state = states[change.memory_id]
        if change.event == "ADD":
            if state is not None or change.old_content is not None or change.baseline_version_id is not None:
                raise WriteConflict()
            created_revision = revision
            previous = None
        else:
            if (state is None or state["scope"] != identity.scope or state["content"] != change.old_content
                    or change.baseline_version_id != originals[change.memory_id]):
                raise WriteConflict()
            created_revision = state["created_revision"]
            previous = state["version_id"]
        version_id = str(uuid4())
        action = {
            "index": index, "event": change.event, "memory_id": change.memory_id,
            "old": change.old_content, "new": change.new_content, "vector": change.vector,
            "previous": previous, "version_id": version_id, "created_revision": created_revision,
        }
        actions.append(action)
        states[change.memory_id] = None if change.event == "DELETE" else {
            "scope": identity.scope, "content": change.new_content, "version_id": version_id,
            "created_revision": created_revision,
        }
    return actions


def _insert_version(connection, identity, action, revision, changed_at):
    connection.execute(
        "INSERT INTO oryx_memory.memory_versions("
        "version_id,workspace_id,memory_id,scope,operation_id,action_index,revision,created_revision,event,"
        "old_content,new_content,previous_version_id,changed_at) "
        "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
        (action["version_id"], identity.workspace_id, action["memory_id"], identity.scope,
         identity.operation_id, action["index"], revision, action["created_revision"], action["event"],
         action["old"], action["new"], action["previous"], changed_at),
    )


def _apply_current(connection, workspace_id, scope, action, revision, changed_at, embedding_model):
    vector = _vector_text(action["vector"])
    model = None if vector is None else embedding_model
    if (scope == "ARCHIVAL" and action["event"] != "DELETE"
            and (not isinstance(model, str) or not model.strip())):
        raise InvalidGeneratedResult()
    if action["event"] == "ADD":
        connection.execute(
            "INSERT INTO oryx_memory.memory_current("
            "workspace_id,memory_id,scope,content,version_id,created_revision,updated_revision,embedding,"
            "embedding_model,updated_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s::vector,%s,%s)",
            (workspace_id, action["memory_id"], scope, action["new"],
             action["version_id"], action["created_revision"], revision, vector, model, changed_at),
        )
    elif action["event"] == "UPDATE":
        connection.execute(
            "UPDATE oryx_memory.memory_current SET content=%s,version_id=%s,updated_revision=%s,"
            "embedding=%s::vector,embedding_model=%s,updated_at=%s "
            "WHERE workspace_id=%s AND memory_id=%s AND version_id=%s",
            (action["new"], action["version_id"], revision, vector, model, changed_at,
             workspace_id, action["memory_id"], action["previous"]),
        )
    else:
        connection.execute(
            "DELETE FROM oryx_memory.memory_current WHERE workspace_id=%s AND memory_id=%s AND version_id=%s",
            (workspace_id, action["memory_id"], action["previous"]),
        )


def _text(value):
    try:
        size = len(value.encode("utf-8"))
    except UnicodeError:
        raise InvalidGeneratedResult() from None
    if not value.strip() or "\x00" in value:
        raise InvalidGeneratedResult()
    if size > 32768:
        raise ResultLimitExceeded()


def _vector(value, dimensions):
    if (type(value) not in (tuple, list) or len(value) != dimensions
            or any(type(item) not in (int, float) or not math.isfinite(item) for item in value)
            or not any(value)):
        raise InvalidGeneratedResult()


def _vector_text(value):
    if value is None:
        return None
    return "[" + ",".join(str(float(item)) for item in value) + "]"


def _utc_text(value):
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _engine_failure(error):
    if error.code == Code.AUDIT_FAILURE:
        return AuditUnavailable(), "AUDIT_UNAVAILABLE"
    if error.code == Code.LIMIT_EXCEEDED:
        return ResultLimitExceeded(), "ENGINE_LIMIT_EXCEEDED"
    if error.code == Code.INVALID_RESULT:
        return InvalidGeneratedResult(), "ENGINE_INVALID_RESULT"
    return WriteConflict(), "OPERATION_DEADLINE"


def _fault(faults, point):
    if faults is not None:
        faults.hit(point)
