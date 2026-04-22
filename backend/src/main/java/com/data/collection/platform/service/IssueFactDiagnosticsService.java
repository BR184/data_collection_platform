package com.data.collection.platform.service;

import com.data.collection.platform.entity.IssueFactCountBreakdownResponse;
import com.data.collection.platform.entity.IssueFactDiagnosticsResponse;
import com.data.collection.platform.entity.IssueFactScopeDiagnosticsResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class IssueFactDiagnosticsService {
  private static final String FACT_SQL = """
      select project_id,
             coalesce(project_name, '') as project_name,
             coalesce(milestone_title, '') as milestone_title,
             coalesce(testing_phase, '') as testing_phase,
             coalesce(system_test_label, '') as system_test_label,
             coalesce(reason_category, '') as reason_category,
             coalesce(has_response, false) as has_response,
             coalesce(is_response_delayed, false) as is_response_delayed,
             coalesce(is_resolve_delayed, false) as is_resolve_delayed,
             coalesce(label_names, '') as label_names,
             created_at_source
        from issue_fact
       where deleted = false
      """;

  private final IssueFactQueryService issueFactQueryService;
  private final SystemTestScopeProfile systemTestScopeProfile;
  private final CustomerIssueScopeProfile customerIssueScopeProfile;

  public IssueFactDiagnosticsService(
      IssueFactQueryService issueFactQueryService,
      SystemTestScopeProfile systemTestScopeProfile,
      CustomerIssueScopeProfile customerIssueScopeProfile) {
    this.issueFactQueryService = issueFactQueryService;
    this.systemTestScopeProfile = systemTestScopeProfile;
    this.customerIssueScopeProfile = customerIssueScopeProfile;
  }

  public IssueFactDiagnosticsResponse getDiagnostics() {
    List<IssueFactDiagnosticView> views =
        issueFactQueryService.query(FACT_SQL, Map.of(), this::mapIssueFact);
    List<IssueFactDiagnosticView> systemTest =
        views.stream()
            .filter(view -> systemTestScopeProfile.matches(view.scopeContext()))
            .toList();
    List<IssueFactDiagnosticView> customerIssue =
        views.stream()
            .filter(view -> customerIssueScopeProfile.matches(view.scopeContext()))
            .toList();
    return new IssueFactDiagnosticsResponse(
        LocalDateTime.now(),
        summarize("overall", "全部 Issue Fact", views),
        summarize("system-test", "系统测试", systemTest),
        summarize("customer-issue", "客户问题", customerIssue),
        breakdown(views, IssueFactDiagnosticView::reasonCategory, "<empty>", "未归因"),
        breakdown(customerIssue, IssueFactDiagnosticView::reasonCategory, "<empty>", "未归因"),
        breakdown(customerIssue, IssueFactDiagnosticView::projectName, "<empty>", "未识别项目"));
  }

  private IssueFactScopeDiagnosticsResponse summarize(
      String scopeKey, String scopeLabel, List<IssueFactDiagnosticView> views) {
    return new IssueFactScopeDiagnosticsResponse(
        scopeKey,
        scopeLabel,
        views.size(),
        views.stream().filter(IssueFactDiagnosticView::hasReasonCategory).count(),
        views.stream().filter(IssueFactDiagnosticView::hasMilestoneTitle).count(),
        views.stream().filter(IssueFactDiagnosticView::hasTemplateReply).count(),
        views.stream().filter(IssueFactDiagnosticView::responseDelayed).count(),
        views.stream().filter(IssueFactDiagnosticView::resolveDelayed).count());
  }

  private List<IssueFactCountBreakdownResponse> breakdown(
      List<IssueFactDiagnosticView> views,
      Function<IssueFactDiagnosticView, String> classifier,
      String emptyKey,
      String emptyLabel) {
    Map<String, Long> counts = new LinkedHashMap<>();
    Map<String, String> labels = new LinkedHashMap<>();
    for (IssueFactDiagnosticView view : views) {
      String value = classifier.apply(view);
      String key = StringUtils.hasText(value) ? value.trim() : emptyKey;
      String label = StringUtils.hasText(value) ? value.trim() : emptyLabel;
      counts.merge(key, 1L, Long::sum);
      labels.putIfAbsent(key, label);
    }
    return counts.entrySet().stream()
        .sorted(
            Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry::getKey))
        .map(
            entry ->
                new IssueFactCountBreakdownResponse(
                    entry.getKey(), labels.get(entry.getKey()), entry.getValue()))
        .toList();
  }

  private IssueFactDiagnosticView mapIssueFact(ResultSet rs, int rowNum) throws SQLException {
    return new IssueFactDiagnosticView(
        rs.getLong("project_id"),
        text(rs.getString("project_name")),
        text(rs.getString("milestone_title")),
        text(rs.getString("testing_phase")),
        text(rs.getString("system_test_label")),
        text(rs.getString("reason_category")),
        rs.getBoolean("has_response"),
        rs.getBoolean("is_response_delayed"),
        rs.getBoolean("is_resolve_delayed"),
        split(rs.getString("label_names")),
        time(rs.getTimestamp("created_at_source")));
  }

  private String text(String value) {
    return StringUtils.hasText(value) ? value.trim() : "";
  }

  private LocalDateTime time(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toLocalDateTime();
  }

  private List<String> split(String raw) {
    if (!StringUtils.hasText(raw)) {
      return List.of();
    }
    Set<String> values = new LinkedHashSet<>();
    for (String value : raw.split(",")) {
      String trimmed = value == null ? "" : value.trim();
      if (!trimmed.isEmpty()) {
        values.add(trimmed);
      }
    }
    return List.copyOf(values);
  }

  private record IssueFactDiagnosticView(
      Long projectId,
      String projectName,
      String milestoneTitle,
      String testingPhase,
      String systemTestLabel,
      String reasonCategory,
      boolean hasTemplateReply,
      boolean responseDelayed,
      boolean resolveDelayed,
      List<String> labels,
      LocalDateTime createdAt) {
    IssueScopeContext scopeContext() {
      return new IssueScopeContext(
          projectId,
          projectName,
          milestoneTitle,
          testingPhase,
          systemTestLabel,
          createdAt,
          labels);
    }

    boolean hasReasonCategory() {
      return StringUtils.hasText(reasonCategory);
    }

    boolean hasMilestoneTitle() {
      return StringUtils.hasText(milestoneTitle);
    }
  }
}
