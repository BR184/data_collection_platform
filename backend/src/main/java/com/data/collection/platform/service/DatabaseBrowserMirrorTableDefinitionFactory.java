package com.data.collection.platform.service;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.GitlabMirrorTableRegistry;
import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.database.DatabaseTableColumn;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DatabaseBrowserMirrorTableDefinitionFactory {
  private static final TypeReference<List<SourceTableColumn>> COLUMN_LIST_TYPE = new TypeReference<>() {};

  private final JsonUtils jsonUtils;

  public DatabaseBrowserMirrorTableDefinitionFactory(JsonUtils jsonUtils) {
    this.jsonUtils = jsonUtils;
  }

  public DatabaseBrowserTableDefinition buildMirrorTableDefinition(GitlabMirrorTableRegistry registry) {
    List<SourceTableColumn> sourceColumns = jsonUtils.fromJson(registry.getColumnSnapshot(), COLUMN_LIST_TYPE);
    if (sourceColumns == null || sourceColumns.isEmpty()) {
      throw new BizException("当前镜像表注册信息缺少字段快照，无法展示数据库查看");
    }
    List<DatabaseTableColumn> columns = new ArrayList<>();
    for (SourceTableColumn sourceColumn : sourceColumns) {
      columns.add(new DatabaseTableColumn(
          sourceColumn.columnName(),
          prettifyColumnName(sourceColumn.columnName()),
          true));
    }
    columns.add(new DatabaseTableColumn("mirror_task_id", "同步任务 ID", true));
    columns.add(new DatabaseTableColumn("source_updated_at", "源更新时间", true));
    columns.add(new DatabaseTableColumn("mirror_synced_at", "镜像时间", true));
    columns.add(new DatabaseTableColumn("mirror_deleted", "是否删除", true));

    List<String> searchableFields = sourceColumns.stream()
        .map(SourceTableColumn::columnName)
        .filter(this::isSearchableField)
        .limit(6)
        .toList();
    if (searchableFields.isEmpty()) {
      searchableFields = List.of(sourceColumns.get(0).columnName());
    }

    String defaultSortField = StringUtils.hasText(registry.getUpdatedAtColumn()) && columns.stream()
        .anyMatch(column -> Objects.equals(column.getKey(), registry.getUpdatedAtColumn()))
        ? registry.getUpdatedAtColumn()
        : "mirror_synced_at";
    return new DatabaseBrowserTableDefinition(
        buildMirrorLabel(registry.getSourceTableName()),
        searchableFields,
        columns,
        defaultSortField);
  }

  public String buildMirrorLabel(String sourceTableName) {
    return "镜像表 / " + sourceTableName;
  }

  private boolean isSearchableField(String columnName) {
    return !List.of("metadata", "payload", "description_html").contains(columnName);
  }

  private String prettifyColumnName(String columnName) {
    String[] parts = columnName.split("_");
    StringBuilder builder = new StringBuilder();
    for (String part : parts) {
      if (!builder.isEmpty()) {
        builder.append(' ');
      }
      builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
      if (part.length() > 1) {
        builder.append(part.substring(1));
      }
    }
    return builder.toString();
  }
}
