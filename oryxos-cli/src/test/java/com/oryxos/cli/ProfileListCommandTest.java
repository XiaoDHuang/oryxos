package com.oryxos.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

@DisplayName("profile list 两来源列出(29 节)")
class ProfileListCommandTest {

  @TempDir Path workspace;

  private String previousRoot;

  private String runList() {
    previousRoot = System.getProperty("oryxos.root");
    System.setProperty("oryxos.root", workspace.toString());
    StringWriter output = new StringWriter();
    CommandLine command = new CommandLine(new ProfileListCommand());
    command.setOut(new PrintWriter(output));
    assertThat(command.execute()).isZero();
    return output.toString();
  }

  @AfterEach
  void restoreRoot() {
    if (previousRoot == null) {
      System.clearProperty("oryxos.root");
    } else {
      System.setProperty("oryxos.root", previousRoot);
    }
  }

  @Test
  @DisplayName("手写YAML与Agent目录两来源同列_同名只列一次_缺AGENT.md不列")
  void listsBothSourcesWithDedup() throws Exception {
    Files.createDirectories(workspace.resolve("profiles"));
    Files.writeString(workspace.resolve("profiles").resolve("ops-agent.yaml"), "name: ops-agent\n");
    Files.createDirectories(workspace.resolve("agents").resolve("daily-reconcile"));
    Files.writeString(
        workspace.resolve("agents").resolve("daily-reconcile").resolve("AGENT.md"), "---\n---\n");
    Files.createDirectories(workspace.resolve("agents").resolve("ops-agent"));
    Files.writeString(
        workspace.resolve("agents").resolve("ops-agent").resolve("AGENT.md"), "---\n---\n");
    Files.createDirectories(workspace.resolve("agents").resolve("no-main-file"));

    String output = runList();

    assertThat(output).contains("ops-agent");
    assertThat(output).contains("daily-reconcile");
    assertThat(output).doesNotContain("no-main-file");
    assertThat(output.split("ops-agent", -1)).hasSize(2);
  }

  @Test
  @DisplayName("只有Agent目录没有profiles目录也照列")
  void listsAgentsWithoutProfilesDir() throws Exception {
    Files.createDirectories(workspace.resolve("agents").resolve("daily-reconcile"));
    Files.writeString(
        workspace.resolve("agents").resolve("daily-reconcile").resolve("AGENT.md"), "---\n---\n");

    String output = runList();

    assertThat(output).contains("daily-reconcile");
  }

  @Test
  @DisplayName("两来源皆空打印无Profile")
  void emptyWorkspacePrintsNone() throws Exception {
    Files.createDirectories(workspace.resolve("profiles"));

    String output = runList();

    assertThat(output).contains("(无 Profile)");
  }
}
