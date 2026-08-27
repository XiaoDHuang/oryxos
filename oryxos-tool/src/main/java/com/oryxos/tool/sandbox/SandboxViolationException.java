package com.oryxos.tool.sandbox;

/**
 * 安全拒绝沿既有工具失败路径审计，不另建安全执行旁路.
 *
 * @author OryxOS Contributors
 */
public class SandboxViolationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** 拒绝保留独立异常类型，交由唯一执行入口转换为失败审计. */
  public SandboxViolationException(String message) {
    super(message);
  }
}
