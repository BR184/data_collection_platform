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
import org.springframework.web.bind.annotation.PathVariable;

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

  private final com.data.collection.platform.service.read.ReadViewApplicationService pageReads;

  public QuestionMetricsController(
      SystemTestIssueSearchService systemTestIssueSearchService,
      SystemTestIllegalRecordService systemTestIllegalRecordService,
      SystemTestIssueMultiBoardService systemTestIssueMultiBoardService,
      QuestionMetricsRequestAssembler questionMetricsRequestAssembler,
      IssueFactRealtimeRefreshService realtimeRefreshService,
      com.data.collection.platform.service.read.ReadViewApplicationService pageReads) {
    this.pageReads = pageReads;
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
      @RequestParam(required = false) String testingPhase, jakarta.servlet.http.HttpServletRequest httpRequest) {
    return pageReads.readFacts(httpRequest, "system-test-multi-board", pageReads.parameters(httpRequest),
        selection -> systemTestIssueMultiBoardService.forRead(selection).getBoard(projectId, testingPhase)).response();
  }

  @GetMapping("/multi-board/export")
  @RequirePermission("system_test.board.export")
  public ResponseEntity<byte[]> exportMultiBoardChart(
      @RequestParam String chartKey,
      @RequestParam(required = false) Long projectId,
      @RequestParam(required = false) String testingPhase, jakarta.servlet.http.HttpServletRequest httpRequest) {
    var exported = pageReads.readFacts(httpRequest, "system-test-multi-board", pageReads.parameters(httpRequest),
        selection -> {
          var bound = systemTestIssueMultiBoardService.forRead(selection);
          return new WorkbookExport(bound.exportChart(projectId, testingPhase, chartKey),
              bound.exportFilename(projectId, testingPhase, chartKey));
        });
    byte[] workbook = exported.data().content();
    String filename = exported.data().filename();
    return ResponseEntity.ok()
        .header("X-Read-Context-Id", exported.metadata().readContextId().toString())
        .contentType(EXCEL_MEDIA_TYPE)
        .header(HttpHeaders.CONTENT_DISPOSITION, DownloadResponseHeaders.attachment(filename))
        .body(workbook);
  }

  private record WorkbookExport(byte[] content, String filename) {}

  @GetMapping("/issues")
  @RequirePermission("system_test.issue.view")
  public ApiResponse<SystemTestIssueSearchListResponse> listIssues(
      @ModelAttribute SystemTestIssueSearchListWebRequest request, jakarta.servlet.http.HttpServletRequest httpRequest) {
    return pageReads.readFacts(httpRequest, "system-test-issues", pageReads.parameters(httpRequest),
        selection -> systemTestIssueSearchService.forRead(selection).listRecords(
            questionMetricsRequestAssembler.toIssueSearchQueryRequest(request))).response();
  }

  @GetMapping("/issues/export")
  @RequirePermission("system_test.issue.export")
  public ResponseEntity<byte[]> exportIssues(
      @ModelAttribute SystemTestIssueSearchListWebRequest request, jakarta.servlet.http.HttpServletRequest httpRequest) {
    var exported = pageReads.readFacts(httpRequest, "system-test-issues", pageReads.parameters(httpRequest),
        selection -> systemTestIssueSearchService.forRead(selection).exportRecordsWorkbook(
            questionMetricsRequestAssembler.toIssueSearchQueryRequest(request)));
    byte[] workbook = exported.data();
    return ResponseEntity.ok()
        .header("X-Read-Context-Id", exported.metadata().readContextId().toString())
        .contentType(EXCEL_MEDIA_TYPE)
        .header(HttpHeaders.CONTENT_DISPOSITION, DownloadResponseHeaders.attachment("多元议题查询结果.xlsx"))
        .body(workbook);
  }

  @GetMapping("/issues/filter-options")
  @RequirePermission("system_test.issue.view")
  public ApiResponse<SystemTestIssueSearchFilterOptionsResponse> getIssueFilterOptions(
      @ModelAttribute SystemTestIssueSearchListWebRequest request, jakarta.servlet.http.HttpServletRequest httpRequest) {
    IssueFactRecordListRequest listRequest =
        questionMetricsRequestAssembler.toIssueSearchQueryRequest(request).listRequest();
    return pageReads.readFacts(httpRequest, "system-test-issues", pageReads.parameters(httpRequest),
        selection -> systemTestIssueSearchService.forRead(selection).getFilterOptions(listRequest.projectId(), listRequest.sourceInstance())).response();
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
      @ModelAttribute SystemTestIllegalRecordListWebRequest request, jakarta.servlet.http.HttpServletRequest httpRequest) {
    return pageReads.readFacts(httpRequest, "system-test-illegal-records", pageReads.parameters(httpRequest),
        selection -> systemTestIllegalRecordService.forRead(selection).listRecords(
            questionMetricsRequestAssembler.toIllegalRecordQueryRequest(request))).response();
  }

  @GetMapping("/illegal-records/export")
  @RequirePermission("system_test.illegal.export")
  public ResponseEntity<byte[]> exportIllegalRecords(
      @ModelAttribute SystemTestIllegalRecordListWebRequest request, jakarta.servlet.http.HttpServletRequest httpRequest) {
    var exported = pageReads.readFacts(httpRequest, "system-test-illegal-records", pageReads.parameters(httpRequest),
        selection -> systemTestIllegalRecordService.forRead(selection).exportRecordsWorkbook(
            questionMetricsRequestAssembler.toIllegalRecordQueryRequest(request)));
    byte[] workbook = exported.data();
    return ResponseEntity.ok()
        .header("X-Read-Context-Id", exported.metadata().readContextId().toString())
        .contentType(EXCEL_MEDIA_TYPE)
        .header(HttpHeaders.CONTENT_DISPOSITION, DownloadResponseHeaders.attachment("多元议题查询结果.xlsx"))
        .body(workbook);
  }

  /** 固定完整版本下的自然根查找，正常修复后的议题仍可继续查看详情。 */
  @GetMapping("/illegal-records/{sourceInstance}/{projectId}/{issueId}")
  @RequirePermission("system_test.illegal.view")
  public ApiResponse<SystemTestIllegalRecordRowResponse> getIllegalRecord(
      @PathVariable String sourceInstance, @PathVariable long projectId, @PathVariable long issueId,
      jakarta.servlet.http.HttpServletRequest request) {
    return pageReads.readFacts(request, "system-test-illegal-records", pageReads.parameters(request),
        selection -> systemTestIllegalRecordService.forRead(selection).findRecord(
            com.data.collection.platform.service.GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance), projectId, issueId)).response();
  }

  @GetMapping("/illegal-records/filter-options")
  @RequirePermission("system_test.illegal.view")
  public ApiResponse<SystemTestIllegalRecordFilterOptionsResponse> getIllegalRecordFilterOptions(
      @RequestParam(required = false) Long projectId, jakarta.servlet.http.HttpServletRequest httpRequest) {
    return pageReads.readFacts(httpRequest, "system-test-illegal-records", pageReads.parameters(httpRequest),
        selection -> systemTestIllegalRecordService.forRead(selection).getFilterOptions(projectId)).response();
  }

  @GetMapping("/illegal-records/rule-explanation")
  @RequirePermission("system_test.illegal.view")
  public ApiResponse<StatisticBoardRuleExplanationResponse> getIllegalRecordRuleExplanation(
      @RequestParam(required = false) Long projectId, jakarta.servlet.http.HttpServletRequest httpRequest) {
    return pageReads.readFacts(httpRequest, "system-test-illegal-records", pageReads.parameters(httpRequest),
        selection -> systemTestIllegalRecordService.forRead(selection).getRuleExplanation(projectId)).response();
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
      @RequestBody SystemTestIllegalRecordSingleRefreshWebRequest request,
      jakarta.servlet.http.HttpServletRequest httpRequest) {
    systemTestIllegalRecordService.refreshSingleRecord(request.getSource(), request.getProjectId(), request.getIssueIid());
    var outcome = pageReads.readFacts(httpRequest, "system-test-illegal-records", pageReads.parameters(httpRequest),
        selection -> systemTestIllegalRecordService.forRead(selection).findRefreshedRecord(
            request.getSource(), request.getProjectId(), request.getIssueIid()));
    return ApiResponse.success("已刷新本条议题事实数据", outcome.data()).withReadView(outcome.metadata());
  }
}
