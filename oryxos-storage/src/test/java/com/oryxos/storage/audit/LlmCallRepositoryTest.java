package com.oryxos.storage.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(
    properties = {
      "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
      "spring.jpa.hibernate.ddl-auto=none"
    })
class LlmCallRepositoryTest {

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EntityScan("com.oryxos.storage.audit")
  @EnableJpaRepositories("com.oryxos.storage.audit")
  static class TestConfig {

    /** 每个测试类运行用全新的 SQLite 文件;DDL 由真实 schema 脚本完成. */
    @Bean
    DataSource dataSource() {
      return DataSourceBuilder.create()
          .driverClassName("org.sqlite.JDBC")
          .url("jdbc:sqlite:target/test-llm-calls-" + UUID.randomUUID() + ".db")
          .build();
    }
  }

  @Autowired private LlmCallRepository repository;

  @Autowired private DataSource dataSource;

  @BeforeEach
  void createSchemaWithRealScript() throws Exception {
    // 表必须来自手工维护的脚本,绝不用 Hibernate 自动 DDL,否则绿灯测试会掩盖
    // 生产脚本与列的不匹配。
    try (Connection connection = dataSource.getConnection()) {
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema.sql"));
    }
  }

  @Test
  @DisplayName("手工脚本建表_成功调用记录能存能读")
  void savesAndReadsSuccessRecord() {
    String id = UUID.randomUUID().toString();
    repository.save(
        new LlmCall(
            id,
            "s-1",
            "deepseek",
            "deepseek-chat",
            12,
            5,
            17,
            120L,
            "completed",
            true,
            null,
            "2026-08-23T00:00:00Z",
            "2026-08-23T00:00:01Z"));

    LlmCall loaded = repository.findById(id).orElseThrow();

    assertThat(loaded.getSuccess()).isTrue();
    assertThat(loaded.getStatus()).isEqualTo("completed");
    assertThat(loaded.getErrorMessage()).isNull();
    assertThat(loaded.getTotalTokens()).isEqualTo(17);
    assertThat(loaded.getLatencyMs()).isEqualTo(120L);
  }

  @Test
  @DisplayName("手工脚本建表_失败记录success与error_message两列真实可写读")
  void savesAndReadsFailureRecord() {
    String id = UUID.randomUUID().toString();
    repository.save(
        new LlmCall(
            id,
            "s-2",
            "kimi",
            "kimi-k2",
            null,
            null,
            null,
            80L,
            "failed",
            false,
            "connect timeout",
            "2026-08-23T00:00:02Z",
            "2026-08-23T00:00:03Z"));

    LlmCall loaded = repository.findById(id).orElseThrow();

    assertThat(loaded.getSuccess()).isFalse();
    assertThat(loaded.getErrorMessage()).isEqualTo("connect timeout");
    assertThat(loaded.getPromptTokens()).isNull();
  }
}
