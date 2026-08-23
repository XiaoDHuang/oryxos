package com.oryxos.provider;

import com.oryxos.core.tool.OryxTool;
import java.util.List;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

/**
 * 把我们的 {@link OryxTool} 描述翻译成 Spring AI 的 {@link ToolDefinition}. 只做翻译 —— 产物携带 schema,绝不携带执行;工具执行属于
 * ToolExecutor(Tool 能力课)。
 *
 * @author OryxOS Contributors
 */
@Component
public class ToolSchemaAdapter {

  /** 把每个工具转成只载 schema 的 {@link ToolDefinition};入参为空/null 则出参为空. */
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
