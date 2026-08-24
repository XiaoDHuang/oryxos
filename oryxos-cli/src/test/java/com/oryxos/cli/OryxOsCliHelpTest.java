package com.oryxos.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class OryxOsCliHelpTest {

  /** 课件的 12 个叶子操作(父命令与兼容命令 version 不计入)。 */
  private static final String[][] LEAF_COMMANDS = {
    {"init"},
    {"status"},
    {"chat"},
    {"serve"},
    {"gateway"},
    {"profile", "list"},
    {"profile", "show"},
    {"profile", "create"},
    {"profile", "delete"},
    {"provider", "list"},
    {"tool", "list"},
    {"session", "list"},
  };

  @Test
  @DisplayName("12个叶子命令的--help退出码全为0")
  void allLeafCommands_helpExitsZero() {
    for (String[] command : LEAF_COMMANDS) {
      String[] args = new String[command.length + 1];
      System.arraycopy(command, 0, args, 0, command.length);
      args[command.length] = "--help";

      CommandLine commandLine = new CommandLine(new OryxOsCli());
      StringWriter capture = new StringWriter();
      commandLine.setOut(new PrintWriter(capture));
      commandLine.setErr(new PrintWriter(capture));
      int exitCode = commandLine.execute(args);

      assertThat(exitCode).as(String.join(" ", command) + " --help 应正常输出").isEqualTo(0);
      assertThat(capture.toString()).contains("Usage");
    }
  }

  @Test
  @DisplayName("根命令--help正常")
  void root_helpExitsZero() {
    CommandLine commandLine = new CommandLine(new OryxOsCli());
    StringWriter capture = new StringWriter();
    commandLine.setOut(new PrintWriter(capture));
    int exitCode = commandLine.execute("--help");

    assertThat(exitCode).isEqualTo(0);
    assertThat(capture.toString()).contains("Commands:");
  }
}
