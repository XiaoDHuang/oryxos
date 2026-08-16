package com.oryxos.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Print OryxOS version (no Spring context).
 *
 * @author OryxOS Contributors
 */
@Command(name = "version", description = "Print OryxOS version")
public class VersionCommand implements Callable<Integer> {

  @Spec private CommandSpec commandSpec;

  @Override
  public Integer call() {
    OryxOsCli.printBanner(commandSpec.commandLine().getOut());
    return 0;
  }
}
