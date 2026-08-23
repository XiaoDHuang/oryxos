package com.oryxos.storage.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 一次工具执行的审计记录,映射到 {@code tool_invocations} 表(由手工维护的 {@code db/schema.sql} 创建). 口径与 {@code
 * llm_calls} 相同:成功与失败都落库,带 {@code success} / {@code error_message} 两列。
 *
 * @author OryxOS Contributors
 */
@Entity
@Table(name = "tool_invocations")
public class ToolInvocation {

  @Id
  @Column(name = "invocation_id", nullable = false, length = 64)
  private String invocationId;

  @Column(name = "session_id")
  private String sessionId;

  @Column(name = "profile_name")
  private String profileName;

  @Column(name = "tool_name", nullable = false)
  private String toolName;

  @Column(name = "parameters")
  private String parameters;

  @Column(name = "status", nullable = false)
  private String status;

  @Column(name = "result")
  private String result;

  @Column(name = "error")
  private String error;

  @Column(name = "success", nullable = false)
  private Boolean success;

  @Column(name = "error_message")
  private String errorMessage;

  @Column(name = "started_at")
  private String startedAt;

  @Column(name = "completed_at")
  private String completedAt;

  @Column(name = "token_cost")
  private Integer tokenCost;

  /** 仅供 JPA 使用的构造器. */
  protected ToolInvocation() {}

  /** 为一次工具执行创建完整审计记录. */
  public ToolInvocation(
      String invocationId,
      String sessionId,
      String profileName,
      String toolName,
      String parameters,
      String status,
      String result,
      String error,
      Boolean success,
      String errorMessage,
      String startedAt,
      String completedAt,
      Integer tokenCost) {
    this.invocationId = invocationId;
    this.sessionId = sessionId;
    this.profileName = profileName;
    this.toolName = toolName;
    this.parameters = parameters;
    this.status = status;
    this.result = result;
    this.error = error;
    this.success = success;
    this.errorMessage = errorMessage;
    this.startedAt = startedAt;
    this.completedAt = completedAt;
    this.tokenCost = tokenCost;
  }

  public String getInvocationId() {
    return invocationId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public String getProfileName() {
    return profileName;
  }

  public String getToolName() {
    return toolName;
  }

  public String getParameters() {
    return parameters;
  }

  public String getStatus() {
    return status;
  }

  public String getResult() {
    return result;
  }

  public String getError() {
    return error;
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

  public Integer getTokenCost() {
    return tokenCost;
  }
}
