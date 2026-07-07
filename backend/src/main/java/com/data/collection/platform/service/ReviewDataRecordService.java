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
  private static final String PAGE_KEY = "review-data-records";
  private static final String RULE_VERSION = "review-data-records@2026-07-07-v2";

  private final ReviewDataRecordQueryService queryService;
  private final ReviewDataRecordCommandService commandService;
  private final ReviewDataFilterOptionService filterOptionService;
  private final ReviewDataRecordPersistenceSupport persistenceSupport;
  private final PageRecordSnapshotService pageRecordSnapshotService;

  public ReviewDataRecordService(
      ReviewDataRecordQueryService queryService,
      ReviewDataRecordCommandService commandService,
      ReviewDataFilterOptionService filterOptionService,
      ReviewDataRecordPersistenceSupport persistenceSupport,
      PageRecordSnapshotService pageRecordSnapshotService) {
    this.queryService = queryService;
    this.commandService = commandService;
    this.filterOptionService = filterOptionService;
    this.persistenceSupport = persistenceSupport;
    this.pageRecordSnapshotService = pageRecordSnapshotService;
  }

  public ReviewDataRecordListResponse listRecords(ReviewDataRecordQueryRequest request) {
    return pageRecordSnapshotService.readOrRefresh(
        snapshotRequest(PageRecordSnapshotService.SNAPSHOT_TYPE_LIST, "all", request),
        ReviewDataRecordListResponse.class,
        () -> queryService.listRecords(request));
  }

  public ReviewDataFilterOptionsResponse getFilterOptions() {
    return pageRecordSnapshotService.readOrRefresh(
        snapshotRequest(PageRecordSnapshotService.SNAPSHOT_TYPE_FILTER_OPTIONS, "all", java.util.Map.of()),
        ReviewDataFilterOptionsResponse.class,
        filterOptionService::getFilterOptions);
  }

  public ReviewDataRecordDetailResponse getRecordDetail(Long recordId) {
    return queryService.getRecordDetail(recordId);
  }

  public List<ReviewDataProblemItemResponse> listProblemItems(Long recordId) {
    return queryService.listProblemItems(recordId);
  }

  public ReviewDataRecordDetailResponse createRecord(ReviewDataRecordSaveRequest request) {
    Long recordId = commandService.createRecord(request);
    invalidateSnapshots();
    return queryService.getRecordDetail(recordId);
  }

  public ReviewDataRecordDetailResponse updateRecord(Long recordId, ReviewDataRecordSaveRequest request) {
    Long updatedRecordId = commandService.updateRecord(recordId, request);
    invalidateSnapshots();
    return queryService.getRecordDetail(updatedRecordId);
  }

  public void deleteRecord(Long recordId) {
    commandService.deleteRecord(recordId);
    invalidateSnapshots();
  }

  public ReviewDataProblemItemResponse createProblemItem(
      Long recordId, ReviewDataProblemItemSaveRequest request) {
    Long itemId = commandService.createProblemItem(recordId, request);
    invalidateSnapshots();
    return queryService.getProblemItem(recordId, itemId);
  }

  public ReviewDataProblemItemResponse updateProblemItem(
      Long recordId, Long itemId, ReviewDataProblemItemSaveRequest request) {
    Long updatedItemId = commandService.updateProblemItem(recordId, itemId, request);
    invalidateSnapshots();
    return queryService.getProblemItem(recordId, updatedItemId);
  }

  public void deleteProblemItem(Long recordId, Long itemId) {
    commandService.deleteProblemItem(recordId, itemId);
    invalidateSnapshots();
  }

  public ReviewDataSearchIndexBackfillResponse backfillMissingSearchIndexes(int batchSize) {
    int safeBatchSize = batchSize <= 0 ? 200 : Math.min(batchSize, 2000);
    persistenceSupport.refreshMissingSearchIndexes(safeBatchSize);
    return new ReviewDataSearchIndexBackfillResponse(
        safeBatchSize,
        persistenceSupport.hasMissingSearchIndexes(),
        persistenceSupport.hasMissingTitleSearchIndexes());
  }

  private PageRecordSnapshotService.SnapshotRequest snapshotRequest(
      String snapshotType, String scopeKey, Object requestPayload) {
    return new PageRecordSnapshotService.SnapshotRequest(
        PAGE_KEY,
        snapshotType,
        scopeKey,
        RULE_VERSION,
        pageRecordSnapshotService.reviewDataSourceVersion(),
        requestPayload);
  }

  private void invalidateSnapshots() {
    pageRecordSnapshotService.invalidatePage(PAGE_KEY);
  }
}
