"""已修复来源不能变成同名包或其他告警的通用豁免。"""

from copy import deepcopy
from types import SimpleNamespace

import pytest

import audit_dependencies
import sdk_build


@pytest.fixture
def proof():
    return sdk_build.verify_installed()


def report():
    return {"dependencies": [
        {"name": "mem0ai", "version": "1.0.11", "vulns": [
            {"id": "PYSEC-2026-2636", "aliases": ["CVE-2026-7597", "GHSA-xqxw-r767-67m7"]}
        ]},
        {"name": "pytest", "version": "9.1.1", "vulns": []},
    ]}


PACKAGES = {"mem0ai": "1.0.11", "pytest": "9.1.1"}


def test_only_fixed_finding_gets_source_bound_disposition(proof):
    original = report()
    before = deepcopy(original)
    result = audit_dependencies.classify_report(original, PACKAGES, proof)
    assert len(result["fixed_by_backport"]) == 1
    assert result["unresolved"] == []
    assert result["proof"]["wheel_sha256"] == sdk_build.load_lock()["wheel_sha256"]
    assert original == before


@pytest.mark.parametrize("package,identifier,aliases", [
    ("mem0ai", "CVE-2099-0001", []),
    ("pytest", "PYSEC-2026-2636", ["CVE-2026-7597"]),
    ("mem0ai", "PYSEC-2026-2636", ["CVE-2026-7597", "CVE-2099-0001"]),
])
def test_other_findings_still_fail(proof, package, identifier, aliases):
    data = report()
    for dependency in data["dependencies"]:
        dependency["vulns"] = ([{"id": identifier, "aliases": aliases}] if dependency["name"] == package else [])
    result = audit_dependencies.classify_report(data, PACKAGES, proof)
    assert len(result["unresolved"]) == 1
    assert result["fixed_by_backport"] == []


@pytest.mark.parametrize("change", ["missing", "duplicate", "skip", "wrong-version", "extra", "bad-vulns"])
def test_incomplete_or_invalid_scan_is_rejected(proof, change):
    data = report()
    if change == "missing":
        data["dependencies"].pop()
    elif change == "duplicate":
        data["dependencies"].append(deepcopy(data["dependencies"][0]))
    elif change == "skip":
        data["dependencies"][0]["skip_reason"] = "无法审计"
    elif change == "wrong-version":
        data["dependencies"][0]["version"] = "2.0.0"
    elif change == "extra":
        data["dependencies"].append({"name": "unknown", "version": "1", "vulns": []})
    else:
        data["dependencies"][0]["vulns"] = None
    with pytest.raises(ValueError):
        audit_dependencies.classify_report(data, PACKAGES, proof)


def test_wrong_build_cannot_use_fixed_disposition(proof):
    proof["wheel_sha256"] = "0" * 64
    with pytest.raises(ValueError):
        audit_dependencies.classify_report(report(), PACKAGES, proof)


def test_unlocked_dependency_version_is_rejected(proof, monkeypatch):
    distribution = SimpleNamespace(metadata={"Name": "pytest"}, version="0.0.0")
    monkeypatch.setattr(audit_dependencies.importlib.metadata, "distributions", lambda: [distribution])
    with pytest.raises(ValueError, match="uv锁定版本"):
        audit_dependencies.installed_packages(proof)


def test_missing_required_dependencies_are_rejected(proof, monkeypatch):
    distribution = SimpleNamespace(metadata={"Name": "mem0ai"}, version=proof["version"])
    monkeypatch.setattr(audit_dependencies.importlib.metadata, "distributions", lambda: [distribution])
    with pytest.raises(ValueError, match="必需包"):
        audit_dependencies.installed_packages(proof)
