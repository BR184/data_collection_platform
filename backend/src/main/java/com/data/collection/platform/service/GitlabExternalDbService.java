package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.DirectConnectionPoolMetrics;
import com.data.collection.platform.entity.GitlabSourceMetadataDiagnosticsResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabTableProbe;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.TableWhitelistOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * GitLab 来源数据库访问门面：组合扫描 SQL 构建、DIRECT/Docker 查询分派、schema 发现与元数据支持，
 * 向同步、诊断和浏览器组件提供统一的来源读取入口。本类不持有连接资源，生命周期由各执行器管理。
 */
@Service
@Slf4j
public class GitlabExternalDbService {
  private final GitlabSourceScanSqlBuilder scanSqlBuilder;
  private final GitlabPrimaryKeyExistenceQueryBuilder primaryKeyQueryBuilder;
  private final GitlabAuthoritativeScopeQueryBuilder authoritativeScopeQueryBuilder;
  private final GitlabDirectJdbcExecutor directJdbcExecutor;
  private final GitlabSourceQueryDispatcher queryDispatcher;
  private final GitlabSourceSchemaDiscoveryService schemaDiscoveryService;
  private final GitlabSourceMetadataSupport metadataSupport;

  public GitlabExternalDbService(
      GitlabSourceScanSqlBuilder scanSqlBuilder,
      GitlabPrimaryKeyExistenceQueryBuilder primaryKeyQueryBuilder,
      GitlabAuthoritativeScopeQueryBuilder authoritativeScopeQueryBuilder,
      GitlabDirectJdbcExecutor directJdbcExecutor,
      GitlabSourceQueryDispatcher queryDispatcher,
      GitlabSourceSchemaDiscoveryService schemaDiscoveryService,
      GitlabSourceMetadataSupport metadataSupport) {
    this.scanSqlBuilder = scanSqlBuilder;
    this.primaryKeyQueryBuilder = primaryKeyQueryBuilder;
    this.authoritativeScopeQueryBuilder = authoritativeScopeQueryBuilder;
    this.directJdbcExecutor = directJdbcExecutor;
    this.queryDispatcher = queryDispatcher;
    this.schemaDiscoveryService = schemaDiscoveryService;
    this.metadataSupport = metadataSupport;
  }

  /** 测试指定数据源配置的连通性；非 BizException 失败统一包装为连接失败业务异常。 */
  public void testConnection(GitlabSyncConfig config) {
    try {
      queryDispatcher.testConnection(config);
    } catch (Exception e) {
      throw e instanceof BizException bizException
          ? bizException
          : new BizException("GitLab PostgreSQL connection failed: " + e.getMessage());
    }
  }

  /**
   * 返回指定 DIRECT 数据源当前已初始化连接池的即时指标。
   *
   * @param configId GitLab 数据源配置 ID
   * @return 连接池尚未初始化时为空
   */
  public Optional<DirectConnectionPoolMetrics> directPoolMetrics(Long configId) {
    return directJdbcExecutor.poolMetrics(configId);
  }

  public List<TableWhitelistOption> discoverTables(GitlabSyncConfig config, Map<String, String> labels, List<String> recommendedTables) {
    return schemaDiscoveryService.discoverTables(config, labels, recommendedTables);
  }

  public SourceTableSchema discoverTableSchema(GitlabSyncConfig config, TableWhitelistOption option) {
    return schemaDiscoveryService.discoverTableSchema(config, option);
  }

  public List<Map<String, Object>> fullTableScan(GitlabSyncConfig config, TableWhitelistOption option) {
    return queryDispatcher.query(config, scanSqlBuilder.buildFullTableScanSql(option));
  }

  public List<Map<String, Object>> fullCursorScan(
      GitlabSyncConfig config,
      TableWhitelistOption option,
      SourceTableSchema schema,
      String cursorPk,
      int batchSize) {
    return executeSourceQuery(config, buildFullCursorScanSql(option, schema, cursorPk, batchSize));
  }

  public List<Map<String, Object>> incrementalScan(GitlabSyncConfig config, TableWhitelistOption option, LocalDateTime since) {
    if (since == null || option.updatedAtColumn() == null || option.updatedAtColumn().isBlank()) {
      return List.of();
    }
    return timeWindowScan(config, option, since);
  }

  /** 在固定时间窗口内按时间+主键游标分页读取增量行。 */
  public List<Map<String, Object>> incrementalCursorScan(
      GitlabSyncConfig config,
      TableWhitelistOption option,
      SourceTableSchema schema,
      LocalDateTime watermark,
      LocalDateTime upperBound,
      LocalDateTime cursorUpdatedAt,
      String cursorPk,
      int batchSize) {
    if (watermark == null || option.updatedAtColumn() == null || option.updatedAtColumn().isBlank()) {
      return List.of();
    }
    return executeSourceQuery(
        config,
        scanSqlBuilder.buildCursorBatchScanSql(
            option, schema, watermark, upperBound, cursorUpdatedAt, cursorPk, batchSize));
  }

  /** 按固定单调主键上界读取追加型来源表。 */
  public List<Map<String, Object>> monotonicPrimaryKeyScan(
      GitlabSyncConfig config,
      TableWhitelistOption option,
      SourceTableSchema schema,
      String cursorPk,
      String upperBoundPk,
      int batchSize) {
    return executeSourceQuery(
        config,
        scanSqlBuilder.buildMonotonicPrimaryKeyScanSql(
            option, schema, cursorPk, upperBoundPk, batchSize));
  }

  /** 按完整范围查询来源当前集合。 */
  public List<Map<String, Object>> preciseScan(
      GitlabSyncConfig config,
      TableWhitelistOption option,
      Map<String, Object> lookupScope) {
    if (lookupScope == null || lookupScope.isEmpty()) {
      return List.of();
    }
    return executeSourceQuery(config, scanSqlBuilder.buildPreciseScanSql(option, lookupScope));
  }

  /**
   * 在一次来源查询中读取多个同构权威范围的完整当前集合。
   *
   * <p>返回映射始终包含全部请求范围；来源没有任何行的范围对应空列表。范围 ID 仅用于
   * 本批次关联，不进入来源业务数据。
   */
  public Map<Long, List<Map<String, Object>>> authoritativeScopeScan(
      GitlabSyncConfig config,
      TableWhitelistOption option,
      SourceTableSchema schema,
      Map<Long, Map<String, Object>> lookupScopes) {
    if (lookupScopes == null || lookupScopes.isEmpty()) {
      return Map.of();
    }
    List<GitlabAuthoritativeScopeQueryBuilder.ScopeInput> inputs =
        lookupScopes.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(
                entry ->
                    new GitlabAuthoritativeScopeQueryBuilder.ScopeInput(
                        entry.getKey(), entry.getValue()))
            .toList();
    boolean direct = config != null && config.getSourceMode() == com.data.collection.platform.entity.SourceMode.DIRECT;
    List<Map<String, Object>> rows =
        direct
            ? queryDispatcher.query(
                config, authoritativeScopeQueryBuilder.buildDirect(option, schema, inputs))
            : queryDispatcher.scriptQuery(
                config,
                authoritativeScopeQueryBuilder.buildDockerCopyScript(option, schema, inputs));
    LinkedHashMap<Long, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
    inputs.forEach(input -> grouped.put(input.scopeId(), new ArrayList<>()));
    for (Map<String, Object> row : rows) {
      long scopeId = toLong(row.get(GitlabAuthoritativeScopeQueryBuilder.SCOPE_ID_COLUMN));
      List<Map<String, Object>> scopeRows = grouped.get(scopeId);
      if (scopeRows == null) {
        throw new BizException("GitLab 权威范围查询返回了请求批次之外的范围 ID");
      }
      LinkedHashMap<String, Object> sourceRow = new LinkedHashMap<>(row);
      sourceRow.remove(GitlabAuthoritativeScopeQueryBuilder.SCOPE_ID_COLUMN);
      scopeRows.add(java.util.Collections.unmodifiableMap(sourceRow));
    }
    LinkedHashMap<Long, List<Map<String, Object>>> immutable = new LinkedHashMap<>();
    grouped.forEach((scopeId, scopeRows) -> immutable.put(scopeId, List.copyOf(scopeRows)));
    return java.util.Collections.unmodifiableMap(immutable);
  }

  public List<Map<String, Object>> previewTablePage(
      GitlabSyncConfig config,
      TableWhitelistOption option,
      SourceTableSchema schema,
      String keyword,
      String sortField,
      String sortOrder,
      int page,
      int size) {
    return executeSourceQuery(
        config,
        scanSqlBuilder.buildPreviewTablePageSql(option, schema, keyword, sortField, sortOrder, page, size));
  }

  public GitlabTableProbe probeTable(GitlabSyncConfig config, TableWhitelistOption option) {
    List<Map<String, Object>> rows = executeSourceQuery(config, scanSqlBuilder.buildTableProbeSql(option));
    if (rows.isEmpty()) {
      return new GitlabTableProbe(0L, null, "", "");
    }
    Map<String, Object> row = rows.get(0);
    return new GitlabTableProbe(
        toLong(row.get("row_count")),
        toLocalDateTime(row.get("max_updated_at")),
        Objects.toString(row.get("min_pk"), ""),
        Objects.toString(row.get("max_pk"), ""));
  }

  public LocalDateTime findMaxUpdatedAt(GitlabSyncConfig config, TableWhitelistOption option) {
    if (option == null || option.updatedAtColumn() == null || option.updatedAtColumn().isBlank()) {
      return null;
    }
    List<Map<String, Object>> rows = executeSourceQuery(config, scanSqlBuilder.buildMaxUpdatedAtProbeSql(option));
    if (rows.isEmpty()) {
      return null;
    }
    return toLocalDateTime(rows.get(0).get("max_updated_at"));
  }

  /** 返回单列主键来源当前最大主键的类型化 JSON 游标。 */
  public String findMaxPrimaryKeyCursor(
      GitlabSyncConfig config, TableWhitelistOption option) {
    List<Map<String, Object>> rows =
        executeSourceQuery(config, scanSqlBuilder.buildMaxPrimaryKeyProbeSql(option));
    if (rows.isEmpty() || rows.getFirst().get("max_pk") == null) {
      return null;
    }
    return scanSqlBuilder.toJsonCursor(List.of(String.valueOf(rows.getFirst().get("max_pk"))));
  }

  public Set<String> findExistingPrimaryKeySignatures(
      GitlabSyncConfig config,
      TableWhitelistOption option,
      SourceTableSchema schema,
      List<Map<String, Object>> primaryKeyRows) {
    if (primaryKeyRows == null || primaryKeyRows.isEmpty()) {
      return Set.of();
    }
    List<String> configuredPrimaryKeys = metadataSupport.splitPrimaryKeys(option.primaryKey());
    List<String> primaryKeys = configuredPrimaryKeys.isEmpty() ? List.of("id") : configuredPrimaryKeys;
    boolean direct = config != null && config.getSourceMode() == com.data.collection.platform.entity.SourceMode.DIRECT;
    List<Map<String, Object>> rows =
        direct
            ? queryDispatcher.query(
                config,
                primaryKeyQueryBuilder.buildDirect(
                    option, schema, primaryKeys, primaryKeyRows))
            : queryDispatcher.scriptQuery(
                config,
                primaryKeyQueryBuilder.buildDockerCopyScript(
                    option, schema, primaryKeys, primaryKeyRows));
    Set<String> requested =
        primaryKeyRows.stream()
            .map(row -> PrimaryKeySignatureSupport.signature(primaryKeys, row))
            .collect(java.util.stream.Collectors.toSet());
    Set<String> existing = rows.stream()
        .map(row -> PrimaryKeySignatureSupport.signature(primaryKeys, row))
        .collect(java.util.stream.Collectors.toSet());
    if (!requested.containsAll(existing)) {
      throw new BizException("GitLab 主键存在性查询返回了请求批次之外的键");
    }
    return existing;
  }

  private List<Map<String, Object>> timeWindowScan(GitlabSyncConfig config, TableWhitelistOption option, LocalDateTime since) {
    return executeSourceQuery(config, scanSqlBuilder.buildTimeWindowScanSql(option, since));
  }

  String buildFullCursorScanSql(
      TableWhitelistOption option,
      SourceTableSchema schema,
      String cursorPk,
      int batchSize) {
    return scanSqlBuilder.buildFullCursorScanSql(option, schema, cursorPk, batchSize);
  }

  Map<String, String> discoverPrimaryKeysByTable(GitlabSyncConfig config) {
    return schemaDiscoveryService.discoverPrimaryKeysByTable(config);
  }

  Map<String, String> discoverUpdatedAtColumns(GitlabSyncConfig config) {
    return schemaDiscoveryService.discoverUpdatedAtColumns(config);
  }

  Map<String, List<com.data.collection.platform.entity.SourceTableColumn>> discoverColumnsByTable(GitlabSyncConfig config) {
    return schemaDiscoveryService.discoverColumnsByTable(config);
  }

  public GitlabSourceMetadataDiagnosticsResponse inspectSourceMetadata(
      GitlabSyncConfig config,
      List<TableWhitelistOption> whitelistOptions) {
    return schemaDiscoveryService.inspectSourceMetadata(config, whitelistOptions);
  }

  public LocalDateTime extractUpdatedAt(TableWhitelistOption option, Map<String, Object> row) {
    if (option.updatedAtColumn() == null || option.updatedAtColumn().isBlank()) {
      return null;
    }
    Object value = row.get(option.updatedAtColumn());
    if (value == null) {
      return null;
    }
    return GitlabSourceTimestampNormalizer.normalizeSourceValue(value);
  }

  private List<Map<String, Object>> executeSourceQuery(GitlabSyncConfig config, String sql) {
    return queryDispatcher.query(config, sql);
  }

  private long toLong(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value == null) {
      return 0L;
    }
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  private LocalDateTime toLocalDateTime(Object value) {
    return GitlabSourceTimestampNormalizer.normalizeSourceValue(value);
  }
}
