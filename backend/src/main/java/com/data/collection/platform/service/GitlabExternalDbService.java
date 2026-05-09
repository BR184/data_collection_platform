package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.logging.GitlabSyncLogContext;
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
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class GitlabExternalDbService {
  private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final List<String> UPDATED_AT_CANDIDATES = List.of(
      "updatedat",
      "modifiedat",
      "lastmodifiedat",
      "lastupdatedat",
      "updatetime",
      "modifiedtime",
      "lastupdatetime",
      "lastmodifytime",
      "updatedon",
      "modifiedon",
      "lastmodifiedon",
      "lastupdatedon",
      "gmtmodified",
      "operatetime",
      "eventtime",
      "synctime");
  private static final List<String> CREATED_AT_CANDIDATES = List.of(
      "createdat",
      "createdon",
      "createtime",
      "inserttime",
      "writetime",
      "gmtcreate",
      "loadtime",
      "etltime");

  private final GitlabMirrorProperties properties;
  private final ObjectMapper objectMapper;
  private final Map<SourceMode, GitlabSourceAdapter> sourceAdapters;

  public GitlabExternalDbService(GitlabMirrorProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.sourceAdapters = Map.of(
        SourceMode.DIRECT, new DirectJdbcSourceAdapter(),
        SourceMode.DOCKER, new DockerPsqlSourceAdapter());
  }

  public void testConnection(GitlabSyncConfig config) {
    try {
      sourceAdapter(config).testConnection(config);
    } catch (Exception e) {
      try (GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Connection_Test")) {
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
      throw new BizException("未发现源表结构: " + option.tableName());
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
    return "select * from %s".formatted(quoteQualifiedPublicTable(option.tableName()));
  }

  String buildPreciseScanSql(TableWhitelistOption option, String lookupColumn, Object lookupValue) {
    return "select * from %s where %s = %s".formatted(
        quoteQualifiedPublicTable(option.tableName()),
        quoteIdentifier(lookupColumn),
        toSqlLiteral(lookupValue));
  }

  String buildTimeWindowScanSql(TableWhitelistOption option, LocalDateTime since) {
    LocalDateTime gitlabSince = toGitlabSourceTime(since);
    return "select * from %s where %s >= timestamp '%s'".formatted(
        quoteQualifiedPublicTable(option.tableName()),
        quoteIdentifier(option.updatedAtColumn()),
        gitlabSince.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
  }

  String buildCursorBatchScanSql(
      TableWhitelistOption option,
      LocalDateTime watermark,
      LocalDateTime cursorUpdatedAt,
      String cursorPk,
      int batchSize) {
    LocalDateTime gitlabWatermark = toGitlabSourceTime(watermark);
    String updatedAtColumn = quoteIdentifier(option.updatedAtColumn());
    String primaryKeyColumn = quoteIdentifier(firstPrimaryKey(option));
    StringBuilder sql = new StringBuilder("select * from ")
        .append(quoteQualifiedPublicTable(option.tableName()))
        .append(" where ")
        .append(updatedAtColumn)
        .append(" >= timestamp '")
        .append(gitlabWatermark.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
        .append("'");
    if (cursorUpdatedAt != null && cursorPk != null && !cursorPk.isBlank()) {
      LocalDateTime gitlabCursor = toGitlabSourceTime(cursorUpdatedAt);
      sql.append(" and (")
          .append(updatedAtColumn)
          .append(" > timestamp '")
          .append(gitlabCursor.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
          .append("' or (")
          .append(updatedAtColumn)
          .append(" = timestamp '")
          .append(gitlabCursor.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
          .append("' and ")
          .append(primaryKeyColumn)
          .append(" > ")
          .append(toSqlLiteral(cursorPk))
          .append("))");
    }
    return sql.append(" order by ")
        .append(updatedAtColumn)
        .append(" asc, ")
        .append(primaryKeyColumn)
        .append(" asc limit ")
        .append(Math.max(1, batchSize))
        .toString();
  }

  String buildTableProbeSql(TableWhitelistOption option) {
    String primaryKeyColumn = quoteIdentifier(firstPrimaryKey(option));
    String maxUpdatedAtExpression = option.updatedAtColumn() == null || option.updatedAtColumn().isBlank()
        ? "null::timestamp"
        : "max(" + quoteIdentifier(option.updatedAtColumn()) + ")";
    return """
        select count(*) as row_count,
               %s as max_updated_at,
               min(%s)::text as min_pk,
               max(%s)::text as max_pk
          from %s
        """.formatted(
        maxUpdatedAtExpression,
        primaryKeyColumn,
        primaryKeyColumn,
        quoteQualifiedPublicTable(option.tableName())).strip();
  }

  String buildExistingPrimaryKeysSql(
      TableWhitelistOption option,
      List<String> primaryKeys,
      List<Map<String, Object>> primaryKeyRows) {
    String selectColumns = primaryKeys.stream()
        .map(primaryKey -> quoteIdentifier(primaryKey) + "::text as " + quoteIdentifier(primaryKey))
        .collect(java.util.stream.Collectors.joining(", "));
    String predicate = primaryKeyRows.stream()
        .map(row -> primaryKeys.stream()
            .map(primaryKey -> quoteIdentifier(primaryKey) + "::text = " + toSqlLiteral(Objects.toString(row.get(primaryKey), "")))
            .collect(java.util.stream.Collectors.joining(" and ", "(", ")")))
        .collect(java.util.stream.Collectors.joining(" or "));
    return """
        select %s
          from %s
         where %s
        """.formatted(
        selectColumns,
        quoteQualifiedPublicTable(option.tableName()),
        predicate).strip();
  }

  String buildTableShardProbeSql(TableWhitelistOption option, SourceTableSchema schema, int shardKeyLength) {
    String pkExpression = primaryKeySignatureExpression(splitPrimaryKeys(option.primaryKey()), null);
    String rowExpression = rowSignatureExpression(schema, null);
    String maxUpdatedAtExpression = option.updatedAtColumn() == null || option.updatedAtColumn().isBlank()
        ? "null::timestamp"
        : "max(" + quoteIdentifier(option.updatedAtColumn()) + ")";
    int safeShardKeyLength = Math.max(1, Math.min(8, shardKeyLength));
    return """
        select shard_key,
               count(*) as row_count,
               %s as max_updated_at,
               min(pk_signature) as min_pk,
               max(pk_signature) as max_pk,
               md5(coalesce(string_agg(row_hash, ',' order by pk_signature), '')) as checksum
          from (
            select %s as pk_signature,
                   substring(md5(%s), 1, %d) as shard_key,
                   md5(%s) as row_hash,
                   %s
              from %s
          ) shard_rows
         group by shard_key
         order by shard_key
        """.formatted(
        maxUpdatedAtExpression,
        pkExpression,
        pkExpression,
        safeShardKeyLength,
        rowExpression,
        option.updatedAtColumn() == null || option.updatedAtColumn().isBlank()
            ? "null::timestamp as " + quoteIdentifier("__updated_at")
            : quoteIdentifier(option.updatedAtColumn()),
        quoteQualifiedPublicTable(option.tableName())).strip();
  }

  String buildShardCursorScanSql(
      TableWhitelistOption option,
      SourceTableSchema schema,
      String shardKey,
      String cursorPk,
      int batchSize) {
    String pkExpression = primaryKeySignatureExpression(splitPrimaryKeys(option.primaryKey()), "source_rows");
    String cursorPredicate = cursorPk == null || cursorPk.isBlank()
        ? ""
        : " and pk_signature > " + toSqlLiteral(cursorPk);
    return """
        select *
          from (
            select source_rows.*,
                   %s as pk_signature
              from %s source_rows
          ) shard_rows
         where substring(md5(pk_signature), 1, %d) = %s
               %s
         order by pk_signature asc
         limit %d
        """.formatted(
        pkExpression,
        quoteQualifiedPublicTable(option.tableName()),
        Math.max(1, Math.min(8, shardKey == null ? 1 : shardKey.length())),
        toSqlLiteral(shardKey),
        cursorPredicate,
        Math.max(1, batchSize)).strip();
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
          and c.relkind in ('r', 'p')
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
          and c.relkind in ('r', 'p')
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
    return resolveCandidate(columnNames, UPDATED_AT_CANDIDATES)
        .orElseGet(() -> resolveCandidate(columnNames, CREATED_AT_CANDIDATES).orElse(null));
  }

  private java.util.Optional<String> resolveCandidate(List<String> columnNames, List<String> candidates) {
    return columnNames.stream()
        .map(columnName -> Map.entry(columnName, normalizeColumnName(columnName)))
        .filter(entry -> candidates.contains(entry.getValue()))
        .min(Comparator.comparingInt(entry -> candidates.indexOf(entry.getValue())))
        .map(Map.Entry::getKey);
  }

  private String normalizeColumnName(String columnName) {
    return columnName == null ? "" : columnName.replaceAll("[^A-Za-z0-9]", "").toLowerCase();
  }

  private String quoteIdentifier(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  private String quoteQualifiedPublicTable(String tableName) {
    return quoteIdentifier("public") + "." + quoteIdentifier(tableName);
  }

  private String primaryKeySignatureExpression(List<String> primaryKeys, String tableAlias) {
    List<String> safePrimaryKeys = primaryKeys == null || primaryKeys.isEmpty() ? List.of("id") : primaryKeys;
    return safePrimaryKeys.stream()
        .map(primaryKey -> qualifiedColumn(tableAlias, primaryKey) + "::text")
        .collect(java.util.stream.Collectors.joining(", ", "concat_ws(chr(31), ", ")"));
  }

  private String rowSignatureExpression(SourceTableSchema schema, String tableAlias) {
    List<String> columns = schema.columns().stream()
        .map(SourceTableColumn::columnName)
        .toList();
    if (columns.isEmpty()) {
      return "''";
    }
    return columns.stream()
        .map(column -> "to_jsonb(" + qualifiedColumn(tableAlias, column) + ")")
        .collect(java.util.stream.Collectors.joining(", ", "jsonb_build_array(", ")::text"));
  }

  private String qualifiedColumn(String tableAlias, String column) {
    if (tableAlias == null || tableAlias.isBlank()) {
      return quoteIdentifier(column);
    }
    return tableAlias + "." + quoteIdentifier(column);
  }

  private String toSqlLiteral(Object value) {
    if (value instanceof Number || value instanceof Boolean) {
      return String.valueOf(value);
    }
    return "'" + String.valueOf(value).replace("'", "''") + "'";
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
    if (value instanceof Timestamp timestamp) {
      return timestamp.toLocalDateTime();
    }
    if (value instanceof java.util.Date date) {
      return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }
    if (value instanceof String text) {
      return parseDateTime(text);
    }
    return null;
  }

  String resolveRowStrategy(String updatedAtColumn) {
    return updatedAtColumn == null || updatedAtColumn.isBlank() ? "FULL_ONLY" : "INCREMENTAL";
  }

  String buildSchemaFingerprint(SourceTableSchema schema) {
    String payload = schema.tableName()
        + "|pk=" + String.join(",", schema.primaryKeys())
        + "|updated=" + Objects.toString(schema.updatedAtColumn(), "")
        + "|columns=" + schema.columns().stream()
            .map(column -> column.columnName() + ":" + column.formattedType() + ":" + column.nullable())
            .reduce((left, right) -> left + "|" + right)
            .orElse("");
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash).substring(0, 16);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to hash source schema fingerprint", e);
    }
  }

  private List<String> splitPrimaryKeys(String primaryKey) {
    if (primaryKey == null || primaryKey.isBlank()) {
      return List.of();
    }
    return List.of(primaryKey.split(","))
        .stream()
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .toList();
  }

  private String firstPrimaryKey(TableWhitelistOption option) {
    if (option.primaryKey() == null || option.primaryKey().isBlank()) {
      return "id";
    }
    return List.of(option.primaryKey().split(",")).stream()
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .findFirst()
        .orElse("id");
  }

  private List<Map<String, Object>> executeSourceQuery(GitlabSyncConfig config, String sql) {
    return sourceAdapter(config).query(config, sql);
  }

  private GitlabSourceAdapter sourceAdapter(GitlabSyncConfig config) {
    SourceMode sourceMode = config == null || config.getSourceMode() == null ? SourceMode.DOCKER : config.getSourceMode();
    GitlabSourceAdapter adapter = sourceAdapters.get(sourceMode);
    if (adapter == null) {
      throw new BizException("Unsupported GitLab source mode: " + sourceMode);
    }
    return adapter;
  }

  private List<Map<String, Object>> executeJdbcQuery(GitlabSyncConfig config, String sql) {
    try {
      return executeExternalQueryWithRetry("JDBC query", () -> {
        try (Connection connection = openConnection(config);
             Statement statement = connection.createStatement()) {
          statement.setQueryTimeout(resolveExternalQueryTimeoutSeconds());
          try (ResultSet resultSet = statement.executeQuery(sql)) {
            List<Map<String, Object>> rows = new ArrayList<>();
            ResultSetMetaData metaData = resultSet.getMetaData();
            int count = metaData.getColumnCount();
            while (resultSet.next()) {
              Map<String, Object> row = new LinkedHashMap<>();
              for (int i = 1; i <= count; i++) {
                row.put(metaData.getColumnLabel(i), resultSet.getObject(i));
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
      try (GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Data_Fetching")) {
        log.error("Failed to query GitLab database via JDBC", e);
      }
      throw e;
    }
  }

  private List<Map<String, Object>> executeDockerQuery(GitlabSyncConfig config, String sql) {
    try {
      return executeExternalQueryWithRetry("Docker query", () -> {
        try {
          List<String> lines = executeDockerSql(config, "select row_to_json(t)::text from (%s) t".formatted(sql));
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
      try (GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Data_Fetching")) {
        log.error("Failed to query GitLab database via Docker", e);
      }
      throw e;
    }
  }

  private List<String> executeDockerSql(GitlabSyncConfig config, String sql) {
    String containerName = config.getDockerContainerName();
    if (containerName == null || containerName.isBlank()) {
      throw new BizException("Docker mode requires a container name");
    }

    String script = """
        gitlab-psql -d "%s" -At <<'SQL'
        %s;
        SQL
        """.formatted(
        sanitizeShell(normalizeDbName(config)),
        sql);

    try {
      ProcessBuilder builder = new ProcessBuilder(
          properties.getDockerCommand(),
          "exec",
          containerName,
          "bash",
          "-lc",
          script);
      builder.redirectErrorStream(true);
      Process process = builder.start();
      ExecutorService outputReader = Executors.newSingleThreadExecutor();
      List<String> lines;
      try {
        Future<List<String>> outputFuture = outputReader.submit(() -> readProcessOutput(process));
        boolean finished = process.waitFor(resolveExternalQueryTimeoutSeconds(), TimeUnit.SECONDS);
        if (!finished) {
          process.destroyForcibly();
          throw new BizException("Docker GitLab PostgreSQL command timed out after "
              + resolveExternalQueryTimeoutSeconds() + " seconds");
        }
        try {
          lines = outputFuture.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
          throw new BizException("Docker GitLab PostgreSQL command output read timed out");
        }
      } finally {
        outputReader.shutdownNow();
      }
      int exitCode = process.exitValue();
      if (exitCode != 0) {
        throw new BizException("Docker GitLab PostgreSQL command failed: " + String.join(System.lineSeparator(), lines));
      }
      return lines;
    } catch (BizException e) {
      try (GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Data_Fetching")) {
        log.error("Docker GitLab PostgreSQL command failed", e);
      }
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      try (GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Data_Fetching")) {
        log.error("Docker GitLab PostgreSQL command interrupted", e);
      }
      throw new BizException("Docker GitLab PostgreSQL command interrupted");
    } catch (Exception e) {
      try (GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Data_Fetching")) {
        log.error("Docker GitLab PostgreSQL command failed", e);
      }
      throw new BizException("Docker GitLab PostgreSQL command failed: " + e.getMessage());
    }
  }

  private Connection openConnection(GitlabSyncConfig config) throws Exception {
    return DriverManager.getConnection(buildJdbcUrl(config), normalizeDbUser(config), config.getDbPassword());
  }

  String buildJdbcUrl(GitlabSyncConfig config) {
    int timeoutSeconds = resolveExternalQueryTimeoutSeconds();
    return "jdbc:postgresql://%s:%d/%s?connectTimeout=%d&socketTimeout=%d&tcpKeepAlive=true".formatted(
        config.getDbHost(),
        config.getDbPort(),
        normalizeDbName(config),
        timeoutSeconds,
        timeoutSeconds);
  }

  <T> T executeExternalQueryWithRetry(String operation, Supplier<T> supplier) {
    int attempts = Math.max(1, properties.getExternalQueryRetryAttempts());
    RuntimeException lastFailure = null;
    for (int attempt = 1; attempt <= attempts; attempt++) {
      try {
        return supplier.get();
      } catch (RuntimeException e) {
        lastFailure = e;
        if (attempt >= attempts || !isRetryableExternalFailure(e)) {
          throw e;
        }
        long retryDelayMs = computeExternalQueryRetryDelayMs(attempt);
        log.warn(
            "Transient GitLab external query failure, operation={}, attempt={}/{}, retryDelayMs={}, message={}",
            operation,
            attempt,
            attempts,
            retryDelayMs,
            e.getMessage());
        sleepBeforeRetry(retryDelayMs);
      }
    }
    throw lastFailure;
  }

  long computeExternalQueryRetryDelayMs(int attempt) {
    long baseDelayMs = Math.max(0, properties.getExternalQueryRetryDelayMs());
    if (baseDelayMs <= 0) {
      return 0;
    }
    long maxDelayMs = Math.max(baseDelayMs, properties.getExternalQueryRetryMaxDelayMs());
    int exponent = Math.max(0, Math.min(10, attempt - 1));
    long exponentialDelay = baseDelayMs * (1L << exponent);
    long cappedDelay = Math.min(exponentialDelay, maxDelayMs);
    if (cappedDelay >= maxDelayMs) {
      return maxDelayMs;
    }
    long jitterBound = Math.max(1, cappedDelay / 2);
    long jitter = ThreadLocalRandom.current().nextLong(jitterBound + 1);
    return Math.min(maxDelayMs, cappedDelay + jitter);
  }

  boolean isRetryableExternalFailure(RuntimeException e) {
    String message = flattenMessage(e);
    if (message.isBlank()) {
      return false;
    }
    if (message.contains("ERROR:") || message.contains("FATAL:") || message.toLowerCase(Locale.ROOT).contains("syntax error")) {
      return false;
    }
    String lowerMessage = message.toLowerCase(Locale.ROOT);
    return lowerMessage.contains("timeout")
        || lowerMessage.contains("timed out")
        || lowerMessage.contains("connection reset")
        || lowerMessage.contains("connection refused")
        || lowerMessage.contains("could not connect")
        || lowerMessage.contains("connection has been closed")
        || lowerMessage.contains("closed connection")
        || lowerMessage.contains("broken pipe")
        || lowerMessage.contains("i/o error")
        || lowerMessage.contains("io exception")
        || lowerMessage.contains("network")
        || lowerMessage.contains("temporarily unavailable");
  }

  private List<String> readProcessOutput(Process process) throws Exception {
    List<String> lines = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        lines.add(line);
      }
    }
    return lines;
  }

  private void sleepBeforeRetry(long retryDelayMs) {
    if (retryDelayMs <= 0) {
      return;
    }
    try {
      Thread.sleep(retryDelayMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BizException("GitLab external query retry interrupted");
    }
  }

  private String flattenMessage(Throwable throwable) {
    StringBuilder message = new StringBuilder();
    Throwable current = throwable;
    while (current != null) {
      if (current.getMessage() != null) {
        if (!message.isEmpty()) {
          message.append(' ');
        }
        message.append(current.getMessage());
      }
      current = current.getCause();
    }
    return message.toString();
  }

  private int resolveExternalQueryTimeoutSeconds() {
    return Math.max(1, properties.getExternalQueryTimeoutSeconds());
  }

  private String normalizeDbName(GitlabSyncConfig config) {
    return config.getDbName() == null || config.getDbName().isBlank() ? "gitlabhq_production" : config.getDbName().trim();
  }

  private String normalizeDbUser(GitlabSyncConfig config) {
    return config.getDbUsername() == null || config.getDbUsername().isBlank() ? "gitlab" : config.getDbUsername().trim();
  }

  private String sanitizeShell(String text) {
    return text.replace("\"", "\\\"");
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
    if (value instanceof Timestamp timestamp) {
      return timestamp.toLocalDateTime();
    }
    if (value instanceof java.util.Date date) {
      return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }
    if (value instanceof String text) {
      return parseDateTime(text);
    }
    return null;
  }

  private LocalDateTime toGitlabSourceTime(LocalDateTime localTime) {
    return localTime.atZone(ZoneId.systemDefault())
        .withZoneSameInstant(ZoneOffset.UTC)
        .toLocalDateTime();
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
      executeExternalQueryWithRetry("JDBC connection test", () -> {
        try (Connection connection = openConnection(config); Statement statement = connection.createStatement()) {
          statement.setQueryTimeout(resolveExternalQueryTimeoutSeconds());
          statement.execute("select 1");
          return null;
        } catch (Exception e) {
          throw new BizException("GitLab PostgreSQL connection failed: " + e.getMessage());
        }
      });
    }

    @Override
    public List<Map<String, Object>> query(GitlabSyncConfig config, String sql) {
      return executeJdbcQuery(config, sql);
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
        executeDockerSql(config, "select 1");
        return null;
      });
    }

    @Override
    public List<Map<String, Object>> query(GitlabSyncConfig config, String sql) {
      return executeDockerQuery(config, sql);
    }
  }
}
