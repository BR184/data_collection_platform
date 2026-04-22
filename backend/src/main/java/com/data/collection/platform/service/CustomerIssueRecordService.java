package com.data.collection.platform.service;

import com.data.collection.platform.entity.CustomerIssueRecordFilterOptionsResponse;
import com.data.collection.platform.entity.CustomerIssueRecordListResponse;
import com.data.collection.platform.entity.CustomerIssueRecordRowResponse;
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
public class CustomerIssueRecordService {
  private static final String TOPIC_CC_PRODUCT = "cc-product";
  private static final String TOPIC_DELAY = "delay";
  private static final String RULE_VERSION = "customer-issue-records@2026-04-22-v1";
  private static final String FACT_SQL =
      """
      select project_id,
             coalesce(project_name, '') as project_name,
             issue_id,
             issue_iid,
             coalesce(title, '') as title,
             coalesce(issue_state, 'opened') as issue_state,
             coalesce(testing_phase, '') as testing_phase,
             coalesce(system_test_label, '') as system_test_label,
             coalesce(severity_level, '') as severity_level,
             coalesce(priority_level, '') as priority_level,
             coalesce(bug_status, '') as bug_status,
             coalesce(category, '') as category,
             coalesce(reason_category, '') as reason_category,
             coalesce(milestone_title, '') as milestone_title,
             coalesce(author_name, '') as author_name,
             coalesce(assignee_name, '') as assignee_name,
             coalesce(module_names, '') as module_names,
             coalesce(label_names, '') as label_names,
             coalesce(delay_issue, false) as delay_issue,
             coalesce(delay_reason, '') as delay_reason,
             coalesce(delay_cause, '') as delay_cause,
             coalesce(is_response_delayed, false) as is_response_delayed,
             coalesce(is_resolve_delayed, false) as is_resolve_delayed,
             coalesce(is_illegal, false) as is_illegal,
             coalesce(illegal_reason, '') as illegal_reason,
             created_at_source,
             updated_at_source,
             closed_at_source
        from issue_fact
       where deleted = false
      """;

  private final IssueFactQueryService issueFactQueryService;
  private final CustomerIssueScopeProfile customerIssueScopeProfile;

  public CustomerIssueRecordService(
      IssueFactQueryService issueFactQueryService,
      CustomerIssueScopeProfile customerIssueScopeProfile) {
    this.issueFactQueryService = issueFactQueryService;
    this.customerIssueScopeProfile = customerIssueScopeProfile;
  }

  public CustomerIssueRecordListResponse listRecords(
      String topic,
      Long projectId,
      String keyword,
      String issueIid,
      String title,
      String projectName,
      String moduleName,
      String reasonCategory,
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
    String safeTopic = normalizeTopic(topic);

    List<CustomerIssueRecordView> filtered =
        loadTopicScopedViews(safeTopic, projectId).stream()
            .filter(view -> matchesKeyword(view, keyword))
            .filter(view -> matchesIssueIid(view, issueIid))
            .filter(view -> matchesText(view.title(), title))
            .filter(view -> matchesEquals(view.projectName(), projectName))
            .filter(view -> matchesModule(view, moduleName))
            .filter(view -> matchesEquals(view.reasonCategory(), reasonCategory))
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

    PageSlice<CustomerIssueRecordView> pageSlice =
        PageSliceSupport.slice(filtered, safePage, safeSize);
    return new CustomerIssueRecordListResponse(
        pageSlice.records().stream().map(this::toResponse).toList(),
        pageSlice.total(),
        pageSlice.page(),
        pageSlice.size(),
        safeSortField,
        safeSortOrder);
  }

  public CustomerIssueRecordFilterOptionsResponse getFilterOptions(String topic, Long projectId) {
    List<CustomerIssueRecordView> rows = loadTopicScopedViews(normalizeTopic(topic), projectId);
    return new CustomerIssueRecordFilterOptionsResponse(
        toOptions(rows, CustomerIssueRecordView::projectName),
        toOptions(rows.stream().flatMap(view -> view.moduleNames().stream()).toList()),
        toOptions(rows, CustomerIssueRecordView::reasonCategory),
        toOptions(rows, CustomerIssueRecordView::severityLevel),
        toOptions(rows, CustomerIssueRecordView::priorityLevel),
        toOptions(rows, CustomerIssueRecordView::issueState),
        toOptions(rows, CustomerIssueRecordView::bugStatus),
        toOptions(rows, CustomerIssueRecordView::category),
        toOptions(rows, CustomerIssueRecordView::milestoneTitle));
  }

  public StatisticBoardRuleExplanationResponse getRuleExplanation(String topic, Long projectId) {
    String safeTopic = normalizeTopic(topic);
    List<CustomerIssueRecordView> loaded = loadFacts(projectId);
    List<CustomerIssueRecordView> scoped = scopeCustomerIssues(loaded);
    List<CustomerIssueRecordView> topicScoped = applyTopic(scoped, safeTopic);
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

  private List<CustomerIssueRecordView> loadTopicScopedViews(String topic, Long projectId) {
    return applyTopic(scopeCustomerIssues(loadFacts(projectId)), topic);
  }

  private List<CustomerIssueRecordView> applyTopic(List<CustomerIssueRecordView> rows, String topic) {
    if (TOPIC_DELAY.equals(topic)) {
      return rows.stream().filter(CustomerIssueRecordView::delayRelated).toList();
    }
    return rows;
  }

  private List<CustomerIssueRecordView> scopeCustomerIssues(List<CustomerIssueRecordView> rows) {
    return rows.stream()
        .filter(view -> customerIssueScopeProfile.matches(view.scopeContext()))
        .toList();
  }

  private List<CustomerIssueRecordView> loadFacts(Long projectId) {
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

  private CustomerIssueRecordView mapIssueFact(ResultSet rs, int rowNum) throws SQLException {
    return new CustomerIssueRecordView(
        rs.getLong("project_id"),
        text(rs.getString("project_name")),
        rs.getLong("issue_id"),
        rs.getInt("issue_iid"),
        text(rs.getString("title")),
        text(rs.getString("issue_state")),
        text(rs.getString("testing_phase")),
        text(rs.getString("system_test_label")),
        text(rs.getString("severity_level")),
        text(rs.getString("priority_level")),
        text(rs.getString("bug_status")),
        text(rs.getString("category")),
        text(rs.getString("reason_category")),
        text(rs.getString("milestone_title")),
        text(rs.getString("author_name")),
        text(rs.getString("assignee_name")),
        split(rs.getString("module_names")),
        split(rs.getString("label_names")),
        rs.getBoolean("delay_issue"),
        text(rs.getString("delay_reason")),
        text(rs.getString("delay_cause")),
        rs.getBoolean("is_response_delayed"),
        rs.getBoolean("is_resolve_delayed"),
        rs.getBoolean("is_illegal"),
        text(rs.getString("illegal_reason")),
        time(rs.getTimestamp("created_at_source")),
        time(rs.getTimestamp("updated_at_source")),
        time(rs.getTimestamp("closed_at_source")));
  }

  private CustomerIssueRecordRowResponse toResponse(CustomerIssueRecordView view) {
    return new CustomerIssueRecordRowResponse(
        view.issueId(),
        view.issueIid(),
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

  private boolean matchesKeyword(CustomerIssueRecordView view, String keyword) {
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

  private boolean matchesIssueIid(CustomerIssueRecordView view, String issueIid) {
    String normalized = TextQuerySupport.trimToNull(issueIid);
    return normalized == null
        || TextQuerySupport.containsIgnoreCase(String.valueOf(view.issueIid()), normalized);
  }

  private boolean matchesText(String source, String query) {
    return TextQuerySupport.containsAbstractSearch(source, query);
  }

  private boolean matchesModule(CustomerIssueRecordView view, String moduleName) {
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

  private Comparator<CustomerIssueRecordView> buildComparator(String sortField, String sortOrder) {
    Comparator<CustomerIssueRecordView> comparator =
        switch (sortField) {
          case "issueIid" -> SortSupport.nullableComparable(CustomerIssueRecordView::issueIid);
          case "title" -> SortSupport.nullableString(CustomerIssueRecordView::title);
          case "projectName" -> SortSupport.nullableString(CustomerIssueRecordView::projectName);
          case "moduleNames" -> SortSupport.nullableString(view -> String.join("、", view.moduleNames()));
          case "reasonCategory" -> SortSupport.nullableString(CustomerIssueRecordView::reasonCategory);
          case "severityLevel" -> SortSupport.nullableString(CustomerIssueRecordView::severityLevel);
          case "priorityLevel" -> SortSupport.nullableString(CustomerIssueRecordView::priorityLevel);
          case "bugStatus" -> SortSupport.nullableString(CustomerIssueRecordView::bugStatus);
          case "issueState" -> SortSupport.nullableString(CustomerIssueRecordView::issueState);
          case "authorName" -> SortSupport.nullableString(CustomerIssueRecordView::authorName);
          case "assigneeName" -> SortSupport.nullableString(CustomerIssueRecordView::assigneeName);
          case "category" -> SortSupport.nullableString(CustomerIssueRecordView::category);
          case "milestoneTitle" -> SortSupport.nullableString(CustomerIssueRecordView::milestoneTitle);
          case "createdAt" -> SortSupport.nullableComparable(CustomerIssueRecordView::createdAt);
          case "closedAt" -> SortSupport.nullableComparable(CustomerIssueRecordView::closedAt);
          default -> SortSupport.nullableComparable(CustomerIssueRecordView::updatedAt);
        };
    comparator = comparator.thenComparing(CustomerIssueRecordView::issueIid);
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
          "reasonCategory",
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

  private StatisticRuleFlowStep step(
      String key, String title, String description, List<CustomerIssueRecordView> output, long inputCount) {
    return new StatisticRuleFlowStep(key, title, description, inputCount, output.size(), sample(output));
  }

  private List<StatisticRuleFlowStepSample> sample(List<CustomerIssueRecordView> rows) {
    return rows.stream()
        .limit(3)
        .map(row -> new StatisticRuleFlowStepSample("#" + row.issueIid() + " " + row.projectName(), row.title()))
        .toList();
  }

  private List<OptionItemResponse> toOptions(
      List<CustomerIssueRecordView> rows,
      java.util.function.Function<CustomerIssueRecordView, String> extractor) {
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

  private record CustomerIssueRecordView(
      Long projectId,
      String projectName,
      Long issueId,
      Integer issueIid,
      String title,
      String issueState,
      String testingPhase,
      String systemTestLabel,
      String severityLevel,
      String priorityLevel,
      String bugStatus,
      String category,
      String reasonCategory,
      String milestoneTitle,
      String authorName,
      String assigneeName,
      List<String> moduleNames,
      List<String> labels,
      boolean delayIssue,
      String delayReason,
      String delayCause,
      boolean responseDelayed,
      boolean resolveDelayed,
      boolean illegal,
      String illegalReason,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      LocalDateTime closedAt) {
    IssueScopeContext scopeContext() {
      return new IssueScopeContext(
          projectId, projectName, milestoneTitle, testingPhase, systemTestLabel, createdAt, labels);
    }

    boolean delayRelated() {
      return delayIssue || responseDelayed || resolveDelayed;
    }
  }
}
