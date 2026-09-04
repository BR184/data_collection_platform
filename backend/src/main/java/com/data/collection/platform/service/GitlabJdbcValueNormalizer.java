package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 将 JDBC 驱动对象脱离为可安全持久化的 Java 值。 */
@Component
class GitlabJdbcValueNormalizer {
  Object normalize(Object value) {
    return normalize(value, Types.OTHER, null);
  }

  Object normalize(Object value, int jdbcType, String jdbcTypeName) {
    if (value == null) {
      return null;
    }
    if (value instanceof OffsetDateTime odt) {
      return GitlabSourceTimestampNormalizer.normalizeOffsetDateTime(odt);
    }
    if (value instanceof Timestamp timestamp) {
      return GitlabSourceTimestampNormalizer.normalizeJdbcTimestamp(
          timestamp, jdbcType, jdbcTypeName);
    }
    if (value instanceof java.sql.SQLXML sqlXml) {
      try {
        return sqlXml.getString();
      } catch (Exception e) {
        throw new BizException("Failed to normalize SQLXML value: " + e.getMessage());
      } finally {
        try {
          sqlXml.free();
        } catch (Exception ignored) {
        }
      }
    }
    if (value instanceof java.sql.Array sqlArray) {
      try {
        Object array = sqlArray.getArray();
        return normalizeArrayValue(
            array, sqlArray.getBaseType(), sqlArray.getBaseTypeName());
      } catch (Exception e) {
        throw new BizException("Failed to normalize SQL array value: " + e.getMessage());
      } finally {
        try {
          sqlArray.free();
        } catch (Exception ignored) {
          // no-op
        }
      }
    }
    if (value instanceof Object[]) {
      return normalizeArrayValue(value, Types.OTHER, null);
    }
    if (value.getClass().getName().startsWith("org.postgresql.util.PG")) {
      try {
        return value.getClass().getMethod("getValue").invoke(value);
      } catch (Exception ignored) {
        return value.toString();
      }
    }
    return value;
  }

  private Object normalizeArrayValue(Object value, int jdbcType, String jdbcTypeName) {
    if (value == null) {
      return null;
    }
    if (value instanceof Object[] array) {
      List<Object> normalized = new ArrayList<>(array.length);
      for (Object item : array) {
        normalized.add(normalize(item, jdbcType, jdbcTypeName));
      }
      return normalized;
    }
    return value;
  }
}
