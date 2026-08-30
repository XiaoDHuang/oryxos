package com.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.oryxos.core.config.CoreEngineConfiguration;
import com.oryxos.core.context.ContextLoader;
import com.oryxos.core.profile.Profile;
import com.oryxos.core.profile.ProfileRegistry;
import com.oryxos.core.prompt.Prompt;
import com.oryxos.core.react.AgentService;
import com.oryxos.core.react.LlmGateway;
import com.oryxos.core.react.ProfileContext;
import com.oryxos.core.session.SessionManager;
import com.oryxos.memory.LongTermMemory;
import com.oryxos.storage.audit.JpaToolInvocationAudit;
import com.oryxos.storage.session.JpaSessionManager;
import com.oryxos.tool.ToolRegistry;
import com.oryxos.tool.mcp.McpClientService;
import com.oryxos.tool.notify.WebhookNotifyAdapter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

@Tag("integration")
@DisplayName("Memory跨重启到真实SQLite审计的完整链路")
class MemorySystemIntegrationTest {

  @TempDir Path directory;

  @Test
  @DisplayName("保存后关闭上下文_新Session自动注入并回忆且各审计一次")
  void remembersAcrossRestartAndAuditsSaveAndRecallOnce() throws Exception {
    Path workspace = directory.resolve(".oryxos");
    Files.createDirectories(workspace);
    Path database = directory.resolve("memory-test.db");
    AtomicReference<String> firstSessionId = new AtomicReference<>();
    Scenario save = new Scenario(Mode.SAVE);

    runner(workspace, database, save)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              SessionManager sessions = context.getBean(SessionManager.class);
              var session = sessions.getOrCreate("cli", "writer", "memory-test");
              firstSessionId.set(session.id());

              assertEquals(
                  "保存完成", context.getBean(AgentService.class).process(session, "记住我的技术偏好"));
              assertThat(Files.readString(workspace.resolve("memory/MEMORY.md")))
                  .contains("项目使用 Spring Boot");
              assertAuditRows(context.getBean(DataSource.class), List.of("save_memory"));
              assertThat(ProfileContext.current()).isNull();
            });

    Scenario recall = new Scenario(Mode.RECALL);
    runner(workspace, database, recall)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              SessionManager sessions = context.getBean(SessionManager.class);
              var session = sessions.getOrCreate("cli", "reader", "memory-test");
              assertThat(session.id()).isNotEqualTo(firstSessionId.get());

              assertEquals("回忆完成", context.getBean(AgentService.class).process(session, "回忆技术偏好"));
              assertThat(recall.firstPrompt.get().messages())
                  .anyMatch(message -> message.getText().contains("项目使用 Spring Boot"));
              assertAuditRows(
                  context.getBean(DataSource.class), List.of("recall_memory", "save_memory"));
              assertThat(ProfileContext.current()).isNull();
            });
  }

  private ApplicationContextRunner runner(Path workspace, Path database, Scenario scenario) {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DataSourceAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class,
                TransactionAutoConfiguration.class,
                SqlInitializationAutoConfiguration.class,
                memoryAutoConfiguration(),
                toolAutoConfiguration()))
        .withUserConfiguration(RuntimeFixture.class)
        .withBean(Path.class, () -> workspace)
        .withBean(Scenario.class, () -> scenario)
        .withBean(RestClient.Builder.class, RestClient::builder)
        .withPropertyValues(
            "spring.datasource.url=jdbc:sqlite:" + database,
            "spring.datasource.driver-class-name=org.sqlite.JDBC",
            "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:db/schema.sql");
  }

  private static void assertAuditRows(DataSource dataSource, List<String> expectedNames) {
    List<Map<String, Object>> rows =
        new JdbcTemplate(dataSource)
            .queryForList("SELECT tool_name, status FROM tool_invocations ORDER BY tool_name");
    assertThat(rows).hasSize(expectedNames.size());
    assertThat(rows.stream().map(row -> (String) row.get("tool_name")).toList())
        .containsExactlyElementsOf(expectedNames);
    assertThat(rows).allMatch(row -> "completed".equals(row.get("status")));
  }

  private static Class<?> memoryAutoConfiguration() {
    return loadConfiguration("com.oryxos.memory.MemoryConfiguration", "Memory自动配置必须存在");
  }

  private static Class<?> toolAutoConfiguration() {
    return loadConfiguration("com.oryxos.tool.ToolConfiguration", "工具自动配置必须存在");
  }

  private static Class<?> loadConfiguration(String name, String message) {
    try {
      return Class.forName(name);
    } catch (ClassNotFoundException exception) {
      throw new AssertionError(message, exception);
    }
  }

  enum Mode {
    SAVE,
    RECALL
  }

  static class Scenario {
    final Mode mode;
    final AtomicReference<Prompt> firstPrompt = new AtomicReference<>();

    Scenario(Mode mode) {
      this.mode = mode;
    }
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
                  "memory-test",
                  null,
                  null,
                  new Profile.Provider("fake", "fake-model", 0.0, null),
                  List.of("save_memory", "recall_memory"),
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  new Profile.Settings(10, 20),
                  null,
                  null)));
    }

    @Bean
    @Primary
    LongTermMemory testLongTermMemory() {
      return new LongTermMemory(workspace);
    }

    @Bean
    McpClientService mcpClientService(ToolRegistry registry) {
      return mock(McpClientService.class);
    }

    @Bean
    LlmGateway fakeGateway(Scenario scenario) {
      return (sessionId, profile, prompt) -> {
        boolean toolResult =
            prompt.messages().stream().anyMatch(ToolResponseMessage.class::isInstance);
        if (toolResult) {
          return response(scenario.mode == Mode.SAVE ? "保存完成" : "回忆完成");
        }
        scenario.firstPrompt.compareAndSet(null, prompt);
        assertEquals(2, prompt.availableTools().size());
        AssistantMessage.ToolCall call =
            scenario.mode == Mode.SAVE
                ? new AssistantMessage.ToolCall(
                    "save",
                    "function",
                    "save_memory",
                    "{\"content\":\"项目使用 Spring Boot\",\"scope\":\"core\"}")
                : new AssistantMessage.ToolCall(
                    "recall", "function", "recall_memory", "{\"keyword\":\"Spring\"}");
        return new ChatResponse(
            List.of(
                new Generation(
                    AssistantMessage.builder().content("").toolCalls(List.of(call)).build())));
      };
    }

    private static ChatResponse response(String content) {
      return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
  }
}
