package com.data.collection.platform.service;

import com.data.collection.platform.entity.QualityBoardChartRowResponse;
import com.data.collection.platform.entity.QualityBoardCodeReviewRecordExportRow;
import com.data.collection.platform.entity.QualityBoardCodeReviewRateSummaryRow;
import com.data.collection.platform.entity.QualityBoardFixUserSeverityRowResponse;
import com.data.collection.platform.entity.QualityBoardRdDashboardResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

@Service
public class QualityBoardWorkbookExportService {
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

  /** 按当前读模式导出走查明细；兼容模式同时提供MR去重速率汇总。 */
  public byte[] exportCodeReviewRecordsWorkbook(String projectName, String source) {
    CodeReviewDataReadMode readMode = codeReviewReadSupport.configuredReadMode();
    return exportCodeReviewRecordsWorkbook(projectName, source, readMode);
  }

  /**
   * 导出当前版本范围内的代码走查明细，兼容模式同时生成按来源和MR IID去重的速率汇总。
   * 同一提交新增行数只计一次、走查工时累计，两个工作表由同一次查询结果生成。
   *
   * @param projectName 启用的产品版本业务键；为空时使用管理员设置的默认版本
   * @param source 业务来源；dgm不区分大小写，其它值按cc处理
   * @param readMode 本次导出冻结的正式或兼容读模式
   * @return XLSX字节，保留ALL明细；兼容模式默认选中速率汇总，数据不完整时汇总速率为空
   */
  public byte[] exportCodeReviewRecordsWorkbook(
      String projectName, String source, CodeReviewDataReadMode readMode) {
    List<QualityBoardCodeReviewRecordExportRow> rows =
        codeReviewReadSupport.codeReviewRecordRows(
            source, normalizeProjectName(projectName), readMode);
    String normalizedSource = "dgm".equalsIgnoreCase(source == null ? "" : source.trim()) ? "dgm" : "cc";
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
      if (readMode == CodeReviewDataReadMode.MATCH_MODE) {
        writeRateSummarySheet(workbook, header, body, rows, normalizedSource);
        sheet.setSelected(false);
        workbook.setActiveSheet(1);
        workbook.getSheetAt(1).setSelected(true);
      }
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException error) {
      throw new IllegalStateException("Failed to export quality board code review records", error);
    }
  }

  private void writeRateSummarySheet(
      Workbook workbook,
      CellStyle header,
      CellStyle body,
      List<QualityBoardCodeReviewRecordExportRow> rows,
      String requestedSource) {
    List<QualityBoardCodeReviewRateSummaryRow> summaries =
        summarizeRates(rows, requestedSource);
    CellStyle rate = workbook.createCellStyle();
    rate.cloneStyleFrom(body);
    rate.setDataFormat(workbook.createDataFormat().getFormat("0.00"));
    Sheet sheet = workbook.createSheet("走查速率汇总");
    writeHeader(
        sheet,
        header,
        "合并请求标识",
        "来源",
        "项目名",
        "合并请求编号",
        "走查次数",
        "新增代码行数（去重）",
        "累计走查工时（分钟）",
        "代码走查速率（LOC/H）",
        "代码走查速率（KLOC/H）",
        "数据状态");
    for (int index = 0; index < summaries.size(); index++) {
      QualityBoardCodeReviewRateSummaryRow item = summaries.get(index);
      Row row = sheet.createRow(index + 1);
      write(row, 0, item.mergeRequestKey(), body);
      write(row, 1, item.source(), body);
      write(row, 2, item.projectName(), body);
      write(row, 3, item.mergeRequestIid(), body);
      write(row, 4, item.reviewCount(), body);
      write(row, 5, item.addedLines(), body);
      write(row, 6, item.reviewDurationMinutes(), body);
      write(row, 7, item.reviewSpeedLocPerHour(), rate);
      write(row, 8, item.reviewSpeedKlocPerHour(), rate);
      write(row, 9, item.status(), body);
    }
    ExcelExportStyles.applyHeaderRows(sheet, 1);
    ExcelExportStyles.autoSizeColumns(sheet, 10, 8);
  }

  private List<QualityBoardCodeReviewRateSummaryRow> summarizeRates(
      List<QualityBoardCodeReviewRecordExportRow> rows,
      String requestedSource) {
    Map<String, RateAccumulator> accumulators = new LinkedHashMap<>();
    for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
      QualityBoardCodeReviewRecordExportRow row = rows.get(rowIndex);
      String key = mergeRequestKey(row, requestedSource, rowIndex);
      RateAccumulator accumulator = accumulators.computeIfAbsent(
          key, ignored -> new RateAccumulator(row, key, identityMissing(row), requestedSource));
      accumulator.accept(row);
    }
    List<QualityBoardCodeReviewRateSummaryRow> summaries = new ArrayList<>();
    long totalRows = 0L;
    long totalMinutes = 0L;
    boolean incomplete = false;
    for (RateAccumulator accumulator : accumulators.values()) {
      QualityBoardCodeReviewRateSummaryRow summary = accumulator.toSummary();
      summaries.add(summary);
      totalRows += summary.addedLines() == null ? 0 : summary.addedLines();
      totalMinutes += summary.reviewDurationMinutes() == null ? 0 : summary.reviewDurationMinutes();
      incomplete |= !summary.complete();
    }
    if (!summaries.isEmpty()) {
      summaries.add(
          new QualityBoardCodeReviewRateSummaryRow(
              "ALL", "", "当前导出范围", null, rows.size(),
              incomplete ? null : totalRows,
              incomplete ? null : totalMinutes,
              incomplete || totalMinutes <= 0 ? null : totalRows * 60D / totalMinutes,
              incomplete || totalMinutes <= 0 ? null : totalRows / 1000D * 60D / totalMinutes,
              !incomplete && totalMinutes > 0,
              incomplete ? "数据不完整" : "正常"));
    }
    return summaries;
  }

  private String mergeRequestKey(
      QualityBoardCodeReviewRecordExportRow row,
      String requestedSource,
      int rowIndex) {
    if (identityMissing(row)) {
      // 无法证明相同MR的记录分别保留，行序只用于区分未知项，不充当MR身份。
      return requestedSource + ":缺失MR身份#" + (rowIndex + 1);
    }
    return requestedSource + ":iid=" + row.mergeRequestIid();
  }

  private boolean identityMissing(QualityBoardCodeReviewRecordExportRow row) {
    return row.mergeRequestIid() == null || row.mergeRequestIid() <= 0;
  }

  private static final class RateAccumulator {
    private final String key;
    private final String source;
    private final String projectName;
    private final Long iid;
    private final boolean identityMissing;
    private int count;
    private Integer addedLines;
    private long minutes;
    private boolean invalid;
    private String reason;

    private RateAccumulator(
        QualityBoardCodeReviewRecordExportRow first,
        String key,
        boolean identityMissing,
        String requestedSource) {
      this.key = key;
      this.source = requestedSource;
      this.projectName = first.projectName();
      this.iid = first.mergeRequestIid();
      this.identityMissing = identityMissing;
      this.count = 0;
    }

    private void accept(QualityBoardCodeReviewRecordExportRow row) {
      count++;
      if (row.addedLines() == null || row.addedLines() < 0
          || row.reviewDurationMinutes() == null || row.reviewDurationMinutes() <= 0) {
        invalid = true;
        reason = row.addedLines() == null || row.addedLines() < 0 ? "新增行数异常" : "走查工时异常";
      } else {
        if (addedLines == null) {
          addedLines = row.addedLines();
        } else if (!addedLines.equals(row.addedLines())) {
          invalid = true;
          reason = "同MR新增行数冲突";
        }
        minutes += row.reviewDurationMinutes();
      }
    }

    private QualityBoardCodeReviewRateSummaryRow toSummary() {
      boolean complete = !invalid && !identityMissing && minutes > 0;
      String status = identityMissing ? "数据不完整：MR身份缺失"
          : invalid ? "数据不完整：" + reason : "正常";
      return new QualityBoardCodeReviewRateSummaryRow(
          key, source, projectName, iid, count, complete ? (long) addedLines : null,
          complete ? minutes : null,
          complete ? addedLines * 60D / minutes : null,
          complete ? addedLines / 1000D * 60D / minutes : null,
          complete,
          status);
    }
  }

  public String codeReviewRecordsFilename(String projectName, String source) {
    String sourceLabel = "dgm".equalsIgnoreCase(source) ? "DGM" : "CC";
    return sourceLabel + "库-" + normalizeProjectName(projectName) + "项目-代码走查数据.xlsx";
  }

  private String normalizeProjectName(String projectName) {
    return rdService.normalizeProjectName(projectName);
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
