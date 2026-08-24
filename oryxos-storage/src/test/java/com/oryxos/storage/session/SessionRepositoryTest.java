package com.oryxos.storage.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.oryxos.core.session.Session;
import java.sql.Connection;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(
    properties = {
      "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
      "spring.jpa.hibernate.ddl-auto=none"
    })
class SessionRepositoryTest {

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EntityScan("com.oryxos.storage")
  @EnableJpaRepositories("com.oryxos.storage")
  static class TestConfig {

    /** 每个测试类一份全新 SQLite 库;表结构由真实脚本建. */
    @Bean
    DataSource dataSource() {
      return DataSourceBuilder.create()
          .driverClassName("org.sqlite.JDBC")
          .url("jdbc:sqlite:target/test-session-repo-" + UUID.randomUUID() + ".db")
          .build();
    }
  }

  @Autowired private SessionRepository repository;

  @Autowired private DataSource dataSource;

  @BeforeEach
  void createSchemaWithRealScript() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema.sql"));
    }
  }

  @Test
  @DisplayName("手工脚本建表_sessions能存能读")
  void savesAndReadsEntity() {
    SessionEntity entity =
        new SessionEntity(
            "cli:wang:default",
            "default",
            "cli",
            "wang",
            "[]",
            null,
            "active",
            "2026-08-23T00:00:00Z",
            "2026-08-23T00:00:00Z",
            null);
    repository.save(entity);

    SessionEntity loaded = repository.findById("cli:wang:default").orElseThrow();

    assertThat(loaded.getProfileName()).isEqualTo("default");
    assertThat(loaded.getChannel()).isEqualTo("cli");
    assertThat(loaded.getStatus()).isEqualTo("active");
  }

  @Test
  @DisplayName("messages_json回读_三角色与工具调用意图完整")
  void messagesJson_roundTripPreservesAllRoles() {
    JpaSessionManager manager = new JpaSessionManager(repository);
    Session session = manager.getOrCreate("cli", "wang", "default");
    session.append(new UserMessage("查天气"));
    session.append(
        AssistantMessage.builder()
            .content("我查一下")
            .toolCalls(
                java.util.List.of(
                    new AssistantMessage.ToolCall(
                        "c-1", "function", "http_get", "{\"url\":\"w\"}")))
            .build());
    session.appendToolResult(
        ToolResponseMessage.builder()
            .responses(
                java.util.List.of(
                    new ToolResponseMessage.ToolResponse("c-1", "http_get", "sunny 30C")))
            .build());
    manager.save(session);

    // 模拟重启:换一个全新的 manager 实例按 id 回读
    Session reloaded = new JpaSessionManager(repository).get("cli:wang:default").orElseThrow();

    assertThat(reloaded.messages()).hasSize(3);
    assertThat(reloaded.messages().get(0)).isInstanceOf(UserMessage.class);
    assertThat(reloaded.messages().get(1)).isInstanceOf(AssistantMessage.class);
    AssistantMessage assistant = (AssistantMessage) reloaded.messages().get(1);
    assertThat(assistant.getText()).isEqualTo("我查一下");
    assertThat(assistant.getToolCalls()).hasSize(1);
    assertThat(assistant.getToolCalls().get(0).name()).isEqualTo("http_get");
    assertThat(reloaded.messages().get(2)).isInstanceOf(ToolResponseMessage.class);
    ToolResponseMessage toolMessage = (ToolResponseMessage) reloaded.messages().get(2);
    assertThat(toolMessage.getResponses().get(0).responseData()).isEqualTo("sunny 30C");
  }
}
