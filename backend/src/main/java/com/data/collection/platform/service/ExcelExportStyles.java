package com.data.collection.platform.service;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;

public final class ExcelExportStyles {
  private static final String HEADER_FONT_NAME = "微软雅黑";
  private static final short HEADER_FONT_SIZE = 11;

  private ExcelExportStyles() {}

  public static CellStyle createHeaderStyle(Workbook workbook) {
    CellStyle style = workbook.createCellStyle();
    style.setFillForegroundColor(IndexedColors.GREY_40_PERCENT.getIndex());
    style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    style.setAlignment(HorizontalAlignment.CENTER);
    style.setVerticalAlignment(VerticalAlignment.CENTER);
    style.setFont(createHeaderFont(workbook));
    applyThinBorder(style);
    return style;
  }

  public static void applyThinBorder(CellStyle style) {
    style.setBorderTop(BorderStyle.THIN);
    style.setBorderBottom(BorderStyle.THIN);
    style.setBorderLeft(BorderStyle.THIN);
    style.setBorderRight(BorderStyle.THIN);
  }

  private static Font createHeaderFont(Workbook workbook) {
    Font font = workbook.createFont();
    font.setFontName(HEADER_FONT_NAME);
    font.setFontHeightInPoints(HEADER_FONT_SIZE);
    font.setColor(IndexedColors.WHITE.getIndex());
    font.setBold(true);
    return font;
  }
}
