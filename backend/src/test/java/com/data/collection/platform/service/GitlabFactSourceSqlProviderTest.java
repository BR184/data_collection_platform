package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class GitlabFactSourceSqlProviderTest {
  private final GitlabFactSourceSqlProvider provider = new GitlabFactSourceSqlProvider();

  @Test
  void shouldProvideIssueSqlWithProjectBoundMilestoneJoin() {
    assertThat(provider.issueSourceSql())
        .contains("from ods_gitlab_issues i")
        .contains("coalesce(i.description, '') as description")
        .contains("coalesce(assignees.assignee_names, '') as handler_name")
        .contains("left join ods_gitlab_milestones milestone")
        .contains("milestone.project_id = i.project_id")
        .contains("coalesce(milestone.title, '') as milestone_title")
        .contains("ll.target_type = 'Issue'");
  }

  @Test
  void issueFixedTimeUsesOnlyTheResourceLabelEventHistory() {
    assertThat(provider.issueSourceSql())
        .contains("from ods_gitlab_resource_label_events rle")
        .contains("rle.issue_id")
        .contains("rle.action = 1")
        .contains("max(rle.created_at) as fixed_label_time")
        .doesNotContain("rle.action = 'add'")
        .doesNotContain("rle.resource_id")
        .doesNotContain("rle.resource_type")
        .doesNotContain("max(coalesce(ll.created_at, ll.updated_at)) as fixed_label_time");
  }

  @Test
  void shouldProvideMergeRequestSqlWithImportedMetricsAndFormRecords() {
    assertThat(provider.mergeRequestSourceSql("default"))
        .contains("from ods_gitlab_merge_requests mr")
        .contains("from code_review_external_metrics m")
        .contains("from collect_form_records f")
        .contains("join ods_gitlab_notes n")
        .contains("n.noteable_type = 'MergeRequest'")
        .contains("## 代码走查数据")
        .contains("mr.state_id")
        .contains("metrics.merged_at as merged_at")
        .doesNotContain("coalesce(metrics.merged_at, mr.updated_at) as merged_at")
        .contains("as project_label_titles")
        .contains("order by coalesce(ll.source_updated_at, ll.updated_at, ll.created_at) asc nulls last, ll.id asc")
        .doesNotContain("p.name as project_name")
        .contains("code_walkthrough_date")
        .contains("ll.target_type = 'MergeRequest'");
  }

  @Test
  void test_merge_request_commit_sql_uses_only_latest_diff_and_real_commit_time() {
    assertThat(provider.mergeRequestCommitSourceSql())
        .contains(
            "from ods_gitlab_merge_request_diffs diff",
            "partition by diff.merge_request_id",
            "diff.authority_rank = 1",
            "join ods_gitlab_merge_request_diff_commits commit_row",
            "encode(commit_row.sha, 'hex') as commit_sha",
            "commit_row.committed_date as committed_at_source")
        .doesNotContain("mr.updated_at as committed_at_source")
        .doesNotContain("commits_count as");
  }

  @Test
  void test_root_scoped_issue_sql_declares_roots_first_and_narrows_every_aggregate() {
    String sql = provider.issueSourceSqlForRoots(List.of(7L, 8L, 9L));

    assertThat(sql)
        .startsWith("with target_roots(root_id) as (values (?), (?), (?)), distinct_issue_labels as (")
        .contains("and ll.target_id in (select root_id from target_roots)")
        .contains("and ia.issue_id in (select root_id from target_roots)")
        .contains("and n.noteable_id in (select root_id from target_roots)")
        .contains("and rle.issue_id in (select root_id from target_roots)")
        .contains("and i.id in (select root_id from target_roots)")
        .containsOnlyOnce("target_roots(root_id) as (values")
        .doesNotContain("__");
  }

  @Test
  void test_root_scoped_merge_request_sql_narrows_every_aggregate() {
    String sql = provider.mergeRequestSourceSqlForRoots("default", List.of(7L));

    assertThat(sql)
        .startsWith("with target_roots(root_id) as (values (?)), reviewer_names as (")
        .contains("and mr.merge_request_id in (select root_id from target_roots)")
        .contains("and ma.merge_request_id in (select root_id from target_roots)")
        .contains("and ll.target_id in (select root_id from target_roots)")
        .contains("and id in (select root_id from target_roots)")
        .contains("and mr.id in (select root_id from target_roots)")
        .contains("lower('default')")
        .doesNotContain("__");
  }

  @Test
  void test_root_scoped_commit_sql_narrows_ranked_diff_window() {
    String sql = provider.mergeRequestCommitSourceSqlForRoots(List.of(7L));

    assertThat(sql)
        .startsWith("with target_roots(root_id) as (values (?)), ranked_diffs as (")
        .contains("and diff.merge_request_id in (select root_id from target_roots)")
        .contains("diff.authority_rank = 1")
        .doesNotContain("__");
  }

  @Test
  void test_root_scoped_sql_requires_a_non_empty_root_set() {
    assertThatThrownBy(() -> provider.issueSourceSqlForRoots(List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("稳定根集合");
    assertThatThrownBy(() -> provider.mergeRequestSourceSqlForRoots("default", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("稳定根集合");
    assertThatThrownBy(() -> provider.mergeRequestCommitSourceSqlForRoots(List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("稳定根集合");
  }
}
