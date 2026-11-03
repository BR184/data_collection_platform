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

/** 验证原有ALL速率列的MR累计口径，禁止新增sheet或列。 */
class QualityBoardCodeReviewRateExportTest {
  private final QualityBoardRdService rdService = mock(QualityBoardRdService.class);
  private final QualityBoardCodeReviewReadSupport readSupport = mock(QualityBoardCodeReviewReadSupport.class);
  private final QualityBoardWorkbookExportService exporter = new QualityBoardWorkbookExportService(rdService, readSupport);

  @Test
  void repeatedMrRowsShowTheSameDeduplicatedRateWithoutAddingSheets() throws Exception {
    try (var book = export(" DGM ", CodeReviewDataReadMode.MATCH_MODE, List.of(
        row("DGM", "旧项目", 77L, 1000, 60), row("dgm", "组合项目", 77L, 1000, 30)))) {
      assertOriginalStructure(book, 2);
      var sheet = book.getSheet("ALL");
      for (int index = 1; index <= 2; index++) {
        assertThat(sheet.getRow(index).getCell(12).getNumericCellValue()).isEqualTo(1000);
        assertThat(sheet.getRow(index).getCell(20).getNumericCellValue()).isCloseTo(1000D / 90 * 60, offset(1e-9));
        assertThat(sheet.getRow(index).getCell(21).getNumericCellValue()).isCloseTo(1D / 90 * 60, offset(1e-9));
      }
      assertThat(sheet.getRow(1).getCell(11).getNumericCellValue()).isEqualTo(60);
      assertThat(sheet.getRow(2).getCell(11).getNumericCellValue()).isEqualTo(30);
    }
  }

  @Test
  void differentMrsHaveTheirOwnRatesInsteadOfSharingAnOverallAverage() throws Exception {
    try (var book = export("dgm", CodeReviewDataReadMode.MATCH_MODE, List.of(
        row("dgm", "版本", 77L, 1000, 60), row("dgm", "版本", 78L, 3000, 30)))) {
      assertOriginalStructure(book, 2);
      assertThat(book.getSheet("ALL").getRow(1).getCell(20).getNumericCellValue()).isEqualTo(1000);
      assertThat(book.getSheet("ALL").getRow(2).getCell(20).getNumericCellValue()).isEqualTo(6000);
    }
  }

  static Stream<Arguments> missingIdentities() {
    return Stream.of(Arguments.of((Long) null), Arguments.of(0L), Arguments.of(-1L));
  }

  @ParameterizedTest
  @MethodSource("missingIdentities")
  void missingIdentitiesLeaveTheExistingRateCellsEmpty(Long iid) throws Exception {
    try (var book = export("dgm", CodeReviewDataReadMode.MATCH_MODE, List.of(
        row("dgm", "版本", iid, 1000, 60), row("dgm", "版本", iid, 1000, 30)))) {
      assertOriginalStructure(book, 2);
      assertRateEmpty(book, 1);
      assertRateEmpty(book, 2);
    }
  }

  static Stream<Arguments> invalidMetrics() {
    return Stream.of(Arguments.of(null, 30), Arguments.of(-1, 30),
        Arguments.of(1000, null), Arguments.of(1000, 0), Arguments.of(1000, -1));
  }

  @ParameterizedTest
  @MethodSource("invalidMetrics")
  void anInvalidReviewMakesOnlyItsMrRateIncomplete(Integer lines, Integer minutes) throws Exception {
    try (var book = export("dgm", CodeReviewDataReadMode.MATCH_MODE, List.of(
        row("dgm", "版本", 77L, 1000, 60), row("dgm", "版本", 78L, 1000, 60),
        row("dgm", "版本", 78L, lines, minutes)))) {
      assertOriginalStructure(book, 3);
      assertThat(book.getSheet("ALL").getRow(1).getCell(20).getNumericCellValue()).isEqualTo(1000);
      assertRateEmpty(book, 2);
      assertRateEmpty(book, 3);
    }
  }

  @Test
  void conflictingLinesMakeEveryRateForThatMrEmpty() throws Exception {
    try (var book = export("dgm", CodeReviewDataReadMode.MATCH_MODE, List.of(
        row("dgm", "版本", 77L, 1000, 60), row("dgm", "版本", 77L, 900, 30),
        row("dgm", "版本", 78L, 500, 30)))) {
      assertOriginalStructure(book, 3);
      assertRateEmpty(book, 1);
      assertRateEmpty(book, 2);
      assertThat(book.getSheet("ALL").getRow(3).getCell(20).getNumericCellValue()).isEqualTo(1000);
    }
  }

  @Test
  void zeroLinesAndPositiveTimeProduceZeroRate() throws Exception {
    try (var book = export("dgm", CodeReviewDataReadMode.MATCH_MODE,
        List.of(row("dgm", "版本", 77L, 0, 30)))) {
      assertOriginalStructure(book, 1);
      assertThat(book.getSheet("ALL").getRow(1).getCell(20).getNumericCellValue()).isZero();
      assertThat(book.getSheet("ALL").getRow(1).getCell(21).getNumericCellValue()).isZero();
    }
  }

  @Test
  void cumulativeReviewMinutesCanExceedIntegerRange() throws Exception {
    try (var book = export("dgm", CodeReviewDataReadMode.MATCH_MODE, List.of(
        row("dgm", "版本", 77L, Integer.MAX_VALUE, Integer.MAX_VALUE),
        row("dgm", "版本", 77L, Integer.MAX_VALUE, Integer.MAX_VALUE),
        row("dgm", "版本", 78L, Integer.MAX_VALUE, Integer.MAX_VALUE)))) {
      assertOriginalStructure(book, 3);
      assertThat(book.getSheet("ALL").getRow(1).getCell(20).getNumericCellValue()).isEqualTo(30);
      assertThat(book.getSheet("ALL").getRow(2).getCell(20).getNumericCellValue()).isEqualTo(30);
      assertThat(book.getSheet("ALL").getRow(3).getCell(20).getNumericCellValue()).isEqualTo(60);
    }
  }

  @Test
  void emptyScopeOnlyExportsTheOriginalHeader() throws Exception {
    try (var book = export("dgm", CodeReviewDataReadMode.MATCH_MODE, List.of())) {
      assertOriginalStructure(book, 0);
    }
  }

  @Test
  void omittedSourceStillComputesTheCcMrRate() throws Exception {
    try (var book = export(null, CodeReviewDataReadMode.MATCH_MODE, List.of(
        row("cc", "旧名称", 77L, 1000, 60), row("cc", "组合名称", 77L, 1000, 30)))) {
      assertOriginalStructure(book, 2);
      assertThat(book.getSheet("ALL").getRow(1).getCell(20).getNumericCellValue()).isCloseTo(1000D / 90 * 60, offset(1e-9));
    }
  }

  @Test
  void existingRateColumnsDisplayTwoDecimalsWithoutRoundingTheCalculationEarly() throws Exception {
    try (var book = export("dgm", CodeReviewDataReadMode.MATCH_MODE, List.of(
        row("dgm", "版本", 77L, 1000, 60), row("dgm", "版本", 77L, 1000, 30)))) {
      assertOriginalStructure(book, 2);
      var row = book.getSheet("ALL").getRow(1);
      var formatter = new DataFormatter(Locale.ROOT);
      assertThat(formatter.formatCellValue(row.getCell(20))).isEqualTo("666.67");
      assertThat(formatter.formatCellValue(row.getCell(21))).isEqualTo("0.67");
      assertThat(row.getCell(21).getNumericCellValue()).isCloseTo(row.getCell(20).getNumericCellValue() / 1000, offset(1e-12));
    }
  }

  @Test
  void formalExportKeepsItsOriginalStoredRates() throws Exception {
    try (var book = export("dgm", CodeReviewDataReadMode.FORMAL,
        List.of(row("dgm", "版本", 77L, 1000, 60)))) {
      assertOriginalStructure(book, 1);
      assertThat(book.getSheet("ALL").getRow(1).getCell(20).getNumericCellValue()).isEqualTo(-999);
      assertThat(book.getSheet("ALL").getRow(1).getCell(21).getNumericCellValue()).isEqualTo(-0.999);
    }
  }

  private static void assertOriginalStructure(XSSFWorkbook book, int rows) {
    assertThat(book.getNumberOfSheets()).isEqualTo(1);
    assertThat(book.getSheetName(0)).isEqualTo("ALL");
    assertThat(book.getActiveSheetIndex()).isZero();
    assertThat(book.getSheet("ALL").getLastRowNum()).isEqualTo(rows);
    assertThat(book.getSheet("ALL").getRow(0).getLastCellNum()).isEqualTo((short) 35);
    assertThat(book.getSheet("ALL").getRow(0).getCell(20).getStringCellValue()).isEqualTo("代码走查速率（LOC/H）");
    assertThat(book.getSheet("ALL").getRow(0).getCell(21).getStringCellValue()).isEqualTo("代码走查速率（KLOC/H）");
  }

  private static void assertRateEmpty(XSSFWorkbook book, int index) {
    assertThat(book.getSheet("ALL").getRow(index).getCell(20).getCellType()).isNotEqualTo(CellType.NUMERIC);
    assertThat(book.getSheet("ALL").getRow(index).getCell(21).getCellType()).isNotEqualTo(CellType.NUMERIC);
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
