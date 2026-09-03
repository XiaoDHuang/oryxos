"""请求级暂存没有业务写仓储，失败标志不得被SDK吞错清除。"""

import json
import math
from datetime import datetime, timezone
from dataclasses import dataclass, field
from enum import StrEnum
from time import monotonic
from typing import Callable, Mapping, Protocol
from types import MappingProxyType
from uuid import UUID

MAX_TEXT_BYTES = 32 * 1024
MAX_IO_BYTES = 1024 * 1024
MAX_FACTS = 64
MAX_ACTIONS = 128


class Code(StrEnum):
    INVALID_CONFIG = "INVALID_CONFIG"
    INVALID_RESULT = "ENGINE_INVALID_RESULT"
    LIMIT_EXCEEDED = "ENGINE_LIMIT_EXCEEDED"
    ACCESS_DENIED = "ACCESS_DENIED"
    DEADLINE = "OPERATION_DEADLINE"
    SERVICE_FAILURE = "SERVICE_FAILURE"
    AUDIT_FAILURE = "AUDIT_UNAVAILABLE"
    HISTORY_FAILURE = "HISTORY_UNAVAILABLE"


class EngineError(RuntimeError):
    def __init__(self, code: Code):
        if not isinstance(code, Code):
            raise TypeError("错误分类必须固定")
        self.code = code
        super().__init__("记忆处理未完成：" + code.value)


def valid_uuid(value):
    try:
        return isinstance(value, str) and str(UUID(value)) == value and UUID(value).int != 0
    except ValueError:
        return False


@dataclass(frozen=True)
class RunContext:
    workspace_id: str
    operation_id: str
    scope: str
    revision: int
    dimensions: int
    deadline: float
    clock: Callable[[], float] = field(default=monotonic, repr=False, compare=False)
    _failure: Code | None = field(default=None, init=False, repr=False, compare=False)
    _active_call: str | None = field(default=None, init=False, repr=False, compare=False)

    def __post_init__(self):
        if (not valid_uuid(self.workspace_id) or not valid_uuid(self.operation_id)
                or not isinstance(self.scope, str) or self.scope not in {"CORE", "ARCHIVAL"}
                or type(self.revision) is not int or self.revision < 0
                or type(self.dimensions) is not int or self.dimensions <= 0
                or type(self.deadline) not in (int, float) or not math.isfinite(self.deadline)
                or self.deadline - self.clock() > 30):
            raise EngineError(Code.INVALID_CONFIG)

    @property
    def failure(self):
        return self._failure

    def latch(self, code):
        if not isinstance(code, Code):
            raise EngineError(Code.INVALID_CONFIG)
        if self._failure is None:
            object.__setattr__(self, "_failure", code)

    def fail(self, code):
        self.latch(code)
        raise EngineError(self._failure) from None

    @property
    def active_call(self):
        return self._active_call

    def enter_call(self, call_id):
        if self._active_call is not None or not valid_uuid(call_id):
            self.fail(Code.AUDIT_FAILURE)
        object.__setattr__(self, "_active_call", call_id)

    def leave_call(self, call_id):
        if self._active_call != call_id:
            self.fail(Code.AUDIT_FAILURE)
        object.__setattr__(self, "_active_call", None)

    def check(self):
        if self._failure is not None:
            raise EngineError(self._failure) from None
        remaining = self.deadline - self.clock()
        if remaining <= 0:
            self.fail(Code.DEADLINE)
        return remaining


def text_value(context, value):
    if not isinstance(value, str) or not value.strip() or "\x00" in value:
        context.fail(Code.INVALID_RESULT)
    try:
        length = len(value.encode("utf-8"))
    except UnicodeError:
        context.fail(Code.INVALID_RESULT)
    if length > MAX_TEXT_BYTES:
        context.fail(Code.LIMIT_EXCEEDED)
    return value


def vector_value(context, value):
    if not isinstance(value, (tuple, list)) or len(value) != context.dimensions:
        context.fail(Code.INVALID_RESULT)
    try:
        if any(type(number) not in (int, float) or not math.isfinite(number) for number in value):
            context.fail(Code.INVALID_RESULT)
    except OverflowError:
        context.fail(Code.INVALID_RESULT)
    if not any(value):
        context.fail(Code.INVALID_RESULT)
    return tuple(float(number) for number in value)


def json_bytes(context, value):
    try:
        result = json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")
    except (ValueError, TypeError, UnicodeError, RecursionError):
        context.fail(Code.INVALID_RESULT)
    if len(result) > MAX_IO_BYTES:
        context.fail(Code.LIMIT_EXCEEDED)
    return result


def strict_json(context, raw):
    if not isinstance(raw, bytes):
        context.fail(Code.INVALID_RESULT)
    if len(raw) > MAX_IO_BYTES:
        context.fail(Code.LIMIT_EXCEEDED)

    def object_pairs(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                context.fail(Code.INVALID_RESULT)
            result[key] = value
        return result

    try:
        return json.loads(raw.decode("utf-8"), object_pairs_hook=object_pairs,
                          parse_constant=lambda value: context.fail(Code.INVALID_RESULT))
    except (ValueError, UnicodeError, RecursionError):
        context.fail(Code.INVALID_RESULT)


@dataclass(frozen=True)
class Point:
    id: str
    payload: dict
    vector: tuple[float, ...]
    version_id: str | None = None


class ReadonlySnapshot(Protocol):
    workspace_id: str
    scope: str
    revision: int

    def get(self, memory_id: str) -> Point | None: ...

    def search(self, vector: tuple[float, ...], limit: int,
               overlay: Mapping[str, Point | None]) -> list[Point]: ...


@dataclass(frozen=True)
class Change:
    event: str
    memory_id: str
    old_content: str | None
    new_content: str | None
    vector: tuple[float, ...] | None
    baseline_version_id: str | None


@dataclass(frozen=True)
class Noop:
    memory_id: str
    content: str
    previous_updated_at: str
    ignored_updated_at: str


@dataclass(frozen=True)
class StagedResult:
    workspace_id: str
    operation_id: str
    revision: int
    changes: tuple[Change, ...]
    noops: tuple[Noop, ...]
    fact_count: int

    @property
    def outcome(self):
        return "CHANGED" if self.changes else "NOOP"


class StagedVectorStore:
    def __init__(self, context, snapshot):
        if (context.scope != "ARCHIVAL" or snapshot.workspace_id != context.workspace_id
                or snapshot.scope != context.scope or snapshot.revision != context.revision):
            context.fail(Code.ACCESS_DENIED)
        self.context = context
        self.snapshot = snapshot
        self._overlay = {}
        self._originals = {}
        self._seen = {}
        self._journal = []

    @property
    def seen_ids(self):
        return tuple(self._seen)

    @property
    def journal(self):
        return tuple(self._journal)

    def _point(self, point, require_version=False):
        state = self.context
        if not isinstance(point, Point) or not valid_uuid(point.id) or type(point.payload) is not dict:
            state.fail(Code.INVALID_RESULT)
        payload = point.payload
        if (payload.get("user_id") != state.workspace_id or payload.get("scope") != "ARCHIVAL"
                or payload.get("agent_id") != "ARCHIVAL"):
            state.fail(Code.ACCESS_DENIED)
        if set(payload) != {"user_id", "agent_id", "scope", "data", "hash", "created_at", "updated_at"}:
            state.fail(Code.INVALID_RESULT)
        text_value(state, payload["data"])
        if not isinstance(payload["hash"], str) or not 1 <= len(payload["hash"]) <= 128:
            state.fail(Code.INVALID_RESULT)
        timestamp(state, payload["created_at"])
        timestamp(state, payload["updated_at"])
        if point.version_id is not None and not valid_uuid(point.version_id):
            state.fail(Code.INVALID_RESULT)
        if require_version and point.version_id is None:
            state.fail(Code.INVALID_RESULT)
        return Point(point.id, payload.copy(), vector_value(state, point.vector), point.version_id)

    def _read_point(self, point):
        point = self._point(point, require_version=True)
        old = self._originals.get(point.id)
        if old is not None and old != point:
            self.context.fail(Code.INVALID_RESULT)
        self._originals[point.id] = point
        return self._point(point)

    def get(self, vector_id):
        self.context.check()
        if not valid_uuid(vector_id):
            self.context.fail(Code.INVALID_RESULT)
        if vector_id in self._overlay:
            point = self._overlay[vector_id]
            return None if point is None else self._point(point)
        try:
            point = self.snapshot.get(vector_id)
        except Exception:
            self.context.fail(Code.SERVICE_FAILURE)
        self.context.check()
        if point is None:
            return None
        point = self._read_point(point)
        if point.id != vector_id:
            self.context.fail(Code.INVALID_RESULT)
        return point

    def search(self, query, vectors, limit=5, filters=None):
        state = self.context
        state.check()
        text_value(state, query)
        vector = vector_value(state, vectors)
        if filters != {"user_id": state.workspace_id, "agent_id": "ARCHIVAL"}:
            state.fail(Code.ACCESS_DENIED)
        if type(limit) is not int or not 1 <= limit <= 5:
            state.fail(Code.INVALID_RESULT)
        overlay = MappingProxyType({key: None if value is None else self._point(value) for key, value in self._overlay.items()})
        try:
            found = self.snapshot.search(vector, limit, overlay)
        except Exception:
            state.fail(Code.SERVICE_FAILURE)
        state.check()
        if not isinstance(found, (list, tuple)) or len(found) > limit:
            state.fail(Code.INVALID_RESULT)
        result, ids = [], set()
        for raw in found:
            point = self._point(raw)
            if point.id in ids:
                state.fail(Code.INVALID_RESULT)
            ids.add(point.id)
            if point.id in self._overlay:
                if point != self._overlay[point.id]:
                    state.fail(Code.INVALID_RESULT)
            else:
                point = self._read_point(point)
            self._seen.setdefault(point.id, None)
            result.append(point)
        return result

    def _append(self, evidence):
        self.context.check()
        if len(self._journal) >= MAX_ACTIONS:
            self.context.fail(Code.LIMIT_EXCEEDED)
        self._journal.append(evidence)

    def insert(self, vectors, payloads=None, ids=None):
        state = self.context
        state.check()
        if not all(isinstance(value, (list, tuple)) for value in (vectors, payloads, ids)):
            state.fail(Code.INVALID_RESULT)
        if not len(vectors) == len(payloads) == len(ids) or not vectors:
            state.fail(Code.INVALID_RESULT)
        if len(self._journal) + len(ids) > MAX_ACTIONS:
            state.fail(Code.LIMIT_EXCEEDED)
        for memory_id, vector, payload in zip(ids, vectors, payloads, strict=True):
            if not valid_uuid(memory_id):
                state.fail(Code.INVALID_RESULT)
            if memory_id in self._overlay or self.get(memory_id) is not None:
                state.fail(Code.INVALID_RESULT)
            point = self._point(Point(memory_id, payload, vector))
            self._append(Change("ADD", memory_id, None, point.payload["data"], point.vector, None))
            self._overlay[memory_id] = point

    def update(self, vector_id, vector=None, payload=None):
        state = self.context
        state.check()
        old = self.get(vector_id)
        if old is None:
            state.fail(Code.INVALID_RESULT)
        point = self._point(Point(vector_id, payload, old.vector if vector is None else vector, old.version_id))
        if timestamp(state, old.payload["created_at"]) != timestamp(state, point.payload["created_at"]):
            state.fail(Code.INVALID_RESULT)
        if vector is None:
            preserved = set(old.payload) - {"created_at", "updated_at"}
            if any(old.payload[key] != point.payload[key] for key in preserved):
                state.fail(Code.INVALID_RESULT)
            self._append(Noop(vector_id, old.payload["data"], old.payload["updated_at"], point.payload["updated_at"]))
            # NONE的元数据时间只作证据，不写overlay或改变归档recency。
            return
        self._append(Change("UPDATE", vector_id, old.payload["data"], point.payload["data"], point.vector, old.version_id))
        self._overlay[vector_id] = point

    def delete(self, vector_id):
        old = self.get(vector_id)
        if old is None:
            self.context.fail(Code.INVALID_RESULT)
        self._append(Change("DELETE", vector_id, old.payload["data"], None, None, old.version_id))
        self._overlay[vector_id] = None

    def _forbidden(self, *args, **kwargs):
        self.context.fail(Code.ACCESS_DENIED)

    create_col = delete_col = reset = list = list_cols = col_info = _forbidden

    def close(self):
        # 快照连接由协调器管理，本容器没有持久资源。
        return None


class StagedHistory:
    def __init__(self, context):
        self.context = context
        self.events = []

    def add_history(self, memory_id, old_memory, new_memory, event, **metadata):
        state = self.context
        state.check()
        if not valid_uuid(memory_id) or not isinstance(event, str) or event not in {"ADD", "UPDATE", "DELETE"}:
            state.fail(Code.HISTORY_FAILURE)
        if len(self.events) >= MAX_ACTIONS:
            state.fail(Code.LIMIT_EXCEEDED)
        if set(metadata) - {"created_at", "updated_at", "actor_id", "role", "is_deleted"}:
            state.fail(Code.HISTORY_FAILURE)
        if metadata.get("actor_id") is not None or metadata.get("role") is not None:
            state.fail(Code.HISTORY_FAILURE)
        if metadata.get("is_deleted", 0) != (1 if event == "DELETE" else 0):
            state.fail(Code.HISTORY_FAILURE)
        timestamp(state, metadata.get("created_at"))
        timestamp(state, metadata.get("updated_at"))
        if event == "ADD" and old_memory is not None or event == "DELETE" and new_memory is not None:
            state.fail(Code.HISTORY_FAILURE)
        if event != "ADD":
            text_value(state, old_memory)
        if event != "DELETE":
            text_value(state, new_memory)
        self.events.append((event, memory_id, old_memory, new_memory))

    def reset(self):
        self.context.fail(Code.ACCESS_DENIED)

    def close(self):
        # SDK兼容关闭入口不连接或关闭任何业务历史库。
        return None


def timestamp(context, value):
    try:
        if not isinstance(value, str) or len(value) > 64:
            context.fail(Code.INVALID_RESULT)
        result = datetime.fromisoformat(value)
        if result.utcoffset() is None:
            context.fail(Code.INVALID_RESULT)
        return result.astimezone(timezone.utc)
    except ValueError:
        context.fail(Code.INVALID_RESULT)
