package com.oryxos.cli;

import java.io.PrintWriter;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * OryxOS CLI root entry (fat JAR {@code Start-Class}). Lightweight commands (init / version) do not
 * start Spring; LLM commands (chat / serve / gateway) will start Spring later.
 *
 * @author OryxOS Contributors
 */
@Command(
    name = "oryxos",
    description = "OryxOS — enterprise Agent OS",
    mixinStandardHelpOptions = true,
    versionProvider = OryxOsCli.OryxOsVersionProvider.class,
    subcommands = {InitCommand.class, VersionCommand.class})
public class OryxOsCli implements Runnable {

  /** Keep in sync with Maven {@code project.version} until packaging injects it. */
  public static final String VERSION = "1.0.0-SNAPSHOT";

  @Spec private CommandSpec commandSpec;

  /** Starts the Picocli command dispatcher. */
  public static void main(String[] args) {
    int code = new CommandLine(new OryxOsCli()).execute(args);
    System.exit(code);
  }

  @Override
  public void run() {
    PrintWriter out = commandSpec.commandLine().getOut();
    printBanner(out);
    commandSpec.commandLine().usage(out);
  }

  static void printBanner(PrintWriter out) {
    out.println("OryxOS " + VERSION);
    out.println("Enterprise Agent OS · Java 21 + Spring Boot 3.x");
    out.println();
  }

  /**
   * Picocli {@code -V/--version} provider.
   *
   * @author OryxOS Contributors
   */
  public static final class OryxOsVersionProvider implements IVersionProvider {
    @Override
    public String[] getVersion() {
      return new String[] {"OryxOS " + VERSION, "Enterprise Agent OS · Java 21 + Spring Boot 3.x"};
    }
  }
}
