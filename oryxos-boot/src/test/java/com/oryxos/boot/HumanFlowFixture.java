package com.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oryxos.storage.audit.JpaToolInvocationAudit;
import com.oryxos.storage.session.JpaSessionManager;
import com.oryxos.tool.notify.WebhookNotifyAdapter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/** 合成语料只放在本测试拥有的目录,审计和会话始终使用真实 SQLite. */
final class HumanFlowFixture {

  static final ObjectMapper JSON = new ObjectMapper();
  static final String FACT = "我在北京，喜欢美式咖啡";
  static final List<String> TOOLS =
      List.of(
          "read_file",
          "write_file",
          "list_dir",
          "shell",
          "http_get",
          "http_post",
          "notify",
          "save_memory",
          "recall_memory");

  private HumanFlowFixture() {}

  static Path prepare(Path root, String provider, String model) throws IOException {
    Files.createDirectories(root.resolve("profiles"));
    Files.createDirectories(root.resolve("memory"));
    Files.writeString(root.resolve("AGENTS.md"), "仅使用本次测试的合成数据。\n");
    Files.writeString(
        root.resolve("memory/MEMORY.md"), "# Long-term memory\n\n## 核心记忆\n\n## 归档记忆\n");
    Files.writeString(root.resolve("mcp_servers.yaml"), "servers: []\n");
    Files.writeString(
        root.resolve("profiles/flow.yaml"),
        """
        name: flow
        provider:
          name: %s
          model: %s
          temperature: 0
        tools: [read_file, write_file, list_dir, shell, http_get, http_post, notify, save_memory, recall_memory]
        bootstrap: [AGENTS.md]
        settings:
          max_iterations: 10
          max_history_turns: 20
        """
            .formatted(provider, model));
    return root;
  }

  static ApplicationContextRunner storageAndTools(Path root) throws ClassNotFoundException {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DataSourceAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class,
                TransactionAutoConfiguration.class,
                SqlInitializationAutoConfiguration.class,
                Class.forName("com.oryxos.memory.MemoryConfiguration"),
                Class.forName("com.oryxos.tool.ToolConfiguration")))
        .withUserConfiguration(Storage.class)
        .withBean(RestClient.Builder.class, RestClient::builder)
        .withPropertyValues(
            "oryxos.root=" + root,
            "memory.backend=markdown",
            "spring.datasource.url=jdbc:sqlite:" + root.resolve("oryxos.db"),
            "spring.datasource.driver-class-name=org.sqlite.JDBC",
            "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:db/schema.sql",
            "file.allowed-paths=" + root,
            "shell.allowed-commands=",
            "http.allowed-domains=localhost");
  }

  static void assertAccounts(
      DataSource source, String sessionId, int modelCalls, String tool, boolean success) {
    JdbcTemplate jdbc = new JdbcTemplate(source);
    var calls = jdbc.queryForList("SELECT * FROM llm_calls WHERE session_id=?", sessionId);
    assertThat(calls).hasSize(modelCalls);
    assertThat(calls)
        .allSatisfy(
            row -> {
              assertThat(((Number) row.get("success")).intValue()).isEqualTo(1);
              assertThat(((Number) row.get("total_tokens")).intValue()).isPositive();
              assertThat(row.get("provider")).isNotNull();
            });
    var tools = jdbc.queryForList("SELECT * FROM tool_invocations WHERE session_id=?", sessionId);
    assertThat(tools).hasSize(tool == null ? 0 : 1);
    if (tool != null) {
      assertThat(tools.getFirst().get("tool_name")).isEqualTo(tool);
      assertThat(((Number) tools.getFirst().get("success")).intValue()).isEqualTo(success ? 1 : 0);
      assertThat(tools.getFirst().get("started_at")).isNotNull();
      assertThat(tools.getFirst().get("completed_at")).isNotNull();
      if (!success) {
        assertThat(tools.getFirst().get("error_message")).isNotNull();
      }
    }
  }

  static JsonNode history(DataSource source, String sessionId) throws IOException {
    return JSON.readTree(
        new JdbcTemplate(source)
            .queryForObject(
                "SELECT messages_json FROM sessions WHERE session_id=?", String.class, sessionId));
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EntityScan("com.oryxos.storage")
  @EnableJpaRepositories("com.oryxos.storage")
  @Import({JpaSessionManager.class, JpaToolInvocationAudit.class, WebhookNotifyAdapter.class})
  static class Storage {}
}
