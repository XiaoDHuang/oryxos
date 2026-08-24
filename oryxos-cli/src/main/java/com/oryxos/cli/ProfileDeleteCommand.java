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
 * 轻命令 {@code profile delete}:删除指定 Profile 的 YAML 文件,不起 Spring.
 *
 * @author OryxOS Contributors
 */
@Command(mixinStandardHelpOptions = true, name = "delete", description = "删除指定 Profile")
public class ProfileDeleteCommand implements Runnable {

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
      Files.delete(file);
      out.println("已删除 Profile: " + name);
    } catch (IOException | RuntimeException e) {
      out.println("删除 Profile 失败: " + name + " (" + e.getMessage() + ")");
    }
  }
}
