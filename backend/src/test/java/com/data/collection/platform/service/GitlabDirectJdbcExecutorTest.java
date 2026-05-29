package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceMode;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GitlabDirectJdbcExecutorTest {
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
            config -> {
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
            config -> "gitlabhq_secondary".equals(config.getDbName()) ? second : first);

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

    executor.close();

    verify(first).close();
    verify(second).close();
    assertThat(executor.pooledDataSourceCount()).isZero();
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
