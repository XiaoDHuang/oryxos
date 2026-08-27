package com.oryxos.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MCP原始Schema保真与离线校验")
class SchemaPreservingMcpJsonMapperTest {
  static final String FULL_SCHEMA =
      """
      {"type":"object","properties":{"value":{"type":"string"}},"required":["value"],
       "allOf":[{"properties":{"value":{"minLength":2}}}],
       "$defs":{"amount":{"type":"integer"}},"additionalProperties":{"$ref":"#/$defs/amount"}}
      """;

  @Test
  @DisplayName("组合约束与对象additionalProperties不被SDK投影丢失")
  void retainsOriginalSchemaAndValidatesIt() throws Exception {
    var mapper = new SchemaPreservingMcpJsonMapper(schemaCheck());
    mapper.beginDiscovery();
    Object raw = mapper.readValue(pageJson(FULL_SCHEMA), Object.class);
    ListToolsResult page = mapper.convertValue(raw, new TypeRef<ListToolsResult>() {});
    assertEquals(1, mapper.pendingCount());
    String original = mapper.consumeSchemas(page).getFirst();
    assertEquals(new ObjectMapper().readTree(FULL_SCHEMA), new ObjectMapper().readTree(original));
    assertEquals(0, mapper.pendingCount());
    assertNull(page.tools().getFirst().inputSchema().additionalProperties());
    assertDoesNotThrow(() -> argumentCheck().accept(original, "{\"value\":\"你好\",\"extra\":2}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> argumentCheck().accept(original, "{\"value\":\"x\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> argumentCheck().accept(original, "{\"value\":\"你好\",\"extra\":\"x\"}"));
    assertThrows(IllegalStateException.class, () -> mapper.consumeSchemas(page));
  }

  @Test
  @DisplayName("首次JSON读取保留精确小数且拒绝重复键")
  void preservesPrecisionBeforeConversion() throws Exception {
    var mapper = new SchemaPreservingMcpJsonMapper(schemaCheck());
    mapper.beginDiscovery();
    String precise =
        "{\"type\":\"object\",\"properties\":{\"amount\":{\"minimum\":0"
            + ".12345678901234567890123456789}}}";
    var page = mapper.readValue(pageJson(precise), ListToolsResult.class);
    String saved = mapper.consumeSchemas(page).getFirst();
    assertTrue(saved.contains("0.12345678901234567890123456789"));
    Map<?, ?> numbers = mapper.readValue("{\"value\":0.12345678901234567890123456789}", Map.class);
    assertEquals(new BigDecimal("0.12345678901234567890123456789"), numbers.get("value"));
    assertThrows(java.io.IOException.class, () -> mapper.readValue("{\"x\":1,\"x\":2}", Map.class));
  }

  @Test
  @DisplayName("相同工具名的不同页按对象身份绑定")
  void bindsByPageIdentity() throws Exception {
    var mapper = new SchemaPreservingMcpJsonMapper(schemaCheck());
    mapper.beginDiscovery();
    var first =
        mapper.readValue(
            pageJson("{\"type\":\"object\",\"minProperties\":1}"), ListToolsResult.class);
    var second =
        mapper.readValue(
            pageJson("{\"type\":\"object\",\"minProperties\":2}"), ListToolsResult.class);
    assertEquals(first, second);
    assertTrue(mapper.consumeSchemas(first).getFirst().contains(":1"));
    assertTrue(mapper.consumeSchemas(second).getFirst().contains(":2"));
  }

  @Test
  @DisplayName("未开始或已关闭发现窗口拒绝清单并清空绑定")
  void rejectsOutsideDiscoveryWindow() throws Exception {
    var mapper = new SchemaPreservingMcpJsonMapper(schemaCheck());
    assertThrows(
        IllegalStateException.class, () -> mapper.readValue(pageJson("{}"), ListToolsResult.class));
    mapper.beginDiscovery();
    var page = mapper.readValue(pageJson("{}"), ListToolsResult.class);
    mapper.endDiscovery();
    assertEquals(0, mapper.pendingCount());
    assertThrows(IllegalStateException.class, () -> mapper.consumeSchemas(page));
    assertThrows(
        IllegalStateException.class, () -> mapper.readValue(pageJson("{}"), ListToolsResult.class));
  }

  @Test
  @DisplayName("待消费页数最多32且失败不留下部分页")
  void boundsPendingPages() throws Exception {
    var mapper = new SchemaPreservingMcpJsonMapper(schemaCheck());
    mapper.beginDiscovery();
    for (int page = 0; page < 32; page++) {
      mapper.readValue(pageJson("{}"), ListToolsResult.class);
    }
    assertEquals(32, mapper.pendingCount());
    assertThrows(
        IllegalStateException.class, () -> mapper.readValue(pageJson("{}"), ListToolsResult.class));
    mapper.endDiscovery();
    mapper.beginDiscovery();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            mapper.readValue(
                pageJson("{\"$ref\":\"https://invalid/schema\"}"), ListToolsResult.class));
    assertEquals(0, mapper.pendingCount());
  }

  static String pageJson(String schema) {
    return "{\"tools\":[{\"name\":\"business_lookup\",\"description\":\"本地查询\",\"inputSchema\":"
        + schema
        + "}]}";
  }

  @Test
  @DisplayName("取消无需等待Schema校验且迟到页不能重新绑定")
  void cancellationDiscardsLateConversion() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    var mapper =
        new SchemaPreservingMcpJsonMapper(
            schema -> {
              entered.countDown();
              try {
                release.await();
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
              }
            });
    mapper.beginDiscovery();
    AtomicReference<Exception> failure = new AtomicReference<>();
    Thread worker =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    mapper.readValue(pageJson("{}"), ListToolsResult.class);
                  } catch (Exception exception) {
                    failure.set(exception);
                  }
                });
    try {
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      assertTimeoutPreemptively(Duration.ofSeconds(1), mapper::endDiscovery);
    } finally {
      release.countDown();
    }
    assertTrue(worker.join(Duration.ofSeconds(1)));
    assertInstanceOf(IllegalStateException.class, failure.get());
    assertEquals(0, mapper.pendingCount());
  }

  static Consumer<String> schemaCheck() {
    Object validator = validator();
    Method method = validationMethod("validateSchema", String.class);
    return schema -> invoke(validator, method, schema);
  }

  static BiConsumer<String, String> argumentCheck() {
    Object validator = validator();
    Method method = validationMethod("validateArguments", String.class, String.class);
    return (schema, arguments) -> invoke(validator, method, schema, arguments);
  }

  private static Object validator() {
    try {
      var constructor =
          Class.forName("com.oryxos.tool.ToolArgumentValidator").getDeclaredConstructor();
      constructor.setAccessible(true);
      return constructor.newInstance();
    } catch (ReflectiveOperationException exception) {
      throw new AssertionError(exception);
    }
  }

  private static Method validationMethod(String name, Class<?>... types) {
    try {
      Method method =
          Class.forName("com.oryxos.tool.ToolArgumentValidator").getDeclaredMethod(name, types);
      method.setAccessible(true);
      return method;
    } catch (ReflectiveOperationException exception) {
      throw new AssertionError(exception);
    }
  }

  private static void invoke(Object target, Method method, Object... arguments) {
    try {
      method.invoke(target, arguments);
    } catch (InvocationTargetException exception) {
      throw (RuntimeException) exception.getCause();
    } catch (ReflectiveOperationException exception) {
      throw new AssertionError(exception);
    }
  }
}
