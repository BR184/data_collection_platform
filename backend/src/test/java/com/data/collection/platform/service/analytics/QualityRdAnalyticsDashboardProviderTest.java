package com.data.collection.platform.service.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.QualityBoardChartRowResponse;
import com.data.collection.platform.entity.QualityBoardFixUserSeverityRowResponse;
import com.data.collection.platform.entity.QualityBoardRdDashboardResponse;
import com.data.collection.platform.entity.QualityBoardRdOverviewResponse;
import com.data.collection.platform.service.CodeReviewDataReadMode;
import com.data.collection.platform.service.CodeReviewMatchModeSwitchService;
import com.data.collection.platform.service.QualityBoardCodeReviewReadSupport;
import com.data.collection.platform.service.QualityBoardRdService;
import com.data.collection.platform.service.QualityBoardWorkbookExportService;
import com.data.collection.platform.service.ReviewDataMatchModeRecordRepository;
import com.data.collection.platform.service.ReviewDataRecordReadRepository;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class QualityRdAnalyticsDashboardProviderTest {
  private final QualityBoardRdService rdService = mock(QualityBoardRdService.class);
  private final QualityBoardWorkbookExportService workbookExportService =
      mock(QualityBoardWorkbookExportService.class);
  private final QualityRdAnalyticsDashboardProvider provider =
      new QualityRdAnalyticsDashboardProvider(rdService, workbookExportService);

  @Test
  void exposesEightMetricsFiveChartsRulesActionsAndPointFilters() {
    when(rdService.getRdDashboard("CC2026R4", "dgm", CodeReviewDataReadMode.MATCH_MODE))
        .thenReturn(sampleDashboard());
    var context = new AnalyticsDashboardQueryContext(
        Map.of("projectName", "CC2026R4", "codeReviewSource", "dgm"),
        1,
        20,
        null,
        null,
        AnalyticsDashboardQueryContext.ReadMode.MATCH_MODE);

    var dashboard = provider.loadDashboard(context);
    var rules = provider.loadRules(context);

    assertThat(provider.dashboardKey()).isEqualTo("quality-rd");
    assertThat(provider.dashboardParameterKeys())
        .containsExactlyInAnyOrder("projectName", "codeReviewSource");
    assertThat(provider.readModeSource())
        .isEqualTo(AnalyticsDashboardProvider.ReadModeSource.CODE_REVIEW_SETTING);
    assertThat(provider.ruleVersion(context)).isEqualTo("quality-rd-rules@1");
    assertThat(provider.sourceVersion(context)).contains("match-mode");
    assertThat(dashboard.metrics()).hasSize(8);
    assertThat(dashboard.charts()).hasSize(5);
    assertThat(rules.rules()).hasSize(13);

    var demandMetric = dashboard.metrics().stream()
        .filter(metric -> metric.key().equals("demand-review-density"))
        .findFirst()
        .orElseThrow();
    assertThat(demandMetric.detail().viewKey()).isEqualTo("review-data-records");
    assertThat(demandMetric.detail().params())
        .containsEntry("projectName", "CC2026R4")
        .containsEntry("reviewType", "需求说明书评审");

    var dgmMetric = dashboard.metrics().stream()
        .filter(metric -> metric.key().equals("code-review-density-dgm"))
        .findFirst()
        .orElseThrow();
    assertThat(dgmMetric.detail()).isNull();
    assertThat(dgmMetric.export().exportKey()).isEqualTo("code-review-records-dgm");

    var newIssueMetric = dashboard.metrics().stream()
        .filter(metric -> metric.key().equals("new-issue-fix-rate"))
        .findFirst()
        .orElseThrow();
    assertThat(newIssueMetric.detail().viewKey()).isEqualTo("system-test-defect-summary");
    assertThat(newIssueMetric.detail().params()).containsEntry("testingPhase", "CC2026R4");

    var authorChart = dashboard.charts().stream()
        .filter(chart -> chart.key().equals("author-defect-density"))
        .findFirst()
        .orElseThrow();
    assertThat(authorChart.detail().viewKey()).isEqualTo("quality-code-review-records");
    assertThat(authorChart.detail().params()).containsEntry("topic", "author-density");
    var series = (List<?>) authorChart.option().get("series");
    var firstSeries = (Map<?, ?>) series.getFirst();
    var data = (List<?>) firstSeries.get("data");
    var firstPoint = (Map<?, ?>) data.getFirst();
    @SuppressWarnings("unchecked")
    var detailParams = (Map<String, String>) firstPoint.get("detailParams");
    assertThat(detailParams)
        .containsEntry("authorName", "被走查人A")
        .containsEntry("topic", "author-density");

    assertThat(rules.rules()).anySatisfy(rule -> {
      assertThat(rule.key()).isEqualTo("quality-rd.release-leakage-rate");
      assertThat(rule.formula()).isEqualTo("未关闭缺陷数 / 全部有效缺陷数 × 100%");
      assertThat(rule.scope()).contains("排除已拒绝");
      assertThat(rule.target()).isEqualTo("不超过 15.00%");
    });
    assertThat(rules.rules()).anySatisfy(rule -> {
      assertThat(rule.key()).isEqualTo("quality-rd.defect-repair-user");
      assertThat(rule.scope()).contains("不排除已拒绝");
      assertThat(rule.description()).contains("空指派人不生成分组");
    });
  }

  @Test
  void exportsThroughTheSameQualityBoardWorkbookServiceAndRejectsFormalDgm() {
    when(workbookExportService.exportRdChartWorkbook(
            "CC2026R4", "cc", "frequency-code-submission", CodeReviewDataReadMode.FORMAL))
        .thenReturn(new byte[] {1, 2});
    when(workbookExportService.rdChartFilename("CC2026R4", "frequency-code-submission"))
        .thenReturn("CC2026R4代码提交频次统计.xlsx");
    var formal = AnalyticsDashboardQueryContext.of(
        Map.of("projectName", "CC2026R4", "codeReviewSource", "cc"));

    var exported = provider.export("frequency-code-submission", formal);

    assertThat(exported.filename()).isEqualTo("CC2026R4代码提交频次统计.xlsx");
    assertThat(exported.content()).containsExactly(1, 2);
    assertThatThrownBy(() -> provider.export("code-review-records-dgm", formal))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("DGM");
  }

  @Test
  void refusesToGenerateAnEmptyHtgcWorkbookWhenTheFormalSourceIsNotReady() {
    var detailQuery = mock(QualityRdAnalyticsDetailQueryService.class);
    var detailWorkbook = mock(QualityRdAnalyticsWorkbookService.class);
    when(detailQuery.htgcDataReady()).thenReturn(false);
    var fullProvider = new QualityRdAnalyticsDashboardProvider(
        rdService, workbookExportService, detailQuery, detailWorkbook);

    assertThatThrownBy(() -> fullProvider.export(
            "assignee-remaining-htgc-detail",
            AnalyticsDashboardQueryContext.of(Map.of("projectName", "CC2026R3"))))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("HTGC 数据源未就绪");
  }

  @Test
  void serviceExcludesRejectedIssuesOnlyFromLeakageRatesAndDropsBlankAssignees() {
    var jdbc = new CapturingJdbcTemplate();
    var phaseScopeResolver = mock(SystemTestPhaseScopeResolver.class);
    var formalReviews = mock(ReviewDataRecordReadRepository.class);
    var matchReviews = mock(ReviewDataMatchModeRecordRepository.class);
    var matchMode = mock(CodeReviewMatchModeSwitchService.class);
    var codeReview = mock(QualityBoardCodeReviewReadSupport.class);
    when(phaseScopeResolver.resolveLegacyCrownCadPhases("CC2026R4"))
        .thenReturn(List.of("CC2026R4系统测试"));
    when(formalReviews.loadRecords(null, "CC2026R4", null, null, null, null, null, null))
        .thenReturn(List.of());
    when(codeReview.configuredReadMode()).thenReturn(CodeReviewDataReadMode.FORMAL);
    when(codeReview.listAvailableSources(CodeReviewDataReadMode.FORMAL))
        .thenReturn(List.of(new OptionItemResponse("CC", "cc")));
    when(codeReview.personDefectDensityRows(
            "reviewer_names",
            false,
            "cc",
            "CC2026R4",
            CodeReviewDataReadMode.FORMAL))
        .thenReturn(List.of());
    when(codeReview.personDefectDensityRows(
            "author_name",
            true,
            "cc",
            "CC2026R4",
            CodeReviewDataReadMode.FORMAL))
        .thenReturn(List.of());
    when(codeReview.frequencyRows("cc", "CC2026R4", CodeReviewDataReadMode.FORMAL))
        .thenReturn(List.of());
    var service = new QualityBoardRdService(
        jdbc, phaseScopeResolver, formalReviews, matchReviews, matchMode, codeReview);

    service.getRdDashboard("CC2026R4", "cc");

    long rejectedPredicateCount = jdbc.countSqlContaining("not like '%已拒绝%'");
    assertThat(rejectedPredicateCount).isGreaterThanOrEqualTo(5);
    String assigneeSql = jdbc.sqlStatements.stream()
        .filter(sql -> sql.contains("assignee_name"))
        .findFirst()
        .orElseThrow();
    assertThat(assigneeSql)
        .contains("nullif(btrim(assignee_name), '') is not null")
        .doesNotContain("未标注指派人")
        .doesNotContain("已拒绝");
  }

  private QualityBoardRdDashboardResponse sampleDashboard() {
    var overview = new QualityBoardRdOverviewResponse(
        "CC2026R4", 0.3, 0.4, 3.2, 4.1, 92D, 10D, 91D, 88D, List.of());
    return new QualityBoardRdDashboardResponse(
        overview,
        "dgm",
        List.of(new OptionItemResponse("CC", "cc"), new OptionItemResponse("DGM", "dgm")),
        List.of(new QualityBoardChartRowResponse("走查人A", 3.1)),
        List.of(new QualityBoardChartRowResponse("被走查人A", 2.8)),
        List.of(new QualityBoardFixUserSeverityRowResponse("修复人A", 1, 2, 3, 1, 7)),
        List.of(new QualityBoardChartRowResponse("提交人A", 12D)),
        List.of(new QualityBoardChartRowResponse("指派人A", 5D)));
  }

  private static final class CapturingJdbcTemplate extends JdbcTemplate {
    private final List<String> sqlStatements = new ArrayList<>();

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
      sqlStatements.add(sql);
      return List.of();
    }

    @Override
    public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
      sqlStatements.add(sql);
      if (requiredType == Boolean.class) {
        return requiredType.cast(false);
      }
      if (requiredType == Long.class) {
        return requiredType.cast(0L);
      }
      return null;
    }

    private long countSqlContaining(String expected) {
      return sqlStatements.stream().filter(sql -> sql.contains(expected)).count();
    }
  }
}
