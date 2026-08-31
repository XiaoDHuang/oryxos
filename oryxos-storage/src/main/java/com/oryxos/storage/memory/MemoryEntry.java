package com.oryxos.storage.memory;

import com.oryxos.core.memory.MemoryScope;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * 一次保存对应一行原文,不以窗口裁剪修改历史.
 *
 * @author OryxOS Contributors
 */
@Entity
@Table(name = "memory_entries")
public class MemoryEntry {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private MemoryScope scope;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String content;

  @Convert(converter = UtcMillisInstantConverter.class)
  @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMP")
  private Instant createdAt;

  /** JPA专用构造器. */
  protected MemoryEntry() {}

  /** 保留原始文本,不因分区格式或查询规则改写输入. */
  public MemoryEntry(MemoryScope scope, String content, Instant createdAt) {
    this.scope = scope;
    this.content = content;
    this.createdAt = createdAt;
  }

  @PrePersist
  void validateBeforeInsert() {
    // 实体需要保持可代理,在持久化边界校验避免构造失败暴露半初始化对象。
    Objects.requireNonNull(scope, "记忆范围不能为空");
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("记忆内容不能为空");
    }
    Objects.requireNonNull(createdAt, "记忆时间不能为空");
  }

  public Long getId() {
    return id;
  }

  public MemoryScope getScope() {
    return scope;
  }

  public String getContent() {
    return content;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
