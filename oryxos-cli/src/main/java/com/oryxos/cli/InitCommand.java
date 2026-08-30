package com.oryxos.cli;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * 轻量命令:创建 {@code .oryxos/} 工作区,不启动 Spring.
 *
 * @author OryxOS Contributors
 */
@Command(mixinStandardHelpOptions = true, name = "init", description = "在当前目录初始化 .oryxos 工作区")
public class InitCommand implements Callable<Integer> {

  private final Path root;

  @Spec private CommandSpec commandSpec;

  /** 使用当前目录下的默认工作区. */
  public InitCommand() {
    this(Path.of(".oryxos"));
  }

  /** 测试和嵌入场景显式指定工作区,避免修改全局工作目录. */
  InitCommand(Path root) {
    this.root = root;
  }

  @Override
  public Integer call() throws Exception {
    PrintWriter out = commandSpec.commandLine().getOut();
    if (Files.exists(root)) {
      out.println(".oryxos 已存在 —— 跳过");
      return 0;
    }

    Files.createDirectories(root.resolve("profiles"));
    Files.createDirectories(root.resolve("sessions"));
    Files.createDirectories(root.resolve("skills"));
    Files.createDirectories(root.resolve("logs"));
    Files.createDirectories(root.resolve("tools"));
    Files.createDirectories(root.resolve("memory"));

    // 占位 DB 文件;表结构在首次 Spring 启动(chat/serve)时由 classpath 的 db/schema.sql 应用。
    Files.writeString(root.resolve("oryxos.db"), "");

    Files.writeString(
        root.resolve("memory/MEMORY.md"), "# Long-term memory\n\n## 核心记忆\n\n## 归档记忆\n");
    Files.writeString(root.resolve("AGENTS.md"), "# Project agent guidelines\n\n");
    Files.writeString(root.resolve("SOUL.md"), "# Agent personality\n\n");
    Files.writeString(root.resolve("USER.md"), "# User preferences\n\n");
    Files.writeString(
        root.resolve("mcp_servers.yaml"),
        """
        # MCP servers (stdio). Example:
        # servers:
        #   - name: example
        #     transport: stdio
        #     command: ["npx", "-y", "example-mcp"]
        servers: []
        """);

    String now = Instant.now().toString();
    String defaultProfile =
        """
        name: default
        description: Default agent profile
        identity:
          agent_name: oryxos-default
          prompt: You are a helpful enterprise assistant.
        provider:
          name: deepseek
          model: deepseek-chat
          temperature: 0.7
        tools:
          - http_get
          - http_post
          - read_file
          - write_file
          - list_dir
          - save_memory
          - recall_memory
        skills: []
        mcp_servers: []
        channels:
          - cli
        bootstrap:
          - AGENTS.md
          - SOUL.md
          - USER.md
        settings:
          max_iterations: 10
          max_history_turns: 20
        created_at: __NOW__
        updated_at: __NOW__
        """
            .replace("__NOW__", now);
    Files.writeString(root.resolve("profiles/default.yaml"), defaultProfile);

    out.println("已初始化 .oryxos 工作区");
    return 0;
  }
}
