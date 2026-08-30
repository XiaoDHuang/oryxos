package com.oryxos.memory;

import com.oryxos.core.memory.MemoryScope;
import com.oryxos.core.memory.MemoryService;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Agent主动保存和回忆长期记忆的两个内置工具.
 *
 * @author OryxOS Contributors
 */
public final class MemoryTools {

  private static final String CORE_SCOPE = "core";
  private static final String ARCHIVAL_SCOPE = "archival";

  private final MemoryService memoryService;

  /** 绑定唯一Memory端口. */
  public MemoryTools(MemoryService memoryService) {
    this.memoryService = Objects.requireNonNull(memoryService, "MemoryService不能为空");
  }

  /** 保存一条长期记忆. */
  @Tool(name = "save_memory", description = "记住一件值得长期记住的事")
  public String saveMemory(
      @ToolParam(description = "要记住的内容", required = true) String content,
      @ToolParam(description = "core或archival,不确定可省略", required = false) String scope) {
    memoryService.remember(content, parseScope(scope));
    return "已记住";
  }

  /** 按关键词回忆归档记忆. */
  @Tool(name = "recall_memory", description = "按关键词检索长期记忆")
  public String recallMemory(@ToolParam(description = "检索关键词", required = true) String keyword) {
    List<String> hits = memoryService.recall(keyword);
    return hits.isEmpty() ? "没有找到相关记忆" : String.join("\n", hits);
  }

  private static MemoryScope parseScope(String scope) {
    if (scope == null || scope.isBlank()) {
      return MemoryScope.ARCHIVAL;
    }
    String normalized = asciiLowercase(scope.trim());
    if (CORE_SCOPE.equals(normalized)) {
      return MemoryScope.CORE;
    }
    if (ARCHIVAL_SCOPE.equals(normalized)) {
      return MemoryScope.ARCHIVAL;
    }
    throw new IllegalArgumentException("记忆scope只允许core或archival");
  }

  private static String asciiLowercase(String value) {
    StringBuilder normalized = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      normalized.append(
          current >= 'A' && current <= 'Z' ? (char) (current + ('a' - 'A')) : current);
    }
    return normalized.toString();
  }
}
