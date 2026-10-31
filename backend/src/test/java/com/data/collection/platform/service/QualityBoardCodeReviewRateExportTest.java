package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.QualityBoardCodeReviewRecordExportRow;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 验证实际Excel产出的MR去重、完整性和显示契约。 */
class QualityBoardCodeReviewRateExportTest {
  private final QualityBoardRdService rdService = mock(QualityBoardRdService.class);
  private final QualityBoardCodeReviewReadSupport readSupport = mock(QualityBoardCodeReviewReadSupport.class);
  private final QualityBoardWorkbookExportService exporter = new QualityBoardWorkbookExportService(rdService, readSupport);

  @Test
  void compatibilitySharesTheMrAcrossProjectNamesAndSourceCapitalization() throws Exception {
    try (var workbook = export(" DGM ", CodeReviewDataReadMode.MATCH_MODE, List.of(
        row("DGM", "旧项目", 77L, 1000, 60),
        row("dgm", "组合项目", 77L, 1000, 30)))) {
      var sheet = workbook.getSheet("走查速率汇总");
      assertThat(sheet.getLastRowNum()).isEqualTo(2);
      assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("dgm");
      assertThat(sheet.getRow(1).getCell(5).getNumericCellValue()).isEqualTo(1000);
      assertThat(sheet.getRow(1).getCell(6).getNumericCellValue()).isEqualTo(90);
      assertThat(sheet.getRow(2).getCell(7).getNumericCellValue()).isCloseTo(1000D / 90 * 60, offset(1e-9));
    }
  }

  @Test
  void totalRateDividesTotalUniqueLinesByTotalMinutesInsteadOfAveragingMrRates() throws Exception {
    try (var workbook = export("dgm", CodeReviewDataReadMode.MATCH_MODE, List.of(
        row("dgm", "版本", 77L, 1000, 60),
        row("dgm", "版本", 78L, 3000, 30)))) {
      var sheet = workbook.getSheet("走查速率汇总");
      assertThat(sheet.getLastRowNum()).isEqualTo(3);
      var total = sheet.getRow(3);
      assertThat(total.getCell(5).getNumericCellValue()).isEqualTo(4000);
      assertThat(total.getCell(6).getNumericCellValue()).isEqualTo(90);
      assertThat(total.getCell(7).getNumericCellValue()).isCloseTo(4000D / 90 * 60, offset(1e-9));
      assertThat(total.getCell(8).getNumericCellValue()).isCloseTo(4D / 90 * 60, offset(1e-9));
      assertThat(new DataFormatter(Locale.ROOT).formatCellValue(total.getCell(7))).isEqualTo("2666.67");
      assertThat(total.getCell(7).getCellStyle().getDataFormatString()).isEqualTo("0.00");
      assertThat(total.getCell(8).getCellStyle().getDataFormatString()).isEqualTo("0.00");
    }
  }

  static Stream<Arguments> missingIdentities() {
    return Stream.of(Arguments.of((Long) null), Arguments.of(0L), Arguments.of(-1L));
  }

  @ParameterizedTest
  @MethodSource("missingIdentities")
  void missingIdentitiesRemainSeparateAndCannotProduceAMrOrTotalRate(
      Long iid) throws Exception {
    try (var workbook = export("dgm", CodeReviewDataReadMode.MATCH_MODE, List.of(
        row("dgm", "版本", iid, 1000, 60),
        row("dgm", "版本", iid, 1000, 30)))) {
      var sheet = workbook.getSheet("走查速率汇总");
      assertThat(sheet.getLastRowNum()).isEqualTo(3);
      for (int index = 1; index <= 2; index++) {
        assertThat(sheet.getRow(index).getCell(7).getCellType()).isNotEqualTo(CellType.NUMERIC);
        assertThat(sheet.getRow(index).getCell(9).getStringCellValue()).contains("MR身份缺失");
      }
      assertThat(sheet.getRow(3).getCell(7).getCellType()).isNotEqualTo(CellType.NUMERIC);
      assertThat(sheet.getRow(3).getCell(9).getStringCellValue()).isEqualTo("数据不完整");
    }
  }

  static Stream<Arguments> invalidMetrics() {
    return Stream.of(
        Arguments.of(null, 30, "新增行数异常"),
        Arguments.of(-1, 30, "新增行数异常"),
        Arguments.of(1000, null, "走查工时异常"),
        Arguments.of(1000, 0, "走查工时异常"),
        Arguments.of(1000, -1, "走查工时异常"));
  }

  @ParameterizedTest
  @MethodSource("invalidMetrics")
  void anInvalidMrDoesNotHideValidMrResultsButMakesTheTotalIncomplete(
      Integer lines, Integer minutes, String reason) throws Exception {
    try (var workbook = export("dgm", CodeReviewDataReadMode.MATCH_MODE, List.of(
        row("dgm", "版本", 77L, 1000, 60),
        row("dgm", "版本", 78L, lines, minutes)))) {
      var sheet = workbook.getSheet("走查速率汇总");
      assertThat(sheet.getRow(1).getCell(7).getNumericCellValue()).isEqualTo(1000);
      assertThat(sheet.getRow(2).getCell(7).getCellType()).isNotEqualTo(CellType.NUMERIC);
      assertThat(sheet.getRow(2).getCell(9).getStringCellValue()).contains(reason);
      assertThat(sheet.getRow(3).getCell(7).getCellType()).isNotEqualTo(CellType.NUMERIC);
      assertThat(sheet.getRow(3).getCell(9).getStringCellValue()).isEqualTo("数据不完整");
    }
  }

  @Test
  void zeroAddedLinesAndPositiveMinutesProduceAValidZeroRate() throws Exception {
    try (var workbook = export("dgm", CodeReviewDataReadMode.MATCH_MODE,
        List.of(row("dgm", "版本", 77L, 0, 30)))) {
      var sheet = workbook.getSheet("走查速率汇总");
      assertThat(sheet.getRow(1).getCell(7).getNumericCellValue()).isZero();
      assertThat(sheet.getRow(2).getCell(7).getNumericCellValue()).isZero();
      assertThat(sheet.getRow(2).getCell(9).getStringCellValue()).isEqualTo("正常");
    }
  }

  @Test
  void accumulatedLinesAndMinutesCanExceedIntegerRange() throws Exception {
    try (var workbook = export("dgm", CodeReviewDataReadMode.MATCH_MODE, List.of(
        row("dgm", "版本", 77L, Integer.MAX_VALUE, Integer.MAX_VALUE),
        row("dgm", "版本", 77L, Integer.MAX_VALUE, Integer.MAX_VALUE),
        row("dgm", "版本", 78L, Integer.MAX_VALUE, Integer.MAX_VALUE)))) {
      var sheet = workbook.getSheet("走查速率汇总");
      assertThat(sheet.getRow(1).getCell(6).getNumericCellValue()).isEqualTo(2D * Integer.MAX_VALUE);
      assertThat(sheet.getRow(3).getCell(5).getNumericCellValue()).isEqualTo(2D * Integer.MAX_VALUE);
      assertThat(sheet.getRow(3).getCell(6).getNumericCellValue()).isEqualTo(3D * Integer.MAX_VALUE);
      assertThat(sheet.getRow(3).getCell(7).getNumericCellValue()).isEqualTo(40);
    }
  }

  @Test
  void emptyScopeExportsHeadersWithoutInventingAZeroRate() throws Exception {
    try (var workbook = export("dgm", CodeReviewDataReadMode.MATCH_MODE, List.of())) {
      assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
      assertThat(workbook.getSheet("ALL").getLastRowNum()).isZero();
      assertThat(workbook.getSheet("走查速率汇总").getLastRowNum()).isZero();
    }
  }

  @Test
  void omittedSourceUsesTheCcBusinessScope() throws Exception {
    try (var workbook = export(null, CodeReviewDataReadMode.MATCH_MODE, List.of(
        row("cc", "旧名称", 77L, 1000, 60),
        row("cc", "组合名称", 77L, 1000, 30)))) {
      var sheet = workbook.getSheet("走查速率汇总");
      assertThat(sheet.getLastRowNum()).isEqualTo(2);
      assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("cc");
      assertThat(sheet.getRow(2).getCell(7).getNumericCellValue()).isCloseTo(1000D / 90 * 60, offset(1e-9));
    }
  }

  @Test
  void onlySummaryIsSelectedWhileAllOriginalRowsAndStoredRatesArePreserved() throws Exception {
    try (var workbook = export("dgm", CodeReviewDataReadMode.MATCH_MODE, List.of(
        row("dgm", "版本", 77L, 1000, 60),
        row("dgm", "版本", 77L, 1000, 30)))) {
      assertThat(workbook.getActiveSheetIndex()).isEqualTo(workbook.getSheetIndex("走查速率汇总"));
      assertThat(workbook.getSheet("ALL").isSelected()).isFalse();
      assertThat(workbook.getSheet("走查速率汇总").isSelected()).isTrue();
      var details = workbook.getSheet("ALL");
      assertThat(details.getRow(0).getLastCellNum()).isEqualTo((short) 35);
      assertThat(details.getLastRowNum()).isEqualTo(2);
      for (int index = 1; index <= 2; index++) {
        assertThat(details.getRow(index).getCell(12).getNumericCellValue()).isEqualTo(1000);
        assertThat(details.getRow(index).getCell(20).getNumericCellValue()).isEqualTo(-999);
      }
    }
  }

  private XSSFWorkbook export(String source, CodeReviewDataReadMode mode,
      List<QualityBoardCodeReviewRecordExportRow> rows) throws Exception {
    when(rdService.normalizeProjectName("CC2026R4")).thenReturn("CC2026R4");
    when(readSupport.codeReviewRecordRows(source, "CC2026R4", mode)).thenReturn(rows);
    return new XSSFWorkbook(new ByteArrayInputStream(exporter.exportCodeReviewRecordsWorkbook("CC2026R4", source, mode)));
  }

  private static QualityBoardCodeReviewRecordExportRow row(String source, String project,
      Long iid, Integer lines, Integer minutes) {
    return new QualityBoardCodeReviewRecordExportRow(source, null, project, "模块", "MERGED", iid,
        "走查记录", "作者", "走查人", "指派人", null, "合并人", minutes, lines, null,
        null, null, null, null, null, null, -999, -0.999, null, null, "dev", null,
        null, null, null, null, null, null, null, "DGM", null);
  }
}
