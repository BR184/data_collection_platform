package com.data.collection.platform.service;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
