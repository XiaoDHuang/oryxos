"""用固定来源与补丁构建SDK，不在运行时修改已安装包。"""

import argparse
import ast
import base64
import csv
import difflib
import hashlib
import importlib.metadata
import importlib.util
import io
import json
import re
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "vendor/sdk-lock.json"
PATCH = ROOT / "patches/CVE-2026-7597.patch"
TARGET = "mem0/vector_stores/faiss.py"


def load_lock():
    lock = json.loads(LOCK.read_text(encoding="utf-8"))
    if lock["schema"] != 1 or lock["patch_target"] != TARGET:
        raise ValueError("SDK来源锁格式非法")
    return lock


def sha256(content):
    return hashlib.sha256(content).hexdigest()


def checked_bytes(path, expected):
    content = path.read_bytes()
    if sha256(content) != expected:
        raise ValueError("SDK构建输入摘要不匹配")
    return content


def vendor_path(relative):
    root = (ROOT / "vendor").resolve()
    path = (root / relative).resolve()
    if not path.is_relative_to(root):
        raise ValueError("SDK构建路径越界")
    return path


def git_blob(content):
    # SHA1仅用于匹配Git既有对象格式，产物完整性另外使用SHA256。
    return hashlib.sha1(b"blob " + str(len(content)).encode("ascii") + b"\0" + content).hexdigest()


def apply_exact_patch(original, patch):
    lines = patch.decode("utf-8").splitlines(keepends=True)
    if lines[:2] != [f"--- a/{TARGET}\n", f"+++ b/{TARGET}\n"]:
        raise ValueError("补丁必须仅指向已批准FAISS文件")
    source = original.decode("utf-8").splitlines(keepends=True)
    result = []
    position = 0
    index = 2
    while index < len(lines):
        if all(not line.strip() for line in lines[index:]):
            break
        match = re.fullmatch(r"@@ -(\d+),(\d+) \+(\d+),(\d+) @@[^\n]*\n", lines[index])
        if match is None:
            raise ValueError("补丁块格式非法")
        start, old_count, new_start, new_count = map(int, match.groups())
        if start - 1 < position:
            raise ValueError("补丁块重叠")
        result.extend(source[position:start - 1])
        position = start - 1
        if len(result) != new_start - 1:
            raise ValueError("补丁新位置不匹配")
        consumed = added = 0
        index += 1
        while consumed < old_count or added < new_count:
            if index >= len(lines):
                raise ValueError("补丁块不完整")
            line = lines[index]
            index += 1
            if line[:1] not in (" ", "+", "-"):
                raise ValueError("补丁内容非法")
            if line[0] in " -":
                if position >= len(source) or source[position] != line[1:]:
                    raise ValueError("补丁上下文不匹配，禁止模糊套用")
                position += 1
                consumed += 1
            if line[0] in " +":
                result.append(line[1:])
                added += 1
        if consumed != old_count or added != new_count:
            raise ValueError("补丁行数不匹配")
    result.extend(source[position:])
    return "".join(result).encode("utf-8")


def source_digest(files):
    records = "".join(f"{name}\0{sha256(data)}\n" for name, data in sorted(files.items()))
    return sha256(records.encode("utf-8"))


def expected_backport(original, official):
    """只移植官方安全节点，保留1.0.11的limit接口和其余方法原文。"""
    old = original.decode("utf-8")
    fixed = official.decode("utf-8")
    old_lines = old.splitlines(keepends=True)
    fixed_lines = fixed.splitlines(keepends=True)
    old_tree, fixed_tree = ast.parse(old), ast.parse(fixed)
    old_class = next(node for node in old_tree.body if isinstance(node, ast.ClassDef) and node.name == "FAISS")
    fixed_class = next(node for node in fixed_tree.body if isinstance(node, ast.ClassDef) and node.name == "FAISS")
    replacements = []
    for name in ("__init__", "_load", "_save", "delete_col"):
        before = next(node for node in old_class.body if isinstance(node, ast.FunctionDef) and node.name == name)
        after = next(node for node in fixed_class.body if isinstance(node, ast.FunctionDef) and node.name == name)
        replacements.append((before.lineno - 1, before.end_lineno, "".join(fixed_lines[after.lineno - 1:after.end_lineno])))
    helpers = []
    for name in ("SafeUnpickler", "_safe_pickle_load", "_validate_docstore_structure"):
        node = next(node for node in fixed_tree.body if isinstance(node, (ast.ClassDef, ast.FunctionDef)) and node.name == name)
        helpers.append("".join(fixed_lines[node.lineno - 1:node.end_lineno]))
    output = next(node for node in old_tree.body if isinstance(node, ast.ClassDef) and node.name == "OutputData")
    replacements.append((output.lineno - 1, output.lineno - 1, "\n\n".join(helpers) + "\n\n"))
    for start, end, content in sorted(replacements, reverse=True):
        old_lines[start:end] = [content]
    combined = "".join(old_lines)
    old_import = "from typing import Dict, List, Optional\n"
    if combined.count(old_import) != 1:
        raise ValueError("SDK导入上下文不匹配")
    return ("import json\n" + combined.replace(old_import, "from typing import Any, Dict, List, Optional\n")).encode("utf-8")


def build_wheel_bytes():
    lock = load_lock()
    archive = checked_bytes(vendor_path(lock["upstream_wheel"]), lock["upstream_sha256"])
    patch = checked_bytes(PATCH, lock["patch_sha256"])
    checked_bytes(ROOT / "patches/CVE-2026-7597.upstream.patch", lock["upstream_patch_sha256"])
    manifest = json.loads(checked_bytes(vendor_path("upstream-sdk-files.json"), lock["source_manifest_sha256"]))
    with zipfile.ZipFile(io.BytesIO(archive)) as wheel:
        if len(wheel.namelist()) != len(set(wheel.namelist())):
            raise ValueError("SDK源包条目重复")
        files = {name: wheel.read(name) for name in wheel.namelist()}
    sources = {name: data for name, data in files.items() if name.startswith("mem0/")}
    if sources.keys() != manifest.keys():
        raise ValueError("SDK源码集合不匹配")
    if any(git_blob(data) != manifest[name] for name, data in sources.items()):
        raise ValueError("SDK源码与锁定Git树不匹配")
    files[TARGET] = apply_exact_patch(sources[TARGET], patch)
    official = checked_bytes(vendor_path("upstream/faiss-fixed.py"), lock["official_faiss_sha256"])
    if git_blob(official) != lock["official_git_blob"]:
        raise ValueError("官方安全修复源码身份不匹配")
    if files[TARGET] != expected_backport(sources[TARGET], official):
        raise ValueError("回移结果与官方安全节点不匹配")
    if [name for name in sources if sources[name] != files[name]] != [TARGET]:
        raise ValueError("补丁修改了未批准的SDK源码")
    base_info = f"mem0ai-{lock['upstream_version']}.dist-info/"
    info = f"mem0ai-{lock['version']}.dist-info/"
    metadata = files[base_info + "METADATA"]
    header = f"Version: {lock['upstream_version']}\n".encode("ascii")
    if metadata.count(header) != 1:
        raise ValueError("SDK上游版本元数据不匹配")
    files[base_info + "METADATA"] = metadata.replace(header, f"Version: {lock['version']}\n".encode("ascii"))
    files[base_info + "WHEEL"] = re.sub(rb"(?m)^Generator:.*$", b"Generator: oryxos-sdk-repack-1", files[base_info + "WHEEL"])
    files.pop(base_info + "RECORD")
    files = {info + name[len(base_info):] if name.startswith(base_info) else name: data for name, data in files.items()}
    proof = {key: lock[key] for key in ("version", "upstream_version", "git_commit", "upstream_sha256", "patch_commit", "patch_sha256")}
    proof.update(changed_sources=[TARGET], sdk_file_count=len(sources), sdk_sha256=source_digest({name: files[name] for name in sources}))
    files[info + "oryxos-build.json"] = (json.dumps(proof, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
    record = io.StringIO(newline="")
    writer = csv.writer(record, lineterminator="\n")
    for name, data in sorted(files.items()):
        digest = base64.urlsafe_b64encode(hashlib.sha256(data).digest()).decode("ascii").rstrip("=")
        writer.writerow([name, "sha256=" + digest, len(data)])
    writer.writerow([info + "RECORD", "", ""])
    files[info + "RECORD"] = record.getvalue().encode("utf-8")
    output = io.BytesIO()
    # 不依赖平台zlib版本，固定顺序、时间、权限生成相同字节。
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as wheel:
        for name, data in sorted(files.items()):
            entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.create_system = 3
            entry.external_attr = 0o100644 << 16
            wheel.writestr(entry, data)
    return output.getvalue()


def verify_installed():
    lock = load_lock()
    expected = build_wheel_bytes()
    if sha256(expected) != lock["wheel_sha256"]:
        raise ValueError("SDK产物与锁定摘要不匹配")
    if checked_bytes(vendor_path(lock["wheel"]), lock["wheel_sha256"]) != expected:
        raise ValueError("SDK产物不可重复构建")
    distribution = importlib.metadata.distribution("mem0ai")
    if distribution.version != lock["version"]:
        raise ValueError("实际安装的SDK版本不匹配")
    package_root = Path(distribution.locate_file("mem0")).resolve()
    spec = importlib.util.find_spec("mem0")
    if spec is None or Path(spec.origin).resolve() != package_root / "__init__.py":
        raise ValueError("SDK导入位置与安装位置不匹配")
    with zipfile.ZipFile(io.BytesIO(expected)) as wheel:
        names = {name for name in wheel.namelist() if name.startswith("mem0/")}
        actual = {"mem0/" + path.relative_to(package_root).as_posix() for path in package_root.rglob("*")
                  if path.is_file() and "__pycache__" not in path.parts}
        if actual != names:
            raise ValueError("实际安装的SDK文件集合不匹配")
        for name in names:
            path = Path(distribution.locate_file(name)).resolve()
            if not path.is_relative_to(package_root) or path.read_bytes() != wheel.read(name):
                raise ValueError("实际安装的SDK源码已改变")
        info = f"mem0ai-{lock['version']}.dist-info/"
        for name in ("METADATA", "WHEEL", "oryxos-build.json"):
            installed = distribution.read_text(name)
            if installed is None or installed.encode("utf-8") != wheel.read(info + name):
                raise ValueError("实际安装的SDK来源元数据不匹配")
        proof = json.loads(wheel.read(info + "oryxos-build.json"))
    proof["wheel_sha256"] = lock["wheel_sha256"]
    return proof


def main():
    parser = argparse.ArgumentParser(description="离线重建并校验受控SDK")
    parser.add_argument("--write", action="store_true", help="写入已锁定名称的构建产物")
    parser.add_argument("--check-installed", action="store_true")
    parser.add_argument("--derive-patch", action="store_true", help="输出供评审的最小回移补丁")
    args = parser.parse_args()
    lock = load_lock()
    if args.derive_patch:
        archive = checked_bytes(vendor_path(lock["upstream_wheel"]), lock["upstream_sha256"])
        with zipfile.ZipFile(io.BytesIO(archive)) as wheel:
            original = wheel.read(TARGET)
        official = checked_bytes(vendor_path("upstream/faiss-fixed.py"), lock["official_faiss_sha256"])
        patch = "".join(difflib.unified_diff(original.decode().splitlines(keepends=True), expected_backport(original, official).decode().splitlines(keepends=True), fromfile="a/" + TARGET, tofile="b/" + TARGET))
        print(json.dumps({"patch": patch}))
        return
    content = build_wheel_bytes()
    if lock["wheel_sha256"] is not None and sha256(content) != lock["wheel_sha256"]:
        raise ValueError("构建产物摘要不匹配")
    if args.write:
        vendor_path(lock["wheel"]).write_bytes(content)
    if args.check_installed:
        print(json.dumps(verify_installed(), sort_keys=True))
    else:
        print(json.dumps({"version": lock["version"], "sha256": sha256(content), "bytes": len(content)}))


if __name__ == "__main__":
    main()
