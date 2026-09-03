"""操作原文先独立登记；租约只允许一个未过期RECEIVED取得。"""

from dataclasses import dataclass
from datetime import timezone
import json

import psycopg

from oryx_mem0.contracts import (
    OperationIdentity, PendingReceipt, Request, receipt_from_bytes, request_hash,
)
from oryx_mem0.engine.staging import valid_uuid


class RequestConflict(RuntimeError):
    """相同操作编号对应了不同请求。"""

    def __init__(self):
        super().__init__("记忆操作编号对应的请求不一致")


class WriteConflict(RuntimeError):
    """操作租约或基线已经失效。"""


class OperationError(RuntimeError):
    """持久操作异常不暴露数据库错误正文。"""

    def __init__(self):
        super().__init__("记忆操作持久化失败")


@dataclass(frozen=True)
class OperationLease:
    identity: OperationIdentity
    owner_token: str
    baseline_revision: int
    deadline_at: str


class OperationStore:
    def __init__(self, connection_factory, faults=None):
        self.connection_factory = connection_factory
        self.faults = faults

    def register(self, identity, raw_input):
        return self.register_result(identity, raw_input)[0]

    def register_result(self, identity, raw_input):
        _request(identity, raw_input)
        connection = self.connection_factory()
        try:
            with connection.transaction():
                connection.execute(
                    "INSERT INTO oryx_memory.memory_namespaces(workspace_id,revision,created_at) "
                    "VALUES (%s,0,clock_timestamp()) ON CONFLICT(workspace_id) DO NOTHING",
                    (identity.workspace_id,),
                )
                inserted = connection.execute(
                    "WITH stamp AS (SELECT clock_timestamp() AS created_at) "
                    "INSERT INTO oryx_memory.memory_operations("
                    "workspace_id,operation_id,kind,scope,request_hash,raw_input,state,deadline_at,created_at) "
                    "SELECT %s,%s,%s,%s,%s,%s,'RECEIVED',created_at+interval '30 seconds',created_at FROM stamp "
                    "ON CONFLICT(workspace_id,operation_id) DO NOTHING",
                    (identity.workspace_id, identity.operation_id, identity.kind, identity.scope,
                     identity.request_hash, raw_input),
                ).rowcount == 1
                row = _select(connection, identity.workspace_id, identity.operation_id, lock=True)
                receipt = _receipt(row, identity, raw_input)
            _fault(self.faults, "operation_registered")
            return receipt, not inserted
        except (RequestConflict, OperationError):
            raise
        except psycopg.Error:
            raise OperationError() from None
        finally:
            connection.close()

    def claim(self, identity, owner_token):
        if type(identity) is not OperationIdentity or not valid_uuid(owner_token):
            raise OperationError()
        connection = self.connection_factory()
        try:
            with connection.transaction():
                row = connection.execute(
                    "WITH candidate AS ("
                    "SELECT o.workspace_id,o.operation_id,n.revision FROM oryx_memory.memory_operations o "
                    "JOIN oryx_memory.memory_namespaces n ON n.workspace_id=o.workspace_id "
                    "WHERE o.workspace_id=%s AND o.operation_id=%s AND o.kind=%s AND o.scope=%s "
                    "AND o.request_hash=%s AND o.state='RECEIVED' AND o.deadline_at>clock_timestamp() "
                    "FOR UPDATE OF o) "
                    "UPDATE oryx_memory.memory_operations o SET state='RUNNING',owner_token=%s,"
                    "baseline_revision=c.revision,started_at=clock_timestamp() FROM candidate c "
                    "WHERE o.workspace_id=c.workspace_id AND o.operation_id=c.operation_id "
                    "RETURNING o.baseline_revision,o.deadline_at",
                    (identity.workspace_id, identity.operation_id, identity.kind, identity.scope,
                     identity.request_hash, owner_token),
                ).fetchone()
                if row is None:
                    existing = _select(connection, identity.workspace_id, identity.operation_id, lock=False)
                    if existing is not None and not _same_identity(existing, identity):
                        raise RequestConflict()
                    return None
                lease = OperationLease(identity, owner_token, row[0], _utc_text(row[1]))
            _fault(self.faults, "owner_claimed")
            return lease
        except (RequestConflict, OperationError):
            raise
        except psycopg.Error:
            raise OperationError() from None
        finally:
            connection.close()

    def get(self, workspace_id, operation_id):
        if not valid_uuid(workspace_id) or not valid_uuid(operation_id):
            raise OperationError()
        connection = self.connection_factory()
        try:
            row = _select(connection, workspace_id, operation_id, lock=False)
            if row is None:
                return None
            identity = OperationIdentity(row[0], row[1], row[2], row[3], row[4])
            return _receipt(row, identity, row[5])
        except (OperationError, ValueError):
            raise OperationError() from None
        except psycopg.Error:
            raise OperationError() from None
        finally:
            connection.close()

    def recover_expired(self, workspace_id):
        from oryx_mem0.storage.recovery import recover_expired_operations
        return recover_expired_operations(self.connection_factory, workspace_id)


def _request(identity, raw_input):
    if type(identity) is not OperationIdentity:
        raise OperationError()
    try:
        Request(identity.kind, identity.scope, raw_input)
        actual = request_hash(identity.workspace_id, identity.kind, identity.scope, raw_input)
    except (ValueError, TypeError):
        raise OperationError() from None
    if actual != identity.request_hash:
        raise RequestConflict()


def _select(connection, workspace_id, operation_id, lock):
    suffix = " FOR UPDATE" if lock else ""
    return connection.execute(
        "SELECT workspace_id::text,operation_id::text,kind,scope,request_hash,raw_input,state,"
        "deadline_at,result_json,committed_revision,error_code FROM oryx_memory.memory_operations "
        "WHERE workspace_id=%s AND operation_id=%s" + suffix,
        (workspace_id, operation_id),
    ).fetchone()


def _same_identity(row, identity):
    return row[:5] == (
        identity.workspace_id, identity.operation_id, identity.kind, identity.scope, identity.request_hash,
    )


def _receipt(row, identity, raw_input):
    if not _same_identity(row, identity) or row[5] != raw_input:
        raise RequestConflict()
    try:
        stored_hash = request_hash(identity.workspace_id, identity.kind, identity.scope, raw_input)
    except (ValueError, TypeError):
        raise OperationError() from None
    if stored_hash != identity.request_hash:
        raise OperationError()
    if row[6] in {"RECEIVED", "RUNNING"}:
        return PendingReceipt(identity, row[6], _utc_text(row[7]))
    if type(row[8]) is not dict:
        raise OperationError()
    try:
        receipt = receipt_from_bytes(json.dumps(
            row[8], ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8"))
    except (ValueError, TypeError, UnicodeError):
        raise OperationError() from None
    if receipt.identity != identity or receipt.state != row[6]:
        raise OperationError()
    if row[6] == "COMMITTED" and receipt.revision != row[9]:
        raise OperationError()
    if row[6] in {"FAILED", "ABORTED"} and receipt.error_code != row[10]:
        raise OperationError()
    return receipt


def _utc_text(value):
    if value is None or value.tzinfo is None:
        raise OperationError()
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _fault(faults, point):
    if faults is not None:
        faults.hit(point)
