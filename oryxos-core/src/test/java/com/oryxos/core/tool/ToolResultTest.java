package com.oryxos.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ToolResultTest {

  @Test
  @DisplayName("四参构造与既有成功失败工厂保持兼容")
  void preservesLegacyConstructorsAndFactories() {
    ToolResult legacy = new ToolResult("read_file", true, "内容", null);
    assertThat(legacy).isEqualTo(ToolResult.ok("read_file", "内容"));
    assertThat(ToolResult.fail("read_file", "读取失败").success()).isFalse();
    assertThat(ToolResult.fail("read_file", "读取失败").errorMessage()).isEqualTo("读取失败");
  }

  @Test
  @DisplayName("统一工具仍以JSON文本发布Schema和接收参数")
  void preservesStringJsonInterface() throws Exception {
    assertThat(OryxTool.class.getMethod("getInputSchema").getReturnType()).isEqualTo(String.class);
    assertThat(OryxTool.class.getMethod("execute", String.class).getReturnType())
        .isEqualTo(ToolResult.class);
    assertThat(OryxTool.class.getDeclaredMethods()).hasSize(4);
  }

  @Test
  @DisplayName("结果在已有四个字段之后提供可重试标记")
  void exposesRetryableWithoutRemovingLegacyFields() {
    assertThat(ToolResult.class.getRecordComponents())
        .extracting(RecordComponent::getName)
        .containsExactly("toolName", "success", "content", "errorMessage", "retryable");
  }

  @Test
  @DisplayName("旧成功与失败工厂默认不可重试")
  void legacyResultsAreNotRetryable() throws Exception {
    assertThat(retryable(ToolResult.ok("read_file", "内容"))).isFalse();
    assertThat(retryable(ToolResult.fail("read_file", "失败"))).isFalse();
  }

  @Test
  @DisplayName("瞬态失败工厂保留可重试语义")
  void preservesExplicitRetryableFailure() throws Exception {
    ToolResult result =
        (ToolResult)
            ToolResult.class
                .getMethod("fail", String.class, String.class, boolean.class)
                .invoke(null, "http_get", "暂时不可用", true);
    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).isEqualTo("暂时不可用");
    assertThat(retryable(result)).isTrue();
  }

  @Test
  @DisplayName("成功结果不得同时要求重试")
  void rejectsRetryableSuccess() {
    assertThatThrownBy(
            () ->
                ToolResult.class
                    .getConstructor(
                        String.class, boolean.class, String.class, String.class, boolean.class)
                    .newInstance("read_file", true, "内容", null, true))
        .isInstanceOf(InvocationTargetException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("空成功输出和缺失失败消息都有明确含义")
  void normalizesMissingPayloads() {
    assertThat(ToolResult.ok("read_file", null).content()).isEmpty();
    assertThat(ToolResult.fail("read_file", null).errorMessage()).isNotBlank();
  }

  private static boolean retryable(ToolResult result) throws Exception {
    return (boolean) ToolResult.class.getMethod("retryable").invoke(result);
  }
}
