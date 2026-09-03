"""内部模型调用先持久STARTED，再允许下游I/O。"""

import json
from uuid import uuid4

import psycopg
from psycopg.types.json import Jsonb

from oryx_mem0.engine.staging import MAX_IO_BYTES, valid_uuid
from oryx_mem0.storage.memories import AuditUnavailable

_KINDS = frozenset({"LLM", "EMBEDDING"})
_TERMINAL = frozenset({"COMPLETED", "FAILED"})
_ERRORS = frozenset({
    "ENGINE_INVALID_RESULT", "ENGINE_LIMIT_EXCEEDED", "ACCESS_DENIED", "OPERATION_DEADLINE",
    "SERVICE_FAILURE", "AUDIT_UNAVAILABLE", "HISTORY_UNAVAILABLE",
})


class CallAuditStore:
    def __init__(self, connection_factory, faults=None):
        self.connection_factory = connection_factory
        self.faults = faults

    def begin(self, workspace_id, operation_id=None, kind=None, phase=None, provider=None,
              model=None, request_json=None):
        record = _begin_record(
            workspace_id, operation_id, kind, phase, provider, model, request_json)
        call_id = str(uuid4())
        _fault(self.faults, "audit_started")
        connection = self.connection_factory()
        try:
            with connection.transaction():
                if record["call_index"] is None:
                    record["call_index"] = connection.execute(
                        "SELECT COALESCE(max(call_index),0)+1 FROM oryx_memory.memory_call_audits "
                        "WHERE workspace_id=%s AND operation_id=%s",
                        (record["workspace_id"], record["operation_id"]),
                    ).fetchone()[0]
                connection.execute(
                    "INSERT INTO oryx_memory.memory_call_audits("
                    "call_id,workspace_id,operation_id,call_index,kind,phase,provider,model,state,started_at,request_json) "
                    "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,'STARTED',clock_timestamp(),%s)",
                    (call_id, record["workspace_id"], record["operation_id"], record["call_index"],
                     record["kind"], record["phase"], record["provider"], record["model"],
                     None if record["request_json"] is None else Jsonb(record["request_json"])),
                )
            return call_id
        except psycopg.Error:
            raise AuditUnavailable() from None
        finally:
            connection.close()

    def finish(self, call_id, state, response_json=None, usage=None, error_code=None, latency_ms=None):
        values = _finish_values(call_id, state, response_json, usage, error_code, latency_ms)
        _fault(self.faults, "audit_finished")
        connection = self.connection_factory()
        try:
            with connection.transaction():
                updated = connection.execute(
                    "UPDATE oryx_memory.memory_call_audits SET state=%s,completed_at=clock_timestamp(),"
                    "latency_ms=COALESCE(%s,GREATEST(0,(EXTRACT(EPOCH FROM (clock_timestamp()-started_at))*1000)::bigint)),"
                    "prompt_tokens=%s,completion_tokens=%s,total_tokens=%s,response_json=%s,error_code=%s "
                    "WHERE call_id=%s AND state='STARTED'",
                    (values["state"], values["latency_ms"], values["prompt_tokens"],
                     values["completion_tokens"], values["total_tokens"],
                     None if values["response_json"] is None else Jsonb(values["response_json"]),
                     values["error_code"], call_id),
                ).rowcount
                if updated != 1:
                    raise AuditUnavailable()
        except AuditUnavailable:
            raise
        except psycopg.Error:
            raise AuditUnavailable() from None
        finally:
            connection.close()

    def recover_unknown(self, workspace_id):
        from oryx_mem0.storage.recovery import recover_unknown_calls
        return recover_unknown_calls(self.connection_factory, workspace_id)


def _begin_record(workspace_id, operation_id, kind, phase, provider, model, request_json):
    if type(workspace_id) is dict and operation_id is None:
        source = workspace_id
        required = {
            "workspace_id", "operation_id", "call_index", "kind", "phase", "provider", "model",
            "state", "started_at", "request_json",
        }
        if set(source) != required or source["state"] != "STARTED":
            raise AuditUnavailable()
        workspace_id, operation_id = source["workspace_id"], source["operation_id"]
        kind, phase, provider, model = source["kind"], source["phase"], source["provider"], source["model"]
        request_json, call_index = source["request_json"], source["call_index"]
    else:
        call_index = None
    if (not valid_uuid(workspace_id) or not valid_uuid(operation_id) or kind not in _KINDS
            or not _safe_text(phase) or not _safe_text(provider) or not _safe_text(model)
            or (call_index is not None and (type(call_index) is not int or call_index < 1))):
        raise AuditUnavailable()
    _json_budget(request_json)
    return {
        "workspace_id": workspace_id, "operation_id": operation_id, "call_index": call_index,
        "kind": kind, "phase": phase, "provider": provider, "model": model, "request_json": request_json,
    }


def _finish_values(call_id, state, response_json, usage, error_code, latency_ms):
    if (not valid_uuid(call_id) or state not in _TERMINAL
            or (latency_ms is not None and (type(latency_ms) is not int or latency_ms < 0))):
        raise AuditUnavailable()
    if state == "COMPLETED" and error_code is not None:
        raise AuditUnavailable()
    if state == "FAILED" and error_code not in _ERRORS:
        raise AuditUnavailable()
    _json_budget(response_json)
    prompt = completion = total = None
    if usage is not None:
        if type(usage) is not dict or set(usage) != {"prompt_tokens", "completion_tokens", "total_tokens"}:
            raise AuditUnavailable()
        prompt, completion, total = (usage["prompt_tokens"], usage["completion_tokens"], usage["total_tokens"])
        values = (prompt, completion, total)
        if (any(value is not None and (type(value) is not int or value < 0) for value in values)
                or (all(value is not None for value in values) and total != prompt + completion)):
            raise AuditUnavailable()
    return {
        "state": state, "response_json": response_json, "prompt_tokens": prompt,
        "completion_tokens": completion, "total_tokens": total, "error_code": error_code,
        "latency_ms": latency_ms,
    }


def _json_budget(value):
    if value is not None and type(value) not in (dict, list):
        raise AuditUnavailable()
    try:
        raw = json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")
    except (TypeError, ValueError, UnicodeError, RecursionError):
        raise AuditUnavailable() from None
    if len(raw) > MAX_IO_BYTES:
        raise AuditUnavailable()


def _safe_text(value):
    return isinstance(value, str) and bool(value.strip()) and "\x00" not in value and len(value) <= 256


def _fault(faults, point):
    if faults is not None:
        faults.hit(point)
