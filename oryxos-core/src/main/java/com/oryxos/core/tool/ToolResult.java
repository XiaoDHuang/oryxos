package com.oryxos.core.tool;

/**
 * 一次工具执行的结果,由 {@link OryxTool#execute(String)} 产出、被 ReAct 循环消费: 包装回会话成为工具消息,并镜像写入 {@code
 * tool_invocations} 审计表.重试标记仅表达失败是否适合安全重放，调度仍由唯一执行器控制.
 *
 * @author OryxOS Contributors
 */
public record ToolResult(
    String toolName, boolean success, String content, String errorMessage, boolean retryable) {

  /** 成功与失败不能同时成立，否则执行器无法可靠决定审计状态和是否重试. */
  public ToolResult {
    boolean invalidSuccess = retryable || errorMessage != null;
    if (success && invalidSuccess) {
      throw new IllegalArgumentException("成功结果不能携带错误或要求重试");
    }
    if (success && content == null) {
      content = "";
    }
    boolean missingError = errorMessage == null || errorMessage.isBlank();
    if (!success && missingError) {
      errorMessage = "工具执行失败";
    }
  }

  /** 保留前序调用方的构造方式，旧结果不得隐式获得重试权限. */
  public ToolResult(String toolName, boolean success, String content, String errorMessage) {
    this(toolName, success, content, errorMessage, false);
  }

  /** 创建携带工具输出内容的成功结果. */
  public static ToolResult ok(String toolName, String content) {
    return new ToolResult(toolName, true, content, null);
  }

  /** 创建携带人类可读失败原因的失败结果. */
  public static ToolResult fail(String toolName, String errorMessage) {
    return fail(toolName, errorMessage, false);
  }

  /** 只有工具实现确认失败可安全重放后，才能显式设置重试标记. */
  public static ToolResult fail(String toolName, String errorMessage, boolean retryable) {
    return new ToolResult(toolName, false, null, errorMessage, retryable);
  }
}
