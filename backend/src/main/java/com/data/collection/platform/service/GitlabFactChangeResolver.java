package com.data.collection.platform.service;

import com.data.collection.platform.entity.FactChangeIdentity;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.MirrorRowChange;
import com.data.collection.platform.service.sync.GitlabSourceLineageCatalog;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 把 ODS 业务行变化解析为稳定 Issue/MR 根目标。 */
@Service
public class GitlabFactChangeResolver {
  private final JdbcTemplate jdbcTemplate;

  public GitlabFactChangeResolver(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 解析一批已确认真实发生的镜像变化。
   *
   * <p>同时读取 before/after 归属，确保关系迁移和删除不会丢失旧根；维表通过固定反向关系批量解析。
   */
  public List<FactChangeIdentity> resolve(
      String sourceInstance, String sourceTable, List<MirrorRowChange> changes) {
    if (changes == null || changes.isEmpty()) {
      return List.of();
    }
    GitlabSourceLineageCatalog.SourceDefinition source =
        GitlabSourceLineageCatalog.requireSource(sourceTable);
    if (source.derivationKind()
        == GitlabSourceLineageCatalog.DerivationKind.NO_DERIVED_CONSUMER
        || source.derivationKind()
            == GitlabSourceLineageCatalog.DerivationKind.CHANGE_SIGNAL) {
      return List.of();
    }
    LinkedHashSet<RootReference> roots = new LinkedHashSet<>();
    List<Map<String, Object>> rows = changedRows(changes);
    switch (source.tableName()) {
      case "issues" -> addRoots(roots, RootType.ISSUE, values(rows, "id"));
      case "issue_assignees", "issue_metrics" ->
          addRoots(roots, RootType.ISSUE, values(rows, "issue_id"));
      case "merge_requests" -> addRoots(roots, RootType.MERGE_REQUEST, values(rows, "id"));
      case "merge_request_assignees", "merge_request_reviewers", "merge_request_metrics" ->
          addRoots(roots, RootType.MERGE_REQUEST, values(rows, "merge_request_id"));
      case "notes" -> addPolymorphicRoots(roots, rows, "noteable_type", "noteable_id");
      case "label_links" -> addPolymorphicRoots(roots, rows, "target_type", "target_id");
      case "labels" -> roots.addAll(rootsForLabels(values(rows, "id")));
      case "milestones" -> roots.addAll(issueRootsByColumn("milestone_id", values(rows, "id")));
      case "projects" -> roots.addAll(rootsForProjects(values(rows, "id")));
      case "namespaces" -> roots.addAll(rootsForNamespaces(values(rows, "id")));
      case "users" -> roots.addAll(rootsForUsers(values(rows, "id")));
      default -> throw new IllegalStateException("来源血缘缺少根目标解析器：" + source.tableName());
    }
    return identities(sourceInstance, source, roots);
  }

  private List<Map<String, Object>> changedRows(List<MirrorRowChange> changes) {
    ArrayList<Map<String, Object>> rows = new ArrayList<>(changes.size() * 2);
    for (MirrorRowChange change : changes) {
      if (!change.before().isEmpty()) {
        rows.add(change.before());
      }
      if (!change.after().isEmpty()) {
        rows.add(change.after());
      }
    }
    return rows;
  }

  private List<FactChangeIdentity> identities(
      String sourceInstance,
      GitlabSourceLineageCatalog.SourceDefinition source,
      Set<RootReference> roots) {
    Set<Long> issueIds = rootIds(roots, RootType.ISSUE);
    Set<Long> mergeRequestIds = rootIds(roots, RootType.MERGE_REQUEST);
    Map<Long, RootDetails> issueDetails = loadRootDetails("ods_gitlab_issues", "project_id", issueIds);
    Map<Long, RootDetails> mergeRequestDetails =
        loadRootDetails("ods_gitlab_merge_requests", "target_project_id", mergeRequestIds);
    LinkedHashSet<FactChangeIdentity> identities = new LinkedHashSet<>();
    for (RootReference root : roots) {
      if (root.type() == RootType.ISSUE) {
        RootDetails details = issueDetails.getOrDefault(root.id(), RootDetails.empty());
        if (source.factConsumers().contains(FactType.ISSUE)) {
          identities.add(
              new FactChangeIdentity(
                  sourceInstance, FactType.ISSUE, root.id(), details.projectId(), details.iid()));
        }
        if (source.factConsumers().contains(FactType.INTEGRATION_TEST)) {
          identities.add(
              new FactChangeIdentity(
                  sourceInstance,
                  FactType.INTEGRATION_TEST,
                  root.id(),
                  details.projectId(),
                  details.iid()));
        }
      } else if (source.factConsumers().contains(FactType.MERGE_REQUEST)) {
        RootDetails details = mergeRequestDetails.getOrDefault(root.id(), RootDetails.empty());
        identities.add(
            new FactChangeIdentity(
                sourceInstance,
                FactType.MERGE_REQUEST,
                root.id(),
                details.projectId(),
                details.iid()));
      }
    }
    return identities.stream().sorted().toList();
  }

  private Set<RootReference> rootsForLabels(Set<Long> labelIds) {
    if (labelIds.isEmpty()) {
      return Set.of();
    }
    String sql = """
        select target_type, target_id
          from ods_gitlab_label_links
         where mirror_deleted = false
           and label_id in (%s)
        """.formatted(placeholders(labelIds.size()));
    LinkedHashSet<RootReference> roots = new LinkedHashSet<>();
    jdbcTemplate.query(
        sql,
        (org.springframework.jdbc.core.RowCallbackHandler) resultSet ->
            addPolymorphicRoot(
                roots, resultSet.getString("target_type"), resultSet.getLong("target_id")),
        labelIds.toArray());
    return roots;
  }

  private Set<RootReference> rootsForProjects(Set<Long> projectIds) {
    LinkedHashSet<RootReference> roots = new LinkedHashSet<>();
    roots.addAll(issueRootsByColumn("project_id", projectIds));
    roots.addAll(mergeRequestRootsByColumn("target_project_id", projectIds));
    return roots;
  }

  private Set<RootReference> rootsForNamespaces(Set<Long> namespaceIds) {
    if (namespaceIds.isEmpty()) {
      return Set.of();
    }
    String sql = """
        select mr.id
          from ods_gitlab_merge_requests mr
          join ods_gitlab_projects project
            on project.id = mr.target_project_id
           and project.mirror_deleted = false
         where mr.mirror_deleted = false
           and project.namespace_id in (%s)
        """.formatted(placeholders(namespaceIds.size()));
    return queryRoots(sql, RootType.MERGE_REQUEST, namespaceIds);
  }

  private Set<RootReference> rootsForUsers(Set<Long> userIds) {
    if (userIds.isEmpty()) {
      return Set.of();
    }
    LinkedHashSet<RootReference> roots = new LinkedHashSet<>();
    roots.addAll(queryRoots(
        "select id from ods_gitlab_issues where mirror_deleted = false and author_id in ("
            + placeholders(userIds.size()) + ")",
        RootType.ISSUE,
        userIds));
    roots.addAll(queryRoots(
        "select issue_id as id from ods_gitlab_issue_assignees "
            + "where mirror_deleted = false and user_id in (" + placeholders(userIds.size()) + ")",
        RootType.ISSUE,
        userIds));
    roots.addAll(queryRoots(
        "select noteable_id as id from ods_gitlab_notes where mirror_deleted = false "
            + "and noteable_type = 'Issue' and author_id in (" + placeholders(userIds.size()) + ")",
        RootType.ISSUE,
        userIds));
    roots.addAll(queryRoots(
        "select id from ods_gitlab_merge_requests where mirror_deleted = false "
            + "and (author_id in (" + placeholders(userIds.size()) + ") or merge_user_id in ("
            + placeholders(userIds.size()) + "))",
        RootType.MERGE_REQUEST,
        repeated(userIds, 2)));
    roots.addAll(queryRoots(
        "select merge_request_id as id from ods_gitlab_merge_request_assignees "
            + "where mirror_deleted = false and user_id in (" + placeholders(userIds.size()) + ")",
        RootType.MERGE_REQUEST,
        userIds));
    roots.addAll(queryRoots(
        "select merge_request_id as id from ods_gitlab_merge_request_reviewers "
            + "where mirror_deleted = false and user_id in (" + placeholders(userIds.size()) + ")",
        RootType.MERGE_REQUEST,
        userIds));
    roots.addAll(queryRoots(
        "select noteable_id as id from ods_gitlab_notes where mirror_deleted = false "
            + "and noteable_type = 'MergeRequest' and author_id in ("
            + placeholders(userIds.size()) + ")",
        RootType.MERGE_REQUEST,
        userIds));
    return roots;
  }

  private Set<RootReference> issueRootsByColumn(String column, Set<Long> values) {
    return rootsByColumn("ods_gitlab_issues", column, RootType.ISSUE, values);
  }

  private Set<RootReference> mergeRequestRootsByColumn(String column, Set<Long> values) {
    return rootsByColumn("ods_gitlab_merge_requests", column, RootType.MERGE_REQUEST, values);
  }

  private Set<RootReference> rootsByColumn(
      String table, String column, RootType rootType, Set<Long> values) {
    if (values.isEmpty()) {
      return Set.of();
    }
    String sql = "select id from " + table + " where mirror_deleted = false and "
        + column + " in (" + placeholders(values.size()) + ")";
    return queryRoots(sql, rootType, values);
  }

  private Set<RootReference> queryRoots(
      String sql, RootType rootType, Iterable<Long> parameters) {
    ArrayList<Object> args = new ArrayList<>();
    parameters.forEach(args::add);
    LinkedHashSet<RootReference> roots = new LinkedHashSet<>();
    jdbcTemplate.query(
        sql,
        resultSet -> {
          long id = resultSet.getLong("id");
          if (id > 0L) {
            roots.add(new RootReference(rootType, id));
          }
        },
        args.toArray());
    return roots;
  }

  private Map<Long, RootDetails> loadRootDetails(
      String table, String projectColumn, Set<Long> rootIds) {
    if (rootIds.isEmpty()) {
      return Map.of();
    }
    String sql = "select id, " + projectColumn + " as project_id, iid from " + table
        + " where id in (" + placeholders(rootIds.size()) + ")";
    Map<Long, RootDetails> details = new java.util.LinkedHashMap<>();
    jdbcTemplate.query(
        sql,
        (org.springframework.jdbc.core.RowCallbackHandler) resultSet ->
            details.put(
                resultSet.getLong("id"),
                new RootDetails(
                    nullableLong(resultSet.getObject("project_id")),
                    nullableLong(resultSet.getObject("iid")))),
        rootIds.toArray());
    return Map.copyOf(details);
  }

  private void addPolymorphicRoots(
      Set<RootReference> roots,
      List<Map<String, Object>> rows,
      String typeColumn,
      String idColumn) {
    for (Map<String, Object> row : rows) {
      addPolymorphicRoot(roots, text(row.get(typeColumn)), longValue(row.get(idColumn)));
    }
  }

  private void addPolymorphicRoot(Set<RootReference> roots, String type, long id) {
    if (id <= 0L) {
      return;
    }
    if ("Issue".equals(type)) {
      roots.add(new RootReference(RootType.ISSUE, id));
    } else if ("MergeRequest".equals(type)) {
      roots.add(new RootReference(RootType.MERGE_REQUEST, id));
    }
  }

  private void addRoots(Set<RootReference> roots, RootType type, Set<Long> ids) {
    ids.forEach(id -> roots.add(new RootReference(type, id)));
  }

  private Set<Long> rootIds(Set<RootReference> roots, RootType type) {
    LinkedHashSet<Long> ids = new LinkedHashSet<>();
    roots.stream().filter(root -> root.type() == type).map(RootReference::id).forEach(ids::add);
    return ids;
  }

  private Set<Long> values(List<Map<String, Object>> rows, String column) {
    LinkedHashSet<Long> values = new LinkedHashSet<>();
    rows.stream().map(row -> longValue(row.get(column))).filter(value -> value > 0L).forEach(values::add);
    return values;
  }

  private List<Long> repeated(Set<Long> values, int repetitions) {
    ArrayList<Long> repeated = new ArrayList<>(values.size() * repetitions);
    for (int index = 0; index < repetitions; index++) {
      repeated.addAll(values);
    }
    return repeated;
  }

  private String placeholders(int count) {
    return String.join(", ", java.util.Collections.nCopies(count, "?"));
  }

  private Long nullableLong(Object value) {
    long converted = longValue(value);
    return converted <= 0L ? null : converted;
  }

  private long longValue(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value == null || String.valueOf(value).isBlank()) {
      return 0L;
    }
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (NumberFormatException ignored) {
      return 0L;
    }
  }

  private String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private enum RootType {
    ISSUE,
    MERGE_REQUEST
  }

  private record RootReference(RootType type, long id) {}

  private record RootDetails(Long projectId, Long iid) {
    private static RootDetails empty() {
      return new RootDetails(null, null);
    }
  }
}
