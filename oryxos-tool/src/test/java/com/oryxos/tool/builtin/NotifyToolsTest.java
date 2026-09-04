package com.oryxos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.oryxos.core.profile.Profile;
import com.oryxos.core.react.ProfileContext;
import com.oryxos.tool.notify.NotifyChannelAdapter;
import com.oryxos.tool.notify.NotifyTarget;
import com.oryxos.tool.sandbox.ActionType;
import com.oryxos.tool.sandbox.FileSandboxProperties;
import com.oryxos.tool.sandbox.HttpSandboxProperties;
import com.oryxos.tool.sandbox.Sandbox;
import com.oryxos.tool.sandbox.SandboxAction;
import com.oryxos.tool.sandbox.SandboxViolationException;
import com.oryxos.tool.sandbox.ShellSandboxProperties;
import com.oryxos.tool.sandbox.WhitelistSandbox;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("通知工具的Profile目标解析")
class NotifyToolsTest {
  private final Sandbox sandbox = mock(Sandbox.class);
  private final NotifyChannelAdapter adapter = mock(NotifyChannelAdapter.class);
  private final NotifyTools tools = new NotifyTools(sandbox, adapter);

  @AfterEach
  void clearContext() {
    ProfileContext.clear();
  }

  @Test
  @DisplayName("缺省只选首目标且先安全检查再发送一次")
  void usesFirstAndEnforcesBeforeSend() {
    profile(List.of(target("http://localhost/secret-token"), target("http://localhost/other")));
    var result = tools.notify("消息", null);
    assertTrue(result.success());
    assertFalse(result.content().contains("secret-token"));
    var order = inOrder(sandbox, adapter);
    order
        .verify(sandbox)
        .enforce(new SandboxAction(ActionType.HTTP_REQUEST, "http://localhost/secret-token"));
    order
        .verify(adapter)
        .send(new NotifyTarget("webhook", Map.of("url", "http://localhost/secret-token")), "消息");
    order.verifyNoMoreInteractions();
  }

  @Test
  @DisplayName("显式类型必须唯一匹配而不能默默广播")
  void selectsUniqueType() {
    profile(
        List.of(
            Map.of("type", "unsupported", "url", "http://localhost/x"),
            target("http://localhost/ok")));
    assertTrue(tools.notify("内容", "webhook").success());
    verify(adapter).send(new NotifyTarget("webhook", Map.of("url", "http://localhost/ok")), "内容");
    reset(adapter, sandbox);
    profile(List.of(target("http://localhost/a"), target("http://localhost/b")));
    assertFalse(tools.notify("内容", "webhook").success());
    assertFalse(tools.notify("内容", "missing").success());
    verifyNoInteractions(adapter, sandbox);
  }

  @Test
  @DisplayName("缺Profile空配置非法目标与空内容均零发送")
  void rejectsInvalidConfigurations() {
    assertFalse(tools.notify("内容", null).success());
    profile(List.of());
    assertFalse(tools.notify("内容", null).success());
    for (Map<String, Object> config :
        List.of(
            Map.<String, Object>of(),
            target(""),
            target("${MISSING}"),
            target("file:///tmp/x"),
            target("http://name:secret@localhost/x"),
            Map.<String, Object>of("type", "other", "url", "http://localhost/x"))) {
      profile(List.of(config));
      assertFalse(tools.notify("内容", null).success());
    }
    profile(List.of(target("http://localhost/x")));
    assertFalse(tools.notify(" ", null).success());
    verifyNoInteractions(adapter, sandbox);
  }

  @Test
  @DisplayName("安全拒绝不发送并透传异常")
  void deniesBeforeSend() {
    profile(List.of(target("http://localhost/x")));
    doThrow(new SandboxViolationException("禁止通知")).when(sandbox).enforce(any());
    assertThrows(SandboxViolationException.class, () -> tools.notify("内容", null));
    verifyNoInteractions(adapter);
  }

  @Test
  @DisplayName("真实白名单外webhook被拦且渠道零发送")
  void whitelistDeniesWebhookWithoutSending() {
    WhitelistSandbox whitelist =
        new WhitelistSandbox(
            new FileSandboxProperties(List.of()),
            new ShellSandboxProperties(List.of()),
            new HttpSandboxProperties(List.of("api.example.com")));
    NotifyTools guarded = new NotifyTools(whitelist, adapter);
    profile(List.of(target("http://localhost/webhook")));
    assertThrows(SandboxViolationException.class, () -> guarded.notify("内容", null));
    verifyNoInteractions(adapter);
  }

  @Test
  @DisplayName("不确定发送失败不重试也不回显配置秘密")
  void sanitizesDeliveryFailure() {
    profile(List.of(target("http://localhost/secret-token")));
    doThrow(new IllegalStateException("http://localhost/secret-token"))
        .when(adapter)
        .send(any(), any());
    var result = tools.notify("内容", "webhook");
    assertFalse(result.success());
    assertFalse(result.retryable());
    assertFalse(result.errorMessage().contains("secret-token"));
    verify(adapter).send(any(), any());
  }

  private static Map<String, Object> target(String url) {
    return Map.of("type", "webhook", "url", url);
  }

  private static void profile(List<Map<String, Object>> targets) {
    ProfileContext.set(
        new Profile(
            "test", null, null, null, null, null, null, null, targets, null, null, null, null,
            null));
  }
}
