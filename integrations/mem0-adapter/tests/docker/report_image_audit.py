"""保留全部镜像发现；只有已验证安装字节的指定SDK回移项可以处置。"""

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import sdk_build


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def summarize(report, proof, sdk_exception):
    if not isinstance(report.get("Results"), list):
        raise ValueError("镜像扫描结果缺失")
    fixed, remaining, secret_count = [], [], 0
    for result in report["Results"]:
        secret_count += len(result.get("Secrets") or [])
        for vulnerability in result.get("Vulnerabilities") or []:
            item = {key: vulnerability.get(key) for key in
                    ("PkgName", "VulnerabilityID", "InstalledVersion", "FixedVersion", "Severity", "Status")}
            allowed = (sdk_exception and item["PkgName"] == "mem0ai"
                       and item["InstalledVersion"] == proof["version"]
                       and item["VulnerabilityID"] in sdk_build.load_lock()["fixed_ids"])
            (fixed if allowed else remaining).append(item)
    return {
        "artifact_id": report.get("ArtifactID"), "package_findings": len(fixed) + len(remaining),
        "fixed_by_verified_backport": fixed, "unresolved": remaining, "secret_findings": secret_count,
        "severity_counts": dict(Counter(item["Severity"] for item in fixed + remaining)),
    }


def load_dispositions(path):
    ledger = read(path)
    if ledger.get("schema") != 1 or not isinstance(ledger.get("entries"), list):
        raise ValueError("处置台账格式非法")
    entries = {}
    for entry in ledger["entries"]:
        if (not isinstance(entry, dict)
                or entry.get("disposition") not in ("vendor_assessed", "not_applicable_configuration")
                or not isinstance(entry.get("reason"), str) or len(entry["reason"]) < 20
                or not isinstance(entry.get("references"), list) or not entry["references"]
                or any(not isinstance(ref, str) or not ref.startswith("https://") for ref in entry["references"])
                or not isinstance(entry.get("reviewed_at"), str) or not entry["reviewed_at"]):
            raise ValueError("处置记录缺少理由、来源或复核日期")
        key = (entry.get("image"), entry.get("id"), entry.get("package"))
        if key in entries or any(part is None for part in key):
            raise ValueError("处置记录重复或键缺失")
        entries[key] = entry
    return entries


def apply_dispositions(report, image, entries):
    covered, used = [], set()
    for item in report["unresolved"]:
        key = (image, item["VulnerabilityID"], item["PkgName"])
        entry = entries.get(key)
        if entry is None:
            raise ValueError(f"存在无处置记录的镜像发现：{key}")
        if entry.get("installed_version") != item["InstalledVersion"]:
            raise ValueError(f"处置记录版本与镜像实际不一致：{key}")
        used.add(key)
        covered.append({**item, "disposition": entry["disposition"], "reviewed_at": entry["reviewed_at"]})
    if used != set(entries) or any(key[0] != image for key in entries):
        raise ValueError("台账包含过期或不属于本镜像的处置记录")
    return covered


def main():
    parser = argparse.ArgumentParser(description="T049本地镜像扫描门禁，不使用全局忽略")
    parser.add_argument("--adapter", required=True, type=Path)
    parser.add_argument("--postgres", required=True, type=Path)
    parser.add_argument("--sdk-proof", required=True, type=Path)
    parser.add_argument("--database-metadata", required=True, type=Path)
    parser.add_argument("--dispositions", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    manifest = read(ROOT / "build-manifest.json")
    if manifest["status"] != "built_local":
        raise ValueError("当前源码未绑定实际镜像")
    proof = read(args.sdk_proof)
    if proof != sdk_build.verify_installed():
        raise ValueError("镜像内SDK字节证明与获准回移构建不一致")
    adapter, postgres = read(args.adapter), read(args.postgres)
    labels = adapter["Metadata"]["ImageConfig"]["config"]["Labels"]
    if labels.get("org.oryxos.source-sha256") != manifest["source_manifest_sha256"]:
        raise ValueError("扫描镜像与当前源码摘要不一致")
    reports = {"adapter": summarize(adapter, proof, True), "postgres": summarize(postgres, proof, False)}
    dispositions = {}
    if args.dispositions:
        dispositions = load_dispositions(args.dispositions)
        for image in ("adapter", "postgres"):
            scoped = {key: entry for key, entry in dispositions.items() if key[0] == image}
            reports[image]["dispositioned"] = apply_dispositions(reports[image], image, scoped)
            reports[image]["unresolved"] = []
    result = {
        "status": "blocked" if any(item["unresolved"] or item["secret_findings"] for item in reports.values()) else "passed",
        "adapter_image_digest": manifest["adapter_image_digest"], "postgres_image": manifest["postgres_image"],
        "postgres_runtime_image_digest": manifest.get("postgres_runtime_image_digest"),
        "source_manifest_sha256": manifest["source_manifest_sha256"], "sdk_proof": proof,
        "scanner": "Trivy 0.73.0", "database_metadata": read(args.database_metadata), "reports": reports,
        "evidence_sha256": {name: sha256(path) for name, path in
            (("adapter", args.adapter), ("postgres", args.postgres), ("sdk_proof", args.sdk_proof))},
    }
    if args.dispositions:
        result["evidence_sha256"]["dispositions"] = sha256(args.dispositions)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": result["status"], "unresolved": {name: len(item["unresolved"]) for name, item in reports.items()},
                      "fixed_by_backport": len(reports["adapter"]["fixed_by_verified_backport"]),
                      "dispositioned": {name: len(item.get("dispositioned", [])) for name, item in reports.items()}}, ensure_ascii=False))
    return 0 if result["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
