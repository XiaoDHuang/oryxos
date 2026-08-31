package com.oryxos.storage.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oryxos.core.memory.MemoryScope;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class MemoryEntryRepositoryTest {

  @TempDir Path directory;

  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DataSourceAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class,
                TransactionAutoConfiguration.class,
                SqlInitializationAutoConfiguration.class))
        .withUserConfiguration(Fixture.class)
        .withPropertyValues(
            "spring.datasource.url=jdbc:sqlite:" + directory.resolve("memory.db"),
            "spring.datasource.driver-class-name=org.sqlite.JDBC",
            "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.defer-datasource-initialization=true",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:db/schema.sql");
  }

  @Test
  void persistsFourFieldsWithCanonicalTextTimeAndReopens() {
    runner()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var repository = context.getBean(MemoryEntryRepository.class);
              repository.saveAndFlush(
                  new MemoryEntry(
                      MemoryScope.CORE, " 原文\n🚀 ", Instant.parse("2026-08-31T00:00:00.123987Z")));
              var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
              assertThat(jdbc.queryForList("PRAGMA table_info(memory_entries)"))
                  .extracting(row -> row.get("name"))
                  .containsExactly("id", "scope", "content", "created_at");
              assertThat(
                      jdbc.queryForObject(
                          "SELECT typeof(created_at) FROM memory_entries", String.class))
                  .isEqualTo("text");
              assertThat(jdbc.queryForObject("SELECT created_at FROM memory_entries", String.class))
                  .isEqualTo("2026-08-31T00:00:00.123Z");
            });
    runner()
        .run(
            context -> {
              var row = context.getBean(MemoryEntryRepository.class).findAll().getFirst();
              assertThat(row.getId()).isPositive();
              assertThat(row.getContent()).isEqualTo(" 原文\n🚀 ");
              assertThat(row.getCreatedAt()).isEqualTo(Instant.parse("2026-08-31T00:00:00.123Z"));
            });
  }

  @Test
  void usesStableTimeIdOrderAndLiteralArchiveOnlySearch() {
    runner()
        .run(
            context -> {
              var repository = context.getBean(MemoryEntryRepository.class);
              Instant later = Instant.parse("2026-08-31T00:00:00.001Z");
              Instant earlier = Instant.parse("2026-08-31T00:00:00Z");
              repository.saveAndFlush(new MemoryEntry(MemoryScope.ARCHIVAL, "later 中文", later));
              repository.saveAndFlush(new MemoryEntry(MemoryScope.CORE, "onlyCore %_'中文", earlier));
              for (String content : List.of("First %_'中文", "second 中文", "third 中文")) {
                repository.saveAndFlush(new MemoryEntry(MemoryScope.ARCHIVAL, content, earlier));
              }
              assertThat(repository.recall("中文"))
                  .extracting(MemoryEntry::getContent)
                  .containsExactly("First %_'中文", "second 中文", "third 中文", "later 中文");
              assertThat(repository.recall("%_'中文"))
                  .extracting(MemoryEntry::getContent)
                  .containsExactly("First %_'中文");
              assertThat(repository.recall("first")).isEmpty();
              assertThat(repository.recall("' OR 1=1 --")).isEmpty();
              assertThat(
                      repository.findTop100ByScopeOrderByCreatedAtDescIdDesc(MemoryScope.ARCHIVAL))
                  .extracting(MemoryEntry::getContent)
                  .containsExactly("later 中文", "third 中文", "second 中文", "First %_'中文");
            });
  }

  @Test
  void additiveSchemaCanRunRepeatedlyWithoutChangingExistingRows() {
    runner()
        .run(
            context -> {
              DataSource source = context.getBean(DataSource.class);
              var jdbc = new JdbcTemplate(source);
              jdbc.update(
                  "INSERT INTO sessions(session_id,profile_name,status)"
                      + " VALUES('old','p','active')");
              jdbc.update(
                  "INSERT INTO tool_invocations(invocation_id,tool_name,status,success)"
                      + " VALUES('old','save_memory','completed',1)");
              jdbc.update("INSERT INTO llm_calls(call_id,success) VALUES('old',1)");
              // 删除的是本用例新建的空表,模拟006只有原三表的工作区。
              jdbc.execute("DROP TABLE memory_entries");
              try (Connection connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema.sql"));
              }
              context
                  .getBean(MemoryEntryRepository.class)
                  .saveAndFlush(new MemoryEntry(MemoryScope.ARCHIVAL, "旧归档", Instant.now()));
              try (Connection connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema.sql"));
              }
              for (String table :
                  List.of("sessions", "tool_invocations", "llm_calls", "memory_entries")) {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class))
                    .isEqualTo(1);
              }
            });
  }

  @Test
  void rejectsMalformedStoredTimeWithoutReplacingIt() {
    runner()
        .run(
            context -> {
              var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
              jdbc.update(
                  "INSERT INTO memory_entries(scope,content,created_at)"
                      + " VALUES('CORE','原文','bad-time')");
              assertThatThrownBy(() -> context.getBean(MemoryEntryRepository.class).findAll())
                  .isInstanceOf(RuntimeException.class);
              assertThat(jdbc.queryForObject("SELECT created_at FROM memory_entries", String.class))
                  .isEqualTo("bad-time");
            });
  }

  @Test
  void rejectsInvalidEntitiesBeforeInsert() {
    runner()
        .run(
            context -> {
              var repository = context.getBean(MemoryEntryRepository.class);
              assertThatThrownBy(
                      () ->
                          repository.saveAndFlush(
                              new MemoryEntry(MemoryScope.CORE, " \n", Instant.now())))
                  .isInstanceOf(RuntimeException.class);
              assertThatThrownBy(
                      () -> repository.saveAndFlush(new MemoryEntry(null, "原文", Instant.now())))
                  .isInstanceOf(RuntimeException.class);
              assertThatThrownBy(
                      () -> repository.saveAndFlush(new MemoryEntry(MemoryScope.CORE, "原文", null)))
                  .isInstanceOf(RuntimeException.class);
              assertThat(repository.count()).isZero();
            });
  }

  @Configuration(proxyBeanMethods = false)
  @EntityScan("com.oryxos.storage.memory")
  @EnableJpaRepositories("com.oryxos.storage.memory")
  static class Fixture {}
}
