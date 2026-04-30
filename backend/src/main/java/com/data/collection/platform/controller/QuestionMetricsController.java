package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.SystemTestIllegalRecordFilterOptionsResponse;
import com.data.collection.platform.entity.SystemTestIllegalRecordListResponse;
import com.data.collection.platform.entity.SystemTestIssueSearchFilterOptionsResponse;
import com.data.collection.platform.entity.SystemTestIssueSearchListResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.service.SystemTestIllegalRecordService;
import com.data.collection.platform.service.SystemTestIssueSearchService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/question-metrics")
public class QuestionMetricsController {
  private final SystemTestIssueSearchService systemTestIssueSearchService;
  private final SystemTestIllegalRecordService systemTestIllegalRecordService;
  private final QuestionMetricsRequestAssembler questionMetricsRequestAssembler;

  public QuestionMetricsController(
      SystemTestIssueSearchService systemTestIssueSearchService,
      SystemTestIllegalRecordService systemTestIllegalRecordService,
      QuestionMetricsRequestAssembler questionMetricsRequestAssembler) {
    this.systemTestIssueSearchService = systemTestIssueSearchService;
    this.systemTestIllegalRecordService = systemTestIllegalRecordService;
    this.questionMetricsRequestAssembler = questionMetricsRequestAssembler;
  }

  @GetMapping("/issues")
  public ApiResponse<SystemTestIssueSearchListResponse> listIssues(
      @ModelAttribute SystemTestIssueSearchListWebRequest request) {
    return ApiResponse.success(
        systemTestIssueSearchService.listRecords(
            questionMetricsRequestAssembler.toIssueSearchQueryRequest(request)));
  }

  @GetMapping("/issues/export")
  public ResponseEntity<String> exportIssues(
      @ModelAttribute SystemTestIssueSearchListWebRequest request) {
    String csv =
        systemTestIssueSearchService.exportRecordsCsv(
            questionMetricsRequestAssembler.toIssueSearchQueryRequest(request));
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"system-test-issues.csv\"")
        .body(csv);
  }

  @GetMapping("/issues/filter-options")
  public ApiResponse<SystemTestIssueSearchFilterOptionsResponse> getIssueFilterOptions(
      @RequestParam(required = false) Long projectId) {
    return ApiResponse.success(systemTestIssueSearchService.getFilterOptions(projectId));
  }

  @GetMapping("/illegal-records")
  public ApiResponse<SystemTestIllegalRecordListResponse> listIllegalRecords(
      @ModelAttribute SystemTestIllegalRecordListWebRequest request) {
    return ApiResponse.success(
        systemTestIllegalRecordService.listRecords(
            questionMetricsRequestAssembler.toIllegalRecordQueryRequest(request)));
  }

  @GetMapping("/illegal-records/export")
  public ResponseEntity<String> exportIllegalRecords(
      @ModelAttribute SystemTestIllegalRecordListWebRequest request) {
    String csv =
        systemTestIllegalRecordService.exportRecordsCsv(
            questionMetricsRequestAssembler.toIllegalRecordQueryRequest(request));
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"system-test-illegal-records.csv\"")
        .body(csv);
  }

  @GetMapping("/illegal-records/filter-options")
  public ApiResponse<SystemTestIllegalRecordFilterOptionsResponse> getIllegalRecordFilterOptions(
      @RequestParam(required = false) Long projectId) {
    return ApiResponse.success(systemTestIllegalRecordService.getFilterOptions(projectId));
  }

  @GetMapping("/illegal-records/rule-explanation")
  public ApiResponse<StatisticBoardRuleExplanationResponse> getIllegalRecordRuleExplanation(
      @RequestParam(required = false) Long projectId) {
    return ApiResponse.success(systemTestIllegalRecordService.getRuleExplanation(projectId));
  }
}
