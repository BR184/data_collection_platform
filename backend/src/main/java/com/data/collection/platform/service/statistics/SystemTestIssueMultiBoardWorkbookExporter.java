package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.statistics.SystemTestIssueMultiBoardResponse;
import com.data.collection.platform.service.ExcelExportStyles;
import com.data.collection.platform.service.SystemTestLegacyCauseExportFields;
import com.data.collection.platform.service.TextQuerySupport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.util.StringUtils;

/** Writes chart-specific legacy workbook contracts without changing the chart response model. */
final class SystemTestIssueMultiBoardWorkbookExporter {
  private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
  private static final List<String> CAUSE_SUMMARY_HEADERS =
      List.of("缺陷原因", "一级缺陷", "二级缺陷", "三级缺陷", "需求&建议类", "共计");
  private static final List<String> SEVERITY_RAW_HEADERS =
      List.of(
          "议题严重程度", "议题更新时间", "议题提交时间", "模块名", "议题编号", "议题标题",
          "议题提交人", "议题处理人", "议题状态", "测试状态", "测试阶段", "议题类别", "里程碑",
          "优先级", "缺陷原因", "一级缺陷原因", "二级缺陷原因", "其他原因", "延期原因", "缺陷修复人");
  private static final List<String> PHASE_RAW_HEADERS =
      List.of(
          "阶段", "议题更新时间", "议题提交时间", "模块名", "议题编号", "议题标题", "议题提交人",
          "议题处理人", "测试阶段", "议题状态", "测试状态", "议题严重程度", "议题类别", "里程碑",
          "优先级", "缺陷原因", "一级缺陷原因", "二级缺陷原因", "其他原因", "延期原因", "缺陷修复人");
  private static final List<String> MODULE_RAW_HEADERS =
      List.of(
          "模块名", "议题严重程度", "议题更新时间", "议题提交时间", "议题编号", "议题标题",
          "议题提交人", "议题处理人", "议题状态", "测试状态", "测试阶段", "里程碑", "优先级",
          "缺陷原因", "一级缺陷原因", "二级缺陷原因", "其他原因", "延期原因", "缺陷修复人");
  private static final List<String> CAUSE_RAW_HEADERS =
      List.of(
          "一级缺陷原因", "二级缺陷原因", "议题更新时间", "议题提交时间", "模块名", "议题编号",
          "议题标题", "议题提交人", "议题处理人", "议题状态", "测试状态", "测试阶段", "议题严重程度",
          "议题类别", "里程碑", "优先级", "缺陷原因", "其他原因", "延期原因", "缺陷修复人");

  private SystemTestIssueMultiBoardWorkbookExporter() {}

  static byte[] export(
      SystemTestIssueMultiBoardResponse.Chart chart,
      List<SystemTestIssueMultiBoardService.IssueRow> rows) {
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Styles styles = new Styles(workbook);
      switch (chart.key()) {
        case "severity-level" -> writeSeverity(workbook, styles, rows);
        case "phase-severity" -> writeRawSheet(workbook, styles, "原始数据", PHASE_RAW_HEADERS, rows, RawLayout.PHASE);
        case "module-severity" -> writeModule(workbook, styles, rows);
        case "major-cause" -> writeMajorCause(workbook, styles, rows);
        case "cause-detail" -> writeCauseDetail(workbook, styles, rows);
        case "fix-user-severity" -> writeSummarySheet(
            workbook, styles, "修复人员统计", causeSummaryRows(groupByFixUser(rows), false));
        case "delay-cause" -> writeSummarySheet(
            workbook, styles, "申请延期缺陷原因分析", causeSummaryRows(groupByDelayCause(rows), true));
        default -> writeGenericChart(workbook, styles, chart);
      }
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException error) {
      throw new IllegalStateException("生成议题多元看板导出失败", error);
    }
  }

  private static void writeSeverity(
      XSSFWorkbook workbook, Styles styles, List<SystemTestIssueMultiBoardService.IssueRow> rows) {
    List<List<?>> summary =
        List.of(
            List.of("一级缺陷", severityCount(rows, "LEVEL1")),
            List.of("二级缺陷", severityCount(rows, "LEVEL2")),
            List.of("三级缺陷", severityCount(rows, "LEVEL3")),
            List.of("建议类缺陷", severityCount(rows, "SUGGESTION")));
    writeSheet(workbook, styles, "统计结果", List.of("名称", "数量"), summary);
    writeRawSheet(workbook, styles, "原始数据", SEVERITY_RAW_HEADERS, rows, RawLayout.SEVERITY);
  }

  private static void writeModule(
      XSSFWorkbook workbook, Styles styles, List<SystemTestIssueMultiBoardService.IssueRow> rows) {
    Map<String, List<SystemTestIssueMultiBoardService.IssueRow>> grouped = new LinkedHashMap<>();
    for (SystemTestIssueMultiBoardService.IssueRow row : rows) {
      for (String module : modules(row.moduleNames())) {
        grouped.computeIfAbsent(module, ignored -> new ArrayList<>()).add(row);
      }
    }
    List<SeveritySummary> summaries = grouped.entrySet().stream()
        .map(entry -> severitySummary(entry.getKey(), entry.getValue()))
        .sorted(SeveritySummary.ORDER)
        .toList();
    writeSheet(
        workbook,
        styles,
        "统计结果",
        List.of("模块名称", "一级缺陷", "二级缺陷", "三级缺陷", "建议类缺陷", "合计"),
        summaries.stream().map(SeveritySummary::values).toList());
    writeRawSheet(workbook, styles, "原始数据", MODULE_RAW_HEADERS, rows, RawLayout.MODULE);
  }

  private static void writeMajorCause(
      XSSFWorkbook workbook, Styles styles, List<SystemTestIssueMultiBoardService.IssueRow> rows) {
    List<? extends List<?>> summary = SystemTestIssueMetricDimensionSupport.MAJOR_CAUSES.stream()
        .map(cause -> List.of(cause, rows.stream().filter(row -> cause.equals(majorCause(row))).count()))
        .toList();
    writeSheet(workbook, styles, "统计结果", List.of("名称", "数量"), summary);
    List<SystemTestIssueMultiBoardService.IssueRow> rawRows = rows.stream()
        .filter(row -> SystemTestIssueMetricDimensionSupport.MAJOR_CAUSES.contains(majorCause(row)))
        .toList();
    writeRawSheet(workbook, styles, "原始数据", SEVERITY_RAW_HEADERS, rawRows, RawLayout.SEVERITY);
  }

  private static void writeCauseDetail(
      XSSFWorkbook workbook, Styles styles, List<SystemTestIssueMultiBoardService.IssueRow> rows) {
    List<SeveritySummary> summaries = DefectCauseMetricCatalog.METRICS.stream()
        .map(metric -> severitySummary(
            metric.label(),
            rows.stream().filter(row -> matchesCause(row, metric)).toList()))
        .sorted(SeveritySummary.ORDER)
        .toList();
    writeSummarySheet(workbook, styles, "统计结果", causeSummaryRows(summaries));
    List<SystemTestIssueMultiBoardService.IssueRow> rawRows = rows.stream()
        .filter(row -> DefectCauseMetricCatalog.METRICS.stream().anyMatch(metric -> matchesCause(row, metric)))
        .toList();
    writeRawSheet(workbook, styles, "原始数据", CAUSE_RAW_HEADERS, rawRows, RawLayout.CAUSE);
  }

  private static Map<String, List<SystemTestIssueMultiBoardService.IssueRow>> groupByFixUser(
      List<SystemTestIssueMultiBoardService.IssueRow> rows) {
    return rows.stream()
        .filter(row -> StringUtils.hasText(row.fixUser()))
        .collect(Collectors.groupingBy(
            SystemTestIssueMultiBoardService.IssueRow::fixUser,
            LinkedHashMap::new,
            Collectors.toList()));
  }

  private static Map<String, List<SystemTestIssueMultiBoardService.IssueRow>> groupByDelayCause(
      List<SystemTestIssueMultiBoardService.IssueRow> rows) {
    Map<String, List<SystemTestIssueMultiBoardService.IssueRow>> grouped = new LinkedHashMap<>();
    for (String cause : SystemTestIssueMetricDimensionSupport.DELAY_CAUSES) {
      grouped.put(
          cause,
          rows.stream()
              .filter(SystemTestIssueMultiBoardService.IssueRow::delay)
              .filter(row -> resolvedDelayCauses(row).contains(cause))
              .toList());
    }
    return grouped;
  }

  private static List<SeveritySummary> groupSummaries(
      Map<String, List<SystemTestIssueMultiBoardService.IssueRow>> grouped,
      boolean includeEmpty) {
    return grouped.entrySet().stream()
        .map(entry -> severitySummary(entry.getKey(), entry.getValue()))
        .filter(summary -> includeEmpty || summary.total() > 0)
        .sorted(SeveritySummary.ORDER)
        .toList();
  }

  private static List<List<?>> causeSummaryRows(
      Map<String, List<SystemTestIssueMultiBoardService.IssueRow>> grouped,
      boolean includeEmpty) {
    return causeSummaryRows(groupSummaries(grouped, includeEmpty));
  }

  private static List<List<?>> causeSummaryRows(List<SeveritySummary> summaries) {
    return summaries.stream().map(SeveritySummary::values).toList();
  }

  private static void writeSummarySheet(
      XSSFWorkbook workbook, Styles styles, String sheetName, List<List<?>> rows) {
    writeSheet(workbook, styles, sheetName, CAUSE_SUMMARY_HEADERS, rows);
  }

  private static void writeRawSheet(
      XSSFWorkbook workbook,
      Styles styles,
      String sheetName,
      List<String> headers,
      List<SystemTestIssueMultiBoardService.IssueRow> rows,
      RawLayout layout) {
    List<? extends List<?>> values = rows.stream()
        .sorted(Comparator.comparing(
            SystemTestIssueMultiBoardService.IssueRow::issueIid,
            Comparator.nullsLast(Comparator.naturalOrder())))
        .map(row -> rawValues(row, layout))
        .toList();
    writeSheet(workbook, styles, sheetName, headers, values);
  }

  private static List<?> rawValues(
      SystemTestIssueMultiBoardService.IssueRow row, RawLayout layout) {
    String reasonText = text(row.reasonCategory());
    SystemTestLegacyCauseExportFields causeFields =
        SystemTestLegacyCauseExportFields.fromReasonText(reasonText);
    LegacyRawIssue rawIssue =
        new LegacyRawIssue(
            date(row.updatedAt()),
            date(row.createdAt()),
            joinedModules(row.moduleNames()),
            issueReference(row.issueIid()),
            text(row.title()),
            text(row.authorName()),
            text(row.assigneeName()),
            issueState(row.issueState()),
            text(row.bugStatus()),
            text(row.testingPhase()),
            severityLabel(row),
            text(row.category()),
            text(row.milestoneTitle()),
            text(row.priorityLevel()),
            reasonText,
            text(causeFields.majorCause()),
            text(causeFields.secondCause()),
            otherCause(reasonText),
            text(row.delayCause()),
            text(row.fixUser()));
    return switch (layout) {
      case SEVERITY -> rawIssue.severityValues();
      case PHASE -> rawIssue.phaseValues();
      case MODULE -> rawIssue.moduleValues();
      case CAUSE -> rawIssue.causeValues();
    };
  }

  private static void writeGenericChart(
      XSSFWorkbook workbook,
      Styles styles,
      SystemTestIssueMultiBoardResponse.Chart chart) {
    if (!chart.points().isEmpty()) {
      writeSheet(
          workbook,
          styles,
          safeSheetName(chart.title()),
          List.of("名称", "数值"),
          chart.points().stream().map(point -> List.of(point.name(), point.value())).toList());
      return;
    }
    List<String> headers = new ArrayList<>();
    headers.add("维度");
    headers.addAll(chart.series().stream().map(SystemTestIssueMultiBoardResponse.Series::name).toList());
    List<List<?>> values = new ArrayList<>();
    for (int index = 0; index < chart.categories().size(); index++) {
      List<Object> row = new ArrayList<>();
      row.add(chart.categories().get(index));
      for (SystemTestIssueMultiBoardResponse.Series series : chart.series()) {
        row.add(index < series.data().size() ? series.data().get(index).value() : BigDecimal.ZERO);
      }
      values.add(row);
    }
    writeSheet(workbook, styles, safeSheetName(chart.title()), headers, values);
  }

  private static void writeSheet(
      XSSFWorkbook workbook,
      Styles styles,
      String sheetName,
      List<String> headers,
      List<? extends List<?>> rows) {
    Sheet sheet = workbook.createSheet(sheetName);
    writeRow(sheet.createRow(0), headers, styles.header());
    for (int index = 0; index < rows.size(); index++) {
      writeRow(sheet.createRow(index + 1), rows.get(index), styles.body());
    }
    sheet.createFreezePane(0, 1);
    ExcelExportStyles.applyHeaderRows(sheet, 1);
    ExcelExportStyles.autoSizeColumns(sheet, headers.size());
  }

  private static void writeRow(Row row, List<?> values, CellStyle style) {
    for (int index = 0; index < values.size(); index++) {
      Cell cell = row.createCell(index);
      Object value = values.get(index);
      if (value instanceof Number number) {
        cell.setCellValue(number.doubleValue());
      } else {
        cell.setCellValue(value == null ? "" : String.valueOf(value));
      }
      cell.setCellStyle(style);
    }
  }

  private static SeveritySummary severitySummary(
      String name, List<SystemTestIssueMultiBoardService.IssueRow> rows) {
    return new SeveritySummary(
        name,
        severityCount(rows, "LEVEL1"),
        severityCount(rows, "LEVEL2"),
        severityCount(rows, "LEVEL3"),
        severityCount(rows, "SUGGESTION"));
  }

  private static long severityCount(
      List<SystemTestIssueMultiBoardService.IssueRow> rows, String severity) {
    return rows.stream().filter(row -> severity.equals(metricSeverity(row))).count();
  }

  private static String metricSeverity(SystemTestIssueMultiBoardService.IssueRow row) {
    return SystemTestIssueMetricDimensionSupport.metricSeverity(
        row.excluded(), row.exclusionReason(), row.severityLevel(), row.category());
  }

  private static String severityLabel(SystemTestIssueMultiBoardService.IssueRow row) {
    return switch (metricSeverity(row)) {
      case "LEVEL1" -> "一级缺陷";
      case "LEVEL2" -> "二级缺陷";
      case "LEVEL3" -> "三级缺陷";
      case "SUGGESTION" -> "建议类缺陷";
      default -> text(row.severityLevel());
    };
  }

  private static boolean matchesCause(
      SystemTestIssueMultiBoardService.IssueRow row, DefectCauseMetricCatalog.Metric metric) {
    return SystemTestIssueMetricDimensionSupport.matchesCauseMetric(
        metric.key(), row.reasonCategory(), row.labelNames());
  }

  private static String majorCause(SystemTestIssueMultiBoardService.IssueRow row) {
    return SystemTestIssueMetricDimensionSupport.majorCause(row.reasonCategory(), row.labelNames());
  }

  private static List<String> resolvedDelayCauses(SystemTestIssueMultiBoardService.IssueRow row) {
    return SystemTestIssueMetricDimensionSupport.delayCauses(
        row.delayCause(), row.delayReason(), row.labelNames());
  }

  private static List<String> modules(String value) {
    if (!StringUtils.hasText(value)) {
      return List.of();
    }
    return List.of(value.split("\\s*,\\s*")).stream()
        .map(TextQuerySupport::trimToNull)
        .filter(item -> item != null && !item.startsWith("未设定") && !item.startsWith("未标注"))
        .distinct()
        .toList();
  }

  private static String joinedModules(String value) {
    return String.join("&", modules(value));
  }

  private static String otherCause(String reasonText) {
    for (String marker : List.of("具体原因,请描述:", "具体原因, 请描述：")) {
      int index = reasonText.lastIndexOf(marker);
      if (index >= 0) {
        return reasonText.substring(index);
      }
    }
    return "";
  }

  private static String issueReference(Long iid) {
    return iid == null ? "" : "#" + iid;
  }

  private static String issueState(String state) {
    return "closed".equalsIgnoreCase(state) ? "CLOSED" : "OPEN";
  }

  private static String date(LocalDateTime value) {
    return value == null ? "" : DATE_FORMATTER.format(value);
  }

  private static String text(String value) {
    return value == null ? "" : value;
  }

  private static String safeSheetName(String title) {
    String value = WorkbookUtil.createSafeSheetName(title);
    return StringUtils.hasText(value) ? value : "议题多元看板";
  }

  private enum RawLayout {
    SEVERITY,
    PHASE,
    MODULE,
    CAUSE
  }

  private record Styles(CellStyle header, CellStyle body) {
    Styles(XSSFWorkbook workbook) {
      this(
          ExcelExportStyles.createHeaderStyle(workbook),
          ExcelExportStyles.createBodyStyle(workbook));
    }
  }

  private record SeveritySummary(
      String name, long level1, long level2, long level3, long suggestion) {
    private static final Comparator<SeveritySummary> ORDER =
        Comparator.comparingLong(SeveritySummary::total)
            .reversed()
            .thenComparing(Comparator.comparingLong(SeveritySummary::level1).reversed())
            .thenComparing(SeveritySummary::name);

    long total() {
      return level1 + level2 + level3 + suggestion;
    }

    List<?> values() {
      return List.of(name, level1, level2, level3, suggestion, total());
    }
  }

  private record LegacyRawIssue(
      String updatedDate,
      String submissionDate,
      String moduleName,
      String issueReference,
      String issueTitle,
      String author,
      String handler,
      String status,
      String bugStatus,
      String testingPhase,
      String severityLevel,
      String category,
      String milestone,
      String urgency,
      String cause,
      String majorCause,
      String secondCause,
      String otherCause,
      String delayCause,
      String fixUser) {

    List<?> severityValues() {
      return List.of(
          severityLevel, updatedDate, submissionDate, moduleName, issueReference, issueTitle, author,
          handler, status, bugStatus, testingPhase, category, milestone, urgency, cause, majorCause,
          secondCause, otherCause, delayCause, fixUser);
    }

    List<?> phaseValues() {
      return List.of(
          testingPhase, updatedDate, submissionDate, moduleName, issueReference, issueTitle, author,
          handler, testingPhase, status, bugStatus, severityLevel, category, milestone, urgency, cause,
          majorCause, secondCause, otherCause, delayCause, fixUser);
    }

    List<?> moduleValues() {
      return List.of(
          moduleName, severityLevel, updatedDate, submissionDate, issueReference, issueTitle, author,
          handler, status, bugStatus, testingPhase, milestone, urgency, cause, majorCause, secondCause,
          otherCause, delayCause, fixUser);
    }

    List<?> causeValues() {
      return List.of(
          majorCause, secondCause, updatedDate, submissionDate, moduleName, issueReference, issueTitle,
          author, handler, status, bugStatus, testingPhase, severityLevel, category, milestone, urgency,
          cause, otherCause, delayCause, fixUser);
    }
  }
}
