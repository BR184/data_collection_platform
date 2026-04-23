package com.data.collection.platform.service;

import com.data.collection.platform.entity.ReviewDataFilterOptionsResponse;
import com.data.collection.platform.entity.ReviewDataProblemItemResponse;
import com.data.collection.platform.entity.ReviewDataProblemItemSaveRequest;
import com.data.collection.platform.entity.ReviewDataRecordDetailResponse;
import com.data.collection.platform.entity.ReviewDataRecordListResponse;
import com.data.collection.platform.entity.ReviewDataRecordSaveRequest;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ReviewDataRecordService {
  private final ReviewDataRecordQueryService queryService;
  private final ReviewDataRecordCommandService commandService;
  private final ReviewDataFilterOptionService filterOptionService;

  public ReviewDataRecordService(
      ReviewDataRecordQueryService queryService,
      ReviewDataRecordCommandService commandService,
      ReviewDataFilterOptionService filterOptionService) {
    this.queryService = queryService;
    this.commandService = commandService;
    this.filterOptionService = filterOptionService;
  }

  public ReviewDataRecordListResponse listRecords(
      String keyword,
      String title,
      String projectName,
      String moduleName,
      String reviewOwner,
      String reviewType,
      String problemStatus,
      String reviewExpert,
      String filterGroupJson,
      int page,
      int size,
      String sortField,
      String sortOrder) {
    return queryService.listRecords(
        keyword,
        title,
        projectName,
        moduleName,
        reviewOwner,
        reviewType,
        problemStatus,
        reviewExpert,
        filterGroupJson,
        page,
        size,
        sortField,
        sortOrder);
  }

  public ReviewDataFilterOptionsResponse getFilterOptions() {
    return filterOptionService.getFilterOptions();
  }

  public ReviewDataRecordDetailResponse getRecordDetail(Long recordId) {
    return queryService.getRecordDetail(recordId);
  }

  public List<ReviewDataProblemItemResponse> listProblemItems(Long recordId) {
    return queryService.listProblemItems(recordId);
  }

  public ReviewDataRecordDetailResponse createRecord(ReviewDataRecordSaveRequest request) {
    return commandService.createRecord(request);
  }

  public ReviewDataRecordDetailResponse updateRecord(Long recordId, ReviewDataRecordSaveRequest request) {
    return commandService.updateRecord(recordId, request);
  }

  public void deleteRecord(Long recordId) {
    commandService.deleteRecord(recordId);
  }

  public ReviewDataProblemItemResponse createProblemItem(
      Long recordId, ReviewDataProblemItemSaveRequest request) {
    return commandService.createProblemItem(recordId, request);
  }

  public ReviewDataProblemItemResponse updateProblemItem(
      Long recordId, Long itemId, ReviewDataProblemItemSaveRequest request) {
    return commandService.updateProblemItem(recordId, itemId, request);
  }

  public void deleteProblemItem(Long recordId, Long itemId) {
    commandService.deleteProblemItem(recordId, itemId);
  }
}
