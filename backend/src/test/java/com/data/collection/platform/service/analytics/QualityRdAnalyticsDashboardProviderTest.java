package com.data.collection.platform.service.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.QualityBoardChartRowResponse;
import com.data.collection.platform.entity.QualityBoardFixUserSeverityRowResponse;
import com.data.collection.platform.entity.QualityBoardRdDashboardResponse;
import com.data.collection.platform.entity.QualityBoardRdOverviewResponse;
import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardResponse;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardRulesResponse;
import com.data.collection.platform.service.CodeReviewDataReadMode;
import com.data.collection.platform.service.QualityBoardCodeReviewReadSupport;
import com.data.collection.platform.service.QualityBoardRdService;
import com.data.collection.platform.service.QualityBoardWorkbookExportService;
import com.data.collection.platform.service.ReviewDataMatchModeRecordRepository;
import com.data.collection.platform.service.ReviewDataRecordReadRepository;
import com.data.collection.platform.service.dashboard.DashboardVersionScopeService;
import com.data.collection.platform.service.dashboard.DashboardVersionScopeTestSupport;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
  private final DashboardVersionScopeService versionScopeService =
      DashboardVersionScopeTestSupport.service(
          DashboardVersionScopeTestSupport.group(
              40L,
              "CC2026R4",
              1,
              DashboardVersionScopeTestSupport.member(401L, "CC2026R4系统测试", 1)));
  private QualityRdAnalyticsDashboardProvider provider;

  @org.junit.jupiter.api.BeforeEach
  void bindProviderFixture() {
    var selection = com.data.collection.platform.service.read.TestReadSelections.datasets();
    org.mockito.Mockito.lenient().when(rdService.forRead(selection)).thenReturn(rdService);
    org.mockito.Mockito.lenient().when(workbookExportService.forRead(selection)).thenReturn(workbookExportService);
    var details = mock(QualityRdAnalyticsDetailQueryService.class);
    org.mockito.Mockito.lenient().when(details.forRead(selection)).thenReturn(details);
    provider = new QualityRdAnalyticsDashboardProvider(rdService, workbookExportService, details,
        mock(QualityRdAnalyticsWorkbookService.class), versionScopeService).forRead(selection);
  }

  @Test
  void exposesEightMetricsFiveChartsRulesActionsAndPointFilters() {
    when(rdService.normalizeProjectName("CC2026R4")).thenReturn("CC2026R4");
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
    assertThat(provider.ruleVersion(context)).isEqualTo("quality-rd-rules@3");
    assertThat(provider.sourceVersion(context)).startsWith(com.data.collection.platform.service.read.TestReadSelections.datasets().sourceVersion() + "|scope:");
    assertThat(dashboard.metrics()).hasSize(8);
    assertThat(dashboard.charts()).hasSize(5);
    assertThat(rules.rules()).hasSize(13);
    assertThat(rules.rules())
        .allSatisfy(rule -> assertThat(String.valueOf(rule.description()))
            .doesNotContain("兼容模式", "Match mode", "老平台"));
    assertThat(scopeOf(rules, "quality-rd.integration-pass-rate"))
        .isEqualTo("CC2026R4集成测试；逐条通过率先保留两位小数后再取算术平均");
    assertThat(scopeOf(rules, "quality-rd.development-leakage-rate"))
        .isEqualTo("CC2026R4 的集成测试与系统测试阶段；系统测试有效缺陷排除已拒绝记录");

    var demandMetric = dashboard.metrics().stream()
        .filter(metric -> metric.key().equals("demand-review-density"))
        .findFirst()
        .orElseThrow();
    assertThat(demandMetric.detail().viewKey()).isEqualTo("quality-review-records");
    assertThat(demandMetric.detail().params())
        .containsEntry("projectName", "CC2026R4")
        .containsEntry("reviewType", "需求说明书评审");
    assertThat(demandMetric.status()).isEqualTo("success");

    var dgmMetric = dashboard.metrics().stream()
        .filter(metric -> metric.key().equals("code-review-density-dgm"))
        .findFirst()
        .orElseThrow();
    assertThat(dgmMetric.detail()).isNull();
    assertThat(dgmMetric.export().exportKey()).isEqualTo("code-review-records-dgm");
    assertThat(dgmMetric.status()).isEqualTo("success");

    var newIssueMetric = dashboard.metrics().stream()
        .filter(metric -> metric.key().equals("new-issue-fix-rate"))
        .findFirst()
        .orElseThrow();
    assertThat(newIssueMetric.detail().viewKey()).isEqualTo("new-issue-defects");
    assertThat(newIssueMetric.detail().params())
        .containsEntry("projectName", "CC2026R4")
        .doesNotContainKey("testingPhase");

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
    assertThat(authorChart.option()).containsKey("dataZoom");

    assertThat(rules.rules()).anySatisfy(rule -> {
      assertThat(rule.key()).isEqualTo("quality-rd.release-leakage-rate");
      assertThat(rule.formula()).isEqualTo("未关闭缺陷数 / 全部有效缺陷数 × 100%");
      assertThat(rule.scope()).contains("排除已拒绝");
      assertThat(rule.target()).isEqualTo("不超过 15.00%");
    });
    assertThat(rules.rules()).anySatisfy(rule -> {
      assertThat(rule.key()).isEqualTo("quality-rd.defect-repair-user");
      assertThat(rule.scope()).contains("保留已拒绝状态记录");
      assertThat(rule.description()).contains("空指派人不生成分组");
    });
  }

  @Test
  void judgesReviewAndCodeReviewDensityStatusAgainstUpdatedTargetBands() {
    when(rdService.normalizeProjectName("CC2026R4")).thenReturn("CC2026R4");
    when(rdService.getRdDashboard("CC2026R4", "dgm", CodeReviewDataReadMode.MATCH_MODE))
        .thenReturn(bandDashboard());
    var context = new AnalyticsDashboardQueryContext(
        Map.of("projectName", "CC2026R4", "codeReviewSource", "dgm"),
        1,
        20,
        null,
        null,
        AnalyticsDashboardQueryContext.ReadMode.MATCH_MODE);

    var dashboard = provider.loadDashboard(context);
    var rules = provider.loadRules(context);

    assertThat(statusOf(dashboard, "demand-review-density")).isEqualTo("success");
    assertThat(statusOf(dashboard, "design-review-density")).isEqualTo("danger");
    assertThat(statusOf(dashboard, "code-review-density-cc")).isEqualTo("danger");
    assertThat(statusOf(dashboard, "code-review-density-dgm")).isEqualTo("success");
    assertThat(rules.rules()).anySatisfy(rule -> {
      assertThat(rule.key()).isEqualTo("quality-rd.demand-review-density");
      assertThat(rule.target()).isEqualTo("[0.20, 0.60]");
    });
    assertThat(rules.rules()).anySatisfy(rule -> {
      assertThat(rule.key()).isEqualTo("quality-rd.design-review-density");
      assertThat(rule.target()).isEqualTo("[0.30, 0.80]");
    });
    assertThat(rules.rules()).anySatisfy(rule -> {
      assertThat(rule.key()).isEqualTo("quality-rd.code-review-density-cc");
      assertThat(rule.target()).isEqualTo("[3.00, 12.00] KLOC");
    });
    assertThat(rules.rules()).anySatisfy(rule -> {
      assertThat(rule.key()).isEqualTo("quality-rd.author-defect-density");
      assertThat(rule.target()).isEqualTo("[3.00, 12.00] KLOC");
    });
  }

  private QualityBoardRdDashboardResponse bandDashboard() {
    var overview = new QualityBoardRdOverviewResponse(
        "CC2026R4", 0.25D, 0.25D, 2.5D, 11.5D, 92D, 10D, 91D, 88D, List.of());
    return new QualityBoardRdDashboardResponse(
        overview,
        "dgm",
        List.of(new OptionItemResponse("DGM", "dgm")),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  @Test
  void combinedVersionRulesDescribeActualMemberIntegrationPhases() {
    var combinedService = DashboardVersionScopeTestSupport.service(
        List.of(
            DashboardVersionScopeTestSupport.group(
                10L,
                "CC2027R1",
                2,
                DashboardVersionScopeTestSupport.member(101L, "CC2027R1第一轮系统测试", 1)),
            DashboardVersionScopeTestSupport.group(
                20L,
                "CC2027R2",
                3,
                DashboardVersionScopeTestSupport.member(201L, "CC2027R2第一轮系统测试", 1))),
        List.of(DashboardVersionScopeTestSupport.scope(
            "cc-2027-r1-r2",
            "CC2027R1&R2",
            "CC2027R1",
            List.of("CC2027R1", "CC2027R2"),
            List.of("2027R1&R2"),
            List.of("CC2027R1集成测试", "CC2027R2集成测试"))));
    var combinedProvider = new QualityRdAnalyticsDashboardProvider(
        rdService, workbookExportService, combinedService);
    when(rdService.normalizeProjectName("cc-2027-r1-r2")).thenReturn("cc-2027-r1-r2");
    when(rdService.displayProjectName("cc-2027-r1-r2")).thenReturn("CC2027R1&R2");

    var rules = combinedProvider.loadRules(
        AnalyticsDashboardQueryContext.of(Map.of("projectName", "cc-2027-r1-r2")));

    assertThat(scopeOf(rules, "quality-rd.integration-pass-rate"))
        .isEqualTo("CC2027R1集成测试、CC2027R2集成测试；逐条通过率先保留两位小数后再取算术平均");
    assertThat(scopeOf(rules, "quality-rd.development-leakage-rate"))
        .isEqualTo("CC2027R1集成测试、CC2027R2集成测试 与系统测试阶段；系统测试有效缺陷排除已拒绝记录");
  }

  private static String scopeOf(AnalyticsDashboardRulesResponse rules, String key) {
    return rules.rules().stream()
        .filter(rule -> rule.key().equals(key))
        .findFirst()
        .orElseThrow()
        .scope();
  }

  private String statusOf(AnalyticsDashboardResponse dashboard, String key) {
    return dashboard.metrics().stream()
        .filter(metric -> metric.key().equals(key))
        .findFirst()
        .orElseThrow()
        .status();
  }

  @Test
  void exportsThroughTheSameQualityBoardWorkbookServiceAndRejectsFormalDgm() {
    when(rdService.normalizeProjectName("CC2026R4")).thenReturn("CC2026R4");
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
    org.mockito.Mockito.lenient().when(detailQuery.forRead(org.mockito.ArgumentMatchers.any(com.data.collection.platform.entity.read.DatasetReadSelection.class))).thenReturn(detailQuery);
    var detailWorkbook = mock(QualityRdAnalyticsWorkbookService.class);
    when(detailQuery.htgcDataReady()).thenReturn(false);
    var fullProvider = new QualityRdAnalyticsDashboardProvider(
        rdService, workbookExportService, detailQuery, detailWorkbook, versionScopeService);

    assertThatThrownBy(() -> fullProvider.export(
            "assignee-remaining-htgc-detail",
            AnalyticsDashboardQueryContext.of(Map.of("projectName", "CC2026R3"))))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("HTGC 数据源未就绪");
  }

  @Test
  void serviceExcludesRejectedIssuesFromRemainingAssigneeChartAndDropsBlankAssignees() {
    var jdbc = new CapturingJdbcTemplate();
    var formalReviews = mock(ReviewDataRecordReadRepository.class);
    org.mockito.Mockito.lenient().when(formalReviews.forRead(org.mockito.ArgumentMatchers.any(com.data.collection.platform.entity.read.DatasetReadSelection.class))).thenReturn(formalReviews);
    var matchReviews = mock(ReviewDataMatchModeRecordRepository.class);
    org.mockito.Mockito.lenient().when(matchReviews.forRead(org.mockito.ArgumentMatchers.any(com.data.collection.platform.entity.read.DatasetReadSelection.class))).thenReturn(matchReviews);
    var codeReview = mock(QualityBoardCodeReviewReadSupport.class);
    org.mockito.Mockito.lenient().when(codeReview.forRead(org.mockito.ArgumentMatchers.any(com.data.collection.platform.entity.read.DatasetReadSelection.class))).thenReturn(codeReview);
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
        jdbc, formalReviews, matchReviews, codeReview, versionScopeService);

    service.getRdDashboard("CC2026R4", "cc");

    long rejectedPredicateCount = jdbc.countSqlContaining("not like '%已拒绝%'");
    assertThat(rejectedPredicateCount).isGreaterThanOrEqualTo(5);
    String assigneeSql = jdbc.sqlStatements.stream()
        .filter(sql -> sql.contains("assignee_name"))
        .findFirst()
        .orElseThrow();
    assertThat(assigneeSql)
        .contains("nullif(btrim(assignee_name), '') is not null")
        .contains("已拒绝")
        .doesNotContain("未标注指派人");
  }

  @Test
  void reviewDensityAlwaysCombinesFormalAndHistoricalSnapshotRecords() {
    var jdbc = new CapturingJdbcTemplate();
    var formalReviews = mock(ReviewDataRecordReadRepository.class);
    org.mockito.Mockito.lenient().when(formalReviews.forRead(org.mockito.ArgumentMatchers.any(com.data.collection.platform.entity.read.DatasetReadSelection.class))).thenReturn(formalReviews);
    var matchReviews = mock(ReviewDataMatchModeRecordRepository.class);
    org.mockito.Mockito.lenient().when(matchReviews.forRead(org.mockito.ArgumentMatchers.any(com.data.collection.platform.entity.read.DatasetReadSelection.class))).thenReturn(matchReviews);
    var codeReview = mock(QualityBoardCodeReviewReadSupport.class);
    org.mockito.Mockito.lenient().when(codeReview.forRead(org.mockito.ArgumentMatchers.any(com.data.collection.platform.entity.read.DatasetReadSelection.class))).thenReturn(codeReview);
    when(formalReviews.loadRecords(null, "CC2026R4", null, null, null, null, null, null))
        .thenReturn(
            List.of(
                reviewRow(1L, "需求说明书评审", 10, 2),
                reviewRow(2L, "设计说明书评审", 10, 1)));
    when(matchReviews.loadRecords())
        .thenReturn(
            List.of(
                reviewRow(-3L, "需求说明书评审", 10, 3),
                reviewRow(-4L, "设计说明书评审", 10, 4)));
    var service = new QualityBoardRdService(
        jdbc, formalReviews, matchReviews, codeReview, versionScopeService);

    QualityBoardRdOverviewResponse overview =
        service.getOverview("CC2026R4", CodeReviewDataReadMode.FORMAL);

    assertThat(overview.demandReviewReportDensity()).isEqualTo(0.25D);
    assertThat(overview.designReviewReportDensity()).isEqualTo(0.25D);
  }

  @Test
  void assigneeRemainingSummaryExcludesRejectedIssuesLikeTheOldPlatform() {
    var jdbc = new CapturingJdbcTemplate();
    var codeReview = mock(QualityBoardCodeReviewReadSupport.class);
    var service = new QualityRdAnalyticsDetailQueryService(
        jdbc, codeReview, versionScopeService, mock(QualityBoardRdService.class));

    service.assigneeSummaryRows("CC2026R4", null);

    assertThat(jdbc.sqlStatements.getFirst())
        .contains("bug_status")
        .contains("已拒绝");
  }

  @Test
  void assigneeRemainingExportsIgnoreDrilldownAssigneeFilter() {
    var detailQuery = mock(QualityRdAnalyticsDetailQueryService.class);
    org.mockito.Mockito.lenient().when(detailQuery.forRead(org.mockito.ArgumentMatchers.any(com.data.collection.platform.entity.read.DatasetReadSelection.class))).thenReturn(detailQuery);
    var detailWorkbook = mock(QualityRdAnalyticsWorkbookService.class);
    when(detailQuery.canonicalProjectName("CC2026R4")).thenReturn("CC2026R4");
    when(detailQuery.assigneeSummaryRows("CC2026R4", null)).thenReturn(List.of());
    when(detailQuery.ccAssigneeDetailRows("CC2026R4", null)).thenReturn(List.of());
    when(detailWorkbook.assigneeSummary(List.of())).thenReturn(new byte[] {1});
    when(detailWorkbook.ccDetails(List.of())).thenReturn(new byte[] {2});
    var fullProvider = new QualityRdAnalyticsDashboardProvider(
        rdService, workbookExportService, detailQuery, detailWorkbook, versionScopeService);
    var context = AnalyticsDashboardQueryContext.of(
        Map.of("projectName", "CC2026R4", "assigneeName", "张三"));

    fullProvider.export("assignee-remaining-summary", context);
    fullProvider.export("assignee-remaining-cc-detail", context);

    verify(detailQuery).assigneeSummaryRows("CC2026R4", null);
    verify(detailQuery).ccAssigneeDetailRows("CC2026R4", null);
  }

  @Test
  void ccAssigneeDetailRowsKeepOldPlatformModuleOrderWhenModuleTotalsTie() {
    var jdbc = new RowReturningJdbcTemplate(List.of(
        issue("模块B", "处理人2"),
        issue("模块A", "处理人1"),
        issue("模块A", "处理人3"),
        issue("模块B", "处理人4")));
    var codeReview = mock(QualityBoardCodeReviewReadSupport.class);
    var service = new QualityRdAnalyticsDetailQueryService(
        jdbc, codeReview, versionScopeService, mock(QualityBoardRdService.class));

    var rows = service.ccAssigneeDetailRows("CC2026R4", null);

    assertThat(rows).extracting(QualityRdAnalyticsDetailQueryService.CcAssigneeDetailRow::moduleName)
        .containsExactly("模块B", "模块B", "模块A", "模块A");
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

  private static Map<String, String> issue(String moduleName, String assigneeName) {
    return Map.of(
        "module_name", moduleName,
        "module_names", moduleName,
        "assignee_name", assigneeName,
        "bug_status", "未修复",
        "delay_cause", "",
        "severity_level", "LEVEL1",
        "priority_level", "P1",
        "urgency", "P1");
  }

  private static ReviewDataRecordRowResponse reviewRow(
      Long id, String reviewType, int reviewScalePages, int problemCount) {
    return new ReviewDataRecordRowResponse(
        id,
        "CC2026R4",
        "评审记录" + id,
        "模块A",
        reviewType,
        LocalDate.of(2026, 6, 1),
        "负责人A",
        "专家A",
        reviewScalePages,
        "评审文档",
        "作者A",
        "V1",
        problemCount,
        0D,
        LocalDateTime.of(2026, 6, 1, 10, 0),
        false);
  }

  private static final class RowReturningJdbcTemplate extends JdbcTemplate {
    private final List<Map<String, String>> rows;

    private RowReturningJdbcTemplate(List<Map<String, String>> rows) {
      this.rows = rows;
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
      List<T> mapped = new ArrayList<>();
      for (int index = 0; index < rows.size(); index++) {
        try {
          ResultSet resultSet = mock(ResultSet.class);
          Map<String, String> row = rows.get(index);
          when(resultSet.getString(org.mockito.ArgumentMatchers.anyString()))
              .thenAnswer(invocation -> row.get(invocation.getArgument(0, String.class)));
          mapped.add(rowMapper.mapRow(resultSet, index));
        } catch (Exception error) {
          throw new AssertionError(error);
        }
      }
      return mapped;
    }
  }
}
