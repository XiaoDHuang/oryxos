package com.oryxos.cli;

import picocli.CommandLine.Command;

/**
 * {@code provider} 父命令,挂载 list 子命令.
 *
 * @author OryxOS Contributors
 */
@Command(
    mixinStandardHelpOptions = true,
    name = "provider",
    description = "LLM provider 查看:list",
    subcommands = {ProviderListCommand.class})
public class ProviderCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }
}
