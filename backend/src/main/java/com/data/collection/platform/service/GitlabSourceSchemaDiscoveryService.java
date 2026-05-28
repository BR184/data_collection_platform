package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.GitlabSourceMetadataDiagnosticsResponse;
import com.data.collection.platform.entity.GitlabSourceTableDiagnosticsResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.TableWhitelistOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

class GitlabSourceSchemaDiscoveryService {
  private final BiFunction<GitlabSyncConfig, String, List<Map<String, Object>>> queryRunner;
  private final GitlabSourceMetadataSupport metadataSupport;

  GitlabSourceSchemaDiscoveryService(
      BiFunction<GitlabSyncConfig, String, List<Map<String, Object>>> queryRunner,
      GitlabSourceMetadataSupport metadataSupport) {
    this.queryRunner = queryRunner;
    this.metadataSupport = metadataSupport;
  }

  List<TableWhitelistOption> discoverTables(
      GitlabSyncConfig config,
      Map<String, String> labels,
      List<String> recommendedTables) {
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

  SourceTableSchema discoverTableSchema(GitlabSyncConfig config, TableWhitelistOption option) {
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
    List<Map<String, Object>> rows = queryRunner.apply(config, sql);
    List<SourceTableColumn> columns = new ArrayList<>(rows.size());
    for (Map<String, Object> row : rows) {
      columns.add(toColumn(row));
    }
    if (columns.isEmpty()) {
      throw new BizException("閺堫亜褰傞悳鐗堢爱鐞涖劎绮ㄩ弸? " + option.tableName());
    }
    return new SourceTableSchema(
        option.tableName(),
        metadataSupport.splitPrimaryKeys(option.primaryKey()),
        option.updatedAtColumn(),
        columns);
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
          and c.relkind = 'r'
        group by c.relname
        order by c.relname
        """;
    List<Map<String, Object>> rows = queryRunner.apply(config, sql);
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
      String updatedAtColumn = metadataSupport.resolveUpdatedAtColumn(
          entry.getValue().stream().map(SourceTableColumn::columnName).toList());
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
          and c.relkind = 'r'
          and a.attnum > 0
          and not a.attisdropped
        order by c.relname, a.attnum
        """;
    List<Map<String, Object>> rows = queryRunner.apply(config, sql);
    Map<String, List<SourceTableColumn>> columnsByTable = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      String tableName = String.valueOf(row.get("table_name"));
      columnsByTable.computeIfAbsent(tableName, ignored -> new ArrayList<>()).add(toColumn(row));
    }
    return columnsByTable;
  }

  GitlabSourceMetadataDiagnosticsResponse inspectSourceMetadata(
      GitlabSyncConfig config,
      List<TableWhitelistOption> whitelistOptions) {
    Map<String, String> primaryKeysByTable = discoverPrimaryKeysByTable(config);
    Map<String, List<SourceTableColumn>> columnsByTable = discoverColumnsByTable(config);
    Map<String, String> updatedAtColumns = new HashMap<>();
    for (Map.Entry<String, List<SourceTableColumn>> entry : columnsByTable.entrySet()) {
      updatedAtColumns.put(
          entry.getKey(),
          metadataSupport.resolveUpdatedAtColumn(entry.getValue().stream().map(SourceTableColumn::columnName).toList()));
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
          metadataSupport.splitPrimaryKeys(primaryKey),
          updatedAtColumn,
          entry.getValue());
      sourceTables.add(new GitlabSourceTableDiagnosticsResponse(
          tableName,
          primaryKey,
          updatedAtColumn,
          metadataSupport.resolveRowStrategy(updatedAtColumn),
          metadataSupport.buildSchemaFingerprint(schema),
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

  private SourceTableColumn toColumn(Map<String, Object> row) {
    return new SourceTableColumn(
        String.valueOf(row.get("column_name")),
        String.valueOf(row.get("formatted_type")),
        Boolean.parseBoolean(String.valueOf(row.get("nullable"))),
        ((Number) row.get("ordinal_position")).intValue());
  }
}
