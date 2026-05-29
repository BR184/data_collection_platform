package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.logging.SyncRunLogContext;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceMode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class GitlabDirectJdbcExecutor implements AutoCloseable {
  private final GitlabSourceConnectionSettings connectionSettings;
  private final GitlabSourceQueryRetryPolicy queryRetryPolicy;
  private final GitlabJdbcValueNormalizer jdbcValueNormalizer;
  private final Function<GitlabSyncConfig, HikariDataSource> dataSourceFactory;
  private final ConcurrentMap<String, HikariDataSource> directDataSources = new ConcurrentHashMap<>();

  GitlabDirectJdbcExecutor(
      GitlabSourceConnectionSettings connectionSettings,
      GitlabSourceQueryRetryPolicy queryRetryPolicy,
      GitlabJdbcValueNormalizer jdbcValueNormalizer) {
    this(connectionSettings, queryRetryPolicy, jdbcValueNormalizer, null);
  }

  GitlabDirectJdbcExecutor(
      GitlabSourceConnectionSettings connectionSettings,
      GitlabSourceQueryRetryPolicy queryRetryPolicy,
      GitlabJdbcValueNormalizer jdbcValueNormalizer,
      Function<GitlabSyncConfig, HikariDataSource> dataSourceFactory) {
    this.connectionSettings = connectionSettings;
    this.queryRetryPolicy = queryRetryPolicy;
    this.jdbcValueNormalizer = jdbcValueNormalizer;
    this.dataSourceFactory = dataSourceFactory == null ? this::createDirectDataSource : dataSourceFactory;
  }

  void testConnection(GitlabSyncConfig config) {
    queryRetryPolicy.executeWithRetry("JDBC connection test", () -> {
      try (Connection connection = openConnection(config); Statement statement = connection.createStatement()) {
        statement.setQueryTimeout(connectionSettings.resolveExternalQueryTimeoutSeconds());
        statement.execute("select 1");
        return null;
      } catch (Exception e) {
        throw new BizException("GitLab PostgreSQL connection failed: " + e.getMessage());
      }
    });
  }

  List<Map<String, Object>> query(GitlabSyncConfig config, String sql) {
    try {
      return queryRetryPolicy.executeWithRetry("JDBC query", () -> {
        try (Connection connection = openConnection(config);
             Statement statement = connection.createStatement()) {
          statement.setQueryTimeout(connectionSettings.resolveExternalQueryTimeoutSeconds());
          try (ResultSet resultSet = statement.executeQuery(sql)) {
            List<Map<String, Object>> rows = new ArrayList<>();
            ResultSetMetaData metaData = resultSet.getMetaData();
            int count = metaData.getColumnCount();
            while (resultSet.next()) {
              Map<String, Object> row = new LinkedHashMap<>();
              for (int i = 1; i <= count; i++) {
                row.put(metaData.getColumnLabel(i), jdbcValueNormalizer.normalize(resultSet.getObject(i)));
              }
              rows.add(row);
            }
            return rows;
          }
        } catch (Exception e) {
          throw new BizException("Failed to query GitLab database: " + e.getMessage());
        }
      });
    } catch (BizException e) {
      try (SyncRunLogContext.Scope action = SyncRunLogContext.action("Data_Fetching")) {
        log.error("Failed to query GitLab database via JDBC", e);
      }
      throw e;
    }
  }

  Connection openConnection(GitlabSyncConfig config) throws Exception {
    if (config != null && config.getSourceMode() == SourceMode.DIRECT) {
      return directDataSources.computeIfAbsent(connectionSettings.directDataSourceKey(config), ignored -> dataSourceFactory.apply(config))
          .getConnection();
    }
    return DriverManager.getConnection(
        connectionSettings.buildJdbcUrl(config),
        connectionSettings.normalizeDbUser(config),
        config.getDbPassword());
  }

  private HikariDataSource createDirectDataSource(GitlabSyncConfig config) {
    HikariConfig hikariConfig = new HikariConfig();
    hikariConfig.setJdbcUrl(connectionSettings.buildJdbcUrl(config));
    hikariConfig.setUsername(connectionSettings.normalizeDbUser(config));
    hikariConfig.setPassword(config.getDbPassword());
    hikariConfig.setMaximumPoolSize(2);
    hikariConfig.setMinimumIdle(0);
    hikariConfig.setPoolName("gitlab-direct-" + config.getId());
    hikariConfig.setConnectionTimeout(5000);
    hikariConfig.setIdleTimeout(60000);
    hikariConfig.setMaxLifetime(300000);
    return new HikariDataSource(hikariConfig);
  }

  @Override
  public void close() {
    directDataSources.forEach((key, dataSource) -> {
      try {
        dataSource.close();
      } catch (RuntimeException e) {
        log.warn("Failed to close GitLab direct JDBC datasource, key={}", key, e);
      }
    });
    directDataSources.clear();
  }

  int pooledDataSourceCount() {
    return directDataSources.size();
  }
}
