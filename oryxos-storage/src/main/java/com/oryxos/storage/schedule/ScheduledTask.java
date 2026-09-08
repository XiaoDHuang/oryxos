package com.oryxos.storage.schedule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 定时任务持久化记录,对应 scheduled_tasks 表. 时间列是 UTC epoch 毫秒(Long),视图层的 Instant 转换归 Store; 运行时索引(catalog)在
 * core,本实体只承载持久化状态。
 *
 * @author OryxOS Contributors
 */
@Entity
@Table(name = "scheduled_tasks")
public class ScheduledTask {

  @Id
  @Column(name = "task_id", nullable = false)
  private String taskId;

  @Column(name = "profile_name", nullable = false)
  private String profileName;

  @Column(name = "cron", nullable = false)
  private String cron;

  @Column(name = "zone", nullable = false)
  private String zone;

  @Column(name = "message", nullable = false)
  private String message;

  @Column(name = "enabled", nullable = false)
  private Boolean enabled;

  @Column(name = "next_run_at")
  private Long nextRunAt;

  @Column(name = "last_run_at")
  private Long lastRunAt;

  @Column(name = "last_status")
  private String lastStatus;

  @Column(name = "run_count", nullable = false)
  private Long runCount;

  /** JPA 专用构造器. */
  protected ScheduledTask() {}

  /** 新建一条任务登记(初始 enabled=true,计数为零). */
  public ScheduledTask(
      String taskId,
      String profileName,
      String cron,
      String zone,
      String message,
      Boolean enabled,
      Long nextRunAt,
      Long lastRunAt,
      String lastStatus,
      Long runCount) {
    this.taskId = taskId;
    this.profileName = profileName;
    this.cron = cron;
    this.zone = zone;
    this.message = message;
    this.enabled = enabled;
    this.nextRunAt = nextRunAt;
    this.lastRunAt = lastRunAt;
    this.lastStatus = lastStatus;
    this.runCount = runCount;
  }

  /** 同 Profile 重登记:只更新定义快照与候选时间,enabled/计数/历史不动. */
  public void updateDefinition(
      String newCron, String newZone, String newMessage, Long newNextRunAt) {
    this.cron = newCron;
    this.zone = newZone;
    this.message = newMessage;
    this.nextRunAt = newNextRunAt;
  }

  /** 更新下一次候选(失效规则传 null,行与历史保留). */
  public void applyNextRun(Long newNextRunAt) {
    this.nextRunAt = newNextRunAt;
  }

  /** 持久化启停与对应候选时间. */
  public void applyEnabled(Boolean newEnabled, Long newNextRunAt) {
    this.enabled = newEnabled;
    this.nextRunAt = newNextRunAt;
  }

  /** Begin 时同步最近开始/运行中状态并加一次计数. */
  public void markRunStarted(Long startedAt) {
    this.lastRunAt = startedAt;
    this.lastStatus = "running";
    this.runCount = this.runCount + 1;
  }

  /** Finish 时同步最近一次终态. */
  public void applyTerminalStatus(String terminalStatus) {
    this.lastStatus = terminalStatus;
  }

  /** 启动恢复时把最近一次标记为进程中断未知. */
  public void applyUnknownStatus() {
    this.lastStatus = "unknown";
  }

  public String getTaskId() {
    return taskId;
  }

  public String getProfileName() {
    return profileName;
  }

  public String getCron() {
    return cron;
  }

  public String getZone() {
    return zone;
  }

  public String getMessage() {
    return message;
  }

  public Boolean getEnabled() {
    return enabled;
  }

  public Long getNextRunAt() {
    return nextRunAt;
  }

  public Long getLastRunAt() {
    return lastRunAt;
  }

  public String getLastStatus() {
    return lastStatus;
  }

  public Long getRunCount() {
    return runCount;
  }
}
