package com.data.collection.platform.service;

import com.data.collection.platform.entity.QualityBoardChartRowResponse;
import com.data.collection.platform.entity.QualityBoardCodeReviewRecordExportRow;
import com.data.collection.platform.entity.QualityBoardFixUserSeverityRowResponse;
import com.data.collection.platform.entity.QualityBoardRdDashboardResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

@Service
public class QualityBoardWorkbookExportService {
  private static final String DEFAULT_PROJECT_NAME = "CC2026R3";

  private final QualityBoardRdService rdService;
  private final QualityBoardCodeReviewReadSupport codeReviewReadSupport;

  public QualityBoardWorkbookExportService(
      QualityBoardRdService rdService,
      QualityBoardCodeReviewReadSupport codeReviewReadSupport) {
    this.rdService = rdService;
    this.codeReviewReadSupport = codeReviewReadSupport;
  }

  public byte[] exportRdChartWorkbook(
      String projectName,
      String codeReviewSource,
      String chartKey) {
    CodeReviewDataReadMode readMode = codeReviewReadSupport.configuredReadMode();
    return exportRdChartWorkbook(projectName, codeReviewSource, chartKey, readMode);
  }

  public byte[] exportRdChartWorkbook(
      String projectName,
      String codeReviewSource,
      String chartKey,
      CodeReviewDataReadMode readMode) {
    QualityBoardRdDashboardResponse dashboard =
        rdService.getRdDashboard(projectName, codeReviewSource, readMode);
    return switch (chartKey) {
      case "assignee-defect-density" ->
          exportNamedValueRows(
              "统计结果", dashboard.assigneeDefectDensityRows(), "名称", "缺陷密度(K/LOC)");
      case "author-defect-density" ->
          exportNamedValueRows(
              "统计结果", dashboard.authorDefectDensityRows(), "姓名", "被走查人千行缺陷率");
      case "fix-user-severity" ->
          exportFixUserSeverityRows(dashboard.fixUserSeverityRows());
      case "frequency-code-submission" ->
          exportNamedValueRows(
              "代码提交频次统计", dashboard.frequencyCodeSubmissionRows(), "姓名", "代码提交次数");
      case "defect-repair-user" ->
          exportNamedValueRows(
              "指派人剩余缺陷数量", dashboard.defectRepairUserRows(), "功能名称", "缺陷数量");
      default -> throw new IllegalArgumentException("Unsupported quality board chart: " + chartKey);
    };
  }

  public String rdChartFilename(String projectName, String chartKey) {
    String normalizedProjectName = normalizeProjectName(projectName);
    return switch (chartKey) {
      case "assignee-defect-density" -> normalizedProjectName + "走查人千行缺陷率统计.xlsx";
      case "author-defect-density" -> normalizedProjectName + "被走查人千行缺陷率统计.xlsx";
      case "fix-user-severity" -> normalizedProjectName + "-修复人-缺陷数量统计.xlsx";
      case "frequency-code-submission" -> normalizedProjectName + "代码提交频次统计.xlsx";
      case "defect-repair-user" -> normalizedProjectName + "指派人剩余缺陷数量统计.xlsx";
      default -> "研发质量看板图表数据.xlsx";
    };
  }

  public byte[] exportCodeReviewRecordsWorkbook(String projectName, String source) {
    CodeReviewDataReadMode readMode = codeReviewReadSupport.configuredReadMode();
    return exportCodeReviewRecordsWorkbook(projectName, source, readMode);
  }

  public byte[] exportCodeReviewRecordsWorkbook(
      String projectName, String source, CodeReviewDataReadMode readMode) {
    List<QualityBoardCodeReviewRecordExportRow> rows =
        codeReviewReadSupport.codeReviewRecordRows(
            source, normalizeProjectName(projectName), readMode);
    try (Workbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      CellStyle header = ExcelExportStyles.createHeaderStyle(workbook);
      CellStyle body = ExcelExportStyles.createBodyStyle(workbook);
      Sheet sheet = workbook.createSheet("ALL");
      writeHeader(
          sheet,
          header,
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
          "Clang-tidy 解析的新增代码行数结果");
      for (int index = 0; index < rows.size(); index++) {
        QualityBoardCodeReviewRecordExportRow item = rows.get(index);
        Row row = sheet.createRow(index + 1);
        write(row, 0, formatDate(item.codeWalkthroughDate()), body);
        write(row, 1, item.projectName(), body);
        write(row, 2, item.moduleName(), body);
        write(row, 3, item.mergeRequestState(), body);
        write(row, 4, item.mergeRequestIid(), body);
        write(row, 5, item.title(), body);
        write(row, 6, item.authorName(), body);
        write(row, 7, item.reviewerNames(), body);
        write(row, 8, item.assigneeNames(), body);
        write(row, 9, formatDateTime(item.mergedAtSource()), body);
        write(row, 10, item.mergeUserName(), body);
        write(row, 11, item.reviewDurationMinutes(), body);
        write(row, 12, item.addedLines(), body);
        write(row, 13, item.deletedLines(), body);
        write(row, 14, item.codeSpecificationCount(), body);
        write(row, 15, item.codeLogicSpecificationCount(), body);
        write(row, 16, item.performanceSpecificationCount(), body);
        write(row, 17, item.designSpecificationCount(), body);
        write(row, 18, item.otherSpecificationCount(), body);
        write(row, 19, item.defectCount(), body);
        write(row, 20, item.reviewSpeedLocPerHour(), body);
        write(row, 21, item.reviewSpeedKlocPerHour(), body);
        write(row, 22, item.reviewDefectDensityPerKloc(), body);
        write(row, 23, item.reviewEfficiencyPerHour(), body);
        write(row, 24, item.targetBranch(), body);
        write(row, 25, item.scanStatus(), body);
        write(row, 26, item.commitCount(), body);
        write(row, 27, item.commitRate(), body);
        write(row, 28, item.functionName(), body);
        write(row, 29, item.commentRate(), body);
        write(row, 30, item.annotationRateResult(), body);
        write(row, 31, item.scanBugCount(), body);
        write(row, 32, item.bugCountResult(), body);
        write(row, 33, item.repositoryName(), body);
        write(row, 34, item.clangAddedLineCount(), body);
      }
      ExcelExportStyles.applyHeaderRows(sheet, 1);
      ExcelExportStyles.autoSizeColumns(sheet, 35, 8);
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException error) {
      throw new IllegalStateException("Failed to export quality board code review records", error);
    }
  }

  public String codeReviewRecordsFilename(String projectName, String source) {
    String sourceLabel = "dgm".equalsIgnoreCase(source) ? "DGM" : "CC";
    return sourceLabel + "库-" + normalizeProjectName(projectName) + "项目-代码走查数据.xlsx";
  }

  private String normalizeProjectName(String projectName) {
    String normalized = TextQuerySupport.trimToNull(projectName);
    return normalized == null ? DEFAULT_PROJECT_NAME : TextQuerySupport.normalizeDisplay(projectName);
  }

  private String formatDate(java.time.LocalDateTime value) {
    return value == null
        ? ""
        : value.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
  }

  private String formatDateTime(java.time.LocalDateTime value) {
    return value == null
        ? ""
        : value.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
  }

  private byte[] exportNamedValueRows(
      String sheetName,
      List<QualityBoardChartRowResponse> rows,
      String nameHeader,
      String valueHeader) {
    try (Workbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      CellStyle header = ExcelExportStyles.createHeaderStyle(workbook);
      CellStyle body = ExcelExportStyles.createBodyStyle(workbook);
      Sheet sheet = workbook.createSheet(sheetName);
      writeHeader(sheet, header, nameHeader, valueHeader);
      for (int index = 0; index < rows.size(); index++) {
        QualityBoardChartRowResponse item = rows.get(index);
        Row row = sheet.createRow(index + 1);
        write(row, 0, item.name(), body);
        write(row, 1, item.value(), body);
      }
      ExcelExportStyles.applyHeaderRows(sheet, 1);
      ExcelExportStyles.autoSizeColumns(sheet, 2);
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException error) {
      throw new IllegalStateException("Failed to export quality board chart", error);
    }
  }

  private byte[] exportFixUserSeverityRows(List<QualityBoardFixUserSeverityRowResponse> rows) {
    try (Workbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      CellStyle header = ExcelExportStyles.createHeaderStyle(workbook);
      CellStyle body = ExcelExportStyles.createBodyStyle(workbook);
      Sheet sheet = workbook.createSheet("修复人员统计");
      writeHeader(
          sheet,
          header,
          "缺陷原因",
          "一级缺陷",
          "二级缺陷",
          "三级缺陷",
          "需求&建议类",
          "共计");
      for (int index = 0; index < rows.size(); index++) {
        QualityBoardFixUserSeverityRowResponse item = rows.get(index);
        Row row = sheet.createRow(index + 1);
        write(row, 0, item.name(), body);
        write(row, 1, item.level1(), body);
        write(row, 2, item.level2(), body);
        write(row, 3, item.level3(), body);
        write(row, 4, item.suggestion(), body);
        write(row, 5, item.total(), body);
      }
      ExcelExportStyles.applyHeaderRows(sheet, 1);
      ExcelExportStyles.autoSizeColumns(sheet, 6);
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException error) {
      throw new IllegalStateException("Failed to export quality board severity chart", error);
    }
  }

  private void writeHeader(Sheet sheet, CellStyle style, String... labels) {
    Row row = sheet.createRow(0);
    for (int index = 0; index < labels.length; index++) {
      write(row, index, labels[index], style);
    }
  }

  private void write(Row row, int columnIndex, Object value, CellStyle style) {
    Cell cell = row.createCell(columnIndex);
    cell.setCellStyle(style);
    if (value instanceof Number number) {
      cell.setCellValue(number.doubleValue());
      return;
    }
    cell.setCellValue(value == null ? "" : value.toString());
  }
}
