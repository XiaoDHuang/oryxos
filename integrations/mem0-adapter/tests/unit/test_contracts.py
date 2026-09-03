"""共享凭据在写事务前即可验证身份、状态和真实字节预算。"""

from dataclasses import FrozenInstanceError
import hashlib
import json
from uuid import uuid4

import pytest

from oryx_mem0.contracts import (
    ActionCounts, ContractError, FailureReceipt, OperationIdentity, PendingReceipt,
    RecallItem, RecallReceipt, Request, SaveReceipt, check_receipt_budget,
    parse_request, receipt_bytes, request_hash, response_bytes,
)

WORKSPACE = "11111111-1111-4111-8111-111111111111"
OPERATION = "22222222-2222-4222-8222-222222222222"
MEMORY = "33333333-3333-4333-8333-333333333333"
VERSION = "44444444-4444-4444-8444-444444444444"
REQUEST_ID = "55555555-5555-4555-8555-555555555555"
WHEN = "2026-09-01T00:00:00.123456Z"
LIMIT = 1024 * 1024


def identity(kind="SAVE", scope="ARCHIVAL"):
    return OperationIdentity(WORKSPACE, OPERATION, kind, scope, "a" * 64)


def saved(**changes):
    args = dict(identity=identity(), committed_at=WHEN, revision=4, outcome="CHANGED",
                action_counts=ActionCounts(1, 0, 0), affected_ids=(MEMORY,), history_complete=True)
    args.update(changes)
    return SaveReceipt(**args)


def recalled(**changes):
    args = dict(identity=identity("RECALL"), committed_at=WHEN, revision=4,
                items=(RecallItem(MEMORY, "偏好Java21", VERSION, 0.75),), snapshot_revision=4,
                truncated_by_bytes=False)
    args.update(changes)
    return RecallReceipt(**args)


def test_request_preserves_exact_unicode_and_hash_separators():
    text = '  原文e\u0301\n"\\😀  '
    raw = json.dumps({"kind": "SAVE", "scope": "CORE", "content": text}).encode()
    request = parse_request(raw)
    assert request == Request("SAVE", "CORE", text)
    expected = hashlib.sha256(("oryx-memory-v1\0" + WORKSPACE + "\0SAVE\0CORE\0" + text).encode("utf-8")).hexdigest()
    assert request_hash(WORKSPACE, "SAVE", "CORE", text) == expected
    assert request_hash(WORKSPACE, "SAVE", "CORE", text.replace("e\u0301", "é")) != expected
    assert parse_request(b'{"kind":"RECALL","query":"Java"}') == Request("RECALL", "ARCHIVAL", "Java")
    with pytest.raises(FrozenInstanceError):
        request.text = "changed"


@pytest.mark.parametrize("raw", [
    b'{}', b'[]', b'null', b'{"kind":"SAVE","content":"x"}',
    b'{"kind":"SAVE","scope":"CORE","content":"x","infer":false}',
    b'{"kind":"RECALL","scope":"ARCHIVAL","query":"x"}',
    b'{"kind":"RECALL","query":"x","query":"y"}',
    b'{"kind":"RECALL","query":"\\ud800"}', b'{"kind":"RECALL","query":NaN}',
    b'{"kind":"RECALL","query":"before\\u0000after"}',
    b'{"kind":"SAVE","scope":"archival","content":"x"}',
    b'{"kind":"SAVE","scope":[],"content":"x"}', b'\xff',
    b'{"kind":"RECALL","query":" "}', b'{"kind":"RECALL","query":1}',
    b'{"kind":"RECALL","query":"' + b'x' * 32769 + b'"}',
    b' ' * (256 * 1024) + b'{}',
], ids=["empty", "array", "null", "missing-scope", "unknown", "recall-scope", "duplicate", "surrogate",
        "nan", "nul", "scope-case", "scope-type", "utf8", "blank", "number", "text-limit", "body-limit"])
def test_invalid_request_is_rejected_before_any_storage(raw):
    with pytest.raises(ContractError):
        parse_request(raw)


def test_input_limits_count_utf8_not_characters_or_json_escapes():
    value = '界' * 10922 + "ab"
    assert len(value.encode()) == 32768
    assert parse_request(json.dumps({"kind": "RECALL", "query": value}).encode()).text == value
    with pytest.raises(ContractError):
        Request("RECALL", "ARCHIVAL", value + "c")
    body = b'{"kind":"RECALL","query":"x"}'
    assert parse_request(body + b' ' * (256 * 1024 - len(body))).text == "x"


@pytest.mark.parametrize("args", [
    (WORKSPACE.upper().replace("1", "A"), "SAVE", "CORE", "x"),
    ("00000000-0000-0000-0000-000000000000", "SAVE", "CORE", "x"),
    (WORKSPACE, "UNKNOWN", "CORE", "x"), (WORKSPACE, "RECALL", "CORE", "x"),
    (WORKSPACE, "SAVE", "ARCHIVAL", "\ud800"), (WORKSPACE, "SAVE", "ARCHIVAL", " "),
    (WORKSPACE, "SAVE", "ARCHIVAL", "before\x00after"),
])
def test_hash_rejects_invalid_identity_or_text(args):
    with pytest.raises(ContractError):
        request_hash(*args)


def test_committed_save_receipt_contains_required_proof_and_replays_identically():
    value = saved()
    check_receipt_budget(value)
    original = json.loads(receipt_bytes(value, REQUEST_ID))
    replay = json.loads(receipt_bytes(value, str(uuid4()), replayed=True))
    assert original["state"] == "COMMITTED"
    assert original["action_counts"] == {"ADD": 1, "UPDATE": 0, "DELETE": 0}
    assert original["history_complete"] is True
    assert original["operation_id"] == OPERATION
    assert original["workspace_id"] == WORKSPACE
    assert original["request_hash"] == "a" * 64
    assert original.pop("request_id") == REQUEST_ID
    replay.pop("request_id")
    assert original.pop("replayed") is False
    assert replay.pop("replayed") is True
    assert original == replay == value.to_dict()


@pytest.mark.parametrize("changes", [
    {"identity": identity("RECALL")}, {"history_complete": False}, {"history_complete": 1},
    {"revision": True}, {"revision": -1}, {"revision": 2**63}, {"committed_at": "2026-09-01"},
    {"committed_at": WHEN.replace("Z", "+08:00")}, {"outcome": "NOOP"},
    {"affected_ids": ()}, {"affected_ids": (MEMORY, MEMORY)},
    {"action_counts": ActionCounts(0, 0, 0)},
])
def test_invalid_save_receipt_cannot_claim_success(changes):
    with pytest.raises(ContractError):
        saved(**changes)


def test_noop_and_core_rules_are_distinct():
    value = saved(outcome="NOOP", action_counts=ActionCounts(0, 0, 0), affected_ids=())
    assert value.to_dict()["outcome"] == "NOOP"
    with pytest.raises(ContractError):
        saved(identity=identity(scope="CORE"), action_counts=ActionCounts(0, 1, 0))
    with pytest.raises(ContractError):
        saved(identity=identity(scope="CORE"), outcome="NOOP", action_counts=ActionCounts(0, 0, 0), affected_ids=())


@pytest.mark.parametrize("state", ["RECEIVED", "RUNNING"])
def test_pending_receipts_carry_immutable_deadline_without_commit_fields(state):
    value = PendingReceipt(identity(), state, WHEN)
    payload = json.loads(receipt_bytes(value, REQUEST_ID))
    assert payload["deadline_at"] == WHEN
    assert "committed_at" not in payload and "history_complete" not in payload
    with pytest.raises(FrozenInstanceError):
        value.deadline_at = "2026-10-01T00:00:00Z"
    with pytest.raises(ContractError):
        PendingReceipt(identity(), "COMMITTED", WHEN)


@pytest.mark.parametrize("state", ["FAILED", "ABORTED"])
def test_failure_receipt_only_proves_projection_not_committed(state):
    value = FailureReceipt(identity(), state, WHEN, "OPERATION_DEADLINE", False)
    payload = json.loads(receipt_bytes(value, REQUEST_ID))
    assert payload["memory_effects_applied"] is False
    assert payload["request_hash"] == "a" * 64
    with pytest.raises(ContractError):
        FailureReceipt(identity(), state, WHEN, "OPERATION_DEADLINE", True)
    with pytest.raises(ContractError):
        FailureReceipt(identity(), state, WHEN, "secret remote exception", False)


def test_recall_receipt_requires_current_archival_unique_ranked_complete_items():
    payload = json.loads(receipt_bytes(recalled(), REQUEST_ID))
    assert payload["returned_count"] == 1
    assert payload["items"][0]["scope"] == "ARCHIVAL"
    assert payload["snapshot_revision"] == payload["revision"] == 4
    assert json.loads(receipt_bytes(recalled(items=()), REQUEST_ID))["returned_count"] == 0
    for changes in ({"snapshot_revision": 3}, {"identity": identity()},
                    {"items": (), "truncated_by_bytes": True}, {"truncated_by_bytes": 1}):
        with pytest.raises(ContractError):
            recalled(**changes)
    first = RecallItem(MEMORY, "x", VERSION, 0.7)
    second = RecallItem("66666666-6666-4666-8666-666666666666", "x", VERSION, 0.9)
    for items in ((first, first), (first, second), tuple(first for _ in range(21))):
        with pytest.raises(ContractError):
            recalled(items=items)


@pytest.mark.parametrize("score", [float("nan"), float("inf"), -0.1, True, "0.5", 10**400])
def test_recall_score_must_be_nonnegative_finite_number(score):
    with pytest.raises(ContractError):
        RecallItem(MEMORY, "x", VERSION, score)


def test_whole_envelope_budget_includes_unicode_escaping_and_reserved_fields():
    payload = {"content": '"\\界😀'}
    actual = response_bytes(payload, REQUEST_ID)
    assert json.loads(actual) == dict(payload, request_id=REQUEST_ID)
    assert b'\\"' in actual and b'\\\\' in actual
    base = len(response_bytes({"content": ""}, REQUEST_ID))
    assert len(response_bytes({"content": "x" * (LIMIT - base)}, REQUEST_ID)) == LIMIT
    with pytest.raises(ContractError):
        response_bytes({"content": "x" * (LIMIT - base + 1)}, REQUEST_ID)
    with pytest.raises(ContractError):
        response_bytes({"request_id": REQUEST_ID}, REQUEST_ID)
    for bad in ("incoming-header", "00000000-0000-0000-0000-000000000000"):
        with pytest.raises(ContractError):
            response_bytes({}, bad)


def test_20_legal_items_may_exceed_serialized_receipt_budget_without_truncation():
    items = tuple(RecallItem(str(uuid4()), '\x01' * 32768, str(uuid4()), 0.5) for _ in range(20))
    items = tuple(sorted(items, key=lambda item: item.memory_id))
    value = recalled(items=items)
    assert sum(len(item.content.encode()) for item in items) < LIMIT
    with pytest.raises(ContractError):
        check_receipt_budget(value)
    assert len(value.items) == 20 and all(len(item.content) == 32768 for item in value.items)


def test_receipt_budget_checks_false_and_true_packaging(monkeypatch):
    import oryx_mem0.contracts as contracts
    calls = []
    original = contracts.receipt_bytes
    def record(receipt, request_id, replayed=False):
        calls.append(replayed)
        return original(receipt, request_id, replayed)
    monkeypatch.setattr(contracts, "receipt_bytes", record)
    contracts.check_receipt_budget(saved())
    assert calls == [False, True]


@pytest.mark.parametrize("changes", [{"workspace_id": "x"}, {"operation_id": None}, {"kind": "x"},
                                     {"scope": "x"}, {"request_hash": "A" * 64}])
def test_persisted_identity_is_strict(changes):
    args = dict(workspace_id=WORKSPACE, operation_id=OPERATION, kind="SAVE", scope="CORE", request_hash="a" * 64)
    args.update(changes)
    with pytest.raises(ContractError):
        OperationIdentity(**args)


@pytest.mark.parametrize("counts", [(True, 0, 0), (-1, 0, 0), (129, 0, 0), (64, 64, 1), (1.0, 0, 0)])
def test_action_counts_are_bounded_actual_actions(counts):
    with pytest.raises(ContractError):
        ActionCounts(*counts)


def test_identity_factory_binds_exact_request_hash():
    request = Request("SAVE", "CORE", "  中文\n")
    value = OperationIdentity.from_request(WORKSPACE, OPERATION, request)
    assert value.request_hash == request_hash(WORKSPACE, "SAVE", "CORE", request.text)
    assert value.scope == "CORE"


@pytest.mark.parametrize("payload", [{1: "x"}, {"x": float("nan")}, {"x": "\udfff"}, {"x": object()}])
def test_serializer_rejects_implicit_coercion_invalid_unicode_and_non_json(payload):
    with pytest.raises(ContractError):
        response_bytes(payload, REQUEST_ID)


def test_equal_score_tie_uses_memory_id_and_input_cannot_mutate_receipt():
    first = RecallItem(MEMORY, "x", VERSION, 0)
    second = RecallItem("66666666-6666-4666-8666-666666666666", "y", str(uuid4()), 0)
    assert recalled(items=(first, second)).returned_count == 2
    with pytest.raises(ContractError):
        recalled(items=(second, first))
    with pytest.raises(ContractError):
        recalled(items=[first])
    payload = recalled().to_dict()
    payload["items"][0]["content"] = "modified"
    assert recalled().items[0].content == "偏好Java21"


def test_a_staged_object_cannot_serialize_as_a_committed_receipt():
    with pytest.raises(ContractError):
        receipt_bytes(object(), REQUEST_ID)
    with pytest.raises(ContractError):
        receipt_bytes(saved(), REQUEST_ID, replayed=1)
