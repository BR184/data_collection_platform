package com.data.collection.platform.service;

import com.data.collection.platform.entity.CustomerIssueRecordFilterOptionsResponse;
import com.data.collection.platform.entity.CustomerIssueRecordListResponse;
import com.data.collection.platform.entity.CustomerIssueRecordRowResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupExpansionResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.entity.statistics.StatisticRuleMetricDefinition;
import com.data.collection.platform.service.labelgroup.LabelGroupExpansionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CustomerIssueRecordService extends AbstractIssueFactRecordListService {
  private static final String TOPIC_CC_PRODUCT = "cc-product";
  private static final String TOPIC_DELAY = "delay";
  private static final String PAGE_KEY = "customer-issues-cc-product-issues";
  private static final String RULE_VERSION = "customer-issue-records@2026-04-22-v1";
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

  public CustomerIssueRecordService(
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

  public CustomerIssueRecordListResponse listRecords(CustomerIssueRecordQueryRequest request) {
    IssueFactRecordListRequest listRequest = request.listRequest();
    int safePage = normalizePage(listRequest.page());
    int safeSize = normalizeSize(listRequest.size());
    String safeSortField =
        normalizeSortField(listRequest.sortField(), DEFAULT_SORT_FIELD, SORT_COMPARATORS.keySet());
    String safeSortOrder = normalizeSortOrder(listRequest.sortOrder());
    String safeTopic = normalizeTopic(request.topic());
    StatisticFilterGroup filterGroup =
        IssueFactRecordFilterGroupSupport.parse(
            objectMapper,
            request.filterGroupJson(),
            IssueFactRecordFilterGroupSupport.CUSTOMER_ISSUE_FILTER_OPERATORS);
    StatisticFilterGroup expandedFilterGroup = expandLabelGroupConditions(filterGroup, listRequest.sourceInstance());
    boolean hasLabelGroupFilters = IssueFactRecordFilterGroupSupport.hasLabelGroupConditions(expandedFilterGroup);

    if (!hasLabelGroupFilters && canUseSqlPage(listRequest, request.filterGroupJson(), safeSortField)) {
      PageSlice<IssueFactRecord> pageSlice =
          loadFactPage(
              new IssueFactRecordPageQuery(
                  IssueFactRecordPageQuery.Scope.CUSTOMER,
                  listRequest,
                  expandedFilterGroup,
                  request.reasonCategory(),
                  null,
                  null,
                  null,
                  null,
                  TOPIC_DELAY.equals(safeTopic),
                  false,
                  false,
                  false,
                  false,
                  safePage,
                  safeSize,
                  safeSortField,
                  safeSortOrder));
      return new CustomerIssueRecordListResponse(
          pageSlice.records().stream().map(this::toResponse).toList(),
          pageSlice.total(),
          pageSlice.page(),
          pageSlice.size(),
          safeSortField,
          safeSortOrder);
    }

    List<IssueFactRecord> filtered =
        applyBaseFilters(
                loadTopicScopedViews(safeTopic, listRequest.projectId()),
                listRequest,
                view -> matchesKeyword(view, listRequest.keyword()))
            .stream()
            .filter(view -> matchesEquals(view.reasonCategory(), request.reasonCategory()))
            .filter(view -> IssueFactRecordFilterGroupSupport.matches(view, expandedFilterGroup))
            .sorted(applySortDirection(SORT_COMPARATORS.get(safeSortField), safeSortOrder))
            .toList();

    PageSlice<IssueFactRecord> pageSlice = PageSliceSupport.slice(filtered, safePage, safeSize);
    return new CustomerIssueRecordListResponse(
        pageSlice.records().stream().map(this::toResponse).toList(),
        pageSlice.total(),
        pageSlice.page(),
        pageSlice.size(),
        safeSortField,
        safeSortOrder);
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

  public String exportRecordsCsv(CustomerIssueRecordQueryRequest request) {
    List<CustomerIssueRecordRowResponse> rows = new ArrayList<>();
    int page = 1;
    while (true) {
      IssueFactRecordListRequest listRequest = request.listRequest();
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
              request.filterGroupJson());
      CustomerIssueRecordListResponse response = listRecords(pageRequest);
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
                "项目",
                "模块",
                "缺陷原因",
                "严重程度",
                "优先级",
                "缺陷状态",
                "状态",
                "创建人",
                "处理人",
                "缺陷分类",
                "里程碑",
                "延期问题",
                "延期原因",
                "延期分类",
                "响应延期",
                "解决延期",
                "非法数据",
                "非法原因",
                "创建时间",
                "更新时间",
                "关闭时间",
                "标题",
                "链接")));
    for (CustomerIssueRecordRowResponse row : rows) {
      lines.add(
          String.join(
              ",",
              List.of(
                  CsvExportSupport.cell(row.issueIid()),
                  CsvExportSupport.cell(row.projectName()),
                  CsvExportSupport.cell(row.moduleNames()),
                  CsvExportSupport.cell(row.reasonCategory()),
                  CsvExportSupport.cell(row.severityLevel()),
                  CsvExportSupport.cell(row.priorityLevel()),
                  CsvExportSupport.cell(row.bugStatus()),
                  CsvExportSupport.cell(row.issueState()),
                  CsvExportSupport.cell(row.authorName()),
                  CsvExportSupport.cell(row.assigneeName()),
                  CsvExportSupport.cell(row.category()),
                  CsvExportSupport.cell(row.milestoneTitle()),
                  CsvExportSupport.cell(row.delayIssue() ? "是" : "否"),
                  CsvExportSupport.cell(row.delayReason()),
                  CsvExportSupport.cell(row.delayCause()),
                  CsvExportSupport.cell(row.responseDelayed() ? "是" : "否"),
                  CsvExportSupport.cell(row.resolveDelayed() ? "是" : "否"),
                  CsvExportSupport.cell(row.illegal() ? "是" : "否"),
                  CsvExportSupport.cell(row.illegalReason()),
                  CsvExportSupport.cell(CsvExportSupport.dateTime(row.createdAt())),
                  CsvExportSupport.cell(CsvExportSupport.dateTime(row.updatedAt())),
                  CsvExportSupport.cell(CsvExportSupport.dateTime(row.closedAt())),
                  CsvExportSupport.cell(row.title()),
                  CsvExportSupport.cell(row.issueLink()))));
    }
    return String.join("\n", lines) + "\n";
  }

  private List<String> describeExpandedLabelGroupFilters(CustomerIssueRecordQueryRequest request) {
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

  public CustomerIssueRecordFilterOptionsResponse getFilterOptions(String topic, Long projectId) {
    return getFilterOptions(topic, projectId, null);
  }

  public CustomerIssueRecordFilterOptionsResponse getFilterOptions(
      String topic, Long projectId, String sourceInstance) {
    List<IssueFactRecord> rows =
        loadTopicScopedViews(normalizeTopic(topic), projectId).stream()
            .filter(view -> matchesSourceInstance(view, sourceInstance))
            .toList();
    return new CustomerIssueRecordFilterOptionsResponse(
        toLegacyOptions(rows, IssueFactRecord::projectName),
        toLegacyOptions(rows.stream().flatMap(view -> view.moduleNames().stream()).toList()),
        toOptions(rows, IssueFactRecord::reasonCategory),
        toOptions(rows, IssueFactRecord::severityLevel),
        toOptions(rows, IssueFactRecord::priorityLevel),
        toOptions(rows, IssueFactRecord::issueState),
        toOptions(rows, IssueFactRecord::bugStatus),
        toOptions(rows, IssueFactRecord::category),
        toLegacyOptions(rows, IssueFactRecord::assigneeName),
        toLegacyOptions(rows, IssueFactRecord::milestoneTitle));
  }

  public StatisticBoardRuleExplanationResponse getRuleExplanation(String topic, Long projectId) {
    String safeTopic = normalizeTopic(topic);
    List<IssueFactRecord> loaded = loadFacts(projectId);
    List<IssueFactRecord> scoped = scopeCustomerIssues(loaded);
    List<IssueFactRecord> topicScoped = applyTopic(scoped, safeTopic);
    return new StatisticBoardRuleExplanationResponse(
        "customer-issue-" + safeTopic + "-records",
        true,
        topicTitle(safeTopic) + "规则说明",
        RULE_VERSION,
        "当前页面基于 issue_fact 事实层，先用 CustomerIssueScopeProfile 限定客户问题范围，再按页面专题继续收敛。",
        topicSummary(safeTopic),
        List.of(
            step("source-load", "加载议题事实", "从 issue_fact 读取已归一化的议题事实。", loaded, loaded.size()),
            step("scope-filter", "限定客户问题范围", "复用客户问题 scope profile，避免和系统测试口径混在一起。", scoped, loaded.size()),
            step("topic-filter", topicTitle(safeTopic), topicFilterDescription(safeTopic), topicScoped, scoped.size())),
        List.of(
            new StatisticRuleMetricDefinition(
                "total",
                "记录总数",
                "当前专题范围内的客户问题议题数量。",
                "记录总数 = count(issue_fact where customer scope and topic filter)",
                null),
            new StatisticRuleMetricDefinition(
                "delay",
                "延期标记",
                "延期问题专题保留 delay_issue、响应延期或解决延期命中的记录。",
                "延期记录 = delay_issue = true or is_response_delayed = true or is_resolve_delayed = true",
                null)),
        null);
  }

  private List<IssueFactRecord> loadTopicScopedViews(String topic, Long projectId) {
    return applyTopic(scopeCustomerIssues(loadFacts(projectId)), topic);
  }

  private List<IssueFactRecord> applyTopic(List<IssueFactRecord> rows, String topic) {
    if (TOPIC_DELAY.equals(topic)) {
      return rows.stream().filter(IssueFactRecord::delayRelated).toList();
    }
    return rows;
  }

  private List<IssueFactRecord> scopeCustomerIssues(List<IssueFactRecord> rows) {
    return rows.stream()
        .filter(view -> customerIssueScopeProfile.matches(view.scopeContext()))
        .toList();
  }

  private CustomerIssueRecordRowResponse toResponse(IssueFactRecord view) {
    return new CustomerIssueRecordRowResponse(
        view.issueId(),
        view.issueIid(),
        buildIssueLink(view.sourceInstance(), view.projectId(), view.issueIid()),
        view.projectId(),
        view.projectName(),
        view.title(),
        view.issueState(),
        view.severityLevel(),
        view.priorityLevel(),
        view.bugStatus(),
        view.category(),
        view.reasonCategory(),
        view.milestoneTitle(),
        view.authorName(),
        view.assigneeName(),
        String.join("、", view.moduleNames()),
        view.delayIssue(),
        view.delayReason(),
        view.delayCause(),
        view.responseDelayed(),
        view.resolveDelayed(),
        view.illegal(),
        view.illegalReason(),
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
        || TextQuerySupport.containsAbstractSearch(view.reasonCategory(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.authorName(), normalizedKeyword)
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
      return "保留 delay_issue、is_response_delayed 或 is_resolve_delayed 命中的客户问题议题。";
    }
    return "不再做额外过滤，保留客户问题范围内的全部议题。";
  }

  private static Map<String, Comparator<IssueFactRecord>> createSortComparators() {
    Map<String, Comparator<IssueFactRecord>> comparators = new LinkedHashMap<>();
    comparators.put("issueIid", SortSupport.nullableComparable(IssueFactRecord::issueIid));
    comparators.put("title", SortSupport.nullableString(IssueFactRecord::title));
    comparators.put("projectName", SortSupport.nullableString(IssueFactRecord::projectName));
    comparators.put(
        "moduleNames", SortSupport.nullableString(view -> String.join("、", view.moduleNames())));
    comparators.put("reasonCategory", SortSupport.nullableString(IssueFactRecord::reasonCategory));
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
