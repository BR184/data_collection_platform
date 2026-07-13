package com.data.collection.platform.service;

import com.data.collection.platform.entity.analytics.AnalyticsDashboardDetailResponse;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardExport;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardResponse;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardRulesResponse;
import com.data.collection.platform.service.analytics.AnalyticsDashboardProvider;
import com.data.collection.platform.service.analytics.AnalyticsDashboardQueryContext;
import com.data.collection.platform.service.analytics.CodeReviewAnalyticsReadMode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

@Component
public class CodeReviewMultiBoardAnalyticsProvider implements AnalyticsDashboardProvider {
  public static final String DASHBOARD_KEY = "code-review-multi";
  private static final String DETAIL_VIEW_KEY = "code-review-statistics";
  private static final Set<String> DASHBOARD_PARAMETERS = Set.of("source", "projectName");
  private static final Set<String> DETAIL_PARAMETERS =
      Set.of("source", "projectName", "topic", "pointKey");
  private static final Set<String> DETAIL_SORT_KEYS = Set.of("name", "value");

  private final CodeReviewMultiBoardAnalyticsQueryService queryService;

  public CodeReviewMultiBoardAnalyticsProvider(
      CodeReviewMultiBoardAnalyticsQueryService queryService) {
    this.queryService = queryService;
  }

  @Override
  public String dashboardKey() {
    return DASHBOARD_KEY;
  }

  @Override
  public String ruleVersion(AnalyticsDashboardQueryContext context) {
    return "code-review-multi-rules-v2";
  }

  @Override
  public String sourceVersion(AnalyticsDashboardQueryContext context) {
    return queryService.sourceVersion(projectScope(context));
  }

  @Override
  public ReadModeSource readModeSource() {
    return ReadModeSource.CODE_REVIEW_SETTING;
  }

  @Override
  public Set<String> dashboardParameterKeys() {
    return DASHBOARD_PARAMETERS;
  }

  @Override
  public AnalyticsDashboardResponse loadDashboard(AnalyticsDashboardQueryContext context) {
    CodeReviewMultiBoardProjectScope projectScope = projectScope(context);
    String source = projectScope.source();
    String projectName = projectScope.projectName();
    List<AnalyticsDashboardResponse.Chart> charts = new ArrayList<>();
    for (CodeReviewMultiBoardTopic topic : CodeReviewMultiBoardTopic.values()) {
      List<CodeReviewMultiBoardAnalyticsRow> rows =
          queryService.loadRows(topic, projectScope);
      charts.add(toChart(topic, rows, projectScope));
    }
    String sourceLabel = "dgm".equals(source) ? "DGM" : "CC";
    String subtitle = projectName.isBlank()
        ? sourceLabel + " 当前没有可展示的 MERGED 代码走查数据"
        : sourceLabel + " / " + projectName + "；所有专题使用同一数据源与项目范围";
    return new AnalyticsDashboardResponse(
        DASHBOARD_KEY,
        "代码走查多元看板",
        subtitle,
        List.of(),
        charts);
  }

  @Override
  public AnalyticsDashboardRulesResponse loadRules(AnalyticsDashboardQueryContext context) {
    return new AnalyticsDashboardRulesResponse(
        DASHBOARD_KEY,
        java.util.Arrays.stream(CodeReviewMultiBoardTopic.values())
            .map(this::toRule)
            .toList());
  }

  @Override
  public Set<String> detailViewKeys() {
    return Set.of(DETAIL_VIEW_KEY);
  }

  @Override
  public Set<String> detailParameterKeys(String viewKey) {
    return DETAIL_PARAMETERS;
  }

  @Override
  public Set<String> detailSortableKeys(String viewKey) {
    return DETAIL_SORT_KEYS;
  }

  @Override
  public AnalyticsDashboardDetailResponse loadDetail(
      String viewKey,
      AnalyticsDashboardQueryContext context) {
    CodeReviewMultiBoardTopic topic = topic(context);
    CodeReviewMultiBoardProjectScope projectScope = projectScope(context);
    List<CodeReviewMultiBoardAnalyticsRow> rows = filteredRows(topic, context, projectScope);
    rows = sortedRows(rows, context.sortField(), context.sortOrder());
    int fromIndex = Math.min((context.page() - 1) * context.size(), rows.size());
    int toIndex = Math.min(fromIndex + context.size(), rows.size());
    List<Map<String, Object>> records = new ArrayList<>();
    for (int index = fromIndex; index < toIndex; index++) {
      CodeReviewMultiBoardAnalyticsRow row = rows.get(index);
      Map<String, Object> record = new LinkedHashMap<>();
      record.put("rank", index + 1);
      record.put("name", row.label());
      record.put("value", row.value());
      records.add(record);
    }
    return new AnalyticsDashboardDetailResponse(
        DASHBOARD_KEY,
        DETAIL_VIEW_KEY,
        topic.title() + "详情",
        topic.scope() + " " + topic.description(),
        List.of(
            new AnalyticsDashboardDetailResponse.Column("rank", "序号", "number", 80),
            new AnalyticsDashboardDetailResponse.Column("name", topic.dimensionLabel(), "text", 220),
            new AnalyticsDashboardDetailResponse.Column("value", topic.valueLabel(), "decimal", 160)),
        records,
        rows.size(),
        context.page(),
        context.size(),
        List.of(new AnalyticsDashboardResponse.ExportAction(topic.key(), "导出 Excel")));
  }

  @Override
  public Set<String> exportKeys() {
    return java.util.Arrays.stream(CodeReviewMultiBoardTopic.values())
        .map(CodeReviewMultiBoardTopic::key)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  @Override
  public Set<String> exportParameterKeys(String exportKey) {
    return DETAIL_PARAMETERS;
  }

  @Override
  public Optional<String> exportDetailViewKey(String exportKey) {
    return Optional.of(DETAIL_VIEW_KEY);
  }

  @Override
  public AnalyticsDashboardExport export(
      String exportKey,
      AnalyticsDashboardQueryContext context) {
    CodeReviewMultiBoardTopic topic = CodeReviewMultiBoardTopic.fromKey(exportKey);
    CodeReviewMultiBoardProjectScope projectScope = projectScope(context);
    return AnalyticsDashboardExport.xlsx(
        topic.filename(projectScope.projectName()),
        toWorkbook(topic, filteredRows(topic, context, projectScope)));
  }

  private AnalyticsDashboardResponse.Chart toChart(
      CodeReviewMultiBoardTopic topic,
      List<CodeReviewMultiBoardAnalyticsRow> rows,
      CodeReviewMultiBoardProjectScope projectScope) {
    Map<String, Object> option = new LinkedHashMap<>();
    option.put("animationDuration", 450);
    option.put("grid", Map.of("left", 24, "right", 36, "top", 24, "bottom", 52, "containLabel", true));
    option.put("tooltip", Map.of("trigger", "axis", "axisPointer", Map.of("type", "shadow")));
    option.put(
        "xAxis",
        Map.of(
            "type", "value",
            "name", topic.valueLabel(),
            "nameLocation", "middle",
            "nameGap", 34,
            "splitLine", Map.of("lineStyle", Map.of("color", "#eef2f7"))));
    option.put(
        "yAxis",
        Map.of(
            "type", "category",
            "inverse", true,
            "data", rows.stream().map(CodeReviewMultiBoardAnalyticsRow::label).toList(),
            "axisLabel", Map.of("width", 150, "overflow", "truncate")));
    if (rows.size() > 12) {
      option.put(
          "dataZoom",
          List.of(
              Map.of("type", "inside", "yAxisIndex", 0, "startValue", 0, "endValue", 11),
              Map.of("type", "slider", "yAxisIndex", 0, "right", 4, "width", 12)));
    }
    option.put(
        "series",
        List.of(
            Map.of(
                "name", topic.valueLabel(),
                "type", "bar",
                "barMaxWidth", 28,
                "itemStyle", Map.of("color", chartColor(topic), "borderRadius", List.of(0, 6, 6, 0)),
                "data", rows.stream().map(this::pointData).toList())));
    return new AnalyticsDashboardResponse.Chart(
        topic.key(),
        topic.title(),
        topic.description(),
        option,
        topic.key(),
        new AnalyticsDashboardResponse.DetailAction(
            DETAIL_VIEW_KEY,
            Map.of(
                "source", projectScope.source(),
                "projectName", projectScope.projectName(),
                "topic", topic.key())),
        new AnalyticsDashboardResponse.ExportAction(topic.key(), "导出 Excel"));
  }

  private Map<String, Object> pointData(CodeReviewMultiBoardAnalyticsRow row) {
    Map<String, Object> point = new LinkedHashMap<>();
    point.put("name", row.label());
    point.put("value", row.value());
    point.put("pointKey", row.label());
    point.put("detailParams", Map.of("pointKey", row.label()));
    return point;
  }

  private AnalyticsDashboardRulesResponse.Rule toRule(CodeReviewMultiBoardTopic topic) {
    return new AnalyticsDashboardRulesResponse.Rule(
        topic.key(),
        topic.title(),
        topic.formula(),
        topic.scope(),
        target(topic),
        topic.description());
  }

  private String target(CodeReviewMultiBoardTopic topic) {
    return switch (topic.aggregation()) {
      case AVERAGE_ROW_DENSITY, DEFECT_PER_KLOC -> "结合团队基线观察，密度异常升高需核查";
      case DEFECT_SUM, DISTINCT_MERGE_REQUEST_COUNT, ROW_COUNT -> "用于规模与分布比较，不设单一达标阈值";
    };
  }

  private List<CodeReviewMultiBoardAnalyticsRow> filteredRows(
      CodeReviewMultiBoardTopic topic,
      AnalyticsDashboardQueryContext context,
      CodeReviewMultiBoardProjectScope projectScope) {
    List<CodeReviewMultiBoardAnalyticsRow> rows =
        queryService.loadRows(topic, projectScope);
    String pointKey = context.parameter("pointKey").orElse(null);
    if (pointKey == null) {
      return rows;
    }
    return rows.stream().filter(row -> row.label().equals(pointKey)).toList();
  }

  private List<CodeReviewMultiBoardAnalyticsRow> sortedRows(
      List<CodeReviewMultiBoardAnalyticsRow> rows,
      String sortField,
      String sortOrder) {
    if (sortField == null) {
      return rows;
    }
    Comparator<CodeReviewMultiBoardAnalyticsRow> comparator = "name".equals(sortField)
        ? Comparator.comparing(CodeReviewMultiBoardAnalyticsRow::label)
        : Comparator.comparing(CodeReviewMultiBoardAnalyticsRow::value);
    if (!"asc".equalsIgnoreCase(sortOrder)) {
      comparator = comparator.reversed();
    }
    return rows.stream().sorted(comparator).toList();
  }

  private byte[] toWorkbook(
      CodeReviewMultiBoardTopic topic,
      List<CodeReviewMultiBoardAnalyticsRow> rows) {
    try (XSSFWorkbook workbook = new XSSFWorkbook();
         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet(topic.sheetName());
      CellStyle headerStyle = workbook.createCellStyle();
      headerStyle.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
      headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      org.apache.poi.ss.usermodel.Font headerFont = workbook.createFont();
      headerFont.setBold(true);
      headerStyle.setFont(headerFont);
      Row header = sheet.createRow(0);
      createCell(header, 0, topic.dimensionLabel(), headerStyle);
      createCell(header, 1, topic.valueLabel(), headerStyle);
      for (int index = 0; index < rows.size(); index++) {
        Row excelRow = sheet.createRow(index + 1);
        excelRow.createCell(0).setCellValue(rows.get(index).label());
        excelRow.createCell(1).setCellValue(rows.get(index).value().doubleValue());
      }
      sheet.setColumnWidth(0, 28 * 256);
      sheet.setColumnWidth(1, 20 * 256);
      sheet.createFreezePane(0, 1);
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("生成代码走查看板 Excel 失败", exception);
    }
  }

  private void createCell(Row row, int index, String value, CellStyle style) {
    Cell cell = row.createCell(index);
    cell.setCellValue(value);
    cell.setCellStyle(style);
  }

  private CodeReviewMultiBoardTopic topic(AnalyticsDashboardQueryContext context) {
    return CodeReviewMultiBoardTopic.fromKey(context.parameter("topic").orElse(""));
  }

  private String source(AnalyticsDashboardQueryContext context) {
    return "dgm".equalsIgnoreCase(context.parameter("source").orElse("")) ? "dgm" : "cc";
  }

  private String requestedProjectName(AnalyticsDashboardQueryContext context) {
    return context.parameter("projectName").orElse("");
  }

  private CodeReviewMultiBoardProjectScope projectScope(
      AnalyticsDashboardQueryContext context) {
    return queryService.resolveProjectScope(
        source(context),
        requestedProjectName(context),
        CodeReviewAnalyticsReadMode.from(context.readMode()));
  }

  private String chartColor(CodeReviewMultiBoardTopic topic) {
    return switch (topic) {
      case MODULE_DEFECT_DENSITY -> "#2563eb";
      case REVIEWER_DEFECT_DENSITY -> "#0f766e";
      case REVIEWER_FIXED_DEFECTS -> "#7c3aed";
      case ASSIGNEE_FIXED_DEFECTS -> "#9333ea";
      case AUTHOR_DEFECT_DENSITY -> "#c2410c";
      case MERGE_REQUEST_COUNT -> "#0284c7";
      case CODE_SUBMISSION_FREQUENCY -> "#059669";
      case CODE_SUBMISSION_DEFECT_DENSITY -> "#dc2626";
    };
  }
}
