package com.data.collection.platform.service.sync;

import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** 为 PostgreSQL 集成测试选择外部隔离库或本机 Testcontainers。 */
final class PostgresIntegrationTestDatabase implements AutoCloseable {
  private static final String JDBC_URL_ENV = "TEST_POSTGRES_JDBC_URL";
  private static final String USERNAME_ENV = "TEST_POSTGRES_USERNAME";
  private static final String PASSWORD_ENV = "TEST_POSTGRES_PASSWORD";

  private final String jdbcUrl;
  private final String username;
  private final String password;
  private final PostgreSQLContainer<?> container;

  private PostgresIntegrationTestDatabase(
      String jdbcUrl,
      String username,
      String password,
      PostgreSQLContainer<?> container) {
    this.jdbcUrl = jdbcUrl;
    this.username = username;
    this.password = password;
    this.container = container;
  }

  static PostgresIntegrationTestDatabase open(String databaseName) {
    String externalUrl = trimToNull(System.getenv(JDBC_URL_ENV));
    if (externalUrl != null) {
      return new PostgresIntegrationTestDatabase(
          externalUrl,
          defaultValue(System.getenv(USERNAME_ENV), "test"),
          defaultValue(System.getenv(PASSWORD_ENV), "test"),
          null);
    }
    PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName(databaseName)
            .withUsername("test")
            .withPassword("test");
    try {
      postgres.start();
      return new PostgresIntegrationTestDatabase(
          postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), postgres);
    } catch (RuntimeException error) {
      postgres.close();
      Assumptions.assumeTrue(false, "当前环境没有可用的隔离 PostgreSQL：" + error.getMessage());
      throw error;
    }
  }

  DataSource dataSource() {
    return new DriverManagerDataSource(jdbcUrl, username, password);
  }

  @Override
  public void close() {
    if (container != null) {
      container.close();
    }
  }

  private static String trimToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private static String defaultValue(String value, String fallback) {
    String normalized = trimToNull(value);
    return normalized == null ? fallback : normalized;
  }
}
