package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

final class CsvExportSupport {
  static final int MAX_EXPORT_ROWS = 10000;
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

  static void ensureWithinRowLimit(long total) {
    if (total > MAX_EXPORT_ROWS) {
      throw new BizException("导出结果超过 " + MAX_EXPORT_ROWS + " 行，请缩小筛选条件后再导出");
    }
  }
}
