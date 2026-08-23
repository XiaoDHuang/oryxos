package com.oryxos.provider;

import com.oryxos.core.profile.Profile;
import com.oryxos.core.prompt.Prompt;
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
 * The front desk between Agents and LLMs: pick the right model by the Profile's provider name,
 * issue one call, hand the response back untouched. Tool schemas are translated and attached, but
 * the model's tool-call intents are returned to the caller unexecuted — execution belongs to the
 * ReAct loop's ToolExecutor. Every call, success or failure, is audited to {@code llm_calls}.
 *
 * @author OryxOS Contributors
 */
@Service
public class ProviderService {

  private final Map<String, ChatModel> chatModelRegistry;

  private final ToolSchemaAdapter toolSchemaAdapter;

  private final LlmCallAudit llmCallAudit;

  /** Creates the service with its explicit model registry, schema adapter and audit writer. */
  public ProviderService(
      Map<String, ChatModel> chatModelRegistry,
      ToolSchemaAdapter toolSchemaAdapter,
      LlmCallAudit llmCallAudit) {
    this.chatModelRegistry = Map.copyOf(chatModelRegistry);
    this.toolSchemaAdapter = toolSchemaAdapter;
    this.llmCallAudit = llmCallAudit;
  }

  /**
   * Issues one LLM call for the given profile/prompt. Unknown provider names throw {@link
   * ProviderNotFoundException}; provider failures are rethrown to the caller (no fallback in the
   * core stage) after the failure audit has been persisted.
   */
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification =
          "Courseware contract: failed provider calls rethrow the original RuntimeException to the"
              + " caller after the failure audit lands — no fallback in the core stage.")
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
    // OpenAI-compatible providers only in the core stage, so the options type matches every
    // ChatModel this build produces.
    OpenAiChatOptions.Builder optionsBuilder =
        OpenAiChatOptions.builder()
            // Pitfall #2: Spring AI's internal tool execution must stay off — execution is owned
            // by ToolExecutor; otherwise tools would run twice and bypass the sandbox.
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
