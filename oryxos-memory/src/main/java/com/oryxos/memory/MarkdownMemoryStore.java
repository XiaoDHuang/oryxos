package com.oryxos.memory;

import com.oryxos.core.memory.MemoryScope;
import java.util.List;
import java.util.Objects;

/**
 * 保留文件后端的独立兼容边界.
 *
 * @author OryxOS Contributors
 */
public final class MarkdownMemoryStore implements LongTermMemoryStore {

  private final LongTermMemory memory;

  /** 复用既有解析、路径锁和原子替换,不创建第二份文件状态. */
  public MarkdownMemoryStore(LongTermMemory memory) {
    this.memory = Objects.requireNonNull(memory, "长期记忆实现不能为空");
  }

  @Override
  public void append(String content, MemoryScope scope) {
    memory.append(content, scope);
  }

  @Override
  public String load() {
    return memory.load();
  }

  @Override
  public List<String> recall(String query) {
    return memory.recallByKeyword(query);
  }
}
