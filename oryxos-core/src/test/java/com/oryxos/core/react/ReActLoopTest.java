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

  @Test
  @DisplayName("默认十轮和Profile覆盖不受工具可重试结果改变")
  void iterationLimitIsIndependentOfRetryability() {
    assertThat(new Profile.Settings(null, null).maxIterations()).isEqualTo(10);
    AssistantMessage.ToolCall call =
        new AssistantMessage.ToolCall("c", "function", "http_get", "{}");
    ChatResponse response = responseWithToolCall(call);
    when(llmGateway.chat(any(), any(), any())).thenReturn(response);
    when(toolExecutor.execute(any(), any())).thenReturn(ToolResult.fail("http_get", "重试已耗尽", true));
    String reply = loop.run(session, "查询", profileWithMaxIterations(2));
    assertThat(reply).contains("达到最大轮数");
    verify(llmGateway, times(2)).chat(any(), any(), any());
    verify(toolExecutor, times(2)).execute(any(), any());
  }

  @Test
  @DisplayName("工具结果携带本轮成败元数据_成功为true失败为false")
  void toolResponse_carriesSuccessMetadata() {
    AssistantMessage.ToolCall call =
        new AssistantMessage.ToolCall("c-1", "function", "http_get", "{}");
    ChatResponse withCall = responseWithToolCall(call);
    ChatResponse finalText = responseWithText("收尾");
    when(llmGateway.chat(any(), any(), any())).thenReturn(withCall).thenReturn(finalText);
    when(toolExecutor.execute(any(), any())).thenReturn(ToolResult.ok("http_get", "sunny"));

    loop.run(session, "查天气", profileWithMaxIterations(10));

    ToolResponseMessage toolMessage = onlyToolResponse();
    assertThat(toolMessage.getMetadata()).containsEntry("oryxos.tool.success", true);
  }

  @Test
  @DisplayName("工具失败后模型回了普通文本_失败仍可由元数据识别")
  void toolFailureFollowedByPlainReply_stillClassifiableAsFailure() {
    AssistantMessage.ToolCall call =
        new AssistantMessage.ToolCall("c-1", "function", "http_get", "{}");
    ChatResponse withCall = responseWithToolCall(call);
    ChatResponse finalText = responseWithText("抱歉,没查到");
    when(llmGateway.chat(any(), any(), any())).thenReturn(withCall).thenReturn(finalText);
    when(toolExecutor.execute(any(), any())).thenReturn(ToolResult.fail("http_get", "超时", false));

    String reply = loop.run(session, "查天气", profileWithMaxIterations(10));

    assertThat(reply).isEqualTo("抱歉,没查到");
    assertThat(onlyToolResponse().getMetadata()).containsEntry("oryxos.tool.success", false);
  }

  @Test
  @DisplayName("工具正文以ERROR开头_不误判成败元数据")
  void errorPrefixedContent_doesNotAffectMetadata() {
    AssistantMessage.ToolCall call =
        new AssistantMessage.ToolCall("c-1", "function", "http_get", "{}");
    ChatResponse withCall = responseWithToolCall(call);
    ChatResponse finalText = responseWithText("收尾");
    when(llmGateway.chat(any(), any(), any())).thenReturn(withCall).thenReturn(finalText);
    // 正文内容以 ERROR 开头但调用本身成功:元数据必须按 ToolResult 记 true,不解析文本
    when(toolExecutor.execute(any(), any()))
        .thenReturn(ToolResult.ok("http_get", "ERROR: city not found"));

    loop.run(session, "查天气", profileWithMaxIterations(10));

    assertThat(onlyToolResponse().getMetadata()).containsEntry("oryxos.tool.success", true);
  }

  @Test
  @DisplayName("轮数耗尽时_尾消息是ToolResponse")
  void maxIterationsExhausted_tailMessageIsToolResponse() {
    AssistantMessage.ToolCall call =
        new AssistantMessage.ToolCall("c-1", "function", "http_get", "{}");
    ChatResponse withCall = responseWithToolCall(call);
    when(llmGateway.chat(any(), any(), any())).thenReturn(withCall);
    when(toolExecutor.execute(any(), any())).thenReturn(ToolResult.ok("http_get", "data"));

    String reply = loop.run(session, "查天气", profileWithMaxIterations(1));

    assertThat(reply).contains("达到最大轮数");
    assertThat(session.messages().getLast()).isInstanceOf(ToolResponseMessage.class);
  }

  @Test
  @DisplayName("中断后不再发起新的LLM或Tool调用")
  void interrupt_stopsNewActions() {
    AssistantMessage.ToolCall call =
        new AssistantMessage.ToolCall("c-1", "function", "http_get", "{}");
    ChatResponse withCall = responseWithToolCall(call);
    ChatResponse finalText = responseWithText("不该到达");
    when(llmGateway.chat(any(), any(), any())).thenReturn(withCall).thenReturn(finalText);
    when(toolExecutor.execute(any(), any()))
        .thenAnswer(
            invocation -> {
              // 模拟 watchdog 在工具返回后掐断:循环不得再发起下一轮 LLM
              Thread.currentThread().interrupt();
              return ToolResult.ok("http_get", "data");
            });

    String reply;
    try {
      reply = loop.run(session, "查天气", profileWithMaxIterations(10));
    } finally {
      // 清掉中断标记,不污染同线程后续测试
      Thread.interrupted();
    }

    verify(llmGateway, times(1)).chat(any(), any(), any());
    assertThat(reply).contains("中断");
  }

  @Test
  @DisplayName("起点即中断_零LLM零Tool")
  void interruptedBeforeStart_zeroCalls() {
    Thread.currentThread().interrupt();
    try {
      loop.run(session, "查天气", profileWithMaxIterations(10));
    } finally {
      Thread.interrupted();
    }

    verify(llmGateway, never()).chat(any(), any(), any());
    verify(toolExecutor, never()).execute(any(), any());
  }

  private ToolResponseMessage onlyToolResponse() {
    return (ToolResponseMessage)
        session.messages().stream()
            .filter(message -> message instanceof ToolResponseMessage)
            .findFirst()
            .orElseThrow();
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
