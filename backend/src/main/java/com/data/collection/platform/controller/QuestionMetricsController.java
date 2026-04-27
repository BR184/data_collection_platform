package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.SystemTestIllegalRecordFilterOptionsResponse;
import com.data.collection.platform.entity.SystemTestIllegalRecordListResponse;
import com.data.collection.platform.entity.SystemTestIssueSearchFilterOptionsResponse;
import com.data.collection.platform.entity.SystemTestIssueSearchListResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.service.IssueFactRecordListRequest;
import com.data.collection.platform.service.SystemTestIllegalRecordQueryRequest;
import com.data.collection.platform.service.SystemTestIllegalRecordService;
import com.data.collection.platform.service.SystemTestIssueSearchService;
import com.data.collection.platform.service.SystemTestIssueSearchQueryRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/question-metrics")
public class QuestionMetricsController {
  private final SystemTestIssueSearchService systemTestIssueSearchService;
  private final SystemTestIllegalRecordService systemTestIllegalRecordService;

  public QuestionMetricsController(
      SystemTestIssueSearchService systemTestIssueSearchService,
      SystemTestIllegalRecordService systemTestIllegalRecordService) {
    this.systemTestIssueSearchService = systemTestIssueSearchService;
    this.systemTestIllegalRecordService = systemTestIllegalRecordService;
  }

  @GetMapping("/issues")
  public ApiResponse<SystemTestIssueSearchListResponse> listIssues(
      @RequestParam(required = false) Long projectId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String issueIid,
      @RequestParam(required = false) String title,
      @RequestParam(required = false) String projectName,
      @RequestParam(required = false) String moduleName,
      @RequestParam(required = false) String testingPhase,
      @RequestParam(required = false) String authorName,
      @RequestParam(required = false) String assigneeName,
      @RequestParam(required = false) String issueState,
      @RequestParam(required = false) String severityLevel,
      @RequestParam(required = false) String bugStatus,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String milestoneTitle,
      @RequestParam(required = false) String createdAtStart,
      @RequestParam(required = false) String createdAtEnd,
      @RequestParam(required = false) String updatedAtStart,
      @RequestParam(required = false) String updatedAtEnd,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String sortBy,
      @RequestParam(required = false) String sortOrder) {
    return ApiResponse.success(
        systemTestIssueSearchService.listRecords(
            new SystemTestIssueSearchQueryRequest(
                new IssueFactRecordListRequest(
                    projectId,
                    keyword,
                    issueIid,
                    title,
                    projectName,
                    moduleName,
                    severityLevel,
                    null,
                    issueState,
                    bugStatus,
                    category,
                    milestoneTitle,
                    createdAtStart,
                    createdAtEnd,
                    updatedAtStart,
                    updatedAtEnd,
                    page,
                    size,
                    sortBy,
                    sortOrder),
                testingPhase,
                authorName,
                assigneeName)));
  }

  @GetMapping("/issues/filter-options")
  public ApiResponse<SystemTestIssueSearchFilterOptionsResponse> getIssueFilterOptions(
      @RequestParam(required = false) Long projectId) {
    return ApiResponse.success(systemTestIssueSearchService.getFilterOptions(projectId));
  }

  @GetMapping("/illegal-records")
  public ApiResponse<SystemTestIllegalRecordListResponse> listIllegalRecords(
      @RequestParam(required = false) Long projectId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String issueIid,
      @RequestParam(required = false) String title,
      @RequestParam(required = false) String projectName,
      @RequestParam(required = false) String moduleName,
      @RequestParam(required = false) String testingPhase,
      @RequestParam(required = false) String illegalReason,
      @RequestParam(required = false) String authorName,
      @RequestParam(required = false) String assigneeName,
      @RequestParam(required = false) String issueState,
      @RequestParam(required = false) String severityLevel,
      @RequestParam(required = false) String bugStatus,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String milestoneTitle,
      @RequestParam(required = false) String createdAtStart,
      @RequestParam(required = false) String createdAtEnd,
      @RequestParam(required = false) String updatedAtStart,
      @RequestParam(required = false) String updatedAtEnd,
      @RequestParam(required = false) String filterGroup,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String sortBy,
      @RequestParam(required = false) String sortOrder) {
    return ApiResponse.success(
        systemTestIllegalRecordService.listRecords(
            new SystemTestIllegalRecordQueryRequest(
                new IssueFactRecordListRequest(
                    projectId,
                    keyword,
                    issueIid,
                    title,
                    projectName,
                    moduleName,
                    severityLevel,
                    null,
                    issueState,
                    bugStatus,
                    category,
                    milestoneTitle,
                    createdAtStart,
                    createdAtEnd,
                    updatedAtStart,
                    updatedAtEnd,
                    page,
                    size,
                    sortBy,
                    sortOrder),
                testingPhase,
                illegalReason,
                authorName,
                assigneeName,
                filterGroup)));
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
