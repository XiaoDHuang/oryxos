"""真实本地模型（Ollama qwen2.5 + bge-m3）端到端冒烟：保存→原子历史→重启→回忆。

只针对 harness --real-models 建立的隔离部署运行，模型响应一律来自真实推理，不允许合成对端。
"""

import json
import os
from pathlib import Path
import sys
from uuid import uuid4

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "docker"))
from harness import DATABASE, Deployment  # noqa: E402

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "docker"))
from test_deployed_api import Api  # noqa: E402

pytestmark = pytest.mark.integration


@pytest.fixture
def deployed_real():
    directory = os.environ.get("ORYX_MEM0_DOCKER_TEST_DIR")
    if not directory:
        pytest.fail("必须显式提供本次真实模型隔离部署目录")
    deployment = Deployment(directory, os.environ.get("ORYX_DOCKER", "docker"))
    if not deployment.state.get("real_models"):
        pytest.fail("该部署不是真实模型模式，拒绝用合成对端冒充真实模型验收")
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
    yield api
    deployment.save_logs()


def test_real_model_save_history_restart_and_recall(deployed_real):
    content = "我是后端工程师林一，主力语言是Java，服务框架用Spring Boot，部署目标是Kubernetes，饮品偏好只喝美式咖啡。"
    # 真实提炼+动作选择+嵌入受协议30秒期限约束；PUT同步等待终态。
    operation, (status, saved) = deployed_real.save(content, "ARCHIVAL")
    assert status == 200, saved
    assert saved["state"] == "COMMITTED" and saved["history_complete"] is True
    assert saved["outcome"] == "CHANGED" and saved["action_counts"]["ADD"] >= 1

    # 原子历史与逐调用审计：模型调用必须留痕，正文投影与版本同生共死。
    versions = int(deployed_real.deployment.sql(
        "SELECT count(*) FROM oryx_memory.memory_versions;").strip())
    assert versions == saved["action_counts"]["ADD"]
    audits = deployed_real.deployment.sql(
        "SELECT kind, state, count(*) FROM oryx_memory.memory_call_audits"
        " WHERE operation_id='" + operation + "' GROUP BY kind, state ORDER BY kind;")
    audit_rows = {tuple(row.split("|")[:2]): int(row.split("|")[2]) for row in audits.splitlines()}
    assert audit_rows.get(("LLM", "COMPLETED")) == 2
    assert audit_rows.get(("EMBEDDING", "COMPLETED"), 0) >= saved["action_counts"]["ADD"]
    assert all(state == "COMPLETED" for (_, state) in audit_rows)

    # 重启适配器后，真实持久状态仍可回忆（新客户端、新操作ID）。
    deployed_real.deployment.compose("restart", "adapter")
    deployed_real.ready()
    status, persisted = deployed_real.request("GET", deployed_real.root + "/operations/" + operation)
    assert status == 200 and persisted["state"] == "COMMITTED"

    status, recalled = deployed_real.request("PUT", deployed_real.root + "/operations/" + str(uuid4()),
        {"kind": "RECALL", "query": "林一的主力开发语言是什么"})
    assert status == 200 and recalled["state"] == "COMMITTED"
    assert recalled["truncated_by_bytes"] is False and recalled["returned_count"] == len(recalled["items"])
    assert recalled["returned_count"] >= 1, recalled
    assert any("Java" in item["content"] for item in recalled["items"])

    # 嵌入语义召回而非关键词：同义查询应命中饮品偏好（模型可把正文规范化为其他语言表述）。
    status, drink = deployed_real.request("PUT", deployed_real.root + "/operations/" + str(uuid4()),
        {"kind": "RECALL", "query": "他喝什么饮料"})
    assert status == 200
    assert drink["items"], drink
    top = drink["items"][0]["content"].lower()
    assert any(term in top for term in ("americano", "coffee", "咖啡", "美式")), drink["items"]
