package com.oryxos.core.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.oryxos.core.tool.OryxTool;
import com.oryxos.core.tool.ToolResult;
import java.util.Map;
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
            isNull(),
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
    assertThat(result.errorMessage()).contains("boom");
    verify(audit)
        .record(
            eq("s-1"),
            isNull(),
            eq("shell"),
            eq("{}"),
            eq(false),
            isNull(),
            contains("boom"),
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
            isNull(),
            eq("no_such_tool"),
            eq("{}"),
            eq(false),
            isNull(),
            contains("no_such_tool"),
            anyLong());
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
