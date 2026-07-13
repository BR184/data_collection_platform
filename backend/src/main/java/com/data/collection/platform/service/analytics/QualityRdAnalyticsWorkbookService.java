package com.data.collection.platform.service.analytics;

import com.data.collection.platform.service.ExcelExportStyles;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

/** Excel layouts retained from the old-platform assignee remaining-defect page. */
@Service
public class QualityRdAnalyticsWorkbookService {
  byte[] assigneeSummary(
      List<QualityRdAnalyticsDetailQueryService.AssigneeSummaryRow> rows) {
    return workbook(
        "指派人剩余缺陷数量",
        List.of("功能名称", "缺陷数量"),
        rows,
        false,
        (row, value, style) -> {
          write(row, 0, value.assigneeName(), style);
          write(row, 1, value.remainingCount(), style);
        });
  }

  byte[] ccDetails(
      List<QualityRdAnalyticsDetailQueryService.CcAssigneeDetailRow> rows) {
    return workbook(
        "指派人剩余缺陷数量",
        List.of(
            "模块名称",
            "指派人",
            "open缺陷数",
            "已修复",
            "剩余缺陷",
            "未复现",
            "需求如此",
            "技术卡点",
            "修复率",
            "一级缺陷数",
            "一级缺陷修复数",
            "一级缺陷修复率",
            "P1缺陷数量",
            "P1缺陷修复数量",
            "P1缺陷修复率",
            "P2缺陷数量",
            "P2缺陷修复数量",
            "P2缺陷修复率"),
        rows,
        true,
        (row, value, style) -> {
          write(row, 0, value.moduleName(), style);
          write(row, 1, value.assigneeName(), style);
          write(row, 2, value.count(), style);
          write(row, 3, value.fixedCount(), style);
          write(row, 4, value.remainingCount(), style);
          write(row, 5, value.unreproducibleCount(), style);
          write(row, 6, value.designCount(), style);
          write(row, 7, value.technicalBlockCount(), style);
          writePercentage(row, 8, value.fixedRate(), style);
          write(row, 9, value.level1Count(), style);
          write(row, 10, value.level1FixedCount(), style);
          writePercentage(row, 11, value.level1FixedRate(), style);
          write(row, 12, value.p1Count(), style);
          write(row, 13, value.p1FixedCount(), style);
          writePercentage(row, 14, value.p1FixedRate(), style);
          write(row, 15, value.p2Count(), style);
          write(row, 16, value.p2FixedCount(), style);
          writePercentage(row, 17, value.p2FixedRate(), style);
        });
  }

  byte[] htgcDetails(
      List<QualityRdAnalyticsDetailQueryService.HtgcAssigneeDetailRow> rows) {
    return workbook(
        "HTGC指派人剩余缺陷数量",
        List.of(
            "指派人",
            "open缺陷数",
            "已修复",
            "剩余缺陷",
            "未复现",
            "修复率",
            "一级缺陷数",
            "一级缺陷修复数",
            "一级缺陷修复率",
            "二级缺陷数",
            "二级缺陷修复数",
            "二级缺陷修复率",
            "三级缺陷数",
            "三级缺陷修复数",
            "三级缺陷修复率"),
        rows,
        false,
        (row, value, style) -> {
          write(row, 0, value.assigneeName(), style);
          write(row, 1, value.count(), style);
          write(row, 2, value.fixedCount(), style);
          write(row, 3, value.remainingCount(), style);
          write(row, 4, value.unreproducibleCount(), style);
          writePercentage(row, 5, value.fixedRate(), style);
          write(row, 6, value.level1Count(), style);
          write(row, 7, value.level1FixedCount(), style);
          writePercentage(row, 8, value.level1FixedRate(), style);
          write(row, 9, value.level2Count(), style);
          write(row, 10, value.level2FixedCount(), style);
          writePercentage(row, 11, value.level2FixedRate(), style);
          write(row, 12, value.level3Count(), style);
          write(row, 13, value.level3FixedCount(), style);
          writePercentage(row, 14, value.level3FixedRate(), style);
        });
  }

  private <T> byte[] workbook(
      String sheetName,
      List<String> headers,
      List<T> rows,
      boolean mergeFirstColumn,
      RowWriter<T> rowWriter) {
    try (Workbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet(sheetName);
      CellStyle headerStyle = ExcelExportStyles.createHeaderStyle(workbook);
      CellStyle bodyStyle = ExcelExportStyles.createBodyStyle(workbook);
      Row header = sheet.createRow(0);
      for (int index = 0; index < headers.size(); index++) {
        write(header, index, headers.get(index), headerStyle);
      }
      for (int index = 0; index < rows.size(); index++) {
        rowWriter.write(sheet.createRow(index + 1), rows.get(index), bodyStyle);
      }
      if (mergeFirstColumn) {
        mergeConsecutiveFirstColumnValues(sheet, rows.size());
      }
      ExcelExportStyles.applyHeaderRows(sheet, 1);
      ExcelExportStyles.autoSizeColumns(sheet, headers.size(), 8);
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException error) {
      throw new IllegalStateException("研发质量看板 Excel 生成失败: " + sheetName, error);
    }
  }

  private void mergeConsecutiveFirstColumnValues(Sheet sheet, int dataRowCount) {
    int groupStart = 1;
    while (groupStart <= dataRowCount) {
      String value = sheet.getRow(groupStart).getCell(0).getStringCellValue();
      int groupEnd = groupStart;
      while (groupEnd + 1 <= dataRowCount
          && value.equals(sheet.getRow(groupEnd + 1).getCell(0).getStringCellValue())) {
        groupEnd++;
      }
      if (groupEnd > groupStart) {
        sheet.addMergedRegion(new CellRangeAddress(groupStart, groupEnd, 0, 0));
      }
      groupStart = groupEnd + 1;
    }
  }

  private void writePercentage(Row row, int columnIndex, double value, CellStyle style) {
    write(row, columnIndex, String.format(java.util.Locale.ROOT, "%.2f%%", value), style);
  }

  private void write(Row row, int columnIndex, Object value, CellStyle style) {
    Cell cell = row.createCell(columnIndex);
    cell.setCellStyle(style);
    if (value instanceof Number number) {
      cell.setCellValue(number.doubleValue());
      return;
    }
    cell.setCellValue(value == null ? "" : value.toString());
  }

  @FunctionalInterface
  private interface RowWriter<T> {
    void write(Row row, T value, CellStyle style);
  }
}
