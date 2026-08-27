package com.oryxos.tool.sandbox;

/**
 * 不携带策略实现细节，使工具只需声明即将触及的外部目标.
 *
 * @author OryxOS Contributors
 */
public record SandboxAction(ActionType type, String target) {

  /** 缺少动作信息不能交给后续安全策略猜测. */
  public SandboxAction {
    if (type == null) {
      throw new IllegalArgumentException("安全动作类型不能为空");
    }
    if (target == null || target.isBlank()) {
      throw new IllegalArgumentException("安全动作目标不能为空");
    }
  }
}
