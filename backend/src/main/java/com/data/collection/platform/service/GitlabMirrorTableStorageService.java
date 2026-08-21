package com.data.collection.platform.service;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.common.SqlIdentifierSupport;
import com.data.collection.platform.entity.GitlabTableProbe;
import com.data.collection.platform.entity.MirrorPrimaryKeyBatch;
import com.data.collection.platform.entity.MirrorMutationResult;
import com.data.collection.platform.entity.MirrorRowChange;
import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GitlabMirrorTableStorageService {

  private final JdbcTemplate jdbcTemplate;
  private final JsonUtils jsonUtils;

  public GitlabMirrorTableStorageService(JdbcTemplate jdbcTemplate, JsonUtils jsonUtils) {
    this.jdbcTemplate = jdbcTemplate;
    this.jsonUtils = jsonUtils;
  }

  public MirrorMutationResult applyBatch(
      SourceTableSchema mirrorSchema, List<Map<String, Object>> rows, Long taskId) {
    return applyBatch(mirrorSchema, rows, taskId, false);
  }

  /** 批量写入来源行，只把业务列真实变化或 tombstone 恢复报告为变化。 */
  public MirrorMutationResult applyBatch(
      SourceTableSchema mirrorSchema,
      List<Map<String, Object>> rows,
      Long taskId,
      boolean forceUpdate) {
    if (rows == null || rows.isEmpty()) {
      return MirrorMutationResult.empty();
    }
    validateSourceRows(mirrorSchema, rows);
    Map<String, Map<String, Object>> beforeByKey =
        indexByPrimaryKey(mirrorSchema, listRowsByPrimaryKeys(mirrorSchema, rows));
    String sql = buildUpsertSql(mirrorSchema, forceUpdate);
    List<Map<String, Object>> changedRows =
        jdbcTemplate.query(
            sql,
            (resultSet, rowNum) -> jsonUtils.toMap(resultSet.getString("row_data")),
            taskId,
            jsonUtils.toJson(rows));
    List<MirrorRowChange> changes =
        changedRows.stream()
            .map(
                after ->
                    new MirrorRowChange(
                        beforeByKey.getOrDefault(primaryKeySignature(mirrorSchema, after), Map.of()),
                        sourceColumnsOnly(mirrorSchema, after)))
            .toList();
    return new MirrorMutationResult(rows.size(), changes, rows.size() - changes.size());
  }

  /**
   * 以来源查询结果权威替换指定 lookup 范围，并在同一事务内完成缺失行删除与当前行写入。
   *
   * @param mirrorSchema 镜像表结构，必须包含范围列和完整主键
   * @param lookupScope 定义权威范围的一个或多个来源列和值
   * @param rows 来源当前返回的完整范围集合，空集合表示清空该范围
   * @param taskId 当前同步任务编号，用于变更追踪
   * @return 来源行数、实际写入与删除总数及跳过冲突数
   * @throws IllegalArgumentException lookup 契约无效或来源行越出声明范围时抛出
   */
  @Transactional
  public MirrorMutationResult replaceAuthoritativeScope(
      SourceTableSchema mirrorSchema,
      Map<String, Object> lookupScope,
      List<Map<String, Object>> rows,
      Long taskId) {
    List<Map<String, Object>> sourceRows = rows == null ? List.of() : new ArrayList<>(rows);
    Map<String, Object> normalizedScope = normalizeAuthoritativeScope(lookupScope);
    validateAuthoritativeScope(mirrorSchema, normalizedScope, sourceRows);
    List<String> primaryKeys = PrimaryKeySignatureSupport.primaryKeyColumns(mirrorSchema);
    Set<String> sourceSignatures = sourceRows.stream()
        .map(row -> PrimaryKeySignatureSupport.signature(primaryKeys, row))
        .collect(Collectors.toCollection(HashSet::new));
    List<Map<String, Object>> mirrorOnlyRows = listActiveRowsByScope(mirrorSchema, normalizedScope).stream()
        .filter(row -> !sourceSignatures.contains(PrimaryKeySignatureSupport.signature(primaryKeys, row)))
        .toList();
    MirrorMutationResult deleted = markRowsDeletedByPrimaryKeys(mirrorSchema, mirrorOnlyRows, taskId);
    MirrorMutationResult written = applyBatch(mirrorSchema, sourceRows, taskId, true);
    List<MirrorRowChange> changes = new ArrayList<>(deleted.changes());
    changes.addAll(written.changes());
    return new MirrorMutationResult(sourceRows.size(), changes, written.unchangedRows());
  }

  public MirrorMutationResult markRowsDeletedByPrimaryKeys(
      SourceTableSchema mirrorSchema,
      List<Map<String, Object>> primaryKeyRows,
      Long taskId) {
    if (mirrorSchema == null || primaryKeyRows == null || primaryKeyRows.isEmpty()) {
      return MirrorMutationResult.empty();
    }
    List<String> primaryKeys = PrimaryKeySignatureSupport.primaryKeyColumns(mirrorSchema);
    String sql = """
        update %s target
           set mirror_task_id = ?,
               mirror_deleted = true,
               mirror_synced_at = current_timestamp,
               mirror_updated_at = current_timestamp
          from %s
         where target.mirror_deleted = false
           and %s
        returning to_jsonb(target.*) as row_data
        """.formatted(
        SqlIdentifierSupport.quoteIdentifier(mirrorSchema.tableName()),
        buildTypedValuesRelation(mirrorSchema, primaryKeys, primaryKeyRows.size()),
        primaryKeys.stream()
            .map(
                primaryKey ->
                    "target." + SqlIdentifierSupport.quoteIdentifier(primaryKey)
                        + " = source_keys." + SqlIdentifierSupport.quoteIdentifier(primaryKey))
            .collect(Collectors.joining(" and ")));
    List<Object> args = new ArrayList<>();
    args.add(taskId);
    addPrimaryKeyArguments(primaryKeys, primaryKeyRows, args);
    List<MirrorRowChange> changes =
        jdbcTemplate.query(
            sql,
            (resultSet, rowNum) ->
                new MirrorRowChange(
                    sourceColumnsOnly(
                        mirrorSchema, jsonUtils.toMap(resultSet.getString("row_data"))),
                    Map.of()),
            args.toArray());
    return new MirrorMutationResult(
        primaryKeyRows.size(), changes, primaryKeyRows.size() - changes.size());
  }

  private List<Map<String, Object>> listActiveRowsByScope(
      SourceTableSchema mirrorSchema, Map<String, Object> lookupScope) {
    String predicate = lookupScope.keySet().stream()
        .map(column -> SqlIdentifierSupport.quoteIdentifier(column) + " = ?::" + columnType(mirrorSchema, column))
        .collect(Collectors.joining(" and "));
    String sql = """
        select %s
          from %s
         where %s
           and mirror_deleted = false
        """.formatted(
        mirrorSchema.columns().stream()
            .map(SourceTableColumn::columnName)
            .map(SqlIdentifierSupport::quoteIdentifier)
            .collect(Collectors.joining(", ")),
        SqlIdentifierSupport.quoteIdentifier(mirrorSchema.tableName()),
        predicate);
    Object[] values = lookupScope.values().stream()
        .map(value -> Objects.toString(value, ""))
        .toArray();
    return jdbcTemplate.queryForList(sql, values);
  }

  private Map<String, Object> normalizeAuthoritativeScope(Map<String, Object> lookupScope) {
    if (lookupScope == null || lookupScope.isEmpty()) {
      throw new IllegalArgumentException("权威范围必须声明至少一个列和值");
    }
    Map<String, Object> normalizedScope = new java.util.TreeMap<>();
    lookupScope.forEach((column, value) -> {
      if (column == null || column.isBlank() || value == null || String.valueOf(value).isBlank()) {
        throw new IllegalArgumentException("权威范围包含无效列或值");
      }
      normalizedScope.put(column, value);
    });
    return java.util.Collections.unmodifiableMap(normalizedScope);
  }

  private void validateAuthoritativeScope(
      SourceTableSchema mirrorSchema,
      Map<String, Object> lookupScope,
      List<Map<String, Object>> rows) {
    if (mirrorSchema == null) {
      throw new IllegalArgumentException("权威范围必须声明有效镜像表");
    }
    Set<String> schemaColumns = mirrorSchema.columns().stream()
        .map(SourceTableColumn::columnName)
        .collect(Collectors.toSet());
    for (String lookupColumn : lookupScope.keySet()) {
      if (!schemaColumns.contains(lookupColumn)) {
        throw new IllegalArgumentException("权威范围 lookup 列不属于镜像表：" + lookupColumn);
      }
    }
    List<String> primaryKeys = PrimaryKeySignatureSupport.primaryKeyColumns(mirrorSchema);
    boolean containsOutOfScopeRow = rows.stream()
        .anyMatch(row -> row == null
            || lookupScope.entrySet().stream().anyMatch(entry ->
                !Objects.toString(entry.getValue(), "")
                    .equals(Objects.toString(row.get(entry.getKey()), "")))
            || primaryKeys.stream().anyMatch(primaryKey -> row.get(primaryKey) == null));
    if (containsOutOfScopeRow) {
      throw new IllegalArgumentException("来源结果包含权威范围之外的行");
    }
  }

  public MirrorPrimaryKeyBatch listActivePrimaryKeys(
      SourceTableSchema mirrorSchema,
      String cursor,
      int batchSize) {
    List<String> primaryKeys = PrimaryKeySignatureSupport.primaryKeyColumns(mirrorSchema);
    List<String> cursorValues = PrimaryKeySignatureSupport.decodeCursor(jsonUtils, cursor);
    List<Object> args = new ArrayList<>();
    String cursorPredicate = "";
    if (cursorValues.size() == primaryKeys.size()) {
      cursorPredicate = " and " + buildCursorPredicate(mirrorSchema, primaryKeys, args, cursorValues);
    }
    String sql = """
        select %s
          from %s
         where mirror_deleted = false
               %s
         order by %s
         limit ?
        """.formatted(
        primaryKeys.stream()
            .map(SqlIdentifierSupport::quoteIdentifier)
            .collect(Collectors.joining(", ")),
        SqlIdentifierSupport.quoteIdentifier(mirrorSchema.tableName()),
        cursorPredicate,
        primaryKeys.stream()
            .map(primaryKey -> SqlIdentifierSupport.quoteIdentifier(primaryKey) + " asc")
            .collect(Collectors.joining(", ")));
    args.add(Math.max(1, batchSize));
    List<Map<String, Object>> keys = jdbcTemplate.queryForList(sql, args.toArray());
    String nextCursor = keys.isEmpty()
        ? null
        : PrimaryKeySignatureSupport.encodeCursor(jsonUtils, primaryKeys, keys.get(keys.size() - 1));
    return new MirrorPrimaryKeyBatch(keys, nextCursor);
  }

  /** 返回镜像 active 行的单列最大主键游标；空表返回 {@code null}。 */
  public String findMaxActivePrimaryKeyCursor(SourceTableSchema mirrorSchema) {
    List<String> primaryKeys = PrimaryKeySignatureSupport.primaryKeyColumns(mirrorSchema);
    if (primaryKeys.size() != 1) {
      throw new IllegalArgumentException("单调主键镜像基线只支持单列主键");
    }
    String primaryKey = primaryKeys.getFirst();
    String sql =
        """
        select %s
          from %s
         where mirror_deleted = false
         order by %s desc
         limit 1
        """
            .formatted(
                SqlIdentifierSupport.quoteIdentifier(primaryKey),
                SqlIdentifierSupport.quoteIdentifier(mirrorSchema.tableName()),
                SqlIdentifierSupport.quoteIdentifier(primaryKey))
            .strip();
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
    if (rows.isEmpty()) {
      return null;
    }
    return PrimaryKeySignatureSupport.encodeCursor(jsonUtils, primaryKeys, rows.getFirst());
  }

  public GitlabTableProbe probeMirrorTable(SourceTableSchema mirrorSchema) {
    String primaryKeyColumn = mirrorSchema.primaryKeys().isEmpty() ? "id" : mirrorSchema.primaryKeys().get(0);
    String sql = """
        select count(*) as row_count,
               max(source_updated_at) as max_updated_at,
               min(%s)::text as min_pk,
               max(%s)::text as max_pk
          from %s
         where mirror_deleted = false
        """.formatted(
        SqlIdentifierSupport.quoteIdentifier(primaryKeyColumn),
        SqlIdentifierSupport.quoteIdentifier(primaryKeyColumn),
        SqlIdentifierSupport.quoteIdentifier(mirrorSchema.tableName()));
    Map<String, Object> row = jdbcTemplate.queryForMap(sql);
    return new GitlabTableProbe(
        toLong(row.get("row_count")),
        toLocalDateTime(row.get("max_updated_at")),
        Objects.toString(row.get("min_pk"), ""),
        Objects.toString(row.get("max_pk"), ""));
  }

  private String buildUpsertSql(SourceTableSchema schema, boolean forceUpdate) {
    String tableName = SqlIdentifierSupport.quoteIdentifier(schema.tableName());
    List<String> sourceColumns = schema.columns().stream().map(SourceTableColumn::columnName).toList();
    String insertColumns = sourceColumns.stream().map(SqlIdentifierSupport::quoteIdentifier).collect(Collectors.joining(", "));
    String selectColumns = sourceColumns.stream()
        .map(column -> "p." + SqlIdentifierSupport.quoteIdentifier(column))
        .collect(Collectors.joining(", "));
    String conflictColumns = schema.primaryKeys().stream()
        .map(SqlIdentifierSupport::quoteIdentifier)
        .collect(Collectors.joining(", "));
    String updateAssignments = sourceColumns.stream()
        .map(column -> SqlIdentifierSupport.quoteIdentifier(column) + " = excluded."
            + SqlIdentifierSupport.quoteIdentifier(column))
        .collect(Collectors.joining(", "));
    String sourceUpdatedExpression = schema.updatedAtColumn() == null || schema.updatedAtColumn().isBlank()
        ? "null"
        : "p." + SqlIdentifierSupport.quoteIdentifier(schema.updatedAtColumn());
    String conflictGuard = buildConflictGuard(schema, sourceColumns, forceUpdate);
    return """
        insert into %s as target
                    (%s, mirror_task_id, source_updated_at, mirror_synced_at, mirror_deleted, mirror_updated_at)
        select %s, ?, %s, current_timestamp, false, current_timestamp
        from jsonb_populate_recordset(null::%s, cast(? as jsonb)) as p
        on conflict (%s) do update
        set %s,
            mirror_task_id = excluded.mirror_task_id,
            source_updated_at = excluded.source_updated_at,
            mirror_synced_at = current_timestamp,
            mirror_deleted = false,
            mirror_updated_at = current_timestamp
        %s
        returning to_jsonb(target.*) as row_data
        """.formatted(
        tableName,
        insertColumns,
        selectColumns,
        sourceUpdatedExpression,
        tableName,
        conflictColumns,
        updateAssignments,
        conflictGuard);
  }

  private String buildConflictGuard(
      SourceTableSchema schema, List<String> sourceColumns, boolean forceUpdate) {
    String recencyGuard = "true";
    if (!forceUpdate && schema.updatedAtColumn() != null && !schema.updatedAtColumn().isBlank()) {
      String updatedAtColumn = SqlIdentifierSupport.quoteIdentifier(schema.updatedAtColumn());
      recencyGuard =
          "(excluded.%1$s is null or target.%1$s is null or excluded.%1$s >= target.%1$s)"
              .formatted(updatedAtColumn);
    }
    String currentValues =
        sourceColumns.stream()
            .map(column -> "target." + SqlIdentifierSupport.quoteIdentifier(column))
            .collect(Collectors.joining(", "));
    String incomingValues =
        sourceColumns.stream()
            .map(column -> "excluded." + SqlIdentifierSupport.quoteIdentifier(column))
            .collect(Collectors.joining(", "));
    return "where (target.mirror_deleted = true or %s) "
        .formatted(recencyGuard)
        + "and (target.mirror_deleted = true or row(%s) is distinct from row(%s))"
            .formatted(currentValues, incomingValues);
  }

  private void validateSourceRows(
      SourceTableSchema mirrorSchema, List<Map<String, Object>> sourceRows) {
    List<String> primaryKeys = PrimaryKeySignatureSupport.primaryKeyColumns(mirrorSchema);
    Set<String> signatures = new HashSet<>();
    for (Map<String, Object> row : sourceRows) {
      if (row == null || primaryKeys.stream().anyMatch(primaryKey -> row.get(primaryKey) == null)) {
        throw new IllegalArgumentException("来源批次包含缺少主键的行：" + mirrorSchema.tableName());
      }
      String signature = PrimaryKeySignatureSupport.signature(primaryKeys, row);
      if (!signatures.add(signature)) {
        throw new IllegalArgumentException("来源批次包含重复主键：" + mirrorSchema.tableName());
      }
    }
  }

  private List<Map<String, Object>> listRowsByPrimaryKeys(
      SourceTableSchema mirrorSchema, List<Map<String, Object>> primaryKeyRows) {
    if (primaryKeyRows.isEmpty()) {
      return List.of();
    }
    List<String> primaryKeys = PrimaryKeySignatureSupport.primaryKeyColumns(mirrorSchema);
    String sql = """
        select %s
          from %s target
          join %s
            on %s
         where target.mirror_deleted = false
        """.formatted(
        mirrorSchema.columns().stream()
            .map(SourceTableColumn::columnName)
            .map(column -> "target." + SqlIdentifierSupport.quoteIdentifier(column))
            .collect(Collectors.joining(", ")),
        SqlIdentifierSupport.quoteIdentifier(mirrorSchema.tableName()),
        buildTypedValuesRelation(mirrorSchema, primaryKeys, primaryKeyRows.size()),
        primaryKeys.stream()
            .map(
                primaryKey ->
                    "target." + SqlIdentifierSupport.quoteIdentifier(primaryKey)
                        + " = source_keys." + SqlIdentifierSupport.quoteIdentifier(primaryKey))
            .collect(Collectors.joining(" and ")));
    List<Object> args = new ArrayList<>();
    addPrimaryKeyArguments(primaryKeys, primaryKeyRows, args);
    return jdbcTemplate.queryForList(sql, args.toArray());
  }

  private String buildTypedValuesRelation(
      SourceTableSchema schema, List<String> primaryKeys, int rowCount) {
    String rowTemplate =
        primaryKeys.stream()
            .map(primaryKey -> "?::" + columnType(schema, primaryKey))
            .collect(Collectors.joining(", ", "(", ")"));
    String values =
        java.util.Collections.nCopies(rowCount, rowTemplate).stream()
            .collect(Collectors.joining(", "));
    String columns =
        primaryKeys.stream().map(SqlIdentifierSupport::quoteIdentifier).collect(Collectors.joining(", "));
    return "(values " + values + ") as source_keys(" + columns + ")";
  }

  private void addPrimaryKeyArguments(
      List<String> primaryKeys,
      List<Map<String, Object>> primaryKeyRows,
      List<Object> arguments) {
    for (Map<String, Object> row : primaryKeyRows) {
      for (String primaryKey : primaryKeys) {
        arguments.add(row.get(primaryKey));
      }
    }
  }

  private Map<String, Map<String, Object>> indexByPrimaryKey(
      SourceTableSchema mirrorSchema, List<Map<String, Object>> rows) {
    Map<String, Map<String, Object>> indexed = new java.util.LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      indexed.put(primaryKeySignature(mirrorSchema, row), sourceColumnsOnly(mirrorSchema, row));
    }
    return Map.copyOf(indexed);
  }

  private String primaryKeySignature(
      SourceTableSchema mirrorSchema, Map<String, Object> row) {
    return PrimaryKeySignatureSupport.signature(
        PrimaryKeySignatureSupport.primaryKeyColumns(mirrorSchema), row);
  }

  private Map<String, Object> sourceColumnsOnly(
      SourceTableSchema mirrorSchema, Map<String, Object> row) {
    Map<String, Object> sourceRow = new java.util.LinkedHashMap<>();
    for (SourceTableColumn column : mirrorSchema.columns()) {
      sourceRow.put(column.columnName(), row.get(column.columnName()));
    }
    return java.util.Collections.unmodifiableMap(sourceRow);
  }

  private String buildCursorPredicate(
      SourceTableSchema schema,
      List<String> primaryKeys,
      List<Object> args,
      List<String> cursorValues) {
    args.addAll(cursorValues);
    String columns = primaryKeys.stream().map(SqlIdentifierSupport::quoteIdentifier).collect(Collectors.joining(", "));
    String values = primaryKeys.stream()
        .map(primaryKey -> "?::" + columnType(schema, primaryKey))
        .collect(Collectors.joining(", "));
    return "(" + columns + ") > (" + values + ")";
  }

  private String columnType(SourceTableSchema schema, String columnName) {
    String type = schema.columns().stream()
        .filter(column -> Objects.equals(column.columnName(), columnName))
        .map(SourceTableColumn::formattedType)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("镜像表字段元数据缺失：" + columnName));
    if (!type.matches("[A-Za-z0-9_ \\[\\](),.\"]+")) {
      throw new IllegalArgumentException("镜像表字段类型不合法：" + columnName);
    }
    return type;
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
    if (value instanceof LocalDateTime localDateTime) {
      return localDateTime;
    }
    if (value instanceof Timestamp timestamp) {
      return timestamp.toLocalDateTime();
    }
    if (value instanceof java.util.Date date) {
      return LocalDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC);
    }
    return null;
  }
}
