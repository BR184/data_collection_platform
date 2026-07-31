package com.data.collection.platform.service.sync;

import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GitLab 权威范围与派生关系目录。
 *
 * <p>根实体主键范围和可变子集合都必须在此显式声明。只有范围列集合及固定限定值完整匹配目录时，精确任务才可清理来源已不存在的
 * ODS 行；未声明范围只能执行普通 upsert。
 */
public final class AuthoritativeRelationCatalog {
  private static final List<ScopeDefinition> DIRECT_SCOPES =
      List.of(
          ScopeDefinition.primaryKey("issues"),
          ScopeDefinition.primaryKey("merge_requests"),
          ScopeDefinition.primaryKey("projects"),
          ScopeDefinition.primaryKey("users"),
          ScopeDefinition.primaryKey("ci_pipelines"),
          ScopeDefinition.primaryKey("ci_builds"),
          ScopeDefinition.primaryKey("deployments"));

  private static final List<Relation> RELATIONS =
      List.of(
          new Relation("issues", "id", "issue_assignees", "issue_id"),
          new Relation("issues", "id", "issue_metrics", "issue_id"),
          new Relation(
              "issues",
              "notes",
              Map.of("noteable_id", "id"),
              Map.of("noteable_type", "Issue"),
              Map.of()),
          new Relation(
              "issues",
              "label_links",
              Map.of("target_id", "id"),
              Map.of("target_type", "Issue"),
              Map.of()),
          new Relation(
              "resource_label_events",
              "label_links",
              Map.of("target_id", "issue_id"),
              Map.of("target_type", "Issue"),
              Map.of()),
          new Relation(
              "resource_label_events",
              "label_links",
              Map.of("target_id", "merge_request_id"),
              Map.of("target_type", "MergeRequest"),
              Map.of()),
          new Relation("merge_requests", "id", "merge_request_assignees", "merge_request_id"),
          new Relation("merge_requests", "id", "merge_request_reviewers", "merge_request_id"),
          new Relation("merge_requests", "id", "merge_request_metrics", "merge_request_id"),
          new Relation(
              "merge_requests",
              "notes",
              Map.of("noteable_id", "id"),
              Map.of("noteable_type", "MergeRequest"),
              Map.of()),
          new Relation(
              "merge_requests",
              "label_links",
              Map.of("target_id", "id"),
              Map.of("target_type", "MergeRequest"),
              Map.of()),
          new Relation(
              "notes",
              "notes",
              Map.of("noteable_id", "noteable_id"),
              Map.of("noteable_type", "Issue"),
              Map.of("noteable_type", "Issue")),
          new Relation(
              "notes",
              "notes",
              Map.of("noteable_id", "noteable_id"),
              Map.of("noteable_type", "MergeRequest"),
              Map.of("noteable_type", "MergeRequest")));

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
   * @param lookupScope 精确查询完整范围
   * @return 只有范围与目录中可直接执行的完整关系边界一致时返回 {@code true}
   */
  public static boolean isAuthoritativeTarget(String childTable, Map<String, String> lookupScope) {
    String normalizedChild = normalize(childTable);
    if (lookupScope == null || lookupScope.isEmpty()) {
      return false;
    }
    List<ScopeDefinition> candidates = new ArrayList<>(DIRECT_SCOPES);
    RELATIONS.stream().map(Relation::targetScope).forEach(candidates::add);
    return candidates.stream()
        .filter(scope -> scope.tableName().equals(normalizedChild))
        .anyMatch(scope -> scope.matches(lookupScope));
  }

  private static String normalize(String tableName) {
    return GitlabSourceInstanceSupport.normalizeSourceTableName(tableName);
  }

  /** 描述父资源主键与子关系权威范围之间的稳定映射。 */
  public record Relation(
      String parentTable,
      String childTable,
      Map<String, String> childScopeColumns,
      Map<String, String> fixedScopeValues,
      Map<String, String> requiredParentValues) {
    public Relation(
        String parentTable,
        String parentKey,
        String childTable,
        String childLookupColumn) {
      this(
          parentTable,
          childTable,
          Map.of(childLookupColumn, parentKey),
          Map.of(),
          Map.of());
    }

    public Relation {
      parentTable = normalize(parentTable);
      childTable = normalize(childTable);
      childScopeColumns = Map.copyOf(childScopeColumns);
      fixedScopeValues = Map.copyOf(fixedScopeValues);
      requiredParentValues = Map.copyOf(requiredParentValues);
    }

    /**
     * 根据父资源来源行生成子关系的完整权威范围。
     *
     * @param parentRow 当前父资源来源行
     * @return 包含 lookup、固定限定列和父行限定列的不可变范围；关键值缺失时返回空
     */
    public Map<String, Object> scopeForParentRow(Map<String, Object> parentRow) {
      if (parentRow == null) {
        return Map.of();
      }
      boolean parentMatches = requiredParentValues.entrySet().stream()
          .allMatch(entry -> entry.getValue().equals(String.valueOf(parentRow.get(entry.getKey()))));
      if (!parentMatches) {
        return Map.of();
      }
      LinkedHashMap<String, Object> scope = new LinkedHashMap<>();
      for (Map.Entry<String, String> entry : childScopeColumns.entrySet()) {
        Object value = parentRow.get(entry.getValue());
        if (value == null || String.valueOf(value).isBlank()) {
          return Map.of();
        }
        scope.put(entry.getKey(), value);
      }
      fixedScopeValues.forEach(scope::put);
      return Map.copyOf(scope);
    }

    private ScopeDefinition targetScope() {
      return new ScopeDefinition(
          childTable,
          Set.copyOf(childScopeColumns.keySet()),
          fixedScopeValues);
    }
  }

  private record ScopeDefinition(
      String tableName,
      Set<String> variableColumns,
      Map<String, String> fixedValues) {
    private ScopeDefinition {
      tableName = normalize(tableName);
      variableColumns = Set.copyOf(variableColumns);
      fixedValues = Map.copyOf(fixedValues);
    }

    private static ScopeDefinition primaryKey(String tableName) {
      return new ScopeDefinition(tableName, Set.of("id"), Map.of());
    }

    private boolean matches(Map<String, String> lookupScope) {
      if (lookupScope.size() != variableColumns.size() + fixedValues.size()) {
        return false;
      }
      if (!lookupScope.keySet().containsAll(variableColumns)
          || !lookupScope.keySet().containsAll(fixedValues.keySet())) {
        return false;
      }
      return fixedValues.entrySet().stream()
          .allMatch(entry -> entry.getValue().equals(lookupScope.get(entry.getKey())));
    }
  }
}
