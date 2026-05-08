package com.data.collection.platform.service;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.MirrorBatchWriteResult;
import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
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
}
