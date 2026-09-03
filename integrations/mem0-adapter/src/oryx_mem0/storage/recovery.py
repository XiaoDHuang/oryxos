"""恢复只终结过期状态，不重新执行推理或删除历史。"""

from datetime import timezone

import psycopg
from psycopg.types.json import Jsonb

from oryx_mem0.contracts import FailureReceipt, OperationIdentity
from oryx_mem0.engine.staging import valid_uuid


class RecoveryError(RuntimeError):
    def __init__(self):
        super().__init__("记忆恢复失败")


def recover_expired_operations(connection_factory, workspace_id):
    if not valid_uuid(workspace_id):
        raise RecoveryError()
    connection = connection_factory()
    try:
        with connection.transaction():
            rows = connection.execute(
                "SELECT operation_id::text,kind,scope,request_hash FROM oryx_memory.memory_operations "
                "WHERE workspace_id=%s AND state IN ('RECEIVED','RUNNING') "
                "AND deadline_at<=clock_timestamp() FOR UPDATE SKIP LOCKED", (workspace_id,),
            ).fetchall()
            count = 0
            for operation_id, kind, scope, request_hash in rows:
                completed = connection.execute("SELECT clock_timestamp()").fetchone()[0]
                identity = OperationIdentity(workspace_id, operation_id, kind, scope, request_hash)
                receipt = FailureReceipt(
                    identity, "ABORTED", _utc_text(completed), "OPERATION_DEADLINE", False)
                count += connection.execute(
                    "UPDATE oryx_memory.memory_operations SET state='ABORTED',result_json=%s,"
                    "error_code='OPERATION_DEADLINE',completed_at=%s "
                    "WHERE workspace_id=%s AND operation_id=%s AND state IN ('RECEIVED','RUNNING') "
                    "AND deadline_at<=clock_timestamp()",
                    (Jsonb(receipt.to_dict()), completed, workspace_id, operation_id),
                ).rowcount
            return count
    except psycopg.Error:
        raise RecoveryError() from None
    finally:
        connection.close()


def recover_unknown_calls(connection_factory, workspace_id):
    if not valid_uuid(workspace_id):
        raise RecoveryError()
    connection = connection_factory()
    try:
        with connection.transaction():
            return connection.execute(
                "UPDATE oryx_memory.memory_call_audits a SET state='UNKNOWN',completed_at=clock_timestamp(),"
                "latency_ms=GREATEST(0,(EXTRACT(EPOCH FROM (clock_timestamp()-a.started_at))*1000)::bigint),"
                "error_code='OPERATION_DEADLINE' FROM oryx_memory.memory_operations o "
                "WHERE a.workspace_id=o.workspace_id AND a.operation_id=o.operation_id "
                "AND a.workspace_id=%s AND a.state='STARTED' AND o.state='ABORTED'",
                (workspace_id,),
            ).rowcount
    except psycopg.Error:
        raise RecoveryError() from None
    finally:
        connection.close()


def _utc_text(value):
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
