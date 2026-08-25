package com.oryxos.tool.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class WebhookNotifyAdapterTest {

  private MockWebServer server;

  @BeforeEach
  void startServer() throws Exception {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void stopServer() throws Exception {
    server.close();
  }

  @Test
  @DisplayName("发送内容到配置的 webhook")
  void send_postsContentToConfiguredUrl() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(204));
    WebhookNotifyAdapter adapter = new WebhookNotifyAdapter(RestClient.builder());
    NotifyTarget target =
        new NotifyTarget("webhook", Map.of("url", server.url("/notify-target").toString()));

    adapter.send(target, "hello notify");

    RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
    assertThat(request).isNotNull();
    assertThat(request.getMethod()).isEqualTo("POST");
    assertThat(request.getPath()).isEqualTo("/notify-target");
    assertThat(request.getHeader("Content-Type")).startsWith("application/json");
    assertThat(request.getBody().readUtf8()).contains("\"content\":\"hello notify\"");
  }

  @Test
  @DisplayName("通知地址缺失时在网络请求前明确失败")
  void missingUrl_failsBeforeRequest() {
    WebhookNotifyAdapter adapter = new WebhookNotifyAdapter(RestClient.builder());
    NotifyTarget target = new NotifyTarget("webhook", Map.of());

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.send(target, "hello"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("webhook url 未配置");
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  @DisplayName("webhook 返回 4xx 时异常向上抛")
  void clientError_propagates() {
    server.enqueue(new MockResponse().setResponseCode(400));
    WebhookNotifyAdapter adapter = new WebhookNotifyAdapter(RestClient.builder());
    NotifyTarget target =
        new NotifyTarget("webhook", Map.of("url", server.url("/client-error").toString()));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.send(target, "hello"))
        .isInstanceOf(HttpClientErrorException.class);
  }

  @Test
  @DisplayName("webhook 返回 5xx 时异常向上抛")
  void serverError_propagates() {
    server.enqueue(new MockResponse().setResponseCode(500));
    WebhookNotifyAdapter adapter = new WebhookNotifyAdapter(RestClient.builder());
    NotifyTarget target =
        new NotifyTarget("webhook", Map.of("url", server.url("/server-error").toString()));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.send(target, "hello"))
        .isInstanceOf(HttpServerErrorException.class);
  }

  @Test
  @DisplayName("网络连接中断时异常向上抛")
  void networkError_propagates() {
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
    WebhookNotifyAdapter adapter = new WebhookNotifyAdapter(RestClient.builder());
    NotifyTarget target =
        new NotifyTarget("webhook", Map.of("url", server.url("/disconnect").toString()));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.send(target, "hello"))
        .isInstanceOf(ResourceAccessException.class);
  }

  @Test
  @DisplayName("新增渠道实现不改变统一发送契约")
  void differentAdapters_useSameContract() throws Exception {
    NotifyChannelAdapter webhookAdapter = new WebhookNotifyAdapter(RestClient.builder());
    List<String> delivered = new ArrayList<>();
    NotifyChannelAdapter testLocalAdapter =
        (target, content) -> delivered.add(target.channelType() + ":" + content);

    testLocalAdapter.send(new NotifyTarget("test-local", Map.of()), "same contract");

    assertThat(webhookAdapter).isInstanceOf(NotifyChannelAdapter.class);
    assertThat(delivered).containsExactly("test-local:same contract");
    assertThat(
            NotifyChannelAdapter.class
                .getMethod("send", NotifyTarget.class, String.class)
                .getReturnType())
        .isEqualTo(Void.TYPE);
  }
}
