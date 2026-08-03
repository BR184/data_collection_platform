package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GitLab 推荐来源表、权威范围和派生血缘的唯一目录。
 *
 * <p>每张推荐表必须显式声明主键、删除探测资格和派生归属。没有派生消费者也是明确状态，
 * 新增推荐表时不存在默认忽略路径。
 */
public final class GitlabSourceLineageCatalog {
  private static final List<SourceDefinition> SOURCES =
      List.of(
          dimension("users", List.of("id"), FactType.ISSUE, FactType.MERGE_REQUEST, FactType.INTEGRATION_TEST),
          noConsumer("user_details", List.of("user_id")),
          dimension("projects", List.of("id"), FactType.ISSUE, FactType.MERGE_REQUEST, FactType.INTEGRATION_TEST),
          dimension("namespaces", List.of("id"), FactType.MERGE_REQUEST),
          noConsumer("members", List.of("id")),
          dimension("milestones", List.of("id"), FactType.ISSUE),
          direct("issues", List.of("id"), FactType.ISSUE, FactType.INTEGRATION_TEST),
          direct("issue_assignees", List.of("issue_id", "user_id"), FactType.ISSUE),
          direct("issue_metrics", List.of("issue_id"), FactType.ISSUE),
          polymorphic("notes", List.of("id"), FactType.ISSUE, FactType.MERGE_REQUEST, FactType.INTEGRATION_TEST),
          dimension("labels", List.of("id"), FactType.ISSUE, FactType.MERGE_REQUEST, FactType.INTEGRATION_TEST),
          polymorphic("label_links", List.of("id"), FactType.ISSUE, FactType.MERGE_REQUEST, FactType.INTEGRATION_TEST),
          signal("resource_label_events", List.of("id"), FactType.ISSUE, FactType.MERGE_REQUEST),
          direct("merge_requests", List.of("id"), FactType.MERGE_REQUEST),
          direct("merge_request_assignees", List.of("merge_request_id", "user_id"), FactType.MERGE_REQUEST),
          direct("merge_request_reviewers", List.of("merge_request_id", "user_id"), FactType.MERGE_REQUEST),
          direct("merge_request_metrics", List.of("merge_request_id"), FactType.MERGE_REQUEST),
          noConsumer("ci_pipelines", List.of("id")),
          noConsumer("ci_builds", List.of("id", "partition_id")),
          noConsumer("deployments", List.of("id")),
          noConsumer("environments", List.of("id")),
          noConsumer("events", List.of("id")),
          noConsumer("todos", List.of("id")));

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
          Relation.simple("issue-assignees", "issues", "id", "issue_assignees", "issue_id"),
          Relation.simple("issue-metrics", "issues", "id", "issue_metrics", "issue_id"),
          new Relation(
              "issue-notes",
              "issues",
              "notes",
              Map.of("noteable_id", "id"),
              Map.of("noteable_type", "Issue"),
              Map.of()),
          new Relation(
              "issue-label-links",
              "issues",
              "label_links",
              Map.of("target_id", "id"),
              Map.of("target_type", "Issue"),
              Map.of()),
          new Relation(
              "label-event-issue-links",
              "resource_label_events",
              "label_links",
              Map.of("target_id", "issue_id"),
              Map.of("target_type", "Issue"),
              Map.of()),
          new Relation(
              "label-event-merge-request-links",
              "resource_label_events",
              "label_links",
              Map.of("target_id", "merge_request_id"),
              Map.of("target_type", "MergeRequest"),
              Map.of()),
          Relation.simple(
              "merge-request-assignees",
              "merge_requests",
              "id",
              "merge_request_assignees",
              "merge_request_id"),
          Relation.simple(
              "merge-request-reviewers",
              "merge_requests",
              "id",
              "merge_request_reviewers",
              "merge_request_id"),
          Relation.simple(
              "merge-request-metrics",
              "merge_requests",
              "id",
              "merge_request_metrics",
              "merge_request_id"),
          new Relation(
              "merge-request-notes",
              "merge_requests",
              "notes",
              Map.of("noteable_id", "id"),
              Map.of("noteable_type", "MergeRequest"),
              Map.of()),
          new Relation(
              "merge-request-label-links",
              "merge_requests",
              "label_links",
              Map.of("target_id", "id"),
              Map.of("target_type", "MergeRequest"),
              Map.of()),
          new Relation(
              "issue-note-owner",
              "notes",
              "notes",
              Map.of("noteable_id", "noteable_id"),
              Map.of("noteable_type", "Issue"),
              Map.of("noteable_type", "Issue")),
          new Relation(
              "merge-request-note-owner",
              "notes",
              "notes",
              Map.of("noteable_id", "noteable_id"),
              Map.of("noteable_type", "MergeRequest"),
              Map.of("noteable_type", "MergeRequest")));

  private GitlabSourceLineageCatalog() {}

  /** 返回全部推荐来源定义，顺序稳定。 */
  public static List<SourceDefinition> sources() {
    return SOURCES;
  }

  /** 返回推荐来源表名，顺序稳定且无重复。 */
  public static List<String> recommendedTables() {
    return SOURCES.stream().map(SourceDefinition::tableName).toList();
  }

  /** 返回指定来源定义；未知表显式失败。 */
  public static SourceDefinition requireSource(String tableName) {
    String normalized = normalize(tableName);
    return SOURCES.stream()
        .filter(source -> source.tableName().equals(normalized))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("GitLab 来源表尚未声明血缘：" + normalized));
  }

  /** 返回由指定父表变更驱动的权威关系。 */
  public static List<Relation> relationsForParent(String parentTable) {
    String normalizedParent = normalize(parentTable);
    return RELATIONS.stream()
        .filter(relation -> relation.parentTable().equals(normalizedParent))
        .toList();
  }

  /** 判断精确范围是否覆盖一个已声明关系的完整当前集合。 */
  public static boolean isAuthoritativeTarget(String childTable, Map<String, ?> lookupScope) {
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

  private static SourceDefinition direct(
      String table, List<String> primaryKeys, FactType... consumers) {
    return source(table, primaryKeys, DerivationKind.DIRECT_ROOT, consumers);
  }

  private static SourceDefinition polymorphic(
      String table, List<String> primaryKeys, FactType... consumers) {
    return source(table, primaryKeys, DerivationKind.POLYMORPHIC_ROOT, consumers);
  }

  private static SourceDefinition dimension(
      String table, List<String> primaryKeys, FactType... consumers) {
    return source(table, primaryKeys, DerivationKind.DIMENSION_REVERSE_LOOKUP, consumers);
  }

  private static SourceDefinition signal(
      String table, List<String> primaryKeys, FactType... consumers) {
    return source(table, primaryKeys, DerivationKind.CHANGE_SIGNAL, consumers);
  }

  private static SourceDefinition noConsumer(String table, List<String> primaryKeys) {
    return source(table, primaryKeys, DerivationKind.NO_DERIVED_CONSUMER);
  }

  private static SourceDefinition source(
      String table, List<String> primaryKeys, DerivationKind kind, FactType... consumers) {
    return new SourceDefinition(
        normalize(table),
        List.copyOf(primaryKeys),
        true,
        kind,
        Set.of(consumers));
  }

  private static String normalize(String tableName) {
    return GitlabSourceInstanceSupport.normalizeSourceTableName(tableName);
  }

  /** 来源表对删除探测和派生发布的声明。 */
  public record SourceDefinition(
      String tableName,
      List<String> primaryKeys,
      boolean deletionDetectionEnabled,
      DerivationKind derivationKind,
      Set<FactType> factConsumers) {
    public SourceDefinition {
      tableName = normalize(tableName);
      primaryKeys = List.copyOf(primaryKeys);
      factConsumers = Set.copyOf(factConsumers);
      if (primaryKeys.isEmpty()) {
        throw new IllegalArgumentException("GitLab 来源表必须声明主键：" + tableName);
      }
      if (derivationKind == DerivationKind.NO_DERIVED_CONSUMER && !factConsumers.isEmpty()) {
        throw new IllegalArgumentException("无派生消费者来源不能声明事实类型：" + tableName);
      }
      if (derivationKind != DerivationKind.NO_DERIVED_CONSUMER && factConsumers.isEmpty()) {
        throw new IllegalArgumentException("派生来源必须声明事实消费者：" + tableName);
      }
    }
  }

  /** 来源变化到事实根目标的解析方式。 */
  public enum DerivationKind {
    DIRECT_ROOT,
    POLYMORPHIC_ROOT,
    DIMENSION_REVERSE_LOOKUP,
    CHANGE_SIGNAL,
    NO_DERIVED_CONSUMER
  }

  /** 描述父资源与子关系权威范围之间的稳定映射。 */
  public record Relation(
      String relationKey,
      String parentTable,
      String childTable,
      Map<String, String> childScopeColumns,
      Map<String, String> fixedScopeValues,
      Map<String, String> requiredParentValues) {
    public Relation {
      if (relationKey == null || relationKey.isBlank()) {
        throw new IllegalArgumentException("权威关系必须声明稳定 relationKey");
      }
      parentTable = normalize(parentTable);
      childTable = normalize(childTable);
      childScopeColumns = Map.copyOf(childScopeColumns);
      fixedScopeValues = Map.copyOf(fixedScopeValues);
      requiredParentValues = Map.copyOf(requiredParentValues);
    }

    private static Relation simple(
        String relationKey,
        String parentTable,
        String parentKey,
        String childTable,
        String childLookupColumn) {
      return new Relation(
          relationKey,
          parentTable,
          childTable,
          Map.of(childLookupColumn, parentKey),
          Map.of(),
          Map.of());
    }

    /** 根据父资源来源行生成完整权威范围；关键值缺失时返回空。 */
    public Map<String, Object> scopeForParentRow(Map<String, Object> parentRow) {
      if (parentRow == null) {
        return Map.of();
      }
      boolean parentMatches =
          requiredParentValues.entrySet().stream()
              .allMatch(
                  entry ->
                      entry.getValue().equals(String.valueOf(parentRow.get(entry.getKey()))));
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
      return new ScopeDefinition(childTable, Set.copyOf(childScopeColumns.keySet()), fixedScopeValues);
    }
  }

  private record ScopeDefinition(
      String tableName, Set<String> variableColumns, Map<String, String> fixedValues) {
    private ScopeDefinition {
      tableName = normalize(tableName);
      variableColumns = Set.copyOf(variableColumns);
      fixedValues = Map.copyOf(fixedValues);
    }

    private static ScopeDefinition primaryKey(String tableName) {
      SourceDefinition source = requireSource(tableName);
      return new ScopeDefinition(tableName, new LinkedHashSet<>(source.primaryKeys()), Map.of());
    }

    private boolean matches(Map<String, ?> lookupScope) {
      if (lookupScope.size() != variableColumns.size() + fixedValues.size()) {
        return false;
      }
      if (!lookupScope.keySet().containsAll(variableColumns)
          || !lookupScope.keySet().containsAll(fixedValues.keySet())) {
        return false;
      }
      return fixedValues.entrySet().stream()
          .allMatch(entry -> entry.getValue().equals(String.valueOf(lookupScope.get(entry.getKey()))));
    }
  }
}
