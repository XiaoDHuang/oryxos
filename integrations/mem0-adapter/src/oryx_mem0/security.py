"""协议认证与签名令牌不依赖FastAPI路由或数据库连接。"""

import base64
from dataclasses import dataclass, field
from enum import StrEnum
import hashlib
import hmac
import json
import re

from oryx_mem0.engine.staging import valid_uuid
from oryx_mem0.settings import ClientBinding, ConfigError, token_digest

_MAX_TOKEN_CHARS = 8192
_MAX_PAYLOAD_BYTES = 4096
_SNAPSHOT_TTL_SECONDS = 300
_MAX_REVISION = 9223372036854775807


class SecurityCode(StrEnum):
    INVALID_INPUT = "INVALID_INPUT"
    ACCESS_DENIED = "ACCESS_DENIED"
    SNAPSHOT_EXPIRED = "SNAPSHOT_EXPIRED"


class SecurityError(ValueError):
    def __init__(self, code):
        if not isinstance(code, SecurityCode):
            raise TypeError("安全错误必须使用固定分类")
        self.code = code
        super().__init__("记忆安全校验失败：" + code.value)


@dataclass(frozen=True)
class SnapshotClaims:
    workspace_id: str
    revision: int
    nonce: str
    issued_at: int
    expires_at: int


@dataclass(frozen=True)
class CursorClaims:
    snapshot_digest: str
    workspace_id: str
    revision: int
    scope: str
    sort_id: str
    after: tuple = field(repr=False)
    expires_at: int


def _require(condition, code=SecurityCode.INVALID_INPUT):
    if not condition:
        raise SecurityError(code)


def _integer(value, minimum=0, maximum=_MAX_REVISION):
    _require(type(value) is int and minimum <= value <= maximum)


def _uuid(value):
    _require(valid_uuid(value))


def _encode(value):
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def _decode(value):
    _require(isinstance(value, str) and bool(value) and re.fullmatch(r"[A-Za-z0-9_-]+", value))
    try:
        decoded = base64.b64decode(value + "=" * (-len(value) % 4), altchars=b"-_", validate=True)
    except (ValueError, TypeError):
        raise SecurityError(SecurityCode.INVALID_INPUT) from None
    _require(_encode(decoded) == value)
    return decoded


def _json_bytes(payload):
    try:
        raw = json.dumps(
            payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")
    except (TypeError, ValueError, UnicodeError, RecursionError):
        raise SecurityError(SecurityCode.INVALID_INPUT) from None
    _require(len(raw) <= _MAX_PAYLOAD_BYTES)
    return raw


def _json_object(raw):
    _require(type(raw) is bytes and 0 < len(raw) <= _MAX_PAYLOAD_BYTES)

    def pairs(items):
        result = {}
        for key, value in items:
            _require(type(key) is str and key not in result)
            result[key] = value
        return result

    def invalid_constant(value):
        raise SecurityError(SecurityCode.INVALID_INPUT)

    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=pairs, parse_constant=invalid_constant)
    except (ValueError, UnicodeError, RecursionError):
        raise SecurityError(SecurityCode.INVALID_INPUT) from None
    _require(type(value) is dict and _json_bytes(value) == raw)
    return value


def _after(scope, value):
    _require(type(value) in (list, tuple))
    if scope == "CORE":
        _require(len(value) == 2)
        _integer(value[0])
        _uuid(value[1])
        return "core_created_asc", tuple(value)
    _require(scope == "ARCHIVAL" and len(value) == 3)
    _integer(value[0])
    _integer(value[1], maximum=128)
    _uuid(value[2])
    return "archival_updated_asc", tuple(value)


class Authenticator:
    def __init__(self, bindings):
        _require(type(bindings) is tuple and bool(bindings), SecurityCode.ACCESS_DENIED)
        for binding in bindings:
            _require(
                type(binding) is ClientBinding
                and re.fullmatch(r"[0-9a-f]{64}", binding.key_sha256) is not None
                and valid_uuid(binding.workspace_id),
                SecurityCode.ACCESS_DENIED,
            )
        self._bindings = bindings

    def __repr__(self):
        return f"Authenticator(bindings={len(self._bindings)})"

    def authenticate(self, authorization):
        if not isinstance(authorization, str) or not authorization.startswith("Bearer "):
            raise SecurityError(SecurityCode.ACCESS_DENIED)
        token = authorization[7:]
        if authorization != "Bearer " + token or not re.fullmatch(r"[A-Za-z0-9_-]{43}", token):
            raise SecurityError(SecurityCode.ACCESS_DENIED)
        try:
            digest = token_digest(token)
        except ConfigError:
            raise SecurityError(SecurityCode.ACCESS_DENIED) from None
        workspace_id = None
        for binding in self._bindings:
            if hmac.compare_digest(digest, binding.key_sha256):
                workspace_id = binding.workspace_id
        if workspace_id is None:
            raise SecurityError(SecurityCode.ACCESS_DENIED)
        return workspace_id


class TokenCodec:
    def __init__(self, secret):
        _require(type(secret) is bytes and len(secret) == 32)
        self._secret = secret

    def __repr__(self):
        return "TokenCodec(secret=<redacted>)"

    def _issue(self, payload):
        raw = _json_bytes(payload)
        signature = hmac.new(self._secret, raw, hashlib.sha256).digest()
        return _encode(raw) + "." + _encode(signature)

    def _read(self, token):
        _require(isinstance(token, str) and len(token) <= _MAX_TOKEN_CHARS and token.count(".") == 1)
        encoded, signed = token.split(".")
        raw = _decode(encoded)
        signature = _decode(signed)
        _require(len(signature) == hashlib.sha256().digest_size)
        expected = hmac.new(self._secret, raw, hashlib.sha256).digest()
        _require(hmac.compare_digest(signature, expected))
        return _json_object(raw)

    def _snapshot(self, token, workspace_id=None, now=None):
        payload = self._read(token)
        fields = {"v", "type", "workspace_id", "revision", "nonce", "issued_at", "expires_at"}
        _require(set(payload) == fields and payload["v"] == 1 and payload["type"] == "snapshot")
        _uuid(payload["workspace_id"])
        _uuid(payload["nonce"])
        _integer(payload["revision"])
        _integer(payload["issued_at"])
        _integer(payload["expires_at"])
        _require(payload["expires_at"] == payload["issued_at"] + _SNAPSHOT_TTL_SECONDS)
        if workspace_id is not None:
            _uuid(workspace_id)
            _require(
                hmac.compare_digest(payload["workspace_id"].encode(), workspace_id.encode()),
                SecurityCode.ACCESS_DENIED,
            )
        if now is not None:
            _integer(now)
            _require(now >= payload["issued_at"])
            _require(now < payload["expires_at"], SecurityCode.SNAPSHOT_EXPIRED)
        return SnapshotClaims(
            payload["workspace_id"], payload["revision"], payload["nonce"],
            payload["issued_at"], payload["expires_at"],
        )

    def issue_snapshot(self, workspace_id, revision, issued_at, nonce):
        _uuid(workspace_id)
        _uuid(nonce)
        _integer(revision)
        _integer(issued_at, maximum=_MAX_REVISION - _SNAPSHOT_TTL_SECONDS)
        return self._issue({
            "v": 1,
            "type": "snapshot",
            "workspace_id": workspace_id,
            "revision": revision,
            "nonce": nonce,
            "issued_at": issued_at,
            "expires_at": issued_at + _SNAPSHOT_TTL_SECONDS,
        })

    def read_snapshot(self, token, workspace_id, now):
        return self._snapshot(token, workspace_id, now)

    def issue_cursor(self, snapshot_id, snapshot, scope, after, expires_at):
        _require(type(snapshot) is SnapshotClaims)
        actual = self._snapshot(snapshot_id)
        _require(actual == snapshot)
        sort_id, checked_after = _after(scope, after)
        _integer(expires_at)
        _require(snapshot.issued_at < expires_at <= snapshot.expires_at)
        digest = hashlib.sha256(snapshot_id.encode("ascii")).hexdigest()
        return self._issue({
            "v": 1,
            "type": "cursor",
            "snapshot_digest": digest,
            "workspace_id": snapshot.workspace_id,
            "revision": snapshot.revision,
            "scope": scope,
            "sort_id": sort_id,
            "after": list(checked_after),
            "expires_at": expires_at,
        })

    def read_cursor(self, token, snapshot_id, workspace_id, scope, now):
        snapshot = self.read_snapshot(snapshot_id, workspace_id, now)
        payload = self._read(token)
        fields = {
            "v", "type", "snapshot_digest", "workspace_id", "revision", "scope", "sort_id", "after",
            "expires_at",
        }
        _require(set(payload) == fields and payload["v"] == 1 and payload["type"] == "cursor")
        _uuid(payload["workspace_id"])
        _require(
            hmac.compare_digest(payload["workspace_id"].encode(), workspace_id.encode()),
            SecurityCode.ACCESS_DENIED,
        )
        _integer(payload["revision"])
        _require(payload["revision"] == snapshot.revision and payload["workspace_id"] == snapshot.workspace_id)
        _require(payload["scope"] == scope)
        sort_id, checked_after = _after(scope, payload["after"])
        _require(payload["sort_id"] == sort_id)
        expected_digest = hashlib.sha256(snapshot_id.encode("ascii")).hexdigest()
        _require(
            isinstance(payload["snapshot_digest"], str)
            and re.fullmatch(r"[0-9a-f]{64}", payload["snapshot_digest"]) is not None
            and hmac.compare_digest(payload["snapshot_digest"], expected_digest)
        )
        _integer(payload["expires_at"])
        _require(payload["expires_at"] <= snapshot.expires_at)
        _require(now < payload["expires_at"], SecurityCode.SNAPSHOT_EXPIRED)
        return CursorClaims(
            payload["snapshot_digest"], payload["workspace_id"], payload["revision"], scope,
            sort_id, checked_after, payload["expires_at"],
        )
