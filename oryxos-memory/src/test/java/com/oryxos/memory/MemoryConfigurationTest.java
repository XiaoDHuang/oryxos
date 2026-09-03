package com.oryxos.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.oryxos.core.memory.MemoryService;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MemoryConfigurationTest {

  private static final String MEM0_TOKEN =
      java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
  private static final String MEM0_WORKSPACE = "11111111-1111-4111-8111-111111111111";

  @TempDir Path workspace;

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(Mem0PropertiesConfiguration.class, MemoryConfiguration.class));

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

  @Test
  void selectedMem0BindsStrictPropertiesBeforeReportingMissingGuard() {
    runner
        .withPropertyValues(validMem0Properties())
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("MemoryOutboundGuard");
              assertThat(stackTrace(context.getStartupFailure())).doesNotContain(MEM0_TOKEN);
            });
  }

  @Test
  void selectedMem0RejectsSecretWithoutEchoingIt() {
    String secret = "invalid-secret-must-not-appear";
    runner
        .withPropertyValues(
            "memory.backend=mem0",
            "memory.mem0.base-url=https://memory.example",
            "memory.mem0.api-key=" + secret,
            "memory.mem0.workspace-id=" + MEM0_WORKSPACE)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("memory.mem0.api-key无效");
              assertThat(stackTrace(context.getStartupFailure())).doesNotContain(secret);
            });
  }

  @Test
  void customStoreDoesNotResolveUnusedMem0Secrets() {
    LongTermMemoryStore store = mock(LongTermMemoryStore.class);
    runner
        .withBean(LongTermMemoryStore.class, () -> store)
        .withPropertyValues(
            "memory.backend=mem0", "memory.mem0.api-key=${UNDEFINED_MEM0_SECRET_007}")
        .run(
            context -> {
              assertThat(context).hasNotFailed().doesNotHaveBean(Mem0Properties.class);
              assertThat(context.getBean(LongTermMemoryStore.class)).isSameAs(store);
              verifyNoInteractions(store);
            });
  }

  @Test
  void selectedStoreChecksActualHttpsCapabilitiesBeforeBecomingReady() throws Exception {
    try (HttpsFixture https = HttpsFixture.open()) {
      final SSLContext previous = SSLContext.getDefault();
      final AtomicInteger checks = new AtomicInteger();
      final MemoryOutboundGuard guard =
          target -> {
            assertThat(target.getAuthority()).isEqualTo(https.uri("/").getAuthority());
            checks.incrementAndGet();
          };
      try {
        // 仅隔离测试JVM临时信任本用例CA，结束后恢复；从未使用trust-all。
        SSLContext.setDefault(https.clientSslContext());
        String caps =
            """
            {"request_id":"22222222-2222-4222-8222-222222222222",
             "protocol":"oryx-memory-v1","schema_version":1,"sdk_version":"1.0.11+oryx.1",
             "staged_engine":true,"atomic_history":true,"revision_pagination":true,
             "build_version":"0000000000000000000000000000000000000000000000000000000000000000",
             "limits":{"content_max_bytes":32768,"request_max_bytes":262144,"response_max_bytes":1048576,
              "page_size_max":100,"recall_top":20,"operation_deadline_seconds":30}}
            """;
        https
            .server()
            .enqueue(
                new MockResponse().setHeader("Content-Type", "application/json").setBody(caps));
        ApplicationContextRunner selected =
            runner
                .withPropertyValues(validMem0Properties())
                .withPropertyValues("memory.mem0.base-url=" + https.uri("/"))
                .withBean(MemoryOutboundGuard.class, () -> guard);
        selected.run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(LongTermMemoryStore.class)
                  .hasSingleBean(MemoryService.class)
                  .doesNotHaveBean(LongTermMemory.class);
              assertThat(context.getBean(LongTermMemoryStore.class))
                  .isInstanceOf(Mem0MemoryStore.class);
            });
        https
            .server()
            .enqueue(
                new MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(caps.replace("oryx-memory-v1", "other-protocol")));
        selected.run(context -> assertThat(context).hasFailed());
        assertThat(checks).hasValue(2);
        assertThat(https.server().getRequestCount()).isEqualTo(2);
      } finally {
        SSLContext.setDefault(previous);
      }
    }
  }

  private static String[] validMem0Properties() {
    return new String[] {
      "memory.backend=mem0",
      "memory.mem0.base-url=https://memory.example",
      "memory.mem0.api-key=" + MEM0_TOKEN,
      "memory.mem0.workspace-id=" + MEM0_WORKSPACE
    };
  }

  private static String stackTrace(Throwable failure) {
    var output = new java.io.StringWriter();
    failure.printStackTrace(new java.io.PrintWriter(output));
    return output.toString();
  }
}
