package com.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oryxos.core.profile.Profile;
import com.oryxos.core.profile.ProfileRegistry;
import com.oryxos.core.profile.ScheduleConfig;
import com.oryxos.core.react.LlmGateway;
import com.oryxos.core.schedule.AgentScheduler;
import com.oryxos.core.schedule.ScheduledTaskStore;
import com.oryxos.storage.audit.JpaToolInvocationAudit;
import com.oryxos.storage.session.JpaSessionManager;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 真实 60 秒超时集成场景(bean 级真实 watchdog,受控慢模型,不用真 key 等待故障): 504 由 Controller
 * 切片另行覆盖(ScheduleApiControllerTest);本类守"超时终态/取消后零新动作/ 锁保持到真实退出/后续健康任务可用",实测耗时记录进 acceptance.
 *
 * @author OryxOS Contributors
 */
@Tag("integration")
class SchedulerTimeoutIT {

  @TempDir Path directory;

  @Test
  @DisplayName("真实60秒截止_中断后零新动作_锁保持_健康任务可用_迟到不覆盖")
  void realSixtySecondDeadline() {
    runner()
        .run(
            context -> {
              AgentScheduler scheduler = context.getBean(AgentScheduler.class);
              ScheduledTaskStore store = context.getBean(ScheduledTaskStore.class);
              TimeoutScenario scenario = context.getBean(TimeoutScenario.class);
              assertThat(ReflectionTestUtils.getField(scheduler, "executionTimeoutMs"))
                  .isEqualTo(60000L);

              long started = System.currentTimeMillis();
              Thread caller = new Thread(() -> scenario.outcome.set(scheduler.runNow("task-slow")));
              caller.start();
              try {
                assertThat(scenario.slowStarted.await(10, TimeUnit.SECONDS)).isTrue();
                // 不响应中断的 worker 占用期间:同任务拒绝且零新增历史
                assertThatThrownBy(() -> scheduler.runNow("task-slow"))
                    .isInstanceOf(RejectedExecutionException.class);

                // 健康任务(另一 Profile)在慢任务阻塞期间照常可用
                assertThat(scheduler.runNow("task-healthy").success()).isTrue();

                // 等到真实 60 秒 watchdog 落下终态
                assertThat(
                        SchedulerFlowFixture.pollUntil(
                            () ->
                                "timeout"
                                    .equals(store.findTask("task-slow").orElseThrow().lastStatus()),
                            Duration.ofSeconds(80)))
                    .as("60 秒公共截止必须落下 timeout 终态")
                    .isTrue();
                long elapsed = System.currentTimeMillis() - started;
                // 实测耗时供验收记录(60s 截止 + 观察误差)
                assertThat(elapsed).isBetween(55000L, 80000L);
                System.out.println("[timeout-it] 实测截止耗时: " + elapsed + "ms");

                caller.join(5000);
                var view = scenario.outcome.get();
                assertThat(view).isNotNull();
                assertThat(view.success()).isFalse();
                assertThat(view.errorMessage()).isEqualTo("执行超时");
                // 中断后零新动作:模型只被调过一次,工具从未发起
                assertThat(scenario.modelCalls.get()).isEqualTo(1);
                assertThat(scenario.toolCalls.get()).isZero();

                // 放行失控 worker:迟到收尾不覆盖 timeout 终态
                scenario.slowRelease.countDown();
                long firstId = view.executionId();
                assertThat(
                        SchedulerFlowFixture.pollUntil(
                            () -> {
                              try {
                                return Boolean.TRUE.equals(scheduler.runNow("task-slow").success());
                              } catch (RejectedExecutionException busy) {
                                return false;
                              }
                            },
                            Duration.ofSeconds(10)))
                    .as("worker 真实退出后同任务可再次执行")
                    .isTrue();
                var firstRow =
                    store.listExecutions("task-slow", 0, 10).stream()
                        .filter(row -> row.executionId() == firstId)
                        .findFirst()
                        .orElseThrow();
                assertThat(firstRow.success()).isFalse();
                assertThat(firstRow.errorMessage()).isEqualTo("执行超时");
                assertThat(firstRow.durationMs()).isEqualTo(60000L);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scenario.slowRelease.countDown();
                throw new AssertionError(e);
              }
            });
  }

  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DataSourceAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class,
                TransactionAutoConfiguration.class,
                SqlInitializationAutoConfiguration.class,
                autoConfig("com.oryxos.memory.MemoryConfiguration"),
                autoConfig("com.oryxos.tool.ToolConfiguration")))
        .withUserConfiguration(TimeoutFixture.class)
        .withBean(TimeoutScenario.class, TimeoutScenario::new)
        .withBean(Path.class, () -> directory)
        .withBean(
            org.springframework.web.client.RestClient.Builder.class,
            org.springframework.web.client.RestClient::builder)
        .withPropertyValues(
            "oryxos.root=" + directory,
            "oryxos.scheduler.enabled=true",
            "memory.backend=markdown",
            "spring.datasource.url=jdbc:sqlite:"
                + directory.resolve("oryxos-timeout.db")
                + "?busy_timeout=5000",
            "spring.datasource.driver-class-name=org.sqlite.JDBC",
            "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:db/schema.sql",
            "file.allowed-paths=" + directory,
            "shell.allowed-commands=",
            "http.allowed-domains=");
  }

  private static Class<?> autoConfig(String className) {
    try {
      return Class.forName(className);
    } catch (ClassNotFoundException exception) {
      throw new AssertionError("自动配置必须存在: " + className, exception);
    }
  }

  /** 超时场景脚本:慢任务模型吞中断阻塞,健康任务直接答;计数证明截止后零新动作. */
  static final class TimeoutScenario {

    final CountDownLatch slowStarted = new CountDownLatch(1);

    final CountDownLatch slowRelease = new CountDownLatch(1);

    final AtomicInteger modelCalls = new AtomicInteger();

    final AtomicInteger toolCalls = new AtomicInteger();

    final AtomicReference<com.oryxos.core.schedule.TaskExecutionView> outcome =
        new AtomicReference<>();
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EntityScan("com.oryxos.storage")
  @EnableJpaRepositories("com.oryxos.storage")
  @Import({
    JpaSessionManager.class,
    JpaToolInvocationAudit.class,
    com.oryxos.tool.notify.WebhookNotifyAdapter.class
  })
  static class TimeoutFixture extends com.oryxos.core.config.CoreEngineConfiguration {

    private final Path workspace;

    private final TimeoutScenario scenario;

    TimeoutFixture(Path workspace, TimeoutScenario scenario) {
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
              profile("slowpoke", "task-slow", "慢任务"), profile("healthy", "task-healthy", "健康任务")));
    }

    private static Profile profile(String name, String taskId, String message) {
      return new Profile(
          name,
          null,
          null,
          new Profile.Provider("fake", "fake-model", 0.0, null),
          List.of(),
          null,
          null,
          null,
          null,
          List.of(new ScheduleConfig(taskId, "0 0 0 29 2 *", "Asia/Shanghai", message)),
          null,
          new Profile.Settings(5, 20),
          null,
          null);
    }

    @Bean
    LlmGateway timeoutGateway() {
      return (sessionId, profile, prompt) -> {
        boolean slow = "慢任务".equals(prompt.messages().getLast().getText());
        if (slow) {
          scenario.modelCalls.incrementAndGet();
          scenario.slowStarted.countDown();
          // 吞中断的失控模型:只认显式放行
          boolean released = false;
          while (!released) {
            try {
              released = scenario.slowRelease.await(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
              // 刻意吞掉中断(swallow interrupts),模拟失控对端
            }
          }
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage("完成"))));
      };
    }

    @Bean
    ScheduledTaskStore timeoutStore(
        com.oryxos.storage.schedule.ScheduledTaskRepository tasks,
        com.oryxos.storage.schedule.TaskExecutionRepository executions,
        org.springframework.transaction.PlatformTransactionManager transactions) {
      return new com.oryxos.storage.schedule.JpaScheduledTaskStore(tasks, executions, transactions);
    }
  }
}
