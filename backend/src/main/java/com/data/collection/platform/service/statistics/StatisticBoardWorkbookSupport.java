package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.statistics.StatisticBoardResponse;
import com.data.collection.platform.entity.statistics.StatisticCellData;
import com.data.collection.platform.entity.statistics.StatisticColumnGroup;
import com.data.collection.platform.entity.statistics.StatisticColumnLeaf;
import com.data.collection.platform.entity.statistics.StatisticRowData;
import com.data.collection.platform.service.ExcelExportStyles;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

final class StatisticBoardWorkbookSupport {
  private static final int MAX_AUTO_SIZE_COLUMNS = 80;

  private StatisticBoardWorkbookSupport() {
  }

  static byte[] export(StatisticBoardResponse response) {
    try (Workbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet(safeSheetName(response.definition().title()));
      ExportStyles styles = new ExportStyles(workbook);
      List<StatisticColumnGroup> groups = response.definition().columnGroups();
      List<StatisticColumnLeaf> leaves = groups.stream().flatMap(group -> group.leafColumns().stream()).toList();
      int headerDepth = Math.max(1, groups.stream().mapToInt(StatisticBoardWorkbookSupport::depth).max().orElse(1));
      writeHeaders(sheet, response, groups, headerDepth, styles);
      writeRows(sheet, response.rows(), leaves, headerDepth, styles);
      freezeAndSize(sheet, leaves.size() + 1, headerDepth);
      workbook.write(output);
      return output.toByteArray();
    } catch (Exception error) {
      throw new IllegalStateException("Failed to export statistic board workbook", error);
    }
  }

  private static void writeHeaders(
      org.apache.poi.ss.usermodel.Sheet sheet,
      StatisticBoardResponse response,
      List<StatisticColumnGroup> groups,
      int headerDepth,
      ExportStyles styles) {
    for (int index = 0; index < headerDepth; index++) {
      sheet.createRow(index);
    }
    Cell rowHeader = cell(sheet.getRow(0), 0, response.definition().rowHeaderLabel(), styles.header);
    if (headerDepth > 1) {
      sheet.addMergedRegion(new CellRangeAddress(0, headerDepth - 1, 0, 0));
      applyMergedStyle(sheet, 0, headerDepth - 1, 0, 0, styles.header);
    }
    int columnIndex = 1;
    for (StatisticColumnGroup group : groups) {
      columnIndex = writeGroup(sheet, group, 0, columnIndex, headerDepth, styles);
    }
    rowHeader.setCellStyle(styles.header);
  }

  private static int writeGroup(
      org.apache.poi.ss.usermodel.Sheet sheet,
      StatisticColumnGroup group,
      int level,
      int startColumn,
      int headerDepth,
      ExportStyles styles) {
    int columnSpan = Math.max(1, group.columnCount());
    cell(sheet.getRow(level), startColumn, group.label(), styles.header);
    if (columnSpan > 1) {
      sheet.addMergedRegion(new CellRangeAddress(level, level, startColumn, startColumn + columnSpan - 1));
      applyMergedStyle(sheet, level, level, startColumn, startColumn + columnSpan - 1, styles.header);
    }

    int nextColumn = startColumn;
    int childLevel = level + 1;
    for (StatisticColumnGroup child : group.children()) {
      nextColumn = writeGroup(sheet, child, childLevel, nextColumn, headerDepth, styles);
    }
    for (StatisticColumnLeaf leaf : group.columns()) {
      writeLeaf(sheet, leaf, childLevel, nextColumn, headerDepth, styles);
      nextColumn += 1;
    }
    return startColumn + columnSpan;
  }

  private static void writeLeaf(
      org.apache.poi.ss.usermodel.Sheet sheet,
      StatisticColumnLeaf leaf,
      int level,
      int columnIndex,
      int headerDepth,
      ExportStyles styles) {
    int safeLevel = Math.min(level, headerDepth - 1);
    cell(sheet.getRow(safeLevel), columnIndex, leaf.label(), styles.header);
    if (safeLevel < headerDepth - 1) {
      sheet.addMergedRegion(new CellRangeAddress(safeLevel, headerDepth - 1, columnIndex, columnIndex));
      applyMergedStyle(sheet, safeLevel, headerDepth - 1, columnIndex, columnIndex, styles.header);
    }
  }

  private static void writeRows(
      org.apache.poi.ss.usermodel.Sheet sheet,
      List<StatisticRowData> rows,
      List<StatisticColumnLeaf> leaves,
      int headerDepth,
      ExportStyles styles) {
    int rowIndex = headerDepth;
    for (StatisticRowData dataRow : rows) {
      Row row = sheet.createRow(rowIndex++);
      cell(row, 0, dataRow.rowLabel(), styles.rowHeader);
      Map<String, StatisticCellData> cellsByColumn = new HashMap<>();
      for (StatisticCellData cell : dataRow.cells()) {
        cellsByColumn.put(cell.columnKey(), cell);
      }
      for (int columnIndex = 0; columnIndex < leaves.size(); columnIndex++) {
        StatisticColumnLeaf leaf = leaves.get(columnIndex);
        StatisticCellData cellData = cellsByColumn.get(leaf.key());
        String value = cellData == null ? "" : cellData.displayValue();
        cell(row, columnIndex + 1, value, styles.body);
      }
    }
  }

  private static int depth(StatisticColumnGroup group) {
    int childDepth = group.children().stream().mapToInt(StatisticBoardWorkbookSupport::depth).max().orElse(0);
    int leafDepth = group.columns().isEmpty() ? 0 : 1;
    return 1 + Math.max(childDepth, leafDepth);
  }

  private static void freezeAndSize(org.apache.poi.ss.usermodel.Sheet sheet, int columnCount, int headerDepth) {
    sheet.createFreezePane(1, headerDepth);
    ExcelExportStyles.applyHeaderRows(sheet, headerDepth);
    ExcelExportStyles.autoSizeColumns(sheet, columnCount, MAX_AUTO_SIZE_COLUMNS);
  }

  private static Cell cell(Row row, int columnIndex, String value, CellStyle style) {
    Cell cell = row.createCell(columnIndex);
    cell.setCellValue(value == null ? "" : value);
    cell.setCellStyle(style);
    return cell;
  }

  private static void applyMergedStyle(
      org.apache.poi.ss.usermodel.Sheet sheet,
      int firstRow,
      int lastRow,
      int firstColumn,
      int lastColumn,
      CellStyle style) {
    for (int rowIndex = firstRow; rowIndex <= lastRow; rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row == null) {
        row = sheet.createRow(rowIndex);
      }
      for (int columnIndex = firstColumn; columnIndex <= lastColumn; columnIndex++) {
        Cell cell = row.getCell(columnIndex);
        if (cell == null) {
          cell = row.createCell(columnIndex);
        }
        cell.setCellStyle(style);
      }
    }
  }

  private static String safeSheetName(String title) {
    String normalized = title == null || title.isBlank() ? "统计看板" : title.trim();
    normalized = normalized.replaceAll("[\\\\/?*\\[\\]:]", "_");
    return normalized.length() > 31 ? normalized.substring(0, 31) : normalized;
  }

  private static final class ExportStyles {
    final CellStyle header;
    final CellStyle rowHeader;
    final CellStyle body;

    ExportStyles(Workbook workbook) {
      this.header = ExcelExportStyles.createHeaderStyle(workbook);
      this.rowHeader = ExcelExportStyles.createBodyStyle(workbook);
      this.body = ExcelExportStyles.createBodyStyle(workbook);
    }
  }
}
