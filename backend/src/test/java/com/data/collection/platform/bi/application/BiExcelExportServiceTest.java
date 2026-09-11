package com.data.collection.platform.bi.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class BiExcelExportServiceTest {
  // 固定时钟保证元信息时间戳与文件名可复现，不依赖真实系统时间。
  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-09-10T08:30:15Z"), ZoneId.of("UTC"));

  private final BiExcelExportService service = new BiExcelExportService(FIXED_CLOCK);

  @Test
  void writesTitleMetaHeaderAndTypedDataRowsIntoRealWorkbook() throws Exception {
    BiExcelExportService.Export export = service.export(
        "按指派人统计缺陷数",
        "CC2026R4",
        null,
        List.of("指派责任人", "缺陷总数", "修复率 (%)"),
        List.of(
            List.<Object>of("张三", 10, 80.0),
            List.<Object>of("李四", 5, 100.0)));

    assertThat(export.filename()).isEqualTo("按指派人统计缺陷数_20260910083015.xlsx");
    try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(export.content()))) {
      Sheet sheet = workbook.getSheetAt(0);
      assertThat(sheet.getSheetName()).isEqualTo("按指派人统计缺陷数");
      assertThat(text(sheet, 0, 0)).isEqualTo("按指派人统计缺陷数");
      assertThat(text(sheet, 1, 0))
          .isEqualTo("产品版本：CC2026R4  |  导出时间：2026-09-10 08:30:15");
      // 第 2 行为空行分隔，第 3 行为表头。
      assertThat(sheet.getRow(2)).isNotNull();
      assertThat(text(sheet, 3, 0)).isEqualTo("指派责任人");
      assertThat(text(sheet, 3, 1)).isEqualTo("缺陷总数");
      assertThat(text(sheet, 3, 2)).isEqualTo("修复率 (%)");

      Row firstDataRow = sheet.getRow(4);
      assertThat(firstDataRow.getCell(0).getCellType()).isEqualTo(CellType.STRING);
      assertThat(firstDataRow.getCell(0).getStringCellValue()).isEqualTo("张三");
      assertThat(firstDataRow.getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
      assertThat(firstDataRow.getCell(1).getNumericCellValue()).isEqualTo(10.0);
      assertThat(firstDataRow.getCell(2).getNumericCellValue()).isEqualTo(80.0);
      assertThat(text(sheet, 5, 0)).isEqualTo("李四");

      // 冻结前 4 行（标题、元信息、空行、表头），滚动数据时表头常驻。
      assertThat(sheet.getPaneInformation()).isNotNull();
      assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 4);
    }
  }

  @Test
  void writesNullCellAsEmptyStringAndBlankVersionAsPlaceholder() throws Exception {
    BiExcelExportService.Export export = service.export(
        "空值处理",
        "   ",
        null,
        List.of("名称", "数值"),
        List.of(Arrays.asList("未标注", null)));

    try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(export.content()))) {
      Sheet sheet = workbook.getSheetAt(0);
      assertThat(text(sheet, 1, 0)).contains("产品版本：--");
      Row dataRow = sheet.getRow(4);
      assertThat(dataRow.getCell(1).getCellType()).isEqualTo(CellType.STRING);
      assertThat(dataRow.getCell(1).getStringCellValue()).isEmpty();
    }
  }

  @Test
  void sanitizesSheetNameAndTruncatesToExcelLimit() throws Exception {
    String forbiddenAndLongTitle = "评审/质量:分析*报告?[未命名]" + "很长的图表标题".repeat(6);
    BiExcelExportService.Export export = service.export(
        forbiddenAndLongTitle, "CC2026R4", null, List.of("列"), List.of());

    try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(export.content()))) {
      String sheetName = workbook.getSheetAt(0).getSheetName();
      assertThat(sheetName).doesNotContain("/", ":", "*", "?", "[", "]");
      assertThat(sheetName.length()).isLessThanOrEqualTo(31);
    }
  }

  @Test
  void replacesFilenameForbiddenCharactersAndKeepsExtension() {
    BiExcelExportService.Export export = service.export(
        "缺陷/原因:分析", "CC2026R4", null, List.of("列"), List.of());

    assertThat(export.filename()).isEqualTo("缺陷-原因-分析_20260910083015.xlsx");
  }

  @Test
  void writesExplanationRowAndShiftsHeaderAndFreezeWhenProvided() throws Exception {
    BiExcelExportService.Export export = service.export(
        "各模块系统测试修复率达成情况",
        "CC2026R4",
        "统计各模块整体修复率。达标标准：整体修复率 >= 95.00%。",
        List.of("模块名称", "整体修复率 (%)"),
        List.of(List.<Object>of("权限管理", 88.8)));

    try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(export.content()))) {
      Sheet sheet = workbook.getSheetAt(0);
      assertThat(text(sheet, 2, 0))
          .isEqualTo("口径说明：统计各模块整体修复率。达标标准：整体修复率 >= 95.00%。");
      // 说明行挤占一行：空行下移到 3，表头到 4，数据到 5，冻结必须跟着覆盖到表头为止。
      assertThat(sheet.getRow(3).iterator().hasNext()).isFalse();
      assertThat(text(sheet, 4, 0)).isEqualTo("模块名称");
      assertThat(text(sheet, 5, 0)).isEqualTo("权限管理");
      assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 5);
    }
  }

  @Test
  void blankExplanationDoesNotOccupyARow() throws Exception {
    BiExcelExportService.Export export = service.export(
        "空说明", "CC2026R4", "   ", List.of("列"), List.of(List.<Object>of("值")));

    try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(export.content()))) {
      Sheet sheet = workbook.getSheetAt(0);
      // 空白说明不写入，行索引与不带说明时完全一致，避免产生空行漂移。
      assertThat(text(sheet, 2, 0)).isEmpty();
      assertThat(text(sheet, 3, 0)).isEqualTo("列");
      assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 4);
    }
  }

  private static String text(Sheet sheet, int rowIndex, int columnIndex) {
    Row row = sheet.getRow(rowIndex);
    if (row == null) {
      return "";
    }
    Cell cell = row.getCell(columnIndex);
    return cell == null ? "" : cell.getStringCellValue();
  }
}
