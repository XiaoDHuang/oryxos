package com.oryxos.tool.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SandboxContractTest {

  @Test
  @DisplayName("安全动作只包含技术方案确定的三类")
  void exposesOnlyTheThreeApprovedActions() {
    assertThat(ActionType.values())
        .containsExactly(ActionType.FILE_ACCESS, ActionType.SHELL_EXEC, ActionType.HTTP_REQUEST);
  }

  @Test
  @DisplayName("安全动作保留类型与原始目标")
  void preservesActionValues() {
    SandboxAction action = new SandboxAction(ActionType.SHELL_EXEC, "echo hello");
    assertThat(action.type()).isEqualTo(ActionType.SHELL_EXEC);
    assertThat(action.target()).isEqualTo("echo hello");
  }

  @Test
  @DisplayName("安全动作拒绝缺失类型和空白目标")
  void rejectsIncompleteActions() {
    assertThatThrownBy(() -> new SandboxAction(null, "file"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SandboxAction(ActionType.FILE_ACCESS, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SandboxAction(ActionType.FILE_ACCESS, " \t"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("安全拒绝在副作用之前终止执行")
  void rejectsBeforeSideEffects() {
    AtomicBoolean invoked = new AtomicBoolean();
    Sandbox denying =
        action -> {
          throw new SandboxViolationException("安全动作已拒绝");
        };
    assertThatThrownBy(
            () -> {
              denying.enforce(
                  new SandboxAction(ActionType.HTTP_REQUEST, "https://blocked.invalid"));
              invoked.set(true);
            })
        .isInstanceOf(SandboxViolationException.class)
        .hasMessage("安全动作已拒绝");
    assertThat(invoked).isFalse();
  }

  @Test
  @DisplayName("只有显式注入的测试实现允许测试动作")
  void permitsExplicitTestFixture() {
    Sandbox sandbox = new PermissiveSandbox();
    assertThatCode(() -> sandbox.enforce(new SandboxAction(ActionType.FILE_ACCESS, "test-file")))
        .doesNotThrowAnyException();
  }
}
