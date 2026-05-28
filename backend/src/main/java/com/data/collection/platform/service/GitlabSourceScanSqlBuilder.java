package com.data.collection.platform.service;

import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.TableWhitelistOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;

class GitlabSourceScanSqlBuilder {
  String buildFullTableScanSql(TableWhitelistOption option) {
    return "select * from %s".formatted(quoteQualifiedPublicTable(option.tableName()));
  }

  String buildFullCursorScanSql(
      TableWhitelistOption option,
      SourceTableSchema schema,
      String cursorPk,
      int batchSize) {
    String pkExpression = primaryKeySignatureExpression(splitPrimaryKeys(option.primaryKey()), "source_rows");
    String selectColumns = schema.columns().stream()
        .map(column -> "cursor_rows." + quoteIdentifier(column.columnName()))
        .collect(java.util.stream.Collectors.joining(", "));
    String cursorPredicate = cursorPk == null || cursorPk.isBlank()
        ? ""
        : " where cursor_rows.pk_signature > " + toSqlLiteral(cursorPk);
    return """
        select %s
          from (
            select source_rows.*,
                   %s as pk_signature
              from %s source_rows
          ) cursor_rows
         %s
         order by cursor_rows.pk_signature asc
         limit %d
        """.formatted(
        selectColumns,
        pkExpression,
        quoteQualifiedPublicTable(option.tableName()),
        cursorPredicate,
        Math.max(1, batchSize)).strip();
  }

  String buildPreciseScanSql(TableWhitelistOption option, String lookupColumn, Object lookupValue) {
    return "select * from %s where %s = %s".formatted(
        quoteQualifiedPublicTable(option.tableName()),
        quoteIdentifier(lookupColumn),
        toSqlLiteral(lookupValue));
  }

  String buildPreviewTablePageSql(
      TableWhitelistOption option,
      SourceTableSchema schema,
      String keyword,
      String sortField,
      String sortOrder,
      int page,
      int size) {
    int safePage = Math.max(1, page);
    int safeSize = Math.max(1, Math.min(100, size));
    String safeSortField = schema.columns().stream()
        .map(SourceTableColumn::columnName)
        .filter(column -> Objects.equals(column, sortField))
        .findFirst()
        .orElse(firstPrimaryKey(option));
    String safeSortOrder = Objects.equals("asc", sortOrder) ? "asc" : "desc";
    List<String> searchableFields = schema.columns().stream()
        .map(SourceTableColumn::columnName)
        .filter(this::isPreviewSearchableField)
        .limit(6)
        .toList();
    String whereClause = "";
    if (keyword != null && !keyword.isBlank() && !searchableFields.isEmpty()) {
      String likeValue = "'%" + keyword.trim().replace("'", "''") + "%'";
      whereClause = searchableFields.stream()
          .map(field -> "cast(" + quoteIdentifier(field) + " as text) ilike " + likeValue)
          .collect(java.util.stream.Collectors.joining(" or ", " where (", ")"));
    }
    return """
        select *
          from %s
         %s
         order by %s %s
         limit %d offset %d
        """.formatted(
        quoteQualifiedPublicTable(option.tableName()),
        whereClause,
        quoteIdentifier(safeSortField),
        safeSortOrder,
        safeSize,
        (safePage - 1) * safeSize).strip();
  }

  String buildTimeWindowScanSql(TableWhitelistOption option, LocalDateTime since) {
    return "select * from %s where %s >= timestamp '%s'".formatted(
        quoteQualifiedPublicTable(option.tableName()),
        quoteIdentifier(option.updatedAtColumn()),
        since.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
  }

  String buildCursorBatchScanSql(
      TableWhitelistOption option,
      LocalDateTime watermark,
      LocalDateTime cursorUpdatedAt,
      String cursorPk,
      int batchSize) {
    String updatedAtColumn = quoteIdentifier(option.updatedAtColumn());
    String primaryKeyColumn = quoteIdentifier(firstPrimaryKey(option));
    StringBuilder sql = new StringBuilder("select * from ")
        .append(quoteQualifiedPublicTable(option.tableName()))
        .append(" where ")
        .append(updatedAtColumn)
        .append(" >= timestamp '")
        .append(watermark.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
        .append("'");
    if (cursorUpdatedAt != null && cursorPk != null && !cursorPk.isBlank()) {
      sql.append(" and (")
          .append(updatedAtColumn)
          .append(" > timestamp '")
          .append(cursorUpdatedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
          .append("' or (")
          .append(updatedAtColumn)
          .append(" = timestamp '")
          .append(cursorUpdatedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
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

  String buildMaxUpdatedAtProbeSql(TableWhitelistOption option) {
    return """
        select max(%s) as max_updated_at
          from %s
        """.formatted(
        quoteIdentifier(option.updatedAtColumn()),
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

  private boolean isPreviewSearchableField(String columnName) {
    return columnName != null
        && !List.of("metadata", "payload", "description_html").contains(columnName);
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
}
