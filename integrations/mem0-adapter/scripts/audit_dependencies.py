"""保留原始扫描，只对来源验证成立的已回移补丁给出修复证明。"""

import argparse
import importlib.metadata
import json
import os
import re
import subprocess
import sys
import tomllib
import uuid
from datetime import datetime, timezone
from pathlib import Path

import sdk_build

FIXED_IDS = frozenset({"PYSEC-2026-2636", "CVE-2026-7597", "GHSA-xqxw-r767-67m7"})


def canonical_name(name):
    if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", name):
        raise ValueError("依赖名称非法")
    return re.sub(r"[-_.]+", "-", name).lower()


def installed_packages(proof):
    lock = tomllib.loads((sdk_build.ROOT / "uv.lock").read_text(encoding="utf-8"))
    locked_versions = {}
    for package in lock["package"]:
        locked_versions.setdefault(canonical_name(package["name"]), set()).add(package["version"])
    packages = {}
    for distribution in importlib.metadata.distributions():
        name = canonical_name(distribution.metadata["Name"])
        version = distribution.version
        if name in packages or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.+!_-]*", version):
            raise ValueError("安装依赖清单重复或版本非法")
        if version not in locked_versions.get(name, set()):
            raise ValueError("安装依赖与uv锁定版本不匹配")
        if name == "mem0ai":
            if version != proof["version"]:
                raise ValueError("SDK版本与来源证明不一致")
            # 本地补丁版本不得利用PyPI无此版本而跳过漏洞查询。
            version = proof["upstream_version"]
        packages[name] = version
    required = {"mem0ai", "fastapi", "uvicorn", "psycopg", "pytest", "pip-audit"}
    if not required <= packages.keys():
        raise ValueError("扫描依赖清单缺少组件必需包")
    return packages


def classify_report(report, packages, proof):
    if proof != sdk_build.verify_installed():
        raise ValueError("SDK来源证明不匹配")
    if set(sdk_build.load_lock()["fixed_ids"]) != FIXED_IDS:
        raise ValueError("不得扩大已批准告警处置范围")
    if not isinstance(report, dict) or not isinstance(report.get("dependencies"), list):
        raise ValueError("原始扫描报告格式非法")
    seen = set()
    fixed, unresolved = [], []
    for dependency in report["dependencies"]:
        if not isinstance(dependency, dict):
            raise ValueError("扫描依赖记录非法")
        name = canonical_name(dependency.get("name"))
        if name in seen or name not in packages or "skip_reason" in dependency:
            raise ValueError("扫描依赖重复、缺失或被跳过")
        seen.add(name)
        if dependency.get("version") != packages[name] or not isinstance(dependency.get("vulns"), list):
            raise ValueError("扫描依赖版本或结果非法")
        for vulnerability in dependency["vulns"]:
            if not isinstance(vulnerability, dict) or not isinstance(vulnerability.get("id"), str):
                raise ValueError("扫描告警格式非法")
            aliases = vulnerability.get("aliases", [])
            if not isinstance(aliases, list) or any(not isinstance(alias, str) for alias in aliases):
                raise ValueError("扫描告警别名非法")
            identifiers = {vulnerability["id"], *aliases}
            finding = {"package": name, "scanned_version": packages[name], "vulnerability": vulnerability}
            if name == "mem0ai" and packages[name] == proof["upstream_version"] and identifiers and identifiers <= FIXED_IDS:
                fixed.append(finding)
            else:
                unresolved.append(finding)
    if seen != set(packages):
        raise ValueError("原始扫描未覆盖全部安装依赖")
    return {"dependency_count": len(seen), "proof": proof, "fixed_by_backport": fixed, "unresolved": unresolved}


def run_audit(output_directory):
    proof = sdk_build.verify_installed()
    packages = installed_packages(proof)
    run_directory = output_directory / (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + str(uuid.uuid4()))
    run_directory.mkdir(parents=True, exist_ok=False)
    environment = dict(os.environ, PYTHONUTF8="1")
    tests = subprocess.run(
        [sys.executable, "-m", "pytest", "-q", "--basetemp", str(run_directory / "pytest"),
         "tests/unit/test_sdk_security.py", "tests/unit/test_sdk_build.py"],
        cwd=sdk_build.ROOT, env=environment, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=180,
    )
    (run_directory / "patch-regression.log").write_text(tests.stdout + tests.stderr, encoding="utf-8")
    if tests.returncode != 0:
        raise ValueError("补丁回归失败，不得处置告警")
    requirements = run_directory / "audited-requirements.txt"
    requirements.write_text("".join(f"{name}=={version}\n" for name, version in sorted(packages.items())), encoding="ascii")
    raw_path = run_directory / "raw-pip-audit.json"
    scan = subprocess.run(
        [sys.executable, "-m", "pip_audit", "--strict", "--no-deps", "--disable-pip", "--progress-spinner", "off",
         "--requirement", str(requirements), "--format", "json", "--output", str(raw_path)],
        cwd=sdk_build.ROOT, env=environment, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=300,
    )
    (run_directory / "raw-pip-audit.log").write_text(scan.stdout + scan.stderr, encoding="utf-8")
    if scan.returncode not in (0, 1) or not raw_path.is_file():
        raise ValueError("依赖扫描未正常完成")
    raw = json.loads(raw_path.read_text(encoding="utf-8"))
    result = classify_report(raw, packages, proof)
    count = len(result["fixed_by_backport"]) + len(result["unresolved"])
    if scan.returncode != (1 if count else 0):
        raise ValueError("扫描退出码与原始发现不一致")
    if installed_packages(proof) != packages:
        raise ValueError("扫描期间安装依赖发生变化")
    result.update(raw_exit_code=scan.returncode, raw_sha256=sdk_build.sha256(raw_path.read_bytes()),
                  regression_exit_code=tests.returncode, timestamp=datetime.now(timezone.utc).isoformat())
    (run_directory / "source-verified-audit.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"dependency_count": len(packages), "raw_findings": count,
                      "fixed_by_backport": len(result["fixed_by_backport"]), "unresolved": len(result["unresolved"]),
                      "evidence": str(run_directory)}, ensure_ascii=False))
    return 1 if result["unresolved"] else 0


def main():
    parser = argparse.ArgumentParser(description="完整依赖扫描与来源验证后的补丁处置")
    parser.add_argument("--output-dir", type=Path, default=sdk_build.ROOT.parents[1] / ".verification/007-memory-backends/dependency-audit")
    args = parser.parse_args()
    return run_audit(args.output_dir.resolve())


if __name__ == "__main__":
    sys.exit(main())
