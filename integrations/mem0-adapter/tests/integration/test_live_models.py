"""真实本地模型黄金集：提炼/合并/替换/合法空事实/同义召回逐项核对预先指定事实。

语料为获准合成内容（tests/fixtures/memory-golden.json），只跑 harness --real-models 部署。
模型措辞存在正常方差，断言锚定语义要点（关键词/版本号/结构化结果计数），不接受无关内容充数。
"""

import json
import os
from pathlib import Path
import sys
from uuid import uuid4

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "docker"))
from harness import DATABASE, Deployment  # noqa: E402
from test_deployed_api import Api  # noqa: E402

pytestmark = pytest.mark.integration

FIXTURE = json.loads(
    (Path(__file__).resolve().parents[1] / "fixtures/memory-golden.json").read_text(encoding="utf-8"))


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


def _save(api, content):
    operation, (status, receipt) = api.save(content, "ARCHIVAL")
    assert status == 200, receipt
    return operation, receipt


def _recall(api, query):
    status, receipt = api.request("PUT", api.root + "/operations/" + str(uuid4()),
        {"kind": "RECALL", "query": query})
    assert status == 200 and receipt["state"] == "COMMITTED", receipt
    return receipt["items"]


def _archival(api):
    return [item["content"] for item in api.entries("ARCHIVAL")]


def _hit(items, case):
    expect = case["expect_recall"]
    anchors = expect.get("anchor_any") or [expect["anchor"]]
    return any(any(anchor in item for anchor in anchors) for item in items)


def test_golden_extraction_merge_replace_empty_and_synonym_recall(deployed_real):
    api = deployed_real
    cases = {case["name"]: case for case in FIXTURE["cases"]}

    # 提炼：目标事实成为有效归档且可召回
    case = cases["extract_preference"]
    _, receipt = _save(api, case["input"])
    assert receipt["outcome"] == "CHANGED" and receipt["action_counts"]["ADD"] >= 1
    assert _hit([item["content"] for item in _recall(api, case["expect_recall"]["query"])], case)

    # 替换：旧值不再当前有效，历史双方可溯，召回命中新值锚点
    case = cases["replace_outdated_fact"]
    _save(api, case["inputs"][0])
    before = _archival(api)
    assert any("Java" in item and "17" in item for item in before), before
    _save(api, case["inputs"][1])
    after = _archival(api)
    assert _hit([item["content"] for item in _recall(api, case["expect_recall"]["query"])], case), after
    assert not any("Java" in item and "17" in item and "21" not in item for item in after), after
    events = api.deployment.sql(
        "SELECT json_agg(event ORDER BY revision) FROM oryx_memory.memory_versions;")
    assert "ADD" in events and ("UPDATE" in events or "DELETE" in events), events

    # 合并：同义重复输入不得再产出重复当前条目
    case = cases["merge_duplicate"]
    _, first = _save(api, case["inputs"][0])
    first_count = len(_archival(api))
    _, second = _save(api, case["inputs"][1])
    after_merge = _archival(api)
    assert len(after_merge) == first_count, (first, second, after_merge)
    assert _hit([item["content"] for item in _recall(api, case["expect_recall"]["query"])], case)

    # 合法空事实：无目标事实的输入是 NOOP，不产生新版本
    case = cases["legal_empty_facts"]
    versions_before = int(api.deployment.sql(
        "SELECT count(*) FROM oryx_memory.memory_versions;").strip())
    _, noop = _save(api, case["input"])
    assert noop["outcome"] == "NOOP", noop
    assert int(api.deployment.sql(
        "SELECT count(*) FROM oryx_memory.memory_versions;").strip()) == versions_before

    # 同义召回：不含平台名的同义查询仍能命中
    case = cases["synonym_recall"]
    _save(api, case["input"])
    assert _hit([item["content"] for item in _recall(api, case["expect_recall"]["query"])], case)
