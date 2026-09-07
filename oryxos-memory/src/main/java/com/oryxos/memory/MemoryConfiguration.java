package com.oryxos.memory;

import com.oryxos.core.memory.MemoryService;
import com.oryxos.storage.memory.MemoryEntryRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Memory实现保持在能力模块,core只看到端口.
 *
 * @author OryxOS Contributors
 */
@AutoConfiguration
@EnableConfigurationProperties(MemoryProperties.class)
class MemoryConfiguration {

  private static final String SQLITE_PRODUCT = "SQLite";
  private static final String MAIN_DATABASE = "main";
  private static final String SCOPE_INDEX = "idx_memory_scope";
  private static final String SCOPE_COLUMN = "scope";
  private static final String METADATA_NAME = "name";
  private static final Pattern AUTO_ID =
      Pattern.compile(
          "\\bID\\s+INTEGER\\s+PRIMARY\\s+KEY\\s+AUTOINCREMENT\\b", Pattern.CASE_INSENSITIVE);

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(name = "memory.backend", havingValue = "mem0")
  @ConditionalOnMissingBean({MemoryService.class, LongTermMemoryStore.class})
  static class Mem0Configuration {
    @Bean
    Mem0MemoryStore mem0MemoryStore(Mem0Properties properties, MemoryOutboundGuard guard) {
      return new Mem0MemoryStore(properties, guard);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(name = "memory.backend", havingValue = "sqlite")
  @ConditionalOnMissingBean({MemoryService.class, LongTermMemoryStore.class})
  static class SqliteConfiguration {

    @Bean
    SqliteMemoryStore sqliteMemoryStore(
        MemoryEntryRepository repository, PlatformTransactionManager transactions) {
      return new SqliteMemoryStore(repository, transactions);
    }

    @Bean
    SmartInitializingSingleton sqliteMemorySchema(DataSource source) {
      // SQL脚本和JPA都完成初始化后才查元数据,兼容defer-datasource-initialization。
      return () -> validateSqliteSchema(source);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(name = "memory.backend", havingValue = "markdown", matchIfMissing = true)
  static class MarkdownConfiguration {

    @Value("${oryxos.root:.oryxos}")
    private String workspaceRoot = ".oryxos";

    @Bean
    @ConditionalOnMissingBean({
      LongTermMemory.class,
      LongTermMemoryStore.class,
      MemoryService.class
    })
    LongTermMemory longTermMemory() {
      return new LongTermMemory(Path.of(workspaceRoot));
    }

    @Bean
    @ConditionalOnMissingBean({LongTermMemoryStore.class, MemoryService.class})
    LongTermMemoryStore markdownMemoryStore(LongTermMemory memory) {
      return new MarkdownMemoryStore(memory);
    }
  }

  @Bean
  @ConditionalOnMissingBean(MemoryService.class)
  MemoryService memoryService(LongTermMemoryStore store) {
    return new MemoryServiceImpl(store);
  }

  @Bean
  @ConditionalOnMissingBean(MemoryTools.class)
  MemoryTools memoryTools(MemoryService memoryService, ObjectProvider<LongTermMemoryStore> stores) {
    return new MemoryTools(memoryService, stores.getIfUnique() instanceof Mem0MemoryStore);
  }

  private static void validateSqliteSchema(DataSource source) {
    try (var connection = source.getConnection()) {
      if (!SQLITE_PRODUCT.equals(connection.getMetaData().getDatabaseProductName())) {
        throw new IllegalStateException("SQLite记忆工作区不可用");
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("SQLite记忆工作区不可用");
    }
    var jdbc = new JdbcTemplate(source);
    var databases = jdbc.queryForList("PRAGMA database_list");
    boolean persistent =
        databases.stream()
            .anyMatch(
                row ->
                    MAIN_DATABASE.equals(row.get(METADATA_NAME))
                        && row.get("file") instanceof String file
                        && !file.isBlank()
                        && Files.isRegularFile(Path.of(file)));
    if (!persistent) {
      throw new IllegalStateException("SQLite记忆工作区不可用");
    }
    try {
      var columns = jdbc.queryForList("PRAGMA table_info(memory_entries)");
      List<String> names = List.of("id", "scope", "content", "created_at");
      List<String> types = List.of("INTEGER", "VARCHAR(16)", "TEXT", "TIMESTAMP");
      if (columns.size() != names.size()) {
        throw new IllegalStateException("SQLite记忆表结构不兼容");
      }
      for (int index = 0; index < names.size(); index++) {
        var column = columns.get(index);
        boolean wrongName = !names.get(index).equals(column.get(METADATA_NAME));
        boolean wrongType = !types.get(index).equals(column.get("type"));
        int expectedPrimaryKey = index == 0 ? 1 : 0;
        boolean wrongPrimaryKey = ((Number) column.get("pk")).intValue() != expectedPrimaryKey;
        boolean missingNotNull = index > 0 && ((Number) column.get("notnull")).intValue() != 1;
        if (wrongName || wrongType || wrongPrimaryKey || missingNotNull) {
          throw new IllegalStateException("SQLite记忆表结构不兼容");
        }
      }
      String ddl =
          jdbc.queryForObject(
              "SELECT sql FROM sqlite_master WHERE type='table' AND name='memory_entries'",
              String.class);
      if (ddl == null || !AUTO_ID.matcher(ddl).find()) {
        throw new IllegalStateException("SQLite记忆表结构不兼容");
      }
      var indexes = jdbc.queryForList("PRAGMA index_list(memory_entries)");
      if (indexes.stream()
          .noneMatch(
              row ->
                  SCOPE_INDEX.equals(row.get(METADATA_NAME))
                      && ((Number) row.get("unique")).intValue() == 0)) {
        throw new IllegalStateException("SQLite记忆表结构不兼容");
      }
      var indexColumns = jdbc.queryForList("PRAGMA index_info(idx_memory_scope)");
      if (indexColumns.size() != 1
          || !SCOPE_COLUMN.equals(indexColumns.getFirst().get(METADATA_NAME))) {
        throw new IllegalStateException("SQLite记忆表结构不兼容");
      }
    } catch (RuntimeException exception) {
      throw new IllegalStateException("SQLite记忆表结构不兼容");
    }
  }
}
