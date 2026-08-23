package com.oryxos.provider;

import com.oryxos.storage.audit.LlmCall;
import com.oryxos.storage.audit.LlmCallRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Component;

/**
 * Writes one {@code llm_calls} row per LLM call, on success and on failure alike — a real incident
 * that leaves no trace defeats the auditability selling point. Persistence failure itself is logged
 * and swallowed: auditing must never break the call it observes.
 *
 * @author OryxOS Contributors
 */
@Component
public class LlmCallAudit {

  private static final Logger LOGGER = LoggerFactory.getLogger(LlmCallAudit.class);

  private final LlmCallRepository repository;

  /** Creates the audit writer backed by the given repository. */
  public LlmCallAudit(LlmCallRepository repository) {
    this.repository = repository;
  }

  /** Records one call; {@code usage} may be null when the call failed before producing one. */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "sessionId is CR/LF-sanitized before logging; the trailing Throwable is rendered by"
              + " SLF4J as a stack trace, not a log line.")
  public void record(
      String sessionId,
      String provider,
      String model,
      Usage usage,
      boolean success,
      String errorMessage,
      long latencyMs) {
    try {
      Instant completedAt = Instant.now();
      LlmCall call =
          new LlmCall(
              UUID.randomUUID().toString(),
              sessionId,
              provider,
              model,
              usage == null ? null : usage.getPromptTokens(),
              usage == null ? null : usage.getCompletionTokens(),
              usage == null ? null : usage.getTotalTokens(),
              latencyMs,
              success ? "completed" : "failed",
              success,
              errorMessage,
              completedAt.minusMillis(latencyMs).toString(),
              completedAt.toString());
      repository.save(call);
    } catch (RuntimeException e) {
      LOGGER.error("Failed to persist llm_calls audit for session {}", sanitize(sessionId), e);
    }
  }

  /** Strips CR/LF from externally-sourced values before they enter log lines. */
  private static String sanitize(Object value) {
    return String.valueOf(value).replace('\r', '_').replace('\n', '_');
  }
}
