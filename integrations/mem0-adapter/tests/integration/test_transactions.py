"""真实PG事务必须让原文、历史、投影和持久凭据保持可判定。"""

from time import monotonic
from types import SimpleNamespace
from uuid import UUID

import psycopg
import pytest

import oryx_mem0.contracts as contracts
from oryx_mem0.contracts import (
    FailureReceipt, OperationIdentity, PendingReceipt, Request, SaveReceipt,
)
from oryx_mem0.audit.calls import CallAuditor, CallValue
from oryx_mem0.engine.staging import Change, Code, EngineError, RunContext, StagedResult
from oryx_mem0.storage.call_audits import CallAuditStore
from oryx_mem0.storage.memories import (
    AuditUnavailable, HistoryUnavailable, InvalidGeneratedResult, MemoryTransactionError,
    MemoryTransactionStore, ResultLimitExceeded,
)
from oryx_mem0.storage.migrations import SchemaError, apply_migrations, validate_schema
from oryx_mem0.storage.operations import OperationError, OperationStore, RequestConflict, WriteConflict
from oryx_mem0.storage.queries import QueryStore

pytestmark = pytest.mark.integration

WORKSPACE = "11111111-1111-4111-8111-111111111111"
OTHER_WORKSPACE = "22222222-2222-4222-8222-222222222222"


def uuid_value(number):
    return str(UUID(int=number))


def identity(number, text="项目已升级到 Java 21", scope="ARCHIVAL"):
    request = Request("SAVE", scope, text)
    return OperationIdentity.from_request(WORKSPACE, uuid_value(number), request), request


def system(pg_harness, fault_plan=None):
    apply_migrations(pg_harness.open(autocommit=True), 2)
    validate_schema(pg_harness.open(autocommit=True), 2)
    factory = lambda autocommit=False: pg_harness.open(autocommit=autocommit)
    return SimpleNamespace(
        operations=OperationStore(factory, fault_plan),
        memories=MemoryTransactionStore(factory, fault_plan, "test-embedding"),
        audits=CallAuditStore(factory, fault_plan),
        queries=QueryStore(factory),
        connection=lambda: pg_harness.open(autocommit=True),
    )


def stage(operation, baseline, changes):
    context = RunContext(WORKSPACE, operation.operation_id, "ARCHIVAL", baseline, 2, monotonic() + 20)
    result = StagedResult(WORKSPACE, operation.operation_id, baseline, tuple(changes), (), len(changes))
    return context, result


def add(memory_number, content="项目使用 Java 21"):
    return Change("ADD", uuid_value(memory_number), None, content, (1.0, 0.0), None)


def claim(storage, operation, request, owner_number):
    receipt = storage.operations.register(operation, request.text)
    assert isinstance(receipt, PendingReceipt) and receipt.state == "RECEIVED"
    lease = storage.operations.claim(operation, uuid_value(owner_number))
    assert lease is not None
    return lease


def test_migration_creates_exact_five_tables_and_expected_pg_types(pg_harness):
    storage = system(pg_harness)
    before = storage.connection().execute(
        "SELECT relname,relfilenode FROM pg_class JOIN pg_namespace n ON n.oid=relnamespace "
        "WHERE n.nspname='oryx_memory' AND relkind='r' ORDER BY relname"
    ).fetchall()
    apply_migrations(storage.connection(), 2)
    after = storage.connection().execute(
        "SELECT relname,relfilenode FROM pg_class JOIN pg_namespace n ON n.oid=relnamespace "
        "WHERE n.nspname='oryx_memory' AND relkind='r' ORDER BY relname"
    ).fetchall()
    assert before == after
    tables = storage.connection().execute(
        "SELECT tablename FROM pg_tables WHERE schemaname='oryx_memory' ORDER BY tablename"
    ).fetchall()
    assert [row[0] for row in tables] == [
        "memory_call_audits", "memory_current", "memory_namespaces", "memory_operations", "memory_versions",
    ]
    columns = storage.connection().execute(
        "SELECT table_name,column_name,data_type,udt_name FROM information_schema.columns "
        "WHERE table_schema='oryx_memory' ORDER BY table_name,ordinal_position"
    ).fetchall()
    assert ("memory_current", "embedding", "USER-DEFINED", "vector") in columns
    assert ("memory_operations", "raw_input", "text", "text") in columns
    marker = storage.connection().execute(
        "SELECT obj_description(oid,'pg_namespace') FROM pg_namespace WHERE nspname='oryx_memory'"
    ).fetchone()[0]
    assert marker == "oryx-memory-v1;schema=1;dimensions=2"


def test_schema_dimension_and_invalid_dimension_are_rejected_without_rewriting(pg_harness):
    storage = system(pg_harness)
    connection = storage.connection()
    with pytest.raises(SchemaError):
        validate_schema(connection, 3)
    with pytest.raises(SchemaError):
        apply_migrations(connection, 3)
    dimension = connection.execute(
        "SELECT format_type(a.atttypid,a.atttypmod) FROM pg_attribute a "
        "JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace "
        "WHERE n.nspname='oryx_memory' AND c.relname='memory_current' AND a.attname='embedding'"
    ).fetchone()[0]
    assert dimension == "vector(2)"
    for invalid in (0, True, 16001):
        with pytest.raises(SchemaError):
            apply_migrations(connection, invalid)


def test_unknown_existing_schema_is_rejected_before_creating_or_altering_any_table(pg_harness):
    connection = pg_harness.open(autocommit=True)
    connection.execute("CREATE SCHEMA oryx_memory")
    connection.execute("CREATE TABLE oryx_memory.memory_namespaces(unexpected integer NOT NULL)")
    with pytest.raises(SchemaError) as failure:
        apply_migrations(connection, 2)
    assert failure.value.code == "UNKNOWN_SCHEMA"
    assert "unexpected" not in str(failure.value)
    tables = connection.execute(
        "SELECT tablename FROM pg_tables WHERE schemaname='oryx_memory' ORDER BY tablename"
    ).fetchall()
    columns = connection.execute(
        "SELECT column_name FROM information_schema.columns "
        "WHERE table_schema='oryx_memory' AND table_name='memory_namespaces'"
    ).fetchall()
    assert tables == [("memory_namespaces",)] and columns == [("unexpected",)]


def test_validate_schema_rejects_known_marker_with_tampered_structure(pg_harness):
    storage = system(pg_harness)
    connection = storage.connection()
    connection.execute("ALTER TABLE oryx_memory.memory_current DROP COLUMN embedding_model")
    with pytest.raises(SchemaError):
        validate_schema(connection, 2)


def test_history_rows_reject_update_and_delete_and_public_has_no_access(pg_harness):
    storage = system(pg_harness)
    connection = storage.connection()
    operation_id = uuid_value(901)
    memory_id = uuid_value(902)
    version_id = uuid_value(903)
    connection.execute(
        "INSERT INTO oryx_memory.memory_namespaces(workspace_id,revision,created_at) "
        "VALUES (%s,1,clock_timestamp())", (WORKSPACE,),
    )
    connection.execute(
        "INSERT INTO oryx_memory.memory_operations("
        "workspace_id,operation_id,kind,scope,request_hash,raw_input,state,deadline_at,created_at) "
        "VALUES (%s,%s,'SAVE','ARCHIVAL',%s,'历史原文','RECEIVED',"
        "clock_timestamp()+interval '30 seconds',clock_timestamp())",
        (WORKSPACE, operation_id, "a" * 64),
    )
    connection.execute(
        "INSERT INTO oryx_memory.memory_versions("
        "version_id,workspace_id,memory_id,scope,operation_id,action_index,revision,created_revision,event,"
        "old_content,new_content,previous_version_id,changed_at) "
        "VALUES (%s,%s,%s,'ARCHIVAL',%s,0,1,1,'ADD',NULL,'历史原文',NULL,clock_timestamp())",
        (version_id, WORKSPACE, memory_id, operation_id),
    )
    with pytest.raises(psycopg.Error):
        connection.execute(
            "UPDATE oryx_memory.memory_versions SET new_content='篡改' WHERE version_id=%s", (version_id,),
        )
    with pytest.raises(psycopg.Error):
        connection.execute("DELETE FROM oryx_memory.memory_versions WHERE version_id=%s", (version_id,))
    assert connection.execute(
        "SELECT new_content FROM oryx_memory.memory_versions WHERE version_id=%s", (version_id,),
    ).fetchone()[0] == "历史原文"
    privileges = connection.execute(
        "SELECT has_schema_privilege('public','oryx_memory','USAGE'),"
        "has_table_privilege('public','oryx_memory.memory_versions','SELECT'),"
        "has_table_privilege('public','oryx_memory.memory_current','INSERT')"
    ).fetchone()
    assert privileges == (False, False, False)


def test_registration_preserves_raw_input_hash_deadline_and_idempotency(pg_harness):
    storage = system(pg_harness)
    operation, request = identity(101, '  原文e\u0301\n"\\😀  ')
    first = storage.operations.register(operation, request.text)
    replay = storage.operations.register(operation, request.text)
    assert isinstance(first, PendingReceipt) and first.to_dict() == replay.to_dict()
    row = storage.connection().execute(
        "SELECT raw_input,request_hash,state,deadline_at-created_at FROM oryx_memory.memory_operations "
        "WHERE workspace_id=%s AND operation_id=%s", (WORKSPACE, operation.operation_id),
    ).fetchone()
    assert row[:3] == (request.text, operation.request_hash, "RECEIVED")
    assert row[3].total_seconds() == pytest.approx(30)
    conflict_request = Request("SAVE", "ARCHIVAL", request.text + "changed")
    conflict = OperationIdentity.from_request(WORKSPACE, operation.operation_id, conflict_request)
    with pytest.raises(RequestConflict):
        storage.operations.register(conflict, conflict_request.text)
    independent, _ = identity(116, request.text)
    assert isinstance(storage.operations.register(independent, request.text), PendingReceipt)
    assert storage.connection().execute(
        "SELECT count(*) FROM oryx_memory.memory_operations WHERE workspace_id=%s", (WORKSPACE,),
    ).fetchone()[0] == 2


def test_only_one_owner_claims_unexpired_received_without_extending_deadline(pg_harness):
    storage = system(pg_harness)
    operation, request = identity(102)
    registered = storage.operations.register(operation, request.text)
    first = storage.operations.claim(operation, uuid_value(201))
    second = storage.operations.claim(operation, uuid_value(202))
    assert first is not None and second is None
    assert first.deadline_at == registered.deadline_at and first.baseline_revision == 0
    running = storage.operations.get(WORKSPACE, operation.operation_id)
    assert isinstance(running, PendingReceipt) and running.state == "RUNNING"
    expired, expired_request = identity(103)
    storage.operations.register(expired, expired_request.text)
    storage.connection().execute(
        "UPDATE oryx_memory.memory_operations SET deadline_at=clock_timestamp()-interval '1 second' "
        "WHERE workspace_id=%s AND operation_id=%s", (WORKSPACE, expired.operation_id),
    )
    assert storage.operations.claim(expired, uuid_value(203)) is None


def test_revision_cas_allows_one_commit_and_records_matching_failed_conflict(pg_harness):
    storage = system(pg_harness)
    operation_a, request_a = identity(104, "并发事实A")
    operation_b, request_b = identity(105, "并发事实B")
    lease_a = claim(storage, operation_a, request_a, 204)
    lease_b = claim(storage, operation_b, request_b, 205)
    context_a, result_a = stage(operation_a, 0, (add(301, "事实A"),))
    context_b, result_b = stage(operation_b, 0, (add(302, "事实B"),))
    committed = storage.memories.commit(lease_a, context_a, result_a)
    assert isinstance(committed, SaveReceipt) and committed.revision == 1
    with pytest.raises(WriteConflict):
        storage.memories.commit(lease_b, context_b, result_b)
    failed = storage.operations.get(WORKSPACE, operation_b.operation_id)
    assert isinstance(failed, FailureReceipt)
    assert failed.error_code == "WRITE_CONFLICT" and failed.memory_effects_applied is False
    connection = storage.connection()
    assert connection.execute("SELECT revision FROM oryx_memory.memory_namespaces WHERE workspace_id=%s", (WORKSPACE,)).fetchone()[0] == 1
    assert connection.execute("SELECT count(*) FROM oryx_memory.memory_current").fetchone()[0] == 1
    assert connection.execute("SELECT count(*) FROM oryx_memory.memory_versions").fetchone()[0] == 1


def test_staging_snapshot_is_repeatable_read_and_read_only(pg_harness):
    storage = system(pg_harness)
    operation, request = identity(121, "建立工作区")
    storage.operations.register(operation, request.text)
    with storage.memories.read_snapshot() as snapshot:
        assert snapshot.execute(
            "SELECT revision FROM oryx_memory.memory_namespaces WHERE workspace_id=%s", (WORKSPACE,),
        ).fetchone()[0] == 0
        storage.connection().execute(
            "UPDATE oryx_memory.memory_namespaces SET revision=1 WHERE workspace_id=%s", (WORKSPACE,),
        )
        assert snapshot.execute(
            "SELECT revision FROM oryx_memory.memory_namespaces WHERE workspace_id=%s", (WORKSPACE,),
        ).fetchone()[0] == 0
        assert snapshot.execute("SHOW transaction_isolation").fetchone()[0] == "repeatable read"
        assert snapshot.execute("SHOW transaction_read_only").fetchone()[0] == "on"
    with pytest.raises(MemoryTransactionError):
        with storage.memories.read_snapshot() as snapshot:
            snapshot.execute(
                "UPDATE oryx_memory.memory_namespaces SET revision=2 WHERE workspace_id=%s", (WORKSPACE,),
            )


def test_multiple_actions_commit_current_history_revision_and_receipt_together(pg_harness):
    storage = system(pg_harness)
    operation, request = identity(106, "一次保存产生两个事实")
    lease = claim(storage, operation, request, 206)
    context, result = stage(operation, 0, (add(303, "事实一"), add(304, "事实二")))
    receipt = storage.memories.commit(lease, context, result)
    connection = storage.connection()
    rows = connection.execute(
        "SELECT action_index,event,new_content,previous_version_id FROM oryx_memory.memory_versions "
        "WHERE workspace_id=%s AND operation_id=%s ORDER BY action_index", (WORKSPACE, operation.operation_id),
    ).fetchall()
    assert rows == [(0, "ADD", "事实一", None), (1, "ADD", "事实二", None)]
    assert receipt.action_counts.total == 2 and receipt.history_complete is True
    stored = storage.operations.get(WORKSPACE, operation.operation_id)
    assert stored.to_dict() == receipt.to_dict()
    assert connection.execute(
        "SELECT count(*) FROM oryx_memory.memory_current WHERE workspace_id=%s", (WORKSPACE,),
    ).fetchone()[0] == 2


def test_same_operation_multi_step_updates_chain_the_actual_previous_version(pg_harness):
    storage = system(pg_harness)
    original, original_request = identity(117, "初始事实")
    original_lease = claim(storage, original, original_request, 217)
    original_context, original_result = stage(original, 0, (add(310, "Java17"),))
    storage.memories.commit(original_lease, original_context, original_result)
    previous = storage.connection().execute(
        "SELECT version_id::text FROM oryx_memory.memory_current WHERE workspace_id=%s AND memory_id=%s",
        (WORKSPACE, uuid_value(310)),
    ).fetchone()[0]

    operation, request = identity(118, "Java17先改为Java20再改为Java21")
    lease = claim(storage, operation, request, 218)
    changes = (
        Change("UPDATE", uuid_value(310), "Java17", "Java20", (0.8, 0.2), previous),
        Change("UPDATE", uuid_value(310), "Java20", "Java21", (1.0, 0.0), previous),
    )
    context, result = stage(operation, 1, changes)
    storage.memories.commit(lease, context, result)
    rows = storage.connection().execute(
        "SELECT version_id::text,previous_version_id::text,new_content FROM oryx_memory.memory_versions "
        "WHERE workspace_id=%s AND operation_id=%s ORDER BY action_index",
        (WORKSPACE, operation.operation_id),
    ).fetchall()
    assert rows[0][1:] == (previous, "Java20")
    assert rows[1][1:] == (rows[0][0], "Java21")
    assert storage.connection().execute(
        "SELECT content FROM oryx_memory.memory_current WHERE workspace_id=%s AND memory_id=%s",
        (WORKSPACE, uuid_value(310)),
    ).fetchone()[0] == "Java21"


def test_archival_delete_removes_current_but_keeps_tombstone_history(pg_harness):
    storage = system(pg_harness)
    original, original_request = identity(123, "待删除事实")
    original_lease = claim(storage, original, original_request, 223)
    original_context, original_result = stage(original, 0, (add(312, "旧事实"),))
    storage.memories.commit(original_lease, original_context, original_result)
    previous = storage.connection().execute(
        "SELECT version_id::text FROM oryx_memory.memory_current WHERE memory_id=%s", (uuid_value(312),),
    ).fetchone()[0]
    operation, request = identity(124, "删除旧事实")
    lease = claim(storage, operation, request, 224)
    context, result = stage(operation, 1, (
        Change("DELETE", uuid_value(312), "旧事实", None, None, previous),
    ))
    storage.memories.commit(lease, context, result)
    connection = storage.connection()
    assert connection.execute("SELECT count(*) FROM oryx_memory.memory_current").fetchone()[0] == 0
    tombstone = connection.execute(
        "SELECT event,old_content,new_content,previous_version_id::text "
        "FROM oryx_memory.memory_versions WHERE operation_id=%s", (operation.operation_id,),
    ).fetchone()
    assert tombstone == ("DELETE", "旧事实", None, previous)


def test_core_save_is_single_raw_add_without_embedding(pg_harness):
    storage = system(pg_harness)
    operation, request = identity(125, "核心原文", "CORE")
    lease = claim(storage, operation, request, 225)
    context = RunContext(WORKSPACE, operation.operation_id, "CORE", 0, 2, monotonic() + 20)
    result = StagedResult(WORKSPACE, operation.operation_id, 0, (
        Change("ADD", uuid_value(313), None, "核心原文", None, None),
    ), (), 1)
    receipt = storage.memories.commit(lease, context, result)
    row = storage.connection().execute(
        "SELECT scope,content,embedding,embedding_model FROM oryx_memory.memory_current"
    ).fetchone()
    assert row == ("CORE", "核心原文", None, None)
    assert receipt.action_counts == contracts.ActionCounts(1, 0, 0)


def test_legal_archival_noop_increments_revision_without_fabricating_history(pg_harness):
    storage = system(pg_harness)
    operation, request = identity(126, "无需变化")
    lease = claim(storage, operation, request, 226)
    context, result = stage(operation, 0, ())
    receipt = storage.memories.commit(lease, context, result)
    assert receipt.outcome == "NOOP" and receipt.action_counts.total == 0 and receipt.affected_ids == ()
    connection = storage.connection()
    assert connection.execute("SELECT revision FROM oryx_memory.memory_namespaces").fetchone()[0] == 1
    assert connection.execute("SELECT count(*) FROM oryx_memory.memory_versions").fetchone()[0] == 0
    assert connection.execute("SELECT count(*) FROM oryx_memory.memory_current").fetchone()[0] == 0


def test_history_failure_rolls_back_projection_revision_and_writes_failed_receipt(pg_harness):
    storage = system(pg_harness)
    operation, request = identity(107, "历史失败不能覆盖")
    lease = claim(storage, operation, request, 207)
    connection = storage.connection()
    connection.execute(
        "CREATE FUNCTION oryx_memory.reject_history() RETURNS trigger LANGUAGE plpgsql AS "
        "$$BEGIN RAISE EXCEPTION 'synthetic history failure'; END$$"
    )
    connection.execute(
        "CREATE TRIGGER reject_history BEFORE INSERT ON oryx_memory.memory_versions "
        "FOR EACH ROW EXECUTE FUNCTION oryx_memory.reject_history()"
    )
    context, result = stage(operation, 0, (add(305),))
    with pytest.raises(HistoryUnavailable):
        storage.memories.commit(lease, context, result)
    assert connection.execute("SELECT count(*) FROM oryx_memory.memory_current").fetchone()[0] == 0
    assert connection.execute("SELECT count(*) FROM oryx_memory.memory_versions").fetchone()[0] == 0
    assert connection.execute("SELECT revision FROM oryx_memory.memory_namespaces WHERE workspace_id=%s", (WORKSPACE,)).fetchone()[0] == 0
    failed = storage.operations.get(WORKSPACE, operation.operation_id)
    assert isinstance(failed, FailureReceipt) and failed.error_code == "HISTORY_UNAVAILABLE"
    assert failed.memory_effects_applied is False
    assert failed.identity == operation
    assert connection.execute(
        "SELECT raw_input FROM oryx_memory.memory_operations WHERE workspace_id=%s AND operation_id=%s",
        (WORKSPACE, operation.operation_id),
    ).fetchone()[0] == request.text


def test_audit_completion_failure_latches_context_and_blocks_business_commit(pg_harness):
    storage = system(pg_harness)
    operation, request = identity(108, "审计失败不能提交")
    lease = claim(storage, operation, request, 208)
    call_id = storage.audits.begin(
        WORKSPACE, operation.operation_id, "LLM", "FACT_EXTRACTION", "internal", "model", {"input": "合成"},
    )
    connection = storage.connection()
    connection.execute(
        "CREATE FUNCTION oryx_memory.reject_audit_finish() RETURNS trigger LANGUAGE plpgsql AS "
        "$$BEGIN RAISE EXCEPTION 'synthetic audit failure'; END$$"
    )
    connection.execute(
        "CREATE TRIGGER reject_audit_finish BEFORE UPDATE ON oryx_memory.memory_call_audits "
        "FOR EACH ROW EXECUTE FUNCTION oryx_memory.reject_audit_finish()"
    )
    with pytest.raises(AuditUnavailable):
        storage.audits.finish(call_id, "COMPLETED", {"facts": ["合成"]})
    context, result = stage(operation, 0, (add(306),))
    context.latch(Code.AUDIT_FAILURE)
    with pytest.raises(AuditUnavailable):
        storage.memories.commit(lease, context, result)
    assert connection.execute("SELECT count(*) FROM oryx_memory.memory_current").fetchone()[0] == 0
    failed = storage.operations.get(WORKSPACE, operation.operation_id)
    assert isinstance(failed, FailureReceipt) and failed.error_code == "AUDIT_UNAVAILABLE"


def test_call_audits_persist_usage_and_keep_unreported_usage_null(pg_harness):
    storage = system(pg_harness)
    operation, request = identity(127, "审计持久化")
    storage.operations.register(operation, request.text)
    completed = storage.audits.begin(
        WORKSPACE, operation.operation_id, "LLM", "FACT_EXTRACTION", "internal", "model", {"input": "合成"},
    )
    storage.audits.finish(
        completed, "COMPLETED", {"facts": ["Java21"]},
        {"prompt_tokens": 10, "completion_tokens": 2, "total_tokens": 12}, latency_ms=7,
    )
    failed = storage.audits.begin(
        WORKSPACE, operation.operation_id, "EMBEDDING", "QUERY", "internal", "embedding", ["合成"],
    )
    storage.audits.finish(failed, "FAILED", None, None, "SERVICE_FAILURE", latency_ms=3)
    rows = storage.connection().execute(
        "SELECT call_index,kind,state,latency_ms,prompt_tokens,completion_tokens,total_tokens,error_code "
        "FROM oryx_memory.memory_call_audits ORDER BY call_index"
    ).fetchall()
    assert rows == [
        (1, "LLM", "COMPLETED", 7, 10, 2, 12, None),
        (2, "EMBEDDING", "FAILED", 3, None, None, None, "SERVICE_FAILURE"),
    ]


def test_persistent_audit_begin_failure_prevents_wrapped_io(pg_harness, fault_plan):
    storage = system(pg_harness, fault_plan.arm("audit_started"))
    operation, request = identity(128, "开始审计失败")
    storage.operations.register(operation, request.text)
    context = RunContext(WORKSPACE, operation.operation_id, "ARCHIVAL", 0, 2, monotonic() + 20)
    auditor = CallAuditor(context, storage.audits)
    calls = []
    with pytest.raises(EngineError) as failure:
        auditor.invoke(
            "LLM", "FACT_EXTRACTION", "model", {"input": "合成"},
            lambda: calls.append("sent") or CallValue("ok", {"facts": []}, None),
        )
    assert failure.value.code == Code.AUDIT_FAILURE and calls == []
    assert storage.connection().execute("SELECT count(*) FROM oryx_memory.memory_call_audits").fetchone()[0] == 0


@pytest.mark.parametrize(("content", "failure", "error_code"), [
    ("before\x00after", InvalidGeneratedResult, "ENGINE_INVALID_RESULT"),
    ("界" * 10923, ResultLimitExceeded, "ENGINE_LIMIT_EXCEEDED"),
], ids=["nul", "utf8-limit"])
def test_generated_content_rejected_before_commit_and_projection_stays_empty(
        pg_harness, content, failure, error_code):
    storage = system(pg_harness)
    operation, request = identity(109 if "\x00" in content else 110, "生成结果非法")
    lease = claim(storage, operation, request, 209 if "\x00" in content else 210)
    context, result = stage(operation, 0, (add(307, content),))
    with pytest.raises(failure):
        storage.memories.commit(lease, context, result)
    connection = storage.connection()
    assert connection.execute("SELECT count(*) FROM oryx_memory.memory_current").fetchone()[0] == 0
    assert connection.execute("SELECT count(*) FROM oryx_memory.memory_versions").fetchone()[0] == 0
    receipt = storage.operations.get(WORKSPACE, operation.operation_id)
    assert isinstance(receipt, FailureReceipt) and receipt.error_code == error_code
    assert receipt.memory_effects_applied is False


def test_full_success_receipt_budget_checked_before_any_business_commit(pg_harness, monkeypatch):
    storage = system(pg_harness)
    operation, request = identity(111, "完整凭据超限")
    lease = claim(storage, operation, request, 211)
    context, result = stage(operation, 0, tuple(add(400 + index, f"事实{index}") for index in range(8)))
    # 固定失败凭据仍可读取，但含8个affected ID的成功凭据必须超限。
    monkeypatch.setattr(contracts, "MAX_IO_BYTES", 512)
    with pytest.raises(ResultLimitExceeded):
        storage.memories.commit(lease, context, result)
    connection = storage.connection()
    assert connection.execute("SELECT count(*) FROM oryx_memory.memory_current").fetchone()[0] == 0
    assert connection.execute("SELECT count(*) FROM oryx_memory.memory_versions").fetchone()[0] == 0
    failed = storage.operations.get(WORKSPACE, operation.operation_id)
    assert isinstance(failed, FailureReceipt) and failed.error_code == "ENGINE_LIMIT_EXCEEDED"


def test_committed_then_response_lost_is_queryable_and_never_overwritten_failed(pg_harness, fault_plan):
    storage = system(pg_harness, fault_plan.arm("after_commit_before_response"))
    operation, request = identity(112, "提交后应答丢失")
    lease = claim(storage, operation, request, 212)
    context, result = stage(operation, 0, (add(308),))
    with pytest.raises(RuntimeError, match="after_commit_before_response"):
        storage.memories.commit(lease, context, result)
    stored = storage.operations.get(WORKSPACE, operation.operation_id)
    assert isinstance(stored, SaveReceipt) and stored.history_complete is True
    replay = storage.operations.register(operation, request.text)
    assert replay.to_dict() == stored.to_dict()
    assert storage.connection().execute("SELECT count(*) FROM oryx_memory.memory_versions").fetchone()[0] == 1


def test_committed_state_survives_storage_component_reconstruction(pg_harness):
    storage = system(pg_harness)
    operation, request = identity(130, "组件重建后仍存在")
    lease = claim(storage, operation, request, 230)
    context, result = stage(operation, 0, (add(315, "持久事实"),))
    committed = storage.memories.commit(lease, context, result)
    rebuilt = system(pg_harness)
    assert rebuilt.operations.get(WORKSPACE, operation.operation_id).to_dict() == committed.to_dict()
    assert rebuilt.connection().execute("SELECT content FROM oryx_memory.memory_current").fetchone()[0] == "持久事实"
    assert rebuilt.connection().execute("SELECT count(*) FROM oryx_memory.memory_versions").fetchone()[0] == 1


@pytest.mark.parametrize("point", ["current_write", "receipt_write", "before_commit"])
def test_any_precommit_write_failure_rolls_back_all_business_state(
        pg_harness, fault_plan, point):
    storage = system(pg_harness, fault_plan.arm(point))
    operation, request = identity(122, "提交前故障必须全回滚")
    lease = claim(storage, operation, request, 222)
    context, result = stage(operation, 0, (add(311),))
    with pytest.raises(MemoryTransactionError):
        storage.memories.commit(lease, context, result)
    connection = storage.connection()
    assert connection.execute("SELECT revision FROM oryx_memory.memory_namespaces").fetchone()[0] == 0
    assert connection.execute("SELECT count(*) FROM oryx_memory.memory_versions").fetchone()[0] == 0
    assert connection.execute("SELECT count(*) FROM oryx_memory.memory_current").fetchone()[0] == 0
    failed = storage.operations.get(WORKSPACE, operation.operation_id)
    assert isinstance(failed, FailureReceipt) and failed.error_code == "SERVICE_FAILURE"
    assert failed.memory_effects_applied is False


def test_recovery_aborts_expired_operations_marks_started_calls_unknown_and_fences_late_owner(pg_harness):
    storage = system(pg_harness)
    received, received_request = identity(113, "登记后崩溃")
    running, running_request = identity(114, "运行中崩溃")
    storage.operations.register(received, received_request.text)
    lease = claim(storage, running, running_request, 214)
    call_id = storage.audits.begin(
        WORKSPACE, running.operation_id, "LLM", "FACT_EXTRACTION", "internal", "model", {"input": "合成"},
    )
    storage.connection().execute(
        "UPDATE oryx_memory.memory_operations SET deadline_at=clock_timestamp()-interval '1 second' "
        "WHERE workspace_id=%s", (WORKSPACE,),
    )
    assert storage.operations.recover_expired(WORKSPACE) == 2
    assert storage.audits.recover_unknown(WORKSPACE) == 1
    assert storage.operations.recover_expired(WORKSPACE) == 0
    assert storage.audits.recover_unknown(WORKSPACE) == 0
    for operation in (received, running):
        receipt = storage.operations.get(WORKSPACE, operation.operation_id)
        assert isinstance(receipt, FailureReceipt) and receipt.state == "ABORTED"
        assert receipt.memory_effects_applied is False
    audit_state = storage.connection().execute(
        "SELECT state FROM oryx_memory.memory_call_audits WHERE call_id=%s", (call_id,),
    ).fetchone()[0]
    assert audit_state == "UNKNOWN"
    context, result = stage(running, lease.baseline_revision, (add(309),))
    with pytest.raises(WriteConflict):
        storage.memories.commit(lease, context, result)


def test_recovery_never_overwrites_committed_operation(pg_harness):
    storage = system(pg_harness)
    operation, request = identity(129, "已提交不可覆盖")
    lease = claim(storage, operation, request, 229)
    context, result = stage(operation, 0, (add(314),))
    committed = storage.memories.commit(lease, context, result)
    storage.connection().execute(
        "UPDATE oryx_memory.memory_operations SET deadline_at=clock_timestamp()-interval '1 second' "
        "WHERE workspace_id=%s AND operation_id=%s", (WORKSPACE, operation.operation_id),
    )
    assert storage.operations.recover_expired(WORKSPACE) == 0
    assert storage.operations.get(WORKSPACE, operation.operation_id).to_dict() == committed.to_dict()


def test_recall_completion_persists_receipt_without_advancing_revision_or_history(pg_harness):
    storage = system(pg_harness)
    saved, saved_request = identity(131, "召回目标")
    saved_lease = claim(storage, saved, saved_request, 231)
    saved_context, saved_result = stage(saved, 0, (add(316, "召回目标"),))
    storage.memories.commit(saved_lease, saved_context, saved_result)
    recall_request = Request("RECALL", "ARCHIVAL", "目标")
    recall = OperationIdentity.from_request(WORKSPACE, uuid_value(132), recall_request)
    recall_lease = claim(storage, recall, recall_request, 232)
    recall_context = RunContext(WORKSPACE, recall.operation_id, "ARCHIVAL", 1, 2, monotonic() + 20)
    receipt = storage.queries.recall(recall, (1.0, 0.0))
    storage.memories.complete_recall(recall_lease, recall_context, receipt)
    assert storage.operations.get(WORKSPACE, recall.operation_id).to_dict() == receipt.to_dict()
    connection = storage.connection()
    assert connection.execute("SELECT revision FROM oryx_memory.memory_namespaces").fetchone()[0] == 1
    assert connection.execute("SELECT count(*) FROM oryx_memory.memory_versions").fetchone()[0] == 1


def test_workspace_identity_is_part_of_every_operation_key(pg_harness):
    storage = system(pg_harness)
    operation, request = identity(115)
    storage.operations.register(operation, request.text)
    assert storage.operations.get(OTHER_WORKSPACE, operation.operation_id) is None
    row = storage.connection().execute(
        "SELECT workspace_id::text FROM oryx_memory.memory_operations WHERE operation_id=%s",
        (operation.operation_id,),
    ).fetchone()
    assert row[0] == WORKSPACE


def test_invalid_raw_input_and_owner_are_rejected_before_state_change(pg_harness):
    storage = system(pg_harness)
    operation, request = identity(119, "合法正文")
    with pytest.raises(OperationError):
        storage.operations.register(operation, "before\x00after")
    assert storage.connection().execute(
        "SELECT count(*) FROM oryx_memory.memory_operations"
    ).fetchone()[0] == 0
    storage.operations.register(operation, request.text)
    for invalid_owner in ("00000000-0000-0000-0000-000000000000", "not-a-uuid", None):
        with pytest.raises(OperationError):
            storage.operations.claim(operation, invalid_owner)
    pending = storage.operations.get(WORKSPACE, operation.operation_id)
    assert isinstance(pending, PendingReceipt) and pending.state == "RECEIVED"


def test_post_registration_and_post_claim_failures_preserve_durable_state(pg_harness, fault_plan):
    storage = system(pg_harness, fault_plan.arm("operation_registered"))
    operation, request = identity(120, "故障后仍可恢复")
    with pytest.raises(RuntimeError, match="operation_registered"):
        storage.operations.register(operation, request.text)
    received = storage.operations.get(WORKSPACE, operation.operation_id)
    assert isinstance(received, PendingReceipt) and received.state == "RECEIVED"

    fault_plan.arm("owner_claimed")
    with pytest.raises(RuntimeError, match="owner_claimed"):
        storage.operations.claim(operation, uuid_value(220))
    running = storage.operations.get(WORKSPACE, operation.operation_id)
    assert isinstance(running, PendingReceipt) and running.state == "RUNNING"
    assert running.deadline_at == received.deadline_at
    assert storage.operations.claim(operation, uuid_value(221)) is None


def test_recall_snapshot_may_be_newer_than_claim_baseline(pg_harness):
    storage = system(pg_harness)
    saved, saved_request = identity(131, "召回目标")
    saved_lease = claim(storage, saved, saved_request, 231)
    saved_context, saved_result = stage(saved, 0, (add(316, "召回目标"),))
    storage.memories.commit(saved_lease, saved_context, saved_result)
    recall_request = Request("RECALL", "ARCHIVAL", "目标")
    recall = OperationIdentity.from_request(WORKSPACE, uuid_value(132), recall_request)
    recall_lease = claim(storage, recall, recall_request, 232)
    assert recall_lease.baseline_revision == 1
    # 认领后并发 SAVE 把命名空间推进到 revision=2
    second, second_request = identity(133, "并发保存")
    second_lease = claim(storage, second, second_request, 233)
    second_context, second_result = stage(second, 1, (add(317, "并发保存"),))
    storage.memories.commit(second_lease, second_context, second_result)
    # 只读快照晚于 baseline 不得伪失败；revision 与条目来自同一快照
    recall_context = RunContext(WORKSPACE, recall.operation_id, "ARCHIVAL", 1, 2, monotonic() + 20)
    receipt = storage.queries.recall(recall, (1.0, 0.0))
    assert receipt.revision == 2 and receipt.snapshot_revision == 2
    assert {item.content for item in receipt.items} == {"召回目标", "并发保存"}
    storage.memories.complete_recall(recall_lease, recall_context, receipt)
    persisted = storage.operations.get(WORKSPACE, recall.operation_id).to_dict()
    assert persisted["state"] == "COMMITTED" and persisted["revision"] == 2


def test_recall_revision_cannot_regress_below_claim_baseline(pg_harness):
    import dataclasses
    storage = system(pg_harness)
    saved, saved_request = identity(141, "基线目标")
    saved_lease = claim(storage, saved, saved_request, 241)
    saved_context, saved_result = stage(saved, 0, (add(416, "基线目标"),))
    storage.memories.commit(saved_lease, saved_context, saved_result)
    recall_request = Request("RECALL", "ARCHIVAL", "目标")
    recall = OperationIdentity.from_request(WORKSPACE, uuid_value(142), recall_request)
    recall_lease = claim(storage, recall, recall_request, 242)
    receipt = storage.queries.recall(recall, (1.0, 0.0))
    regressed = dataclasses.replace(receipt, revision=receipt.revision - 1,
                                    snapshot_revision=receipt.snapshot_revision - 1)
    recall_context = RunContext(WORKSPACE, recall.operation_id, "ARCHIVAL", 1, 2, monotonic() + 20)
    with pytest.raises(InvalidGeneratedResult):
        storage.memories.complete_recall(recall_lease, recall_context, regressed)


def test_staging_snapshot_reports_real_created_and_updated_timestamps(pg_harness):
    from oryx_mem0.runtime import PgReadonlySnapshot
    storage = system(pg_harness)
    original, original_request = identity(151, "初版内容")
    lease = claim(storage, original, original_request, 251)
    context, result = stage(original, 0, (add(351, "初版内容"),))
    storage.memories.commit(lease, context, result)
    connection = storage.connection()
    previous = connection.execute(
        "SELECT version_id::text FROM oryx_memory.memory_current WHERE workspace_id=%s AND memory_id=%s",
        (WORKSPACE, uuid_value(351)),
    ).fetchone()[0]
    update, update_request = identity(152, "更新后内容")
    update_lease = claim(storage, update, update_request, 252)
    update_context, update_result = stage(update, 1, (
        Change("UPDATE", uuid_value(351), "初版内容", "更新后内容", (1.0, 0.0), previous),))
    storage.memories.commit(update_lease, update_context, update_result)
    # 推理读取路径与运行时一致：REPEATABLE READ 只读快照
    with storage.memories.read_snapshot() as snapshot_connection:
        snapshot = PgReadonlySnapshot(snapshot_connection,
            RunContext(WORKSPACE, uuid_value(153), "ARCHIVAL", 2, 2, monotonic() + 20))
        point = snapshot.get(uuid_value(351))
        # search 路径同列布局：覆盖真实切片与距离排序
        found = snapshot.search((1.0, 0.0), 5, {})
        assert [item.id for item in found] == [uuid_value(351)]
        assert found[0].payload["created_at"] == point.payload["created_at"]
    assert point is not None and point.payload["data"] == "更新后内容"
    created = connection.execute(
        "SELECT changed_at FROM oryx_memory.memory_versions WHERE workspace_id=%s AND memory_id=%s AND event='ADD'",
        (WORKSPACE, uuid_value(351))).fetchone()[0]
    from datetime import timezone
    assert point.payload["created_at"] == created.astimezone(timezone.utc).isoformat()
    assert point.payload["updated_at"] > point.payload["created_at"]


def test_real_connection_loss_at_commit_never_fakes_success(pg_harness):
    class KillBackend:
        def __init__(self, killer):
            self.killer = killer
            # 只终止测试开始后才出现的后端，绝不动 fixture 的核验/清理连接
            self.existing = {row[0] for row in killer.execute(
                "SELECT pid FROM pg_stat_activity WHERE usename=current_user")}

        def hit(self, point):
            if point == "before_commit":
                # 真实终止提交连接：服务端回滚，客户端只能在下次I/O发现
                self.killer.execute(
                    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                    "WHERE usename=current_user AND pid<>pg_backend_pid() AND pid<>ALL(%s)",
                    (list(self.existing),))
    killer = pg_harness.open(autocommit=True)
    storage = system(pg_harness, KillBackend(killer))
    operation, request = identity(171, "提交期连接丢失")
    lease = claim(storage, operation, request, 271)
    context, result = stage(operation, 0, (add(361, "提交期连接丢失"),))
    with pytest.raises(MemoryTransactionError):
        storage.memories.commit(lease, context, result)
    connection = storage.connection()
    assert connection.execute("SELECT count(*) FROM oryx_memory.memory_versions").fetchone()[0] == 0
    assert connection.execute("SELECT count(*) FROM oryx_memory.memory_current").fetchone()[0] == 0
    persisted = storage.operations.get(WORKSPACE, operation.operation_id)
    assert isinstance(persisted, FailureReceipt) and persisted.state == "FAILED"
    assert persisted.error_code == "SERVICE_FAILURE" and persisted.memory_effects_applied is False
    replay = storage.operations.register(operation, request.text)
    assert isinstance(replay, FailureReceipt) and replay.state == "FAILED"
    with pytest.raises(RequestConflict):
        storage.operations.register(operation, "不同请求")


def test_real_connection_loss_while_running_recovers_aborted(pg_harness):
    storage = system(pg_harness)
    operation, request = identity(172, "运行期连接丢失")
    claim(storage, operation, request, 272)
    killer = storage.connection()
    existing = {row[0] for row in killer.execute(
        "SELECT pid FROM pg_stat_activity WHERE usename=current_user")}
    running_backend = storage.connection()  # 占住一条运行期连接再真实终止它
    killer.execute(
        "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
        "WHERE usename=current_user AND pid<>ALL(%s)", (list(existing),))
    with pytest.raises(psycopg.Error):
        running_backend.execute("SELECT 1")
    persisted = storage.operations.get(WORKSPACE, operation.operation_id)
    assert isinstance(persisted, PendingReceipt) and persisted.state == "RUNNING"
    storage.connection().execute(
        "UPDATE oryx_memory.memory_operations SET deadline_at=clock_timestamp()-interval '1 second' "
        "WHERE workspace_id=%s AND operation_id=%s", (WORKSPACE, operation.operation_id),
    )
    assert storage.operations.recover_expired(WORKSPACE) == 1
    recovered = storage.operations.get(WORKSPACE, operation.operation_id)
    assert isinstance(recovered, FailureReceipt) and recovered.state == "ABORTED"
    assert recovered.memory_effects_applied is False
    again = storage.operations.get(WORKSPACE, operation.operation_id)
    assert again.to_dict() == recovered.to_dict()
