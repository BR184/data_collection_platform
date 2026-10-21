package com.data.collection.platform.service;

import com.data.collection.platform.entity.ReviewDataFilterOptionsResponse;
import com.data.collection.platform.entity.ReviewDataProblemItemResponse;
import com.data.collection.platform.entity.ReviewDataProblemItemSaveRequest;
import com.data.collection.platform.entity.ReviewDataRecordDetailResponse;
import com.data.collection.platform.entity.ReviewDataRecordListResponse;
import com.data.collection.platform.entity.ReviewDataRecordSaveRequest;
import com.data.collection.platform.entity.ReviewDataSearchIndexBackfillResponse;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ReviewDataRecordService {
  private static final String PAGE_KEY = "review-data-records";
  // 候选来源或查询口径变化时必须升级版本，使持久化页面快照自然失效，禁止靠人工清缓存生效。
  private static final String RULE_VERSION = "review-data-records@2026-09-18-v7";
  private static final String FILTER_OPTIONS_SCOPE_KEY = "all";

  private final ReviewDataRecordQueryService queryService;
  private final ReviewDataRecordCommandService commandService;
  private final ReviewDataFilterOptionService filterOptionService;
  private final ReviewDataRecordPersistenceSupport persistenceSupport;
  private final PageRecordSnapshotService pageRecordSnapshotService;

  private final com.data.collection.platform.entity.read.DatasetReadSelection fixedReadSelection;

  /** 创建一次读取持有的固定版本对象；工厂不保存页面身份。 */
  public ReviewDataRecordService forRead(com.data.collection.platform.entity.read.DatasetReadSelection selection) {
    return new ReviewDataRecordService(this, selection);
  }

  private ReviewDataRecordService(ReviewDataRecordService factory,
      com.data.collection.platform.entity.read.DatasetReadSelection selection) {
    this.queryService = factory.queryService.forRead(selection);
    this.commandService = factory.commandService;
    this.filterOptionService = factory.filterOptionService.forRead(selection);
    this.persistenceSupport = factory.persistenceSupport;
    this.pageRecordSnapshotService = factory.pageRecordSnapshotService;
    this.fixedReadSelection = java.util.Objects.requireNonNull(selection);
  }

  private com.data.collection.platform.entity.read.DatasetReadSelection readSelection() {
    return java.util.Objects.requireNonNull(fixedReadSelection, "页面数据读取必须持有固定版本身份");
  }

  @org.springframework.beans.factory.annotation.Autowired
  public ReviewDataRecordService(
      ReviewDataRecordQueryService queryService,
      ReviewDataRecordCommandService commandService,
      ReviewDataFilterOptionService filterOptionService,
      ReviewDataRecordPersistenceSupport persistenceSupport,
      PageRecordSnapshotService pageRecordSnapshotService) {
    this.fixedReadSelection = null;
    this.queryService = queryService;
    this.commandService = commandService;
    this.filterOptionService = filterOptionService;
    this.persistenceSupport = persistenceSupport;
    this.pageRecordSnapshotService = pageRecordSnapshotService;
  }

  public ReviewDataRecordListResponse listRecords(ReviewDataRecordQueryRequest request) {
    return pageRecordSnapshotService.readOrRefresh(readSelection(),
        snapshotRequest(PageRecordSnapshotService.SNAPSHOT_TYPE_LIST, "all", request),
        ReviewDataRecordListResponse.class,
        () -> queryService.listRecords(request));
  }

  public ReviewDataFilterOptionsResponse getFilterOptions() {
    return pageRecordSnapshotService.readOrRefresh(readSelection(),
        snapshotRequest(PageRecordSnapshotService.SNAPSHOT_TYPE_FILTER_OPTIONS, FILTER_OPTIONS_SCOPE_KEY, java.util.Map.of()),
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
    return createRecord(request, null);
  }

  public ReviewDataRecordDetailResponse createRecord(
      ReviewDataRecordSaveRequest request, String createdBy) {
    Long recordId = commandService.createRecord(request, createdBy);
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
    return createProblemItem(recordId, request, null);
  }

  public ReviewDataProblemItemResponse createProblemItem(
      Long recordId, ReviewDataProblemItemSaveRequest request, String createdBy) {
    Long itemId = commandService.createProblemItem(recordId, request, createdBy);
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
        () -> pageRecordSnapshotService.reviewDataSourceVersion(),
        requestPayload);
  }

  private void invalidateSnapshots() {
    // 完整业务提交由持久合并请求生成新版本；旧页面缓存继续属于旧读取身份。
  }

}
