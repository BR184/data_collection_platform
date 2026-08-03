package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.WhitelistMode;
import java.util.List;
import org.junit.jupiter.api.Test;

class RealtimeWorkspaceDependencyCatalogTest {
  @Test
  void test_issue_workspaces_require_issue_fact_and_its_source_tables() {
    GitlabSyncConfig config = new GitlabSyncConfig();

    RealtimeWorkspaceDependencyCatalog.Requirement customerIssue =
        RealtimeWorkspaceDependencyCatalog.resolve(
            "customer-issue-cc-product-records", config);
    RealtimeWorkspaceDependencyCatalog.WorkspaceDependency systemTest =
        RealtimeWorkspaceDependencyCatalog.require("system-test-defect-summary");

    assertThat(customerIssue.factRefreshRequired()).isTrue();
    assertThat(customerIssue.factTypes()).containsExactly(FactType.ISSUE);
    assertThat(systemTest.factTypes()).containsExactly(FactType.ISSUE);
    assertThat(systemTest.sourceTables()).contains("issues", "label_links", "labels");
  }

  @Test
  void test_custom_merge_request_source_does_not_enable_issue_workspaces() {
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
            "labels",
            "resource_label_events"));

    RealtimeWorkspaceDependencyCatalog.Requirement customerIssue =
        RealtimeWorkspaceDependencyCatalog.resolve(
            "customer-issue-cc-product-records", config);
    RealtimeWorkspaceDependencyCatalog.Requirement codeReview =
        RealtimeWorkspaceDependencyCatalog.resolve("code-review-illegal-records", config);

    assertThat(customerIssue.factRefreshRequired()).isFalse();
    assertThat(customerIssue.factTypes()).isEmpty();
    assertThat(codeReview.factRefreshRequired()).isTrue();
    assertThat(codeReview.factTypes()).containsExactly(FactType.MERGE_REQUEST);
  }
}
