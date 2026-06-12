package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

@Service
public class ReviewDataTemplateWorkbookService {
  private static final String[] HEADERS = {
    "评审的工作产品",
    "所属项目",
    "模块",
    "文档类型",
    "评审类别",
    "上传时间",
    "负责人",
    "评审规模",
    "评审缺陷个数",
    "文档规范",
    "完整性",
    "功能性",
    "可行性",
    "加权重的评审缺陷密度",
    "评审缺陷密度",
    "评审效率",
    "评审速率",
    "独立评审工作量",
    "有效的独立评审问题数",
    "会议评审工作量",
    "有效的会议评审问题数",
    "不达标原因"
  };

  public byte[] buildTemplateWorkbook() {
    try (Workbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("评审数据");
      CellStyle headerStyle = workbook.createCellStyle();
      headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
      headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      headerStyle.setAlignment(HorizontalAlignment.CENTER);
      headerStyle.setBorderBottom(BorderStyle.THIN);
      headerStyle.setBorderLeft(BorderStyle.THIN);
      headerStyle.setBorderRight(BorderStyle.THIN);
      headerStyle.setBorderTop(BorderStyle.THIN);
      Row header = sheet.createRow(0);
      for (int index = 0; index < HEADERS.length; index++) {
        var cell = header.createCell(index);
        cell.setCellValue(HEADERS[index]);
        cell.setCellStyle(headerStyle);
        sheet.setColumnWidth(index, Math.max(14, HEADERS[index].length() * 2 + 4) * 256);
      }
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException exception) {
      throw new BizException("评审模板生成失败");
    }
  }
}
