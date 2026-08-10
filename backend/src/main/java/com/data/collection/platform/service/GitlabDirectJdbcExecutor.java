package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.logging.SyncRunLogContext;
import com.data.collection.platform.entity.DirectConnectionPoolMetrics;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.service.sync.SyncExecutionBudget;
import com.data.collection.platform.service.sync.SyncThreadBudgetResolver;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class GitlabDirectJdbcExecutor implements AutoCloseable {
  private final GitlabSourceConnectionSettings connectionSettings;
  private final GitlabSourceQueryRetryPolicy queryRetryPolicy;
  private final GitlabJdbcValueNormalizer jdbcValueNormalizer;
  private final SyncThreadBudgetResolver threadBudgetResolver;
  private final BiFunction<GitlabSyncConfig, SyncExecutionBudget, HikariDataSource> dataSourceFactory;
  private final ConcurrentMap<Long, ManagedDataSource> directDataSources = new ConcurrentHashMap<>();

  GitlabDirectJdbcExecutor(
      GitlabSourceConnectionSettings connectionSettings,
      GitlabSourceQueryRetryPolicy queryRetryPolicy,
      GitlabJdbcValueNormalizer jdbcValueNormalizer,
      SyncThreadBudgetResolver threadBudgetResolver) {
    this(connectionSettings, queryRetryPolicy, jdbcValueNormalizer, threadBudgetResolver, null);
  }

  GitlabDirectJdbcExecutor(
      GitlabSourceConnectionSettings connectionSettings,
      GitlabSourceQueryRetryPolicy queryRetryPolicy,
      GitlabJdbcValueNormalizer jdbcValueNormalizer,
      SyncThreadBudgetResolver threadBudgetResolver,
      BiFunction<GitlabSyncConfig, SyncExecutionBudget, HikariDataSource> dataSourceFactory) {
    this.connectionSettings = connectionSettings;
    this.queryRetryPolicy = queryRetryPolicy;
    this.jdbcValueNormalizer = jdbcValueNormalizer;
    this.threadBudgetResolver = threadBudgetResolver;
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
            return readRows(resultSet);
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

  List<Map<String, Object>> query(
      GitlabSyncConfig config,
      GitlabParameterizedQuery query) {
    try {
      return queryRetryPolicy.executeWithRetry(
          "JDBC parameterized query",
          () -> {
            try (Connection connection = openConnection(config);
                PreparedStatement statement = connection.prepareStatement(query.sql())) {
              statement.setQueryTimeout(
                  connectionSettings.resolveExternalQueryTimeoutSeconds());
              for (int index = 0; index < query.parameters().size(); index++) {
                statement.setObject(index + 1, query.parameters().get(index));
              }
              try (ResultSet resultSet = statement.executeQuery()) {
                return readRows(resultSet);
              }
            } catch (Exception error) {
              throw new BizException(
                  "Failed to query GitLab database with parameters: " + error.getMessage());
            }
          });
    } catch (BizException error) {
      try (SyncRunLogContext.Scope action = SyncRunLogContext.action("Data_Fetching")) {
        log.error("Failed to query GitLab database via parameterized JDBC", error);
      }
      throw error;
    }
  }

  private List<Map<String, Object>> readRows(ResultSet resultSet) throws Exception {
    List<Map<String, Object>> rows = new ArrayList<>();
    ResultSetMetaData metaData = resultSet.getMetaData();
    List<ResultColumn> columns = readResultColumns(metaData);
    while (resultSet.next()) {
      Map<String, Object> row = new LinkedHashMap<>();
      for (ResultColumn column : columns) {
        row.put(
            column.label(),
            jdbcValueNormalizer.normalize(
                resultSet.getObject(column.index()),
                column.jdbcType(),
                column.jdbcTypeName()));
      }
      rows.add(row);
    }
    return rows;
  }

  private List<ResultColumn> readResultColumns(ResultSetMetaData metaData) throws Exception {
    int count = metaData.getColumnCount();
    List<ResultColumn> columns = new ArrayList<>(count);
    for (int index = 1; index <= count; index++) {
      columns.add(
          new ResultColumn(
              index,
              metaData.getColumnLabel(index),
              metaData.getColumnType(index),
              metaData.getColumnTypeName(index)));
    }
    return List.copyOf(columns);
  }

  Connection openConnection(GitlabSyncConfig config) throws Exception {
    if (config != null && config.getSourceMode() == SourceMode.DIRECT) {
      SyncExecutionBudget budget = threadBudgetResolver.resolveBudget(config);
      ManagedDataSource managed = acquireManagedDataSource(config, budget);
      ManagedDataSource.Lease lease = managed.acquire();
      try {
        return leasedConnection(lease.getConnection(), lease);
      } catch (Exception error) {
        lease.close();
        throw error;
      }
    }
    return DriverManager.getConnection(
        connectionSettings.buildJdbcUrl(config),
        connectionSettings.normalizeDbUser(config),
        config.getDbPassword());
  }

  private HikariDataSource createDirectDataSource(GitlabSyncConfig config, SyncExecutionBudget budget) {
    HikariConfig hikariConfig = new HikariConfig();
    hikariConfig.setJdbcUrl(connectionSettings.buildJdbcUrl(config));
    hikariConfig.setUsername(connectionSettings.normalizeDbUser(config));
    hikariConfig.setPassword(config.getDbPassword());
    hikariConfig.setMaximumPoolSize(budget.directPoolSize());
    hikariConfig.setMinimumIdle(0);
    hikariConfig.setPoolName("gitlab-direct-" + config.getId());
    hikariConfig.setConnectionTimeout(budget.connectionAcquireTimeoutMs());
    hikariConfig.setIdleTimeout(60000);
    hikariConfig.setMaxLifetime(300000);
    return new HikariDataSource(hikariConfig);
  }

  @Override
  public void close() {
    directDataSources.forEach((key, dataSource) -> dataSource.retire());
    directDataSources.clear();
  }

  void invalidate(Long configId) {
    if (configId == null) {
      return;
    }
    ManagedDataSource removed = directDataSources.remove(configId);
    if (removed != null) {
      removed.retire();
    }
  }

  int pooledDataSourceCount() {
    return directDataSources.size();
  }

  Optional<DirectConnectionPoolMetrics> poolMetrics(Long configId) {
    if (configId == null) {
      return Optional.empty();
    }
    ManagedDataSource managed = directDataSources.get(configId);
    return managed == null ? Optional.empty() : Optional.of(managed.metrics());
  }

  private ManagedDataSource acquireManagedDataSource(
      GitlabSyncConfig config, SyncExecutionBudget budget) {
    if (config.getId() == null) {
      ManagedDataSource temporary =
          new ManagedDataSource(
              dataSourceFactory.apply(config, budget), PoolSpec.from(connectionSettings, config, budget));
      temporary.retireAfterLastLease();
      return temporary;
    }
    PoolSpec requestedSpec = PoolSpec.from(connectionSettings, config, budget);
    return directDataSources.compute(
        config.getId(),
        (configId, current) -> {
          if (current != null && current.matches(requestedSpec)) {
            return current;
          }
          ManagedDataSource replacement =
              new ManagedDataSource(dataSourceFactory.apply(config, budget), requestedSpec);
          if (current != null) {
            current.retire();
          }
          return replacement;
        });
  }

  private Connection leasedConnection(Connection connection, ManagedDataSource.Lease lease) {
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              if ("close".equals(method.getName())) {
                try {
                  connection.close();
                } finally {
                  lease.close();
                }
                return null;
              }
              try {
                return method.invoke(connection, args);
              } catch (InvocationTargetException error) {
                throw error.getCause();
              }
            });
  }

  private record PoolSpec(
      String jdbcUrl,
      String username,
      String password,
      int poolSize,
      long acquireTimeoutMs) {
    private static PoolSpec from(
        GitlabSourceConnectionSettings connectionSettings,
        GitlabSyncConfig config,
        SyncExecutionBudget budget) {
      return new PoolSpec(
          connectionSettings.buildJdbcUrl(config),
          connectionSettings.normalizeDbUser(config),
          config.getDbPassword(),
          budget.directPoolSize(),
          budget.connectionAcquireTimeoutMs());
    }
  }

  private record ResultColumn(
      int index, String label, int jdbcType, String jdbcTypeName) {}

  private static final class ManagedDataSource {
    private final HikariDataSource dataSource;
    private final PoolSpec spec;
    private int leases;
    private boolean retired;
    private boolean retireAfterLastLease;

    private ManagedDataSource(HikariDataSource dataSource, PoolSpec spec) {
      this.dataSource = dataSource;
      this.spec = spec;
    }

    private synchronized Lease acquire() {
      if (retired) {
        throw new IllegalStateException("GitLab DIRECT 连接池已经退休");
      }
      leases++;
      return new Lease(this, dataSource);
    }

    private boolean matches(PoolSpec candidate) {
      return spec.equals(candidate);
    }

    private DirectConnectionPoolMetrics metrics() {
      HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
      if (pool == null) {
        return new DirectConnectionPoolMetrics(spec.poolSize(), 0, 0, 0, 0);
      }
      return new DirectConnectionPoolMetrics(
          spec.poolSize(),
          pool.getTotalConnections(),
          pool.getActiveConnections(),
          pool.getIdleConnections(),
          pool.getThreadsAwaitingConnection());
    }

    private synchronized void retireAfterLastLease() {
      retireAfterLastLease = true;
    }

    private synchronized void retire() {
      retired = true;
      closeIfUnused();
    }

    private synchronized void release() {
      leases = Math.max(0, leases - 1);
      if (retireAfterLastLease) {
        retired = true;
      }
      closeIfUnused();
    }

    private void closeIfUnused() {
      if (retired && leases == 0 && !dataSource.isClosed()) {
        dataSource.close();
      }
    }

    private static final class Lease implements AutoCloseable {
      private final ManagedDataSource owner;
      private final HikariDataSource dataSource;
      private boolean closed;

      private Lease(ManagedDataSource owner, HikariDataSource dataSource) {
        this.owner = owner;
        this.dataSource = dataSource;
      }

      private Connection getConnection() throws Exception {
        return dataSource.getConnection();
      }

      @Override
      public void close() {
        if (closed) {
          return;
        }
        closed = true;
        owner.release();
      }
    }
  }
}
