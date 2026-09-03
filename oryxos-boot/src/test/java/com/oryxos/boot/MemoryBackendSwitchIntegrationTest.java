package com.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.oryxos.core.memory.MemoryScope;
import com.oryxos.core.memory.MemoryService;
import com.oryxos.memory.LongTermMemoryStore;
import java.nio.file.Path;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 三后端六个有向切换：目标从零开始、不隐式搬运或清库、回切后源数据原样可读。 */
@Tag("integration")
class MemoryBackendSwitchIntegrationTest {

  @TempDir Path workspaceA;

  @TempDir Path workspaceB;

  private void save(Path workspace, String backend, Mem0AdapterStub stub, String content)
      throws Exception {
    var probe = new MemoryBackendFixture.RowProbe(workspace.resolve("oryxos.db"));
    MemoryBackendFixture.runner(
            workspace,
            backend,
            new MemorySystemIntegrationTest.Scenario(MemorySystemIntegrationTest.Mode.SAVE),
            probe,
            stub)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              context.getBean(MemoryService.class).remember(content, MemoryScope.ARCHIVAL);
            });
  }

  private java.util.List<String> recall(
      Path workspace, String backend, Mem0AdapterStub stub, String query) throws Exception {
    var probe = new MemoryBackendFixture.RowProbe(workspace.resolve("oryxos.db"));
    var result = new java.util.concurrent.atomic.AtomicReference<java.util.List<String>>();
    MemoryBackendFixture.runner(
            workspace,
            backend,
            new MemorySystemIntegrationTest.Scenario(MemorySystemIntegrationTest.Mode.RECALL),
            probe,
            stub)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              result.set(context.getBean(MemoryService.class).recall(query));
            });
    return result.get();
  }

  private String load(Path workspace, String backend, Mem0AdapterStub stub) throws Exception {
    var probe = new MemoryBackendFixture.RowProbe(workspace.resolve("oryxos.db"));
    var result = new java.util.concurrent.atomic.AtomicReference<String>();
    MemoryBackendFixture.runner(
            workspace,
            backend,
            new MemorySystemIntegrationTest.Scenario(MemorySystemIntegrationTest.Mode.RECALL),
            probe,
            stub)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              result.set(context.getBean(LongTermMemoryStore.class).load());
            });
    return result.get();
  }

  @Test
  void allSixDirectedSwitchesNeverMigrateOrDropData() throws Exception {
    try (Mem0AdapterStub stub = Mem0AdapterStub.open();
        Mem0AdapterStub otherService = Mem0AdapterStub.open()) {
      SSLContext previous = SSLContext.getDefault();
      SSLContext.setDefault(stub.fixture().clientSslContext());
      try {
        // 三个后端各自独立保存（核心+归档）
        save(workspaceA, null, null, "MD归档A");
        save(workspaceA, "sqlite", null, "SQL归档A");
        save(workspaceA, "mem0", stub, "M0归档A");
        // 六个有向切换：目标不含来源数据（不隐式搬运）
        assertThat(recall(workspaceA, "sqlite", null, "MD归档A")).isEmpty();
        assertThat(recall(workspaceA, null, null, "SQL归档A")).isEmpty();
        assertThat(recall(workspaceA, "mem0", stub, "MD归档A")).isEmpty();
        assertThat(recall(workspaceA, null, null, "M0归档A")).isEmpty();
        assertThat(recall(workspaceA, "mem0", stub, "SQL归档A")).isEmpty();
        assertThat(recall(workspaceA, "sqlite", null, "M0归档A")).isEmpty();
        // 回切恢复：各后端原样读回自己的数据（不清库）
        assertThat(recall(workspaceA, null, null, "MD归档A"))
            .anySatisfy(item -> assertThat(item).contains("MD归档A"));
        assertThat(recall(workspaceA, "sqlite", null, "SQL归档A"))
            .anySatisfy(item -> assertThat(item).contains("SQL归档A"));
        assertThat(recall(workspaceA, "mem0", stub, "M0归档A"))
            .anySatisfy(item -> assertThat(item).contains("M0归档A"));
        // 核心原文也按后端各自保留
        save(workspaceA, null, null, "MD核心A");
        save(workspaceA, "sqlite", null, "SQL核心A");
        assertThat(load(workspaceA, null, null)).contains("MD核心A").doesNotContain("SQL核心A");
        assertThat(load(workspaceA, "sqlite", null)).contains("SQL核心A").doesNotContain("MD核心A");
        // 本地后端全程不触远端；mem0 全程不写本地 memory_entries
        assertThat(stub.requests()).isGreaterThan(0);
      } finally {
        SSLContext.setDefault(previous);
      }
      // 两个OryxOS工作区/两个服务身份互不可见
      SSLContext.setDefault(otherService.fixture().clientSslContext());
      try {
        save(workspaceB, null, null, "MD归档B");
        assertThat(recall(workspaceB, null, null, "MD归档A")).isEmpty();
        assertThat(recall(workspaceA, null, null, "MD归档B")).isEmpty();
        save(workspaceB, "mem0", otherService, "M0归档B");
        assertThat(recall(workspaceB, "mem0", otherService, "M0归档B")).containsExactly("M0归档B");
        assertThat(recall(workspaceB, "mem0", otherService, "M0归档A")).isEmpty();
      } finally {
        SSLContext.setDefault(previous);
      }
    }
  }

  @Test
  void unselectedBackendsHaveZeroNetworkAndZeroMemoryRowAccess() throws Exception {
    try (Mem0AdapterStub stub = Mem0AdapterStub.open()) {
      var markdown = new MemoryBackendFixture.RowProbe(workspaceA.resolve("oryxos.db"));
      MemoryBackendFixture.runner(
              workspaceA,
              null,
              new MemorySystemIntegrationTest.Scenario(MemorySystemIntegrationTest.Mode.SAVE),
              markdown)
          .run(
              context -> {
                // 本地默认不解析 MEM0 变量也能启动（占位符不被解析即证明）
                assertThat(context).hasNotFailed();
                context.getBean(MemoryService.class).remember("本地原文", MemoryScope.ARCHIVAL);
              });
      var sqlite = new MemoryBackendFixture.RowProbe(workspaceA.resolve("oryxos.db"));
      MemoryBackendFixture.runner(
              workspaceA,
              "sqlite",
              new MemorySystemIntegrationTest.Scenario(MemorySystemIntegrationTest.Mode.SAVE),
              sqlite)
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                context.getBean(MemoryService.class).remember("本地原文2", MemoryScope.ARCHIVAL);
              });
      assertThat(stub.requests()).isZero();
      assertThat(markdown.memoryRows).hasValue(0);
      assertThat(sqlite.memoryRows.get()).isGreaterThan(0);
    }
  }
}
