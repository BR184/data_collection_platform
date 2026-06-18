package com.data.collection.platform.service;

import com.data.collection.platform.entity.SystemTestIssueSearchFilterOptionsResponse;
import com.data.collection.platform.entity.SystemTestIssueSearchListResponse;
import com.data.collection.platform.entity.SystemTestIssueSearchRowResponse;
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
public class SystemTestIssueSearchService extends AbstractIssueFactRecordListService {
  private static final String DEFAULT_SORT_FIELD = "updatedAt";
  private static final String PAGE_KEY = "question-metrics-issue-search";
  private static final int EXPORT_PAGE_SIZE = 100;
  private static final int MAX_LABEL_GROUP_FILTER_VALUES = 200;
  private static final Map<String, String> LABEL_GROUP_FIELD_VALUE_TYPES =
      Map.ofEntries(
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

  public SystemTestIssueSearchService(
      IssueFactRecordRepository issueFactRecordRepository,
      GitlabResourceLinkService issueLinkService,
      ObjectMapper objectMapper,
      LabelGroupExpansionService labelGroupExpansionService) {
    super(issueFactRecordRepository, issueLinkService);
    this.objectMapper = objectMapper;
    this.labelGroupExpansionService = labelGroupExpansionService;
  }

  public SystemTestIssueSearchListResponse listRecords(SystemTestIssueSearchQueryRequest request) {
    IssueFactRecordListRequest listRequest = request.listRequest();
    int safePage = normalizePage(listRequest.page());
    int safeSize = normalizeSize(listRequest.size());
    String safeSortField =
        normalizeSortField(listRequest.sortField(), DEFAULT_SORT_FIELD, SORT_COMPARATORS.keySet());
    String safeSortOrder = normalizeSortOrder(listRequest.sortOrder());
    StatisticFilterGroup filterGroup =
        IssueFactRecordFilterGroupSupport.parse(
            objectMapper,
            request.filterGroupJson(),
            IssueFactRecordFilterGroupSupport.SYSTEM_TEST_FILTER_OPERATORS);
    StatisticFilterGroup expandedFilterGroup = expandLabelGroupConditions(filterGroup, listRequest.sourceInstance());
    boolean hasLabelGroupFilters = IssueFactRecordFilterGroupSupport.hasLabelGroupConditions(expandedFilterGroup);

    if (!hasLabelGroupFilters && canUseSqlPage(listRequest, request.filterGroupJson(), safeSortField)) {
      PageSlice<IssueFactRecord> pageSlice =
          loadFactPage(
              new IssueFactRecordPageQuery(
                  IssueFactRecordPageQuery.Scope.ALL,
                  listRequest,
                  expandedFilterGroup,
                  null,
                  null,
                  request.testingPhase(),
                  request.testingPhases(),
                  request.authorName(),
                  request.assigneeName(),
                  false,
                  false,
                  false,
                  false,
                  false,
                  false,
                  true,
                  safePage,
                  safeSize,
                  safeSortField,
                  safeSortOrder));
      List<SystemTestIssueSearchRowResponse> records =
          pageSlice.records().stream().map(this::toResponse).toList();
      return new SystemTestIssueSearchListResponse(
          records, pageSlice.total(), pageSlice.page(), pageSlice.size(), safeSortField, safeSortOrder);
    }

    List<IssueFactRecord> filtered =
        applyBaseFilters(
                loadFacts(listRequest.projectId()),
                listRequest,
                view -> matchesKeyword(view, listRequest.keyword()))
            .stream()
            .filter(view -> matchesTestingPhase(view, request.testingPhases()))
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

  public String exportRecordsCsv(SystemTestIssueSearchQueryRequest request) {
    List<SystemTestIssueSearchRowResponse> rows = new ArrayList<>();
    int page = 1;
    while (true) {
      IssueFactRecordListRequest listRequest = request.listRequest();
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
      CsvExportSupport.ensureWithinRowLimit(response.total());
      rows.addAll(response.records());
      if (response.records().size() < EXPORT_PAGE_SIZE || rows.size() >= response.total()) {
        break;
      }
      page += 1;
    }

    List<String> lines = new ArrayList<>();
    List<String> labelGroupSnapshots = describeExpandedLabelGroupFilters(request);
    if (!labelGroupSnapshots.isEmpty()) {
      lines.add(String.join(
          ",",
          List.of("标签组筛选快照", CsvExportSupport.cell(String.join("；", labelGroupSnapshots)))));
    }
    lines.add(
        String.join(
            ",",
            List.of(
                "sourceInstance",
                "projectId",
                "问题编号",
                "项目",
                "模块",
                "测试阶段",
                "议题严重程度",
                "测试状态",
                "议题状态",
                "议题提交人",
                "议题处理人",
                "议题指派人",
                "优先级",
                "功能名称",
                "议题类别",
                "里程碑",
                "延期原因",
                "议题提交时间",
                "议题更新时间",
                "议题关闭时间",
                "议题标题",
                "链接")));
    for (SystemTestIssueSearchRowResponse row : rows) {
      lines.add(
          String.join(
              ",",
              List.of(
                  CsvExportSupport.cell(row.sourceInstance()),
                  CsvExportSupport.cell(row.projectId()),
                  CsvExportSupport.cell(row.issueIid()),
                  CsvExportSupport.cell(row.projectName()),
                  CsvExportSupport.cell(row.moduleNames()),
                  CsvExportSupport.cell(row.testingPhase()),
                  CsvExportSupport.cell(row.severityLevel()),
                  CsvExportSupport.cell(row.bugStatus()),
                  CsvExportSupport.cell(row.issueState()),
                  CsvExportSupport.cell(row.authorName()),
                  CsvExportSupport.cell(row.assigneeName()),
                  CsvExportSupport.cell(row.assigneeName()),
                  CsvExportSupport.cell(row.priorityLevel()),
                  CsvExportSupport.cell(row.functionName()),
                  CsvExportSupport.cell(row.category()),
                  CsvExportSupport.cell(row.milestoneTitle()),
                  CsvExportSupport.cell(row.delayCause()),
                  CsvExportSupport.cell(CsvExportSupport.dateTime(row.createdAt())),
                  CsvExportSupport.cell(CsvExportSupport.dateTime(row.updatedAt())),
                  CsvExportSupport.cell(CsvExportSupport.dateTime(row.closedAt())),
                  CsvExportSupport.cell(row.title()),
                  CsvExportSupport.cell(row.issueLink()))));
    }
    return String.join("\n", lines) + "\n";
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
    List<IssueFactRecord> scopedViews = loadIssueSearchOptionFacts(projectId, sourceInstance);
    return new SystemTestIssueSearchFilterOptionsResponse(
        toLegacyOptions(scopedViews, IssueFactRecord::projectName),
        toLegacyOptions(scopedViews.stream()
            .flatMap(view -> view.moduleNames().stream())
            .filter(SystemTestIssueSearchService::isCleanModuleOption)
            .toList()),
        toLegacyOptions(scopedViews, IssueFactRecord::functionName),
        toOptions(
            scopedViews.stream()
                .map(IssueFactRecord::primaryPhaseLabel)
                .filter(StringUtils::hasText)
                .toList()),
        toLegacyOptions(scopedViews, IssueFactRecord::authorName),
        toLegacyOptions(scopedViews, IssueFactRecord::assigneeName),
        toOptions(scopedViews, IssueFactRecord::issueState),
        toSeverityOptions(scopedViews, IssueFactRecord::severityLevel),
        toOptions(scopedViews, IssueFactRecord::bugStatus),
        toOptions(scopedViews, IssueFactRecord::category),
        toLegacyOptions(scopedViews, IssueFactRecord::milestoneTitle));
  }

  private List<IssueFactRecord> loadIssueSearchOptionFacts(Long projectId, String sourceInstance) {
    return issueFactRecordRepository.findForFilterOptions(
        new IssueFactRecordListRequest(
            projectId,
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
    if (!LabelGroupFilterOperatorSupport.isSetOperator(condition.operator())) {
      throw new com.data.collection.platform.common.exception.BizException("标签组筛选只支持集合关系");
    }
    if (condition.labelGroupId() == null) {
      throw new com.data.collection.platform.common.exception.BizException("标签组筛选缺少标签组 ID");
    }
    LabelGroupExpansionResponse expansion =
        labelGroupExpansionService.expand(
            condition.labelGroupId(), LABEL_GROUP_FIELD_VALUE_TYPES.get(fieldKey), fieldKey, PAGE_KEY, sourceInstance);
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

  private boolean matchesTestingPhase(IssueFactRecord view, String testingPhase) {
    String normalized = TextQuerySupport.trimToNull(testingPhase);
    return normalized == null || TextQuerySupport.equalsNormalized(view.primaryPhaseLabel(), normalized);
  }

  private boolean matchesTestingPhase(IssueFactRecord view, List<String> testingPhases) {
    if (testingPhases == null || testingPhases.isEmpty()) {
      return true;
    }
    return testingPhases.stream()
        .map(TextQuerySupport::trimToNull)
        .filter(value -> value != null)
        .anyMatch(value -> TextQuerySupport.equalsNormalized(view.primaryPhaseLabel(), value));
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
