package com.data.collection.platform.service.form;

import com.data.collection.platform.domain.form.FormHashSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 登记证据的确定性全值编码（N0 §3.2）。
 *
 * <p>每个标量都编码成带类型标签的 JSON 对象：{@code {"t":"null"}}、{@code {"t":"str","v":…}}、
 * {@code {"t":"int","v":…}}、{@code {"t":"bool","v":…}}、{@code {"t":"date","v":…}}、
 * {@code {"t":"time","v":…}}、{@code {"t":"dec","v":…}}、{@code {"t":"arr","v":[…]}}。
 * 因此 NULL 与空串、数字与同形文本、含分隔字符的文本都不会碰撞；字段顺序由调用方固定插入，
 * 序列化结果对同一输入逐字节稳定。禁止用空串占位或未转义分隔符拼接原值。
 */
final class FormEvidenceCodec {

  private final ObjectMapper objectMapper;

  FormEvidenceCodec(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  ObjectNode row() {
    return objectMapper.createObjectNode();
  }

  void putText(ObjectNode row, String key, String value) {
    row.set(key, typed(value));
  }

  void putNumber(ObjectNode row, String key, Long value) {
    row.set(key, typed(value));
  }

  void putInteger(ObjectNode row, String key, Integer value) {
    row.set(key, typed(value == null ? null : value.longValue()));
  }

  void putBoolean(ObjectNode row, String key, Boolean value) {
    row.set(key, typed(value));
  }

  void putTime(ObjectNode row, String key, LocalDateTime value) {
    row.set(key, typed(value));
  }

  /** 业务列原值：先归一化驱动类型，再按类型标签编码。 */
  void putValue(ObjectNode row, String key, Object value) {
    row.set(key, typed(canonical(value)));
  }

  /** 整行/整组证据的指纹：紧凑 JSON 后取 SHA-256。 */
  String fingerprint(ObjectNode envelope) {
    return FormHashSupport.sha256Hex(envelope.toString());
  }

  /** 归一化驱动返回值，使相等比较与编码都不依赖驱动对象身份。 */
  static Object canonical(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Array array) {
      try {
        Object raw = array.getArray();
        if (raw == null) {
          return null;
        }
        Object[] items = (Object[]) raw;
        List<Object> result = new ArrayList<>(items.length);
        for (Object item : items) {
          result.add(canonical(item));
        }
        return result;
      } catch (SQLException error) {
        throw new IllegalStateException("证据读取数组列失败", error);
      }
    }
    // 已规范值可能再次进入本方法：List/数组必须保持元素边界，不能退化成文本。
    if (value instanceof List<?> list) {
      List<Object> result = new ArrayList<>(list.size());
      for (Object item : list) {
        result.add(canonical(item));
      }
      return result;
    }
    if (value instanceof Object[] items) {
      List<Object> result = new ArrayList<>(items.length);
      for (Object item : items) {
        result.add(canonical(item));
      }
      return result;
    }
    if (value instanceof java.sql.Timestamp timestamp) {
      return timestamp.toLocalDateTime();
    }
    if (value instanceof java.sql.Date date) {
      return date.toLocalDate();
    }
    if (value instanceof LocalDate || value instanceof LocalDateTime) {
      return value;
    }
    if (value instanceof Integer integer) {
      return integer.longValue();
    }
    if (value instanceof Long || value instanceof Boolean || value instanceof String) {
      return value;
    }
    if (value instanceof java.math.BigInteger bigInteger) {
      return new BigDecimal(bigInteger);
    }
    if (value instanceof Number number) {
      return new BigDecimal(number.toString());
    }
    return String.valueOf(value);
  }

  private ObjectNode typed(Object canonical) {
    ObjectNode node = objectMapper.createObjectNode();
    if (canonical == null) {
      node.put("t", "null");
      return node;
    }
    if (canonical instanceof String text) {
      node.put("t", "str");
      node.put("v", text);
      return node;
    }
    if (canonical instanceof Long number) {
      node.put("t", "int");
      node.put("v", number);
      return node;
    }
    if (canonical instanceof BigDecimal decimal) {
      node.put("t", "dec");
      node.put("v", decimal.toPlainString());
      return node;
    }
    if (canonical instanceof Boolean flag) {
      node.put("t", "bool");
      node.put("v", flag);
      return node;
    }
    if (canonical instanceof LocalDate date) {
      node.put("t", "date");
      node.put("v", date.toString());
      return node;
    }
    if (canonical instanceof LocalDateTime time) {
      node.put("t", "time");
      node.put("v", time.toString());
      return node;
    }
    if (canonical instanceof List<?> list) {
      node.put("t", "arr");
      ArrayNode values = node.putArray("v");
      for (Object item : list) {
        values.add(typed(item));
      }
      return node;
    }
    node.put("t", "str");
    node.put("v", String.valueOf(canonical));
    return node;
  }
}
