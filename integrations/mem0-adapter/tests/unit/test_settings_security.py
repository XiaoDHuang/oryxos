"""配置坏项必须整体失败，冷进程验证真实SDK导入边界。"""

import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import traceback
from dataclasses import FrozenInstanceError
from tempfile import TemporaryDirectory

import pytest

from oryx_mem0.settings import ConfigError, Settings, token_digest

WORKSPACE = "11111111-1111-4111-8111-111111111111"
TOKEN = base64.urlsafe_b64encode(bytes(range(32))).decode().rstrip("=")
CURSOR = base64.urlsafe_b64encode(bytes(range(32, 64))).decode().rstrip("=")
DIGEST = hashlib.sha256(TOKEN.encode("ascii")).hexdigest()


@pytest.fixture(scope="module")
def configuration():
    # 合成私钥是公开测试资产，运行时Secret仍只能经部署渠道注入。
    fixture = json.loads((Path(__file__).parents[1] / "fixtures/tls-unit.json").read_text(encoding="utf-8"))
    with TemporaryDirectory(prefix="oryx-config-") as directory:
        root = Path(directory)
        (root / "cert.pem").write_text(fixture["certificate"], encoding="ascii")
        (root / "key.pem").write_text(fixture["private_key"], encoding="ascii")
        (root / "sdk").mkdir()
        yield {
            "ADAPTER_DATABASE_URL": "postgresql://test:synthetic-db-secret@db.invalid:5432/oryx_test?sslmode=verify-full",
            "ADAPTER_CLIENT_BINDINGS": json.dumps([{"key_sha256": DIGEST, "workspace_id": WORKSPACE}]),
            "ADAPTER_CURSOR_SECRET": CURSOR,
            "ADAPTER_LLM_BASE_URL": "https://LLM.invalid:443/api/v1/",
            "ADAPTER_LLM_MODEL": "local/test-model",
            "ADAPTER_LLM_API_KEY": "synthetic-llm-secret",
            "ADAPTER_EMBEDDING_BASE_URL": "https://embedding.invalid:8443/v1",
            "ADAPTER_EMBEDDING_MODEL": "local/test-embedding",
            "ADAPTER_EMBEDDING_API_KEY": "synthetic-embedding-secret",
            "ADAPTER_EMBEDDING_DIMENSIONS": "2",
            "ADAPTER_ALLOWED_ORIGINS": '["https://llm.invalid", "https://embedding.invalid:8443"]',
            "ADAPTER_TLS_CERT": str(root / "cert.pem"),
            "ADAPTER_TLS_KEY": str(root / "key.pem"),
            "MEM0_DIR": str(root / "sdk"),
        }


def test_explicit_settings_are_immutable_and_do_not_echo_secrets(configuration):
    value = Settings.load(configuration)
    assert value.llm.base_url == "https://llm.invalid/api/v1"
    assert value.dimensions == 2
    assert value.cursor_secret == bytes(range(32, 64))
    assert value.client_bindings[0].key_sha256 == DIGEST
    assert value.client_bindings[0].workspace_id == WORKSPACE
    assert value.mem0_dir == Path(configuration["MEM0_DIR"]).resolve()
    rendered = repr(value)
    for key in ("ADAPTER_DATABASE_URL", "ADAPTER_CURSOR_SECRET", "ADAPTER_LLM_API_KEY", "ADAPTER_EMBEDDING_API_KEY"):
        assert configuration[key] not in rendered
    with pytest.raises(FrozenInstanceError):
        value.dimensions = 3


@pytest.mark.parametrize("key", [
    "ADAPTER_DATABASE_URL", "ADAPTER_CLIENT_BINDINGS", "ADAPTER_CURSOR_SECRET",
    "ADAPTER_LLM_BASE_URL", "ADAPTER_LLM_MODEL", "ADAPTER_LLM_API_KEY",
    "ADAPTER_EMBEDDING_BASE_URL", "ADAPTER_EMBEDDING_MODEL", "ADAPTER_EMBEDDING_API_KEY",
    "ADAPTER_EMBEDDING_DIMENSIONS", "ADAPTER_ALLOWED_ORIGINS", "ADAPTER_TLS_CERT", "ADAPTER_TLS_KEY", "MEM0_DIR",
])
@pytest.mark.parametrize("missing", [None, "", "  "])
def test_all_fields_are_required(configuration, key, missing):
    data = dict(configuration)
    if missing is None:
        data.pop(key)
    else:
        data[key] = missing
    with pytest.raises(ConfigError) as failure:
        Settings.load(data)
    assert failure.value.field == key
    assert key in str(failure.value)


@pytest.mark.parametrize("raw", [
    "{}", "[]", "null", "false", '"csv"', '[null]', '[{"key_sha256":1,"workspace_id":"x"}]',
    json.dumps([{"key_sha256": DIGEST, "workspace_id": WORKSPACE, "extra": True}]),
    json.dumps([{"key_sha256": DIGEST}]),
    json.dumps([{"key_sha256": DIGEST.upper(), "workspace_id": WORKSPACE}]),
    json.dumps([{"key_sha256": "a" * 63, "workspace_id": WORKSPACE}]),
    json.dumps([{"key_sha256": "g" * 64, "workspace_id": WORKSPACE}]),
    json.dumps([{"key_sha256": DIGEST, "workspace_id": "00000000-0000-0000-0000-000000000000"}]),
    json.dumps([{"key_sha256": DIGEST, "workspace_id": WORKSPACE.replace("-", "")}]),
    json.dumps([{"key_sha256": DIGEST, "workspace_id": "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"}]),
    '[{"key_sha256":"' + DIGEST + '","key_sha256":"' + DIGEST + '","workspace_id":"' + WORKSPACE + '"}]',
    json.dumps([{"key_sha256": DIGEST, "workspace_id": WORKSPACE}, None]),
])
def test_binding_schema_rejects_entire_bad_input(configuration, raw):
    with pytest.raises(ConfigError, match="ADAPTER_CLIENT_BINDINGS"):
        Settings.load(dict(configuration, ADAPTER_CLIENT_BINDINGS=raw))


@pytest.mark.parametrize("second_workspace", [WORKSPACE, "22222222-2222-4222-8222-222222222222"])
def test_duplicate_digest_is_never_last_wins(configuration, second_workspace):
    raw = json.dumps([{"key_sha256": DIGEST, "workspace_id": workspace} for workspace in (WORKSPACE, second_workspace)])
    with pytest.raises(ConfigError):
        Settings.load(dict(configuration, ADAPTER_CLIENT_BINDINGS=raw))


def test_distinct_keys_can_rotate_within_same_workspace(configuration):
    raw = json.dumps([{"key_sha256": digest, "workspace_id": WORKSPACE} for digest in (DIGEST, "a" * 64)])
    assert len(Settings.load(dict(configuration, ADAPTER_CLIENT_BINDINGS=raw)).client_bindings) == 2


@pytest.mark.parametrize("raw", [
    '[]', '{}', 'null', '[null]', '[1]', 'https://llm.invalid,https://embedding.invalid:8443',
    '["http://llm.invalid"]', '["https://*.invalid"]', '["https://llm.invalid/api"]',
    '["https://user:secret@llm.invalid"]', '["https://llm.invalid?"]', '["https://llm.invalid#"]',
    '["https://llm.invalid", "https://LLM.invalid:443/"]',
    '["https://[0:0:0:0:0:0:0:1]", "https://[::1]:443"]',
    '["https://例子.测试", "https://xn--fsqu00a.xn--0zwm56d"]',
])
def test_origin_shapes_and_normalized_duplicates_rejected(configuration, raw):
    with pytest.raises(ConfigError, match="ADAPTER_ALLOWED_ORIGINS"):
        Settings.load(dict(configuration, ADAPTER_ALLOWED_ORIGINS=raw))


def test_origins_are_canonical_and_exact_not_suffixes(configuration):
    allowed = '["https://LLM.invalid.:443/", "https://embedding.invalid:8443", "https://例子.测试", "https://[0:0:0:0:0:0:0:1]"]'
    value = Settings.load(dict(configuration, ADAPTER_ALLOWED_ORIGINS=allowed))
    assert value.allowed_origins == ("https://llm.invalid", "https://embedding.invalid:8443", "https://xn--fsqu00a.xn--0zwm56d", "https://[::1]")
    with pytest.raises(ConfigError, match="ADAPTER_LLM_BASE_URL"):
        Settings.load(dict(configuration, ADAPTER_LLM_BASE_URL="https://llm.invalid.attacker.invalid"))


@pytest.mark.parametrize("bad", [TOKEN + "=", TOKEN[:-1], "!" * 43, " " + TOKEN, TOKEN + "\n", "é" * 43, TOKEN[:-1] + "9"])
def test_noncanonical_api_tokens_and_cursor_secrets_rejected(configuration, bad):
    with pytest.raises(ConfigError):
        token_digest(bad)
    with pytest.raises(ConfigError, match="ADAPTER_CURSOR_SECRET"):
        Settings.load(dict(configuration, ADAPTER_CURSOR_SECRET=bad))


def test_api_digest_hashes_ascii_not_decoded_bytes_and_reuse_is_denied(configuration):
    assert token_digest(TOKEN) == DIGEST
    assert token_digest(TOKEN) != hashlib.sha256(bytes(range(32))).hexdigest()
    with pytest.raises(ConfigError, match="ADAPTER_CURSOR_SECRET"):
        Settings.load(dict(configuration, ADAPTER_CURSOR_SECRET=TOKEN))


@pytest.mark.parametrize("bad", ["0", "-1", "2.0", "true", " 2", "02", "NaN", "9" * 100])
def test_dimensions_are_explicit_positive_integer(configuration, bad):
    with pytest.raises(ConfigError, match="ADAPTER_EMBEDDING_DIMENSIONS"):
        Settings.load(dict(configuration, ADAPTER_EMBEDDING_DIMENSIONS=bad))


@pytest.mark.parametrize("key,bad", [
    ("ADAPTER_DATABASE_URL", "postgresql:///db"),
    ("ADAPTER_DATABASE_URL", "host=cloud.invalid dbname=secret"),
    ("ADAPTER_DATABASE_URL", "postgresql://u:p@db.invalid/db?host=other.invalid"),
    ("ADAPTER_DATABASE_URL", "postgresql://u:p@db.invalid/db?sslmode=verify-full&sslmode=disable"),
    ("ADAPTER_LLM_API_KEY", "secret\r\nAuthorization: unsafe"),
    ("ADAPTER_LLM_MODEL", "../not-a-model"),
    ("ADAPTER_TLS_CERT", "relative.pem"),
    ("ADAPTER_TLS_KEY", "relative.key"),
    ("MEM0_DIR", "relative-sdk"),
])
def test_structural_configuration_errors_are_safe(configuration, key, bad):
    with pytest.raises(ConfigError) as failure:
        Settings.load(dict(configuration, **{key: bad}))
    rendered = "".join(traceback.format_exception(failure.value))
    assert bad not in str(failure.value)
    assert "synthetic-db-secret" not in rendered
    assert failure.value.field == key


@pytest.mark.parametrize("key", ["ADAPTER_TLS_CERT", "ADAPTER_TLS_KEY", "MEM0_DIR"])
def test_missing_absolute_files_and_directory_fail(configuration, key):
    with pytest.raises(ConfigError, match=key):
        Settings.load(dict(configuration, **{key: str(Path(configuration["MEM0_DIR"]) / "absent")}))


def test_tls_pair_must_load_without_interactive_password_prompt(configuration):
    value = Settings.load(configuration)
    assert value.tls_context().minimum_version.name in {"TLSv1_2", "TLSv1_3"}
    value = Settings.load(dict(configuration, ADAPTER_TLS_KEY=configuration["ADAPTER_TLS_CERT"]))
    with pytest.raises(ConfigError, match="ADAPTER_TLS_KEY") as failure:
        value.tls_context()
    assert "PEM" not in str(failure.value)


def test_encrypted_private_key_callback_does_not_prompt(configuration, monkeypatch):
    import ssl
    callbacks = []
    def encrypted_key(context, certfile, keyfile, password):
        callbacks.append(password)
        password()
    monkeypatch.setattr(ssl.SSLContext, "load_cert_chain", encrypted_key)
    with pytest.raises(ConfigError, match="ADAPTER_TLS_KEY"):
        Settings.load(configuration).tls_context()
    assert len(callbacks) == 1


def test_constructor_cannot_reflect_arbitrary_error_field():
    error = ConfigError("synthetic-private-value")
    assert error.field == "ENVIRONMENT"
    assert "synthetic" not in str(error)


_COLD_PROCESS = r'''
import importlib.abc, json, os, pathlib, socket, sys, traceback
data = json.loads(sys.stdin.read())
for key in list(os.environ):
    if key.startswith("ADAPTER_") or key in {"MEM0_TELEMETRY", "MEM0_DIR"}:
        del os.environ[key]
network_attempts = []
def forbidden(*args, **kwargs):
    network_attempts.append(True)
    raise AssertionError("冷启动测试禁止网络")
socket.socket.connect = socket.socket.connect_ex = socket.create_connection = socket.getaddrinfo = forbidden
from oryx_mem0.bootstrap import bootstrap
assert not any(name == "mem0" or name.startswith("mem0.") for name in sys.modules)
assert "oryx_mem0.engine.staged_memory" not in sys.modules
mode = data.pop("_mode")
if mode == "preloaded":
    sys.modules["mem0"] = object()
if mode == "config_link_probe":
    original_is_symlink = pathlib.Path.is_symlink
    config_path = pathlib.Path(data["MEM0_DIR"]).resolve() / "config.json"
    pathlib.Path.is_symlink = lambda path: path == config_path or original_is_symlink(path)
os.environ["MEM0_TELEMETRY"] = "true"
class ImportGuard(importlib.abc.MetaPathFinder):
    def find_spec(self, fullname, path, target=None):
        if fullname == "mem0":
            assert os.environ["MEM0_TELEMETRY"] == "false"
            assert pathlib.Path(os.environ["MEM0_DIR"]) == pathlib.Path(data["MEM0_DIR"]).resolve()
sys.meta_path.insert(0, ImportGuard())
try:
    runtime = bootstrap(data)
    assert mode == "valid"
    from mem0.memory import telemetry, setup
    assert telemetry.MEM0_TELEMETRY is False
    assert telemetry.client_telemetry.posthog is None
    assert pathlib.Path(setup.mem0_dir) == runtime.settings.mem0_dir
    assert runtime.staged_memory_type.__name__ == "StagedMemory"
    assert {p.name for p in runtime.settings.mem0_dir.iterdir()} == {"config.json"}
    assert not network_attempts
    print("SAFE_STARTUP")
except Exception as error:
    if mode == "valid":
        raise
    from oryx_mem0.settings import ConfigError
    assert isinstance(error, ConfigError)
    assert "oryx_mem0.engine.staged_memory" not in sys.modules
    if mode != "preloaded":
        assert "mem0" not in sys.modules
    assert "synthetic" not in "".join(traceback.format_exception(error))
    assert not network_attempts
    print("SAFE_REJECTION")
'''


@pytest.mark.parametrize("mode", ["valid", "missing_secret", "bad_tls", "preloaded", "config_link_probe"])
def test_bootstrap_cold_process_has_no_unsafe_import_or_network(configuration, mode):
    data = dict(configuration, _mode=mode)
    if mode == "missing_secret":
        data.pop("ADAPTER_LLM_API_KEY")
    if mode == "bad_tls":
        data["ADAPTER_TLS_KEY"] = data["ADAPTER_TLS_CERT"]
    with TemporaryDirectory(prefix="oryx-bootstrap-") as directory:
        sdk = Path(directory) / "sdk"
        sdk.mkdir()
        target = Path(directory) / "outside.json"
        data["MEM0_DIR"] = str(sdk)
        environment = dict(os.environ, PYTHONPATH=str(Path(__file__).parents[2] / "src"))
        result = subprocess.run([sys.executable, "-c", _COLD_PROCESS], input=json.dumps(data),
                                text=True, capture_output=True, env=environment, timeout=45)
        assert result.returncode == 0, result.stdout + result.stderr
        assert result.stdout.strip() == ("SAFE_STARTUP" if mode == "valid" else "SAFE_REJECTION")
        assert not target.exists()
        if mode != "valid":
            assert list(sdk.iterdir()) == []
