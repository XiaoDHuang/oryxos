package com.oryxos.cli;

import picocli.CommandLine.Command;

/**
 * {@code session} 父命令,挂载 list 子命令.
 *
 * @author OryxOS Contributors
 */
@Command(
    name = "session",
    description = "会话查看:list",
    subcommands = {SessionListCommand.class})
public class SessionCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }
}
