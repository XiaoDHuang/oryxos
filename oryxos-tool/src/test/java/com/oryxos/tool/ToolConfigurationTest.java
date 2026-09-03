package com.oryxos.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.oryxos.core.config.CoreEngineConfiguration;
import com.oryxos.core.context.ContextLoader;
import com.oryxos.core.memory.MemoryScope;
import com.oryxos.core.memory.MemoryService;
import com.oryxos.core.profile.ProfileRegistry;
import com.oryxos.core.react.LlmGateway;
import com.oryxos.core.react.PromptBuilder;
import com.oryxos.core.react.ToolExecutor;
import com.oryxos.core.react.ToolInvocationAudit;
import com.oryxos.core.session.Session;
import com.oryxos.core.session.SessionManager;
import com.oryxos.core.tool.OryxTool;
import com.oryxos.memory.MemoryTools;
import com.oryxos.tool.mcp.McpClientService;
import com.oryxos.tool.notify.WebhookNotifyAdapter;
import com.oryxos.tool.sandbox.ActionType;
import com.oryxos.tool.sandbox.HttpWhitelistSandbox;
import com.oryxos.tool.sandbox.PermissiveSandbox;
import com.oryxos.tool.sandbox.Sandbox;
import com.oryxos.tool.sandbox.SandboxAction;
import com.oryxos.tool.sandbox.SandboxViolationException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

@DisplayName("工具生产装配安全与启动顺序")
class ToolConfigurationTest {
  @TempDir Path directory;

  static ApplicationContextRunner runner(Class<?>... fixtures) {
    return new ApplicationContextRunner()
        .withUserConfiguration(fixtures)
        .withUserConfiguration(WebhookNotifyAdapter.class)
        .withConfiguration(AutoConfigurations.of(ToolConfiguration.class))
        .withBean(RestClient.Builder.class, RestClient::builder);
  }

  @Test
  @DisplayName("生产装配必须作为Boot自动配置并登记imports")
  void publishesBootAutoConfigurationMetadata() throws Exception {
    assertTrue(ToolConfiguration.class.isAnnotationPresent(AutoConfiguration.class));
    String resource =
        "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
    try (var input = ToolConfiguration.class.getClassLoader().getResourceAsStream(resource)) {
      assertNotNull(input);
      assertTrue(
          new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
              .lines()
              .anyMatch(ToolConfiguration.class.getName()::equals));
    }
  }

  @Test
  @DisplayName("用户Sandbox优先于默认拒绝且不依赖配置类声明顺序")
  void userSandboxWinsBeforeAutoConfiguration() {
    new ApplicationContextRunner()
        .withUserConfiguration(CustomSandboxFixture.class, WebhookNotifyAdapter.class)
        .withConfiguration(AutoConfigurations.of(ToolConfiguration.class))
        .withPropertyValues("http.allowed-domains[0]=https://invalid.example")
        .withBean(RestClient.Builder.class, RestClient::builder)
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(Sandbox.class);
              assertSame(CustomSandboxFixture.CUSTOM, context.getBean(Sandbox.class));
            });
  }

  @Test
  @DisplayName("默认Sandbox只允许配置中的精确HTTP域名且继续拒绝其他动作")
  void defaultSandboxUsesExactHttpDomainList() {
    runner()
        .withPropertyValues("http.allowed_domains[0]=Example.COM.")
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(Sandbox.class);
              Sandbox sandbox = context.getBean(Sandbox.class);
              assertThat(sandbox).isInstanceOf(HttpWhitelistSandbox.class);
              sandbox.enforce(
                  new SandboxAction(ActionType.HTTP_REQUEST, "https://example.com:8443/path"));
              assertThrows(
                  SandboxViolationException.class,
                  () ->
                      sandbox.enforce(
                          new SandboxAction(
                              ActionType.HTTP_REQUEST, "https://sub.example.com/path")));
              assertThrows(
                  SandboxViolationException.class,
                  () -> sandbox.enforce(new SandboxAction(ActionType.FILE_ACCESS, "file.txt")));
            });
  }

  @Test
  @DisplayName("生产缺省拒绝文件动作且测试放行实现不被扫描")
  void defaultsToDenyWithoutTestSandbox() {
    runner()
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(ToolRegistry.class)
                  .hasSingleBean(Sandbox.class);
              assertTrue(context.getBeansOfType(PermissiveSandbox.class).isEmpty());
              var registry = context.getBean(ToolRegistry.class);
              assertEquals(7, registry.all().size());
              String target =
                  directory.resolve("should-not-exist").toString().replace("\\", "\\\\");
              assertThrows(
                  SandboxViolationException.class,
                  () ->
                      registry
                          .asMap()
                          .get("write_file")
                          .execute("{\"path\":\"" + target + "\",\"content\":\"x\"}"));
              assertFalse(Files.exists(directory.resolve("should-not-exist")));
            });
  }

  @Test
  @DisplayName("MCP发现后才发布冻结快照且销毁关闭服务")
  void discoversThenFreezesAndCloses() {
    McpSyncFixture.last = null;
    runner(McpSyncFixture.class, MapConsumer.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var registry = context.getBean(ToolRegistry.class);
              assertEquals(8, registry.all().size());
              assertTrue(table(context.getBean("toolTable")).containsKey("remote"));
              assertEquals(8, context.getBean("consumer", Integer.class));
              assertThrows(
                  IllegalStateException.class,
                  () -> registry.register(ToolRegistryTest.stub("late", "迟到结果", "{}")));
              assertFalse(table(context.getBean("toolTable")).containsKey("late"));
              assertThrows(
                  UnsupportedOperationException.class,
                  () -> table(context.getBean("toolTable")).clear());
              verify(McpSyncFixture.last).connectAll();
            });
    verify(McpSyncFixture.last).close();
  }

  @Test
  @DisplayName("装配把完整Schema与参数校验回调传入MCP而非另一套规则")
  @SuppressWarnings("unchecked")
  void wiresSharedValidationCallbacks() {
    runner()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var service = context.getBean(McpClientService.class);
              var schema =
                  (Consumer<String>) ReflectionTestUtils.getField(service, "schemaValidator");
              var arguments =
                  (BiConsumer<String, String>)
                      ReflectionTestUtils.getField(service, "argumentValidator");
              assertNotNull(schema);
              assertNotNull(arguments);
              assertThrows(
                  IllegalArgumentException.class,
                  () -> schema.accept("{\"$ref\":\"https://invalid/schema\"}"));
              assertThrows(
                  IllegalArgumentException.class,
                  () -> arguments.accept("{\"allOf\":[{\"required\":[\"value\"]}]}", "{}"));
            });
  }

  @Test
  @DisplayName("默认Sandbox在真实MCP启动前拒绝且没有子进程副作用")
  void deniesMcpBeforeProcessStart() throws Exception {
    Path marker = directory.resolve("pid.txt");
    Path config = directory.resolve("mcp_servers.yaml");
    String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    Files.writeString(
        config,
        new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "servers",
                    java.util.List.of(
                        Map.of(
                            "name",
                            "blocked",
                            "transport",
                            "stdio",
                            "command",
                            java.util.List.of(
                                javaExecutable,
                                "-cp",
                                System.getProperty("java.class.path"),
                                "com.oryxos.tool.mcp.McpStdioFixture",
                                "normal",
                                marker.toString()))))));
    runner(McpSyncFixture.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var validator = new ToolArgumentValidator();
              // 独立注册表避免把冻结状态误当作启动安全门。
              try (var service =
                  new McpClientService(
                      config,
                      new ToolRegistry(),
                      context.getBean(Sandbox.class),
                      validator::validateSchema,
                      validator::validateArguments)) {
                service.connectAll();
              }
              assertFalse(Files.exists(marker));
            });
  }

  @Test
  @DisplayName("Memory工具作为内置能力可执行且保留普通插件默认拒绝")
  void registersMemoryToolsAsTrustedBuiltins() {
    runner(MemoryFixture.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              ToolRegistry registry = context.getBean(ToolRegistry.class);
              MemoryService service = context.getBean(MemoryService.class);
              when(service.recall("Java")).thenReturn(List.of("归档Java"));

              var saved =
                  registry
                      .asMap()
                      .get("save_memory")
                      .execute("{\"content\":\"偏好\",\"scope\":\"core\"}");
              var recalled =
                  registry.asMap().get("recall_memory").execute("{\"keyword\":\"Java\"}");

              assertTrue(saved.success());
              assertEquals("已记住", saved.content());
              assertTrue(recalled.success());
              assertEquals("归档Java", recalled.content());
              verify(service).remember("偏好", MemoryScope.CORE);
              verify(service).recall("Java");
            });
  }

  @Test
  @DisplayName("Memory工具名称冲突会阻止冻结发布")
  void rejectsMemoryToolNameConflicts() {
    runner(MemoryFixture.class, MemoryConflictFixture.class)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure()).hasStackTraceContaining("save_memory");
            });
  }

  @SuppressWarnings("unchecked")
  private static Map<String, OryxTool> table(Object value) {
    return (Map<String, OryxTool>) value;
  }

  @Test
  @DisplayName("core的两个Map注入位必须显式限定toolTable")
  void qualifiesBothCoreConsumers() throws Exception {
    var prompt =
        CoreEngineConfiguration.class.getMethod(
            "promptBuilder", ContextLoader.class, ObjectProvider.class, ObjectProvider.class);
    var executor =
        CoreEngineConfiguration.class.getMethod(
            "toolExecutor", ObjectProvider.class, ToolInvocationAudit.class);
    assertNotNull(prompt.getParameters()[2].getAnnotation(Qualifier.class));
    assertNotNull(executor.getParameters()[0].getAnnotation(Qualifier.class));
    assertEquals("toolTable", prompt.getParameters()[2].getAnnotation(Qualifier.class).value());
    assertEquals("toolTable", executor.getParameters()[0].getAnnotation(Qualifier.class).value());
  }

  @Test
  @DisplayName("core消费者共享完整工具集合且其他Map不会混入")
  void injectsCompleteTableOnly() {
    runner()
        .withUserConfiguration(CoreConsumers.class, DecoyMap.class)
        .withBean(Path.class, () -> directory)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var expected = table(context.getBean("toolTable"));
              var prompt = context.getBean(PromptBuilder.class);
              var executor = context.getBean(ToolExecutor.class);
              assertEquals(expected, ReflectionTestUtils.getField(prompt, "toolTable"));
              assertEquals(expected, ReflectionTestUtils.getField(executor, "toolTable"));
              assertFalse(expected.containsKey("decoy"));
              assertThrows(
                  IllegalArgumentException.class,
                  () ->
                      prompt.build(
                          new Session("bad", "test"),
                          ToolRegistryTest.profile(List.of("missing"))));
              assertTrue(
                  prompt
                      .build(new Session("good", "test"), ToolRegistryTest.profile(List.of()))
                      .availableTools()
                      .isEmpty());
            });
  }

  @Test
  @DisplayName("没有tool模块时即使存在其他工具Map也必须注入空表")
  void retainsEmptyFallbackWithoutToolConfiguration() {
    new ApplicationContextRunner()
        .withUserConfiguration(CoreConsumers.class, DecoyMap.class)
        .withBean(Path.class, () -> directory)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertEquals(
                  Map.of(),
                  ReflectionTestUtils.getField(context.getBean(PromptBuilder.class), "toolTable"));
              assertEquals(
                  Map.of(),
                  ReflectionTestUtils.getField(context.getBean(ToolExecutor.class), "toolTable"));
            });
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class CoreConsumers extends CoreEngineConfiguration {
    private final Path workspace;

    CoreConsumers(Path workspace) {
      this.workspace = workspace;
    }

    @Override
    @Bean
    public ContextLoader contextLoader() {
      return new ContextLoader(workspace);
    }

    @Override
    @Bean
    public ProfileRegistry profileRegistry(ObjectProvider<Set<String>> providers) {
      return new ProfileRegistry(List.of());
    }

    @Bean
    ToolInvocationAudit audit() {
      return mock(ToolInvocationAudit.class);
    }

    @Bean
    LlmGateway gateway() {
      return mock(LlmGateway.class);
    }

    @Bean
    SessionManager sessions() {
      return mock(SessionManager.class);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class DecoyMap {
    @Bean
    Map<String, OryxTool> unrelatedToolMap() {
      return Map.of("decoy", ToolRegistryTest.stub("decoy", "其他工具表", "{}"));
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class McpSyncFixture {
    static McpClientService last;

    @Bean
    McpClientService mcpClientService(ToolRegistry registry) {
      last = mock(McpClientService.class);
      doAnswer(
              ignored -> {
                registry.register(ToolRegistryTest.stub("remote", "发现阶段工具", "{}"));
                return null;
              })
          .when(last)
          .connectAll();
      return last;
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class MapConsumer {
    @Bean
    Integer consumer(@Qualifier("toolTable") Map<String, OryxTool> table) {
      return table.size();
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class CustomSandboxFixture {
    static final Sandbox CUSTOM = action -> {};

    @Bean
    Sandbox customSandbox() {
      return CUSTOM;
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class MemoryFixture {
    @Bean
    MemoryService memoryService() {
      return mock(MemoryService.class);
    }

    @Bean
    MemoryTools memoryTools(MemoryService memoryService) {
      return new MemoryTools(memoryService);
    }
  }

  static class MemoryConflict {
    @Tool(name = "save_memory", description = "冲突的记忆工具")
    public String save(String content) {
      return content;
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class MemoryConflictFixture {
    @Bean
    MemoryConflict memoryConflict() {
      return new MemoryConflict();
    }
  }
}
