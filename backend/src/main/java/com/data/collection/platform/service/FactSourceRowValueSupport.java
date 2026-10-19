package com.data.collection.platform.service;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.util.StringUtils;

final class FactSourceRowValueSupport {
  private FactSourceRowValueSupport() {}

  static boolean isClosed(ResultSet resultSet) throws SQLException {
    Timestamp closedAt = resultSet.getTimestamp("closed_at");
    Integer stateId = (Integer) resultSet.getObject("state_id");
    return closedAt != null || (stateId != null && stateId != 1);
  }

  static String mergeRequestState(ResultSet resultSet) throws SQLException {
    Integer stateId = (Integer) resultSet.getObject("state_id");
    if (stateId != null) {
      return switch (stateId) {
        case 3 -> "merged";
        case 2 -> "closed";
        default -> "opened";
      };
    }
    return toLocalDateTime(resultSet.getTimestamp("merged_at")) == null
        ? "opened"
        : "merged";
  }

  static LocalDateTime toLocalDateTime(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toLocalDateTime();
  }

  /**
   * 按 ResultSet 返回的物理列类型解释来源时间。
   *
   * <p>带时区类型（{@code timestamptz}/{@code timestamp with time zone}）先取时刻再显式投影到目标时区；
   * 无时区类型原样保留来源墙上值，不做任何偏移猜测。元数据不可用时沿既有投影语义处理带时区来源。
   *
   * @param resultSet 来源结果集
   * @param column 时间列名
   * @param projectionZone 带时区列的目标投影时区
   * @return 投影后的墙上时间；列为空时返回 {@code null}
   */
  static LocalDateTime readTemporalProjection(
      ResultSet resultSet, String column, ZoneId projectionZone) throws SQLException {
    Timestamp timestamp = resultSet.getTimestamp(column);
    if (timestamp == null) {
      return null;
    }
    ResultSetMetaData metadata = resultSet.getMetaData();
    if (metadata != null && !isTimestampWithTimeZone(metadata, resultSet.findColumn(column))) {
      return timestamp.toLocalDateTime();
    }
    return timestamp.toInstant().atZone(projectionZone).toLocalDateTime();
  }

  private static boolean isTimestampWithTimeZone(ResultSetMetaData metadata, int columnIndex)
      throws SQLException {
    if (metadata.getColumnType(columnIndex) == Types.TIMESTAMP_WITH_TIMEZONE) {
      return true;
    }
    String typeName = metadata.getColumnTypeName(columnIndex);
    if (typeName == null || typeName.isBlank()) {
      return false;
    }
    String normalized = typeName.trim().toLowerCase(Locale.ROOT);
    return "timestamptz".equals(normalized) || normalized.contains("timestamp with time zone");
  }

  static Long nullableLong(ResultSet resultSet, String columnName) throws SQLException {
    Object value = resultSet.getObject(columnName);
    return value instanceof Number number ? number.longValue() : null;
  }

  static List<String> readTextArray(Array array) throws SQLException {
    if (array == null || !(array.getArray() instanceof Object[] values)) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    for (Object value : values) {
      String normalized = defaultText(value == null ? null : String.valueOf(value), null);
      if (normalized != null) {
        result.add(normalized);
      }
    }
    return List.copyOf(result);
  }

  static String defaultText(String value) {
    return defaultText(value, "");
  }

  static String defaultText(String value, String fallback) {
    return StringUtils.hasText(value) ? value.trim() : fallback;
  }
}
