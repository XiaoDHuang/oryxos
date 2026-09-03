"""此用例只在显式隔离PG环境运行，缺环境必须失败而不是跳过。"""

import pytest


pytestmark = pytest.mark.integration


def test_real_fixture_is_pg17_pgvector_and_not_superuser(pg_harness):
    assert pg_harness.server_version_num == 170011
    assert pg_harness.vector_version == "0.8.6"
    assert pg_harness.superuser is False
    with pg_harness.open(autocommit=True) as connection:
        assert connection.execute("SELECT '[1,0]'::vector <=> '[0,1]'::vector").fetchone()[0] == pytest.approx(1.0)

