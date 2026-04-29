package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.CodeReviewIllegalRecordFilterOptionsResponse;
import com.data.collection.platform.entity.CodeReviewIllegalRecordListResponse;
import com.data.collection.platform.entity.CodeReviewMultiBoardOverviewResponse;
import com.data.collection.platform.entity.CodeReviewRulePreviewResponse;
import com.data.collection.platform.entity.RealtimeWorkspaceStatusResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.service.CodeReviewIllegalRecordService;
import com.data.collection.platform.service.CodeReviewMultiBoardService;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/code-review")
public class CodeReviewController {

  private final CodeReviewIllegalRecordService codeReviewIllegalRecordService;
  private final CodeReviewMultiBoardService codeReviewMultiBoardService;
  private final CodeReviewRequestAssembler codeReviewRequestAssembler;

  public CodeReviewController(
      CodeReviewIllegalRecordService codeReviewIllegalRecordService,
      CodeReviewMultiBoardService codeReviewMultiBoardService,
      CodeReviewRequestAssembler codeReviewRequestAssembler) {
    this.codeReviewIllegalRecordService = codeReviewIllegalRecordService;
    this.codeReviewMultiBoardService = codeReviewMultiBoardService;
    this.codeReviewRequestAssembler = codeReviewRequestAssembler;
  }

  @GetMapping("/illegal-records")
  public ApiResponse<CodeReviewIllegalRecordListResponse> listIllegalRecords(
      @ModelAttribute CodeReviewIllegalRecordListWebRequest request) {
    return ApiResponse.success(
        codeReviewIllegalRecordService.listRecords(
            codeReviewRequestAssembler.toIllegalRecordQueryRequest(request)));
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
  public ApiResponse<CodeReviewRulePreviewResponse> previewIllegalRecordRuleConfig(
      @RequestBody CodeReviewRulePreviewWebRequest request) {
    return ApiResponse.success(
        codeReviewIllegalRecordService.previewRuleConfig(
            codeReviewRequestAssembler.toRulePreviewRequest(request)));
  }

  @GetMapping("/illegal-records/status")
  public ApiResponse<RealtimeWorkspaceStatusResponse> getIllegalRecordRealtimeStatus() {
    return ApiResponse.success(codeReviewIllegalRecordService.getRealtimeStatus());
  }

  @PostMapping("/illegal-records/refresh")
  public ApiResponse<RealtimeWorkspaceStatusResponse> refreshIllegalRecords() {
    return ApiResponse.success("已开始刷新最新数据", codeReviewIllegalRecordService.requestRealtimeRefresh());
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
}
