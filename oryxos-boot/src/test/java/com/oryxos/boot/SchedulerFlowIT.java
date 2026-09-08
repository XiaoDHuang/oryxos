package com.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.oryxos.OryxOsApplication;
import com.oryxos.core.schedule.ScheduledTaskStore;
import com.oryxos.tool.sandbox.WhitelistSandbox;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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
 * 真实模型定时链路:DeepSeek + 本次真实北京天气(open-meteo,免 key)+ 回环 webhook. 手动两次运行各 3 LLM + 2 Tool 同一固定会话;notify
 * 域名拒绝→失败审计→恢复许可后真实 cron 成功。 真实外呼与付费模型,显式 integration 组运行;模型偏离脚本按 D28-04 显式判失败,不放宽计数。
 *
 * @author OryxOS Contributors
 */
@Tag("integration")
@SpringBootTest(
    classes = OryxOsApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "memory.backend=markdown",
      "shell.allowed-commands=",
      "oryxos.scheduler.enabled=true"
    })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SchedulerFlowIT {

  private static final String SESSION = "scheduler:scheduler:weather";

  private static MockWebServer loopback;

  @TempDir static Path root;

  @LocalServerPort private int port;

  @Autowired private javax.sql.DataSource source;

  @Autowired private ScheduledTaskStore store;

  @Autowired private WhitelistSandbox sandbox;

  @BeforeAll
  static void startLoopback() throws IOException {
    loopback = new MockWebServer();
    loopback.start();
    // 预置充足的成功响应:tick 与 weather 的 notify 都会消费;不给就 501,那才是假失败
    for (int index = 0; index < 60; index++) {
      loopback.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(200));
    }
  }

  @AfterAll
  static void stopLoopback() throws IOException {
    if (loopback != null) {
      loopback.shutdown();
    }
  }

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) throws Exception {
    prepare(root, loopback.getPort());
    registry.add("oryxos.root", () -> root.toString());
    registry.add("file.allowed-paths", () -> root.toString());
    registry.add("http.allowed-domains", () -> "api.open-meteo.com,localhost");
  }

  private static void prepare(Path workspace, int hookPort) throws IOException {
    Files.createDirectories(workspace.resolve("profiles"));
    Files.createDirectories(workspace.resolve("memory"));
    Files.writeString(workspace.resolve("AGENTS.md"), "仅使用本次测试的合成数据。\n");
    Files.writeString(
        workspace.resolve("memory/MEMORY.md"), "# Long-term memory\n\n## 核心记忆\n\n## 归档记忆\n");
    Files.writeString(workspace.resolve("mcp_servers.yaml"), "servers: []\n");
    Files.writeString(
        workspace.resolve("profiles/weather.yaml"),
        """
        name: weather
        provider:
          name: deepseek
          model: deepseek-chat
          temperature: 0
        tools: [http_get, notify]
        notify_channels:
          - type: webhook
            url: http://localhost:%d/hook
        schedules:
          - id: task-weather
            cron: "0 0 0 29 2 *"
            zone: Asia/Shanghai
            message: "严格按顺序执行且每步只调一个工具:第一步调用 http_get 获取北京天气(url: https://api.open-meteo.com/v1/forecast?latitude=39.9042&longitude=116.4074&current=temperature_2m);第二步调用 notify 把刚才查到的气温推送到已配置渠道;第三步直接文字回复已完成。"
        settings:
          max_iterations: 8
          max_history_turns: 20
        """
            .formatted(hookPort));
    // tick 必须独立 Profile:同 Profile 的执行互斥锁会让手动 weather 运行撞上 cron 心跳
    Files.writeString(
        workspace.resolve("profiles/weather-tick.yaml"),
        """
        name: weather-tick
        provider:
          name: deepseek
          model: deepseek-chat
          temperature: 0
        tools: [notify]
        notify_channels:
          - type: webhook
            url: http://localhost:%d/hook
        schedules:
          - id: task-tick
            cron: "*/5 * * * * *"
            zone: Asia/Shanghai
            message: "调用 notify 推送'心跳'两个字,然后一句话回复完成。"
        settings:
          max_iterations: 5
          max_history_turns: 20
        """
            .formatted(hookPort));
  }

  @Test
  @DisplayName("真实模型两次手动运行各3LLM2Tool同一Session_天气值进webhook_停用恢复后cron成功")
  void realFlowTwiceThenDenyAndRecover() throws Exception {
    Assumptions.assumeTrue(
        System.getenv("DEEPSEEK_API_KEY") != null && !System.getenv("DEEPSEEK_API_KEY").isBlank(),
        "未设置 DEEPSEEK_API_KEY");

    // 任务登记就位
    assertThat(
            SchedulerFlowFixture.pollUntil(
                () -> store.findTask("task-weather").isPresent(), Duration.ofSeconds(10)))
        .isTrue();

    for (int round = 1; round <= 2; round++) {
      JsonNode run = data(request("POST", "/api/v1/schedules/task-weather/run"));
      assertThat(run.path("success").asBoolean())
          .as(
              "第 %s 次运行须严格成功(3 LLM + 2 Tool 脚本);实际 errorMessage=%s",
              round, run.path("errorMessage").asText())
          .isTrue();
      // 同一固定会话,每轮严格 3 LLM + 2 Tool
      var jdbc = new JdbcTemplate(source);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM llm_calls WHERE session_id=?", Integer.class, SESSION))
          .isEqualTo(3 * round);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM tool_invocations WHERE session_id=?",
                  Integer.class,
                  SESSION))
          .isEqualTo(2 * round);
    }

    // webhook 内容必须带本次天气的气温值(不是旧数据或编造)
    var jdbc = new JdbcTemplate(source);
    String weatherJson =
        jdbc.queryForObject(
            "SELECT result FROM tool_invocations WHERE session_id=? AND tool_name='http_get'"
                + " ORDER BY rowid DESC LIMIT 1",
            String.class,
            SESSION);
    String temperature = String.valueOf(parseTemperature(weatherJson));
    // 接收队列里混有心跳推送,抽干并找本次天气那条(不假定到达顺序)
    long drainDeadline = System.currentTimeMillis() + 5000;
    boolean sawWeatherPush = false;
    while (System.currentTimeMillis() < drainDeadline && !sawWeatherPush) {
      var hookRequest = loopback.takeRequest(500, java.util.concurrent.TimeUnit.MILLISECONDS);
      if (hookRequest == null) {
        break;
      }
      if (hookRequest.getBody().readUtf8().contains(temperature)) {
        sawWeatherPush = true;
      }
    }
    assertThat(sawWeatherPush).as("webhook 必须收到包含本次实时气温 %s 的推送", temperature).isTrue();

    // 同一任务两条成功历史
    assertThat(store.countExecutions("task-weather")).isEqualTo(2);
    assertThat(store.findTask("task-weather").orElseThrow().runCount()).isEqualTo(2);

    // notify 域名拒绝 → 业务失败 + 失败审计
    sandbox.denyDomain("localhost");
    JsonNode denied = data(request("POST", "/api/v1/schedules/task-weather/run"));
    assertThat(denied.path("success").asBoolean()).isFalse();
    assertThat(denied.path("errorMessage").asText()).isEqualTo("工具执行失败");
    var failedNotify =
        jdbc.queryForMap(
            "SELECT success, error_message FROM tool_invocations WHERE session_id=?"
                + " AND tool_name='notify' ORDER BY rowid DESC LIMIT 1",
            SESSION);
    assertThat(((Number) failedNotify.get("success")).intValue()).isZero();
    assertThat(failedNotify.get("error_message")).isNotNull();

    // 恢复许可 → 下一真实 cron 触发成功(每 5 秒规则,非手动)
    sandbox.allowDomain("localhost");
    for (int index = 0; index < 20; index++) {
      loopback.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(200));
    }
    long recoveredAfter = System.currentTimeMillis();
    assertThat(
            SchedulerFlowFixture.pollUntil(
                () ->
                    store.listExecutions("task-tick", 0, 5).stream()
                        .anyMatch(
                            row ->
                                Boolean.TRUE.equals(row.success())
                                    && row.startedAt().toEpochMilli() >= recoveredAfter),
                Duration.ofSeconds(30)))
        .as("恢复许可后,真实 cron 应到点成功一次")
        .isTrue();
  }

  private static double parseTemperature(String weatherJson) throws IOException {
    JsonNode tree = HumanFlowFixture.JSON.readTree(weatherJson);
    return tree.path("current").path("temperature_2m").asDouble();
  }

  private HttpResponse<String> request(String method, String path) throws Exception {
    try (HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
      var request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .timeout(Duration.ofSeconds(120))
              .method(method, HttpRequest.BodyPublishers.noBody());
      return client.send(
          request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
  }

  private static JsonNode data(HttpResponse<String> response) throws IOException {
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode json = HumanFlowFixture.JSON.readTree(response.body());
    assertThat(json.path("code").asText()).isEqualTo("SUCCESS");
    return json.path("data");
  }
}
