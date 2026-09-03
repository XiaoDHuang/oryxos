package com.oryxos.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class Mem0PropertiesTest {

  private static final String TOKEN =
      Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
  private static final String WORKSPACE = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

  @Test
  void canonicalizesExplicitOriginAndAppliesBoundedDefaultsWithoutLeakingToken() {
    Mem0Properties properties =
        properties("https://MEMORY.Example:443/", TOKEN, WORKSPACE, null, null, null);
    assertThat(properties.baseUrl()).hasToString("https://memory.example");
    assertThat(properties.apiKey()).isEqualTo(TOKEN);
    assertThat(properties.workspaceId()).hasToString(WORKSPACE);
    assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
    assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(properties.operationTimeout()).isEqualTo(Duration.ofSeconds(40));
    assertThat(properties.toString()).doesNotContain(TOKEN);
  }

  @Test
  void acceptsExactTimeoutBoundaries() {
    Mem0Properties minimum =
        properties(
            "https://memory.example",
            TOKEN,
            WORKSPACE,
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5));
    assertThat(minimum.operationTimeout()).isEqualTo(Duration.ofSeconds(5));
    Mem0Properties maximum =
        properties(
            "https://memory.example",
            TOKEN,
            WORKSPACE,
            Duration.ofSeconds(55),
            Duration.ofSeconds(55),
            Duration.ofSeconds(55));
    assertThat(maximum.operationTimeout()).isEqualTo(Duration.ofSeconds(55));
  }

  @Test
  void rejectsNonOriginOrNonHttpsBaseUrls() {
    for (String value :
        List.of(
            "http://memory.example",
            "https://user:secret@memory.example",
            "https://memory.example/path",
            "https://memory.example?query=value",
            "https://memory.example#fragment",
            "https:///missing-host",
            "memory.example",
            "https://memory.example:0",
            "https://memory.example:65536")) {
      assertThatThrownBy(() -> properties(value, TOKEN, WORKSPACE, null, null, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("memory.mem0.base-url无效");
    }
  }

  @Test
  void rejectsNonCanonicalTokenAndWorkspaceId() {
    for (String token :
        List.of(TOKEN + "=", TOKEN.substring(1), "!".repeat(43), TOKEN.substring(0, 42) + "9")) {
      assertThatThrownBy(
              () -> properties("https://memory.example", token, WORKSPACE, null, null, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("memory.mem0.api-key无效");
    }
    for (String workspace :
        List.of(
            "00000000-0000-0000-0000-000000000000",
            WORKSPACE.toUpperCase(),
            WORKSPACE.replace("-", ""))) {
      assertThatThrownBy(
              () -> properties("https://memory.example", TOKEN, workspace, null, null, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("memory.mem0.workspace-id无效");
    }
    assertThatThrownBy(() -> properties("https://memory.example", TOKEN, null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("memory.mem0.workspace-id无效");
  }

  @Test
  void rejectsInvalidOrInconsistentTimeouts() {
    for (Duration[] values :
        List.of(
            new Duration[] {Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(5)},
            new Duration[] {Duration.ofSeconds(1), Duration.ZERO, Duration.ofSeconds(5)},
            new Duration[] {Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(4)},
            new Duration[] {Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(56)},
            new Duration[] {Duration.ofSeconds(6), Duration.ofSeconds(1), Duration.ofSeconds(5)},
            new Duration[] {Duration.ofSeconds(1), Duration.ofSeconds(6), Duration.ofSeconds(5)})) {
      assertThatThrownBy(
              () ->
                  properties(
                      "https://memory.example", TOKEN, WORKSPACE, values[0], values[1], values[2]))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("memory.mem0超时配置无效");
    }
  }

  @Test
  void validationErrorsAndStackTracesDoNotEchoSecret() {
    String secret = "secret-value-that-must-not-appear";
    Throwable failure =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> properties("https://memory.example", secret, WORKSPACE, null, null, null));
    assertThat(failure).isInstanceOf(IllegalArgumentException.class);
    StringWriter stack = new StringWriter();
    failure.printStackTrace(new PrintWriter(stack));
    assertThat(failure.getMessage()).doesNotContain(secret);
    assertThat(stack.toString()).doesNotContain(secret);
  }

  private static Mem0Properties properties(
      String baseUrl,
      String apiKey,
      String workspaceId,
      Duration connectTimeout,
      Duration readTimeout,
      Duration operationTimeout) {
    return new Mem0Properties(
        baseUrl, apiKey, workspaceId, connectTimeout, readTimeout, operationTimeout);
  }
}
