package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

@Service
public class IntegrationTestExcelExportService {
  private final IntegrationTestQueryService queryService;

  public IntegrationTestExcelExportService(IntegrationTestQueryService queryService) {
    this.queryService = queryService;
  }

  public byte[] exportModuleFunctionWorkbook(
      Long projectId, String testingPhase, String sourceInstance) {
    IntegrationTestModuleFunctionExportData data =
        queryService.getModuleFunctionExportData(projectId, testingPhase, sourceInstance);
    try (Workbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      ExportStyles styles = new ExportStyles(workbook);
      var sheet = workbook.createSheet("ALL");
      writeModuleFunctionSheet(sheet, styles, data);
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException e) {
      throw new BizException("集成测试 Excel 导出失败");
    }
  }

  public byte[] exportComparisonWorkbook(
      Long projectId, String basePhase, String targetPhase, String sourceInstance) {
    IntegrationTestComparisonExportData data =
        queryService.getComparisonExportData(projectId, basePhase, targetPhase, sourceInstance);
    try (Workbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      ExportStyles styles = new ExportStyles(workbook);
      var sheet = workbook.createSheet(safeSheetName(data.basePhase() + "-" + data.targetPhase()));
      writeComparisonSheet(sheet, styles, data);
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException e) {
      throw new BizException("集成测试横向对比 Excel 导出失败");
    }
  }

  private void writeModuleFunctionSheet(
      org.apache.poi.ss.usermodel.Sheet sheet,
      ExportStyles styles,
      IntegrationTestModuleFunctionExportData data) {
    String[] headers = {
      "功能名",
      "执行用例数",
      "通过用例数",
      "测试通过率",
      "初始未通过用例数",
      "本次未通过用例数",
      "问题用例数",
      "例外问题数",
      "功能标签",
      "模块名称",
      "模块通过率",
      "模块执行用例数",
      "模块通过用例数"
    };
    writeHeaderRow(sheet.createRow(0), styles.header, headers);
    List<IntegrationTestFunctionExportRow> functionRows = data.functionRows();
    List<IntegrationTestModuleExportRow> moduleRows = data.moduleRows();
    int rowCount = Math.max(functionRows.size(), moduleRows.size());
    for (int index = 0; index < rowCount; index++) {
      Row row = sheet.createRow(index + 1);
      if (index < functionRows.size()) {
        IntegrationTestFunctionExportRow item = functionRows.get(index);
        writeText(row, 0, item.functionName(), styles.body);
        writeNumber(row, 1, item.executeCase(), styles.body);
        writeNumber(row, 2, item.passCase(), styles.body);
        writePercent(row, 3, item.passRate(), styles.percent);
        writeNumber(row, 4, item.notPassCase(), styles.body);
        writeNumber(row, 5, item.notPassCaseNow(), styles.body);
        writeNumber(row, 6, item.problemCase(), styles.body);
        writeNumber(row, 7, item.exceptionCount(), styles.body);
        writeText(row, 8, item.functionLabels(), styles.body);
      }
      if (index < moduleRows.size()) {
        IntegrationTestModuleExportRow item = moduleRows.get(index);
        writeText(row, 9, item.moduleName(), styles.body);
        writePercent(row, 10, item.passRate(), styles.percent);
        writeNumber(row, 11, item.executeCase(), styles.body);
        writeNumber(row, 12, item.passCase(), styles.body);
      }
    }
    setColumnWidths(sheet, 18, 12, 12, 12, 16, 16, 12, 12, 16, 16, 12, 14, 14);
    sheet.createFreezePane(0, 1);
  }

  private void writeComparisonSheet(
      org.apache.poi.ss.usermodel.Sheet sheet,
      ExportStyles styles,
      IntegrationTestComparisonExportData data) {
    Row firstHeader = sheet.createRow(0);
    Row secondHeader = sheet.createRow(1);
    mergeHeader(sheet, firstHeader, secondHeader, 0, "模块名称", styles.header);
    mergeHeader(sheet, firstHeader, secondHeader, 1, "功能名称", styles.header);
    int column = 2;
    column = writeMetricHeader(sheet, firstHeader, secondHeader, column, "测试通过率(%)", data, styles.header);
    column = writeMetricHeader(sheet, firstHeader, secondHeader, column, "执行用例数(个)", data, styles.header);
    column = writeMetricHeader(sheet, firstHeader, secondHeader, column, "通过用例数(个)", data, styles.header);
    column = writeMetricHeader(sheet, firstHeader, secondHeader, column, "初始未通过用例数(个)", data, styles.header);
    column = writeMetricHeader(sheet, firstHeader, secondHeader, column, "未通过用例数(个)", data, styles.header);
    column = writeMetricHeader(sheet, firstHeader, secondHeader, column, "问题用例数(个)", data, styles.header);
    column = writeMetricHeader(sheet, firstHeader, secondHeader, column, "用例外问题数(个)", data, styles.header);
    writePairedHeader(sheet, firstHeader, secondHeader, column, "功能标签", data, styles.header);

    int rowIndex = 2;
    for (IntegrationTestComparisonExportRow item : data.rows()) {
      Row row = sheet.createRow(rowIndex++);
      writeText(row, 0, item.moduleName(), styles.body);
      writeText(row, 1, item.functionName(), styles.body);
      int dataColumn = 2;
      dataColumn = writeMetric(row, dataColumn, item, MetricKind.PASS_RATE, styles);
      dataColumn = writeMetric(row, dataColumn, item, MetricKind.EXECUTE_CASE, styles);
      dataColumn = writeMetric(row, dataColumn, item, MetricKind.PASS_CASE, styles);
      dataColumn = writeMetric(row, dataColumn, item, MetricKind.NOT_PASS_CASE, styles);
      dataColumn = writeMetric(row, dataColumn, item, MetricKind.NOT_PASS_CASE_NOW, styles);
      dataColumn = writeMetric(row, dataColumn, item, MetricKind.PROBLEM_CASE, styles);
      dataColumn = writeMetric(row, dataColumn, item, MetricKind.EXCEPTION_COUNT, styles);
      writeText(row, dataColumn, labels(item.base()), styles.body);
      writeText(row, dataColumn + 1, labels(item.target()), styles.body);
    }
    setColumnWidths(
        sheet,
        14, 28, 14, 14, 10, 14, 14, 10, 14, 14, 10, 18, 18, 10, 14, 14, 10,
        14, 14, 10, 16, 16, 10, 18, 18);
    sheet.createFreezePane(2, 2);
  }

  private int writeMetric(
      Row row,
      int column,
      IntegrationTestComparisonExportRow item,
      MetricKind kind,
      ExportStyles styles) {
    CellStyle style = kind == MetricKind.PASS_RATE ? styles.percent : styles.body;
    writeMetricValue(row, column, value(item.base(), kind), style);
    writeMetricValue(row, column + 1, value(item.target(), kind), style);
    writeMetricValue(row, column + 2, value(item.diff(), kind), style);
    return column + 3;
  }

  private void writeMetricValue(Row row, int column, Object value, CellStyle style) {
    if (value instanceof BigDecimal decimal) {
      writePercent(row, column, decimal, style);
      return;
    }
    if (value instanceof Integer integer) {
      writeNumber(row, column, integer, style);
      return;
    }
    writeText(row, column, "", style);
  }

  private Object value(IntegrationTestComparisonMetric metric, MetricKind kind) {
    if (metric == null) {
      return null;
    }
    return switch (kind) {
      case PASS_RATE -> metric.passRate();
      case EXECUTE_CASE -> metric.executeCase();
      case PASS_CASE -> metric.passCase();
      case NOT_PASS_CASE -> metric.notPassCase();
      case NOT_PASS_CASE_NOW -> metric.notPassCaseNow();
      case PROBLEM_CASE -> metric.problemCase();
      case EXCEPTION_COUNT -> metric.exceptionCount();
    };
  }

  private int writeMetricHeader(
      org.apache.poi.ss.usermodel.Sheet sheet,
      Row firstHeader,
      Row secondHeader,
      int startColumn,
      String label,
      IntegrationTestComparisonExportData data,
      CellStyle style) {
    sheet.addMergedRegion(new CellRangeAddress(0, 0, startColumn, startColumn + 2));
    writeText(firstHeader, startColumn, label, style);
    writeText(firstHeader, startColumn + 1, "", style);
    writeText(firstHeader, startColumn + 2, "", style);
    writeText(secondHeader, startColumn, data.basePhase(), style);
    writeText(secondHeader, startColumn + 1, data.targetPhase(), style);
    writeText(secondHeader, startColumn + 2, "差值", style);
    return startColumn + 3;
  }

  private void writePairedHeader(
      org.apache.poi.ss.usermodel.Sheet sheet,
      Row firstHeader,
      Row secondHeader,
      int startColumn,
      String label,
      IntegrationTestComparisonExportData data,
      CellStyle style) {
    sheet.addMergedRegion(new CellRangeAddress(0, 0, startColumn, startColumn + 1));
    writeText(firstHeader, startColumn, label, style);
    writeText(firstHeader, startColumn + 1, "", style);
    writeText(secondHeader, startColumn, data.basePhase(), style);
    writeText(secondHeader, startColumn + 1, data.targetPhase(), style);
  }

  private void mergeHeader(
      org.apache.poi.ss.usermodel.Sheet sheet,
      Row firstHeader,
      Row secondHeader,
      int column,
      String label,
      CellStyle style) {
    sheet.addMergedRegion(new CellRangeAddress(0, 1, column, column));
    writeText(firstHeader, column, label, style);
    writeText(secondHeader, column, "", style);
  }

  private void writeHeaderRow(Row row, CellStyle style, String[] headers) {
    for (int index = 0; index < headers.length; index++) {
      writeText(row, index, headers[index], style);
    }
  }

  private void writeText(Row row, int column, String value, CellStyle style) {
    Cell cell = row.createCell(column);
    cell.setCellValue(value == null ? "" : value);
    cell.setCellStyle(style);
  }

  private void writeNumber(Row row, int column, int value, CellStyle style) {
    Cell cell = row.createCell(column);
    cell.setCellValue(value);
    cell.setCellStyle(style);
  }

  private void writePercent(Row row, int column, BigDecimal value, CellStyle style) {
    Cell cell = row.createCell(column);
    cell.setCellValue(value == null ? 0 : value.doubleValue() / 100);
    cell.setCellStyle(style);
  }

  private String labels(IntegrationTestComparisonMetric metric) {
    return metric == null ? "" : metric.functionLabels();
  }

  private void setColumnWidths(org.apache.poi.ss.usermodel.Sheet sheet, int... widths) {
    for (int index = 0; index < widths.length; index++) {
      sheet.setColumnWidth(index, widths[index] * 256);
    }
  }

  private String safeSheetName(String name) {
    String normalized = TextQuerySupport.trimToNull(name);
    if (normalized == null) {
      return "ALL";
    }
    return normalized.replaceAll("[\\\\/?*\\[\\]:]", "-").substring(0, Math.min(31, normalized.length()));
  }

  private enum MetricKind {
    PASS_RATE,
    EXECUTE_CASE,
    PASS_CASE,
    NOT_PASS_CASE,
    NOT_PASS_CASE_NOW,
    PROBLEM_CASE,
    EXCEPTION_COUNT
  }

  private static final class ExportStyles {
    private final CellStyle header;
    private final CellStyle body;
    private final CellStyle percent;

    private ExportStyles(Workbook workbook) {
      header = workbook.createCellStyle();
      header.setAlignment(HorizontalAlignment.CENTER);
      header.setVerticalAlignment(VerticalAlignment.CENTER);
      header.setWrapText(true);
      header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
      header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      addBorder(header);
      var font = workbook.createFont();
      font.setBold(true);
      font.setFontName("Arial");
      header.setFont(font);

      body = workbook.createCellStyle();
      body.setAlignment(HorizontalAlignment.CENTER);
      body.setVerticalAlignment(VerticalAlignment.CENTER);
      body.setWrapText(true);
      addBorder(body);
      var bodyFont = workbook.createFont();
      bodyFont.setFontName("Arial");
      body.setFont(bodyFont);

      percent = workbook.createCellStyle();
      percent.cloneStyleFrom(body);
      percent.setDataFormat(workbook.createDataFormat().getFormat("0.00%"));
    }

    private static void addBorder(CellStyle style) {
      style.setBorderTop(BorderStyle.THIN);
      style.setBorderRight(BorderStyle.THIN);
      style.setBorderBottom(BorderStyle.THIN);
      style.setBorderLeft(BorderStyle.THIN);
    }
  }
}
