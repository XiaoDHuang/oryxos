"""HTTP读取和输出信封必须按实际字节限制，不依赖Content-Length声明。"""

from datetime import datetime, timezone

import pytest

from oryx_mem0.app import RequestTooLarge, _read_body, _receipt
from oryx_mem0.contracts import ActionCounts, ContractError, MAX_REQUEST_BYTES, OperationIdentity, SaveReceipt


class Request:
    def __init__(self, chunks, length=None):
        self.headers = {} if length is None else {"content-length": length}
        self.chunks = chunks
        self.pulled = 0

    async def stream(self):
        for chunk in self.chunks:
            self.pulled += 1
            yield chunk


def complete(coroutine):
    try:
        coroutine.send(None)
    except StopIteration as result:
        return result.value
    raise AssertionError("合成读取不得发起外部异步I/O")


def test_declared_oversize_is_rejected_without_consuming_the_stream():
    request = Request([b"not-read"], str(MAX_REQUEST_BYTES + 1))
    with pytest.raises(RequestTooLarge):
        complete(_read_body(request))
    assert request.pulled == 0


def test_stream_is_stopped_at_the_first_over_budget_chunk():
    request = Request([b"x" * MAX_REQUEST_BYTES, b"x", b"must-not-be-read"])
    with pytest.raises(RequestTooLarge):
        complete(_read_body(request))
    assert request.pulled == 2


def test_valid_request_bytes_are_not_normalized():
    content = '  原文é\n"\\😀  '.encode()
    assert complete(_read_body(Request([content[:5], content[5:]]))) == content


def test_typed_receipt_with_wrong_request_hash_is_not_trusted():
    workspace = "11111111-1111-4111-8111-111111111111"
    operation = "22222222-2222-4222-8222-222222222222"
    received = OperationIdentity(workspace, operation, "SAVE", "CORE", "a" * 64)
    expected = OperationIdentity(workspace, operation, "SAVE", "CORE", "b" * 64)
    receipt = SaveReceipt(received, datetime.now(timezone.utc).isoformat(), 1, "CHANGED",
        ActionCounts(1, 0, 0), ("33333333-3333-4333-8333-333333333333",), True)
    with pytest.raises(ContractError):
        _receipt(receipt, False, expected)
