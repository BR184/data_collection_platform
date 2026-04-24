package com.data.collection.platform.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

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
             coalesce(is_excluded, false) as is_excluded,
             coalesce(exclusion_reason, '') as exclusion_reason,
             coalesce(is_fixed, false) as is_fixed,
             coalesce(is_regression, false) as is_regression,
             coalesce(is_crash, false) as is_crash,
             coalesce(is_level1_other, false) as is_level1_other,
             coalesce(is_legacy, false) as is_legacy,
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
        IssueFactValueSupport.text(rs.getString("project_name")),
        rs.getLong("issue_id"),
        rs.getInt("issue_iid"),
        IssueFactValueSupport.text(rs.getString("title")),
        IssueFactValueSupport.text(rs.getString("issue_state")),
        IssueFactValueSupport.text(rs.getString("testing_phase")),
        IssueFactValueSupport.text(rs.getString("system_test_label")),
        IssueFactValueSupport.text(rs.getString("severity_level")),
        IssueFactValueSupport.text(rs.getString("priority_level")),
        IssueFactValueSupport.text(rs.getString("bug_status")),
        IssueFactValueSupport.text(rs.getString("category")),
        IssueFactValueSupport.text(rs.getString("reason_category")),
        rs.getBoolean("is_excluded"),
        IssueFactValueSupport.text(rs.getString("exclusion_reason")),
        rs.getBoolean("is_fixed"),
        rs.getBoolean("is_regression"),
        rs.getBoolean("is_crash"),
        rs.getBoolean("is_level1_other"),
        rs.getBoolean("is_legacy"),
        IssueFactValueSupport.text(rs.getString("milestone_title")),
        IssueFactValueSupport.text(rs.getString("author_name")),
        IssueFactValueSupport.text(rs.getString("assignee_name")),
        IssueFactValueSupport.split(rs.getString("module_names")),
        IssueFactValueSupport.split(rs.getString("label_names")),
        rs.getBoolean("delay_issue"),
        IssueFactValueSupport.text(rs.getString("delay_reason")),
        IssueFactValueSupport.text(rs.getString("delay_cause")),
        rs.getBoolean("is_response_delayed"),
        rs.getBoolean("is_resolve_delayed"),
        rs.getBoolean("is_illegal"),
        IssueFactValueSupport.text(rs.getString("illegal_reason")),
        IssueFactValueSupport.time(rs.getTimestamp("created_at_source")),
        IssueFactValueSupport.time(rs.getTimestamp("updated_at_source")),
        IssueFactValueSupport.time(rs.getTimestamp("closed_at_source")));
  }
}
