package com.oryxos.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * 轻命令 {@code profile create}:在 profiles 目录生成一份最简可用的 Profile 模板,不起 Spring.
 *
 * @author OryxOS Contributors
 */
@Command(mixinStandardHelpOptions = true, name = "create", description = "新建一个 Profile 模板")
public class ProfileCreateCommand implements Runnable {

  @Option(names = "--name", required = true, description = "Profile 名")
  String name;

  @Spec private CommandSpec commandSpec;

  @Override
  public void run() {
    PrintWriter out = commandSpec.commandLine().getOut();
    if (!CliFiles.requireWorkspace(out)) {
      return;
    }
    Path file = CliFiles.workspace().resolve("profiles").resolve(name + ".yaml");
    if (Files.exists(file)) {
      out.println("Profile 已存在: " + name);
      return;
    }
    String now = Instant.now().toString();
    String template =
        """
        name: __NAME__
        description: TODO 描述这个 Agent
        identity:
          agent_name: __NAME__
          prompt: You are a helpful enterprise assistant.
        provider:
          name: deepseek
          model: deepseek-chat
          temperature: 0.7
        tools:
          - http_get
          - read_file
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
            .replace("__NAME__", name)
            .replace("__NOW__", now);
    try {
      Files.writeString(file, template);
      out.println("已创建 Profile: " + name + "(profiles/" + name + ".yaml,请按需编辑)");
    } catch (IOException | RuntimeException e) {
      out.println("创建 Profile 失败: " + name + " (" + e.getMessage() + ")");
    }
  }
}
