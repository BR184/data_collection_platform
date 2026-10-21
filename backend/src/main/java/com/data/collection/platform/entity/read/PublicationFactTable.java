package com.data.collection.platform.entity.read;

import com.data.collection.platform.entity.FactType;

/** 参与读发布的三张主事实表，及其在业务键中的根列。 */
public enum PublicationFactTable {
  ISSUE("issue_fact", "issue_id"),
  MERGE_REQUEST("merge_request_fact", "merge_request_id"),
  INTEGRATION_TEST("integration_test_fact", "issue_id");

  private final String tableName;
  private final String rootColumn;

  PublicationFactTable(String tableName, String rootColumn) {
    this.tableName = tableName;
    this.rootColumn = rootColumn;
  }

  /** 物理表名。 */
  public String tableName() {
    return tableName;
  }

  /** 业务根列名；与 source_system/source_instance/project_id 共同构成业务根身份。 */
  public String rootColumn() {
    return rootColumn;
  }

  /** 对应的事实类型；用于与现有 FactType 契约对接。 */
  public FactType factType() {
    return FactType.valueOf(name());
  }

  /** 按域类型标记解析事实表；未知标记表示该域不是 GitLab 主事实域。 */
  public static PublicationFactTable ofFactType(String factType) {
    if (factType == null || factType.isBlank()) {
      throw new IllegalArgumentException("读查询上下文必须声明事实类型");
    }
    try {
      return valueOf(factType.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("不支持的读发布事实类型：" + factType);
    }
  }

  /** 按事实类型解析事实表。 */
  public static PublicationFactTable of(FactType factType) {
    if (factType == null) {
      throw new IllegalArgumentException("读查询上下文必须声明事实类型");
    }
    return valueOf(factType.name());
  }
}
