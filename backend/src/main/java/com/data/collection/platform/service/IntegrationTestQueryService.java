package com.data.collection.platform.service;

import com.data.collection.platform.entity.IntegrationTestDetailResponse;
import com.data.collection.platform.entity.IntegrationTestDetailRowResponse;
import com.data.collection.platform.entity.IntegrationTestPhaseOptionResponse;
import com.data.collection.platform.entity.IntegrationTestProjectOptionResponse;
import com.data.collection.platform.entity.IntegrationTestSummaryResponse;
import com.data.collection.platform.entity.IntegrationTestSummaryRowResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class IntegrationTestQueryService {
  private static final String UNKNOWN_MODULE = "未识别模块";

  private static final Map<String, String> DETAIL_SORT_FIELDS =
      Map.ofEntries(
          Map.entry("issueIid", "issue_iid"),
          Map.entry("title", "title"),
          Map.entry("moduleName", "module_name"),
          Map.entry("functionName", "function_name"),
          Map.entry("executor", "executor"),
          Map.entry("executeCase", "execute_case"),
          Map.entry("passCase", "pass_case"),
          Map.entry("notPassCaseNow", "not_pass_case_now"),
          Map.entry("problemCase", "problem_case"),
          Map.entry("exceptionCount", "exception_count"),
          Map.entry("passRate", "pass_rate"),
          Map.entry("legal", "legal"),
          Map.entry("noteUpdatedAt", "note_updated_at_source"),
          Map.entry("updatedAtSource", "updated_at_source"));

  private final JdbcTemplate jdbcTemplate;

  public IntegrationTestQueryService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<IntegrationTestProjectOptionResponse> listProjectOptions() {
    return jdbcTemplate.query(
        """
        select project_id, max(coalesce(project_name, '')) as project_name
          from integration_test_fact
         where deleted = false
         group by project_id
         order by project_id asc
        """,
        (rs, rowNum) ->
            new IntegrationTestProjectOptionResponse(
                rs.getLong("project_id"),
                TextQuerySupport.normalizeDisplay(rs.getString("project_name"))));
  }

  public List<IntegrationTestPhaseOptionResponse> listPhaseOptions(Long projectId) {
    List<Object> args = new ArrayList<>();
    StringBuilder sql =
        new StringBuilder(
            """
            select project_id,
                   max(coalesce(project_name, '')) as project_name,
                   testing_phase,
                   count(*) as record_count
              from integration_test_fact
             where deleted = false
               and testing_phase is not null
               and btrim(testing_phase) <> ''
            """);
    if (projectId != null) {
      sql.append(" and project_id = ?");
      args.add(projectId);
    }
    sql.append(
        """
         group by project_id, testing_phase
         order by max(note_updated_at_source) desc nulls last, project_id asc, testing_phase asc
        """);
    return jdbcTemplate.query(
        sql.toString(),
        (rs, rowNum) ->
            new IntegrationTestPhaseOptionResponse(
                rs.getLong("project_id"),
                TextQuerySupport.normalizeDisplay(rs.getString("project_name")),
                TextQuerySupport.normalizeDisplay(rs.getString("testing_phase")),
                rs.getLong("record_count")),
        args.toArray());
  }

  public IntegrationTestSummaryResponse getSummary(Long projectId, String testingPhase) {
    List<Object> args = new ArrayList<>();
    StringBuilder where =
        new StringBuilder(
            """
            from integration_test_fact
             where deleted = false
            """);
    appendScopedFilters(where, args, projectId, testingPhase, null);

    List<IntegrationTestSummaryRowResponse> rows =
        jdbcTemplate.query(
            """
            select coalesce(module_name, '未识别模块') as module_name,
                   count(*) as issue_count,
                   coalesce(sum(execute_case), 0) as execute_case,
                   coalesce(sum(pass_case), 0) as pass_case,
                   coalesce(sum(not_pass_case), 0) as not_pass_case,
                   coalesce(sum(not_pass_case_now), 0) as not_pass_case_now,
                   coalesce(sum(problem_case), 0) as problem_case,
                   coalesce(sum(exception_count), 0) as exception_count,
                   case
                     when coalesce(sum(execute_case), 0) = 0 then 0
                     else round(sum(pass_case)::numeric * 100 / nullif(sum(execute_case), 0), 2)
                   end as pass_rate,
                   coalesce(sum(case when legal = false then 1 else 0 end), 0) as illegal_count
            """
                + where
                + """
                 group by coalesce(module_name, '未识别模块')
                 order by lower(coalesce(module_name, '未识别模块')) asc
                """,
            this::mapSummaryRow,
            args.toArray());

    Long totalIssueCount =
        jdbcTemplate.queryForObject("select count(*) " + where, Long.class, args.toArray());
    LocalDateTime factRefreshedAt =
        jdbcTemplate.queryForObject(
            "select max(fact_refreshed_at) " + where,
            LocalDateTime.class,
            args.toArray());
    return new IntegrationTestSummaryResponse(
        projectId,
        TextQuerySupport.trimToNull(testingPhase),
        rows.size(),
        totalIssueCount == null ? 0 : totalIssueCount,
        factRefreshedAt,
        rows);
  }

  public IntegrationTestDetailResponse getDetails(
      Long projectId,
      String testingPhase,
      String moduleName,
      int page,
      int size,
      String sortField,
      String sortOrder) {
    int safePage = page <= 0 ? 1 : page;
    int safeSize = size <= 0 ? 20 : Math.min(size, 100);
    String safeSortField = normalizeSortField(sortField);
    String safeSortOrder = normalizeSortOrder(sortOrder);

    List<Object> args = new ArrayList<>();
    StringBuilder where =
        new StringBuilder(
            """
            from integration_test_fact
             where deleted = false
            """);
    appendScopedFilters(where, args, projectId, testingPhase, moduleName);

    List<Object> queryArgs = new ArrayList<>(args);
    queryArgs.add(safeSize);
    queryArgs.add((safePage - 1) * safeSize);
    List<IntegrationTestDetailRowResponse> rows =
        jdbcTemplate.query(
            """
            select issue_id,
                   issue_iid,
                   issuable_reference,
                   project_id,
                   project_name,
                   title,
                   coalesce(module_name, '未识别模块') as module_name,
                   function_name,
                   function_labels,
                   executor,
                   execute_case,
                   pass_case,
                   not_pass_case,
                   not_pass_case_now,
                   problem_case,
                   exception_count,
                   pass_rate,
                   legal,
                   issue_state,
                   author_name,
                   assignee_name,
                   note_updated_at_source,
                   updated_at_source
            """
                + where
                + " order by "
                + DETAIL_SORT_FIELDS.get(safeSortField)
                + " "
                + safeSortOrder
                + " nulls last, issue_iid asc limit ? offset ?",
            this::mapDetailRow,
            queryArgs.toArray());

    Long total =
        jdbcTemplate.queryForObject("select count(*) " + where, Long.class, args.toArray());
    return new IntegrationTestDetailResponse(
        rows,
        total == null ? 0 : total,
        safePage,
        safeSize,
        safeSortField,
        safeSortOrder);
  }

  private void appendScopedFilters(
      StringBuilder where,
      List<Object> args,
      Long projectId,
      String testingPhase,
      String moduleName) {
    if (projectId != null) {
      where.append(" and project_id = ?");
      args.add(projectId);
    }
    String normalizedPhase = TextQuerySupport.trimToNull(testingPhase);
    if (normalizedPhase != null) {
      where.append(" and testing_phase = ?");
      args.add(normalizedPhase);
    }
    String normalizedModule = TextQuerySupport.trimToNull(moduleName);
    if (normalizedModule != null) {
      where.append(" and coalesce(module_name, '").append(UNKNOWN_MODULE).append("') = ?");
      args.add(normalizedModule);
    }
  }

  private IntegrationTestSummaryRowResponse mapSummaryRow(ResultSet rs, int rowNum)
      throws SQLException {
    return new IntegrationTestSummaryRowResponse(
        TextQuerySupport.normalizeDisplay(rs.getString("module_name")),
        rs.getLong("issue_count"),
        rs.getInt("execute_case"),
        rs.getInt("pass_case"),
        rs.getInt("not_pass_case"),
        rs.getInt("not_pass_case_now"),
        rs.getInt("problem_case"),
        rs.getInt("exception_count"),
        rs.getBigDecimal("pass_rate"),
        rs.getLong("illegal_count"));
  }

  private IntegrationTestDetailRowResponse mapDetailRow(ResultSet rs, int rowNum)
      throws SQLException {
    return new IntegrationTestDetailRowResponse(
        rs.getLong("issue_id"),
        rs.getLong("issue_iid"),
        TextQuerySupport.normalizeDisplay(rs.getString("issuable_reference")),
        rs.getLong("project_id"),
        TextQuerySupport.normalizeDisplay(rs.getString("project_name")),
        TextQuerySupport.normalizeDisplay(rs.getString("title")),
        TextQuerySupport.normalizeDisplay(rs.getString("module_name")),
        TextQuerySupport.normalizeDisplay(rs.getString("function_name")),
        TextQuerySupport.normalizeDisplay(rs.getString("function_labels")),
        TextQuerySupport.normalizeDisplay(rs.getString("executor")),
        getInteger(rs, "execute_case"),
        getInteger(rs, "pass_case"),
        getInteger(rs, "not_pass_case"),
        getInteger(rs, "not_pass_case_now"),
        getInteger(rs, "problem_case"),
        getInteger(rs, "exception_count"),
        rs.getBigDecimal("pass_rate"),
        rs.getObject("legal", Boolean.class),
        TextQuerySupport.normalizeDisplay(rs.getString("issue_state")),
        TextQuerySupport.normalizeDisplay(rs.getString("author_name")),
        TextQuerySupport.normalizeDisplay(rs.getString("assignee_name")),
        toLocalDateTime(rs.getTimestamp("note_updated_at_source")),
        toLocalDateTime(rs.getTimestamp("updated_at_source")));
  }

  private Integer getInteger(ResultSet rs, String column) throws SQLException {
    Object value = rs.getObject(column);
    return value instanceof Integer integer ? integer : null;
  }

  private LocalDateTime toLocalDateTime(Timestamp value) {
    return value == null ? null : value.toLocalDateTime();
  }

  private String normalizeSortField(String sortField) {
    String normalized = TextQuerySupport.trimToNull(sortField);
    return normalized == null || !DETAIL_SORT_FIELDS.containsKey(normalized)
        ? "noteUpdatedAt"
        : normalized;
  }

  private String normalizeSortOrder(String sortOrder) {
    return "asc".equalsIgnoreCase(sortOrder) ? "asc" : "desc";
  }
}
