package com.oryxos.tool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oryxos.core.tool.OryxTool;
import com.oryxos.tool.builtin.FileTools;
import com.oryxos.tool.builtin.HttpTools;
import com.oryxos.tool.builtin.NotifyTools;
import com.oryxos.tool.builtin.ShellTools;
import com.oryxos.tool.mcp.McpToolAdapter;
import com.oryxos.tool.sandbox.ActionType;
import com.oryxos.tool.sandbox.PermissiveSandbox;
import com.oryxos.tool.sandbox.SandboxAction;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.web.client.RestClient;

@DisplayName("全来源工具遵守同一契约")
class OryxToolContractTest {

  @ParameterizedTest
  @MethodSource("allRegisteredTools")
  @DisplayName("注册表中的每个工具均发布非空元数据和合法Schema")
  void publishesContract(OryxTool tool) {
    assertNotNull(tool.getName());
    assertNotNull(tool.getDescription());
    assertNotNull(tool.getInputSchema());
    assertFalse(tool.getName().isBlank());
    assertFalse(tool.getDescription().isBlank());
    assertFalse(tool.getInputSchema().isBlank());
    assertDoesNotThrow(() -> new ToolArgumentValidator().validateSchema(tool.getInputSchema()));
  }

  static Stream<OryxTool> allRegisteredTools() throws Exception {
    ToolRegistry registry = new ToolRegistry();
    registerBuiltins(registry);
    registry.registerAnnotated(new ContractPlugin());
    var mapper = new ObjectMapper();
    var raw =
        mapper.readTree(
            "{\"name\":\"mcp_contract\",\"description\":\"MCP契约工具\",\"input"
                + "Schema\":{\"type\":\"object\"}}");
    var validator = new ToolArgumentValidator();
    registry.register(
        new McpToolAdapter(
            "contract",
            mock(McpSyncClient.class),
            mapper.treeToValue(raw, McpSchema.Tool.class),
            raw.get("inputSchema").toString(),
            new PermissiveSandbox(),
            new SandboxAction(ActionType.SHELL_EXEC, "java"),
            validator::validateSchema,
            validator::validateArguments,
            Set.of()));
    var tools = registry.all();
    assertFalse(tools.isEmpty());
    var names = new HashSet<String>();
    tools.forEach(tool -> assertTrue(names.add(tool.getName())));
    return tools.stream();
  }

  static void registerBuiltins(ToolRegistry registry) {
    var sandbox = new PermissiveSandbox();
    for (Object bean :
        List.of(
            new FileTools(sandbox),
            new ShellTools(sandbox),
            new HttpTools(sandbox, RestClient.builder()),
            new NotifyTools(sandbox, (target, content) -> {}))) {
      registry.registerAnnotated(bean, () -> {});
    }
  }

  public static class ContractPlugin {
    @Tool(description = "契约测试工具")
    public String contractEcho(String value) {
      return value;
    }
  }
}
