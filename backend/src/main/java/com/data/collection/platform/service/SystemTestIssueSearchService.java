package com.data.collection.platform.service;

import com.data.collection.platform.entity.SystemTestIssueSearchFilterOptionsResponse;
import com.data.collection.platform.entity.SystemTestIssueSearchListResponse;
import com.data.collection.platform.entity.SystemTestIssueSearchRowResponse;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.ProjectionScopeType;
import com.data.collection.platform.entity.labelgroup.LabelGroupExpansionResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.service.labelgroup.LabelGroupExpansionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SystemTestIssueSearchService extends AbstractIssueFactRecordListService
    implements PageRecordSnapshotRefresher {
  private static final String DEFAULT_SORT_FIELD = "updatedAt";
  private static final String PAGE_KEY = "question-metrics-issue-search";
  private static final String RULE_VERSION = "system-test-issue-search@2026-07-22-v2";
  private static final long LEGACY_CROWN_CAD_PROJECT_ID = 9L;
  private static final int EXPORT_PAGE_SIZE = 100;
  private static final int MAX_LABEL_GROUP_FILTER_VALUES = 200;
  private static final Map<String, String> LABEL_GROUP_FIELD_VALUE_TYPES =
      Map.ofEntries(
          Map.entry("title", "STRING"),
          Map.entry("projectName", "STRING"),
          Map.entry("moduleName", "STRING"),
          Map.entry("functionName", "STRING"),
          Map.entry("testingPhase", "STRING"),
          Map.entry("severityLevel", "STRING"),
          Map.entry("issueState", "STRING"),
          Map.entry("bugStatus", "STRING"),
          Map.entry("category", "STRING"),
          Map.entry("priorityLevel", "STRING"),
          Map.entry("milestoneTitle", "STRING"),
          Map.entry("authorName", "STRING"),
          Map.entry("assigneeName", "STRING"));
  private static final Pattern NUMERIC_ONLY_MODULE = Pattern.compile("^\\d+$");
  private static final Pattern CROSS_FIELD_PREFIX_MODULE =
      Pattern.compile("^(?:项目|分支|客户|状态|阶段|测试阶段|严重程度|类别|延期原因|版本|里程碑|project|branch|customer|status|phase)\\s*[:：].*",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern CUSTOMER_RELEASE_MODULE = Pattern.compile("^cc\\d{4}r\\d+.*客户.*$", Pattern.CASE_INSENSITIVE);
  private static final Set<String> DIRTY_HISTORICAL_MODULE_VALUES =
      Set.of("前端", "未识别模块", "未设定模块", "未标记模块");
  private static final Map<String, Comparator<IssueFactRecord>> SORT_COMPARATORS =
      createSortComparators();

  private final ObjectMapper objectMapper;
  private final LabelGroupExpansionService labelGroupExpansionService;
  private final SystemTestPhaseScopeResolver phaseScopeResolver;
  private final SystemTestPhaseCatalogService phaseCatalogService;
  private final PageRecordSnapshotService pageRecordSnapshotService;

  public SystemTestIssueSearchService(
      IssueFactRecordRepository issueFactRecordRepository,
      GitlabResourceLinkService issueLinkService,
      ObjectMapper objectMapper,
      LabelGroupExpansionService labelGroupExpansionService,
      SystemTestPhaseScopeResolver phaseScopeResolver,
      SystemTestPhaseCatalogService phaseCatalogService,
      PageRecordSnapshotService pageRecordSnapshotService) {
    super(issueFactRecordRepository, issueLinkService);
    this.objectMapper = objectMapper;
    this.labelGroupExpansionService = labelGroupExpansionService;
    this.phaseScopeResolver = phaseScopeResolver;
    this.phaseCatalogService = phaseCatalogService;
    this.pageRecordSnapshotService = pageRecordSnapshotService;
  }

  public SystemTestIssueSearchListResponse listRecords(SystemTestIssueSearchQueryRequest request) {
    SystemTestIssueSearchQueryRequest safeRequest = withSnapshotDefaults(request);
    return pageRecordSnapshotService.readOrRefresh(
        snapshotRequest(
            PageRecordSnapshotService.SNAPSHOT_TYPE_LIST,
            "project:" + safeRequest.listRequest().projectId(),
            safeRequest.listRequest().sourceInstance(),
            safeRequest.listRequest().projectId(),
            safeRequest.testingPhase(),
            safeRequest),
        SystemTestIssueSearchListResponse.class,
        () -> loadRecords(safeRequest));
  }

  private SystemTestIssueSearchListResponse loadRecords(SystemTestIssueSearchQueryRequest request) {
    IssueFactRecordListRequest listRequest = withLegacyDefaultProject(request.listRequest());
    int safePage = normalizePage(listRequest.page());
    int safeSize = normalizeSize(listRequest.size());
    String safeSortField =
        normalizeSortField(listRequest.sortField(), DEFAULT_SORT_FIELD, SORT_COMPARATORS.keySet());
    String safeSortOrder = normalizeSortOrder(listRequest.sortOrder());
    StatisticFilterGroup parsedFilterGroup =
        IssueFactRecordFilterGroupSupport.parse(
            objectMapper,
            request.filterGroupJson(),
            IssueFactRecordFilterGroupSupport.SYSTEM_TEST_FILTER_OPERATORS);
    StatisticFilterGroup filterGroup =
        SystemTestPhaseFilterGroupExpander.expand(
            parsedFilterGroup, listRequest.projectId(), phaseScopeResolver);
    StatisticFilterGroup expandedFilterGroup = expandLabelGroupConditions(filterGroup, listRequest.sourceInstance());
    List<String> requestedTestingPhases = effectiveTestingPhases(request.testingPhases());
    List<String> resolvedTestingPhases =
        phaseScopeResolver.resolvePhases(listRequest.projectId(), requestedTestingPhases);
    if (!requestedTestingPhases.isEmpty() && resolvedTestingPhases.isEmpty()) {
      return new SystemTestIssueSearchListResponse(
          List.of(), 0, safePage, safeSize, safeSortField, safeSortOrder);
    }

    List<IssueFactRecord> filtered =
        applyBaseFilters(
                loadFacts(listRequest.projectId()),
                listRequest,
                view -> matchesKeyword(view, listRequest.keyword()))
            .stream()
            .filter(this::matchesLegacyIssueSearchVisibility)
            .filter(view -> matchesTestingPhase(view, resolvedTestingPhases))
            .filter(view -> matchesEquals(view.authorName(), request.authorName()))
            .filter(view -> matchesEquals(view.assigneeName(), request.assigneeName()))
            .filter(view -> matchesFunctionName(view, listRequest.functionName()))
            .filter(view -> IssueFactRecordFilterGroupSupport.matches(view, expandedFilterGroup, true))
            .sorted(applySortDirection(SORT_COMPARATORS.get(safeSortField), safeSortOrder))
            .toList();

    PageSlice<IssueFactRecord> pageSlice = PageSliceSupport.slice(filtered, safePage, safeSize);
    List<SystemTestIssueSearchRowResponse> records =
        pageSlice.records().stream().map(this::toResponse).toList();
    return new SystemTestIssueSearchListResponse(
        records, pageSlice.total(), pageSlice.page(), pageSlice.size(), safeSortField, safeSortOrder);
  }

  public byte[] exportRecordsWorkbook(SystemTestIssueSearchQueryRequest request) {
    List<SystemTestIssueSearchRowResponse> rows = new ArrayList<>();
    int page = 1;
    while (true) {
      IssueFactRecordListRequest listRequest = withLegacyDefaultProject(request.listRequest());
      SystemTestIssueSearchQueryRequest pageRequest =
          new SystemTestIssueSearchQueryRequest(
              new IssueFactRecordListRequest(
                  listRequest.projectId(),
                  listRequest.keyword(),
                  listRequest.searchType(),
                  listRequest.issueIid(),
                  listRequest.title(),
                  listRequest.projectName(),
                  listRequest.moduleName(),
                  listRequest.functionName(),
                  listRequest.severityLevel(),
                  listRequest.priorityLevel(),
                  listRequest.issueState(),
                  listRequest.bugStatus(),
                  listRequest.category(),
                  listRequest.milestoneTitle(),
                  listRequest.createdAtStart(),
                  listRequest.createdAtEnd(),
                  listRequest.updatedAtStart(),
                  listRequest.updatedAtEnd(),
                  listRequest.sourceInstance(),
                  page,
                  EXPORT_PAGE_SIZE,
                  listRequest.sortField(),
                  listRequest.sortOrder()),
              request.testingPhase(),
              request.authorName(),
              request.assigneeName(),
              request.filterGroupJson());
      SystemTestIssueSearchListResponse response = listRecords(pageRequest);
      rows.addAll(response.records());
      if (response.records().size() < EXPORT_PAGE_SIZE || rows.size() >= response.total()) {
        break;
      }
      page += 1;
    }
    return SystemTestIssueRecordWorkbookExportSupport.exportRecords(rows, describeExpandedLabelGroupFilters(request));
  }

  private List<String> describeExpandedLabelGroupFilters(SystemTestIssueSearchQueryRequest request) {
    StatisticFilterGroup filterGroup =
        IssueFactRecordFilterGroupSupport.parse(
            objectMapper,
            request.filterGroupJson(),
            IssueFactRecordFilterGroupSupport.SYSTEM_TEST_FILTER_OPERATORS);
    StatisticFilterGroup expandedFilterGroup =
        expandLabelGroupConditions(filterGroup, request.listRequest().sourceInstance());
    if (expandedFilterGroup == null || expandedFilterGroup.conditions() == null) {
      return List.of();
    }
    return expandedFilterGroup.conditions().stream()
        .filter(StatisticFilterCondition::usesLabelGroup)
        .map(condition -> "%s %s %s（标签组：%s）".formatted(
            condition.fieldKey(),
            condition.operator(),
            TextQuerySupport.trimToNull(condition.labelGroupName()) == null
                ? condition.labelGroupId()
                : condition.labelGroupName(),
            String.join("、", condition.values())))
        .toList();
  }

  public SystemTestIssueSearchFilterOptionsResponse getFilterOptions(Long projectId) {
    return getFilterOptions(projectId, null);
  }

  public SystemTestIssueSearchFilterOptionsResponse getFilterOptions(Long projectId, String sourceInstance) {
    Map<String, Object> requestPayload = new LinkedHashMap<>();
    requestPayload.put("projectId", projectId == null ? LEGACY_CROWN_CAD_PROJECT_ID : projectId);
    String normalizedSource = TextQuerySupport.trimToNull(sourceInstance);
    if (normalizedSource != null) {
      requestPayload.put("sourceInstance", normalizedSource);
    }
    return pageRecordSnapshotService.readOrRefresh(
        snapshotRequest(
            PageRecordSnapshotService.SNAPSHOT_TYPE_FILTER_OPTIONS,
            "project:" + requestPayload.get("projectId"),
            sourceInstance,
            (Long) requestPayload.get("projectId"),
            null,
            requestPayload),
        SystemTestIssueSearchFilterOptionsResponse.class,
        () -> loadFilterOptions(projectId, sourceInstance));
  }

  private SystemTestIssueSearchFilterOptionsResponse loadFilterOptions(Long projectId, String sourceInstance) {
    List<IssueFactRecord> scopedViews = loadIssueSearchOptionFacts(projectId, sourceInstance);
    return new SystemTestIssueSearchFilterOptionsResponse(
        toLegacyOptions(scopedViews, IssueFactRecord::projectName),
        toLegacyOptions(scopedViews.stream()
            .flatMap(view -> view.moduleNames().stream())
            .filter(SystemTestIssueSearchService::isCleanModuleOption)
            .toList()),
        toLegacyOptions(scopedViews, IssueFactRecord::functionName),
        toOptionsPreservingOrder(phaseCatalogService.listParentNames(LEGACY_CROWN_CAD_PROJECT_ID)),
        toLegacyOptions(scopedViews, IssueFactRecord::authorName),
        toLegacyOptions(scopedViews, IssueFactRecord::assigneeName),
        toOptions(scopedViews, IssueFactRecord::issueState),
        toSeverityOptions(scopedViews, IssueFactRecord::severityLevel),
        OptionItemResponseFactory.fromIssueStatusMembers(
            scopedViews.stream().map(IssueFactRecord::bugStatus).toList()),
        toOptions(scopedViews, IssueFactRecord::category),
        toLegacyOptions(scopedViews, IssueFactRecord::milestoneTitle));
  }

  @Override
  public void refreshRecordSnapshots(com.data.collection.platform.entity.FactPublicationContext context) {
    if (context == null
        || !context.covers(
            FactType.ISSUE,
            ProjectionScopeType.PROJECT,
            FactProjectionScopeKeyCodec.project(LEGACY_CROWN_CAD_PROJECT_ID))) {
      return;
    }
    getFilterOptions(LEGACY_CROWN_CAD_PROJECT_ID);
    listRecords(defaultRequest());
  }

  private List<IssueFactRecord> loadIssueSearchOptionFacts(Long projectId, String sourceInstance) {
    return issueFactRecordRepository.findForFilterOptions(
        new IssueFactRecordListRequest(
            projectId == null ? LEGACY_CROWN_CAD_PROJECT_ID : projectId,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            sourceInstance,
            1,
            20,
            "updatedAt",
            "desc"));
  }

  private SystemTestIssueSearchQueryRequest withSnapshotDefaults(SystemTestIssueSearchQueryRequest request) {
    return new SystemTestIssueSearchQueryRequest(
        withLegacyDefaultProject(request.listRequest()),
        request.testingPhase(),
        request.authorName(),
        request.assigneeName(),
        request.filterGroupJson());
  }

  private SystemTestIssueSearchQueryRequest defaultRequest() {
    return new SystemTestIssueSearchQueryRequest(
        new IssueFactRecordListRequest(
            LEGACY_CROWN_CAD_PROJECT_ID,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            1,
            20,
            DEFAULT_SORT_FIELD,
            "descending"),
        null,
        null,
        null,
        null);
  }

  private PageRecordSnapshotService.SnapshotRequest snapshotRequest(
      String snapshotType,
      String scopeKey,
      String sourceInstance,
      Long projectId,
      String testingPhaseBusinessKey,
      Object requestPayload) {
    return new PageRecordSnapshotService.SnapshotRequest(
        PAGE_KEY,
        snapshotType,
        scopeKey,
        RULE_VERSION,
        pageRecordSnapshotService.issueFactSourceVersion(
            sourceInstance,
            projectId == null ? LEGACY_CROWN_CAD_PROJECT_ID : projectId,
            IssueScopeDimension.TESTING_PHASE,
            testingPhaseBusinessKey),
        requestPayload);
  }

  private StatisticFilterGroup expandLabelGroupConditions(
      StatisticFilterGroup filterGroup, String sourceInstance) {
    if (filterGroup == null || filterGroup.conditions() == null || filterGroup.conditions().isEmpty()) {
      return filterGroup;
    }
    return new StatisticFilterGroup(
        filterGroup.logic(),
        filterGroup.conditions().stream()
            .map(condition -> expandLabelGroupCondition(condition, sourceInstance))
            .toList());
  }

  private StatisticFilterCondition expandLabelGroupCondition(
      StatisticFilterCondition condition, String sourceInstance) {
    if (condition == null || !condition.usesLabelGroup()) {
      return condition;
    }
    String fieldKey = requireSupportedLabelGroupField(condition.fieldKey());
    String expectedValueType = LABEL_GROUP_FIELD_VALUE_TYPES.get(fieldKey);
    if (!LabelGroupFilterOperatorSupport.isSetOperator(condition.operator())) {
      throw new com.data.collection.platform.common.exception.BizException("标签组筛选只支持集合关系");
    }
    if (!LabelGroupFilterOperatorSupport.supportsPartialContainsAny(condition.operator(), expectedValueType)) {
      throw new com.data.collection.platform.common.exception.BizException("局部包含任意标签组仅支持字符串字段");
    }
    if (condition.labelGroupId() == null) {
      throw new com.data.collection.platform.common.exception.BizException("标签组筛选缺少标签组 ID");
    }
    LabelGroupExpansionResponse expansion =
        labelGroupExpansionService.expand(
            condition.labelGroupId(), expectedValueType, fieldKey, PAGE_KEY, sourceInstance);
    if (expansion.values().size() > MAX_LABEL_GROUP_FILTER_VALUES) {
      throw new com.data.collection.platform.common.exception.BizException("筛选条件展开后超过 200 个值，请减少普通筛选值或拆分标签组");
    }
    return new StatisticFilterCondition(
        fieldKey,
        LabelGroupFilterOperatorSupport.normalize(condition.operator()),
        null,
        null,
        "LABEL_GROUP",
        condition.labelGroupId(),
        condition.labelGroupName(),
        expansion.values());
  }

  private String requireSupportedLabelGroupField(String fieldKey) {
    String normalized = TextQuerySupport.trimToNull(fieldKey);
    if (normalized == null || !LABEL_GROUP_FIELD_VALUE_TYPES.containsKey(normalized)) {
      throw new com.data.collection.platform.common.exception.BizException("当前页面不支持该标签组筛选字段");
    }
    return normalized;
  }

  private static boolean isCleanModuleOption(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return false;
    }
    if (DIRTY_HISTORICAL_MODULE_VALUES.contains(normalized)) {
      return false;
    }
    if (NUMERIC_ONLY_MODULE.matcher(normalized).matches()) {
      return false;
    }
    if (CROSS_FIELD_PREFIX_MODULE.matcher(normalized).matches()) {
      return false;
    }
    String lower = normalized.toLowerCase(Locale.ROOT);
    return !CUSTOMER_RELEASE_MODULE.matcher(lower).matches() && !normalized.endsWith("客户");
  }

  private IssueFactRecordListRequest withLegacyDefaultProject(IssueFactRecordListRequest request) {
    return new IssueFactRecordListRequest(
        request.projectId() == null ? LEGACY_CROWN_CAD_PROJECT_ID : request.projectId(),
        request.keyword(),
        request.searchType(),
        request.issueIid(),
        request.title(),
        request.projectName(),
        request.moduleName(),
        request.functionName(),
        request.severityLevel(),
        request.priorityLevel(),
        request.issueState(),
        request.bugStatus(),
        request.category(),
        request.milestoneTitle(),
        request.createdAtStart(),
        request.createdAtEnd(),
        request.updatedAtStart(),
        request.updatedAtEnd(),
        request.sourceInstance(),
        request.page(),
        request.size(),
        request.sortField(),
        request.sortOrder());
  }

  private SystemTestIssueSearchRowResponse toResponse(IssueFactRecord view) {
    return new SystemTestIssueSearchRowResponse(
        view.issueId(),
        view.issueIid(),
        buildIssueLink(view.sourceInstance(), view.projectId(), view.issueIid()),
        view.sourceInstance(),
        view.projectId(),
        view.projectName(),
        view.title(),
        view.issueState(),
        view.primaryPhaseLabel(),
        IssueDisplayValueSupport.displaySeverityLevelOrBlank(view.severityLevel()),
        view.priorityLevel(),
        view.bugStatus(),
        view.category(),
        view.milestoneTitle(),
        view.delayCause(),
        view.authorName(),
        view.assigneeName(),
        String.join(" & ", view.moduleNames()),
        view.functionName(),
        view.createdAt(),
        view.updatedAt(),
        view.closedAt(),
        view.labels());
  }

  private boolean matchesKeyword(IssueFactRecord view, String keyword) {
    String normalizedKeyword = TextQuerySupport.trimToNull(keyword);
    if (normalizedKeyword == null) {
      return true;
    }
    return TextQuerySupport.containsAbstractSearch(String.valueOf(view.issueIid()), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.title(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.projectName(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(String.join(" ", view.moduleNames()), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.functionName(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.primaryPhaseLabel(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.authorName(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.assigneeName(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.bugStatus(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.category(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.milestoneTitle(), normalizedKeyword);
  }

  private boolean matchesLegacyIssueSearchVisibility(IssueFactRecord view) {
    return !TextQuerySupport.containsAbstractSearch(view.bugStatus(), "已拒绝")
        && !TextQuerySupport.containsAbstractSearch(view.category(), "功能屏蔽");
  }

  private boolean matchesTestingPhase(IssueFactRecord view, List<String> resolvedTestingPhases) {
    if (resolvedTestingPhases == null || resolvedTestingPhases.isEmpty()) {
      return true;
    }
    String actualTestingPhase = TextQuerySupport.trimToNull(view.primaryPhaseLabel());
    return actualTestingPhase != null
        && resolvedTestingPhases.stream()
            .filter(StringUtils::hasText)
            .anyMatch(phase -> actualTestingPhase.equalsIgnoreCase(phase));
  }

  private List<String> effectiveTestingPhases(List<String> requestedTestingPhases) {
    if (requestedTestingPhases != null && !requestedTestingPhases.isEmpty()) {
      return requestedTestingPhases;
    }
    return List.of();
  }

  private boolean matchesFunctionName(IssueFactRecord view, String functionName) {
    String normalized = TextQuerySupport.trimToNull(functionName);
    return normalized == null || TextQuerySupport.containsAbstractSearch(view.functionName(), normalized);
  }

  private static Map<String, Comparator<IssueFactRecord>> createSortComparators() {
    Map<String, Comparator<IssueFactRecord>> comparators = new LinkedHashMap<>();
    comparators.put("issueIid", SortSupport.nullableComparable(IssueFactRecord::issueIid));
    comparators.put("title", SortSupport.nullableString(IssueFactRecord::title));
    comparators.put("projectName", SortSupport.nullableString(IssueFactRecord::projectName));
    comparators.put(
        "moduleNames", SortSupport.nullableString(view -> String.join(" & ", view.moduleNames())));
    comparators.put("functionName", SortSupport.nullableString(IssueFactRecord::functionName));
    comparators.put("testingPhase", SortSupport.nullableString(IssueFactRecord::primaryPhaseLabel));
    comparators.put("severityLevel", SortSupport.nullableString(IssueFactRecord::severityLevel));
    comparators.put("bugStatus", SortSupport.nullableString(IssueFactRecord::bugStatus));
    comparators.put("issueState", SortSupport.nullableString(IssueFactRecord::issueState));
    comparators.put("authorName", SortSupport.nullableString(IssueFactRecord::authorName));
    comparators.put("assigneeName", SortSupport.nullableString(IssueFactRecord::assigneeName));
    comparators.put("category", SortSupport.nullableString(IssueFactRecord::category));
    comparators.put("milestoneTitle", SortSupport.nullableString(IssueFactRecord::milestoneTitle));
    comparators.put("createdAt", SortSupport.nullableComparable(IssueFactRecord::createdAt));
    comparators.put("updatedAt", SortSupport.nullableComparable(IssueFactRecord::updatedAt));
    comparators.put("closedAt", SortSupport.nullableComparable(IssueFactRecord::closedAt));
    return Map.copyOf(comparators);
  }
}
