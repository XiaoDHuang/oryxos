"""重新生成 build-manifest.json：所有摘要按当前文件实况重算，不保留任何无法核验的字段。"""

import hashlib
import json
import argparse
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# 基础镜像与工具版本来自部署契约固定值，digest 由部署方拉取时按此核验。
PINNED = {
    "platform": "linux/amd64",
    "python_image": "docker.io/library/python:3.12.14-slim@sha256:2fe5997d249a808b8eeea52c58a1dbffbba28754dc11699ef5c029f2d818ce79",
    "python_index_digest": "sha256:e5c9fa26ffb76e11e0f054f30dc2523a2f9693f0c36c0cf1e39b27e152d899fc",
    "postgres_image": "docker.io/pgvector/pgvector:0.8.6-pg17-trixie@sha256:71f806616c90e1de182cacfae8ac1f9ef00f0f34ee8c53b9042495db8689ccf2",
    "postgres_index_digest": "sha256:724a4041afdb1750446e3f6b5cfa8f3b0ac5a2cf538ddfa6bfee4f94c2fa85c6",
    "uv_version": "0.10.11",
}

HASHED_FILES = {
    "dockerfile_sha256": "Dockerfile",
    "compose_sha256": "compose.yaml",
    "dockerignore_sha256": ".dockerignore",
    "entrypoint_sha256": "scripts/container-entrypoint.sh",
    "prepare_image_context_sha256": "scripts/prepare_image_context.sh",
    "postgres_init_sha256": "scripts/postgres-init/00-create-app-role.sh",
    "runtime_requirements_sha256": "build/runtime-requirements.txt",
    "migration_sha256": "migrations/001_initial.sql",
    "pyproject_sha256": "pyproject.toml",
    "uv_lock_sha256": "uv.lock",
}

SOURCE_ROOTS = ("src", "migrations", "scripts", "postgres", "vendor/sdk-lock.json")
SOURCE_FILES = ("pyproject.toml", ".python-version", "Dockerfile", "compose.yaml", ".dockerignore")


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def image_evidence(metadata, inspected, source_sha256):
    try:
        digest = metadata["containerimage.digest"]
        config = metadata.get("containerimage.config.digest")
        if config is None:
            descriptor = metadata["containerimage.descriptor"]
            if (descriptor["digest"] != digest
                    or descriptor["mediaType"] != "application/vnd.oci.image.manifest.v1+json"
                    or descriptor["platform"] != {"architecture": "amd64", "os": "linux"}):
                raise ValueError()
            config = digest
        if (not re.fullmatch(r"sha256:[0-9a-f]{64}", digest)
                or not re.fullmatch(r"sha256:[0-9a-f]{64}", config)
                or not isinstance(inspected, list) or len(inspected) != 1):
            raise ValueError()
        image = inspected[0]
        if (image["Id"] != config or image["Os"] != "linux" or image["Architecture"] != "amd64"
                or image["Config"]["Labels"]["org.oryxos.source-sha256"] != source_sha256):
            raise ValueError()
        return {"status": "built_local", "adapter_image_digest": digest, "adapter_image_id": config}
    except (KeyError, TypeError, ValueError):
        raise ValueError("镜像构建证据与当前源码不匹配") from None


def postgres_image_evidence(metadata, inspected):
    # 派生PG镜像只移除gosu，不携带适配器源码标签；绑定digest/平台/身份即可。
    try:
        digest = metadata["containerimage.digest"]
        config = metadata.get("containerimage.config.digest")
        if config is None:
            descriptor = metadata["containerimage.descriptor"]
            if (descriptor["digest"] != digest
                    or descriptor["mediaType"] != "application/vnd.oci.image.manifest.v1+json"
                    or descriptor["platform"] != {"architecture": "amd64", "os": "linux"}):
                raise ValueError()
            config = digest
        if (not re.fullmatch(r"sha256:[0-9a-f]{64}", digest)
                or not re.fullmatch(r"sha256:[0-9a-f]{64}", config)
                or not isinstance(inspected, list) or len(inspected) != 1):
            raise ValueError()
        image = inspected[0]
        if image["Id"] != config or image["Os"] != "linux" or image["Architecture"] != "amd64":
            raise ValueError()
        return {"postgres_runtime_image_digest": digest, "postgres_runtime_image_id": config}
    except (KeyError, TypeError, ValueError):
        raise ValueError("派生PG镜像构建证据无效") from None


def source_files():
    files = []
    for item in SOURCE_ROOTS:
        path = ROOT / item
        if path.is_file():
            files.append(path)
            continue
        for child in sorted(path.rglob("*")):
            if child.is_file() and "__pycache__" not in child.parts:
                files.append(child)
    files.extend(ROOT / name for name in SOURCE_FILES)
    return sorted(set(files))


def main():
    parser = argparse.ArgumentParser(description="绑定锁图、源码与真实本地镜像构建证据")
    parser.add_argument("--build-metadata", type=Path)
    parser.add_argument("--image-inspect", type=Path)
    parser.add_argument("--pg-build-metadata", type=Path)
    parser.add_argument("--pg-image-inspect", type=Path)
    args = parser.parse_args()
    if bool(args.build_metadata) != bool(args.image_inspect):
        raise SystemExit("镜像元数据与inspect证据必须同时提供")
    if bool(args.pg_build_metadata) != bool(args.pg_image_inspect):
        raise SystemExit("派生PG镜像元数据与inspect证据必须同时提供")
    lock = json.loads((ROOT / "vendor/sdk-lock.json").read_text(encoding="utf-8"))
    wheel = ROOT / "vendor" / lock["wheel"]
    if sha256(wheel) != lock["wheel_sha256"]:
        raise SystemExit("受控SDK产物与sdk-lock摘要不匹配")

    lines = [f"{sha256(path)}  {path.relative_to(ROOT).as_posix()}" for path in source_files()]
    source_manifest = ROOT / "build/source-manifest.txt"
    source_manifest.parent.mkdir(parents=True, exist_ok=True)
    source_manifest.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")

    manifest = {
        "schema": 1,
        # 镜像尚未按当前源码重建：digest 只能在构建后以T049/T072证据回填，不保留过期值。
        "status": "authored_pending_image_build",
        "adapter_image_digest": None,
        "sdk_version": lock["version"],
        "sdk_wheel_sha256": lock["wheel_sha256"],
        "source_manifest_sha256": sha256(source_manifest),
        **PINNED,
        **{field: sha256(ROOT / relative) for field, relative in HASHED_FILES.items()},
    }
    if args.build_metadata:
        metadata = json.loads(args.build_metadata.read_text(encoding="utf-8"))
        inspected = json.loads(args.image_inspect.read_text(encoding="utf-8"))
        manifest.update(image_evidence(metadata, inspected, manifest["source_manifest_sha256"]))
        manifest.update(build_metadata_sha256=sha256(args.build_metadata),
                        image_inspect_sha256=sha256(args.image_inspect))
    if args.pg_build_metadata:
        metadata = json.loads(args.pg_build_metadata.read_text(encoding="utf-8"))
        inspected = json.loads(args.pg_image_inspect.read_text(encoding="utf-8"))
        manifest.update(postgres_image_evidence(metadata, inspected))
        manifest.update(pg_build_metadata_sha256=sha256(args.pg_build_metadata),
                        pg_image_inspect_sha256=sha256(args.pg_image_inspect))
    output = ROOT / "build-manifest.json"
    output.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps({"status": manifest["status"], "source_files": len(lines)}, sort_keys=True))


if __name__ == "__main__":
    main()
