package com.oryxos.tool.notify;

import java.util.Map;
import java.util.Objects;

/**
 * 渠道中立的通知目标. config 在构造时复制,避免调用方事后修改凭证或地址导致一次发送的目标漂移。
 *
 * @param channelType 渠道类型
 * @param config 渠道专属配置
 * @author OryxOS Contributors
 */
public record NotifyTarget(String channelType, Map<String, String> config) {

  /** 校验中立字段;url 等专属必填键由对应 Adapter 校验. */
  public NotifyTarget {
    Objects.requireNonNull(channelType, "通知渠道类型不能为空");
    if (channelType.isBlank()) {
      throw new IllegalArgumentException("通知渠道类型不能为空白");
    }
    config = Map.copyOf(Objects.requireNonNull(config, "通知目标配置不能为空"));
  }
}
