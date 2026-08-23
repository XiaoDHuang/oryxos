package com.oryxos.provider;

import com.oryxos.core.profile.Profile;
import com.oryxos.core.prompt.Prompt;
import com.oryxos.core.react.LlmGateway;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;

/**
 * Agent 与 LLM 之间的前台:按 Profile 的 provider 名挑对模型、发起一次调用、把响应原样 交还. 工具的 schema
 * 会被翻译并附上,但模型的工具调用意图原样返回给调用方、不在此执行 —— 执行属于 ReAct 循环的 ToolExecutor。每次调用无论成败都审计进 {@code llm_calls}。
 *
 * @author OryxOS Contributors
 */
@Service
public class ProviderService implements LlmGateway {

  private final Map<String, ChatModel> chatModelRegistry;

  private final ToolSchemaAdapter toolSchemaAdapter;

  private final LlmCallAudit llmCallAudit;

  /** 以显式模型注册表、schema 适配器与审计写入器创建服务. */
  public ProviderService(
      Map<String, ChatModel> chatModelRegistry,
      ToolSchemaAdapter toolSchemaAdapter,
      LlmCallAudit llmCallAudit) {
    this.chatModelRegistry = Map.copyOf(chatModelRegistry);
    this.toolSchemaAdapter = toolSchemaAdapter;
    this.llmCallAudit = llmCallAudit;
  }

  /**
   * 为给定的 profile/prompt 发起一次 LLM 调用. 未知的 provider 名抛 {@link ProviderNotFoundException};provider
   * 调用失败在失败审计落库后原样重抛给调用方 (核心阶段不做 fallback)。
   */
  @Override
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification =
          "课件契约:provider 调用失败在失败审计落库后,把原始 RuntimeException 重抛给" + "调用方 —— 核心阶段不做 fallback。")
  public ChatResponse chat(String sessionId, Profile profile, Prompt prompt) {
    String providerName = profile.provider().name();
    ChatModel model = chatModelRegistry.get(providerName);
    if (model == null) {
      throw new ProviderNotFoundException(providerName);
    }
    List<ToolDefinition> toolDefinitions =
        toolSchemaAdapter.toSpringAiTools(prompt.getAvailableTools());
    long startedAt = System.currentTimeMillis();
    try {
      ChatResponse response = model.call(buildSpringPrompt(profile, prompt, toolDefinitions));
      Usage usage = response.getMetadata().getUsage();
      llmCallAudit.record(
          sessionId,
          providerName,
          profile.provider().model(),
          usage,
          true,
          null,
          System.currentTimeMillis() - startedAt);
      return response;
    } catch (RuntimeException e) {
      llmCallAudit.record(
          sessionId,
          providerName,
          profile.provider().model(),
          null,
          false,
          e.getMessage(),
          System.currentTimeMillis() - startedAt);
      throw e;
    }
  }

  private static org.springframework.ai.chat.prompt.Prompt buildSpringPrompt(
      Profile profile, Prompt prompt, List<ToolDefinition> toolDefinitions) {
    // 核心阶段只接 OpenAI 兼容 provider,因此 options 类型与本构建产出的每个 ChatModel 匹配。
    OpenAiChatOptions.Builder optionsBuilder =
        OpenAiChatOptions.builder()
            // 坑 #2:Spring AI 的内部工具执行必须关闭 —— 执行归 ToolExecutor 所有;
            // 否则工具会跑两次并绕过沙箱。
            .internalToolExecutionEnabled(false);
    if (profile.provider().model() != null) {
      optionsBuilder.model(profile.provider().model());
    }
    if (profile.provider().temperature() != null) {
      optionsBuilder.temperature(profile.provider().temperature());
    }
    if (!toolDefinitions.isEmpty()) {
      optionsBuilder.toolCallbacks(
          toolDefinitions.stream().<ToolCallback>map(SchemaOnlyToolCallback::new).toList());
    }
    return new org.springframework.ai.chat.prompt.Prompt(prompt.messages(), optionsBuilder.build());
  }
}
