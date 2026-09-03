"""显式建立并核验受控PostgreSQL结构，不修补未知业务库。"""

from pathlib import Path

import psycopg

SCHEMA_VERSION = 1
PG_VERSION = 170011
VECTOR_VERSION = "0.8.6"
MAX_VECTOR_DIMENSIONS = 16000
_SCHEMA = "oryx_memory"
_MIGRATION = Path(__file__).parents[3] / "migrations/001_initial.sql"
_TABLES = frozenset({
    "memory_namespaces", "memory_operations", "memory_versions", "memory_current", "memory_call_audits",
})
_INDEXES = frozenset({
    "idx_memory_operations_state_deadline", "idx_memory_versions_workspace_memory_revision",
    "idx_memory_versions_workspace_scope_revision", "idx_memory_current_workspace_scope_created",
    "idx_memory_current_workspace_scope_updated", "idx_memory_call_audits_operation",
    "idx_memory_call_audits_state",
})
_CONSTRAINT_INDEXES = frozenset({
    "pk_memory_namespaces", "pk_memory_operations", "pk_memory_versions", "uq_memory_versions_action",
    "uq_memory_versions_identity", "pk_memory_current", "pk_memory_call_audits",
    "uq_memory_call_audits_index",
})
_CONSTRAINTS = frozenset({
    "pk_memory_namespaces", "ck_memory_namespaces_workspace", "ck_memory_namespaces_revision",
    "pk_memory_operations", "fk_memory_operations_namespace", "ck_memory_operations_workspace",
    "ck_memory_operations_id", "ck_memory_operations_kind", "ck_memory_operations_scope",
    "ck_memory_operations_recall_scope", "ck_memory_operations_hash", "ck_memory_operations_raw",
    "ck_memory_operations_state", "ck_memory_operations_baseline", "ck_memory_operations_committed",
    "ck_memory_operations_owner", "ck_memory_operations_result", "ck_memory_operations_terminal",
    "pk_memory_versions", "uq_memory_versions_action", "uq_memory_versions_identity",
    "fk_memory_versions_operation", "fk_memory_versions_previous", "ck_memory_versions_ids",
    "ck_memory_versions_scope", "ck_memory_versions_action", "ck_memory_versions_revision",
    "ck_memory_versions_event", "ck_memory_versions_core", "ck_memory_versions_content",
    "ck_memory_versions_shape", "pk_memory_current", "fk_memory_current_version",
    "ck_memory_current_ids", "ck_memory_current_scope", "ck_memory_current_content",
    "ck_memory_current_revision", "ck_memory_current_embedding", "pk_memory_call_audits",
    "uq_memory_call_audits_index", "fk_memory_call_audits_operation", "ck_memory_call_audits_ids",
    "ck_memory_call_audits_index", "ck_memory_call_audits_kind", "ck_memory_call_audits_text",
    "ck_memory_call_audits_state", "ck_memory_call_audits_latency", "ck_memory_call_audits_tokens",
    "ck_memory_call_audits_json",
})


class SchemaError(RuntimeError):
    """结构异常只暴露固定分类，不回显数据库正文。"""

    _CODES = frozenset({"DIMENSIONS", "RUNTIME", "UNKNOWN_SCHEMA", "STRUCTURE", "MIGRATION"})

    def __init__(self, code):
        self.code = code if isinstance(code, str) and code in self._CODES else "STRUCTURE"
        super().__init__("记忆数据库结构无效：" + self.code)


def _dimensions(value):
    if type(value) is not int or not 1 <= value <= MAX_VECTOR_DIMENSIONS:
        raise SchemaError("DIMENSIONS")
    return value


def _marker(dimensions):
    return f"oryx-memory-v1;schema={SCHEMA_VERSION};dimensions={dimensions}"


def _runtime(connection):
    version = connection.execute("SELECT current_setting('server_version_num')::integer").fetchone()
    vector = connection.execute("SELECT extversion FROM pg_extension WHERE extname='vector'").fetchone()
    if version is None or version[0] != PG_VERSION or vector is None or vector[0] != VECTOR_VERSION:
        raise SchemaError("RUNTIME")


def _schema_exists(connection):
    return connection.execute("SELECT to_regnamespace(%s) IS NOT NULL", (_SCHEMA,)).fetchone()[0]


def _expected_columns(dimensions):
    timestamptz = "timestamp with time zone"
    return {
        "memory_namespaces": {
            "workspace_id": ("uuid", True), "revision": ("bigint", True),
            "created_at": (timestamptz, True),
        },
        "memory_operations": {
            "workspace_id": ("uuid", True), "operation_id": ("uuid", True), "kind": ("text", True),
            "scope": ("text", True), "request_hash": ("text", True), "raw_input": ("text", True),
            "state": ("text", True), "baseline_revision": ("bigint", False),
            "committed_revision": ("bigint", False), "owner_token": ("uuid", False),
            "deadline_at": (timestamptz, True), "result_json": ("jsonb", False),
            "error_code": ("text", False), "created_at": (timestamptz, True),
            "started_at": (timestamptz, False), "completed_at": (timestamptz, False),
        },
        "memory_versions": {
            "version_id": ("uuid", True), "workspace_id": ("uuid", True), "memory_id": ("uuid", True),
            "scope": ("text", True), "operation_id": ("uuid", True), "action_index": ("integer", True),
            "revision": ("bigint", True), "created_revision": ("bigint", True), "event": ("text", True),
            "old_content": ("text", False), "new_content": ("text", False),
            "previous_version_id": ("uuid", False), "changed_at": (timestamptz, True),
        },
        "memory_current": {
            "workspace_id": ("uuid", True), "memory_id": ("uuid", True), "scope": ("text", True),
            "content": ("text", True), "version_id": ("uuid", True),
            "created_revision": ("bigint", True), "updated_revision": ("bigint", True),
            "embedding": (f"vector({dimensions})", False), "embedding_model": ("text", False),
            "updated_at": (timestamptz, True),
        },
        "memory_call_audits": {
            "call_id": ("uuid", True), "workspace_id": ("uuid", True), "operation_id": ("uuid", True),
            "call_index": ("integer", True), "kind": ("text", True), "phase": ("text", True),
            "provider": ("text", True), "model": ("text", True), "state": ("text", True),
            "started_at": (timestamptz, True), "completed_at": (timestamptz, False),
            "latency_ms": ("bigint", False), "prompt_tokens": ("bigint", False),
            "completion_tokens": ("bigint", False), "total_tokens": ("bigint", False),
            "request_json": ("jsonb", False), "response_json": ("jsonb", False),
            "error_code": ("text", False),
        },
    }


def _actual_columns(connection):
    rows = connection.execute(
        "SELECT c.relname,a.attname,format_type(a.atttypid,a.atttypmod),a.attnotnull "
        "FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
        "JOIN pg_attribute a ON a.attrelid=c.oid "
        "WHERE n.nspname=%s AND c.relkind='r' AND a.attnum>0 AND NOT a.attisdropped "
        "ORDER BY c.relname,a.attnum", (_SCHEMA,),
    ).fetchall()
    result = {}
    for table, column, data_type, not_null in rows:
        result.setdefault(table, {})[column] = (data_type, not_null)
    return result


def _validate_objects(connection, dimensions):
    marker = connection.execute(
        "SELECT obj_description(oid,'pg_namespace') FROM pg_namespace WHERE nspname=%s", (_SCHEMA,),
    ).fetchone()
    if marker is None or marker[0] != _marker(dimensions):
        raise SchemaError("UNKNOWN_SCHEMA")
    tables = frozenset(row[0] for row in connection.execute(
        "SELECT tablename FROM pg_tables WHERE schemaname=%s", (_SCHEMA,),
    ).fetchall())
    if tables != _TABLES or _actual_columns(connection) != _expected_columns(dimensions):
        raise SchemaError("STRUCTURE")
    constraints = frozenset(row[0] for row in connection.execute(
        "SELECT conname FROM pg_constraint p JOIN pg_class c ON c.oid=p.conrelid "
        "JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=%s", (_SCHEMA,),
    ).fetchall())
    if constraints != _CONSTRAINTS:
        raise SchemaError("STRUCTURE")
    indexes = frozenset(row[0] for row in connection.execute(
        "SELECT indexname FROM pg_indexes WHERE schemaname=%s", (_SCHEMA,),
    ).fetchall())
    if indexes != _INDEXES | _CONSTRAINT_INDEXES:
        raise SchemaError("STRUCTURE")
    triggers = connection.execute(
        "SELECT t.tgname,t.tgenabled FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid "
        "JOIN pg_namespace n ON n.oid=c.relnamespace "
        "WHERE n.nspname=%s AND NOT t.tgisinternal", (_SCHEMA,),
    ).fetchall()
    if triggers != [("trg_memory_versions_immutable", "O")]:
        raise SchemaError("STRUCTURE")
    functions = connection.execute(
        "SELECT p.proname,p.prosecdef,p.provolatile,pg_get_function_result(p.oid),l.lanname,p.prosrc "
        "FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace "
        "JOIN pg_language l ON l.oid=p.prolang WHERE n.nspname=%s", (_SCHEMA,),
    ).fetchall()
    if len(functions) != 1:
        raise SchemaError("STRUCTURE")
    name, security_definer, volatility, result_type, language, source = functions[0]
    normalized_source = " ".join(source.split())
    expected_source = (
        "BEGIN RAISE EXCEPTION '记忆历史禁止更新或删除' USING ERRCODE = '55000'; END;"
    )
    if ((name, security_definer, volatility, result_type, language)
            != ("reject_memory_version_mutation", False, "v", "trigger", "plpgsql")
            or normalized_source != expected_source):
        raise SchemaError("STRUCTURE")
    public_tables = connection.execute(
        "SELECT count(*) FROM information_schema.table_privileges "
        "WHERE table_schema=%s AND grantee='PUBLIC'", (_SCHEMA,),
    ).fetchone()[0]
    public_schema = connection.execute(
        "SELECT count(*) FROM aclexplode((SELECT nspacl FROM pg_namespace WHERE nspname=%s)) "
        "WHERE grantee=0", (_SCHEMA,),
    ).fetchone()[0]
    public_functions = connection.execute(
        "SELECT count(*) FROM information_schema.routine_privileges "
        "WHERE routine_schema=%s AND grantee='PUBLIC'", (_SCHEMA,),
    ).fetchone()[0]
    public_defaults = connection.execute(
        "SELECT count(*) FROM pg_default_acl d JOIN pg_namespace n ON n.oid=d.defaclnamespace "
        "CROSS JOIN LATERAL aclexplode(d.defaclacl) a WHERE n.nspname=%s AND a.grantee=0", (_SCHEMA,),
    ).fetchone()[0]
    if public_tables != 0 or public_schema != 0 or public_functions != 0 or public_defaults != 0:
        raise SchemaError("STRUCTURE")


def validate_schema(connection, dimensions):
    """只读核验精确版本、维度和结构；任何差异都拒绝。"""
    checked = _dimensions(dimensions)
    try:
        _runtime(connection)
        if not _schema_exists(connection):
            raise SchemaError("UNKNOWN_SCHEMA")
        _validate_objects(connection, checked)
    except SchemaError:
        raise
    except (psycopg.Error, TypeError, ValueError, UnicodeError):
        raise SchemaError("STRUCTURE") from None


def _migration_sql(dimensions):
    try:
        source = _MIGRATION.read_text(encoding="utf-8")
    except (OSError, UnicodeError):
        raise SchemaError("MIGRATION") from None
    if source.count("__ORYX_SCHEMA_MARKER__") != 1 or source.count("__ORYX_VECTOR_DIMENSIONS__") != 1:
        raise SchemaError("MIGRATION")
    return source.replace("__ORYX_SCHEMA_MARKER__", _marker(dimensions)).replace(
        "__ORYX_VECTOR_DIMENSIONS__", str(dimensions))


def apply_migrations(connection, dimensions):
    """只创建全新受控schema；已有schema仅核验，不自动修复。"""
    checked = _dimensions(dimensions)
    try:
        _runtime(connection)
        with connection.transaction():
            connection.execute("SELECT pg_advisory_xact_lock(%s,%s)", (667831458, SCHEMA_VERSION))
            if _schema_exists(connection):
                validate_schema(connection, checked)
                return
            connection.execute(_migration_sql(checked))
            validate_schema(connection, checked)
    except SchemaError:
        raise
    except (psycopg.Error, OSError, TypeError, ValueError, UnicodeError):
        raise SchemaError("MIGRATION") from None
