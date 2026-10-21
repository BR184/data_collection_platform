package com.data.collection.platform.entity.read;

import java.util.List;

/**
 * 服务端数据集的一张真实关系；SQL 与标识符只由生产者/表注册目录生成，不能接受客户端 SQL。
 *
 * @param tableKey 数据集内部稳定表键
 * @param relationName 当前归一化 PostgreSQL 关系名；读取类型来自发布时冻结的字段模式
 * @param keyColumns 真实稳定业务键；空列表仅允许完整新代际装载
 * @param ignoredIngestColumns 明确不参与业务值的装载审计列；完整行仍保留这些列
 * @param selectSql 完整关系集合的服务端 SELECT
 * @param arguments SELECT 绑定参数，保留 NULL
 */
public record DatasetRelationInput(String tableKey, String relationName, List<String> keyColumns,
    List<String> ignoredIngestColumns, String selectSql, List<Object> arguments) {
  public DatasetRelationInput {
    requireIdentifier(tableKey);
    requireIdentifier(relationName);
    keyColumns = List.copyOf(keyColumns);
    ignoredIngestColumns = List.copyOf(ignoredIngestColumns);
    keyColumns.forEach(DatasetRelationInput::requireIdentifier);
    ignoredIngestColumns.forEach(DatasetRelationInput::requireIdentifier);
    if (keyColumns.stream().anyMatch(ignoredIngestColumns::contains)) {
      throw new IllegalArgumentException("业务键不能被当作装载审计字段忽略");
    }
    String readSql = selectSql.stripLeading().toLowerCase(java.util.Locale.ROOT);
    if ((!readSql.startsWith("select ") && !readSql.startsWith("select\n") && !readSql.startsWith("with "))
        || selectSql.contains(";")) {
      throw new IllegalArgumentException("数据集捕获只接受服务端单条 SELECT");
    }
    arguments = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(arguments));
  }

  /** 限定本地真实关系与列标识符；不允许动态表达式、路径或带引号的客户端输入。 */
  public static void requireIdentifier(String identifier) {
    if (identifier == null || !identifier.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
      throw new IllegalArgumentException("数据集关系/列必须是注册的本地标识符");
    }
  }
}
