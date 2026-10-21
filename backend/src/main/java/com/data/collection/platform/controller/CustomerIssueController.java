package com.data.collection.platform.controller;

import com.data.collection.platform.common.DownloadResponseHeaders;
import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.CustomerIssueIllegalRecordFilterOptionsResponse;
import com.data.collection.platform.entity.CustomerIssueIllegalRecordListResponse;
import com.data.collection.platform.entity.CustomerIssueIllegalRecordRowResponse;
import com.data.collection.platform.entity.CustomerIssueRecordFilterOptionsResponse;
import com.data.collection.platform.entity.CustomerIssueRecordListResponse;
import com.data.collection.platform.entity.RealtimeWorkspaceStatusResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.security.PlatformPermissionCodes;
import com.data.collection.platform.security.RequirePermission;
import com.data.collection.platform.service.CustomerIssueIllegalRecordService;
import com.data.collection.platform.service.CustomerIssueRecordService;
import com.data.collection.platform.service.IssueFactRealtimeRefreshService;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customer-issues")
// 客户问题控制器复用 issue_fact 记录查询能力，只暴露客户问题域的正式记录和非法记录接口。
// 系统测试共用逻辑留在底层服务，避免两个控制器复制筛选和导出规则。
public class CustomerIssueController {
  private static final String TOPIC_DELAY = "delay";
  private static final String TOPIC_CC_PRODUCT = "cc-product";
  private static final String ILLEGAL_RECORDS_WORKSPACE_KEY = "customer-issue-illegal-records";

  private final CustomerIssueIllegalRecordService customerIssueIllegalRecordService;
  private final CustomerIssueRecordService customerIssueRecordService;
  private final CustomerIssueRequestAssembler customerIssueRequestAssembler;
  private final IssueFactRealtimeRefreshService realtimeRefreshService;

  private final com.data.collection.platform.service.read.ReadViewApplicationService pageReads;

  public CustomerIssueController(
      CustomerIssueIllegalRecordService customerIssueIllegalRecordService,
      CustomerIssueRecordService customerIssueRecordService,
      CustomerIssueRequestAssembler customerIssueRequestAssembler,
      IssueFactRealtimeRefreshService realtimeRefreshService,
      com.data.collection.platform.service.read.ReadViewApplicationService pageReads) {
    this.pageReads = pageReads;
    this.customerIssueIllegalRecordService = customerIssueIllegalRecordService;
    this.customerIssueRecordService = customerIssueRecordService;
    this.customerIssueRequestAssembler = customerIssueRequestAssembler;
    this.realtimeRefreshService = realtimeRefreshService;
  }

  @GetMapping("/records")
  @RequirePermission("customer_issue.record.view")
  public ApiResponse<CustomerIssueRecordListResponse> listRecords(
      @ModelAttribute CustomerIssueRecordListWebRequest request, jakarta.servlet.http.HttpServletRequest httpRequest) {
    return pageReads.readFacts(httpRequest, "customer-issues-cc-product-issues", pageReads.parameters(httpRequest),
        selection -> customerIssueRecordService.forRead(selection).listRecords(
            customerIssueRequestAssembler.toRecordQueryRequest(request))).response();
  }

  @GetMapping("/records/export")
  @RequirePermission("customer_issue.record.export")
  public ResponseEntity<byte[]> exportRecords(
      @ModelAttribute CustomerIssueRecordListWebRequest request, jakarta.servlet.http.HttpServletRequest httpRequest) {
    var exported = pageReads.readFacts(httpRequest, "customer-issues-cc-product-issues", pageReads.parameters(httpRequest),
        selection -> customerIssueRecordService.forRead(selection).exportRecordsWorkbook(
            customerIssueRequestAssembler.toRecordQueryRequest(request)));
    byte[] workbook = exported.data();
    return ResponseEntity.ok()
        .header("X-Read-Context-Id", exported.metadata().readContextId().toString())
        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header(HttpHeaders.CONTENT_DISPOSITION, DownloadResponseHeaders.attachment(recordExportFilename(request.getTopic())))
        .body(workbook);
  }

  /** 记录自然身份在路径中，与页面范围分开；只在该页面已冻结的事实集合内查找。 */
  @GetMapping("/records/{sourceInstance}/{projectId}/{issueId}")
  @RequirePermission("customer_issue.record.view")
  public ApiResponse<com.data.collection.platform.entity.CustomerIssueRecordRowResponse> getRecord(
      @PathVariable String sourceInstance, @PathVariable long projectId, @PathVariable long issueId,
      jakarta.servlet.http.HttpServletRequest request) {
    return pageReads.readFacts(request, "customer-issues-cc-product-issues", pageReads.parameters(request),
        selection -> customerIssueRecordService.forRead(selection).findRecord(
            com.data.collection.platform.service.GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance), projectId, issueId)).response();
  }

  @GetMapping("/records/filter-options")
  @RequirePermission("customer_issue.record.view")
  public ApiResponse<CustomerIssueRecordFilterOptionsResponse> getRecordFilterOptions(
      @RequestParam(required = false) String topic,
      @RequestParam(required = false) Long projectId, jakarta.servlet.http.HttpServletRequest httpRequest) {
    return pageReads.readFacts(httpRequest, "customer-issues-cc-product-issues", pageReads.parameters(httpRequest),
        selection -> customerIssueRecordService.forRead(selection).getFilterOptions(topic, projectId)).response();
  }

  @GetMapping("/records/rule-explanation")
  @RequirePermission("customer_issue.record.view")
  public ApiResponse<StatisticBoardRuleExplanationResponse> getRecordRuleExplanation(
      @RequestParam(required = false) String topic,
      @RequestParam(required = false) Long projectId, jakarta.servlet.http.HttpServletRequest httpRequest) {
    return pageReads.readFacts(httpRequest, "customer-issues-cc-product-issues", pageReads.parameters(httpRequest),
        selection -> customerIssueRecordService.forRead(selection).getRuleExplanation(topic, projectId)).response();
  }

  @GetMapping("/records/status")
  @RequirePermission("customer_issue.record.view")
  public ApiResponse<RealtimeWorkspaceStatusResponse> getRecordRealtimeStatus(
      @RequestParam(required = false) String topic,
      @RequestParam Map<String, String> filters) {
    return ApiResponse.success(realtimeRefreshService.getStatus(recordWorkspaceKey(topic), filters));
  }

  @PostMapping("/records/refresh")
  @RequirePermission(PlatformPermissionCodes.BUSINESS_DATA_REFRESH)
  public ApiResponse<RealtimeWorkspaceStatusResponse> refreshRecords(
      @RequestParam(required = false) String topic) {
    return ApiResponse.success(
        "已开始刷新最新数据", realtimeRefreshService.requestRefresh(recordWorkspaceKey(topic)));
  }

  @GetMapping("/illegal-records")
  @RequirePermission("customer_issue.illegal.view")
  public ApiResponse<CustomerIssueIllegalRecordListResponse> listIllegalRecords(
      @ModelAttribute CustomerIssueIllegalRecordListWebRequest request, jakarta.servlet.http.HttpServletRequest httpRequest) {
    return pageReads.readFacts(httpRequest, "customer-issues-illegal-records", pageReads.parameters(httpRequest),
        selection -> customerIssueIllegalRecordService.forRead(selection).listRecords(
            customerIssueRequestAssembler.toIllegalRecordQueryRequest(request))).response();
  }

  @GetMapping("/illegal-records/export")
  @RequirePermission("customer_issue.illegal.export")
  public ResponseEntity<byte[]> exportIllegalRecords(
      @ModelAttribute CustomerIssueIllegalRecordListWebRequest request, jakarta.servlet.http.HttpServletRequest httpRequest) {
    var exported = pageReads.readFacts(httpRequest, "customer-issues-illegal-records", pageReads.parameters(httpRequest),
        selection -> customerIssueIllegalRecordService.forRead(selection).exportRecordsWorkbook(
            customerIssueRequestAssembler.toIllegalRecordQueryRequest(request)));
    byte[] workbook = exported.data();
    return ResponseEntity.ok()
        .header("X-Read-Context-Id", exported.metadata().readContextId().toString())
        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header(HttpHeaders.CONTENT_DISPOSITION, DownloadResponseHeaders.attachment("多元议题查询结果.xlsx"))
        .body(workbook);
  }

  /** 下钻自然身份与页面范围分离，使用该非法记录页持有的完整事实组合。 */
  @GetMapping("/illegal-records/{sourceInstance}/{projectId}/{issueId}")
  @RequirePermission("customer_issue.illegal.view")
  public ApiResponse<CustomerIssueIllegalRecordRowResponse> getIllegalRecord(
      @PathVariable String sourceInstance, @PathVariable long projectId, @PathVariable long issueId,
      jakarta.servlet.http.HttpServletRequest request) {
    return pageReads.readFacts(request, "customer-issues-illegal-records", pageReads.parameters(request),
        selection -> customerIssueIllegalRecordService.forRead(selection).findRecord(
            com.data.collection.platform.service.GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance), projectId, issueId)).response();
  }

  @GetMapping("/illegal-records/filter-options")
  @RequirePermission("customer_issue.illegal.view")
  public ApiResponse<CustomerIssueIllegalRecordFilterOptionsResponse> getIllegalRecordFilterOptions(
      @RequestParam(required = false) Long projectId, jakarta.servlet.http.HttpServletRequest httpRequest) {
    return pageReads.readFacts(httpRequest, "customer-issues-illegal-records", pageReads.parameters(httpRequest),
        selection -> customerIssueIllegalRecordService.forRead(selection).getFilterOptions(projectId)).response();
  }

  @GetMapping("/illegal-records/rule-explanation")
  @RequirePermission("customer_issue.illegal.view")
  public ApiResponse<StatisticBoardRuleExplanationResponse> getIllegalRecordRuleExplanation(
      @RequestParam(required = false) Long projectId, jakarta.servlet.http.HttpServletRequest httpRequest) {
    return pageReads.readFacts(httpRequest, "customer-issues-illegal-records", pageReads.parameters(httpRequest),
        selection -> customerIssueIllegalRecordService.forRead(selection).getRuleExplanation(projectId)).response();
  }

  @GetMapping("/illegal-records/status")
  @RequirePermission("customer_issue.illegal.view")
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
  public ApiResponse<CustomerIssueIllegalRecordRowResponse> refreshOneIllegalRecord(
      @RequestBody CustomerIssueIllegalRecordSingleRefreshWebRequest request,
      jakarta.servlet.http.HttpServletRequest httpRequest) {
    customerIssueIllegalRecordService.refreshSingleRecord(request.getSource(), request.getProjectId(), request.getIssueIid());
    var outcome = pageReads.readFacts(httpRequest, "customer-issues-illegal-records", pageReads.parameters(httpRequest),
        selection -> customerIssueIllegalRecordService.forRead(selection).findRefreshedRecord(
            request.getSource(), request.getProjectId(), request.getIssueIid()));
    return ApiResponse.success("已刷新本条客户问题事实数据", outcome.data()).withReadView(outcome.metadata());
  }

  private String recordWorkspaceKey(String topic) {
    return TOPIC_DELAY.equalsIgnoreCase(topic) ? "customer-issue-delay-records" : "customer-issue-cc-product-records";
  }

  private String recordExportFilename(String topic) {
    return TOPIC_DELAY.equalsIgnoreCase(topic) ? "延期问题明细.xlsx" : "（全量）CCProduct议题查询结果.xlsx";
  }

}
