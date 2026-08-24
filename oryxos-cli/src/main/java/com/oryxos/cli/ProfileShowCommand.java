package com.oryxos.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * 轻命令 {@code profile show}:打印指定 Profile 的 YAML 原文,不起 Spring.
 *
 * @author OryxOS Contributors
 */
@Command(mixinStandardHelpOptions = true, name = "show", description = "查看指定 Profile 的配置原文")
public class ProfileShowCommand implements Runnable {

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
    if (!Files.isRegularFile(file)) {
      out.println("Profile 不存在: " + name);
      return;
    }
    try {
      out.println(Files.readString(file));
    } catch (IOException | RuntimeException e) {
      out.println("读取 Profile 失败: " + name + " (" + e.getMessage() + ")");
    }
  }
}
