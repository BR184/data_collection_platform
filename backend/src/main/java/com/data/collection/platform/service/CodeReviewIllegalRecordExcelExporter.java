package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.CodeReviewIllegalRecordRowResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/** 代码走查非法记录的 legacy 35 列 Excel 导出器；列序与老平台导出布局一一对应，不得调整。 */
@Component
class CodeReviewIllegalRecordExcelExporter {
  private static final DateTimeFormatter EXPORT_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
  private static final String[] LEGACY_EXPORT_HEADERS = {
    "走查时间",
    "项目名",
    "模块名",
    "合并请求状态",
    "合并请求编号",
    "合并请求内容",
    "被走查人",
    "走查人",
    "被指派人",
    "合并时间",
    "合并人",
    "走查工作量（分钟）",
    "新增走查代码行数（LOC）",
    "删除走查代码行数（LOC）",
    "规范类缺陷数（个）",
    "逻辑类缺陷数（个）",
    "性能类缺陷个数",
    "设计类缺陷个数",
    "其他类缺陷数（个）",
    "缺陷数（个）",
    "代码走查速率（LOC/H）",
    "代码走查速率（KLOC/H）",
    "代码走查缺陷密度（个/KLOC）",
    "代码走查效率（个/H）",
    "合并目标分支",
    "是否进行sonQube扫描",
    "提交次数",
    "提交频率(行每次)",
    "功能名称",
    "代码注释量%",
    "编码规范扫描结果",
    "bug数量",
    "静态扫描结果",
    "所属项目名称",
    "Clang-tidy 解析的新增代码行数结果"
  };

  /** 写出单个 legacy 布局工作表并返回工作簿字节；sheetName 由调用方决定。 */
  byte[] exportWorkbook(
      String illegalSheetName,
      List<CodeReviewIllegalRecordRowResponse> illegalRows,
      String allSheetName,
      List<CodeReviewIllegalRecordRowResponse> allRows) {
    try (Workbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      ExportStyles styles = new ExportStyles(workbook);
      writeLegacySheet(workbook, styles, illegalSheetName, illegalRows);
      if (allRows != null) {
        writeLegacySheet(workbook, styles, allSheetName, allRows);
      }
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException e) {
      throw new BizException("代码走查非法数据 Excel 导出失败");
    }
  }

  private void writeLegacySheet(
      Workbook workbook,
      ExportStyles styles,
      String sheetName,
      List<CodeReviewIllegalRecordRowResponse> rows) {
    var sheet = workbook.createSheet(sheetName);
    writeHeader(sheet.createRow(0), styles.header);
    int rowIndex = 1;
    for (CodeReviewIllegalRecordRowResponse row : rows) {
      writeLegacyExportRow(sheet.createRow(rowIndex++), row, styles.body);
    }
    ExcelExportStyles.setReadableColumnWidths(
        sheet,
        18, 18, 16, 14, 14, 36, 16, 22, 22, 20, 16, 18, 20, 20, 18, 18, 18, 18,
        18, 14, 20, 20, 24, 20, 18, 20, 12, 18, 20, 14, 22, 12, 22, 18, 28);
    sheet.createFreezePane(0, 1);
    ExcelExportStyles.applyHeaderRows(sheet, 1);
  }

  private void writeHeader(Row row, CellStyle style) {
    for (int index = 0; index < LEGACY_EXPORT_HEADERS.length; index++) {
      writeText(row, index, LEGACY_EXPORT_HEADERS[index], style);
    }
  }

  private void writeLegacyExportRow(
      Row row, CodeReviewIllegalRecordRowResponse record, CellStyle style) {
    writeText(row, 0, formatDate(record.codeWalkthroughDate()), style);
    writeText(row, 1, record.projectName(), style);
    writeText(row, 2, record.moduleName(), style);
    writeText(row, 3, "MERGED", style);
    writeNumber(row, 4, record.mergeRequestIid(), style);
    writeText(row, 5, record.mergeRequestContent(), style);
    writeText(row, 6, record.author(), style);
    writeText(row, 7, record.reviewerNames(), style);
    writeText(row, 8, record.assigneeNames(), style);
    writeText(row, 9, CsvExportSupport.dateTime(record.mergedAt()), style);
    writeText(row, 10, record.mergedBy(), style);
    writeNumber(row, 11, record.reviewDurationMinutes(), style);
    writeNumber(row, 12, record.addedLines(), style);
    writeNumber(row, 13, record.deletedLines(), style);
    writeNumber(row, 14, record.codeSpecificationCount(), style);
    writeNumber(row, 15, record.codeLogicSpecificationCount(), style);
    writeNumber(row, 16, record.performanceSpecificationCount(), style);
    writeNumber(row, 17, record.designSpecificationCount(), style);
    writeNumber(row, 18, record.otherSpecificationCount(), style);
    writeNumber(row, 19, record.defectCount(), style);
    writeNumber(row, 20, record.reviewSpeedLocPerHour(), style);
    writeNumber(row, 21, record.reviewSpeedKlocPerHour(), style);
    writeNumber(row, 22, record.defectDensityPerKloc(), style);
    writeNumber(row, 23, record.reviewEfficiencyPerHour(), style);
    writeText(row, 24, record.targetBranch(), style);
    writeText(row, 25, record.scanStatus(), style);
    writeNumber(row, 26, record.commitCount(), style);
    writeNumber(row, 27, record.commitRate(), style);
    writeText(row, 28, record.functionName(), style);
    writeNumber(row, 29, record.commentRate(), style);
    writeText(row, 30, record.annotationRateResult(), style);
    writeNumber(row, 31, record.scanBugCount(), style);
    writeText(row, 32, record.bugCountResult(), style);
    writeText(row, 33, record.repositoryName(), style);
    writeNumber(row, 34, record.clangAddedLineCount(), style);
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

  private String formatDate(java.time.LocalDateTime value) {
    return value == null ? "" : EXPORT_DATE.format(value);
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
