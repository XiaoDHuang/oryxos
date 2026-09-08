package com.oryxos.storage.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.oryxos.core.session.Session;
import com.oryxos.core.session.SessionPage;
import java.sql.Connection;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
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
class SessionManagerTest {

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EntityScan("com.oryxos.storage")
  @EnableJpaRepositories("com.oryxos.storage")
  static class TestConfig {

    /** 每个测试类一份全新 SQLite 库;表结构由真实脚本建. */
    @Bean
    DataSource dataSource() {
      return DataSourceBuilder.create()
          .driverClassName("org.sqlite.JDBC")
          .url("jdbc:sqlite:target/test-sessions-" + UUID.randomUUID() + ".db?busy_timeout=5000")
          .build();
    }
  }

  @Autowired private SessionRepository repository;

  @Autowired private DataSource dataSource;

  private JpaSessionManager sessionManager;

  @BeforeEach
  void setUp() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema.sql"));
    }
    sessionManager = new JpaSessionManager(repository);
  }

  @Test
  @DisplayName("同一三元组_历次getOrCreate都是同一个Session")
  void sameTriple_alwaysSameSession() {
    Session first = sessionManager.getOrCreate("cli", "wang", "default");
    Session second = sessionManager.getOrCreate("cli", "wang", "default");
    assertThat(second.id()).isEqualTo(first.id());

    Session other = sessionManager.getOrCreate("web", "wang", "default");
    assertThat(other.id()).isNotEqualTo(first.id());
  }

  @Test
  @DisplayName("user或profile不同_也是不同会话")
  void differentUserOrProfile_differentSession() {
    Session base = sessionManager.getOrCreate("cli", "wang", "default");

    assertThat(sessionManager.getOrCreate("cli", "li", "default").id()).isNotEqualTo(base.id());
    assertThat(sessionManager.getOrCreate("cli", "wang", "ops").id()).isNotEqualTo(base.id());
  }

  @Test
  @DisplayName("id生成只此一处_格式为channel冒号user冒号profile")
  void idComposedOnlyInsideManager() {
    Session session = sessionManager.getOrCreate("cli", "wang", "default");

    assertThat(session.id()).isEqualTo("cli:wang:default");
  }

  @Test
  @DisplayName("分量含冒号_直接拒绝防碰撞")
  void componentWithColon_rejected() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> sessionManager.getOrCreate("cl:i", "wang", "default"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("冒号");
  }

  @Test
  @DisplayName("save后按id取回_消息历史完整")
  void saveThenGet_historyRoundTrips() {
    Session session = sessionManager.getOrCreate("cli", "wang", "default");
    session.append(new UserMessage("第一句话"));
    session.append(new UserMessage("第二句话"));
    sessionManager.save(session);

    Session reloaded = sessionManager.get("cli:wang:default").orElseThrow();

    assertThat(reloaded.messages()).hasSize(2);
    assertThat(reloaded.messages().get(0).getText()).isEqualTo("第一句话");
    assertThat(reloaded.messages().get(1).getText()).isEqualTo("第二句话");
  }

  @Test
  @DisplayName("归档后状态置位_回读带归档标记")
  void archive_marksStatusAndReloadCarriesFlag() {
    Session session = sessionManager.getOrCreate("web", "wang", "default");
    assertThat(session.archived()).isFalse();

    assertThat(sessionManager.archive(session.id())).isTrue();

    SessionEntity entity = repository.findById(session.id()).orElseThrow();
    assertThat(entity.getStatus()).isEqualTo("archived");
    assertThat(entity.getArchivedAt()).isNotBlank();
    assertThat(sessionManager.get(session.id()).orElseThrow().archived()).isTrue();
  }

  @Test
  @DisplayName("重复归档幂等_不覆写归档时间")
  void archiveTwice_idempotentKeepsFirstTimestamp() {
    Session session = sessionManager.getOrCreate("cli", "li", "default");
    assertThat(sessionManager.archive(session.id())).isTrue();
    String firstArchivedAt = repository.findById(session.id()).orElseThrow().getArchivedAt();

    assertThat(sessionManager.archive(session.id())).isTrue();

    assertThat(repository.findById(session.id()).orElseThrow().getArchivedAt())
        .isEqualTo(firstArchivedAt);
  }

  @Test
  @DisplayName("归档未知会话_返回false")
  void archiveUnknown_returnsFalse() {
    assertThat(sessionManager.archive("web:ghost:default")).isFalse();
  }

  @Test
  @DisplayName("列表按最后活跃倒序分页_含归档与全量条数")
  void listSessions_descByLastActiveWithTotal() {
    Session older = sessionManager.getOrCreate("cli", "a", "default");
    sessionManager.getOrCreate("cli", "b", "default");
    sessionManager.getOrCreate("cli", "c", "default");
    sessionManager.archive(older.id());
    // 推新最老那条的活跃时间,制造确定的倒序首尾
    sessionManager.save(older);

    SessionPage first = sessionManager.listSessions(0, 2);

    assertThat(first.total()).isEqualTo(3);
    assertThat(first.content()).hasSize(2);
    assertThat(first.content().getFirst().sessionId()).isEqualTo(older.id());
    assertThat(sessionManager.listSessions(1, 2).content()).hasSize(1);
    assertThat(sessionManager.listSessions(0, 500).size()).isEqualTo(100);
    // id 格式由 idComposedOnlyInsideManager 钉死,此处直接引用字面量
    assertThat(
            sessionManager.listSessions(0, 10).content().stream()
                .anyMatch(summary -> summary.sessionId().equals("cli:c:default")))
        .isTrue();
    assertThat(
            sessionManager.listSessions(0, 10).content().stream()
                .filter(summary -> summary.sessionId().equals(older.id()))
                .findFirst()
                .orElseThrow()
                .status())
        .isEqualTo("archived");
  }

  @Test
  @DisplayName("非法分页参数_直接拒绝")
  void listSessions_invalidPageArgs_rejected() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> sessionManager.listSessions(-1, 10))
        .isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> sessionManager.listSessions(0, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
