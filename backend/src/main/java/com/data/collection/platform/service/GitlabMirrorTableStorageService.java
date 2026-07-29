package com.data.collection.platform.service;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.GitlabTableProbe;
import com.data.collection.platform.entity.MirrorPrimaryKeyBatch;
import com.data.collection.platform.entity.MirrorBatchWriteResult;
import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
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

  public MirrorBatchWriteResult upsertBatch(SourceTableSchema mirrorSchema, List<Map<String, Object>> rows, Long taskId) {
    return upsertBatch(mirrorSchema, rows, taskId, false);
  }

  public MirrorBatchWriteResult upsertBatch(
      SourceTableSchema mirrorSchema,
      List<Map<String, Object>> rows,
      Long taskId,
      boolean forceUpdate) {
    if (rows == null || rows.isEmpty()) {
      return new MirrorBatchWriteResult(0, 0, 0);
    }
    String sql = buildUpsertSql(mirrorSchema, forceUpdate);
    int[] results = jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
      @Override
      public void setValues(PreparedStatement ps, int i) throws SQLException {
        ps.setObject(1, taskId);
        ps.setString(2, jsonUtils.toJson(rows.get(i)));
      }

      @Override
      public int getBatchSize() {
        return rows.size();
      }
    });
    int appliedRows = 0;
    int skippedConflicts = 0;
    for (int result : results) {
      if (result > 0) {
        appliedRows++;
      } else {
        skippedConflicts++;
      }
    }
    return new MirrorBatchWriteResult(rows.size(), appliedRows, skippedConflicts);
  }

  /**
   * 以来源查询结果权威替换指定 lookup 范围，并在同一事务内完成缺失行删除与当前行写入。
   *
   * @param mirrorSchema 镜像表结构，必须包含 lookup 列和完整主键
   * @param lookupColumn 定义权威范围的来源列
   * @param lookupValue 定义权威范围的非空值
   * @param rows 来源当前返回的完整范围集合，空集合表示清空该范围
   * @param taskId 当前同步任务编号，用于变更追踪
   * @return 来源行数、实际写入与删除总数及跳过冲突数
   * @throws IllegalArgumentException lookup 契约无效或来源行越出声明范围时抛出
   */
  @Transactional
  public MirrorBatchWriteResult replaceAuthoritativeScope(
      SourceTableSchema mirrorSchema,
      String lookupColumn,
      Object lookupValue,
      List<Map<String, Object>> rows,
      Long taskId) {
    List<Map<String, Object>> sourceRows = rows == null ? List.of() : new ArrayList<>(rows);
    validateAuthoritativeScope(mirrorSchema, lookupColumn, lookupValue, sourceRows);
    List<String> primaryKeys = PrimaryKeySignatureSupport.primaryKeyColumns(mirrorSchema);
    Set<String> sourceSignatures = sourceRows.stream()
        .map(row -> PrimaryKeySignatureSupport.signature(primaryKeys, row))
        .collect(Collectors.toCollection(HashSet::new));
    List<Map<String, Object>> mirrorOnlyRows = listActivePrimaryKeysByLookup(
            mirrorSchema, lookupColumn, lookupValue).stream()
        .filter(row -> !sourceSignatures.contains(PrimaryKeySignatureSupport.signature(primaryKeys, row)))
        .toList();
    int deletedRows = markRowsDeletedByPrimaryKeys(mirrorSchema, mirrorOnlyRows, taskId);
    MirrorBatchWriteResult writeResult = upsertBatch(mirrorSchema, sourceRows, taskId, true);
    return new MirrorBatchWriteResult(
        sourceRows.size(),
        deletedRows + writeResult.appliedRows(),
        writeResult.skippedConflicts());
  }

  public int markRowsDeleted(SourceTableSchema mirrorSchema, String lookupColumn, Object lookupValue, Long taskId) {
    if (mirrorSchema == null || lookupColumn == null || lookupColumn.isBlank() || lookupValue == null) {
      return 0;
    }
    String sql = """
        update %s
           set mirror_task_id = ?,
               mirror_deleted = true,
               mirror_synced_at = current_timestamp,
               mirror_updated_at = current_timestamp
         where %s = ?
           and coalesce(mirror_deleted, false) = false
        """.formatted(
        quoteIdentifier(mirrorSchema.tableName()),
        quoteIdentifier(lookupColumn));
    return jdbcTemplate.update(sql, taskId, lookupValue);
  }

  public int markRowsDeletedByPrimaryKeys(
      SourceTableSchema mirrorSchema,
      List<Map<String, Object>> primaryKeyRows,
      Long taskId) {
    if (mirrorSchema == null || primaryKeyRows == null || primaryKeyRows.isEmpty()) {
      return 0;
    }
    List<String> primaryKeys = PrimaryKeySignatureSupport.primaryKeyColumns(mirrorSchema);
    String sql = """
        update %s
           set mirror_task_id = ?,
               mirror_deleted = true,
               mirror_synced_at = current_timestamp,
               mirror_updated_at = current_timestamp
         where coalesce(mirror_deleted, false) = false
           and (%s)
        """.formatted(
        quoteIdentifier(mirrorSchema.tableName()),
        primaryKeyRows.stream()
            .map(ignored -> primaryKeys.stream()
                .map(primaryKey -> quoteIdentifier(primaryKey) + " = ?::" + columnType(mirrorSchema, primaryKey))
                .collect(Collectors.joining(" and ", "(", ")")))
            .collect(Collectors.joining(" or ")));
    List<Object> args = new ArrayList<>();
    args.add(taskId);
    for (Map<String, Object> row : primaryKeyRows) {
      for (String primaryKey : primaryKeys) {
        args.add(Objects.toString(row.get(primaryKey), ""));
      }
    }
    return jdbcTemplate.update(sql, args.toArray());
  }

  private List<Map<String, Object>> listActivePrimaryKeysByLookup(
      SourceTableSchema mirrorSchema, String lookupColumn, Object lookupValue) {
    List<String> primaryKeys = PrimaryKeySignatureSupport.primaryKeyColumns(mirrorSchema);
    String sql = """
        select %s
          from %s
         where %s = ?::%s
           and coalesce(mirror_deleted, false) = false
        """.formatted(
        primaryKeys.stream()
            .map(this::quoteIdentifier)
            .collect(Collectors.joining(", ")),
        quoteIdentifier(mirrorSchema.tableName()),
        quoteIdentifier(lookupColumn),
        columnType(mirrorSchema, lookupColumn));
    return jdbcTemplate.queryForList(sql, Objects.toString(lookupValue, ""));
  }

  private void validateAuthoritativeScope(
      SourceTableSchema mirrorSchema,
      String lookupColumn,
      Object lookupValue,
      List<Map<String, Object>> rows) {
    if (mirrorSchema == null || lookupColumn == null || lookupColumn.isBlank() || lookupValue == null) {
      throw new IllegalArgumentException("权威范围必须声明有效镜像表、lookup 列和值");
    }
    boolean lookupExists = mirrorSchema.columns().stream()
        .map(SourceTableColumn::columnName)
        .anyMatch(lookupColumn::equals);
    if (!lookupExists) {
      throw new IllegalArgumentException("权威范围 lookup 列不属于镜像表：" + lookupColumn);
    }
    String expectedValue = Objects.toString(lookupValue, "");
    List<String> primaryKeys = PrimaryKeySignatureSupport.primaryKeyColumns(mirrorSchema);
    boolean containsOutOfScopeRow = rows.stream()
        .anyMatch(row -> row == null
            || !expectedValue.equals(Objects.toString(row.get(lookupColumn), ""))
            || primaryKeys.stream().anyMatch(primaryKey -> row.get(primaryKey) == null));
    if (containsOutOfScopeRow) {
      throw new IllegalArgumentException("来源结果包含权威范围之外的行：" + lookupColumn);
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
         where coalesce(mirror_deleted, false) = false
               %s
         order by %s
         limit ?
        """.formatted(
        primaryKeys.stream()
            .map(this::quoteIdentifier)
            .collect(Collectors.joining(", ")),
        quoteIdentifier(mirrorSchema.tableName()),
        cursorPredicate,
        primaryKeys.stream()
            .map(primaryKey -> quoteIdentifier(primaryKey) + " asc")
            .collect(Collectors.joining(", ")));
    args.add(Math.max(1, batchSize));
    List<Map<String, Object>> keys = jdbcTemplate.queryForList(sql, args.toArray());
    String nextCursor = keys.isEmpty()
        ? null
        : PrimaryKeySignatureSupport.encodeCursor(jsonUtils, primaryKeys, keys.get(keys.size() - 1));
    return new MirrorPrimaryKeyBatch(keys, nextCursor);
  }

  public GitlabTableProbe probeMirrorTable(SourceTableSchema mirrorSchema) {
    String primaryKeyColumn = mirrorSchema.primaryKeys().isEmpty() ? "id" : mirrorSchema.primaryKeys().get(0);
    String sql = """
        select count(*) as row_count,
               max(source_updated_at) as max_updated_at,
               min(%s)::text as min_pk,
               max(%s)::text as max_pk
          from %s
         where coalesce(mirror_deleted, false) = false
        """.formatted(
        quoteIdentifier(primaryKeyColumn),
        quoteIdentifier(primaryKeyColumn),
        quoteIdentifier(mirrorSchema.tableName()));
    Map<String, Object> row = jdbcTemplate.queryForMap(sql);
    return new GitlabTableProbe(
        toLong(row.get("row_count")),
        toLocalDateTime(row.get("max_updated_at")),
        Objects.toString(row.get("min_pk"), ""),
        Objects.toString(row.get("max_pk"), ""));
  }

  private String buildUpsertSql(SourceTableSchema schema) {
    return buildUpsertSql(schema, false);
  }

  private String buildUpsertSql(SourceTableSchema schema, boolean forceUpdate) {
    String tableName = quoteIdentifier(schema.tableName());
    List<String> sourceColumns = schema.columns().stream().map(SourceTableColumn::columnName).toList();
    String insertColumns = sourceColumns.stream().map(this::quoteIdentifier).collect(Collectors.joining(", "));
    String selectColumns = sourceColumns.stream().map(column -> "p." + quoteIdentifier(column)).collect(Collectors.joining(", "));
    String conflictColumns = schema.primaryKeys().stream().map(this::quoteIdentifier).collect(Collectors.joining(", "));
    String updateAssignments = sourceColumns.stream()
        .map(column -> quoteIdentifier(column) + " = excluded." + quoteIdentifier(column))
        .collect(Collectors.joining(", "));
    String sourceUpdatedExpression = schema.updatedAtColumn() == null || schema.updatedAtColumn().isBlank()
        ? "null"
        : "p." + quoteIdentifier(schema.updatedAtColumn());
    String conflictGuard = forceUpdate ? "" : buildConflictGuard(schema);
    return """
        insert into %s (%s, mirror_task_id, source_updated_at, mirror_synced_at, mirror_deleted, mirror_updated_at)
        select %s, ?, %s, current_timestamp, false, current_timestamp
        from jsonb_populate_record(null::%s, cast(? as jsonb)) as p
        on conflict (%s) do update
        set %s,
            mirror_task_id = excluded.mirror_task_id,
            source_updated_at = excluded.source_updated_at,
            mirror_synced_at = current_timestamp,
            mirror_deleted = false,
            mirror_updated_at = current_timestamp
        %s
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

  private String buildConflictGuard(SourceTableSchema schema) {
    if (schema.updatedAtColumn() == null || schema.updatedAtColumn().isBlank()) {
      return "";
    }
    String updatedAtColumn = quoteIdentifier(schema.updatedAtColumn());
    return "where excluded.%s is null or %s.%s is null or excluded.%s >= %s.%s".formatted(
        updatedAtColumn,
        quoteIdentifier(schema.tableName()),
        updatedAtColumn,
        updatedAtColumn,
        quoteIdentifier(schema.tableName()),
        updatedAtColumn);
  }

  private String quoteIdentifier(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  private String buildCursorPredicate(
      SourceTableSchema schema,
      List<String> primaryKeys,
      List<Object> args,
      List<String> cursorValues) {
    args.addAll(cursorValues);
    String columns = primaryKeys.stream().map(this::quoteIdentifier).collect(Collectors.joining(", "));
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
