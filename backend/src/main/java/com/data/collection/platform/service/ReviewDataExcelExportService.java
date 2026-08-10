package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.ReviewDataProblemItemResponse;
import com.data.collection.platform.entity.ReviewDataRecordListResponse;
import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
    "更新日期"
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

  /**
   * 按当前筛选范围导出逐条评审问题清单。
   *
   * @param request 与评审列表一致的筛选和排序条件
   * @return 包含父评审字段和逐条问题字段的 Excel 工作簿字节
   */
  public byte[] exportProblemDetailsWorkbook(ReviewDataRecordQueryRequest request) {
    return exportProblemDetailsWorkbook(loadAllRecords(request), request);
  }

  /**
   * 导出指定评审记录下的逐条问题清单。
   *
   * @param recordId 评审记录 ID
   * @return 包含父评审字段和逐条问题字段的 Excel 工作簿字节
   */
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
        // 问题清单导出必须跟前端详情/展开行使用同一合并读源。
        List<ReviewDataProblemItemResponse> items = queryService.listProblemItems(record.id());
        if (items.isEmpty()) {
          continue;
        }
        for (ReviewDataProblemItemResponse item : items) {
          writeProblemItemCells(sheet.createRow(rowIndex++), record, item, styles.body);
        }
      }
      setColumnWidths(sheet, 18, 24, 40, 18, 14, 14, 18, 24, 18, 40, 40, 18, 22, 18, 22);
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

  private void writeProblemItemCells(
      Row row, ReviewDataRecordRowResponse record, ReviewDataProblemItemResponse item, CellStyle style) {
    writeText(row, 0, record.projectName(), style);
    writeText(row, 1, record.reviewType(), style);
    writeText(row, 2, record.reviewProduct(), style);
    writeText(row, 3, record.moduleName(), style);
    writeText(row, 4, item.reviewerName(), style);
    writeNumber(row, 5, item.workloadHours(), style);
    writeText(row, 6, item.reviewCategory(), style);
    writeText(row, 7, item.documentPosition(), style);
    writeText(row, 8, item.problemCategory(), style);
    writeText(row, 9, item.problemDescription(), style);
    writeText(row, 10, item.suggestedSolution(), style);
    writeText(row, 11, item.ownerName(), style);
    writeText(row, 12, item.rejectionReason(), style);
    writeText(row, 13, item.problemStatus(), style);
    writeText(row, 14, formatDateTime(item.updatedAt()), style);
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
