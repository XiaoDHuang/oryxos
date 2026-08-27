package com.oryxos.tool.sandbox;

/**
 * 将安全决策与实际外部动作分离，真实白名单在第24节接入.
 *
 * @author OryxOS Contributors
 */
@FunctionalInterface
public interface Sandbox {

  /**
   * 不允许的动作必须抛出异常，使调用方无法继续产生副作用.
   *
   * @param action 即将产生副作用的类型与真实目标
   */
  void enforce(SandboxAction action);
}
