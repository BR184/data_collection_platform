package com.data.collection.platform.bi.application;

import com.data.collection.platform.bi.domain.model.BiDownloadScope;
import com.data.collection.platform.bi.domain.model.BiDownloadScope.MemberSelection;
import com.data.collection.platform.bi.domain.model.BiDownloadScope.RangeType;
import com.data.collection.platform.bi.domain.port.BiCatContractUnavailableException;
import com.data.collection.platform.bi.domain.port.BiCurrentSourceVersionPort;
import com.data.collection.platform.bi.domain.port.BiProductVersionPort;
import com.data.collection.platform.bi.domain.source.BiProductVersionScope;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.service.CustomerIssueFactQueryService;
import java.util.Set;

/** 校验 BI 图表下载的页面、实例、模板、范围和当前来源版本。 */
public final class BiDownloadAuthorizationService {
  private static final Set<ChartRegistration> CHARTS = Set.of(
      chart(RangeType.PRODUCT_VERSION, "requirements", "requirements-categories", "distribution-donut"),
      chart(RangeType.PRODUCT_VERSION, "requirements", "requirements-module-quality", "review-quality-dual-panel"),
      chart(RangeType.PRODUCT_VERSION, "requirements", "requirements-review-scatter", "review-quality-scatter"),
      chart(RangeType.PRODUCT_VERSION, "design", "design-categories", "distribution-donut"),
      chart(RangeType.PRODUCT_VERSION, "design", "design-module-quality", "review-quality-dual-panel"),
      chart(RangeType.PRODUCT_VERSION, "design", "design-review-scatter", "review-quality-scatter"),
      chart(RangeType.PRODUCT_VERSION, "coding", "coding-module-review-quality", "review-quality-dual-panel"),
      chart(RangeType.PRODUCT_VERSION, "coding", "coding-review-categories", "distribution-donut"),
      chart(RangeType.PRODUCT_VERSION, "coding", "coding-contributors", "vertical-category-bar"),
      chart(RangeType.PRODUCT_VERSION, "coding", "coding-module-increments", "vertical-category-bar"),
      chart(RangeType.PRODUCT_VERSION, "coding", "coding-quality-trend", "quality-trend-small-multiples"),
      chart(RangeType.PRODUCT_VERSION, "coding", "coding-code-trend", "coding-trend-combo"),
      chart(RangeType.PRODUCT_VERSION, "coding", "coding-submission-trend", "submission-trend-combo"),
      chart(RangeType.PRODUCT_VERSION, "coding", "coding-review-scatter", "review-quality-scatter"),
      chart(RangeType.PRODUCT_VERSION, "unit-test", "unit-test-module-attainment", "test-quality-attainment"),
      chart(RangeType.PRODUCT_VERSION, "unit-test", "unit-test-function-attainment", "test-quality-attainment"),
      chart(RangeType.PRODUCT_VERSION, "integration-test", "integration-test-module-attainment", "test-quality-attainment"),
      chart(RangeType.PRODUCT_VERSION, "integration-test", "integration-test-function-attainment", "test-quality-attainment"),
      chart(RangeType.PRODUCT_VERSION, "system-test", "system-test-rounds", "quality-round-track"),
      chart(RangeType.PRODUCT_VERSION, "system-test", "system-test-severity-distribution", "distribution-donut"),
      chart(RangeType.PRODUCT_VERSION, "system-test", "system-test-module-repair", "module-repair-matrix"),
      chart(RangeType.PRODUCT_VERSION, "system-test", "system-test-delay-analysis", "delay-heatmap"),
      chart(RangeType.PRODUCT_VERSION, "system-test", "system-test-module-severity", "stacked-category-bar"),
      chart(RangeType.PRODUCT_VERSION, "system-test", "system-test-module-overlay", "overlay-category-bar"),
      chart(RangeType.PRODUCT_VERSION, "system-test", "system-test-cause-categories", "distribution-donut"),
      chart(RangeType.PRODUCT_VERSION, "system-test", "system-test-cause-subcategories", "vertical-category-bar"),
      chart(RangeType.PRODUCT_VERSION, "system-test", "system-test-assignee-workload", "developer-workload"),
      chart(RangeType.CUSTOMER_ISSUE, "customer-issues", "customer-issue-module-defects", "stacked-category-bar"),
      chart(RangeType.CUSTOMER_ISSUE, "customer-issues", "customer-issue-severity-distribution", "distribution-donut"),
      chart(RangeType.CUSTOMER_ISSUE, "customer-issues", "customer-issue-module-severity", "stacked-category-bar"),
      chart(RangeType.CUSTOMER_ISSUE, "customer-issues", "customer-issue-cause-distribution", "vertical-category-bar"),
      chart(RangeType.CUSTOMER_ISSUE, "customer-issues", "customer-issue-delay-analysis", "delay-heatmap"),
      chart(RangeType.CUSTOMER_ISSUE, "customer-issues", "customer-issue-assignee-workload", "developer-workload"),
      chart(RangeType.CUSTOMER_ISSUE, "customer-issues", "customer-issue-module-demand", "stacked-category-bar"),
      chart(RangeType.CUSTOMER_ISSUE, "customer-issues", "customer-issue-daily-trend", "daily-defect-trend"));

  private final BiProductVersionPort versionPort;
  private final BiCurrentSourceVersionPort sourceVersionPort;
  private final BiCustomerIssuePageService customerIssuePage;

  public BiDownloadAuthorizationService(
      BiProductVersionPort versionPort,
      BiCurrentSourceVersionPort sourceVersionPort,
      BiCustomerIssuePageService customerIssuePage) {
    this.versionPort = versionPort;
    this.sourceVersionPort = sourceVersionPort;
    this.customerIssuePage = customerIssuePage;
  }

  /** 校验下载身份与完整页面范围，并返回工作簿可读范围说明。 */
  public Authorization authorize(Request request) {
    BiDownloadScope scope = request.scope();
    ChartRegistration registration = chart(
        scope.rangeType(), request.pageKey(), request.chartInstanceId(), request.chartTemplateId());
    if (!CHARTS.contains(registration)) {
      throw new BizException("当前页面不支持该 BI 图表下载组合");
    }
    if (request.sourceVersion() == null || request.sourceVersion().isBlank()) {
      throw new BizException("下载前必须提供当前页面来源版本");
    }

    String rangeDescription;
    if (scope.rangeType() == RangeType.PRODUCT_VERSION) {
      BiProductVersionScope productScope = versionPort.requireScope(scope.productVersionId());
      try {
        String current = sourceVersionPort.current(request.pageKey(), productScope);
        if (!request.sourceVersion().equals(current)) {
          throw new BizException("页面已有新数据，请刷新整页后再下载");
        }
      } catch (BiCatContractUnavailableException unavailable) {
        throw new BizException(unavailable.getMessage() + "，当前页面不能下载");
      }
      rangeDescription = "产品版本：" + productScope.displayName()
          + "  |  来源版本：" + request.sourceVersion();
    } else {
      rangeDescription = customerIssuePage.validateDownloadContext(
          customerQuery(scope), request.sourceVersion(), scope.businessDate());
    }
    return new Authorization(
        true, scope, request.pageKey(), request.chartInstanceId(), request.chartTemplateId(),
        request.sourceVersion(), rangeDescription);
  }

  private static BiCustomerIssuePageService.Query customerQuery(BiDownloadScope scope) {
    return new BiCustomerIssuePageService.Query(
        scope.milestoneBusinessKey(),
        factSelection(scope.customer()),
        factSelection(scope.module()),
        factSelection(scope.function()));
  }

  private static CustomerIssueFactQueryService.MemberSelection factSelection(MemberSelection selection) {
    return switch (selection.kind()) {
      case ALL -> CustomerIssueFactQueryService.MemberSelection.all();
      case MISSING -> CustomerIssueFactQueryService.MemberSelection.missing();
      case VALUE -> CustomerIssueFactQueryService.MemberSelection.of(selection.value());
    };
  }

  private static ChartRegistration chart(
      RangeType rangeType, String pageKey, String chartInstanceId, String chartTemplateId) {
    return new ChartRegistration(rangeType, pageKey, chartInstanceId, chartTemplateId);
  }

  /** 一张图表的一次下载授权请求。 */
  public record Request(
      BiDownloadScope scope,
      String pageKey,
      String chartInstanceId,
      String chartTemplateId,
      String sourceVersion) {}

  /** 授权结果携带已校验范围，Excel 元信息不再假设所有页面都有产品版本。 */
  public record Authorization(
      boolean authorized,
      BiDownloadScope scope,
      String pageKey,
      String chartInstanceId,
      String chartTemplateId,
      String sourceVersion,
      String rangeDescription) {}

  private record ChartRegistration(
      RangeType rangeType, String pageKey, String chartInstanceId, String chartTemplateId) {}
}
