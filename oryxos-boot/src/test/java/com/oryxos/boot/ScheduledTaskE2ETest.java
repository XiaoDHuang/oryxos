package com.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.oryxos.OryxOsApplication;
import com.oryxos.core.profile.ScheduleConfig;
import com.oryxos.core.schedule.ScheduledTaskStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 定时任务真实整机链路:登记→手动执行→审计/记忆/历史/次数对账→停用→真实 cron 停用跳过. 生产装配只替换模型(mock),会话/记忆/审计/任务全部落真实 SQLite。
 *
 * @author OryxOS Contributors
 */
@SpringBootTest(
    classes = OryxOsApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "oryxos.providers[0].name=mock",
      "oryxos.providers[0].api-key=",
      "oryxos.providers[0].base-url=",
      "memory.backend=markdown",
      "shell.allowed-commands=",
      "http.allowed-domains=",
      "oryxos.scheduler.enabled=true"
    })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ScheduledTaskE2ETest {

  private static final String SCHEDULER_SESSION = "scheduler:scheduler:sched";

  @TempDir static Path root;
  @LocalServerPort private int port;
  @Autowired private DataSource source;
  @Autowired private ScheduledTaskStore store;

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) throws Exception {
    SchedulerFlowFixture.prepare(root);
    registry.add("oryxos.root", () -> root.toString());
    registry.add("file.allowed-paths", () -> root.toString());
  }

  @Test
  @DisplayName("登记落库_列表可查且available为true")
  void registration_persistedAndListed() throws Exception {
    var task = store.findTask("task-a");
    assertThat(task).isPresent();
    assertThat(task.orElseThrow().enabled()).isTrue();
    assertThat(task.orElseThrow().cron()).isEqualTo("0 0 0 29 2 *");
    assertThat(task.orElseThrow().zone()).isEqualTo("Asia/Shanghai");
    assertThat(task.orElseThrow().nextRunAt()).isNotNull();

    JsonNode tasks = data(request("GET", "/api/v1/schedules", null));
    assertThat(tasks.findValuesAsText("taskId")).contains("task-a", "task-b");
    assertThat(tasks.findValuesAsText("available")).doesNotContain("false");
  }

  @Test
  @DisplayName("课件五步_登记run两轮一次save_memory_记忆历史次数对账后停用")
  void coursewareFiveSteps_runThenAuditThenDisable() throws Exception {
    long runsBefore = store.countExecutions("task-a");
    long runCountBefore = store.findTask("task-a").orElseThrow().runCount();

    JsonNode run = data(request("POST", "/api/v1/schedules/task-a/run", null));
    assertThat(run.path("success").asBoolean()).isTrue();
    assertThat(run.path("executionId").isTextual()).isTrue();

    // scheduler 会话跨测试方法共享,审计按增量对账:本轮 2 次 LLM + 1 次 save_memory
    JdbcTemplate jdbc = new JdbcTemplate(source);
    assertThat(
            jdbc.queryForList(
                "SELECT * FROM llm_calls WHERE session_id=? ORDER BY rowid DESC LIMIT 2",
                SCHEDULER_SESSION))
        .allSatisfy(
            row -> {
              assertThat(((Number) row.get("success")).intValue()).isEqualTo(1);
              assertThat(((Number) row.get("total_tokens")).intValue()).isPositive();
            });
    var tools =
        jdbc.queryForList(
            "SELECT * FROM tool_invocations WHERE session_id=? AND tool_name='save_memory'"
                + " ORDER BY rowid DESC LIMIT 1",
            SCHEDULER_SESSION);
    assertThat(tools).hasSize(1);
    assertThat(((Number) tools.getFirst().get("success")).intValue()).isEqualTo(1);
    // 记忆:默认 markdown 后端,内容可读且文件真实落盘;不能误当 SQLite 后端
    JsonNode memory = data(request("GET", "/api/v1/memory", null));
    assertThat(memory.path("backend").asText()).isEqualTo("markdown");
    assertThat(memory.path("content").asText()).contains(SchedulerFlowFixture.FACT);
    assertThat(Files.readString(root.resolve("memory/MEMORY.md")))
        .contains(SchedulerFlowFixture.FACT);

    JsonNode executions = data(request("GET", "/api/v1/schedules/task-a/executions", null));
    assertThat(executions.path("total").asLong()).isEqualTo(runsBefore + 1);
    assertThat(executions.path("content").get(0).path("success").asBoolean()).isTrue();
    assertThat(executions.path("content").get(0).path("durationMs").asLong())
        .isGreaterThanOrEqualTo(0);

    JsonNode tasks = data(request("GET", "/api/v1/schedules", null));
    JsonNode taskA = findTask(tasks, "task-a");
    assertThat(taskA.path("runCount").asLong()).isEqualTo(runCountBefore + 1);
    assertThat(taskA.path("lastStatus").asText()).isEqualTo("success");

    JsonNode disabled = data(request("PUT", "/api/v1/schedules/task-a", Map.of("enabled", false)));
    assertThat(disabled.path("enabled").asBoolean()).isFalse();
    assertThat(store.findTask("task-a").orElseThrow().enabled()).isFalse();
  }

  @Test
  @DisplayName("真实cron触发后停用_自动触发跳过但手动仍可执行")
  void realCronThenDisable_automaticSkipsManualStillRuns() throws Exception {
    // 真实到点:每秒规则应在几秒内产生第一条历史
    assertThat(
            SchedulerFlowFixture.pollUntil(
                () -> store.findTask("task-b").orElseThrow().runCount() >= 1,
                Duration.ofSeconds(5)))
        .as("每秒 cron 规则应在 5 秒内产生真实执行")
        .isTrue();

    data(request("PUT", "/api/v1/schedules/task-b", Map.of("enabled", false)));
    // 给在途触发一点落定时间再快照,之后自动触发必须全部跳过
    Thread.sleep(300);
    long quiescent = store.countExecutions("task-b");
    Thread.sleep(1600);
    assertThat(store.countExecutions("task-b")).as("停用后自动触发应零新增历史").isEqualTo(quiescent);

    JsonNode run = data(request("POST", "/api/v1/schedules/task-b/run", null));
    assertThat(run.path("success").asBoolean()).isTrue();
    assertThat(store.countExecutions("task-b")).isEqualTo(quiescent + 1);
  }

  @Test
  @DisplayName("失效规则_只读可查且执行与启用按契约拒绝")
  void staleRule_readableButNotRunnable() throws Exception {
    // 规则只存在于库(当前 Profile 未声明):模拟定义消失的既有任务
    store.register(
        "legacy", new ScheduleConfig("ghost-task", "0 0 9 * * *", "Asia/Shanghai", "旧规则"), null);

    JsonNode tasks = data(request("GET", "/api/v1/schedules", null));
    JsonNode ghost = findTask(tasks, "ghost-task");
    assertThat(ghost.path("available").asBoolean()).isFalse();

    var run = request("POST", "/api/v1/schedules/ghost-task/run", null);
    assertThat(run.statusCode()).isEqualTo(400);
    assertThat(HumanFlowFixture.JSON.readTree(run.body()).path("errorCode").asText())
        .isEqualTo("INVALID_REQUEST");
  }

  @Test
  @DisplayName("并发runNow_忙碌方400且不产生历史,成功数与历史增量一致")
  void concurrentRunNow_busyRejectedWithoutHistory() throws Exception {
    long before = store.countExecutions("task-a");
    List<HttpResponse<String>> responses = new java.util.ArrayList<>();
    try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      List<java.util.concurrent.Future<HttpResponse<String>>> futures =
          List.of(
              pool.submit(() -> request("POST", "/api/v1/schedules/task-a/run", null)),
              pool.submit(() -> request("POST", "/api/v1/schedules/task-a/run", null)));
      for (var future : futures) {
        responses.add(future.get());
      }
    }
    long succeeded = responses.stream().filter(response -> response.statusCode() == 200).count();
    for (HttpResponse<String> response : responses) {
      assertThat(response.statusCode()).isIn(200, 400);
      if (response.statusCode() == 400) {
        assertThat(HumanFlowFixture.JSON.readTree(response.body()).path("errorCode").asText())
            .isEqualTo("INVALID_REQUEST");
      }
    }
    // 忙碌拒绝不产生历史:历史增量必须等于成功数,不多不少
    assertThat(store.countExecutions("task-a")).isEqualTo(before + succeeded);
  }

  private static JsonNode findTask(JsonNode tasks, String taskId) {
    for (JsonNode task : tasks) {
      if (taskId.equals(task.path("taskId").asText())) {
        return task;
      }
    }
    throw new AssertionError("任务未出现在列表: " + taskId);
  }

  private HttpResponse<String> request(String method, String path, Object body) throws Exception {
    try (HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
      var request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .timeout(Duration.ofSeconds(30))
              .header("Content-Type", "application/json")
              .method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(
                          HumanFlowFixture.JSON.writeValueAsString(body)));
      return client.send(
          request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
  }

  private static JsonNode data(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode json = HumanFlowFixture.JSON.readTree(response.body());
    assertThat(json.path("code").asText()).isEqualTo("SUCCESS");
    return json.path("data");
  }
}
