package com.oryxos.cli;

import java.io.PrintWriter;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * OryxOS CLI 根入口(fat JAR 的 {@code Start-Class}). 轻量命令(init / version)不启动 Spring;LLM 命令(chat / serve
 * / gateway)后续才会启动 Spring。
 *
 * @author OryxOS Contributors
 */
@Command(
    name = "oryxos",
    description = "OryxOS —— 企业级 Agent OS",
    mixinStandardHelpOptions = true,
    versionProvider = OryxOsCli.OryxOsVersionProvider.class,
    subcommands = {InitCommand.class, VersionCommand.class})
public class OryxOsCli implements Runnable {

  /** 在打包注入版本号之前,与 Maven {@code project.version} 保持同步. */
  public static final String VERSION = "1.0.0-SNAPSHOT";

  @Spec private CommandSpec commandSpec;

  /** 启动 Picocli 命令分发器. */
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
    out.println("企业级 Agent OS · Java 21 + Spring Boot 3.x");
    out.println();
  }

  /**
   * Picocli 的 {@code -V/--version} provider.
   *
   * @author OryxOS Contributors
   */
  public static final class OryxOsVersionProvider implements IVersionProvider {
    @Override
    public String[] getVersion() {
      return new String[] {"OryxOS " + VERSION, "企业级 Agent OS · Java 21 + Spring Boot 3.x"};
    }
  }
}
