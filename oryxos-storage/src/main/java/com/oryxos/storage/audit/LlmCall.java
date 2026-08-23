package com.oryxos.storage.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 一次 LLM 调用的审计记录,映射到 {@code llm_calls} 表. 表由手工维护的 {@code db/schema.sql} 创建(绝不由 Hibernate 自动迁移 ——
 * SQLite 的 ALTER TABLE 太弱,无法 安全演进 schema)。
 *
 * @author OryxOS Contributors
 */
@Entity
@Table(name = "llm_calls")
public class LlmCall {

  @Id
  @Column(name = "call_id", nullable = false, length = 64)
  private String callId;

  @Column(name = "session_id")
  private String sessionId;

  @Column(name = "provider")
  private String provider;

  @Column(name = "model")
  private String model;

  @Column(name = "prompt_tokens")
  private Integer promptTokens;

  @Column(name = "completion_tokens")
  private Integer completionTokens;

  @Column(name = "total_tokens")
  private Integer totalTokens;

  @Column(name = "latency_ms")
  private Long latencyMs;

  @Column(name = "status")
  private String status;

  @Column(name = "success", nullable = false)
  private Boolean success;

  @Column(name = "error_message")
  private String errorMessage;

  @Column(name = "started_at")
  private String startedAt;

  @Column(name = "completed_at")
  private String completedAt;

  /** 仅供 JPA 使用的构造器. */
  protected LlmCall() {}

  /** 创建一条完整审计记录;失败的调用 tokens 可为 null. */
  public LlmCall(
      String callId,
      String sessionId,
      String provider,
      String model,
      Integer promptTokens,
      Integer completionTokens,
      Integer totalTokens,
      Long latencyMs,
      String status,
      Boolean success,
      String errorMessage,
      String startedAt,
      String completedAt) {
    this.callId = callId;
    this.sessionId = sessionId;
    this.provider = provider;
    this.model = model;
    this.promptTokens = promptTokens;
    this.completionTokens = completionTokens;
    this.totalTokens = totalTokens;
    this.latencyMs = latencyMs;
    this.status = status;
    this.success = success;
    this.errorMessage = errorMessage;
    this.startedAt = startedAt;
    this.completedAt = completedAt;
  }

  public String getCallId() {
    return callId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public String getProvider() {
    return provider;
  }

  public String getModel() {
    return model;
  }

  public Integer getPromptTokens() {
    return promptTokens;
  }

  public Integer getCompletionTokens() {
    return completionTokens;
  }

  public Integer getTotalTokens() {
    return totalTokens;
  }

  public Long getLatencyMs() {
    return latencyMs;
  }

  public String getStatus() {
    return status;
  }

  public Boolean getSuccess() {
    return success;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public String getStartedAt() {
    return startedAt;
  }

  public String getCompletedAt() {
    return completedAt;
  }
}
