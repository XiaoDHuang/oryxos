package com.oryxos.tool.mcp;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * SDK只保存有限Schema字段，原始约束必须在转换前另行绑定.
 *
 * @author OryxOS Contributors
 */
final class SchemaPreservingMcpJsonMapper implements McpJsonMapper {
  private static final int MAX_PENDING_PAGES = 32;
  private static final int MAX_PAGE_TOOLS = 1000;
  private static final String TOOLS_FIELD = "tools";
  private static final List<String> SDK_SCHEMA_FIELDS =
      List.of("type", "properties", "required", "$defs", "definitions");
  private final ObjectMapper mapper =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
          .build();
  private final McpJsonMapper delegate = new JacksonMcpJsonMapper(mapper);
  private final Consumer<String> schemaValidator;
  private final Map<ListToolsResult, List<String>> pending = new IdentityHashMap<>();
  private long generation;
  private boolean discovering;
  private int converting;

  SchemaPreservingMcpJsonMapper(Consumer<String> schemaValidator) {
    this.schemaValidator = Objects.requireNonNull(schemaValidator);
  }

  synchronized void beginDiscovery() {
    if (discovering) {
      throw new IllegalStateException("MCP发现窗口已经打开");
    }
    generation++;
    discovering = true;
    converting = 0;
    pending.clear();
  }

  synchronized void endDiscovery() {
    discovering = false;
    generation++;
    converting = 0;
    pending.clear();
  }

  synchronized int pendingCount() {
    return pending.size();
  }

  synchronized List<String> consumeSchemas(ListToolsResult page) {
    List<String> schemas = pending.remove(page);
    if (schemas == null) {
      throw new IllegalStateException("MCP清单缺少原始Schema绑定");
    }
    return schemas;
  }

  private ListToolsResult preservePage(Object value) {
    long version;
    synchronized (this) {
      if (!discovering || pending.size() + converting >= MAX_PENDING_PAGES) {
        throw new IllegalStateException("MCP清单不在发现窗口内或待消费页超过上限");
      }
      converting++;
      version = generation;
    }
    try {
      JsonNode node = mapper.valueToTree(value);
      if (!node.isObject()
          || !node.path(TOOLS_FIELD).isArray()
          || node.path(TOOLS_FIELD).size() > MAX_PAGE_TOOLS) {
        throw new IllegalArgumentException("MCP工具清单格式无效");
      }
      ObjectNode projection = ((ObjectNode) node).deepCopy();
      List<String> originals = new ArrayList<>();
      for (JsonNode tool : projection.path(TOOLS_FIELD)) {
        JsonNode schema = tool.get("inputSchema");
        if (!tool.isObject() || schema == null || !schema.isObject()) {
          throw new IllegalArgumentException("MCP工具缺少对象Schema");
        }
        String original = schema.toString();
        // 校验可能耗时，不能持有窗口锁阻塞超时取消与绑定清理。
        schemaValidator.accept(original);
        originals.add(original);
        ObjectNode compatible = mapper.createObjectNode();
        for (String field : SDK_SCHEMA_FIELDS) {
          if (schema.has(field)) {
            compatible.set(field, schema.get(field));
          }
        }
        if (schema.path("additionalProperties").isBoolean()) {
          compatible.set("additionalProperties", schema.get("additionalProperties"));
        }
        ((ObjectNode) tool).set("inputSchema", compatible);
      }
      ListToolsResult page = delegate.convertValue(projection, ListToolsResult.class);
      synchronized (this) {
        if (!discovering || generation != version) {
          throw new IllegalStateException("MCP发现已经取消，丢弃迟到清单");
        }
        pending.put(page, List.copyOf(originals));
      }
      return page;
    } finally {
      synchronized (this) {
        if (generation == version) {
          converting--;
        }
      }
    }
  }

  @Override
  public <T> T readValue(String content, Class<T> type) throws IOException {
    return type == ListToolsResult.class
        ? convertValue(delegate.readValue(content, Object.class), type)
        : delegate.readValue(content, type);
  }

  @Override
  public <T> T readValue(byte[] content, Class<T> type) throws IOException {
    return type == ListToolsResult.class
        ? convertValue(delegate.readValue(content, Object.class), type)
        : delegate.readValue(content, type);
  }

  @Override
  public <T> T readValue(String content, TypeRef<T> type) throws IOException {
    return type.getType() == ListToolsResult.class
        ? convertValue(delegate.readValue(content, Object.class), type)
        : delegate.readValue(content, type);
  }

  @Override
  public <T> T readValue(byte[] content, TypeRef<T> type) throws IOException {
    return type.getType() == ListToolsResult.class
        ? convertValue(delegate.readValue(content, Object.class), type)
        : delegate.readValue(content, type);
  }

  @Override
  public <T> T convertValue(Object value, Class<T> type) {
    return type == ListToolsResult.class
        ? type.cast(preservePage(value))
        : delegate.convertValue(value, type);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T convertValue(Object value, TypeRef<T> type) {
    return type.getType() == ListToolsResult.class
        ? (T) preservePage(value)
        : delegate.convertValue(value, type);
  }

  @Override
  public String writeValueAsString(Object value) throws IOException {
    return delegate.writeValueAsString(value);
  }

  @Override
  public byte[] writeValueAsBytes(Object value) throws IOException {
    return delegate.writeValueAsBytes(value);
  }
}
