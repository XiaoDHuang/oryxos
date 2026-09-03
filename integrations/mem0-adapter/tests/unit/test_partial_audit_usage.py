"""embedding通常不返回completion_tokens，不得伪造0或因此拒绝合法审计。"""

import pytest

from oryx_mem0.storage.call_audits import _finish_values
from oryx_mem0.storage.memories import AuditUnavailable

CALL = "11111111-1111-4111-8111-111111111111"


@pytest.mark.parametrize("usage", [
    {"prompt_tokens": 2, "completion_tokens": None, "total_tokens": 2},
    {"prompt_tokens": None, "completion_tokens": None, "total_tokens": 3},
    {"prompt_tokens": None, "completion_tokens": None, "total_tokens": None},
])
def test_partial_usage_keeps_missing_values_null(usage):
    values = _finish_values(CALL, "COMPLETED", {}, usage, None, 1)
    assert {name: values[name] for name in usage} == usage


@pytest.mark.parametrize("usage", [
    {"prompt_tokens": True, "completion_tokens": None, "total_tokens": 2},
    {"prompt_tokens": -1, "completion_tokens": None, "total_tokens": 2},
    {"prompt_tokens": 1, "completion_tokens": 2, "total_tokens": 4},
])
def test_invalid_or_inconsistent_usage_is_still_rejected(usage):
    with pytest.raises(AuditUnavailable):
        _finish_values(CALL, "COMPLETED", {}, usage, None, 1)
