package com.oryxos.boot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/** 定时链路夹具:合成工作区(带两条定时规则的 Profile)与有界轮询;合成语料只进本测试目录. */
final class SchedulerFlowFixture {

  static final String FACT = "定时合成事实A";

  private SchedulerFlowFixture() {}

  static Path prepare(Path root) throws IOException {
    Files.createDirectories(root.resolve("profiles"));
    Files.createDirectories(root.resolve("memory"));
    Files.writeString(root.resolve("AGENTS.md"), "仅使用本次测试的合成数据。\n");
    Files.writeString(
        root.resolve("memory/MEMORY.md"), "# Long-term memory\n\n## 核心记忆\n\n## 归档记忆\n");
    Files.writeString(root.resolve("mcp_servers.yaml"), "servers: []\n");
    // task-a:闰年 2 月 29 日才到点,测试期间永不自动触发,专供手动 run/启停
    Files.writeString(
        root.resolve("profiles/sched.yaml"),
        """
        name: sched
        provider:
          name: mock
          model: mock-script
          temperature: 0
        tools: [save_memory, recall_memory]
        schedules:
          - id: task-a
            cron: "0 0 0 29 2 *"
            zone: Asia/Shanghai
            message: "记住：定时合成事实A"
        settings:
          max_iterations: 5
          max_history_turns: 20
        """);
    // task-b:每秒到点,专供"真实 cron 触发"与"停用后自动跳过"的证明
    Files.writeString(
        root.resolve("profiles/sched2.yaml"),
        """
        name: sched2
        provider:
          name: mock
          model: mock-script
          temperature: 0
        tools: [save_memory, recall_memory]
        schedules:
          - id: task-b
            cron: "* * * * * *"
            zone: Asia/Shanghai
            message: "巡检心跳"
        settings:
          max_iterations: 5
          max_history_turns: 20
        """);
    return root;
  }

  /** 有界轮询:条件在时限内达成返回 true,超时返回 false(调用方决定断言文案). */
  static boolean pollUntil(BooleanSupplier condition, Duration timeout) {
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    while (System.currentTimeMillis() < deadline) {
      if (condition.getAsBoolean()) {
        return true;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return condition.getAsBoolean();
  }
}
