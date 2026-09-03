"""PG fixture的安全判断可离线验证，不连接任何数据库。"""

from dataclasses import FrozenInstanceError
import importlib.util
from pathlib import Path

import pytest


def _module():
    path = Path(__file__).parents[1] / "integration/conftest.py"
    spec = importlib.util.spec_from_file_location("oryx_pg_fixture_policy", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture(scope="module")
def policy():
    return _module()


@pytest.fixture
def database_environment():
    database = "oryx_mem0_integration_test"
    return {
        "ORYX_MEM0_TEST_DATABASE_URL": (
            "postgresql://oryx_test:synthetic-test-secret@127.0.0.1:55432/" + database
            + "?sslmode=disable&connect_timeout=3&application_name=oryx_mem0_integration"
        ),
        "ORYX_MEM0_TEST_DATABASE_NAME": database,
        "ORYX_MEM0_TEST_DATABASE_CONFIRM": "DELETE:" + database,
    }


def test_explicit_disposable_target_is_frozen_and_does_not_echo_dsn(policy, database_environment):
    target = policy.validated_target(database_environment)
    assert target.database == "oryx_mem0_integration_test"
    assert target.host == "127.0.0.1"
    assert target.port == 55432
    assert target.loopback is True
    assert "synthetic-test-secret" not in repr(target)
    with pytest.raises(FrozenInstanceError):
        target.database = "other"


@pytest.mark.parametrize("removed", [
    "ORYX_MEM0_TEST_DATABASE_URL", "ORYX_MEM0_TEST_DATABASE_NAME", "ORYX_MEM0_TEST_DATABASE_CONFIRM",
])
def test_every_safety_input_is_required(policy, database_environment, removed):
    database_environment.pop(removed)
    with pytest.raises(policy.TestDatabaseError) as failure:
        policy.validated_target(database_environment)
    assert failure.value.field == removed


@pytest.mark.parametrize("changes", [
    {"ORYX_MEM0_TEST_DATABASE_NAME": "production"},
    {"ORYX_MEM0_TEST_DATABASE_CONFIRM": "yes"},
    {"ORYX_MEM0_TEST_DATABASE_URL": "host=127.0.0.1 dbname=oryx_mem0_integration_test"},
    {"ORYX_MEM0_TEST_DATABASE_URL": "postgresql://u:p@127.0.0.1/prod?sslmode=disable&connect_timeout=3&application_name=oryx_mem0_integration"},
    {"ORYX_MEM0_TEST_DATABASE_URL": "postgresql://u:p@a,b/oryx_mem0_integration_test?sslmode=verify-full&connect_timeout=3&application_name=oryx_mem0_integration"},
    {"ORYX_MEM0_TEST_DATABASE_URL": "postgresql://u:p@db.internal/oryx_mem0_integration_test?sslmode=disable&connect_timeout=3&application_name=oryx_mem0_integration"},
    {"ORYX_MEM0_TEST_DATABASE_URL": "postgresql://u:p@127.0.0.1/oryx_mem0_integration_test?sslmode=disable&connect_timeout=30&application_name=oryx_mem0_integration"},
    {"ORYX_MEM0_TEST_DATABASE_URL": "postgresql://u:p@127.0.0.1/oryx_mem0_integration_test?sslmode=disable&connect_timeout=3&application_name=psql"},
])
def test_ambiguous_or_non_disposable_database_is_rejected(policy, database_environment, changes):
    database_environment.update(changes)
    with pytest.raises(policy.TestDatabaseError):
        policy.validated_target(database_environment)


def test_runtime_database_url_cannot_be_reused_as_test_target(policy, database_environment):
    database_environment["ADAPTER_DATABASE_URL"] = database_environment["ORYX_MEM0_TEST_DATABASE_URL"]
    with pytest.raises(policy.TestDatabaseError, match="ORYX_MEM0_TEST_DATABASE_URL"):
        policy.validated_target(database_environment)


def test_remote_test_database_requires_verified_tls_and_absolute_ca(policy, database_environment):
    database_environment["ORYX_MEM0_TEST_DATABASE_URL"] = (
        "postgresql://u:p@pg-test.internal/oryx_mem0_integration_test?sslmode=verify-full"
        "&connect_timeout=3&application_name=oryx_mem0_integration&sslrootcert=D:/definitely-missing-oryx-test-ca.pem"
    )
    with pytest.raises(policy.TestDatabaseError):
        policy.validated_target(database_environment)


def test_errors_and_traceback_never_contain_database_secret(policy, database_environment):
    database_environment["ORYX_MEM0_TEST_DATABASE_CONFIRM"] = "wrong-synthetic-test-secret"
    with pytest.raises(policy.TestDatabaseError) as failure:
        policy.validated_target(database_environment)
    assert "synthetic-test-secret" not in str(failure.value)


def test_fault_points_are_declared_single_use_and_traceable(policy):
    plan = policy.FaultPlan().arm("history_insert", "receipt_write")
    with pytest.raises(policy.InjectedFailure, match="history_insert"):
        plan.hit("history_insert")
    plan.hit("history_insert")
    with pytest.raises(policy.InjectedFailure, match="receipt_write"):
        plan.hit("receipt_write")
    assert plan.evidence == ("history_insert", "history_insert", "receipt_write")
    with pytest.raises(ValueError):
        policy.FaultPlan().arm("arbitrary_sql")


def test_synthetic_data_is_immutable_cross_workspace_and_has_no_nul(policy):
    data = policy.synthetic_data_value()
    assert data.workspace_a != data.workspace_b
    assert data.operation_ids[0] != data.operation_ids[1]
    assert len(data.vectors) >= 2 and all(len(vector) == 2 for vector in data.vectors)
    assert any('"' in content and "\\" in content and "中文" in content for content in data.contents)
    assert all("\x00" not in content for content in data.contents)
    with pytest.raises(FrozenInstanceError):
        data.workspace_a = data.workspace_b


def test_connection_defaults_are_bounded_and_schema_cleanup_target_is_constant(policy, database_environment, monkeypatch):
    class FakeConnection:
        closed = False
        def __init__(self):
            self.statements = []
            self.committed = False
        def execute(self, statement):
            self.statements.append(statement)
            return self
        def commit(self):
            self.committed = True
        def close(self):
            self.closed = True
    connection = FakeConnection()
    monkeypatch.setattr(policy.psycopg, "connect", lambda dsn, autocommit: connection)
    target = policy.validated_target(database_environment)
    harness = policy.PgHarness(target, 170011, "0.8.6", False, "oryx_test")
    assert harness.open().committed is True
    assert connection.statements == [
        "SET statement_timeout = '5s'", "SET lock_timeout = '2s'",
        "SET idle_in_transaction_session_timeout = '30s'",
    ]
    connection.statements.clear()
    policy._drop_test_schema(connection)
    assert connection.statements == ["DROP SCHEMA IF EXISTS oryx_memory CASCADE"]
    harness.close()
    assert connection.closed is True
