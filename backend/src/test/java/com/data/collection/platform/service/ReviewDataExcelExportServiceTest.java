package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.ReviewDataProblemItemResponse;
import com.data.collection.platform.entity.ReviewDataRecordListResponse;
import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import com.data.collection.platform.entity.ReviewDataSummaryResponse;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReviewDataExcelExportServiceTest {
  @Mock private ReviewDataRecordQueryService queryService;
  @Mock private ReviewDataRecordPersistenceSupport persistenceSupport;

  @Test
  void shouldBuildReviewRecordWorkbookWithLegacyMetricFields() throws Exception {
    ReviewDataRecordQueryRequest request = request();
    when(queryService.listRecords(new ReviewDataRecordQueryRequest(
            null, null, null, null, null, null, null, null, null, 1, 100, "updatedAt", "desc")))
        .thenReturn(new ReviewDataRecordListResponse(
            List.of(record()),
            1,
            1,
            100,
            "updatedAt",
            "desc",
            new ReviewDataSummaryResponse(1, 5, 24, 5)));

    byte[] workbook = new ReviewDataExcelExportService(queryService, persistenceSupport)
        .exportReviewRecordsWorkbook(request);

    try (XSSFWorkbook xlsx = new XSSFWorkbook(new ByteArrayInputStream(workbook))) {
      var sheet = xlsx.getSheet("评审列表");
      assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("标题");
      assertThat(sheet.getRow(0).getCell(13).getStringCellValue()).isEqualTo("评审效率(个/小时)");
      assertThat(sheet.getRow(0).getCell(20).getStringCellValue()).isEqualTo("不达标说明");
      assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("需求评审单");
      assertThat(sheet.getRow(1).getCell(12).getNumericCellValue()).isEqualTo(0.25);
      assertThat(sheet.getRow(1).getCell(13).getNumericCellValue()).isEqualTo(2.5);
      assertThat(sheet.getRow(1).getCell(20).getStringCellValue()).isEqualTo("样本不足");
      assertThat(sheet.getRow(1).getCell(21).getStringCellValue()).isEqualTo("是");
    }
  }

  @Test
  void shouldBuildProblemDetailWorkbookForFilteredRecords() throws Exception {
    ReviewDataRecordQueryRequest request = request();
    ReviewDataRecordRowResponse record = record();
    ReviewDataProblemItemResponse item =
        new ReviewDataProblemItemResponse(
            9L,
            1L,
            "专家A",
            1.5,
            "独立评审",
            "2.1",
            "完整性",
            "缺少异常流程",
            "补充异常流程",
            "负责人A",
            "",
            "已确认",
            LocalDateTime.of(2026, 4, 13, 9, 30));
    when(queryService.listRecords(new ReviewDataRecordQueryRequest(
            null, null, null, null, null, null, null, null, null, 1, 100, "updatedAt", "desc")))
        .thenReturn(new ReviewDataRecordListResponse(
            List.of(record),
            1,
            1,
            100,
            "updatedAt",
            "desc",
            new ReviewDataSummaryResponse(1, 5, 24, 5)));
    when(persistenceSupport.listProblemItemsByRecordIds(List.of(1L)))
        .thenReturn(Map.of(1L, List.of(item)));

    byte[] workbook = new ReviewDataExcelExportService(queryService, persistenceSupport)
        .exportProblemDetailsWorkbook(request);

    try (XSSFWorkbook xlsx = new XSSFWorkbook(new ByteArrayInputStream(workbook))) {
      var sheet = xlsx.getSheet("问题详情");
      assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("标题");
      assertThat(sheet.getRow(0).getCell(22).getStringCellValue()).isEqualTo("评审人");
      assertThat(sheet.getRow(0).getCell(29).getStringCellValue()).isEqualTo("问题状态");
      assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("需求评审单");
      assertThat(sheet.getRow(1).getCell(22).getStringCellValue()).isEqualTo("专家A");
      assertThat(sheet.getRow(1).getCell(24).getStringCellValue()).isEqualTo("独立评审");
      assertThat(sheet.getRow(1).getCell(27).getStringCellValue()).isEqualTo("缺少异常流程");
    }
  }

  private ReviewDataRecordQueryRequest request() {
    return new ReviewDataRecordQueryRequest(
        null, null, null, null, null, null, null, null, null, 1, 20, "updatedAt", "desc");
  }

  private ReviewDataRecordRowResponse record() {
    return new ReviewDataRecordRowResponse(
        1L,
        "CrownCAD",
        "需求评审单",
        "草图",
        "需求说明书评审",
        LocalDate.of(2026, 4, 10),
        "负责人A",
        "专家A、专家B",
        20,
        "需求文档",
        "作者A",
        "V1.0",
        5,
        0.25,
        2.5,
        10.0,
        1.2,
        3,
        0.8,
        2,
        "样本不足",
        true,
        LocalDateTime.of(2026, 4, 12, 10, 0),
        LocalDateTime.of(2026, 4, 12, 10, 0),
        false,
        null,
        null,
        null);
  }
}
