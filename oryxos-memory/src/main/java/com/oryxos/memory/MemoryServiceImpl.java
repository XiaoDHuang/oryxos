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
 * 统一Memory端口的文件式实现.
 *
 * @author OryxOS Contributors
 */
public final class MemoryServiceImpl implements MemoryService {

  private final LongTermMemory longTermMemory;

  /** 绑定长期记忆实现. */
  public MemoryServiceImpl(LongTermMemory longTermMemory) {
    this.longTermMemory = Objects.requireNonNull(longTermMemory, "长期记忆实现不能为空");
  }

  @Override
  public List<Message> buildContext(Session session, int maxHistoryTurns) {
    Objects.requireNonNull(session, "Session不能为空");
    if (maxHistoryTurns < 0) {
      throw new IllegalArgumentException("历史消息上限不能为负数");
    }
    List<Message> context = new ArrayList<>();
    String longTerm = longTermMemory.load();
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
    longTermMemory.append(content, scope == null ? MemoryScope.ARCHIVAL : scope);
  }

  @Override
  public List<String> recall(String keyword) {
    if (keyword == null || keyword.isBlank()) {
      throw new IllegalArgumentException("记忆检索关键词不能为空");
    }
    return longTermMemory.recallByKeyword(keyword);
  }
}
