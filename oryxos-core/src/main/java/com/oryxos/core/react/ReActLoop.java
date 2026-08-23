package com.oryxos.core.react;

import com.oryxos.core.profile.Profile;
import com.oryxos.core.prompt.Prompt;
import com.oryxos.core.session.Session;
import com.oryxos.core.tool.ToolResult;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Agent 的核心循环 —— 刻意只有几十行,只做调度:每轮组装 prompt、经 provider 端口发起 一次 LLM
 * 调用、把响应累积进会话,然后要么结束(无工具调用意图),要么按顺序执行被请求 的工具并把结果喂回去. 轮数上限(默认 10,可被 profile 覆盖)是死循环保险丝;执行本身 住在
 * {@link ToolExecutor},绝不在这里,也绝不在框架里。
 *
 * @author OryxOS Contributors
 */
public class ReActLoop {

  private final LlmGateway llmGateway;

  private final PromptBuilder promptBuilder;

  private final ToolExecutor toolExecutor;

  /** 以三个协作者创建循环. */
  public ReActLoop(LlmGateway llmGateway, PromptBuilder promptBuilder, ToolExecutor toolExecutor) {
    this.llmGateway = llmGateway;
    this.promptBuilder = promptBuilder;
    this.toolExecutor = toolExecutor;
  }

  /** 为一条用户消息运行循环,返回最终回复文本. */
  public String run(Session session, String userMessage, Profile profile) {
    session.append(new UserMessage(userMessage));
    int maxIterations = profile.settings().maxIterations();
    for (int i = 0; i < maxIterations; i++) {
      Prompt prompt = promptBuilder.build(session, profile);
      // sessionId 随行,使 llm_calls 审计行能关联到本会话。
      ChatResponse response = llmGateway.chat(session.id(), profile, prompt);
      AssistantMessage assistant = response.getResult().getOutput();
      session.append(assistant);
      if (!assistant.hasToolCalls()) {
        return assistant.getText();
      }
      for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
        // sessionId 同样随行,使 tool_invocations 能关联到本会话。
        ToolResult result = toolExecutor.execute(session.id(), call);
        session.appendToolResult(toToolResponseMessage(call, result));
      }
    }
    return "达到最大轮数,已停止";
  }

  private static ToolResponseMessage toToolResponseMessage(
      AssistantMessage.ToolCall call, ToolResult result) {
    String content = result.success() ? result.content() : "ERROR: " + result.errorMessage();
    return ToolResponseMessage.builder()
        .responses(List.of(new ToolResponseMessage.ToolResponse(call.id(), call.name(), content)))
        .build();
  }
}
