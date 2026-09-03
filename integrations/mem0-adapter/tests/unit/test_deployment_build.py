"""部署清单必须绑定真实源码、锁文件和受限容器配置。"""

import hashlib
import json
from pathlib import Path
import copy

import pytest
import build_manifest

ROOT = Path(__file__).parents[2]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_manifest_entries():
    entries = []
    for line in (ROOT / "build/source-manifest.txt").read_text(encoding="utf-8").splitlines():
        recorded, _, name = line.partition("  ")
        path = (ROOT / name).resolve()
        assert path.is_relative_to(ROOT) and recorded == digest(path), name
        entries.append(name)
    assert len(entries) == len(set(entries))
    return entries


def test_build_manifest_binds_sources_and_pinned_images():
    value = json.loads((ROOT / "build-manifest.json").read_text(encoding="utf-8"))
    # 镜像digest只能来自按当前源码的真实构建；未重建前必须显式待建，不得保留过期digest。
    assert value["status"] in ("authored_pending_image_build", "built_local")
    if value["status"] == "built_local":
        assert value["adapter_image_digest"].startswith("sha256:")
    else:
        assert value["adapter_image_digest"] is None
    assert value["python_image"].endswith("sha256:2fe5997d249a808b8eeea52c58a1dbffbba28754dc11699ef5c029f2d818ce79")
    assert value["postgres_image"].endswith("sha256:71f806616c90e1de182cacfae8ac1f9ef00f0f34ee8c53b9042495db8689ccf2")
    if value["status"] == "built_local":
        # 派生PG镜像（移除gosu、应用发行版修复）也必须绑定实际构建digest。
        assert value["postgres_runtime_image_digest"].startswith("sha256:")
    assert value["pyproject_sha256"] == digest(ROOT / "pyproject.toml")
    assert value["uv_lock_sha256"] == digest(ROOT / "uv.lock")
    assert value["dockerfile_sha256"] == digest(ROOT / "Dockerfile")
    assert value["compose_sha256"] == digest(ROOT / "compose.yaml")
    assert value["dockerignore_sha256"] == digest(ROOT / ".dockerignore")
    assert value["entrypoint_sha256"] == digest(ROOT / "scripts/container-entrypoint.sh")
    assert value["prepare_image_context_sha256"] == digest(ROOT / "scripts/prepare_image_context.sh")
    assert value["postgres_init_sha256"] == digest(ROOT / "scripts/postgres-init/00-create-app-role.sh")
    assert value["migration_sha256"] == digest(ROOT / "migrations/001_initial.sql")
    entries = source_manifest_entries()
    assert value["source_manifest_sha256"] == digest(ROOT / "build/source-manifest.txt")
    for required in ("src/oryx_mem0/app.py", "src/oryx_mem0/runtime.py", "migrations/001_initial.sql",
                     "scripts/container-entrypoint.sh", "scripts/build_manifest.py", "vendor/sdk-lock.json"):
        assert required in entries


def test_container_is_nonroot_loopback_only_and_profile_gated():
    dockerfile = (ROOT / "Dockerfile").read_text(encoding="utf-8")
    compose = (ROOT / "compose.yaml").read_text(encoding="utf-8")
    assert "USER 65534:65534" in dockerfile and "FROM python:3.12.14-slim@sha256:" in dockerfile
    assert 'profiles: ["mem0"]' in compose
    assert '"127.0.0.1:${ORYX_MEM0_PORT:-8443}:8443"' in compose
    assert "internal: true" in compose and "read_only: true" in compose
    assert 'cap_drop: ["ALL"]' in compose and 'no-new-privileges:true' in compose
    # Postgres 只挂隔离内网；适配器到内网模型端点走独立 egress 网络，端口仍只绑 loopback。
    assert "mem0-egress" in compose and "docker-entrypoint-initdb.d:ro" in compose
    assert "POSTGRES_PASSWORD=" not in compose and "ADAPTER_LLM_API_KEY:" not in compose
    # PG 派生镜像：固定 trixie 上游、全程 999 运行、移除 gosu、应用发行版修复、无特权回补。
    pg_dockerfile = (ROOT / "postgres/Dockerfile").read_text(encoding="utf-8")
    assert "pgvector:0.8.6-pg17-trixie@sha256:" in pg_dockerfile and "apt-mark hold" in pg_dockerfile
    assert "rm -f /usr/local/bin/gosu" in pg_dockerfile and "USER 999:999" in pg_dockerfile
    assert 'user: "999:999"' in compose and "cap_add" not in compose


def test_build_installs_locked_wheels_inside_the_pinned_image():
    dockerfile = (ROOT / "Dockerfile").read_text(encoding="utf-8")
    assert "--require-hashes" in dockerfile and "--only-binary=:all:" in dockerfile
    assert "COPY build/runtime-site-packages" not in dockerfile
    assert "sha256sum -c build/source-manifest.txt" in dockerfile
    assert "org.oryxos.source-sha256" in dockerfile


def test_image_evidence_must_match_source_and_image_configuration():
    source = "a" * 64
    digest = "sha256:" + "b" * 64
    config = "sha256:" + "c" * 64
    metadata = {"containerimage.digest": digest, "containerimage.config.digest": config}
    inspected = [{"Id": config, "Os": "linux", "Architecture": "amd64",
                  "Config": {"Labels": {"org.oryxos.source-sha256": source}}}]
    evidence = build_manifest.image_evidence(metadata, inspected, source)
    assert evidence == {"status": "built_local", "adapter_image_digest": digest,
                        "adapter_image_id": config}
    for field, bad in (("Id", "sha256:" + "d" * 64), ("Os", "windows"), ("Architecture", "arm64")):
        changed = copy.deepcopy(inspected)
        changed[0][field] = bad
        with pytest.raises(ValueError):
            build_manifest.image_evidence(metadata, changed, source)
    with pytest.raises(ValueError):
        build_manifest.image_evidence(metadata, inspected, "e" * 64)
    with pytest.raises(ValueError):
        build_manifest.image_evidence({}, inspected, source)
    containerd_metadata = {"containerimage.digest": digest, "containerimage.descriptor": {
        "digest": digest, "mediaType": "application/vnd.oci.image.manifest.v1+json",
        "platform": {"architecture": "amd64", "os": "linux"}}}
    containerd_image = copy.deepcopy(inspected)
    containerd_image[0]["Id"] = digest
    assert build_manifest.image_evidence(containerd_metadata, containerd_image, source)["adapter_image_id"] == digest
    containerd_metadata["containerimage.descriptor"]["digest"] = config
    with pytest.raises(ValueError):
        build_manifest.image_evidence(containerd_metadata, containerd_image, source)
