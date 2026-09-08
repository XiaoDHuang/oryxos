package com.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oryxos.core.context.ContextLoader;
import com.oryxos.core.profile.Profile;
import com.oryxos.core.profile.ProfileRegistry;
import com.oryxos.core.react.LlmGateway;
import com.oryxos.core.schedule.AgentScheduler;
import com.oryxos.core.schedule.ScheduledTaskStore;
import com.oryxos.storage.audit.JpaToolInvocationAudit;
import com.oryxos.storage.session.JpaSessionManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 多 Agent 隔离:两个差异 Profile 共享同一运行时,捕获模型实际看到的 Tool schema;真实引擎/Session/SQLite; A 失败不拖垮 B;同 Profile
 * 的两条规则共享会话,必须互斥.
 *
 * @author OryxOS Contributors
 */
class MultiAgentIsolationTest {

  @TempDir Path directory;

  @Test
  @DisplayName("工具与会话按Profile隔离_A失败时B成功")
  void toolsAndSessionsIsolated_failureDoesNotSpread() {
    runner()
        .run(
            context -> {
              AgentScheduler scheduler = context.getBean(AgentScheduler.class);
              ScheduledTaskStore store = context.getBean(ScheduledTaskStore.class);
              IsoScenario scenario = context.getBean(IsoScenario.class);

              var viewA = scheduler.runNow("iso-task-a");
              var viewB = scheduler.runNow("iso-task-b");

              assertThat(viewA.success()).isFalse();
              assertThat(viewA.errorMessage()).isEqualTo("模型调用失败");
              assertThat(viewB.success()).isTrue();
              assertThat(store.findTask("iso-task-a").orElseThrow().lastStatus())
                  .isEqualTo("failed");
              assertThat(store.findTask("iso-task-b").orElseThrow().lastStatus())
                  .isEqualTo("success");

              // 工具隔离:模型按 Profile 只看到自己的工具集
              assertThat(scenario.toolsSeen.get("iso-a")).containsExactly(List.of("save_memory"));
              assertThat(scenario.toolsSeen.get("iso-b")).containsExactly(List.of());

              // 会话隔离:各自固定三元组,内容互不串
              var jdbc = new JdbcTemplate(context.getBean(javax.sql.DataSource.class));
              var sessionIds =
                  jdbc.queryForList(
                      "SELECT session_id FROM sessions ORDER BY session_id", String.class);
              assertThat(sessionIds)
                  .containsExactly("scheduler:scheduler:iso-a", "scheduler:scheduler:iso-b");
              String historyA =
                  jdbc.queryForObject(
                      "SELECT messages_json FROM sessions"
                          + " WHERE session_id='scheduler:scheduler:iso-a'",
                      String.class);
              String historyB =
                  jdbc.queryForObject(
                      "SELECT messages_json FROM sessions"
                          + " WHERE session_id='scheduler:scheduler:iso-b'",
                      String.class);
              // 引擎失败不保证保存未完成会话(plan §3):A 的历史不落 B 的内容,B 的完成不串入 A
              assertThat(historyA).doesNotContain("iso-b").doesNotContain("iso-b完成");
              assertThat(historyB).contains("iso-b完成").doesNotContain("任务iso-task-a");
            });
  }

  @Test
  @DisplayName("同Profile两条规则共享会话_并发互斥零双跑")
  void sameProfileRules_mutuallyExclude() throws Exception {
    runner()
        .run(
            context -> {
              AgentScheduler scheduler = context.getBean(AgentScheduler.class);
              ScheduledTaskStore store = context.getBean(ScheduledTaskStore.class);
              IsoScenario scenario = context.getBean(IsoScenario.class);
              scenario.blockC = true;

              // c1 阻塞期间,c2 立即执行必须被拒且零历史
              Thread first = new Thread(() -> scheduler.runNow("iso-task-c1"));
              first.start();
              try {
                assertThat(scenario.startedLatch.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> scheduler.runNow("iso-task-c2"))
                    .isInstanceOf(RejectedExecutionException.class)
                    .hasMessageContaining("同 Profile");
                assertThat(store.countExecutions("iso-task-c2")).isZero();
              } finally {
                scenario.releaseLatch.countDown();
                try {
                  first.join(5000);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
              assertThat(store.findTask("iso-task-c1").orElseThrow().lastStatus())
                  .isEqualTo("success");
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
        .withUserConfiguration(IsoFixture.class)
        .withBean(IsoScenario.class, IsoScenario::new)
        .withBean(Path.class, () -> directory)
        .withBean(
            org.springframework.web.client.RestClient.Builder.class,
            org.springframework.web.client.RestClient::builder)
        .withPropertyValues(
            "oryxos.root=" + directory,
            "oryxos.scheduler.enabled=true",
            "memory.backend=markdown",
            "spring.datasource.url=jdbc:sqlite:"
                + directory.resolve("oryxos-iso.db")
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

  /** 按 Profile 分叉的脚本模型:记录每次调用实际看到的工具名,可阻塞/可失败. */
  static final class IsoScenario {
    final Map<String, List<List<String>>> toolsSeen = new ConcurrentHashMap<>();
    volatile boolean blockC;
    final CountDownLatch startedLatch = new CountDownLatch(1);
    final CountDownLatch releaseLatch = new CountDownLatch(1);
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EntityScan("com.oryxos.storage")
  @EnableJpaRepositories("com.oryxos.storage")
  @Import({
    JpaSessionManager.class,
    JpaToolInvocationAudit.class,
    com.oryxos.tool.notify.WebhookNotifyAdapter.class
  })
  static class IsoFixture extends com.oryxos.core.config.CoreEngineConfiguration {

    private final Path workspace;

    IsoFixture(Path workspace, IsoScenario scenario) {
      this.workspace = workspace;
      this.scenario = scenario;
    }

    private final IsoScenario scenario;

    @Override
    @Bean
    public ContextLoader contextLoader() {
      return new ContextLoader(workspace);
    }

    @Override
    @Bean
    public ProfileRegistry profileRegistry(ObjectProvider<java.util.Set<String>> providers) {
      return new ProfileRegistry(
          List.of(
              profile("iso-a", List.of("save_memory"), "iso-task-a"),
              profile("iso-b", List.of(), "iso-task-b"),
              profileWithTwoRules("iso-c")));
    }

    @Bean
    LlmGateway isoGateway() {
      return (sessionId, profile, prompt) -> {
        List<String> tools = prompt.availableTools().stream().map(tool -> tool.getName()).toList();
        scenario
            .toolsSeen
            .computeIfAbsent(profile.name(), name -> new CopyOnWriteArrayList<>())
            .add(tools);
        if ("iso-a".equals(profile.name())) {
          // A 的模型故障必须留在 A 自己:任务 failed,不影响 B
          throw new RuntimeException("A 模型故障");
        }
        if ("iso-c".equals(profile.name()) && scenario.blockC) {
          scenario.startedLatch.countDown();
          try {
            scenario.releaseLatch.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
        return new ChatResponse(
            List.of(new Generation(new AssistantMessage(profile.name() + "完成"))));
      };
    }

    @Bean
    ScheduledTaskStore scheduledTaskStore(
        com.oryxos.storage.schedule.ScheduledTaskRepository tasks,
        com.oryxos.storage.schedule.TaskExecutionRepository executions,
        org.springframework.transaction.PlatformTransactionManager transactions) {
      return new com.oryxos.storage.schedule.JpaScheduledTaskStore(tasks, executions, transactions);
    }

    private static Profile profile(String name, List<String> tools, String taskId) {
      return new Profile(
          name,
          null,
          null,
          new Profile.Provider("fake", "fake-model", 0.0, null),
          tools,
          null,
          null,
          null,
          null,
          List.of(
              new com.oryxos.core.profile.ScheduleConfig(
                  taskId, "0 0 0 29 2 *", "Asia/Shanghai", "任务" + taskId)),
          null,
          new Profile.Settings(5, 20),
          null,
          null);
    }

    private static Profile profileWithTwoRules(String name) {
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
          List.of(
              new com.oryxos.core.profile.ScheduleConfig(
                  "iso-task-c1", "0 0 0 29 2 *", "Asia/Shanghai", "C1"),
              new com.oryxos.core.profile.ScheduleConfig(
                  "iso-task-c2", "0 0 0 29 2 *", "Asia/Shanghai", "C2")),
          null,
          new Profile.Settings(5, 20),
          null,
          null);
    }
  }
}
