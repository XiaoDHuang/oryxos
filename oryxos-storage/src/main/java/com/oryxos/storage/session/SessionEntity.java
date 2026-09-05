package com.oryxos.storage.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 持久化形态的会话记录,对应 sessions 表. 运行时形态是 core 的 {@code Session}(持有活消息 对象),两者经 {@code JpaSessionManager}
 * 双向转换;对话历史整列 JSON 存取,核心阶段不按条拆表。 表结构只由手工脚本 db/schema.sql 维护(SQLite 的 ALTER TABLE 太弱,不靠 Hibernate
 * 迁移)。
 *
 * @author OryxOS Contributors
 */
@Entity
@Table(name = "sessions")
public class SessionEntity {

  @Id
  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(name = "profile_name", nullable = false)
  private String profileName;

  @Column(name = "channel")
  private String channel;

  @Column(name = "user_id")
  private String userId;

  @Column(name = "messages_json")
  private String messagesJson;

  @Column(name = "context_state")
  private String contextState;

  @Column(name = "status", nullable = false)
  private String status;

  @Column(name = "created_at")
  private String createdAt;

  @Column(name = "last_active_at")
  private String lastActiveAt;

  @Column(name = "archived_at")
  private String archivedAt;

  /** JPA 专用构造器. */
  protected SessionEntity() {}

  /** 新建一条会话持久化记录. */
  public SessionEntity(
      String sessionId,
      String profileName,
      String channel,
      String userId,
      String messagesJson,
      String contextState,
      String status,
      String createdAt,
      String lastActiveAt,
      String archivedAt) {
    this.sessionId = sessionId;
    this.profileName = profileName;
    this.channel = channel;
    this.userId = userId;
    this.messagesJson = messagesJson;
    this.contextState = contextState;
    this.status = status;
    this.createdAt = createdAt;
    this.lastActiveAt = lastActiveAt;
    this.archivedAt = archivedAt;
  }

  public String getSessionId() {
    return sessionId;
  }

  public String getProfileName() {
    return profileName;
  }

  public String getChannel() {
    return channel;
  }

  public String getUserId() {
    return userId;
  }

  public String getMessagesJson() {
    return messagesJson;
  }

  /** 更新历史快照与最后活跃时间(save 时调用). */
  public void updateHistory(String newMessagesJson, String newLastActiveAt) {
    this.messagesJson = newMessagesJson;
    this.lastActiveAt = newLastActiveAt;
  }

  /** 置归档状态与归档时间;仅在未归档时调用(幂等判断归 SessionManager). */
  public void markArchived(String newArchivedAt) {
    this.status = "archived";
    this.archivedAt = newArchivedAt;
  }

  public String getContextState() {
    return contextState;
  }

  public String getStatus() {
    return status;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public String getLastActiveAt() {
    return lastActiveAt;
  }

  public String getArchivedAt() {
    return archivedAt;
  }
}
