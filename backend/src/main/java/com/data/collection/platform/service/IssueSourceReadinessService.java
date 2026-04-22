package com.data.collection.platform.service;

import com.data.collection.platform.entity.IssueFactCountBreakdownResponse;
import com.data.collection.platform.entity.IssueSourceReadinessResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class IssueSourceReadinessService {
  private static final String PROJECT_COUNT_SQL = """
      select count(*)
        from ods_gitlab_projects
       where coalesce(mirror_deleted, false) = false
      """;

  private static final String ISSUE_COUNT_SQL = """
      select count(*)
        from ods_gitlab_issues
       where coalesce(mirror_deleted, false) = false
      """;

  private static final String MILESTONE_COUNT_SQL = """
      select count(*)
        from ods_gitlab_milestones
       where coalesce(mirror_deleted, false) = false
      """;

  private static final String ISSUES_WITH_MILESTONE_SQL = """
      select count(*)
        from ods_gitlab_issues
       where coalesce(mirror_deleted, false) = false
         and milestone_id is not null
      """;

  private static final String CUSTOMER_PROJECT_COUNT_SQL = """
      select count(*)
        from ods_gitlab_projects p
       where coalesce(p.mirror_deleted, false) = false
         and (
           lower(coalesce(p.name, '')) like '%cc_product%'
           or lower(coalesce(p.name, '')) like '%cc-product%'
           or lower(coalesce(p.name, '')) like '%ccproduct%'
           or lower(coalesce(p.path, '')) like '%cc_product%'
           or lower(coalesce(p.path, '')) like '%cc-product%'
           or lower(coalesce(p.path, '')) like '%ccproduct%'
         )
      """;

  private static final String CUSTOMER_PROJECT_ISSUE_COUNT_SQL = """
      select count(*)
        from ods_gitlab_issues i
        join ods_gitlab_projects p
          on p.id = i.project_id
         and coalesce(p.mirror_deleted, false) = false
       where coalesce(i.mirror_deleted, false) = false
         and (
           lower(coalesce(p.name, '')) like '%cc_product%'
           or lower(coalesce(p.name, '')) like '%cc-product%'
           or lower(coalesce(p.name, '')) like '%ccproduct%'
           or lower(coalesce(p.path, '')) like '%cc_product%'
           or lower(coalesce(p.path, '')) like '%cc-product%'
           or lower(coalesce(p.path, '')) like '%ccproduct%'
         )
      """;

  private static final String CUSTOMER_LABEL_ISSUE_COUNT_SQL = """
      select count(distinct ll.target_id)
        from ods_gitlab_label_links ll
        join ods_gitlab_labels l
          on l.id = ll.label_id
         and coalesce(l.mirror_deleted, false) = false
       where coalesce(ll.mirror_deleted, false) = false
         and ll.target_type = 'Issue'
         and (
           lower(coalesce(l.title, '')) like '%cc_product%'
           or lower(coalesce(l.title, '')) like '%cc-product%'
           or lower(coalesce(l.title, '')) like '%ccproduct%'
         )
      """;

  private static final String SYSTEM_TEST_ISSUE_COUNT_SQL = """
      select count(distinct ll.target_id)
        from ods_gitlab_label_links ll
        join ods_gitlab_labels l
          on l.id = ll.label_id
         and coalesce(l.mirror_deleted, false) = false
       where coalesce(ll.mirror_deleted, false) = false
         and ll.target_type = 'Issue'
         and (
           coalesce(l.title, '') like '%系统测试%'
           or coalesce(l.title, '') like '%回归测试%'
         )
      """;

  private static final String TOP_PROJECTS_SQL = """
      select i.project_id,
             coalesce(p.name, '未命名项目') as project_name,
             count(*) as issue_count
        from ods_gitlab_issues i
        left join ods_gitlab_projects p
          on p.id = i.project_id
         and coalesce(p.mirror_deleted, false) = false
       where coalesce(i.mirror_deleted, false) = false
       group by i.project_id, coalesce(p.name, '未命名项目')
       order by issue_count desc, i.project_id asc
       limit 10
      """;

  private final JdbcTemplate jdbcTemplate;

  public IssueSourceReadinessService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public IssueSourceReadinessResponse getReadiness() {
    List<String> warnings = new ArrayList<>();
    return new IssueSourceReadinessResponse(
        LocalDateTime.now(),
        safeCount(PROJECT_COUNT_SQL, "ods_gitlab_projects", warnings),
        safeCount(ISSUE_COUNT_SQL, "ods_gitlab_issues", warnings),
        safeCount(MILESTONE_COUNT_SQL, "ods_gitlab_milestones", warnings),
        safeCount(ISSUES_WITH_MILESTONE_SQL, "ods_gitlab_issues.milestone_id", warnings),
        safeCount(CUSTOMER_PROJECT_COUNT_SQL, "customer issue projects", warnings),
        safeCount(CUSTOMER_PROJECT_ISSUE_COUNT_SQL, "customer issue project matches", warnings),
        safeCount(CUSTOMER_LABEL_ISSUE_COUNT_SQL, "customer issue labels", warnings),
        safeCount(SYSTEM_TEST_ISSUE_COUNT_SQL, "system test labels", warnings),
        safeTopProjects(warnings),
        List.copyOf(warnings));
  }

  private long safeCount(String sql, String metricName, List<String> warnings) {
    try {
      Long value = jdbcTemplate.queryForObject(sql, Long.class);
      return value == null ? 0L : value;
    } catch (DataAccessException error) {
      warnings.add(metricName + " unavailable: " + rootMessage(error));
      return 0L;
    }
  }

  private List<IssueFactCountBreakdownResponse> safeTopProjects(List<String> warnings) {
    try {
      return jdbcTemplate.query(
          TOP_PROJECTS_SQL,
          (rs, rowNum) ->
              new IssueFactCountBreakdownResponse(
                  String.valueOf(rs.getLong("project_id")),
                  text(rs.getString("project_name"), "未命名项目"),
                  rs.getLong("issue_count")));
    } catch (DataAccessException error) {
      warnings.add("top issue projects unavailable: " + rootMessage(error));
      return List.of();
    }
  }

  private String text(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private String rootMessage(DataAccessException error) {
    Throwable cause = error.getMostSpecificCause();
    String message = cause == null ? error.getMessage() : cause.getMessage();
    return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
  }
}
