package com.oryxos.tool.notify;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 核心阶段的通用 webhook 通知实现. 这里只负责协议发送;目标选择和 Sandbox 校验由后续 NotifyTools 调用链负责.
 *
 * @author OryxOS Contributors
 */
@Component
public class WebhookNotifyAdapter implements NotifyChannelAdapter {
  private static final Pattern WEB_SCHEME = Pattern.compile("https?", Pattern.CASE_INSENSITIVE);
  private static final String WEBHOOK_TYPE = "webhook";
  private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

  private final RestClient restClient;

  /** Boot 默认只提供 Builder Bean,在构造时固化客户端以保持 Adapter 无状态. */
  public WebhookNotifyAdapter(RestClient.Builder restClientBuilder) {
    SimpleClientHttpRequestFactory factory =
        new SimpleClientHttpRequestFactory() {
          @Override
          protected void prepareConnection(HttpURLConnection connection, String method)
              throws IOException {
            super.prepareConnection(connection, method);
            // 通知目标只允许已选定的一项，禁止底层悄悄转发到其他地址。
            connection.setInstanceFollowRedirects(false);
          }
        };
    factory.setConnectTimeout(Duration.ofSeconds(5));
    factory.setReadTimeout(Duration.ofSeconds(30));
    this.restClient = restClientBuilder.clone().requestFactory(factory).build();
  }

  @Override
  public void send(NotifyTarget target, String content) {
    if (target == null) {
      throw new IllegalArgumentException("通知目标不能为空");
    }
    String url = target.config().get("url");
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("webhook url 未配置");
    }
    URI uri;
    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("通知地址无效");
    }
    boolean supportedScheme =
        uri.getScheme() != null && WEB_SCHEME.matcher(uri.getScheme()).matches();
    boolean safeAuthority = uri.getHost() != null && uri.getRawUserInfo() == null;
    if (!supportedScheme || !safeAuthority) {
      throw new IllegalArgumentException("通知地址必须是无凭证的HTTP或HTTPS地址");
    }
    if (content == null || content.isBlank() || !WEBHOOK_TYPE.equals(target.channelType())) {
      throw new IllegalArgumentException("通知内容或渠道类型无效");
    }
    restClient
        .post()
        .uri(uri)
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("content", content))
        .exchange(
            (request, response) -> {
              var status = response.getStatusCode();
              if (status.is4xxClientError()) {
                throw new HttpClientErrorException(response.getStatusCode(), "通知请求失败");
              }
              if (status.is5xxServerError()) {
                throw new HttpServerErrorException(response.getStatusCode(), "通知服务失败");
              }
              if (!status.is2xxSuccessful()) {
                throw new RestClientException("通知请求拒绝重定向或非成功状态");
              }
              if (response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1).length
                  > MAX_RESPONSE_BYTES) {
                throw new RestClientException("通知响应超过1MiB上限");
              }
              return null;
            });
  }
}
