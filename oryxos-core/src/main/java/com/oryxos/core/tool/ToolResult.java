package com.oryxos.core.tool;

/**
 * 一次工具执行的结果,由 {@link OryxTool#execute(String)} 产出、被 ReAct 循环消费: 包装回会话成为工具消息,并镜像写入 {@code
 * tool_invocations} 审计表. 完整的 Tool 能力课会扩展这个形状。
 *
 * @author OryxOS Contributors
 */
public record ToolResult(String toolName, boolean success, String content, String errorMessage) {

  /** 创建携带工具输出内容的成功结果. */
  public static ToolResult ok(String toolName, String content) {
    return new ToolResult(toolName, true, content, null);
  }

  /** 创建携带人类可读失败原因的失败结果. */
  public static ToolResult fail(String toolName, String errorMessage) {
    return new ToolResult(toolName, false, null, errorMessage);
  }
}
