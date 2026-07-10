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
    QualityBoardRdDashboardResponse dashboard =
        rdService.getRdDashboard(projectName, codeReviewSource);
    return switch (chartKey) {
      case "assignee-defect-density" ->
          exportNamedValueRows("按走查人统计代码走查缺陷密度", dashboard.assigneeDefectDensityRows(), "缺陷密度(K/LOC)");
      case "author-defect-density" ->
          exportNamedValueRows("按被走查人统计代码走查缺陷密度", dashboard.authorDefectDensityRows(), "缺陷密度(K/LOC)");
      case "fix-user-severity" ->
          exportFixUserSeverityRows(dashboard.fixUserSeverityRows());
      case "frequency-code-submission" ->
          exportNamedValueRows("代码提交频次", dashboard.frequencyCodeSubmissionRows(), "提交次数");
      case "defect-repair-user" ->
          exportNamedValueRows("指派人剩余缺陷数量", dashboard.defectRepairUserRows(), "剩余缺陷数");
      default -> throw new IllegalArgumentException("Unsupported quality board chart: " + chartKey);
    };
  }

  public String rdChartFilename(String chartKey) {
    return switch (chartKey) {
      case "assignee-defect-density" -> "按走查人统计代码走查缺陷密度.xlsx";
      case "author-defect-density" -> "按被走查人统计代码走查缺陷密度.xlsx";
      case "fix-user-severity" -> "按修复人统计缺陷数.xlsx";
      case "frequency-code-submission" -> "代码提交频次.xlsx";
      case "defect-repair-user" -> "指派人剩余缺陷数量.xlsx";
      default -> "研发质量看板图表数据.xlsx";
    };
  }

  public byte[] exportCodeReviewRecordsWorkbook(String projectName, String source) {
    List<QualityBoardCodeReviewRecordExportRow> rows =
        codeReviewReadSupport.codeReviewRecordRows(source, normalizeProjectName(projectName));
    try (Workbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      CellStyle header = ExcelExportStyles.createHeaderStyle(workbook);
      CellStyle body = ExcelExportStyles.createBodyStyle(workbook);
      Sheet sheet = workbook.createSheet("代码走查数据");
      writeHeader(
          sheet,
          header,
          "数据源",
          "项目名称",
          "仓库",
          "合并请求IID",
          "标题",
          "提交人",
          "走查人",
          "目标分支",
          "状态",
          "新增行数",
          "缺陷数",
          "缺陷密度(K/LOC)");
      for (int index = 0; index < rows.size(); index++) {
        QualityBoardCodeReviewRecordExportRow item = rows.get(index);
        Row row = sheet.createRow(index + 1);
        write(row, 0, item.source(), body);
        write(row, 1, item.projectName(), body);
        write(row, 2, item.repositoryName(), body);
        write(row, 3, item.mergeRequestIid(), body);
        write(row, 4, item.title(), body);
        write(row, 5, item.authorName(), body);
        write(row, 6, item.assigneeNames(), body);
        write(row, 7, item.targetBranch(), body);
        write(row, 8, item.mergeRequestState(), body);
        write(row, 9, item.addedLines(), body);
        write(row, 10, item.defectCount(), body);
        write(row, 11, item.reviewDefectDensityPerKloc(), body);
      }
      ExcelExportStyles.applyHeaderRows(sheet, 1);
      ExcelExportStyles.autoSizeColumns(sheet, 12, 8);
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException error) {
      throw new IllegalStateException("Failed to export quality board code review records", error);
    }
  }

  public String codeReviewRecordsFilename(String source) {
    return "dgm".equalsIgnoreCase(source)
        ? "DGM库项目代码走查数据.xlsx"
        : "CC库项目代码走查数据.xlsx";
  }

  private String normalizeProjectName(String projectName) {
    String normalized = TextQuerySupport.trimToNull(projectName);
    return normalized == null ? DEFAULT_PROJECT_NAME : TextQuerySupport.normalizeDisplay(projectName);
  }

  private byte[] exportNamedValueRows(
      String sheetName,
      List<QualityBoardChartRowResponse> rows,
      String valueHeader) {
    try (Workbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      CellStyle header = ExcelExportStyles.createHeaderStyle(workbook);
      CellStyle body = ExcelExportStyles.createBodyStyle(workbook);
      Sheet sheet = workbook.createSheet(sheetName);
      writeHeader(sheet, header, "名称", valueHeader);
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
      Sheet sheet = workbook.createSheet("按修复人统计缺陷数");
      writeHeader(sheet, header, "名称", "合计", "一级缺陷", "二级缺陷", "三级缺陷", "建议类");
      for (int index = 0; index < rows.size(); index++) {
        QualityBoardFixUserSeverityRowResponse item = rows.get(index);
        Row row = sheet.createRow(index + 1);
        write(row, 0, item.name(), body);
        write(row, 1, item.total(), body);
        write(row, 2, item.level1(), body);
        write(row, 3, item.level2(), body);
        write(row, 4, item.level3(), body);
        write(row, 5, item.suggestion(), body);
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
