package com.oryxos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.oryxos.tool.sandbox.ActionType;
import com.oryxos.tool.sandbox.PermissiveSandbox;
import com.oryxos.tool.sandbox.SandboxViolationException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

@DisplayName("HTTP工具的受控传输")
class HttpToolsTest {
  private MockWebServer server;
  private HttpTools tools;

  @BeforeEach
  void startServer() throws Exception {
    server = new MockWebServer();
    server.start();
    tools = new HttpTools(new PermissiveSandbox(), RestClient.builder());
  }

  @AfterEach
  void closeServer() throws Exception {
    server.close();
  }

  @Test
  @DisplayName("GET读取正文且POST发送原始JSON与正确媒体类型")
  void exchangesRequests() throws Exception {
    server.enqueue(new MockResponse().setBody("你好"));
    assertEquals("你好", tools.httpGet(server.url("/get").toString()).content());
    assertEquals("GET", server.takeRequest(1, TimeUnit.SECONDS).getMethod());
    server.enqueue(new MockResponse().setBody("完成"));
    assertEquals(
        "完成", tools.httpPost(server.url("/post").toString(), "{\"name\":\"小明\"}").content());
    var request = server.takeRequest(1, TimeUnit.SECONDS);
    assertEquals("POST", request.getMethod());
    assertTrue(request.getHeader("Content-Type").startsWith("application/json"));
    assertEquals("{\"name\":\"小明\"}", request.getBody().readUtf8());
  }

  @Test
  @DisplayName("安全拒绝零请求并保留课件异常守点")
  void enforcesBeforeRequest() {
    HttpTools denied =
        new HttpTools(
            action -> {
              assertEquals(ActionType.HTTP_REQUEST, action.type());
              assertEquals(server.url("/deny").toString(), action.target());
              throw new SandboxViolationException("禁止请求");
            },
            RestClient.builder());
    assertThrows(
        SandboxViolationException.class, () -> denied.httpGet(server.url("/deny").toString()));
    assertThrows(
        SandboxViolationException.class,
        () -> denied.httpPost(server.url("/deny").toString(), "{}"));
    assertEquals(0, server.getRequestCount());
  }

  @Test
  @DisplayName("非法URL凭证与非法JSON在发送前拒绝")
  void validatesBeforeRequest() {
    for (String url :
        List.of(
            "",
            "file:///tmp/x",
            "http://user:secret@localhost/x",
            "http:///path",
            "https://${MISSING}")) {
      assertFalse(tools.httpGet(url).success());
    }
    for (String body : List.of("", "{bad", "{}{}")) {
      assertFalse(tools.httpPost(server.url("/").toString(), body).success());
    }
    assertEquals(0, server.getRequestCount());
  }

  @Test
  @DisplayName("重定向不跟随且状态分类不重放POST")
  void classifiesStatusesWithoutRedirects() {
    for (int code : List.of(302, 400, 429, 500, 503, 600)) {
      server.enqueue(
          new MockResponse()
              .setResponseCode(code)
              .addHeader("Location", server.url("/other"))
              .setBody("secret-token"));
      var result = tools.httpGet(server.url("/status?secret-token").toString());
      assertFalse(result.success());
      assertEquals(code >= 500 && code < 600, result.retryable());
      assertTrue(result.errorMessage().contains(Integer.toString(code)));
      assertFalse(result.errorMessage().contains("secret-token"));
    }
    server.enqueue(new MockResponse().setResponseCode(503));
    assertFalse(tools.httpPost(server.url("/").toString(), "{}").retryable());
    assertEquals(7, server.getRequestCount());
  }

  @Test
  @DisplayName("响应恰好1MiB成功而超限流式失败不可重试")
  void boundsResponseBody() {
    String allowed = "x".repeat(HttpTools.MAX_RESPONSE_BYTES);
    server.enqueue(new MockResponse().setBody(allowed));
    assertEquals(allowed.length(), tools.httpGet(server.url("/").toString()).content().length());
    server.enqueue(new MockResponse().setChunkedBody(allowed + "x", 8192));
    var result = tools.httpGet(server.url("/").toString());
    assertFalse(result.success());
    assertFalse(result.retryable());
  }

  @Test
  @DisplayName("生产连接5秒读取30秒且读取超时仅GET可重试")
  void timeoutsAreBounded() {
    assertEquals(Duration.ofSeconds(5), HttpTools.CONNECT_TIMEOUT);
    assertEquals(Duration.ofSeconds(30), HttpTools.READ_TIMEOUT);
    HttpTools shortRead =
        new HttpTools(
            new PermissiveSandbox(),
            RestClient.builder(),
            HttpTools.CONNECT_TIMEOUT,
            Duration.ofMillis(100));
    server.enqueue(new MockResponse().setBody("delayed").setBodyDelay(300, TimeUnit.MILLISECONDS));
    var get = shortRead.httpGet(server.url("/").toString());
    assertFalse(get.success());
    assertTrue(get.retryable());
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
    var post = shortRead.httpPost(server.url("/").toString(), "{}");
    assertFalse(post.success());
    assertFalse(post.retryable());
  }
}
