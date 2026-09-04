package com.data.collection.platform.service;

import com.data.collection.platform.entity.ReviewDataFilterOptionsResponse;
import com.data.collection.platform.entity.ReviewDataProblemItemResponse;
import com.data.collection.platform.entity.ReviewDataProblemItemSaveRequest;
import com.data.collection.platform.entity.ReviewDataRecordDetailResponse;
import com.data.collection.platform.entity.ReviewDataRecordListResponse;
import com.data.collection.platform.entity.ReviewDataRecordSaveRequest;
import com.data.collection.platform.entity.ReviewDataSearchIndexBackfillResponse;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ReviewDataRecordService {
  private static final String PAGE_KEY = "review-data-records";
  // 候选来源或查询口径变化时必须升级版本，使持久化页面快照自然失效，禁止靠人工清缓存生效。
  private static final String RULE_VERSION = "review-data-records@2026-07-20-v6";
  private static final String FILTER_OPTIONS_SCOPE_KEY = "all";

  private final ExecutorService snapshotPrewarmExecutor =
      Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "review-snapshot-prewarm");
            thread.setDaemon(true);
            return thread;
          });

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
        pageRecordSnapshotService.reviewDataSourceVersion(),
        requestPayload);
  }

  private void invalidateSnapshots() {
    pageRecordSnapshotService.invalidatePage(PAGE_KEY);
    prewarmFilterOptionsSnapshot();
  }

  // 写后异步预热筛选候选快照：用户的下一次打开/刷新大概率直接命中缓存，而不是撞上冷重建。
  // 列表快照与筛选参数组合相关，写入侧无法预知下一个请求参数，交给快照层的 single-flight 合并并发重建。
  private void prewarmFilterOptionsSnapshot() {
    snapshotPrewarmExecutor.execute(() -> {
      try {
        pageRecordSnapshotService.readOrRefresh(
            snapshotRequest(
                PageRecordSnapshotService.SNAPSHOT_TYPE_FILTER_OPTIONS,
                FILTER_OPTIONS_SCOPE_KEY,
                java.util.Map.of()),
            ReviewDataFilterOptionsResponse.class,
            filterOptionService::getFilterOptions);
      } catch (RuntimeException error) {
        log.debug("Review filter options snapshot prewarm failed; next read will rebuild", error);
      }
    });
  }
}
