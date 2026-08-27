package com.oryxos.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oryxos.core.profile.Profile;
import com.oryxos.core.react.ProfileContext;
import com.oryxos.tool.sandbox.ActionType;
import com.oryxos.tool.sandbox.Sandbox;
import com.oryxos.tool.sandbox.SandboxAction;
import com.oryxos.tool.sandbox.SandboxViolationException;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("MCP统一工具适配")
class McpToolAdapterTest {
  private final McpSyncClient client = mock(McpSyncClient.class);
  private final Sandbox sandbox = mock(Sandbox.class);

  @AfterEach
  void clearContext() {
    ProfileContext.clear();
  }

  @Test
  @DisplayName("只发布原始Schema且真实组合约束阻止非法参数")
  void preservesSchemaAndEnforcesConstraints() throws Exception {
    var adapter = adapter();
    assertEquals("business_lookup", adapter.getName());
    assertEquals("本地查询", adapter.getDescription());
    assertEquals(
        new ObjectMapper().readTree(SchemaPreservingMcpJsonMapperTest.FULL_SCHEMA),
        new ObjectMapper().readTree(adapter.getInputSchema()));
    profile(List.of("business_lookup"), List.of("business"));
    assertFalse(adapter.execute("{\"value\":\"x\"}").success());
    assertFalse(adapter.execute("{\"value\":\"你好\",\"extra\":\"not-number\"}").success());
    verifyNoInteractions(client, sandbox);
  }

  @Test
  @DisplayName("参数原样转发且安全检查早于RPC")
  void forwardsOnlyAfterAuthorization() throws Exception {
    var adapter = adapter();
    profile(List.of("business_lookup"), List.of("business"));
    when(client.callTool(any(McpSchema.CallToolRequest.class)))
        .thenReturn(
            result(
                "{\"content\":[{\"type\":\"text\",\"text\":\"第一段\"},{\"type\":"
                    + "\"text\",\"text\":\"第二段\"}],\"isError\":false}"));
    var output = adapter.execute("{\"value\":\"你好\",\"extra\":123}");
    assertTrue(output.success());
    assertEquals("第一段\n第二段", output.content());
    var order = inOrder(sandbox, client);
    order.verify(sandbox).enforce(new SandboxAction(ActionType.SHELL_EXEC, "java"));
    var captor = ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
    order.verify(client).callTool(captor.capture());
    assertEquals("business_lookup", captor.getValue().name());
    assertEquals(Set.of("value", "extra"), captor.getValue().arguments().keySet());
    assertEquals("你好", captor.getValue().arguments().get("value"));
    assertEquals(123, ((Number) captor.getValue().arguments().get("extra")).intValue());
  }

  @Test
  @DisplayName("缺工具或服务授权时零RPC且不自动扩权")
  void refusesUnauthorizedCalls() throws Exception {
    var adapter = adapter();
    assertFalse(adapter.execute("{\"value\":\"你好\"}").success());
    profile(List.of("business_lookup"), List.of());
    assertFalse(adapter.execute("{\"value\":\"你好\"}").success());
    profile(List.of(), List.of("business"));
    assertFalse(adapter.execute("{\"value\":\"你好\"}").success());
    verifyNoInteractions(client, sandbox);
  }

  @Test
  @DisplayName("沙箱拒绝透传且不发送RPC")
  void propagatesDenial() throws Exception {
    var adapter = adapter();
    profile(List.of("business_lookup"), List.of("business"));
    doThrow(new SandboxViolationException("禁止MCP")).when(sandbox).enforce(any());
    assertThrows(SandboxViolationException.class, () -> adapter.execute("{\"value\":\"你好\"}"));
    verifyNoInteractions(client);
  }

  @Test
  @DisplayName("远端错误和未知传输故障均不盲目重试或回显秘密")
  void sanitizesFailuresWithoutRetry() throws Exception {
    var adapter = adapter();
    profile(List.of("business_lookup"), List.of("business"));
    when(client.callTool(any(McpSchema.CallToolRequest.class)))
        .thenReturn(
            result(
                "{\"content\":[{\"type\":\"text\",\"text\":\"secret-token\"}],\"isError\":true}"))
        .thenThrow(new IllegalStateException("secret-token"));
    for (int index = 0; index < 2; index++) {
      var output = adapter.execute("{\"value\":\"你好\"}");
      assertFalse(output.success());
      assertFalse(output.retryable());
      assertFalse(output.errorMessage().contains("secret-token"));
    }
    verify(client, times(2)).callTool(any(McpSchema.CallToolRequest.class));
  }

  @Test
  @DisplayName("非文本与结构化结果不丢块也不自动拉取资源")
  void retainsStructuredContent() throws Exception {
    var adapter = adapter();
    profile(List.of("business_lookup"), List.of("business"));
    String json =
        "{\"content\":[{\"type\":\"image\",\"data\":\"AA==\",\"mimeType"
            + "\":\"image/png\"}],\"structuredContent\":{\"answer\":42},\"isE"
            + "rror\":false}";
    when(client.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(result(json));
    var output = adapter.execute("{\"value\":\"你好\"}");
    assertTrue(output.success());
    var tree = new ObjectMapper().readTree(output.content());
    assertEquals("AA==", tree.path("content").get(0).path("data").asText());
    assertEquals(42, tree.path("structuredContent").path("answer").asInt());
    verify(client).callTool(any(McpSchema.CallToolRequest.class));
  }

  @Test
  @DisplayName("空内容成功与空协议结果失败区分且配置秘密不会成为结果")
  void handlesEmptyAndSensitiveResults() throws Exception {
    var adapter = adapter();
    profile(List.of("business_lookup"), List.of("business"));
    when(client.callTool(any(McpSchema.CallToolRequest.class)))
        .thenReturn(
            result("{\"content\":[],\"isError\":false}"),
            null,
            result(
                "{\"content\":[{\"type\":\"text\",\"text\":\"secret-token\"}],\"isError\":false}"));
    assertEquals("", adapter.execute("{\"value\":\"你好\"}").content());
    assertFalse(adapter.execute("{\"value\":\"你好\"}").success());
    assertFalse(adapter.execute("{\"value\":\"你好\"}").content().contains("secret-token"));
  }

  @Test
  @DisplayName("结构化内容中的引号与反斜杠凭证脱敏后仍是合法JSON")
  void redactsEscapedStructuredSecrets() throws Exception {
    String secret = "part\"quote\\slash";
    var adapter = adapter(Set.of(secret));
    profile(List.of("business_lookup"), List.of("business"));
    String json =
        new ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "content",
                    List.of(),
                    "structuredContent",
                    Map.of("credential", "prefix-" + secret + "-suffix")));
    when(client.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(result(json));
    var output = adapter.execute("{\"value\":\"你好\"}");
    assertTrue(output.success());
    assertEquals(
        "prefix-[已脱敏]-suffix",
        new ObjectMapper()
            .readTree(output.content())
            .path("structuredContent")
            .path("credential")
            .asText());
  }

  @Test
  @DisplayName("结构化结果字段名包含已知秘密时失败关闭")
  void rejectsSecretInStructuredFieldName() throws Exception {
    String secret = "secret-token";
    var adapter = adapter(Set.of(secret));
    profile(List.of("business_lookup"), List.of("business"));
    String json =
        new ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "content",
                    List.of(),
                    "structuredContent",
                    Map.of("prefix-" + secret + "-suffix", 42)));
    when(client.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(result(json));
    var output = adapter.execute("{\"value\":\"你好\"}");
    assertFalse(output.success());
    assertFalse(output.errorMessage().contains(secret));
  }

  private McpToolAdapter adapter() throws Exception {
    return adapter(Set.of("secret-token"));
  }

  private McpToolAdapter adapter(Set<String> secrets) throws Exception {
    var mapper = new SchemaPreservingMcpJsonMapper(SchemaPreservingMcpJsonMapperTest.schemaCheck());
    mapper.beginDiscovery();
    var page =
        mapper.readValue(
            SchemaPreservingMcpJsonMapperTest.pageJson(
                SchemaPreservingMcpJsonMapperTest.FULL_SCHEMA),
            McpSchema.ListToolsResult.class);
    return new McpToolAdapter(
        "business",
        client,
        page.tools().getFirst(),
        mapper.consumeSchemas(page).getFirst(),
        sandbox,
        new SandboxAction(ActionType.SHELL_EXEC, "java"),
        SchemaPreservingMcpJsonMapperTest.schemaCheck(),
        SchemaPreservingMcpJsonMapperTest.argumentCheck(),
        secrets);
  }

  private static McpSchema.CallToolResult result(String json) throws Exception {
    return new ObjectMapper().readValue(json, McpSchema.CallToolResult.class);
  }

  static void profile(List<String> tools, List<String> servers) {
    ProfileContext.set(
        new Profile(
            "test", null, null, null, tools, null, servers, null, null, null, null, null, null,
            null));
  }
}
