package com.data.collection.platform.service.sync;

import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import java.util.List;

/** 动态 GitLab ODS 表的查询索引唯一目录。 */
public final class GitlabMirrorIndexCatalog {
  private static final String ACTIVE = "mirror_deleted = false";
  private static final List<IndexDefinition> DEFINITIONS =
      List.of(
          active(
              "idx_ods_gitlab_label_links_issue_active",
              "label_links",
              column("target_type"),
              column("target_id"),
              column("label_id")),
          active(
              "idx_ods_gitlab_labels_title_active",
              "labels",
              column("title")),
          active(
              "idx_ods_gitlab_issues_project_updated_active",
              "issues",
              column("project_id"),
              descending("updated_at")),
          active(
              "idx_ods_gitlab_notes_issue_active",
              "notes",
              column("noteable_type"),
              column("noteable_id"),
              column("updated_at"),
              column("created_at")),
          active(
              "idx_ods_gitlab_merge_requests_target_updated_active",
              "merge_requests",
              column("target_project_id"),
              descending("updated_at")),
          active(
              "idx_ods_gitlab_issues_mirror_task",
              "issues",
              column("mirror_task_id"),
              column("project_id"),
              column("iid")),
          active(
              "idx_ods_gitlab_merge_requests_mirror_task",
              "merge_requests",
              column("mirror_task_id"),
              column("target_project_id"),
              column("iid")),
          active(
              "idx_ods_gitlab_notes_mirror_task",
              "notes",
              column("mirror_task_id"),
              column("noteable_type"),
              column("noteable_id")),
          active(
              "idx_ods_gitlab_label_links_mirror_task",
              "label_links",
              column("mirror_task_id"),
              column("target_type"),
              column("target_id")),
          active(
              "idx_ods_gitlab_issue_assignees_mirror_task",
              "issue_assignees",
              column("mirror_task_id"),
              column("issue_id")),
          active(
              "idx_ods_gitlab_merge_request_metrics_mirror_task",
              "merge_request_metrics",
              column("mirror_task_id"),
              column("merge_request_id")),
          active(
              "idx_ods_gitlab_merge_request_reviewers_mirror_task",
              "merge_request_reviewers",
              column("mirror_task_id"),
              column("merge_request_id")),
          active(
              "idx_ods_gitlab_merge_request_assignees_mirror_task",
              "merge_request_assignees",
              column("mirror_task_id"),
              column("merge_request_id")),
          active(
              "idx_ods_gitlab_issues_project_root_active",
              "issues",
              column("project_id"),
              column("id")),
          active(
              "idx_ods_gitlab_issues_author_root_active",
              "issues",
              column("author_id"),
              column("id")),
          active(
              "idx_ods_gitlab_issues_milestone_root_active",
              "issues",
              column("milestone_id"),
              column("id")),
          active(
              "idx_ods_gitlab_merge_requests_project_root_active",
              "merge_requests",
              column("target_project_id"),
              column("id")),
          active(
              "idx_ods_gitlab_merge_requests_author_root_active",
              "merge_requests",
              column("author_id"),
              column("id")),
          active(
              "idx_ods_gitlab_merge_requests_merge_user_root_active",
              "merge_requests",
              column("merge_user_id"),
              column("id")),
          active(
              "idx_ods_gitlab_issue_assignees_user_root_active",
              "issue_assignees",
              column("user_id"),
              column("issue_id")),
          active(
              "idx_ods_gitlab_merge_request_assignees_user_root_active",
              "merge_request_assignees",
              column("user_id"),
              column("merge_request_id")),
          active(
              "idx_ods_gitlab_merge_request_reviewers_user_root_active",
              "merge_request_reviewers",
              column("user_id"),
              column("merge_request_id")),
          active(
              "idx_ods_gitlab_notes_author_root_active",
              "notes",
              column("author_id"),
              column("noteable_type"),
              column("noteable_id")),
          active(
              "idx_ods_gitlab_label_links_label_root_active",
              "label_links",
              column("label_id"),
              column("target_type"),
              column("target_id")),
          active(
              "idx_ods_gitlab_projects_namespace_root_active",
              "projects",
              column("namespace_id"),
              column("id")),
          conditional(
              "idx_ods_gitlab_resource_label_events_issue_fix_gitlab16",
              "resource_label_events",
              ACTIVE + " and issue_id is not null",
              List.of("mirror_deleted", "issue_id"),
              column("issue_id"),
              column("action"),
              column("label_id"),
              descending("created_at")),
          conditional(
              "idx_ods_gitlab_resource_label_events_task_issue",
              "resource_label_events",
              "issue_id is not null",
              List.of("issue_id"),
              column("mirror_task_id"),
              column("issue_id")),
          conditional(
              "idx_ods_gitlab_resource_label_events_task_mr",
              "resource_label_events",
              "merge_request_id is not null",
              List.of("merge_request_id"),
              column("mirror_task_id"),
              column("merge_request_id")));

  private GitlabMirrorIndexCatalog() {}

  /** 返回指定来源表的全部当前索引定义，顺序稳定。 */
  public static List<IndexDefinition> definitionsFor(String sourceTable) {
    String normalized = GitlabSourceInstanceSupport.normalizeSourceTableName(sourceTable);
    return DEFINITIONS.stream()
        .filter(definition -> definition.sourceTable().equals(normalized))
        .toList();
  }

  private static IndexDefinition active(
      String name, String table, IndexColumn... columns) {
    return conditional(name, table, ACTIVE, List.of("mirror_deleted"), columns);
  }

  private static IndexDefinition conditional(
      String name,
      String table,
      String predicate,
      List<String> predicateColumns,
      IndexColumn... columns) {
    return new IndexDefinition(
        name,
        GitlabSourceInstanceSupport.normalizeSourceTableName(table),
        List.of(columns),
        predicate,
        predicateColumns);
  }

  private static IndexColumn column(String name) {
    return new IndexColumn(name, false);
  }

  private static IndexColumn descending(String name) {
    return new IndexColumn(name, true);
  }

  /** 一个可验证、可幂等创建的 ODS 索引定义。 */
  public record IndexDefinition(
      String name,
      String sourceTable,
      List<IndexColumn> columns,
      String predicate,
      List<String> predicateColumns) {
    public IndexDefinition {
      if (name == null
          || !name.matches("[a-z0-9_]+")
          || sourceTable == null
          || sourceTable.isBlank()
          || columns == null
          || columns.isEmpty()
          || predicate == null
          || predicate.isBlank()) {
        throw new IllegalArgumentException("动态 ODS 索引定义不完整：" + name);
      }
      columns = List.copyOf(columns);
      predicateColumns = List.copyOf(predicateColumns);
    }

    /** 返回创建该索引所需的全部物理列。 */
    public List<String> requiredColumns() {
      return java.util.stream.Stream.concat(
              columns.stream().map(IndexColumn::name), predicateColumns.stream())
          .distinct()
          .toList();
    }
  }

  /** 索引列及其排序方向。 */
  public record IndexColumn(String name, boolean descending) {
    public IndexColumn {
      if (name == null || !name.matches("[a-z0-9_]+")) {
        throw new IllegalArgumentException("动态 ODS 索引列非法：" + name);
      }
    }
  }
}
