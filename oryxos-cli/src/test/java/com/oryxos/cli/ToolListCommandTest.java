package com.oryxos.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

@DisplayName("tool list保持轻量声明视图")
class ToolListCommandTest {
  @TempDir Path workspace;

  @Test
  @DisplayName("未知运行时工具仍按Profile声明展示且不启动Spring或MCP")
  void listsDeclarationsWithoutStartingRuntime() throws Exception {
    Files.createDirectories(workspace.resolve("profiles"));
    Files.writeString(
        workspace.resolve("profiles/custom.yaml"),
        "name: custom\ntools: [read_file, absent_runtime_tool]\nmcp_servers: [business]\n");
    Files.writeString(
        workspace.resolve("mcp_servers.yaml"),
        "invalid MCP config must never be read by tool list");
    try (var files = mockStatic(CliFiles.class, CALLS_REAL_METHODS);
        var runtime = mockStatic(SpringRuntime.class);
        var processes = mockConstruction(ProcessBuilder.class)) {
      files.when(CliFiles::workspace).thenReturn(workspace);
      StringWriter output = new StringWriter();
      CommandLine command = new CommandLine(new OryxOsCli());
      command.setOut(new PrintWriter(output));
      assertEquals(0, command.execute("tool", "list", "--profile", "custom"));
      assertEquals("read_file\nabsent_runtime_tool\n", output.toString().replace("\r", ""));
      runtime.verifyNoInteractions();
      assertTrue(processes.constructed().isEmpty());
    }
  }

  @Test
  @DisplayName("空声明不会自动补上注册表工具")
  void keepsEmptyDeclarationEmpty() throws Exception {
    Files.createDirectories(workspace.resolve("profiles"));
    Files.writeString(workspace.resolve("profiles/default.yaml"), "tools: []\n");
    try (var files = mockStatic(CliFiles.class, CALLS_REAL_METHODS);
        var runtime = mockStatic(SpringRuntime.class)) {
      files.when(CliFiles::workspace).thenReturn(workspace);
      StringWriter output = new StringWriter();
      CommandLine command = new CommandLine(new OryxOsCli());
      command.setOut(new PrintWriter(output));
      assertEquals(0, command.execute("tool", "list"));
      assertTrue(output.toString().contains("未声明工具"));
      runtime.verifyNoInteractions();
    }
  }
}
