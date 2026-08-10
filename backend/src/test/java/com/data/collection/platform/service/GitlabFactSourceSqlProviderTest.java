package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GitlabFactSourceSqlProviderTest {
  private final GitlabFactSourceSqlProvider provider = new GitlabFactSourceSqlProvider();

  @Test
  void shouldProvideIssueSqlWithMilestoneAndFallbackWithoutMilestoneJoin() {
    assertThat(provider.issueSourceSql())
        .contains("from ods_gitlab_issues i")
        .contains("coalesce(i.description, '') as description")
        .contains("coalesce(assignees.assignee_names, '') as handler_name")
        .contains("left join ods_gitlab_milestones milestone")
        .contains("coalesce(milestone.title, '') as milestone_title")
        .contains("ll.target_type = 'Issue'");

    assertThat(provider.issueSourceSqlFallback())
        .contains("from ods_gitlab_issues i")
        .contains("coalesce(i.description, '') as description")
        .contains("coalesce(assignees.assignee_names, '') as handler_name")
        .doesNotContain("ods_gitlab_milestones")
        .contains("'' as milestone_title")
        .contains("ll.target_type = 'Issue'");
  }

  @Test
  void test_resource_label_event_sql_uses_gitlab_16_issue_identity() {
    assertThat(provider.issueSourceSql(true))
        .contains("rle.issue_id")
        .contains("rle.action = 1")
        .doesNotContain("rle.action = 'add'")
        .doesNotContain("rle.resource_id")
        .doesNotContain("rle.resource_type");
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
}
