package com.oryxos.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * 轻命令 {@code tool list}:列出指定 Profile 声明启用的工具(配置面视角),不起 Spring. 运行时 注册表(ToolRegistry)由 Tool 课(20
 * 节)交付,届时再由重命令/端点给出运行时视角。
 *
 * @author OryxOS Contributors
 */
@Command(name = "list", description = "列出指定 Profile 声明的工具")
public class ToolListCommand implements Runnable {

  @Option(names = "--profile", defaultValue = "default", description = "Profile 名")
  String profile;

  @Spec private CommandSpec commandSpec;

  @Override
  public void run() {
    PrintWriter out = commandSpec.commandLine().getOut();
    if (!CliFiles.requireWorkspace(out)) {
      return;
    }
    Path file = CliFiles.workspace().resolve("profiles").resolve(profile + ".yaml");
    if (!Files.isRegularFile(file)) {
      out.println("Profile 不存在: " + profile);
      return;
    }
    try {
      Map<String, Object> map = new Yaml().load(Files.readString(file));
      Object tools = map == null ? null : map.get("tools");
      if (tools instanceof List<?> list && !list.isEmpty()) {
        list.forEach(tool -> out.println(String.valueOf(tool)));
      } else {
        out.println("(该 Profile 未声明工具)");
      }
    } catch (IOException | RuntimeException e) {
      out.println("读取 Profile 失败: " + profile + " (" + e.getMessage() + ")");
    }
  }
}
