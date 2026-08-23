package com.oryxos.core.session;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * 一段对话的会话上下文容器:身份加上累积的消息列表(用户输入、模型响应、工具结果). 本节为内存实现;CLI 课会把它升级为 SQLite 持久化({@code sessions} 表已存在于
 * schema.sql)。
 *
 * @author OryxOS Contributors
 */
public class Session {

  private final String id;

  private final String profileName;

  private final List<Message> messages = new ArrayList<>();

  /** 创建绑定到给定 profile 的会话. */
  public Session(String id, String profileName) {
    this.id = id;
    this.profileName = profileName;
  }

  /** 把用户的消息追加进历史. */
  public void append(UserMessage message) {
    messages.add(message);
  }

  /** 把一条模型响应追加进历史(每轮都留痕). */
  public void append(AssistantMessage message) {
    messages.add(message);
  }

  /** 把一条工具执行结果追加进历史. */
  public void appendToolResult(ToolResponseMessage message) {
    messages.add(message);
  }

  /** 返回会话 id,用作审计表的关联键. */
  public String id() {
    return id;
  }

  /** 返回本会话绑定的 profile 名. */
  public String profileName() {
    return profileName;
  }

  /** 返回累积历史的只读视图. */
  public List<Message> messages() {
    return List.copyOf(messages);
  }
}
