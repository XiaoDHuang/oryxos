package com.oryxos.storage.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Audit record of one LLM call, mapped to the {@code llm_calls} table. The table is created by the
 * hand-maintained {@code db/schema.sql} (never by Hibernate auto-migration — SQLite's ALTER TABLE
 * is too weak to evolve schemas safely).
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

  /** JPA-only constructor. */
  protected LlmCall() {}

  /** Creates a complete audit record; tokens may be null for failed calls. */
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
