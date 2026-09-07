package com.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.oryxos.OryxOsApplication;
import com.oryxos.channel.cli.CliChannel;
import com.oryxos.core.profile.ProfileRegistry;
import com.oryxos.core.react.AgentService;
import com.oryxos.core.react.PromptBuilder;
import com.oryxos.core.react.ReActLoop;
import com.oryxos.core.react.ToolExecutor;
import com.oryxos.core.session.SessionManager;
import com.oryxos.provider.LlmCallAudit;
import com.oryxos.provider.ProviderService;
import com.oryxos.provider.ToolSchemaAdapter;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 真模型和天气网络只由 integration 显式触发,测试语料与持久数据完全隔离. */
@Tag("integration")
@SpringBootTest(
    classes = OryxOsApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "oryxos.providers[0].name=deepseek",
      "memory.backend=markdown",
      "shell.allowed-commands=",
      "oryxos.scheduler.enabled=true"
    })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HumanTriggerFlowIT {

  private static final String WEATHER_URL =
      "https://api.open-meteo.com/v1/forecast?latitude=39.9&longitude=116.4&current=temperature_2m";
  @TempDir static Path root;
  @LocalServerPort private int port;
  @Autowired private SessionManager sessions;
  @Autowired private AgentService agents;
  @Autowired private DataSource source;
  @Autowired private ApplicationContext context;

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) throws Exception {
    String key = System.getenv("DEEPSEEK_API_KEY");
    if (key == null || key.isBlank()) {
      throw new IllegalStateException("真实人推集成需要 DEEPSEEK_API_KEY；未执行不能算通过");
    }
    HumanFlowFixture.prepare(root, "deepseek", "deepseek-chat");
    String profile = Files.readString(root.resolve("profiles/flow.yaml"));
    Files.writeString(
        root.resolve("profiles/weather.yaml"),
        profile
            .replace("name: flow\n", "name: weather\n")
            .replace(
                "tools: [read_file, write_file, list_dir, shell, http_get, http_post,"
                    + " notify, save_memory, recall_memory]",
                "tools: [http_get]"));
    registry.add("oryxos.root", () -> root.toString());
    // 列表在高优先级配置源整项替换,名称与凭证必须在同一源一起提供。
    registry.add("oryxos.providers[0].name", () -> "deepseek");
    registry.add("oryxos.providers[0].api-key", () -> key);
    registry.add(
        "oryxos.providers[0].base-url",
        () -> {
          String url = System.getenv("DEEPSEEK_BASE_URL");
          return url == null || url.isBlank() ? "https://api.deepseek.com" : url;
        });
    registry.add("http.allowed-domains", () -> "api.open-meteo.com");
    registry.add("file.allowed-paths", () -> root.toString());
  }

  @Test
  @DisplayName("真模型CLI和REST天气各两轮一次http_get_记忆跨会话可读_三面同源")
  void realHumanTriggerReconcilesAllThreePillars() throws Exception {
    final String before = Files.readString(root.resolve("memory/MEMORY.md"));
    String question =
        "今天北京天气怎么样，穿什么合适？请且仅调用一次 http_get 获取 " + WEATHER_URL + " 的天气数据，然后用一句中文回答，不做任何其他工具调用。";
    var cliSession = sessions.getOrCreate("cli", "weather-reader", "weather");
    StringWriter output = new StringWriter();
    new CliChannel(
            agents,
            cliSession,
            new BufferedReader(new StringReader(question + "\n/quit\n")),
            new PrintWriter(output))
        .run();
    assertThat(cliSession.messages()).hasSize(4);
    assertThat(cliSession.messages().getLast().getText()).isNotBlank();
    assertWeather(cliSession.id());
    String webId = create("weather");
    assertThat(send(webId, question)).isNotBlank();
    assertWeather(webId);
    assertThat(Files.readString(root.resolve("memory/MEMORY.md"))).isEqualTo(before);
    String writer = create("flow");
    assertThat(send(writer, "请调用且只调用一次 save_memory，把原文“我在北京，怕冷”存到 archival，然后简短确认。")).isNotBlank();
    HumanFlowFixture.assertAccounts(source, writer, 2, "save_memory", true);
    assertThat(data("GET", "/api/v1/memory", null).path("content").asText()).contains("北京");
    assertThat(send(create("flow"), "仅根据已经注入的长期记忆直接回答我在哪个城市，不调用工具。")).contains("北京");
    assertThat(data("GET", "/api/v1/tools", null).findValuesAsText("name"))
        .containsExactlyInAnyOrderElementsOf(HumanFlowFixture.TOOLS);
    assertThat(
            data("GET", "/api/v1/sessions?size=100", null)
                .path("content")
                .findValuesAsText("sessionId"))
        .contains(cliSession.id(), webId, writer);
    assertThat(request("GET", "/admin/sessions", null).body()).contains("<div id=\"app\"");
  }

  @Test
  @DisplayName("真实Provider协议故障与两类工具失败均可追溯_服务继续可用")
  void failurePathsKeepTheirAuditRecords() throws Exception {
    for (Path target :
        new Path[] {root.resolveSibling("denied-live-file"), root.resolve("missing-live-file")}) {
      String id = create("flow");
      assertThat(send(id, "只调用一次 read_file，路径为 " + target + " 。无论成功失败都立即用一句话报告结果，不重试、不调用其他工具。"))
          .isNotBlank();
      HumanFlowFixture.assertAccounts(source, id, 2, "read_file", false);
    }
    try (MockWebServer endpoint = new MockWebServer()) {
      endpoint.start();
      endpoint.enqueue(
          new MockResponse()
              .setResponseCode(503)
              .setHeader("Content-Type", "application/json")
              .setBody("{\"error\":{\"message\":\"合成Provider不可用\",\"type\":\"server_error\"}}"));
      // 真凭证不能发给故障对端,只在此使用合成值和一次调用的故障注入预算。
      var api =
          OpenAiApi.builder()
              .baseUrl(endpoint.url("/").toString())
              .apiKey("synthetic-flow-only")
              .build();
      var model =
          OpenAiChatModel.builder()
              .openAiApi(api)
              .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
              .build();
      var provider =
          new ProviderService(
              Map.of("deepseek", model),
              new ToolSchemaAdapter(),
              context.getBean(LlmCallAudit.class));
      var engine =
          new AgentService(
              new ReActLoop(
                  provider,
                  context.getBean(PromptBuilder.class),
                  context.getBean(ToolExecutor.class)),
              context.getBean(ProfileRegistry.class),
              sessions);
      var session = sessions.getOrCreate("cli", "provider-down", "flow");
      assertThatThrownBy(() -> engine.process(session, "故障注入"))
          .isInstanceOf(RuntimeException.class);
      assertThat(endpoint.getRequestCount()).isEqualTo(1);
      var rows =
          new JdbcTemplate(source)
              .queryForList(
                  "SELECT success,error_message FROM llm_calls WHERE session_id=?", session.id());
      assertThat(rows).hasSize(1);
      assertThat(((Number) rows.getFirst().get("success")).intValue()).isZero();
      assertThat(rows.getFirst().get("error_message")).isNotNull();
    }
    assertThat(data("GET", "/api/v1/health", null).path("status").asText()).isEqualTo("ok");
  }

  private void assertWeather(String id) throws Exception {
    HumanFlowFixture.assertAccounts(source, id, 2, "http_get", true);
    JsonNode detail = data("GET", "/api/v1/sessions/" + id, null);
    assertThat(detail.path("messages").findValuesAsText("role"))
        .containsExactly("user", "assistant", "tool", "assistant");
    assertThat(detail.path("messages").get(2).path("content").asText()).contains("temperature_2m");
    assertThat(HumanFlowFixture.history(source, id).findValuesAsText("role"))
        .isEqualTo(detail.path("messages").findValuesAsText("role"));
  }

  private String create(String profile) throws Exception {
    return data(
            "POST",
            "/api/v1/sessions",
            Map.of("profileName", profile, "userId", "human-" + UUID.randomUUID()))
        .path("sessionId")
        .asText();
  }

  private String send(String id, String content) throws Exception {
    return data("POST", "/api/v1/sessions/" + id + "/messages", Map.of("content", content))
        .path("reply")
        .asText();
  }

  private JsonNode data(String method, String path, Object body) throws Exception {
    var response = request(method, path, body);
    assertThat(response.statusCode()).isEqualTo(200);
    var json = HumanFlowFixture.JSON.readTree(response.body());
    assertThat(json.path("code").asText()).isEqualTo("SUCCESS");
    return json.path("data");
  }

  private HttpResponse<String> request(String method, String path, Object body) throws Exception {
    try (HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
      var request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .timeout(Duration.ofSeconds(65))
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
}
