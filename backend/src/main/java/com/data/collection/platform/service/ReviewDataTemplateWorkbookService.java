package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.stereotype.Service;

@Service
public class ReviewDataTemplateWorkbookService {
  public byte[] buildTemplateWorkbook() {
    try (Workbook workbook = new HSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      TemplateStyles styles = new TemplateStyles(workbook);
      writeInstructionSheet(workbook.createSheet("填写说明"), styles);
      writeCoverSheet(workbook.createSheet("封面"), styles);
      writeReportSheet(workbook.createSheet("评审报告"), styles);
      writeProblemSheet(workbook.createSheet("评审问题清单"), styles);
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException e) {
      throw new BizException("评审模板文件生成失败");
    }
  }

  private void writeInstructionSheet(Sheet sheet, TemplateStyles styles) {
    writeText(sheet.createRow(0), 0, "评审文件模板", styles.title);
    writeText(sheet.createRow(1), 0, "请按老平台模板结构填写。导入时会读取“封面”“评审报告”“评审问题清单”三个工作表。", styles.body);
    writeText(sheet.createRow(2), 0, "封面：第 6 行第 3 列填写项目名称，例如“CrownCAD 项目”。", styles.body);
    writeText(sheet.createRow(3), 0, "评审报告：填写标题、评审日期、负责人、工作产品描述、评审分工和统计信息。", styles.body);
    writeText(sheet.createRow(4), 0, "评审问题清单：从第 3 行开始填写问题明细，评审专家不能为空。", styles.body);
    sheet.setColumnWidth(0, 90 * 256);
  }

  private void writeCoverSheet(Sheet sheet, TemplateStyles styles) {
    for (int rowIndex = 0; rowIndex < 6; rowIndex++) {
      sheet.createRow(rowIndex);
    }
    writeText(sheet.getRow(4), 2, "CrownCAD 项目", styles.input);
    sheet.setColumnWidth(0, 16 * 256);
    sheet.setColumnWidth(1, 16 * 256);
    sheet.setColumnWidth(2, 30 * 256);
  }

  private void writeReportSheet(Sheet sheet, TemplateStyles styles) {
    writeText(sheet.createRow(0), 0, "【模块名称】评审标题", styles.title);
    Row meta = sheet.createRow(1);
    writeText(meta, 0, "评审日期", styles.header);
    writeText(meta, 1, "2026-06-18", styles.input);
    writeText(meta, 2, "评审负责人", styles.header);
    writeText(meta, 3, "负责人姓名", styles.input);

    Row descriptionHeader = sheet.createRow(2);
    writeHeader(descriptionHeader, styles.header, "序号", "评审的工作产品", "版本", "作者", "评审规模", "单位");
    Row description = sheet.createRow(3);
    writeHeader(description, styles.input, "1", "需求说明书", "V1.0", "作者姓名", "10", "页");

    Row contentHeader = sheet.createRow(4);
    writeHeader(contentHeader, styles.header, "序号", "评审专家", "评审分工内容", "独立评审工作量", "有效的独立评审问题数", "会议评审工作量", "有效的会议评审问题数");
    Row content = sheet.createRow(5);
    writeHeader(content, styles.input, "1", "专家姓名", "全文", "1", "0", "0", "0");

    Row sum = sheet.createRow(6);
    writeHeader(sum, styles.header, "独立评审工作量合计", "1", "有效的独立评审问题数合计", "0", "会议评审工作量", "0", "有效的会议评审问题数合计", "0");
    Row metrics = sheet.createRow(7);
    writeHeader(metrics, styles.header, "评审缺陷密度", "", "评审效率", "0", "评审速率", "0");
    Row standard = sheet.createRow(8);
    writeHeader(standard, styles.header, "评审缺陷密度目标值", "0.2~0.6个/页", "是否达标", "是", "未达标原因分析", "");

    sheet.createRow(9);
    sheet.createRow(10);
    Row categoryHeader = sheet.createRow(11);
    writeHeader(categoryHeader, styles.header, "文档规范", "完整性", "功能性", "可行性");
    Row category = sheet.createRow(12);
    writeHeader(category, styles.input, "0", "0", "0", "0");
    setColumnWidths(sheet, 18, 24, 24, 18, 18, 18, 22, 18);
  }

  private void writeProblemSheet(Sheet sheet, TemplateStyles styles) {
    writeText(sheet.createRow(0), 0, "评审问题清单", styles.title);
    Row header = sheet.createRow(1);
    writeHeader(
        header,
        styles.header,
        "序号",
        "评审专家",
        "评审工作量",
        "评审类别",
        "在文档中的位置",
        "预留列",
        "问题类别",
        "问题描述",
        "建议解决方案",
        "责任人",
        "不接受理由",
        "问题状态",
        "关闭日期");
    Row example = sheet.createRow(2);
    writeHeader(
        example,
        styles.input,
        "1",
        "专家姓名",
        "0",
        "独立评审",
        "第 1 页",
        "",
        "文档规范",
        "问题描述",
        "建议解决方案",
        "责任人",
        "",
        "新提交",
        "2026-06-18");
    setColumnWidths(sheet, 8, 16, 12, 14, 18, 10, 14, 32, 32, 14, 20, 14, 16);
  }

  private void writeHeader(Row row, CellStyle style, String... values) {
    for (int index = 0; index < values.length; index++) {
      writeText(row, index, values[index], style);
    }
  }

  private void writeText(Row row, int column, String value, CellStyle style) {
    var cell = row.createCell(column);
    cell.setCellValue(value);
    cell.setCellStyle(style);
  }

  private void setColumnWidths(Sheet sheet, int... widths) {
    for (int index = 0; index < widths.length; index++) {
      sheet.setColumnWidth(index, widths[index] * 256);
    }
  }

  private static final class TemplateStyles {
    private final CellStyle title;
    private final CellStyle header;
    private final CellStyle input;
    private final CellStyle body;

    private TemplateStyles(Workbook workbook) {
      title = bordered(workbook);
      title.setAlignment(HorizontalAlignment.CENTER);
      title.setVerticalAlignment(VerticalAlignment.CENTER);
      title.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
      title.setFillPattern(FillPatternType.SOLID_FOREGROUND);

      header = bordered(workbook);
      header.setAlignment(HorizontalAlignment.CENTER);
      header.setVerticalAlignment(VerticalAlignment.CENTER);
      header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
      header.setFillPattern(FillPatternType.SOLID_FOREGROUND);

      input = bordered(workbook);
      input.setVerticalAlignment(VerticalAlignment.CENTER);
      input.setWrapText(true);

      body = bordered(workbook);
      body.setVerticalAlignment(VerticalAlignment.CENTER);
      body.setWrapText(true);
    }

    private static CellStyle bordered(Workbook workbook) {
      CellStyle style = workbook.createCellStyle();
      style.setBorderBottom(BorderStyle.THIN);
      style.setBorderLeft(BorderStyle.THIN);
      style.setBorderRight(BorderStyle.THIN);
      style.setBorderTop(BorderStyle.THIN);
      return style;
    }
  }
}
