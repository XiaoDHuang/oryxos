package com.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.oryxos.core.profile.Profile;
import com.oryxos.core.profile.ProfileRegistry;
import com.oryxos.core.profile.ScheduleConfig;
import com.oryxos.core.react.LlmGateway;
import com.oryxos.core.schedule.AgentScheduler;
import com.oryxos.core.schedule.ScheduledTaskStore;
import com.oryxos.storage.audit.JpaToolInvocationAudit;
import com.oryxos.storage.session.JpaSessionManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 调度稳定性:真实 cron 到点与固定时区、失效 MCP 不阻断整机、受控截止与迟到阻塞夹具、60 秒公共配置值守. 真实线程池只在"真实触发"用例启用,截止用例用捕获调度器手动触发
 * watchdog。
 *
 * @author OryxOS Contributors
 */
class SchedulerStabilityTest {

  @TempDir Path directory;

  @org.junit.jupiter.api.BeforeEach
  void prepareWorkspace() throws IOException {
    Files.writeString(directory.resolve("AGENTS.md"), "仅使用本次测试的合成数据。\n");
    Files.createDirectories(directory.resolve("memory"));
    Files.writeString(
        directory.resolve("memory/MEMORY.md"), "# Long-term memory\n\n## 核心记忆\n\n## 归档记忆\n");
  }

  @org.junit.jupiter.api.BeforeAll
  static void installBrokenMcpEntry() throws IOException {
    // 失效 MCP 只在本测试类期间存在于共享 CWD 夹具,立即退出的进程让握手快速失败
    Path config = Path.of(".oryxos").resolve("mcp_servers.yaml");
    Files.createDirectories(config.getParent());
    brokenMcpBackup = Files.isRegularFile(config) ? Files.readString(config) : null;
    Files.writeString(
        config,
        """
        servers:
          - name: broken-mcp
            transport: stdio
            command: ["java", "-version"]
        """);
  }

  @org.junit.jupiter.api.AfterAll
  static void restoreMcpEntry() throws IOException {
    Path config = Path.of(".oryxos").resolve("mcp_servers.yaml");
    if (brokenMcpBackup == null) {
      Files.deleteIfExists(config);
    } else {
      Files.writeString(config, brokenMcpBackup);
    }
  }

  private static String brokenMcpBackup;

  @Test
  @DisplayName("真实cron每秒触发_行带固定时区快照_候选推进_失效MCP不阻断整机")
  void realCronFiresWithZoneAndBrokenMcpDoesNotBlock() {
    runner(true)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              ScheduledTaskStore store = context.getBean(ScheduledTaskStore.class);
              assertThat(
                      SchedulerFlowFixture.pollUntil(
                          () -> store.findTask("stability-tick").orElseThrow().runCount() >= 1,
                          Duration.ofSeconds(5)))
                  .as("每秒规则应在 5 秒内真实到点")
                  .isTrue();
              var task = store.findTask("stability-tick").orElseThrow();
              assertThat(task.zone()).isEqualTo("Asia/Shanghai");
              assertThat(task.nextRunAt()).isNotNull();
              assertThat(task.lastStatus()).isIn("success", "running");
              // 失效 MCP 存在时整机仍完成调度(启动未崩 + 任务已真实执行)
              assertThat(store.countExecutions("stability-tick")).isGreaterThanOrEqualTo(1);
            });
  }

  @Test
  @DisplayName("受控截止_超时终态唯一且迟到返回不覆盖_锁最终释放")
  void controlledDeadline_timeoutTerminalWinsAndLockFreed() {
    runner(false)
        .run(
            context -> {
              AgentScheduler scheduler = context.getBean(AgentScheduler.class);
              ScheduledTaskStore store = context.getBean(ScheduledTaskStore.class);
              StabilityScenario scenario = context.getBean(StabilityScenario.class);
              CapturingScheduler capturing =
                  context.getBean("capturingTaskScheduler", CapturingScheduler.class);

              Thread caller =
                  new Thread(() -> scenario.outcome.set(scheduler.runNow("stability-slow")));
              caller.start();
              try {
                // 全量构建高负载下 worker 起跑可能超过 5 秒,给 15 秒余量(非超时语义,只是起跑等待)
                assertThat(scenario.slowStarted.await(15, TimeUnit.SECONDS)).isTrue();
                // 手动到点:统一触发当前捕获到的全部 watchdog(心跳的因其已完成而静默,慢任务的落 timeout)
                for (Runnable watchdog : capturing.drainWatchdogs()) {
                  watchdog.run();
                }
                caller.join(5000);

                var view = scenario.outcome.get();
                assertThat(view).isNotNull();
                assertThat(view.success()).isFalse();
                assertThat(view.errorMessage()).isEqualTo("执行超时");
                assertThat(store.findTask("stability-slow").orElseThrow().lastStatus())
                    .isEqualTo("timeout");

                // 放行迟到 worker:第一次执行的 timeout 终态不被迟到收尾覆盖;
                // 锁释放以"同任务可再次执行成功"证明(比读锁内部状态更强的行为断言)
                scenario.slowRelease.countDown();
                assertThat(
                        SchedulerFlowFixture.pollUntil(
                            () -> {
                              try {
                                return Boolean.TRUE.equals(
                                    scheduler.runNow("stability-slow").success());
                              } catch (java.util.concurrent.RejectedExecutionException busy) {
                                return false;
                              }
                            },
                            Duration.ofSeconds(5)))
                    .as("worker 真实退出后同任务必须可再次执行")
                    .isTrue();
                long firstExecutionId = scenario.outcome.get().executionId();
                var firstRow =
                    store.listExecutions("stability-slow", 0, 10).stream()
                        .filter(row -> row.executionId() == firstExecutionId)
                        .findFirst()
                        .orElseThrow();
                assertThat(firstRow.success()).isFalse();
                assertThat(firstRow.errorMessage()).isEqualTo("执行超时");
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
              }
            });
  }

  @Test
  @DisplayName("单次执行公共截止为60秒_配置值守点")
  void defaultExecutionDeadlineIsSixtySeconds() {
    runner(false)
        .run(
            context -> {
              AgentScheduler scheduler = context.getBean(AgentScheduler.class);
              assertThat(ReflectionTestUtils.getField(scheduler, "executionTimeoutMs"))
                  .isEqualTo(60000L);
            });
  }

  private ApplicationContextRunner runner(boolean realScheduler) {
    var base =
        new ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    HibernateJpaAutoConfiguration.class,
                    TransactionAutoConfiguration.class,
                    SqlInitializationAutoConfiguration.class,
                    autoConfig("com.oryxos.memory.MemoryConfiguration"),
                    autoConfig("com.oryxos.tool.ToolConfiguration")))
            .withUserConfiguration(StabilityFixture.class)
            .withBean(StabilityScenario.class, StabilityScenario::new)
            .withBean(Path.class, () -> directory)
            .withBean(
                org.springframework.web.client.RestClient.Builder.class,
                org.springframework.web.client.RestClient::builder)
            .withPropertyValues(
                "oryxos.root=" + directory,
                "oryxos.scheduler.enabled=true",
                "memory.backend=markdown",
                "spring.datasource.url=jdbc:sqlite:"
                    + directory.resolve("oryxos-stability.db")
                    + "?busy_timeout=5000",
                "spring.datasource.driver-class-name=org.sqlite.JDBC",
                "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:db/schema.sql",
                "file.allowed-paths=" + directory,
                "shell.allowed-commands=",
                "http.allowed-domains=");
    if (realScheduler) {
      return base;
    }
    return base.withUserConfiguration(CapturingSchedulerConfiguration.class);
  }

  private static Class<?> autoConfig(String className) {
    try {
      return Class.forName(className);
    } catch (ClassNotFoundException exception) {
      throw new AssertionError("自动配置必须存在: " + className, exception);
    }
  }

  /** 稳定性脚本模型:stability-slow 阻塞到放行,其余直接答. */
  static final class StabilityScenario {

    final CountDownLatch slowStarted = new CountDownLatch(1);

    final CountDownLatch slowRelease = new CountDownLatch(1);

    final AtomicReference<com.oryxos.core.schedule.TaskExecutionView> outcome =
        new AtomicReference<>();
  }

  /** 捕获 Instant 定时的 watchdog 回调;按到达顺序留存,测试手动统一触发(心跳任务也会注册,不能只取最后一个). */
  static final class CapturingScheduler extends ThreadPoolTaskScheduler {

    private final java.util.List<Runnable> pending =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
      pending.add(task);
      // 不真正安装:watchdog 由测试手动触发,避免 60 秒真实等待
      return mockFuture();
    }

    java.util.List<Runnable> drainWatchdogs() {
      java.util.List<Runnable> drained = new java.util.ArrayList<>(pending);
      pending.clear();
      return drained;
    }

    private static ScheduledFuture<?> mockFuture() {
      return org.mockito.Mockito.mock(ScheduledFuture.class);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class CapturingSchedulerConfiguration {

    /** 注入主候选:装配条件评估顺序不保证既有 resident Bean 退让,用 @Primary 而非条件互斥. */
    @Bean
    @org.springframework.context.annotation.Primary
    CapturingScheduler capturingTaskScheduler() {
      return new CapturingScheduler();
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EntityScan("com.oryxos.storage")
  @EnableJpaRepositories("com.oryxos.storage")
  @Import({
    JpaSessionManager.class,
    JpaToolInvocationAudit.class,
    com.oryxos.tool.notify.WebhookNotifyAdapter.class
  })
  static class StabilityFixture extends com.oryxos.core.config.CoreEngineConfiguration {

    private final Path workspace;

    private final StabilityScenario scenario;

    StabilityFixture(Path workspace, StabilityScenario scenario) {
      this.workspace = workspace;
      this.scenario = scenario;
    }

    @Override
    @Bean
    public com.oryxos.core.context.ContextLoader contextLoader() {
      return new com.oryxos.core.context.ContextLoader(workspace);
    }

    @Override
    @Bean
    public ProfileRegistry profileRegistry(ObjectProvider<java.util.Set<String>> providers) {
      return new ProfileRegistry(
          List.of(
              // 心跳与慢任务分属两个 Profile:同 Profile 执行互斥,否则每秒心跳会撞慢任务
              new Profile(
                  "stability",
                  null,
                  null,
                  new Profile.Provider("fake", "fake-model", 0.0, null),
                  List.of(),
                  null,
                  null,
                  null,
                  null,
                  List.of(
                      new ScheduleConfig("stability-slow", "0 0 0 29 2 *", "Asia/Shanghai", "慢任务")),
                  null,
                  new Profile.Settings(5, 20),
                  null,
                  null),
              new Profile(
                  "stability-tick",
                  null,
                  null,
                  new Profile.Provider("fake", "fake-model", 0.0, null),
                  List.of(),
                  null,
                  null,
                  null,
                  null,
                  List.of(
                      new ScheduleConfig("stability-tick", "* * * * * *", "Asia/Shanghai", "巡检")),
                  null,
                  new Profile.Settings(5, 20),
                  null,
                  null)));
    }

    @Bean
    LlmGateway stabilityGateway() {
      return (sessionId, profile, prompt) -> {
        if ("慢任务".equals(prompt.messages().getLast().getText())) {
          scenario.slowStarted.countDown();
          try {
            scenario.slowRelease.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage("巡检正常"))));
      };
    }

    @Bean
    ScheduledTaskStore stabilityStore(
        com.oryxos.storage.schedule.ScheduledTaskRepository tasks,
        com.oryxos.storage.schedule.TaskExecutionRepository executions,
        org.springframework.transaction.PlatformTransactionManager transactions) {
      return new com.oryxos.storage.schedule.JpaScheduledTaskStore(tasks, executions, transactions);
    }
  }
}
