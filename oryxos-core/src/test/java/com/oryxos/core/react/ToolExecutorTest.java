package com.oryxos.core.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.oryxos.core.profile.Profile;
import com.oryxos.core.tool.OryxTool;
import com.oryxos.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

class ToolExecutorTest {

  private ToolInvocationAudit audit;
  private ToolExecutor toolExecutor;

  @BeforeEach
  void setUp() {
    audit = mock(ToolInvocationAudit.class);
    ProfileContext.set(profile(List.of("http_get", "shell", "no_such_tool")));
  }

  @AfterEach
  void clearContext() {
    ProfileContext.clear();
    Thread.interrupted();
  }

  @Test
  @DisplayName("成功_审计写success为true")
  void success_auditsSuccessTrue() {
    toolExecutor = new ToolExecutor(Map.of("http_get", stubTool("http_get", null)), audit);
    AssistantMessage.ToolCall call =
        new AssistantMessage.ToolCall("c-1", "function", "http_get", "{\"url\":\"x\"}");

    ToolResult result = toolExecutor.execute("s-1", call);

    assertThat(result.success()).isTrue();
    assertThat(result.content()).isEqualTo("stub-output");
    verify(audit)
        .record(
            eq("s-1"),
            eq("test"),
            eq("http_get"),
            eq("{\"url\":\"x\"}"),
            eq(true),
            eq("stub-output"),
            isNull(),
            anyLong());
  }

  @Test
  @DisplayName("失败_审计写success为false带原因且异常不吞")
  void failure_auditsSuccessFalseWithReason() {
    toolExecutor =
        new ToolExecutor(Map.of("shell", stubTool("shell", new RuntimeException("boom"))), audit);
    AssistantMessage.ToolCall call =
        new AssistantMessage.ToolCall("c-2", "function", "shell", "{}");

    ToolResult result = toolExecutor.execute("s-1", call);

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("工具执行失败").doesNotContain("boom");
    verify(audit)
        .record(
            eq("s-1"),
            eq("test"),
            eq("shell"),
            eq("{}"),
            eq(false),
            isNull(),
            contains("工具执行失败"),
            anyLong());
  }

  @Test
  @DisplayName("未知工具_走失败路径不崩循环")
  void unknownTool_failsGracefully() {
    toolExecutor = new ToolExecutor(Map.of(), audit);
    AssistantMessage.ToolCall call =
        new AssistantMessage.ToolCall("c-3", "function", "no_such_tool", "{}");

    ToolResult result = toolExecutor.execute("s-1", call);

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("no_such_tool");
    verify(audit)
        .record(
            eq("s-1"),
            eq("test"),
            eq("no_such_tool"),
            eq("{}"),
            eq(false),
            isNull(),
            contains("no_such_tool"),
            anyLong());
  }

  @Test
  @DisplayName("返回失败不能被当成成功审计")
  void auditsReturnedFailure() {
    OryxTool tool = mock(OryxTool.class);
    when(tool.execute("{}")).thenReturn(ToolResult.fail("shell", "执行失败"));
    ToolExecutor executor = new ToolExecutor(Map.of("shell", tool), audit);
    assertThat(executor.execute("s", call("shell")).success()).isFalse();
    verify(audit)
        .record(
            eq("s"), eq("test"), eq("shell"), eq("{}"), eq(false), isNull(), eq("执行失败"), anyLong());
    verifyNoMoreInteractions(audit);
  }

  @Test
  @DisplayName("首次加三次重试耗尽仅审计一次且总耗时包含退避")
  void exhaustsThreeRetries() {
    OryxTool tool = mock(OryxTool.class);
    when(tool.execute("{}")).thenReturn(ToolResult.fail("http_get", "瞬态故障", true));
    List<Long> waits = new ArrayList<>();
    AtomicLong clock = new AtomicLong();
    ToolExecutor executor =
        new ToolExecutor(
            Map.of("http_get", tool),
            audit,
            millis -> {
              waits.add(millis);
              clock.addAndGet(millis * 1_000_000);
            },
            clock::get);
    var result = executor.execute("s", call("http_get"));
    assertThat(result.success()).isFalse();
    assertThat(result.retryable()).isTrue();
    assertThat(waits).containsExactly(100L, 200L, 400L);
    verify(tool, times(4)).execute("{}");
    verify(audit)
        .record(
            eq("s"),
            eq("test"),
            eq("http_get"),
            eq("{}"),
            eq(false),
            isNull(),
            eq("瞬态故障"),
            eq(700L));
    verifyNoMoreInteractions(audit);
  }

  @Test
  @DisplayName("重试成功立即停止并记录最终成功")
  void stopsAfterRecovery() {
    OryxTool tool = mock(OryxTool.class);
    when(tool.execute("{}"))
        .thenReturn(ToolResult.fail("http_get", "瞬态故障", true), ToolResult.ok("http_get", "恢复"));
    List<Long> waits = new ArrayList<>();
    var executor = new ToolExecutor(Map.of("http_get", tool), audit, waits::add, () -> 0L);
    assertThat(executor.execute("s", call("http_get")).content()).isEqualTo("恢复");
    assertThat(waits).containsExactly(100L);
    verify(tool, times(2)).execute("{}");
    verify(audit)
        .record(
            eq("s"), eq("test"), eq("http_get"), eq("{}"), eq(true), eq("恢复"), isNull(), eq(0L));
    verifyNoMoreInteractions(audit);
  }

  @Test
  @DisplayName("不可重试失败和未知异常都不进入退避")
  void neverGuessesRetryability() {
    OryxTool tool = mock(OryxTool.class);
    when(tool.execute("{}"))
        .thenReturn(ToolResult.fail("shell", "失败"))
        .thenThrow(new IllegalStateException("secret-token"));
    AtomicInteger waits = new AtomicInteger();
    var executor =
        new ToolExecutor(
            Map.of("shell", tool), audit, ignored -> waits.incrementAndGet(), () -> 0L);
    assertThat(executor.execute("s", call("shell")).retryable()).isFalse();
    assertThat(executor.execute("s", call("shell")).errorMessage()).doesNotContain("secret-token");
    assertThat(waits.get()).isZero();
    verify(tool, times(2)).execute("{}");
  }

  @Test
  @DisplayName("缺Profile或未授权调用零执行仍有失败审计")
  void deniesBeforeExecution() {
    OryxTool tool = mock(OryxTool.class);
    var executor = new ToolExecutor(Map.of("shell", tool), audit);
    ProfileContext.clear();
    assertThat(executor.execute("s", call("shell")).success()).isFalse();
    ProfileContext.set(profile(List.of()));
    assertThat(executor.execute("s", call("shell")).success()).isFalse();
    org.mockito.Mockito.verifyNoInteractions(tool);
    verify(audit, times(2))
        .record(eq("s"), any(), eq("shell"), eq("{}"), eq(false), isNull(), any(), anyLong());
  }

  @Test
  @DisplayName("预先中断零执行且退避中断保留标志")
  void respectsCancellation() {
    OryxTool tool = mock(OryxTool.class);
    when(tool.execute("{}")).thenReturn(ToolResult.fail("http_get", "瞬态故障", true));
    var executor =
        new ToolExecutor(
            Map.of("http_get", tool),
            audit,
            ignored -> Thread.currentThread().interrupt(),
            () -> 0L);
    Thread.currentThread().interrupt();
    assertThat(executor.execute("s", call("http_get")).success()).isFalse();
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
    org.mockito.Mockito.verifyNoInteractions(tool);
    Thread.interrupted();
    assertThat(executor.execute("s", call("http_get")).success()).isFalse();
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
    verify(tool).execute("{}");
    verify(audit, times(2))
        .record(
            eq("s"),
            eq("test"),
            eq("http_get"),
            eq("{}"),
            eq(false),
            isNull(),
            contains("中断"),
            anyLong());
  }

  @Test
  @DisplayName("工具返回已验证的不可重试失败后发生中断仍保留错误分类和编号")
  void preservesValidatedFailureWhenToolInterruptsAfterSideEffect() {
    String detail =
        "MEMORY_OUTCOME_UNKNOWN：记忆保存结果不确定，请勿重复保存；operationId="
            + "11111111-1111-4111-8111-111111111111";
    OryxTool tool = mock(OryxTool.class);
    when(tool.execute("{}"))
        .thenAnswer(
            ignored -> {
              Thread.currentThread().interrupt();
              return ToolResult.fail("shell", detail, false);
            });
    try {
      ToolResult result =
          new ToolExecutor(Map.of("shell", tool), audit).execute("s", call("shell"));
      assertThat(result.success()).isFalse();
      assertThat(result.retryable()).isFalse();
      assertThat(result.errorMessage()).isEqualTo(detail);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      verify(tool).execute("{}");
      verify(audit)
          .record(
              eq("s"),
              eq("test"),
              eq("shell"),
              eq("{}"),
              eq(false),
              isNull(),
              eq(detail),
              anyLong());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("审计故障不重放已执行工具且不伪造第二次审计")
  void auditFailureDoesNotReplay() {
    OryxTool tool = mock(OryxTool.class);
    when(tool.execute("{}")).thenReturn(ToolResult.ok("shell", "已执行"));
    doThrow(new IllegalStateException("数据库不可用"))
        .when(audit)
        .record(any(), any(), any(), any(), anyBoolean(), any(), any(), anyLong());
    var result = new ToolExecutor(Map.of("shell", tool), audit).execute("s", call("shell"));
    assertThat(result.content()).isEqualTo("已执行");
    verify(tool).execute("{}");
    verify(audit).record(any(), any(), any(), any(), anyBoolean(), any(), any(), anyLong());
    verifyNoMoreInteractions(audit);
  }

  @Test
  @DisplayName("空结果和错误工具名转不可重试失败")
  void rejectsMalformedResults() {
    OryxTool tool = mock(OryxTool.class);
    when(tool.execute("{}")).thenReturn(null, ToolResult.ok("wrong", ""));
    var executor = new ToolExecutor(Map.of("shell", tool), audit);
    for (int index = 0; index < 2; index++) {
      var result = executor.execute("s", call("shell"));
      assertThat(result.success()).isFalse();
      assertThat(result.retryable()).isFalse();
      assertThat(result.toolName()).isEqualTo("shell");
    }
    verify(audit, times(2))
        .record(any(), any(), any(), any(), eq(false), isNull(), any(), anyLong());
  }

  private static Profile profile(List<String> tools) {
    return new Profile(
        "test", null, null, null, tools, null, null, null, null, null, null, null, null, null);
  }

  private static AssistantMessage.ToolCall call(String name) {
    return new AssistantMessage.ToolCall("call", "function", name, "{}");
  }

  private static OryxTool stubTool(String name, RuntimeException throwOnExecute) {
    return new OryxTool() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public String getDescription() {
        return "stub";
      }

      @Override
      public String getInputSchema() {
        return "{}";
      }

      @Override
      public ToolResult execute(String argumentsJson) {
        if (throwOnExecute != null) {
          throw throwOnExecute;
        }
        return ToolResult.ok(name, "stub-output");
      }
    };
  }
}
