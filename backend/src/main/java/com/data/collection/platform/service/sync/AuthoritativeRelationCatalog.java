package com.data.collection.platform.service.sync;

import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import java.util.List;

/**
 * GitLab 可变关系的权威集合目录。
 *
 * <p>目录中的子表查询按父资源 lookup 范围返回当前完整集合，因此执行器可以在同一事务内删除来源已不存在的关系。
 * 未在目录中声明的精确查询只执行普通 upsert，不能推断为权威替换。
 */
public final class AuthoritativeRelationCatalog {
  private static final List<Relation> RELATIONS =
      List.of(
          new Relation("issues", "id", "issue_assignees", "issue_id"),
          new Relation("issues", "id", "label_links", "target_id"),
          new Relation("merge_requests", "id", "merge_request_assignees", "merge_request_id"),
          new Relation("merge_requests", "id", "merge_request_reviewers", "merge_request_id"),
          new Relation("merge_requests", "id", "label_links", "target_id"));

  private AuthoritativeRelationCatalog() {
  }

  /**
   * 返回由指定父表变更驱动的权威关系。
   *
   * @param parentTable GitLab 来源父表名
   * @return 按目录顺序排列的关系定义
   */
  public static List<Relation> relationsForParent(String parentTable) {
    String normalizedParent = normalize(parentTable);
    return RELATIONS.stream()
        .filter(relation -> relation.parentTable().equals(normalizedParent))
        .toList();
  }

  /**
   * 判断精确查询是否覆盖一个已声明关系的完整当前集合。
   *
   * @param childTable GitLab 来源子表名
   * @param lookupColumn 精确查询列
   * @return 只有表和 lookup 列同时匹配目录时返回 {@code true}
   */
  public static boolean isAuthoritativeTarget(String childTable, String lookupColumn) {
    String normalizedChild = normalize(childTable);
    return lookupColumn != null
        && RELATIONS.stream()
            .anyMatch(relation ->
                relation.childTable().equals(normalizedChild)
                    && relation.childLookupColumn().equals(lookupColumn));
  }

  private static String normalize(String tableName) {
    return GitlabSourceInstanceSupport.normalizeSourceTableName(tableName);
  }

  /** 描述父资源主键与子关系 lookup 范围之间的稳定映射。 */
  public record Relation(
      String parentTable,
      String parentKey,
      String childTable,
      String childLookupColumn) {
  }
}
