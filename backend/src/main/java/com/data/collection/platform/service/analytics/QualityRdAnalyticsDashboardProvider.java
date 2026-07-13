package com.data.collection.platform.service.analytics;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.QualityBoardChartRowResponse;
import com.data.collection.platform.entity.QualityBoardRdDashboardResponse;
import com.data.collection.platform.entity.QualityBoardRdOverviewResponse;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardDetailResponse;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardExport;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardResponse;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardRulesResponse;
import com.data.collection.platform.service.CodeReviewDataReadMode;
import com.data.collection.platform.service.QualityBoardRdService;
import com.data.collection.platform.service.QualityBoardWorkbookExportService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class QualityRdAnalyticsDashboardProvider implements AnalyticsDashboardProvider {
  private static final String DASHBOARD_KEY = "quality-rd";
  private static final String DEFAULT_PROJECT_NAME = "CC2026R3";
  private static final String DEFAULT_CODE_REVIEW_SOURCE = "cc";
  private static final Set<String> DASHBOARD_PARAMETERS =
      Set.of("projectName", "codeReviewSource");
  private static final Set<String> EXPORT_KEYS = Set.of(
      "code-review-records-cc",
      "code-review-records-dgm",
      "assignee-defect-density",
      "author-defect-density",
      "fix-user-severity",
      "frequency-code-submission",
      "defect-repair-user",
      "assignee-remaining-summary",
      "assignee-remaining-cc-detail",
      "assignee-remaining-htgc-detail");

  private final QualityBoardRdService rdService;
  private final QualityBoardWorkbookExportService workbookExportService;
  private final QualityRdAnalyticsDetailQueryService detailQueryService;
  private final QualityRdAnalyticsWorkbookService analyticsWorkbookService;

  @Autowired
  public QualityRdAnalyticsDashboardProvider(
      QualityBoardRdService rdService,
      QualityBoardWorkbookExportService workbookExportService,
      QualityRdAnalyticsDetailQueryService detailQueryService,
      QualityRdAnalyticsWorkbookService analyticsWorkbookService) {
    this.rdService = rdService;
    this.workbookExportService = workbookExportService;
    this.detailQueryService = detailQueryService;
    this.analyticsWorkbookService = analyticsWorkbookService;
  }

  /** Kept for the focused provider unit fixture; production wiring always uses the full constructor. */
  QualityRdAnalyticsDashboardProvider(
      QualityBoardRdService rdService,
      QualityBoardWorkbookExportService workbookExportService) {
    this(rdService, workbookExportService, null, null);
  }

  @Override
  public String dashboardKey() {
    return DASHBOARD_KEY;
  }

  @Override
  public String ruleVersion(AnalyticsDashboardQueryContext context) {
    return "quality-rd-rules@1";
  }

  @Override
  public String sourceVersion(AnalyticsDashboardQueryContext context) {
    return "quality-rd-facts@1:" + context.readMode().key();
  }

  @Override
  public ReadModeSource readModeSource() {
    //兼容模式-MatchMode：代码走查读模式只由服务端系统设置解析，客户端不能指定。
    return ReadModeSource.CODE_REVIEW_SETTING;
  }

  @Override
  public Set<String> dashboardParameterKeys() {
    return DASHBOARD_PARAMETERS;
  }

  @Override
  public AnalyticsDashboardResponse loadDashboard(AnalyticsDashboardQueryContext context) {
    CodeReviewDataReadMode readMode = CodeReviewAnalyticsReadMode.from(context.readMode());
    QualityBoardRdDashboardResponse legacy = rdService.getRdDashboard(
        context.parameter("projectName").orElse(DEFAULT_PROJECT_NAME),
        context.parameter("codeReviewSource").orElse(DEFAULT_CODE_REVIEW_SOURCE),
        readMode);
    QualityBoardRdOverviewResponse overview = legacy.summary();
    String projectName = overview.projectName();
    String codeReviewSource = legacy.codeReviewSource();
    boolean dgmAvailable = legacy.codeReviewSourceOptions().stream()
        .map(OptionItemResponse::value)
        .anyMatch("dgm"::equalsIgnoreCase);

    List<AnalyticsDashboardResponse.Metric> metrics = List.of(
        metric(
            "demand-review-density",
            "需求评审缺陷密度",
            overview.demandReviewReportDensity(),
            "",
            "quality-rd.demand-review-density",
            detail("review-data-records", projectName, Map.of("reviewType", "需求说明书评审")),
            null),
        metric(
            "design-review-density",
            "设计评审缺陷密度",
            overview.designReviewReportDensity(),
            "",
            "quality-rd.design-review-density",
            detail("review-data-records", projectName, Map.of("reviewType", "设计说明书评审")),
            null),
        metric(
            "code-review-density-cc",
            "CC代码走查缺陷密度",
            overview.codeWalkThroughDefectDensityCc(),
            "KLOC",
            "quality-rd.code-review-density-cc",
            null,
            new AnalyticsDashboardResponse.ExportAction(
                "code-review-records-cc", "下载 CC 代码走查数据")),
        metric(
            "code-review-density-dgm",
            "DGM代码走查缺陷密度",
            overview.codeWalkThroughDefectDensityDgm(),
            "KLOC",
            "quality-rd.code-review-density-dgm",
            null,
            dgmAvailable
                ? new AnalyticsDashboardResponse.ExportAction(
                    "code-review-records-dgm", "下载 DGM 代码走查数据")
                : null),
        metric(
            "integration-pass-rate",
            "集成测试通过率",
            overview.integrationPassRate(),
            "%",
            "quality-rd.integration-pass-rate",
            detail("integration-test-results", projectName, Map.of()),
            null),
        metric(
            "release-leakage-rate",
            "发布缺陷遗留率",
            overview.defectLeakageRate(),
            "%",
            "quality-rd.release-leakage-rate",
            detail("release-leakage-defects", projectName, Map.of()),
            null),
        metric(
            "development-leakage-rate",
            "开发缺陷遗留率",
            overview.defectEliminationRate(),
            "%",
            "quality-rd.development-leakage-rate",
            detail("development-leakage-defects", projectName, Map.of()),
            null),
        metric(
            "new-issue-fix-rate",
            "新发缺陷修复率",
            overview.newIssueFixRate(),
            "%",
            "quality-rd.new-issue-fix-rate",
            new AnalyticsDashboardResponse.DetailAction(
                "system-test-defect-summary",
                Map.of("testingPhase", projectName)),
            null));

    Map<String, String> reviewerDetailParams =
        codeReviewDetailParams(projectName, codeReviewSource, "reviewer-density");
    Map<String, String> authorDetailParams =
        codeReviewDetailParams(projectName, codeReviewSource, "author-density");
    Map<String, String> submissionDetailParams =
        codeReviewDetailParams(projectName, codeReviewSource, "submission-frequency");
    List<AnalyticsDashboardResponse.Chart> charts = List.of(
        new AnalyticsDashboardResponse.Chart(
            "assignee-defect-density",
            "按走查人统计代码走查缺陷密度",
            "当前代码走查来源：" + sourceLabel(codeReviewSource),
            QualityRdDashboardChartOptions.horizontalBar(
                legacy.assigneeDefectDensityRows(),
                "缺陷密度(K/LOC)",
                "#2563eb",
                new QualityRdDashboardChartOptions.PointParameters(
                    "reviewer-density", reviewerDetailParams, "reviewerName")),
            "quality-rd.assignee-defect-density",
            new AnalyticsDashboardResponse.DetailAction(
                "quality-code-review-records", reviewerDetailParams),
            exportAction("assignee-defect-density")),
        new AnalyticsDashboardResponse.Chart(
            "author-defect-density",
            "按被走查人统计代码走查缺陷密度",
            "当前代码走查来源：" + sourceLabel(codeReviewSource),
            QualityRdDashboardChartOptions.horizontalBar(
                legacy.authorDefectDensityRows(),
                "缺陷密度(K/LOC)",
                "#0f9f6e",
                new QualityRdDashboardChartOptions.PointParameters(
                    "author-density", authorDetailParams, "authorName")),
            "quality-rd.author-defect-density",
            new AnalyticsDashboardResponse.DetailAction(
                "quality-code-review-records", authorDetailParams),
            exportAction("author-defect-density")),
        new AnalyticsDashboardResponse.Chart(
            "fix-user-severity",
            "按修复人统计缺陷数",
            "按一级、二级、三级和建议类缺陷分层统计",
            QualityRdDashboardChartOptions.stackedSeverity(
                legacy.fixUserSeverityRows(), projectName),
            "quality-rd.fix-user-severity",
            detail("fix-user-defects", projectName, Map.of()),
            exportAction("fix-user-severity")),
        new AnalyticsDashboardResponse.Chart(
            "frequency-code-submission",
            "代码提交频次",
            "当前代码走查来源：" + sourceLabel(codeReviewSource),
            QualityRdDashboardChartOptions.horizontalBar(
                legacy.frequencyCodeSubmissionRows(),
                "提交次数",
                "#d97706",
                new QualityRdDashboardChartOptions.PointParameters(
                    "submission-frequency", submissionDetailParams, "authorName")),
            "quality-rd.frequency-code-submission",
            new AnalyticsDashboardResponse.DetailAction(
                "quality-code-review-records", submissionDetailParams),
            exportAction("frequency-code-submission")),
        new AnalyticsDashboardResponse.Chart(
            "defect-repair-user",
            "指派人剩余缺陷数量",
            "按当前未关闭系统测试缺陷统计",
            QualityRdDashboardChartOptions.horizontalBar(
                legacy.defectRepairUserRows(),
                "剩余缺陷数",
                "#dc5360",
                new QualityRdDashboardChartOptions.PointParameters(
                    "remaining-defects",
                    Map.of("projectId", "9", "projectName", projectName),
                    "assigneeName")),
            "quality-rd.defect-repair-user",
            new AnalyticsDashboardResponse.DetailAction(
                "assignee-remaining-defects",
                Map.of("projectId", "9", "projectName", projectName)),
            exportAction("defect-repair-user")));

    return new AnalyticsDashboardResponse(
        DASHBOARD_KEY,
        "研发质量看板",
        "汇总评审、代码走查、集成测试与系统测试质量指标。",
        metrics,
        charts);
  }

  @Override
  public AnalyticsDashboardRulesResponse loadRules(AnalyticsDashboardQueryContext context) {
    String projectName = context.parameter("projectName").orElse(DEFAULT_PROJECT_NAME);
    String source = canonicalCodeReviewSource(context);
    String codeReviewScope = projectName + "，" + sourceLabel(source) + " 代码走查数据";
    return new AnalyticsDashboardRulesResponse(
        DASHBOARD_KEY,
        List.of(
            rule(
                "demand-review-density",
                "需求评审缺陷密度",
                "有效需求评审问题数 / 需求评审规模页数",
                projectName + " 的需求说明书评审；问题与规模按评审数据统一有效口径重算",
                "[0.20, 0.60]",
                "兼容评审数据与正式评审数据使用同一计算公式。"),
            rule(
                "design-review-density",
                "设计评审缺陷密度",
                "有效设计评审问题数 / 设计评审规模页数",
                projectName + " 的设计说明书评审；问题与规模按评审数据统一有效口径重算",
                "[0.20, 0.60]",
                "兼容评审数据与正式评审数据使用同一计算公式。"),
            rule(
                "code-review-density-cc",
                "CC代码走查缺陷密度",
                "合并请求缺陷数合计 / 新增代码行数合计 × 1000",
                projectName + " 的 CC 已合并 dev 分支数据；按 MR 去重，重复行缺陷数相加、新增行数取第一条",
                "[2.00, 10.00] KLOC",
                "兼容模式和正式模式使用隔离的数据表与 MR 去重键。"),
            rule(
                "code-review-density-dgm",
                "DGM代码走查缺陷密度",
                "合并请求缺陷数合计 / 新增代码行数合计 × 1000",
                projectName + " 的 DGM 已合并 dev 分支数据；按 MR 去重，重复行缺陷数相加、新增行数取第一条",
                "[2.00, 10.00] KLOC",
                "DGM 当前只在兼容模式提供，不会回落读取正式 CC 数据。"),
            rule(
                "integration-pass-rate",
                "集成测试通过率",
                "各集成测试记录的（通过用例数 / 执行用例数 × 100%）之和 / 记录数",
                projectName + "集成测试；逐条通过率先保留两位小数后再取算术平均",
                "不低于 90.00%",
                "执行用例为空或为 0 的记录按 0 参与记录数平均。"),
            rule(
                "release-leakage-rate",
                "发布缺陷遗留率",
                "未关闭缺陷数 / 全部有效缺陷数 × 100%",
                projectName + " 系统测试阶段，排除已拒绝缺陷；未关闭同时识别 open 和 opened",
                "不超过 15.00%",
                null),
            rule(
                "development-leakage-rate",
                "开发缺陷遗留率",
                "集成测试未通过用例数 /（集成测试未通过用例数 + 系统测试有效缺陷数）× 100%",
                projectName + " 的集成测试与系统测试阶段；系统测试有效缺陷排除已拒绝记录",
                "不低于 90.00%",
                null),
            rule(
                "new-issue-fix-rate",
                "新发缺陷修复率",
                "已修复或未复现的新发有效缺陷数 / 新发有效缺陷总数 × 100%",
                projectName + " 系统测试阶段；排除功能屏蔽、已拒绝、建议类、历史遗留及公共关闭排除项",
                "不低于 90.00%",
                null),
            rule(
                "assignee-defect-density",
                "按走查人统计代码走查缺陷密度",
                "人员有效行级缺陷密度之和 / 该人员全部匹配记录数",
                codeReviewScope + "；排除空值、占位值和非法人员值",
                "[2.00, 10.00] KLOC",
                "DGM 只在兼容模式进入本图。"),
            rule(
                "author-defect-density",
                "按被走查人统计代码走查缺陷密度",
                "人员有效行级缺陷密度之和 / 该人员全部匹配记录数",
                codeReviewScope + "；额外排除走查人字段为非法占位值的记录",
                "[2.00, 10.00] KLOC",
                "DGM 只在兼容模式进入本图。"),
            rule(
                "fix-user-severity",
                "按修复人统计缺陷数",
                "按修复人分别汇总一级、二级、三级和建议类缺陷数量",
                projectName + " 系统测试阶段；排除功能屏蔽、已拒绝、空修复人、无合法评论和未设定占位值",
                null,
                "修复人来自第一条合法修复状态评论作者。"),
            rule(
                "frequency-code-submission",
                "代码提交频次",
                "按被走查人统计全部匹配代码走查记录数",
                codeReviewScope + "；不附加默认合并时间范围",
                null,
                "DGM 只在兼容模式进入本图。"),
            rule(
                "defect-repair-user",
                "指派人剩余缺陷数量",
                "按指派人统计未关闭缺陷数量",
                projectName + " 系统测试阶段；未关闭识别 open 和 opened，沿用老平台口径，不排除已拒绝",
                null,
                "空指派人不生成分组。")));
  }

  @Override
  public Set<String> detailViewKeys() {
    return QualityRdAnalyticsDetailView.keys();
  }

  @Override
  public Set<String> detailParameterKeys(String viewKey) {
    return switch (viewKey) {
      case "fix-user-defects" -> Set.of("projectName", "fixUser", "severityLevel");
      case "assignee-remaining-defects" ->
          Set.of("projectId", "projectName", "assigneeName");
      case "quality-code-review-records" ->
          Set.of("projectName", "source", "topic", "reviewerName", "authorName");
      default -> Set.of("projectName");
    };
  }

  @Override
  public Set<String> detailSortableKeys(String viewKey) {
    return QualityRdAnalyticsDetailView.fromKey(viewKey).sortableKeys();
  }

  @Override
  public AnalyticsDashboardDetailResponse loadDetail(
      String viewKey, AnalyticsDashboardQueryContext context) {
    requireDetailServices();
    QualityRdAnalyticsDetailView view = QualityRdAnalyticsDetailView.fromKey(viewKey);
    QualityRdAnalyticsDetailQueryService.DetailPage page = detailQueryService.load(viewKey, context);
    return new AnalyticsDashboardDetailResponse(
        DASHBOARD_KEY,
        viewKey,
        view.title(),
        detailDescription(viewKey, view),
        view.columns(),
        page.records(),
        page.total(),
        context.page(),
        context.size(),
        detailExports(viewKey),
        detailFilters(viewKey, context),
        detailChart(viewKey, context));
  }

  private String detailDescription(String viewKey, QualityRdAnalyticsDetailView view) {
    if ("assignee-remaining-defects".equals(viewKey) && !detailQueryService.htgcDataReady()) {
      return view.description() + "；HTGC 数据源未就绪。";
    }
    return view.description();
  }

  private AnalyticsDashboardDetailResponse.Chart detailChart(
      String viewKey, AnalyticsDashboardQueryContext context) {
    if (!"assignee-remaining-defects".equals(viewKey)) {
      return null;
    }
    List<QualityBoardChartRowResponse> rows = detailQueryService.assigneeSummaryRows(context).stream()
        .map(row -> new QualityBoardChartRowResponse(row.assigneeName(), (double) row.remainingCount()))
        .toList();
    String projectName = detailQueryService.canonicalProjectName(
        context.parameter("projectName").orElse(null));
    Map<String, Object> option = QualityRdDashboardChartOptions.horizontalBar(
        rows,
        "剩余缺陷数",
        "#2563eb",
        new QualityRdDashboardChartOptions.PointParameters(
            "assignee-remaining-detail",
            Map.of("projectId", "9", "projectName", projectName),
            "assigneeName"));
    return new AnalyticsDashboardDetailResponse.Chart(
        "assignee-remaining-defects",
        "指派人剩余缺陷数量",
        projectName + " · 未关闭缺陷",
        option,
        420);
  }

  @Override
  public Set<String> exportKeys() {
    return EXPORT_KEYS;
  }

  @Override
  public Set<String> exportParameterKeys(String exportKey) {
    return DASHBOARD_PARAMETERS;
  }

  @Override
  public Optional<String> exportDetailViewKey(String exportKey) {
    return switch (exportKey) {
      case "assignee-remaining-summary",
          "assignee-remaining-cc-detail",
          "assignee-remaining-htgc-detail" -> Optional.of("assignee-remaining-defects");
      default -> Optional.empty();
    };
  }

  @Override
  public AnalyticsDashboardExport export(
      String exportKey, AnalyticsDashboardQueryContext context) {
    String projectName = context.parameter("projectName").orElse(DEFAULT_PROJECT_NAME);
    if (Set.of(
            "defect-repair-user",
            "assignee-remaining-summary",
            "assignee-remaining-cc-detail",
            "assignee-remaining-htgc-detail")
        .contains(exportKey)) {
      requireDetailServices();
      projectName = detailQueryService.canonicalProjectName(
          context.parameter("projectName").orElse(null));
    }
    String codeReviewSource = context.parameter("codeReviewSource").orElse(DEFAULT_CODE_REVIEW_SOURCE);
    CodeReviewDataReadMode readMode = CodeReviewAnalyticsReadMode.from(context.readMode());
    if ("defect-repair-user".equals(exportKey)
        || "assignee-remaining-summary".equals(exportKey)) {
      requireDetailServices();
      return AnalyticsDashboardExport.xlsx(
          projectName + "指派人剩余缺陷数量统计.xlsx",
          analyticsWorkbookService.assigneeSummary(
              detailQueryService.assigneeSummaryRows(context)));
    }
    if ("assignee-remaining-cc-detail".equals(exportKey)) {
      requireDetailServices();
      return AnalyticsDashboardExport.xlsx(
          "CrownCAD" + projectName + "指派人剩余缺陷数量详细统计数据.xlsx",
          analyticsWorkbookService.ccDetails(detailQueryService.ccAssigneeDetailRows(
              projectName, context.parameter("assigneeName").orElse(null))));
    }
    if ("assignee-remaining-htgc-detail".equals(exportKey)) {
      requireDetailServices();
      if (!detailQueryService.htgcDataReady()) {
        throw new BizException("HTGC 数据源未就绪");
      }
      return AnalyticsDashboardExport.xlsx(
          "HTGC指派人剩余缺陷数量详细统计数据.xlsx",
          analyticsWorkbookService.htgcDetails(detailQueryService.htgcAssigneeDetailRows()));
    }
    if ("code-review-records-cc".equals(exportKey)) {
      return AnalyticsDashboardExport.xlsx(
          workbookExportService.codeReviewRecordsFilename(projectName, "cc"),
          workbookExportService.exportCodeReviewRecordsWorkbook(projectName, "cc", readMode));
    }
    if ("code-review-records-dgm".equals(exportKey)) {
      if (context.readMode() != AnalyticsDashboardQueryContext.ReadMode.MATCH_MODE) {
        throw new BizException("DGM 代码走查数据仅在兼容模式可用");
      }
      //兼容模式-MatchMode：DGM 导出与看板聚合共用兼容快照，禁止回落到正式 CC 事实表。
      return AnalyticsDashboardExport.xlsx(
          workbookExportService.codeReviewRecordsFilename(projectName, "dgm"),
          workbookExportService.exportCodeReviewRecordsWorkbook(projectName, "dgm", readMode));
    }
    if (!EXPORT_KEYS.contains(exportKey)) {
      throw new BizException("研发质量看板不支持该导出: " + exportKey);
    }
    return AnalyticsDashboardExport.xlsx(
        workbookExportService.rdChartFilename(projectName, exportKey),
        workbookExportService.exportRdChartWorkbook(
            projectName, codeReviewSource, exportKey, readMode));
  }

  private AnalyticsDashboardResponse.Metric metric(
      String key,
      String title,
      Double value,
      String unit,
      String ruleKey,
      AnalyticsDashboardResponse.DetailAction detail,
      AnalyticsDashboardResponse.ExportAction export) {
    double safeValue = value == null ? 0D : value;
    return new AnalyticsDashboardResponse.Metric(
        key,
        title,
        BigDecimal.valueOf(safeValue),
        String.format(Locale.ROOT, "%.2f", safeValue),
        unit,
        ruleKey,
        detail,
        export);
  }

  private AnalyticsDashboardRulesResponse.Rule rule(
      String key,
      String title,
      String formula,
      String scope,
      String target,
      String description) {
    return new AnalyticsDashboardRulesResponse.Rule(
        "quality-rd." + key, title, formula, scope, target, description);
  }

  private AnalyticsDashboardResponse.DetailAction detail(
      String viewKey,
      String projectName,
      Map<String, String> additionalParameters) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("projectName", projectName);
    params.putAll(additionalParameters);
    return new AnalyticsDashboardResponse.DetailAction(viewKey, params);
  }

  private AnalyticsDashboardResponse.ExportAction exportAction(String exportKey) {
    return new AnalyticsDashboardResponse.ExportAction(exportKey, "导出 Excel");
  }

  private Map<String, String> codeReviewDetailParams(
      String projectName, String source, String topic) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("projectName", projectName);
    params.put("source", source);
    params.put("topic", topic);
    return Map.copyOf(params);
  }

  private List<AnalyticsDashboardResponse.ExportAction> detailExports(String viewKey) {
    if (!"assignee-remaining-defects".equals(viewKey)) {
      return List.of();
    }
    return List.of(
        new AnalyticsDashboardResponse.ExportAction(
            "assignee-remaining-summary", "导出阶段统计"),
        new AnalyticsDashboardResponse.ExportAction(
            "assignee-remaining-cc-detail", "导出 CC 详细数据"),
        new AnalyticsDashboardResponse.ExportAction(
            "assignee-remaining-htgc-detail", "导出 HTGC 详细数据"));
  }

  private List<AnalyticsDashboardDetailResponse.Filter> detailFilters(
      String viewKey, AnalyticsDashboardQueryContext context) {
    if (!"assignee-remaining-defects".equals(viewKey)) {
      return List.of();
    }
    String selected = detailQueryService.canonicalProjectName(
        context.parameter("projectName").orElse(null));
    List<AnalyticsDashboardDetailResponse.Option> options = new java.util.ArrayList<>(
        detailQueryService.phaseOptions().stream()
            .map(option -> new AnalyticsDashboardDetailResponse.Option(
                option.label(), option.value()))
            .toList());
    if (options.stream().noneMatch(option -> selected.equals(option.value()))) {
      options.addFirst(new AnalyticsDashboardDetailResponse.Option(selected, selected));
    }
    return List.of(new AnalyticsDashboardDetailResponse.Filter(
        "projectName", "测试阶段", selected, options));
  }

  private void requireDetailServices() {
    if (detailQueryService == null || analyticsWorkbookService == null) {
      throw new IllegalStateException("研发质量看板详情服务未初始化");
    }
  }

  private String sourceLabel(String source) {
    return "dgm".equalsIgnoreCase(source) ? "DGM" : "CC";
  }

  private String canonicalCodeReviewSource(AnalyticsDashboardQueryContext context) {
    String requested = context.parameter("codeReviewSource").orElse(DEFAULT_CODE_REVIEW_SOURCE);
    //兼容模式-MatchMode：只有固定为兼容读模式的请求才允许规则展示 DGM 口径；
    //兼容模式-MatchMode：正式模式始终回到正式 CC，与 loadDashboard 的 canonical source 一致。
    return context.readMode() == AnalyticsDashboardQueryContext.ReadMode.MATCH_MODE
            && "dgm".equalsIgnoreCase(requested)
        ? "dgm"
        : "cc";
  }
}
