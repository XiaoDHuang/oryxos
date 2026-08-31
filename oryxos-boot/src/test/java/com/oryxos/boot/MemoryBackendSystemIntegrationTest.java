package com.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.oryxos.core.memory.MemoryScope;
import com.oryxos.core.memory.MemoryService;
import com.oryxos.core.react.AgentService;
import com.oryxos.core.session.SessionManager;
import com.oryxos.memory.LongTermMemory;
import com.oryxos.memory.LongTermMemoryStore;
import com.oryxos.memory.SqliteMemoryStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("integration")
class MemoryBackendSystemIntegrationTest {

  @TempDir Path workspace;

  @Test
  void switchesLocalBackendsWithoutMigrationAndRetainsRestartAudit() throws Exception {
    Path database = workspace.resolve("oryxos.db");
    var markdown = new MemoryBackendFixture.RowProbe(database);
    var save = new MemorySystemIntegrationTest.Scenario(MemorySystemIntegrationTest.Mode.SAVE);
    MemoryBackendFixture.runner(workspace, null, save, markdown)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var session =
                  context.getBean(SessionManager.class).getOrCreate("cli", "md", "memory-test");
              assertThat(context.getBean(AgentService.class).process(session, "记住偏好"))
                  .isEqualTo("保存完成");
              assertThat(markdown.memoryRows).hasValue(0);
              new JdbcTemplate(markdown)
                  .update("INSERT INTO llm_calls(call_id,success) VALUES('old-llm',1)");
            });
    String originalMarkdown = Files.readString(workspace.resolve("memory/MEMORY.md"));

    var sqlite = new MemoryBackendFixture.RowProbe(database);
    MemoryBackendFixture.runner(workspace, "sqlite", save, sqlite)
        .run(
            context -> {
              assertThat(context).hasNotFailed().doesNotHaveBean(LongTermMemory.class);
              assertThat(context.getBean(LongTermMemoryStore.class))
                  .isInstanceOf(SqliteMemoryStore.class);
              MemoryService memory = context.getBean(MemoryService.class);
              assertThat(memory.buildContext(new com.oryxos.core.session.Session("empty", "p"), 0))
                  .isEmpty();
              var session =
                  context.getBean(SessionManager.class).getOrCreate("cli", "sql", "memory-test");
              assertThat(context.getBean(AgentService.class).process(session, "记住偏好"))
                  .isEqualTo("保存完成");
              memory.remember("SQLite归档中文", MemoryScope.ARCHIVAL);
              var jdbc = new JdbcTemplate(sqlite);
              assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_entries", Integer.class))
                  .isEqualTo(2);
              assertThat(jdbc.queryForObject("SELECT count(*) FROM sessions", Integer.class))
                  .isEqualTo(2);
              assertThat(
                      jdbc.queryForObject(
                          "SELECT count(*) FROM tool_invocations WHERE status='completed'",
                          Integer.class))
                  .isEqualTo(2);
              assertThat(
                      jdbc.queryForObject(
                          "SELECT count(*) FROM llm_calls WHERE call_id='old-llm'", Integer.class))
                  .isEqualTo(1);
            });
    assertThat(Files.readString(workspace.resolve("memory/MEMORY.md"))).isEqualTo(originalMarkdown);

    var recall = new MemorySystemIntegrationTest.Scenario(MemorySystemIntegrationTest.Mode.RECALL);
    var reopened = new MemoryBackendFixture.RowProbe(database);
    MemoryBackendFixture.runner(workspace, "sqlite", recall, reopened)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(MemoryService.class).recall("SQLite"))
                  .containsExactly("SQLite归档中文");
              var session =
                  context.getBean(SessionManager.class).getOrCreate("cli", "reader", "memory-test");
              assertThat(context.getBean(AgentService.class).process(session, "回忆偏好"))
                  .isEqualTo("回忆完成");
              assertThat(recall.firstPrompt.get().messages())
                  .anyMatch(message -> message.getText().contains("项目使用 Spring Boot"));
              var jdbc = new JdbcTemplate(reopened);
              assertThat(
                      jdbc.queryForObject(
                          "SELECT count(*) FROM tool_invocations WHERE status='completed'",
                          Integer.class))
                  .isEqualTo(3);
              assertThat(jdbc.queryForObject("SELECT count(*) FROM sessions", Integer.class))
                  .isEqualTo(3);
            });
    var back = new MemoryBackendFixture.RowProbe(database);
    MemoryBackendFixture.runner(workspace, "markdown", recall, back)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(MemoryService.class).recall("SQLite")).isEmpty();
              assertThat(context.getBean(LongTermMemoryStore.class).load())
                  .contains("项目使用 Spring Boot");
              assertThat(back.memoryRows).hasValue(0);
            });
    assertThat(Files.readString(workspace.resolve("memory/MEMORY.md"))).isEqualTo(originalMarkdown);
  }

  @Test
  void selectedSqliteRejectsExistingWrongStructureAfterInitialization() throws Exception {
    var source = new MemoryBackendFixture.RowProbe(workspace.resolve("wrong.db"));
    new JdbcTemplate(source)
        .execute(
            "CREATE TABLE memory_entries(id INTEGER PRIMARY KEY,"
                + "scope VARCHAR(16) NOT NULL,content INTEGER NOT NULL,"
                + "created_at TIMESTAMP NOT NULL)");
    MemoryBackendFixture.runner(
            workspace,
            "sqlite",
            new MemorySystemIntegrationTest.Scenario(MemorySystemIntegrationTest.Mode.SAVE),
            source)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure()).hasMessageContaining("SQLite记忆表结构不兼容");
              assertThat(source.memoryRows).hasValue(0);
            });
  }
}
