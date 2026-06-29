package com.data.collection.platform.service;

import com.data.collection.platform.entity.CustomerIssueIllegalRecordRowResponse;
import com.data.collection.platform.entity.CustomerIssueRecordRowResponse;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

final class CustomerIssueRecordWorkbookExportSupport {
  private static final DateTimeFormatter LEGACY_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
  private static final String SHEET_NAME = "数据";
  private static final List<String> LEGACY_RECORD_HEADERS =
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
          "功能名称");
  private static final List<String> LEGACY_ILLEGAL_HEADERS =
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
          "关闭时间",
          "非法类型");

  private CustomerIssueRecordWorkbookExportSupport() {
  }

  static byte[] exportRecords(List<CustomerIssueRecordRowResponse> rows) {
    return exportWorkbook(LEGACY_RECORD_HEADERS, rows.stream().map(CustomerIssueRecordWorkbookExportSupport::recordValues).toList());
  }

  static byte[] exportIllegalRecords(List<CustomerIssueIllegalRecordRowResponse> rows) {
    return exportWorkbook(
        LEGACY_ILLEGAL_HEADERS,
        rows.stream().map(CustomerIssueRecordWorkbookExportSupport::illegalRecordValues).toList());
  }

  private static byte[] exportWorkbook(List<String> headers, List<List<String>> rows) {
    try (Workbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet(SHEET_NAME);
      ExportStyles styles = new ExportStyles(workbook);
      writeHeader(sheet, headers, styles.header);
      writeRows(sheet, rows, styles.body);
      freezeAndSize(sheet, headers.size());
      workbook.write(output);
      return output.toByteArray();
    } catch (Exception error) {
      throw new IllegalStateException("Failed to export customer issue workbook", error);
    }
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

  private static List<String> recordValues(CustomerIssueRecordRowResponse row) {
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
        "",
        text(row.severityLevel()),
        text(row.category()),
        text(row.milestoneTitle()),
        text(row.assigneeName()),
        text(row.priorityLevel()),
        text(row.delayCause()),
        "",
        text(row.functionName()));
  }

  private static List<String> illegalRecordValues(CustomerIssueIllegalRecordRowResponse row) {
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
        "",
        text(row.functionName()),
        date(row.closedAt()),
        text(row.illegalReason()));
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
    for (int index = 0; index < columnCount; index++) {
      sheet.autoSizeColumn(index);
      int currentWidth = sheet.getColumnWidth(index);
      sheet.setColumnWidth(index, Math.min(Math.max(currentWidth + 512, 2800), 12000));
    }
  }

  private static final class ExportStyles {
    private final CellStyle header;
    private final CellStyle body;

    private ExportStyles(Workbook workbook) {
      this.header = workbook.createCellStyle();
      header.setAlignment(HorizontalAlignment.CENTER);
      header.setVerticalAlignment(VerticalAlignment.CENTER);
      header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
      header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      applyBorder(header);

      this.body = workbook.createCellStyle();
      body.setAlignment(HorizontalAlignment.CENTER);
      body.setVerticalAlignment(VerticalAlignment.CENTER);
      applyBorder(body);
    }

    private static void applyBorder(CellStyle style) {
      style.setBorderTop(BorderStyle.THIN);
      style.setBorderBottom(BorderStyle.THIN);
      style.setBorderLeft(BorderStyle.THIN);
      style.setBorderRight(BorderStyle.THIN);
    }
  }
}
