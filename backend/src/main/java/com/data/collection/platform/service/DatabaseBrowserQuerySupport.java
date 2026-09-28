package com.data.collection.platform.service;

import com.data.collection.platform.common.SqlIdentifierSupport;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.database.DatabaseTableColumn;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.util.StringUtils;

final class DatabaseBrowserQuerySupport {
  private static final int DEFAULT_PAGE = 1;
  private static final int DEFAULT_SIZE = 20;
  private static final int MAX_SIZE = 100;

  private DatabaseBrowserQuerySupport() {
  }

  static int normalizePage(Integer page) {
    return page == null || page < 1 ? DEFAULT_PAGE : page;
  }

  static int normalizeSize(Integer size) {
    return size == null || size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
  }

  static String normalizeKeyword(String keyword) {
    return StringUtils.hasText(keyword) ? keyword.trim() : null;
  }

  static String normalizeSortField(DatabaseBrowserTableDefinition definition, String sortField) {
    if (!StringUtils.hasText(sortField)) {
      return definition.defaultSortField();
    }
    boolean allowed = definition.columns().stream().anyMatch(column -> column.isSortable() && Objects.equals(column.getKey(), sortField));
    if (!allowed) {
      throw new BizException("当前排序字段不允许使用");
    }
    return sortField;
  }

  static String normalizeSortOrder(String sortOrder) {
    if (!StringUtils.hasText(sortOrder)) {
      return "desc";
    }
    String normalized = sortOrder.trim().toLowerCase(Locale.ROOT);
    if (!Objects.equals(normalized, "asc") && !Objects.equals(normalized, "desc")) {
      throw new BizException("当前排序方向不允许使用");
    }
    return normalized;
  }

  static DatabaseBrowserSqlBundle buildSql(
      DatabaseBrowserTableDefinition definition,
      String tableName,
      String keyword,
      String sortField,
      String sortOrder,
      int page,
      int size) {
    StringBuilder whereBuilder = new StringBuilder(" where 1 = 1");
    List<Object> arguments = new ArrayList<>();
    if (StringUtils.hasText(keyword) && !definition.searchableFields().isEmpty()) {
      whereBuilder.append(" and (");
      whereBuilder.append(definition.searchableFields().stream()
          .map(field -> "cast(" + SqlIdentifierSupport.quoteIdentifier(field) + " as text) ilike ?")
          .collect(Collectors.joining(" or ")));
      whereBuilder.append(")");
      String likeValue = "%" + keyword + "%";
      definition.searchableFields().forEach(field -> arguments.add(likeValue));
    }
    String orderClause =
        " order by " + SqlIdentifierSupport.quoteIdentifier(sortField) + " " + sortOrder + ", "
            + SqlIdentifierSupport.quoteIdentifier(definition.defaultSortField()) + " desc";
    String projection = buildProjection(definition);
    String countSql = "select count(*) from " + SqlIdentifierSupport.quoteIdentifier(tableName) + whereBuilder;
    String rowsSql = "select " + projection + " from " + SqlIdentifierSupport.quoteIdentifier(tableName) + whereBuilder + orderClause + " limit " + size + " offset "
        + ((page - 1) * size);
    return new DatabaseBrowserSqlBundle(countSql, rowsSql, arguments);
  }

  /**
   * 按表定义允许的公开列生成显式投影，替代 {@code select *}。
   *
   * <p>只有受控表定义声明的列才会进入查询与响应；物理表新增列或声明外的敏感列（如同步配置的
   * {@code db_password}、{@code api_token}、{@code system_hook_secret}）不会被自动暴露。
   * 缺少列定义时明确失败，不回退到全列查询。
   */
  private static String buildProjection(DatabaseBrowserTableDefinition definition) {
    List<String> columnKeys = definition.columns().stream()
        .map(DatabaseTableColumn::getKey)
        .filter(StringUtils::hasText)
        .toList();
    if (columnKeys.isEmpty()) {
      throw new BizException("当前表缺少可展示的列定义");
    }
    return columnKeys.stream()
        .map(SqlIdentifierSupport::quoteIdentifier)
        .collect(Collectors.joining(", "));
  }
}
