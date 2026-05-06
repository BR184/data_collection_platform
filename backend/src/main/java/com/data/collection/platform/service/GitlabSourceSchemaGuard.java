package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class GitlabSourceSchemaGuard {
  private static final List<SourceTableRequirement> ISSUE_FACT_SOURCE =
      List.of(
          requirement(
              "ods_gitlab_issues",
              "id",
              "iid",
              "project_id",
              "title",
              "author_id",
              "created_at",
              "updated_at",
              "closed_at",
              "state_id",
              "mirror_deleted"),
          requirement("ods_gitlab_projects", "id", "name", "mirror_deleted"),
          requirement("ods_gitlab_users", "id", "name", "mirror_deleted"),
          requirement(
              "ods_gitlab_label_links",
              "label_id",
              "target_id",
              "target_type",
              "mirror_deleted"),
          requirement("ods_gitlab_labels", "id", "title", "mirror_deleted"),
          requirement(
              "ods_gitlab_notes",
              "id",
              "noteable_id",
              "noteable_type",
              "note",
              "created_at",
              "updated_at",
              "mirror_deleted"));

  private static final List<SourceTableRequirement> MERGE_REQUEST_FACT_SOURCE =
      List.of(
          requirement(
              "ods_gitlab_merge_requests",
              "id",
              "iid",
              "target_project_id",
              "title",
              "author_id",
              "merge_user_id",
              "target_branch",
              "source_branch",
              "created_at",
              "updated_at",
              "mirror_deleted"),
          requirement(
              "ods_gitlab_merge_request_metrics",
              "merge_request_id",
              "merged_at",
              "added_lines",
              "mirror_deleted"),
          requirement(
              "ods_gitlab_projects", "id", "name", "path", "namespace_id", "mirror_deleted"),
          requirement("ods_gitlab_namespaces", "id", "path", "mirror_deleted"),
          requirement("ods_gitlab_users", "id", "name", "mirror_deleted"),
          requirement(
              "ods_gitlab_merge_request_reviewers",
              "merge_request_id",
              "user_id",
              "mirror_deleted"),
          requirement(
              "ods_gitlab_merge_request_assignees",
              "merge_request_id",
              "user_id",
              "mirror_deleted"),
          requirement(
              "ods_gitlab_label_links",
              "id",
              "label_id",
              "target_id",
              "target_type",
              "source_updated_at",
              "updated_at",
              "created_at",
              "mirror_deleted"),
          requirement("ods_gitlab_labels", "id", "title", "mirror_deleted"));

  private static final List<SourceTableRequirement> INTEGRATION_TEST_SOURCE = ISSUE_FACT_SOURCE;

  private final JdbcTemplate jdbcTemplate;

  public GitlabSourceSchemaGuard(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void verifyIssueFactSource() {
    verify("议题事实", ISSUE_FACT_SOURCE);
  }

  public void verifyMergeRequestFactSource() {
    verify("合并请求事实", MERGE_REQUEST_FACT_SOURCE);
  }

  public void verifyIntegrationTestSource() {
    verify("集成测试事实", INTEGRATION_TEST_SOURCE);
  }

  private void verify(String scopeName, List<SourceTableRequirement> requirements) {
    List<String> missing = new ArrayList<>();
    for (SourceTableRequirement requirement : requirements) {
      Set<String> actualColumns = loadColumns(requirement.tableName());
      if (actualColumns.isEmpty()) {
        missing.add("缺少源表 " + requirement.tableName());
        continue;
      }
      for (String column : requirement.requiredColumns()) {
        if (!actualColumns.contains(column.toLowerCase(Locale.ROOT))) {
          missing.add("缺少源字段 " + requirement.tableName() + "." + column);
        }
      }
    }
    if (!missing.isEmpty()) {
      throw new BizException("GitLab 源表结构不完整，无法构建" + scopeName + "：" + String.join("；", missing));
    }
  }

  private Set<String> loadColumns(String tableName) {
    try {
      List<String> rows =
          jdbcTemplate.queryForList(
              """
              select column_name
                from information_schema.columns
               where table_schema = current_schema()
                 and table_name = ?
              """,
              String.class,
              tableName);
      Set<String> columns = new LinkedHashSet<>();
      for (String row : rows) {
        if (row != null) {
          columns.add(row.toLowerCase(Locale.ROOT));
        }
      }
      return columns;
    } catch (DataAccessException error) {
      throw new BizException("无法检查 GitLab 源表结构：" + rootMessage(error));
    }
  }

  private String rootMessage(DataAccessException error) {
    Throwable cause = error.getMostSpecificCause();
    String message = cause == null ? error.getMessage() : cause.getMessage();
    return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
  }

  private static SourceTableRequirement requirement(String tableName, String... requiredColumns) {
    return new SourceTableRequirement(tableName, List.of(requiredColumns));
  }

  private record SourceTableRequirement(String tableName, List<String> requiredColumns) {}
}
