package com.data.collection.platform.service;

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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CustomerIssueIllegalRecordService {
  private static final String WORKSPACE_KEY = "customer-issue-illegal-records";
  private static final String RULE_VERSION = "customer-issue-illegal-records@2026-04-22-v1";

  private final IssueFactRecordRepository issueFactRecordRepository;
  private final CustomerIssueScopeProfile customerIssueScopeProfile;
  private final ObjectMapper objectMapper;

  public CustomerIssueIllegalRecordService(
      IssueFactRecordRepository issueFactRecordRepository,
      CustomerIssueScopeProfile customerIssueScopeProfile,
      ObjectMapper objectMapper) {
    this.issueFactRecordRepository = issueFactRecordRepository;
    this.customerIssueScopeProfile = customerIssueScopeProfile;
    this.objectMapper = objectMapper;
  }

  public CustomerIssueIllegalRecordListResponse listRecords(
      Long projectId,
      String keyword,
      String issueIid,
      String title,
      String projectName,
      String moduleName,
      String illegalReason,
      String severityLevel,
      String priorityLevel,
      String issueState,
      String bugStatus,
      String category,
      String milestoneTitle,
      String createdAtStart,
      String createdAtEnd,
      String updatedAtStart,
      String updatedAtEnd,
      String filterGroupJson,
      int page,
      int size,
      String sortField,
      String sortOrder) {
    int safePage = page <= 0 ? 1 : page;
    int safeSize = size <= 0 ? 20 : Math.min(size, 100);
    String safeSortField = normalizeSortField(sortField);
    String safeSortOrder = normalizeSortOrder(sortOrder);
    StatisticFilterGroup filterGroup =
        IssueFactRecordFilterGroupSupport.parse(
            objectMapper,
            filterGroupJson,
            IssueFactRecordFilterGroupSupport.CUSTOMER_ISSUE_FILTER_OPERATORS);

    List<IssueFactRecord> filtered =
        loadScopedViews(projectId).stream()
            .filter(IssueFactRecord::illegal)
            .filter(view -> matchesKeyword(view, keyword))
            .filter(view -> matchesIssueIid(view, issueIid))
            .filter(view -> matchesText(view.title(), title))
            .filter(view -> matchesEquals(view.projectName(), projectName))
            .filter(view -> matchesModule(view, moduleName))
            .filter(view -> matchesEquals(view.illegalReason(), illegalReason))
            .filter(view -> matchesEquals(view.severityLevel(), severityLevel))
            .filter(view -> matchesEquals(view.priorityLevel(), priorityLevel))
            .filter(view -> matchesEquals(view.issueState(), issueState))
            .filter(view -> matchesEquals(view.bugStatus(), bugStatus))
            .filter(view -> matchesEquals(view.category(), category))
            .filter(view -> matchesEquals(view.milestoneTitle(), milestoneTitle))
            .filter(view -> matchesDateRange(view.createdAt(), createdAtStart, createdAtEnd))
            .filter(view -> matchesDateRange(view.updatedAt(), updatedAtStart, updatedAtEnd))
            .filter(view -> IssueFactRecordFilterGroupSupport.matches(view, filterGroup))
            .sorted(buildComparator(safeSortField, safeSortOrder))
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

  private List<IssueFactRecord> loadFacts(Long projectId) {
    return issueFactRecordRepository.findByProjectId(projectId);
  }

  private CustomerIssueIllegalRecordRowResponse toResponse(IssueFactRecord view) {
    return new CustomerIssueIllegalRecordRowResponse(
        view.issueId(),
        view.issueIid(),
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

  private boolean matchesIssueIid(IssueFactRecord view, String issueIid) {
    String normalized = TextQuerySupport.trimToNull(issueIid);
    return normalized == null
        || TextQuerySupport.containsIgnoreCase(String.valueOf(view.issueIid()), normalized);
  }

  private boolean matchesText(String source, String query) {
    return TextQuerySupport.containsAbstractSearch(source, query);
  }

  private boolean matchesModule(IssueFactRecord view, String moduleName) {
    String normalized = TextQuerySupport.trimToNull(moduleName);
    return normalized == null
        || view.moduleNames().stream()
            .anyMatch(item -> TextQuerySupport.equalsNormalized(item, normalized));
  }

  private boolean matchesEquals(String source, String target) {
    return TextQuerySupport.equalsNormalized(source, target);
  }

  private boolean matchesDateRange(LocalDateTime source, String start, String end) {
    LocalDate startDate = parseDate(start);
    if (startDate != null && (source == null || source.isBefore(startDate.atStartOfDay()))) {
      return false;
    }
    LocalDate endDate = parseDate(end);
    return endDate == null || (source != null && source.isBefore(endDate.plusDays(1).atStartOfDay()));
  }

  private Comparator<IssueFactRecord> buildComparator(String sortField, String sortOrder) {
    Comparator<IssueFactRecord> comparator =
        switch (sortField) {
          case "issueIid" -> SortSupport.nullableComparable(IssueFactRecord::issueIid);
          case "title" -> SortSupport.nullableString(IssueFactRecord::title);
          case "projectName" -> SortSupport.nullableString(IssueFactRecord::projectName);
          case "moduleNames" -> SortSupport.nullableString(view -> String.join("、", view.moduleNames()));
          case "illegalReason" -> SortSupport.nullableString(IssueFactRecord::illegalReason);
          case "severityLevel" -> SortSupport.nullableString(IssueFactRecord::severityLevel);
          case "priorityLevel" -> SortSupport.nullableString(IssueFactRecord::priorityLevel);
          case "bugStatus" -> SortSupport.nullableString(IssueFactRecord::bugStatus);
          case "issueState" -> SortSupport.nullableString(IssueFactRecord::issueState);
          case "authorName" -> SortSupport.nullableString(IssueFactRecord::authorName);
          case "assigneeName" -> SortSupport.nullableString(IssueFactRecord::assigneeName);
          case "category" -> SortSupport.nullableString(IssueFactRecord::category);
          case "milestoneTitle" -> SortSupport.nullableString(IssueFactRecord::milestoneTitle);
          case "createdAt" -> SortSupport.nullableComparable(IssueFactRecord::createdAt);
          case "closedAt" -> SortSupport.nullableComparable(IssueFactRecord::closedAt);
          default -> SortSupport.nullableComparable(IssueFactRecord::updatedAt);
        };
    comparator = comparator.thenComparing(IssueFactRecord::issueIid);
    return SortSupport.applyDirection(comparator, "asc".equalsIgnoreCase(sortOrder));
  }

  private String normalizeSortField(String sortField) {
    String normalized = TextQuerySupport.trimToNull(sortField);
    if (normalized == null) {
      return "updatedAt";
    }
    return switch (normalized) {
      case "issueIid",
          "title",
          "projectName",
          "moduleNames",
          "illegalReason",
          "severityLevel",
          "priorityLevel",
          "bugStatus",
          "issueState",
          "authorName",
          "assigneeName",
          "category",
          "milestoneTitle",
          "createdAt",
          "updatedAt",
          "closedAt" -> normalized;
      default -> "updatedAt";
    };
  }

  private String normalizeSortOrder(String sortOrder) {
    return "asc".equalsIgnoreCase(sortOrder) ? "asc" : "desc";
  }

  private StatisticRuleFlowStep step(
      String key,
      String title,
      String description,
      List<IssueFactRecord> output,
      long inputCount) {
    return new StatisticRuleFlowStep(key, title, description, inputCount, output.size(), sample(output));
  }

  private List<StatisticRuleFlowStepSample> sample(List<IssueFactRecord> rows) {
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

  private List<OptionItemResponse> toOptions(
      List<IssueFactRecord> rows,
      java.util.function.Function<IssueFactRecord, String> extractor) {
    return OptionItemResponseFactory.from(rows, extractor, TextQuerySupport::trimToNull);
  }

  private List<OptionItemResponse> toOptions(List<String> values) {
    return OptionItemResponseFactory.from(values, TextQuerySupport::trimToNull);
  }

  private LocalDate parseDate(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    return normalized == null ? null : LocalDate.parse(normalized);
  }
}
