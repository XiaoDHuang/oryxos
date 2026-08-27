package com.oryxos.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.oryxos.core.react.ProfileContext;
import com.oryxos.core.react.ToolExecutor;
import com.oryxos.core.react.ToolInvocationAudit;
import com.oryxos.tool.sandbox.SandboxViolationException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@DisplayName("Java插件Bean自动发现与唯一执行链")
class JavaPluginRegistrationTest {
  @Test
  @DisplayName("普通与JDK代理Bean自动注册但插件默认guard拒绝")
  void discoversBeansWithoutGrantingPermission() {
    ToolConfigurationTest.runner()
        .withUserConfiguration(PluginBeans.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var registry = context.getBean(ToolRegistry.class);
              assertTrue(registry.contains("plugin_echo"));
              assertTrue(registry.contains("proxy_echo"));
              assertThrows(
                  SandboxViolationException.class,
                  () -> registry.asMap().get("plugin_echo").execute("{\"text\":\"x\"}"));
              assertEquals(0, context.getBean(Plugin.class).calls.get());
            });
  }

  @Test
  @DisplayName("插件名称与内置冲突会明确阻止发布")
  void rejectsConflicts() {
    ToolConfigurationTest.runner()
        .withUserConfiguration(ConflictingBeans.class)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure()).hasStackTraceContaining("read_file");
            });
  }

  @Test
  @DisplayName("测试显式许可的Spring插件仍只执行和审计一次")
  void executesPermittedBeanThroughSingleExecutor() {
    ToolConfigurationTest.runner()
        .withUserConfiguration(PluginBeans.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              Plugin plugin = context.getBean(Plugin.class);
              ToolRegistry permitted = new ToolRegistry();
              permitted.registerAnnotated(plugin, () -> {});
              ToolInvocationAudit audit = mock(ToolInvocationAudit.class);
              ProfileContext.set(ToolRegistryTest.profile(List.of("plugin_echo")));
              try {
                var result =
                    new ToolExecutor(permitted.asMap(), audit)
                        .execute(
                            "s",
                            new AssistantMessage.ToolCall(
                                "c", "function", "plugin_echo", "{\"text\":\"原样返回\"}"));
                assertTrue(result.success());
                assertEquals("原样返回", result.content());
                assertEquals(1, plugin.calls.get());
                verify(audit)
                    .record(
                        eq("s"),
                        eq("test"),
                        eq("plugin_echo"),
                        anyString(),
                        eq(true),
                        eq("原样返回"),
                        isNull(),
                        anyLong());
                verifyNoMoreInteractions(audit);
              } finally {
                ProfileContext.clear();
              }
            });
  }

  public static class Plugin {
    final AtomicInteger calls = new AtomicInteger();

    @Tool(name = "plugin_echo", description = "Java业务回显")
    public String echo(String text) {
      calls.incrementAndGet();
      return text;
    }
  }

  public static class Conflict {
    @Tool(name = "read_file", description = "冲突声明")
    public String read(String path) {
      return path;
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class PluginBeans {
    @Bean
    Plugin plugin() {
      return new Plugin();
    }

    @Bean
    AnnotatedToolAdapterTest.ProxyContract proxyPlugin() {
      return (AnnotatedToolAdapterTest.ProxyContract)
          new ProxyFactory(new AnnotatedToolAdapterTest.ProxyFixture()).getProxy();
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ConflictingBeans {
    @Bean
    Conflict conflict() {
      return new Conflict();
    }
  }
}
