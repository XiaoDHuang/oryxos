package com.oryxos.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.oryxos.core.memory.MemoryScope;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkdownMemoryStoreContractTest extends AbstractMemoryStoreContractTest {

  @TempDir Path workspace;

  @Override
  LongTermMemoryStore openStore() {
    return new MarkdownMemoryStore(new LongTermMemory(workspace));
  }

  @Override
  LongTermMemoryStore unavailableStore() {
    return new MarkdownMemoryStore(new LongTermMemory(workspace.resolve("missing")));
  }

  @Test
  void preservesLegacyAndMissingSectionsAndUserAssets() throws Exception {
    Files.createDirectories(workspace.resolve("memory"));
    Path file = workspace.resolve("memory/MEMORY.md");
    Path user = workspace.resolve("USER.md");
    Files.writeString(user, "用户维护的偏好");
    for (String original : new String[] {"旧归档Spring", "## 归档记忆\n旧归档Spring"}) {
      Files.writeString(file, original);
      LongTermMemoryStore store = openStore();
      assertThat(store.recall("Spring")).containsExactly("旧归档Spring");
      store.append("核心原文", MemoryScope.CORE);
      assertThat(store.load()).contains("核心原文", "旧归档Spring");
    }
    Files.writeString(file, "## 核心记忆\n旧核心Spring");
    assertThat(openStore().load()).contains("旧核心Spring").doesNotContain("## 归档记忆");
    assertThat(openStore().recall("Spring")).isEmpty();
    assertThat(Files.readString(user)).isEqualTo("用户维护的偏好");
  }

  @Test
  void keepsJavaCharWindowWithoutLosingFullLiteralSearch() throws Exception {
    Files.createDirectories(workspace.resolve("memory"));
    Path file = workspace.resolve("memory/MEMORY.md");
    for (int length : new int[] {3999, 4000, 4001}) {
      String archive = "x".repeat(length - 2) + "🚀";
      Files.writeString(file, "## 核心记忆\n核心\n\n## 归档记忆\n" + archive);
      assertThat(openStore().load())
          .isEqualTo("## 核心记忆\n核心\n\n## 归档记忆\n" + archive.substring(Math.max(0, length - 4000)));
      assertThat(Files.readString(file)).endsWith(archive);
    }
    Files.writeString(file, "## 归档记忆\nOld %_'中文\n" + "x".repeat(4500));
    assertThat(openStore().load()).doesNotContain("Old");
    assertThat(openStore().recall("%_'中文")).containsExactly("Old %_'中文");
    assertThat(openStore().recall("old")).isEmpty();
  }
}
