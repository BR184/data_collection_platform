package com.data.collection.platform.controller;

import com.data.collection.platform.common.DownloadResponseHeaders;
import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.RealtimeWorkspaceStatusResponse;
import com.data.collection.platform.entity.SystemTestIllegalRecordFilterOptionsResponse;
import com.data.collection.platform.entity.SystemTestIllegalRecordListResponse;
import com.data.collection.platform.entity.SystemTestIllegalRecordRowResponse;
import com.data.collection.platform.entity.SystemTestIssueSearchFilterOptionsResponse;
import com.data.collection.platform.entity.SystemTestIssueSearchListResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.entity.statistics.SystemTestIssueMultiBoardResponse;
import com.data.collection.platform.security.PlatformPermissionCodes;
import com.data.collection.platform.security.RequirePermission;
import com.data.collection.platform.service.IssueFactRealtimeRefreshService;
import com.data.collection.platform.service.IssueFactRecordListRequest;
import com.data.collection.platform.service.SystemTestIllegalRecordService;
import com.data.collection.platform.service.SystemTestIssueSearchService;
import com.data.collection.platform.service.statistics.SystemTestIssueMultiBoardService;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/question-metrics")
// 系统测试问题指标控制器保留旧入口路径，但内部已收口到 issue_fact 查询服务。
// 这样前端路由和历史接口可以稳定过渡，业务口径仍由共享事实层统一。
public class QuestionMetricsController {
  private static final String ISSUE_SEARCH_WORKSPACE_KEY = "system-test-issues";
  private static final String ILLEGAL_RECORDS_WORKSPACE_KEY = "system-test-illegal-records";
  private static final MediaType EXCEL_MEDIA_TYPE =
      MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

  private final SystemTestIssueSearchService systemTestIssueSearchService;
  private final SystemTestIllegalRecordService systemTestIllegalRecordService;
  private final SystemTestIssueMultiBoardService systemTestIssueMultiBoardService;
  private final QuestionMetricsRequestAssembler questionMetricsRequestAssembler;
  private final IssueFactRealtimeRefreshService realtimeRefreshService;

  public QuestionMetricsController(
      SystemTestIssueSearchService systemTestIssueSearchService,
      SystemTestIllegalRecordService systemTestIllegalRecordService,
      SystemTestIssueMultiBoardService systemTestIssueMultiBoardService,
      QuestionMetricsRequestAssembler questionMetricsRequestAssembler,
      IssueFactRealtimeRefreshService realtimeRefreshService) {
    this.systemTestIssueSearchService = systemTestIssueSearchService;
    this.systemTestIllegalRecordService = systemTestIllegalRecordService;
    this.systemTestIssueMultiBoardService = systemTestIssueMultiBoardService;
    this.questionMetricsRequestAssembler = questionMetricsRequestAssembler;
    this.realtimeRefreshService = realtimeRefreshService;
  }

  @GetMapping("/multi-board")
  @RequirePermission("system_test.board.view")
  public ApiResponse<SystemTestIssueMultiBoardResponse> getMultiBoard(
      @RequestParam(required = false) Long projectId,
      @RequestParam(required = false) String testingPhase) {
    return ApiResponse.success(systemTestIssueMultiBoardService.getBoard(projectId, testingPhase));
  }

  @GetMapping("/multi-board/export")
  @RequirePermission("system_test.board.export")
  public ResponseEntity<byte[]> exportMultiBoardChart(
      @RequestParam String chartKey,
      @RequestParam(required = false) Long projectId,
      @RequestParam(required = false) String testingPhase) {
    byte[] workbook = systemTestIssueMultiBoardService.exportChart(projectId, testingPhase, chartKey);
    String filename = systemTestIssueMultiBoardService.exportFilename(projectId, testingPhase, chartKey);
    return ResponseEntity.ok()
        .contentType(EXCEL_MEDIA_TYPE)
        .header(HttpHeaders.CONTENT_DISPOSITION, DownloadResponseHeaders.attachment(filename))
        .body(workbook);
  }

  @GetMapping("/issues")
  @RequirePermission("system_test.issue.view")
  public ApiResponse<SystemTestIssueSearchListResponse> listIssues(
      @ModelAttribute SystemTestIssueSearchListWebRequest request) {
    return ApiResponse.success(
        systemTestIssueSearchService.listRecords(
            questionMetricsRequestAssembler.toIssueSearchQueryRequest(request)));
  }

  @GetMapping("/issues/export")
  @RequirePermission("system_test.issue.export")
  public ResponseEntity<byte[]> exportIssues(
      @ModelAttribute SystemTestIssueSearchListWebRequest request) {
    byte[] workbook =
        systemTestIssueSearchService.exportRecordsWorkbook(
            questionMetricsRequestAssembler.toIssueSearchQueryRequest(request));
    return ResponseEntity.ok()
        .contentType(EXCEL_MEDIA_TYPE)
        .header(HttpHeaders.CONTENT_DISPOSITION, DownloadResponseHeaders.attachment("多元议题查询结果.xlsx"))
        .body(workbook);
  }

  @GetMapping("/issues/filter-options")
  @RequirePermission("system_test.issue.view")
  public ApiResponse<SystemTestIssueSearchFilterOptionsResponse> getIssueFilterOptions(
      @ModelAttribute SystemTestIssueSearchListWebRequest request) {
    IssueFactRecordListRequest listRequest =
        questionMetricsRequestAssembler.toIssueSearchQueryRequest(request).listRequest();
    return ApiResponse.success(
        systemTestIssueSearchService.getFilterOptions(listRequest.projectId(), listRequest.sourceInstance()));
  }

  @GetMapping("/issues/status")
  @RequirePermission("system_test.issue.view")
  public ApiResponse<RealtimeWorkspaceStatusResponse> getIssueRealtimeStatus(
      @RequestParam Map<String, String> filters) {
    return ApiResponse.success(realtimeRefreshService.getStatus(ISSUE_SEARCH_WORKSPACE_KEY, filters));
  }

  @PostMapping("/issues/refresh")
  @RequirePermission(PlatformPermissionCodes.BUSINESS_DATA_REFRESH)
  public ApiResponse<RealtimeWorkspaceStatusResponse> refreshIssues() {
    return ApiResponse.success(
        "已开始刷新最新数据", realtimeRefreshService.requestRefresh(ISSUE_SEARCH_WORKSPACE_KEY));
  }

  @GetMapping("/illegal-records")
  @RequirePermission("system_test.illegal.view")
  public ApiResponse<SystemTestIllegalRecordListResponse> listIllegalRecords(
      @ModelAttribute SystemTestIllegalRecordListWebRequest request) {
    return ApiResponse.success(
        systemTestIllegalRecordService.listRecords(
            questionMetricsRequestAssembler.toIllegalRecordQueryRequest(request)));
  }

  @GetMapping("/illegal-records/export")
  @RequirePermission("system_test.illegal.export")
  public ResponseEntity<byte[]> exportIllegalRecords(
      @ModelAttribute SystemTestIllegalRecordListWebRequest request) {
    byte[] workbook =
        systemTestIllegalRecordService.exportRecordsWorkbook(
            questionMetricsRequestAssembler.toIllegalRecordQueryRequest(request));
    return ResponseEntity.ok()
        .contentType(EXCEL_MEDIA_TYPE)
        .header(HttpHeaders.CONTENT_DISPOSITION, DownloadResponseHeaders.attachment("多元议题查询结果.xlsx"))
        .body(workbook);
  }

  @GetMapping("/illegal-records/filter-options")
  @RequirePermission("system_test.illegal.view")
  public ApiResponse<SystemTestIllegalRecordFilterOptionsResponse> getIllegalRecordFilterOptions(
      @RequestParam(required = false) Long projectId) {
    return ApiResponse.success(systemTestIllegalRecordService.getFilterOptions(projectId));
  }

  @GetMapping("/illegal-records/rule-explanation")
  @RequirePermission("system_test.illegal.view")
  public ApiResponse<StatisticBoardRuleExplanationResponse> getIllegalRecordRuleExplanation(
      @RequestParam(required = false) Long projectId) {
    return ApiResponse.success(systemTestIllegalRecordService.getRuleExplanation(projectId));
  }

  @GetMapping("/illegal-records/status")
  @RequirePermission("system_test.illegal.view")
  public ApiResponse<RealtimeWorkspaceStatusResponse> getIllegalRecordRealtimeStatus(
      @RequestParam Map<String, String> filters) {
    return ApiResponse.success(realtimeRefreshService.getStatus(ILLEGAL_RECORDS_WORKSPACE_KEY, filters));
  }

  @PostMapping("/illegal-records/refresh")
  @RequirePermission(PlatformPermissionCodes.BUSINESS_DATA_REFRESH)
  public ApiResponse<RealtimeWorkspaceStatusResponse> refreshIllegalRecords() {
    return ApiResponse.success(
        "已开始刷新最新数据", realtimeRefreshService.requestRefresh(ILLEGAL_RECORDS_WORKSPACE_KEY));
  }

  @PostMapping("/illegal-records/refresh-one")
  @RequirePermission(PlatformPermissionCodes.BUSINESS_DATA_REFRESH)
  public ApiResponse<SystemTestIllegalRecordRowResponse> refreshOneIllegalRecord(
      @RequestBody SystemTestIllegalRecordSingleRefreshWebRequest request) {
    return ApiResponse.success(
        "已刷新本条议题事实数据",
        systemTestIllegalRecordService.refreshSingleRecord(
            request.getSource(), request.getProjectId(), request.getIssueIid()));
  }
}
