package com.oryxos.tool.notify;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 核心阶段的通用 webhook 通知实现. 这里只负责协议发送;目标选择和 Sandbox 校验由后续 NotifyTools 调用链负责。
 *
 * @author OryxOS Contributors
 */
@Component
public class WebhookNotifyAdapter implements NotifyChannelAdapter {

  private final RestClient restClient;

  /** Boot 默认只提供 Builder Bean,在构造时固化客户端以保持 Adapter 无状态. */
  public WebhookNotifyAdapter(RestClient.Builder restClientBuilder) {
    this.restClient = restClientBuilder.build();
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
    restClient
        .post()
        .uri(url)
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("content", content))
        .retrieve()
        .toBodilessEntity();
  }
}
