package com.oryxos.memory;

import java.net.URI;

/**
 * 记忆模块声明每次出站前的窄检查，由Boot组合真实Sandbox且不得提供默认空实现.
 *
 * @author OryxOS Contributors
 */
@FunctionalInterface
public interface MemoryOutboundGuard {

  /**
   * 最终目标不获许可时必须在任何网络I/O前抛出SecurityException.
   *
   * @param target 即将访问的最终HTTPS目标
   */
  void check(URI target);
}
