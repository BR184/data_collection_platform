package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.logging.SyncRunLogContext;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSourceMetadataDiagnosticsResponse;
import com.data.collection.platform.entity.GitlabSourceTableDiagnosticsResponse;
import com.data.collection.platform.entity.GitlabTableProbe;
import com.data.collection.platform.entity.GitlabTableShardProbe;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class GitlabExternalDbService {
  private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final ObjectMapper objectMapper;
  private final GitlabSourceScanSqlBuilder scanSqlBuilder;
  private final GitlabSourceQueryRetryPolicy queryRetryPolicy;
  private final GitlabSourceConnectionSettings connectionSettings;
  private final GitlabDockerPsqlExecutor dockerPsqlExecutor;
  private final GitlabJdbcValueNormalizer jdbcValueNormalizer;
  private final GitlabDirectJdbcExecutor directJdbcExecutor;
  private final GitlabSourceMetadataSupport metadataSupport;
  private final Map<SourceMode, GitlabSourceAdapter> sourceAdapters;

  public GitlabExternalDbService(GitlabMirrorProperties properties, ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    this.scanSqlBuilder = new GitlabSourceScanSqlBuilder();
    this.queryRetryPolicy = new GitlabSourceQueryRetryPolicy(properties);
    this.connectionSettings = new GitlabSourceConnectionSettings(properties);
    this.dockerPsqlExecutor = new GitlabDockerPsqlExecutor(properties, connectionSettings);
    this.jdbcValueNormalizer = new GitlabJdbcValueNormalizer();
    this.directJdbcExecutor =
        new GitlabDirectJdbcExecutor(connectionSettings, queryRetryPolicy, jdbcValueNormalizer);
    this.metadataSupport = new GitlabSourceMetadataSupport();
    this.sourceAdapters = Map.of(
        SourceMode.DIRECT, new DirectJdbcSourceAdapter(),
        SourceMode.DOCKER, new DockerPsqlSourceAdapter());
  }

  public void testConnection(GitlabSyncConfig config) {
    try {
      sourceAdapter(config).testConnection(config);
    } catch (Exception e) {
      try (SyncRunLogContext.Scope action = SyncRunLogContext.action("Connection_Test")) {
        log.error("GitLab PostgreSQL connection test failed", e);
      }
      throw e instanceof BizException bizException
          ? bizException
          : new BizException("GitLab PostgreSQL connection failed: " + e.getMessage());
    }
  }

  public List<TableWhitelistOption> discoverTables(GitlabSyncConfig config, Map<String, String> labels, List<String> recommendedTables) {
    Map<String, String> primaryKeyMap = discoverPrimaryKeysByTable(config);
    Map<String, String> updatedAtColumnMap = discoverUpdatedAtColumns(config);
    List<TableWhitelistOption> result = new ArrayList<>(primaryKeyMap.size());
    for (Map.Entry<String, String> entry : primaryKeyMap.entrySet()) {
      String tableName = entry.getKey();
      String primaryKey = entry.getValue();
      String updatedAtColumn = updatedAtColumnMap.get(tableName);
      String label = labels.getOrDefault(tableName, tableName);
      boolean recommended = recommendedTables.contains(tableName);
      result.add(new TableWhitelistOption(tableName, label, primaryKey, updatedAtColumn, recommended));
    }
    return result;
  }

  public SourceTableSchema discoverTableSchema(GitlabSyncConfig config, TableWhitelistOption option) {
    String sql = """
        select
          a.attname as column_name,
          pg_catalog.format_type(a.atttypid, a.atttypmod) as formatted_type,
          not a.attnotnull as nullable,
          a.attnum as ordinal_position
        from pg_attribute a
        join pg_class c on a.attrelid = c.oid
        join pg_namespace n on c.relnamespace = n.oid
        where n.nspname = 'public'
          and c.relname = '%s'
          and a.attnum > 0
          and not a.attisdropped
        order by a.attnum
        """.formatted(option.tableName().replace("'", "''"));
    List<Map<String, Object>> rows = executeSourceQuery(config, sql);
    List<SourceTableColumn> columns = new ArrayList<>(rows.size());
    for (Map<String, Object> row : rows) {
      columns.add(new SourceTableColumn(
          String.valueOf(row.get("column_name")),
          String.valueOf(row.get("formatted_type")),
          Boolean.parseBoolean(String.valueOf(row.get("nullable"))),
          ((Number) row.get("ordinal_position")).intValue()));
    }
    if (columns.isEmpty()) {
      throw new BizException("鏈彂鐜版簮琛ㄧ粨鏋? " + option.tableName());
    }
    List<String> primaryKeys = List.of(option.primaryKey().split(","))
        .stream()
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .toList();
    return new SourceTableSchema(option.tableName(), primaryKeys, option.updatedAtColumn(), columns);
  }

  public List<Map<String, Object>> fullTableScan(GitlabSyncConfig config, TableWhitelistOption option) {
    String sql = buildFullTableScanSql(option);
    return executeSourceQuery(config, sql);
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

  public List<Map<String, Object>> compensationScan(GitlabSyncConfig config, TableWhitelistOption option, LocalDateTime since) {
    if (since == null || option.updatedAtColumn() == null || option.updatedAtColumn().isBlank()) {
      return List.of();
    }
    return timeWindowScan(config, option, since);
  }

  public List<Map<String, Object>> incrementalCursorScan(
      GitlabSyncConfig config,
      TableWhitelistOption option,
      LocalDateTime watermark,
      LocalDateTime cursorUpdatedAt,
      String cursorPk,
      int batchSize) {
    if (watermark == null || option.updatedAtColumn() == null || option.updatedAtColumn().isBlank()) {
      return List.of();
    }
    return executeSourceQuery(config, buildCursorBatchScanSql(option, watermark, cursorUpdatedAt, cursorPk, batchSize));
  }

  public List<Map<String, Object>> preciseScan(
      GitlabSyncConfig config,
      TableWhitelistOption option,
      String lookupColumn,
      Object lookupValue) {
    if (lookupColumn == null || lookupColumn.isBlank() || lookupValue == null) {
      return List.of();
    }
    String sql = buildPreciseScanSql(option, lookupColumn, lookupValue);
    return executeSourceQuery(config, sql);
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
        buildPreviewTablePageSql(option, schema, keyword, sortField, sortOrder, page, size));
  }

  public GitlabTableProbe probeTable(GitlabSyncConfig config, TableWhitelistOption option) {
    List<Map<String, Object>> rows = executeSourceQuery(config, buildTableProbeSql(option));
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
    List<Map<String, Object>> rows = executeSourceQuery(config, buildMaxUpdatedAtProbeSql(option));
    if (rows.isEmpty()) {
      return null;
    }
    return toLocalDateTime(rows.get(0).get("max_updated_at"));
  }

  public List<GitlabTableShardProbe> probeTableShards(
      GitlabSyncConfig config,
      TableWhitelistOption option,
      SourceTableSchema schema,
      int shardKeyLength) {
    return executeSourceQuery(config, buildTableShardProbeSql(option, schema, shardKeyLength)).stream()
        .map(this::toShardProbe)
        .toList();
  }

  public Set<String> findExistingPrimaryKeySignatures(
      GitlabSyncConfig config,
      TableWhitelistOption option,
      List<Map<String, Object>> primaryKeyRows) {
    if (primaryKeyRows == null || primaryKeyRows.isEmpty()) {
      return Set.of();
    }
    List<String> configuredPrimaryKeys = splitPrimaryKeys(option.primaryKey());
    List<String> primaryKeys = configuredPrimaryKeys.isEmpty() ? List.of("id") : configuredPrimaryKeys;
    List<Map<String, Object>> rows =
        executeSourceQuery(config, buildExistingPrimaryKeysSql(option, primaryKeys, primaryKeyRows));
    return rows.stream()
        .map(row -> PrimaryKeySignatureSupport.signature(primaryKeys, row))
        .collect(java.util.stream.Collectors.toSet());
  }

  public List<Map<String, Object>> shardCursorScan(
      GitlabSyncConfig config,
      TableWhitelistOption option,
      SourceTableSchema schema,
      String shardKey,
      String cursorPk,
      int batchSize) {
    return executeSourceQuery(config, buildShardCursorScanSql(option, schema, shardKey, cursorPk, batchSize));
  }

  private List<Map<String, Object>> timeWindowScan(GitlabSyncConfig config, TableWhitelistOption option, LocalDateTime since) {
    String sql = buildTimeWindowScanSql(option, since);
    return executeSourceQuery(config, sql);
  }

  String buildFullTableScanSql(TableWhitelistOption option) {
    return scanSqlBuilder.buildFullTableScanSql(option);
  }

  String buildFullCursorScanSql(
      TableWhitelistOption option,
      SourceTableSchema schema,
      String cursorPk,
      int batchSize) {
    return scanSqlBuilder.buildFullCursorScanSql(option, schema, cursorPk, batchSize);
  }

  String buildPreciseScanSql(TableWhitelistOption option, String lookupColumn, Object lookupValue) {
    return scanSqlBuilder.buildPreciseScanSql(option, lookupColumn, lookupValue);
  }

  String buildPreviewTablePageSql(
      TableWhitelistOption option,
      SourceTableSchema schema,
      String keyword,
      String sortField,
      String sortOrder,
      int page,
      int size) {
    return scanSqlBuilder.buildPreviewTablePageSql(option, schema, keyword, sortField, sortOrder, page, size);
  }

  String buildTimeWindowScanSql(TableWhitelistOption option, LocalDateTime since) {
    return scanSqlBuilder.buildTimeWindowScanSql(option, since);
  }

  String buildCursorBatchScanSql(
      TableWhitelistOption option,
      LocalDateTime watermark,
      LocalDateTime cursorUpdatedAt,
      String cursorPk,
      int batchSize) {
    return scanSqlBuilder.buildCursorBatchScanSql(option, watermark, cursorUpdatedAt, cursorPk, batchSize);
  }

  String buildTableProbeSql(TableWhitelistOption option) {
    return scanSqlBuilder.buildTableProbeSql(option);
  }

  String buildMaxUpdatedAtProbeSql(TableWhitelistOption option) {
    return scanSqlBuilder.buildMaxUpdatedAtProbeSql(option);
  }

  String buildExistingPrimaryKeysSql(
      TableWhitelistOption option,
      List<String> primaryKeys,
      List<Map<String, Object>> primaryKeyRows) {
    return scanSqlBuilder.buildExistingPrimaryKeysSql(option, primaryKeys, primaryKeyRows);
  }

  String buildTableShardProbeSql(TableWhitelistOption option, SourceTableSchema schema, int shardKeyLength) {
    return scanSqlBuilder.buildTableShardProbeSql(option, schema, shardKeyLength);
  }

  String buildShardCursorScanSql(
      TableWhitelistOption option,
      SourceTableSchema schema,
      String shardKey,
      String cursorPk,
      int batchSize) {
    return scanSqlBuilder.buildShardCursorScanSql(option, schema, shardKey, cursorPk, batchSize);
  }

  Map<String, String> discoverPrimaryKeysByTable(GitlabSyncConfig config) {
    String sql = """
        select
          c.relname as table_name,
          string_agg(a.attname, ',' order by array_position(i.indkey::int2[], a.attnum::int2)) as primary_key
        from pg_class c
        join pg_namespace n
          on n.oid = c.relnamespace
        join pg_index i
          on i.indrelid = c.oid
         and i.indisprimary
        join pg_attribute a
          on a.attrelid = c.oid
         and a.attnum = any(i.indkey)
        where n.nspname = 'public'
          and c.relkind = 'r'
        group by c.relname
        order by c.relname
        """;
    List<Map<String, Object>> rows = executeSourceQuery(config, sql);
    Map<String, String> primaryKeys = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      primaryKeys.put(String.valueOf(row.get("table_name")), String.valueOf(row.get("primary_key")));
    }
    return primaryKeys;
  }

  Map<String, String> discoverUpdatedAtColumns(GitlabSyncConfig config) {
    Map<String, List<SourceTableColumn>> columnsByTable = discoverColumnsByTable(config);
    Map<String, String> resolved = new HashMap<>();
    for (Map.Entry<String, List<SourceTableColumn>> entry : columnsByTable.entrySet()) {
      String updatedAtColumn = resolveUpdatedAtColumn(entry.getValue().stream().map(SourceTableColumn::columnName).toList());
      if (updatedAtColumn != null) {
        resolved.put(entry.getKey(), updatedAtColumn);
      }
    }
    return resolved;
  }

  Map<String, List<SourceTableColumn>> discoverColumnsByTable(GitlabSyncConfig config) {
    String sql = """
        select
          c.relname as table_name,
          a.attname as column_name,
          pg_catalog.format_type(a.atttypid, a.atttypmod) as formatted_type,
          not a.attnotnull as nullable,
          a.attnum as ordinal_position
        from pg_attribute a
        join pg_class c on a.attrelid = c.oid
        join pg_namespace n on c.relnamespace = n.oid
        where n.nspname = 'public'
          and c.relkind = 'r'
          and a.attnum > 0
          and not a.attisdropped
        order by c.relname, a.attnum
        """;
    List<Map<String, Object>> rows = executeSourceQuery(config, sql);
    Map<String, List<SourceTableColumn>> columnsByTable = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      String tableName = String.valueOf(row.get("table_name"));
      SourceTableColumn column = new SourceTableColumn(
          String.valueOf(row.get("column_name")),
          String.valueOf(row.get("formatted_type")),
          Boolean.parseBoolean(String.valueOf(row.get("nullable"))),
          ((Number) row.get("ordinal_position")).intValue());
      columnsByTable.computeIfAbsent(tableName, ignored -> new ArrayList<>()).add(column);
    }
    return columnsByTable;
  }

  public GitlabSourceMetadataDiagnosticsResponse inspectSourceMetadata(
      GitlabSyncConfig config,
      List<TableWhitelistOption> whitelistOptions) {
    Map<String, String> primaryKeysByTable = discoverPrimaryKeysByTable(config);
    Map<String, List<SourceTableColumn>> columnsByTable = discoverColumnsByTable(config);
    Map<String, String> updatedAtColumns = new HashMap<>();
    for (Map.Entry<String, List<SourceTableColumn>> entry : columnsByTable.entrySet()) {
      updatedAtColumns.put(
          entry.getKey(),
          resolveUpdatedAtColumn(entry.getValue().stream().map(SourceTableColumn::columnName).toList()));
    }
    Map<String, TableWhitelistOption> optionsByTable = new HashMap<>();
    for (TableWhitelistOption option : whitelistOptions == null ? List.<TableWhitelistOption>of() : whitelistOptions) {
      optionsByTable.put(option.tableName(), option);
    }
    List<GitlabSourceTableDiagnosticsResponse> sourceTables = new ArrayList<>();
    for (Map.Entry<String, List<SourceTableColumn>> entry : columnsByTable.entrySet()) {
      String tableName = entry.getKey();
      String primaryKey = primaryKeysByTable.get(tableName);
      String updatedAtColumn = updatedAtColumns.get(tableName);
      TableWhitelistOption option = optionsByTable.get(tableName);
      SourceTableSchema schema = new SourceTableSchema(
          tableName,
          splitPrimaryKeys(primaryKey),
          updatedAtColumn,
          entry.getValue());
      sourceTables.add(new GitlabSourceTableDiagnosticsResponse(
          tableName,
          primaryKey,
          updatedAtColumn,
          resolveRowStrategy(updatedAtColumn),
          buildSchemaFingerprint(schema),
          option != null && option.recommended()));
    }
    long missingPrimaryKeyCount = sourceTables.stream()
        .filter(table -> table.primaryKey() == null || table.primaryKey().isBlank())
        .count();
    long missingUpdatedAtCount = sourceTables.stream()
        .filter(table -> !"INCREMENTAL".equals(table.rowStrategy()))
        .count();
    return new GitlabSourceMetadataDiagnosticsResponse(
        true,
        "GitLab source metadata discovered",
        sourceTables.size(),
        primaryKeysByTable.size(),
        Math.toIntExact(missingPrimaryKeyCount),
        Math.toIntExact(missingUpdatedAtCount),
        sourceTables);
  }

  String resolveUpdatedAtColumn(List<String> columnNames) {
    return metadataSupport.resolveUpdatedAtColumn(columnNames);
  }

  public String buildRecordKey(TableWhitelistOption option, Map<String, Object> row) {
    String[] primaryKeys = option.primaryKey().split(",");
    List<String> values = new ArrayList<>();
    for (String primaryKey : primaryKeys) {
      Object value = row.get(primaryKey.trim());
      values.add(value == null ? "null" : String.valueOf(value));
    }
    return String.join("::", values);
  }

  public LocalDateTime extractUpdatedAt(TableWhitelistOption option, Map<String, Object> row) {
    if (option.updatedAtColumn() == null || option.updatedAtColumn().isBlank()) {
      return null;
    }
    Object value = row.get(option.updatedAtColumn());
    if (value == null) {
      return null;
    }
    if (value instanceof LocalDateTime localDateTime) {
      return localDateTime;
    }
    if (value instanceof Timestamp timestamp) {
      return timestamp.toInstant().atOffset(ZoneOffset.UTC).toLocalDateTime();
    }
    if (value instanceof java.util.Date date) {
      return LocalDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC);
    }
    if (value instanceof String text) {
      return parseDateTime(text);
    }
    return null;
  }

  String resolveRowStrategy(String updatedAtColumn) {
    return metadataSupport.resolveRowStrategy(updatedAtColumn);
  }

  String buildSchemaFingerprint(SourceTableSchema schema) {
    return metadataSupport.buildSchemaFingerprint(schema);
  }

  private List<String> splitPrimaryKeys(String primaryKey) {
    return metadataSupport.splitPrimaryKeys(primaryKey);
  }

  private List<Map<String, Object>> executeSourceQuery(GitlabSyncConfig config, String sql) {
    return sourceAdapter(config).query(config, sql);
  }

  private GitlabSourceAdapter sourceAdapter(GitlabSyncConfig config) {
    SourceMode sourceMode = config == null || config.getSourceMode() == null ? SourceMode.DOCKER : config.getSourceMode();
    GitlabSourceAdapter adapter = sourceAdapters.get(sourceMode);
    if (adapter == null) {
      throw new BizException("不支持的 GitLab 数据源模式：" + sourceMode);
    }
    return adapter;
  }

  Object normalizeJdbcValue(Object value) {
    return jdbcValueNormalizer.normalize(value);
  }

  private List<Map<String, Object>> executeDockerQuery(GitlabSyncConfig config, String sql) {
    try {
      return executeExternalQueryWithRetry("Docker query", () -> {
        try {
          List<String> lines = dockerPsqlExecutor.execute(config, "select row_to_json(t)::text from (%s) t".formatted(sql));
          List<Map<String, Object>> rows = new ArrayList<>();
          for (String line : lines) {
            if (line == null || line.isBlank()) {
              continue;
            }
            if (line.startsWith("ERROR:") || line.startsWith("FATAL:")) {
              throw new BizException(line);
            }
            rows.add(objectMapper.readValue(line, MAP_TYPE));
          }
          return rows;
        } catch (BizException e) {
          throw e;
        } catch (Exception e) {
          throw new BizException("Failed to query GitLab database via Docker: " + e.getMessage());
        }
      });
    } catch (BizException e) {
      try (SyncRunLogContext.Scope action = SyncRunLogContext.action("Data_Fetching")) {
        log.error("Failed to query GitLab database via Docker", e);
      }
      throw e;
    }
  }

  String buildJdbcUrl(GitlabSyncConfig config) {
    return connectionSettings.buildJdbcUrl(config);
  }

  <T> T executeExternalQueryWithRetry(String operation, Supplier<T> supplier) {
    return queryRetryPolicy.executeWithRetry(operation, supplier);
  }

  long computeExternalQueryRetryDelayMs(int attempt) {
    return queryRetryPolicy.computeRetryDelayMs(attempt);
  }

  boolean isRetryableExternalFailure(RuntimeException e) {
    return queryRetryPolicy.isRetryableExternalFailure(e);
  }

  private LocalDateTime parseDateTime(String text) {
    try {
      return LocalDateTime.parse(text);
    } catch (Exception ignored) {
    }
    try {
      return LocalDateTime.parse(text.replace(" ", "T"));
    } catch (Exception ignored) {
    }
    try {
      return OffsetDateTime.parse(text).toLocalDateTime();
    } catch (Exception ignored) {
    }
    return null;
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

  private GitlabTableShardProbe toShardProbe(Map<String, Object> row) {
    return new GitlabTableShardProbe(
        Objects.toString(row.get("shard_key"), ""),
        toLong(row.get("row_count")),
        toLocalDateTime(row.get("max_updated_at")),
        Objects.toString(row.get("min_pk"), ""),
        Objects.toString(row.get("max_pk"), ""),
        Objects.toString(row.get("checksum"), ""));
  }

  private LocalDateTime toLocalDateTime(Object value) {
    if (value instanceof LocalDateTime localDateTime) {
      return localDateTime;
    }
    if (value instanceof OffsetDateTime odt) {
      return odt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
    if (value instanceof Timestamp timestamp) {
      return timestamp.toInstant().atOffset(ZoneOffset.UTC).toLocalDateTime();
    }
    if (value instanceof java.util.Date date) {
      return LocalDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC);
    }
    if (value instanceof String text) {
      return parseDateTime(text);
    }
    return null;
  }

  private interface GitlabSourceAdapter {
    SourceMode sourceMode();

    void testConnection(GitlabSyncConfig config);

    List<Map<String, Object>> query(GitlabSyncConfig config, String sql);
  }

  private class DirectJdbcSourceAdapter implements GitlabSourceAdapter {
    @Override
    public SourceMode sourceMode() {
      return SourceMode.DIRECT;
    }

    @Override
    public void testConnection(GitlabSyncConfig config) {
      directJdbcExecutor.testConnection(config);
    }

    @Override
    public List<Map<String, Object>> query(GitlabSyncConfig config, String sql) {
      return directJdbcExecutor.query(config, sql);
    }
  }

  private class DockerPsqlSourceAdapter implements GitlabSourceAdapter {
    @Override
    public SourceMode sourceMode() {
      return SourceMode.DOCKER;
    }

    @Override
    public void testConnection(GitlabSyncConfig config) {
      executeExternalQueryWithRetry("Docker connection test", () -> {
        dockerPsqlExecutor.execute(config, "select 1");
        return null;
      });
    }

    @Override
    public List<Map<String, Object>> query(GitlabSyncConfig config, String sql) {
      return executeDockerQuery(config, sql);
    }
  }
}
