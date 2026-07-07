package com.data.collection.platform.controller;

import com.data.collection.platform.common.DownloadResponseHeaders;
import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.AuthRole;
import com.data.collection.platform.entity.CodeReviewIllegalRecordFilterOptionsResponse;
import com.data.collection.platform.entity.CodeReviewIllegalRecordListResponse;
import com.data.collection.platform.entity.CodeReviewIllegalRecordRowResponse;
import com.data.collection.platform.entity.CodeReviewMatchModeStatusResponse;
import com.data.collection.platform.entity.CodeReviewMultiBoardOverviewResponse;
import com.data.collection.platform.entity.CodeReviewRulePreviewResponse;
import com.data.collection.platform.entity.RealtimeWorkspaceStatusResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.security.RequireRole;
import com.data.collection.platform.service.CodeReviewIllegalRecordService;
import com.data.collection.platform.service.CodeReviewMatchModeConfigService;
import com.data.collection.platform.service.CodeReviewMultiBoardService;
import com.data.collection.platform.service.MergeRequestFactRealtimeRefreshService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

  public CodeReviewController(
      CodeReviewIllegalRecordService codeReviewIllegalRecordService,
      CodeReviewMatchModeConfigService codeReviewMatchModeConfigService,
      CodeReviewMultiBoardService codeReviewMultiBoardService,
      CodeReviewRequestAssembler codeReviewRequestAssembler,
      MergeRequestFactRealtimeRefreshService multiBoardRealtimeRefreshService) {
    this.codeReviewIllegalRecordService = codeReviewIllegalRecordService;
    this.codeReviewMatchModeConfigService = codeReviewMatchModeConfigService;
    this.codeReviewMultiBoardService = codeReviewMultiBoardService;
    this.codeReviewRequestAssembler = codeReviewRequestAssembler;
    this.multiBoardRealtimeRefreshService = multiBoardRealtimeRefreshService;
  }

  @GetMapping("/match-mode/status")
  public ApiResponse<CodeReviewMatchModeStatusResponse> getMatchModeStatus() {
    var settings = codeReviewMatchModeConfigService.getResponse();
    return ApiResponse.success(new CodeReviewMatchModeStatusResponse(
        settings.enabled(),
        settings.reviewDataReadMode(),
        settings.codeReviewReadMode(),
        settings.enabled() && "compatibility".equals(settings.reviewDataReadMode()),
        settings.enabled() && "compatibility".equals(settings.codeReviewReadMode())));
  }

  @GetMapping("/illegal-records")
  public ApiResponse<CodeReviewIllegalRecordListResponse> listIllegalRecords(
      @ModelAttribute CodeReviewIllegalRecordListWebRequest request) {
    return ApiResponse.success(
        codeReviewIllegalRecordService.listRecords(
            codeReviewRequestAssembler.toIllegalRecordQueryRequest(request)));
  }

  @GetMapping("/illegal-records/export")
  public ResponseEntity<byte[]> exportIllegalRecords(
      @ModelAttribute CodeReviewIllegalRecordListWebRequest request) {
    byte[] workbook =
        codeReviewIllegalRecordService.exportRecordsWorkbook(
            codeReviewRequestAssembler.toIllegalRecordQueryRequest(request));
    return ResponseEntity.ok()
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header(HttpHeaders.CONTENT_DISPOSITION, DownloadResponseHeaders.attachment(codeReviewIllegalExportFilename(request)))
        .body(workbook);
  }

  private String codeReviewIllegalExportFilename(CodeReviewIllegalRecordListWebRequest request) {
    if ("dgm".equalsIgnoreCase(request.getSource())) {
      return "内核代码走查非法数据.xlsx";
    }
    return "代码走查非法数据.xlsx";
  }

  @GetMapping("/illegal-records/filter-options")
  public ApiResponse<CodeReviewIllegalRecordFilterOptionsResponse> getIllegalRecordFilterOptions(
      @ModelAttribute CodeReviewIllegalRecordFilterOptionsWebRequest request) {
    return ApiResponse.success(
        codeReviewIllegalRecordService.getFilterOptions(
            codeReviewRequestAssembler.toIllegalRecordFilterOptionsRequest(request)));
  }

  @GetMapping("/illegal-records/rule-explanation")
  public ApiResponse<StatisticBoardRuleExplanationResponse> getIllegalRecordRuleExplanation() {
    return ApiResponse.success(codeReviewIllegalRecordService.getRuleExplanation());
  }

  @PostMapping("/illegal-records/rule-config/preview")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<CodeReviewRulePreviewResponse> previewIllegalRecordRuleConfig(
      @RequestBody CodeReviewRulePreviewWebRequest request) {
    return ApiResponse.success(
        codeReviewIllegalRecordService.previewRuleConfig(
            codeReviewRequestAssembler.toRulePreviewRequest(request)));
  }

  @GetMapping("/illegal-records/status")
  public ApiResponse<RealtimeWorkspaceStatusResponse> getIllegalRecordRealtimeStatus(
      @RequestParam(required = false) String source) {
    return ApiResponse.success(codeReviewIllegalRecordService.getRealtimeStatus(source));
  }

  @PostMapping("/illegal-records/refresh")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<RealtimeWorkspaceStatusResponse> refreshIllegalRecords() {
    return ApiResponse.success("已开始刷新最新数据", codeReviewIllegalRecordService.requestRealtimeRefresh());
  }

  @PostMapping("/illegal-records/refresh-one")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<CodeReviewIllegalRecordRowResponse> refreshOneIllegalRecord(
      @RequestBody CodeReviewSingleRecordRefreshWebRequest request) {
    return ApiResponse.success(
        "已刷新本条合并请求数据",
        codeReviewIllegalRecordService.refreshSingleRecord(
            request.getSource(), request.getProjectId(), request.getMergeRequestIid()));
  }

  @GetMapping("/multi-board/source-options")
  public ApiResponse<java.util.List<OptionItemResponse>> getMultiBoardSourceOptions() {
    return ApiResponse.success(codeReviewMultiBoardService.listSourceOptions());
  }

  @GetMapping("/multi-board/overview")
  public ApiResponse<CodeReviewMultiBoardOverviewResponse> getMultiBoardOverview(
      @ModelAttribute CodeReviewMultiBoardOverviewWebRequest request) {
    return ApiResponse.success(
        codeReviewMultiBoardService.getOverview(
            codeReviewRequestAssembler.toMultiBoardOverviewRequest(request)));
  }

  @GetMapping("/multi-board/status")
  public ApiResponse<RealtimeWorkspaceStatusResponse> getMultiBoardRealtimeStatus() {
    return ApiResponse.success(multiBoardRealtimeRefreshService.getStatus(MULTI_BOARD_WORKSPACE_KEY));
  }

  @PostMapping("/multi-board/refresh")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<RealtimeWorkspaceStatusResponse> refreshMultiBoard() {
    return ApiResponse.success(
        "已开始刷新最新数据",
        multiBoardRealtimeRefreshService.requestRefresh(MULTI_BOARD_WORKSPACE_KEY));
  }
}
