package com.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.oryxos.OryxOsApplication;
import com.oryxos.channel.cli.CliChannel;
import com.oryxos.core.profile.ProfileRegistry;
import com.oryxos.core.react.AgentService;
import com.oryxos.core.session.SessionManager;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 真实 HTTP、生产装配与临时 SQLite 共同守住仅替换模型的整机链路. */
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
class MockAgentE2ETest {

  @TempDir static Path root;
  @LocalServerPort private int port;
  @Autowired private DataSource source;
  @Autowired private SessionManager sessions;
  @Autowired private AgentService agents;
  @Autowired private ApplicationContext context;

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) throws Exception {
    HumanFlowFixture.prepare(root, "mock", "mock-script");
    registry.add("oryxos.root", () -> root.toString());
    registry.add("file.allowed-paths", () -> root.toString());
  }

  @Test
  @DisplayName("真实HTTP两轮一次工具_四条角色_跨会话Memory_三张表对账")
  void realHttpUsesOneEngineAndDurableMemory() throws Exception {
    String id = createSession("http-save");
    JsonNode reply =
        data(
            request(
                "POST",
                "/api/v1/sessions/" + id + "/messages",
                Map.of("content", "记住：" + HumanFlowFixture.FACT)));
    assertThat(reply.path("reply").asText()).contains("已记住");
    JsonNode detail = data(request("GET", "/api/v1/sessions/" + id, null));
    assertThat(detail.path("messages").findValuesAsText("role"))
        .containsExactly("user", "assistant", "tool", "assistant");
    assertThat(detail.path("totalMessages").asInt()).isEqualTo(4);
    assertThat(detail.path("messages").get(2).path("content").asText()).isEqualTo("已记住");
    assertThat(HumanFlowFixture.history(source, id).findValuesAsText("role"))
        .isEqualTo(detail.path("messages").findValuesAsText("role"));
    HumanFlowFixture.assertAccounts(source, id, 2, "save_memory", true);
    assertThat(
            data(request("GET", "/api/v1/sessions?page=0&size=100", null))
                .path("content")
                .findValuesAsText("sessionId"))
        .contains(id);
    assertThat(data(request("GET", "/api/v1/memory", null)).path("content").asText())
        .contains(HumanFlowFixture.FACT);
    assertThat(Files.readString(root.resolve("memory/MEMORY.md"))).contains(HumanFlowFixture.FACT);
    String reader = createSession("http-recall");
    String before = Files.readString(root.resolve("memory/MEMORY.md"));
    assertThat(
            data(request(
                    "POST",
                    "/api/v1/sessions/" + reader + "/messages",
                    Map.of("content", "我在哪个城市")))
                .path("reply")
                .asText())
        .contains("北京");
    HumanFlowFixture.assertAccounts(source, reader, 1, null, true);
    assertThat(Files.readString(root.resolve("memory/MEMORY.md"))).isEqualTo(before);
  }

  @Test
  @DisplayName("CLI产生的同一会话可被真实REST列表和详情读取")
  void cliSessionIsVisibleToConsoleDataEndpoints() throws Exception {
    var session = sessions.getOrCreate("cli", "cli-" + UUID.randomUUID(), "flow");
    StringWriter output = new StringWriter();
    new CliChannel(
            agents,
            session,
            new BufferedReader(new StringReader("记住：CLI合成偏好\n/quit\n")),
            new PrintWriter(output))
        .run();
    assertThat(output.toString()).contains("已记住");
    JsonNode detail = data(request("GET", "/api/v1/sessions/" + session.id(), null));
    assertThat(detail.path("sessionId").asText()).isEqualTo(session.id());
    assertThat(detail.path("messages").get(0).path("content").asText()).contains("CLI合成偏好");
    assertThat(
            data(request("GET", "/api/v1/sessions?size=100", null))
                .path("content")
                .findValuesAsText("sessionId"))
        .contains(session.id());
    HumanFlowFixture.assertAccounts(source, session.id(), 2, "save_memory", true);
  }

  @Test
  @DisplayName("真实装配只加载临时工作区_九工具完整_数据库路径同根")
  void productionAssemblyUsesIsolatedWorkspace() throws Exception {
    assertThat(context.getBean(ProfileRegistry.class).all()).hasSize(1);
    assertThat(context.getBean("chatModelRegistry", Map.class)).containsOnlyKeys("mock");
    assertThat(data(request("GET", "/api/v1/tools", null)).findValuesAsText("name"))
        .containsExactlyInAnyOrderElementsOf(HumanFlowFixture.TOOLS);
    assertThat(new JdbcTemplate(source).queryForList("PRAGMA database_list"))
        .anySatisfy(
            row ->
                assertThat(Path.of((String) row.get("file")).toRealPath())
                    .isEqualTo(root.resolve("oryxos.db").toRealPath()));
    assertThat(data(request("GET", "/api/v1/profiles", null))).hasSize(1);
    assertThat(context.getBeansOfType(org.springframework.data.repository.Repository.class))
        .hasSize(4);
  }

  @Test
  @DisplayName("真实容器转发admin根和子路由_前端资源及OpenAPI可读_API不回落HTML")
  void jarServesAdminAndApiIndependently() throws Exception {
    for (String path :
        new String[] {"/admin", "/admin/", "/admin/sessions", "/admin/sessions/detail"}) {
      var response = request("GET", path, null);
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).contains("<div id=\"app\"");
    }
    assertThat(data(request("GET", "/api/v1/health", null)).path("status").asText())
        .isEqualTo("ok");
    var missing = request("GET", "/api/v1/does-not-exist", null);
    assertThat(missing.statusCode()).isEqualTo(404);
    assertThat(missing.body()).doesNotContain("<html");
    var openapi = request("GET", "/v3/api-docs", null);
    assertThat(openapi.statusCode()).isEqualTo(200);
    assertThat(openapi.body()).contains("/api/v1/sessions");
  }

  @Test
  @DisplayName("显式Bootstrap消失_真实请求失败且未调用模型_恢复文件后仍可用")
  void missingBootstrapDoesNotSilentlyRun() throws Exception {
    Path bootstrap = root.resolve("AGENTS.md");
    String text = Files.readString(bootstrap);
    String id = createSession("missing-bootstrap");
    Files.delete(bootstrap);
    try {
      var failed =
          request("POST", "/api/v1/sessions/" + id + "/messages", Map.of("content", "测试缺失"));
      assertThat(failed.statusCode()).isEqualTo(500);
      assertThat(failed.body()).doesNotContain(root.toString());
      assertThat(
              new JdbcTemplate(source)
                  .queryForObject(
                      "SELECT count(*) FROM llm_calls WHERE session_id=?", Integer.class, id))
          .isZero();
    } finally {
      Files.writeString(bootstrap, text);
    }
    assertThat(
            data(request(
                    "POST", "/api/v1/sessions/" + id + "/messages", Map.of("content", "文件已恢复")))
                .path("reply")
                .asText())
        .isNotBlank();
  }

  private String createSession(String label) throws Exception {
    return data(request(
            "POST",
            "/api/v1/sessions",
            Map.of("profileName", "flow", "userId", label + "-" + UUID.randomUUID())))
        .path("sessionId")
        .asText();
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
