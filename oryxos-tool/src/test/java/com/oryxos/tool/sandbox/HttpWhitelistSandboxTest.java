package com.oryxos.tool.sandbox;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class HttpWhitelistSandboxTest {

  @Test
  void allowsOnlyExactCanonicalHostsForHttpRequests() {
    HttpWhitelistSandbox sandbox =
        new HttpWhitelistSandbox(List.of("Example.COM.", "例子.测试", "127.0.0.1", "0:0:0:0:0:0:0:1"));

    assertThatCode(
            () ->
                sandbox.enforce(
                    new SandboxAction(
                        ActionType.HTTP_REQUEST, "HTTPS://example.com:8443/path?q=value")))
        .doesNotThrowAnyException();
    assertThatCode(
            () -> sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "https://例子.测试/path")))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                sandbox.enforce(
                    new SandboxAction(ActionType.HTTP_REQUEST, "http://127.0.0.1:8080/path")))
        .doesNotThrowAnyException();
    assertThatCode(
            () -> sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "https://[::1]/path")))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsSuffixesSubdomainsAndLookalikeAuthorities() {
    HttpWhitelistSandbox sandbox = new HttpWhitelistSandbox(List.of("example.com"));

    for (String target :
        List.of(
            "https://example.com.attacker.invalid",
            "https://sub.example.com",
            "https://example.com@attacker.invalid",
            "https://attacker.invalid/example.com",
            "https://example.com.evil",
            "http://127.0.0.01/path")) {
      assertThatThrownBy(() -> sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, target)))
          .isInstanceOf(SandboxViolationException.class)
          .hasMessage("HTTP目标不在允许列表中");
    }
  }

  @Test
  void rejectsMalformedTargetsNonHttpActionsAndEmptyLists() {
    HttpWhitelistSandbox sandbox = new HttpWhitelistSandbox(List.of("example.com"));
    for (SandboxAction action :
        List.of(
            new SandboxAction(ActionType.HTTP_REQUEST, "ftp://example.com/file"),
            new SandboxAction(ActionType.HTTP_REQUEST, "https:///missing-host"),
            new SandboxAction(ActionType.HTTP_REQUEST, "https://example.com:99999/path"),
            new SandboxAction(ActionType.HTTP_REQUEST, "https://example.com/path#fragment"),
            new SandboxAction(ActionType.FILE_ACCESS, "/tmp/file"),
            new SandboxAction(ActionType.SHELL_EXEC, "curl https://example.com"))) {
      assertThatThrownBy(() -> sandbox.enforce(action))
          .isInstanceOf(SandboxViolationException.class);
    }
    assertThatThrownBy(() -> sandbox.enforce(null)).isInstanceOf(SandboxViolationException.class);
    assertThatThrownBy(
            () ->
                new HttpWhitelistSandbox(List.of())
                    .enforce(
                        new SandboxAction(ActionType.HTTP_REQUEST, "https://example.com/path")))
        .isInstanceOf(SandboxViolationException.class);
  }

  @Test
  void rejectsInvalidOrDuplicateConfigurationAsOneUnit() {
    for (List<String> domains :
        List.of(
            List.of(""),
            List.of("*.example.com"),
            List.of(".example.com"),
            List.of("example.com/path"),
            List.of("https://example.com"),
            List.of("example.com:443"),
            List.of("example.com", "EXAMPLE.COM."),
            List.of("例子.测试", "xn--fsqu00a.xn--0zwm56d"),
            List.of("127.0.0.01"),
            List.of("fe80::1%12"))) {
      assertThatThrownBy(() -> new HttpWhitelistSandbox(domains))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("HTTP允许域名配置无效");
    }
    assertThatThrownBy(() -> new HttpWhitelistSandbox(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("HTTP允许域名配置无效");
  }
}
