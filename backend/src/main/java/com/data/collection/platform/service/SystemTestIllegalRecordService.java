package com.data.collection.platform.service;

import com.data.collection.platform.entity.SystemTestIllegalRecordFilterOptionsResponse;
import com.data.collection.platform.entity.SystemTestIllegalRecordListResponse;
import com.data.collection.platform.entity.SystemTestIllegalRecordRowResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStepSample;
import com.data.collection.platform.entity.statistics.StatisticRuleMetricDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SystemTestIllegalRecordService extends AbstractIssueFactRecordListService
    implements PageRecordSnapshotRefresher {
  private static final String WORKSPACE_KEY = "system-test-illegal-records";
  private static final String RULE_VERSION = "system-test-illegal-records@2026-07-22-v4";
  private static final String DEFAULT_SORT_FIELD = "updatedAt";
  private static final long LEGACY_CROWN_CAD_PROJECT_ID = 9L;
  private static final int EXPORT_PAGE_SIZE = 100;
  private static final Map<String, Comparator<IssueFactRecord>> SORT_COMPARATORS =
      createSortComparators();

  private final SystemTestScopeProfile systemTestScopeProfile;
  private final ObjectMapper objectMapper;
  private final FactBuildService factBuildService;
  private final SystemTestPhaseScopeResolver phaseScopeResolver;
  private final SystemTestPhaseCatalogService phaseCatalogService;
  private final PageRecordSnapshotService pageRecordSnapshotService;

  public SystemTestIllegalRecordService(
      IssueFactRecordRepository issueFactRecordRepository,
      SystemTestScopeProfile systemTestScopeProfile,
      ObjectMapper objectMapper,
      GitlabResourceLinkService issueLinkService,
      FactBuildService factBuildService,
      SystemTestPhaseScopeResolver phaseScopeResolver,
      SystemTestPhaseCatalogService phaseCatalogService,
      PageRecordSnapshotService pageRecordSnapshotService) {
    super(issueFactRecordRepository, issueLinkService);
    this.systemTestScopeProfile = systemTestScopeProfile;
    this.objectMapper = objectMapper;
    this.factBuildService = factBuildService;
    this.phaseScopeResolver = phaseScopeResolver;
    this.phaseCatalogService = phaseCatalogService;
    this.pageRecordSnapshotService = pageRecordSnapshotService;
  }

  public SystemTestIllegalRecordListResponse listRecords(SystemTestIllegalRecordQueryRequest request) {
    SystemTestIllegalRecordQueryRequest safeRequest = withSnapshotDefaults(request);
    return pageRecordSnapshotService.readOrRefresh(
        snapshotRequest(
            PageRecordSnapshotService.SNAPSHOT_TYPE_LIST,
            "project:" + defaultProjectId(safeRequest.listRequest().projectId()),
            safeRequest),
        SystemTestIllegalRecordListResponse.class,
        () -> loadRecords(safeRequest));
  }

  private SystemTestIllegalRecordListResponse loadRecords(SystemTestIllegalRecordQueryRequest request) {
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
        SystemTestPhaseFilterGroupExpander.expand(parsedFilterGroup, phaseScopeResolver);
    List<String> resolvedTestingPhases = resolvedRequestedPhaseOrWhitelist(request.testingPhase());
    if (resolvedTestingPhases.isEmpty()) {
      return new SystemTestIllegalRecordListResponse(
          List.of(), 0, safePage, safeSize, safeSortField, safeSortOrder);
    }

    if (canUseSqlPage(listRequest, request.filterGroupJson(), safeSortField)) {
      PageSlice<IssueFactRecord> pageSlice =
          loadFactPage(
              new IssueFactRecordPageQuery(
                  IssueFactRecordPageQuery.Scope.SYSTEM_TEST,
                  listRequest,
                  filterGroup,
                  null,
                  request.illegalReason(),
                  null,
                  resolvedTestingPhases,
                  request.authorName(),
                  null,
                  request.assigneeName(),
                  null,
                  null,
                  null,
                  false,
                  true,
                  true,
                  false,
                  true,
                  false,
                  true,
                  true,
                  safePage,
                  safeSize,
                  safeSortField,
                  safeSortOrder,
                  null));
      List<SystemTestIllegalRecordRowResponse> records =
          pageSlice.records().stream().map(this::toResponse).toList();
      return new SystemTestIllegalRecordListResponse(
          records, pageSlice.total(), pageSlice.page(), pageSlice.size(), safeSortField, safeSortOrder);
    }

    List<IssueFactRecord> filtered =
        applyBaseFilters(
                loadScopedIllegalViews(listRequest.projectId()),
                withoutModuleFilter(listRequest),
                view -> matchesKeyword(view, listRequest.keyword()))
            .stream()
            .filter(view -> matchesDisplayModule(view, listRequest.moduleName()))
            .filter(view -> matchesTestingPhase(view, resolvedTestingPhases))
            .filter(view -> matchesIllegalReason(view, request.illegalReason()))
            .filter(view -> matchesEquals(view.authorName(), request.authorName()))
            .filter(view -> matchesEquals(view.assigneeName(), request.assigneeName()))
            .filter(view -> IssueFactRecordFilterGroupSupport.matches(view, filterGroup, true))
            .sorted(applySortDirection(SORT_COMPARATORS.get(safeSortField), safeSortOrder))
            .toList();

    PageSlice<IssueFactRecord> pageSlice = PageSliceSupport.slice(filtered, safePage, safeSize);
    List<SystemTestIllegalRecordRowResponse> records =
        pageSlice.records().stream().map(this::toResponse).toList();
    return new SystemTestIllegalRecordListResponse(
        records, pageSlice.total(), pageSlice.page(), pageSlice.size(), safeSortField, safeSortOrder);
  }

  public byte[] exportRecordsWorkbook(SystemTestIllegalRecordQueryRequest request) {
    List<SystemTestIllegalRecordRowResponse> rows = new ArrayList<>();
    int page = 1;
    while (true) {
      IssueFactRecordListRequest listRequest = withLegacyDefaultProject(request.listRequest());
      SystemTestIllegalRecordQueryRequest pageRequest =
          new SystemTestIllegalRecordQueryRequest(
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
              request.illegalReason(),
              request.authorName(),
              request.assigneeName(),
              request.filterGroupJson());
      SystemTestIllegalRecordListResponse response = listRecords(pageRequest);
      rows.addAll(response.records());
      if (response.records().size() < EXPORT_PAGE_SIZE || rows.size() >= response.total()) {
        break;
      }
      page += 1;
    }
    return SystemTestIssueRecordWorkbookExportSupport.exportIllegalRecords(rows);
  }

  @Override
  protected boolean canUseSqlPage(
      IssueFactRecordListRequest request, String filterGroupJson, String safeSortField) {
    return false;
  }

  public SystemTestIllegalRecordFilterOptionsResponse getFilterOptions(Long projectId) {
    Long safeProjectId = defaultProjectId(projectId);
    Map<String, Object> requestPayload = Map.of("projectId", safeProjectId);
    return pageRecordSnapshotService.readOrRefresh(
        snapshotRequest(
            PageRecordSnapshotService.SNAPSHOT_TYPE_FILTER_OPTIONS,
            "project:" + safeProjectId,
            requestPayload),
        SystemTestIllegalRecordFilterOptionsResponse.class,
        () -> loadFilterOptions(safeProjectId));
  }

  private SystemTestIllegalRecordFilterOptionsResponse loadFilterOptions(Long projectId) {
    List<IssueFactRecord> values = loadScopedIllegalViews(projectId);
    return new SystemTestIllegalRecordFilterOptionsResponse(
        toLegacyOptions(values, IssueFactRecord::projectName),
        toLegacyOptions(values.stream().flatMap(row -> displayModuleNames(row).stream()).toList()),
        toOptionsPreservingOrder(phaseScopeOptions()),
        toOptionsPreservingOrder(normalizedExistingIllegalReasons(
            values.stream().flatMap(row -> displayIllegalReasons(row).stream()).toList())),
        toLegacyOptions(values, IssueFactRecord::authorName),
        toLegacyOptions(values, IssueFactRecord::assigneeName),
        toOptions(values.stream().map(IssueFactRecord::issueState).toList()),
        OptionItemResponseFactory.fromValues(
            values.stream().map(IssueFactRecord::severityLevel).toList(),
            TextQuerySupport::trimToNull,
            IssueDisplayValueSupport::displaySeverityLevel),
        OptionItemResponseFactory.fromIssueStatusMembers(
            values.stream().map(IssueFactRecord::bugStatus).toList()),
        toOptions(values.stream().map(IssueFactRecord::category).toList()),
        toLegacyOptions(values, IssueFactRecord::milestoneTitle));
  }

  @Override
  public void refreshRecordSnapshots(PageRecordSnapshotRefresher.RefreshContext context) {
    if (!context.affectsIssues()) {
      return;
    }
    getFilterOptions(LEGACY_CROWN_CAD_PROJECT_ID);
    listRecords(
        new SystemTestIllegalRecordQueryRequest(
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
            firstPhaseOption(),
            null,
            null,
            null,
            null));
  }

  public SystemTestIllegalRecordRowResponse refreshSingleRecord(
      String sourceInstance, Long projectId, Long issueIid) {
    Long safeProjectId = defaultProjectId(projectId);
    factBuildService.rebuildIssueFactByIid(sourceInstance, safeProjectId, issueIid);
    pageRecordSnapshotService.invalidatePage(WORKSPACE_KEY);
    String normalizedSource = GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance);
    return loadScopedIllegalViews(safeProjectId).stream()
        .filter(row -> row.issueIid() != null && row.issueIid().longValue() == issueIid)
        .filter(row -> GitlabSourceInstanceSupport.normalizeSourceInstance(row.sourceInstance()).equals(normalizedSource))
        .findFirst()
        .map(this::toResponse)
        .orElse(null);
  }

  public StatisticBoardRuleExplanationResponse getRuleExplanation(Long projectId) {
    List<IssueFactRecord> loaded = loadFacts(defaultProjectId(projectId));
    List<IssueFactRecord> scoped = scopeSystemTests(loaded);
    List<IssueFactRecord> valid = scoped.stream().filter(this::matchesOldPlatformSystemTestExclusion).toList();
    List<IssueFactRecord> illegal = valid.stream().filter(IssueFactRecord::illegal).toList();
    return new StatisticBoardRuleExplanationResponse(
        WORKSPACE_KEY,
        true,
        "系统测试非法数据规则说明",
        RULE_VERSION,
        "当前页面展示系统测试和回归测试范围内命中非法规则的议题。平台会先限定系统测试范围，再剔除不应参与统计的无效议题。",
        "非法类型按系统测试非法数据规则展示；同一个议题可能同时命中多个非法类型，页面筛选和导出都会保留这些类型。",
        List.of(
            step("source-load", "加载议题数据", "加载已同步到平台的议题数据，并使用整理后的测试阶段、模块、严重程度和非法类型。", loaded, loaded.size()),
            step("scope-filter", "限定系统测试范围", "保留系统测试和回归测试相关议题，避免与客户问题等其它范围混在一起。", scoped, loaded.size()),
            step("exclude-filter", "剔除排除数据", "排除功能屏蔽、已拒绝、建议、申请否决关闭、需求如此关闭等数据。", valid, scoped.size()),
            step("illegal-filter", "筛出非法数据", "保留命中非法判定规则的系统测试议题；同一议题可保留多个非法类型。", illegal, valid.size())),
        List.of(
            new StatisticRuleMetricDefinition(
                "missing-severity",
                SystemTestIllegalReasonSupport.MISSING_SEVERITY,
                "严重程度未命中一级缺陷、二级缺陷或三级缺陷。",
                "未设定严重程度 = 系统测试范围内缺少一级/二级/三级缺陷标签的议题数量",
                null),
            new StatisticRuleMetricDefinition(
                "missing-module",
                SystemTestIllegalReasonSupport.MISSING_MODULE,
                "议题没有模块标签。",
                "未设定模块 = 系统测试范围内缺少有效模块标签的议题数量",
                null),
            new StatisticRuleMetricDefinition(
                "template-not-followed",
                SystemTestIllegalReasonSupport.TEMPLATE_NOT_FOLLOWED,
                "议题带已修复/完成标签，但未按缺陷调研模板回复。",
                "未按照模板回复 = 已修复或已完成，但没有按要求填写缺陷调研模板的议题数量",
                null),
            new StatisticRuleMetricDefinition(
                "non-unique-reason",
                SystemTestIllegalReasonSupport.NON_UNIQUE_REASON,
                "议题带已修复/完成标签，但缺陷原因数量不是 1 个。",
                "缺陷原因不唯一 = 已修复或已完成，但缺陷原因不是唯一一个的议题数量",
                null)),
        null);
  }

  private List<IssueFactRecord> loadScopedIllegalViews(Long projectId) {
    return scopeSystemTests(loadFacts(projectId)).stream()
        .filter(this::matchesOldPlatformSystemTestExclusion)
        .filter(IssueFactRecord::illegal)
        .toList();
  }

  private List<IssueFactRecord> scopeSystemTests(List<IssueFactRecord> rows) {
    return rows.stream()
        .filter(view -> systemTestScopeProfile.matches(view.scopeContext()))
        .toList();
  }

  private IssueFactRecordListRequest withoutModuleFilter(IssueFactRecordListRequest request) {
    return new IssueFactRecordListRequest(
        request.projectId(),
        request.keyword(),
        request.searchType(),
        request.issueIid(),
        request.title(),
        request.projectName(),
        null,
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

  private SystemTestIllegalRecordRowResponse toResponse(IssueFactRecord view) {
    SystemTestLegacyCauseExportFields causeFields =
        SystemTestLegacyCauseExportFields.fromReasonText(view.reasonCategory());
    return new SystemTestIllegalRecordRowResponse(
        view.issueId(),
        view.issueIid(),
        buildIssueLink(view.sourceInstance(), view.projectId(), view.issueIid()),
        view.sourceInstance(),
        view.projectId(),
        view.projectName(),
        view.title(),
        view.issueState(),
        view.primaryPhaseLabel(),
        String.join(",", displayIllegalReasons(view)),
        IssueDisplayValueSupport.displaySeverityLevelOrBlank(view.severityLevel()),
        view.bugStatus(),
        view.category(),
        view.milestoneTitle(),
        view.priorityLevel(),
        view.delayCause(),
        view.authorName(),
        view.assigneeName(),
        String.join("&", displayModuleNames(view)),
        view.functionName(),
        view.fixUser(),
        causeFields.fixStatus(),
        causeFields.majorCause(),
        causeFields.secondCause(),
        causeFields.specificReason(),
        causeFields.modification(),
        causeFields.causedByOther(),
        causeFields.effectFunction(),
        causeFields.hasTested(),
        causeFields.potentialImpact(),
        causeFields.relationTableUpdated(),
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
        || TextQuerySupport.containsAbstractSearch(String.join(" ", displayModuleNames(view)), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.functionName(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.primaryPhaseLabel(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(String.join(" ", displayIllegalReasons(view)), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.authorName(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.assigneeName(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.bugStatus(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.category(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.milestoneTitle(), normalizedKeyword);
  }

  private boolean matchesDisplayModule(IssueFactRecord view, String moduleName) {
    String normalized = TextQuerySupport.trimToNull(moduleName);
    return normalized == null
        || displayModuleNames(view).stream()
            .anyMatch(item -> TextQuerySupport.equalsNormalized(item, normalized));
  }

  private boolean matchesTestingPhase(IssueFactRecord view, String testingPhase) {
    return phaseScopeResolver.matchesPhase(
        LEGACY_CROWN_CAD_PROJECT_ID, view.primaryPhaseLabel(), testingPhase);
  }

  private boolean matchesTestingPhase(IssueFactRecord view, List<String> testingPhases) {
    return phaseScopeResolver.matchesPhases(
        LEGACY_CROWN_CAD_PROJECT_ID, view.primaryPhaseLabel(), testingPhases);
  }

  private boolean matchesIllegalReason(IssueFactRecord view, String illegalReason) {
    String normalizedExpected = SystemTestIllegalReasonSupport.normalize(illegalReason);
    return normalizedExpected == null || displayIllegalReasons(view).contains(normalizedExpected);
  }

  private List<String> displayModuleNames(IssueFactRecord view) {
    if (displayIllegalReasons(view).contains(SystemTestIllegalReasonSupport.MISSING_MODULE)) {
      return List.of(SystemTestIllegalReasonSupport.MISSING_MODULE);
    }
    return view.moduleNames().isEmpty() ? List.of(SystemTestIllegalReasonSupport.MISSING_MODULE) : view.moduleNames();
  }

  private boolean matchesOldPlatformSystemTestExclusion(IssueFactRecord view) {
    if (containsText(view.category(), "功能屏蔽")
        || containsText(view.bugStatus(), "已拒绝")
        || containsText(view.category(), "建议")) {
      return false;
    }
    boolean closed = containsText(view.issueState(), "closed") || containsText(view.issueState(), "CLOSED");
    if (closed && containsText(view.bugStatus(), "申请否决")) {
      return false;
    }
    return !closed || !containsText(view.bugStatus(), "需求如此");
  }

  private static boolean containsText(String source, String token) {
    return source != null && token != null && source.contains(token);
  }

  private static List<String> displayIllegalReasons(IssueFactRecord view) {
    List<String> reasons =
        view.illegalReasons().isEmpty() ? List.of(view.illegalReason()) : view.illegalReasons();
    return reasons.stream()
        .map(SystemTestIllegalReasonSupport::normalize)
        .filter(StringUtils::hasText)
        .distinct()
        .toList();
  }

  private Long defaultProjectId(Long projectId) {
    return projectId == null ? LEGACY_CROWN_CAD_PROJECT_ID : projectId;
  }

  private List<String> resolvedRequestedPhaseOrWhitelist(String testingPhase) {
    String normalized = TextQuerySupport.trimToNull(testingPhase);
    if (normalized != null) {
      return phaseScopeResolver.resolvePhases(LEGACY_CROWN_CAD_PROJECT_ID, normalized);
    }
    return phaseScopeOptions().stream()
        .findFirst()
        .map(value -> phaseScopeResolver.resolvePhases(LEGACY_CROWN_CAD_PROJECT_ID, value))
        .orElse(List.of());
  }

  private List<String> phaseScopeOptions() {
    return phaseCatalogService.listParentNames(LEGACY_CROWN_CAD_PROJECT_ID);
  }

  private List<String> normalizedExistingIllegalReasons(List<String> rawReasons) {
    List<String> normalized =
        rawReasons.stream()
            .map(SystemTestIllegalReasonSupport::normalize)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    return SystemTestIllegalReasonSupport.SUPPORTED_REASONS.stream()
        .filter(normalized::contains)
        .toList();
  }

  private IssueFactRecordListRequest withLegacyDefaultProject(IssueFactRecordListRequest request) {
    return new IssueFactRecordListRequest(
        defaultProjectId(request.projectId()),
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

  private SystemTestIllegalRecordQueryRequest withSnapshotDefaults(SystemTestIllegalRecordQueryRequest request) {
    IssueFactRecordListRequest listRequest = withLegacyDefaultProject(request.listRequest());
    return new SystemTestIllegalRecordQueryRequest(
        listRequest,
        request.testingPhase(),
        request.illegalReason(),
        request.authorName(),
        request.assigneeName(),
        request.filterGroupJson());
  }

  private PageRecordSnapshotService.SnapshotRequest snapshotRequest(
      String snapshotType, String scopeKey, Object requestPayload) {
    return new PageRecordSnapshotService.SnapshotRequest(
        WORKSPACE_KEY,
        snapshotType,
        scopeKey,
        RULE_VERSION,
        pageRecordSnapshotService.issueFactSourceVersion(),
        requestPayload);
  }

  private String firstPhaseOption() {
    return phaseScopeOptions().stream().findFirst().orElse(null);
  }

  @Override
  protected List<StatisticRuleFlowStepSample> sample(List<IssueFactRecord> rows) {
    return rows.stream()
        .limit(3)
        .map(
            row ->
                new StatisticRuleFlowStepSample(
                    "#" + row.issueIid() + " " + row.projectName(),
                    row.title()
                        + (!displayIllegalReasons(row).isEmpty()
                            ? " | 非法类型: " + String.join(",", displayIllegalReasons(row))
                            : "")))
        .toList();
  }

  private static Map<String, Comparator<IssueFactRecord>> createSortComparators() {
    Map<String, Comparator<IssueFactRecord>> comparators = new LinkedHashMap<>();
    comparators.put("issueIid", SortSupport.nullableComparable(IssueFactRecord::issueIid));
    comparators.put("title", SortSupport.nullableString(IssueFactRecord::title));
    comparators.put("projectName", SortSupport.nullableString(IssueFactRecord::projectName));
    comparators.put(
        "moduleNames", SortSupport.nullableString(view -> String.join("&", view.moduleNames())));
    comparators.put("functionName", SortSupport.nullableString(IssueFactRecord::functionName));
    comparators.put("testingPhase", SortSupport.nullableString(IssueFactRecord::primaryPhaseLabel));
    comparators.put(
        "illegalReason",
        SortSupport.nullableString(view -> String.join(",", displayIllegalReasons(view))));
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
