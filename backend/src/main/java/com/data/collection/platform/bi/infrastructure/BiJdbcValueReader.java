package com.data.collection.platform.bi.infrastructure;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;

/** 在 BI JDBC 边界将驱动原生整数表示精确映射为领域长整数，并保留数据库空值。 */
final class BiJdbcValueReader {
  private BiJdbcValueReader() {}

  static Long nullableLong(ResultSet resultSet, String columnLabel) throws SQLException {
    Object value = resultSet.getObject(columnLabel);
    if (value == null) {
      return null;
    }
    if (!(value instanceof Number number)) {
      throw conversionError(columnLabel, value, null);
    }
    try {
      if (number instanceof BigDecimal decimal) {
        return decimal.longValueExact();
      }
      if (number instanceof BigInteger integer) {
        return integer.longValueExact();
      }
      if (number instanceof Byte
          || number instanceof Short
          || number instanceof Integer
          || number instanceof Long) {
        return number.longValue();
      }
      return new BigDecimal(number.toString()).longValueExact();
    } catch (ArithmeticException | NumberFormatException error) {
      throw conversionError(columnLabel, value, error);
    }
  }

  private static SQLException conversionError(
      String columnLabel,
      Object value,
      Exception cause) {
    String message = "BI 整数字段 " + columnLabel
        + " 无法从 " + value.getClass().getSimpleName() + " 精确转换为 Long";
    return cause == null ? new SQLException(message) : new SQLException(message, cause);
  }
}
