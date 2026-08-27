package com.oryxos.tool.sandbox;

import java.util.Objects;

/**
 * 仅在测试中显式放行动作，生产产物不得携带这个实现.
 *
 * @author OryxOS Contributors
 */
public final class PermissiveSandbox implements Sandbox {

  @Override
  public void enforce(SandboxAction action) {
    Objects.requireNonNull(action, "安全动作不能为空");
  }
}
