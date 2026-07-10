package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.QualityBoardCodeReviewRecordExportRow;
import com.data.collection.platform.entity.QualityBoardChartRowResponse;
import com.data.collection.platform.entity.QualityBoardFixUserSeverityRowResponse;
import com.data.collection.platform.entity.QualityBoardRdDashboardResponse;
import java.io.ByteArrayInputStream;
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
    when(rdService.getRdDashboard("CC2026R4", "dgm"))
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
      assertThat(xlsx.getSheetAt(0).getSheetName()).isEqualTo("按修复人统计缺陷数");
      assertThat(xlsx.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("名称");
      assertThat(xlsx.getSheetAt(0).getRow(0).getCell(5).getStringCellValue()).isEqualTo("建议类");
      assertThat(xlsx.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).isEqualTo("修复人A");
      assertThat(xlsx.getSheetAt(0).getRow(1).getCell(5).getNumericCellValue()).isEqualTo(4D);
    }
  }

  @Test
  void exportsCodeReviewRecordsThroughReadSupportSoMatchModeRemainsIsolated() throws Exception {
    when(codeReviewReadSupport.codeReviewRecordRows("dgm", "CC2026R4"))
        .thenReturn(
            List.of(
                new QualityBoardCodeReviewRecordExportRow(
                    "dgm",
                    "CrownCAD 2026 R4",
                    "DGM",
                    101L,
                    "修复几何问题",
                    "作者A",
                    "走查人A",
                    "dev",
                    "MERGED",
                    120,
                    3,
                    25D)));

    byte[] workbook = exportService.exportCodeReviewRecordsWorkbook("CC2026R4", "dgm");

    try (XSSFWorkbook xlsx = new XSSFWorkbook(new ByteArrayInputStream(workbook))) {
      assertThat(xlsx.getSheetAt(0).getSheetName()).isEqualTo("代码走查数据");
      assertThat(xlsx.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("数据源");
      assertThat(xlsx.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).isEqualTo("dgm");
      assertThat(xlsx.getSheetAt(0).getRow(1).getCell(1).getStringCellValue()).isEqualTo("CrownCAD 2026 R4");
      assertThat(xlsx.getSheetAt(0).getRow(1).getCell(10).getNumericCellValue()).isEqualTo(3D);
    }
  }
}
