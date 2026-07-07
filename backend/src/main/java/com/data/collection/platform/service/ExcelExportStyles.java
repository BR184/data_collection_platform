package com.data.collection.platform.service;

import java.awt.Color;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;

public final class ExcelExportStyles {
  private static final String FONT_NAME = "微软雅黑";
  private static final short FONT_SIZE = 11;
  private static final Color HEADER_FILL = new Color(0xF2, 0xF3, 0xF5);
  private static final Color SUMMARY_FILL = new Color(0xDA, 0xE9, 0xF8);
  private static final int DEFAULT_MIN_COLUMN_WIDTH = 16 * 256;
  private static final int DEFAULT_MAX_COLUMN_WIDTH = 72 * 256;
  private static final int COLUMN_WIDTH_PADDING = 3 * 256;
  private static final float TWO_LINE_HEADER_HEIGHT_POINTS = 34F;

  private ExcelExportStyles() {}

  public static CellStyle createHeaderStyle(Workbook workbook) {
    CellStyle style = workbook.createCellStyle();
    setFillColor(style, HEADER_FILL, IndexedColors.GREY_25_PERCENT);
    style.setAlignment(HorizontalAlignment.CENTER);
    style.setVerticalAlignment(VerticalAlignment.CENTER);
    style.setWrapText(true);
    style.setFont(createHeaderFont(workbook));
    applyThinBorder(style);
    return style;
  }

  public static CellStyle createBodyStyle(Workbook workbook) {
    CellStyle style = workbook.createCellStyle();
    style.setAlignment(HorizontalAlignment.CENTER);
    style.setVerticalAlignment(VerticalAlignment.CENTER);
    style.setWrapText(true);
    style.setFont(createBodyFont(workbook));
    applyThinBorder(style);
    return style;
  }

  public static CellStyle createSummaryStyle(Workbook workbook) {
    CellStyle style = createBodyStyle(workbook);
    setFillColor(style, SUMMARY_FILL, IndexedColors.LIGHT_CORNFLOWER_BLUE);
    return style;
  }

  public static void applyThinBorder(CellStyle style) {
    style.setBorderTop(BorderStyle.THIN);
    style.setBorderBottom(BorderStyle.THIN);
    style.setBorderLeft(BorderStyle.THIN);
    style.setBorderRight(BorderStyle.THIN);
  }

  public static void applyHeaderRows(Sheet sheet, int headerRowCount) {
    for (int rowIndex = 0; rowIndex < headerRowCount; rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row != null) {
        row.setHeightInPoints(TWO_LINE_HEADER_HEIGHT_POINTS);
      }
    }
  }

  public static void autoSizeColumns(Sheet sheet, int columnCount) {
    autoSizeColumns(sheet, columnCount, columnCount);
  }

  public static void autoSizeColumns(Sheet sheet, int columnCount, int maxAutoSizeColumns) {
    int limit = Math.min(columnCount, maxAutoSizeColumns);
    for (int columnIndex = 0; columnIndex < limit; columnIndex++) {
      sheet.autoSizeColumn(columnIndex);
      int currentWidth = sheet.getColumnWidth(columnIndex);
      sheet.setColumnWidth(columnIndex, boundedWidth(currentWidth + COLUMN_WIDTH_PADDING));
    }
    for (int columnIndex = limit; columnIndex < columnCount; columnIndex++) {
      int currentWidth = sheet.getColumnWidth(columnIndex);
      sheet.setColumnWidth(columnIndex, Math.max(currentWidth, DEFAULT_MIN_COLUMN_WIDTH));
    }
  }

  public static void setReadableColumnWidths(Sheet sheet, int... characterWidths) {
    for (int columnIndex = 0; columnIndex < characterWidths.length; columnIndex++) {
      sheet.setColumnWidth(columnIndex, boundedWidth(characterWidths[columnIndex] * 256));
    }
  }

  private static void setFillColor(CellStyle style, Color color, IndexedColors fallback) {
    if (style instanceof XSSFCellStyle xssfStyle) {
      xssfStyle.setFillForegroundColor(new XSSFColor(color, null));
    } else {
      style.setFillForegroundColor(fallback.getIndex());
    }
    style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
  }

  private static int boundedWidth(int width) {
    return Math.min(Math.max(width, DEFAULT_MIN_COLUMN_WIDTH), DEFAULT_MAX_COLUMN_WIDTH);
  }

  private static Font createHeaderFont(Workbook workbook) {
    Font font = workbook.createFont();
    font.setFontName(FONT_NAME);
    font.setFontHeightInPoints(FONT_SIZE);
    font.setColor(IndexedColors.BLACK.getIndex());
    font.setBold(true);
    return font;
  }

  private static Font createBodyFont(Workbook workbook) {
    Font font = workbook.createFont();
    font.setFontName(FONT_NAME);
    font.setFontHeightInPoints(FONT_SIZE);
    font.setColor(IndexedColors.BLACK.getIndex());
    return font;
  }
}
