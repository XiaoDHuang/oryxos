package com.oryxos.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * 打印 OryxOS 版本(无 Spring 上下文).
 *
 * @author OryxOS Contributors
 */
@Command(name = "version", description = "打印 OryxOS 版本")
public class VersionCommand implements Callable<Integer> {

  @Spec private CommandSpec commandSpec;

  @Override
  public Integer call() {
    OryxOsCli.printBanner(commandSpec.commandLine().getOut());
    return 0;
  }
}
