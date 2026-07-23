package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.exception.BizException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class GitlabSourceSchemaGuardTest {
  @Mock private JdbcTemplate jdbcTemplate;

  @Test
  void shouldPassWhenIssueFactSourceTablesContainRequiredColumns() {
    stubColumns(
        Map.of(
            "ods_gitlab_issues",
            List.of(
                "id",
                "iid",
                "project_id",
                "title",
                "description",
                "author_id",
                "created_at",
                "updated_at",
                "closed_at",
                "state_id",
                "mirror_deleted"),
            "ods_gitlab_projects",
            List.of("id", "name", "mirror_deleted"),
             "ods_gitlab_users",
             List.of("id", "name", "mirror_deleted"),
             "ods_gitlab_issue_assignees",
             List.of("issue_id", "user_id", "mirror_deleted"),
             "ods_gitlab_label_links",
             List.of(
                 "label_id",
                 "target_id",
                 "target_type",
                 "mirror_deleted",
                 "created_at",
                 "updated_at"),
             "ods_gitlab_labels",
             List.of("id", "title", "color", "mirror_deleted"),
             "ods_gitlab_notes",
             List.of(
                 "id",
                 "noteable_id",
                 "noteable_type",
                 "author_id",
                 "note",
                 "created_at",
                 "updated_at",
                 "mirror_deleted")));

    assertThatCode(() -> new GitlabSourceSchemaGuard(jdbcTemplate).verifyIssueFactSource())
        .doesNotThrowAnyException();
  }

  @Test
  void shouldReportMissingSourceColumnBeforeFactBuildRunsLargeSql() {
    Map<String, List<String>> columns = new HashMap<>();
    columns.put(
        "ods_gitlab_issues",
        List.of(
            "id",
            "project_id",
            "title",
            "author_id",
            "created_at",
            "updated_at",
            "closed_at",
            "state_id",
            "mirror_deleted"));
    columns.put("ods_gitlab_projects", List.of("id", "name", "mirror_deleted"));
    columns.put("ods_gitlab_users", List.of("id", "name", "mirror_deleted"));
    columns.put("ods_gitlab_label_links", List.of("label_id", "target_id", "target_type", "mirror_deleted"));
    columns.put("ods_gitlab_labels", List.of("id", "title", "color", "mirror_deleted"));
    columns.put(
        "ods_gitlab_notes",
        List.of("id", "noteable_id", "noteable_type", "note", "created_at", "updated_at", "mirror_deleted"));
    stubColumns(columns);

    assertThatThrownBy(() -> new GitlabSourceSchemaGuard(jdbcTemplate).verifyIssueFactSource())
        .isInstanceOf(BizException.class)
        .hasMessageContaining("ods_gitlab_issues.iid");
  }

  @Test
  void shouldRejectAllFactBuildWhenAnyRequiredSourceColumnIsMissing() {
    List<String> allKnownColumns =
        List.of(
            "id",
            "iid",
            "project_id",
            "target_project_id",
            "title",
            "description",
            "author_id",
            "merge_user_id",
            "target_branch",
            "source_branch",
            "created_at",
            "updated_at",
            "source_updated_at",
            "closed_at",
            "state_id",
            "mirror_deleted",
            "name",
            "path",
            "namespace_id",
            "issue_id",
            "user_id",
            "label_id",
            "target_id",
            "target_type",
            "color",
            "noteable_id",
            "noteable_type",
            "note",
            "merge_request_id",
            "merged_at",
            "added_lines");
    when(jdbcTemplate.queryForList(anyString(), eq(String.class), any()))
        .thenAnswer(
            invocation ->
                "ods_gitlab_merge_requests".equals(invocation.getArgument(2))
                    ? allKnownColumns.stream().filter(column -> !"merge_user_id".equals(column)).toList()
                    : allKnownColumns);

    assertThatThrownBy(() -> new GitlabSourceSchemaGuard(jdbcTemplate).verifyAllFactSources("default"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("全部事实层")
        .hasMessageContaining("ods_gitlab_merge_requests.merge_user_id");
  }

  private void stubColumns(Map<String, List<String>> columnsByTable) {
    when(jdbcTemplate.queryForList(anyString(), eq(String.class), any()))
        .thenAnswer(invocation -> columnsByTable.getOrDefault(invocation.getArgument(2), List.of()));
  }
}
