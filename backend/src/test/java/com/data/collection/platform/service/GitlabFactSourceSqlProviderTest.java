package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GitlabFactSourceSqlProviderTest {
  private final GitlabFactSourceSqlProvider provider = new GitlabFactSourceSqlProvider();

  @Test
  void shouldProvideIssueSqlWithMilestoneAndFallbackWithoutMilestoneJoin() {
    assertThat(provider.issueSourceSql())
        .contains("from ods_gitlab_issues i")
        .contains("left join ods_gitlab_milestones milestone")
        .contains("coalesce(milestone.title, '') as milestone_title")
        .contains("ll.target_type = 'Issue'");

    assertThat(provider.issueSourceSqlFallback())
        .contains("from ods_gitlab_issues i")
        .doesNotContain("ods_gitlab_milestones")
        .contains("'' as milestone_title")
        .contains("ll.target_type = 'Issue'");
  }

  @Test
  void shouldProvideMergeRequestSqlWithImportedMetricsAndFormRecords() {
    assertThat(provider.mergeRequestSourceSql())
        .contains("from ods_gitlab_merge_requests mr")
        .contains("from code_review_external_metrics m")
        .contains("from collect_form_records f")
        .contains("ll.target_type = 'MergeRequest'");
  }
}
