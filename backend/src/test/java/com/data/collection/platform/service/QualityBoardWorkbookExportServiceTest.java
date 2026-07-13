package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.QualityBoardCodeReviewRecordExportRow;
import com.data.collection.platform.entity.QualityBoardChartRowResponse;
import com.data.collection.platform.entity.QualityBoardFixUserSeverityRowResponse;
import com.data.collection.platform.entity.QualityBoardRdDashboardResponse;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class QualityBoardWorkbookExportServiceTest {

  private final QualityBoardRdService rdService = mock(QualityBoardRdService.class);
  private final QualityBoardCodeReviewReadSupport codeReviewReadSupport =
      mock(QualityBoardCodeReviewReadSupport.class);
  private final QualityBoardWorkbookExportService exportService =
      new QualityBoardWorkbookExportService(rdService, codeReviewReadSupport);

  @Test
  void exportsRdChartRowsFromCurrentSourceWithoutQueryingOtherCharts() throws Exception {
    when(codeReviewReadSupport.configuredReadMode()).thenReturn(CodeReviewDataReadMode.MATCH_MODE);
    when(rdService.getRdDashboard("CC2026R4", "dgm", CodeReviewDataReadMode.MATCH_MODE))
        .thenReturn(
            new QualityBoardRdDashboardResponse(
                null,
                "dgm",
                List.of(),
                List.of(new QualityBoardChartRowResponse("走查人A", 3.12)),
                List.of(new QualityBoardChartRowResponse("被走查人A", 2.5)),
                List.of(new QualityBoardFixUserSeverityRowResponse("修复人A", 1, 2, 3, 4, 10)),
                List.of(new QualityBoardChartRowResponse("提交人A", 8D)),
                List.of(new QualityBoardChartRowResponse("指派人A", 5D))));

    byte[] workbook = exportService.exportRdChartWorkbook("CC2026R4", "dgm", "fix-user-severity");

    try (XSSFWorkbook xlsx = new XSSFWorkbook(new ByteArrayInputStream(workbook))) {
      assertThat(xlsx.getSheetAt(0).getSheetName()).isEqualTo("修复人员统计");
      assertThat(xlsx.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("缺陷原因");
      assertThat(xlsx.getSheetAt(0).getRow(0).getCell(5).getStringCellValue()).isEqualTo("共计");
      assertThat(xlsx.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).isEqualTo("修复人A");
      assertThat(xlsx.getSheetAt(0).getRow(1).getCell(4).getNumericCellValue()).isEqualTo(4D);
      assertThat(xlsx.getSheetAt(0).getRow(1).getCell(5).getNumericCellValue()).isEqualTo(10D);
    }
  }

  @Test
  void exportsCodeReviewRecordsThroughReadSupportSoMatchModeRemainsIsolated() throws Exception {
    when(codeReviewReadSupport.configuredReadMode()).thenReturn(CodeReviewDataReadMode.MATCH_MODE);
    when(codeReviewReadSupport.codeReviewRecordRows(
            "dgm", "CC2026R4", CodeReviewDataReadMode.MATCH_MODE))
        .thenReturn(
            List.of(
                new QualityBoardCodeReviewRecordExportRow(
                    "dgm",
                    LocalDateTime.of(2026, 7, 1, 9, 30),
                    "CrownCAD 2026 R4",
                    "几何",
                    "MERGED",
                    101L,
                    "修复几何问题",
                    "作者A",
                    "走查人A",
                    "被指派人A",
                    LocalDateTime.of(2026, 7, 2, 10, 20),
                    "合并人A",
                    60,
                    120,
                    20,
                    1,
                    1,
                    0,
                    0,
                    1,
                    3,
                    120,
                    0.12,
                    25D,
                    0.5,
                    "dev",
                    "扫描通过",
                    2,
                    60,
                    "功能A",
                    15D,
                    "编码规范通过",
                    0,
                    "静态扫描通过",
                    "DGM",
                    130)));

    byte[] workbook = exportService.exportCodeReviewRecordsWorkbook("CC2026R4", "dgm");

    try (XSSFWorkbook xlsx = new XSSFWorkbook(new ByteArrayInputStream(workbook))) {
      assertThat(xlsx.getSheetAt(0).getSheetName()).isEqualTo("ALL");
      assertThat(xlsx.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("走查时间");
      assertThat(xlsx.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).isEqualTo("2026-07-01");
      assertThat(xlsx.getSheetAt(0).getRow(1).getCell(1).getStringCellValue()).isEqualTo("CrownCAD 2026 R4");
      assertThat(xlsx.getSheetAt(0).getRow(1).getCell(19).getNumericCellValue()).isEqualTo(3D);
      assertThat(xlsx.getSheetAt(0).getRow(1).getCell(33).getStringCellValue()).isEqualTo("DGM");
    }
  }
}
