package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.WhitelistMode;
import java.util.List;
import org.junit.jupiter.api.Test;

class RealtimeWorkspaceFactRequirementResolverTest {
  @Test
  void shouldRequireIssueFactsForCustomerIssueAndSystemTestWorkspaces() {
    GitlabSyncConfig config = new GitlabSyncConfig();

    RealtimeWorkspaceFactRequirementResolver.Requirement customerIssue =
        RealtimeWorkspaceFactRequirementResolver.resolve("customer-issue-cc-product-records", config);
    RealtimeWorkspaceFactRequirementResolver.Requirement systemTest =
        RealtimeWorkspaceFactRequirementResolver.resolve("system-test-defect-summary", config);

    assertThat(customerIssue.factRefreshRequired()).isTrue();
    assertThat(customerIssue.factTypes()).containsExactly(GitlabFactRefreshRequirements.FACT_TYPE_ISSUE);
    assertThat(systemTest.factTypes()).containsExactly(GitlabFactRefreshRequirements.FACT_TYPE_ISSUE);
  }

  @Test
  void shouldNotTreatMergeRequestFactRefreshAsCustomerIssueFactRefresh() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setWhitelistMode(WhitelistMode.CUSTOM);
    config.setWhitelistTables(
        List.of(
            "merge_requests",
            "merge_request_metrics",
            "projects",
            "namespaces",
            "users",
            "merge_request_reviewers",
            "merge_request_assignees",
            "notes",
            "label_links",
            "labels"));

    RealtimeWorkspaceFactRequirementResolver.Requirement customerIssue =
        RealtimeWorkspaceFactRequirementResolver.resolve("customer-issue-cc-product-records", config);
    RealtimeWorkspaceFactRequirementResolver.Requirement codeReview =
        RealtimeWorkspaceFactRequirementResolver.resolve("code-review-illegal-records", config);

    assertThat(customerIssue.factRefreshRequired()).isFalse();
    assertThat(customerIssue.factTypes()).isEmpty();
    assertThat(codeReview.factRefreshRequired()).isTrue();
    assertThat(codeReview.factTypes())
        .containsExactly(GitlabFactRefreshRequirements.FACT_TYPE_MERGE_REQUEST);
  }
}
