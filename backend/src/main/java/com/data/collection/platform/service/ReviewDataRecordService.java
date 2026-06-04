package com.data.collection.platform.service;

import com.data.collection.platform.entity.ReviewDataFilterOptionsResponse;
import com.data.collection.platform.entity.ReviewDataProblemItemResponse;
import com.data.collection.platform.entity.ReviewDataProblemItemSaveRequest;
import com.data.collection.platform.entity.ReviewDataRecordDetailResponse;
import com.data.collection.platform.entity.ReviewDataRecordListResponse;
import com.data.collection.platform.entity.ReviewDataRecordSaveRequest;
import com.data.collection.platform.entity.ReviewDataSearchIndexBackfillResponse;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ReviewDataRecordService {
  private final ReviewDataRecordQueryService queryService;
  private final ReviewDataRecordCommandService commandService;
  private final ReviewDataFilterOptionService filterOptionService;
  private final ReviewDataRecordPersistenceSupport persistenceSupport;

  public ReviewDataRecordService(
      ReviewDataRecordQueryService queryService,
      ReviewDataRecordCommandService commandService,
      ReviewDataFilterOptionService filterOptionService,
      ReviewDataRecordPersistenceSupport persistenceSupport) {
    this.queryService = queryService;
    this.commandService = commandService;
    this.filterOptionService = filterOptionService;
    this.persistenceSupport = persistenceSupport;
  }

  public ReviewDataRecordListResponse listRecords(ReviewDataRecordQueryRequest request) {
    return queryService.listRecords(request);
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
    Long recordId = commandService.createRecord(request);
    return queryService.getRecordDetail(recordId);
  }

  public ReviewDataRecordDetailResponse updateRecord(Long recordId, ReviewDataRecordSaveRequest request) {
    Long updatedRecordId = commandService.updateRecord(recordId, request);
    return queryService.getRecordDetail(updatedRecordId);
  }

  public void deleteRecord(Long recordId) {
    commandService.deleteRecord(recordId);
  }

  public ReviewDataProblemItemResponse createProblemItem(
      Long recordId, ReviewDataProblemItemSaveRequest request) {
    Long itemId = commandService.createProblemItem(recordId, request);
    return queryService.getProblemItem(recordId, itemId);
  }

  public ReviewDataProblemItemResponse updateProblemItem(
      Long recordId, Long itemId, ReviewDataProblemItemSaveRequest request) {
    Long updatedItemId = commandService.updateProblemItem(recordId, itemId, request);
    return queryService.getProblemItem(recordId, updatedItemId);
  }

  public void deleteProblemItem(Long recordId, Long itemId) {
    commandService.deleteProblemItem(recordId, itemId);
  }

  public ReviewDataSearchIndexBackfillResponse backfillMissingSearchIndexes(int batchSize) {
    int safeBatchSize = batchSize <= 0 ? 200 : Math.min(batchSize, 2000);
    persistenceSupport.refreshMissingSearchIndexes(safeBatchSize);
    return new ReviewDataSearchIndexBackfillResponse(
        safeBatchSize,
        persistenceSupport.hasMissingSearchIndexes(),
        persistenceSupport.hasMissingTitleSearchIndexes());
  }
}
