"""真实镜像入口、TLS、SDK、受限角色PG；只有模型返回值为显式合成对端。"""

import hashlib
import base64
import json
import os
import time
from urllib.parse import quote
from uuid import uuid4

import pytest

from harness import DATABASE, Deployment

pytestmark = pytest.mark.integration
PREFIX = "/oryx-memory/v1"


class Api:
    def __init__(self, deployment):
        self.deployment = deployment
        self.ports = deployment.ports()
        self.workspace = deployment.state["workspace"]
        self.root = PREFIX + "/workspaces/" + self.workspace

    def request(self, method, path, value=None, authenticated=True, models=False, raw=None):
        headers = {"Content-Type": "application/json"}
        if authenticated:
            key = "control" if models else "client"
            headers["Authorization"] = "Bearer " + self.deployment.state["secrets"][key]
        body = raw if raw is not None else (None if value is None else json.dumps(value, ensure_ascii=False).encode())
        # 内部网络不发布应用端口；客户端进入同一容器以真实TLS请求loopback，不扩大出口。
        program = """
import base64,http.client,json,ssl,sys
request=json.load(sys.stdin)
tls=ssl.create_default_context(cafile=request['ca'])
connection=http.client.HTTPSConnection('127.0.0.1',request['port'],context=tls,timeout=35)
try:
    body=None if request['body'] is None else base64.b64decode(request['body'])
    connection.request(request['method'],request['path'],body,request['headers'])
    response=connection.getresponse()
    content=response.read(1024*1024+1)
    assert len(content)<=1024*1024
    print(json.dumps({'status':response.status,'body':json.loads(content)}))
finally:
    connection.close()
"""
        service = "models" if models else "adapter"
        payload = {"port": 9443 if models else 8443, "method": method, "path": path,
            "headers": headers, "body": None if body is None else base64.b64encode(body).decode(),
            "ca": "/certs/ca.pem" if models else "/run/secrets/test_ca"}
        response = json.loads(self.deployment.compose("exec", "-T", service, "python", "-c", program,
                              text=json.dumps(payload)))
        return response["status"], response["body"]

    def ready(self):
        until = time.monotonic() + 20
        while time.monotonic() < until:
            try:
                status, _ = self.request("GET", PREFIX + "/capabilities")
                if status == 200:
                    return
            except (OSError, ValueError, RuntimeError):
                pass
            time.sleep(0.2)
        raise AssertionError("隔离适配器未按时就绪")

    def models(self, responses=None):
        body = {} if responses is None else {"responses": responses}
        status, result = self.request("POST", "/fixture", body, models=True)
        assert status == 200
        return result

    def save(self, content, scope="CORE", operation=None):
        operation = operation or str(uuid4())
        return operation, self.request("PUT", self.root + "/operations/" + operation,
            {"kind": "SAVE", "scope": scope, "content": content})

    def save_many_core(self, contents):
        # 一次进入测试容器，仍逐条发出独立HTTPS/UUID保存，避免把CLI进程耗时当服务性能。
        requests = [{"id": str(uuid4()), "content": content} for content in contents]
        program = """
import http.client,json,ssl,sys
payload=json.load(sys.stdin)
tls=ssl.create_default_context(cafile='/run/secrets/test_ca')
statuses=[]
for item in payload['requests']:
    connection=http.client.HTTPSConnection('127.0.0.1',8443,context=tls,timeout=35)
    try:
        body=json.dumps({'kind':'SAVE','scope':'CORE','content':item['content']}).encode()
        connection.request('PUT',payload['root']+'/operations/'+item['id'],body,
            {'Content-Type':'application/json','Authorization':'Bearer '+payload['token']})
        response=connection.getresponse()
        result=json.loads(response.read(1024*1024+1))
        statuses.append([response.status,result.get('state'),result.get('history_complete')])
    finally:
        connection.close()
print(json.dumps(statuses))
"""
        payload = {"root": self.root, "token": self.deployment.state["secrets"]["client"], "requests": requests}
        statuses = json.loads(self.deployment.compose("exec", "-T", "adapter", "python", "-c", program,
                                                   text=json.dumps(payload)))
        assert statuses == [[200, "COMMITTED", True]] * len(contents)

    def entries(self, scope, snapshot=None, size=100):
        if snapshot is None:
            status, snapshot = self.request("POST", self.root + "/snapshots", {})
            assert status == 200
        items, cursor = [], None
        while True:
            path = self.root + "/snapshots/" + quote(snapshot["snapshot_id"], safe="") + "/entries?scope=" + scope + "&page_size=" + str(size)
            if cursor:
                path += "&cursor=" + quote(cursor, safe="")
            status, page = self.request("GET", path)
            assert status == 200
            assert page["revision"] == snapshot["revision"] and page["scope"] == scope
            items.extend(page["items"])
            if page["complete"]:
                assert page["next_cursor"] is None
                assert len(items) == page["total_count"]
                return items
            assert page["items"] and page["next_cursor"] != cursor
            cursor = page["next_cursor"]


@pytest.fixture
def deployed():
    directory = os.environ.get("ORYX_MEM0_DOCKER_TEST_DIR")
    if not directory:
        pytest.fail("必须显式提供本次隔离Docker部署目录")
    deployment = Deployment(directory, os.environ.get("ORYX_DOCKER", "docker"))
    checks = deployment.sql("SELECT json_build_array(current_database(),current_user,rolsuper,"
        "current_setting('server_version_num'),shobj_description(d.oid,'pg_database'),"
        "(SELECT extversion FROM pg_extension WHERE extname='vector')) "
        "FROM pg_database d,pg_roles r WHERE d.datname=current_database() AND r.rolname=current_user;")
    assert json.loads(checks) == [DATABASE, "oryx_mem0_app", False, "170011", "ORYXOS_DISPOSABLE_TEST_DATABASE", "0.8.6"]
    deployment.compose("stop", "adapter")
    deployment.sql("DROP SCHEMA IF EXISTS oryx_memory CASCADE;")
    deployment.compose("up", "-d", "--no-build", "adapter")
    api = Api(deployment)
    api.ready()
    api.models([])
    yield api
    deployment.save_logs()


def test_core_is_durable_idempotent_and_never_calls_models(deployed):
    raw = '  原文é\n"\\😀  '
    operation, (status, first) = deployed.save(raw)
    assert status == 200 and first["history_complete"] is True and first["replayed"] is False
    expected = hashlib.sha256(("oryx-memory-v1\0" + deployed.workspace + "\0SAVE\0CORE\0" + raw).encode()).hexdigest()
    assert first["request_hash"] == expected
    _, (status, replay) = deployed.save(raw, operation=operation)
    assert status == 200 and replay["replayed"] is True
    assert replay["revision"] == first["revision"] and replay["affected_ids"] == first["affected_ids"]
    assert deployed.models()["calls"] == []
    assert [item["content"] for item in deployed.entries("CORE")] == [raw]
    assert deployed.deployment.sql("SELECT count(*) FROM oryx_memory.memory_versions;").strip() == "1"
    deployed.deployment.compose("restart", "adapter")
    deployed.ready()
    status, persisted = deployed.request("GET", deployed.root + "/operations/" + operation)
    assert status == 200 and persisted["request_hash"] == expected
    assert [item["content"] for item in deployed.entries("CORE")] == [raw]


def test_archive_real_sdk_updates_history_and_recall_audits(deployed):
    original, updated = "项目使用Java17", "项目升级Java21"
    deployed.models([{"facts": [original]}, {"memory": [{"event": "ADD", "text": original}]}])
    first_id, (status, first) = deployed.save(original, "ARCHIVAL")
    assert status == 200 and first["outcome"] == "CHANGED"
    expected_calls = len(deployed.models()["calls"])
    deployed.models([{"facts": [updated]}, {"memory": [{"event": "UPDATE", "id": "0", "text": updated, "old_memory": original}]}])
    second_id, (status, second) = deployed.save(updated, "ARCHIVAL")
    assert status == 200 and second["action_counts"]["UPDATE"] == 1 and second["history_complete"] is True
    expected_calls += len(deployed.models()["calls"])
    assert [item["content"] for item in deployed.entries("ARCHIVAL")] == [updated]
    history = json.loads(deployed.deployment.sql("SELECT json_agg(json_build_array(event,old_content,new_content) ORDER BY revision) FROM oryx_memory.memory_versions;"))
    assert history == [["ADD", None, original], ["UPDATE", original, updated]]
    deployed.models([{"facts": []}])
    _, (status, noop) = deployed.save("不包含新事实的输入", "ARCHIVAL")
    assert status == 200 and noop["outcome"] == "NOOP"
    expected_calls += len(deployed.models()["calls"])
    deployed.models([])
    status, recalled = deployed.request("PUT", deployed.root + "/operations/" + str(uuid4()), {"kind": "RECALL", "query": "项目Java版本"})
    assert status == 200 and [item["content"] for item in recalled["items"]] == [updated]
    assert recalled["returned_count"] == 1 and recalled["truncated_by_bytes"] is False
    expected_calls += len(deployed.models()["calls"])
    assert deployed.deployment.sql("SELECT count(*) FROM oryx_memory.memory_versions;").strip() == "2"
    audits = json.loads(deployed.deployment.sql("SELECT json_build_array(count(*),bool_and(state='COMPLETED'),bool_and(total_tokens IS NOT NULL)) FROM oryx_memory.memory_call_audits;"))
    assert expected_calls >= 6 and audits == [expected_calls, True, True]
    assert first_id != second_id


def test_failed_model_has_matching_persistent_failure(deployed):
    deployed.models(["not-json"])
    operation, (status, failed) = deployed.save("不能误报成功", "ARCHIVAL")
    assert status == 422
    assert failed["state"] == "FAILED" and failed["memory_effects_applied"] is False
    assert failed["error_code"] == "ENGINE_INVALID_RESULT"
    status, persisted = deployed.request("GET", deployed.root + "/operations/" + operation)
    assert status == 422 and persisted["request_hash"] == failed["request_hash"]
    assert deployed.deployment.sql("SELECT count(*) FROM oryx_memory.memory_current;").strip() == "0"


def test_conflicting_operation_id_is_not_overwritten(deployed):
    operation, (status, first) = deployed.save("第一条原文")
    assert status == 200
    _, (status, error) = deployed.save("冲突原文", operation=operation)
    assert status == 409 and error["error_code"] == "REQUEST_ID_CONFLICT"
    status, original = deployed.request("GET", deployed.root + "/operations/" + operation)
    assert status == 200 and original["request_hash"] == first["request_hash"]


def test_one_snapshot_reads_all_core_pages_without_model_calls(deployed):
    deployed.save_many_core(["完整核心-" + str(index) for index in range(103)])
    status, snapshot = deployed.request("POST", deployed.root + "/snapshots", {})
    assert status == 200 and snapshot["core_count"] == 103
    core = deployed.entries("CORE", snapshot, size=17)
    assert len(core) == 103 and len({item["memory_id"] for item in core}) == 103
    assert deployed.entries("ARCHIVAL", snapshot) == []
    assert deployed.models()["calls"] == []


def test_auth_scope_and_oversized_input_are_rejected_before_registration(deployed):
    status, _ = deployed.request("GET", PREFIX + "/capabilities", authenticated=False)
    assert status == 401
    status, _ = deployed.request("POST", PREFIX + "/workspaces/22222222-2222-4222-8222-222222222222/snapshots", {})
    assert status == 403
    _, (status, _) = deployed.save("before\x00after")
    assert status == 400
    status, _ = deployed.request("PUT", deployed.root + "/operations/" + str(uuid4()), raw=b"x" * (256 * 1024 + 1))
    assert status == 413
    assert deployed.deployment.sql("SELECT count(*) FROM oryx_memory.memory_operations;").strip() == "0"


def test_status_read_recovers_expired_owner_and_unfinished_audit(deployed):
    operation, owner, call = (str(uuid4()) for _ in range(3))
    raw = "expired synthetic input"
    digest = hashlib.sha256(("oryx-memory-v1\0" + deployed.workspace + "\0SAVE\0ARCHIVAL\0" + raw).encode()).hexdigest()
    deployed.deployment.sql(
        "INSERT INTO oryx_memory.memory_namespaces(workspace_id,revision) VALUES ('" + deployed.workspace + "',0);"
        "INSERT INTO oryx_memory.memory_operations(workspace_id,operation_id,kind,scope,request_hash,raw_input,"
        "state,baseline_revision,owner_token,deadline_at,created_at,started_at) VALUES ('" + deployed.workspace
        + "','" + operation + "','SAVE','ARCHIVAL','" + digest + "','" + raw + "','RUNNING',0,'" + owner
        + "',clock_timestamp()-interval '1 second',clock_timestamp()-interval '31 seconds',clock_timestamp()-interval '30 seconds');"
        "INSERT INTO oryx_memory.memory_call_audits(call_id,workspace_id,operation_id,call_index,kind,phase,"
        "provider,model,state,started_at,request_json) VALUES ('" + call + "','" + deployed.workspace + "','"
        + operation + "',1,'LLM','FACT_EXTRACTION','fixture','fixture-llm','STARTED',clock_timestamp()-interval '30 seconds','{}');")
    status, result = deployed.request("GET", deployed.root + "/operations/" + operation)
    assert status == 504 and result["state"] == "ABORTED"
    assert result["memory_effects_applied"] is False and result["request_hash"] == digest
    assert deployed.deployment.sql("SELECT state FROM oryx_memory.memory_call_audits;").strip() == "UNKNOWN"
    assert deployed.models()["calls"] == []


def test_runtime_is_nonroot_readonly_and_has_no_default_egress_route(deployed):
    container = deployed.deployment.compose("ps", "-q", "adapter").strip()
    data = json.loads(deployed.deployment.run(["inspect", container]))[0]
    assert data["Config"]["User"] == "65534:65534"
    assert data["HostConfig"]["ReadonlyRootfs"] is True
    assert "ALL" in data["HostConfig"]["CapDrop"]
    for network in data["NetworkSettings"]["Networks"]:
        detail = json.loads(deployed.deployment.run(["network", "inspect", network]))[0]
        assert detail["Internal"] is True
    program = "from pathlib import Path; rows=Path('/proc/net/route').read_text().splitlines()[1:]; print(any(row.split()[1]=='00000000' for row in rows))"
    assert deployed.deployment.compose("exec", "-T", "adapter", "python", "-c", program).strip() == "False"
