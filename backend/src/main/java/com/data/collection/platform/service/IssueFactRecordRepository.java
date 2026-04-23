package com.data.collection.platform.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class IssueFactRecordRepository {
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

  public IssueFactRecordRepository(IssueFactQueryService issueFactQueryService) {
    this.issueFactQueryService = issueFactQueryService;
  }

  public List<IssueFactRecord> findByProjectId(Long projectId) {
    Map<String, String> filters = new LinkedHashMap<>();
    if (projectId != null) {
      filters.put("projectId", String.valueOf(projectId));
    }
    return findByFilters(filters);
  }

  public List<IssueFactRecord> findByFilters(Map<String, String> filters) {
    try {
      return issueFactQueryService.query(
          FACT_SQL, filters == null ? Map.of() : filters, this::mapIssueFact);
    } catch (DataAccessException error) {
      return List.of();
    }
  }

  private IssueFactRecord mapIssueFact(ResultSet rs, int rowNum) throws SQLException {
    return new IssueFactRecord(
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
}
