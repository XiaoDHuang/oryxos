"""构建证明同时绑定源包、补丁、产物与实际安装。"""

import hashlib
import ast
import io
import tomllib
import zipfile

import pytest

import sdk_build


def test_build_is_reproducible_and_only_faiss_source_changes():
    first = sdk_build.build_wheel_bytes()
    assert sdk_build.build_wheel_bytes() == first
    lock = sdk_build.load_lock()
    assert hashlib.sha256(first).hexdigest() == lock["wheel_sha256"]
    with zipfile.ZipFile(sdk_build.ROOT / "vendor" / lock["upstream_wheel"]) as before:
        with zipfile.ZipFile(io.BytesIO(first)) as after:
            old_files = {name: before.read(name) for name in before.namelist() if name.startswith("mem0/")}
            new_files = {name: after.read(name) for name in after.namelist() if name.startswith("mem0/")}
            assert len(old_files) == 149
            assert old_files.keys() == new_files.keys()
            assert [name for name in old_files if old_files[name] != new_files[name]] == [lock["patch_target"]]
            assert any(name.endswith("/licenses/LICENSE") or name.endswith("/LICENSE") for name in after.namelist())


def test_tampered_patch_is_rejected(monkeypatch, tmp_path):
    wrong = tmp_path / "changed.patch"
    wrong.write_text("被篡改的补丁", encoding="utf-8")
    monkeypatch.setattr(sdk_build, "PATCH", wrong, raising=False)
    with pytest.raises(ValueError):
        sdk_build.build_wheel_bytes()


def test_installed_package_matches_verified_wheel():
    proof = sdk_build.verify_installed()
    assert proof["version"] == "1.0.11+oryx.1"
    assert proof["changed_sources"] == ["mem0/vector_stores/faiss.py"]


def test_non_security_faiss_methods_keep_original_interfaces_and_logic():
    lock = sdk_build.load_lock()
    with zipfile.ZipFile(sdk_build.ROOT / "vendor" / lock["upstream_wheel"]) as archive:
        original = ast.parse(archive.read(sdk_build.TARGET))
    with zipfile.ZipFile(io.BytesIO(sdk_build.build_wheel_bytes())) as archive:
        patched = ast.parse(archive.read(sdk_build.TARGET))
    before = next(node for node in original.body if isinstance(node, ast.ClassDef) and node.name == "FAISS")
    after = next(node for node in patched.body if isinstance(node, ast.ClassDef) and node.name == "FAISS")
    methods = {node.name: node for node in after.body if isinstance(node, ast.FunctionDef)}
    for method in before.body:
        if isinstance(method, ast.FunctionDef) and method.name not in {"__init__", "_load", "_save", "delete_col"}:
            assert ast.dump(method) == ast.dump(methods[method.name]), method.name


def test_tampered_upstream_wheel_is_rejected(monkeypatch, tmp_path):
    wrong = tmp_path / "upstream.whl"
    wrong.write_bytes("被篡改的源包".encode("utf-8"))
    original = sdk_build.vendor_path
    monkeypatch.setattr(sdk_build, "vendor_path", lambda path: wrong if path.startswith("upstream/") else original(path))
    with pytest.raises(ValueError):
        sdk_build.build_wheel_bytes()


def test_dependency_lock_points_to_the_verified_wheel():
    lock = sdk_build.load_lock()
    environment = tomllib.loads((sdk_build.ROOT / "uv.lock").read_text(encoding="utf-8"))
    packages = [package for package in environment["package"] if package["name"] == "mem0ai"]
    assert len(packages) == 1
    assert packages[0]["version"] == lock["version"]
    assert packages[0]["source"] == {"path": "vendor/" + lock["wheel"]}
    assert packages[0]["wheels"] == [{"filename": lock["wheel"], "hash": "sha256:" + lock["wheel_sha256"]}]
