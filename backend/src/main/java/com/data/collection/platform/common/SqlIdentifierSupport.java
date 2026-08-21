package com.data.collection.platform.common;

/** 提供动态 SQL 标识符的统一引用规则。 */
public final class SqlIdentifierSupport {
  private SqlIdentifierSupport() {}

  /**
   * 将单个 SQL 标识符按 PostgreSQL 双引号规则进行引用。
   *
   * @param identifier 要引用的表名、列名或索引名；不能为空或仅包含空白
   * @return 已引用的 SQL 标识符
   * @throws IllegalArgumentException 标识符为空时抛出
   */
  public static String quoteIdentifier(String identifier) {
    if (identifier == null || identifier.isBlank()) {
      throw new IllegalArgumentException("SQL 标识符不能为空");
    }
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }
}
