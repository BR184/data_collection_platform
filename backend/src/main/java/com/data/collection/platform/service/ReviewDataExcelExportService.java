package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.ReviewDataProblemItemResponse;
import com.data.collection.platform.entity.ReviewDataRecordListResponse;
import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

@Service
public class ReviewDataExcelExportService {
  private static final int EXPORT_PAGE_SIZE = 100;
  private static final DateTimeFormatter DATE_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final String[] RECORD_HEADERS = {
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
    "有效的会议评审数量",
    "所属项目"
  };
  private static final String[] PROBLEM_HEADERS = {
    "评审文档类型",
    "评审的工作产品",
    "评审类别",
    "文档类别",
    "评审缺陷个数",
    "需求页数/个数",
    "评审工作量（小时）",
    "问题类别数量统计-文档",
    "问题类别数量统计-完整性",
    "问题类别数量统计-功能性",
    "问题类别数量统计-可行性",
    "评审缺陷密度",
    "加权重的评审缺陷密度",
    "缺陷效率(个/小时)",
    "评审速率",
    "评审规模总和"
  };

  private final ReviewDataRecordQueryService queryService;

  public ReviewDataExcelExportService(ReviewDataRecordQueryService queryService) {
    this.queryService = queryService;
  }

  public byte[] exportReviewRecordsWorkbook(ReviewDataRecordQueryRequest request) {
    List<ReviewDataRecordRowResponse> records = loadAllRecords(request);
    try (Workbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      ExportStyles styles = new ExportStyles(workbook);
      var sheet = workbook.createSheet("评审列表");
      writeHeader(sheet.createRow(0), styles.header, RECORD_HEADERS);
      int rowIndex = 1;
      for (ReviewDataRecordRowResponse record : records) {
        writeRecordRow(sheet.createRow(rowIndex++), record, styles.body);
      }
      setColumnWidths(sheet, 50, 20, 30, 18, 14, 12, 12, 12, 12, 18, 18, 18, 18, 12, 16, 22, 18, 22, 30);
      sheet.createFreezePane(0, 1);
      ExcelExportStyles.applyHeaderRows(sheet, 1);
      writeFilterSnapshotSheet(workbook, styles, request);
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException e) {
      throw new BizException("评审列表 Excel 导出失败");
    }
  }

  public byte[] exportProblemDetailsWorkbook(ReviewDataRecordQueryRequest request) {
    return exportProblemDetailsWorkbook(loadAllRecords(request), request);
  }

  public byte[] exportProblemDetailsWorkbook(Long recordId) {
    return exportProblemDetailsWorkbook(List.of(queryService.getRecordDetail(recordId).record()), null);
  }

  private byte[] exportProblemDetailsWorkbook(
      List<ReviewDataRecordRowResponse> records, ReviewDataRecordQueryRequest request) {
    try (Workbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      ExportStyles styles = new ExportStyles(workbook);
      var sheet = workbook.createSheet("问题详情");
      writeHeader(sheet.createRow(0), styles.header, PROBLEM_HEADERS);
      int rowIndex = 1;
      for (ReviewDataRecordRowResponse record : records) {
        //问题清单导出必须跟前端详情/展开行使用同一合并读源。
        List<ReviewDataProblemItemResponse> items = queryService.listProblemItems(record.id());
        if (items.isEmpty()) {
          continue;
        }
        writeProblemSummaryCells(sheet.createRow(rowIndex++), record, items, styles.body);
      }
      setColumnWidths(sheet, 18, 30, 24, 18, 16, 12, 14, 22, 24, 24, 24, 18, 22, 18, 14, 14);
      sheet.createFreezePane(0, 1);
      ExcelExportStyles.applyHeaderRows(sheet, 1);
      writeFilterSnapshotSheet(workbook, styles, request);
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException e) {
      throw new BizException("评审问题详情 Excel 导出失败");
    }
  }

  private List<ReviewDataRecordRowResponse> loadAllRecords(ReviewDataRecordQueryRequest request) {
    List<ReviewDataRecordRowResponse> records = new ArrayList<>();
    int page = 1;
    long expectedTotal = Long.MAX_VALUE;
    while (records.size() < expectedTotal && page < 10000) {
      ReviewDataRecordListResponse response =
          queryService.listRecords(
              new ReviewDataRecordQueryRequest(
                  request.keyword(),
                  request.title(),
                  request.projectName(),
                  request.moduleName(),
                  request.reviewOwner(),
                  request.reviewType(),
                  request.problemStatus(),
                  request.reviewExpert(),
                  request.filterGroupJson(),
                  request.sourceInstance(),
                  page,
                  EXPORT_PAGE_SIZE,
                  request.sortField(),
                  request.sortOrder()));
      expectedTotal = response.total();
      records.addAll(response.records());
      if (response.records().isEmpty()) {
        break;
      }
      page += 1;
    }
    return records;
  }

  private void writeHeader(Row row, CellStyle style, String[] headers) {
    for (int index = 0; index < headers.length; index++) {
      writeText(row, index, headers[index], style);
    }
  }

  private void writeRecordRow(Row row, ReviewDataRecordRowResponse record, CellStyle style) {
    writeRecordCells(row, record, style);
  }

  private void writeRecordCells(Row row, ReviewDataRecordRowResponse record, CellStyle style) {
    writeText(row, 0, record.title(), style);
    writeText(row, 1, record.reviewCategorySummary(), style);
    writeText(row, 2, legacyDocumentCategory(record), style);
    writeText(row, 3, legacyDocumentType(record), style);
    writeNumber(row, 4, record.problemCount(), style);
    writeNumber(row, 5, record.docSpecificationCount(), style);
    writeNumber(row, 6, record.integrityCount(), style);
    writeNumber(row, 7, record.functionalityCount(), style);
    writeNumber(row, 8, record.feasibilityCount(), style);
    writeNumber(row, 9, record.problemDensity(), style);
    writeNumber(row, 10, record.weightedDefectDensity(), style);
    writeNumber(row, 11, record.reviewEfficiency(), style);
    writeNumber(row, 12, record.reviewRate(), style);
    writeNumber(row, 13, record.reviewScalePages(), style);
    writeText(row, 14, "页", style);
    writeText(row, 15, record.notReachStandardReason(), style);
    writeNumber(row, 16, record.independentReviewProblemCount(), style);
    writeNumber(row, 17, record.meetingReviewProblemCount(), style);
    writeText(row, 18, record.projectName(), style);
  }

  private void writeProblemSummaryCells(
      Row row, ReviewDataRecordRowResponse record, List<ReviewDataProblemItemResponse> items, CellStyle style) {
    ReviewDataMetricCalculator.ReviewProblemSummary summary =
        ReviewDataMetricCalculator.problemSummary(record::reviewScalePages, items);

    writeText(row, 0, record.reviewType(), style);
    writeText(row, 1, record.reviewProduct(), style);
    writeText(row, 2, legacyReviewCategoryListText(items), style);
    writeText(row, 3, legacyDocumentCategory(record), style);
    writeNumber(row, 4, summary.defectCount(), style);
    writeNumber(row, 5, summary.value1(), style);
    writeNumber(row, 6, summary.workload(), style);
    writeNumber(row, 7, summary.docSpecification(), style);
    writeNumber(row, 8, summary.integrity(), style);
    writeNumber(row, 9, summary.functionality(), style);
    writeNumber(row, 10, summary.feasibility(), style);
    writeNumber(row, 11, record.problemDensity(), style);
    writeNumber(row, 12, summary.weightedDefectDensity(), style);
    writeNumber(row, 13, summary.defectEfficiency(), style);
    writeNumber(row, 14, summary.reviewRate(), style);
    writeNumber(row, 15, summary.sumCount(), style);
  }

  private String legacyReviewCategoryListText(List<ReviewDataProblemItemResponse> items) {
    return items.stream()
        .map(ReviewDataProblemItemResponse::reviewCategory)
        .filter(value -> value != null && !value.isBlank())
        .distinct()
        .toList()
        .toString();
  }

  private String legacyDocumentCategory(ReviewDataRecordRowResponse record) {
    // 老平台导出口径，兼容模式 match mode 和正式模式共用；删除兼容模式时不要删除本映射。
    return record.reviewType();
  }

  private String legacyDocumentType(ReviewDataRecordRowResponse record) {
    String category = legacyDocumentCategory(record);
    if (category == null || category.isBlank()) {
      return "";
    }
    if (category.contains("需求")) {
      return "需求评审";
    }
    if (category.contains("设计")) {
      return "设计评审";
    }
    return category;
  }

  private void writeFilterSnapshotSheet(
      Workbook workbook, ExportStyles styles, ReviewDataRecordQueryRequest request) {
    if (request == null) {
      return;
    }
    List<String> snapshots = queryService.describeExpandedLabelGroupFilters(request);
    if (snapshots == null || snapshots.isEmpty()) {
      return;
    }
    var sheet = workbook.createSheet("筛选说明");
    writeText(sheet.createRow(0), 0, "导出时标签组展开快照", styles.header);
    int rowIndex = 1;
    for (String snapshot : snapshots) {
      writeText(sheet.createRow(rowIndex++), 0, snapshot, styles.body);
    }
    ExcelExportStyles.applyHeaderRows(sheet, 1);
    ExcelExportStyles.setReadableColumnWidths(sheet, 80);
  }

  private void writeText(Row row, int column, String value, CellStyle style) {
    var cell = row.createCell(column);
    cell.setCellValue(value == null ? "" : value);
    cell.setCellStyle(style);
  }

  private void writeNumber(Row row, int column, Number value, CellStyle style) {
    var cell = row.createCell(column);
    if (value != null) {
      cell.setCellValue(value.doubleValue());
    }
    cell.setCellStyle(style);
  }

  private void setColumnWidths(org.apache.poi.ss.usermodel.Sheet sheet, int... widths) {
    ExcelExportStyles.setReadableColumnWidths(sheet, widths);
  }

  private String formatDate(LocalDate value) {
    return value == null ? "" : value.toString();
  }

  private String formatDateTime(LocalDateTime value) {
    return value == null ? "" : DATE_TIME_FORMATTER.format(value);
  }

  private static class ExportStyles {
    private final CellStyle header;
    private final CellStyle body;

    private ExportStyles(Workbook workbook) {
      header = ExcelExportStyles.createHeaderStyle(workbook);
      body = ExcelExportStyles.createBodyStyle(workbook);
    }
  }
}
