"""分类型令牌、Bearer绑定和固定错误不接受宽松解析。"""

import base64
import hashlib
import hmac
import json
from pathlib import Path

import pytest

from oryx_mem0.contracts import (
    ContractError, ServiceErrorCode, error_bytes, receipt_from_bytes, request_hash,
)
from oryx_mem0.security import Authenticator, SecurityCode, SecurityError, TokenCodec
from oryx_mem0.settings import ClientBinding

WORKSPACE = "11111111-1111-4111-8111-111111111111"
OTHER_WORKSPACE = "22222222-2222-4222-8222-222222222222"
TOKEN = base64.urlsafe_b64encode(bytes(range(32))).decode().rstrip("=")
OTHER_TOKEN = base64.urlsafe_b64encode(bytes(range(32, 64))).decode().rstrip("=")
SECRET = bytes(range(32, 64))


def fixture():
    return json.loads((Path(__file__).parents[1] / "fixtures/protocol-v1.json").read_text(encoding="utf-8"))


def test_bearer_authentication_hashes_exact_ascii_and_supports_rotation():
    bindings = (
        ClientBinding(hashlib.sha256(TOKEN.encode("ascii")).hexdigest(), WORKSPACE),
        ClientBinding(hashlib.sha256(OTHER_TOKEN.encode("ascii")).hexdigest(), WORKSPACE),
    )
    authenticator = Authenticator(bindings)
    assert authenticator.authenticate("Bearer " + TOKEN) == WORKSPACE
    assert authenticator.authenticate("Bearer " + OTHER_TOKEN) == WORKSPACE
    assert TOKEN not in repr(authenticator)


@pytest.mark.parametrize("authorization", [
    None, "", TOKEN, "bearer " + TOKEN, "Bearer  " + TOKEN, "Bearer " + TOKEN + " ",
    "Bearer " + TOKEN + "=", "Bearer " + ("A" * 43), "Basic " + TOKEN,
])
def test_authentication_rejects_malformed_or_unbound_values(authorization):
    authenticator = Authenticator((
        ClientBinding(hashlib.sha256(TOKEN.encode("ascii")).hexdigest(), WORKSPACE),
    ))
    with pytest.raises(SecurityError) as failure:
        authenticator.authenticate(authorization)
    assert failure.value.code == SecurityCode.ACCESS_DENIED
    assert TOKEN not in str(failure.value)


def test_snapshot_and_cursor_match_frozen_golden_vectors():
    value = fixture()
    codec = TokenCodec(SECRET)
    assert repr(codec) == "TokenCodec(secret=<redacted>)"
    snapshot = value["snapshot"]
    snapshot_id = codec.issue_snapshot(
        snapshot["workspace_id"], snapshot["revision"], snapshot["issued_at"], snapshot["nonce"])
    assert snapshot_id == snapshot["token"]
    claims = codec.read_snapshot(snapshot_id, WORKSPACE, snapshot["issued_at"])
    assert claims.expires_at == snapshot["expires_at"]
    for expected in value["cursors"]:
        token = codec.issue_cursor(
            snapshot_id, claims, expected["scope"], tuple(expected["after"]), expected["expires_at"])
        assert token == expected["token"]
        parsed = codec.read_cursor(token, snapshot_id, WORKSPACE, expected["scope"], snapshot["issued_at"])
        assert parsed.after == tuple(expected["after"])
        assert parsed.sort_id == expected["sort_id"]


def test_request_hash_matches_frozen_nul_delimited_vector():
    expected = fixture()["request_hash_vectors"][0]
    assert request_hash(
        expected["workspace_id"], expected["kind"], expected["scope"], expected["text"]
    ) == expected["sha256"]
    assert request_hash(
        expected["workspace_id"], expected["kind"], expected["scope"],
        expected["text"].replace("e\u0301", "é"),
    ) != expected["sha256"]


def test_snapshot_is_scope_independent_but_cursor_is_not():
    value = fixture()
    codec = TokenCodec(SECRET)
    snapshot = value["snapshot"]
    claims = codec.read_snapshot(snapshot["token"], WORKSPACE, snapshot["issued_at"])
    assert not hasattr(claims, "scope")
    core = value["cursors"][0]
    with pytest.raises(SecurityError) as failure:
        codec.read_cursor(core["token"], snapshot["token"], WORKSPACE, "ARCHIVAL", snapshot["issued_at"])
    assert failure.value.code == SecurityCode.INVALID_INPUT


def test_tokens_reject_tampering_wrong_type_binding_and_expiry():
    value = fixture()
    codec = TokenCodec(SECRET)
    snapshot = value["snapshot"]
    token = snapshot["token"]
    for bad in (token + "=", token[:-1] + ("A" if token[-1] != "A" else "B"), "x", "a.b.c"):
        with pytest.raises(SecurityError):
            codec.read_snapshot(bad, WORKSPACE, snapshot["issued_at"])
    with pytest.raises(SecurityError) as wrong_workspace:
        codec.read_snapshot(token, OTHER_WORKSPACE, snapshot["issued_at"])
    assert wrong_workspace.value.code == SecurityCode.ACCESS_DENIED
    with pytest.raises(SecurityError) as expired:
        codec.read_snapshot(token, WORKSPACE, snapshot["expires_at"])
    assert expired.value.code == SecurityCode.SNAPSHOT_EXPIRED
    with pytest.raises(SecurityError):
        codec.read_cursor(token, token, WORKSPACE, "CORE", snapshot["issued_at"])


def test_cursor_binds_exact_snapshot_revision_sort_and_after_shape():
    value = fixture()
    codec = TokenCodec(SECRET)
    snapshot = value["snapshot"]
    claims = codec.read_snapshot(snapshot["token"], WORKSPACE, snapshot["issued_at"])
    with pytest.raises(SecurityError):
        codec.issue_cursor(snapshot["token"], claims, "CORE", (1,), snapshot["expires_at"])
    with pytest.raises(SecurityError):
        codec.issue_cursor(snapshot["token"], claims, "ARCHIVAL", (1, 2, "bad-id"), snapshot["expires_at"])
    with pytest.raises(SecurityError):
        codec.issue_cursor(snapshot["token"], claims, "CORE",
                           (1, "33333333-3333-4333-8333-333333333333"), snapshot["expires_at"] + 1)
    other_snapshot = codec.issue_snapshot(WORKSPACE, snapshot["revision"], snapshot["issued_at"],
                                          "99999999-9999-4999-8999-999999999999")
    with pytest.raises(SecurityError):
        codec.read_cursor(value["cursors"][0]["token"], other_snapshot, WORKSPACE, "CORE",
                          snapshot["issued_at"])


def test_signed_payload_is_sorted_compact_utf8_and_hmac_uses_original_bytes():
    token = fixture()["snapshot"]["token"]
    encoded, signature = token.split(".")
    raw = base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4))
    assert raw == json.dumps(json.loads(raw), ensure_ascii=False, sort_keys=True,
                             separators=(",", ":")).encode("utf-8")
    actual = base64.urlsafe_b64decode(signature + "=" * (-len(signature) % 4))
    assert hmac.compare_digest(actual, hmac.new(SECRET, raw, hashlib.sha256).digest())

    noncanonical = json.dumps(json.loads(raw), ensure_ascii=False, indent=1).encode("utf-8")
    forged = (
        base64.urlsafe_b64encode(noncanonical).decode().rstrip("=") + "."
        + base64.urlsafe_b64encode(hmac.new(SECRET, noncanonical, hashlib.sha256).digest()).decode().rstrip("=")
    )
    with pytest.raises(SecurityError):
        TokenCodec(SECRET).read_snapshot(forged, WORKSPACE, fixture()["snapshot"]["issued_at"])


def test_receipt_parser_round_trips_every_persistent_state_and_rejects_envelope_fields():
    value = fixture()
    for payload in value["receipts"]:
        raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        assert receipt_from_bytes(raw).to_dict() == payload
    bad = dict(value["receipts"][0], request_id="55555555-5555-4555-8555-555555555555")
    with pytest.raises(ContractError):
        receipt_from_bytes(json.dumps(bad).encode())
    with pytest.raises(ContractError):
        receipt_from_bytes(b'{"state":"FAILED","state":"COMMITTED"}')


def test_error_templates_are_fixed_and_never_accept_remote_message():
    request_id = "55555555-5555-4555-8555-555555555555"
    for code in ServiceErrorCode:
        payload = json.loads(error_bytes(code, request_id))
        assert payload["error_code"] == code.value
        assert payload["request_id"] == request_id
        assert "remote-secret" not in payload["message"]
    with pytest.raises(TypeError):
        error_bytes("SERVICE_FAILURE", request_id)
