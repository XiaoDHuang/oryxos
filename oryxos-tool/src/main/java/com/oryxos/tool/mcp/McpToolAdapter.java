package com.oryxos.tool.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.oryxos.core.profile.Profile;
import com.oryxos.core.react.ProfileContext;
import com.oryxos.core.tool.OryxTool;
import com.oryxos.core.tool.ToolResult;
import com.oryxos.tool.sandbox.Sandbox;
import com.oryxos.tool.sandbox.SandboxAction;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 外部工具保留原始参数契约，由唯一执行器负责审计与重试.
 *
 * @author OryxOS Contributors
 */
public final class McpToolAdapter implements OryxTool {
  private final String serverName;
  private final Function<McpSchema.CallToolRequest, McpSchema.CallToolResult> callTool;
  private final String name;
  private final String description;
  private final String originalSchema;
  private final Sandbox sandbox;
  private final SandboxAction action;
  private final BiConsumer<String, String> argumentValidator;
  private final Set<String> secrets;
  private final ObjectMapper mapper =
      JsonMapper.builder()
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
          .build();

  /** 原始Schema与授权门随连接绑定，SDK投影不能成为公开契约. */
  public McpToolAdapter(
      String serverName,
      McpSyncClient client,
      McpSchema.Tool tool,
      String originalSchema,
      Sandbox sandbox,
      SandboxAction action,
      Consumer<String> schemaValidator,
      BiConsumer<String, String> argumentValidator,
      Set<String> secrets) {
    this.serverName = Objects.requireNonNull(serverName);
    // Adapter只保留调用能力，连接状态和关闭权限归McpClientService管理。
    this.callTool = Objects.requireNonNull(client)::callTool;
    this.name = Objects.requireNonNull(tool).name();
    this.description = tool.description();
    this.originalSchema = Objects.requireNonNull(originalSchema);
    this.sandbox = Objects.requireNonNull(sandbox);
    this.action = Objects.requireNonNull(action);
    this.argumentValidator = Objects.requireNonNull(argumentValidator);
    this.secrets = Set.copyOf(secrets);
    boolean invalidName = name == null || name.isBlank();
    boolean invalidDescription = description == null || description.isBlank();
    if (invalidName || invalidDescription) {
      throw new IllegalArgumentException("MCP工具元数据不能为空");
    }
    for (String secret : secrets) {
      String encoded = mapper.valueToTree(secret).toString();
      boolean identityContainsSecret = name.contains(secret) || description.contains(secret);
      boolean schemaContainsSecret =
          originalSchema.contains(secret)
              || originalSchema.contains(encoded.substring(1, encoded.length() - 1));
      boolean metadataContainsSecret = identityContainsSecret || schemaContainsSecret;
      if (!secret.isBlank() && metadataContainsSecret) {
        throw new IllegalArgumentException("MCP工具元数据包含配置秘密");
      }
    }
    schemaValidator.accept(originalSchema);
  }

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
    return originalSchema;
  }

  @Override
  public ToolResult execute(String argumentsJson) {
    Map<String, Object> arguments;
    try {
      argumentValidator.accept(originalSchema, argumentsJson);
      arguments = mapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {});
    } catch (JsonProcessingException | IllegalArgumentException exception) {
      return ToolResult.fail(name, "MCP工具参数校验失败");
    }
    Profile profile = ProfileContext.current();
    if (profile == null
        || !profile.tools().contains(name)
        || !profile.mcpServers().contains(serverName)) {
      return ToolResult.fail(name, "Profile未授权此MCP工具或服务");
    }
    sandbox.enforce(action);
    try {
      McpSchema.CallToolResult result =
          callTool.apply(new McpSchema.CallToolRequest(name, arguments));
      if (result == null || Boolean.TRUE.equals(result.isError())) {
        return ToolResult.fail(name, "MCP工具返回失败或无效结果");
      }
      List<McpSchema.Content> blocks = result.content() == null ? List.of() : result.content();
      String content;
      if (result.structuredContent() == null
          && blocks.stream().allMatch(McpSchema.TextContent.class::isInstance)) {
        content =
            blocks.stream()
                .map(McpSchema.TextContent.class::cast)
                .map(block -> Objects.requireNonNullElse(block.text(), ""))
                .collect(Collectors.joining("\n"));
      } else {
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("content", blocks);
        structured.put("structuredContent", result.structuredContent());
        // 对JSON节点中的字符串脱敏，不能在序列化后替换而遗漏转义或破坏结构。
        content = mapper.writeValueAsString(redactTree(mapper.valueToTree(structured)));
      }
      return ToolResult.ok(
          name,
          result.structuredContent() == null
                  && blocks.stream().allMatch(McpSchema.TextContent.class::isInstance)
              ? redact(content)
              : content);
    } catch (JsonProcessingException | RuntimeException exception) {
      // 无法确认远端是否已产生副作用，不根据超时或远端提示擅自重试。
      return ToolResult.fail(name, "MCP调用或结果转换失败，未自动重试");
    }
  }

  private String redact(String content) {
    String safe = content;
    for (String secret : secrets) {
      if (!secret.isBlank()) {
        safe = safe.replace(secret, "[已脱敏]");
      }
    }
    return safe;
  }

  private JsonNode redactTree(JsonNode node) {
    if (node.isTextual()) {
      return TextNode.valueOf(redact(node.textValue()));
    }
    if (node instanceof ObjectNode object) {
      object
          .properties()
          .forEach(
              entry -> {
                if (containsSecret(entry.getKey())) {
                  throw new IllegalArgumentException("MCP结构化结果字段名包含配置秘密");
                }
                object.set(entry.getKey(), redactTree(entry.getValue()));
              });
    } else if (node instanceof ArrayNode array) {
      for (int index = 0; index < array.size(); index++) {
        array.set(index, redactTree(array.get(index)));
      }
    }
    return node;
  }

  private boolean containsSecret(String value) {
    for (String secret : secrets) {
      if (!secret.isBlank() && value.contains(secret)) {
        return true;
      }
    }
    return false;
  }
}
