"""只替换原生向量I/O，实际执行SDK的序列化修复。"""

import importlib
import json
import pickle
import sys
import types
from pathlib import Path

import pytest


def _record_execution(path):
    Path(path).write_text("仅用于隔离回归的执行标记", encoding="utf-8")
    return ({"id": {"data": "旧内容"}}, {0: "id"})


class Payload:
    def __init__(self, path):
        self.path = str(path)

    def __reduce__(self):
        return (_record_execution, (self.path,))


@pytest.fixture
def sdk(monkeypatch):
    native = types.ModuleType("faiss")
    native.read_index = lambda path: types.SimpleNamespace(ntotal=1, d=2)
    native.write_index = lambda index, path: None
    monkeypatch.setitem(sys.modules, "faiss", native)
    module = importlib.import_module("mem0.vector_stores.faiss")
    monkeypatch.setattr(module, "faiss", native)
    return module


def store(sdk, tmp_path):
    instance = sdk.FAISS.__new__(sdk.FAISS)
    instance.path = str(tmp_path)
    instance.collection_name = "test"
    instance.index = types.SimpleNamespace(ntotal=1, d=2)
    instance.docstore = {"id": {"data": "原文中文"}}
    instance.index_to_id = {0: "id"}
    return instance


def test_dangerous_pickle_cannot_execute(sdk, tmp_path):
    marker = tmp_path / "execution-marker"
    legacy = tmp_path / "test.pkl"
    legacy.write_bytes(pickle.dumps(Payload(marker)))
    with pytest.raises(ValueError):
        store(sdk, tmp_path)._load(str(tmp_path / "test.faiss"), str(legacy))
    assert not marker.exists()


def test_legacy_data_migrates_to_json_without_changing_input(sdk, tmp_path):
    original = ({"id": {"data": "原文中文"}}, {0: "id"})
    legacy = tmp_path / "test.pkl"
    content = pickle.dumps(original)
    legacy.write_bytes(content)
    instance = store(sdk, tmp_path)
    instance._load(str(tmp_path / "test.faiss"), str(legacy))
    assert (instance.docstore, instance.index_to_id) == original
    assert legacy.read_bytes() == content
    assert json.loads((tmp_path / "test.json").read_text(encoding="utf-8"))["docstore"] == original[0]


def test_save_uses_json_and_never_writes_pickle(sdk, tmp_path):
    store(sdk, tmp_path)._save()
    assert (tmp_path / "test.json").is_file()
    assert not (tmp_path / "test.pkl").exists()


def test_json_preferred_over_dangerous_legacy_pickle(sdk, tmp_path):
    marker = tmp_path / "execution-marker"
    (tmp_path / "test.pkl").write_bytes(pickle.dumps(Payload(marker)))
    expected = {"docstore": {"id": {"data": "JSON原文"}}, "index_to_id": {"0": "id"}}
    (tmp_path / "test.json").write_text(json.dumps(expected), encoding="utf-8")
    instance = store(sdk, tmp_path)
    instance._load(str(tmp_path / "test.faiss"), str(tmp_path / "test.pkl"))
    assert instance.docstore == expected["docstore"]
    assert not marker.exists()


@pytest.mark.parametrize("invalid", [[], ({},), ({}, []), ({1: {}}, {}), ({"id": "bad"}, {}), ({}, {"0": "id"}), ({}, {0: 1})])
def test_invalid_legacy_structure_is_rejected(sdk, invalid):
    with pytest.raises(ValueError):
        sdk._validate_docstore_structure(invalid)
