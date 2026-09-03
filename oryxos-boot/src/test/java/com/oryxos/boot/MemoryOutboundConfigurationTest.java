package com.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.oryxos.core.memory.MemoryService;
import com.oryxos.memory.MemoryOutboundGuard;
import com.oryxos.tool.sandbox.HttpWhitelistSandbox;
import com.oryxos.tool.sandbox.Sandbox;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MemoryOutboundConfigurationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(MemoryOutboundConfiguration.class));

  @Test
  void defaultBackendDoesNotCreateOrUseGuard() {
    Sandbox sandbox = mock(Sandbox.class);
    runner
        .withBean(Sandbox.class, () -> sandbox)
        .run(
            context -> {
              assertThat(context).hasNotFailed().doesNotHaveBean(MemoryOutboundGuard.class);
              verifyNoInteractions(sandbox);
            });
  }

  @Test
  void selectedMem0UsesRealSandboxAndSanitizesDenial() {
    runner
        .withPropertyValues("memory.backend=mem0")
        .withBean(Sandbox.class, () -> new HttpWhitelistSandbox(List.of("memory.example")))
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(MemoryOutboundGuard.class);
              MemoryOutboundGuard guard = context.getBean(MemoryOutboundGuard.class);
              guard.check(URI.create("https://memory.example/oryx-memory/v1/capabilities"));
              assertThatThrownBy(() -> guard.check(URI.create("https://unapproved.example/secret")))
                  .isInstanceOf(SecurityException.class)
                  .hasMessageNotContaining("secret");
            });
  }

  @Test
  void missingSandboxFailsSelectedBackend() {
    runner
        .withPropertyValues("memory.backend=mem0")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void customGuardBacksOffWithoutRequiringSandbox() {
    MemoryOutboundGuard custom = mock(MemoryOutboundGuard.class);
    runner
        .withPropertyValues("memory.backend=mem0")
        .withBean(MemoryOutboundGuard.class, () -> custom)
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(MemoryOutboundGuard.class);
              assertThat(context.getBean(MemoryOutboundGuard.class)).isSameAs(custom);
              verifyNoInteractions(custom);
            });
  }

  @Test
  void customMemoryServiceDoesNotRequireUnusedGuardOrSandbox() {
    runner
        .withPropertyValues("memory.backend=mem0")
        .withBean(MemoryService.class, () -> mock(MemoryService.class))
        .run(
            context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(MemoryOutboundGuard.class));
  }
}
