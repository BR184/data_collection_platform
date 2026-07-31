package com.data.collection.platform.service.analytics;

import com.data.collection.platform.entity.analytics.AnalyticsDashboardDetailResponse;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardExport;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardResponse;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardRulesResponse;
import com.data.collection.platform.service.PageRecordSnapshotService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class QualityBoardOtherAnalyticsProvider implements AnalyticsDashboardProvider {
  public static final String DASHBOARD_KEY = "quality-board-other";
  private static final String POINT_KEY = "pointKey";
  private static final Set<String> SORTABLE_KEYS =
      Set.of("scope", "name", "numerator", "denominator", "value");

  private final QualityBoardOtherQueryService queryService;
  private final QualityBoardOtherWorkbookService workbookService;
  private final PageRecordSnapshotService snapshotService;

  public QualityBoardOtherAnalyticsProvider(
      QualityBoardOtherQueryService queryService,
      QualityBoardOtherWorkbookService workbookService,
      PageRecordSnapshotService snapshotService) {
    this.queryService = queryService;
    this.workbookService = workbookService;
    this.snapshotService = snapshotService;
  }

  @Override
  public String dashboardKey() {
    return DASHBOARD_KEY;
  }

  @Override
  public String ruleVersion(AnalyticsDashboardQueryContext context) {
    return "legacy-personal-quality-v3";
  }

  @Override
  public String sourceVersion(AnalyticsDashboardQueryContext context) {
    return snapshotService.issueFactSourceVersion()
        + "|"
        + snapshotService.codeReviewSourceVersion()
        + "|members:"
        + queryService.memberScopeSourceVersion();
  }

  @Override
  public Set<String> dashboardParameterKeys() {
    return Set.of(
        "functionCountProjectName",
        "functionDensityProjectName",
        "qualityRankingProjectName",
        "memberUnresolvedProjectName");
  }

  @Override
  public AnalyticsDashboardResponse loadDashboard(AnalyticsDashboardQueryContext context) {
    List<AnalyticsDashboardResponse.Chart> charts = new ArrayList<>();
    for (QualityBoardOtherTopic topic : QualityBoardOtherTopic.values()) {
      charts.add(chart(topic, context, queryService.load(topic, context)));
    }
    return new AnalyticsDashboardResponse(
        DASHBOARD_KEY,
        "质量专题分析",
        "按功能、成员和版本维度分析缺陷数量、密度与遗留情况。",
        List.of(),
        charts);
  }

  @Override
  public AnalyticsDashboardRulesResponse loadRules(AnalyticsDashboardQueryContext context) {
    return new AnalyticsDashboardRulesResponse(
        DASHBOARD_KEY,
        List.of(
            rule(
                QualityBoardOtherTopic.FUNCTION_DEFECT_COUNT,
                "按功能统计系统测试缺陷数量",
                "所选版本对应的系统测试阶段；排除已拒绝数据",
                "不截断 TopN，返回全部有效功能"),
            rule(
                QualityBoardOtherTopic.FUNCTION_DEFECT_DENSITY,
                "系统测试缺陷数 ÷ CC 新增代码行数 × 100",
                "系统测试缺陷按所选版本阶段统计；新增行按 CC 已合并且需要代码走查的数据统计",
                "系统测试缺陷排除已拒绝数据"),
            rule(
                QualityBoardOtherTopic.QUALITY_RANKING,
                "各子测试阶段（成员缺陷数 ÷ 成员全部 CC 新增代码行数）平均值 × 1000",
                "所选版本的系统测试阶段；成员范围由质量看板业务成员配置维护",
                "排除已拒绝；保留 0 密度业务成员，按密度从高到低展示"),
            rule(
                QualityBoardOtherTopic.MEMBER_UNRESOLVED_RATE,
                "个人未修复缺陷数 ÷ 个人缺陷总数 × 100%",
                "所选版本对应的系统测试阶段，按修复人统计",
                "排除已拒绝；不展示空修复人和 0% 成员"),
            rule(
                QualityBoardOtherTopic.RELEASE_LEAKAGE_RATE,
                "未关闭系统测试缺陷数 ÷ 系统测试缺陷总数 × 100%",
                "全部启用发布版本；未关闭同时识别 open 与 opened",
                "排除已拒绝；按版本展示完整集合"),
            rule(
                QualityBoardOtherTopic.DEVELOPMENT_LEAKAGE_RATE,
                "未关闭系统测试缺陷数 ÷ 系统测试缺陷总数 × 100%",
                "全部启用发布版本的系统测试事实；未关闭同时识别 open 与 opened",
                "排除已拒绝；不读取集成测试数据")));
  }

  @Override
  public Set<String> detailViewKeys() {
    return java.util.Arrays.stream(QualityBoardOtherTopic.values())
        .map(QualityBoardOtherTopic::viewKey)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  @Override
  public Set<String> detailParameterKeys(String viewKey) {
    QualityBoardOtherTopic topic = QualityBoardOtherTopic.fromViewKey(viewKey);
    return topic.scopeParameterKey() == null
        ? Set.of(POINT_KEY)
        : Set.of(topic.scopeParameterKey(), POINT_KEY);
  }

  @Override
  public Set<String> detailSortableKeys(String viewKey) {
    QualityBoardOtherTopic.fromViewKey(viewKey);
    return SORTABLE_KEYS;
  }

  @Override
  public AnalyticsDashboardDetailResponse loadDetail(
      String viewKey, AnalyticsDashboardQueryContext context) {
    QualityBoardOtherTopic topic = QualityBoardOtherTopic.fromViewKey(viewKey);
    List<QualityBoardOtherQueryService.Row> rows = detailRows(topic, context);
    int fromIndex = Math.min((context.page() - 1) * context.size(), rows.size());
    int toIndex = Math.min(fromIndex + context.size(), rows.size());
    return new AnalyticsDashboardDetailResponse(
        DASHBOARD_KEY,
        viewKey,
        topic.title() + "数据详情",
        topic.subtitle(),
        columns(topic),
        rows.subList(fromIndex, toIndex).stream().map(row -> record(topic, row)).toList(),
        rows.size(),
        context.page(),
        context.size(),
        List.of(new AnalyticsDashboardResponse.ExportAction(topic.exportKey(), "导出")));
  }

  @Override
  public Set<String> exportKeys() {
    return java.util.Arrays.stream(QualityBoardOtherTopic.values())
        .map(QualityBoardOtherTopic::exportKey)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  @Override
  public Optional<String> exportDetailViewKey(String exportKey) {
    return Optional.of(QualityBoardOtherTopic.fromExportKey(exportKey).viewKey());
  }

  @Override
  public AnalyticsDashboardExport export(
      String exportKey, AnalyticsDashboardQueryContext context) {
    QualityBoardOtherTopic topic = QualityBoardOtherTopic.fromExportKey(exportKey);
    List<QualityBoardOtherQueryService.Row> rows = detailRows(topic, context);
    String scope = queryService.scope(topic, context);
    return AnalyticsDashboardExport.xlsx(
        topic.filename(scope), workbookService.export(topic, scope, rows));
  }

  private AnalyticsDashboardResponse.Chart chart(
      QualityBoardOtherTopic topic,
      AnalyticsDashboardQueryContext context,
      List<QualityBoardOtherQueryService.Row> rows) {
    Map<String, String> actionParameters = new LinkedHashMap<>();
    if (topic.scopeParameterKey() != null) {
      actionParameters.put(topic.scopeParameterKey(), queryService.scope(topic, context));
    }
    return new AnalyticsDashboardResponse.Chart(
        topic.chartKey(),
        topic.title(),
        topic.subtitle(),
        chartOption(topic, rows),
        topic.chartKey(),
        new AnalyticsDashboardResponse.DetailAction(topic.viewKey(), actionParameters),
        new AnalyticsDashboardResponse.ExportAction(topic.exportKey(), "导出"));
  }

  private AnalyticsDashboardRulesResponse.Rule rule(
      QualityBoardOtherTopic topic, String formula, String scope, String description) {
    return new AnalyticsDashboardRulesResponse.Rule(
        topic.chartKey(), topic.title(), formula, scope, null, description);
  }

  private List<QualityBoardOtherQueryService.Row> detailRows(
      QualityBoardOtherTopic topic, AnalyticsDashboardQueryContext context) {
    String point = context.parameter(POINT_KEY).orElse(null);
    var rows = queryService.load(topic, context).stream()
        .filter(row -> point == null || row.name().equals(point))
        .toList();
    if (context.sortField() == null) {
      return rows;
    }
    return rows.stream().sorted(comparator(context)).toList();
  }

  private Comparator<QualityBoardOtherQueryService.Row> comparator(
      AnalyticsDashboardQueryContext context) {
    Comparator<QualityBoardOtherQueryService.Row> comparator = switch (context.sortField()) {
      case "scope" -> Comparator.comparing(QualityBoardOtherQueryService.Row::scope);
      case "name" -> Comparator.comparing(QualityBoardOtherQueryService.Row::name);
      case "numerator" -> Comparator.comparingLong(QualityBoardOtherQueryService.Row::numerator);
      case "denominator" -> Comparator.comparingLong(QualityBoardOtherQueryService.Row::denominator);
      case "value" -> Comparator.comparingDouble(QualityBoardOtherQueryService.Row::value);
      default -> throw new IllegalStateException("未校验的其他看板排序字段: " + context.sortField());
    };
    boolean descending = "desc".equals(context.sortOrder());
    return (descending ? comparator.reversed() : comparator)
        .thenComparing(QualityBoardOtherQueryService.Row::name);
  }

  private List<AnalyticsDashboardDetailResponse.Column> columns(QualityBoardOtherTopic topic) {
    List<AnalyticsDashboardDetailResponse.Column> columns = new ArrayList<>();
    columns.add(new AnalyticsDashboardDetailResponse.Column("scope", "统计范围", "text", 160));
    columns.add(new AnalyticsDashboardDetailResponse.Column("name", topic.nameLabel(), "text", 180));
    columns.add(new AnalyticsDashboardDetailResponse.Column(
        "numerator", topic.numeratorLabel(), "number", 150));
    if (topic != QualityBoardOtherTopic.FUNCTION_DEFECT_COUNT) {
      columns.add(new AnalyticsDashboardDetailResponse.Column(
          "denominator", topic.denominatorLabel(), "number", 160));
      columns.add(new AnalyticsDashboardDetailResponse.Column(
          "value", valueColumnLabel(topic), "number", 170));
    }
    return List.copyOf(columns);
  }

  private Map<String, Object> record(
      QualityBoardOtherTopic topic, QualityBoardOtherQueryService.Row row) {
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("scope", row.scope());
    record.put("name", row.name());
    record.put("numerator", row.numerator());
    if (topic != QualityBoardOtherTopic.FUNCTION_DEFECT_COUNT) {
      record.put("denominator", row.denominator());
      record.put("value", row.value());
    }
    return record;
  }

  private String valueColumnLabel(QualityBoardOtherTopic topic) {
    if (topic == QualityBoardOtherTopic.QUALITY_RANKING) {
      return "千行缺陷密度(KLOC)";
    }
    return topic.title() + (topic.unit() == null ? "" : "(" + topic.unit() + ")");
  }

  private Map<String, Object> chartOption(
      QualityBoardOtherTopic topic, List<QualityBoardOtherQueryService.Row> rows) {
    return switch (topic) {
      case QUALITY_RANKING -> horizontalRankingOption(topic, rows);
      case RELEASE_LEAKAGE_RATE, DEVELOPMENT_LEAKAGE_RATE -> trendOption(topic, rows);
      default -> verticalBarOption(topic, rows);
    };
  }

  private Map<String, Object> verticalBarOption(
      QualityBoardOtherTopic topic, List<QualityBoardOtherQueryService.Row> rows) {
    Map<String, Object> option = baseOption(topic);
    option.put(
        "grid",
        Map.of(
            "left", 20,
            "right", 24,
            "top", 28,
            "bottom", AnalyticsDataZoomOptions.HORIZONTAL_GRID_BOTTOM,
            "containLabel", true));
    option.put("xAxis", Map.of(
        "type", "category",
        "data", rows.stream().map(QualityBoardOtherQueryService.Row::name).toList(),
        "axisLabel", categoryAxisLabel()));
    option.put("yAxis", Map.of("type", "value", "name", axisName(topic)));
    option.put(
        "dataZoom",
        AnalyticsDataZoomOptions.horizontal(
            rows.size(), AnalyticsDataZoomOptions.LEGACY_INITIAL_VIEWPORT_END_VALUE));
    option.put("series", List.of(Map.of(
        "name", topic.title(),
        "type", "bar",
        "barMaxWidth", 52,
        "data", rows.stream().map(this::chartPoint).toList(),
        "itemStyle", Map.of("color", color(topic), "borderRadius", List.of(6, 6, 0, 0)),
        "label", Map.of("show", true, "position", "top"),
        "labelLayout", Map.of("hideOverlap", true))));
    return option;
  }

  private Map<String, Object> horizontalRankingOption(
      QualityBoardOtherTopic topic, List<QualityBoardOtherQueryService.Row> rows) {
    List<QualityBoardOtherQueryService.Row> displayRows = rows.reversed();
    Map<String, Object> option = baseOption(topic);
    option.put(
        "grid",
        Map.of(
            "left", 24,
            "right", AnalyticsDataZoomOptions.VERTICAL_GRID_RIGHT,
            "top", 28,
            "bottom", 34,
            "containLabel", true));
    option.put("xAxis", Map.of("type", "value", "name", axisName(topic)));
    option.put("yAxis", Map.of(
        "type", "category",
        "data", displayRows.stream().map(QualityBoardOtherQueryService.Row::name).toList(),
        "axisLabel", Map.of("width", 130, "overflow", "truncate", "hideOverlap", true)));
    option.put("dataZoom", AnalyticsDataZoomOptions.vertical(displayRows.size(), 14));
    option.put("series", List.of(Map.of(
        "name", topic.title(),
        "type", "bar",
        "barMaxWidth", 24,
        "data", displayRows.stream().map(this::chartPoint).toList(),
        "itemStyle", Map.of("color", color(topic), "borderRadius", List.of(0, 6, 6, 0)),
        "label", Map.of("show", true, "position", "right"),
        "labelLayout", Map.of("hideOverlap", true))));
    return option;
  }

  private Map<String, Object> trendOption(
      QualityBoardOtherTopic topic, List<QualityBoardOtherQueryService.Row> rows) {
    Map<String, Object> option = baseOption(topic);
    option.put(
        "grid",
        Map.of(
            "left", 20,
            "right", 24,
            "top", 28,
            "bottom", AnalyticsDataZoomOptions.HORIZONTAL_GRID_BOTTOM,
            "containLabel", true));
    option.put("xAxis", Map.of(
        "type", "category",
        "boundaryGap", false,
        "data", rows.stream().map(QualityBoardOtherQueryService.Row::name).toList(),
        "axisLabel", categoryAxisLabel()));
    option.put("yAxis", Map.of("type", "value", "name", "%"));
    option.put(
        "dataZoom",
        AnalyticsDataZoomOptions.horizontal(
            rows.size(), AnalyticsDataZoomOptions.LEGACY_INITIAL_VIEWPORT_END_VALUE));
    option.put("series", List.of(Map.of(
        "name", topic.title(),
        "type", "line",
        "smooth", true,
        "symbolSize", 8,
        "data", rows.stream().map(this::chartPoint).toList(),
        "lineStyle", Map.of("width", 3, "color", color(topic)),
        "itemStyle", Map.of("color", color(topic)),
        "areaStyle", Map.of("opacity", 0.12),
        "label", Map.of("show", true, "position", "top"),
        "labelLayout", Map.of("hideOverlap", true))));
    return option;
  }

  private Map<String, Object> baseOption(QualityBoardOtherTopic topic) {
    Map<String, Object> option = new LinkedHashMap<>();
    option.put("animationDuration", 500);
    option.put("tooltip", Map.of("trigger", "axis", "confine", true));
    option.put("aria", Map.of("enabled", true, "description", topic.subtitle()));
    return option;
  }

  /** Lets ECharts choose a readable subset when the category window is wider than the card. */
  private Map<String, Object> categoryAxisLabel() {
    return Map.of(
        "rotate", 28,
        "interval", "auto",
        "hideOverlap", true,
        "width", 96,
        "overflow", "truncate");
  }

  private Map<String, Object> chartPoint(QualityBoardOtherQueryService.Row row) {
    return Map.of(
        "value", row.value(),
        "pointKey", row.name(),
        "detailParams", Map.of(POINT_KEY, row.name()));
  }

  private String axisName(QualityBoardOtherTopic topic) {
    return topic.unit() == null ? "数量" : topic.unit();
  }

  private String color(QualityBoardOtherTopic topic) {
    return switch (topic) {
      case FUNCTION_DEFECT_COUNT -> "#2563eb";
      case FUNCTION_DEFECT_DENSITY -> "#0f9f8f";
      case QUALITY_RANKING -> "#7c3aed";
      case MEMBER_UNRESOLVED_RATE -> "#dc4c64";
      case RELEASE_LEAKAGE_RATE -> "#d97706";
      case DEVELOPMENT_LEAKAGE_RATE -> "#0284c7";
    };
  }
}
