package com.data.collection.platform.service;

import com.data.collection.platform.entity.CustomerIssueRecordFilterOptionsResponse;
import com.data.collection.platform.entity.CustomerIssueRecordListResponse;
import com.data.collection.platform.entity.CustomerIssueRecordRowResponse;
import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupExpansionResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.entity.statistics.StatisticRuleMetricDefinition;
import com.data.collection.platform.service.labelgroup.LabelGroupExpansionService;
import com.data.collection.platform.service.statistics.CustomerIssueMilestoneCatalogService;
import com.data.collection.platform.service.statistics.CustomerIssueMilestoneOrdering;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CustomerIssueRecordService extends AbstractIssueFactRecordListService
    implements PageRecordSnapshotRefresher {
  private static final String TOPIC_CC_PRODUCT = "cc-product";
  private static final String TOPIC_DELAY = "delay";
  private static final String PAGE_KEY = "customer-issues-cc-product-issues";
  private static final String CC_PRODUCT_RULE_VERSION = "customer-issue-records@2026-07-22-v7";
  private static final String DELAY_RULE_VERSION = "customer-issue-records@2026-07-22-v4";
  private static final String DEFAULT_SORT_FIELD = "updatedAt";
  private static final long LEGACY_CC_PRODUCT_PROJECT_ID = CustomerIssueScopeProfile.LEGACY_CC_PRODUCT_PROJECT_ID;
  private static final int EXPORT_PAGE_SIZE = 100;
  private static final int MAX_LABEL_GROUP_FILTER_VALUES = 200;
  private static final Map<String, String> LABEL_GROUP_FIELD_VALUE_TYPES =
      Map.ofEntries(
          Map.entry("title", "STRING"),
          Map.entry("moduleName", "STRING"),
          Map.entry("functionName", "STRING"),
          Map.entry("priorityLevel", "STRING"),
          Map.entry("bugStatus", "STRING"),
          Map.entry("authorName", "STRING"),
          Map.entry("handlerName", "STRING"),
          Map.entry("assigneeName", "STRING"),
          Map.entry("milestoneTitle", "STRING"));
  private static final Map<String, Comparator<IssueFactRecord>> SORT_COMPARATORS =
      createSortComparators();

  private final CustomerIssueScopeProfile customerIssueScopeProfile;
  private final ObjectMapper objectMapper;
  private final LabelGroupExpansionService labelGroupExpansionService;
  private final CustomerIssueMilestoneCatalogService milestoneCatalogService;
  private final PageRecordSnapshotService pageRecordSnapshotService;

  @Autowired
  public CustomerIssueRecordService(
      IssueFactRecordRepository issueFactRecordRepository,
      CustomerIssueScopeProfile customerIssueScopeProfile,
      ObjectMapper objectMapper,
      GitlabResourceLinkService issueLinkService,
      LabelGroupExpansionService labelGroupExpansionService,
      CustomerIssueMilestoneCatalogService milestoneCatalogService,
      PageRecordSnapshotService pageRecordSnapshotService) {
    super(issueFactRecordRepository, issueLinkService);
    this.customerIssueScopeProfile = customerIssueScopeProfile;
    this.objectMapper = objectMapper;
    this.labelGroupExpansionService = labelGroupExpansionService;
    this.milestoneCatalogService = milestoneCatalogService;
    this.pageRecordSnapshotService = pageRecordSnapshotService;
  }

  public CustomerIssueRecordService(
      IssueFactRecordRepository issueFactRecordRepository,
      CustomerIssueScopeProfile customerIssueScopeProfile,
      ObjectMapper objectMapper,
      GitlabResourceLinkService issueLinkService,
      LabelGroupExpansionService labelGroupExpansionService) {
    this(
        issueFactRecordRepository,
        customerIssueScopeProfile,
        objectMapper,
        issueLinkService,
        labelGroupExpansionService,
        null,
        null);
  }

  public CustomerIssueRecordListResponse listRecords(CustomerIssueRecordQueryRequest request) {
    CustomerIssueRecordPageSnapshot snapshot = readRecordSnapshot(withSnapshotDefaults(request));
    return toListResponse(snapshot, LocalDateTime.now(java.time.ZoneOffset.UTC));
  }

  private CustomerIssueRecordPageSnapshot readRecordSnapshot(CustomerIssueRecordQueryRequest safeRequest) {
    if (pageRecordSnapshotService == null) {
      return loadRecords(safeRequest);
    }
    return pageRecordSnapshotService.readOrRefresh(
        snapshotRequest(
            PageRecordSnapshotService.SNAPSHOT_TYPE_LIST,
            "topic:" + normalizeTopic(safeRequest.topic()),
            CustomerIssueRecordProfile.forTopic(normalizeTopic(safeRequest.topic())).ruleVersion(),
            safeRequest),
        CustomerIssueRecordPageSnapshot.class,
        () -> loadRecords(safeRequest));
  }

  private CustomerIssueRecordPageSnapshot loadRecords(CustomerIssueRecordQueryRequest request) {
    IssueFactRecordListRequest listRequest = withCustomerProject(request.listRequest());
    int safePage = normalizePage(listRequest.page());
    int safeSize = normalizeSize(listRequest.size());
    String safeSortField =
        normalizeSortField(listRequest.sortField(), DEFAULT_SORT_FIELD, SORT_COMPARATORS.keySet());
    String safeSortOrder = normalizeSortOrder(listRequest.sortOrder());
    String safeTopic = normalizeTopic(request.topic());
    CustomerIssueRecordProfile recordProfile = CustomerIssueRecordProfile.forTopic(safeTopic);
    StatisticFilterGroup filterGroup =
        IssueFactRecordFilterGroupSupport.parse(
            objectMapper,
            request.filterGroupJson(),
            recordProfile.supportsCustomerFields()
                ? IssueFactRecordFilterGroupSupport.CUSTOMER_ISSUE_RECORD_FILTER_OPERATORS
                : IssueFactRecordFilterGroupSupport.CUSTOMER_ISSUE_FILTER_OPERATORS);
    StatisticFilterGroup expandedFilterGroup = expandLabelGroupConditions(filterGroup, listRequest.sourceInstance());
    boolean hasLabelGroupFilters = IssueFactRecordFilterGroupSupport.hasLabelGroupConditions(expandedFilterGroup);
    String customerName = recordProfile.supportsCustomerFields() ? request.customerName() : null;

    if (!hasLabelGroupFilters && canUseSqlPage(listRequest, request.filterGroupJson(), safeSortField)) {
      PageSlice<IssueFactRecord> pageSlice =
          loadFactPage(
              new IssueFactRecordPageQuery(
                  recordProfile.pageScope(),
                  listRequest,
                  expandedFilterGroup,
                  request.reasonCategory(),
                  null,
                  null,
                   List.of(),
                   request.authorName(),
                   request.handlerName(),
                   request.assigneeName(),
                   request.testingPhase(),
                   request.fixUser(),
                   request.delayCause(),
                   recordProfile.delayOnly(),
                  false,
                  recordProfile.excludeExcluded(),
                  recordProfile.excludeRejectedBugStatus(),
                   false,
                   false,
                   false,
                   true,
                  safePage,
                  safeSize,
                  safeSortField,
                  safeSortOrder,
                  customerName));
      return new CustomerIssueRecordPageSnapshot(
          pageSlice.records(),
          pageSlice.total(),
          pageSlice.page(),
          pageSlice.size(),
          safeSortField,
          safeSortOrder);
    }

    List<IssueFactRecord> filtered =
        applyBaseFilters(
                loadTopicScopedViews(recordProfile, listRequest.projectId()),
                listRequest,
                view -> matchesKeyword(view, listRequest.keyword()))
            .stream()
            .filter(view -> matchesEquals(view.reasonCategory(), request.reasonCategory()))
            .filter(view -> matchesEquals(view.authorName(), request.authorName()))
            .filter(view -> matchesEquals(view.handlerName(), request.handlerName()))
            .filter(view -> matchesEquals(view.assigneeName(), request.assigneeName()))
            .filter(view -> CustomerIssueTestingPhaseSupport.matchesFilter(
                view.testingPhase(), request.testingPhase()))
            .filter(view -> matchesEquals(view.fixUser(), request.fixUser()))
            .filter(view -> matchesEquals(view.delayCause(), request.delayCause()))
            .filter(view -> matchesCustomerName(view.customerNames(), customerName))
            .filter(view -> IssueFactRecordFilterGroupSupport.matchesCustomerIssue(view, expandedFilterGroup))
            .sorted(applySortDirection(SORT_COMPARATORS.get(safeSortField), safeSortOrder))
            .toList();

    PageSlice<IssueFactRecord> pageSlice = PageSliceSupport.slice(filtered, safePage, safeSize);
    return new CustomerIssueRecordPageSnapshot(
        pageSlice.records(),
        pageSlice.total(),
        pageSlice.page(),
        pageSlice.size(),
        safeSortField,
        safeSortOrder);
  }

  private CustomerIssueRecordListResponse toListResponse(
      CustomerIssueRecordPageSnapshot snapshot, LocalDateTime asOf) {
    return new CustomerIssueRecordListResponse(
        snapshot.records().stream().map(view -> toResponse(view, asOf)).toList(),
        snapshot.total(),
        snapshot.page(),
        snapshot.size(),
        snapshot.sortField(),
        snapshot.sortOrder());
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

  public byte[] exportRecordsWorkbook(CustomerIssueRecordQueryRequest request) {
    List<CustomerIssueRecordRowResponse> rows = new ArrayList<>();
    LocalDateTime asOf = LocalDateTime.now(java.time.ZoneOffset.UTC);
    int page = 1;
    while (true) {
      IssueFactRecordListRequest listRequest = withCustomerProject(request.listRequest());
      CustomerIssueRecordQueryRequest pageRequest =
          new CustomerIssueRecordQueryRequest(
              request.topic(),
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
               request.reasonCategory(),
               request.authorName(),
               request.handlerName(),
               request.assigneeName(),
               request.testingPhase(),
               request.fixUser(),
               request.delayCause(),
               request.filterGroupJson(),
               request.customerName());
      CustomerIssueRecordListResponse response =
          toListResponse(readRecordSnapshot(withSnapshotDefaults(pageRequest)), asOf);
      rows.addAll(response.records());
      if (response.records().size() < EXPORT_PAGE_SIZE || rows.size() >= response.total()) {
        break;
      }
      page += 1;
    }
    CustomerIssueRecordProfile profile =
        CustomerIssueRecordProfile.forTopic(normalizeTopic(request.topic()));
    return CustomerIssueRecordWorkbookExportSupport.exportRecords(rows, profile.workbookLayout());
  }

  public CustomerIssueRecordFilterOptionsResponse getFilterOptions(String topic, Long projectId) {
    return getFilterOptions(topic, projectId, null);
  }

  public CustomerIssueRecordFilterOptionsResponse getFilterOptions(
      String topic, Long projectId, String sourceInstance) {
    String safeTopic = normalizeTopic(topic);
    Map<String, Object> requestPayload =
        new LinkedHashMap<>(
            Map.of(
                "topic", safeTopic,
                "projectId", LEGACY_CC_PRODUCT_PROJECT_ID));
    if (TextQuerySupport.trimToNull(sourceInstance) != null) {
      requestPayload.put("sourceInstance", TextQuerySupport.trimToNull(sourceInstance));
    }
    if (pageRecordSnapshotService == null) {
      return withSortedMilestoneOptions(loadFilterOptions(safeTopic, sourceInstance));
    }
    return withSortedMilestoneOptions(pageRecordSnapshotService.readOrRefresh(
        snapshotRequest(
            PageRecordSnapshotService.SNAPSHOT_TYPE_FILTER_OPTIONS,
            "topic:" + safeTopic,
            CustomerIssueRecordProfile.forTopic(safeTopic).ruleVersion(),
            requestPayload),
        CustomerIssueRecordFilterOptionsResponse.class,
        () -> loadFilterOptions(safeTopic, sourceInstance)));
  }

  private CustomerIssueRecordFilterOptionsResponse loadFilterOptions(String topic, String sourceInstance) {
    CustomerIssueRecordProfile profile = CustomerIssueRecordProfile.forTopic(normalizeTopic(topic));
    IssueFactRecordRepository.CustomerIssueFilterValues values =
        issueFactRecordRepository.findCustomerIssueRecordFilterValues(
            profile.scope() == CustomerIssueRecordScope.CUSTOMER_OPERATIONS,
            profile.delayOnly(),
            profile.excludeExcluded(),
            profile.excludeRejectedBugStatus(),
            sourceInstance);
    return new CustomerIssueRecordFilterOptionsResponse(
        toLegacyOptions(values.projectNames()),
        toLegacyOptions(values.moduleNames()),
        toLegacyOptions(values.functionNames()),
        profile.supportsCustomerFields() ? toOptions(values.customerNames()) : List.of(),
        toOptions(values.reasonCategories()),
        OptionItemResponseFactory.fromValues(
            values.severityLevels(),
            TextQuerySupport::trimToNull,
            IssueDisplayValueSupport::displaySeverityLevel),
        toOptions(values.priorityLevels()),
        toOptions(values.issueStates()),
        OptionItemResponseFactory.fromIssueStatusMembers(values.bugStatuses()),
        toOptions(values.categories()),
        toLegacyOptions(values.authorNames()),
        toLegacyOptions(values.handlerNames()),
        toLegacyOptions(values.assigneeNames()),
        toOptions(values.testingPhases()),
        toLegacyOptions(values.fixUsers()),
        toOptions(values.delayCauses()),
        toOptionsPreservingOrder(customerIssueMilestones(values.milestoneTitles())));
  }

  private CustomerIssueRecordFilterOptionsResponse withSortedMilestoneOptions(
      CustomerIssueRecordFilterOptionsResponse response) {
    return new CustomerIssueRecordFilterOptionsResponse(
        response.projectNames(),
        response.moduleNames(),
        response.functionNames(),
        response.customerNames(),
        response.reasonCategories(),
        response.severityLevels(),
        response.priorityLevels(),
        response.issueStates(),
        response.bugStatuses(),
        response.categories(),
        response.authorNames(),
        response.handlerNames(),
        response.assigneeNames(),
        response.testingPhases(),
        response.fixUsers(),
        response.delayCauses(),
        sortedMilestoneOptions(response.milestoneTitles()));
  }

  private List<OptionItemResponse> sortedMilestoneOptions(List<OptionItemResponse> options) {
    if (options == null || options.isEmpty()) {
      return List.of();
    }
    Map<String, OptionItemResponse> byValue = new LinkedHashMap<>();
    for (OptionItemResponse option : options) {
      String value = TextQuerySupport.trimToNull(option == null ? null : option.value());
      if (value == null) {
        continue;
      }
      String label = TextQuerySupport.trimToNull(option.label());
      byValue.putIfAbsent(value, new OptionItemResponse(label == null ? value : label, value));
    }
    return CustomerIssueMilestoneOrdering.sortLatestFirst(byValue.keySet()).stream()
        .map(byValue::get)
        .toList();
  }

  @Override
  public void refreshRecordSnapshots(PageRecordSnapshotRefresher.RefreshContext context) {
    if (!context.affectsIssues() || pageRecordSnapshotService == null) {
      return;
    }
    for (String topic : List.of(TOPIC_CC_PRODUCT, TOPIC_DELAY)) {
      getFilterOptions(topic, LEGACY_CC_PRODUCT_PROJECT_ID);
      listRecords(defaultRequest(topic));
    }
  }

  public StatisticBoardRuleExplanationResponse getRuleExplanation(String topic, Long projectId) {
    String safeTopic = normalizeTopic(topic);
    List<IssueFactRecord> loaded = loadFacts(LEGACY_CC_PRODUCT_PROJECT_ID);
    CustomerIssueRecordProfile recordProfile = CustomerIssueRecordProfile.forTopic(safeTopic);
    List<IssueFactRecord> scoped = scopeCustomerIssues(loaded, recordProfile);
    List<IssueFactRecord> visible = applyRecordProfile(scoped, recordProfile);
    List<IssueFactRecord> topicScoped = applyTopic(visible, recordProfile);
    return new StatisticBoardRuleExplanationResponse(
        "customer-issue-" + safeTopic + "-records",
        true,
        topicTitle(safeTopic) + "规则说明",
        recordProfile.ruleVersion(),
        "当前页面展示客户问题范围内的议题记录，并按当前专题继续收敛范围。",
        topicSummary(safeTopic),
        List.of(
            step("source-load", "加载议题数据", "加载已同步到平台的客户问题议题数据，并使用整理后的里程碑、模块、负责人和处理状态。", loaded, loaded.size()),
            step("scope-filter", "限定客户问题范围", "保留 CC_Product 客户问题范围内的议题，避免和系统测试口径混在一起。", scoped, loaded.size()),
            step("exclude-filter", "剔除排除数据", recordProfile.explanation(), visible, scoped.size()),
            step("topic-filter", topicTitle(safeTopic), topicFilterDescription(safeTopic), topicScoped, visible.size())),
        List.of(
            new StatisticRuleMetricDefinition(
                "total",
                "记录总数",
                "当前专题范围内的客户问题议题数量。",
                "记录总数 = 当前客户问题专题范围内的议题数量",
                null),
            new StatisticRuleMetricDefinition(
                "delay",
                "延期标记",
                "延期问题专题保留已标记为延期、响应延期或解决延期的记录。",
                "延期记录 = 已标记延期，或响应超过期限，或解决超过期限",
                null)),
        null);
  }

  private List<IssueFactRecord> loadTopicScopedViews(CustomerIssueRecordProfile profile, Long projectId) {
    return applyTopic(applyRecordProfile(scopeCustomerIssues(loadFacts(LEGACY_CC_PRODUCT_PROJECT_ID), profile), profile), profile);
  }

  private List<String> customerIssueMilestones(List<String> values) {
    Set<String> milestones = new LinkedHashSet<>();
    if (milestoneCatalogService != null) {
      milestones.addAll(milestoneCatalogService.listMilestones());
    }
    values.stream()
        .map(TextQuerySupport::trimToNull)
        .filter(value -> value != null)
        .forEach(milestones::add);
    return CustomerIssueMilestoneOrdering.sortLatestFirst(milestones);
  }

  private IssueFactRecordListRequest withCustomerProject(IssueFactRecordListRequest request) {
    return new IssueFactRecordListRequest(
        LEGACY_CC_PRODUCT_PROJECT_ID,
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

  private CustomerIssueRecordQueryRequest withSnapshotDefaults(CustomerIssueRecordQueryRequest request) {
    String safeTopic = normalizeTopic(request.topic());
    CustomerIssueRecordProfile profile = CustomerIssueRecordProfile.forTopic(safeTopic);
    return new CustomerIssueRecordQueryRequest(
        safeTopic,
        withCustomerProject(request.listRequest()),
        request.reasonCategory(),
        request.authorName(),
        request.handlerName(),
        request.assigneeName(),
        request.testingPhase(),
        request.fixUser(),
        request.delayCause(),
        request.filterGroupJson(),
        profile.supportsCustomerFields() ? request.customerName() : null);
  }

  private CustomerIssueRecordQueryRequest defaultRequest(String topic) {
    return new CustomerIssueRecordQueryRequest(
        topic,
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
        null,
        null,
        null,
        null,
        null);
  }

  private PageRecordSnapshotService.SnapshotRequest snapshotRequest(
      String snapshotType, String scopeKey, String ruleVersion, Object requestPayload) {
    return new PageRecordSnapshotService.SnapshotRequest(
        "customer-issue-records",
        snapshotType,
        scopeKey,
        ruleVersion,
        pageRecordSnapshotService.issueFactSourceVersion(),
        requestPayload);
  }

  private List<IssueFactRecord> applyTopic(List<IssueFactRecord> rows, CustomerIssueRecordProfile profile) {
    if (profile.delayOnly()) {
      return rows.stream().filter(IssueFactRecord::delayRelated).toList();
    }
    return rows;
  }

  private List<IssueFactRecord> scopeCustomerIssues(List<IssueFactRecord> rows) {
    return scopeCustomerIssues(rows, CustomerIssueRecordProfile.customerOperationsProfile());
  }

  private List<IssueFactRecord> scopeCustomerIssues(
      List<IssueFactRecord> rows, CustomerIssueRecordProfile profile) {
    return rows.stream()
        .filter(view -> profile.scope().matches(view, customerIssueScopeProfile))
        .toList();
  }

  private List<IssueFactRecord> applyRecordProfile(
      List<IssueFactRecord> rows, CustomerIssueRecordProfile profile) {
    return rows.stream()
        .filter(view -> !profile.excludeExcluded() || !view.excluded())
        .filter(view -> !profile.excludeRejectedBugStatus() || !containsRejectedBugStatus(view))
        .toList();
  }

  private boolean containsRejectedBugStatus(IssueFactRecord view) {
    return TextQuerySupport.containsAbstractSearch(view.bugStatus(), "已拒绝");
  }

  private CustomerIssueRecordRowResponse toResponse(IssueFactRecord view, LocalDateTime asOf) {
    return new CustomerIssueRecordRowResponse(
        view.issueId(),
        view.issueIid(),
        buildIssueLink(view.sourceInstance(), view.projectId(), view.issueIid()),
        view.projectId(),
        view.projectName(),
        view.title(),
        String.join("、", view.customerNames()),
        view.issueState(),
        IssueDisplayValueSupport.displaySeverityLevelOrBlank(view.severityLevel()),
        view.priorityLevel(),
        view.bugStatus(),
        view.category(),
        view.reasonCategory(),
        view.milestoneTitle(),
        view.authorName(),
        view.handlerName(),
        view.assigneeName(),
        CustomerIssueTestingPhaseSupport.display(view.testingPhase()),
        view.fixUser(),
        String.join("、", view.moduleNames()),
        view.functionName(),
        view.delayIssue(),
        view.delayReason(),
        view.delayCause(),
        view.responseDelayed(),
        view.resolveDelayed(),
        view.illegal(),
        view.illegalReason(),
        view.createdAt(),
        IssueRetentionDurationSupport.hoursBetween(view.createdAt(), asOf),
        view.plannedResolutionAt(),
        view.plannedResolutionText(),
        view.plannedMergeVersionBranch(),
        view.updatedAt(),
        view.closedAt(),
        view.labels());
  }

  private boolean matchesCustomerName(List<String> customerNames, String customerName) {
    String normalized = TextQuerySupport.trimToNull(customerName);
    return normalized == null
        || customerNames.stream().anyMatch(value -> TextQuerySupport.equalsNormalized(value, normalized));
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
        || TextQuerySupport.containsAbstractSearch(view.reasonCategory(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.authorName(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.handlerName(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.assigneeName(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.milestoneTitle(), normalizedKeyword);
  }

  private String normalizeTopic(String topic) {
    return TOPIC_DELAY.equalsIgnoreCase(TextQuerySupport.trimToNull(topic)) ? TOPIC_DELAY : TOPIC_CC_PRODUCT;
  }

  private String topicTitle(String topic) {
    return TOPIC_DELAY.equals(topic) ? "延期问题" : "CC_PRODUCT 议题";
  }

  private String topicSummary(String topic) {
    if (TOPIC_DELAY.equals(topic)) {
      return "延期问题专题展示客户问题范围内已经申请延期、响应延期或解决延期的议题。";
    }
    return "CC_PRODUCT 议题专题展示客户问题范围内的全部议题。";
  }

  private String topicFilterDescription(String topic) {
    if (TOPIC_DELAY.equals(topic)) {
      return "保留已申请延期、响应延期或解决延期的客户问题议题。";
    }
    return "不默认选择里程碑，保留 2026-01-01 以来除已拒绝状态外的议题。";
  }

  private record CustomerIssueRecordProfile(
      boolean delayOnly,
      boolean excludeExcluded,
      boolean excludeRejectedBugStatus,
      IssueFactRecordPageQuery.Scope pageScope,
      CustomerIssueRecordScope scope,
      CustomerIssueRecordWorkbookLayout workbookLayout,
      String ruleVersion,
      String explanation) {

    private static CustomerIssueRecordProfile forTopic(String topic) {
      if (TOPIC_DELAY.equals(topic)) {
        return new CustomerIssueRecordProfile(
            true,
            true,
            false,
            IssueFactRecordPageQuery.Scope.CUSTOMER,
            CustomerIssueRecordScope.CUSTOMER_OPERATIONS,
            CustomerIssueRecordWorkbookLayout.DELAY,
            DELAY_RULE_VERSION,
            "延期记录复用客户问题统计口径，排除已关闭的申请否决、需求如此和设计如此类数据。");
      }
      return new CustomerIssueRecordProfile(
          false,
          false,
          true,
          IssueFactRecordPageQuery.Scope.CUSTOMER,
          CustomerIssueRecordScope.CUSTOMER_OPERATIONS,
          CustomerIssueRecordWorkbookLayout.CC_PRODUCT,
          CC_PRODUCT_RULE_VERSION,
          "CC_PRODUCT 议题默认全里程碑，查询 CC_Product 项目自 2026-01-01 以来提交的记录，排除处理状态包含“已拒绝”的记录，不套用客户问题统计页公共排除。");
    }

    private static CustomerIssueRecordProfile customerOperationsProfile() {
      return new CustomerIssueRecordProfile(
          false,
          true,
          false,
          IssueFactRecordPageQuery.Scope.CUSTOMER,
          CustomerIssueRecordScope.CUSTOMER_OPERATIONS,
          CustomerIssueRecordWorkbookLayout.CC_PRODUCT,
          CC_PRODUCT_RULE_VERSION,
          "客户问题运营统计口径限定 CC_Product 项目且创建时间不早于 2026-01-01。");
    }

    private boolean supportsCustomerFields() {
      return workbookLayout == CustomerIssueRecordWorkbookLayout.CC_PRODUCT;
    }
  }

  private enum CustomerIssueRecordScope {
    CUSTOMER_PROJECT {
      @Override
      boolean matches(IssueFactRecord record, CustomerIssueScopeProfile customerIssueScopeProfile) {
        return record != null
            && record.projectId() != null
            && record.projectId().longValue() == CustomerIssueScopeProfile.LEGACY_CC_PRODUCT_PROJECT_ID;
      }
    },
    CUSTOMER_OPERATIONS {
      @Override
      boolean matches(IssueFactRecord record, CustomerIssueScopeProfile customerIssueScopeProfile) {
        return record != null && customerIssueScopeProfile.matches(record.scopeContext());
      }
    };

    abstract boolean matches(IssueFactRecord record, CustomerIssueScopeProfile customerIssueScopeProfile);
  }

  private static Map<String, Comparator<IssueFactRecord>> createSortComparators() {
    Map<String, Comparator<IssueFactRecord>> comparators = new LinkedHashMap<>();
    comparators.put("issueIid", SortSupport.nullableComparable(IssueFactRecord::issueIid));
    comparators.put("title", SortSupport.nullableString(IssueFactRecord::title));
    comparators.put("projectName", SortSupport.nullableString(IssueFactRecord::projectName));
    comparators.put(
        "moduleNames", SortSupport.nullableString(view -> String.join("、", view.moduleNames())));
    comparators.put("functionName", SortSupport.nullableString(IssueFactRecord::functionName));
    comparators.put(
        "customerNames", SortSupport.nullableString(view -> String.join("、", view.customerNames())));
    comparators.put("testingPhase", SortSupport.nullableString(IssueFactRecord::testingPhase));
    comparators.put("reasonCategory", SortSupport.nullableString(IssueFactRecord::reasonCategory));
    comparators.put("fixUser", SortSupport.nullableString(IssueFactRecord::fixUser));
    comparators.put("delayCause", SortSupport.nullableString(IssueFactRecord::delayCause));
    comparators.put("severityLevel", SortSupport.nullableString(IssueFactRecord::severityLevel));
    comparators.put("priorityLevel", SortSupport.nullableString(IssueFactRecord::priorityLevel));
    comparators.put("bugStatus", SortSupport.nullableString(IssueFactRecord::bugStatus));
    comparators.put("issueState", SortSupport.nullableString(IssueFactRecord::issueState));
    comparators.put("authorName", SortSupport.nullableString(IssueFactRecord::authorName));
    comparators.put("handlerName", SortSupport.nullableString(IssueFactRecord::handlerName));
    comparators.put("assigneeName", SortSupport.nullableString(IssueFactRecord::assigneeName));
    comparators.put("category", SortSupport.nullableString(IssueFactRecord::category));
    comparators.put("milestoneTitle", SortSupport.nullableString(IssueFactRecord::milestoneTitle));
    comparators.put("plannedResolutionAt", SortSupport.nullableComparable(IssueFactRecord::plannedResolutionAt));
    comparators.put(
        "plannedMergeVersionBranch",
        SortSupport.nullableString(IssueFactRecord::plannedMergeVersionBranch));
    comparators.put("createdAt", SortSupport.nullableComparable(IssueFactRecord::createdAt));
    comparators.put("updatedAt", SortSupport.nullableComparable(IssueFactRecord::updatedAt));
    comparators.put("closedAt", SortSupport.nullableComparable(IssueFactRecord::closedAt));
    return Map.copyOf(comparators);
  }
}
