package com.oryxos.tool.sandbox;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WhitelistSandboxTest {

  @TempDir Path workspace;

  private WhitelistSandbox sandboxWith(
      List<String> paths, List<String> commands, List<String> domains) {
    return new WhitelistSandbox(
        new FileSandboxProperties(paths),
        new ShellSandboxProperties(commands),
        new HttpSandboxProperties(domains));
  }

  @Test
  void allowsFilePathInsideWhitelistedRoot() {
    WhitelistSandbox sandbox = sandboxWith(List.of(workspace.toString()), List.of(), List.of());

    assertThatCode(
            () ->
                sandbox.enforce(
                    new SandboxAction(
                        ActionType.FILE_ACCESS, workspace.resolve("ok/file.txt").toString())))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsFilePathOutsideWhitelistedRoot() {
    WhitelistSandbox sandbox = sandboxWith(List.of(workspace.toString()), List.of(), List.of());
    String sibling = workspace.resolve("..").resolve("workspace-evil.txt").normalize().toString();

    assertThatThrownBy(() -> sandbox.enforce(new SandboxAction(ActionType.FILE_ACCESS, sibling)))
        .isInstanceOf(SandboxViolationException.class)
        .hasMessageContaining("路径不在白名单内");
  }

  @Test
  @DisplayName("相对路径穿越必须被拦")
  void relativePathTraversalMustBeBlocked() {
    // 课件关键回归：白名单只有工作区，构造 .. 序列爬到白名单之外
    WhitelistSandbox sandbox = sandboxWith(List.of(workspace.toString()), List.of(), List.of());
    String traversal = workspace.resolve("../../outside/secret.txt").toString();

    assertThatThrownBy(() -> sandbox.enforce(new SandboxAction(ActionType.FILE_ACCESS, traversal)))
        .isInstanceOf(SandboxViolationException.class)
        .hasMessageContaining("路径不在白名单内");
  }

  @Test
  void rejectsLookalikeSiblingDirectoryPrefix() {
    // /workspace-evil 以 /workspace 为字符串前缀但不是其子路径，目录边界比较不得放行
    WhitelistSandbox sandbox = sandboxWith(List.of(workspace.toString()), List.of(), List.of());
    String lookalike =
        workspace.resolve("..").resolve(workspace.getFileName() + "-evil").normalize().toString();

    assertThatThrownBy(() -> sandbox.enforce(new SandboxAction(ActionType.FILE_ACCESS, lookalike)))
        .isInstanceOf(SandboxViolationException.class);
  }

  @Test
  void allowsShellCommandWhoseFirstTokenIsWhitelisted() {
    WhitelistSandbox sandbox = sandboxWith(List.of(), List.of("ls", "cat"), List.of());

    assertThatCode(() -> sandbox.enforce(new SandboxAction(ActionType.SHELL_EXEC, "ls -la")))
        .doesNotThrowAnyException();
    assertThatCode(() -> sandbox.enforce(new SandboxAction(ActionType.SHELL_EXEC, "cat a.txt")))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsShellCommandOutsideWhitelist() {
    WhitelistSandbox sandbox = sandboxWith(List.of(), List.of("ls"), List.of());

    assertThatThrownBy(() -> sandbox.enforce(new SandboxAction(ActionType.SHELL_EXEC, "rm -rf x")))
        .isInstanceOf(SandboxViolationException.class)
        .hasMessageContaining("命令不在白名单内")
        .hasMessageContaining("rm");
  }

  @Test
  void shellFirstTokenTrimsLeadingWhitespaceAndStaysCaseSensitive() {
    WhitelistSandbox sandbox = sandboxWith(List.of(), List.of("ls"), List.of());

    assertThatCode(() -> sandbox.enforce(new SandboxAction(ActionType.SHELL_EXEC, "   ls -la")))
        .doesNotThrowAnyException();
    // 命令名大小写敏感，与文件系统语义一致，不做归一化
    assertThatThrownBy(() -> sandbox.enforce(new SandboxAction(ActionType.SHELL_EXEC, "LS")))
        .isInstanceOf(SandboxViolationException.class);
  }

  @Test
  void allowsHttpRequestToExactWhitelistedDomain() {
    WhitelistSandbox sandbox = sandboxWith(List.of(), List.of(), List.of("example.com"));

    assertThatCode(
            () ->
                sandbox.enforce(
                    new SandboxAction(ActionType.HTTP_REQUEST, "https://example.com/path")))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsHttpRequestOutsideWhitelist() {
    WhitelistSandbox sandbox = sandboxWith(List.of(), List.of(), List.of("example.com"));

    assertThatThrownBy(
            () ->
                sandbox.enforce(
                    new SandboxAction(ActionType.HTTP_REQUEST, "https://other.invalid/path")))
        .isInstanceOf(SandboxViolationException.class);
  }

  @Test
  @DisplayName("通配符域名_不能被形似域名绕过")
  void wildcardDomainsMustNotBeBypassedByLookalikes() {
    // 课件关键回归；008 FR-011 决议为精确匹配不引入通配符：
    // 白名单 example.com 命中自身，evil-example.com 与子域 api.example.com 均不得命中
    WhitelistSandbox sandbox = sandboxWith(List.of(), List.of(), List.of("example.com"));

    assertThatCode(
            () ->
                sandbox.enforce(
                    new SandboxAction(ActionType.HTTP_REQUEST, "https://example.com/x")))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                sandbox.enforce(
                    new SandboxAction(ActionType.HTTP_REQUEST, "https://evil-example.com/x")))
        .isInstanceOf(SandboxViolationException.class);
    assertThatThrownBy(
            () ->
                sandbox.enforce(
                    new SandboxAction(ActionType.HTTP_REQUEST, "https://api.example.com/x")))
        .isInstanceOf(SandboxViolationException.class);
  }

  @Test
  void emptyWhitelistsDenyEveryActionType() {
    WhitelistSandbox sandbox = sandboxWith(List.of(), List.of(), List.of());

    assertThatThrownBy(
            () ->
                sandbox.enforce(
                    new SandboxAction(
                        ActionType.FILE_ACCESS, workspace.resolve("a.txt").toString())))
        .isInstanceOf(SandboxViolationException.class);
    assertThatThrownBy(() -> sandbox.enforce(new SandboxAction(ActionType.SHELL_EXEC, "ls")))
        .isInstanceOf(SandboxViolationException.class);
    assertThatThrownBy(
            () ->
                sandbox.enforce(
                    new SandboxAction(ActionType.HTTP_REQUEST, "https://example.com/x")))
        .isInstanceOf(SandboxViolationException.class);
  }

  @Test
  void nullWhitelistPropertiesAreTreatedAsEmpty() {
    WhitelistSandbox sandbox =
        new WhitelistSandbox(
            new FileSandboxProperties(null),
            new ShellSandboxProperties(null),
            new HttpSandboxProperties(null));

    assertThatThrownBy(() -> sandbox.enforce(new SandboxAction(ActionType.SHELL_EXEC, "ls")))
        .isInstanceOf(SandboxViolationException.class);
  }

  @Test
  void rejectsInvalidWhitelistedPathAtConstruction() {
    // 非法配置项必须启动期失败，不得静默放行或静默丢弃
    assertThatThrownBy(
            () ->
                sandboxWith(List.of(workspace.toString() + "\u0000illegal"), List.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsInvalidHttpDomainThroughDelegate() {
    // HTTP名单的"坏项拒绝整份配置"由007既有实现承载，委托不得吞掉该启动期失败
    assertThatThrownBy(() -> sandboxWith(List.of(), List.of(), List.of("*.example.com")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> sandboxWith(List.of(), List.of(), List.of("exa mple.com")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
