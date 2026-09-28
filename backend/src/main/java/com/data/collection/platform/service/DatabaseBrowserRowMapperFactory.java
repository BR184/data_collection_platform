package com.data.collection.platform.service;

import com.data.collection.platform.entity.database.DatabaseTableColumn;
import java.sql.SQLException;
import java.time.temporal.TemporalAccessor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;

final class DatabaseBrowserRowMapperFactory {
  private DatabaseBrowserRowMapperFactory() {
  }

  /**
   * 按表定义允许的列顺序构建行映射器，与 {@code buildSql} 的显式投影一一对应。
   *
   * <p>结果只包含声明列，不遍历 ResultSet 全部元数据。前置条件：{@code buildSql} 必须按同一
   * {@code definition.columns()} 的顺序与集合生成投影，映射器据此按位置读取——投影列与定义列
   * 一旦错序或数量不符，按位置读取会把值错配到别的键（含把未声明列的值写入声明键）。该一致性
   * 由二者共用同一列定义保证，不由本映射器兜底；缺少列定义应在生成投影前失败。
   */
  static RowMapper<Map<String, Object>> createTableRowMapper(List<DatabaseTableColumn> columns) {
    List<String> columnKeys = columns.stream().map(DatabaseTableColumn::getKey).toList();
    return (resultSet, rowNum) -> {
      Map<String, Object> row = new LinkedHashMap<>();
      for (int index = 0; index < columnKeys.size(); index++) {
        row.put(columnKeys.get(index), normalizeValue(resultSet.getObject(index + 1)));
      }
      return row;
    };
  }

  private static Object normalizeValue(Object value) throws SQLException {
    if (value == null) {
      return null;
    }
    if ("org.postgresql.util.PGobject".equals(value.getClass().getName())) {
      return value.toString();
    }
    if (value instanceof TemporalAccessor) {
      return value.toString();
    }
    return value;
  }
}
