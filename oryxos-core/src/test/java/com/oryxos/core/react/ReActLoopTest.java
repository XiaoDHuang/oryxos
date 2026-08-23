package com.oryxos.core.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.oryxos.core.profile.Profile;
import com.oryxos.core.prompt.Prompt;
import com.oryxos.core.session.Session;
import com.oryxos.core.tool.ToolResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class ReActLoopTest {

  private LlmGateway llmGateway;
  private PromptBuilder promptBuilder;
  private ToolExecutor toolExecutor;
  private ReActLoop loop;
  private Session session;

  @BeforeEach
  void setUp() {
    llmGateway = mock(LlmGateway.class);
    promptBuilder = mock(PromptBuilder.class);
    toolExecutor = mock(ToolExecutor.class);
    loop = new ReActLoop(llmGateway, promptBuilder, toolExecutor);
    session = new Session("s-1", "ops");
    when(promptBuilder.build(any(), any()))
        .thenReturn(new Prompt(List.of(new UserMessage("查天气")), List.of()));
  }

  @Test
  @DisplayName("无工具调用_一轮收尾")
  void noToolCall_finishesInOneRound() {
    when(llmGateway.chat(any(), any(), any())).thenReturn(responseWithText("今天晴,穿短袖"));

    String reply = loop.run(session, "查天气", profileWithMaxIterations(10));

    assertThat(reply).isEqualTo("今天晴,穿短袖");
    verify(llmGateway, times(1)).chat(any(), any(), any());
    verify(toolExecutor, never()).execute(any(), any());
  }

  @Test
  @DisplayName("有工具调用_执行并回填进下一轮")
  void toolCall_executedAndFedBack() {
    AssistantMessage.ToolCall call =
        new AssistantMessage.ToolCall("c-1", "function", "http_get", "{}");
    ChatResponse withCall = responseWithToolCall(call);
    ChatResponse finalText = responseWithText("根据天气建议穿短袖");
    when(llmGateway.chat(any(), any(), any())).thenReturn(withCall).thenReturn(finalText);
    when(toolExecutor.execute(any(), any())).thenReturn(ToolResult.ok("http_get", "sunny 30C"));

    String reply = loop.run(session, "查天气", profileWithMaxIterations(10));

    assertThat(reply).isEqualTo("根据天气建议穿短袖");
    verify(toolExecutor, times(1)).execute(eq("s-1"), eq(call));
    assertThat(session.messages()).anyMatch(message -> message instanceof ToolResponseMessage);
  }

  @Test
  @DisplayName("单轮多个工具调用_按顺序执行")
  void multipleToolCalls_executedInOrder() {
    AssistantMessage.ToolCall first =
        new AssistantMessage.ToolCall("c-1", "function", "http_get", "{}");
    AssistantMessage.ToolCall second =
        new AssistantMessage.ToolCall("c-2", "function", "read_file", "{}");
    ChatResponse withCalls = responseWithToolCalls(List.of(first, second));
    ChatResponse finalText = responseWithText("done");
    when(llmGateway.chat(any(), any(), any())).thenReturn(withCalls).thenReturn(finalText);
    when(toolExecutor.execute(any(), any())).thenReturn(ToolResult.ok("x", "y"));

    loop.run(session, "任务", profileWithMaxIterations(10));

    InOrder inOrder = inOrder(toolExecutor);
    inOrder.verify(toolExecutor).execute(any(), eq(first));
    inOrder.verify(toolExecutor).execute(any(), eq(second));
  }

  @Test
  @DisplayName("模型一直要调工具_转满最大轮数强制停")
  void modelAlwaysCallsTools_forcedStopAtMaxIterations() {
    AssistantMessage.ToolCall call =
        new AssistantMessage.ToolCall("c-1", "function", "http_get", "{}");
    ChatResponse alwaysCall = responseWithToolCall(call);
    when(llmGateway.chat(any(), any(), any())).thenReturn(alwaysCall);
    when(toolExecutor.execute(any(), any())).thenReturn(ToolResult.ok("http_get", "data"));

    String reply = loop.run(session, "查天气", profileWithMaxIterations(10));

    verify(llmGateway, times(10)).chat(any(), any(), any());
    assertThat(reply).contains("达到最大轮数");
  }

  @Test
  @DisplayName("每轮响应和工具结果都累积进Session")
  void everyRound_accumulatesIntoSession() {
    AssistantMessage.ToolCall call =
        new AssistantMessage.ToolCall("c-1", "function", "http_get", "{}");
    ChatResponse withCall = responseWithToolCall(call);
    ChatResponse finalText = responseWithText("final");
    when(llmGateway.chat(any(), any(), any())).thenReturn(withCall).thenReturn(finalText);
    when(toolExecutor.execute(any(), any())).thenReturn(ToolResult.ok("http_get", "sunny"));

    loop.run(session, "查天气", profileWithMaxIterations(10));

    List<Message> messages = session.messages();
    assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
    assertThat(messages.stream().filter(message -> message instanceof AssistantMessage).count())
        .isEqualTo(2);
    assertThat(messages.stream().filter(message -> message instanceof ToolResponseMessage).count())
        .isEqualTo(1);
  }

  private static Profile profileWithMaxIterations(int maxIterations) {
    return new Profile(
        "ops",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        new Profile.Settings(maxIterations, 20),
        null,
        null);
  }

  private static ChatResponse responseWithText(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }

  private static ChatResponse responseWithToolCall(AssistantMessage.ToolCall call) {
    return responseWithToolCalls(List.of(call));
  }

  private static ChatResponse responseWithToolCalls(List<AssistantMessage.ToolCall> calls) {
    AssistantMessage message = mock(AssistantMessage.class);
    when(message.hasToolCalls()).thenReturn(true);
    when(message.getToolCalls()).thenReturn(calls);
    return new ChatResponse(List.of(new Generation(message)));
  }
}
