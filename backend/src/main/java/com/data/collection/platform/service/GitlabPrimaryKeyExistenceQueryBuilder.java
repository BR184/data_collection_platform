package com.data.collection.platform.service;

import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.TableWhitelistOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 为 DIRECT 参数查询和 Docker COPY 会话生成同一套主键存在性语义。 */
final class GitlabPrimaryKeyExistenceQueryBuilder {

  GitlabParameterizedQuery buildDirect(
      TableWhitelistOption option,
      SourceTableSchema schema,
      List<String> primaryKeys,
      List<Map<String, Object>> primaryKeyRows) {
    validate(option, schema, primaryKeys, primaryKeyRows);
    String rowTemplate =
        primaryKeys.stream()
            .map(
                primaryKey ->
                    "?::" + GitlabTypedValuesSqlSupport.sqlType(schema, primaryKey))
            .collect(Collectors.joining(", ", "(", ")"));
    String values =
        java.util.Collections.nCopies(primaryKeyRows.size(), rowTemplate).stream()
            .collect(Collectors.joining(", "));
    ArrayList<Object> parameters = new ArrayList<>(primaryKeys.size() * primaryKeyRows.size());
    for (Map<String, Object> row : primaryKeyRows) {
      primaryKeys.forEach(primaryKey -> parameters.add(row.get(primaryKey)));
    }
    return new GitlabParameterizedQuery(
        selectSql(
            option,
            primaryKeys,
            "(values " + values + ") as requested_keys(" + quotedColumns(primaryKeys) + ")"),
        parameters);
  }

  String buildDockerCopyScript(
      TableWhitelistOption option,
      SourceTableSchema schema,
      List<String> primaryKeys,
      List<Map<String, Object>> primaryKeyRows) {
    validate(option, schema, primaryKeys, primaryKeyRows);
    String columnDefinitions =
        primaryKeys.stream()
            .map(
                primaryKey ->
                    GitlabTypedValuesSqlSupport.quoteIdentifier(primaryKey)
                        + " "
                        + GitlabTypedValuesSqlSupport.sqlType(schema, primaryKey))
            .collect(Collectors.joining(", "));
    StringBuilder script = new StringBuilder();
    script.append("begin;\n")
        .append("create temp table requested_keys(")
        .append(columnDefinitions)
        .append(") on commit drop;\n")
        .append("copy requested_keys(")
        .append(quotedColumns(primaryKeys))
        .append(") from stdin with (format csv, null '\\N');\n");
    for (Map<String, Object> row : primaryKeyRows) {
      script.append(
          primaryKeys.stream()
              .map(primaryKey -> csvField(row.get(primaryKey)))
              .collect(Collectors.joining(",")));
      script.append('\n');
    }
    script.append("\\.\n")
        .append("select row_to_json(result_row)::text from (")
        .append(selectSql(option, primaryKeys, "requested_keys"))
        .append(") result_row;\n")
        .append("commit;\n");
    return script.toString();
  }

  private String selectSql(
      TableWhitelistOption option, List<String> primaryKeys, String requestedRelation) {
    String selectColumns =
        primaryKeys.stream()
            .map(
                primaryKey ->
                    "source."
                        + GitlabTypedValuesSqlSupport.quoteIdentifier(primaryKey)
                        + "::text as "
                        + GitlabTypedValuesSqlSupport.quoteIdentifier(primaryKey))
            .collect(Collectors.joining(", "));
    String joinPredicate =
        primaryKeys.stream()
            .map(
                primaryKey ->
                    "source."
                        + GitlabTypedValuesSqlSupport.quoteIdentifier(primaryKey)
                        + " = requested_keys."
                        + GitlabTypedValuesSqlSupport.quoteIdentifier(primaryKey))
            .collect(Collectors.joining(" and "));
    return "select "
        + selectColumns
        + " from "
        + GitlabTypedValuesSqlSupport.quoteIdentifier("public")
        + "."
        + GitlabTypedValuesSqlSupport.quoteIdentifier(option.tableName())
        + " source join "
        + requestedRelation
        + " on "
        + joinPredicate;
  }

  private void validate(
      TableWhitelistOption option,
      SourceTableSchema schema,
      List<String> primaryKeys,
      List<Map<String, Object>> rows) {
    if (option == null || schema == null || primaryKeys == null || primaryKeys.isEmpty()) {
      throw new IllegalArgumentException("主键存在性查询必须包含表结构和主键");
    }
    if (rows == null || rows.isEmpty()) {
      throw new IllegalArgumentException("主键存在性查询批次不能为空");
    }
    for (String primaryKey : primaryKeys) {
      GitlabTypedValuesSqlSupport.sqlType(schema, primaryKey);
    }
    boolean invalid =
        rows.stream()
            .anyMatch(
                row ->
                    row == null
                        || primaryKeys.stream().anyMatch(primaryKey -> row.get(primaryKey) == null));
    if (invalid) {
      throw new IllegalArgumentException("主键存在性查询包含空主键值");
    }
  }

  private String quotedColumns(List<String> columns) {
    return GitlabTypedValuesSqlSupport.quotedColumns(columns);
  }

  private String csvField(Object value) {
    return GitlabTypedValuesSqlSupport.csvField(value);
  }
}
