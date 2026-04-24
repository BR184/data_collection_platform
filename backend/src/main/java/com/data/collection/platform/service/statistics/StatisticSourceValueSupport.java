package com.data.collection.platform.service.statistics;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.util.StringUtils;

final class StatisticSourceValueSupport {

  private StatisticSourceValueSupport() {}

  static Long parseLong(String value) {
    try {
      return StringUtils.hasText(value) ? Long.parseLong(value.trim()) : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  static String text(String value, String fallback) {
    return StringUtils.hasText(value) ? value.trim() : fallback;
  }

  static String text(String value) {
    return value == null ? "" : value.trim();
  }

  static LocalDateTime time(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toLocalDateTime();
  }

  static List<String> split(String raw) {
    if (!StringUtils.hasText(raw)) {
      return List.of();
    }
    Set<String> values = new LinkedHashSet<>();
    for (String value : raw.split(",")) {
      String trimmed = value == null ? "" : value.trim();
      if (!trimmed.isEmpty()) {
        values.add(trimmed);
      }
    }
    return List.copyOf(values);
  }
}
