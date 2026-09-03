"""调用审计端口由后续持久适配实现，不用SDK回调冒充完整审计。"""

from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Protocol

from oryx_mem0.engine.staging import Code, EngineError, valid_uuid


class AuditSink(Protocol):
    def begin(self, call: dict) -> str: ...

    def finish(self, call_id: str, **result) -> None: ...


@dataclass(frozen=True)
class CallValue:
    value: object
    response: dict
    usage: dict | None


class CallAuditor:
    def __init__(self, context, sink):
        if sink is None or not callable(getattr(sink, "begin", None)) or not callable(getattr(sink, "finish", None)):
            context.fail(Code.AUDIT_FAILURE)
        self.context = context
        self.sink = sink
        self._secrets = set()
        self._sequence = 0

    def protect(self, secret):
        if not isinstance(secret, str) or not secret:
            self.context.fail(Code.INVALID_CONFIG)
        self._secrets.add(secret)

    def _safe(self, value):
        if isinstance(value, str):
            for secret in sorted(self._secrets, key=len, reverse=True):
                value = value.replace(secret, "<已脱敏>")
            return value
        if isinstance(value, dict):
            return {self._safe(key): self._safe(item) for key, item in value.items()}
        if isinstance(value, (tuple, list)):
            return [self._safe(item) for item in value]
        return value

    def invoke(self, kind, phase, model, request, operation):
        state = self.context
        state.check()
        if state.active_call is not None:
            state.fail(Code.AUDIT_FAILURE)
        self._sequence += 1
        started = state.clock()
        record = {"workspace_id": state.workspace_id, "operation_id": state.operation_id,
                  "call_index": self._sequence, "kind": kind, "phase": phase,
                  "provider": "self-hosted-openai", "model": model, "state": "STARTED",
                  "started_at": datetime.now(timezone.utc).isoformat(), "request_json": self._safe(request)}
        try:
            call_id = self.sink.begin(self._safe(record))
            if not valid_uuid(call_id):
                state.fail(Code.AUDIT_FAILURE)
        except Exception:
            state.fail(Code.AUDIT_FAILURE)
        state.enter_call(call_id)
        try:
            return self._execute(call_id, started, operation)
        finally:
            state.leave_call(call_id)

    def _execute(self, call_id, started, operation):
        state = self.context
        try:
            state.check()
            result = operation()
            state.check()
            if not isinstance(result, CallValue):
                state.fail(Code.INVALID_RESULT)
        except Exception as error:
            state.latch(error.code if isinstance(error, EngineError) else Code.SERVICE_FAILURE)
            try:
                self.sink.finish(call_id, state="FAILED", response_json=None, usage=None,
                                 error_code=state.failure.value, latency_ms=max(0, int((state.clock() - started) * 1000)))
            except Exception:
                state.latch(Code.AUDIT_FAILURE)
            state.fail(state.failure)
        try:
            self.sink.finish(call_id, state="COMPLETED", response_json=self._safe(result.response),
                             usage=result.usage, error_code=None, latency_ms=max(0, int((state.clock() - started) * 1000)))
        except Exception:
            state.fail(Code.AUDIT_FAILURE)
        state.check()
        return result.value
