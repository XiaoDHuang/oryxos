"""真实PG查询必须按固定revision分页，并只召回当前有效归档。"""

from uuid import UUID, uuid5, NAMESPACE_URL

import pytest

from oryx_mem0.contracts import MAX_RECALL_ITEMS, OperationIdentity, RecallReceipt, Request, receipt_bytes
from oryx_mem0.engine.staging import MAX_IO_BYTES
from oryx_mem0.storage.migrations import apply_migrations, validate_schema
from oryx_mem0.storage.queries import QueryStore

pytestmark = pytest.mark.integration

WORKSPACE = "11111111-1111-4111-8111-111111111111"
OTHER_WORKSPACE = "22222222-2222-4222-8222-222222222222"
REQUEST_ID = "55555555-5555-4555-8555-555555555555"


def uuid_value(number):
    return str(UUID(int=number))


def system(pg_harness):
    apply_migrations(pg_harness.open(autocommit=True), 2)
    validate_schema(pg_harness.open(autocommit=True), 2)
    factory = lambda autocommit=False: pg_harness.open(autocommit=autocommit)
    return QueryStore(factory), pg_harness.open(autocommit=True)


def seed(connection, workspace_id, entries):
    revision = max((entry[3] for entry in entries), default=0)
    connection.execute(
        "INSERT INTO oryx_memory.memory_namespaces(workspace_id,revision,created_at) "
        "VALUES (%s,%s,clock_timestamp()) ON CONFLICT(workspace_id) DO UPDATE SET revision=EXCLUDED.revision",
        (workspace_id, revision),
    )
    for index, (memory_id, scope, content, updated_revision, vector) in enumerate(entries):
        operation_id = str(uuid5(NAMESPACE_URL, f"operation:{workspace_id}:{memory_id}:{updated_revision}"))
        version_id = str(uuid5(NAMESPACE_URL, f"version:{workspace_id}:{memory_id}:{updated_revision}"))
        request_hash = f"{updated_revision:064x}"[-64:]
        connection.execute(
            "INSERT INTO oryx_memory.memory_operations("
            "workspace_id,operation_id,kind,scope,request_hash,raw_input,state,baseline_revision,"
            "committed_revision,owner_token,deadline_at,result_json,error_code,created_at,started_at,completed_at) "
            "VALUES (%s,%s,'SAVE',%s,%s,%s,'COMMITTED',%s,%s,NULL,clock_timestamp(),%s::jsonb,NULL,"
            "clock_timestamp(),clock_timestamp(),clock_timestamp())",
            (workspace_id, operation_id, scope, request_hash, content, updated_revision - 1, updated_revision, "{}"),
        )
        connection.execute(
            "INSERT INTO oryx_memory.memory_versions("
            "version_id,workspace_id,memory_id,scope,operation_id,action_index,revision,created_revision,event,"
            "old_content,new_content,previous_version_id,changed_at) "
            "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,'ADD',NULL,%s,NULL,clock_timestamp())",
            (version_id, workspace_id, memory_id, scope, operation_id, 0, updated_revision,
             updated_revision, content),
        )
        connection.execute(
            "INSERT INTO oryx_memory.memory_current("
            "workspace_id,memory_id,scope,content,version_id,created_revision,updated_revision,embedding,"
            "embedding_model,updated_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s::vector,%s,clock_timestamp())",
            (workspace_id, memory_id, scope, content, version_id, updated_revision, updated_revision,
             None if vector is None else "[" + ",".join(str(value) for value in vector) + "]",
             None if vector is None else "test-embedding"),
        )


def append_version(connection, workspace_id, memory_id, scope, old_content, new_content, revision, event="UPDATE"):
    current = connection.execute(
        "SELECT version_id,created_revision FROM oryx_memory.memory_current WHERE workspace_id=%s AND memory_id=%s",
        (workspace_id, memory_id),
    ).fetchone()
    operation_id = str(uuid5(NAMESPACE_URL, f"operation:update:{workspace_id}:{memory_id}:{revision}"))
    version_id = str(uuid5(NAMESPACE_URL, f"version:update:{workspace_id}:{memory_id}:{revision}"))
    connection.execute(
        "INSERT INTO oryx_memory.memory_operations("
        "workspace_id,operation_id,kind,scope,request_hash,raw_input,state,baseline_revision,committed_revision,"
        "owner_token,deadline_at,result_json,error_code,created_at,started_at,completed_at) "
        "VALUES (%s,%s,'SAVE',%s,%s,%s,'COMMITTED',%s,%s,NULL,clock_timestamp(),'{}'::jsonb,NULL,"
        "clock_timestamp(),clock_timestamp(),clock_timestamp())",
        (workspace_id, operation_id, scope, f"{revision:064x}"[-64:], new_content or old_content,
         revision - 1, revision),
    )
    connection.execute(
        "INSERT INTO oryx_memory.memory_versions("
        "version_id,workspace_id,memory_id,scope,operation_id,action_index,revision,created_revision,event,"
        "old_content,new_content,previous_version_id,changed_at) "
        "VALUES (%s,%s,%s,%s,%s,0,%s,%s,%s,%s,%s,%s,clock_timestamp())",
        (version_id, workspace_id, memory_id, scope, operation_id, revision, current[1], event,
         old_content, new_content, current[0]),
    )
    if event == "DELETE":
        connection.execute(
            "DELETE FROM oryx_memory.memory_current WHERE workspace_id=%s AND memory_id=%s",
            (workspace_id, memory_id),
        )
    else:
        connection.execute(
            "UPDATE oryx_memory.memory_current SET content=%s,version_id=%s,updated_revision=%s,updated_at=clock_timestamp() "
            "WHERE workspace_id=%s AND memory_id=%s",
            (new_content, version_id, revision, workspace_id, memory_id),
        )
    connection.execute(
        "UPDATE oryx_memory.memory_namespaces SET revision=%s WHERE workspace_id=%s", (revision, workspace_id),
    )


def test_same_scope_free_snapshot_pages_all_core_and_latest_archival_window(pg_harness):
    queries, connection = system(pg_harness)
    entries = []
    for index in range(1, 102):
        entries.append((uuid_value(index), "CORE", f"核心{index}", index, None))
    for index in range(1, 106):
        revision = 101 + index
        entries.append((uuid_value(1000 + index), "ARCHIVAL", f"归档{index}", revision, (1.0, 0.0)))
    seed(connection, WORKSPACE, entries)
    snapshot = queries.create_snapshot(WORKSPACE)
    assert not hasattr(snapshot, "scope")
    assert snapshot.core_count == 101 and snapshot.archival_count == 100
    first = queries.page(snapshot, "CORE", page_size=100)
    second = queries.page(snapshot, "CORE", after=first.next_after, page_size=100)
    assert len(first.items) == 100 and first.complete is False and first.next_after is not None
    assert len(second.items) == 1 and second.complete is True and second.next_after is None
    assert [item.content for item in first.items + second.items] == [f"核心{index}" for index in range(1, 102)]
    archival = queries.page(snapshot, "ARCHIVAL", page_size=100)
    assert archival.complete is True and archival.total_count == 100
    assert [item.content for item in archival.items] == [f"归档{index}" for index in range(6, 106)]


def test_snapshot_reconstructs_old_update_and_delete_while_new_snapshot_sees_current(pg_harness):
    queries, connection = system(pg_harness)
    memory_a, memory_b = uuid_value(2001), uuid_value(2002)
    seed(connection, WORKSPACE, [
        (memory_a, "ARCHIVAL", "Java17", 1, (1.0, 0.0)),
        (memory_b, "ARCHIVAL", "保留后删除", 2, (0.0, 1.0)),
    ])
    old = queries.create_snapshot(WORKSPACE)
    append_version(connection, WORKSPACE, memory_a, "ARCHIVAL", "Java17", "Java21", 3)
    append_version(connection, WORKSPACE, memory_b, "ARCHIVAL", "保留后删除", None, 4, "DELETE")
    old_page = queries.page(old, "ARCHIVAL")
    assert [(item.memory_id, item.content) for item in old_page.items] == [
        (memory_a, "Java17"), (memory_b, "保留后删除"),
    ]
    current = queries.create_snapshot(WORKSPACE)
    current_page = queries.page(current, "ARCHIVAL")
    assert [(item.memory_id, item.content) for item in current_page.items] == [(memory_a, "Java21")]


def test_page_budget_uses_complete_json_bytes_and_never_truncates_item_content(pg_harness):
    queries, connection = system(pg_harness)
    content = '\\"界😀' * 3640
    entries = [
        (uuid_value(3000 + index), "CORE", content + str(index), index, None) for index in range(1, 41)
    ]
    seed(connection, WORKSPACE, entries)
    snapshot = queries.create_snapshot(WORKSPACE)
    page = queries.page(snapshot, "CORE", page_size=100)
    assert 0 < len(page.items) < 40 and page.complete is False and page.next_after is not None
    assert page.serialized_size <= MAX_IO_BYTES
    assert all(item.content == content + str(index) for index, item in enumerate(page.items, start=1))


def test_recall_is_current_archival_only_top20_exact_cosine_with_stable_ties(pg_harness):
    queries, connection = system(pg_harness)
    entries = [(uuid_value(4000), "CORE", "核心不得召回", 1, None)]
    for index in range(1, 23):
        vector = (1.0, 0.0) if index <= 2 else (1.0, index / 100.0)
        entries.append((uuid_value(4000 + index), "ARCHIVAL", f"归档{index}", index + 1, vector))
    seed(connection, WORKSPACE, entries)
    request = Request("RECALL", "ARCHIVAL", "Java")
    operation = OperationIdentity.from_request(WORKSPACE, uuid_value(4900), request)
    receipt = queries.recall(operation, (1.0, 0.0))
    assert isinstance(receipt, RecallReceipt)
    assert receipt.returned_count == MAX_RECALL_ITEMS and receipt.truncated_by_bytes is False
    assert all(item.scope == "ARCHIVAL" and item.content != "核心不得召回" for item in receipt.items)
    assert tuple(item.score for item in receipt.items) == tuple(sorted(
        (item.score for item in receipt.items), reverse=True,
    ))
    tied = [item.memory_id for item in receipt.items if item.score == receipt.items[0].score]
    assert tied == sorted(tied)


def test_recall_returns_longest_complete_prefix_when_json_escaping_exceeds_budget(pg_harness):
    queries, connection = system(pg_harness)
    content = "\\" * (32 * 1024)
    entries = [
        (uuid_value(5000 + index), "ARCHIVAL", content, index, (1.0, index / 1000.0))
        for index in range(1, 21)
    ]
    seed(connection, WORKSPACE, entries)
    request = Request("RECALL", "ARCHIVAL", "转义预算")
    operation = OperationIdentity.from_request(WORKSPACE, uuid_value(5900), request)
    receipt = queries.recall(operation, (1.0, 0.0))
    assert 0 < receipt.returned_count < 20 and receipt.truncated_by_bytes is True
    assert len(receipt_bytes(receipt, REQUEST_ID)) <= MAX_IO_BYTES
    assert all(item.content == content for item in receipt.items)


def test_empty_recall_is_not_byte_truncated_or_filled_with_history(pg_harness):
    queries, connection = system(pg_harness)
    seed(connection, WORKSPACE, [(uuid_value(6001), "CORE", "只有核心", 1, None)])
    request = Request("RECALL", "ARCHIVAL", "不存在")
    operation = OperationIdentity.from_request(WORKSPACE, uuid_value(6900), request)
    receipt = queries.recall(operation, (1.0, 0.0))
    assert receipt.items == () and receipt.returned_count == 0 and receipt.truncated_by_bytes is False


def test_workspace_isolation_and_post_snapshot_concurrent_insert_do_not_change_page(pg_harness):
    queries, connection = system(pg_harness)
    shared_id = uuid_value(7001)
    seed(connection, WORKSPACE, [(shared_id, "CORE", "工作区A", 1, None)])
    seed(connection, OTHER_WORKSPACE, [(shared_id, "CORE", "工作区B", 1, None)])
    snapshot = queries.create_snapshot(WORKSPACE)
    seed(connection, WORKSPACE, [(uuid_value(7002), "CORE", "快照后新增", 2, None)])
    page = queries.page(snapshot, "CORE")
    other = queries.page(queries.create_snapshot(OTHER_WORKSPACE), "CORE")
    assert [(item.memory_id, item.content) for item in page.items] == [(shared_id, "工作区A")]
    assert [(item.memory_id, item.content) for item in other.items] == [(shared_id, "工作区B")]


def test_recall_clamps_negative_cosine_to_zero_without_dropping_item(pg_harness):
    queries, connection = system(pg_harness)
    seed(connection, WORKSPACE, [
        (uuid_value(8101), "ARCHIVAL", "近邻", 1, (1.0, 0.0)),
        (uuid_value(8102), "ARCHIVAL", "反向", 2, (-1.0, 0.0)),
    ])
    request = Request("RECALL", "ARCHIVAL", "方向")
    operation = OperationIdentity.from_request(WORKSPACE, uuid_value(8900), request)
    receipt = queries.recall(operation, (1.0, 0.0))
    scores = {item.content: item.score for item in receipt.items}
    assert scores == {"近邻": pytest.approx(1.0), "反向": 0.0}
    assert receipt.items[-1].content == "反向"
