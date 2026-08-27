package com.oryxos.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.oryxos.tool.ToolRegistry;
import com.oryxos.tool.sandbox.PermissiveSandbox;
import com.oryxos.tool.sandbox.Sandbox;
import com.oryxos.tool.sandbox.SandboxViolationException;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

@DisplayName("MCP配置发现与故障隔离")
class McpClientServiceTest {
  @TempDir Path directory;
  private final ToolRegistry registry = new ToolRegistry();

  @Test
  @DisplayName("坏服务有安全WARN且好服务照常原子注册")
  void isolatesFailedServerAndClosesConnections() throws Exception {
    final Path file = config(entry("bad") + entry("good"));
    McpSyncClient bad = mock(McpSyncClient.class);
    final McpSyncClient good = mock(McpSyncClient.class);
    when(bad.initialize()).thenThrow(new IllegalStateException("secret-token"));
    ListAppender<ILoggingEvent> events = new ListAppender<>();
    events.start();
    Logger logger = (Logger) LoggerFactory.getLogger(McpClientService.class);
    logger.addAppender(events);
    try (var service =
        service(
            file,
            new PermissiveSandbox(),
            (configuration, mapper) -> {
              if (configuration.name().equals("bad")) {
                return bad;
              }
              pages(good, mapper, List.of(page("good_mcp_tool", null, 1)));
              return good;
            },
            Map.of(),
            Duration.ofSeconds(5))) {
      assertDoesNotThrow(service::connectAll);
      assertTrue(registry.contains("good_mcp_tool"));
      assertFalse(registry.contains("bad_mcp_tool"));
      assertTrue(
          events.list.stream().anyMatch(event -> event.getLevel().toString().equals("WARN")));
      assertTrue(
          events.list.stream()
              .noneMatch(event -> event.getFormattedMessage().contains("secret-token")));
      verify(bad).closeGracefully();
    } finally {
      logger.detachAppender(events);
    }
    verify(good).closeGracefully();
    verify(good).close();
  }

  @Test
  @DisplayName("启动前安全拒绝零客户端或进程创建")
  void refusesBeforeCreatingClient() throws Exception {
    AtomicInteger created = new AtomicInteger();
    try (var service =
        service(
            config(entry("denied")),
            action -> {
              throw new SandboxViolationException("禁止进程");
            },
            (configuration, mapper) -> {
              created.incrementAndGet();
              return mock(McpSyncClient.class);
            },
            Map.of(),
            Duration.ofSeconds(5))) {
      assertDoesNotThrow(service::connectAll);
      assertEquals(0, created.get());
      assertTrue(registry.all().isEmpty());
    }
  }

  @Test
  @DisplayName("环境占位符原键保留而命令按argv传递")
  void resolvesDeclaredEnvironment() throws Exception {
    List<McpServerConfig> received = new ArrayList<>();
    Path file =
        config(
            "  - name: good\n    transport: stdio\n    command: [java, '-Dn"
                + "ame=a b']\n    env: {API_KEY: '${TOKEN}'}\n");
    try (var service =
        service(
            file,
            new PermissiveSandbox(),
            (configuration, mapper) -> {
              received.add(configuration);
              var client = mock(McpSyncClient.class);
              pages(client, mapper, List.of(page("good_mcp_tool", null, 1)));
              return client;
            },
            Map.of("TOKEN", "resolved-secret"),
            Duration.ofSeconds(5))) {
      service.connectAll();
      assertEquals(1, received.size());
      assertEquals(List.of("java", "-Dname=a b"), received.getFirst().command());
      assertEquals(Map.of("API_KEY", "resolved-secret"), received.getFirst().env());
      assertTrue(registry.contains("good_mcp_tool"));
    }
  }

  @Test
  @DisplayName("普通短环境变量不误伤元数据而敏感键仍保留为秘密")
  void distinguishesOrdinaryAndSensitiveEnvironmentValues() throws Exception {
    Path file =
        config(
            "  - name: good\n"
                + "    transport: stdio\n"
                + "    command: [java]\n"
                + "    env: {MODE: a, API_KEY: '${TOKEN}'}\n");
    List<McpServerConfig> received = new ArrayList<>();
    try (var service =
        service(
            file,
            new PermissiveSandbox(),
            (configuration, mapper) -> {
              received.add(configuration);
              var client = mock(McpSyncClient.class);
              pages(client, mapper, List.of(page("data_tool", null, 1)));
              return client;
            },
            Map.of("TOKEN", "protected-secret"),
            Duration.ofSeconds(5))) {
      service.connectAll();
      assertTrue(registry.contains("data_tool"));
      assertEquals(Map.of("MODE", "a", "API_KEY", "protected-secret"), received.getFirst().env());
    }
  }

  @Test
  @DisplayName("重名配置全部拒绝且非法配置不影响合法服务")
  void rejectsInvalidAndDuplicateConfigurations() throws Exception {
    List<String> created = new ArrayList<>();
    String yaml =
        entry("duplicate")
            + entry("duplicate")
            + "  - {name: bad-transport, transport: sse, command: [java]}\n"
            + "  - {name: empty-command, transport: stdio, command: []}\n"
            + "  - {name: missing-env, transport: stdio, command: [java], env"
            + ": {TOKEN: '${ABSENT}'}}\n"
            + "  - {name: numeric-env, transport: stdio, command: [java], env: {TOKEN: 12}}\n"
            + entry("good");
    try (var service =
        service(
            config(yaml),
            new PermissiveSandbox(),
            (configuration, mapper) -> {
              created.add(configuration.name());
              var client = mock(McpSyncClient.class);
              pages(client, mapper, List.of(page("good_mcp_tool", null, 1)));
              return client;
            },
            Map.of(),
            Duration.ofSeconds(5))) {
      service.connectAll();
      assertEquals(List.of("good"), created);
      assertEquals(1, registry.all().size());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {32, 33})
  @DisplayName("分页32页边界与超限全批拒绝")
  void boundsPages(int count) throws Exception {
    List<String> rawPages = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      rawPages.add(page("tool" + index, index + 1 == count ? null : "cursor" + index, 1));
    }
    McpSyncClient client = mock(McpSyncClient.class);
    try (var service =
        service(
            config(entry("paged")),
            new PermissiveSandbox(),
            (configuration, mapper) -> {
              pages(client, mapper, rawPages);
              return client;
            },
            Map.of(),
            Duration.ofSeconds(5))) {
      service.connectAll();
      assertEquals(count == 32 ? 32 : 0, registry.all().size());
      verify(client, times(32)).listTools(nullable(String.class));
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {1000, 1001})
  @DisplayName("工具数恰好1000允许而第1001项拒绝整批")
  void boundsToolCount(int count) throws Exception {
    var client = mock(McpSyncClient.class);
    try (var service =
        service(
            config(entry("many")),
            new PermissiveSandbox(),
            (configuration, mapper) -> {
              pages(client, mapper, List.of(page("many", null, count)));
              return client;
            },
            Map.of(),
            Duration.ofSeconds(5))) {
      service.connectAll();
      assertEquals(count == 1000 ? 1000 : 0, registry.all().size());
    }
  }

  @Test
  @DisplayName("循环游标与跨页重名都不留下部分工具")
  void rejectsCyclesAndDuplicates() throws Exception {
    for (List<String> rawPages :
        List.of(
            List.of(page("one", "same", 1), page("two", "same", 1)),
            List.of(page("same", "next", 1), page("same", null, 1)))) {
      try (var service =
          service(
              config(entry("bad")),
              new PermissiveSandbox(),
              (configuration, mapper) -> {
                var client = mock(McpSyncClient.class);
                pages(client, mapper, rawPages);
                return client;
              },
              Map.of(),
              Duration.ofSeconds(5))) {
        service.connectAll();
        assertTrue(registry.all().isEmpty());
      }
    }
  }

  @Test
  @DisplayName("总预算超时取消迟到注册且继续后续好服务")
  void discardsLateBatchAndContinues() throws Exception {
    var late = mock(McpSyncClient.class);
    var good = mock(McpSyncClient.class);
    AtomicBoolean exited = new AtomicBoolean();
    try (var service =
        service(
            config(entry("late") + entry("good")),
            new PermissiveSandbox(),
            (configuration, mapper) -> {
              if (configuration.name().equals("good")) {
                pages(good, mapper, List.of(page("good_mcp_tool", null, 1)));
                return good;
              }
              when(late.listTools(nullable(String.class)))
                  .thenAnswer(
                      invocation -> {
                        try {
                          Thread.sleep(1000);
                        } catch (InterruptedException ignored) {
                          Thread.sleep(50);
                        }
                        try {
                          return mapper.readValue(
                              page("late_mcp_tool", null, 1), McpSchema.ListToolsResult.class);
                        } finally {
                          exited.set(true);
                        }
                      });
              return late;
            },
            Map.of(),
            Duration.ofMillis(200))) {
      assertTimeoutPreemptively(Duration.ofSeconds(3), service::connectAll);
      assertTrue(exited.get());
      assertFalse(registry.contains("late_mcp_tool"));
      assertTrue(registry.contains("good_mcp_tool"));
      verify(late).closeGracefully();
    }
  }

  @Test
  @DisplayName("缺失文件或坏YAML不阻断启动且生产时限固定")
  void handlesMissingAndMalformedFiles() throws Exception {
    AtomicInteger created = new AtomicInteger();
    for (Path file : List.of(directory.resolve("absent.yaml"), config("  - [broken\n"))) {
      try (var service =
          service(
              file,
              new PermissiveSandbox(),
              (configuration, mapper) -> {
                created.incrementAndGet();
                return mock(McpSyncClient.class);
              },
              Map.of(),
              Duration.ofSeconds(5))) {
        assertDoesNotThrow(service::connectAll);
      }
    }
    assertEquals(0, created.get());
    assertEquals(Duration.ofSeconds(10), McpClientService.INITIALIZE_TIMEOUT);
    assertEquals(Duration.ofSeconds(30), McpClientService.REQUEST_TIMEOUT);
    assertEquals(Duration.ofSeconds(30), McpClientService.DISCOVERY_TIMEOUT);
    assertEquals(Duration.ofSeconds(10), McpClientService.CLEANUP_TIMEOUT);
    assertThrows(
        IllegalArgumentException.class,
        () -> new McpServerConfig("", "stdio", List.of("java"), Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new McpServerConfig("x", "stdio", List.of(), Map.of()));
  }

  private McpClientService service(
      Path file,
      Sandbox sandbox,
      BiFunction<McpServerConfig, SchemaPreservingMcpJsonMapper, McpSyncClient> factory,
      Map<String, String> environment,
      Duration timeout) {
    return new McpClientService(
        file,
        registry,
        sandbox,
        SchemaPreservingMcpJsonMapperTest.schemaCheck(),
        SchemaPreservingMcpJsonMapperTest.argumentCheck(),
        factory,
        environment,
        timeout,
        Duration.ofSeconds(1));
  }

  private Path config(String entries) throws Exception {
    Path file = directory.resolve("mcp_servers.yaml");
    Files.writeString(file, "servers:\n" + entries);
    return file;
  }

  private static String entry(String name) {
    return "  - name: " + name + "\n    transport: stdio\n    command: [java]\n";
  }

  private static String page(String prefix, String cursor, int count) {
    List<String> tools = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      String name = count == 1 ? prefix : prefix + index;
      tools.add(
          "{\"name\":\""
              + name
              + "\",\"description\":\"测试工具\",\"inputSchema\":{\"type\":\"object\"}}");
    }
    return "{\"tools\":["
        + String.join(",", tools)
        + "]"
        + (cursor == null ? "" : ",\"nextCursor\":\"" + cursor + "\"")
        + "}";
  }

  private static void pages(
      McpSyncClient client, SchemaPreservingMcpJsonMapper mapper, List<String> pages) {
    AtomicInteger index = new AtomicInteger();
    when(client.listTools(nullable(String.class)))
        .thenAnswer(
            invocation ->
                mapper.readValue(
                    pages.get(index.getAndIncrement()), McpSchema.ListToolsResult.class));
  }
}
