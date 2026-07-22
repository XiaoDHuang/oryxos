package com.oryxos.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;

/**
 * OryxOS CLI root entry (fat JAR {@code Start-Class}).
 * Lightweight commands (init / version) do not start Spring;
 * LLM commands (chat / serve / gateway) will start Spring later.
 */
@Command(
        name = "oryxos",
        description = "OryxOS — enterprise Agent OS",
        mixinStandardHelpOptions = true,
        versionProvider = OryxOsCli.OryxOsVersionProvider.class,
        subcommands = {
                InitCommand.class,
                VersionCommand.class
        })
public class OryxOsCli implements Runnable {

    /** Keep in sync with Maven {@code project.version} until packaging injects it. */
    public static final String VERSION = "1.0.0-SNAPSHOT";

    public static void main(String[] args) {
        int code = new CommandLine(new OryxOsCli()).execute(args);
        System.exit(code);
    }

    @Override
    public void run() {
        printBanner(System.out);
        new CommandLine(this).usage(System.out);
    }

    static void printBanner(java.io.PrintStream out) {
        out.println("OryxOS " + VERSION);
        out.println("Enterprise Agent OS · Java 21 + Spring Boot 3.x");
        out.println();
    }

    /** Picocli {@code -V/--version} provider. */
    public static final class OryxOsVersionProvider implements IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {
                    "OryxOS " + VERSION,
                    "Enterprise Agent OS · Java 21 + Spring Boot 3.x"
            };
        }
    }
}
