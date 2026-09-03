"""从显式配置组合可部署的受控适配器。"""

from datetime import timezone
import hashlib
import math
import os
from pathlib import Path

import psycopg

from oryx_mem0.app import ApiRuntime, create_app
from oryx_mem0.audit.calls import CallAuditor
from oryx_mem0.bootstrap import bootstrap
from oryx_mem0.contracts import CAPABILITY_LIMITS
from oryx_mem0.engine.providers import AuditedEmbedding, AuditedLlm
from oryx_mem0.engine.staging import Point
from oryx_mem0.security import Authenticator, TokenCodec
from oryx_mem0.services.operations import OperationService
from oryx_mem0.services.snapshots import SnapshotService
from oryx_mem0.storage.call_audits import CallAuditStore
from oryx_mem0.storage.memories import MemoryTransactionStore
from oryx_mem0.storage.migrations import apply_migrations, validate_schema
from oryx_mem0.storage.operations import OperationStore
from oryx_mem0.storage.queries import QueryStore


def _build_version():
    """构建版本 = 镜像内源码清单的实际字节摘要；清单缺失时拒绝启动，不伪造身份。"""
    path = Path(os.environ.get("ADAPTER_SOURCE_MANIFEST", "/app/source-manifest.txt"))
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError:
        raise ValueError("源码构建清单缺失，无法确定构建版本") from None


def create_application():
    boot = bootstrap()
    settings = boot.settings

    def connection_factory(autocommit=False):
        connection = psycopg.connect(settings.database_url, autocommit=autocommit)
        connection.execute("SET statement_timeout='30s'")
        connection.execute("SET lock_timeout='5s'")
        connection.execute("SET idle_in_transaction_session_timeout='30s'")
        if not autocommit:
            connection.commit()
        return connection

    migration = connection_factory(autocommit=True)
    try:
        apply_migrations(migration, settings.dimensions)
    finally:
        migration.close()
    operations = OperationStore(connection_factory)
    memories = MemoryTransactionStore(connection_factory, embedding_model=settings.embedding.model)
    audits = CallAuditStore(connection_factory)
    queries = QueryStore(connection_factory)

    def archive_stage(context, connection, content):
        auditor = CallAuditor(context, audits)
        engine = boot.staged_memory_type(
            context, PgReadonlySnapshot(connection, context),
            AuditedLlm(context, settings.llm, auditor),
            AuditedEmbedding(context, settings.embedding, auditor),
        )
        try:
            return engine.infer(content)
        finally:
            engine.close()

    def recall_stage(lease, context, query):
        auditor = CallAuditor(context, audits)
        vector = AuditedEmbedding(context, settings.embedding, auditor).embed(query, "search")
        return memories.complete_recall(lease, context, queries.recall(lease.identity, vector))

    def recover(workspace):
        operations.recover_expired(workspace)
        audits.recover_unknown(workspace)

    service = OperationService(operations, memories, archive_stage, recall_stage, settings.dimensions, recover)
    snapshots = SnapshotService(queries, TokenCodec(settings.cursor_secret))

    def startup_check():
        connection = connection_factory(autocommit=True)
        try:
            validate_schema(connection, settings.dimensions)
        finally:
            connection.close()
        for binding in settings.client_bindings:
            operations.recover_expired(binding.workspace_id)
            audits.recover_unknown(binding.workspace_id)

    capabilities = {
        "protocol": "oryx-memory-v1", "schema_version": 1, "sdk_version": "1.0.11+oryx.1",
        "staged_engine": True, "atomic_history": True, "revision_pagination": True,
        "build_version": _build_version(), "limits": dict(CAPABILITY_LIMITS),
    }
    return create_app(ApiRuntime(
        Authenticator(settings.client_bindings), service, snapshots, capabilities, startup_check))


class PgReadonlySnapshot:
    scope = "ARCHIVAL"

    def __init__(self, connection, context):
        self.connection = connection
        self.workspace_id = context.workspace_id
        self.revision = context.revision

    def get(self, memory_id):
        # created_at 必须取该记忆 ADD 版本的真实时间，不能用 updated_at 冒充。
        row = self.connection.execute(
            "SELECT c.content,c.embedding::text,c.version_id::text,c.created_revision,c.updated_revision,"
            "v.changed_at,c.updated_at "
            "FROM oryx_memory.memory_current c JOIN oryx_memory.memory_versions v "
            "ON v.workspace_id=c.workspace_id AND v.memory_id=c.memory_id "
            "AND v.revision=c.created_revision AND v.event='ADD' "
            "WHERE c.workspace_id=%s AND c.memory_id=%s AND c.scope='ARCHIVAL'",
            (self.workspace_id, memory_id),
        ).fetchone()
        return None if row is None else self._point(memory_id, row)

    def search(self, vector, limit, overlay):
        excluded = list(overlay)
        rows = self.connection.execute(
            "SELECT c.memory_id::text,c.content,c.embedding::text,c.version_id::text,c.created_revision,"
            "c.updated_revision,v.changed_at,c.updated_at,"
            "c.embedding <=> %s::vector distance FROM oryx_memory.memory_current c "
            "JOIN oryx_memory.memory_versions v ON v.workspace_id=c.workspace_id AND v.memory_id=c.memory_id "
            "AND v.revision=c.created_revision AND v.event='ADD' "
            "WHERE c.workspace_id=%s AND c.scope='ARCHIVAL' AND NOT(c.memory_id=ANY(%s::uuid[])) "
            "ORDER BY distance,c.memory_id LIMIT %s",
            (_vector_text(vector), self.workspace_id, excluded, limit),
        ).fetchall()
        candidates = [(self._point(row[0], row[1:8]), float(row[8])) for row in rows]
        for point in overlay.values():
            if point is None:
                continue
            distance = self.connection.execute(
                "SELECT %s::vector <=> %s::vector", (_vector_text(point.vector), _vector_text(vector)),
            ).fetchone()[0]
            candidates.append((point, float(distance)))
        candidates.sort(key=lambda item: (item[1], item[0].id))
        return [point for point, _ in candidates[:limit]]

    def _point(self, memory_id, row):
        content, vector, version_id, created_revision, updated_revision, created_at, updated_at = row
        return Point(memory_id, {
            "user_id": self.workspace_id, "agent_id": "ARCHIVAL", "scope": "ARCHIVAL",
            "data": content, "hash": hashlib.sha256(content.encode("utf-8")).hexdigest(),
            "created_at": created_at.astimezone(timezone.utc).isoformat(),
            "updated_at": updated_at.astimezone(timezone.utc).isoformat(),
        }, _parse_vector(vector), version_id)


def _parse_vector(value):
    return tuple(float(item) for item in value.strip("[]").split(","))


def _vector_text(value):
    if any(not math.isfinite(float(item)) for item in value):
        raise ValueError("记忆向量无效")
    return "[" + ",".join(str(float(item)) for item in value) + "]"
