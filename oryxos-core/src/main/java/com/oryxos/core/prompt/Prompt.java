package com.oryxos.core.prompt;

import com.oryxos.core.tool.OryxTool;
import java.util.List;
import org.springframework.ai.chat.messages.Message;

/**
 * 交给 Provider 的一个工作单元:本轮要发送的消息,加上本轮可用的工具. 直接使用 Spring AI 的 {@link Message} 类型,让 ReAct
 * 循环无需维护平行的消息体系即可把工具结果喂回去。
 *
 * @author OryxOS Contributors
 */
public record Prompt(List<Message> messages, List<OryxTool> availableTools) {

  /** 把 null 归一为空列表的规范构造器. */
  public Prompt {
    messages = messages == null ? List.of() : List.copyOf(messages);
    availableTools = availableTools == null ? List.of() : List.copyOf(availableTools);
  }

  /** 返回本轮可用的工具(访问器名贴合课件). */
  public List<OryxTool> getAvailableTools() {
    return availableTools;
  }
}
