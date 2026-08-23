package com.oryxos.provider;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 只载 schema 的 {@link ToolCallback} 壳. 因为 Spring AI 通过 {@code ToolCallback} 接口把工具 schema
 * 运给模型,所以为传输而包装定义 —— 但执行归 ToolExecutor 所有,因此 {@link #call(String)}
 * 是绊线而非真实执行路径:被调到就抛异常。请求上关闭了内部工具执行,它 永远不会被调用。
 *
 * @author OryxOS Contributors
 */
final class SchemaOnlyToolCallback implements ToolCallback {

  private final ToolDefinition toolDefinition;

  SchemaOnlyToolCallback(ToolDefinition toolDefinition) {
    this.toolDefinition = toolDefinition;
  }

  @Override
  public ToolDefinition getToolDefinition() {
    return toolDefinition;
  }

  @Override
  public String call(String toolInput) {
    throw new IllegalStateException(
        "工具执行归 ToolExecutor 所有;Provider 只为 '" + toolDefinition.name() + "' 翻译 schema");
  }
}
