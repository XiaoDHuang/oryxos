package com.oryxos.storage.schedule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 一次定时执行的持久化记录,对应 task_executions 表. 进行中限定 success/errorMessage/durationMs 全 NULL; unknown(进程中断)只写
 * false+固定文本,durationMs 保持 NULL,不用零占位。
 *
 * @author OryxOS Contributors
 */
@Entity
@Table(name = "task_executions")
public class TaskExecution {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "execution_id", nullable = false)
  private Long executionId;

  @Column(name = "task_id", nullable = false)
  private String taskId;

  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(name = "started_at", nullable = false)
  private Long startedAt;

  @Column(name = "success")
  private Boolean success;

  @Column(name = "error_message")
  private String errorMessage;

  @Column(name = "duration_ms")
  private Long durationMs;

  /** JPA 专用构造器. */
  protected TaskExecution() {}

  /** 新建一条进行中的执行记录(begin). */
  public TaskExecution(String taskId, String sessionId, Long startedAt) {
    this.taskId = taskId;
    this.sessionId = sessionId;
    this.startedAt = startedAt;
  }

  /** 写入终态(只允许对仍进行中的行调用,条件由 Store 的原子更新保证). */
  public void applyTerminal(Boolean newSuccess, String newErrorMessage, Long newDurationMs) {
    this.success = newSuccess;
    this.errorMessage = newErrorMessage;
    this.durationMs = newDurationMs;
  }

  /** 启动恢复时标记进程中断未知:durationMs 保持 NULL. */
  public void applyUnknown(String unknownMessage) {
    this.success = false;
    this.errorMessage = unknownMessage;
    this.durationMs = null;
  }

  public Long getExecutionId() {
    return executionId;
  }

  public String getTaskId() {
    return taskId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public Long getStartedAt() {
    return startedAt;
  }

  public Boolean getSuccess() {
    return success;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public Long getDurationMs() {
    return durationMs;
  }
}
