package com.oryxos.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * 轻命令 {@code profile list}:列出全部已定义的 Agent(29 节起两来源同规矩 —— profiles 目录的手写 YAML 与 agents 目录的 Agent
 * 目录,同名只列一次),不起 Spring.
 *
 * @author OryxOS Contributors
 */
@Command(mixinStandardHelpOptions = true, name = "list", description = "列出全部 Profile")
public class ProfileListCommand implements Runnable {

  @Spec private CommandSpec commandSpec;

  @Override
  public void run() {
    PrintWriter out = commandSpec.commandLine().getOut();
    if (!CliFiles.requireWorkspace(out)) {
      return;
    }
    TreeSet<String> names = new TreeSet<>();
    Path profiles = CliFiles.workspace().resolve("profiles");
    if (Files.isDirectory(profiles)) {
      try (Stream<Path> stream = Files.list(profiles)) {
        stream.filter(CliFiles::isYaml).forEach(path -> names.add(stripYamlExtension(path)));
      } catch (IOException | RuntimeException e) {
        out.println("读取 profiles 目录失败: " + e.getMessage());
      }
    }
    // 29 节:一个含 AGENT.md 的子目录 = 一个 Agent,与手写 YAML 同规矩列出
    Path agents = CliFiles.workspace().resolve("agents");
    if (Files.isDirectory(agents)) {
      try (Stream<Path> stream = Files.list(agents)) {
        stream
            .filter(Files::isDirectory)
            .filter(dir -> Files.isRegularFile(dir.resolve("AGENT.md")))
            .forEach(dir -> addAgentName(names, dir));
      } catch (IOException | RuntimeException e) {
        out.println("读取 agents 目录失败: " + e.getMessage());
      }
    }
    if (names.isEmpty()) {
      out.println("(无 Profile)");
      return;
    }
    names.forEach(out::println);
  }

  private static void addAgentName(TreeSet<String> names, Path dir) {
    Path fileName = dir.getFileName();
    if (fileName != null) {
      names.add(fileName.toString());
    }
  }

  private static String stripYamlExtension(Path path) {
    Path fileName = path.getFileName();
    if (fileName == null) {
      return "";
    }
    return fileName.toString().replaceFirst("\\.(yaml|yml)$", "");
  }
}
