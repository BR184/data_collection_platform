package com.data.collection.platform.service;

import com.data.collection.platform.entity.SystemTestIllegalRecordRowResponse;
import com.data.collection.platform.entity.SystemTestIssueSearchRowResponse;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public final class SystemTestIssueRecordWorkbookExportSupport {
  private static final DateTimeFormatter LEGACY_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
  private static final String ISSUE_SEARCH_SHEET_NAME = "多元查询结果";
  private static final String ILLEGAL_SHEET_NAME = "议题数据";
  private static final List<String> LEGACY_ISSUE_HEADERS =
      List.of(
          "议题更新时间",
          "议题提交时间",
          "模块名",
          "议题编号",
          "议题标题",
          "议题提交人",
          "议题处理人",
          "议题状态",
          "测试状态",
          "测试阶段",
          "议题严重程度",
          "议题类别",
          "里程碑",
          "议题指派人",
          "优先级",
          "延期原因",
          "缺陷修复人",
          "功能名称",
          "修复状态",
          "一级缺陷原因",
          "二级缺陷原因",
          "具体原因",
          "修改方案",
          "由修改其他缺陷造成的",
          "修改该缺陷可能影响的功能",
          "是否对可能影响的功能进行了测试",
          "有无遗留问题或潜在的影响",
          "是否更新了关联关系表",
          "议题关闭时间");
  private static final List<String> LEGACY_ILLEGAL_HEADERS =
      append(LEGACY_ISSUE_HEADERS, "非法类型");

  private SystemTestIssueRecordWorkbookExportSupport() {
  }

  public static byte[] exportRecords(List<SystemTestIssueSearchRowResponse> rows) {
    return exportRecords(rows, List.of());
  }

  public static byte[] exportRecords(List<SystemTestIssueSearchRowResponse> rows, List<String> labelGroupSnapshots) {
    return exportWorkbook(
        ISSUE_SEARCH_SHEET_NAME,
        LEGACY_ISSUE_HEADERS,
        rows.stream().map(SystemTestIssueRecordWorkbookExportSupport::recordValues).toList(),
        labelGroupSnapshots);
  }

  public static byte[] exportIssueDataRecords(List<SystemTestIssueSearchRowResponse> rows) {
    return exportWorkbook(
        ILLEGAL_SHEET_NAME,
        LEGACY_ISSUE_HEADERS,
        rows.stream().map(SystemTestIssueRecordWorkbookExportSupport::recordValues).toList(),
        List.of());
  }

  static byte[] exportIllegalRecords(List<SystemTestIllegalRecordRowResponse> rows) {
    return exportWorkbook(
        ILLEGAL_SHEET_NAME,
        LEGACY_ILLEGAL_HEADERS,
        rows.stream().map(SystemTestIssueRecordWorkbookExportSupport::illegalRecordValues).toList(),
        List.of());
  }

  private static byte[] exportWorkbook(
      String sheetName, List<String> headers, List<List<String>> rows, List<String> labelGroupSnapshots) {
    try (Workbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet(sheetName);
      ExportStyles styles = new ExportStyles(workbook);
      writeSnapshotSheet(workbook, styles, labelGroupSnapshots);
      writeHeader(sheet, headers, styles.header);
      writeRows(sheet, rows, styles.body);
      freezeAndSize(sheet, headers.size());
      workbook.write(output);
      return output.toByteArray();
    } catch (Exception error) {
      throw new IllegalStateException("Failed to export system test issue workbook", error);
    }
  }

  private static void writeSnapshotSheet(Workbook workbook, ExportStyles styles, List<String> snapshots) {
    if (snapshots == null || snapshots.isEmpty()) {
      return;
    }
    Sheet sheet = workbook.createSheet("导出条件");
    Row header = sheet.createRow(0);
    cell(header, 0, "标签组筛选快照", styles.header);
    for (int index = 0; index < snapshots.size(); index++) {
      Row row = sheet.createRow(index + 1);
      cell(row, 0, snapshots.get(index), styles.body);
    }
    freezeAndSize(sheet, 1);
  }

  private static void writeHeader(Sheet sheet, List<String> headers, CellStyle style) {
    Row row = sheet.createRow(0);
    for (int index = 0; index < headers.size(); index++) {
      cell(row, index, headers.get(index), style);
    }
  }

  private static void writeRows(Sheet sheet, List<List<String>> rows, CellStyle style) {
    for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
      Row row = sheet.createRow(rowIndex + 1);
      List<String> values = rows.get(rowIndex);
      for (int columnIndex = 0; columnIndex < values.size(); columnIndex++) {
        cell(row, columnIndex, values.get(columnIndex), style);
      }
    }
  }

  private static List<String> recordValues(SystemTestIssueSearchRowResponse row) {
    return List.of(
        date(row.updatedAt()),
        date(row.createdAt()),
        text(row.moduleNames()),
        issueReference(row.issueIid()),
        text(row.title()),
        text(row.authorName()),
        text(row.assigneeName()),
        displayIssueState(row.issueState(), row.closedAt()),
        text(row.bugStatus()),
        text(row.testingPhase()),
        text(row.severityLevel()),
        text(row.category()),
        text(row.milestoneTitle()),
        text(row.assigneeName()),
        text(row.priorityLevel()),
        text(row.delayCause()),
        text(row.fixUser()),
        text(row.functionName()),
        text(row.fixStatus()),
        text(row.majorCause()),
        text(row.secondCause()),
        text(row.specificReason()),
        text(row.modification()),
        text(row.causedByOther()),
        text(row.effectFunction()),
        text(row.hasTested()),
        text(row.potentialImpact()),
        text(row.relationTableUpdated()),
        date(row.closedAt()));
  }

  private static List<String> illegalRecordValues(SystemTestIllegalRecordRowResponse row) {
    List<String> values =
        List.of(
            date(row.updatedAt()),
            date(row.createdAt()),
            text(row.moduleNames()),
            issueReference(row.issueIid()),
            text(row.title()),
            text(row.authorName()),
            text(row.assigneeName()),
            displayIssueState(row.issueState(), row.closedAt()),
            text(row.bugStatus()),
            text(row.testingPhase()),
            text(row.severityLevel()),
            text(row.category()),
            text(row.milestoneTitle()),
            text(row.assigneeName()),
            "",
            "",
            "",
            text(row.functionName()),
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            date(row.closedAt()),
            text(row.illegalReason()));
    return values;
  }

  private static String displayIssueState(String state, LocalDateTime closedAt) {
    if (closedAt != null || "closed".equalsIgnoreCase(state)) {
      return "已关闭";
    }
    if ("opened".equalsIgnoreCase(state) || "open".equalsIgnoreCase(state)) {
      return "未关闭";
    }
    return text(state);
  }

  private static String issueReference(Integer iid) {
    return iid == null ? "" : "#" + iid;
  }

  private static String date(LocalDateTime time) {
    return time == null ? "" : LEGACY_DATE_FORMATTER.format(time);
  }

  private static String text(String value) {
    return value == null ? "" : value;
  }

  private static Cell cell(Row row, int columnIndex, String value, CellStyle style) {
    Cell cell = row.createCell(columnIndex);
    cell.setCellValue(value == null ? "" : value);
    cell.setCellStyle(style);
    return cell;
  }

  private static void freezeAndSize(Sheet sheet, int columnCount) {
    sheet.createFreezePane(0, 1);
    ExcelExportStyles.applyHeaderRows(sheet, 1);
    ExcelExportStyles.autoSizeColumns(sheet, columnCount);
  }

  private static List<String> append(List<String> values, String extra) {
    java.util.ArrayList<String> result = new java.util.ArrayList<>(values);
    result.add(extra);
    return List.copyOf(result);
  }

  private static final class ExportStyles {
    private final CellStyle header;
    private final CellStyle body;

    private ExportStyles(Workbook workbook) {
      this.header = ExcelExportStyles.createHeaderStyle(workbook);
      this.body = ExcelExportStyles.createBodyStyle(workbook);
    }
  }
}
