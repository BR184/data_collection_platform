package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.DirectConnectionPoolMetrics;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceMode;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

class GitlabDirectJdbcExecutorTest {
  @Test
  @ResourceLock("default-time-zone")
  void test_query_distinguishes_postgresql_timestamp_types_by_type_name() throws Exception {
    TimeZone original = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("Asia/Shanghai")));
    try {
      Connection connection = mock(Connection.class);
      Statement statement = mock(Statement.class);
      ResultSet resultSet = mock(ResultSet.class);
      ResultSetMetaData metaData = mock(ResultSetMetaData.class);
      HikariDataSource dataSource = mock(HikariDataSource.class);
      when(dataSource.getConnection()).thenReturn(connection);
      when(connection.createStatement()).thenReturn(statement);
      when(statement.executeQuery("select timestamps")).thenReturn(resultSet);
      when(resultSet.getMetaData()).thenReturn(metaData);
      when(metaData.getColumnCount()).thenReturn(2);
      when(metaData.getColumnLabel(1)).thenReturn("naive_time");
      when(metaData.getColumnLabel(2)).thenReturn("aware_time");
      when(metaData.getColumnType(1)).thenReturn(Types.TIMESTAMP);
      when(metaData.getColumnType(2)).thenReturn(Types.TIMESTAMP);
      when(metaData.getColumnTypeName(1)).thenReturn("timestamp");
      when(metaData.getColumnTypeName(2)).thenReturn("timestamptz");
      when(resultSet.next()).thenReturn(true, false);
      LocalDateTime naiveSource = LocalDateTime.of(2026, 8, 6, 3, 20, 22, 840_104_000);
      Instant awareSource = Instant.parse("2026-08-06T03:20:22.840104Z");
      when(resultSet.getObject(1)).thenReturn(Timestamp.valueOf(naiveSource));
      when(resultSet.getObject(2)).thenReturn(Timestamp.from(awareSource));
      GitlabMirrorProperties properties = new GitlabMirrorProperties();
      GitlabDirectJdbcExecutor executor =
          new GitlabDirectJdbcExecutor(
              new GitlabSourceConnectionSettings(properties),
              new GitlabSourceQueryRetryPolicy(properties),
              new GitlabJdbcValueNormalizer(),
              new com.data.collection.platform.service.sync.SyncThreadBudgetResolver(properties),
              (config, ignored) -> dataSource);

      List<Map<String, Object>> rows =
          executor.query(directConfig("gitlabhq_production", "gitlab"), "select timestamps");

      assertThat(rows)
          .containsExactly(
              Map.of(
                  "naive_time", naiveSource,
                  "aware_time", LocalDateTime.ofInstant(awareSource, java.time.ZoneOffset.UTC)));
    } finally {
      TimeZone.setDefault(original);
    }
  }

  @Test
  void shouldReuseSamePooledDataSourceForEquivalentDirectConfigs() throws Exception {
    AtomicInteger createCount = new AtomicInteger();
    Connection firstConnection = mock(Connection.class);
    Connection secondConnection = mock(Connection.class);
    HikariDataSource dataSource = mock(HikariDataSource.class);
    when(dataSource.getConnection()).thenReturn(firstConnection, secondConnection);

    GitlabDirectJdbcExecutor executor =
        new GitlabDirectJdbcExecutor(
            new GitlabSourceConnectionSettings(new GitlabMirrorProperties()),
            new GitlabSourceQueryRetryPolicy(new GitlabMirrorProperties()),
            new GitlabJdbcValueNormalizer(),
            new com.data.collection.platform.service.sync.SyncThreadBudgetResolver(new GitlabMirrorProperties()),
            (config, ignored) -> {
              createCount.incrementAndGet();
              return dataSource;
            });

    try (Connection ignored = executor.openConnection(directConfig("  gitlabhq_production  ", "  gitlab  "))) {
      // close by try-with-resources
    }
    try (Connection ignored = executor.openConnection(directConfig("gitlabhq_production", "gitlab"))) {
      // close by try-with-resources
    }

    assertThat(createCount).hasValue(1);
    assertThat(executor.pooledDataSourceCount()).isEqualTo(1);
    verify(dataSource, times(2)).getConnection();
  }

  @Test
  void shouldCloseAllCachedDirectDataSources() throws Exception {
    HikariDataSource first = mock(HikariDataSource.class);
    HikariDataSource second = mock(HikariDataSource.class);
    Connection firstConnection = mock(Connection.class);
    Connection secondConnection = mock(Connection.class);
    when(first.getConnection()).thenReturn(firstConnection);
    when(second.getConnection()).thenReturn(secondConnection);

    GitlabDirectJdbcExecutor executor =
        new GitlabDirectJdbcExecutor(
            new GitlabSourceConnectionSettings(new GitlabMirrorProperties()),
            new GitlabSourceQueryRetryPolicy(new GitlabMirrorProperties()),
            new GitlabJdbcValueNormalizer(),
            new com.data.collection.platform.service.sync.SyncThreadBudgetResolver(new GitlabMirrorProperties()),
            (config, ignored) -> "gitlabhq_secondary".equals(config.getDbName()) ? second : first);

    try (Connection ignored = executor.openConnection(directConfig("gitlabhq_production", "gitlab"))) {
      // close by try-with-resources
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    try (Connection ignored = executor.openConnection(directConfig("gitlabhq_secondary", "gitlab"))) {
      // close by try-with-resources
    } catch (Exception e) {
      throw new AssertionError(e);
    }

    executor.destroy();

    verify(first).close();
    verify(second).close();
    assertThat(executor.pooledDataSourceCount()).isZero();
  }

  @Test
  void shouldRetireOnlyChangedConfigPoolAndKeepBorrowedConnectionUsable() throws Exception {
    List<HikariDataSource> created = new ArrayList<>();
    Connection firstConnection = mock(Connection.class);
    Connection secondConnection = mock(Connection.class);
    HikariDataSource first = mock(HikariDataSource.class);
    HikariDataSource second = mock(HikariDataSource.class);
    when(first.getConnection()).thenReturn(firstConnection);
    when(second.getConnection()).thenReturn(secondConnection);

    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    GitlabDirectJdbcExecutor executor =
        new GitlabDirectJdbcExecutor(
            new GitlabSourceConnectionSettings(properties),
            new GitlabSourceQueryRetryPolicy(properties),
            new GitlabJdbcValueNormalizer(),
            new com.data.collection.platform.service.sync.SyncThreadBudgetResolver(properties),
            (config, ignored) -> {
              HikariDataSource dataSource = created.isEmpty() ? first : second;
              created.add(dataSource);
              return dataSource;
            });

    Connection borrowed = executor.openConnection(directConfig("gitlabhq_production", "gitlab"));
    executor.invalidate(7L);

    verify(first, never()).close();
    borrowed.close();
    verify(first).close();

    try (Connection ignored = executor.openConnection(directConfig("gitlabhq_production", "gitlab"))) {
      // close by try-with-resources
    }
    assertThat(created).hasSize(2);
    assertThat(executor.pooledDataSourceCount()).isEqualTo(1);
  }

  @Test
  void shouldExposeCurrentPoolCapacityAndUtilization() throws Exception {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setMaxSyncThreads(4);
    HikariDataSource dataSource = mock(HikariDataSource.class);
    HikariPoolMXBean pool = mock(HikariPoolMXBean.class);
    when(dataSource.getConnection()).thenReturn(mock(Connection.class));
    when(dataSource.getHikariPoolMXBean()).thenReturn(pool);
    when(pool.getTotalConnections()).thenReturn(4);
    when(pool.getActiveConnections()).thenReturn(3);
    when(pool.getIdleConnections()).thenReturn(1);
    when(pool.getThreadsAwaitingConnection()).thenReturn(2);
    GitlabDirectJdbcExecutor executor =
        new GitlabDirectJdbcExecutor(
            new GitlabSourceConnectionSettings(properties),
            new GitlabSourceQueryRetryPolicy(properties),
            new GitlabJdbcValueNormalizer(),
            new com.data.collection.platform.service.sync.SyncThreadBudgetResolver(properties),
            (config, ignored) -> dataSource);

    GitlabSyncConfig config = directConfig("gitlabhq_production", "gitlab");
    config.setSyncThreadMode("FIXED");
    config.setSyncThreadValue(BigDecimal.valueOf(3));
    config.setMaxSyncThreads(4);
    try (Connection ignored = executor.openConnection(config)) {
      // 初始化并保留配置对应的连接池。
    }

    assertThat(executor.poolMetrics(7L))
        .contains(
            new DirectConnectionPoolMetrics(
                4,
                4,
                3,
                1,
                2));
    assertThat(executor.poolMetrics(8L)).isEmpty();
  }

  private GitlabSyncConfig directConfig(String dbName, String dbUsername) {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(7L);
    config.setSourceMode(SourceMode.DIRECT);
    config.setDbHost("10.0.0.8");
    config.setDbPort(5432);
    config.setDbName(dbName);
    config.setDbUsername(dbUsername);
    config.setDbPassword("secret");
    return config;
  }
}
