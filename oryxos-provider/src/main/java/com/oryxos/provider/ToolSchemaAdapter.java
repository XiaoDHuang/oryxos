package com.oryxos.provider;

import com.oryxos.core.tool.OryxTool;
import java.util.List;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

/**
 * Translates our {@link OryxTool} descriptors into Spring AI {@link ToolDefinition}s. Translation
 * only — the product carries schema, never execution; tool execution belongs to the ToolExecutor
 * (Tool capability lesson).
 *
 * @author OryxOS Contributors
 */
@Component
public class ToolSchemaAdapter {

  /** Converts each tool to a schema-only {@link ToolDefinition}; empty/null in, empty out. */
  public List<ToolDefinition> toSpringAiTools(List<OryxTool> tools) {
    if (tools == null || tools.isEmpty()) {
      return List.of();
    }
    return tools.stream()
        .map(
            tool ->
                DefaultToolDefinition.builder()
                    .name(tool.getName())
                    .description(tool.getDescription())
                    .inputSchema(tool.getInputSchema())
                    .build())
        .toList();
  }
}
