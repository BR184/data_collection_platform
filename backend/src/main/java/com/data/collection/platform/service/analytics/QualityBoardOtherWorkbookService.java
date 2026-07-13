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
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

@Service
public class QualityBoardOtherWorkbookService {
  byte[] export(
      QualityBoardOtherTopic topic,
      String scope,
      List<QualityBoardOtherQueryService.Row> rows) {
    try (Workbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet(WorkbookUtil.createSafeSheetName(topic.sheetName(scope)));
      CellStyle headerStyle = ExcelExportStyles.createHeaderStyle(workbook);
      CellStyle bodyStyle = ExcelExportStyles.createBodyStyle(workbook);
      List<String> headers = headers(topic);
      writeHeader(sheet, headerStyle, headers);
      for (int index = 0; index < rows.size(); index++) {
        writeRow(sheet.createRow(index + 1), bodyStyle, topic, rows.get(index));
      }
      ExcelExportStyles.applyHeaderRows(sheet, 1);
      ExcelExportStyles.autoSizeColumns(sheet, headers.size(), 8);
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException error) {
      throw new IllegalStateException("其他看板 Excel 生成失败: " + topic.title(), error);
    }
  }

  private List<String> headers(QualityBoardOtherTopic topic) {
    return switch (topic) {
      case FUNCTION_DEFECT_COUNT -> List.of("功能名称", "缺陷数量");
      case FUNCTION_DEFECT_DENSITY -> List.of(
          "功能名称",
          "缺陷密度 (缺陷数量/新增代码行数 * 100)",
          "缺陷数量",
          "新增代码行数");
      case QUALITY_RANKING -> List.of(
          "姓名",
          "系统测试缺陷数/新增代码行数 * 1000",
          "新增代码行数",
          "缺陷数量");
      case MEMBER_UNRESOLVED_RATE -> List.of(
          "姓名", "未修复缺陷率", "个人未修复缺陷数", "个人缺陷总数");
      case RELEASE_LEAKAGE_RATE, DEVELOPMENT_LEAKAGE_RATE ->
          List.of("测试阶段", "缺陷遗留率%");
    };
  }

  private void writeHeader(Sheet sheet, CellStyle style, List<String> headers) {
    Row row = sheet.createRow(0);
    for (int index = 0; index < headers.size(); index++) {
      write(row, index, headers.get(index), style);
    }
  }

  private void writeRow(
      Row row,
      CellStyle style,
      QualityBoardOtherTopic topic,
      QualityBoardOtherQueryService.Row item) {
    write(row, 0, item.name(), style);
    switch (topic) {
      case FUNCTION_DEFECT_COUNT -> write(row, 1, item.numerator(), style);
      case FUNCTION_DEFECT_DENSITY -> {
        write(row, 1, item.value(), style);
        write(row, 2, item.numerator(), style);
        write(row, 3, item.denominator(), style);
      }
      case QUALITY_RANKING -> {
        write(row, 1, item.value(), style);
        write(row, 2, item.denominator(), style);
        write(row, 3, item.numerator(), style);
      }
      case MEMBER_UNRESOLVED_RATE -> {
        write(row, 1, item.value(), style);
        write(row, 2, item.numerator(), style);
        write(row, 3, item.denominator(), style);
      }
      case RELEASE_LEAKAGE_RATE, DEVELOPMENT_LEAKAGE_RATE ->
          write(row, 1, item.value(), style);
    }
  }

  private void write(Row row, int index, Object value, CellStyle style) {
    Cell cell = row.createCell(index);
    cell.setCellStyle(style);
    if (value instanceof Number number) {
      cell.setCellValue(number.doubleValue());
    } else {
      cell.setCellValue(value == null ? "" : value.toString());
    }
  }
}
