"""模型协议、审计和I/O预算的负例不能被解释为空事实。"""

import json
import io
import ssl
import threading
from dataclasses import replace
from time import monotonic

import pytest

from oryx_mem0.engine.providers import Endpoint
from oryx_mem0.engine.staging import Code, EngineError, MAX_IO_BYTES
from oryx_mem0.engine import providers


def test_failed_start_audit_prevents_io(engine_factory):
    system = engine_factory([{"facts": []}])
    system.audit.reject_begin = True
    with pytest.raises(EngineError) as error:
        system.llm.generate_response([{"role": "user", "content": "内容"}])
    assert error.value.code == Code.AUDIT_FAILURE
    assert system.transport.requests == []


def test_failed_finish_audit_is_fatal(engine_factory):
    system = engine_factory([{"facts": []}])
    system.audit.reject_finish = True
    with pytest.raises(EngineError) as error:
        system.llm.generate_response([{"role": "user", "content": "内容"}])
    assert error.value.code == Code.AUDIT_FAILURE
    assert len(system.transport.requests) == 1
    assert system.context.failure == Code.AUDIT_FAILURE
    assert system.context.active_call is None


def test_transport_failure_is_sanitized_and_audited_once(engine_factory):
    system = engine_factory([RuntimeError("llm-test-secret https://private.example/secret")])
    with pytest.raises(EngineError) as error:
        system.llm.generate_response([{"role": "user", "content": "内容"}])
    assert "secret" not in str(error.value)
    assert system.audit.calls[0]["state"] == "FAILED"
    assert "secret" not in json.dumps(system.audit.calls)
    assert len(system.transport.requests) == 1


def test_audit_redacts_configured_secrets_and_does_not_invent_usage(engine_factory):
    system = engine_factory([{"facts": []}])
    system.llm.generate_response([{"role": "user", "content": "llm-test-secret embedding-test-secret"}])
    serialized = json.dumps(system.audit.calls)
    assert "llm-test-secret" not in serialized
    assert "embedding-test-secret" not in serialized
    assert system.audit.calls[0]["usage"] is None
    assert system.audit.calls[0]["workspace_id"] == system.context.workspace_id
    assert system.audit.calls[0]["operation_id"] == system.context.operation_id


def test_request_budget_counts_json_escaping_before_io(engine_factory):
    system = engine_factory([])
    with pytest.raises(EngineError) as error:
        system.llm.generate_response([{"role": "user", "content": '"' * (MAX_IO_BYTES // 2)}])
    assert error.value.code == Code.LIMIT_EXCEEDED
    assert system.transport.requests == []


def test_response_budget_checked_before_parse(engine_factory):
    system = engine_factory([b"x" * (MAX_IO_BYTES + 1)])
    with pytest.raises(EngineError) as error:
        system.llm.generate_response([{"role": "user", "content": "内容"}])
    assert error.value.code == Code.LIMIT_EXCEEDED
    assert system.audit.calls[-1]["state"] == "FAILED"


@pytest.mark.parametrize("vector", [[0, 0], [float("nan"), 0], [float("inf"), 0], [1], [True, 0], ["1", 0]])
def test_embedding_rejects_invalid_vectors(engine_factory, vector):
    system = engine_factory([])
    system.transport.embedding_response = {"data": [{"index": 0, "embedding": vector}]}
    with pytest.raises(EngineError):
        system.embedding.embed("内容")
    assert system.context.failure is not None
    assert system.audit.calls[-1]["state"] == "FAILED"


@pytest.mark.parametrize("base,allowed", [
    ("http://llm.example/v1", ("https://llm.example",)),
    ("https://llm.example/v1", ()),
    ("https://llm.example.evil/v1", ("https://llm.example",)),
    ("https://key@llm.example/v1", ("https://llm.example",)),
    ("https://llm.example/v1?key=secret", ("https://llm.example",)),
])
def test_endpoint_requires_explicit_https_origin(base, allowed):
    with pytest.raises(EngineError):
        Endpoint(base, "model", "test-secret", allowed)


def test_expired_deadline_prevents_io(engine_factory, run_context):
    system = engine_factory([], context=replace(run_context, deadline=monotonic() - 1))
    with pytest.raises(EngineError) as error:
        system.llm.generate_response([{"role": "user", "content": "内容"}])
    assert error.value.code == Code.DEADLINE
    assert system.transport.requests == []


@pytest.fixture
def http_fixture(monkeypatch):
    options = {"status": 200, "headers": {"Content-Type": "application/json"}, "stall": None,
               "body": json.dumps({"choices": [{"finish_reason": "stop", "message": {"role": "assistant", "content": '{"facts":[]}'}}]}).encode()}
    connections = []
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    monkeypatch.setattr(providers.ssl, "create_default_context", lambda: tls)

    class Response:
        def __init__(self, connection):
            self.connection = connection
            self.status = options["status"]
            self.body = io.BytesIO(options["body"])
            self.closed = False

        def getheader(self, name, default=None):
            return options["headers"].get(name, default)

        def read(self, amount):
            if options["stall"] == "body":
                self.connection.aborted.wait(1)
                raise OSError("合成慢响应已关闭")
            return self.body.read(amount)

        def __enter__(self):
            return self

        def __exit__(self, *args):
            self.closed = True

    class Connection:
        def __init__(self, host, port, context, ssl_context):
            assert ssl_context.verify_mode == ssl.CERT_REQUIRED
            assert ssl_context.check_hostname
            self.context = context
            self.host = host
            self.aborted = threading.Event()
            self.requests = []
            self.response = Response(self)
            connections.append(self)

        def request(self, method, path, body, headers):
            assert self.context.active_call is not None
            self.requests.append((method, path, body, headers))

        def getresponse(self):
            if options["stall"] == "headers":
                self.aborted.wait(1)
                raise OSError("合成慢响应头已关闭")
            return self.response

        def abort(self):
            self.aborted.set()

    monkeypatch.setattr(providers, "_BoundedHttpsConnection", Connection)
    return options, connections, tls


def test_https_transport_guards_audit_before_creating_connection(run_context, http_fixture):
    _, connections, _ = http_fixture
    endpoint = Endpoint("https://llm.example/v1", "model", "secret", ("https://llm.example",))
    with pytest.raises(EngineError) as error:
        providers.HttpsTransport().post(endpoint, "chat/completions", b"{}", run_context)
    assert error.value.code == Code.AUDIT_FAILURE
    assert connections == []


def test_https_transport_is_direct_and_closes_resources(engine_factory, http_fixture, monkeypatch):
    _, connections, _ = http_fixture
    monkeypatch.setenv("HTTPS_PROXY", "https://unapproved.invalid")
    monkeypatch.setenv("OPENROUTER_API_KEY", "unapproved-key")
    system = engine_factory([])
    system.llm.transport = providers.HttpsTransport()
    assert json.loads(system.llm.generate_response([{"role": "user", "content": "内容"}])) == {"facts": []}
    connection = connections[0]
    assert connection.host == "llm.example"
    assert len(connection.requests) == 1
    assert connection.requests[0][0:2] == ("POST", "/v1/chat/completions")
    assert connection.requests[0][3]["Accept-Encoding"] == "identity"
    assert connection.response.closed and connection.aborted.is_set()
    assert system.context.active_call is None


@pytest.mark.parametrize("status", [301, 302, 307, 308, 429, 500])
def test_http_errors_do_not_follow_redirects_or_retry(engine_factory, http_fixture, status):
    options, connections, _ = http_fixture
    options["status"] = status
    options["headers"]["Location"] = "https://unapproved.invalid"
    system = engine_factory([])
    system.llm.transport = providers.HttpsTransport()
    with pytest.raises(EngineError):
        system.llm.generate_response([{"role": "user", "content": "内容"}])
    assert len(connections) == len(connections[0].requests) == 1
    assert connections[0].aborted.is_set()
    assert system.audit.calls[-1]["state"] == "FAILED"


def test_response_stream_limit_and_compression_are_rejected(engine_factory, http_fixture):
    options, connections, _ = http_fixture
    options["body"] = b"x" * (MAX_IO_BYTES + 1)
    system = engine_factory([])
    system.llm.transport = providers.HttpsTransport()
    with pytest.raises(EngineError) as error:
        system.llm.generate_response([{"role": "user", "content": "内容"}])
    assert error.value.code == Code.LIMIT_EXCEEDED
    assert connections[0].response.body.tell() == MAX_IO_BYTES + 1
    assert connections[0].response.closed


@pytest.mark.parametrize("stall", ["headers", "body"])
def test_total_deadline_closes_stalled_io(engine_factory, run_context, http_fixture, stall):
    options, connections, _ = http_fixture
    options["stall"] = stall
    context = replace(run_context, deadline=monotonic() + 0.08)
    system = engine_factory([], context=context)
    system.llm.transport = providers.HttpsTransport()
    started = monotonic()
    with pytest.raises(EngineError) as error:
        system.llm.generate_response([{"role": "user", "content": "内容"}])
    assert error.value.code == Code.DEADLINE
    assert monotonic() - started < 0.6
    assert connections[0].aborted.is_set()
    assert system.audit.calls[-1]["state"] == "FAILED"


def test_weak_tls_context_is_never_used(engine_factory, http_fixture):
    _, connections, tls = http_fixture
    tls.check_hostname = False
    tls.verify_mode = ssl.CERT_NONE
    system = engine_factory([])
    system.llm.transport = providers.HttpsTransport()
    with pytest.raises(EngineError) as error:
        system.llm.generate_response([{"role": "user", "content": "内容"}])
    assert error.value.code == Code.INVALID_CONFIG
    assert connections == []


def test_slow_dns_is_bounded_and_cannot_connect_after_expiry(run_context, monkeypatch):
    release = threading.Event()
    finished = threading.Event()
    def resolve(*args):
        release.wait(1)
        finished.set()
        return [(2, 1, 0, "", ("127.0.0.1", 443))]
    monkeypatch.setattr(providers.socket, "getaddrinfo", resolve)
    context = replace(run_context, deadline=monotonic() + 0.04)
    started = monotonic()
    try:
        with pytest.raises(EngineError) as error:
            providers._resolve("llm.example", 443, context)
        assert error.value.code == Code.DEADLINE
        assert monotonic() - started < 0.6
    finally:
        release.set()
        assert finished.wait(0.5)


def test_compressed_response_is_rejected_before_read(engine_factory, http_fixture):
    options, connections, _ = http_fixture
    options["headers"]["Content-Encoding"] = "gzip"
    system = engine_factory([])
    system.llm.transport = providers.HttpsTransport()
    with pytest.raises(EngineError) as error:
        system.llm.generate_response([{"role": "user", "content": "内容"}])
    assert error.value.code == Code.INVALID_RESULT
    assert connections[0].response.body.tell() == 0


@pytest.mark.parametrize("index", [False, 0.0, 1, None])
def test_embedding_index_is_not_coerced(engine_factory, index):
    system = engine_factory([])
    system.transport.embedding_response = {"data": [{"index": index, "embedding": [1, 0]}]}
    with pytest.raises(EngineError):
        system.embedding.embed("内容")


@pytest.mark.parametrize("change", ["finish", "refusal", "tools", "index", "usage", "model"])
def test_malformed_completion_is_audited_as_failure(engine_factory, change):
    response = {"choices": [{"index": 0, "finish_reason": "stop", "message": {"role": "assistant", "content": '{"facts":[]}'}}]}
    if change == "finish":
        response["choices"][0]["finish_reason"] = "length"
    elif change == "refusal":
        response["choices"][0]["message"]["refusal"] = "拒绝"
    elif change == "tools":
        response["choices"][0]["message"]["tool_calls"] = [{"name": "不能执行"}]
    elif change == "index":
        response["choices"][0]["index"] = False
    elif change == "usage":
        response["usage"] = {"prompt_tokens": 2, "completion_tokens": 3, "total_tokens": 9}
    else:
        response["model"] = "other-model"
    system = engine_factory([json.dumps(response).encode()])
    with pytest.raises(EngineError):
        system.llm.generate_response([{"role": "user", "content": "内容"}])
    assert system.audit.calls[-1]["state"] == "FAILED"


def test_connection_abort_closes_active_socket_and_prevents_late_registration(run_context):
    actions = []
    class Socket:
        def shutdown(self, how):
            actions.append("shutdown")
        def close(self):
            actions.append("close")
    connection = providers._BoundedHttpsConnection("llm.example", 443, run_context, ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT))
    connection._register(Socket())
    connection.abort()
    assert actions == ["shutdown", "close"]
    with pytest.raises(TimeoutError):
        connection._register(Socket())
    assert actions == ["shutdown", "close", "close"]


def test_endpoint_normalizes_idna_and_default_ports():
    endpoint = Endpoint("https://例子.测试:443/v1/", "local-model", "test-secret", ("https://例子.测试/",))
    assert endpoint.target("embeddings") == "https://xn--fsqu00a.xn--0zwm56d/v1/embeddings"
    with pytest.raises(EngineError):
        Endpoint("https://llm.example/v1", "model", "test-secret", ("https://LLM.example", "https://llm.example:443/"))


@pytest.mark.parametrize("framing", ["short", "invalid-length", "ambiguous"])
def test_incomplete_or_ambiguous_http_framing_is_not_success(engine_factory, http_fixture, framing):
    options, connections, _ = http_fixture
    if framing == "short":
        options["headers"]["Content-Length"] = str(len(options["body"]) + 1)
    elif framing == "invalid-length":
        options["headers"]["Content-Length"] = "not-a-number"
    else:
        options["headers"]["Content-Length"] = str(len(options["body"]))
        options["headers"]["Transfer-Encoding"] = "chunked"
    system = engine_factory([])
    system.llm.transport = providers.HttpsTransport()
    with pytest.raises(EngineError) as error:
        system.llm.generate_response([{"role": "user", "content": "内容"}])
    assert error.value.code == Code.INVALID_RESULT
    assert connections[0].response.closed
    assert system.audit.calls[-1]["state"] == "FAILED"
