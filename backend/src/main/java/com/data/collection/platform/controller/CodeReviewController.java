package com.data.collection.platform.controller;

import com.data.collection.platform.common.DownloadResponseHeaders;
import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.CodeReviewIllegalRecordFilterOptionsResponse;
import com.data.collection.platform.entity.CodeReviewIllegalRecordListResponse;
import com.data.collection.platform.entity.CodeReviewIllegalRecordRowResponse;
import com.data.collection.platform.entity.CodeReviewMatchModeStatusResponse;
import com.data.collection.platform.entity.CodeReviewMultiBoardOverviewResponse;
import com.data.collection.platform.entity.CodeReviewRulePreviewResponse;
import com.data.collection.platform.entity.RealtimeWorkspaceStatusResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.security.PlatformPermissionCodes;
import com.data.collection.platform.security.RequirePermission;
import com.data.collection.platform.service.CodeReviewIllegalRecordService;
import com.data.collection.platform.service.CodeReviewMatchModeConfigService;
import com.data.collection.platform.service.CodeReviewMultiBoardService;
import com.data.collection.platform.service.MergeRequestFactRealtimeRefreshService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/code-review")
// 代码走查控制器同时承接非法记录、规则配置和多元看板入口。
// 请求对象先经 assembler 收口，再交给服务层处理规则、筛选和导出。
public class CodeReviewController {
  private static final String MULTI_BOARD_WORKSPACE_KEY = "code-review-multi-board";

  private final CodeReviewIllegalRecordService codeReviewIllegalRecordService;
  private final CodeReviewMatchModeConfigService codeReviewMatchModeConfigService;
  private final CodeReviewMultiBoardService codeReviewMultiBoardService;
  private final CodeReviewRequestAssembler codeReviewRequestAssembler;
  private final MergeRequestFactRealtimeRefreshService multiBoardRealtimeRefreshService;

  private final com.data.collection.platform.service.read.ReadViewApplicationService pageReads;

  public CodeReviewController(
      CodeReviewIllegalRecordService codeReviewIllegalRecordService,
      CodeReviewMatchModeConfigService codeReviewMatchModeConfigService,
      CodeReviewMultiBoardService codeReviewMultiBoardService,
      CodeReviewRequestAssembler codeReviewRequestAssembler,
      MergeRequestFactRealtimeRefreshService multiBoardRealtimeRefreshService,
      com.data.collection.platform.service.read.ReadViewApplicationService pageReads) {
    this.pageReads = pageReads;
    this.codeReviewIllegalRecordService = codeReviewIllegalRecordService;
    this.codeReviewMatchModeConfigService = codeReviewMatchModeConfigService;
    this.codeReviewMultiBoardService = codeReviewMultiBoardService;
    this.codeReviewRequestAssembler = codeReviewRequestAssembler;
    this.multiBoardRealtimeRefreshService = multiBoardRealtimeRefreshService;
  }

  @GetMapping("/match-mode/status")
  @RequirePermission("code_review.illegal.view")
  public ApiResponse<CodeReviewMatchModeStatusResponse> getMatchModeStatus() {
    var settings = codeReviewMatchModeConfigService.getResponse();
    return ApiResponse.success(new CodeReviewMatchModeStatusResponse(
        settings.enabled(),
        settings.codeReviewReadMode(),
        settings.enabled() && "compatibility".equals(settings.codeReviewReadMode())));
  }

  @GetMapping("/illegal-records")
  @RequirePermission("code_review.illegal.view")
  public ApiResponse<CodeReviewIllegalRecordListResponse> listIllegalRecords(
      @ModelAttribute CodeReviewIllegalRecordListWebRequest request, jakarta.servlet.http.HttpServletRequest httpRequest) {
    return pageReads.read(httpRequest, "code-review-illegal-records", pageReads.parameters(httpRequest),
        selection -> codeReviewIllegalRecordService.forRead(selection).listRecords(
            codeReviewRequestAssembler.toIllegalRecordQueryRequest(request))).response();
  }

  /** 页面候选和来源模式由本页完整版本提供，不借用多源看板权限或当前设置。 */
  @GetMapping("/illegal-records/context")
  @RequirePermission("code_review.illegal.view")
  public ApiResponse<IllegalRecordContext> illegalRecordContext(jakarta.servlet.http.HttpServletRequest request) {
    return pageReads.read(request, "code-review-illegal-records", pageReads.parameters(request),
        selection -> {
          boolean compatibility = codeReviewIllegalRecordService.forRead(selection).codeReviewCompatibilityReadEnabled();
          return new IllegalRecordContext(compatibility,
              java.util.List.of(new OptionItemResponse("CC", "cc"), new OptionItemResponse("DGM", "dgm")));
        }).response();
  }

  /** 固定版本下读取已打开记录，来源撤回时返回空值。 */
  @GetMapping("/illegal-records/{sourceInstance}/{projectId}/{mergeRequestId}")
  @RequirePermission("code_review.illegal.view")
  public ApiResponse<CodeReviewIllegalRecordRowResponse> illegalRecord(
      @PathVariable String sourceInstance, @PathVariable Long projectId,
      @PathVariable Long mergeRequestId, @RequestParam(required = false) String legacySourceId,
      jakarta.servlet.http.HttpServletRequest request) {
    return pageReads.read(request, "code-review-illegal-records", pageReads.parameters(request),
        selection -> codeReviewIllegalRecordService.forRead(selection)
            .findRecord(sourceInstance, projectId, mergeRequestId, legacySourceId)).response();
  }

  /** 已发布页面的来源选择信息。 */
  public record IllegalRecordContext(boolean compatibilityRead, java.util.List<OptionItemResponse> sources) {}

  @GetMapping("/illegal-records/export")
  @RequirePermission("code_review.illegal.export")
  public ResponseEntity<byte[]> exportIllegalRecords(
      @ModelAttribute CodeReviewIllegalRecordListWebRequest request, jakarta.servlet.http.HttpServletRequest httpRequest) {
    var exported = pageReads.read(httpRequest, "code-review-illegal-records", pageReads.parameters(httpRequest),
        selection -> codeReviewIllegalRecordService.forRead(selection).exportRecordsWorkbook(
            codeReviewRequestAssembler.toIllegalRecordQueryRequest(request)));
    byte[] workbook = exported.data();
    return exported.download(ResponseEntity.ok()
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header(HttpHeaders.CONTENT_DISPOSITION, DownloadResponseHeaders.attachment(codeReviewIllegalExportFilename(request)))
        .body(workbook));
  }

  private String codeReviewIllegalExportFilename(CodeReviewIllegalRecordListWebRequest request) {
    if ("dgm".equalsIgnoreCase(request.getSource())) {
      return "内核代码走查非法数据.xlsx";
    }
    return "代码走查非法数据.xlsx";
  }

  @GetMapping("/illegal-records/filter-options")
  @RequirePermission("code_review.illegal.view")
  public ApiResponse<CodeReviewIllegalRecordFilterOptionsResponse> getIllegalRecordFilterOptions(
      @ModelAttribute CodeReviewIllegalRecordFilterOptionsWebRequest request, jakarta.servlet.http.HttpServletRequest httpRequest) {
    return pageReads.read(httpRequest, "code-review-illegal-records", pageReads.parameters(httpRequest),
        selection -> codeReviewIllegalRecordService.forRead(selection).getFilterOptions(
            codeReviewRequestAssembler.toIllegalRecordFilterOptionsRequest(request))).response();
  }

  @GetMapping("/illegal-records/rule-explanation")
  @RequirePermission("code_review.illegal.view")
  public ApiResponse<StatisticBoardRuleExplanationResponse> getIllegalRecordRuleExplanation(jakarta.servlet.http.HttpServletRequest httpRequest) {
    return pageReads.read(httpRequest, "code-review-illegal-records", pageReads.parameters(httpRequest),
        selection -> codeReviewIllegalRecordService.forRead(selection).getRuleExplanation()).response();
  }

  @PostMapping("/illegal-records/rule-config/preview")
  @RequirePermission("code_review.illegal.view")
  public ApiResponse<CodeReviewRulePreviewResponse> previewIllegalRecordRuleConfig(
      @RequestBody CodeReviewRulePreviewWebRequest request, jakarta.servlet.http.HttpServletRequest httpRequest) {
    java.util.Map<String, String> scope = new java.util.LinkedHashMap<>(pageReads.parameters(httpRequest));
    for (String key : java.util.List.of("source", "projectId", "repositoryName")) { scope.remove(key); }
    if (request.getSource() != null) { scope.put("source", request.getSource()); }
    if (request.getProjectId() != null) { scope.put("projectId", Long.toString(request.getProjectId())); }
    if (request.getRepositoryName() != null) { scope.put("repositoryName", request.getRepositoryName()); }
    return pageReads.read(httpRequest, "code-review-illegal-records", scope,
        selection -> codeReviewIllegalRecordService.forRead(selection).previewRuleConfig(
            codeReviewRequestAssembler.toRulePreviewRequest(request))).response();
  }

  @GetMapping("/illegal-records/status")
  @RequirePermission("code_review.illegal.view")
  public ApiResponse<RealtimeWorkspaceStatusResponse> getIllegalRecordRealtimeStatus(
      @RequestParam(required = false) String source) {
    return ApiResponse.success(codeReviewIllegalRecordService.getRealtimeStatus(source));
  }

  @PostMapping("/illegal-records/refresh")
  @RequirePermission(PlatformPermissionCodes.BUSINESS_DATA_REFRESH)
  public ApiResponse<RealtimeWorkspaceStatusResponse> refreshIllegalRecords() {
    return ApiResponse.success("已开始刷新最新数据", codeReviewIllegalRecordService.requestRealtimeRefresh());
  }

  @PostMapping("/illegal-records/refresh-one")
  @RequirePermission(PlatformPermissionCodes.BUSINESS_DATA_REFRESH)
  public ApiResponse<CodeReviewIllegalRecordRowResponse> refreshOneIllegalRecord(
      @RequestBody CodeReviewSingleRecordRefreshWebRequest request) {
    return ApiResponse.success(
        "已刷新本条合并请求数据",
        codeReviewIllegalRecordService.refreshSingleRecord(
            request.getSource(), request.getProjectId(), request.getMergeRequestIid()));
  }

  @GetMapping("/multi-board/source-options")
  @RequirePermission("code_review.board.view")
  public ApiResponse<java.util.List<OptionItemResponse>> getMultiBoardSourceOptions(jakarta.servlet.http.HttpServletRequest httpRequest) {
    return pageReads.read(httpRequest, "code-review-multi", pageReads.parameters(httpRequest),
        selection -> codeReviewMultiBoardService.forRead((com.data.collection.platform.entity.read.DatasetReadSelection) selection).listSourceOptions()).response();
  }

  @GetMapping("/multi-board/overview")
  @RequirePermission("code_review.board.view")
  public ApiResponse<CodeReviewMultiBoardOverviewResponse> getMultiBoardOverview(
      @ModelAttribute CodeReviewMultiBoardOverviewWebRequest request, jakarta.servlet.http.HttpServletRequest httpRequest) {
    return pageReads.read(httpRequest, "code-review-multi", pageReads.parameters(httpRequest),
        selection -> codeReviewMultiBoardService.forRead((com.data.collection.platform.entity.read.DatasetReadSelection) selection).getOverview(
            codeReviewRequestAssembler.toMultiBoardOverviewRequest(request))).response();
  }

  @GetMapping("/multi-board/project-options")
  @RequirePermission("code_review.board.view")
  public ApiResponse<java.util.List<OptionItemResponse>> getMultiBoardProjectOptions(
      @RequestParam(required = false) String source, jakarta.servlet.http.HttpServletRequest httpRequest) {
    return pageReads.read(httpRequest, "code-review-multi", pageReads.parameters(httpRequest),
        selection -> codeReviewMultiBoardService.forRead((com.data.collection.platform.entity.read.DatasetReadSelection) selection).listProjectOptions(source)).response();
  }

  @GetMapping("/multi-board/status")
  @RequirePermission("code_review.board.view")
  public ApiResponse<RealtimeWorkspaceStatusResponse> getMultiBoardRealtimeStatus() {
    return ApiResponse.success(multiBoardRealtimeRefreshService.getStatus(MULTI_BOARD_WORKSPACE_KEY));
  }

  @PostMapping("/multi-board/refresh")
  @RequirePermission(PlatformPermissionCodes.BUSINESS_DATA_REFRESH)
  public ApiResponse<RealtimeWorkspaceStatusResponse> refreshMultiBoard() {
    return ApiResponse.success(
        "已开始刷新最新数据",
        multiBoardRealtimeRefreshService.requestRefresh(MULTI_BOARD_WORKSPACE_KEY));
  }
}
