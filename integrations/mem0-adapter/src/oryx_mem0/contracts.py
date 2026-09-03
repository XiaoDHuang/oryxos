"""共享协议值和提交前预算；构造DTO不构成持久化成功证明。"""

from dataclasses import asdict, dataclass, field
from datetime import datetime
from enum import StrEnum
import hashlib
import json
import math
import re

from oryx_mem0.engine.staging import MAX_ACTIONS, MAX_IO_BYTES, MAX_TEXT_BYTES, valid_uuid

PROTOCOL = "oryx-memory-v1"
MAX_REQUEST_BYTES = 256 * 1024
MAX_RECALL_ITEMS = 20
MAX_PAGE_ITEMS = 100
OPERATION_DEADLINE_SECONDS = 30
_BUDGET_REQUEST_ID = "00000000-0000-4000-8000-000000000001"

# capabilities 报告的固定限制；取自真实执行常量，不得手写漂移。
CAPABILITY_LIMITS = {
    "content_max_bytes": MAX_TEXT_BYTES,
    "request_max_bytes": MAX_REQUEST_BYTES,
    "response_max_bytes": MAX_IO_BYTES,
    "page_size_max": MAX_PAGE_ITEMS,
    "recall_top": MAX_RECALL_ITEMS,
    "operation_deadline_seconds": OPERATION_DEADLINE_SECONDS,
}


class ErrorCode(StrEnum):
    INVALID_INPUT = "INVALID_INPUT"
    INVALID_RESULT = "ENGINE_INVALID_RESULT"
    ENGINE_LIMIT = "ENGINE_LIMIT_EXCEEDED"
    RESULT_LIMIT = "RESULT_LIMIT_EXCEEDED"


class ServiceErrorCode(StrEnum):
    """HTTP错误信封只允许协议定义的固定分类。"""

    INVALID_INPUT = "INVALID_INPUT"
    ACCESS_DENIED = "ACCESS_DENIED"
    OPERATION_NOT_FOUND = "OPERATION_NOT_FOUND"
    REQUEST_ID_CONFLICT = "REQUEST_ID_CONFLICT"
    WRITE_CONFLICT = "WRITE_CONFLICT"
    SNAPSHOT_EXPIRED = "SNAPSHOT_EXPIRED"
    ENGINE_INVALID_RESULT = "ENGINE_INVALID_RESULT"
    ENGINE_LIMIT_EXCEEDED = "ENGINE_LIMIT_EXCEEDED"
    RESULT_LIMIT_EXCEEDED = "RESULT_LIMIT_EXCEEDED"
    HISTORY_UNAVAILABLE = "HISTORY_UNAVAILABLE"
    AUDIT_UNAVAILABLE = "AUDIT_UNAVAILABLE"
    OPERATION_DEADLINE = "OPERATION_DEADLINE"
    SERVICE_FAILURE = "SERVICE_FAILURE"
    OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN"


_SERVICE_MESSAGES = {
    ServiceErrorCode.INVALID_INPUT: "请求不符合记忆协议",
    ServiceErrorCode.ACCESS_DENIED: "记忆访问被拒绝",
    ServiceErrorCode.OPERATION_NOT_FOUND: "未找到记忆操作",
    ServiceErrorCode.REQUEST_ID_CONFLICT: "记忆操作编号冲突",
    ServiceErrorCode.WRITE_CONFLICT: "记忆写入发生冲突",
    ServiceErrorCode.SNAPSHOT_EXPIRED: "记忆快照已过期",
    ServiceErrorCode.ENGINE_INVALID_RESULT: "记忆引擎结果无效",
    ServiceErrorCode.ENGINE_LIMIT_EXCEEDED: "记忆引擎结果超过限制",
    ServiceErrorCode.RESULT_LIMIT_EXCEEDED: "记忆响应超过限制",
    ServiceErrorCode.HISTORY_UNAVAILABLE: "记忆历史不可用",
    ServiceErrorCode.AUDIT_UNAVAILABLE: "记忆审计不可用",
    ServiceErrorCode.OPERATION_DEADLINE: "记忆操作超过期限",
    ServiceErrorCode.SERVICE_FAILURE: "记忆服务暂时不可用",
    ServiceErrorCode.OUTCOME_UNKNOWN: "记忆操作结果尚未确认",
}


class ContractError(ValueError):
    def __init__(self, code=ErrorCode.INVALID_INPUT):
        if not isinstance(code, ErrorCode):
            raise TypeError("协议错误必须使用固定分类")
        self.code = code
        super().__init__("记忆协议校验失败：" + code.value)


def _require(condition, code=ErrorCode.INVALID_INPUT):
    if not condition:
        raise ContractError(code)


def _text(value, code=ErrorCode.INVALID_INPUT):
    _require(isinstance(value, str) and bool(value.strip()) and "\x00" not in value, code)
    try:
        length = len(value.encode("utf-8"))
    except UnicodeError:
        raise ContractError(code) from None
    _require(length <= MAX_TEXT_BYTES, code)


def _kind_scope(kind, scope, code=ErrorCode.INVALID_INPUT):
    _require(isinstance(kind, str) and kind in {"SAVE", "RECALL"}
             and isinstance(scope, str) and scope in {"CORE", "ARCHIVAL"}, code)
    _require(kind != "RECALL" or scope == "ARCHIVAL", code)


def _integer(value, minimum=0, maximum=9223372036854775807):
    _require(type(value) is int and minimum <= value <= maximum, ErrorCode.INVALID_RESULT)


def _utc(value):
    try:
        if not isinstance(value, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|\+00:00)", value):
            raise ValueError()
        datetime.fromisoformat(value)
    except ValueError:
        raise ContractError(ErrorCode.INVALID_RESULT) from None


@dataclass(frozen=True)
class Request:
    kind: str
    scope: str
    text: str = field(repr=False)

    def __post_init__(self):
        _kind_scope(self.kind, self.scope)
        _text(self.text)


def parse_request(raw):
    _require(type(raw) is bytes and len(raw) <= MAX_REQUEST_BYTES)

    def pairs(items):
        result = {}
        for key, value in items:
            _require(key not in result)
            result[key] = value
        return result

    def invalid_constant(value):
        raise ContractError()

    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=pairs, parse_constant=invalid_constant)
    except (ValueError, UnicodeError, RecursionError):
        raise ContractError() from None
    _require(type(value) is dict)
    if value.get("kind") == "SAVE":
        _require(set(value) == {"kind", "scope", "content"})
        return Request("SAVE", value["scope"], value["content"])
    _require(value.get("kind") == "RECALL" and set(value) == {"kind", "query"})
    return Request("RECALL", "ARCHIVAL", value["query"])


def request_hash(workspace_id, kind, scope, text):
    _require(valid_uuid(workspace_id))
    Request(kind, scope, text)
    # 正文不trim或规范化；分隔符只分隔固定字面量身份字段。
    return hashlib.sha256("\0".join((PROTOCOL, workspace_id, kind, scope, text)).encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class OperationIdentity:
    workspace_id: str
    operation_id: str
    kind: str
    scope: str
    request_hash: str

    def __post_init__(self):
        _require(valid_uuid(self.workspace_id) and valid_uuid(self.operation_id), ErrorCode.INVALID_RESULT)
        _kind_scope(self.kind, self.scope, ErrorCode.INVALID_RESULT)
        _require(isinstance(self.request_hash, str) and re.fullmatch(r"[0-9a-f]{64}", self.request_hash), ErrorCode.INVALID_RESULT)

    @classmethod
    def from_request(cls, workspace_id, operation_id, request):
        _require(type(request) is Request)
        return cls(workspace_id, operation_id, request.kind, request.scope,
                   request_hash(workspace_id, request.kind, request.scope, request.text))


def _identity(value, kind=None):
    _require(type(value) is OperationIdentity and (kind is None or value.kind == kind), ErrorCode.INVALID_RESULT)


class _Receipt:
    def to_dict(self):
        values = asdict(self)
        common = values.pop("identity")
        return dict(common, state=self.state, **values)


@dataclass(frozen=True)
class PendingReceipt(_Receipt):
    identity: OperationIdentity
    state: str
    deadline_at: str

    def __post_init__(self):
        _identity(self.identity)
        _require(isinstance(self.state, str) and self.state in {"RECEIVED", "RUNNING"}, ErrorCode.INVALID_RESULT)
        _utc(self.deadline_at)

    def to_dict(self):
        return dict(asdict(self.identity), state=self.state, deadline_at=self.deadline_at)


FAILURE_CODES = frozenset({
    "INVALID_INPUT", "ACCESS_DENIED", "REQUEST_ID_CONFLICT", "WRITE_CONFLICT", "ENGINE_INVALID_RESULT",
    "ENGINE_LIMIT_EXCEEDED", "RESULT_LIMIT_EXCEEDED", "HISTORY_UNAVAILABLE", "AUDIT_UNAVAILABLE",
    "OPERATION_DEADLINE", "SERVICE_FAILURE",
})


@dataclass(frozen=True)
class FailureReceipt(_Receipt):
    identity: OperationIdentity
    state: str
    completed_at: str
    error_code: str
    memory_effects_applied: bool

    def __post_init__(self):
        _identity(self.identity)
        _require(isinstance(self.state, str) and self.state in {"FAILED", "ABORTED"}, ErrorCode.INVALID_RESULT)
        _utc(self.completed_at)
        _require(isinstance(self.error_code, str) and self.error_code in FAILURE_CODES
                 and self.memory_effects_applied is False, ErrorCode.INVALID_RESULT)

    def to_dict(self):
        return dict(asdict(self.identity), state=self.state, completed_at=self.completed_at,
                    error_code=self.error_code, memory_effects_applied=False)


@dataclass(frozen=True)
class ActionCounts:
    add: int
    update: int
    delete: int

    def __post_init__(self):
        for value in (self.add, self.update, self.delete):
            _integer(value, maximum=MAX_ACTIONS)
        _require(self.total <= MAX_ACTIONS, ErrorCode.INVALID_RESULT)

    @property
    def total(self):
        return self.add + self.update + self.delete


@dataclass(frozen=True)
class SaveReceipt(_Receipt):
    identity: OperationIdentity
    committed_at: str
    revision: int
    outcome: str
    action_counts: ActionCounts
    affected_ids: tuple[str, ...]
    history_complete: bool
    state = "COMMITTED"

    def __post_init__(self):
        _identity(self.identity, "SAVE")
        _utc(self.committed_at)
        _integer(self.revision, minimum=1)
        _require(self.history_complete is True and type(self.action_counts) is ActionCounts, ErrorCode.INVALID_RESULT)
        _require(type(self.affected_ids) is tuple and all(valid_uuid(item) for item in self.affected_ids), ErrorCode.INVALID_RESULT)
        _require(len(set(self.affected_ids)) == len(self.affected_ids), ErrorCode.INVALID_RESULT)
        if self.outcome == "NOOP":
            _require(self.action_counts.total == 0 and not self.affected_ids and self.identity.scope == "ARCHIVAL", ErrorCode.INVALID_RESULT)
        else:
            _require(self.outcome == "CHANGED" and 1 <= len(self.affected_ids) <= self.action_counts.total, ErrorCode.INVALID_RESULT)
        if self.identity.scope == "CORE":
            _require(self.action_counts == ActionCounts(1, 0, 0), ErrorCode.INVALID_RESULT)

    def to_dict(self):
        values = super().to_dict()
        values["action_counts"] = {"ADD": self.action_counts.add, "UPDATE": self.action_counts.update, "DELETE": self.action_counts.delete}
        values["affected_ids"] = list(self.affected_ids)
        return values


@dataclass(frozen=True)
class RecallItem:
    memory_id: str
    content: str = field(repr=False)
    version_id: str
    score: float
    scope: str = "ARCHIVAL"

    def __post_init__(self):
        _require(valid_uuid(self.memory_id) and valid_uuid(self.version_id) and self.scope == "ARCHIVAL", ErrorCode.INVALID_RESULT)
        _text(self.content, ErrorCode.INVALID_RESULT)
        try:
            _require(type(self.score) in (int, float) and math.isfinite(self.score) and self.score >= 0, ErrorCode.INVALID_RESULT)
        except OverflowError:
            raise ContractError(ErrorCode.INVALID_RESULT) from None


@dataclass(frozen=True)
class RecallReceipt(_Receipt):
    identity: OperationIdentity
    committed_at: str
    revision: int
    items: tuple[RecallItem, ...]
    snapshot_revision: int
    truncated_by_bytes: bool
    state = "COMMITTED"

    def __post_init__(self):
        _identity(self.identity, "RECALL")
        _utc(self.committed_at)
        _integer(self.revision)
        _integer(self.snapshot_revision)
        _require(self.snapshot_revision == self.revision and type(self.truncated_by_bytes) is bool, ErrorCode.INVALID_RESULT)
        _require(type(self.items) is tuple and len(self.items) <= MAX_RECALL_ITEMS
                 and all(type(item) is RecallItem for item in self.items), ErrorCode.INVALID_RESULT)
        _require(bool(self.items) or not self.truncated_by_bytes, ErrorCode.INVALID_RESULT)
        _require(len({item.memory_id for item in self.items}) == len(self.items), ErrorCode.INVALID_RESULT)
        _require(tuple(sorted(self.items, key=lambda item: (-item.score, item.memory_id))) == self.items, ErrorCode.INVALID_RESULT)

    @property
    def returned_count(self):
        return len(self.items)

    def to_dict(self):
        values = super().to_dict()
        values["items"] = [asdict(item) for item in self.items]
        return dict(values, returned_count=self.returned_count)


def _json_value(value):
    if type(value) is dict:
        _require(all(type(key) is str for key in value), ErrorCode.INVALID_RESULT)
        for child in value.values():
            _json_value(child)
    elif type(value) in (list, tuple):
        for child in value:
            _json_value(child)
    else:
        _require(value is None or type(value) in (str, int, float, bool), ErrorCode.INVALID_RESULT)


def response_bytes(payload, request_id):
    _require(type(payload) is dict and "request_id" not in payload and valid_uuid(request_id), ErrorCode.INVALID_RESULT)
    try:
        _json_value(payload)
        result = json.dumps(dict(payload, request_id=request_id), sort_keys=True, ensure_ascii=False,
                            allow_nan=False, separators=(",", ":")).encode("utf-8")
    except (ValueError, TypeError, UnicodeError, RecursionError):
        raise ContractError(ErrorCode.INVALID_RESULT) from None
    _require(len(result) <= MAX_IO_BYTES, ErrorCode.RESULT_LIMIT)
    return result


def error_bytes(code, request_id):
    """生成固定错误信封，禁止把上游错误正文带回调用方。"""
    if not isinstance(code, ServiceErrorCode):
        raise TypeError("服务错误必须使用固定分类")
    return response_bytes({"error_code": code.value, "message": _SERVICE_MESSAGES[code]}, request_id)


def receipt_bytes(receipt, request_id, replayed=False):
    _require(type(receipt) in (PendingReceipt, FailureReceipt, SaveReceipt, RecallReceipt)
             and type(replayed) is bool, ErrorCode.INVALID_RESULT)
    return response_bytes(dict(receipt.to_dict(), replayed=replayed), request_id)


def check_receipt_budget(receipt):
    # 两种包装与真实响应共用序列化器；UUID信封长度固定，不生成假的提交证据。
    for replayed in (False, True):
        try:
            receipt_bytes(receipt, _BUDGET_REQUEST_ID, replayed)
        except ContractError as error:
            if error.code == ErrorCode.RESULT_LIMIT and type(receipt) is SaveReceipt:
                raise ContractError(ErrorCode.ENGINE_LIMIT) from None
            raise


def _strict_json_object(raw):
    _require(type(raw) is bytes and 0 < len(raw) <= MAX_IO_BYTES, ErrorCode.INVALID_RESULT)

    def pairs(items):
        result = {}
        for key, value in items:
            _require(type(key) is str and key not in result, ErrorCode.INVALID_RESULT)
            result[key] = value
        return result

    def invalid_constant(value):
        raise ContractError(ErrorCode.INVALID_RESULT)

    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=pairs, parse_constant=invalid_constant)
    except (ValueError, UnicodeError, RecursionError):
        raise ContractError(ErrorCode.INVALID_RESULT) from None
    _require(type(value) is dict, ErrorCode.INVALID_RESULT)
    return value


def _receipt_identity(value):
    return OperationIdentity(
        value["workspace_id"], value["operation_id"], value["kind"], value["scope"], value["request_hash"])


def receipt_from_bytes(raw):
    """严格解析数据库持久receipt；HTTP信封字段不能混入持久凭据。"""
    value = _strict_json_object(raw)
    common = {"workspace_id", "operation_id", "kind", "scope", "request_hash", "state"}
    _require(common <= set(value) and not {"request_id", "replayed"} & set(value), ErrorCode.INVALID_RESULT)
    state = value["state"]
    _require(isinstance(state, str), ErrorCode.INVALID_RESULT)
    identity = _receipt_identity(value)

    if state in {"RECEIVED", "RUNNING"}:
        _require(set(value) == common | {"deadline_at"}, ErrorCode.INVALID_RESULT)
        return PendingReceipt(identity, state, value["deadline_at"])

    if state in {"FAILED", "ABORTED"}:
        _require(
            set(value) == common | {"completed_at", "error_code", "memory_effects_applied"},
            ErrorCode.INVALID_RESULT,
        )
        return FailureReceipt(
            identity, state, value["completed_at"], value["error_code"], value["memory_effects_applied"])

    _require(state == "COMMITTED", ErrorCode.INVALID_RESULT)
    if identity.kind == "SAVE":
        expected = common | {
            "committed_at", "revision", "outcome", "action_counts", "affected_ids", "history_complete",
        }
        _require(set(value) == expected, ErrorCode.INVALID_RESULT)
        counts = value["action_counts"]
        _require(type(counts) is dict and set(counts) == {"ADD", "UPDATE", "DELETE"}, ErrorCode.INVALID_RESULT)
        affected_ids = value["affected_ids"]
        _require(type(affected_ids) is list, ErrorCode.INVALID_RESULT)
        return SaveReceipt(
            identity,
            value["committed_at"],
            value["revision"],
            value["outcome"],
            ActionCounts(counts["ADD"], counts["UPDATE"], counts["DELETE"]),
            tuple(affected_ids),
            value["history_complete"],
        )

    expected = common | {
        "committed_at", "revision", "items", "snapshot_revision", "truncated_by_bytes", "returned_count",
    }
    _require(set(value) == expected, ErrorCode.INVALID_RESULT)
    items = value["items"]
    _require(type(items) is list, ErrorCode.INVALID_RESULT)
    parsed = []
    item_fields = {"memory_id", "content", "scope", "version_id", "score"}
    for item in items:
        _require(type(item) is dict and set(item) == item_fields, ErrorCode.INVALID_RESULT)
        parsed.append(RecallItem(**item))
    _require(type(value["returned_count"]) is int and value["returned_count"] == len(parsed), ErrorCode.INVALID_RESULT)
    return RecallReceipt(
        identity,
        value["committed_at"],
        value["revision"],
        tuple(parsed),
        value["snapshot_revision"],
        value["truncated_by_bytes"],
    )
