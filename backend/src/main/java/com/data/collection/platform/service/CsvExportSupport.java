package com.data.collection.platform.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

final class CsvExportSupport {
  private static final DateTimeFormatter DATE_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private CsvExportSupport() {}

  static String cell(Object value) {
    if (value == null) {
      return "";
    }
    String text = String.valueOf(value);
    if (text.contains("\"") || text.contains(",") || text.contains("\n") || text.contains("\r")) {
      return "\"" + text.replace("\"", "\"\"") + "\"";
    }
    return text;
  }

  static String dateTime(LocalDateTime value) {
    return value == null ? "" : DATE_TIME_FORMATTER.format(value);
  }
}
