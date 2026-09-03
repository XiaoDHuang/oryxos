"""按追加版本重构固定revision视图，并执行精确向量召回。"""

from dataclasses import dataclass, field
from datetime import timezone
import json

from oryx_mem0.contracts import ContractError, RecallItem, RecallReceipt, receipt_bytes
from oryx_mem0.engine.staging import MAX_IO_BYTES, valid_uuid


@dataclass(frozen=True)
class Snapshot:
    workspace_id: str
    revision: int
    core_count: int
    archival_count: int


@dataclass(frozen=True)
class MemoryItem:
    memory_id: str
    version_id: str
    content: str = field(repr=False)
    scope: str = "ARCHIVAL"
    created_revision: int = 0
    updated_revision: int = 0
    action_index: int = 0


@dataclass(frozen=True)
class Page:
    snapshot: Snapshot
    scope: str
    items: tuple[MemoryItem, ...]
    total_count: int
    complete: bool
    next_after: tuple | None
    serialized_size: int


class QueryStore:
    def __init__(self, connection_factory):
        self.connection_factory = connection_factory

    def create_snapshot(self, workspace_id):
        if not valid_uuid(workspace_id):
            raise ValueError("记忆快照身份无效")
        connection = self.connection_factory()
        try:
            row = connection.execute(
                "SELECT revision FROM oryx_memory.memory_namespaces WHERE workspace_id=%s", (workspace_id,),
            ).fetchone()
            revision = 0 if row is None else row[0]
        finally:
            connection.close()
        return self.snapshot_at(workspace_id, revision)

    def snapshot_at(self, workspace_id, revision):
        if not valid_uuid(workspace_id) or type(revision) is not int or revision < 0:
            raise ValueError("记忆快照身份无效")
        connection = self.connection_factory()
        try:
            current = connection.execute(
                "SELECT revision FROM oryx_memory.memory_namespaces WHERE workspace_id=%s", (workspace_id,),
            ).fetchone()
            current = 0 if current is None else current[0]
            if revision > current:
                raise ValueError("记忆快照revision无效")
            counts = connection.execute(_EFFECTIVE +
                " SELECT count(*) FILTER(WHERE scope='CORE'),count(*) FILTER(WHERE scope='ARCHIVAL') "
                "FROM effective WHERE event<>'DELETE'", (workspace_id, revision),
            ).fetchone()
            return Snapshot(workspace_id, revision, counts[0], min(100, counts[1]))
        finally:
            connection.close()

    def page(self, snapshot, scope, after=None, page_size=100):
        if type(snapshot) is not Snapshot or scope not in {"CORE", "ARCHIVAL"} or not 1 <= page_size <= 100:
            raise ValueError("记忆分页参数无效")
        connection = self.connection_factory()
        try:
            rows = connection.execute(_EFFECTIVE +
                " SELECT memory_id::text,version_id::text,new_content,scope,created_revision,revision,action_index "
                "FROM effective WHERE event<>'DELETE' AND scope=%s",
                (snapshot.workspace_id, snapshot.revision, scope),
            ).fetchall()
        finally:
            connection.close()
        if scope == "CORE":
            rows.sort(key=lambda row: (row[4], row[0]))
            total = snapshot.core_count
            key = lambda row: (row[4], row[0])
        else:
            rows.sort(key=lambda row: (row[5], row[6], row[0]), reverse=True)
            rows = list(reversed(rows[:100]))
            total = snapshot.archival_count
            key = lambda row: (row[5], row[6], row[0])
        if after is not None:
            rows = [row for row in rows if key(row) > tuple(after)]
        items = []
        size = 0
        for row in rows[:page_size]:
            item = MemoryItem(row[0], row[1], row[2], row[3], row[4], row[5], row[6])
            candidate = items + [item]
            measured = _page_size(snapshot, scope, candidate, total, False, key(row))
            if measured > MAX_IO_BYTES:
                break
            items.append(item)
            size = measured
        consumed = len(items)
        complete = consumed == len(rows)
        next_after = None if complete else (key(rows[consumed - 1]) if consumed else None)
        if not complete and not items:
            raise ValueError("单条记忆超过响应限制")
        size = _page_size(snapshot, scope, items, total, complete, next_after)
        return Page(snapshot, scope, tuple(items), total, complete, next_after, size)

    def recall(self, identity, query_vector):
        if identity.kind != "RECALL" or type(query_vector) not in (tuple, list):
            raise ValueError("记忆召回参数无效")
        vector = "[" + ",".join(str(float(value)) for value in query_vector) + "]"
        connection = self.connection_factory()
        try:
            # 只读 REPEATABLE READ：revision 与条目取自同一快照，并发 SAVE 不会造成两者错位。
            with connection.transaction():
                connection.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY")
                state = connection.execute(
                    "SELECT revision,clock_timestamp() FROM oryx_memory.memory_namespaces WHERE workspace_id=%s",
                    (identity.workspace_id,),
                ).fetchone()
                if state is None:
                    revision, committed_at = 0, connection.execute("SELECT clock_timestamp()").fetchone()[0]
                else:
                    revision, committed_at = state
                rows = connection.execute(
                    "SELECT memory_id::text,content,version_id::text,GREATEST(0,1-(embedding <=> %s::vector)) score "
                    "FROM oryx_memory.memory_current WHERE workspace_id=%s AND scope='ARCHIVAL' "
                    "ORDER BY embedding <=> %s::vector,memory_id LIMIT 20",
                    (vector, identity.workspace_id, vector),
                ).fetchall()
        finally:
            connection.close()
        candidates = tuple(RecallItem(row[0], row[1], row[2], float(row[3])) for row in rows)
        request_id = "00000000-0000-4000-8000-000000000001"
        for length in range(len(candidates), -1, -1):
            receipt = RecallReceipt(identity, committed_at.astimezone(timezone.utc).isoformat(), revision,
                                    candidates[:length], revision,
                                    length < len(candidates))
            try:
                receipt_bytes(receipt, request_id)
                return receipt
            except ContractError:
                continue
        raise ValueError("记忆召回结果超过限制")


_EFFECTIVE = (
    "WITH effective AS (SELECT DISTINCT ON (memory_id) memory_id,version_id,new_content,scope,"
    "created_revision,revision,action_index,event FROM oryx_memory.memory_versions "
    "WHERE workspace_id=%s AND revision<=%s ORDER BY memory_id,revision DESC,action_index DESC)"
)


def _page_size(snapshot, scope, items, total, complete, next_after):
    payload = {
        "snapshot_id": "x", "revision": snapshot.revision, "scope": scope,
        "items": [item.__dict__ for item in items], "total_count": total,
        "complete": complete, "next_cursor": next_after,
    }
    return len(json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
