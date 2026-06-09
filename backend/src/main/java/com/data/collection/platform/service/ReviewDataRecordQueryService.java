package com.data.collection.platform.service;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.ReviewDataProblemItemResponse;
import com.data.collection.platform.entity.ReviewDataRecordDetailResponse;
import com.data.collection.platform.entity.ReviewDataRecordListResponse;
import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
// 评审数据查询服务在 SQL 下推和 Java fallback 之间做路由选择。
// 当搜索影子字段完整且高级筛选可下推时走 SQL，否则保留 Java 过滤以兼容历史数据。
public class ReviewDataRecordQueryService {
  private final ReviewDataRecordPersistenceSupport persistenceSupport;
  private final ReviewDataSummaryService summaryService;
  private final JsonUtils jsonUtils;

  public ReviewDataRecordQueryService(
      ReviewDataRecordPersistenceSupport persistenceSupport,
      ReviewDataSummaryService summaryService,
      JsonUtils jsonUtils) {
    this.persistenceSupport = persistenceSupport;
    this.summaryService = summaryService;
    this.jsonUtils = jsonUtils;
  }

  public ReviewDataRecordListResponse listRecords(ReviewDataRecordQueryRequest request) {
    int safePage = request.page() <= 0 ? 1 : request.page();
    int safeSize = request.size() <= 0 ? 20 : Math.min(request.size(), 100);
    String safeSortField = ReviewDataRecordSortSupport.normalizeSortField(request.sortField());
    String safeSortOrder = ReviewDataRecordSortSupport.normalizeSortOrder(request.sortOrder());

    StatisticFilterGroup filterGroup =
        ReviewDataRecordFilterGroupSupport.parse(jsonUtils, request.filterGroupJson());
    boolean hasFilterGroup =
        filterGroup != null && filterGroup.conditions() != null && !filterGroup.conditions().isEmpty();
    boolean keywordSearch = TextQuerySupport.trimToNull(request.keyword()) != null;
    boolean titleSearchFilter =
        hasFilterGroup && ReviewDataFilterGroupSqlSupport.needsTitleSearchIndex(filterGroup);
    boolean canUseSqlPath =
        (!keywordSearch || !persistenceSupport.hasMissingSearchIndexes())
            && (!titleSearchFilter || !persistenceSupport.hasMissingTitleSearchIndexes())
            && (!hasFilterGroup || ReviewDataFilterGroupSqlSupport.canPushDown(filterGroup));
    // SQL 路径是目标形态：分页、排序、关键词和高级筛选都尽量交给数据库完成。
    if (canUseSqlPath) {
      ReviewDataRecordReadRepository.RecordPageResult pageResult =
          persistenceSupport.loadRecordPage(
              request.title(),
              request.projectName(),
              request.moduleName(),
              request.reviewOwner(),
              request.reviewType(),
              request.problemStatus(),
              request.reviewExpert(),
              request.keyword(),
              hasFilterGroup ? filterGroup : null,
              safePage,
              safeSize,
              safeSortField,
              safeSortOrder);
      return new ReviewDataRecordListResponse(
          pageResult.records(),
          pageResult.total(),
          safePage,
          safeSize,
          safeSortField,
          safeSortOrder,
          pageResult.summary());
    }
    // fallback 只用于历史搜索索引缺失或筛选表达式暂未下推的场景，避免用户查询突然失效。
    return listRecordsWithJavaFilters(
        request, hasFilterGroup ? filterGroup : null, safePage, safeSize, safeSortField, safeSortOrder);
  }

  private ReviewDataRecordListResponse listRecordsWithJavaFilters(
      ReviewDataRecordQueryRequest request,
      StatisticFilterGroup filterGroup,
      int safePage,
      int safeSize,
      String safeSortField,
      String safeSortOrder) {
    List<ReviewDataRecordRowResponse> legacyFiltered =
        persistenceSupport.loadRecords(
            request.title(),
            request.projectName(),
            request.moduleName(),
            request.reviewOwner(),
            request.reviewType(),
            request.problemStatus(),
            request.reviewExpert(),
            null);
    List<ReviewDataRecordRowResponse> keywordFiltered =
        legacyFiltered.stream()
            .filter(row -> ReviewDataSearchSupport.matchesKeyword(row, request.keyword()))
            .toList();
    Map<Long, List<String>> problemStatusesByRecordId =
        filterGroup != null && ReviewDataRecordFilterGroupSupport.needsField(filterGroup, "problemStatus")
            ? persistenceSupport.loadProblemStatusesByRecordIds(keywordFiltered)
            : Map.of();
    List<ReviewDataRecordRowResponse> filtered =
        keywordFiltered.stream()
            .filter(
                row ->
                    filterGroup == null
                        || ReviewDataRecordFilterGroupSupport.matches(row, filterGroup, problemStatusesByRecordId))
            .sorted(ReviewDataRecordSortSupport.buildComparator(safeSortField, safeSortOrder))
            .toList();

    PageSlice<ReviewDataRecordRowResponse> pageSlice =
        PageSliceSupport.slice(filtered, safePage, safeSize);

    return new ReviewDataRecordListResponse(
        pageSlice.records(),
        pageSlice.total(),
        pageSlice.page(),
        pageSlice.size(),
        safeSortField,
        safeSortOrder,
        summaryService.buildSummary(filtered));
  }

  public ReviewDataRecordDetailResponse getRecordDetail(Long recordId) {
    ReviewDataRecordRowResponse record = persistenceSupport.getRecordOrThrow(recordId);
    return new ReviewDataRecordDetailResponse(
        record,
        persistenceSupport.listRecordExperts(recordId),
        persistenceSupport.listProblemItems(recordId));
  }

  public List<ReviewDataProblemItemResponse> listProblemItems(Long recordId) {
    persistenceSupport.assertRecordExists(recordId);
    return persistenceSupport.listProblemItems(recordId);
  }

  public ReviewDataProblemItemResponse getProblemItem(Long recordId, Long itemId) {
    persistenceSupport.assertRecordExists(recordId);
    return persistenceSupport.getProblemItemOrThrow(recordId, itemId);
  }
}
