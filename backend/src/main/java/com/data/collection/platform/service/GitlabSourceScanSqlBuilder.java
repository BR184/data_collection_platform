package com.data.collection.platform.service;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.SourceCursorStrategy;
import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.TableWhitelistOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;

class GitlabSourceScanSqlBuilder {
  private static final DateTimeFormatter TIMESTAMP_LITERAL_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

  private final JsonUtils jsonUtils;

  GitlabSourceScanSqlBuilder(JsonUtils jsonUtils) {
    this.jsonUtils = jsonUtils;
  }

  String buildFullTableScanSql(TableWhitelistOption option) {
    return "select * from %s".formatted(quoteQualifiedPublicTable(option.tableName()));
  }

  String buildFullCursorScanSql(
      TableWhitelistOption option,
      SourceTableSchema schema,
      String cursorPk,
      int batchSize) {
    List<String> primaryKeys = primaryKeyColumns(option);
    String cursorPredicate = buildPrimaryKeyCursorPredicate(schema, primaryKeys, cursorPk, " where ");
    return """
        select *
          from %s
         %s
         order by %s
         limit %d
        """.formatted(
        quoteQualifiedPublicTable(option.tableName()),
        cursorPredicate,
        orderByPrimaryKeys(primaryKeys),
        Math.max(1, batchSize)).strip();
  }

  String buildMonotonicPrimaryKeyScanSql(
      TableWhitelistOption option,
      SourceTableSchema schema,
      String cursorPk,
      String upperBoundPk,
      int batchSize) {
    List<String> primaryKeys = primaryKeyColumns(option);
    if (primaryKeys.size() != 1) {
      throw new IllegalArgumentException("单调主键增量只支持单列主键：" + option.tableName());
    }
    if (upperBoundPk == null || upperBoundPk.isBlank()) {
      throw new IllegalArgumentException("单调主键增量必须提供固定扫描上界");
    }
    String primaryKey = primaryKeys.getFirst();
    String upperValue =
        typedPrimaryKeyLiterals(schema, primaryKeys, decodeCursor(primaryKeys, upperBoundPk))
            .getFirst();
    String cursorPredicate =
        buildPrimaryKeyCursorPredicate(schema, primaryKeys, cursorPk, " and ");
    return """
        select *
          from %s
         where %s <= %s%s
         order by %s
         limit %d
        """
        .formatted(
            quoteQualifiedPublicTable(option.tableName()),
            quoteIdentifier(primaryKey),
            upperValue,
            cursorPredicate,
            orderByPrimaryKeys(primaryKeys),
            Math.max(1, batchSize))
        .strip();
  }

  String buildPreciseScanSql(TableWhitelistOption option, Map<String, Object> lookupScope) {
    if (lookupScope == null || lookupScope.isEmpty()) {
      throw new IllegalArgumentException("权威关系来源查询必须指定范围");
    }
    String predicate = lookupScope.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(entry -> quoteIdentifier(entry.getKey()) + " = " + toSqlLiteral(entry.getValue()))
        .collect(java.util.stream.Collectors.joining(" and "));
    return "select * from %s where %s".formatted(
        quoteQualifiedPublicTable(option.tableName()), predicate);
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
        formatTimestampLiteral(since));
  }

  String buildCursorBatchScanSql(
      TableWhitelistOption option,
      SourceTableSchema schema,
      LocalDateTime watermark,
      LocalDateTime upperBound,
      LocalDateTime cursorUpdatedAt,
      String cursorPk,
      int batchSize) {
    if (upperBound == null) {
      throw new IllegalArgumentException("增量分页必须提供固定扫描上界");
    }
    if (option.cursorStrategy() == null || option.cursorStrategy() == SourceCursorStrategy.NONE) {
      throw new IllegalArgumentException("当前源表没有可执行的增量游标策略");
    }
    List<String> primaryKeys = primaryKeyColumns(option);
    String updatedAtColumn = quoteIdentifier(option.updatedAtColumn());
    StringBuilder sql = new StringBuilder("select * from ")
        .append(quoteQualifiedPublicTable(option.tableName()))
        .append(" where ")
        .append(updatedAtColumn)
        .append(" > ")
        .append(timestampLiteral(schema, option.updatedAtColumn(), watermark))
        .append(" and ")
        .append(updatedAtColumn)
        .append(" <= ")
        .append(timestampLiteral(schema, option.updatedAtColumn(), upperBound));
    if (option.cursorStrategy() == SourceCursorStrategy.TIMESTAMP_KEYSET) {
      appendTimestampCursor(sql, schema, option.updatedAtColumn(), primaryKeys, cursorUpdatedAt, cursorPk);
      sql.append(" order by ").append(updatedAtColumn).append(" asc, ");
    } else {
      sql.append(buildPrimaryKeyCursorPredicate(schema, primaryKeys, cursorPk, " and "));
      sql.append(" order by ");
    }
    return sql.append(orderByPrimaryKeys(primaryKeys))
        .append(" limit ")
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

  String buildMaxPrimaryKeyProbeSql(TableWhitelistOption option) {
    List<String> primaryKeys = primaryKeyColumns(option);
    if (primaryKeys.size() != 1) {
      throw new IllegalArgumentException("单调主键增量只支持单列主键：" + option.tableName());
    }
    return "select max(%s)::text as max_pk from %s"
        .formatted(
            quoteIdentifier(primaryKeys.getFirst()),
            quoteQualifiedPublicTable(option.tableName()));
  }

  private String quoteIdentifier(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  private String quoteQualifiedPublicTable(String tableName) {
    return quoteIdentifier("public") + "." + quoteIdentifier(tableName);
  }

  private void appendTimestampCursor(
      StringBuilder sql,
      SourceTableSchema schema,
      String updatedAtColumn,
      List<String> primaryKeys,
      LocalDateTime cursorUpdatedAt,
      String cursorPk) {
    if (cursorPk == null || cursorPk.isBlank()) {
      return;
    }
    if (cursorUpdatedAt == null) {
      throw new IllegalArgumentException("时间游标缺少 cursor_updated_at");
    }
    List<String> cursorValues = decodeCursor(primaryKeys, cursorPk);
    List<String> leftColumns = new java.util.ArrayList<>();
    leftColumns.add(quoteIdentifier(updatedAtColumn));
    leftColumns.addAll(primaryKeys.stream().map(this::quoteIdentifier).toList());
    List<String> rightValues = new java.util.ArrayList<>();
    rightValues.add(timestampLiteral(schema, updatedAtColumn, cursorUpdatedAt));
    rightValues.addAll(typedPrimaryKeyLiterals(schema, primaryKeys, cursorValues));
    sql.append(" and (")
        .append(String.join(", ", leftColumns))
        .append(") > (")
        .append(String.join(", ", rightValues))
        .append(")");
  }

  private String buildPrimaryKeyCursorPredicate(
      SourceTableSchema schema,
      List<String> primaryKeys,
      String cursorPk,
      String prefix) {
    if (cursorPk == null || cursorPk.isBlank()) {
      return "";
    }
    List<String> cursorValues = decodeCursor(primaryKeys, cursorPk);
    return prefix
        + "("
        + primaryKeys.stream().map(this::quoteIdentifier).collect(java.util.stream.Collectors.joining(", "))
        + ") > ("
        + String.join(", ", typedPrimaryKeyLiterals(schema, primaryKeys, cursorValues))
        + ")";
  }

  private List<String> decodeCursor(List<String> primaryKeys, String cursorPk) {
    List<String> values = jsonUtils.toStringList(cursorPk);
    if (values.size() != primaryKeys.size()) {
      throw new IllegalArgumentException("主键游标列数与源表主键不一致");
    }
    return values;
  }

  private List<String> typedPrimaryKeyLiterals(
      SourceTableSchema schema, List<String> primaryKeys, List<String> cursorValues) {
    List<String> result = new java.util.ArrayList<>(primaryKeys.size());
    for (int index = 0; index < primaryKeys.size(); index++) {
      result.add(toSqlLiteral(cursorValues.get(index)) + "::" + columnType(schema, primaryKeys.get(index)));
    }
    return result;
  }

  private String timestampLiteral(
      SourceTableSchema schema, String columnName, LocalDateTime value) {
    String formatted = formatTimestampLiteral(value);
    String type = columnType(schema, columnName).toLowerCase(java.util.Locale.ROOT);
    return type.contains("with time zone")
        ? "timestamptz '" + formatted + "+00'"
        : "timestamp '" + formatted + "'";
  }

  private String columnType(SourceTableSchema schema, String columnName) {
    return schema.columns().stream()
        .filter(column -> Objects.equals(column.columnName(), columnName))
        .map(SourceTableColumn::formattedType)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("源表字段元数据缺失：" + columnName));
  }

  private String orderByPrimaryKeys(List<String> primaryKeys) {
    return primaryKeys.stream()
        .map(primaryKey -> quoteIdentifier(primaryKey) + " asc")
        .collect(java.util.stream.Collectors.joining(", "));
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

  private List<String> primaryKeyColumns(TableWhitelistOption option) {
    List<String> columns = splitPrimaryKeys(option.primaryKey());
    return columns.isEmpty() ? List.of("id") : columns;
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

  private String formatTimestampLiteral(LocalDateTime value) {
    return value.format(TIMESTAMP_LITERAL_FORMATTER);
  }
}
