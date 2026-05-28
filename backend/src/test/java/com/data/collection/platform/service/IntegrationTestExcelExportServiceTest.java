package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IntegrationTestExcelExportServiceTest {
  @Mock private IntegrationTestQueryService queryService;

  @Test
  void shouldBuildModuleFunctionWorkbook() throws Exception {
    when(queryService.getModuleFunctionExportData(325L, "CC2025R4集成测试", null))
        .thenReturn(
            new IntegrationTestModuleFunctionExportData(
                "CC2025R4集成测试",
                List.of(
                    new IntegrationTestFunctionExportRow(
                        "AI", "智能预览", 36, 36, 0, 0, 8, 0, new BigDecimal("100.00"), "新功能")),
                List.of(new IntegrationTestModuleExportRow("AI", 36, 36, new BigDecimal("100.00")))));

    byte[] workbook =
        new IntegrationTestExcelExportService(queryService)
            .exportModuleFunctionWorkbook(325L, "CC2025R4集成测试", null);

    try (XSSFWorkbook xlsx = new XSSFWorkbook(new ByteArrayInputStream(workbook))) {
      var sheet = xlsx.getSheet("ALL");
      assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("功能名");
      assertThat(sheet.getRow(0).getCell(9).getStringCellValue()).isEqualTo("模块名称");
      assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("智能预览");
      assertThat(sheet.getRow(1).getCell(1).getNumericCellValue()).isEqualTo(36);
      assertThat(sheet.getRow(1).getCell(3).getNumericCellValue()).isEqualTo(1.0);
      assertThat(sheet.getRow(1).getCell(9).getStringCellValue()).isEqualTo("AI");
    }
  }

  @Test
  void shouldBuildComparisonWorkbook() throws Exception {
    when(queryService.getComparisonExportData(325L, "CC2025R2集成测试", "CC2025R3集成测试", null))
        .thenReturn(
            new IntegrationTestComparisonExportData(
                "CC2025R2集成测试",
                "CC2025R3集成测试",
                List.of(
                    new IntegrationTestComparisonExportRow(
                        "草图",
                        "尺寸约束",
                        new IntegrationTestComparisonMetric(
                            10, 9, 1, 1, 0, 0, new BigDecimal("90.00"), "老功能"),
                        new IntegrationTestComparisonMetric(
                            12, 12, 1, 0, 0, 0, new BigDecimal("100.00"), "老功能"),
                        new IntegrationTestComparisonMetric(
                            2, 3, 0, -1, 0, 0, new BigDecimal("10.00"), null)))));

    byte[] workbook =
        new IntegrationTestExcelExportService(queryService)
            .exportComparisonWorkbook(325L, "CC2025R2集成测试", "CC2025R3集成测试", null);

    try (XSSFWorkbook xlsx = new XSSFWorkbook(new ByteArrayInputStream(workbook))) {
      var sheet = xlsx.getSheetAt(0);
      assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("模块名称");
      assertThat(sheet.getRow(0).getCell(2).getStringCellValue()).isEqualTo("测试通过率(%)");
      assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("CC2025R2集成测试");
      assertThat(sheet.getRow(1).getCell(4).getStringCellValue()).isEqualTo("差值");
      assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("草图");
      assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("尺寸约束");
      assertThat(sheet.getRow(2).getCell(2).getNumericCellValue()).isEqualTo(0.9);
      assertThat(sheet.getRow(2).getCell(4).getNumericCellValue()).isEqualTo(0.1);
    }
  }
}
