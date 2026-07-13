package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.analytics.AnalyticsDashboardResponse;
import com.data.collection.platform.service.analytics.AnalyticsDashboardProvider;
import com.data.collection.platform.service.analytics.AnalyticsDashboardQueryContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CodeReviewMultiBoardAnalyticsProviderTest {

  private final CodeReviewMultiBoardAnalyticsQueryService queryService =
      mock(CodeReviewMultiBoardAnalyticsQueryService.class);
  private final CodeReviewMultiBoardAnalyticsProvider provider =
      new CodeReviewMultiBoardAnalyticsProvider(queryService);

  @Test
  void exposesAllEightLegacyTopicsWithRulesDetailsAndExports() {
    CodeReviewMultiBoardProjectScope projectScope = new CodeReviewMultiBoardProjectScope(
        "cc",
        "CC2026R3",
        CodeReviewDataReadMode.FORMAL,
        new QualityBoardCodeReviewReadScope(
            true,
            "merge_request_fact",
            "cc",
            List.of("CC2026R3"),
            "project_id, merge_request_id",
            " and deleted = false",
            "project_id = ?",
            List.of(9L)));
    when(queryService.resolveProjectScope(
            "cc", "CC2026R3", CodeReviewDataReadMode.FORMAL))
        .thenReturn(projectScope);
    for (CodeReviewMultiBoardTopic topic : CodeReviewMultiBoardTopic.values()) {
      when(queryService.loadRows(topic, projectScope))
          .thenReturn(List.of(new CodeReviewMultiBoardAnalyticsRow("张三", BigDecimal.ONE)));
    }

    AnalyticsDashboardResponse dashboard = provider.loadDashboard(context("cc", "CC2026R3"));

    assertThat(dashboard.charts()).hasSize(8);
    assertThat(dashboard.charts())
        .extracting(AnalyticsDashboardResponse.Chart::key)
        .containsExactly(
            "module-defect-density",
            "reviewer-defect-density",
            "reviewer-fixed-defects",
            "assignee-fixed-defects",
            "author-defect-density",
            "merge-request-count",
            "code-submission-frequency",
            "code-submission-defect-density");
    assertThat(dashboard.charts()).allSatisfy(chart -> {
      assertThat(chart.ruleKey()).isNotBlank();
      assertThat(chart.detail()).isNotNull();
      assertThat(chart.export()).isNotNull();
    });
    assertThat(provider.loadRules(context("cc", "CC2026R3")).rules()).hasSize(8);
    assertThat(provider.detailViewKeys()).containsExactly("code-review-statistics");
    assertThat(provider.exportKeys()).hasSize(8);
  }

  @Test
  void delegatesReadModeToServerSideCodeReviewSetting() {
    assertThat(provider.readModeSource())
        .isEqualTo(AnalyticsDashboardProvider.ReadModeSource.CODE_REVIEW_SETTING);
    assertThat(provider.dashboardParameterKeys()).containsExactlyInAnyOrder("source", "projectName");
  }

  private AnalyticsDashboardQueryContext context(String source, String projectName) {
    return AnalyticsDashboardQueryContext.of(
        Map.of("source", source, "projectName", projectName));
  }
}
