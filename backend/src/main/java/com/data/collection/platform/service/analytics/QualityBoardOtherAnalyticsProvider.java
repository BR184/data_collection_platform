package com.data.collection.platform.service.analytics;

import com.data.collection.platform.entity.analytics.AnalyticsDashboardDetailResponse;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardExport;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardResponse;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardRulesResponse;
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

  public QualityBoardOtherAnalyticsProvider(
      QualityBoardOtherQueryService queryService,
      QualityBoardOtherWorkbookService workbookService) {
    this.queryService = queryService;
    this.workbookService = workbookService;
  }

  @Override
  public String dashboardKey() {
    return DASHBOARD_KEY;
  }

  @Override
  public String ruleVersion(AnalyticsDashboardQueryContext context) {
    return "legacy-personal-quality-v2";
  }

  @Override
  public String sourceVersion(AnalyticsDashboardQueryContext context) {
    return "formal-cc-issue-merge-integration-v1";
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
        "对齐老平台其他看板的功能、成员与版本质量专题",
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
                "系统测试缺陷数 ÷ 正式 CC 新增代码行数 × 100",
                "系统测试缺陷按所选版本阶段统计；新增行固定读取正式 CC 合并请求事实",
                "系统测试缺陷排除已拒绝；不读取 DGM"),
            rule(
                QualityBoardOtherTopic.QUALITY_RANKING,
                "修复人系统测试缺陷数 ÷ 同名 CC 提交人新增代码行数 × 1000",
                "所选版本的系统测试议题与正式 CC 合并请求；系统测试缺陷排除已拒绝",
                "数值越低越好；不展示空修复人、无有效新增行和 0 密度成员"),
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
                "集成测试未通过数 ÷（集成测试未通过数 + 系统测试缺陷数）× 100%",
                "全部启用发布版本的集成测试与系统测试事实",
                "系统测试侧排除已拒绝")));
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
        List.of(new AnalyticsDashboardResponse.ExportAction(topic.exportKey(), "导出 Excel")));
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
        new AnalyticsDashboardResponse.ExportAction(topic.exportKey(), "导出 Excel"));
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
    option.put("grid", Map.of("left", 20, "right", 24, "top", 28, "bottom", 72, "containLabel", true));
    option.put("xAxis", Map.of(
        "type", "category",
        "data", rows.stream().map(QualityBoardOtherQueryService.Row::name).toList(),
        "axisLabel", Map.of("rotate", 28, "interval", 0)));
    option.put("yAxis", Map.of("type", "value", "name", axisName(topic)));
    option.put("dataZoom", List.of(
        Map.of("type", "inside", "startValue", 0, "endValue", Math.min(11, Math.max(0, rows.size() - 1))),
        Map.of("type", "slider", "height", 16, "bottom", 12, "startValue", 0,
            "endValue", Math.min(11, Math.max(0, rows.size() - 1)))));
    option.put("series", List.of(Map.of(
        "name", topic.title(),
        "type", "bar",
        "barMaxWidth", 52,
        "data", rows.stream().map(this::chartPoint).toList(),
        "itemStyle", Map.of("color", color(topic), "borderRadius", List.of(6, 6, 0, 0)),
        "label", Map.of("show", true, "position", "top"))));
    return option;
  }

  private Map<String, Object> horizontalRankingOption(
      QualityBoardOtherTopic topic, List<QualityBoardOtherQueryService.Row> rows) {
    List<QualityBoardOtherQueryService.Row> displayRows = rows.reversed();
    Map<String, Object> option = baseOption(topic);
    option.put("grid", Map.of("left", 24, "right", 42, "top", 28, "bottom", 34, "containLabel", true));
    option.put("xAxis", Map.of("type", "value", "name", axisName(topic)));
    option.put("yAxis", Map.of(
        "type", "category",
        "data", displayRows.stream().map(QualityBoardOtherQueryService.Row::name).toList(),
        "axisLabel", Map.of("width", 130, "overflow", "truncate")));
    option.put("dataZoom", List.of(
        Map.of("type", "inside", "yAxisIndex", 0, "startValue", 0,
            "endValue", Math.min(14, Math.max(0, displayRows.size() - 1))),
        Map.of("type", "slider", "yAxisIndex", 0, "width", 14, "right", 4, "startValue", 0,
            "endValue", Math.min(14, Math.max(0, displayRows.size() - 1)))));
    option.put("series", List.of(Map.of(
        "name", topic.title(),
        "type", "bar",
        "barMaxWidth", 24,
        "data", displayRows.stream().map(this::chartPoint).toList(),
        "itemStyle", Map.of("color", color(topic), "borderRadius", List.of(0, 6, 6, 0)),
        "label", Map.of("show", true, "position", "right"))));
    return option;
  }

  private Map<String, Object> trendOption(
      QualityBoardOtherTopic topic, List<QualityBoardOtherQueryService.Row> rows) {
    Map<String, Object> option = baseOption(topic);
    option.put("grid", Map.of("left", 20, "right", 24, "top", 28, "bottom", 70, "containLabel", true));
    option.put("xAxis", Map.of(
        "type", "category",
        "boundaryGap", false,
        "data", rows.stream().map(QualityBoardOtherQueryService.Row::name).toList(),
        "axisLabel", Map.of("rotate", 28, "interval", 0)));
    option.put("yAxis", Map.of("type", "value", "name", "%"));
    option.put("dataZoom", List.of(
        Map.of("type", "inside", "startValue", 0, "endValue", Math.min(11, Math.max(0, rows.size() - 1))),
        Map.of("type", "slider", "height", 16, "bottom", 10, "startValue", 0,
            "endValue", Math.min(11, Math.max(0, rows.size() - 1)))));
    option.put("series", List.of(Map.of(
        "name", topic.title(),
        "type", "line",
        "smooth", true,
        "symbolSize", 8,
        "data", rows.stream().map(this::chartPoint).toList(),
        "lineStyle", Map.of("width", 3, "color", color(topic)),
        "itemStyle", Map.of("color", color(topic)),
        "areaStyle", Map.of("opacity", 0.12),
        "label", Map.of("show", true, "position", "top"))));
    return option;
  }

  private Map<String, Object> baseOption(QualityBoardOtherTopic topic) {
    Map<String, Object> option = new LinkedHashMap<>();
    option.put("animationDuration", 500);
    option.put("tooltip", Map.of("trigger", "axis", "confine", true));
    option.put("aria", Map.of("enabled", true, "description", topic.subtitle()));
    return option;
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
