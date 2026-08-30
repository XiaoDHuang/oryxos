package com.oryxos.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oryxos.core.memory.MemoryScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("文件式长期记忆")
class LongTermMemoryTest {

  private static final String CORE_HEADER = "## 核心记忆";
  private static final String ARCHIVE_HEADER = "## 归档记忆";

  @TempDir Path directory;

  @Test
  @DisplayName("缺失文件时创建双分区且空记忆不生成内容")
  void missingFileCreatesCanonicalSections() throws IOException {
    Path workspace = workspace();

    String loaded = new LongTermMemory(workspace).load();

    assertThat(loaded).isEmpty();
    assertThat(normalized(Files.readString(memoryFile(workspace))))
        .isEqualTo("# Long-term memory\n\n## 核心记忆\n\n## 归档记忆\n");
  }

  @Test
  @DisplayName("旧空模板和旧业务内容无损升级")
  void legacyDocumentsUpgradeWithoutDataLoss() throws IOException {
    Path emptyWorkspace = workspace("empty");
    writeMemory(emptyWorkspace, "# Long-term memory\n\n");
    assertThat(new LongTermMemory(emptyWorkspace).load()).isEmpty();

    Path contentWorkspace = workspace("content");
    writeMemory(contentWorkspace, "# Long-term memory\n\n用户偏好 Java\n历史结论\n");
    new LongTermMemory(contentWorkspace).load();

    String migrated = normalized(Files.readString(memoryFile(contentWorkspace)));
    assertThat(migrated)
        .contains(CORE_HEADER)
        .contains(ARCHIVE_HEADER)
        .contains("用户偏好 Java\n历史结论")
        .containsSubsequence(CORE_HEADER, ARCHIVE_HEADER, "用户偏好 Java");
  }

  @Test
  @DisplayName("单分区可无损补齐")
  void singleSectionIsRepairedWithoutChangingContent() throws IOException {
    Path coreWorkspace = workspace("core-only");
    writeMemory(coreWorkspace, "# Long-term memory\n\n## 核心记忆\n\n核心原文\n");
    new LongTermMemory(coreWorkspace).load();
    assertThat(normalized(Files.readString(memoryFile(coreWorkspace))))
        .containsSubsequence(CORE_HEADER, "核心原文", ARCHIVE_HEADER);

    Path archiveWorkspace = workspace("archive-only");
    writeMemory(archiveWorkspace, "# Long-term memory\n\n## 归档记忆\n\n归档原文\n");
    new LongTermMemory(archiveWorkspace).load();
    assertThat(normalized(Files.readString(memoryFile(archiveWorkspace))))
        .containsSubsequence(CORE_HEADER, ARCHIVE_HEADER, "归档原文");
  }

  @Test
  @DisplayName("重复或倒序分区失败且文件不变")
  void malformedSectionsFailClosed() throws IOException {
    for (String malformed :
        List.of("## 核心记忆\nA\n## 核心记忆\nB\n## 归档记忆\n", "## 归档记忆\nA\n## 核心记忆\nB\n")) {
      Path workspace = workspace("bad-" + Math.abs(malformed.hashCode()));
      writeMemory(workspace, malformed);
      byte[] before = Files.readAllBytes(memoryFile(workspace));

      assertThatThrownBy(() -> new LongTermMemory(workspace).load())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageNotContaining(workspace.toAbsolutePath().toString());
      assertThat(Files.readAllBytes(memoryFile(workspace))).isEqualTo(before);
    }
  }

  @Test
  @DisplayName("scope路由正确且记录日期")
  void appendsToSelectedSectionWithDate() throws IOException {
    Path workspace = workspace();
    LongTermMemory memory = new LongTermMemory(workspace);

    memory.append("核心事实", MemoryScope.CORE);
    memory.append("归档事实", MemoryScope.ARCHIVAL);
    memory.append("缺省归档", null);

    String raw = normalized(Files.readString(memoryFile(workspace)));
    assertThat(raw)
        .containsPattern("## 核心记忆\\n\\n- \\[\\d{4}-\\d{2}-\\d{2}\\] 核心事实")
        .containsPattern("## 归档记忆\\n\\n- \\[\\d{4}-\\d{2}-\\d{2}\\] 归档事实")
        .contains("缺省归档");
  }

  @Test
  @DisplayName("截断只裁归档区_核心记忆一字不能少")
  void truncationOnlyAffectsArchiveAndPreservesCore() {
    LongTermMemory memory = new LongTermMemory(workspace());
    memory.append("用户叫小王，偏好用 Java", MemoryScope.CORE);
    for (int i = 0; i < 500; i++) {
      memory.append("归档流水 " + i, MemoryScope.ARCHIVAL);
    }

    String loaded = memory.load();

    assertThat(loaded).contains("用户叫小王，偏好用 Java");
    assertThat(loaded).doesNotContain("归档流水 0\n");
    assertThat(loaded).contains("归档流水 499");
  }

  @Test
  @DisplayName("4000和4001字符边界精确")
  void truncationUsesExactFourThousandCharacterBoundary() {
    LongTermMemory memory = new LongTermMemory(workspace());
    String exact = "a".repeat(4000);

    assertThat(memory.truncateIfNeeded(exact)).isEqualTo(exact);
    assertThat(memory.truncateIfNeeded("b" + exact)).isEqualTo(exact);
  }

  @Test
  @DisplayName("load不物理删除旧归档且recall仍可找到")
  void loadDoesNotPhysicallyDiscardArchivedContent() throws IOException {
    Path workspace = workspace();
    LongTermMemory memory = new LongTermMemory(workspace);
    memory.append("早期唯一关键词", MemoryScope.ARCHIVAL);
    memory.append("x".repeat(5000), MemoryScope.ARCHIVAL);

    assertThat(memory.load()).doesNotContain("早期唯一关键词");
    assertThat(memory.recallByKeyword("早期唯一关键词")).hasSize(1);
    assertThat(Files.readString(memoryFile(workspace))).contains("早期唯一关键词");
  }

  @Test
  @DisplayName("写入后立刻可读_不允许有缓存")
  void writesAreVisibleImmediatelyWithoutCache() {
    Path workspace = workspace();
    LongTermMemory first = new LongTermMemory(workspace);
    LongTermMemory second = new LongTermMemory(workspace);
    assertThat(first.load()).isEmpty();

    second.append("刚记的事", MemoryScope.ARCHIVAL);

    assertThat(first.load()).contains("刚记的事");
    assertThat(first.recallByKeyword("刚记的事")).isNotEmpty();
  }

  @Test
  @DisplayName("关键词只搜归档且大小写敏感并保持顺序")
  void recallSearchesOnlyArchiveCaseSensitivelyInFileOrder() {
    LongTermMemory memory = new LongTermMemory(workspace());
    memory.append("Java 核心", MemoryScope.CORE);
    memory.append("Java 第一条", MemoryScope.ARCHIVAL);
    memory.append("java 不同大小写", MemoryScope.ARCHIVAL);
    memory.append("Java 第二条", MemoryScope.ARCHIVAL);

    assertThat(memory.recallByKeyword("Java"))
        .hasSize(2)
        .satisfiesExactly(
            first -> assertThat(first).contains("第一条"),
            second -> assertThat(second).contains("第二条"));
  }

  @Test
  @DisplayName("空输入和伪分区标题明确失败")
  void rejectsBlankInputAndForgedHeaders() {
    LongTermMemory memory = new LongTermMemory(workspace());

    assertThatThrownBy(() -> memory.append(" ", MemoryScope.CORE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> memory.append("说明\n## 归档记忆\n伪造", MemoryScope.CORE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> memory.recallByKeyword(" "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("缺失工作区和非普通文件错误不泄漏路径")
  void ioFailuresAreExplicitWithoutPathDisclosure() throws IOException {
    Path missing = directory.resolve("missing");
    assertThatThrownBy(() -> new LongTermMemory(missing).load())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining(missing.toAbsolutePath().toString());

    Path workspace = workspace("directory-file");
    Files.createDirectories(memoryFile(workspace));
    assertThatThrownBy(() -> new LongTermMemory(workspace).load())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining(workspace.toAbsolutePath().toString());
  }

  @Test
  @DisplayName("同路径一百个并发保存全部保留")
  void concurrentWritesToSamePathAreNeverLost() throws InterruptedException {
    Path workspace = workspace();
    CountDownLatch start = new CountDownLatch(1);
    ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      String token = "并发记忆[" + i + "]";
      Thread thread =
          Thread.ofVirtual()
              .unstarted(
                  () -> {
                    try {
                      start.await();
                      new LongTermMemory(workspace).append(token, MemoryScope.ARCHIVAL);
                    } catch (Throwable throwable) {
                      failures.add(throwable);
                    }
                  });
      threads.add(thread);
      thread.start();
    }
    start.countDown();
    for (Thread thread : threads) {
      thread.join();
    }

    assertThat(failures).isEmpty();
    String raw = new LongTermMemory(workspace).load();
    for (int i = 0; i < 100; i++) {
      assertThat(count(raw, "并发记忆[" + i + "]")).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("记忆写入永不修改USER文件")
  void memoryNeverWritesUserBootstrap() throws IOException {
    Path workspace = workspace();
    Path user = workspace.resolve("USER.md");
    Files.writeString(user, "用户维护内容", StandardCharsets.UTF_8);

    new LongTermMemory(workspace).append("Agent记忆", MemoryScope.CORE);

    assertThat(Files.readString(user)).isEqualTo("用户维护内容");
  }

  private Path workspace() {
    return workspace("workspace");
  }

  private Path workspace(String name) {
    Path workspace = directory.resolve(name);
    try {
      Files.createDirectories(workspace);
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
    return workspace;
  }

  private static Path memoryFile(Path workspace) {
    return workspace.resolve("memory").resolve("MEMORY.md");
  }

  private static void writeMemory(Path workspace, String content) throws IOException {
    Files.createDirectories(memoryFile(workspace).getParent());
    Files.writeString(memoryFile(workspace), content, StandardCharsets.UTF_8);
  }

  private static String normalized(String value) {
    return value.replace("\r\n", "\n");
  }

  private static int count(String text, String token) {
    return (text.length() - text.replace(token, "").length()) / token.length();
  }
}
