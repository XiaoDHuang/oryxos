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
 * 每次 LLM 调用写一行 {@code llm_calls},成功与失败都写 —— 不留痕的真实故障会摧毁 可审计性这个卖点. 持久化本身失败只记日志并吞掉:审计绝不能弄垮它所观察的调用。
 *
 * @author OryxOS Contributors
 */
@Component
public class LlmCallAudit {

  private static final Logger LOGGER = LoggerFactory.getLogger(LlmCallAudit.class);

  private final LlmCallRepository repository;

  /** 创建以给定仓库为后盾的审计写入器. */
  public LlmCallAudit(LlmCallRepository repository) {
    this.repository = repository;
  }

  /** 记录一次调用;调用在产出 usage 之前失败时 {@code usage} 可为 null. */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "sessionId 在写入日志前做了 CR/LF 清洗;末尾的 Throwable 由 SLF4J 渲染为堆栈," + "而不是日志行。")
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
      LOGGER.error("会话 {} 的 llm_calls 审计持久化失败", sanitize(sessionId), e);
    }
  }

  /** 外部来源的值进入日志行前,先剥掉 CR/LF. */
  private static String sanitize(Object value) {
    return String.valueOf(value).replace('\r', '_').replace('\n', '_');
  }
}
