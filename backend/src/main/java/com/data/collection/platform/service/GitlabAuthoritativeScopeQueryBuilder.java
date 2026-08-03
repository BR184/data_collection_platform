package com.data.collection.platform.service;

import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.TableWhitelistOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 为一批同构权威范围生成 DIRECT typed VALUES 或 Docker COPY 查询。 */
final class GitlabAuthoritativeScopeQueryBuilder {
  static final String SCOPE_ID_COLUMN = "__qaflex_scope_id";

  GitlabParameterizedQuery buildDirect(
      TableWhitelistOption option,
      SourceTableSchema schema,
      List<ScopeInput> scopes) {
    List<String> scopeColumns = validate(option, schema, scopes);
    String rowTemplate = directRowTemplate(schema, scopeColumns);
    String values = java.util.Collections.nCopies(scopes.size(), rowTemplate).stream()
        .collect(Collectors.joining(", "));
    ArrayList<Object> parameters = new ArrayList<>(scopes.size() * (scopeColumns.size() + 1));
    for (ScopeInput scope : scopes) {
      parameters.add(scope.scopeId());
      scopeColumns.forEach(column -> parameters.add(scope.lookupScope().get(column)));
    }
    return new GitlabParameterizedQuery(
        selectSql(
            option,
            scopeColumns,
            "(values " + values + ") as requested_scopes("
                + GitlabTypedValuesSqlSupport.quoteIdentifier(SCOPE_ID_COLUMN)
                + ", "
                + GitlabTypedValuesSqlSupport.quotedColumns(scopeColumns)
                + ")"),
        parameters);
  }

  String buildDockerCopyScript(
      TableWhitelistOption option,
      SourceTableSchema schema,
      List<ScopeInput> scopes) {
    List<String> scopeColumns = validate(option, schema, scopes);
    String definitions = scopeColumns.stream()
        .map(
            column ->
                GitlabTypedValuesSqlSupport.quoteIdentifier(column)
                    + " "
                    + GitlabTypedValuesSqlSupport.sqlType(schema, column))
        .collect(Collectors.joining(", "));
    StringBuilder script = new StringBuilder();
    script.append("begin;\n")
        .append("create temp table requested_scopes(\"")
        .append(SCOPE_ID_COLUMN)
        .append("\" bigint, ")
        .append(definitions)
        .append(") on commit drop;\n")
        .append("copy requested_scopes(\"")
        .append(SCOPE_ID_COLUMN)
        .append("\", ")
        .append(GitlabTypedValuesSqlSupport.quotedColumns(scopeColumns))
        .append(") from stdin with (format csv, null '\\N');\n");
    for (ScopeInput scope : scopes) {
      ArrayList<String> fields = new ArrayList<>(scopeColumns.size() + 1);
      fields.add(GitlabTypedValuesSqlSupport.csvField(scope.scopeId()));
      scopeColumns.forEach(
          column -> fields.add(GitlabTypedValuesSqlSupport.csvField(scope.lookupScope().get(column))));
      script.append(String.join(",", fields)).append('\n');
    }
    script.append("\\.\n")
        .append("select row_to_json(result_row)::text from (")
        .append(selectSql(option, scopeColumns, "requested_scopes"))
        .append(") result_row;\n")
        .append("commit;\n");
    return script.toString();
  }

  private String directRowTemplate(SourceTableSchema schema, List<String> scopeColumns) {
    ArrayList<String> values = new ArrayList<>(scopeColumns.size() + 1);
    values.add("?::bigint");
    scopeColumns.forEach(
        column -> values.add("?::" + GitlabTypedValuesSqlSupport.sqlType(schema, column)));
    return values.stream().collect(Collectors.joining(", ", "(", ")"));
  }

  private String selectSql(
      TableWhitelistOption option, List<String> scopeColumns, String requestedRelation) {
    String joinPredicate = scopeColumns.stream()
        .map(
            column ->
                "source."
                    + GitlabTypedValuesSqlSupport.quoteIdentifier(column)
                    + " = requested_scopes."
                    + GitlabTypedValuesSqlSupport.quoteIdentifier(column))
        .collect(Collectors.joining(" and "));
    return "select requested_scopes."
        + GitlabTypedValuesSqlSupport.quoteIdentifier(SCOPE_ID_COLUMN)
        + ", source.* from "
        + GitlabTypedValuesSqlSupport.quoteIdentifier("public")
        + "."
        + GitlabTypedValuesSqlSupport.quoteIdentifier(option.tableName())
        + " source join "
        + requestedRelation
        + " on "
        + joinPredicate;
  }

  private List<String> validate(
      TableWhitelistOption option,
      SourceTableSchema schema,
      List<ScopeInput> scopes) {
    if (option == null || schema == null || scopes == null || scopes.isEmpty()) {
      throw new IllegalArgumentException("批量权威范围查询必须包含表结构和范围");
    }
    if (schema.columns().stream().anyMatch(column -> SCOPE_ID_COLUMN.equals(column.columnName()))) {
      throw new IllegalArgumentException("来源表包含保留的范围标识列：" + SCOPE_ID_COLUMN);
    }
    Set<String> firstColumns = normalizedColumns(scopes.getFirst().lookupScope());
    if (firstColumns.isEmpty()) {
      throw new IllegalArgumentException("权威范围不能为空");
    }
    for (ScopeInput scope : scopes) {
      if (scope == null || scope.scopeId() <= 0L) {
        throw new IllegalArgumentException("权威范围缺少稳定队列 ID");
      }
      Set<String> columns = normalizedColumns(scope.lookupScope());
      if (!columns.equals(firstColumns)) {
        throw new IllegalArgumentException("同一批权威范围的查询列必须一致");
      }
      for (String column : columns) {
        GitlabTypedValuesSqlSupport.sqlType(schema, column);
        Object value = scope.lookupScope().get(column);
        if (value == null || String.valueOf(value).isBlank()) {
          throw new IllegalArgumentException("权威范围包含空值：" + column);
        }
      }
    }
    return List.copyOf(firstColumns);
  }

  private Set<String> normalizedColumns(Map<String, Object> scope) {
    if (scope == null || scope.isEmpty()) {
      return Set.of();
    }
    return scope.keySet().stream()
        .sorted()
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  record ScopeInput(long scopeId, Map<String, Object> lookupScope) {
    ScopeInput {
      lookupScope = lookupScope == null ? Map.of() : Map.copyOf(lookupScope);
    }
  }
}
