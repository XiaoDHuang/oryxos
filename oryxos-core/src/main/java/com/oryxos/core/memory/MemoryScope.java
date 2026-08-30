package com.oryxos.core.memory;

/**
 * 长期记忆的稳定分区契约.
 *
 * @author OryxOS Contributors
 */
public enum MemoryScope {
  /** 每轮完整注入且永不受归档截断影响. */
  CORE,

  /** 按需检索并仅在Prompt视图中保留最近内容. */
  ARCHIVAL
}
