package com.oryxos.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.oryxos.core.context.ContextLoader;
import com.oryxos.core.memory.MemoryService;
import com.oryxos.core.profile.Profile;
import com.oryxos.core.react.PromptBuilder;
import com.oryxos.core.session.Session;
import com.oryxos.core.tool.OryxTool;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("核心引擎Memory端口装配")
class CoreEngineConfigurationTest {

  @TempDir Path workspace;

  @Test
  @DisplayName("存在MemoryService时注入真实端口且toolTable仍精确限定")
  void injectsMemoryPortWithoutDependingOnImplementation() throws Exception {
    Method factory = memoryAwareFactoryMethod();
    MemoryService memory = mock(MemoryService.class);
    ObjectProvider<MemoryService> memories = provider(memory);
    ObjectProvider<Map<String, OryxTool>> tools = provider(Map.of());

    PromptBuilder builder =
        (PromptBuilder)
            factory.invoke(
                new CoreEngineConfiguration(), new ContextLoader(workspace), memories, tools);

    assertThat(ReflectionTestUtils.getField(builder, "memoryService")).isSameAs(memory);
    Qualifier qualifier = factory.getParameters()[2].getAnnotation(Qualifier.class);
    assertThat(qualifier).isNotNull();
    assertThat(qualifier.value()).isEqualTo("toolTable");
    assertThrows(
        ClassNotFoundException.class,
        () ->
            Class.forName(
                "com.oryxos.memory.MemoryServiceImpl",
                false,
                CoreEngineConfiguration.class.getClassLoader()));
  }

  @Test
  @DisplayName("Memory实现缺席时保持二参构造的历史行为")
  void fallsBackToCompatibleEmptyMemoryPort() throws Exception {
    Method factory = memoryAwareFactoryMethod();
    ObjectProvider<MemoryService> memories = provider(null);
    ObjectProvider<Map<String, OryxTool>> tools = provider(Map.of());
    PromptBuilder builder =
        (PromptBuilder)
            factory.invoke(
                new CoreEngineConfiguration(), new ContextLoader(workspace), memories, tools);
    Session session = new Session("s", "p");
    session.append(new UserMessage("保留历史"));

    assertThat(builder.build(session, profile()).messages().getLast().getText()).isEqualTo("保留历史");
  }

  private static Method memoryAwareFactoryMethod() {
    return Arrays.stream(CoreEngineConfiguration.class.getMethods())
        .filter(method -> "promptBuilder".equals(method.getName()))
        .filter(method -> method.getParameterCount() == 3)
        .findFirst()
        .orElseThrow(() -> new AssertionError("PromptBuilder装配必须接入MemoryService端口"));
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(T value) {
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(value);
    when(provider.getIfAvailable(any(Supplier.class)))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              Supplier<T> fallback = invocation.getArgument(0);
              return value == null ? fallback.get() : value;
            });
    return provider;
  }

  private static Profile profile() {
    return new Profile(
        "p",
        null,
        null,
        null,
        List.of(),
        null,
        null,
        null,
        null,
        null,
        List.of(),
        new Profile.Settings(10, 20),
        null,
        null);
  }
}
