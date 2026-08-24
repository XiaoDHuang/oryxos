package com.oryxos.cli;

import picocli.CommandLine.Command;

/**
 * {@code tool} 父命令,挂载 list 子命令.
 *
 * @author OryxOS Contributors
 */
@Command(
    mixinStandardHelpOptions = true,
    name = "tool",
    description = "工具查看:list",
    subcommands = {ToolListCommand.class})
public class ToolCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }
}
