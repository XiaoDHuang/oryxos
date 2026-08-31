package com.oryxos.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.oryxos.core.memory.MemoryService;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MemoryConfigurationTest {

  @TempDir Path workspace;

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(MemoryConfiguration.class));

  @Test
  void defaultsToUniqueMarkdownWithoutResolvingRemoteSecrets() {
    runner
        .withPropertyValues("memory.mem0.api-key=${UNDEFINED_MEM0_SECRET_007}")
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(LongTermMemoryStore.class)
                  .hasSingleBean(MemoryService.class)
                  .hasSingleBean(MemoryTools.class);
              assertThat(context.getBean(LongTermMemoryStore.class))
                  .isInstanceOf(MarkdownMemoryStore.class);
            });
  }

  @Test
  void usesCustomLegacyMemoryAndPreservesAcknowledgement() {
    LongTermMemory memory = new LongTermMemory(workspace);
    runner
        .withBean(LongTermMemory.class, () -> memory)
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(LongTermMemory.class);
              assertThat(context.getBean(MemoryTools.class).saveMemory("偏好", null))
                  .isEqualTo("已记住");
              assertThat(memory.recallByKeyword("偏好")).hasSize(1);
            });
  }

  @Test
  void customServiceDoesNotCreateOrTouchUnusedStores() {
    MemoryService service = mock(MemoryService.class);
    runner
        .withBean(MemoryService.class, () -> service)
        .withPropertyValues(
            "memory.backend=mem0", "memory.mem0.api-key=${UNDEFINED_MEM0_SECRET_007}")
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .doesNotHaveBean(LongTermMemoryStore.class)
                  .doesNotHaveBean(LongTermMemory.class);
              assertThat(context.getBean(MemoryService.class)).isSameAs(service);
              verifyNoInteractions(service);
            });
  }

  @Test
  void rejectsInvalidBackendEvenWhenServiceIsOverridden() {
    for (String backend : new String[] {"", "other", "MARKDOWN", "markdown,sqlite"}) {
      runner
          .withBean(MemoryService.class, () -> mock(MemoryService.class))
          .withPropertyValues("memory.backend=" + backend)
          .run(context -> assertThat(context).hasFailed());
    }
  }

  @Test
  void unavailableSelectedBackendDoesNotFallBackToMarkdown() {
    for (String backend : new String[] {"sqlite", "mem0"}) {
      runner
          .withPropertyValues("memory.backend=" + backend)
          .run(context -> assertThat(context).hasFailed());
    }
  }
}
