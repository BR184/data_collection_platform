package com.data.collection.platform.service;

import com.data.collection.platform.common.SqlIdentifierSupport;
import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
import java.util.Locale;

/** GitLab typed VALUES 与 Docker COPY 查询共享的类型和转义规则。 */
final class GitlabTypedValuesSqlSupport {
  private GitlabTypedValuesSqlSupport() {}

  static String sqlType(SourceTableSchema schema, String columnName) {
    String sourceType =
        schema.columns().stream()
            .filter(column -> column.columnName().equals(columnName))
            .map(SourceTableColumn::formattedType)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("查询列不属于来源结构：" + columnName));
    String normalized = sourceType == null ? "" : sourceType.trim().toLowerCase(Locale.ROOT);
    if (normalized.equals("bigint") || normalized.equals("int8")) {
      return "bigint";
    }
    if (normalized.equals("integer") || normalized.equals("int") || normalized.equals("int4")) {
      return "integer";
    }
    if (normalized.equals("smallint") || normalized.equals("int2")) {
      return "smallint";
    }
    if (normalized.equals("uuid")) {
      return "uuid";
    }
    if (normalized.equals("numeric") || normalized.equals("decimal")) {
      return "numeric";
    }
    if (normalized.equals("boolean") || normalized.equals("bool")) {
      return "boolean";
    }
    if (normalized.equals("text")
        || normalized.startsWith("character varying")
        || normalized.startsWith("varchar")
        || normalized.startsWith("character(")) {
      return "text";
    }
    throw new IllegalArgumentException("批量参数查询不支持来源列类型：" + columnName + "=" + sourceType);
  }

  static String quotedColumns(Iterable<String> columns) {
    java.util.ArrayList<String> quoted = new java.util.ArrayList<>();
    columns.forEach(column -> quoted.add(SqlIdentifierSupport.quoteIdentifier(column)));
    return String.join(", ", quoted);
  }

  static String csvField(Object value) {
    if (value == null) {
      return "\\N";
    }
    return "\"" + String.valueOf(value).replace("\"", "\"\"") + "\"";
  }
}
