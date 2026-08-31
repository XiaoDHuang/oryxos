package com.oryxos.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.oryxos.core.memory.MemoryScope;
import com.oryxos.storage.memory.MemoryEntryRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class SqliteMemoryStoreContractTest extends AbstractMemoryStoreContractTest {

  @TempDir Path directory;
  private final List<AnnotationConfigApplicationContext> contexts = new ArrayList<>();

  @Override
  LongTermMemoryStore openStore() throws Exception {
    closeContexts();
    return open(directory.resolve("memory.db"));
  }

  private LongTermMemoryStore open(Path database) throws Exception {
    var source = new DriverManagerDataSource("jdbc:sqlite:" + database);
    try (var connection = source.getConnection()) {
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema.sql"));
    }
    var context = new AnnotationConfigApplicationContext();
    contexts.add(context);
    TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
        context,
        "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
        "spring.jpa.hibernate.ddl-auto=none");
    context.registerBean(DataSource.class, () -> source);
    context.register(Fixture.class);
    context.refresh();
    return new SqliteMemoryStore(
        context.getBean(MemoryEntryRepository.class),
        context.getBean(PlatformTransactionManager.class));
  }

  @Override
  LongTermMemoryStore unavailableStore() throws Exception {
    LongTermMemoryStore store = openStore();
    new JdbcTemplate(contexts.getLast().getBean(DataSource.class))
        .execute("DROP TABLE IF EXISTS memory_entries");
    return store;
  }

  @AfterEach
  void closeContexts() {
    contexts.forEach(AnnotationConfigApplicationContext::close);
    contexts.clear();
  }

  @Test
  void boundsArchiveAtOneHundredButRetainsOldMatchesAndOriginalText() throws Exception {
    LongTermMemoryStore store = openStore();
    store.append("核心偏好", MemoryScope.CORE);
    store.append("旧匹配%_'中文\n原文第二行", MemoryScope.ARCHIVAL);
    for (int index = 1; index <= 100; index++) {
      store.append("item-" + index, MemoryScope.ARCHIVAL);
      if (index == 98 || index == 99) {
        assertThat(store.load()).contains("旧匹配%_'中文", "item-" + index);
        assertThat(store.load().lines().filter(line -> line.startsWith("item-")).toList())
            .containsExactlyElementsOf(
                IntStream.rangeClosed(1, index).mapToObj(value -> "item-" + value).toList());
      }
    }
    assertThat(store.load()).contains("核心偏好", "item-100").doesNotContain("旧匹配");
    assertThat(store.load().lines().filter(line -> line.startsWith("item-")).toList())
        .containsExactlyElementsOf(
            IntStream.rangeClosed(1, 100).mapToObj(value -> "item-" + value).toList());
    assertThat(store.recall("%_'中文")).containsExactly("旧匹配%_'中文\n原文第二行");
    assertThat(store.recall("ITEM")).isEmpty();
    assertThat(store.recall("核心偏好")).isEmpty();
    assertThat(openStore().recall("item-")).hasSize(100);
  }

  @Test
  void separateDatabaseDoesNotSeeOrMigrateOtherWorkspace() throws Exception {
    LongTermMemoryStore first = openStore();
    first.append("第一工作区", MemoryScope.CORE);
    LongTermMemoryStore second = open(directory.resolve("second.db"));
    assertThat(second.load()).isEmpty();
    second.append("第二工作区", MemoryScope.CORE);
    assertThat(first.load()).contains("第一工作区").doesNotContain("第二工作区");
  }

  @Test
  void acknowledgementCommitsEvenWhenCallingTransactionRollsBack() throws Exception {
    LongTermMemoryStore store = openStore();
    var outer =
        new TransactionTemplate(contexts.getLast().getBean(PlatformTransactionManager.class));
    outer.executeWithoutResult(
        status -> {
          store.append("已经确认的原文", MemoryScope.ARCHIVAL);
          status.setRollbackOnly();
        });
    assertThat(openStore().recall("确认")).containsExactly("已经确认的原文");
  }

  @Test
  void confirmedConcurrentWritesAreDurable() throws Exception {
    LongTermMemoryStore store = openStore();
    var failures = new CopyOnWriteArrayList<Throwable>();
    List<Thread> threads = new ArrayList<>();
    for (int index = 0; index < 10; index++) {
      String content = "并发原文" + index;
      threads.add(
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      store.append(content, MemoryScope.ARCHIVAL);
                    } catch (RuntimeException failure) {
                      failures.add(failure);
                    }
                  }));
    }
    for (Thread thread : threads) {
      thread.join();
    }
    assertThat(failures).isEmpty();
    assertThat(openStore().recall("并发原文")).hasSize(10).doesNotHaveDuplicates();
  }

  @Configuration(proxyBeanMethods = false)
  @ImportAutoConfiguration({
    HibernateJpaAutoConfiguration.class,
    TransactionAutoConfiguration.class
  })
  @EntityScan("com.oryxos.storage.memory")
  @EnableJpaRepositories("com.oryxos.storage.memory")
  static class Fixture {}
}
