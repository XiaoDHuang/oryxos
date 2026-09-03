"""snapshot只绑定时点；cursor另绑定scope、排序和最后完整键。"""

import json
from time import time
from uuid import uuid4

from oryx_mem0.engine.staging import MAX_IO_BYTES
from oryx_mem0.security import TokenCodec


class SnapshotService:
    def __init__(self, queries, codec, clock=None):
        if queries is None or not isinstance(codec, TokenCodec):
            raise ValueError("记忆快照服务配置无效")
        self.queries = queries
        self.codec = codec
        self.clock = clock or (lambda: int(time()))

    def create(self, workspace_id):
        snapshot = self.queries.create_snapshot(workspace_id)
        now = self._now()
        token = self.codec.issue_snapshot(workspace_id, snapshot.revision, now, str(uuid4()))
        claims = self.codec.read_snapshot(token, workspace_id, now)
        return {
            "snapshot_id": token, "revision": snapshot.revision, "expires_at": claims.expires_at,
            "core_count": snapshot.core_count, "archival_count": snapshot.archival_count,
        }

    def page(self, workspace_id, snapshot_id, scope, cursor=None, page_size=100):
        if scope not in {"CORE", "ARCHIVAL"} or type(page_size) is not int or not 1 <= page_size <= 100:
            raise ValueError("记忆分页参数无效")
        now = self._now()
        claims = self.codec.read_snapshot(snapshot_id, workspace_id, now)
        snapshot = self.queries.snapshot_at(workspace_id, claims.revision)
        after = None
        if cursor is not None:
            after = self.codec.read_cursor(cursor, snapshot_id, workspace_id, scope, now).after
        limit = page_size
        while limit > 0:
            page = self.queries.page(snapshot, scope, after=after, page_size=limit)
            if page.next_after is not None and after is not None and page.next_after <= after:
                raise ValueError("记忆分页未前进")
            next_cursor = None
            if not page.complete:
                if page.next_after is None:
                    raise ValueError("记忆分页缺少游标")
                next_cursor = self.codec.issue_cursor(
                    snapshot_id, claims, scope, page.next_after, claims.expires_at)
            payload = {
                "snapshot_id": snapshot_id, "revision": snapshot.revision, "scope": scope,
                "items": [_item(item) for item in page.items], "total_count": page.total_count,
                "complete": page.complete, "next_cursor": next_cursor,
            }
            encoded = dict(payload, request_id="00000000-0000-4000-8000-000000000001")
            if len(json.dumps(encoded, ensure_ascii=False, separators=(",", ":")).encode("utf-8")) <= MAX_IO_BYTES:
                return payload
            limit = len(page.items) - 1
        raise ValueError("单条记忆超过响应限制")

    def _now(self):
        value = self.clock()
        if type(value) is not int or value < 0:
            raise ValueError("记忆快照时钟无效")
        return value


def _item(value):
    return {
        "memory_id": value.memory_id, "version_id": value.version_id, "content": value.content,
        "scope": value.scope, "created_revision": value.created_revision,
        "updated_revision": value.updated_revision,
    }
