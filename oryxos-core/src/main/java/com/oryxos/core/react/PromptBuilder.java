package com.oryxos.core.react;

import com.oryxos.core.context.ContextLoader;
import com.oryxos.core.profile.Profile;
import com.oryxos.core.prompt.Prompt;
import com.oryxos.core.session.Session;
import com.oryxos.core.tool.OryxTool;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

/**
 * 为每个循环轮组装 prompt,固定四段顺序:系统提示(身份 + Bootstrap/Skill 上下文 + 当前日期时间 —— 模型并不知道「今天」是哪天)→ 长期记忆(预留位,Memory
 * 课接线前 恒为空段)→ 截断后的会话历史(最近 N 条,N = maxHistoryTurns)→ 该 profile 可用的工具.
 *
 * @author OryxOS Contributors
 */
public class PromptBuilder {

  private static final DateTimeFormatter DATE_TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private final ContextLoader contextLoader;

  private final Map<String, OryxTool> toolTable;

  /** 以上下文供应方与工具表(名称 → 工具)创建构建器. */
  public PromptBuilder(ContextLoader contextLoader, Map<String, OryxTool> toolTable) {
    this.contextLoader = contextLoader;
    this.toolTable = Map.copyOf(toolTable);
  }

  /** 由会话历史与 profile 构建本轮 prompt. */
  public Prompt build(Session session, Profile profile) {
    List<Message> messages = new ArrayList<>();
    messages.add(new SystemMessage(buildSystemText(profile)));
    // 长期记忆接入位:22 节 Memory 模块就位后在此注入,本节恒为空段。
    messages.addAll(truncateHistory(session.messages(), profile.settings().maxHistoryTurns()));
    return new Prompt(messages, availableTools(profile));
  }

  private String buildSystemText(Profile profile) {
    StringBuilder system = new StringBuilder();
    if (profile.identity() != null && profile.identity().prompt() != null) {
      system.append(profile.identity().prompt()).append('\n');
    }
    String context = contextLoader.load(profile);
    if (!context.isEmpty()) {
      system.append(context).append('\n');
    }
    system.append("Current date and time: ").append(LocalDateTime.now().format(DATE_TIME_FORMAT));
    return system.toString();
  }

  private static List<Message> truncateHistory(List<Message> messages, int maxHistoryTurns) {
    if (messages.size() <= maxHistoryTurns) {
      return messages;
    }
    // 「轮」按消息条数计(简化口径;CLI 课程完成 Session 完整化时收紧)。
    return messages.subList(messages.size() - maxHistoryTurns, messages.size());
  }

  private List<OryxTool> availableTools(Profile profile) {
    List<OryxTool> tools = new ArrayList<>();
    var declared = new HashSet<String>();
    for (String name : profile.tools()) {
      if (!declared.add(name)) {
        throw new IllegalArgumentException("Profile重复声明工具: " + name);
      }
      OryxTool tool = toolTable.get(name);
      if (tool == null) {
        throw new IllegalArgumentException("Profile声明了未知工具: " + name);
      }
      tools.add(tool);
    }
    return tools;
  }
}
