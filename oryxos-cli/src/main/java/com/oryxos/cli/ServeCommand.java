package com.oryxos.cli;

import java.io.PrintWriter;
import java.util.concurrent.CountDownLatch;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * 重命令 {@code serve}:启动带 Web 容器的运行时对外提供 REST API(业务端点在 Web 课(26 节) 交付,本节只起运行时). 启动后常驻,直到进程被终止。
 *
 * @author OryxOS Contributors
 */
@Command(name = "serve", description = "启动 Web Service(REST API 服务,常驻)")
public class ServeCommand implements Runnable {

  @Option(names = "--port", defaultValue = "8080", description = "监听端口")
  int port;

  @Spec private CommandSpec commandSpec;

  @Override
  public void run() {
    PrintWriter out = commandSpec.commandLine().getOut();
    out.println("Web Service 启动中,端口 " + port + "(REST 业务端点在后续课程交付,当前可用 /actuator/*)...");
    SpringRuntime.start(true, "--server.port=" + port);
    try {
      // 常驻:命令一旦返回,OryxOsCli.main 就会 System.exit 杀掉容器——主线程在此驻留,
      // 收尾交给 Spring 的 shutdown hook(SIGINT/SIGTERM 时优雅关容器)。
      new CountDownLatch(1).await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      out.println("Web Service 已停止");
    }
  }
}
