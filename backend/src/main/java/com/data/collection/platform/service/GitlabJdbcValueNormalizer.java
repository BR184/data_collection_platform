package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

class GitlabJdbcValueNormalizer {
  Object normalize(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof OffsetDateTime odt) {
      return odt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
    if (value instanceof Timestamp timestamp) {
      return timestamp.toInstant().atOffset(ZoneOffset.UTC).toLocalDateTime();
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
        return normalizeArrayValue(array);
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
      return normalizeArrayValue(value);
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

  private Object normalizeArrayValue(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Object[] array) {
      List<Object> normalized = new ArrayList<>(array.length);
      for (Object item : array) {
        normalized.add(normalize(item));
      }
      return normalized;
    }
    return value;
  }
}
