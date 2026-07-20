package com.data.collection.platform.service.statistics;

import com.data.collection.platform.service.ExcelExportStyles;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

final class CustomerIssueSummaryWorkbookExportSupport {
  private static final String SHEET_NAME = "议题数据";

  private CustomerIssueSummaryWorkbookExportSupport() {}

  static byte[] export(List<CustomerIssueSummaryWorkbookRow> rows) {
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet(SHEET_NAME);
      CellStyle headerStyle = ExcelExportStyles.createHeaderStyle(workbook);
      CellStyle bodyStyle = ExcelExportStyles.createBodyStyle(workbook);
      writeRow(sheet.createRow(0), CustomerIssueSummaryWorkbookRow.HEADERS, headerStyle);
      for (int index = 0; index < rows.size(); index++) {
        writeRow(sheet.createRow(index + 1), rows.get(index).values(), bodyStyle);
      }
      sheet.createFreezePane(0, 1);
      ExcelExportStyles.applyHeaderRows(sheet, 1);
      ExcelExportStyles.autoSizeColumns(sheet, CustomerIssueSummaryWorkbookRow.HEADERS.size());
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException error) {
      throw new IllegalStateException("生成客户问题汇总议题数据失败", error);
    }
  }

  private static void writeRow(Row row, List<String> values, CellStyle style) {
    for (int index = 0; index < values.size(); index++) {
      Cell cell = row.createCell(index);
      cell.setCellValue(values.get(index));
      cell.setCellStyle(style);
    }
  }
}
