package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.CodeReviewIllegalRecordFilterOptionsResponse;
import com.data.collection.platform.entity.CodeReviewIllegalRecordListResponse;
import com.data.collection.platform.entity.CodeReviewMultiBoardOverviewResponse;
import com.data.collection.platform.entity.CodeReviewRulePreviewRequest;
import com.data.collection.platform.entity.CodeReviewRulePreviewResponse;
import com.data.collection.platform.entity.RealtimeWorkspaceStatusResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.service.CodeReviewIllegalRecordQueryRequest;
import com.data.collection.platform.service.CodeReviewIllegalRecordService;
import com.data.collection.platform.service.CodeReviewMultiBoardService;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/code-review")
public class CodeReviewController {

  private final CodeReviewIllegalRecordService codeReviewIllegalRecordService;
  private final CodeReviewMultiBoardService codeReviewMultiBoardService;

  public CodeReviewController(
      CodeReviewIllegalRecordService codeReviewIllegalRecordService,
      CodeReviewMultiBoardService codeReviewMultiBoardService) {
    this.codeReviewIllegalRecordService = codeReviewIllegalRecordService;
    this.codeReviewMultiBoardService = codeReviewMultiBoardService;
  }

  @GetMapping("/illegal-records")
  public ApiResponse<CodeReviewIllegalRecordListResponse> listIllegalRecords(
      @RequestParam(required = false) Long projectId,
      @RequestParam(required = false) String repositoryName,
      @RequestParam(required = false) String mergedAtStart,
      @RequestParam(required = false) String mergedAtEnd,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String projectName,
      @RequestParam(required = false) String requestType,
      @RequestParam(required = false) String targetBranch,
      @RequestParam(required = false) String mergedBy,
      @RequestParam(required = false) String moduleName,
      @RequestParam(required = false) String illegalType,
      @RequestParam(required = false) String mergeRequestIid,
      @RequestParam(required = false) String owner,
      @RequestParam(required = false) String filterGroup,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String sortBy,
      @RequestParam(required = false) String sortOrder,
      @RequestParam(required = false) String ruleConfig) {
    return ApiResponse.success(
        codeReviewIllegalRecordService.listRecords(
            new CodeReviewIllegalRecordQueryRequest(
                projectId,
                repositoryName,
                mergedAtStart,
                mergedAtEnd,
                keyword,
                projectName,
                requestType,
                targetBranch,
                mergedBy,
                moduleName,
                illegalType,
                mergeRequestIid,
                owner,
                filterGroup,
                page,
                size,
                sortBy,
                sortOrder,
                ruleConfig)));
  }

  @GetMapping("/illegal-records/filter-options")
  public ApiResponse<CodeReviewIllegalRecordFilterOptionsResponse> getIllegalRecordFilterOptions(
      @RequestParam(required = false) Long projectId) {
    return ApiResponse.success(codeReviewIllegalRecordService.getFilterOptions(projectId));
  }

  @GetMapping("/illegal-records/rule-explanation")
  public ApiResponse<StatisticBoardRuleExplanationResponse> getIllegalRecordRuleExplanation() {
    return ApiResponse.success(codeReviewIllegalRecordService.getRuleExplanation());
  }

  @PostMapping("/illegal-records/rule-config/preview")
  public ApiResponse<CodeReviewRulePreviewResponse> previewIllegalRecordRuleConfig(
      @RequestBody CodeReviewRulePreviewRequest request) {
    return ApiResponse.success(codeReviewIllegalRecordService.previewRuleConfig(request));
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
      @RequestParam(required = false) String source) {
    return ApiResponse.success(codeReviewMultiBoardService.getOverview(source));
  }
}
