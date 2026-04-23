package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
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
          .map(field -> "cast(" + quoteIdentifier(field) + " as text) ilike ?")
          .collect(Collectors.joining(" or ")));
      whereBuilder.append(")");
      String likeValue = "%" + keyword + "%";
      definition.searchableFields().forEach(field -> arguments.add(likeValue));
    }
    String orderClause =
        " order by " + quoteIdentifier(sortField) + " " + sortOrder + ", " + quoteIdentifier(definition.defaultSortField()) + " desc";
    String countSql = "select count(*) from " + quoteIdentifier(tableName) + whereBuilder;
    String rowsSql = "select * from " + quoteIdentifier(tableName) + whereBuilder + orderClause + " limit " + size + " offset "
        + ((page - 1) * size);
    return new DatabaseBrowserSqlBundle(countSql, rowsSql, arguments);
  }

  private static String quoteIdentifier(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }
}
