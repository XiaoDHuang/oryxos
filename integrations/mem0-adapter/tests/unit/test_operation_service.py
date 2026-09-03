"""服务层不得重跑已存在操作，也不得绕开暂存与事务端口。"""

from contextlib import contextmanager
from datetime import datetime, timedelta, timezone

import pytest

from oryx_mem0.contracts import (
    ActionCounts, OperationIdentity, PendingReceipt, RecallReceipt, Request, SaveReceipt,
)
from oryx_mem0.engine.staging import Code, EngineError, StagedResult
from oryx_mem0.services.operations import OperationService
from oryx_mem0.storage.operations import OperationLease

WORKSPACE = "11111111-1111-4111-8111-111111111111"
OPERATION = "22222222-2222-4222-8222-222222222222"


def identity(kind="SAVE", scope="ARCHIVAL", text="合成正文"):
    request = Request(kind, scope, text)
    return OperationIdentity.from_request(WORKSPACE, OPERATION, request), request


class Operations:
    def __init__(self, current=None):
        self.current = current
        self.claims = 0

    def register(self, value, raw):
        if self.current is not None:
            return self.current
        deadline = (datetime.now(timezone.utc) + timedelta(seconds=20)).isoformat()
        return PendingReceipt(value, "RECEIVED", deadline)

    def register_result(self, value, raw):
        return self.register(value, raw), self.current is not None

    def claim(self, value, owner):
        self.claims += 1
        deadline = (datetime.now(timezone.utc) + timedelta(seconds=20)).isoformat()
        return OperationLease(value, owner, 0, deadline)

    def get(self, workspace, operation):
        return self.current


class Memories:
    def __init__(self):
        self.commits = []
        self.failures = []
        self.snapshots = 0

    @contextmanager
    def read_snapshot(self):
        self.snapshots += 1
        yield object()

    def commit(self, lease, context, staged):
        self.commits.append((lease, context, staged))
        return staged

    def fail(self, lease, code):
        self.failures.append(code)


def service(operations=None, memories=None, archive=None, recall=None):
    return OperationService(
        operations or Operations(), memories or Memories(),
        archive or (lambda context, connection, text: StagedResult(
            context.workspace_id, context.operation_id, context.revision, (), (), 0)),
        recall or (lambda lease, context, text: RecallReceipt(
            lease.identity, datetime.now(timezone.utc).isoformat(), 0, (), 0, False)),
        2, lambda workspace: None,
    )


def test_core_save_builds_one_raw_add_without_opening_archive_snapshot():
    operations, memories = Operations(), Memories()
    value, request = identity(scope="CORE")
    result = service(operations, memories).save(value, request.text)
    assert type(result) is StagedResult and len(result.changes) == 1
    change = result.changes[0]
    assert (change.event, change.new_content, change.vector) == ("ADD", request.text, None)
    assert memories.snapshots == 0 and operations.claims == 1


def test_archival_save_uses_snapshot_stage_once_and_commits_noop():
    operations, memories, calls = Operations(), Memories(), []
    value, request = identity()

    def stage(context, connection, text):
        calls.append((connection, text))
        return StagedResult(context.workspace_id, context.operation_id, context.revision, (), (), 0)

    result = service(operations, memories, stage).save(value, request.text)
    assert result.outcome == "NOOP" and len(calls) == 1 and memories.snapshots == 1


def test_existing_terminal_is_returned_without_claim_stage_or_commit():
    value, request = identity()
    receipt = SaveReceipt(
        value, datetime.now(timezone.utc).isoformat(), 1, "CHANGED",
        ActionCounts(1, 0, 0), ("33333333-3333-4333-8333-333333333333",), True,
    )
    operations, memories = Operations(receipt), Memories()
    assert service(operations, memories).save(value, request.text) is receipt
    assert operations.claims == 0 and memories.commits == []


def test_stage_fatal_records_fixed_failure_and_never_commits():
    operations, memories = Operations(), Memories()
    value, request = identity()

    def reject(context, connection, text):
        context.fail(Code.INVALID_RESULT)

    with pytest.raises(EngineError):
        service(operations, memories, reject).save(value, request.text)
    assert memories.failures == ["ENGINE_INVALID_RESULT"] and memories.commits == []


def test_recall_delegates_once_and_validates_identity():
    value, request = identity("RECALL", "ARCHIVAL", "查询")
    calls = []

    def recall(lease, context, text):
        calls.append(text)
        return RecallReceipt(value, datetime.now(timezone.utc).isoformat(), 0, (), 0, False)

    receipt = service(recall=recall).recall(value, request.text)
    assert receipt.identity == value and calls == [request.text]


def test_replayed_terminal_preserves_status_without_second_commit():
    value, request = identity()
    receipt = SaveReceipt(value, datetime.now(timezone.utc).isoformat(), 1, "NOOP", ActionCounts(0, 0, 0), (), True)
    operations, memories = Operations(receipt), Memories()
    reply = service(operations, memories).execute(value, request.text)
    assert reply.receipt is receipt and reply.replayed is True
    assert operations.claims == 0 and memories.commits == []


def test_status_lookup_runs_recovery_before_reading_receipt():
    operations = Operations()
    value = service(operations)
    calls = []
    value.recover = calls.append
    assert value.get(WORKSPACE, OPERATION) is None
    assert calls == [WORKSPACE]
