package com.oryxos.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

@DisplayName("工作区初始化")
class InitCommandTest {

  @TempDir Path directory;

  @Test
  @DisplayName("系统属性覆盖同时用于默认init与轻命令_未设置时保留默认根")
  void rootOverrideIsSharedByDefaultInitAndLightCommands() throws Exception {
    String previous = System.getProperty("oryxos.root");
    Path root = directory.resolve("isolated");
    try {
      System.setProperty("oryxos.root", root.toString());
      assertThat(new CommandLine(new InitCommand()).execute()).isZero();
      assertThat(CliFiles.workspace()).isEqualTo(root);
      assertThat(root.resolve("profiles/default.yaml")).exists();
      StringWriter output = new StringWriter();
      CommandLine status = new CommandLine(new StatusCommand());
      status.setOut(new PrintWriter(output));
      assertThat(status.execute()).isZero();
      assertThat(output.toString()).contains("Profile 数: 1");
      System.clearProperty("oryxos.root");
      assertThat(CliFiles.workspace()).isEqualTo(Path.of(".oryxos"));
    } finally {
      if (previous == null) {
        System.clearProperty("oryxos.root");
      } else {
        System.setProperty("oryxos.root", previous);
      }
    }
  }

  @Test
  @DisplayName("新工作区创建标准Memory双分区")
  void initializesCanonicalMemorySections() throws Exception {
    Path workspace = directory.resolve(".oryxos");

    int exitCode = new CommandLine(new InitCommand(workspace)).execute();

    assertThat(exitCode).isZero();
    assertThat(
            Files.readString(workspace.resolve("memory").resolve("MEMORY.md"))
                .replace("\r\n", "\n"))
        .isEqualTo("# Long-term memory\n\n## 核心记忆\n\n## 归档记忆\n");
  }

  @Test
  @DisplayName("再次初始化不覆盖已有用户内容")
  void repeatedInitDoesNotOverwriteExistingContent() throws Exception {
    Path workspace = directory.resolve(".oryxos");
    CommandLine command = new CommandLine(new InitCommand(workspace));
    assertThat(command.execute()).isZero();
    Path memory = workspace.resolve("memory").resolve("MEMORY.md");
    Files.writeString(memory, "用户已有记忆");

    assertThat(command.execute()).isZero();

    assertThat(Files.readString(memory)).isEqualTo("用户已有记忆");
  }
}
