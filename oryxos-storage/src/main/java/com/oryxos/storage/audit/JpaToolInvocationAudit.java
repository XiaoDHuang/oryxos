package com.oryxos.storage.audit;

import com.oryxos.core.react.ToolInvocationAudit;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * JPA 实现的 {@link ToolInvocationAudit}. 持久化失败只记日志并吞掉:审计绝不能弄垮它所 观察的工具执行(口径与 LlmCallAudit 相同)。
 *
 * @author OryxOS Contributors
 */
@Component
public class JpaToolInvocationAudit implements ToolInvocationAudit {

  private static final Logger LOGGER = LoggerFactory.getLogger(JpaToolInvocationAudit.class);

  private final ToolInvocationRepository repository;

  /** 创建以给定仓库为后盾的审计写入器. */
  public JpaToolInvocationAudit(ToolInvocationRepository repository) {
    this.repository = repository;
  }

  /** 持久化一行 tool_invocations;失败只记日志,绝不向外传播. */
  @Override
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "sessionId 与异常消息在写入日志前都做了 CR/LF 清洗;末尾的 Throwable 由 SLF4J" + " 渲染为堆栈,而不是日志行。")
  public void record(
      String sessionId,
      String profileName,
      String toolName,
      String parameters,
      boolean success,
      String result,
      String errorMessage,
      long latencyMs) {
    try {
      Instant completedAt = Instant.now();
      repository.save(
          new ToolInvocation(
              UUID.randomUUID().toString(),
              sessionId,
              profileName,
              toolName,
              parameters,
              success ? "completed" : "failed",
              result,
              errorMessage,
              success,
              errorMessage,
              completedAt.minusMillis(latencyMs).toString(),
              completedAt.toString(),
              null));
    } catch (RuntimeException e) {
      LOGGER.error(
          "会话 {} 的 tool_invocations 审计持久化失败: {}", sanitize(sessionId), sanitize(e.getMessage()), e);
    }
  }

  /** 外部来源的值进入日志行前,先剥掉 CR/LF. */
  private static String sanitize(Object value) {
    return String.valueOf(value).replace('\r', '_').replace('\n', '_');
  }
}
