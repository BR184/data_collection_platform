package com.data.collection.platform.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.util.StringUtils;

final class IssueFactValueSupport {
  private IssueFactValueSupport() {}

  static String text(String value) {
    return value == null ? "" : value.trim();
  }

  static String text(String value, String fallback) {
    return StringUtils.hasText(value) ? value.trim() : fallback;
  }

  static LocalDateTime time(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toLocalDateTime();
  }

  static List<String> split(String raw) {
    if (!StringUtils.hasText(raw)) {
      return List.of();
    }
    Set<String> values = new LinkedHashSet<>();
    for (String item : raw.split(",")) {
      String normalized = item == null ? "" : item.trim();
      if (!normalized.isEmpty()) {
        values.add(normalized);
      }
    }
    return List.copyOf(values);
  }
}
