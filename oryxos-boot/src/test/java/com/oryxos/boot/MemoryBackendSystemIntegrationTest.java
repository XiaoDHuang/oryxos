package com.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.oryxos.core.memory.MemoryScope;
import com.oryxos.core.memory.MemoryService;
import com.oryxos.core.react.AgentService;
import com.oryxos.core.session.SessionManager;
import com.oryxos.memory.LongTermMemory;
import com.oryxos.memory.LongTermMemoryStore;
import com.oryxos.memory.Mem0MemoryStore;
import com.oryxos.memory.SqliteMemoryStore;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.net.ssl.SSLContext;
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
  void mem0BackendServesSameChainWithoutTouchingLocalMemoryRows() throws Exception {
    Path database = workspace.resolve("oryxos.db");
    try (Mem0AdapterStub stub = Mem0AdapterStub.open()) {
      SSLContext previous = SSLContext.getDefault();
      SSLContext.setDefault(stub.fixture().clientSslContext());
      try {
        var save = new MemorySystemIntegrationTest.Scenario(MemorySystemIntegrationTest.Mode.SAVE);
        var probe = new MemoryBackendFixture.RowProbe(database);
        MemoryBackendFixture.runner(workspace, "mem0", save, probe, stub)
            .run(
                context -> {
                  assertThat(context).hasNotFailed();
                  // 既有构造入口：同一端口/Bean 形态，Store 为真实 Mem0MemoryStore
                  assertThat(context.getBean(LongTermMemoryStore.class))
                      .isInstanceOf(Mem0MemoryStore.class);
                  MemoryService memory = context.getBean(MemoryService.class);
                  // 空存储不产出空段落
                  assertThat(
                          memory.buildContext(new com.oryxos.core.session.Session("empty", "p"), 0))
                      .isEmpty();
                  var session =
                      context.getBean(SessionManager.class).getOrCreate("cli", "m0", "memory-test");
                  assertThat(context.getBean(AgentService.class).process(session, "记住偏好"))
                      .isEqualTo("保存完成");
                  memory.remember("Mem0核心原文", MemoryScope.CORE);
                  memory.remember("Mem0归档偏好中文", MemoryScope.ARCHIVAL);
                  // 本地 memory_entries 行零访问；会话/工具审计仍落本地SQLite
                  assertThat(probe.memoryRows).hasValue(0);
                  var jdbc = new JdbcTemplate(probe);
                  assertThat(jdbc.queryForObject("SELECT count(*) FROM sessions", Integer.class))
                      .isEqualTo(1);
                });

        // 重启生效：同一配置新上下文仍可读到远端持久状态
        var recall =
            new MemorySystemIntegrationTest.Scenario(MemorySystemIntegrationTest.Mode.RECALL);
        var reopened = new MemoryBackendFixture.RowProbe(database);
        MemoryBackendFixture.runner(workspace, "mem0", recall, reopened, stub)
            .run(
                context -> {
                  assertThat(context).hasNotFailed();
                  MemoryService memory = context.getBean(MemoryService.class);
                  assertThat(memory.recall("Mem0归档")).containsExactly("Mem0归档偏好中文");
                  var session =
                      context
                          .getBean(SessionManager.class)
                          .getOrCreate("cli", "m0-reader", "memory-test");
                  assertThat(context.getBean(AgentService.class).process(session, "回忆偏好"))
                      .isEqualTo("回忆完成");
                  // Prompt 注入：核心段在归档段之前，角色为用户输入在最后
                  var messages = recall.firstPrompt.get().messages();
                  String system =
                      messages.stream()
                          .map(org.springframework.ai.chat.messages.Message::getText)
                          .filter(text -> text.contains("## 核心记忆"))
                          .findFirst()
                          .orElseThrow();
                  assertThat(system).contains("## 归档记忆");
                  assertThat(system.indexOf("## 核心记忆")).isLessThan(system.indexOf("## 归档记忆"));
                  assertThat(system).contains("Mem0核心原文").contains("Mem0归档偏好中文");
                  assertThat(messages.getLast().getText()).isEqualTo("回忆偏好");
                  assertThat(reopened.memoryRows).hasValue(0);
                });
      } finally {
        SSLContext.setDefault(previous);
      }
    }
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
