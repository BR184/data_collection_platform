package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.statistics.StatisticBoardResponse;
import com.data.collection.platform.entity.statistics.StatisticCellData;
import com.data.collection.platform.entity.statistics.StatisticRowData;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.util.StringUtils;

final class SystemTestLegacyWorkbookExportSupport {
  private static final List<ExportColumn> DEFECT_SUMMARY_COLUMNS =
      List.of(
          column("moduleName", "模块名"),
          column("level1_back", "一级缺陷", "分类", "回退"),
          column("level1_hang", "一级缺陷", "分类", "挂机"),
          column("level1_other", "一级缺陷", "分类", "其他"),
          column("level1_fixed", "一级缺陷", "一级缺陷已修复数量"),
          column("level1_total", "一级缺陷", "一级缺陷数量(个)"),
          column("level1_rate", "一级缺陷", "一级缺陷修复率（%）"),
          column("level2_fixed", "二级缺陷", "二级缺陷已修复数量"),
          column("level2_total", "二级缺陷", "二级缺陷（个）"),
          column("level2_rate", "二级缺陷", "二级缺陷修复率(%)"),
          column("level3_fixed", "三级缺陷", "三级缺陷修复数量"),
          column("level3_total", "三级缺陷", "三级缺陷(个)"),
          column("level3_rate", "三级缺陷", "三级缺陷修复率(%)"),
          column("suggestion_total", "建议类缺陷(个)"),
          column("p1_count", "P1", "P1级别缺陷"),
          column("p1_fix_rate", "P1", "P1缺陷修复率(%)"),
          column("p1_close_rate", "P1", "P1缺陷关闭率(%)"),
          column("p2_count", "P2", "P2级别缺陷"),
          column("p2_fix_rate", "P2", "P2缺陷修复率(%)"),
          column("p3_count", "P3", "P3级别缺陷"),
          column("p3_fix_rate", "P3", "P3缺陷修复率(%)"),
          column("module_total", "模块总缺陷数(个)"),
          column("defect_ratio", "缺陷占比(%)"),
          column("delay_defect_ratio", "延期缺陷占比(%)"),
          column("solved_count", "已修复/未更新"),
          column("fix_rate", "修复率(%)"),
          column("close_rate", "关闭率(%)"),
          column("open_count", "未关闭缺陷数(个)"),
          column("extension_count", "申请延期(个)"),
          column("retest_failed_count", "复测未通过缺陷数(个)"),
          column("new_issue_fixed", "新发议题", "新发议题修复数量"),
          column("new_issue_total", "新发议题", "新发议题数量"),
          column("new_issue_fix_rate", "新发议题", "新发缺陷修复率(%)"),
          column("new_issue_close_rate", "新发议题", "新发缺陷关闭率(%)"),
          column("level1_legacy_rate", "遗留率", "一级缺陷遗留率(%)"),
          column("level2_legacy_count", "遗留率", "二级缺陷遗留数量"),
          column("level3_legacy_count", "遗留率", "三级缺陷遗留数量"),
          column("level23_legacy_rate", "遗留率", "二三级缺陷遗留率(%)"));

  private static final List<ExportColumn> DEFECT_CAUSE_COLUMNS =
      DefectCauseMetricCatalog.METRICS.stream()
          .map(metric -> column(metric.key(), metric.groupLabel(), metric.label()))
          .toList();

  private static final List<ExportColumn> DELAY_ANALYSIS_COLUMNS =
      List.of(
          column("delayCause", "伦次"),
          column("level1", "一级缺陷"),
          column("level2", "二级缺陷"),
          column("level3", "三级缺陷"),
          column("suggestion", "建议类缺陷"),
          column("total", "总计"));

  private SystemTestLegacyWorkbookExportSupport() {}

  static byte[] exportDefectSummary(StatisticBoardResponse response) {
    return export(response, "系统测试缺陷汇总统计", DEFECT_SUMMARY_COLUMNS);
  }

  static byte[] exportDefectCause(StatisticBoardResponse response) {
    return export(response, "缺陷原因统计表", prependRowColumn("模块", DEFECT_CAUSE_COLUMNS));
  }

  static byte[] exportDelayAnalysis(StatisticBoardResponse response) {
    return export(response, "申请延期缺陷原因分析", DELAY_ANALYSIS_COLUMNS);
  }

  private static byte[] export(
      StatisticBoardResponse response,
      String sheetName,
      List<ExportColumn> columns) {
    int headerDepth = columns.stream().mapToInt(column -> column.header().size()).max().orElse(1);
    try (Workbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet(safeSheetName(sheetName));
      ExportStyles styles = new ExportStyles(workbook);
      writeHeaders(sheet, columns, headerDepth, styles.header);
      writeDataRows(sheet, response.rows(), columns, headerDepth, styles);
      sheet.createFreezePane(1, headerDepth);
      for (int index = 0; index < columns.size(); index++) {
        sheet.autoSizeColumn(index);
        int width = sheet.getColumnWidth(index);
        sheet.setColumnWidth(index, Math.min(Math.max(width + 512, 2800), 12000));
      }
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException error) {
      throw new IllegalStateException(sheetName + "导出失败", error);
    }
  }

  private static void writeHeaders(
      Sheet sheet,
      List<ExportColumn> columns,
      int headerDepth,
      CellStyle headerStyle) {
    for (int rowIndex = 0; rowIndex < headerDepth; rowIndex++) {
      sheet.createRow(rowIndex);
    }
    for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
      List<String> normalized = columns.get(columnIndex).normalizedHeader(headerDepth);
      for (int rowIndex = 0; rowIndex < headerDepth; rowIndex++) {
        createStringCell(sheet.getRow(rowIndex), columnIndex, normalized.get(rowIndex), headerStyle);
      }
    }
    mergeVerticalHeaders(sheet, columns.size(), headerDepth);
    mergeHorizontalHeaders(sheet, columns.size(), headerDepth);
  }

  private static void mergeVerticalHeaders(Sheet sheet, int columnCount, int headerDepth) {
    for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
      int rowIndex = 0;
      while (rowIndex < headerDepth - 1) {
        String value = cellString(sheet, rowIndex, columnIndex);
        int endRow = rowIndex;
        while (endRow + 1 < headerDepth && value.equals(cellString(sheet, endRow + 1, columnIndex))) {
          endRow++;
        }
        if (endRow > rowIndex && StringUtils.hasText(value)) {
          sheet.addMergedRegion(new CellRangeAddress(rowIndex, endRow, columnIndex, columnIndex));
        }
        rowIndex = endRow + 1;
      }
    }
  }

  private static void mergeHorizontalHeaders(Sheet sheet, int columnCount, int headerDepth) {
    for (int rowIndex = 0; rowIndex < headerDepth; rowIndex++) {
      int columnIndex = 0;
      while (columnIndex < columnCount - 1) {
        String value = cellString(sheet, rowIndex, columnIndex);
        int endColumn = columnIndex;
        while (endColumn + 1 < columnCount && value.equals(cellString(sheet, rowIndex, endColumn + 1))) {
          endColumn++;
        }
        if (endColumn > columnIndex && shouldMergeHorizontally(sheet, rowIndex, columnIndex, endColumn, headerDepth)) {
          sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, columnIndex, endColumn));
        }
        columnIndex = endColumn + 1;
      }
    }
  }

  private static boolean shouldMergeHorizontally(
      Sheet sheet,
      int rowIndex,
      int startColumn,
      int endColumn,
      int headerDepth) {
    if (!StringUtils.hasText(cellString(sheet, rowIndex, startColumn))) {
      return false;
    }
    for (int columnIndex = startColumn; columnIndex <= endColumn; columnIndex++) {
      if (rowIndex + 1 < headerDepth && !StringUtils.hasText(cellString(sheet, rowIndex + 1, columnIndex))) {
        return false;
      }
    }
    return true;
  }

  private static void writeDataRows(
      Sheet sheet,
      List<StatisticRowData> rows,
      List<ExportColumn> columns,
      int headerDepth,
      ExportStyles styles) {
    int rowIndex = headerDepth;
    for (StatisticRowData rowData : rows) {
      Row row = sheet.createRow(rowIndex++);
      CellStyle style = ("__total__".equals(rowData.rowKey()) || "__ratio__".equals(rowData.rowKey()))
          ? styles.summary
          : styles.body;
      Map<String, StatisticCellData> cells = new LinkedHashMap<>();
      for (StatisticCellData cell : rowData.cells()) {
        cells.put(cell.columnKey(), cell);
      }
      for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
        ExportColumn column = columns.get(columnIndex);
        String value = column.value(rowData, cells);
        createValueCell(row, columnIndex, value, style);
      }
    }
  }

  private static List<ExportColumn> prependRowColumn(String rowHeader, List<ExportColumn> columns) {
    List<ExportColumn> result = new ArrayList<>();
    result.add(column("rowLabel", rowHeader));
    result.addAll(columns);
    return List.copyOf(result);
  }

  private static ExportColumn column(String key, String... header) {
    return new ExportColumn(key, List.of(header));
  }

  private static void createValueCell(Row row, int columnIndex, String value, CellStyle style) {
    Cell cell = row.createCell(columnIndex);
    String safeValue = value == null ? "" : value;
    if (safeValue.matches("-?\\d+")) {
      cell.setCellValue(Long.parseLong(safeValue));
    } else {
      cell.setCellValue(safeValue);
    }
    cell.setCellStyle(style);
  }

  private static void createStringCell(Row row, int columnIndex, String value, CellStyle style) {
    Cell cell = row.createCell(columnIndex);
    cell.setCellValue(value == null ? "" : value);
    cell.setCellStyle(style);
  }

  private static String cellString(Sheet sheet, int rowIndex, int columnIndex) {
    Row row = sheet.getRow(rowIndex);
    if (row == null) {
      return "";
    }
    Cell cell = row.getCell(columnIndex);
    return cell == null ? "" : cell.getStringCellValue();
  }

  private static String safeSheetName(String name) {
    String normalized = StringUtils.hasText(name) ? name.trim() : "Sheet1";
    normalized = normalized.replaceAll("[\\\\/?*\\[\\]:]", "_");
    return normalized.length() > 31 ? normalized.substring(0, 31) : normalized;
  }

  private record ExportColumn(String key, List<String> header) {
    List<String> normalizedHeader(int headerDepth) {
      List<String> labels = new ArrayList<>(header);
      String last = labels.get(labels.size() - 1);
      while (labels.size() < headerDepth) {
        labels.add(last);
      }
      return labels;
    }

    String value(StatisticRowData rowData, Map<String, StatisticCellData> cells) {
      if ("rowLabel".equals(key) || "moduleName".equals(key) || "delayCause".equals(key)) {
        return rowData.rowLabel();
      }
      StatisticCellData cell = cells.get(key);
      return cell == null ? "" : cell.displayValue();
    }
  }

  private static final class ExportStyles {
    private final CellStyle header;
    private final CellStyle body;
    private final CellStyle summary;

    private ExportStyles(Workbook workbook) {
      header = workbook.createCellStyle();
      header.setAlignment(HorizontalAlignment.CENTER);
      header.setVerticalAlignment(VerticalAlignment.CENTER);
      header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
      header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      setBorders(header);

      body = workbook.createCellStyle();
      body.setAlignment(HorizontalAlignment.CENTER);
      body.setVerticalAlignment(VerticalAlignment.CENTER);
      setBorders(body);

      summary = workbook.createCellStyle();
      summary.setAlignment(HorizontalAlignment.CENTER);
      summary.setVerticalAlignment(VerticalAlignment.CENTER);
      summary.setFillForegroundColor(IndexedColors.LIGHT_TURQUOISE.getIndex());
      summary.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      setBorders(summary);
    }

    private static void setBorders(CellStyle style) {
      style.setBorderTop(BorderStyle.THIN);
      style.setBorderBottom(BorderStyle.THIN);
      style.setBorderLeft(BorderStyle.THIN);
      style.setBorderRight(BorderStyle.THIN);
    }
  }
}
