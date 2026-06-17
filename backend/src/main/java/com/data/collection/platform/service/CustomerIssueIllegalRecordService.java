package com.data.collection.platform.service;

import com.data.collection.platform.entity.CustomerIssueIllegalRecordFilterOptionsResponse;
import com.data.collection.platform.entity.CustomerIssueIllegalRecordListResponse;
import com.data.collection.platform.entity.CustomerIssueIllegalRecordRowResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupExpansionResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStepSample;
import com.data.collection.platform.entity.statistics.StatisticRuleMetricDefinition;
import com.data.collection.platform.service.labelgroup.LabelGroupExpansionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CustomerIssueIllegalRecordService extends AbstractIssueFactRecordListService {
  private static final String WORKSPACE_KEY = "customer-issue-illegal-records";
  private static final String PAGE_KEY = "customer-issues-cc-product-issues";
  private static final String RULE_VERSION = "customer-issue-illegal-records@2026-04-22-v1";
  private static final String DEFAULT_SORT_FIELD = "updatedAt";
  private static final int EXPORT_PAGE_SIZE = 100;
  private static final int MAX_LABEL_GROUP_FILTER_VALUES = 200;
  private static final Map<String, String> LABEL_GROUP_FIELD_VALUE_TYPES =
      Map.ofEntries(
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

  public CustomerIssueIllegalRecordService(
      IssueFactRecordRepository issueFactRecordRepository,
      CustomerIssueScopeProfile customerIssueScopeProfile,
      ObjectMapper objectMapper,
      GitlabResourceLinkService issueLinkService,
      LabelGroupExpansionService labelGroupExpansionService) {
    super(issueFactRecordRepository, issueLinkService);
    this.customerIssueScopeProfile = customerIssueScopeProfile;
    this.objectMapper = objectMapper;
    this.labelGroupExpansionService = labelGroupExpansionService;
  }

  public CustomerIssueIllegalRecordListResponse listRecords(
      CustomerIssueIllegalRecordQueryRequest request) {
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
                  null,
                  null,
                  false,
                  true,
                  false,
                  false,
                  true,
                  false,
                  safePage,
                  safeSize,
                  safeSortField,
                  safeSortOrder));
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

  public String exportRecordsCsv(CustomerIssueIllegalRecordQueryRequest request) {
    List<CustomerIssueIllegalRecordRowResponse> rows = new ArrayList<>();
    int page = 1;
    while (true) {
      IssueFactRecordListRequest listRequest = request.listRequest();
      CustomerIssueIllegalRecordQueryRequest pageRequest =
          new CustomerIssueIllegalRecordQueryRequest(
              new IssueFactRecordListRequest(
                  listRequest.projectId(),
                  listRequest.keyword(),
                  listRequest.issueIid(),
                  listRequest.title(),
                  listRequest.projectName(),
                  listRequest.moduleName(),
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
              request.filterGroupJson());
      CustomerIssueIllegalRecordListResponse response = listRecords(pageRequest);
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
                "问题编号",
                "非法类型",
                "项目",
                "模块",
                "功能名",
                "测试阶段",
                "严重程度",
                "优先级",
                "缺陷状态",
                "状态",
                "创建人",
                "处理人",
                "缺陷分类",
                "里程碑",
                "延期原因",
                "创建时间",
                "更新时间",
                "关闭时间",
                "标题",
                "链接")));
    for (CustomerIssueIllegalRecordRowResponse row : rows) {
      lines.add(
          String.join(
              ",",
              List.of(
                  CsvExportSupport.cell(row.issueIid()),
                  CsvExportSupport.cell(row.illegalReason()),
                  CsvExportSupport.cell(row.projectName()),
                  CsvExportSupport.cell(row.moduleNames()),
                  CsvExportSupport.cell(row.functionName()),
                  CsvExportSupport.cell(row.testingPhase()),
                  CsvExportSupport.cell(row.severityLevel()),
                  CsvExportSupport.cell(row.priorityLevel()),
                  CsvExportSupport.cell(row.bugStatus()),
                  CsvExportSupport.cell(row.issueState()),
                  CsvExportSupport.cell(row.authorName()),
                  CsvExportSupport.cell(row.assigneeName()),
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

  private List<String> describeExpandedLabelGroupFilters(CustomerIssueIllegalRecordQueryRequest request) {
    StatisticFilterGroup filterGroup =
        IssueFactRecordFilterGroupSupport.parse(
            objectMapper,
            request.filterGroupJson(),
            IssueFactRecordFilterGroupSupport.CUSTOMER_ISSUE_FILTER_OPERATORS);
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

  public CustomerIssueIllegalRecordFilterOptionsResponse getFilterOptions(Long projectId) {
    List<IssueFactRecord> rows =
        loadScopedViews(projectId).stream()
            .filter(IssueFactRecord::illegal)
            .filter(this::hasSupportedCustomerIllegalReason)
            .toList();
    List<String> illegalReasons = new ArrayList<>(CustomerIssueIllegalReasonSupport.SUPPORTED_REASONS);
    rows.stream()
        .flatMap(view -> displayIllegalReasons(view).stream())
        .map(CustomerIssueIllegalReasonSupport::normalize)
        .filter(StringUtils::hasText)
        .filter(reason -> !illegalReasons.contains(reason))
        .forEach(illegalReasons::add);
    return new CustomerIssueIllegalRecordFilterOptionsResponse(
        toLegacyOptions(rows, IssueFactRecord::projectName),
        toLegacyOptions(rows.stream().flatMap(view -> view.moduleNames().stream()).toList()),
        toOptions(illegalReasons),
        toOptions(rows, IssueFactRecord::severityLevel),
        toOptions(rows, IssueFactRecord::priorityLevel),
        toOptions(rows, IssueFactRecord::issueState),
        toOptions(rows, IssueFactRecord::bugStatus),
        toOptions(rows, IssueFactRecord::category),
        toLegacyOptions(rows, IssueFactRecord::milestoneTitle));
  }

  public StatisticBoardRuleExplanationResponse getRuleExplanation(Long projectId) {
    List<IssueFactRecord> loaded = loadFacts(projectId);
    List<IssueFactRecord> scoped = scopeCustomerIssues(loaded);
    List<IssueFactRecord> illegal =
        scoped.stream().filter(IssueFactRecord::illegal).filter(this::hasSupportedCustomerIllegalReason).toList();
    return new StatisticBoardRuleExplanationResponse(
        WORKSPACE_KEY,
        true,
        "客户问题缺陷非法数据规则说明",
        RULE_VERSION,
        "当前页面基于 issue_fact 事实层，先用 CustomerIssueScopeProfile 限定客户问题范围，再展示已被事实构建链路判定为非法的缺陷。",
        "非法类型来自 issue_fact.illegal_reasons / illegal_reason，按老平台 illegal_list 多值口径展示和筛选。客户问题非法数据复用系统测试非法规则，并追加缺陷调研模板完整性、计划解决时间和一级缺陷负责人签字规则。",
        List.of(
            step("source-load", "加载议题事实", "从 issue_fact 读取已归一化的议题事实。", loaded, loaded.size()),
            step("scope-filter", "限定客户问题范围", "复用客户问题 scope profile，避免和系统测试口径混在一起。", scoped, loaded.size()),
            step("illegal-filter", "筛出非法数据", "保留 issue_fact.is_illegal = true 的客户问题缺陷。", illegal, scoped.size())),
        List.of(
            new StatisticRuleMetricDefinition(
                "illegal-total",
                "非法数据总数",
                "客户问题范围内 is_illegal = true 的议题数量。",
                "非法数据总数 = count(issue_fact where customer scope and is_illegal = true)",
                null),
            new StatisticRuleMetricDefinition(
                "base-illegal",
                "基础非法类型",
                "复用系统测试非法数据规则：未设定严重程度、未设定模块、已修复但未按模板回复、缺陷原因不唯一。",
                "基础非法类型 = issue_fact.illegal_reasons 中命中系统测试非法规则的类型",
                null),
            new StatisticRuleMetricDefinition(
                "research-template",
                "缺陷调研模板",
                "客户问题必须按要求填写缺陷调研模板；除计划解决时间和一级缺陷负责人签字项外，其余问题需要有回复内容。",
                "未按照要求填写缺陷调研模板 = issue_fact.illegal_reasons contains 未按照要求填写缺陷调研模板",
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
    return scopeCustomerIssues(loadFacts(projectId));
  }

  private List<IssueFactRecord> scopeCustomerIssues(List<IssueFactRecord> rows) {
    return rows.stream()
        .filter(view -> customerIssueScopeProfile.matches(view.scopeContext()))
        .toList();
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
        view.primaryPhaseLabel(),
        displayIllegalReason(view),
        view.severityLevel(),
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
