package com.oryxos.memory;

import com.oryxos.core.memory.MemoryScope;
import com.oryxos.storage.memory.MemoryEntry;
import com.oryxos.storage.memory.MemoryEntryRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SQLite原文后端,不将持久历史当作有限缓存.
 *
 * @author OryxOS Contributors
 */
public final class SqliteMemoryStore implements LongTermMemoryStore {

  private final MemoryEntryRepository repository;
  private final TransactionTemplate writes;
  private final TransactionTemplate reads;

  /** 使用既有SQLite数据源与事务边界. */
  public SqliteMemoryStore(
      MemoryEntryRepository repository, PlatformTransactionManager transactions) {
    this.repository = Objects.requireNonNull(repository, "记忆仓储不能为空");
    Objects.requireNonNull(transactions, "记忆事务管理器不能为空");
    writes = new TransactionTemplate(transactions);
    // 不把外层事务尚未提交的写入误报为保存成功。
    writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    reads = new TransactionTemplate(transactions);
    reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    reads.setReadOnly(true);
  }

  @Override
  public void append(String content, MemoryScope scope) {
    requireText(content, "记忆内容不能为空");
    MemoryScope selected = scope == null ? MemoryScope.ARCHIVAL : scope;
    try {
      writes.executeWithoutResult(
          status -> repository.saveAndFlush(new MemoryEntry(selected, content, Instant.now())));
    } catch (RuntimeException exception) {
      throw new IllegalStateException("SQLite记忆保存失败");
    }
  }

  @Override
  public String load() {
    return read(
        () -> {
          List<String> sections = new ArrayList<>();
          List<MemoryEntry> core = repository.findByScopeOrderByCreatedAtAscIdAsc(MemoryScope.CORE);
          List<MemoryEntry> archival =
              new ArrayList<>(
                  repository.findTop100ByScopeOrderByCreatedAtDescIdDesc(MemoryScope.ARCHIVAL));
          Collections.reverse(archival);
          appendSection(sections, "## 核心记忆", core);
          appendSection(sections, "## 归档记忆", archival);
          return String.join("\n\n", sections);
        });
  }

  @Override
  public List<String> recall(String query) {
    requireText(query, "记忆检索关键词不能为空");
    return read(() -> repository.recall(query).stream().map(MemoryEntry::getContent).toList());
  }

  private <T> T read(Supplier<T> action) {
    try {
      return reads.execute(status -> action.get());
    } catch (RuntimeException exception) {
      throw new IllegalStateException("SQLite记忆读取失败");
    }
  }

  private static void appendSection(
      List<String> sections, String header, List<MemoryEntry> entries) {
    if (!entries.isEmpty()) {
      sections.add(
          header
              + '\n'
              + String.join("\n", entries.stream().map(MemoryEntry::getContent).toList()));
    }
  }

  private static void requireText(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
  }
}
