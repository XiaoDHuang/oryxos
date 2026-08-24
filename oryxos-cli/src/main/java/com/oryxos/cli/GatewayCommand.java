package com.oryxos.cli;

import java.io.PrintWriter;
import java.util.concurrent.CountDownLatch;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * 重命令 {@code gateway}:守护进程骨架. 核心阶段只有 CLI 一个通道,多通道挂靠在后续课程接入; 本节起非 Web 的 Spring 运行时并驻留,验证守护形态与共享存储。
 *
 * @author OryxOS Contributors
 */
@Command(name = "gateway", description = "启动多渠道守护进程(核心阶段仅 CLI 通道)")
public class GatewayCommand implements Runnable {

  @Spec private CommandSpec commandSpec;

  @Override
  public void run() {
    PrintWriter out = commandSpec.commandLine().getOut();
    out.println("gateway 守护进程已启动(核心阶段仅 CLI 通道,IM 通道在扩展阶段接入)。Ctrl+C 退出。");
    SpringRuntime.start(false);
    try {
      // 守护:非 Web 模式没有容器线程常驻,主线程在此驻留直到进程被终止。
      new CountDownLatch(1).await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      out.println("gateway 已停止");
    }
  }
}
