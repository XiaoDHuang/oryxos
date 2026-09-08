package com.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 重启恢复真实子进程验证:测试 classpath 启动真实 main/serve(独立 JVM、独立工作区/端口), 完成记录与在途 running 两类都在 kill
 * 后同目录重启核对——不丢历史、不重放、遗留 running 标 unknown. 进程冒烟,显式 integration 组运行,不影响用户调试服务。
 *
 * @author OryxOS Contributors
 */
@Tag("integration")
class RestartRecoveryIT {

  @TempDir Path workspace;

  private final List<Process> children = new ArrayList<>();

  private Process firstChildProcess;

  @org.junit.jupiter.api.AfterEach
  void killChildren() throws InterruptedException {
    for (Process child : children) {
      if (child.isAlive()) {
        child.destroyForcibly();
        child.waitFor();
      }
    }
  }

  @Test
  @DisplayName("完成后强杀再重启_历史保留零重放且可再执行")
  void completedSurvivesKillAndRestart() throws Exception {
    int port = freePort();
    firstChildProcess = startServe(port, workspace);
    awaitReady(port);
    JsonNode run = data(request(port, "POST", "/api/v1/schedules/restart-a/run"));
    assertThat(run.path("success").asBoolean()).isTrue();
    long runsBefore =
        data(request(port, "GET", "/api/v1/schedules/restart-a/executions")).path("total").asLong();
    assertThat(runsBefore).isEqualTo(1);

    killHard(firstChildProcess);

    Process second = startServe(port, workspace);
    awaitReady(port);
    try {
      String executionId = run.path("executionId").asText();
      JsonNode history = data(request(port, "GET", "/api/v1/schedules/restart-a/executions"));
      // 不重放:历史仍一条,且就是完成那条
      assertThat(history.path("total").asLong()).isEqualTo(1);
      assertThat(history.path("content").get(0).path("executionId").asText())
          .isEqualTo(executionId);
      assertThat(history.path("content").get(0).path("success").asBoolean()).isTrue();
      // 任务仍在且可再次执行
      JsonNode again = data(request(port, "POST", "/api/v1/schedules/restart-a/run"));
      assertThat(again.path("success").asBoolean()).isTrue();
      assertThat(
              data(request(port, "GET", "/api/v1/schedules/restart-a/executions"))
                  .path("total")
                  .asLong())
          .isEqualTo(2);
    } finally {
      killHard(second);
    }
  }

  @Test
  @DisplayName("在途running落库后_重启标记unknown零重放且可再执行")
  void runningRowMarkedUnknownAfterRestart() throws Exception {
    // 第一个实例:建库登记并完整跑完一次,随后强杀
    int first = freePort();
    Process firstChild = startServe(first, workspace);
    awaitReady(first);
    assertThat(
            data(request(first, "POST", "/api/v1/schedules/restart-a/run"))
                .path("success")
                .asBoolean())
        .isTrue();
    killHard(firstChild);

    // 进程已死后写入一条 running 行,精确还原"执行中被 kill"的库形态
    Path db = workspace.resolve("oryxos.db");
    try (java.sql.Connection connection =
            java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement()) {
      long now = System.currentTimeMillis();
      statement.executeUpdate(
          "INSERT INTO task_executions(task_id, session_id, started_at, success, error_message,"
              + " duration_ms) VALUES('restart-a','scheduler:scheduler:schedr',"
              + now
              + ", NULL, NULL, NULL)");
      statement.executeUpdate(
          "UPDATE scheduled_tasks SET last_status='running', last_run_at="
              + now
              + ", run_count=run_count+1 WHERE task_id='restart-a'");
    }

    int second = freePort();
    Process child = startServe(second, workspace);
    awaitReady(second);
    try {
      JsonNode history = data(request(second, "GET", "/api/v1/schedules/restart-a/executions"));
      JsonNode latest = history.path("content").get(0);
      assertThat(latest.path("success").asBoolean()).isFalse();
      assertThat(latest.path("errorMessage").asText()).isEqualTo("进程中断,执行结果未知");
      assertThat(latest.path("durationMs").isNull()).isTrue();
      // 零重放:恢复不产生新历史(完成 1 条 + 遗留 running 1 条,共 2 条)
      assertThat(history.path("total").asLong()).isEqualTo(2);
      JsonNode task = null;
      for (JsonNode item : data(request(second, "GET", "/api/v1/schedules"))) {
        if ("restart-a".equals(item.path("taskId").asText())) {
          task = item;
        }
      }
      assertThat(task).isNotNull();
      // 最近一次状态被恢复为 unknown(覆盖第一次运行留下的 success)
      assertThat(task.path("lastStatus").asText()).isEqualTo("unknown");
      // 恢复后可再执行
      JsonNode again = data(request(second, "POST", "/api/v1/schedules/restart-a/run"));
      assertThat(again.path("success").asBoolean()).isTrue();
    } finally {
      killHard(child);
    }
  }

  /** 启动一个真实 serve 子进程(测试 classpath + mock provider + 隔离工作区),健康探活通过后返回. */
  private Process startServe(int port, Path root) throws IOException {
    prepareWorkspace(root);
    String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    ProcessBuilder builder =
        new ProcessBuilder(
            javaBin,
            "-cp",
            System.getProperty("java.class.path"),
            // 真实 CLI 入口(serve 子命令);OryxOsApplication.main 是裸 Spring 启动,不识 --port
            "com.oryxos.cli.OryxOsCli",
            "serve",
            "--port",
            String.valueOf(port));
    builder.environment().put("ORYXOS_ROOT", root.toString());
    builder.environment().put("MEMORY_BACKEND", "markdown");
    // 子进程声明 mock provider(生产配置对 mock 名显式豁免凭证,安全见 ProviderConfiguration)
    builder.environment().put("ORYXOS_PROVIDERS_0_NAME", "mock");
    builder.environment().put("ORYXOS_PROVIDERS_0_API_KEY", "");
    builder.environment().put("ORYXOS_PROVIDERS_0_BASE_URL", "");
    builder.redirectErrorStream(true);
    Path logs = Path.of(".verification/lesson28");
    Files.createDirectories(logs);
    builder.redirectOutput(
        ProcessBuilder.Redirect.appendTo(logs.resolve("restart-it-" + port + ".log").toFile()));
    Process process = builder.start();
    children.add(process);
    return process;
  }

  private static void prepareWorkspace(Path root) throws IOException {
    Files.createDirectories(root.resolve("profiles"));
    Files.createDirectories(root.resolve("memory"));
    writeIfAbsent(root.resolve("AGENTS.md"), "仅使用本次测试的合成数据。\n");
    writeIfAbsent(root.resolve("memory/MEMORY.md"), "# Long-term memory\n\n## 核心记忆\n\n## 归档记忆\n");
    writeIfAbsent(root.resolve("mcp_servers.yaml"), "servers: []\n");
    writeIfAbsent(
        root.resolve("profiles/schedr.yaml"),
        """
        name: schedr
        provider:
          name: mock
          model: mock-script
          temperature: 0
        tools: []
        schedules:
          - id: restart-a
            cron: "0 0 0 29 2 *"
            zone: Asia/Shanghai
            message: "重启合成任务"
        settings:
          max_iterations: 5
          max_history_turns: 20
        """);
  }

  private static void writeIfAbsent(Path path, String content) throws IOException {
    if (!Files.isRegularFile(path)) {
      Files.writeString(path, content);
    }
  }

  private void awaitReady(int port) throws Exception {
    long deadline = System.currentTimeMillis() + 60000;
    while (System.currentTimeMillis() < deadline) {
      try {
        var response = request(port, "GET", "/api/v1/health");
        if (response.statusCode() == 200) {
          return;
        }
      } catch (IOException e) {
        // 尚未就绪(连接拒绝/超时都是 IOException 子类),继续等
      }
      Thread.sleep(400);
    }
    throw new AssertionError("子进程 serve 60 秒内未就绪,端口 " + port);
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static void killHard(Process process) throws InterruptedException {
    process.destroyForcibly();
    assertThat(process.waitFor()).as("子进程必须退出").isNotNull();
  }

  private static HttpResponse<String> request(int port, String method, String path)
      throws IOException, InterruptedException {
    try (HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
      var request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .timeout(Duration.ofSeconds(30))
              .header("Content-Type", "application/json")
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
