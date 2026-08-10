package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.ReviewDataProblemItemResponse;
import com.data.collection.platform.entity.ReviewDataRecordListResponse;
import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import com.data.collection.platform.entity.ReviewDataSummaryResponse;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReviewDataExcelExportServiceTest {
  @Mock private ReviewDataRecordQueryService queryService;

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
    when(queryService.describeExpandedLabelGroupFilters(request)).thenReturn(List.of());

    byte[] workbook = new ReviewDataExcelExportService(queryService)
        .exportReviewRecordsWorkbook(request);

    try (XSSFWorkbook xlsx = new XSSFWorkbook(new ByteArrayInputStream(workbook))) {
      var sheet = xlsx.getSheet("评审列表");
      assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("评审的工作产品");
      assertThat(sheet.getRow(0).getCell(11).getStringCellValue()).isEqualTo("评审效率");
      assertThat(sheet.getRow(0).getCell(15).getStringCellValue()).isEqualTo("不达标原因");
      assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("需求评审单");
      assertThat(sheet.getRow(1).getCell(9).getNumericCellValue()).isEqualTo(0.25);
      assertThat(sheet.getRow(1).getCell(11).getNumericCellValue()).isEqualTo(2.5);
      assertThat(sheet.getRow(1).getCell(15).getStringCellValue()).isEqualTo("样本不足");
      assertThat(sheet.getRow(1).getCell(18).getStringCellValue()).isEqualTo("CrownCAD");
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
            LocalDateTime.of(2026, 4, 13, 9, 30),
            null);
    ReviewDataProblemItemResponse secondItem =
        new ReviewDataProblemItemResponse(
            10L,
            1L,
            "专家B",
            2.0,
            "会议评审",
            "3.2",
            "功能性",
            "异常流程无法保存",
            "增加保存失败提示",
            "负责人B",
            "当前版本不处理",
            "待确认",
            LocalDateTime.of(2026, 4, 14, 15, 45),
            null);
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
    when(queryService.describeExpandedLabelGroupFilters(request)).thenReturn(List.of());
    when(queryService.listProblemItems(1L)).thenReturn(List.of(item, secondItem));

    byte[] workbook = new ReviewDataExcelExportService(queryService)
        .exportProblemDetailsWorkbook(request);

    try (XSSFWorkbook xlsx = new XSSFWorkbook(new ByteArrayInputStream(workbook))) {
      var sheet = xlsx.getSheet("问题详情");
      assertThat(sheet.getRow(0).getPhysicalNumberOfCells()).isEqualTo(15);
      assertThat(
              IntStream.range(0, 15)
                  .mapToObj(index -> sheet.getRow(0).getCell(index).getStringCellValue())
                  .toList())
          .containsExactly(
              "项目名称",
              "评审文档的类型",
              "评审的工作产品",
              "模块名称",
              "评审专家",
              "评审工作量",
              "评审类别",
              "在文档中的位置",
              "问题类别",
              "问题描述",
              "建议解决方案",
              "责任人",
              "不接受理由",
              "问题状态",
              "更新日期");
      assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(3);
      assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("CrownCAD");
      assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("需求说明书评审");
      assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("需求文档");
      assertThat(sheet.getRow(1).getCell(3).getStringCellValue()).isEqualTo("草图");
      assertThat(sheet.getRow(1).getCell(4).getStringCellValue()).isEqualTo("专家A");
      assertThat(sheet.getRow(1).getCell(5).getNumericCellValue()).isEqualTo(1.5);
      assertThat(sheet.getRow(1).getCell(6).getStringCellValue()).isEqualTo("独立评审");
      assertThat(sheet.getRow(1).getCell(7).getStringCellValue()).isEqualTo("2.1");
      assertThat(sheet.getRow(1).getCell(8).getStringCellValue()).isEqualTo("完整性");
      assertThat(sheet.getRow(1).getCell(9).getStringCellValue()).isEqualTo("缺少异常流程");
      assertThat(sheet.getRow(1).getCell(10).getStringCellValue()).isEqualTo("补充异常流程");
      assertThat(sheet.getRow(1).getCell(11).getStringCellValue()).isEqualTo("负责人A");
      assertThat(sheet.getRow(1).getCell(12).getStringCellValue()).isEmpty();
      assertThat(sheet.getRow(1).getCell(13).getStringCellValue()).isEqualTo("已确认");
      assertThat(sheet.getRow(1).getCell(14).getStringCellValue()).isEqualTo("2026-04-13 09:30:00");
      assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("CrownCAD");
      assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("需求说明书评审");
      assertThat(sheet.getRow(2).getCell(2).getStringCellValue()).isEqualTo("需求文档");
      assertThat(sheet.getRow(2).getCell(3).getStringCellValue()).isEqualTo("草图");
      assertThat(sheet.getRow(2).getCell(4).getStringCellValue()).isEqualTo("专家B");
      assertThat(sheet.getRow(2).getCell(5).getNumericCellValue()).isEqualTo(2.0);
      assertThat(sheet.getRow(2).getCell(6).getStringCellValue()).isEqualTo("会议评审");
      assertThat(sheet.getRow(2).getCell(7).getStringCellValue()).isEqualTo("3.2");
      assertThat(sheet.getRow(2).getCell(8).getStringCellValue()).isEqualTo("功能性");
      assertThat(sheet.getRow(2).getCell(9).getStringCellValue()).isEqualTo("异常流程无法保存");
      assertThat(sheet.getRow(2).getCell(10).getStringCellValue()).isEqualTo("增加保存失败提示");
      assertThat(sheet.getRow(2).getCell(11).getStringCellValue()).isEqualTo("负责人B");
      assertThat(sheet.getRow(2).getCell(12).getStringCellValue()).isEqualTo("当前版本不处理");
      assertThat(sheet.getRow(2).getCell(13).getStringCellValue()).isEqualTo("待确认");
      assertThat(sheet.getRow(2).getCell(14).getStringCellValue()).isEqualTo("2026-04-14 15:45:00");
    }
  }

  @Test
  void shouldKeepSourceInstanceWhenPagingExportRecords() {
    String filterGroupJson =
        "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"moduleName\",\"operator\":\"eq\",\"valueType\":\"LABEL_GROUP\",\"labelGroupId\":1,\"labelGroupName\":\"核心模块\"}]}";
    ReviewDataRecordQueryRequest request =
        new ReviewDataRecordQueryRequest(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            filterGroupJson,
            "cc",
            1,
            20,
            "updatedAt",
            "desc");
    ReviewDataRecordQueryRequest expectedPageRequest =
        new ReviewDataRecordQueryRequest(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            filterGroupJson,
            "cc",
            1,
            100,
            "updatedAt",
            "desc");
    when(queryService.listRecords(expectedPageRequest))
        .thenReturn(
            new ReviewDataRecordListResponse(
                List.of(), 0, 1, 100, "updatedAt", "desc", new ReviewDataSummaryResponse(0, 0, 0, 0)));

    new ReviewDataExcelExportService(queryService).exportReviewRecordsWorkbook(request);

    verify(queryService).listRecords(expectedPageRequest);
  }

  @Test
  void shouldWriteLabelGroupExpansionSnapshotSheet() throws Exception {
    ReviewDataRecordQueryRequest request =
        new ReviewDataRecordQueryRequest(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"moduleName\",\"operator\":\"eq\",\"valueType\":\"LABEL_GROUP\",\"labelGroupId\":1,\"labelGroupName\":\"核心模块\"}]}",
            "cc",
            1,
            20,
            "updatedAt",
            "desc");
    when(queryService.listRecords(new ReviewDataRecordQueryRequest(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            request.filterGroupJson(),
            "cc",
            1,
            100,
            "updatedAt",
            "desc")))
        .thenReturn(new ReviewDataRecordListResponse(
            List.of(record()),
            1,
            1,
            100,
            "updatedAt",
            "desc",
            new ReviewDataSummaryResponse(1, 5, 24, 5)));
    when(queryService.describeExpandedLabelGroupFilters(request))
        .thenReturn(List.of("moduleName eq 核心模块（标签组：草图、工程图）"));

    byte[] workbook =
        new ReviewDataExcelExportService(queryService).exportReviewRecordsWorkbook(request);

    try (XSSFWorkbook xlsx = new XSSFWorkbook(new ByteArrayInputStream(workbook))) {
      var sheet = xlsx.getSheet("筛选说明");
      assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("导出时标签组展开快照");
      assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).contains("核心模块（标签组：草图、工程图）");
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
        "需求评审单.xlsx",
        0.31,
        LocalDateTime.of(2026, 4, 12, 10, 0),
        LocalDateTime.of(2026, 4, 12, 10, 0),
        false,
        null,
        null,
        null);
  }
}
