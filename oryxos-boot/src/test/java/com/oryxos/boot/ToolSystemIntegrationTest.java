package com.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oryxos.core.config.CoreEngineConfiguration;
import com.oryxos.core.context.ContextLoader;
import com.oryxos.core.profile.Profile;
import com.oryxos.core.profile.ProfileRegistry;
import com.oryxos.core.react.AgentService;
import com.oryxos.core.react.LlmGateway;
import com.oryxos.core.react.ProfileContext;
import com.oryxos.core.session.SessionManager;
import com.oryxos.core.tool.OryxTool;
import com.oryxos.core.tool.ToolResult;
import com.oryxos.storage.audit.JpaToolInvocationAudit;
import com.oryxos.storage.session.JpaSessionManager;
import com.oryxos.tool.ToolRegistry;
import com.oryxos.tool.mcp.McpClientService;
import com.oryxos.tool.notify.WebhookNotifyAdapter;
import com.oryxos.tool.sandbox.ActionType;
import com.oryxos.tool.sandbox.Sandbox;
import com.oryxos.tool.sandbox.SandboxViolationException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

@Tag("integration")
@DisplayName("工具体系到真实SQLite的端到端链路")
class ToolSystemIntegrationTest {
  @TempDir Path directory;

  @Test
  @DisplayName("真实文件成功失败与重试均经Agent循环并只写一条最终审计")
  void executesFilesAndRetriesWithRealAudit() {
    runner()
        .withBean(
            Sandbox.class,
            () ->
                action -> {
                  if (action.type() != ActionType.FILE_ACCESS
                      || !Path.of(action.target())
                          .toAbsolutePath()
                          .normalize()
                          .startsWith(directory.toAbsolutePath())) {
                    throw new SandboxViolationException("端到端测试仅允许临时目录");
                  }
                })
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var scenario = context.getBean(Scenario.class);
              var agent = context.getBean(AgentService.class);
              var sessions = context.getBean(SessionManager.class);
              Path file = directory.resolve("日报.txt");
              var mapper = new ObjectMapper();

              scenario.call.set(
                  new AssistantMessage.ToolCall(
                      "write",
                      "function",
                      "write_file",
                      mapper.writeValueAsString(
                          Map.of("path", file.toString(), "content", "工具端到端内容"))));
              var written = sessions.getOrCreate("cli", "writer", "tool-test");
              assertEquals("最终回复", agent.process(written, "写入文件"));
              assertEquals("工具端到端内容", Files.readString(file));

              scenario.call.set(
                  new AssistantMessage.ToolCall(
                      "missing",
                      "function",
                      "read_file",
                      mapper.writeValueAsString(
                          Map.of("path", directory.resolve("missing.txt").toString()))));
              assertEquals(
                  "最终回复",
                  agent.process(sessions.getOrCreate("cli", "missing", "tool-test"), "读取不存在文件"));

              scenario.call.set(
                  new AssistantMessage.ToolCall("retry", "function", "transient_test", "{}"));
              assertEquals(
                  "最终回复",
                  agent.process(sessions.getOrCreate("cli", "retry", "tool-test"), "测试瞬态恢复"));
              assertEquals(4, scenario.attempts.get());

              var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
              var rows =
                  jdbc.queryForList(
                      "SELECT tool_name, status, started_at, completed_at FROM tool_invocations");
              assertEquals(3, rows.size());
              assertEquals("completed", row(rows, "write_file").get("status"));
              assertEquals("failed", row(rows, "read_file").get("status"));
              var retry = row(rows, "transient_test");
              assertEquals("completed", retry.get("status"));
              assertTrue(
                  Duration.between(
                              Instant.parse((String) retry.get("started_at")),
                              Instant.parse((String) retry.get("completed_at")))
                          .toMillis()
                      >= 700);
              var restored = sessions.get(written.id()).orElseThrow();
              assertEquals(4, restored.messages().size());
              assertTrue(
                  restored.messages().stream().anyMatch(ToolResponseMessage.class::isInstance));
              assertEquals("最终回复", restored.messages().getLast().getText());
              assertNull(ProfileContext.current());
              assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM sessions", Integer.class));
            });
  }

  @Test
  @DisplayName("未注入真实白名单时默认拒绝零文件副作用且失败落SQLite")
  void defaultDenyIsAuditedWithoutSideEffects() {
    runner()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              Path file = directory.resolve("denied.txt");
              var scenario = context.getBean(Scenario.class);
              scenario.call.set(
                  new AssistantMessage.ToolCall(
                      "denied",
                      "function",
                      "write_file",
                      new ObjectMapper()
                          .writeValueAsString(Map.of("path", file.toString(), "content", "不应写入"))));
              var sessions = context.getBean(SessionManager.class);
              var session = sessions.getOrCreate("cli", "denied", "tool-test");
              assertEquals("最终回复", context.getBean(AgentService.class).process(session, "写入文件"));
              assertFalse(Files.exists(file));
              var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
              assertEquals(
                  1, jdbc.queryForObject("SELECT COUNT(*) FROM tool_invocations", Integer.class));
              assertEquals(
                  "failed",
                  jdbc.queryForObject("SELECT status FROM tool_invocations", String.class));
              assertEquals(
                  session.id(),
                  jdbc.queryForObject("SELECT session_id FROM tool_invocations", String.class));
              assertEquals(
                  "tool-test",
                  jdbc.queryForObject("SELECT profile_name FROM tool_invocations", String.class));
            });
  }

  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withInitializer(
            context ->
                ((BeanDefinitionRegistry) context.getBeanFactory())
                    .registerBeanDefinition(
                        "mcpClientService",
                        BeanDefinitionBuilder.genericBeanDefinition(
                                McpClientService.class,
                                () ->
                                    RuntimeFixture.createMcpFixture(
                                        context.getBean(ToolRegistry.class),
                                        context.getBean(Scenario.class)))
                            .setDestroyMethodName("close")
                            .getBeanDefinition()))
        .withConfiguration(
            AutoConfigurations.of(
                DataSourceAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class,
                TransactionAutoConfiguration.class,
                SqlInitializationAutoConfiguration.class,
                toolAutoConfiguration()))
        .withUserConfiguration(RuntimeFixture.class)
        .withBean(Path.class, () -> directory)
        .withBean(Scenario.class, Scenario::new)
        .withBean(RestClient.Builder.class, RestClient::builder)
        .withPropertyValues(
            "spring.datasource.url=jdbc:sqlite:" + directory.resolve("oryxos-test.db"),
            "spring.datasource.driver-class-name=org.sqlite.JDBC",
            "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:db/schema.sql");
  }

  private static Class<?> toolAutoConfiguration() {
    try {
      return Class.forName("com.oryxos.tool.ToolConfiguration");
    } catch (ClassNotFoundException exception) {
      throw new AssertionError("工具自动配置必须存在", exception);
    }
  }

  private static Map<String, Object> row(List<Map<String, Object>> rows, String name) {
    var matches = rows.stream().filter(row -> name.equals(row.get("tool_name"))).toList();
    assertEquals(1, matches.size());
    return matches.getFirst();
  }

  static class Scenario {
    final AtomicReference<AssistantMessage.ToolCall> call = new AtomicReference<>();
    final AtomicInteger attempts = new AtomicInteger();
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EntityScan("com.oryxos.storage")
  @EnableJpaRepositories("com.oryxos.storage")
  @Import({JpaSessionManager.class, JpaToolInvocationAudit.class, WebhookNotifyAdapter.class})
  static class RuntimeFixture extends CoreEngineConfiguration {
    private final Path workspace;

    RuntimeFixture(Path workspace) {
      this.workspace = workspace;
    }

    @Override
    @Bean
    public ContextLoader contextLoader() {
      return new ContextLoader(workspace);
    }

    @Override
    @Bean
    public ProfileRegistry profileRegistry(ObjectProvider<Set<String>> providers) {
      return new ProfileRegistry(
          List.of(
              new Profile(
                  "tool-test",
                  null,
                  null,
                  new Profile.Provider("fake", "fake-model", 0.0, null),
                  List.of("write_file", "read_file", "transient_test"),
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null)));
    }

    @Bean
    LlmGateway fakeGateway(Scenario scenario) {
      return (sessionId, profile, prompt) -> {
        assertEquals(3, prompt.availableTools().size());
        AssistantMessage message =
            prompt.messages().stream().anyMatch(ToolResponseMessage.class::isInstance)
                ? new AssistantMessage("最终回复")
                : AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(scenario.call.get()))
                    .build();
        return new ChatResponse(List.of(new Generation(message)));
      };
    }

    static McpClientService createMcpFixture(ToolRegistry registry, Scenario scenario) {
      McpClientService service = mock(McpClientService.class);
      doAnswer(
              ignored -> {
                // 该无IO工具只供重试审计验收，生产没有此声明或许可。
                registry.register(
                    new OryxTool() {
                      @Override
                      public String getName() {
                        return "transient_test";
                      }

                      @Override
                      public String getDescription() {
                        return "测试瞬态故障";
                      }

                      @Override
                      public String getInputSchema() {
                        return "{\"type\":\"object\"}";
                      }

                      @Override
                      public ToolResult execute(String argumentsJson) {
                        return scenario.attempts.incrementAndGet() < 4
                            ? ToolResult.fail(getName(), "测试瞬态失败", true)
                            : ToolResult.ok(getName(), "已恢复");
                      }
                    });
                return null;
              })
          .when(service)
          .connectAll();
      return service;
    }
  }
}
