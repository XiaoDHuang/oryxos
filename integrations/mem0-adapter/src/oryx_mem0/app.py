"""严格HTTP边界只返回匹配的持久凭据，不从异常文字猜测结果。"""

from dataclasses import dataclass
import json
from uuid import uuid4

from fastapi import FastAPI, Request as HttpRequest
from fastapi.responses import Response

from oryx_mem0.contracts import (
    CAPABILITY_LIMITS, ContractError, FailureReceipt, OperationIdentity, PendingReceipt, RecallReceipt, Request,
    SaveReceipt, ServiceErrorCode, MAX_REQUEST_BYTES, error_bytes, parse_request, receipt_bytes, response_bytes,
)
from oryx_mem0.engine.staging import valid_uuid
from oryx_mem0.security import SecurityCode, SecurityError
from oryx_mem0.services.operations import OperationReply, OutcomeUnknown
from oryx_mem0.storage.operations import RequestConflict
import re


class RequestTooLarge(ValueError):
    pass


_CAPABILITY_FIELDS = frozenset({
    "protocol", "schema_version", "sdk_version", "staged_engine", "atomic_history",
    "revision_pagination", "build_version", "limits",
})


def _valid_capabilities(value):
    """形态校验：固定字段集、固定协议/版本/能力、64-hex构建版本、与执行一致的固定限制。"""
    return (isinstance(value, dict) and set(value) == _CAPABILITY_FIELDS
            and value["protocol"] == "oryx-memory-v1"
            and value["schema_version"] == 1 and value["sdk_version"] == "1.0.11+oryx.1"
            and value["staged_engine"] is True and value["atomic_history"] is True
            and value["revision_pagination"] is True
            and isinstance(value["build_version"], str)
            and re.fullmatch(r"[0-9a-f]{64}", value["build_version"]) is not None
            and value["limits"] == CAPABILITY_LIMITS)


@dataclass(frozen=True)
class ApiRuntime:
    authenticator: object
    operations: object
    snapshots: object
    capabilities: dict
    startup_check: object


def create_app(runtime):
    if (type(runtime) is not ApiRuntime or not callable(runtime.startup_check)
            or not callable(getattr(runtime.operations, "get", None))
            or not callable(getattr(runtime.snapshots, "create", None))
            or not callable(getattr(runtime.snapshots, "page", None))):
        raise ValueError("记忆API运行时无效")
    if not _valid_capabilities(runtime.capabilities):
        raise ValueError("记忆API能力声明无效")
    runtime.startup_check()
    app = FastAPI(title="Oryx Memory Adapter", docs_url=None, redoc_url=None, openapi_url=None)
    prefix = "/oryx-memory/v1"

    @app.get(prefix + "/capabilities")
    async def capabilities(request: HttpRequest):
        try:
            _authenticate(runtime, request)
            return _payload(runtime.capabilities)
        except Exception as error:
            return _error(error)

    @app.put(prefix + "/workspaces/{workspace}/operations/{operation}")
    async def put_operation(workspace: str, operation: str, request: HttpRequest):
        try:
            _authorize(runtime, request, workspace)
            if not valid_uuid(operation):
                raise ContractError()
            raw = await _read_body(request)
            parsed = parse_request(raw)
            identity = OperationIdentity.from_request(workspace, operation, parsed)
            replayed = False
            if callable(getattr(runtime.operations, "execute", None)):
                reply = runtime.operations.execute(identity, parsed.text)
                if type(reply) is not OperationReply or type(reply.replayed) is not bool:
                    raise OutcomeUnknown()
                result, replayed = reply.receipt, reply.replayed
            elif callable(getattr(runtime.operations, "put", None)):
                result = runtime.operations.put(workspace, operation, raw)
            elif parsed.kind == "SAVE":
                result = runtime.operations.save(identity, parsed.text)
            else:
                result = runtime.operations.recall(identity, parsed.text)
            try:
                return _receipt(result, replayed=replayed, expected=identity)
            except ContractError:
                if parsed.kind == "SAVE":
                    raise OutcomeUnknown() from None
                raise
        except Exception as error:
            return _error(error)

    @app.get(prefix + "/workspaces/{workspace}/operations/{operation}")
    async def get_operation(workspace: str, operation: str, request: HttpRequest):
        try:
            _authorize(runtime, request, workspace)
            if not valid_uuid(operation):
                raise ContractError()
            result = runtime.operations.get(workspace, operation)
            if result is None:
                return _error_code(ServiceErrorCode.OPERATION_NOT_FOUND, 404)
            return _receipt(result, replayed=True, expected=(workspace, operation))
        except Exception as error:
            return _error(error)

    @app.post(prefix + "/workspaces/{workspace}/snapshots")
    async def create_snapshot(workspace: str, request: HttpRequest):
        try:
            _authorize(runtime, request, workspace)
            raw = await _read_body(request)
            if json.loads(raw.decode("utf-8")) != {}:
                raise ContractError()
            return _payload(runtime.snapshots.create(workspace))
        except Exception as error:
            return _error(error)

    @app.get(prefix + "/workspaces/{workspace}/snapshots/{snapshot}/entries")
    async def entries(workspace: str, snapshot: str, request: HttpRequest):
        try:
            _authorize(runtime, request, workspace)
            scope = request.query_params.get("scope")
            cursor = request.query_params.get("cursor")
            raw_size = request.query_params.get("page_size", "100")
            if scope not in {"CORE", "ARCHIVAL"} or not raw_size.isascii() or not raw_size.isdigit():
                raise ContractError()
            page_size = int(raw_size)
            if not 1 <= page_size <= 100:
                raise ContractError()
            return _payload(runtime.snapshots.page(workspace, snapshot, scope, cursor, page_size))
        except Exception as error:
            return _error(error)
    return app


def _authenticate(runtime, request):
    authorization = request.headers.get("Authorization")
    if authorization is None:
        raise PermissionError()
    return runtime.authenticator.authenticate(authorization)


def _authorize(runtime, request, workspace):
    bound = _authenticate(runtime, request)
    if bound != workspace:
        raise SecurityError(SecurityCode.ACCESS_DENIED)


def _receipt(value, replayed, expected=None):
    if type(value) not in (PendingReceipt, FailureReceipt, SaveReceipt, RecallReceipt):
        raise ContractError()
    actual = value.identity if isinstance(expected, OperationIdentity) else (value.identity.workspace_id, value.identity.operation_id)
    if expected is not None and actual != expected:
        raise ContractError()
    status = 200
    if isinstance(value, PendingReceipt):
        status = 202
    elif isinstance(value, FailureReceipt):
        status = {
            "WRITE_CONFLICT": 409, "OPERATION_DEADLINE": 504, "HISTORY_UNAVAILABLE": 503,
            "AUDIT_UNAVAILABLE": 503, "SERVICE_FAILURE": 503, "ACCESS_DENIED": 403,
        }.get(value.error_code, 422)
    request_id = str(uuid4())
    return Response(receipt_bytes(value, request_id, replayed), status_code=status, media_type="application/json")


def _payload(value):
    return Response(response_bytes(value, str(uuid4())), media_type="application/json")


def _error(error):
    if isinstance(error, RequestTooLarge):
        return _error_code(ServiceErrorCode.INVALID_INPUT, 413)
    if isinstance(error, RequestConflict):
        return _error_code(ServiceErrorCode.REQUEST_ID_CONFLICT, 409)
    if isinstance(error, PermissionError):
        return _error_code(ServiceErrorCode.ACCESS_DENIED, 401)
    if isinstance(error, SecurityError):
        status = 403 if error.code == SecurityCode.ACCESS_DENIED else 400
        if error.code == SecurityCode.SNAPSHOT_EXPIRED:
            status = 410
        return _error_code(ServiceErrorCode.ACCESS_DENIED if status == 403
                           else ServiceErrorCode.SNAPSHOT_EXPIRED if status == 410
                           else ServiceErrorCode.INVALID_INPUT, status)
    if isinstance(error, OutcomeUnknown):
        return _error_code(ServiceErrorCode.OUTCOME_UNKNOWN, 503)
    if isinstance(error, (ContractError, ValueError, json.JSONDecodeError, UnicodeError)):
        return _error_code(ServiceErrorCode.INVALID_INPUT, 400)
    return _error_code(ServiceErrorCode.SERVICE_FAILURE, 500)


def _error_code(code, status):
    return Response(error_bytes(code, str(uuid4())), status_code=status, media_type="application/json")


async def _read_body(request):
    length = request.headers.get("content-length")
    if length is not None:
        if not length.isascii() or not length.isdigit():
            raise ContractError()
        if len(length) > 10 or int(length) > MAX_REQUEST_BYTES:
            raise RequestTooLarge()
    body = bytearray()
    async for chunk in request.stream():
        if len(body) + len(chunk) > MAX_REQUEST_BYTES:
            raise RequestTooLarge()
        body.extend(chunk)
    return bytes(body)
