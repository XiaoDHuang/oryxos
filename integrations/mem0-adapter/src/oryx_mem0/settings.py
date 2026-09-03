"""启动配置先整体校验，不隐式读取云端、代理或SDK默认值。"""

import base64
from dataclasses import dataclass, field
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import ssl
from urllib.parse import parse_qsl, unquote, urlsplit

from oryx_mem0.engine.providers import Endpoint, normalized_url
from oryx_mem0.engine.staging import Code, EngineError, valid_uuid

ENV_FIELDS = (
    "ADAPTER_DATABASE_URL", "ADAPTER_CLIENT_BINDINGS", "ADAPTER_CURSOR_SECRET",
    "ADAPTER_LLM_BASE_URL", "ADAPTER_LLM_MODEL", "ADAPTER_LLM_API_KEY",
    "ADAPTER_EMBEDDING_BASE_URL", "ADAPTER_EMBEDDING_MODEL", "ADAPTER_EMBEDDING_API_KEY",
    "ADAPTER_EMBEDDING_DIMENSIONS", "ADAPTER_ALLOWED_ORIGINS", "ADAPTER_TLS_CERT", "ADAPTER_TLS_KEY", "MEM0_DIR",
)
_ERROR_FIELDS = frozenset(ENV_FIELDS) | {"API_TOKEN", "SDK_IMPORT", "ENVIRONMENT"}


class ConfigError(EngineError):
    def __init__(self, name):
        self.field = name if isinstance(name, str) and name in _ERROR_FIELDS else "ENVIRONMENT"
        super().__init__(Code.INVALID_CONFIG)
        self.args = ("记忆配置无效：INVALID_CONFIG；字段=" + self.field,)


def _secret_bytes(value, name):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{43}", value):
        raise ConfigError(name)
    decoded = base64.urlsafe_b64decode(value + "=")
    if len(decoded) != 32 or base64.urlsafe_b64encode(decoded).decode("ascii").rstrip("=") != value:
        raise ConfigError(name)
    return decoded


def token_digest(value):
    _secret_bytes(value, "API_TOKEN")
    # API认证摘要计算原始ASCII；cursor HMAC才使用解码后的32字节。
    return hashlib.sha256(value.encode("ascii")).hexdigest()


def _json_array(raw, name):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError()
            result[key] = value
        return result

    def invalid_constant(value):
        raise ValueError()

    try:
        value = json.loads(raw, object_pairs_hook=pairs, parse_constant=invalid_constant)
        if not isinstance(value, list) or not value:
            raise ValueError()
        return value
    except (ValueError, UnicodeError, RecursionError):
        raise ConfigError(name) from None


@dataclass(frozen=True)
class ClientBinding:
    key_sha256: str
    workspace_id: str


def _bindings(raw):
    name = "ADAPTER_CLIENT_BINDINGS"
    result, seen = [], set()
    for entry in _json_array(raw, name):
        if (type(entry) is not dict or set(entry) != {"key_sha256", "workspace_id"}
                or not isinstance(entry["key_sha256"], str)
                or not re.fullmatch(r"[0-9a-f]{64}", entry["key_sha256"])
                or not valid_uuid(entry["workspace_id"]) or entry["key_sha256"] in seen):
            raise ConfigError(name)
        seen.add(entry["key_sha256"])
        result.append(ClientBinding(**entry))
    return tuple(result)


def _origins(raw):
    name = "ADAPTER_ALLOWED_ORIGINS"
    try:
        values = tuple(normalized_url(item, origin_only=True) for item in _json_array(raw, name))
        if len(set(values)) != len(values):
            raise ConfigError(name)
        return values
    except EngineError:
        raise ConfigError(name) from None


def _endpoint(values, prefix, origins):
    name = prefix + "_BASE_URL"
    try:
        base = normalized_url(values[name])
        parts = urlsplit(base)
        if normalized_url("https://" + parts.netloc, origin_only=True) not in origins:
            raise ConfigError(name)
    except EngineError:
        raise ConfigError(name) from None
    name = prefix + "_MODEL"
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._/:-]{0,255}", values[name]):
        raise ConfigError(name)
    name = prefix + "_API_KEY"
    if not 1 <= len(values[name]) <= 16384 or any(not 33 <= ord(char) <= 126 for char in values[name]):
        raise ConfigError(name)
    return Endpoint(base, values[prefix + "_MODEL"], values[name], origins)


def _database_url(value):
    try:
        if (len(value) > 16384 or any(ord(char) <= 32 for char in value) or "\\" in value
                or "#" in value or re.search(r"%(?![0-9a-fA-F]{2})", value)):
            raise ValueError()
        parts = urlsplit(value)
        if (parts.scheme not in {"postgresql", "postgres"} or not parts.hostname
                or not parts.username or not parts.password or not parts.path.startswith("/")
                or not parts.path[1:] or "/" in parts.path[1:]):
            raise ValueError()
        normalized_url("https://" + parts.netloc.rsplit("@", 1)[-1], origin_only=True)
        if any("\x00" in unquote(component, errors="strict") for component in (parts.username, parts.password, parts.path)):
            raise ValueError()
        options = parse_qsl(parts.query, keep_blank_values=True, strict_parsing=True, errors="strict")
        # 不允许query覆盖authority，或以service/多主机引入第二个隐式目标。
        allowed = {"sslmode", "sslrootcert", "sslcert", "sslkey", "connect_timeout", "application_name"}
        if len({key for key, _ in options}) != len(options) or any(key not in allowed or not value or "\x00" in value for key, value in options):
            raise ValueError()
        options = dict(options)
        if "sslmode" in options and options["sslmode"] not in {"disable", "allow", "prefer", "require", "verify-ca", "verify-full"}:
            raise ValueError()
        if "connect_timeout" in options and not re.fullmatch(r"[1-9][0-9]{0,2}", options["connect_timeout"]):
            raise ValueError()
        return value
    except (ValueError, UnicodeError, EngineError):
        raise ConfigError("ADAPTER_DATABASE_URL") from None


def _path(value, name, directory=False):
    try:
        path = Path(value)
        if not path.is_absolute():
            raise ValueError()
        path = path.resolve(strict=True)
        if directory:
            if not path.is_dir() or path == Path(path.anchor):
                raise ValueError()
        elif not path.is_file():
            raise ValueError()
        return path
    except (OSError, ValueError, RuntimeError, UnicodeError):
        raise ConfigError(name) from None


@dataclass(frozen=True, repr=False)
class Settings:
    database_url: str = field(repr=False)
    client_bindings: tuple[ClientBinding, ...]
    cursor_secret: bytes = field(repr=False)
    llm: Endpoint
    embedding: Endpoint
    dimensions: int
    allowed_origins: tuple[str, ...]
    tls_cert: Path
    tls_key: Path = field(repr=False)
    mem0_dir: Path

    @classmethod
    def load(cls, environ=None):
        source = os.environ if environ is None else environ
        values = {}
        for name in ENV_FIELDS:
            raw = source.get(name)
            if not isinstance(raw, str) or not raw.strip():
                raise ConfigError(name)
            values[name] = raw
        bindings = _bindings(values["ADAPTER_CLIENT_BINDINGS"])
        cursor_text = values["ADAPTER_CURSOR_SECRET"]
        cursor = _secret_bytes(cursor_text, "ADAPTER_CURSOR_SECRET")
        if any(hmac.compare_digest(hashlib.sha256(cursor_text.encode("ascii")).hexdigest(), entry.key_sha256) for entry in bindings):
            raise ConfigError("ADAPTER_CURSOR_SECRET")
        origins = _origins(values["ADAPTER_ALLOWED_ORIGINS"])
        dimensions = values["ADAPTER_EMBEDDING_DIMENSIONS"]
        # vector(D)的具体兼容性由schema门禁验证，这里先锁定正整数配置。
        if not re.fullmatch(r"[1-9][0-9]{0,9}", dimensions) or int(dimensions) > 2147483647:
            raise ConfigError("ADAPTER_EMBEDDING_DIMENSIONS")
        return cls(
            _database_url(values["ADAPTER_DATABASE_URL"]), bindings, cursor,
            _endpoint(values, "ADAPTER_LLM", origins), _endpoint(values, "ADAPTER_EMBEDDING", origins),
            int(dimensions), origins, _path(values["ADAPTER_TLS_CERT"], "ADAPTER_TLS_CERT"),
            _path(values["ADAPTER_TLS_KEY"], "ADAPTER_TLS_KEY"), _path(values["MEM0_DIR"], "MEM0_DIR", directory=True),
        )

    def tls_context(self):
        try:
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.minimum_version = ssl.TLSVersion.TLSv1_2

            def reject_password():
                # 加密私钥缺解锁渠道时拒绝，不能在守护进程里交互等待密码。
                raise ConfigError("ADAPTER_TLS_KEY")

            context.load_cert_chain(self.tls_cert, self.tls_key, password=reject_password)
            return context
        except (OSError, ValueError, EngineError):
            raise ConfigError("ADAPTER_TLS_KEY") from None
