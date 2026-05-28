package com.data.collection.platform.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ReviewDataLegacyExcelParserTest {

  @Test
  void shouldParseLegacyExportRowsByHeaderName() throws Exception {
    byte[] workbook = workbook(
        List.of(
            "评审的工作产品",
            "评审类别",
            "文档类别",
            "文档类型",
            "评审缺陷个数",
            "文档规范",
            "完整性规范",
            "功能性规范",
            "可行性规范",
            "评审缺陷密度",
            "加权重的评审缺陷密度",
            "评审效率",
            "评审速率",
            "评审规模",
            "评审规模（单位）",
            "不达标原因",
            "有效的独立问题数",
            "有效的会议评审问题数",
            "所属项目"),
        List.of(
            "【工具模块】需求规格说明书评审",
            "[独立评审]",
            "需求规格说明",
            "需求说明书评审",
            6,
            1,
            2,
            1,
            2,
            0.18,
            2.10,
            1.20,
            6.80,
            34,
            "页",
            "",
            4,
            2,
            "CC2026R4"));

    ReviewDataLegacyExcelParser parser = new ReviewDataLegacyExcelParser();
    ReviewDataLegacyExcelParseResult result =
        parser.parse(new ByteArrayInputStream(workbook), "legacy.xlsx", null);

    assertEquals("Data", result.sheetName());
    assertEquals(1, result.rows().size());
    ReviewDataLegacyExcelRow row = result.rows().getFirst();
    assertEquals("【工具模块】需求规格说明书评审", row.title());
    assertEquals("CC2026R4", row.projectName());
    assertEquals("工具模块", row.moduleName());
    assertEquals("需求说明书评审", row.reviewType());
    assertEquals(34, row.reviewScalePages());
    assertEquals(6, row.problemCount());
    assertEquals(1, row.docSpecificationCount());
    assertEquals(2, row.integrityCount());
    assertEquals(1, row.functionalityCount());
    assertEquals(2, row.feasibilityCount());
    assertTrue(result.issues().isEmpty());
  }

  @Test
  void shouldReportMissingReviewTypeWhenSourceTypeColumnIsAbsent() throws Exception {
    byte[] workbook =
        workbook(
            List.of("评审的工作产品", "评审类别", "评审缺陷个数", "评审规模", "所属项目"),
            List.of("【草图模块】需求规格说明书评审", "[独立评审]", 1, 12, "CC2026R4"));

    ReviewDataLegacyExcelParser parser = new ReviewDataLegacyExcelParser();
    ReviewDataLegacyExcelParseResult result =
        parser.parse(new ByteArrayInputStream(workbook), "legacy.xlsx", null);

    assertEquals(1, result.rows().size());
    assertFalse(result.issues().isEmpty());
    assertTrue(result.issues().stream().anyMatch(issue -> issue.message().contains("评审类型")));
  }

  @Test
  void shouldApplyDefaultsAndBuildPreviewSummary() throws Exception {
    byte[] workbook =
        workbook(
            List.of(
                "评审的工作产品",
                "评审类别",
                "文档类型",
                "评审缺陷个数",
                "文档规范",
                "完整性规范",
                "功能性规范",
                "可行性规范",
                "评审规模",
                "所属项目"),
            List.of("【草图模块】需求规格说明书评审", "[独立评审]", "需求说明书评审", 2, 1, 1, 0, 0, 10, "CC2026R4"));
    ReviewDataLegacyExcelImportRequest request =
        new ReviewDataLegacyExcelImportRequest(
            LocalDate.of(2026, 5, 28),
            "负责人",
            List.of("专家A"),
            "作者",
            "R4",
            "已关闭",
            "SKIP");

    ReviewDataLegacyExcelImportService service =
        new ReviewDataLegacyExcelImportService(
            new ReviewDataLegacyExcelParser(), null, null);
    ReviewDataLegacyExcelPreviewResponse preview =
        service.preview(new ByteArrayInputStream(workbook), "legacy.xlsx", null, request);

    assertEquals(1, preview.totalRows());
    assertEquals(1, preview.importableRows());
    assertEquals(1, preview.estimatedRecordCount());
    assertEquals(2, preview.estimatedProblemItemCount());
    assertEquals("负责人", preview.rows().getFirst().record().reviewOwner());
    assertEquals("R4", preview.rows().getFirst().record().reviewVersion());
  }

  private byte[] workbook(List<String> headers, List<Object> values) throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("Data");
      var headerRow = sheet.createRow(0);
      for (int i = 0; i < headers.size(); i++) {
        headerRow.createCell(i).setCellValue(headers.get(i));
      }
      var dataRow = sheet.createRow(1);
      for (int i = 0; i < values.size(); i++) {
        Object value = values.get(i);
        if (value instanceof Number number) {
          dataRow.createCell(i).setCellValue(number.doubleValue());
        } else {
          dataRow.createCell(i).setCellValue(String.valueOf(value));
        }
      }
      workbook.write(out);
      return out.toByteArray();
    }
  }
}
