package com.data.collection.platform.service.read;

import java.util.ArrayList;
import java.util.List;

/**
 * 一段可直接拼进 WHERE 子句的 SQL 谓词及其位置参数。
 *
 * @param sql 不含 {@code where} 关键字的谓词片段
 * @param args 与谓词中 {@code ?} 一一对应的参数
 */
public record SqlFilter(String sql, List<Object> args) {

  public SqlFilter {
    if (sql == null || sql.isBlank()) {
      throw new IllegalArgumentException("SQL 谓词不得为空");
    }
    args = args == null ? List.of() : List.copyOf(args);
  }

  /** 用 AND 连接另一个谓词，参数按顺序拼接。 */
  public SqlFilter and(SqlFilter other) {
    List<Object> merged = new ArrayList<>(args);
    merged.addAll(other.args());
    return new SqlFilter("(" + sql + ") and (" + other.sql() + ")", merged);
  }

  /** 用 OR 连接另一个谓词，参数按顺序拼接。 */
  public SqlFilter or(SqlFilter other) {
    List<Object> merged = new ArrayList<>(args);
    merged.addAll(other.args());
    return new SqlFilter("(" + sql + ") or (" + other.sql() + ")", merged);
  }
}
