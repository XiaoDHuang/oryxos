package com.oryxos.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.oryxos.core.profile.Profile;
import com.oryxos.core.prompt.Prompt;
import com.oryxos.core.tool.OryxTool;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

class ProviderServiceTest {

  private ChatModel deepseek;
  private ChatModel kimi;
  private LlmCallAudit audit;
  private ProviderService service;

  @BeforeEach
  void setUp() {
    deepseek = mock(ChatModel.class);
    kimi = mock(ChatModel.class);
    audit = mock(LlmCallAudit.class);
    service =
        new ProviderService(
            Map.of("deepseek", deepseek, "kimi", kimi), new ToolSchemaAdapter(), audit);
  }

  @Test
  @DisplayName("按名路由_两个provider不串台")
  void routesToNamedProvider_twoProvidersNoCrossTalk() {
    ChatResponse kimiResponse = chatResponseWithUsage();
    when(kimi.call(anyPrompt())).thenReturn(kimiResponse);

    service.chat("s-1", profileUsing("kimi"), textPrompt());

    verify(kimi, times(1)).call(anyPrompt());
    verify(deepseek, never()).call(anyPrompt());
    verify(audit)
        .record(eq("s-1"), eq("kimi"), eq("kimi-chat"), any(), eq(true), isNull(), anyLong());
  }

  @Test
  @DisplayName("引用未知供应商_抛异常且消息带供应商名")
  void unknownProvider_throwsNamedException() {
    assertThatThrownBy(() -> service.chat("s-1", profileUsing("qwen"), textPrompt()))
        .isInstanceOf(ProviderNotFoundException.class)
        .hasMessageContaining("qwen");
    verifyNoInteractions(deepseek, kimi);
  }

  @Test
  @DisplayName("调用失败_审计必须留下success为false的记录")
  void callFails_auditSavedBeforeRethrow() {
    when(deepseek.call(anyPrompt())).thenThrow(new RuntimeException("connect timeout"));

    assertThatThrownBy(() -> service.chat("s-1", profileUsing("deepseek"), textPrompt()))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("connect timeout");
    verify(audit)
        .record(
            eq("s-1"), eq("deepseek"), any(), isNull(), eq(false), contains("timeout"), anyLong());
  }

  @Test
  @DisplayName("带工具schema调用_请求里关闭了自动执行")
  void callWithToolSchema_disablesAutoExecution() {
    ChatResponse stubbedResponse = chatResponseWithUsage();
    when(deepseek.call(anyPrompt())).thenReturn(stubbedResponse);

    service.chat("s-1", profileUsing("deepseek"), promptWithTools(stubTool("http_get")));

    ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> captor =
        ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
    verify(deepseek).call(captor.capture());
    ChatOptions options = captor.getValue().getOptions();
    assertThat(options).isInstanceOf(ToolCallingChatOptions.class);
    ToolCallingChatOptions toolOptions = (ToolCallingChatOptions) options;
    assertThat(toolOptions.getInternalToolExecutionEnabled()).isFalse();
    assertThat(toolOptions.getToolCallbacks()).isNotEmpty();
  }

  @Test
  @DisplayName("成功调用_审计落success为true且token用量齐全")
  void callSucceeds_auditsUsageAndLatency() {
    ChatResponse stubbedResponse = chatResponseWithUsage();
    when(deepseek.call(anyPrompt())).thenReturn(stubbedResponse);

    service.chat("s-1", profileUsing("deepseek"), textPrompt());

    verify(audit)
        .record(
            eq("s-1"),
            eq("deepseek"),
            eq("deepseek-chat"),
            any(Usage.class),
            eq(true),
            isNull(),
            anyLong());
  }

  private static org.springframework.ai.chat.prompt.Prompt anyPrompt() {
    return any(org.springframework.ai.chat.prompt.Prompt.class);
  }

  private static Profile profileUsing(String providerName) {
    return new Profile(
        "ops-agent",
        null,
        null,
        new Profile.Provider(providerName, providerName + "-chat", 0.7, null),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static Prompt textPrompt() {
    return new Prompt(List.of(new UserMessage("hello")), List.of());
  }

  private static Prompt promptWithTools(OryxTool tool) {
    return new Prompt(List.of(new UserMessage("hello")), List.of(tool));
  }

  private static OryxTool stubTool(String name) {
    return new OryxTool() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public String getDescription() {
        return "stub tool";
      }

      @Override
      public String getInputSchema() {
        return "{\"type\":\"object\"}";
      }
    };
  }

  private static ChatResponse chatResponseWithUsage() {
    Usage usage = mock(Usage.class);
    when(usage.getPromptTokens()).thenReturn(12);
    when(usage.getCompletionTokens()).thenReturn(5);
    when(usage.getTotalTokens()).thenReturn(17);
    ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
    when(metadata.getUsage()).thenReturn(usage);
    ChatResponse response = mock(ChatResponse.class);
    when(response.getMetadata()).thenReturn(metadata);
    return response;
  }
}
