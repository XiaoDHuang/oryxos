package com.oryxos.provider;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Schema-only {@link ToolCallback} shell. Spring AI transports tool schemas to the model through
 * the {@code ToolCallback} interface, so definitions are wrapped for transport — but execution is
 * owned by the ToolExecutor, so {@link #call(String)} is a tripwire that throws rather than a real
 * execution path. With internal tool execution disabled on the request, it is never invoked.
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
        "Tool execution is owned by ToolExecutor; Provider only translates schema for '"
            + toolDefinition.name()
            + "'");
  }
}
