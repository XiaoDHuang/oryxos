package com.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.oryxos.core.react.AgentService;
import com.oryxos.core.session.SessionManager;
import java.nio.file.Path;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

/** 三后端记忆操作的审计取证：统一工具链落库与远端各自终态分别成立，互不冒充. */
@Tag("integration")
class MemoryBackendAuditIntegrationTest {

  @TempDir Path workspace;

  private int runCount;

  private JdbcTemplate runSaveTool(Mem0AdapterStub stub, String... extraProperties)
      throws Exception {
    var probe = new MemoryBackendFixture.RowProbe(workspace.resolve("oryxos.db"));
    MemoryBackendFixture.runner(
            workspace,
            "mem0",
            new MemorySystemIntegrationTest.Scenario(MemorySystemIntegrationTest.Mode.SAVE),
            probe,
            stub)
        .withPropertyValues(extraProperties)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var session =
                  context
                      .getBean(SessionManager.class)
                      // 每次运行独立用户，避免旧会话历史让假网关跳过工具调用
                      .getOrCreate("cli", "audit-" + (++runCount), "memory-test");
              // 无论工具成败，统一链路都返回最终文本；成败证据在审计表
              assertThat(context.getBean(AgentService.class).process(session, "记住偏好"))
                  .isEqualTo("保存完成");
            });
    return new JdbcTemplate(probe);
  }

  @Test
  void memorySuccessFailureTimeoutAndUnknownAreAuditedSeparately() throws Exception {
    try (Mem0AdapterStub stub = Mem0AdapterStub.open()) {
      SSLContext previous = SSLContext.getDefault();
      SSLContext.setDefault(stub.fixture().clientSslContext());
      try {
        // 成功：工具链 completed，远端条目真实存在
        var jdbc = runSaveTool(stub);
        assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM tool_invocations WHERE tool_name='save_memory'"
                        + " AND status='completed' AND success=1",
                    Integer.class))
            .isEqualTo(1);
        assertThat(stub.currentContents()).contains("项目使用 Spring Boot");
        final int afterSuccess = stub.operationCount();

        // 拒绝：远端持久FAILED，工具链failed+固定分类与操作UUID，同ID不重放
        stub.nextFailureCode = "SERVICE_FAILURE";
        stub.nextFailureStatus = 503;
        jdbc = runSaveTool(stub);
        var failedRow =
            jdbc.queryForMap(
                "SELECT status,success,error_message FROM tool_invocations"
                    + " WHERE tool_name='save_memory' ORDER BY started_at DESC LIMIT 1");
        assertThat(failedRow.get("status")).isEqualTo("failed");
        assertThat(failedRow.get("success")).isEqualTo(0);
        assertThat((String) failedRow.get("error_message"))
            .contains("MEMORY_SERVICE_FAILURE")
            .containsPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(stub.operationCount()).isEqualTo(afterSuccess + 1);
        assertThat(stub.currentContents()).hasSize(1);

        // 超时但已提交：PUT读超时后按原ID确认COMMITTED，链路仍报成功
        stub.delayNextResponseMillis = 3000;
        jdbc = runSaveTool(stub, "memory.mem0.read-timeout=2s");
        assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM tool_invocations WHERE tool_name='save_memory'"
                        + " AND status='completed'",
                    Integer.class))
            .isGreaterThanOrEqualTo(1);

        // 未知：请求被丢弃且服务端无记录；链路failed+未知分类+UUID，远端无可重放终态
        stub.dropNextRequest = true;
        final int beforeUnknown = stub.operationCount();
        jdbc = runSaveTool(stub, "memory.mem0.operation-timeout=5s", "memory.mem0.read-timeout=3s");
        var unknownRow =
            jdbc.queryForMap(
                "SELECT status,success,error_message FROM tool_invocations"
                    + " WHERE tool_name='save_memory' ORDER BY started_at DESC LIMIT 1");
        assertThat(unknownRow.get("status")).isEqualTo("failed");
        assertThat(unknownRow.get("success")).isEqualTo(0);
        assertThat((String) unknownRow.get("error_message"))
            .contains("MEMORY_OUTCOME_UNKNOWN")
            .containsPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(stub.operationCount()).isEqualTo(beforeUnknown);

        // 审计故障可观测：AUDIT_UNAVAILABLE 固定422失败，不触发重放
        stub.nextFailureCode = "AUDIT_UNAVAILABLE";
        stub.nextFailureStatus = 422;
        final int beforeAudit = stub.operationCount();
        jdbc = runSaveTool(stub);
        var auditRow =
            jdbc.queryForMap(
                "SELECT status,error_message FROM tool_invocations"
                    + " WHERE tool_name='save_memory' ORDER BY started_at DESC LIMIT 1");
        assertThat(auditRow.get("status")).isEqualTo("failed");
        assertThat((String) auditRow.get("error_message")).contains("MEMORY_SERVICE_FAILURE");
        assertThat(stub.operationCount()).isEqualTo(beforeAudit + 1);
        assertThat(stub.currentContents()).hasSize(2);
      } finally {
        SSLContext.setDefault(previous);
      }
    }
  }
}
