"""五端点必须认证、严格判别并拒绝矛盾持久凭据。"""

import base64
import hashlib
from datetime import datetime, timezone
from types import SimpleNamespace
from uuid import UUID

import pytest
from fastapi.testclient import TestClient

from oryx_mem0.app import ApiRuntime, create_app
from oryx_mem0.contracts import (
    CAPABILITY_LIMITS, ActionCounts, FailureReceipt, OperationIdentity, PendingReceipt, SaveReceipt,
)
from oryx_mem0.security import Authenticator
from oryx_mem0.settings import ClientBinding
from oryx_mem0.storage.migrations import apply_migrations
from oryx_mem0.services.operations import OutcomeUnknown

pytestmark = pytest.mark.integration

WORKSPACE = "11111111-1111-4111-8111-111111111111"
OTHER_WORKSPACE = "22222222-2222-4222-8222-222222222222"
TOKEN = base64.urlsafe_b64encode(bytes(range(32))).decode().rstrip("=")
AUTH = {"Authorization": "Bearer " + TOKEN}


def receipt(state):
    identity = OperationIdentity(WORKSPACE, str(UUID(int=8)), "SAVE", "ARCHIVAL", "a" * 64)
    now = datetime.now(timezone.utc).isoformat()
    if state in {"RECEIVED", "RUNNING"}:
        return PendingReceipt(identity, state, now)
    if state == "COMMITTED":
        return SaveReceipt(identity, now, 1, "CHANGED", ActionCounts(1, 0, 0),
                           ("33333333-3333-4333-8333-333333333333",), True)
    return FailureReceipt(identity, state, now, "OPERATION_DEADLINE" if state == "ABORTED"
                          else "ENGINE_INVALID_RESULT", False)


class Operations:
    def __init__(self):
        self.put_result = None
        self.get_result = None
        self.put_calls = []

    def put(self, workspace, operation, raw):
        self.put_calls.append((workspace, operation, raw))
        if isinstance(self.put_result, Exception):
            raise self.put_result
        return self.put_result

    def get(self, workspace, operation):
        return self.get_result


class Snapshots:
    def create(self, workspace):
        return {"snapshot_id": "snapshot", "revision": 0, "core_count": 0, "archival_count": 0}

    def page(self, workspace, snapshot, scope, cursor, page_size):
        if cursor is not None:
            raise ValueError("合成cursor无效")
        return {"snapshot_id": snapshot, "revision": 0, "scope": scope, "items": [],
                "total_count": 0, "complete": True, "next_cursor": None}


@pytest.fixture
def api(pg_harness, inprocess_asgi):
    apply_migrations(pg_harness.open(autocommit=True), 2)
    operations = Operations()
    runtime = ApiRuntime(
        Authenticator((ClientBinding(hashlib.sha256(TOKEN.encode()).hexdigest(), WORKSPACE),)),
        operations, Snapshots(),
        {"protocol": "oryx-memory-v1", "schema_version": 1, "sdk_version": "1.0.11+oryx.1",
         "staged_engine": True, "atomic_history": True, "revision_pagination": True,
         "build_version": "0" * 64, "limits": dict(CAPABILITY_LIMITS)},
        lambda: None,
    )
    return SimpleNamespace(client=TestClient(create_app(runtime)), operations=operations)


@pytest.mark.parametrize(("method", "path"), [
    ("get", "/oryx-memory/v1/capabilities"),
    ("put", f"/oryx-memory/v1/workspaces/{WORKSPACE}/operations/{UUID(int=1)}"),
    ("get", f"/oryx-memory/v1/workspaces/{WORKSPACE}/operations/{UUID(int=1)}"),
    ("post", f"/oryx-memory/v1/workspaces/{WORKSPACE}/snapshots"),
    ("get", f"/oryx-memory/v1/workspaces/{WORKSPACE}/snapshots/snapshot/entries?scope=CORE"),
])
def test_every_protocol_endpoint_requires_bearer(api, method, path):
    assert getattr(api.client, method)(path).status_code == 401


def test_capabilities_is_authenticated_fixed_and_contains_no_secret(api):
    response = api.client.get("/oryx-memory/v1/capabilities", headers=AUTH)
    assert response.status_code == 200
    assert response.json()["protocol"] == "oryx-memory-v1"
    assert response.json()["atomic_history"] is True
    assert response.json()["build_version"] == "0" * 64
    assert response.json()["limits"] == CAPABILITY_LIMITS
    assert TOKEN not in response.text and "api_key" not in response.text.lower()


def test_capabilities_rejects_missing_limits_or_bad_build_version(pg_harness, inprocess_asgi):
    apply_migrations(pg_harness.open(autocommit=True), 2)
    base = {"protocol": "oryx-memory-v1", "schema_version": 1, "sdk_version": "1.0.11+oryx.1",
            "staged_engine": True, "atomic_history": True, "revision_pagination": True,
            "build_version": "0" * 64, "limits": dict(CAPABILITY_LIMITS)}
    for broken in (
            {key: value for key, value in base.items() if key != "limits"},
            {key: value for key, value in base.items() if key != "build_version"},
            {**base, "build_version": "dev"},
            {**base, "limits": {**CAPABILITY_LIMITS, "recall_top": 21}},
            {**base, "extra_field": True}):
        runtime = ApiRuntime(
            Authenticator((ClientBinding(hashlib.sha256(TOKEN.encode()).hexdigest(), WORKSPACE),)),
            Operations(), Snapshots(), broken, lambda: None)
        with pytest.raises(ValueError):
            create_app(runtime)


@pytest.mark.parametrize("body", [
    {}, {"kind": "SAVE", "scope": "ARCHIVAL"},
    {"kind": "SAVE", "scope": "ARCHIVAL", "content": "x", "infer": False},
    {"kind": "RECALL", "query": "before\x00after"},
])
def test_put_rejects_unknown_missing_and_unrepresentable_fields(api, body):
    response = api.client.put(
        f"/oryx-memory/v1/workspaces/{WORKSPACE}/operations/{UUID(int=2)}", headers=AUTH, json=body)
    assert response.status_code == 400
    assert api.operations.put_calls == []


def test_put_rejects_body_over_256k_before_service(api):
    response = api.client.put(
        f"/oryx-memory/v1/workspaces/{WORKSPACE}/operations/{UUID(int=3)}", headers=AUTH,
        content=b'{"kind":"RECALL","query":"' + b"x" * (256 * 1024) + b'"}',
    )
    assert response.status_code == 413 and api.operations.put_calls == []


def test_workspace_binding_is_enforced_without_disclosing_existence(api):
    response = api.client.get(
        f"/oryx-memory/v1/workspaces/{OTHER_WORKSPACE}/operations/{UUID(int=4)}", headers=AUTH)
    assert response.status_code == 403


def test_operation_not_found_is_fixed_404(api):
    response = api.client.get(
        f"/oryx-memory/v1/workspaces/{WORKSPACE}/operations/{UUID(int=5)}", headers=AUTH)
    assert response.status_code == 404
    assert response.json()["error_code"] == "OPERATION_NOT_FOUND"


@pytest.mark.parametrize(("state", "expected"), [
    ("RECEIVED", 202), ("RUNNING", 202), ("COMMITTED", 200), ("FAILED", 422), ("ABORTED", 504),
])
def test_operation_endpoint_preserves_every_persistent_receipt_state(api, state, expected):
    api.operations.get_result = receipt(state)
    response = api.client.get(
        f"/oryx-memory/v1/workspaces/{WORKSPACE}/operations/{UUID(int=8)}", headers=AUTH)
    assert response.status_code == expected and response.json()["state"] == state


def test_snapshot_creation_rejects_scope_but_one_id_serves_both_scopes(api):
    path = f"/oryx-memory/v1/workspaces/{WORKSPACE}/snapshots"
    assert api.client.post(path, headers=AUTH, json={"scope": "CORE"}).status_code == 400
    created = api.client.post(path, headers=AUTH, json={})
    assert created.status_code == 200
    snapshot = created.json()["snapshot_id"]
    for scope in ("CORE", "ARCHIVAL"):
        page = api.client.get(
            f"{path}/{snapshot}/entries?scope={scope}&page_size=100", headers=AUTH)
        assert page.status_code == 200 and page.json()["snapshot_id"] == snapshot


def test_cursor_type_scope_and_page_size_are_strict(api):
    path = f"/oryx-memory/v1/workspaces/{WORKSPACE}/snapshots/snapshot/entries"
    for query in ("scope=UNKNOWN", "scope=CORE&page_size=101", "scope=CORE&cursor=snapshot-token",
                  "scope=ARCHIVAL&cursor=core-cursor"):
        assert api.client.get(f"{path}?{query}", headers=AUTH).status_code == 400


@pytest.mark.parametrize("result", [
    None,
    {"workspace_id": WORKSPACE, "operation_id": str(UUID(int=6)), "kind": "SAVE",
     "scope": "ARCHIVAL", "state": "COMMITTED", "request_hash": "b" * 64, "history_complete": True},
    {"workspace_id": WORKSPACE, "operation_id": str(UUID(int=6)), "kind": "SAVE",
     "scope": "ARCHIVAL", "state": "COMMITTED", "request_hash": "a" * 64, "history_complete": False},
])
def test_contradictory_or_incomplete_success_receipt_never_returns_200(api, result):
    api.operations.put_result = result or {
        "workspace_id": WORKSPACE, "operation_id": str(UUID(int=6)), "kind": "SAVE",
        "scope": "ARCHIVAL", "state": "BROKEN",
    }
    response = api.client.put(
        f"/oryx-memory/v1/workspaces/{WORKSPACE}/operations/{UUID(int=6)}", headers=AUTH,
        json={"kind": "SAVE", "scope": "ARCHIVAL", "content": "合成"},
    )
    assert response.status_code >= 400


def test_outcome_unknown_is_preferred_after_dispatched_save_and_put_is_not_replayed(api):
    api.operations.put_result = OutcomeUnknown()
    response = api.client.put(
        f"/oryx-memory/v1/workspaces/{WORKSPACE}/operations/{UUID(int=7)}", headers=AUTH,
        json={"kind": "SAVE", "scope": "ARCHIVAL", "content": "合成"},
    )
    assert response.status_code == 503
    assert response.json()["error_code"] == "OUTCOME_UNKNOWN"
    assert len(api.operations.put_calls) == 1


def test_history_reset_configure_and_openapi_are_not_exposed(api):
    for path in ("/history", "/reset", "/configure", "/docs", "/openapi.json"):
        assert api.client.get(path, headers=AUTH).status_code == 404
