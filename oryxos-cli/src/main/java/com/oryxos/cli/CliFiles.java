package com.oryxos.cli;

import java.nio.file.Path;

/**
 * 轻命令共享的文件小工具(不进 Spring,无状态).
 *
 * @author OryxOS Contributors
 */
final class CliFiles {

  private CliFiles() {}

  /** 轻命令与 Spring 命令消费相同的系统属性,避免初始化和运行落到不同工作区. */
  static Path workspace() {
    return Path.of(System.getProperty("oryxos.root", ".oryxos"));
  }

  /** 是否为 YAML 文件. */
  static boolean isYaml(Path path) {
    Path fileName = path.getFileName();
    if (fileName == null) {
      return false;
    }
    String name = fileName.toString();
    return name.endsWith(".yaml") || name.endsWith(".yml");
  }

  /** 工作区未初始化时给出可操作错误;已初始化返回 true. */
  static boolean requireWorkspace(java.io.PrintWriter out) {
    if (!java.nio.file.Files.isDirectory(workspace())) {
      out.println("未找到 .oryxos 工作区 —— 请先运行 oryxos init");
      return false;
    }
    return true;
  }
}
