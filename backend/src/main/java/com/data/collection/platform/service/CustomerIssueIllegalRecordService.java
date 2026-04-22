package com.data.collection.platform.service;

import com.data.collection.platform.entity.CustomerIssueIllegalRecordFilterOptionsResponse;
import com.data.collection.platform.entity.CustomerIssueIllegalRecordListResponse;
import com.data.collection.platform.entity.CustomerIssueIllegalRecordRowResponse;
import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStep;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStepSample;
import com.data.collection.platform.entity.statistics.StatisticRuleMetricDefinition;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CustomerIssueIllegalRecordService {
  private static final String WORKSPACE_KEY = "customer-issue-illegal-records";
  private static final String RULE_VERSION = "customer-issue-illegal-records@2026-04-22-v1";
  private static final String FACT_SQL =
      """
      select project_id,
             coalesce(project_name, '') as project_name,
             issue_id,
             issue_iid,
             coalesce(title, '') as title,
             coalesce(issue_state, 'opened') as issue_state,
             coalesce(illegal_reason, '') as illegal_reason,
             coalesce(is_illegal, false) as is_illegal,
             coalesce(testing_phase, '') as testing_phase,
             coalesce(system_test_label, '') as system_test_label,
             coalesce(severity_level, '') as severity_level,
             coalesce(priority_level, '') as priority_level,
             coalesce(bug_status, '') as bug_status,
             coalesce(category, '') as category,
             coalesce(milestone_title, '') as milestone_title,
             coalesce(author_name, '') as author_name,
             coalesce(assignee_name, '') as assignee_name,
             coalesce(module_names, '') as module_names,
             coalesce(label_names, '') as label_names,
             created_at_source,
             updated_at_source,
             closed_at_source
        from issue_fact
       where deleted = false
      """;

  private final IssueFactQueryService issueFactQueryService;
  private final CustomerIssueScopeProfile customerIssueScopeProfile;

  public CustomerIssueIllegalRecordService(
      IssueFactQueryService issueFactQueryService,
      CustomerIssueScopeProfile customerIssueScopeProfile) {
    this.issueFactQueryService = issueFactQueryService;
    this.customerIssueScopeProfile = customerIssueScopeProfile;
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
      int page,
      int size,
      String sortField,
      String sortOrder) {
    int safePage = page <= 0 ? 1 : page;
    int safeSize = size <= 0 ? 20 : Math.min(size, 100);
    String safeSortField = normalizeSortField(sortField);
    String safeSortOrder = normalizeSortOrder(sortOrder);

    List<CustomerIssueIllegalRecordView> filtered =
        loadScopedViews(projectId).stream()
            .filter(CustomerIssueIllegalRecordView::illegal)
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
            .sorted(buildComparator(safeSortField, safeSortOrder))
            .toList();

    PageSlice<CustomerIssueIllegalRecordView> pageSlice =
        PageSliceSupport.slice(filtered, safePage, safeSize);
    List<CustomerIssueIllegalRecordRowResponse> records =
        pageSlice.records().stream().map(this::toResponse).toList();
    return new CustomerIssueIllegalRecordListResponse(
        records, pageSlice.total(), pageSlice.page(), pageSlice.size(), safeSortField, safeSortOrder);
  }

  public CustomerIssueIllegalRecordFilterOptionsResponse getFilterOptions(Long projectId) {
    List<CustomerIssueIllegalRecordView> rows =
        loadScopedViews(projectId).stream().filter(CustomerIssueIllegalRecordView::illegal).toList();
    return new CustomerIssueIllegalRecordFilterOptionsResponse(
        toOptions(rows, CustomerIssueIllegalRecordView::projectName),
        toOptions(rows.stream().flatMap(view -> view.moduleNames().stream()).toList()),
        toOptions(rows, CustomerIssueIllegalRecordView::illegalReason),
        toOptions(rows, CustomerIssueIllegalRecordView::severityLevel),
        toOptions(rows, CustomerIssueIllegalRecordView::priorityLevel),
        toOptions(rows, CustomerIssueIllegalRecordView::issueState),
        toOptions(rows, CustomerIssueIllegalRecordView::bugStatus),
        toOptions(rows, CustomerIssueIllegalRecordView::category),
        toOptions(rows, CustomerIssueIllegalRecordView::milestoneTitle));
  }

  public StatisticBoardRuleExplanationResponse getRuleExplanation(Long projectId) {
    List<CustomerIssueIllegalRecordView> loaded = loadFacts(projectId);
    List<CustomerIssueIllegalRecordView> scoped = scopeCustomerIssues(loaded);
    List<CustomerIssueIllegalRecordView> illegal =
        scoped.stream().filter(CustomerIssueIllegalRecordView::illegal).toList();
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

  private List<CustomerIssueIllegalRecordView> loadScopedViews(Long projectId) {
    return scopeCustomerIssues(loadFacts(projectId));
  }

  private List<CustomerIssueIllegalRecordView> scopeCustomerIssues(
      List<CustomerIssueIllegalRecordView> rows) {
    return rows.stream()
        .filter(view -> customerIssueScopeProfile.matches(view.scopeContext()))
        .toList();
  }

  private List<CustomerIssueIllegalRecordView> loadFacts(Long projectId) {
    Map<String, String> filters = new LinkedHashMap<>();
    if (projectId != null) {
      filters.put("projectId", String.valueOf(projectId));
    }
    try {
      return issueFactQueryService.query(FACT_SQL, filters, this::mapIssueFact);
    } catch (DataAccessException error) {
      return List.of();
    }
  }

  private CustomerIssueIllegalRecordView mapIssueFact(ResultSet rs, int rowNum) throws SQLException {
    return new CustomerIssueIllegalRecordView(
        rs.getLong("project_id"),
        text(rs.getString("project_name")),
        rs.getLong("issue_id"),
        rs.getInt("issue_iid"),
        text(rs.getString("title")),
        text(rs.getString("issue_state")),
        rs.getBoolean("is_illegal"),
        text(rs.getString("illegal_reason")),
        text(rs.getString("testing_phase")),
        text(rs.getString("system_test_label")),
        text(rs.getString("severity_level")),
        text(rs.getString("priority_level")),
        text(rs.getString("bug_status")),
        text(rs.getString("category")),
        text(rs.getString("milestone_title")),
        text(rs.getString("author_name")),
        text(rs.getString("assignee_name")),
        split(rs.getString("module_names")),
        split(rs.getString("label_names")),
        time(rs.getTimestamp("created_at_source")),
        time(rs.getTimestamp("updated_at_source")),
        time(rs.getTimestamp("closed_at_source")));
  }

  private CustomerIssueIllegalRecordRowResponse toResponse(CustomerIssueIllegalRecordView view) {
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

  private boolean matchesKeyword(CustomerIssueIllegalRecordView view, String keyword) {
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

  private boolean matchesIssueIid(CustomerIssueIllegalRecordView view, String issueIid) {
    String normalized = TextQuerySupport.trimToNull(issueIid);
    return normalized == null
        || TextQuerySupport.containsIgnoreCase(String.valueOf(view.issueIid()), normalized);
  }

  private boolean matchesText(String source, String query) {
    return TextQuerySupport.containsAbstractSearch(source, query);
  }

  private boolean matchesModule(CustomerIssueIllegalRecordView view, String moduleName) {
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

  private Comparator<CustomerIssueIllegalRecordView> buildComparator(
      String sortField, String sortOrder) {
    Comparator<CustomerIssueIllegalRecordView> comparator =
        switch (sortField) {
          case "issueIid" -> SortSupport.nullableComparable(CustomerIssueIllegalRecordView::issueIid);
          case "title" -> SortSupport.nullableString(CustomerIssueIllegalRecordView::title);
          case "projectName" -> SortSupport.nullableString(CustomerIssueIllegalRecordView::projectName);
          case "moduleNames" -> SortSupport.nullableString(view -> String.join("、", view.moduleNames()));
          case "illegalReason" -> SortSupport.nullableString(CustomerIssueIllegalRecordView::illegalReason);
          case "severityLevel" -> SortSupport.nullableString(CustomerIssueIllegalRecordView::severityLevel);
          case "priorityLevel" -> SortSupport.nullableString(CustomerIssueIllegalRecordView::priorityLevel);
          case "bugStatus" -> SortSupport.nullableString(CustomerIssueIllegalRecordView::bugStatus);
          case "issueState" -> SortSupport.nullableString(CustomerIssueIllegalRecordView::issueState);
          case "authorName" -> SortSupport.nullableString(CustomerIssueIllegalRecordView::authorName);
          case "assigneeName" -> SortSupport.nullableString(CustomerIssueIllegalRecordView::assigneeName);
          case "category" -> SortSupport.nullableString(CustomerIssueIllegalRecordView::category);
          case "milestoneTitle" -> SortSupport.nullableString(CustomerIssueIllegalRecordView::milestoneTitle);
          case "createdAt" -> SortSupport.nullableComparable(CustomerIssueIllegalRecordView::createdAt);
          case "closedAt" -> SortSupport.nullableComparable(CustomerIssueIllegalRecordView::closedAt);
          default -> SortSupport.nullableComparable(CustomerIssueIllegalRecordView::updatedAt);
        };
    comparator = comparator.thenComparing(CustomerIssueIllegalRecordView::issueIid);
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
      List<CustomerIssueIllegalRecordView> output,
      long inputCount) {
    return new StatisticRuleFlowStep(key, title, description, inputCount, output.size(), sample(output));
  }

  private List<StatisticRuleFlowStepSample> sample(List<CustomerIssueIllegalRecordView> rows) {
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
      List<CustomerIssueIllegalRecordView> rows,
      java.util.function.Function<CustomerIssueIllegalRecordView, String> extractor) {
    return OptionItemResponseFactory.from(rows, extractor, TextQuerySupport::trimToNull);
  }

  private List<OptionItemResponse> toOptions(List<String> values) {
    return OptionItemResponseFactory.from(values, TextQuerySupport::trimToNull);
  }

  private LocalDate parseDate(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    return normalized == null ? null : LocalDate.parse(normalized);
  }

  private LocalDateTime time(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toLocalDateTime();
  }

  private String text(String value) {
    return value == null ? "" : value.trim();
  }

  private List<String> split(String raw) {
    if (!StringUtils.hasText(raw)) {
      return List.of();
    }
    Set<String> values = new LinkedHashSet<>();
    for (String item : raw.split(",")) {
      String normalized = item == null ? "" : item.trim();
      if (!normalized.isEmpty()) {
        values.add(normalized);
      }
    }
    return List.copyOf(values);
  }

  private record CustomerIssueIllegalRecordView(
      Long projectId,
      String projectName,
      Long issueId,
      Integer issueIid,
      String title,
      String issueState,
      boolean illegal,
      String illegalReason,
      String testingPhase,
      String systemTestLabel,
      String severityLevel,
      String priorityLevel,
      String bugStatus,
      String category,
      String milestoneTitle,
      String authorName,
      String assigneeName,
      List<String> moduleNames,
      List<String> labels,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      LocalDateTime closedAt) {
    IssueScopeContext scopeContext() {
      return new IssueScopeContext(
          projectId, projectName, milestoneTitle, testingPhase, systemTestLabel, createdAt, labels);
    }
  }
}
