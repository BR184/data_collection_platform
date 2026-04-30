package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.CustomerIssueIllegalRecordFilterOptionsResponse;
import com.data.collection.platform.entity.CustomerIssueIllegalRecordListResponse;
import com.data.collection.platform.entity.CustomerIssueIllegalRecordRowResponse;
import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStep;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStepSample;
import com.data.collection.platform.entity.statistics.StatisticRuleMetricDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CustomerIssueIllegalRecordService extends AbstractIssueFactRecordListService {
  private static final String WORKSPACE_KEY = "customer-issue-illegal-records";
  private static final String RULE_VERSION = "customer-issue-illegal-records@2026-04-22-v1";
  private static final String DEFAULT_SORT_FIELD = "updatedAt";
  private static final Map<String, Comparator<IssueFactRecord>> SORT_COMPARATORS =
      createSortComparators();

  private final CustomerIssueScopeProfile customerIssueScopeProfile;
  private final ObjectMapper objectMapper;

  public CustomerIssueIllegalRecordService(
      IssueFactRecordRepository issueFactRecordRepository,
      CustomerIssueScopeProfile customerIssueScopeProfile,
      ObjectMapper objectMapper,
      GitlabMirrorProperties gitlabMirrorProperties) {
    super(issueFactRecordRepository, gitlabMirrorProperties.getWebBaseUrl());
    this.customerIssueScopeProfile = customerIssueScopeProfile;
    this.objectMapper = objectMapper;
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

    if (canUseSqlPage(listRequest, request.filterGroupJson(), safeSortField)) {
      PageSlice<IssueFactRecord> pageSlice =
          loadFactPage(
              new IssueFactRecordPageQuery(
                  IssueFactRecordPageQuery.Scope.CUSTOMER,
                  listRequest,
                  filterGroup,
                  null,
                  request.illegalReason(),
                  null,
                  null,
                  null,
                  false,
                  true,
                  false,
                  false,
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
            .filter(view -> matchesEquals(view.illegalReason(), request.illegalReason()))
            .filter(view -> IssueFactRecordFilterGroupSupport.matches(view, filterGroup))
            .sorted(applySortDirection(SORT_COMPARATORS.get(safeSortField), safeSortOrder))
            .toList();

    PageSlice<IssueFactRecord> pageSlice = PageSliceSupport.slice(filtered, safePage, safeSize);
    List<CustomerIssueIllegalRecordRowResponse> records =
        pageSlice.records().stream().map(this::toResponse).toList();
    return new CustomerIssueIllegalRecordListResponse(
        records, pageSlice.total(), pageSlice.page(), pageSlice.size(), safeSortField, safeSortOrder);
  }

  public CustomerIssueIllegalRecordFilterOptionsResponse getFilterOptions(Long projectId) {
    List<IssueFactRecord> rows =
        loadScopedViews(projectId).stream().filter(IssueFactRecord::illegal).toList();
    return new CustomerIssueIllegalRecordFilterOptionsResponse(
        toOptions(rows, IssueFactRecord::projectName),
        toOptions(rows.stream().flatMap(view -> view.moduleNames().stream()).toList()),
        toOptions(rows, IssueFactRecord::illegalReason),
        toOptions(rows, IssueFactRecord::severityLevel),
        toOptions(rows, IssueFactRecord::priorityLevel),
        toOptions(rows, IssueFactRecord::issueState),
        toOptions(rows, IssueFactRecord::bugStatus),
        toOptions(rows, IssueFactRecord::category),
        toOptions(rows, IssueFactRecord::milestoneTitle));
  }

  public StatisticBoardRuleExplanationResponse getRuleExplanation(Long projectId) {
    List<IssueFactRecord> loaded = loadFacts(projectId);
    List<IssueFactRecord> scoped = scopeCustomerIssues(loaded);
    List<IssueFactRecord> illegal =
        scoped.stream().filter(IssueFactRecord::illegal).toList();
    return new StatisticBoardRuleExplanationResponse(
        WORKSPACE_KEY,
        true,
        "客户问题缺陷非法数据规则说明",
        RULE_VERSION,
        "当前页面基于 issue_fact 事实层，先用 CustomerIssueScopeProfile 限定客户问题范围，再展示已被事实构建链路判定为非法的缺陷。",
        "非法原因来自 issue_fact.illegal_reason；当前规则只消费事实层结果，不在页面层重新推导。",
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
                "illegal-reason",
                "非法原因",
                "直接展示事实层沉淀的 illegal_reason，例如缺失模块、流程越位等。",
                "非法原因分布 = group by issue_fact.illegal_reason",
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
        buildIssueLink(view.issueIid()),
        view.projectId(),
        view.projectName(),
        view.title(),
        view.issueState(),
        view.illegalReason(),
        view.severityLevel(),
        view.priorityLevel(),
        view.bugStatus(),
        view.category(),
        view.milestoneTitle(),
        view.authorName(),
        view.assigneeName(),
        String.join("、", view.moduleNames()),
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
        || TextQuerySupport.containsAbstractSearch(view.illegalReason(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.authorName(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.assigneeName(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.milestoneTitle(), normalizedKeyword);
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
                        + (StringUtils.hasText(row.illegalReason())
                            ? " | 非法原因: " + row.illegalReason()
                            : "")))
        .toList();
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
