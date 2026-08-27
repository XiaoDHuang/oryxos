package com.oryxos.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 校验器只处理数据，所有来源的工具共用同一组规则，避免适配时遗漏约束.
 *
 * @author OryxOS Contributors
 */
final class ToolArgumentValidator {

  private static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";
  private static final String OBJECT_TYPE = "object";
  private static final List<String> REFERENCES = List.of("$ref", "$dynamicRef", "$recursiveRef");

  private static final Set<String> SCHEMA_MAPS =
      Set.of("properties", "patternProperties", "$defs", "definitions", "dependentSchemas");

  private static final Set<String> SCHEMA_ARRAYS = Set.of("allOf", "anyOf", "oneOf", "prefixItems");

  private static final Set<String> SCHEMA_VALUES =
      Set.of(
          "additionalProperties",
          "unevaluatedProperties",
          "unevaluatedItems",
          "items",
          "contains",
          "propertyNames",
          "not",
          "if",
          "then",
          "else",
          "contentSchema",
          "additionalItems");

  private final ObjectMapper mapper =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
          .build();

  private final SchemaRegistry schemaRegistry =
      SchemaRegistry.withDialect(
          Dialects.getDraft202012(),
          builder ->
              builder
                  .schemaCacheEnabled(false)
                  .schemaLoader(
                      loader -> loader.fetchRemoteResources(false).block(ignored -> true)));

  /** 编译结果按原始Schema隔离，不能用所有匿名Schema共享的URI作为缓存键. */
  private final Map<String, Schema> schemas = new ConcurrentHashMap<>();

  void validateSchema(String schemaJson) {
    compiledSchema(schemaJson);
  }

  void validateArguments(String schemaJson, String argumentsJson) {
    Schema schema = compiledSchema(schemaJson);
    ObjectNode arguments = readArguments(argumentsJson);
    List<com.networknt.schema.Error> errors;
    try {
      errors = schema.validate(arguments);
    } catch (RuntimeException exception) {
      // 第三方异常正文可能包含参数值或URI，失败原因只保留安全类别。
      throw new IllegalArgumentException("工具参数无法按Schema校验");
    }
    if (!errors.isEmpty()) {
      throw new IllegalArgumentException("工具参数校验失败: " + errors.get(0).getKeyword());
    }
  }

  ObjectNode readArguments(String argumentsJson) {
    return readObject(argumentsJson, "工具参数");
  }

  ObjectMapper mapper() {
    return mapper;
  }

  private Schema compiledSchema(String schemaJson) {
    if (schemaJson == null || schemaJson.isBlank()) {
      throw new IllegalArgumentException("工具Schema不能为空");
    }
    return schemas.computeIfAbsent(schemaJson, this::compile);
  }

  private Schema compile(String schemaJson) {
    ObjectNode node = readObject(schemaJson, "工具Schema");
    JsonNode type = node.get("type");
    boolean objectType = type == null || OBJECT_TYPE.equals(type.textValue());
    if (!objectType) {
      throw new IllegalArgumentException("工具Schema必须描述JSON对象");
    }
    inspectSchema(node);
    try {
      Schema schema = schemaRegistry.getSchema(node);
      schema.initializeValidators();
      return schema;
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("工具Schema无法在本地编译");
    }
  }

  private ObjectNode readObject(String json, String label) {
    if (json == null || json.isBlank()) {
      throw new IllegalArgumentException(label + "不能为空");
    }
    JsonNode node;
    try {
      node = mapper.readTree(json);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException(label + "不是合法的单一JSON对象");
    }
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException(label + "必须是JSON对象");
    }
    return (ObjectNode) node;
  }

  private static void inspectSchema(JsonNode node) {
    if (node.isBoolean()) {
      return;
    }
    if (!node.isObject()) {
      throw new IllegalArgumentException("子Schema格式无效");
    }
    JsonNode dialect = node.get("$schema");
    if (dialect != null) {
      boolean supported =
          DIALECT.equals(dialect.textValue()) || (DIALECT + "#").equals(dialect.textValue());
      if (!supported) {
        throw new IllegalArgumentException("不支持此Schema方言");
      }
    }
    for (String keyword : REFERENCES) {
      JsonNode reference = node.get(keyword);
      if (reference != null) {
        boolean local = reference.isTextual() && reference.textValue().startsWith("#");
        if (!local) {
          throw new IllegalArgumentException("工具Schema只允许本地引用");
        }
      }
    }
    for (String keyword : SCHEMA_MAPS) {
      JsonNode values = node.get(keyword);
      if (values != null) {
        if (!values.isObject()) {
          throw new IllegalArgumentException("Schema属性映射必须是对象");
        }
        values.elements().forEachRemaining(ToolArgumentValidator::inspectSchema);
      }
    }
    for (String keyword : SCHEMA_ARRAYS) {
      JsonNode values = node.get(keyword);
      if (values != null) {
        if (!values.isArray()) {
          throw new IllegalArgumentException("Schema组合必须是数组");
        }
        values.elements().forEachRemaining(ToolArgumentValidator::inspectSchema);
      }
    }
    for (String keyword : SCHEMA_VALUES) {
      JsonNode value = node.get(keyword);
      if (value != null) {
        inspectSchema(value);
      }
    }
    // properties中的键与enum/default中的数据不是Schema关键字，不能把普通数据当成引用。
    JsonNode dependencies = node.get("dependencies");
    if (dependencies != null && dependencies.isObject()) {
      dependencies
          .elements()
          .forEachRemaining(
              value -> {
                if (value.isObject() || value.isBoolean()) {
                  inspectSchema(value);
                }
              });
    }
  }
}
