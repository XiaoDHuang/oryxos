package com.oryxos.boot;

import com.oryxos.memory.LongTermMemory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestClient;

@TestConfiguration(proxyBeanMethods = false)
class MemoryBackendFixture extends MemorySystemIntegrationTest.RuntimeFixture {

  MemoryBackendFixture(Path workspace) {
    super(workspace);
  }

  @Override
  @Bean
  @Primary
  @ConditionalOnProperty(name = "memory.backend", havingValue = "markdown", matchIfMissing = true)
  LongTermMemory testLongTermMemory() {
    return super.testLongTermMemory();
  }

  static ApplicationContextRunner runner(
      Path workspace,
      String backend,
      MemorySystemIntegrationTest.Scenario scenario,
      RowProbe source)
      throws Exception {
    var runner =
        new ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    HibernateJpaAutoConfiguration.class,
                    TransactionAutoConfiguration.class,
                    SqlInitializationAutoConfiguration.class,
                    Class.forName("com.oryxos.memory.MemoryConfiguration"),
                    Class.forName("com.oryxos.tool.ToolConfiguration")))
            .withUserConfiguration(MemoryBackendFixture.class)
            .withBean(Path.class, () -> workspace)
            .withBean(MemorySystemIntegrationTest.Scenario.class, () -> scenario)
            .withBean(DataSource.class, () -> source)
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withPropertyValues(
                "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.jpa.defer-datasource-initialization=true",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:db/schema.sql",
                "memory.mem0.api-key=${UNDEFINED_MEM0_SECRET_007}");
    return backend == null ? runner : runner.withPropertyValues("memory.backend=" + backend);
  }

  static class RowProbe extends DelegatingDataSource {
    private static final Pattern MEMORY_ROWS =
        Pattern.compile("(?is)\\b(?:from|into|update|join)\\s+\"?memory_entries\\b");
    final AtomicInteger memoryRows = new AtomicInteger();

    RowProbe(Path database) {
      super(new DriverManagerDataSource("jdbc:sqlite:" + database));
    }

    @Override
    public Connection getConnection() throws SQLException {
      return wrap(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return wrap(super.getConnection(username, password));
    }

    private Connection wrap(Connection connection) {
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) -> {
                if (method.getName().startsWith("prepare")
                    && args != null
                    && args[0] instanceof String sql) {
                  record(sql);
                }
                try {
                  Object result = method.invoke(connection, args);
                  if ("createStatement".equals(method.getName())) {
                    return statement((Statement) result);
                  }
                  return result;
                } catch (InvocationTargetException exception) {
                  throw exception.getCause();
                }
              });
    }

    private Statement statement(Statement statement) {
      return (Statement)
          Proxy.newProxyInstance(
              Statement.class.getClassLoader(),
              new Class<?>[] {Statement.class},
              (proxy, method, args) -> {
                if (method.getName().startsWith("execute")
                    && args != null
                    && args[0] instanceof String sql) {
                  record(sql);
                }
                try {
                  return method.invoke(statement, args);
                } catch (InvocationTargetException exception) {
                  throw exception.getCause();
                }
              });
    }

    private void record(String sql) {
      if (MEMORY_ROWS.matcher(sql).find()) {
        memoryRows.incrementAndGet();
      }
    }
  }
}
