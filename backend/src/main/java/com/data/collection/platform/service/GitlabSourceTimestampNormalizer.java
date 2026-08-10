package com.data.collection.platform.service;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/** 统一 GitLab 来源两种读取模式的时间语义，并将有时区时间规范为 UTC 墙上值。 */
final class GitlabSourceTimestampNormalizer {
  private static final String POSTGRES_TIMESTAMPTZ = "timestamptz";

  private GitlabSourceTimestampNormalizer() {}

  static LocalDateTime normalizeJdbcTimestamp(
      Timestamp timestamp, int jdbcType, String jdbcTypeName) {
    if (isTimestampWithTimeZone(jdbcType, jdbcTypeName)) {
      return LocalDateTime.ofInstant(timestamp.toInstant(), ZoneOffset.UTC);
    }
    return timestamp.toLocalDateTime();
  }

  static LocalDateTime normalizeOffsetDateTime(OffsetDateTime value) {
    return value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
  }

  static LocalDateTime normalizeSourceValue(Object value) {
    if (value instanceof LocalDateTime localDateTime) {
      return localDateTime;
    }
    if (value instanceof OffsetDateTime offsetDateTime) {
      return normalizeOffsetDateTime(offsetDateTime);
    }
    if (value instanceof Timestamp timestamp) {
      return timestamp.toLocalDateTime();
    }
    if (value instanceof java.util.Date date) {
      return LocalDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC);
    }
    if (value instanceof String text) {
      return parseSourceText(text);
    }
    return null;
  }

  private static boolean isTimestampWithTimeZone(int jdbcType, String jdbcTypeName) {
    if (jdbcType == Types.TIMESTAMP_WITH_TIMEZONE) {
      return true;
    }
    if (jdbcTypeName == null || jdbcTypeName.isBlank()) {
      return false;
    }
    String normalizedType = jdbcTypeName.trim().toLowerCase(Locale.ROOT);
    return POSTGRES_TIMESTAMPTZ.equals(normalizedType)
        || normalizedType.contains("timestamp with time zone");
  }

  private static LocalDateTime parseSourceText(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    String normalizedText = text.trim().replace(' ', 'T');
    try {
      return normalizeOffsetDateTime(OffsetDateTime.parse(normalizedText));
    } catch (DateTimeParseException ignored) {
      // 无偏移时间由下一个分支按来源墙上值解析。
    }
    try {
      return LocalDateTime.parse(normalizedText);
    } catch (DateTimeParseException ignored) {
      return null;
    }
  }
}
