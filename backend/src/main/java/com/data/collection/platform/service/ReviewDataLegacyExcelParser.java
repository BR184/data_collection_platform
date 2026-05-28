package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

@Component
public class ReviewDataLegacyExcelParser {
  private static final int MAX_ROWS = 5000;
  private static final List<String> REVIEW_TYPE_VALUES =
      List.of("需求说明书评审", "设计说明书评审", "产品用户手册", "项目计划评审", "其他");
  private static final List<DateTimeFormatter> DATE_FORMATS =
      List.of(
          DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
          DateTimeFormatter.ofPattern("yyyy/M/d H:mm:ss"),
          DateTimeFormatter.ofPattern("yyyy-MM-dd"),
          DateTimeFormatter.ofPattern("yyyy/M/d"));

  public ReviewDataLegacyExcelParseResult parse(
      InputStream inputStream, String filename, String requestedSheetName) {
    validateFileName(filename);
    try (Workbook workbook = WorkbookFactory.create(inputStream)) {
      Sheet sheet = selectSheet(workbook, requestedSheetName);
      HeaderRow headerRow = locateHeaderRow(sheet);
      List<ReviewDataLegacyExcelRow> rows = new ArrayList<>();
      List<ReviewDataLegacyExcelImportIssue> issues = new ArrayList<>();
      for (int rowIndex = headerRow.rowIndex() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
        if (rows.size() >= MAX_ROWS) {
          issues.add(issue(rowIndex + 1, "file", ReviewDataLegacyExcelIssueLevel.ERROR, "单次最多导入 5000 行"));
          break;
        }
        Row row = sheet.getRow(rowIndex);
        if (isBlankRow(row)) {
          continue;
        }
        ReviewDataLegacyExcelRow parsed = parseDataRow(row, headerRow.columns());
        rows.add(parsed);
        issues.addAll(parsed.issues());
      }
      return new ReviewDataLegacyExcelParseResult(sheet.getSheetName(), rows, issues);
    } catch (IOException exception) {
      throw new BizException("Excel 文件读取失败，请确认文件未损坏");
    } catch (RuntimeException exception) {
      if (exception instanceof BizException bizException) {
        throw bizException;
      }
      throw new BizException("Excel 文件解析失败：" + exception.getMessage());
    }
  }

  private ReviewDataLegacyExcelRow parseDataRow(Row row, Map<String, Integer> columns) {
    int rowNumber = row.getRowNum() + 1;
    List<ReviewDataLegacyExcelImportIssue> issues = new ArrayList<>();
    String title = text(row, columns, "title");
    String projectName = text(row, columns, "projectName");
    String moduleName = text(row, columns, "moduleName");
    String reviewType = normalizeReviewType(text(row, columns, "reviewType"));
    String reviewCategoryText = text(row, columns, "reviewCategory");
    LocalDate reviewDate = date(row, columns, "reviewDate");
    String reviewOwner = text(row, columns, "reviewOwner");
    Integer reviewScalePages = integer(row, columns, "reviewScalePages");
    Integer problemCount = integer(row, columns, "problemCount");
    Integer docSpecification = integer(row, columns, "docSpecification");
    Integer integrity = integer(row, columns, "integrity");
    Integer functionality = integer(row, columns, "functionality");
    Integer feasibility = integer(row, columns, "feasibility");
    Double density = decimal(row, columns, "reviewDefectDensity");
    Double efficiency = decimal(row, columns, "reviewEfficiency");
    Double rate = decimal(row, columns, "reviewRate");
    Double independentWorkload = decimal(row, columns, "independentWorkload");
    Double meetingWorkload = decimal(row, columns, "meetingWorkload");
    String notReachReason = text(row, columns, "notReachStandardReason");

    if (isBlank(title)) {
      issues.add(issue(rowNumber, "title", ReviewDataLegacyExcelIssueLevel.ERROR, "评审的工作产品不能为空"));
    }
    if (isBlank(projectName)) {
      issues.add(issue(rowNumber, "projectName", ReviewDataLegacyExcelIssueLevel.ERROR, "所属项目不能为空"));
    }
    if (isBlank(moduleName) && !isBlank(title)) {
      moduleName = extractModuleName(title);
    }
    if (isBlank(moduleName)) {
      issues.add(issue(rowNumber, "moduleName", ReviewDataLegacyExcelIssueLevel.ERROR, "模块不能为空"));
    }
    if (isBlank(reviewType)) {
      issues.add(issue(rowNumber, "reviewType", ReviewDataLegacyExcelIssueLevel.ERROR, "评审类型不能为空，请确认导出文件包含文档类型/sourceType列"));
    } else if (!REVIEW_TYPE_VALUES.contains(reviewType)) {
      issues.add(issue(rowNumber, "reviewType", ReviewDataLegacyExcelIssueLevel.ERROR, "评审类型不在新平台选项中：" + reviewType));
    }
    if (reviewScalePages == null || reviewScalePages < 0) {
      issues.add(issue(rowNumber, "reviewScalePages", ReviewDataLegacyExcelIssueLevel.ERROR, "评审规模必须是非负整数"));
    }
    int categorySum = safeInt(docSpecification) + safeInt(integrity) + safeInt(functionality) + safeInt(feasibility);
    if (problemCount != null && problemCount >= 0 && categorySum != problemCount) {
      issues.add(issue(rowNumber, "problemCount", ReviewDataLegacyExcelIssueLevel.ERROR, "问题总计与分类合计不一致"));
    }
    if (density != null && problemCount != null && reviewScalePages != null && reviewScalePages > 0) {
      double expectedDensity = problemCount.doubleValue() / reviewScalePages.doubleValue();
      if (Math.abs(expectedDensity - density) > 0.02) {
        issues.add(issue(rowNumber, "reviewDefectDensity", ReviewDataLegacyExcelIssueLevel.WARNING, "评审缺陷密度与问题总计/页数不一致"));
      }
    }

    return new ReviewDataLegacyExcelRow(
        rowNumber,
        title,
        projectName,
        moduleName,
        reviewType,
        reviewCategoryText,
        reviewDate,
        reviewOwner,
        reviewScalePages,
        problemCount,
        docSpecification,
        integrity,
        functionality,
        feasibility,
        density,
        efficiency,
        rate,
        independentWorkload,
        meetingWorkload,
        notReachReason,
        issues);
  }

  private HeaderRow locateHeaderRow(Sheet sheet) {
    for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= Math.min(sheet.getLastRowNum(), 20); rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row == null) {
        continue;
      }
      Map<String, Integer> columns = mapColumns(row);
      if (columns.containsKey("title") && columns.containsKey("projectName")) {
        return new HeaderRow(rowIndex, columns);
      }
    }
    throw new BizException("未识别到旧平台评审列表表头，请确认上传的是旧平台导出的评审列表 Excel");
  }

  private Map<String, Integer> mapColumns(Row header) {
    Map<String, Integer> columns = new HashMap<>();
    for (Cell cell : header) {
      String normalized = normalizeHeader(readCell(cell));
      String key = columnKey(normalized);
      if (key != null && !columns.containsKey(key)) {
        columns.put(key, cell.getColumnIndex());
      }
    }
    return columns;
  }

  private String columnKey(String header) {
    if (containsAny(header, "评审的工作产品", "标题")) {
      return "title";
    }
    if (containsAny(header, "所属项目", "项目")) {
      return "projectName";
    }
    if (containsAny(header, "模块")) {
      return "moduleName";
    }
    if (containsAny(header, "文档类型", "sourceType", "评审类型")) {
      return "reviewType";
    }
    if (containsAny(header, "评审类别")) {
      return "reviewCategory";
    }
    if (containsAny(header, "上传时间", "创建时间")) {
      return "reviewDate";
    }
    if (containsAny(header, "负责人", "评审负责人")) {
      return "reviewOwner";
    }
    if (containsAny(header, "评审规模", "页数")) {
      return "reviewScalePages";
    }
    if (containsAny(header, "评审缺陷个数", "问题总计", "缺陷个数")) {
      return "problemCount";
    }
    if (containsAny(header, "文档规范")) {
      return "docSpecification";
    }
    if (containsAny(header, "完整性")) {
      return "integrity";
    }
    if (containsAny(header, "功能性")) {
      return "functionality";
    }
    if (containsAny(header, "可行性")) {
      return "feasibility";
    }
    if (containsAny(header, "加权重")) {
      return "weightedDensity";
    }
    if (containsAny(header, "缺陷密度")) {
      return "reviewDefectDensity";
    }
    if (containsAny(header, "评审效率")) {
      return "reviewEfficiency";
    }
    if (containsAny(header, "评审速率")) {
      return "reviewRate";
    }
    if (containsAny(header, "独立评审工作量")) {
      return "independentWorkload";
    }
    if (containsAny(header, "会议评审工作量")) {
      return "meetingWorkload";
    }
    if (containsAny(header, "不达标原因", "不达标说明")) {
      return "notReachStandardReason";
    }
    return null;
  }

  private Sheet selectSheet(Workbook workbook, String requestedSheetName) {
    if (!isBlank(requestedSheetName)) {
      Sheet requested = workbook.getSheet(requestedSheetName);
      if (requested == null) {
        throw new BizException("Excel 中不存在指定 sheet：" + requestedSheetName);
      }
      return requested;
    }
    Sheet data = workbook.getSheet("Data");
    if (data != null) {
      return data;
    }
    for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
      Sheet sheet = workbook.getSheetAt(i);
      if (sheet.getSheetName().contains("评审")) {
        return sheet;
      }
    }
    return workbook.getSheetAt(0);
  }

  private String text(Row row, Map<String, Integer> columns, String key) {
    Integer index = columns.get(key);
    if (index == null) {
      return "";
    }
    return normalizeText(readCell(row.getCell(index)));
  }

  private Integer integer(Row row, Map<String, Integer> columns, String key) {
    Double value = decimal(row, columns, key);
    return value == null ? null : Math.max(0, (int) Math.round(value));
  }

  private Double decimal(Row row, Map<String, Integer> columns, String key) {
    Integer index = columns.get(key);
    if (index == null) {
      return null;
    }
    Cell cell = row.getCell(index);
    if (cell == null) {
      return null;
    }
    if (cell.getCellType() == CellType.NUMERIC) {
      return cell.getNumericCellValue();
    }
    String text = normalizeText(readCell(cell));
    if (isBlank(text)) {
      return null;
    }
    try {
      return Double.parseDouble(text.replace("%", ""));
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private LocalDate date(Row row, Map<String, Integer> columns, String key) {
    Integer index = columns.get(key);
    if (index == null) {
      return null;
    }
    Cell cell = row.getCell(index);
    if (cell == null) {
      return null;
    }
    if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
      return cell.getLocalDateTimeCellValue().toLocalDate();
    }
    String text = normalizeText(readCell(cell));
    if (isBlank(text)) {
      return null;
    }
    for (DateTimeFormatter formatter : DATE_FORMATS) {
      try {
        if (formatter.toString().contains("HourOfDay")) {
          return LocalDateTime.parse(text, formatter).toLocalDate();
        }
        return LocalDate.parse(text, formatter);
      } catch (DateTimeParseException ignored) {
      }
    }
    return null;
  }

  private String readCell(Cell cell) {
    if (cell == null) {
      return "";
    }
    return switch (cell.getCellType()) {
      case STRING -> cell.getStringCellValue();
      case NUMERIC -> DateUtil.isCellDateFormatted(cell)
          ? cell.getLocalDateTimeCellValue().toString()
          : stripTrailingZero(cell.getNumericCellValue());
      case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
      case FORMULA -> readFormulaCell(cell);
      default -> "";
    };
  }

  private String readFormulaCell(Cell cell) {
    try {
      return switch (cell.getCachedFormulaResultType()) {
        case STRING -> cell.getStringCellValue();
        case NUMERIC -> stripTrailingZero(cell.getNumericCellValue());
        case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
        default -> "";
      };
    } catch (RuntimeException ignored) {
      return "";
    }
  }

  private String normalizeReviewType(String value) {
    if (isBlank(value)) {
      return "";
    }
    String normalized = normalizeText(value);
    if (normalized.contains("需求") && !normalized.contains("用户")) {
      return "需求说明书评审";
    }
    if (normalized.contains("设计")) {
      return "设计说明书评审";
    }
    if (normalized.contains("用户手册")) {
      return "产品用户手册";
    }
    if (normalized.contains("项目计划")) {
      return "项目计划评审";
    }
    if (REVIEW_TYPE_VALUES.contains(normalized)) {
      return normalized;
    }
    return normalized;
  }

  private boolean isBlankRow(Row row) {
    if (row == null) {
      return true;
    }
    for (Cell cell : row) {
      if (!isBlank(readCell(cell))) {
        return false;
      }
    }
    return true;
  }

  private String extractModuleName(String title) {
    int start = title.indexOf('【');
    int end = title.indexOf('】');
    if (start >= 0 && end > start) {
      return title.substring(start + 1, end);
    }
    return "";
  }

  private ReviewDataLegacyExcelImportIssue issue(
      int rowNumber, String field, ReviewDataLegacyExcelIssueLevel level, String message) {
    return new ReviewDataLegacyExcelImportIssue(rowNumber, field, level, message);
  }

  private String normalizeHeader(String value) {
    return normalizeText(value).replaceAll("[\\s　（）()：:]", "").toLowerCase(Locale.ROOT);
  }

  private String normalizeText(String value) {
    return Objects.toString(value, "").replace('\n', ' ').replace('\r', ' ').trim();
  }

  private boolean containsAny(String value, String... patterns) {
    for (String pattern : patterns) {
      if (value.contains(normalizeHeader(pattern))) {
        return true;
      }
    }
    return false;
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private int safeInt(Integer value) {
    return value == null ? 0 : Math.max(0, value);
  }

  private String stripTrailingZero(double value) {
    if (value == Math.rint(value)) {
      return Long.toString((long) value);
    }
    return Double.toString(value);
  }

  private void validateFileName(String filename) {
    String lower = Objects.toString(filename, "").toLowerCase(Locale.ROOT);
    if (!lower.endsWith(".xlsx") && !lower.endsWith(".xls")) {
      throw new BizException("只支持 Excel 文件（.xlsx 或 .xls）");
    }
  }

  private record HeaderRow(int rowIndex, Map<String, Integer> columns) {}
}
