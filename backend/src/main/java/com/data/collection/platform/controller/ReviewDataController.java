package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.AuthRole;
import com.data.collection.platform.entity.ReviewDataFilterOptionsResponse;
import com.data.collection.platform.entity.ReviewDataGitlabContextRefreshRequest;
import com.data.collection.platform.entity.ReviewDataGitlabContextRefreshResponse;
import com.data.collection.platform.entity.ReviewDataProblemItemResponse;
import com.data.collection.platform.entity.ReviewDataProblemItemSaveRequest;
import com.data.collection.platform.entity.ReviewDataRecordDetailResponse;
import com.data.collection.platform.entity.ReviewDataRecordListResponse;
import com.data.collection.platform.entity.ReviewDataRecordSaveRequest;
import com.data.collection.platform.entity.ReviewDataSearchIndexBackfillResponse;
import com.data.collection.platform.security.RequireRole;
import com.data.collection.platform.service.ReviewDataRecordService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/review-data")
// 评审数据控制器把 Web 查询参数组装成领域请求，记录、详情、问题项和导出都走同一服务入口。
// 这里不直接拼 SQL，也不处理搜索 fallback，保证页面请求边界清晰。
public class ReviewDataController {

  private final ReviewDataRecordService reviewDataRecordService;
  private final ReviewDataRequestAssembler reviewDataRequestAssembler;

  public ReviewDataController(
      ReviewDataRecordService reviewDataRecordService,
      ReviewDataRequestAssembler reviewDataRequestAssembler) {
    this.reviewDataRecordService = reviewDataRecordService;
    this.reviewDataRequestAssembler = reviewDataRequestAssembler;
  }

  @GetMapping("/records")
  public ApiResponse<ReviewDataRecordListResponse> listRecords(
      @ModelAttribute ReviewDataRecordListRequest request) {
    return ApiResponse.success(
        reviewDataRecordService.listRecords(reviewDataRequestAssembler.toQueryRequest(request)));
  }

  @GetMapping("/records/filter-options")
  public ApiResponse<ReviewDataFilterOptionsResponse> getFilterOptions() {
    return ApiResponse.success(reviewDataRecordService.getFilterOptions());
  }

  @GetMapping("/records/{recordId}")
  public ApiResponse<ReviewDataRecordDetailResponse> getRecordDetail(@PathVariable Long recordId) {
    return ApiResponse.success(reviewDataRecordService.getRecordDetail(recordId));
  }

  @PostMapping("/records")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<ReviewDataRecordDetailResponse> createRecord(
      @Valid @RequestBody ReviewDataRecordSaveRequest request) {
    return ApiResponse.success("新增评审成功", reviewDataRecordService.createRecord(request));
  }

  @PutMapping("/records/{recordId}")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<ReviewDataRecordDetailResponse> updateRecord(
      @PathVariable Long recordId, @Valid @RequestBody ReviewDataRecordSaveRequest request) {
    return ApiResponse.success("编辑评审成功", reviewDataRecordService.updateRecord(recordId, request));
  }

  @DeleteMapping("/records/{recordId}")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<Void> deleteRecord(@PathVariable Long recordId) {
    reviewDataRecordService.deleteRecord(recordId);
    return ApiResponse.success("删除评审成功", null);
  }

  @GetMapping("/records/{recordId}/problem-items")
  public ApiResponse<List<ReviewDataProblemItemResponse>> listProblemItems(@PathVariable Long recordId) {
    return ApiResponse.success(reviewDataRecordService.listProblemItems(recordId));
  }

  @PostMapping("/records/search-index/backfill")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<ReviewDataSearchIndexBackfillResponse> backfillSearchIndexes(
      @RequestParam(defaultValue = "200") int batchSize) {
    return ApiResponse.success(
        "评审数据搜索索引回填已执行",
        reviewDataRecordService.backfillMissingSearchIndexes(batchSize));
  }

  @PostMapping("/records/gitlab-context/refresh")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<ReviewDataGitlabContextRefreshResponse> refreshGitlabContext(
      @RequestBody(required = false) ReviewDataGitlabContextRefreshRequest request) {
    return ApiResponse.success(
        "GitLab 上下文同步请求已处理",
        reviewDataRecordService.refreshGitlabContext(
            request == null ? new ReviewDataGitlabContextRefreshRequest(List.of(), null) : request));
  }

  @GetMapping("/records/gitlab-context/refresh/{jobId}")
  public ApiResponse<ReviewDataGitlabContextRefreshResponse> getGitlabContextRefreshStatus(
      @PathVariable Long jobId) {
    return ApiResponse.success(reviewDataRecordService.getGitlabContextRefreshStatus(jobId));
  }

  @PostMapping("/records/{recordId}/problem-items")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<ReviewDataProblemItemResponse> createProblemItem(
      @PathVariable Long recordId, @Valid @RequestBody ReviewDataProblemItemSaveRequest request) {
    return ApiResponse.success("新增评审问题成功", reviewDataRecordService.createProblemItem(recordId, request));
  }

  @PutMapping("/records/{recordId}/problem-items/{itemId}")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<ReviewDataProblemItemResponse> updateProblemItem(
      @PathVariable Long recordId,
      @PathVariable Long itemId,
      @Valid @RequestBody ReviewDataProblemItemSaveRequest request) {
    return ApiResponse.success(
        "编辑评审问题成功",
        reviewDataRecordService.updateProblemItem(recordId, itemId, request));
  }

  @DeleteMapping("/records/{recordId}/problem-items/{itemId}")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<Void> deleteProblemItem(@PathVariable Long recordId, @PathVariable Long itemId) {
    reviewDataRecordService.deleteProblemItem(recordId, itemId);
    return ApiResponse.success("删除评审问题成功", null);
  }
}
