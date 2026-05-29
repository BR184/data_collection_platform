package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.ReviewDataProblemItemSaveRequest;
import com.data.collection.platform.entity.ReviewDataRecordSaveRequest;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewDataLegacyExcelImportService {
  private static final String DEFAULT_PROBLEM_STATUS = "已关闭";
  private static final String DEFAULT_REVIEW_CATEGORY = "独立评审";
  private static final long PREVIEW_TTL_SECONDS = 30 * 60L;
  private static final int MAX_PREVIEW_CACHE_SIZE = 100;

  private final ReviewDataLegacyExcelParser parser;
  private final ReviewDataRecordCommandService commandService;
  private final ReviewDataRecordPersistenceSupport persistenceSupport;
  private final PreviewSessionStore<PreviewSession> previewSessions;

  public ReviewDataLegacyExcelImportService(
      ReviewDataLegacyExcelParser parser,
      ReviewDataRecordCommandService commandService,
      ReviewDataRecordPersistenceSupport persistenceSupport) {
    this(parser, commandService, persistenceSupport, Clock.systemUTC());
  }

  ReviewDataLegacyExcelImportService(
      ReviewDataLegacyExcelParser parser,
      ReviewDataRecordCommandService commandService,
      ReviewDataRecordPersistenceSupport persistenceSupport,
      Clock clock) {
    this.parser = parser;
    this.commandService = commandService;
    this.persistenceSupport = persistenceSupport;
    this.previewSessions =
        new PreviewSessionStore<>(clock, Duration.ofSeconds(PREVIEW_TTL_SECONDS), MAX_PREVIEW_CACHE_SIZE);
  }

  public ReviewDataLegacyExcelPreviewResponse preview(
      InputStream inputStream,
      String filename,
      String sheetName,
      ReviewDataLegacyExcelImportRequest request) {
    ReviewDataLegacyExcelParseResult parseResult = parser.parse(inputStream, filename, sheetName);
    String token = previewSessions.put(new PreviewSession(parseResult.sheetName(), parseResult.rows()));
    return buildPreviewResponse(token, parseResult.sheetName(), parseResult.rows(), parseResult.issues(), request);
  }

  @Transactional
  public ReviewDataLegacyExcelConfirmResponse confirm(ReviewDataLegacyExcelConfirmRequest request) {
    if (request == null || request.previewToken() == null || request.previewToken().isBlank()) {
      throw new BizException("缺少导入预览 token，请先上传并预览 Excel");
    }
    PreviewSession session = previewSessions.getValid(request.previewToken()).orElse(null);
    if (session == null) {
      throw new BizException("导入预览已失效，请重新上传 Excel");
    }
    ReviewDataLegacyExcelPreviewResponse preview = buildPreviewResponse(
        request.previewToken(),
        session.sheetName(),
        session.rows(),
        List.of(),
        toImportRequest(request));
    int importedRecords = 0;
    int skippedRecords = 0;
    int importedProblemItems = 0;
    try {
      for (ReviewDataLegacyExcelPreviewRowResponse row : preview.rows()) {
        if (!row.importable()) {
          continue;
        }
        if (shouldSkipDuplicate(request, row.record())) {
          skippedRecords++;
          continue;
        }
        Long recordId = commandService.createRecord(row.record());
        importedRecords++;
        for (ReviewDataProblemItemSaveRequest item : row.problemItems()) {
          commandService.createProblemItem(recordId, item);
          importedProblemItems++;
        }
      }
    } finally {
      previewSessions.remove(request.previewToken());
    }
    return new ReviewDataLegacyExcelConfirmResponse(importedRecords, skippedRecords, importedProblemItems, List.of());
  }

  private ReviewDataLegacyExcelPreviewResponse buildPreviewResponse(
      String token,
      String sheetName,
      List<ReviewDataLegacyExcelRow> rows,
      List<ReviewDataLegacyExcelImportIssue> globalIssues,
      ReviewDataLegacyExcelImportRequest request) {
    List<ReviewDataLegacyExcelPreviewRowResponse> previewRows = new ArrayList<>();
    List<ReviewDataLegacyExcelImportIssue> allIssues = new ArrayList<>(globalIssues == null ? List.of() : globalIssues);
    for (ReviewDataLegacyExcelRow row : rows) {
      ReviewDataLegacyExcelPreviewRowResponse previewRow = toPreviewRow(row, request);
      previewRows.add(previewRow);
      allIssues.addAll(previewRow.issues());
    }
    int importableRows = (int) previewRows.stream().filter(ReviewDataLegacyExcelPreviewRowResponse::importable).count();
    int warningRows = countRowsByLevel(previewRows, ReviewDataLegacyExcelIssueLevel.WARNING);
    int errorRows = countRowsByLevel(previewRows, ReviewDataLegacyExcelIssueLevel.ERROR);
    int estimatedProblemItems =
        previewRows.stream()
            .filter(ReviewDataLegacyExcelPreviewRowResponse::importable)
            .mapToInt(row -> row.problemItems().size())
            .sum();
    return new ReviewDataLegacyExcelPreviewResponse(
        token,
        sheetName,
        rows.size(),
        importableRows,
        warningRows,
        errorRows,
        importableRows,
        estimatedProblemItems,
        previewRows,
        allIssues);
  }

  private ReviewDataLegacyExcelImportRequest toImportRequest(ReviewDataLegacyExcelConfirmRequest request) {
    return new ReviewDataLegacyExcelImportRequest(
        request.defaultReviewDate(),
        request.defaultReviewOwner(),
        request.defaultReviewExperts(),
        request.defaultAuthorName(),
        request.defaultReviewVersion(),
        request.defaultProblemStatus(),
        request.duplicateStrategy());
  }

  private ReviewDataLegacyExcelPreviewRowResponse toPreviewRow(
      ReviewDataLegacyExcelRow row,
      ReviewDataLegacyExcelImportRequest request) {
    List<ReviewDataLegacyExcelImportIssue> issues = new ArrayList<>(row.issues());
    String owner = firstNonBlank(row.reviewOwner(), request == null ? "" : request.defaultReviewOwner());
    LocalDate reviewDate = row.reviewDate() == null && request != null ? request.defaultReviewDate() : row.reviewDate();
    String authorName = firstNonBlank(request == null ? "" : request.defaultAuthorName(), owner, "历史导入");
    String reviewVersion = firstNonBlank(request == null ? "" : request.defaultReviewVersion(), row.projectName());
    List<String> experts = request == null ? List.of() : request.defaultReviewExperts();
    if (experts.isEmpty() && !owner.isBlank()) {
      experts = List.of(owner);
    }
    String problemStatus = firstNonBlank(request == null ? "" : request.defaultProblemStatus(), DEFAULT_PROBLEM_STATUS);

    if (owner.isBlank()) {
      issues.add(issue(row.rowNumber(), "reviewOwner", ReviewDataLegacyExcelIssueLevel.ERROR, "负责人不能为空"));
    }
    if (reviewDate == null) {
      issues.add(issue(row.rowNumber(), "reviewDate", ReviewDataLegacyExcelIssueLevel.ERROR, "评审日期不能为空"));
    }
    if (experts.isEmpty()) {
      issues.add(issue(row.rowNumber(), "reviewExperts", ReviewDataLegacyExcelIssueLevel.ERROR, "评审专家不能为空"));
    }
    if (reviewVersion.isBlank()) {
      issues.add(issue(row.rowNumber(), "reviewVersion", ReviewDataLegacyExcelIssueLevel.ERROR, "评审版本不能为空"));
    }

    ReviewDataRecordSaveRequest record =
        new ReviewDataRecordSaveRequest(
            firstNonBlank(row.projectName()),
            firstNonBlank(row.title()),
            firstNonBlank(row.moduleName()),
            firstNonBlank(row.reviewType()),
            reviewDate,
            owner,
            experts,
            row.reviewScalePages() == null ? 0 : row.reviewScalePages(),
            firstNonBlank(row.title()),
            authorName,
            reviewVersion);
    List<ReviewDataProblemItemSaveRequest> problemItems =
        buildProblemItems(row, owner, experts, problemStatus, issues);
    boolean importable = issues.stream().noneMatch(issue -> issue.level() == ReviewDataLegacyExcelIssueLevel.ERROR);
    return new ReviewDataLegacyExcelPreviewRowResponse(row.rowNumber(), importable, record, problemItems, issues);
  }

  private List<ReviewDataProblemItemSaveRequest> buildProblemItems(
      ReviewDataLegacyExcelRow row,
      String owner,
      List<String> experts,
      String problemStatus,
      List<ReviewDataLegacyExcelImportIssue> issues) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    counts.put("文档规范", nonNegativeInt(row.docSpecificationCount()));
    counts.put("完整性", nonNegativeInt(row.integrityCount()));
    counts.put("功能性", nonNegativeInt(row.functionalityCount()));
    counts.put("可行性", nonNegativeInt(row.feasibilityCount()));
    int total = counts.values().stream().mapToInt(Integer::intValue).sum();
    if (total == 0) {
      return List.of();
    }
    double totalWorkload = nonNegativeDouble(row.independentWorkloadHours()) + nonNegativeDouble(row.meetingWorkloadHours());
    List<ReviewItemContext> contexts = buildReviewItemContexts(row, total, totalWorkload, issues);
    if (totalWorkload <= 0) {
      issues.add(issue(row.rowNumber(), "workloadHours", ReviewDataLegacyExcelIssueLevel.WARNING, "未读取到评审工作量，合成问题项工作量按 0 处理"));
    }
    String reviewer = experts.isEmpty() ? owner : experts.getFirst();
    List<ReviewDataProblemItemSaveRequest> items = new ArrayList<>();
    int contextIndex = 0;
    for (Map.Entry<String, Integer> entry : counts.entrySet()) {
      for (int i = 0; i < entry.getValue(); i++) {
        ReviewItemContext context = contexts.get(contextIndex++);
        items.add(
            new ReviewDataProblemItemSaveRequest(
                reviewer,
                context.workloadPerItem(),
                context.reviewCategory(),
                "",
                entry.getKey(),
                "历史汇总导入：旧平台导出未提供逐条问题明细，按" + entry.getKey() + "汇总数量生成。",
                "",
                "",
                firstNonBlank(row.notReachStandardReason()),
                problemStatus));
      }
    }
    return items;
  }

  private List<ReviewItemContext> buildReviewItemContexts(
      ReviewDataLegacyExcelRow row,
      int total,
      double totalWorkload,
      List<ReviewDataLegacyExcelImportIssue> issues) {
    double fallbackWorkload = totalWorkload <= 0 ? 0D : round2(totalWorkload / total);
    int independentCount = nonNegativeInt(row.independentProblemCount());
    int meetingCount = nonNegativeInt(row.meetingProblemCount());
    if (independentCount + meetingCount == 0) {
      return repeatedContext(total, resolveReviewCategory(row.reviewCategoryText()), fallbackWorkload);
    }
    if (independentCount + meetingCount != total) {
      issues.add(
          issue(
              row.rowNumber(),
              "reviewCategoryProblemCount",
              ReviewDataLegacyExcelIssueLevel.WARNING,
              "独立/会议评审问题数合计与问题分类合计不一致，已按问题分类总数补齐或截断"));
    }
    List<ReviewItemContext> contexts = new ArrayList<>();
    addReviewContexts(
        contexts,
        Math.min(independentCount, total),
        DEFAULT_REVIEW_CATEGORY,
        workloadPerProblem(row.independentWorkloadHours(), independentCount, fallbackWorkload));
    addReviewContexts(
        contexts,
        Math.min(meetingCount, Math.max(0, total - contexts.size())),
        "会议评审",
        workloadPerProblem(row.meetingWorkloadHours(), meetingCount, fallbackWorkload));
    if (contexts.size() < total) {
      contexts.addAll(
          repeatedContext(total - contexts.size(), resolveReviewCategory(row.reviewCategoryText()), fallbackWorkload));
    }
    return contexts.size() > total ? contexts.subList(0, total) : contexts;
  }

  private List<ReviewItemContext> repeatedContext(int count, String reviewCategory, double workloadPerItem) {
    List<ReviewItemContext> contexts = new ArrayList<>();
    addReviewContexts(contexts, count, reviewCategory, workloadPerItem);
    return contexts;
  }

  private void addReviewContexts(
      List<ReviewItemContext> contexts, int count, String reviewCategory, double workloadPerItem) {
    for (int i = 0; i < count; i++) {
      contexts.add(new ReviewItemContext(reviewCategory, workloadPerItem));
    }
  }

  private double workloadPerProblem(Double workloadHours, int problemCount, double fallbackWorkload) {
    double workload = safeDouble(workloadHours);
    return workload > 0 && problemCount > 0 ? round2(workload / problemCount) : fallbackWorkload;
  }

  private String resolveReviewCategory(String text) {
    String value = firstNonBlank(text);
    if (value.contains("会议")) {
      return "会议评审";
    }
    if (value.contains("走查")) {
      return "走查";
    }
    return DEFAULT_REVIEW_CATEGORY;
  }

  private boolean shouldSkipDuplicate(
      ReviewDataLegacyExcelConfirmRequest request,
      ReviewDataRecordSaveRequest record) {
    String strategy = firstNonBlank(request == null ? "" : request.duplicateStrategy(), "SKIP");
    if (!"SKIP".equalsIgnoreCase(strategy) || persistenceSupport == null) {
      return false;
    }
    return persistenceSupport.existsDuplicateRecord(
        record.projectName(),
        record.title(),
        record.reviewType(),
        record.reviewDate(),
        record.reviewVersion());
  }

  private int countRowsByLevel(
      List<ReviewDataLegacyExcelPreviewRowResponse> rows,
      ReviewDataLegacyExcelIssueLevel level) {
    return (int)
        rows.stream()
            .filter(row -> row.issues().stream().anyMatch(issue -> issue.level() == level))
            .count();
  }

  private ReviewDataLegacyExcelImportIssue issue(
      int rowNumber, String field, ReviewDataLegacyExcelIssueLevel level, String message) {
    return new ReviewDataLegacyExcelImportIssue(rowNumber, field, level, message);
  }

  private String firstNonBlank(String... values) {
    if (values == null) {
      return "";
    }
    for (String value : values) {
      String normalized = TextQuerySupport.trimToNull(value);
      if (normalized != null) {
        return normalized;
      }
    }
    return "";
  }

  private int safeInt(Integer value) {
    return value == null ? 0 : value;
  }

  private int nonNegativeInt(Integer value) {
    return Math.max(0, safeInt(value));
  }

  private double safeDouble(Double value) {
    return value == null ? 0D : value;
  }

  private double nonNegativeDouble(Double value) {
    return Math.max(0D, safeDouble(value));
  }

  private double round2(double value) {
    return Math.round(value * 100D) / 100D;
  }

  private record ReviewItemContext(String reviewCategory, double workloadPerItem) {}

  private record PreviewSession(
      String sheetName,
      List<ReviewDataLegacyExcelRow> rows) {

    PreviewSession {
      rows = rows == null ? List.of() : List.copyOf(rows);
    }
  }
}
