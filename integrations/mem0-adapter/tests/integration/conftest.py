"""真实PG测试必须显式指向已确认可销毁的独立数据库。"""

from dataclasses import dataclass, field
import hmac
import ipaddress
import os
from pathlib import Path
import re
from threading import Lock

import psycopg
from psycopg.conninfo import conninfo_to_dict
import pytest

DATABASE_URL = "ORYX_MEM0_TEST_DATABASE_URL"
DATABASE_NAME = "ORYX_MEM0_TEST_DATABASE_NAME"
DATABASE_CONFIRM = "ORYX_MEM0_TEST_DATABASE_CONFIRM"
APPLICATION_NAME = "oryx_mem0_integration"
DATABASE_COMMENT = "ORYXOS_DISPOSABLE_TEST_DATABASE"
SCHEMA = "oryx_memory"
PG_VERSION = 170011
VECTOR_VERSION = "0.8.6"
FAULT_POINTS = frozenset({
    "operation_registered", "owner_claimed", "audit_started", "audit_finished", "history_insert",
    "current_write", "receipt_write", "before_commit", "after_commit_before_response", "response_lost",
    "worker_crash", "snapshot_read", "recall_read",
})


class TestDatabaseError(RuntimeError):
    __test__ = False

    def __init__(self, field_name):
        self.field = field_name if field_name in {DATABASE_URL, DATABASE_NAME, DATABASE_CONFIRM, "PG_RUNTIME"} else "PG_RUNTIME"
        super().__init__("隔离PG测试配置无效；字段=" + self.field)


@dataclass(frozen=True, repr=False)
class PgTarget:
    dsn: str = field(repr=False)
    database: str
    host: str
    port: int
    loopback: bool


def validated_target(environ=None):
    source = os.environ if environ is None else environ
    values = {}
    for name in (DATABASE_URL, DATABASE_NAME, DATABASE_CONFIRM):
        value = source.get(name)
        if not isinstance(value, str) or not value:
            raise TestDatabaseError(name)
        values[name] = value
    dsn = values[DATABASE_URL]
    database = values[DATABASE_NAME]
    if (not dsn.startswith(("postgresql://", "postgres://")) or len(dsn) > 16384
            or any(ord(char) <= 32 for char in dsn)
            or not re.fullmatch(r"oryx_mem0_[a-z0-9_]*test", database)
            or values[DATABASE_CONFIRM] != "DELETE:" + database):
        raise TestDatabaseError(DATABASE_URL)
    runtime_dsn = source.get("ADAPTER_DATABASE_URL")
    if isinstance(runtime_dsn, str) and hmac.compare_digest(dsn, runtime_dsn):
        raise TestDatabaseError(DATABASE_URL)
    try:
        parameters = conninfo_to_dict(dsn)
        allowed = {"user", "password", "dbname", "host", "port", "sslmode", "sslrootcert",
                   "connect_timeout", "application_name"}
        if (set(parameters) - allowed or parameters.get("dbname") != database
                or not parameters.get("user") or not parameters.get("password")
                or parameters.get("connect_timeout") != "3" or parameters.get("application_name") != APPLICATION_NAME):
            raise ValueError()
        host = parameters.get("host", "")
        if not host or "," in host or any(char.isspace() for char in host):
            raise ValueError()
        port_text = parameters.get("port", "")
        if not re.fullmatch(r"[1-9][0-9]{0,4}", port_text) or not 1 <= int(port_text) <= 65535:
            raise ValueError()
        try:
            loopback = ipaddress.ip_address(host).is_loopback
        except ValueError:
            loopback = host.lower().removesuffix(".") == "localhost"
        sslmode = parameters.get("sslmode")
        if loopback:
            if sslmode not in {"disable", "verify-full"}:
                raise ValueError()
        elif sslmode != "verify-full":
            raise ValueError()
        if sslmode == "verify-full":
            root = Path(parameters.get("sslrootcert", ""))
            if not root.is_absolute() or not root.is_file():
                raise ValueError()
        elif "sslrootcert" in parameters:
            raise ValueError()
        return PgTarget(dsn, database, host, int(port_text), loopback)
    except (ValueError, UnicodeError, psycopg.Error, OSError):
        raise TestDatabaseError(DATABASE_URL) from None


class InjectedFailure(RuntimeError):
    def __init__(self, point):
        super().__init__("合成故障：" + point)


class FaultPlan:
    def __init__(self):
        self._armed = set()
        self._evidence = []
        self._lock = Lock()

    def arm(self, *points):
        if not points or any(point not in FAULT_POINTS for point in points):
            raise ValueError("故障点必须预先声明")
        with self._lock:
            self._armed.update(points)
        return self

    def hit(self, point):
        if point not in FAULT_POINTS:
            raise ValueError("故障点必须预先声明")
        with self._lock:
            self._evidence.append(point)
            active = point in self._armed
            self._armed.discard(point)
        if active:
            raise InjectedFailure(point)

    @property
    def evidence(self):
        with self._lock:
            return tuple(self._evidence)


@dataclass(frozen=True)
class SyntheticData:
    workspace_a: str
    workspace_b: str
    operation_ids: tuple[str, ...]
    contents: tuple[str, ...]
    vectors: tuple[tuple[float, ...], ...]


def synthetic_data_value():
    return SyntheticData(
        "11111111-1111-4111-8111-111111111111",
        "22222222-2222-4222-8222-222222222222",
        ("33333333-3333-4333-8333-333333333333", "44444444-4444-4444-8444-444444444444"),
        ('项目路径是"C:\\工作区"，包含中文与😀', "项目已升级到 Java 21", "项目部署在 K8s 集群"),
        ((1.0, 0.0), (0.0, 1.0), (0.70710678, 0.70710678)),
    )


class PgHarness:
    def __init__(self, target, server_version_num, vector_version, superuser, role):
        self.target = target
        self.server_version_num = server_version_num
        self.vector_version = vector_version
        self.superuser = superuser
        self.role = role
        self._connections = []

    def open(self, autocommit=False):
        connection = psycopg.connect(self.target.dsn, autocommit=autocommit)
        _configure_connection(connection)
        if not autocommit:
            connection.commit()
        self._connections.append(connection)
        return connection

    def close(self):
        for connection in reversed(self._connections):
            if not connection.closed:
                connection.close()
        self._connections.clear()


def _inspect(connection, target):
    row = connection.execute(
        "SELECT current_database(), current_user, current_setting('server_version_num')::integer, "
        "r.rolsuper, shobj_description(d.oid, 'pg_database') "
        "FROM pg_database d JOIN pg_roles r ON r.rolname = current_user WHERE d.datname = current_database()"
    ).fetchone()
    vector = connection.execute("SELECT extversion FROM pg_extension WHERE extname = 'vector'").fetchone()
    if (row is None or vector is None or row[0] != target.database or row[2] != PG_VERSION
            or row[3] is not False or row[4] != DATABASE_COMMENT or vector[0] != VECTOR_VERSION):
        raise TestDatabaseError("PG_RUNTIME")
    return row[2], vector[0], row[3], row[1]


def _configure_connection(connection):
    connection.execute("SET statement_timeout = '5s'")
    connection.execute("SET lock_timeout = '2s'")
    connection.execute("SET idle_in_transaction_session_timeout = '30s'")


def _drop_test_schema(connection):
    # 目标常量且只在名称、确认串、DB注释、版本和非超级用户全部通过后执行。
    connection.execute("DROP SCHEMA IF EXISTS oryx_memory CASCADE")


@pytest.fixture
def fault_plan():
    return FaultPlan()


@pytest.fixture
def pg_harness():
    target = validated_target()
    verifier = None
    harness = None
    try:
        verifier = psycopg.connect(target.dsn, autocommit=True)
        _configure_connection(verifier)
        metadata = _inspect(verifier, target)
        _drop_test_schema(verifier)
        harness = PgHarness(target, *metadata)
        yield harness
    except TestDatabaseError:
        raise
    except psycopg.Error:
        raise TestDatabaseError("PG_RUNTIME") from None
    finally:
        if harness is not None:
            harness.close()
        if verifier is not None and not verifier.closed:
            if harness is not None:
                _drop_test_schema(verifier)
            verifier.close()


@pytest.fixture
def synthetic_data():
    return synthetic_data_value()
