package com.oryxos.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.oryxos.core.tool.OryxTool;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class ToolSchemaAdapterTest {

  private final ToolSchemaAdapter adapter = new ToolSchemaAdapter();

  @Test
  @DisplayName("OryxTool的schema翻译成Spring AI格式后字段一一对齐")
  void translatesFieldsOneToOne() {
    OryxTool tool =
        stubTool(
            "http_get",
            "Fetch a URL over HTTP GET",
            "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}}}");

    List<ToolDefinition> result = adapter.toSpringAiTools(List.of(tool));

    assertThat(result).hasSize(1);
    ToolDefinition definition = result.get(0);
    assertThat(definition.name()).isEqualTo("http_get");
    assertThat(definition.description()).isEqualTo("Fetch a URL over HTTP GET");
    assertThat(definition.inputSchema())
        .isEqualTo("{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}}}");
  }

  @Test
  @DisplayName("产物只含schema_不含任何执行逻辑")
  void productCarriesNoExecutionLogic() {
    List<ToolDefinition> result = adapter.toSpringAiTools(List.of(stubTool("http_get", "d", "{}")));

    assertThat(result.get(0)).isNotInstanceOf(ToolCallback.class);
  }

  @Test
  void emptyOrNullInput_returnsEmptyList() {
    assertThat(adapter.toSpringAiTools(List.of())).isEmpty();
    assertThat(adapter.toSpringAiTools(null)).isEmpty();
  }

  private static OryxTool stubTool(String name, String description, String schema) {
    return new OryxTool() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public String getDescription() {
        return description;
      }

      @Override
      public String getInputSchema() {
        return schema;
      }
    };
  }
}
