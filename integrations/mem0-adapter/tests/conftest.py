"""在测试收集前隔离SDK导入副作用，默认禁止测试访问网络。"""

import os
import json
import socket
import sys
from copy import deepcopy
from time import monotonic
from types import SimpleNamespace
from uuid import uuid4
from tempfile import TemporaryDirectory

import pytest

_memory_directory = TemporaryDirectory(prefix="oryx-mem0-test-")
os.environ["MEM0_TELEMETRY"] = "false"
os.environ["MEM0_DIR"] = _memory_directory.name
_network_guard = pytest.MonkeyPatch()
_original_connect = socket.socket.connect
_original_connect_ex = socket.socket.connect_ex
_original_create_connection = socket.create_connection
_original_getaddrinfo = socket.getaddrinfo


def _reject_network(*args, **kwargs):
    raise AssertionError("测试未批准外部网络访问")


def _loopback_only(original):
    """Windows 的进程内 socketpair 经回环 TCP 实现；只放行 loopback，其余目标仍拒绝。"""

    def guarded(self, address, *args, **kwargs):
        host = address[0] if isinstance(address, tuple) and address else address
        if isinstance(host, bytes):
            host = host.decode("ascii", "replace")
        if not (isinstance(host, str) and host.startswith("127.") or host in ("::1", "localhost")):
            _reject_network()
        return original(self, address, *args, **kwargs)

    return guarded


@pytest.fixture
def inprocess_asgi():
    """仅让Starlette创建本进程事件循环管道（Windows 下经 loopback TCP），测试运行时没有外部客户端。"""
    _network_guard.setattr(socket.socket, "connect", _loopback_only(_original_connect))
    _network_guard.setattr(socket.socket, "connect_ex", _loopback_only(_original_connect_ex))
    yield
    _network_guard.setattr(socket.socket, "connect", _reject_network)
    _network_guard.setattr(socket.socket, "connect_ex", _reject_network)


def pytest_sessionstart(session):
    if sys.version_info[:3] != (3, 12, 14):
        raise pytest.UsageError("测试要求计划锁定的Python 3.12.14")
    if "mem0" in sys.modules:
        raise pytest.UsageError("SDK不得早于隔离配置导入")
    # 同时覆盖收集期导入，避免只在测试函数开始后拦截。
    _network_guard.setattr(socket.socket, "connect", _reject_network)
    _network_guard.setattr(socket.socket, "connect_ex", _reject_network)
    _network_guard.setattr(socket, "create_connection", _reject_network)
    _network_guard.setattr(socket, "getaddrinfo", _reject_network)


def pytest_sessionfinish(session, exitstatus):
    _network_guard.undo()
    _memory_directory.cleanup()


WORKSPACE_ID = "11111111-1111-4111-8111-111111111111"



OPERATION_ID = "22222222-2222-4222-8222-222222222222"


class RecordingAudit:
    def __init__(self):
        self.calls = []
        self.reject_begin = False
        self.reject_finish = False

    def begin(self, call):
        if self.reject_begin:
            raise RuntimeError("合成审计写入失败")
        call_id = str(uuid4())
        self.calls.append(dict(deepcopy(call), call_id=call_id))
        return call_id

    def finish(self, call_id, **result):
        if self.reject_finish:
            raise RuntimeError("合成审计完成失败")
        next(call for call in self.calls if call["call_id"] == call_id).update(deepcopy(result))


class ScriptedTransport:
    def __init__(self, responses):
        self.responses = list(responses)
        self.requests = []
        self.embedding_response = None

    def post(self, endpoint, route, body, context):
        assert context.active_call is not None
        self.requests.append({"route": route, "body": json.loads(body), "bytes": len(body)})
        if route == "embeddings":
            value = self.embedding_response
            if value is None:
                value = {"data": [{"index": 0, "embedding": [1.0, 0.0]}], "model": endpoint.model}
        else:
            value = self.responses.pop(0)
            if isinstance(value, Exception):
                raise value
            if not isinstance(value, bytes):
                content = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)
                value = {"choices": [{"index": 0, "finish_reason": "stop", "message": {"role": "assistant", "content": content}}], "model": endpoint.model}
        return value if isinstance(value, bytes) else json.dumps(value, ensure_ascii=False).encode("utf-8")


class ReadonlyFixture:
    workspace_id = WORKSPACE_ID
    scope = "ARCHIVAL"
    revision = 3

    def __init__(self, points=()):
        self.points = {point.id: deepcopy(point) for point in points}
        self.reads = []
        self.write_count = 0

    def get(self, memory_id):
        self.reads.append(("get", memory_id))
        return deepcopy(self.points.get(memory_id))

    def search(self, vector, limit, overlay):
        self.reads.append(("search", tuple(vector), dict(overlay)))
        entries = dict(self.points)
        entries.update(deepcopy(dict(overlay)))
        return [deepcopy(point) for point in entries.values() if point is not None][:limit]

    def forbidden_write(self, *args, **kwargs):
        self.write_count += 1
        raise AssertionError("只读快照不能写业务库")

    insert = update = delete = reset = forbidden_write


@pytest.fixture
def run_context():
    from oryx_mem0.engine.staging import RunContext
    return RunContext(WORKSPACE_ID, OPERATION_ID, "ARCHIVAL", 3, 2, monotonic() + 20)


@pytest.fixture
def point_factory():
    from oryx_mem0.engine.staging import Point

    def build(content="项目使用Java17", memory_id="33333333-3333-4333-8333-333333333333", **changes):
        payload = {"user_id": WORKSPACE_ID, "agent_id": "ARCHIVAL", "scope": "ARCHIVAL",
                   "data": content, "hash": "旧内容摘要", "created_at": "2026-01-01T00:00:00+00:00",
                   "updated_at": "2026-01-02T00:00:00+00:00"}
        payload.update(changes)
        return Point(memory_id, payload, (1.0, 0.0), "44444444-4444-4444-8444-444444444444")
    return build


@pytest.fixture
def engine_factory(run_context):
    from oryx_mem0.audit.calls import CallAuditor
    from oryx_mem0.engine.providers import AuditedEmbedding, AuditedLlm, Endpoint
    from oryx_mem0.engine.staged_memory import StagedMemory

    def build(responses, points=(), context=None, sink=None):
        state = context or run_context
        snapshot = ReadonlyFixture(points)
        audit = sink or RecordingAudit()
        transport = ScriptedTransport(responses)
        auditor = CallAuditor(state, audit)
        llm = AuditedLlm(state, Endpoint("https://llm.example/v1", "local-llm", "llm-test-secret", ("https://llm.example",)), auditor, transport)
        embedding = AuditedEmbedding(state, Endpoint("https://embedding.example/v1", "local-embedding", "embedding-test-secret", ("https://embedding.example",)), auditor, transport)
        engine = StagedMemory(state, snapshot, llm, embedding)
        return SimpleNamespace(engine=engine, snapshot=snapshot, audit=audit, transport=transport, context=state, llm=llm, embedding=embedding)
    return build
