package com.data.collection.platform.service;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.GitlabTableProbe;
import com.data.collection.platform.entity.GitlabTableShardProbe;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class GitlabMirrorTableStorageService {

  private final JdbcTemplate jdbcTemplate;
  private final JsonUtils jsonUtils;

  public GitlabMirrorTableStorageService(JdbcTemplate jdbcTemplate, JsonUtils jsonUtils) {
    this.jdbcTemplate = jdbcTemplate;
    this.jsonUtils = jsonUtils;
  }

  public MirrorBatchWriteResult upsertBatch(SourceTableSchema mirrorSchema, List<Map<String, Object>> rows, Long taskId) {
    if (rows == null || rows.isEmpty()) {
      return new MirrorBatchWriteResult(0, 0, 0);
    }
    String sql = buildUpsertSql(mirrorSchema);
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
                .map(primaryKey -> quoteIdentifier(primaryKey) + "::text = ?")
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

  public MirrorPrimaryKeyBatch listActivePrimaryKeys(
      SourceTableSchema mirrorSchema,
      String cursor,
      int batchSize) {
    List<String> primaryKeys = PrimaryKeySignatureSupport.primaryKeyColumns(mirrorSchema);
    List<String> cursorValues = PrimaryKeySignatureSupport.decodeCursor(jsonUtils, cursor);
    List<Object> args = new ArrayList<>();
    String cursorPredicate = "";
    if (cursorValues.size() == primaryKeys.size()) {
      cursorPredicate = " and " + buildCursorPredicate(primaryKeys, args, cursorValues);
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
            .map(primaryKey -> quoteIdentifier(primaryKey) + "::text as " + quoteIdentifier(primaryKey))
            .collect(Collectors.joining(", ")),
        quoteIdentifier(mirrorSchema.tableName()),
        cursorPredicate,
        primaryKeys.stream()
            .map(primaryKey -> quoteIdentifier(primaryKey) + "::text asc")
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

  public List<GitlabTableShardProbe> probeMirrorTableShards(SourceTableSchema mirrorSchema, int shardKeyLength) {
    List<String> primaryKeys = PrimaryKeySignatureSupport.primaryKeyColumns(mirrorSchema);
    String pkExpression = primaryKeySignatureExpression(primaryKeys);
    String rowExpression = rowSignatureExpression(mirrorSchema);
    String maxUpdatedAtExpression = mirrorSchema.updatedAtColumn() == null || mirrorSchema.updatedAtColumn().isBlank()
        ? "null::timestamp"
        : "max(" + quoteIdentifier(mirrorSchema.updatedAtColumn()) + ")";
    int safeShardKeyLength = Math.max(1, Math.min(8, shardKeyLength));
    String sql = """
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
             where coalesce(mirror_deleted, false) = false
          ) shard_rows
         group by shard_key
         order by shard_key
        """.formatted(
        maxUpdatedAtExpression,
        pkExpression,
        pkExpression,
        safeShardKeyLength,
        rowExpression,
        mirrorSchema.updatedAtColumn() == null || mirrorSchema.updatedAtColumn().isBlank()
            ? "null::timestamp as " + quoteIdentifier("__updated_at")
            : quoteIdentifier(mirrorSchema.updatedAtColumn()),
        quoteIdentifier(mirrorSchema.tableName())).strip();
    return jdbcTemplate.queryForList(sql).stream()
        .map(this::toShardProbe)
        .toList();
  }

  private String buildUpsertSql(SourceTableSchema schema) {
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
    String conflictGuard = buildConflictGuard(schema);
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

  private String primaryKeySignatureExpression(List<String> primaryKeys) {
    return primaryKeys.stream()
        .map(primaryKey -> quoteIdentifier(primaryKey) + "::text")
        .collect(Collectors.joining(", ", "concat_ws(chr(31), ", ")"));
  }

  private String rowSignatureExpression(SourceTableSchema schema) {
    List<String> columns = schema.columns().stream()
        .map(SourceTableColumn::columnName)
        .toList();
    if (columns.isEmpty()) {
      return "''";
    }
    return columns.stream()
        .map(column -> "to_jsonb(" + quoteIdentifier(column) + ")")
        .collect(Collectors.joining(", ", "jsonb_build_array(", ")::text"));
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

  private String buildCursorPredicate(List<String> primaryKeys, List<Object> args, List<String> cursorValues) {
    List<String> disjunctions = new ArrayList<>();
    for (int i = 0; i < primaryKeys.size(); i++) {
      StringBuilder predicate = new StringBuilder("(");
      for (int j = 0; j < i; j++) {
        predicate.append(quoteIdentifier(primaryKeys.get(j))).append("::text = ? and ");
        args.add(cursorValues.get(j));
      }
      predicate.append(quoteIdentifier(primaryKeys.get(i))).append("::text > ?)");
      args.add(cursorValues.get(i));
      disjunctions.add(predicate.toString());
    }
    return "(" + String.join(" or ", disjunctions) + ")";
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
      return timestamp.toInstant().atOffset(ZoneOffset.UTC).toLocalDateTime();
    }
    if (value instanceof java.util.Date date) {
      return LocalDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC);
    }
    return null;
  }
}
