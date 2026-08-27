package com.oryxos.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oryxos.core.react.ProfileContext;
import com.oryxos.tool.ToolRegistry;
import com.oryxos.tool.sandbox.PermissiveSandbox;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("integration")
@DisplayName("真实本地MCP标准输入输出连通性")
class McpStdioIntegrationTest {
  @TempDir Path directory;

  @Test
  @DisplayName("初始化清单调用关闭保留UTF8且隔离宿主环境")
  void completesRealStdioRoundTrip() throws Exception {
    assertNotNull(System.getenv("ORYXOS_HOST_SENTINEL"), "执行命令必须显式提供无敏感内容的宿主哨兵变量");
    var mapper = new SchemaPreservingMcpJsonMapper(SchemaPreservingMcpJsonMapperTest.schemaCheck());
    var parameters =
        ServerParameters.builder(Path.of(System.getProperty("java.home"), "bin", "java").toString())
            .args(
                List.of(
                    "-Dfile.encoding=UTF-8",
                    "-cp",
                    System.getProperty("java.class.path"),
                    McpStdioFixture.class.getName()))
            .env(Map.of("ORYXOS_FIXTURE_VALUE", "中文环境值"))
            .build();
    var transport = new ConfiguredStdioTransport(parameters, mapper);
    transport.setStdErrorHandler(ignored -> {});
    var client =
        McpClient.sync(transport)
            .initializationTimeout(Duration.ofSeconds(10))
            .requestTimeout(Duration.ofSeconds(30))
            .enableCallToolSchemaCaching(false)
            .build();
    long pid = -1;
    try {
      mapper.beginDiscovery();
      assertNotNull(client.initialize());
      var page = client.listTools(null);
      assertEquals(1, page.tools().size());
      var schemas = mapper.consumeSchemas(page);
      assertEquals(
          new ObjectMapper().readTree(SchemaPreservingMcpJsonMapperTest.FULL_SCHEMA),
          new ObjectMapper().readTree(schemas.getFirst()));
      mapper.endDiscovery();
      var result =
          client.callTool(
              new McpSchema.CallToolRequest("business_lookup", Map.of("value", "你好工具")));
      assertFalse(Boolean.TRUE.equals(result.isError()));
      var payload =
          new ObjectMapper().readTree(((McpSchema.TextContent) result.content().getFirst()).text());
      assertEquals("你好工具", payload.path("value").asText());
      assertEquals("中文环境值", payload.path("env").asText());
      assertTrue(payload.path("leaked").isNull());
      pid = payload.path("pid").asLong();
      assertTrue(ProcessHandle.of(pid).orElseThrow().isAlive());
    } finally {
      mapper.endDiscovery();
      if (!client.closeGracefully()) {
        client.close();
      }
    }
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
        && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
  }

  @Test
  @DisplayName("真实配置服务与Adapter完成分页原始Schema校验调用和关闭")
  void completesServiceAndAdapterFlow() throws Exception {
    ToolRegistry registry = new ToolRegistry();
    Path pidFile = directory.resolve("paged.pid");
    Path config = configuration(List.of(server("local", "paged", pidFile)));
    try (var service =
        new McpClientService(
            config,
            registry,
            new PermissiveSandbox(),
            SchemaPreservingMcpJsonMapperTest.schemaCheck(),
            SchemaPreservingMcpJsonMapperTest.argumentCheck())) {
      service.connectAll();
      assertEquals(2, registry.all().size());
      assertTrue(registry.contains("business_second"));
      var adapter = registry.asMap().get("business_lookup");
      assertEquals(
          new ObjectMapper().readTree(SchemaPreservingMcpJsonMapperTest.FULL_SCHEMA),
          new ObjectMapper().readTree(adapter.getInputSchema()));
      McpToolAdapterTest.profile(List.of("business_lookup"), List.of("local"));
      try {
        assertFalse(adapter.execute("{\"value\":\"x\"}").success());
        var result = adapter.execute("{\"value\":\"真实调用\"}");
        assertTrue(result.success(), result.errorMessage());
        var payload = new ObjectMapper().readTree(result.content());
        assertEquals("真实调用", payload.path("value").asText());
        assertEquals(1, payload.path("calls").asInt());
      } finally {
        ProfileContext.clear();
      }
    }
    assertStopped(pidFile);
  }

  @ParameterizedTest
  @ValueSource(strings = {"cycle", "overflow", "slow"})
  @DisplayName("真实坏服务循环超限或迟到均零注册且后续好服务正常退出")
  void isolatesRealFaultyServices(String mode) throws Exception {
    ToolRegistry registry = new ToolRegistry();
    Path badPid = directory.resolve("bad.pid");
    Path goodPid = directory.resolve("good.pid");
    List<Thread> workers = new ArrayList<>();
    Path config =
        configuration(List.of(server("bad", mode, badPid), server("good", "normal", goodPid)));
    try (var service =
        new McpClientService(
            config,
            registry,
            new PermissiveSandbox(),
            SchemaPreservingMcpJsonMapperTest.schemaCheck(),
            SchemaPreservingMcpJsonMapperTest.argumentCheck(),
            (parameters, mapper) -> {
              workers.add(Thread.currentThread());
              return realClient(parameters, mapper);
            },
            Map.of(),
            Duration.ofSeconds(3),
            Duration.ofSeconds(2))) {
      assertTimeoutPreemptively(Duration.ofSeconds(10), service::connectAll);
      assertEquals(1, registry.all().size());
      assertTrue(registry.contains("business_lookup"));
      assertTrue(workers.stream().noneMatch(Thread::isAlive));
      McpToolAdapterTest.profile(List.of("business_lookup"), List.of("good"));
      try {
        assertTrue(
            registry.asMap().get("business_lookup").execute("{\"value\":\"好服务\"}").success());
      } finally {
        ProfileContext.clear();
      }
      assertStopped(badPid);
    }
    assertStopped(goodPid);
  }

  private Path configuration(List<Map<String, Object>> servers) throws Exception {
    Path config = directory.resolve("mcp_servers.yaml");
    Files.writeString(config, new ObjectMapper().writeValueAsString(Map.of("servers", servers)));
    return config;
  }

  private static Map<String, Object> server(String name, String mode, Path pidFile) {
    return Map.of(
        "name",
        name,
        "transport",
        "stdio",
        "command",
        List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Dfile.encoding=UTF-8",
            "-cp",
            System.getProperty("java.class.path"),
            McpStdioFixture.class.getName(),
            mode,
            pidFile.toString()),
        "env",
        Map.of("ORYXOS_FIXTURE_VALUE", "test-config-value"));
  }

  private static McpSyncClient realClient(
      McpServerConfig configuration, SchemaPreservingMcpJsonMapper mapper) {
    var parameters =
        ServerParameters.builder(configuration.command().getFirst())
            .args(configuration.command().subList(1, configuration.command().size()))
            .env(configuration.env())
            .build();
    var transport = new ConfiguredStdioTransport(parameters, mapper);
    transport.setStdErrorHandler(ignored -> {});
    return McpClient.sync(transport)
        .initializationTimeout(Duration.ofSeconds(10))
        .requestTimeout(Duration.ofSeconds(30))
        .enableCallToolSchemaCaching(false)
        .build();
  }

  private static void assertStopped(Path pidFile) throws Exception {
    assertTrue(Files.exists(pidFile), "必须实际启动进程才能验证退出");
    long pid = Long.parseLong(Files.readString(pidFile));
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
        && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
  }
}
