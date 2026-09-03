"""操作服务只组合登记、暂存、查询与事务，不重放模型调用。"""

from datetime import datetime, timezone
from dataclasses import dataclass
from time import monotonic
from uuid import uuid4

from oryx_mem0.contracts import FailureReceipt, OperationIdentity, PendingReceipt, RecallReceipt, SaveReceipt
from oryx_mem0.engine.staging import Change, Code, EngineError, RunContext, StagedResult
from oryx_mem0.storage.operations import RequestConflict


class OutcomeUnknown(RuntimeError):
    def __init__(self):
        super().__init__("记忆操作未取得匹配持久终态")


@dataclass(frozen=True)
class OperationReply:
    receipt: object
    replayed: bool


class OperationService:
    def __init__(self, operations, memories, archive_stage, recall_stage, dimensions, recover):
        if (operations is None or memories is None or not callable(archive_stage)
                or not callable(recall_stage) or not callable(recover)
                or type(dimensions) is not int or dimensions <= 0):
            raise ValueError("记忆操作服务配置无效")
        self.operations = operations
        self.memories = memories
        self.archive_stage = archive_stage
        self.recall_stage = recall_stage
        self.dimensions = dimensions
        self.recover = recover

    def execute(self, identity, raw_input):
        current, replayed = self.operations.register_result(identity, raw_input)
        try:
            result = (self.save(identity, raw_input, registered=current) if identity.kind == "SAVE"
                      else self.recall(identity, raw_input, registered=current))
            return OperationReply(result, replayed)
        except RequestConflict:
            raise
        except Exception:
            # SDK/存储异常不证明业务效果；只以同一操作的匹配持久终态回复。
            try:
                current = self.get(identity.workspace_id, identity.operation_id)
            except Exception:
                current = None
            if isinstance(current, (SaveReceipt, RecallReceipt, FailureReceipt)) and current.identity == identity:
                return OperationReply(current, replayed)
            raise OutcomeUnknown() from None

    def save(self, identity, content, *, registered=None):
        if identity.kind != "SAVE":
            raise ValueError("保存操作类型无效")
        lease, existing, context = self._start(identity, content, registered)
        if lease is None:
            return existing
        try:
            if identity.scope == "CORE":
                staged = StagedResult(identity.workspace_id, identity.operation_id, lease.baseline_revision, (
                    Change("ADD", str(uuid4()), None, content, None, None),
                ), (), 1)
            else:
                with self.memories.read_snapshot() as connection:
                    staged = self.archive_stage(context, connection, content)
            if type(staged) is not StagedResult:
                context.fail(Code.INVALID_RESULT)
            return self.memories.commit(lease, context, staged)
        except EngineError as error:
            self._record_engine_failure(lease, error.code)
            raise

    def recall(self, identity, query, *, registered=None):
        if identity.kind != "RECALL":
            raise ValueError("召回操作类型无效")
        lease, existing, context = self._start(identity, query, registered)
        if lease is None:
            return existing
        try:
            receipt = self.recall_stage(lease, context, query)
            if not isinstance(receipt, RecallReceipt) or receipt.identity != identity:
                context.fail(Code.INVALID_RESULT)
            return receipt
        except EngineError as error:
            self._record_engine_failure(lease, error.code)
            raise

    def get(self, workspace_id, operation_id):
        self.recover(workspace_id)
        return self.operations.get(workspace_id, operation_id)

    def _start(self, identity, raw_input, registered=None):
        if type(identity) is not OperationIdentity:
            raise ValueError("记忆操作身份无效")
        current = self.operations.register(identity, raw_input) if registered is None else registered
        if isinstance(current, (SaveReceipt, RecallReceipt, FailureReceipt)):
            return None, current, None
        if not isinstance(current, PendingReceipt):
            raise ValueError("记忆操作状态无效")
        if current.state == "RUNNING":
            return None, current, None
        lease = self.operations.claim(identity, str(uuid4()))
        if lease is None:
            return None, self.get(identity.workspace_id, identity.operation_id), None
        remaining = _remaining(lease.deadline_at)
        if remaining <= 0:
            return None, self.get(identity.workspace_id, identity.operation_id), None
        context = RunContext(
            identity.workspace_id, identity.operation_id, identity.scope, lease.baseline_revision,
            self.dimensions, monotonic() + min(30.0, remaining),
        )
        return lease, None, context

    def _record_engine_failure(self, lease, code):
        mapping = {
            Code.INVALID_RESULT: "ENGINE_INVALID_RESULT", Code.LIMIT_EXCEEDED: "ENGINE_LIMIT_EXCEEDED",
            Code.AUDIT_FAILURE: "AUDIT_UNAVAILABLE", Code.HISTORY_FAILURE: "HISTORY_UNAVAILABLE",
            Code.DEADLINE: "OPERATION_DEADLINE", Code.SERVICE_FAILURE: "SERVICE_FAILURE",
            Code.ACCESS_DENIED: "ACCESS_DENIED",
        }
        self.memories.fail(lease, mapping.get(code, "SERVICE_FAILURE"))


def _remaining(deadline_at):
    try:
        deadline = datetime.fromisoformat(deadline_at.replace("Z", "+00:00"))
        if deadline.tzinfo is None:
            raise ValueError()
        return (deadline.astimezone(timezone.utc) - datetime.now(timezone.utc)).total_seconds()
    except (AttributeError, TypeError, ValueError):
        raise ValueError("记忆操作期限无效") from None
