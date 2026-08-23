package com.oryxos.core.prompt;

import com.oryxos.core.tool.OryxTool;
import java.util.List;
import org.springframework.ai.chat.messages.Message;

/**
 * One unit of work handed to the Provider: the messages to send plus the tools available for this
 * turn. Uses Spring AI {@link Message} types directly so the ReAct loop can feed tool results back
 * without a parallel message hierarchy.
 *
 * @author OryxOS Contributors
 */
public record Prompt(List<Message> messages, List<OryxTool> availableTools) {

  /** Canonical constructor normalizing nulls to empty lists. */
  public Prompt {
    messages = messages == null ? List.of() : List.copyOf(messages);
    availableTools = availableTools == null ? List.of() : List.copyOf(availableTools);
  }

  /** Returns the tools available for this turn (courseware-faithful accessor name). */
  public List<OryxTool> getAvailableTools() {
    return availableTools;
  }
}
