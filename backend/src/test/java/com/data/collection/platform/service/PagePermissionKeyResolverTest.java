package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PagePermissionKeyResolverTest {
  private final PagePermissionKeyResolver resolver = new PagePermissionKeyResolver();

  @Test
  void qualityReviewDetailKeepsReviewDataPermission() {
    assertThat(resolver.dashboardDetailViews("quality-rd", "quality-review-records"))
        .containsExactly("quality.rd.view", "review.data.view");
  }

  @Test
  void newIssueDetailKeepsSystemTestSummaryPermission() {
    assertThat(resolver.dashboardDetailViews("quality-rd", "new-issue-defects"))
        .containsExactly("quality.rd.view", "system_test.summary.view");
  }

  @Test
  void existingDashboardDetailsOnlyRequireTheDashboardPermission() {
    assertThat(resolver.dashboardDetailViews("quality-rd", "integration-test-results"))
        .containsExactly("quality.rd.view");
  }
}
