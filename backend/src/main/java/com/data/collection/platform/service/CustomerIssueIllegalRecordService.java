package com.data.collection.platform.service;

import com.data.collection.platform.entity.CustomerIssueIllegalRecordFilterOptionsResponse;
import com.data.collection.platform.entity.CustomerIssueIllegalRecordListResponse;
import com.data.collection.platform.entity.CustomerIssueIllegalRecordRowResponse;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.ProjectionScopeType;
import com.data.collection.platform.entity.labelgroup.LabelGroupExpansionResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStepSample;
import com.data.collection.platform.entity.statistics.StatisticRuleMetricDefinition;
import com.data.collection.platform.service.labelgroup.LabelGroupExpansionService;
import com.data.collection.platform.service.statistics.CustomerIssueMilestoneCatalogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CustomerIssueIllegalRecordService extends AbstractIssueFactRecordListService
    implements PageRecordSnapshotRefresher {
  private static final String WORKSPACE_KEY = "customer-issue-illegal-records";
  private static final String PAGE_KEY = "customer-issues-illegal-records";
  private static final String RULE_VERSION = "customer-issue-illegal-records@2026-07-22-v3";
  private static final String DEFAULT_SORT_FIELD = "updatedAt";
  private static final int EXPORT_PAGE_SIZE = 100;
  private static final int MAX_LABEL_GROUP_FILTER_VALUES = 200;
  private static final long LEGACY_CC_PRODUCT_PROJECT_ID = 325L;
  private static final Map<String, String> LABEL_GROUP_FIELD_VALUE_TYPES =
      Map.ofEntries(
          Map.entry("title", "STRING"),
          Map.entry("moduleName", "STRING"),
          Map.entry("priorityLevel", "STRING"),
          Map.entry("bugStatus", "STRING"),
          Map.entry("assigneeName", "STRING"),
          Map.entry("milestoneTitle", "STRING"));
  private static final Map<String, Comparator<IssueFactRecord>> SORT_COMPARATORS =
      createSortComparators();

  private final CustomerIssueScopeProfile customerIssueScopeProfile;
  private final ObjectMapper objectMapper;
  private final LabelGroupExpansionService labelGroupExpansionService;
  private final FactBuildService factBuildService;
  private final CustomerIssueMilestoneCatalogService milestoneCatalogService;
  private final PageRecordSnapshotService pageRecordSnapshotService;

  @Autowired
  public CustomerIssueIllegalRecordService(
      IssueFactRecordRepository issueFactRecordRepository,
      CustomerIssueScopeProfile customerIssueScopeProfile,
      ObjectMapper objectMapper,
      GitlabResourceLinkService issueLinkService,
      LabelGroupExpansionService labelGroupExpansionService,
      FactBuildService factBuildService,
      CustomerIssueMilestoneCatalogService milestoneCatalogService,
      PageRecordSnapshotService pageRecordSnapshotService) {
    super(issueFactRecordRepository, issueLinkService);
    this.customerIssueScopeProfile = customerIssueScopeProfile;
    this.objectMapper = objectMapper;
    this.labelGroupExpansionService = labelGroupExpansionService;
    this.factBuildService = factBuildService;
    this.milestoneCatalogService = milestoneCatalogService;
    this.pageRecordSnapshotService = pageRecordSnapshotService;
  }

  public CustomerIssueIllegalRecordService(
      IssueFactRecordRepository issueFactRecordRepository,
      CustomerIssueScopeProfile customerIssueScopeProfile,
      ObjectMapper objectMapper,
      GitlabResourceLinkService issueLinkService,
      LabelGroupExpansionService labelGroupExpansionService,
      FactBuildService factBuildService) {
    this(
        issueFactRecordRepository,
        customerIssueScopeProfile,
        objectMapper,
        issueLinkService,
        labelGroupExpansionService,
        factBuildService,
        null,
        null);
  }

  public CustomerIssueIllegalRecordListResponse listRecords(
      CustomerIssueIllegalRecordQueryRequest request) {
    CustomerIssueIllegalRecordQueryRequest safeRequest = withSnapshotDefaults(request);
    if (pageRecordSnapshotService == null) {
      return loadRecords(safeRequest);
    }
    return pageRecordSnapshotService.readOrRefresh(
        snapshotRequest(
            PageRecordSnapshotService.SNAPSHOT_TYPE_LIST,
            "project:" + LEGACY_CC_PRODUCT_PROJECT_ID,
            safeRequest.listRequest().sourceInstance(),
            safeRequest.listRequest().milestoneTitle(),
            safeRequest),
        CustomerIssueIllegalRecordListResponse.class,
        () -> loadRecords(safeRequest));
  }

  private CustomerIssueIllegalRecordListResponse loadRecords(
      CustomerIssueIllegalRecordQueryRequest request) {
    String selectedMilestone =
        TextQuerySupport.trimToNull(request.listRequest().milestoneTitle());
    List<String> milestoneValues =
        selectedMilestone == null
            ? List.of()
            : milestoneCatalogService.resolveMilestoneValues(selectedMilestone);
    IssueFactRecordListRequest listRequest = withLegacyDefaultProject(request.listRequest());
    int safePage = normalizePage(listRequest.page());
    int safeSize = normalizeSize(listRequest.size());
    String safeSortField =
        normalizeSortField(listRequest.sortField(), DEFAULT_SORT_FIELD, SORT_COMPARATORS.keySet());
    String safeSortOrder = normalizeSortOrder(listRequest.sortOrder());
    StatisticFilterGroup filterGroup =
        IssueFactRecordFilterGroupSupport.parse(
            objectMapper,
            request.filterGroupJson(),
            IssueFactRecordFilterGroupSupport.CUSTOMER_ISSUE_FILTER_OPERATORS);
    StatisticFilterGroup expandedFilterGroup = expandLabelGroupConditions(filterGroup, listRequest.sourceInstance());
    boolean hasLabelGroupFilters = IssueFactRecordFilterGroupSupport.hasLabelGroupConditions(expandedFilterGroup);

    if (!hasLabelGroupFilters
        && !StringUtils.hasText(request.illegalReason())
        && canUseSqlPage(listRequest, request.filterGroupJson(), safeSortField)) {
      PageSlice<IssueFactRecord> pageSlice =
          loadFactPage(
              new IssueFactRecordPageQuery(
                  IssueFactRecordPageQuery.Scope.CUSTOMER,
                  listRequest,
                  expandedFilterGroup,
                  null,
                  request.illegalReason(),
                  null,
                  milestoneValues,
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
                  false,
                  true,
                  false,
                  false,
                  safePage,
                  safeSize,
                  safeSortField,
                  safeSortOrder,
                  null));
      List<CustomerIssueIllegalRecordRowResponse> records =
          pageSlice.records().stream().map(this::toResponse).toList();
      return new CustomerIssueIllegalRecordListResponse(
          records, pageSlice.total(), pageSlice.page(), pageSlice.size(), safeSortField, safeSortOrder);
    }

    List<IssueFactRecord> filtered =
        applyBaseFilters(
                loadScopedViews(listRequest.projectId()),
                listRequest,
                view -> matchesKeyword(view, listRequest.keyword()))
            .stream()
            .filter(IssueFactRecord::illegal)
            .filter(this::hasSupportedCustomerIllegalReason)
            .filter(view -> matchesIllegalReason(view, request.illegalReason()))
            .filter(view -> matchesEquals(view.authorName(), request.authorName()))
            .filter(view -> matchesEquals(view.assigneeName(), request.assigneeName()))
            .filter(
                view ->
                    selectedMilestone == null
                        || milestoneCatalogService.matches(
                            selectedMilestone, view.milestoneTitle()))
            .filter(view -> IssueFactRecordFilterGroupSupport.matches(view, expandedFilterGroup))
            .sorted(applySortDirection(SORT_COMPARATORS.get(safeSortField), safeSortOrder))
            .toList();

    PageSlice<IssueFactRecord> pageSlice = PageSliceSupport.slice(filtered, safePage, safeSize);
    List<CustomerIssueIllegalRecordRowResponse> records =
        pageSlice.records().stream().map(this::toResponse).toList();
    return new CustomerIssueIllegalRecordListResponse(
        records, pageSlice.total(), pageSlice.page(), pageSlice.size(), safeSortField, safeSortOrder);
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

  public byte[] exportRecordsWorkbook(CustomerIssueIllegalRecordQueryRequest request) {
    List<CustomerIssueIllegalRecordRowResponse> rows = new ArrayList<>();
    int page = 1;
    while (true) {
      IssueFactRecordListRequest listRequest = withLegacyDefaultProject(request.listRequest());
      CustomerIssueIllegalRecordQueryRequest pageRequest =
          new CustomerIssueIllegalRecordQueryRequest(
              new IssueFactRecordListRequest(
                  listRequest.projectId(),
                  listRequest.keyword(),
                  null,
                  listRequest.issueIid(),
                  listRequest.title(),
                  listRequest.projectName(),
                  listRequest.moduleName(),
                  null,
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
              request.illegalReason(),
              request.testingPhase(),
              request.authorName(),
              request.assigneeName(),
              request.filterGroupJson());
      CustomerIssueIllegalRecordListResponse response = listRecords(pageRequest);
      rows.addAll(response.records());
      if (response.records().size() < EXPORT_PAGE_SIZE || rows.size() >= response.total()) {
        break;
      }
      page += 1;
    }
    return CustomerIssueRecordWorkbookExportSupport.exportIllegalRecords(rows);
  }

  public CustomerIssueIllegalRecordFilterOptionsResponse getFilterOptions(Long projectId) {
    Map<String, Object> requestPayload = Map.of("projectId", LEGACY_CC_PRODUCT_PROJECT_ID);
    if (pageRecordSnapshotService == null) {
      return loadFilterOptions();
    }
    return pageRecordSnapshotService.readOrRefresh(
        snapshotRequest(
            PageRecordSnapshotService.SNAPSHOT_TYPE_FILTER_OPTIONS,
            "project:" + LEGACY_CC_PRODUCT_PROJECT_ID,
            null,
            null,
            requestPayload),
        CustomerIssueIllegalRecordFilterOptionsResponse.class,
        this::loadFilterOptions);
  }

  private CustomerIssueIllegalRecordFilterOptionsResponse loadFilterOptions() {
    IssueFactRecordRepository.CustomerIssueFilterValues values =
        issueFactRecordRepository.findCustomerIssueIllegalFilterValues(null);
    List<String> illegalReasons = new ArrayList<>(CustomerIssueIllegalReasonSupport.SUPPORTED_REASONS);
    values.illegalReasons().stream()
        .map(CustomerIssueIllegalReasonSupport::normalize)
        .filter(StringUtils::hasText)
        .filter(reason -> !illegalReasons.contains(reason))
        .forEach(illegalReasons::add);
    return new CustomerIssueIllegalRecordFilterOptionsResponse(
        toLegacyOptions(values.projectNames()),
        toLegacyOptions(values.moduleNames()),
        toLegacyOptions(values.functionNames()),
        toOptions(illegalReasons),
        OptionItemResponseFactory.fromValues(
            values.severityLevels(),
            TextQuerySupport::trimToNull,
            IssueDisplayValueSupport::displaySeverityLevel),
        toOptions(values.priorityLevels()),
        toOptions(values.issueStates()),
        OptionItemResponseFactory.fromIssueStatusMembers(values.bugStatuses()),
        toOptions(values.categories()),
        toOptions(values.authorNames()),
        toOptions(values.assigneeNames()),
        milestoneCatalogService.listOptions());
  }

  @Override
  public void refreshRecordSnapshots(com.data.collection.platform.entity.FactPublicationContext context) {
    if (pageRecordSnapshotService == null
        || context == null
        || !context.covers(
            FactType.ISSUE,
            ProjectionScopeType.PROJECT,
            FactProjectionScopeKeyCodec.project(LEGACY_CC_PRODUCT_PROJECT_ID))) {
      return;
    }
    getFilterOptions(LEGACY_CC_PRODUCT_PROJECT_ID);
    listRecords(defaultRequest());
  }

  public CustomerIssueIllegalRecordRowResponse refreshSingleRecord(
      String sourceInstance, Long projectId, Long issueIid) {
    Long safeProjectId = defaultProjectId(projectId);
    factBuildService.rebuildIssueFactByIid(sourceInstance, safeProjectId, issueIid);
    if (pageRecordSnapshotService != null) {
      pageRecordSnapshotService.invalidatePage(WORKSPACE_KEY);
    }
    String normalizedSource = GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance);
    return loadScopedViews(safeProjectId).stream()
        .filter(IssueFactRecord::illegal)
        .filter(this::hasSupportedCustomerIllegalReason)
        .filter(row -> row.issueIid() != null && row.issueIid().longValue() == issueIid)
        .filter(row -> GitlabSourceInstanceSupport.normalizeSourceInstance(row.sourceInstance()).equals(normalizedSource))
        .findFirst()
        .map(this::toResponse)
        .orElse(null);
  }

  public StatisticBoardRuleExplanationResponse getRuleExplanation(Long projectId) {
    List<IssueFactRecord> loaded = loadFacts(defaultProjectId(projectId));
    List<IssueFactRecord> scoped = scopeCustomerIssues(loaded);
    List<IssueFactRecord> visible = scoped.stream().filter(view -> !view.excluded()).toList();
    List<IssueFactRecord> illegal =
        visible.stream().filter(IssueFactRecord::illegal).filter(this::hasSupportedCustomerIllegalReason).toList();
    return new StatisticBoardRuleExplanationResponse(
        WORKSPACE_KEY,
        true,
        "客户问题缺陷非法数据规则说明",
        RULE_VERSION,
        "当前页面展示客户问题范围内命中非法规则的缺陷。平台会先限定客户问题范围，再剔除不应参与统计的无效议题。",
        "客户问题非法数据复用系统测试非法规则，并追加客户问题专用要求：缺陷调研模板填写完整、计划解决时间合法、一级缺陷需要负责人签字确认。",
        List.of(
            step("source-load", "加载议题数据", "加载已同步到平台的客户问题议题数据，并使用整理后的里程碑、模块、严重程度和非法类型。", loaded, loaded.size()),
            step("scope-filter", "限定客户问题范围", "保留 CC_Product 客户问题范围内的议题，避免和系统测试口径混在一起。", scoped, loaded.size()),
            step("exclude-filter", "剔除排除数据", "按老平台 CC_Product 记录页查询口径，排除已关闭的申请否决、需求如此和设计如此类数据。", visible, scoped.size()),
            step("illegal-filter", "筛出非法数据", "保留命中客户问题非法判定规则的缺陷。", illegal, visible.size())),
        List.of(
            new StatisticRuleMetricDefinition(
                "illegal-total",
                "非法数据总数",
                "客户问题范围内命中任意非法规则的议题数量。",
                "非法数据总数 = 客户问题范围内命中任意非法规则的议题数量",
                null),
            new StatisticRuleMetricDefinition(
                "base-illegal",
                "基础非法类型",
                "复用系统测试非法数据规则：未设定严重程度、未设定模块、已修复但未按模板回复、缺陷原因不唯一。",
                "基础非法类型 = 命中系统测试非法规则的非法类型",
                null),
            new StatisticRuleMetricDefinition(
                "research-template",
                "缺陷调研模板",
                "客户问题必须按要求填写缺陷调研模板；除计划解决时间和一级缺陷负责人签字项外，其余问题需要有回复内容。",
                "未按照要求填写缺陷调研模板 = 调研模板必填项缺失或回复内容不完整",
                null),
            new StatisticRuleMetricDefinition(
                "plan-solution-time",
                "计划解决时间",
                "计划解决时间有且只能填写一个日期时间戳，日期分隔符按规则总表第 5.4 兼容。",
                "计划解决时间非法 = 调研模板计划解决时间为空、无法解析或出现多个日期",
                null),
            new StatisticRuleMetricDefinition(
                "owner-signature",
                "一级缺陷负责人签字",
                "一级缺陷必须有模块负责人签字确认；二级、三级缺陷不要求负责人签字。",
                "负责人签字非法 = 一级缺陷缺少负责人签字确认",
                null)),
        null);
  }

  private List<IssueFactRecord> loadScopedViews(Long projectId) {
    return scopeVisibleCustomerIssues(loadFacts(projectId));
  }

  private Long defaultProjectId(Long projectId) {
    return LEGACY_CC_PRODUCT_PROJECT_ID;
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
        null,
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

  private CustomerIssueIllegalRecordQueryRequest withSnapshotDefaults(
      CustomerIssueIllegalRecordQueryRequest request) {
    return new CustomerIssueIllegalRecordQueryRequest(
        withLegacyDefaultProject(request.listRequest()),
        request.illegalReason(),
        request.testingPhase(),
        request.authorName(),
        request.assigneeName(),
        request.filterGroupJson());
  }

  private CustomerIssueIllegalRecordQueryRequest defaultRequest() {
    return new CustomerIssueIllegalRecordQueryRequest(
        new IssueFactRecordListRequest(
            LEGACY_CC_PRODUCT_PROJECT_ID,
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
        null,
        null);
  }

  private PageRecordSnapshotService.SnapshotRequest snapshotRequest(
      String snapshotType,
      String scopeKey,
      String sourceInstance,
      String milestoneBusinessKey,
      Object requestPayload) {
    return new PageRecordSnapshotService.SnapshotRequest(
        WORKSPACE_KEY,
        snapshotType,
        scopeKey,
        RULE_VERSION,
        pageRecordSnapshotService.issueFactSourceVersion(
            sourceInstance,
            LEGACY_CC_PRODUCT_PROJECT_ID,
            IssueScopeDimension.MILESTONE,
            milestoneBusinessKey),
        requestPayload);
  }

  private List<IssueFactRecord> scopeCustomerIssues(List<IssueFactRecord> rows) {
    return rows.stream()
        .filter(view -> customerIssueScopeProfile.matches(view.scopeContext()))
        .toList();
  }

  private List<IssueFactRecord> scopeVisibleCustomerIssues(List<IssueFactRecord> rows) {
    return scopeCustomerIssues(rows).stream().filter(view -> !view.excluded()).toList();
  }

  private CustomerIssueIllegalRecordRowResponse toResponse(IssueFactRecord view) {
    return new CustomerIssueIllegalRecordRowResponse(
        view.issueId(),
        view.issueIid(),
        buildIssueLink(view.sourceInstance(), view.projectId(), view.issueIid()),
        view.sourceInstance(),
        view.projectId(),
        view.projectName(),
        view.title(),
        view.issueState(),
        CustomerIssuePhaseSupport.displayPhase(view),
        displayIllegalReason(view),
        IssueDisplayValueSupport.displaySeverityLevelOrBlank(view.severityLevel()),
        view.priorityLevel(),
        view.bugStatus(),
        view.category(),
        view.milestoneTitle(),
        view.authorName(),
        view.assigneeName(),
        String.join("、", view.moduleNames()),
        view.functionName(),
        view.delayReason(),
        view.delayCause(),
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
        || TextQuerySupport.containsAbstractSearch(displayIllegalReason(view), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.functionName(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.delayCause(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.authorName(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.assigneeName(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.milestoneTitle(), normalizedKeyword);
  }

  private boolean matchesIllegalReason(IssueFactRecord view, String illegalReason) {
    String normalized = TextQuerySupport.trimToNull(illegalReason);
    return normalized == null
        || displayIllegalReasons(view).stream()
            .anyMatch(reason -> CustomerIssueIllegalReasonSupport.matches(reason, normalized));
  }

  private boolean hasSupportedCustomerIllegalReason(IssueFactRecord view) {
    return displayIllegalReasons(view).stream()
        .map(CustomerIssueIllegalReasonSupport::normalize)
        .anyMatch(StringUtils::hasText);
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
                        + (StringUtils.hasText(displayIllegalReason(row))
                            ? " | 非法类型: " + displayIllegalReason(row)
                            : "")))
        .toList();
  }

  private static String displayIllegalReason(IssueFactRecord view) {
    return String.join(",", displayIllegalReasons(view));
  }

  private static List<String> displayIllegalReasons(IssueFactRecord view) {
    List<String> reasons = view.illegalReasons().isEmpty() ? List.of(view.illegalReason()) : view.illegalReasons();
    return reasons.stream().filter(StringUtils::hasText).distinct().toList();
  }

  private static Map<String, Comparator<IssueFactRecord>> createSortComparators() {
    Map<String, Comparator<IssueFactRecord>> comparators = new LinkedHashMap<>();
    comparators.put("issueIid", SortSupport.nullableComparable(IssueFactRecord::issueIid));
    comparators.put("title", SortSupport.nullableString(IssueFactRecord::title));
    comparators.put("projectName", SortSupport.nullableString(IssueFactRecord::projectName));
    comparators.put(
        "moduleNames", SortSupport.nullableString(view -> String.join("、", view.moduleNames())));
    comparators.put("illegalReason", SortSupport.nullableString(IssueFactRecord::illegalReason));
    comparators.put("severityLevel", SortSupport.nullableString(IssueFactRecord::severityLevel));
    comparators.put("priorityLevel", SortSupport.nullableString(IssueFactRecord::priorityLevel));
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
