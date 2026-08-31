package com.oryxos.memory;

import com.oryxos.core.memory.MemoryScope;
import com.oryxos.core.memory.MemoryService;
import com.oryxos.core.session.Session;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

/**
 * 存储差异由适配器承接,保持上下文与工具入口不变.
 *
 * @author OryxOS Contributors
 */
public final class MemoryServiceImpl implements MemoryService {

  private final LongTermMemoryStore store;

  /** 绑定长期记忆实现. */
  public MemoryServiceImpl(LongTermMemory longTermMemory) {
    this(new MarkdownMemoryStore(longTermMemory));
  }

  /** 绑定启动时唯一选定的存储,不在调用失败时切换后端. */
  public MemoryServiceImpl(LongTermMemoryStore store) {
    this.store = Objects.requireNonNull(store, "长期记忆实现不能为空");
  }

  @Override
  public List<Message> buildContext(Session session, int maxHistoryTurns) {
    Objects.requireNonNull(session, "Session不能为空");
    if (maxHistoryTurns < 0) {
      throw new IllegalArgumentException("历史消息上限不能为负数");
    }
    List<Message> context = new ArrayList<>();
    String longTerm = store.load();
    if (!longTerm.isBlank()) {
      context.add(new SystemMessage(longTerm));
    }
    List<Message> history = session.messages();
    int first = Math.max(0, history.size() - maxHistoryTurns);
    context.addAll(history.subList(first, history.size()));
    return List.copyOf(context);
  }

  @Override
  public void remember(String content, MemoryScope scope) {
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("记忆内容不能为空");
    }
    store.append(content, scope == null ? MemoryScope.ARCHIVAL : scope);
  }

  @Override
  public List<String> recall(String keyword) {
    if (keyword == null || keyword.isBlank()) {
      throw new IllegalArgumentException("记忆检索关键词不能为空");
    }
    return store.recall(keyword);
  }
}
