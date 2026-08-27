package com.oryxos.tool.builtin;

import com.oryxos.core.profile.Profile;
import com.oryxos.core.react.ProfileContext;
import com.oryxos.core.tool.ToolResult;
import com.oryxos.tool.notify.NotifyChannelAdapter;
import com.oryxos.tool.notify.NotifyTarget;
import com.oryxos.tool.sandbox.ActionType;
import com.oryxos.tool.sandbox.Sandbox;
import com.oryxos.tool.sandbox.SandboxAction;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 模型只能选择Profile声明的通知渠道，不能自行指定目标地址.
 *
 * @author OryxOS Contributors
 */
public final class NotifyTools {
  private static final String CHANNEL_TYPE = "type";
  private static final String WEBHOOK_TYPE = "webhook";
  private final Sandbox sandbox;
  private final NotifyChannelAdapter adapter;

  /** 目标授权与协议发送分开接线，保持通知渠道契约不变. */
  public NotifyTools(Sandbox sandbox, NotifyChannelAdapter adapter) {
    this.sandbox = Objects.requireNonNull(sandbox);
    this.adapter = Objects.requireNonNull(adapter);
  }

  /** 目标只能来自当前Profile，不接受模型直接指定通知地址. */
  @Tool(name = "notify", description = "向Profile配置的通知渠道发送消息")
  public ToolResult notify(
      @ToolParam(description = "非空通知内容") String content,
      @ToolParam(required = false, description = "渠道类型，缺省选择首个配置") String channel) {
    if (content == null || content.isBlank()) {
      return ToolResult.fail("notify", "通知内容不能为空");
    }
    NotifyTarget target;
    try {
      target = resolveTarget(channel);
    } catch (IllegalArgumentException exception) {
      return ToolResult.fail("notify", exception.getMessage());
    }
    sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, target.config().get("url")));
    try {
      adapter.send(target, content);
      return ToolResult.ok("notify", "通知已发送");
    } catch (RuntimeException exception) {
      // 即使返回错误，远端也可能已收取通知，因此不能自动重放。
      return ToolResult.fail("notify", "通知发送失败，未自动重试");
    }
  }

  private static NotifyTarget resolveTarget(String channel) {
    Profile profile = ProfileContext.current();
    if (profile == null || profile.notifyChannels().isEmpty()) {
      throw new IllegalArgumentException("当前Profile未配置通知渠道");
    }
    Map<String, Object> configuration;
    if (channel == null || channel.isBlank()) {
      configuration = profile.notifyChannels().getFirst();
    } else {
      List<Map<String, Object>> matches =
          profile.notifyChannels().stream()
              .filter(item -> channel.equals(item.get("type")))
              .toList();
      if (matches.size() != 1) {
        throw new IllegalArgumentException("通知渠道必须唯一匹配");
      }
      configuration = matches.getFirst();
    }
    if (!WEBHOOK_TYPE.equals(configuration.get(CHANNEL_TYPE))) {
      throw new IllegalArgumentException("通知渠道类型不受支持");
    }
    Object value = configuration.get("url");
    if (!(value instanceof String url)) {
      throw new IllegalArgumentException("通知渠道地址未配置");
    }
    try {
      HttpTools.validateUrl(url);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("通知渠道地址无效或环境占位符未解析");
    }
    return new NotifyTarget("webhook", Map.of("url", url));
  }
}
