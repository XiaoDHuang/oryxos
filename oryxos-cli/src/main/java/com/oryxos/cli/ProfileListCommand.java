package com.oryxos.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * 轻命令 {@code profile list}:列出 profiles 目录里的全部 Profile,不起 Spring.
 *
 * @author OryxOS Contributors
 */
@Command(name = "list", description = "列出全部 Profile")
public class ProfileListCommand implements Runnable {

  @Spec private CommandSpec commandSpec;

  @Override
  public void run() {
    PrintWriter out = commandSpec.commandLine().getOut();
    if (!CliFiles.requireWorkspace(out)) {
      return;
    }
    Path profiles = CliFiles.workspace().resolve("profiles");
    if (!Files.isDirectory(profiles)) {
      out.println("(无 Profile)");
      return;
    }
    try (Stream<Path> stream = Files.list(profiles)) {
      stream.filter(CliFiles::isYaml).sorted().forEach(path -> printProfileName(out, path));
    } catch (IOException | RuntimeException e) {
      out.println("读取 profiles 目录失败: " + e.getMessage());
    }
  }

  private static void printProfileName(PrintWriter out, Path path) {
    Path fileName = path.getFileName();
    if (fileName == null) {
      return;
    }
    out.println(fileName.toString().replaceFirst("\\.(yaml|yml)$", ""));
  }
}
