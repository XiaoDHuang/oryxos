"""snapshot可跨scope复用，cursor只能用于其签名分区。"""

import pytest
import json
from uuid import UUID

from oryx_mem0.security import SecurityError, TokenCodec
from oryx_mem0.services.snapshots import SnapshotService
from oryx_mem0.storage.queries import MemoryItem, Page, Snapshot

WORKSPACE = "11111111-1111-4111-8111-111111111111"
NOW = 1788220800


class Queries:
    def __init__(self):
        self.snapshot = Snapshot(WORKSPACE, 7, 2, 1)
        self.calls = []

    def create_snapshot(self, workspace):
        return self.snapshot

    def snapshot_at(self, workspace, revision):
        assert workspace == WORKSPACE and revision == 7
        return self.snapshot

    def page(self, snapshot, scope, after=None, page_size=100):
        self.calls.append((scope, after, page_size))
        if after is None:
            item = MemoryItem(
                "33333333-3333-4333-8333-333333333333",
                "44444444-4444-4444-8444-444444444444", "正文", scope, 3, 6, 0)
            key = (3, item.memory_id) if scope == "CORE" else (6, 0, item.memory_id)
            return Page(snapshot, scope, (item,), 2, False, key, 200)
        return Page(snapshot, scope, (), 2, True, None, 100)


def service():
    return SnapshotService(Queries(), TokenCodec(bytes(range(32, 64))), lambda: NOW)


def test_created_snapshot_has_no_scope_and_reports_both_counts():
    created = service().create(WORKSPACE)
    assert created["revision"] == 7 and created["core_count"] == 2 and created["archival_count"] == 1
    claims = TokenCodec(bytes(range(32, 64))).read_snapshot(created["snapshot_id"], WORKSPACE, NOW)
    assert not hasattr(claims, "scope") and claims.expires_at == NOW + 300


def test_same_snapshot_pages_both_scopes_but_cursor_cannot_cross_scope():
    value = service()
    created = value.create(WORKSPACE)
    core = value.page(WORKSPACE, created["snapshot_id"], "CORE", page_size=1)
    archival = value.page(WORKSPACE, created["snapshot_id"], "ARCHIVAL", page_size=1)
    assert core["snapshot_id"] == archival["snapshot_id"]
    with pytest.raises(SecurityError):
        value.page(WORKSPACE, created["snapshot_id"], "ARCHIVAL", cursor=core["next_cursor"])


def test_next_cursor_advances_and_complete_page_has_no_cursor():
    value = service()
    created = value.create(WORKSPACE)
    first = value.page(WORKSPACE, created["snapshot_id"], "CORE", page_size=1)
    second = value.page(
        WORKSPACE, created["snapshot_id"], "CORE", cursor=first["next_cursor"], page_size=1)
    assert first["complete"] is False and first["next_cursor"]
    assert second["complete"] is True and second["next_cursor"] is None


def test_expired_snapshot_and_invalid_page_size_are_rejected():
    value = service()
    created = value.create(WORKSPACE)
    value.clock = lambda: NOW + 300
    with pytest.raises(SecurityError):
        value.page(WORKSPACE, created["snapshot_id"], "CORE")
    with pytest.raises(ValueError):
        service().page(WORKSPACE, created["snapshot_id"], "CORE", page_size=101)


def test_page_budget_includes_the_request_id_envelope(monkeypatch):
    import oryx_mem0.services.snapshots as snapshots

    class SizedQueries(Queries):
        def page(self, snapshot, scope, after=None, page_size=100):
            items = tuple(MemoryItem(str(UUID(int=index + 1)), str(UUID(int=index + 10)),
                "正文" * 100, scope, index + 1, index + 1) for index in range(min(page_size, 2)))
            return Page(snapshot, scope, items, 3, False, (len(items), items[-1].memory_id), 0)

    value = SnapshotService(SizedQueries(), TokenCodec(bytes(range(32, 64))), lambda: NOW)
    snapshot = value.create(WORKSPACE)["snapshot_id"]
    original = value.page(WORKSPACE, snapshot, "CORE", page_size=2)
    budget = len(json.dumps(original, ensure_ascii=False, separators=(",", ":")).encode()) + 10
    monkeypatch.setattr(snapshots, "MAX_IO_BYTES", budget)
    page = value.page(WORKSPACE, snapshot, "CORE", page_size=2)
    assert len(page["items"]) == 1 and page["total_count"] == 3 and page["complete"] is False
    page["request_id"] = "00000000-0000-4000-8000-000000000001"
    assert len(json.dumps(page, ensure_ascii=False, separators=(",", ":")).encode()) <= budget
