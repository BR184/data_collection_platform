package com.data.collection.platform.bi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.bi.application.BiCustomerIssuePageService;
import com.data.collection.platform.bi.domain.model.BiDownloadScope;
import com.data.collection.platform.bi.domain.model.BiDownloadScope.MemberSelection;
import com.data.collection.platform.bi.domain.model.BiDownloadScope.RangeType;
import com.data.collection.platform.bi.domain.port.BiCurrentSourceVersionPort;
import com.data.collection.platform.bi.domain.port.BiProductVersionPort;
import com.data.collection.platform.bi.domain.source.BiProductVersionScope;
import com.data.collection.platform.common.exception.BizException;
import java.util.List;
import org.junit.jupiter.api.Test;

class BiDownloadAuthorizationServiceTest {
  @Test
  void authorizesOnlyRegisteredTemplateWithCurrentSourceVersion() {
    BiProductVersionScope scope = new BiProductVersionScope(
        10L, 9L, "CC2026R4", "CC2026R4", 1, List.of());
    BiProductVersionPort versions = mock(BiProductVersionPort.class);
    BiCurrentSourceVersionPort sourceVersions = mock(BiCurrentSourceVersionPort.class);
    when(versions.requireScope(10L)).thenReturn(scope);
    when(sourceVersions.current("system-test", scope)).thenReturn("issue-version-3");
    BiCustomerIssuePageService customerIssues = mock(BiCustomerIssuePageService.class);
    BiDownloadAuthorizationService service = new BiDownloadAuthorizationService(
        versions, sourceVersions, customerIssues);

    var result = service.authorize(new BiDownloadAuthorizationService.Request(
        BiDownloadScope.productVersion(10L), "system-test", "system-test-module-overlay", "overlay-category-bar", "issue-version-3"));

    assertThat(result.authorized()).isTrue();
    assertThatThrownBy(() -> service.authorize(new BiDownloadAuthorizationService.Request(
        BiDownloadScope.productVersion(10L), "system-test", "system-test-module-overlay", "coding-trend-combo", "issue-version-3")))
        .isInstanceOf(BizException.class).hasMessageContaining("不支持");
    assertThatThrownBy(() -> service.authorize(new BiDownloadAuthorizationService.Request(
        BiDownloadScope.productVersion(10L), "system-test", "system-test-module-overlay", "overlay-category-bar", "issue-version-2")))
        .isInstanceOf(BizException.class).hasMessageContaining("刷新整页");
    assertThatThrownBy(() -> service.authorize(new BiDownloadAuthorizationService.Request(
        BiDownloadScope.productVersion(10L), "system-test", "coding-submission-trend", "overlay-category-bar", "issue-version-3")))
        .isInstanceOf(BizException.class).hasMessageContaining("不支持");
    assertThatThrownBy(() -> service.authorize(new BiDownloadAuthorizationService.Request(
        BiDownloadScope.customerIssue("customer-milestone", java.time.LocalDate.of(2026, 9, 24),
            MemberSelection.all(), MemberSelection.all(), MemberSelection.all()),
        "system-test", "system-test-module-overlay", "overlay-category-bar", "issue-version-3")))
        .isInstanceOf(BizException.class).hasMessageContaining("不支持");
  }

  @Test
  void authorizesCustomerPageAgainstFrozenRangeAndCurrentPageVersion() {
    BiProductVersionPort versions = mock(BiProductVersionPort.class);
    BiCurrentSourceVersionPort sourceVersions = mock(BiCurrentSourceVersionPort.class);
    BiCustomerIssuePageService customerIssues = mock(BiCustomerIssuePageService.class);
    BiDownloadAuthorizationService service = new BiDownloadAuthorizationService(
        versions, sourceVersions, customerIssues);
    var scope = BiDownloadScope.customerIssue(
        "customer-milestone",
        java.time.LocalDate.of(2026, 9, 24),
        MemberSelection.value("missing"),
        MemberSelection.all(),
        MemberSelection.missing());
    when(customerIssues.validateDownloadContext(
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.eq("customer-source-v7"),
        org.mockito.ArgumentMatchers.eq(java.time.LocalDate.of(2026, 9, 24))))
        .thenReturn("客户问题 / customer-milestone / missing / 2026-09-24");

    var authorization = service.authorize(new BiDownloadAuthorizationService.Request(
        scope, "customer-issues", "customer-issue-daily-trend", "daily-defect-trend", "customer-source-v7"));

    assertThat(authorization.authorized()).isTrue();
    assertThat(authorization.rangeDescription()).contains("customer-milestone", "2026-09-24");
    org.mockito.Mockito.verify(customerIssues).validateDownloadContext(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("customer-source-v7"),
        org.mockito.ArgumentMatchers.eq(java.time.LocalDate.of(2026, 9, 24)));
    org.mockito.Mockito.verifyNoInteractions(sourceVersions);
  }

  @Test
  void scopeSeparatesProductVersionAndCustomerIssueShapes() {
    assertThat(BiDownloadScope.productVersion(10L).rangeType()).isEqualTo(RangeType.PRODUCT_VERSION);
    assertThatThrownBy(() -> new BiDownloadScope(
        RangeType.CUSTOMER_ISSUE, 10L, "customer-milestone", java.time.LocalDate.of(2026, 9, 24),
        MemberSelection.all(), MemberSelection.all(), MemberSelection.all()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BiDownloadScope(
        RangeType.CUSTOMER_ISSUE, null, "customer-milestone", java.time.LocalDate.of(2026, 9, 24),
        new MemberSelection(BiDownloadScope.SelectionKind.VALUE, null), MemberSelection.all(), MemberSelection.all()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void registersEverySixStageProductChartInstanceWithItsTemplate() {
    BiProductVersionScope scope = new BiProductVersionScope(
        10L, 9L, "CC2026R4", "CC2026R4", 1, List.of());
    BiProductVersionPort versions = mock(BiProductVersionPort.class);
    BiCurrentSourceVersionPort sourceVersions = mock(BiCurrentSourceVersionPort.class);
    BiCustomerIssuePageService customerIssues = mock(BiCustomerIssuePageService.class);
    when(versions.requireScope(10L)).thenReturn(scope);
    when(sourceVersions.current(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
        .thenReturn("version-1");
    BiDownloadAuthorizationService service = new BiDownloadAuthorizationService(
        versions, sourceVersions, customerIssues);
    List<ChartCase> charts = List.of(
        new ChartCase("requirements", "requirements-categories", "distribution-donut"),
        new ChartCase("requirements", "requirements-module-quality", "review-quality-dual-panel"),
        new ChartCase("requirements", "requirements-review-scatter", "review-quality-scatter"),
        new ChartCase("design", "design-categories", "distribution-donut"),
        new ChartCase("design", "design-module-quality", "review-quality-dual-panel"),
        new ChartCase("design", "design-review-scatter", "review-quality-scatter"),
        new ChartCase("coding", "coding-module-review-quality", "review-quality-dual-panel"),
        new ChartCase("coding", "coding-review-categories", "distribution-donut"),
        new ChartCase("coding", "coding-contributors", "vertical-category-bar"),
        new ChartCase("coding", "coding-module-increments", "vertical-category-bar"),
        new ChartCase("coding", "coding-quality-trend", "quality-trend-small-multiples"),
        new ChartCase("coding", "coding-code-trend", "coding-trend-combo"),
        new ChartCase("coding", "coding-submission-trend", "submission-trend-combo"),
        new ChartCase("coding", "coding-review-scatter", "review-quality-scatter"),
        new ChartCase("unit-test", "unit-test-module-attainment", "test-quality-attainment"),
        new ChartCase("unit-test", "unit-test-function-attainment", "test-quality-attainment"),
        new ChartCase("integration-test", "integration-test-module-attainment", "test-quality-attainment"),
        new ChartCase("integration-test", "integration-test-function-attainment", "test-quality-attainment"),
        new ChartCase("system-test", "system-test-rounds", "quality-round-track"),
        new ChartCase("system-test", "system-test-severity-distribution", "distribution-donut"),
        new ChartCase("system-test", "system-test-module-repair", "module-repair-matrix"),
        new ChartCase("system-test", "system-test-delay-analysis", "delay-heatmap"),
        new ChartCase("system-test", "system-test-module-severity", "stacked-category-bar"),
        new ChartCase("system-test", "system-test-module-overlay", "overlay-category-bar"),
        new ChartCase("system-test", "system-test-cause-categories", "distribution-donut"),
        new ChartCase("system-test", "system-test-cause-subcategories", "vertical-category-bar"),
        new ChartCase("system-test", "system-test-assignee-workload", "developer-workload"));

    for (ChartCase chart : charts) {
      assertThat(service.authorize(new BiDownloadAuthorizationService.Request(
          BiDownloadScope.productVersion(10L), chart.pageKey(), chart.chartInstanceId(),
          chart.templateId(), "version-1")).authorized()).isTrue();
    }
  }

  @Test
  void registersAllEightCustomerChartsOnlyForCustomerIssueScope() {
    BiCustomerIssuePageService customerIssues = mock(BiCustomerIssuePageService.class);
    when(customerIssues.validateDownloadContext(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("customer-v1"),
        org.mockito.ArgumentMatchers.eq(java.time.LocalDate.of(2026, 9, 24))))
        .thenReturn("客户问题范围 customer-v1");
    BiDownloadAuthorizationService service = new BiDownloadAuthorizationService(
        mock(BiProductVersionPort.class), mock(BiCurrentSourceVersionPort.class), customerIssues);
    var scope = BiDownloadScope.customerIssue(
        "mile-1", java.time.LocalDate.of(2026, 9, 24),
        MemberSelection.all(), MemberSelection.all(), MemberSelection.all());
    List<ChartCase> charts = List.of(
        new ChartCase("customer-issues", "customer-issue-module-defects", "stacked-category-bar"),
        new ChartCase("customer-issues", "customer-issue-severity-distribution", "distribution-donut"),
        new ChartCase("customer-issues", "customer-issue-module-severity", "stacked-category-bar"),
        new ChartCase("customer-issues", "customer-issue-cause-distribution", "vertical-category-bar"),
        new ChartCase("customer-issues", "customer-issue-delay-analysis", "delay-heatmap"),
        new ChartCase("customer-issues", "customer-issue-assignee-workload", "developer-workload"),
        new ChartCase("customer-issues", "customer-issue-module-demand", "stacked-category-bar"),
        new ChartCase("customer-issues", "customer-issue-daily-trend", "daily-defect-trend"));

    for (ChartCase chart : charts) {
      assertThat(service.authorize(new BiDownloadAuthorizationService.Request(
          scope, chart.pageKey(), chart.chartInstanceId(), chart.templateId(), "customer-v1")).authorized())
          .isTrue();
    }
  }

  private record ChartCase(String pageKey, String chartInstanceId, String templateId) {}
}
