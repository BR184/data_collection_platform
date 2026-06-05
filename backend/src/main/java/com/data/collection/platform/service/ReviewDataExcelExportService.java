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
import java.util.Map;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

@Service
public class ReviewDataExcelExportService {
  private static final int EXPORT_PAGE_SIZE = 100;
  private static final DateTimeFormatter DATE_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final String[] RECORD_HEADERS = {
    "标题",
    "项目",
    "模块",
    "评审类型",
    "评审日期",
    "负责人",
    "评审专家",
    "页数",
    "评审工作产品",
    "作者",
    "评审版本",
    "问题合计(个)",
    "评审缺陷密度(个/页)",
    "评审效率(个/小时)",
    "评审速率(页/小时)",
    "独立评审工作量合计(小时)",
    "有效独立评审问题数合计(个)",
    "会议评审工作量合计(小时)",
    "有效会议评审问题数合计(个)",
    "更新时间",
    "不达标说明",
    "是否达标"
  };
  private static final String[] PROBLEM_HEADERS = {
    "评审人",
    "工作量(小时)",
    "评审类别",
    "文档位置",
    "问题类别",
    "问题描述",
    "建议解决方案",
    "问题状态",
    "责任人",
    "拒绝原因",
    "问题更新时间"
  };

  private final ReviewDataRecordQueryService queryService;
  private final ReviewDataRecordPersistenceSupport persistenceSupport;

  public ReviewDataExcelExportService(
      ReviewDataRecordQueryService queryService,
      ReviewDataRecordPersistenceSupport persistenceSupport) {
    this.queryService = queryService;
    this.persistenceSupport = persistenceSupport;
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
      setColumnWidths(sheet, 28, 18, 16, 18, 14, 14, 24, 10, 18, 14, 14, 14, 18, 18, 18, 24, 28, 24, 28, 20, 24, 12);
      sheet.createFreezePane(0, 1);
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException e) {
      throw new BizException("评审列表 Excel 导出失败");
    }
  }

  public byte[] exportProblemDetailsWorkbook(ReviewDataRecordQueryRequest request) {
    return exportProblemDetailsWorkbook(loadAllRecords(request));
  }

  public byte[] exportProblemDetailsWorkbook(Long recordId) {
    return exportProblemDetailsWorkbook(List.of(queryService.getRecordDetail(recordId).record()));
  }

  private byte[] exportProblemDetailsWorkbook(List<ReviewDataRecordRowResponse> records) {
    Map<Long, List<ReviewDataProblemItemResponse>> problemItemsByRecordId =
        persistenceSupport.listProblemItemsByRecordIds(
            records.stream().map(ReviewDataRecordRowResponse::id).filter(id -> id != null).toList());
    try (Workbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      ExportStyles styles = new ExportStyles(workbook);
      var sheet = workbook.createSheet("问题详情");
      writeHeader(sheet.createRow(0), styles.header, concat(RECORD_HEADERS, PROBLEM_HEADERS));
      int rowIndex = 1;
      for (ReviewDataRecordRowResponse record : records) {
        List<ReviewDataProblemItemResponse> items =
            problemItemsByRecordId.getOrDefault(record.id(), List.of());
        if (items.isEmpty()) {
          Row row = sheet.createRow(rowIndex++);
          writeRecordCells(row, record, styles.body);
          continue;
        }
        for (ReviewDataProblemItemResponse item : items) {
          Row row = sheet.createRow(rowIndex++);
          writeRecordCells(row, record, styles.body);
          writeProblemCells(row, RECORD_HEADERS.length, item, styles.body);
        }
      }
      setColumnWidths(
          sheet,
          28, 18, 16, 18, 14, 14, 24, 10, 18, 14, 14, 14, 18, 18, 18, 24, 28, 24, 28, 20, 24, 12,
          14, 14, 16, 16, 16, 36, 36, 14, 14, 24, 20);
      sheet.createFreezePane(0, 1);
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
                  request.tagSelections(),
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
    writeText(row, 1, record.projectName(), style);
    writeText(row, 2, record.moduleName(), style);
    writeText(row, 3, record.reviewType(), style);
    writeText(row, 4, formatDate(record.reviewDate()), style);
    writeText(row, 5, record.reviewOwner(), style);
    writeText(row, 6, record.reviewExpertsSummary(), style);
    writeNumber(row, 7, record.reviewScalePages(), style);
    writeText(row, 8, record.reviewProduct(), style);
    writeText(row, 9, record.authorName(), style);
    writeText(row, 10, record.reviewVersion(), style);
    writeNumber(row, 11, record.problemCount(), style);
    writeNumber(row, 12, record.problemDensity(), style);
    writeNumber(row, 13, record.reviewEfficiency(), style);
    writeNumber(row, 14, record.reviewRate(), style);
    writeNumber(row, 15, record.independentReviewWorkload(), style);
    writeNumber(row, 16, record.independentReviewProblemCount(), style);
    writeNumber(row, 17, record.meetingReviewWorkload(), style);
    writeNumber(row, 18, record.meetingReviewProblemCount(), style);
    writeText(row, 19, formatDateTime(record.updatedAt()), style);
    writeText(row, 20, record.notReachStandardReason(), style);
    writeText(row, 21, Boolean.TRUE.equals(record.reachStandard()) ? "是" : "否", style);
  }

  private void writeProblemCells(
      Row row, int offset, ReviewDataProblemItemResponse item, CellStyle style) {
    writeText(row, offset, item.reviewerName(), style);
    writeNumber(row, offset + 1, item.workloadHours(), style);
    writeText(row, offset + 2, item.reviewCategory(), style);
    writeText(row, offset + 3, item.documentPosition(), style);
    writeText(row, offset + 4, item.problemCategory(), style);
    writeText(row, offset + 5, item.problemDescription(), style);
    writeText(row, offset + 6, item.suggestedSolution(), style);
    writeText(row, offset + 7, item.problemStatus(), style);
    writeText(row, offset + 8, item.ownerName(), style);
    writeText(row, offset + 9, item.rejectionReason(), style);
    writeText(row, offset + 10, formatDateTime(item.updatedAt()), style);
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
    for (int index = 0; index < widths.length; index++) {
      sheet.setColumnWidth(index, widths[index] * 256);
    }
  }

  private String formatDate(LocalDate value) {
    return value == null ? "" : value.toString();
  }

  private String formatDateTime(LocalDateTime value) {
    return value == null ? "" : DATE_TIME_FORMATTER.format(value);
  }

  private String[] concat(String[] first, String[] second) {
    String[] result = new String[first.length + second.length];
    System.arraycopy(first, 0, result, 0, first.length);
    System.arraycopy(second, 0, result, first.length, second.length);
    return result;
  }

  private static class ExportStyles {
    private final CellStyle header;
    private final CellStyle body;

    private ExportStyles(Workbook workbook) {
      header = workbook.createCellStyle();
      header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
      header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      header.setAlignment(HorizontalAlignment.CENTER);
      header.setVerticalAlignment(VerticalAlignment.CENTER);
      header.setBorderBottom(BorderStyle.THIN);
      header.setBorderLeft(BorderStyle.THIN);
      header.setBorderRight(BorderStyle.THIN);
      header.setBorderTop(BorderStyle.THIN);

      body = workbook.createCellStyle();
      body.setVerticalAlignment(VerticalAlignment.CENTER);
      body.setBorderBottom(BorderStyle.THIN);
      body.setBorderLeft(BorderStyle.THIN);
      body.setBorderRight(BorderStyle.THIN);
      body.setBorderTop(BorderStyle.THIN);
      body.setWrapText(true);
    }
  }
}
