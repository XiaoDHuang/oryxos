package com.oryxos.storage.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oryxos.core.profile.ScheduleConfig;
import com.oryxos.core.schedule.ScheduledTaskView;
import com.oryxos.core.schedule.TaskExecutionView;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 定时任务 Store 的真实 SQLite 验证:旧四表原地升级、重复初始化幂等、独立主键与初始/空历史. 测试先行——Store 与实体未实现时本类编译即红。
 *
 * @author OryxOS Contributors
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(
    properties = {
      "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
      "spring.jpa.hibernate.ddl-auto=none"
    })
class JpaScheduledTaskStoreTest {

  /** 011 之前的老库只有这四张表(与旧 schema.sql 一致的快照,用于模拟原地升级). */
  private static final List<String> OLD_SCHEMA_STATEMENTS =
      List.of(
          "CREATE TABLE sessions (session_id TEXT PRIMARY KEY, profile_name TEXT NOT NULL,"
              + " channel TEXT, user_id TEXT, messages_json TEXT, context_state TEXT,"
              + " status TEXT NOT NULL, created_at TEXT, last_active_at TEXT, archived_at TEXT)",
          "CREATE TABLE tool_invocations (invocation_id TEXT PRIMARY KEY, session_id TEXT,"
              + " profile_name TEXT, tool_name TEXT NOT NULL, parameters TEXT,"
              + " status TEXT NOT NULL, result TEXT, error TEXT, success INTEGER NOT NULL,"
              + " error_message TEXT, started_at TEXT, completed_at TEXT, token_cost INTEGER)",
          "CREATE TABLE llm_calls (call_id TEXT PRIMARY KEY, session_id TEXT, provider TEXT,"
              + " model TEXT, prompt_tokens INTEGER, completion_tokens INTEGER,"
              + " total_tokens INTEGER, latency_ms INTEGER, status TEXT, success INTEGER NOT NULL,"
              + " error_message TEXT, started_at TEXT, completed_at TEXT)",
          "CREATE TABLE memory_entries (id INTEGER PRIMARY KEY AUTOINCREMENT,"
              + " scope VARCHAR(16) NOT NULL CHECK (scope IN ('CORE', 'ARCHIVAL')),"
              + " content TEXT NOT NULL, created_at TIMESTAMP NOT NULL)");

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EntityScan("com.oryxos.storage")
  @EnableJpaRepositories("com.oryxos.storage")
  static class TestConfig {

    /** 每个测试类一份全新 SQLite 库;表结构由真实脚本建. */
    @Bean
    DataSource dataSource() {
      return DataSourceBuilder.create()
          .driverClassName("org.sqlite.JDBC")
          .url("jdbc:sqlite:target/test-schedule-" + UUID.randomUUID() + ".db?busy_timeout=5000")
          .build();
    }
  }

  @Autowired private DataSource dataSource;

  @Autowired private ScheduledTaskRepository taskRepository;

  @Autowired private TaskExecutionRepository executionRepository;

  @Autowired private PlatformTransactionManager transactionManager;

  private JpaScheduledTaskStore store;

  @BeforeEach
  void setUp() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema.sql"));
    }
    store = new JpaScheduledTaskStore(taskRepository, executionRepository, transactionManager);
  }

  @Test
  @DisplayName("旧四表库原地升级_新两表出现且旧数据不动")
  void oldFourTables_upgradeInPlace() throws Exception {
    // 全程只用这一条普通连接(autoCommit=true,逐步提交):JdbcTemplate 会走 @DataJpaTest 的
    // 事务连接,与这条直连连接看到的库视图不一致,混用会读不到对方刚提交的表
    try (Connection connection = dataSource.getConnection()) {
      Statement statement = connection.createStatement();
      for (String table :
          new String[] {
            "task_executions",
            "scheduled_tasks",
            "memory_entries",
            "llm_calls",
            "tool_invocations",
            "sessions"
          }) {
        statement.execute("DROP TABLE IF EXISTS " + table);
      }
      for (String ddl : OLD_SCHEMA_STATEMENTS) {
        statement.execute(ddl);
      }
      statement.executeUpdate(
          "INSERT INTO sessions(session_id, profile_name, status)"
              + " VALUES('cli:u:default','default','active')");
      assertThat(tableNames(connection))
          .containsExactlyInAnyOrder("sessions", "tool_invocations", "llm_calls", "memory_entries");

      // 执行新全量脚本:升级必须是追加,不是重建
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema.sql"));

      assertThat(tableNames(connection))
          .contains(
              "sessions",
              "tool_invocations",
              "llm_calls",
              "memory_entries",
              "scheduled_tasks",
              "task_executions");
      try (var rs = statement.executeQuery("SELECT COUNT(*) FROM sessions")) {
        rs.next();
        assertThat(rs.getLong(1)).isEqualTo(1);
      }
      try (var rs =
          statement.executeQuery(
              "SELECT sql FROM sqlite_master WHERE name='idx_task_executions_task_started'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isNotBlank();
      }
    }
  }

  @Test
  @DisplayName("重复执行schema脚本_幂等且已有行保留")
  void schemaScript_idempotent() throws Exception {
    store.register(
        "default",
        new ScheduleConfig("task-1", "0 9 * * *", "Asia/Shanghai", "日报"),
        Instant.now().plusSeconds(3600));
    try (Connection connection = dataSource.getConnection()) {
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema.sql"));
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema.sql"));
    }
    assertThat(store.listTasks()).hasSize(1);
  }

  @Test
  @DisplayName("初始为空_列表空,未知任务查询按约定异常")
  void emptyStore_contract() {
    assertThat(store.listTasks()).isEmpty();
    assertThat(store.findTask("ghost")).isEmpty();
    assertThatThrownBy(() -> store.listExecutions("ghost", 0, 20))
        .isInstanceOf(NoSuchElementException.class);
    assertThatThrownBy(() -> store.countExecutions("ghost"))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  @DisplayName("登记后初始状态_enabled为true,历史为零")
  void register_initialState() {
    // 时间落库精度为 UTC epoch 毫秒,期望值按毫秒截断对齐契约
    Instant next =
        Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    ScheduledTaskView view =
        store.register(
            "default", new ScheduleConfig("task-1", "0 9 * * *", "Asia/Shanghai", "日报"), next);

    assertThat(view.enabled()).isTrue();
    assertThat(view.nextRunAt()).isEqualTo(next);
    assertThat(view.lastStatus()).isNull();
    assertThat(view.runCount()).isZero();
    assertThat(store.listExecutions("task-1", 0, 20)).isEmpty();
    assertThat(store.countExecutions("task-1")).isZero();
  }

  @Test
  @DisplayName("每次begin独立主键_计数各加一次")
  void begin_independentPrimaryKeys() {
    store.register(
        "default", new ScheduleConfig("task-1", "0 9 * * *", "Asia/Shanghai", "日报"), null);

    TaskExecutionView first = store.begin("task-1", "scheduler:scheduler:default", Instant.now());
    TaskExecutionView second = store.begin("task-1", "scheduler:scheduler:default", Instant.now());

    assertThat(first.executionId()).isNotEqualTo(second.executionId());
    assertThat(second.executionId()).isGreaterThan(first.executionId());
    assertThat(first.success()).isNull();
    assertThat(store.findTask("task-1").orElseThrow().runCount()).isEqualTo(2);
    assertThat(store.listExecutions("task-1", 0, 20)).hasSize(2);
  }

  @Test
  @DisplayName("同id换Profile_拒绝且不更新所属")
  void register_rejectsProfileTransfer() {
    store.register(
        "default", new ScheduleConfig("task-1", "0 9 * * *", "Asia/Shanghai", "日报"), null);

    assertThatThrownBy(
            () ->
                store.register(
                    "ops", new ScheduleConfig("task-1", "0 10 * * *", "Asia/Shanghai", "别的"), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("其他 Profile");
    assertThat(store.findTask("task-1").orElseThrow().profileName()).isEqualTo("default");
  }

  @Test
  @DisplayName("停用任务再登记_开关不被重置且候选保持NULL")
  void register_disabledTaskKeepsDisabledAndNullCandidate() {
    store.register(
        "default",
        new ScheduleConfig("task-1", "0 9 * * *", "Asia/Shanghai", "日报"),
        Instant.now().plusSeconds(3600));
    store.setEnabled("task-1", false, null);

    // 调度器按"启用"算出的候选时间也不能让停用任务复活
    ScheduledTaskView view =
        store.register(
            "default",
            new ScheduleConfig("task-1", "0 10 * * *", "Asia/Shanghai", "新文案"),
            Instant.now().plusSeconds(7200));

    assertThat(view.enabled()).isFalse();
    assertThat(view.nextRunAt()).isNull();
    assertThat(view.cron()).isEqualTo("0 10 * * *");
    assertThat(view.message()).isEqualTo("新文案");
  }

  @Test
  @DisplayName("finish回滚一致性_终态与任务状态同进退")
  void finish_terminalAndTaskStatusConsistent() {
    store.register(
        "default", new ScheduleConfig("task-1", "0 9 * * *", "Asia/Shanghai", "日报"), null);
    TaskExecutionView running = store.begin("task-1", "s-1", Instant.now());

    TaskExecutionView finished = store.finish(running.executionId(), false, "工具执行失败", 123L);

    assertThat(finished.success()).isFalse();
    assertThat(finished.errorMessage()).isEqualTo("工具执行失败");
    assertThat(finished.durationMs()).isEqualTo(123L);
    ScheduledTaskView task = store.findTask("task-1").orElseThrow();
    assertThat(task.lastStatus()).isEqualTo("failed");
    assertThat(task.runCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("超时终态_任务lastStatus为timeout")
  void finish_timeoutMapsToTimeoutStatus() {
    store.register(
        "default", new ScheduleConfig("task-1", "0 9 * * *", "Asia/Shanghai", "日报"), null);
    TaskExecutionView running = store.begin("task-1", "s-1", Instant.now());

    store.finish(running.executionId(), false, "执行超时", 60000L);

    assertThat(store.findTask("task-1").orElseThrow().lastStatus()).isEqualTo("timeout");
  }

  @Test
  @DisplayName("重复finish_回读既有终态不覆盖")
  void finish_repeatReturnsExistingTerminal() {
    store.register(
        "default", new ScheduleConfig("task-1", "0 9 * * *", "Asia/Shanghai", "日报"), null);
    TaskExecutionView running = store.begin("task-1", "s-1", Instant.now());
    store.finish(running.executionId(), false, "执行超时", 60000L);

    TaskExecutionView again = store.finish(running.executionId(), true, null, 1L);

    assertThat(again.success()).isFalse();
    assertThat(again.errorMessage()).isEqualTo("执行超时");
    assertThat(again.durationMs()).isEqualTo(60000L);
    assertThat(store.findTask("task-1").orElseThrow().lastStatus()).isEqualTo("timeout");
    assertThat(store.countExecutions("task-1")).isEqualTo(1);
  }

  @Test
  @DisplayName("相同开始时间_按executionId稳定分页")
  void listExecutions_stableOrderWithSameStartedAt() {
    store.register(
        "default", new ScheduleConfig("task-1", "0 9 * * *", "Asia/Shanghai", "日报"), null);
    Instant same = Instant.ofEpochMilli(1700000000000L);
    for (int index = 0; index < 5; index++) {
      store.begin("task-1", "s-" + index, same);
    }

    List<TaskExecutionView> firstPage = store.listExecutions("task-1", 0, 2);
    List<TaskExecutionView> secondPage = store.listExecutions("task-1", 1, 2);
    List<TaskExecutionView> thirdPage = store.listExecutions("task-1", 2, 2);

    assertThat(firstPage).hasSize(2);
    assertThat(secondPage).hasSize(2);
    assertThat(thirdPage).hasSize(1);
    // 同刻按 executionId DESC:后开始的排前面,页间无重复无遗漏
    List<Long> ids =
        java.util.stream.Stream.of(firstPage, secondPage, thirdPage)
            .flatMap(List::stream)
            .map(TaskExecutionView::executionId)
            .toList();
    assertThat(ids).doesNotHaveDuplicates().hasSize(5);
    assertThat(ids).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    assertThatThrownBy(() -> store.listExecutions("task-1", 0, 101))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.listExecutions("task-1", -1, 10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("启动恢复_遗留running标unknown,不重放,重复恢复幂等")
  void recoverInterrupted_marksUnknownNoReplayIdempotent() {
    store.register(
        "default", new ScheduleConfig("task-1", "0 9 * * *", "Asia/Shanghai", "日报"), null);
    TaskExecutionView running = store.begin("task-1", "s-1", Instant.now());
    TaskExecutionView done = store.begin("task-1", "s-2", Instant.now());
    store.finish(done.executionId(), true, null, 10L);

    store.recoverInterrupted();

    TaskExecutionView recovered =
        store.listExecutions("task-1", 0, 10).stream()
            .filter(view -> view.executionId() == running.executionId())
            .findFirst()
            .orElseThrow();
    assertThat(recovered.success()).isFalse();
    assertThat(recovered.errorMessage()).isEqualTo("进程中断,执行结果未知");
    assertThat(recovered.durationMs()).isNull();
    assertThat(store.findTask("task-1").orElseThrow().lastStatus()).isEqualTo("unknown");
    // 不重放:历史条数与计数不变
    assertThat(store.countExecutions("task-1")).isEqualTo(2);
    assertThat(store.findTask("task-1").orElseThrow().runCount()).isEqualTo(2);
    // 已成功的行不被恢复污染
    TaskExecutionView untouched =
        store.listExecutions("task-1", 0, 10).stream()
            .filter(view -> view.executionId() == done.executionId())
            .findFirst()
            .orElseThrow();
    assertThat(untouched.success()).isTrue();

    // 重复恢复:没有 running 行后零副作用
    store.recoverInterrupted();
    assertThat(store.countExecutions("task-1")).isEqualTo(2);
    assertThat(
            store.listExecutions("task-1", 0, 10).stream()
                .filter(view -> view.executionId() == running.executionId())
                .findFirst()
                .orElseThrow()
                .durationMs())
        .isNull();
  }

  @Test
  @DisplayName("begin失败_任务不存在时零历史零副作用")
  void begin_unknownTaskZeroSideEffects() {
    assertThatThrownBy(() -> store.begin("ghost", "s-1", Instant.now()))
        .isInstanceOf(NoSuchElementException.class);
    assertThat(store.listTasks()).isEmpty();
  }

  private static List<String> tableNames(Connection connection) throws java.sql.SQLException {
    List<String> names = new java.util.ArrayList<>();
    try (var rs =
        connection
            .createStatement()
            .executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
      while (rs.next()) {
        names.add(rs.getString(1));
      }
    }
    return names;
  }
}
