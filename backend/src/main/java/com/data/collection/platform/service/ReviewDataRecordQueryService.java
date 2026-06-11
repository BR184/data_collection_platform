package com.data.collection.platform.service;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.ReviewDataProblemItemResponse;
import com.data.collection.platform.entity.ReviewDataRecordDetailResponse;
import com.data.collection.platform.entity.ReviewDataRecordListResponse;
import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupExpansionResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.service.labelgroup.LabelGroupExpansionService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
// 评审数据查询服务在 SQL 下推和 Java fallback 之间做路由选择。
// 当搜索影子字段完整且高级筛选可下推时走 SQL，否则保留 Java 过滤以兼容历史数据。
public class ReviewDataRecordQueryService {
  private static final String REVIEW_DATA_PAGE_KEY = "review-data-home";
  private static final int MAX_LABEL_GROUP_FILTER_VALUES = 200;
  private static final Map<String, String> LABEL_GROUP_FIELD_VALUE_TYPES =
      Map.of(
          "projectName", "STRING",
          "moduleName", "STRING",
          "reviewOwner", "STRING",
          "reviewExpert", "STRING");
  private final ReviewDataRecordPersistenceSupport persistenceSupport;
  private final ReviewDataSummaryService summaryService;
  private final JsonUtils jsonUtils;
  private final LabelGroupExpansionService labelGroupExpansionService;

  public ReviewDataRecordQueryService(
      ReviewDataRecordPersistenceSupport persistenceSupport,
      ReviewDataSummaryService summaryService,
      JsonUtils jsonUtils,
      LabelGroupExpansionService labelGroupExpansionService) {
    this.persistenceSupport = persistenceSupport;
    this.summaryService = summaryService;
    this.jsonUtils = jsonUtils;
    this.labelGroupExpansionService = labelGroupExpansionService;
  }

  public ReviewDataRecordListResponse listRecords(ReviewDataRecordQueryRequest request) {
    int safePage = request.page() <= 0 ? 1 : request.page();
    int safeSize = request.size() <= 0 ? 20 : Math.min(request.size(), 100);
    String safeSortField = ReviewDataRecordSortSupport.normalizeSortField(request.sortField());
    String safeSortOrder = ReviewDataRecordSortSupport.normalizeSortOrder(request.sortOrder());

    StatisticFilterGroup filterGroup =
        ReviewDataRecordFilterGroupSupport.parse(jsonUtils, request.filterGroupJson());
    StatisticFilterGroup expandedFilterGroup = expandLabelGroupConditions(filterGroup, request.sourceInstance());
    boolean hasFilterGroup =
        expandedFilterGroup != null
            && expandedFilterGroup.conditions() != null
            && !expandedFilterGroup.conditions().isEmpty();
    boolean hasLabelGroupFilters = ReviewDataRecordFilterGroupSupport.hasLabelGroupConditions(expandedFilterGroup);
    boolean keywordSearch = TextQuerySupport.trimToNull(request.keyword()) != null;
    boolean titleSearchFilter =
        hasFilterGroup && ReviewDataFilterGroupSqlSupport.needsTitleSearchIndex(expandedFilterGroup);
    boolean canUseSqlPath =
        !hasLabelGroupFilters
            && (!keywordSearch || !persistenceSupport.hasMissingSearchIndexes())
            && (!titleSearchFilter || !persistenceSupport.hasMissingTitleSearchIndexes())
            && (!hasFilterGroup || ReviewDataFilterGroupSqlSupport.canPushDown(expandedFilterGroup));
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
              hasFilterGroup ? expandedFilterGroup : null,
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
        request,
        hasFilterGroup ? expandedFilterGroup : null,
        safePage,
        safeSize,
        safeSortField,
        safeSortOrder);
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

  public List<String> describeExpandedLabelGroupFilters(ReviewDataRecordQueryRequest request) {
    StatisticFilterGroup filterGroup =
        ReviewDataRecordFilterGroupSupport.parse(jsonUtils, request.filterGroupJson());
    StatisticFilterGroup expandedFilterGroup = expandLabelGroupConditions(filterGroup, request.sourceInstance());
    if (expandedFilterGroup == null || expandedFilterGroup.conditions() == null) {
      return List.of();
    }
    return expandedFilterGroup.conditions().stream()
        .filter(com.data.collection.platform.entity.statistics.StatisticFilterCondition::usesLabelGroup)
        .map(condition -> "%s %s %s（标签组：%s）".formatted(
            condition.fieldKey(),
            condition.operator(),
            TextQuerySupport.trimToNull(condition.labelGroupName()) == null
                ? condition.labelGroupId()
                : condition.labelGroupName(),
            String.join("、", condition.values())))
        .toList();
  }

  public ReviewDataProblemItemResponse getProblemItem(Long recordId, Long itemId) {
    persistenceSupport.assertRecordExists(recordId);
    return persistenceSupport.getProblemItemOrThrow(recordId, itemId);
  }

  private StatisticFilterGroup expandLabelGroupConditions(
      StatisticFilterGroup filterGroup, String sourceInstance) {
    if (filterGroup == null || filterGroup.conditions() == null || filterGroup.conditions().isEmpty()) {
      return filterGroup;
    }
    List<com.data.collection.platform.entity.statistics.StatisticFilterCondition> conditions =
        filterGroup.conditions().stream()
            .map(condition -> expandLabelGroupCondition(condition, sourceInstance))
            .toList();
    return new StatisticFilterGroup(filterGroup.logic(), conditions);
  }

  private com.data.collection.platform.entity.statistics.StatisticFilterCondition expandLabelGroupCondition(
      com.data.collection.platform.entity.statistics.StatisticFilterCondition condition,
      String sourceInstance) {
    if (condition == null || !condition.usesLabelGroup()) {
      return condition;
    }
    String fieldKey = requireSupportedField(condition.fieldKey());
    String expectedValueType = LABEL_GROUP_FIELD_VALUE_TYPES.get(fieldKey);
    if (!"eq".equals(condition.operator()) && !"ne".equals(condition.operator())) {
      throw new BizException("标签组筛选只支持等于或不等于关系");
    }
    if (condition.labelGroupId() == null) {
      throw new BizException("标签组筛选缺少标签组 ID");
    }
    LabelGroupExpansionResponse expansion =
        labelGroupExpansionService.expand(
            condition.labelGroupId(), expectedValueType, fieldKey, REVIEW_DATA_PAGE_KEY, sourceInstance);
    if (expansion.values().size() > MAX_LABEL_GROUP_FILTER_VALUES) {
      throw new BizException("筛选条件展开后超过 200 个值，请减少普通筛选值或拆分标签组");
    }
    return new com.data.collection.platform.entity.statistics.StatisticFilterCondition(
        fieldKey,
        condition.operator(),
        null,
        null,
        "LABEL_GROUP",
        condition.labelGroupId(),
        condition.labelGroupName(),
        expansion.values());
  }

  private String requireSupportedField(String fieldKey) {
    String normalized = TextQuerySupport.trimToNull(fieldKey);
    if (normalized == null || !LABEL_GROUP_FIELD_VALUE_TYPES.containsKey(normalized)) {
      throw new BizException("当前页面不支持该标签组筛选字段");
    }
    return normalized;
  }

}
