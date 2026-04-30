package com.data.collection.platform.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class IssueFactRecordRepository {
  private static final String FACT_SELECT_SQL =
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
      """;
  private static final String FACT_SQL = FACT_SELECT_SQL + " where deleted = false";
  private static final List<String> SYSTEM_TEST_SCOPE_TOKENS =
      List.of("\u7cfb\u7edf\u6d4b\u8bd5", "\u56de\u5f52\u6d4b\u8bd5");
  private static final List<String> CUSTOMER_SCOPE_TOKENS =
      List.of("cc_product", "cc-product", "ccproduct");
  private static final long LEGACY_CC_PRODUCT_PROJECT_ID = 325L;
  private static final LocalDate CUSTOMER_ISSUE_START_DATE = LocalDate.of(2026, 1, 1);
  private static final Map<String, String> SORT_COLUMNS = createSortColumns();

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

  public PageSlice<IssueFactRecord> findPage(IssueFactRecordPageQuery query) {
    QueryParts parts = buildPageQuery(query);
    try {
      long total = issueFactQueryService.count("select count(*) from issue_fact" + parts.where(), parts.args());
      if (total == 0) {
        return new PageSlice<>(List.of(), 0, query.page(), query.size());
      }
      List<Object> pageArgs = new ArrayList<>(parts.args());
      pageArgs.add(query.size());
      pageArgs.add((long) (query.page() - 1) * query.size());
      List<IssueFactRecord> records =
          issueFactQueryService.query(
              FACT_SELECT_SQL
                  + parts.where()
                  + " order by "
                  + sortColumn(query.sortField())
                  + " "
                  + sortOrder(query.sortOrder())
                  + nullsClause(query.sortOrder())
                  + ", issue_iid "
                  + sortOrder(query.sortOrder())
                  + " limit ? offset ?",
              pageArgs,
              this::mapIssueFact);
      return new PageSlice<>(records, total, query.page(), query.size());
    } catch (DataAccessException error) {
      return new PageSlice<>(List.of(), 0, query.page(), query.size());
    }
  }

  private QueryParts buildPageQuery(IssueFactRecordPageQuery query) {
    StringBuilder where = new StringBuilder(" where deleted = false");
    List<Object> args = new ArrayList<>();
    appendScope(where, args, query.scope());
    appendBaseFilters(where, args, query.listRequest(), query.useDisplayModuleFilter());
    appendEqIgnoreCase(where, args, "reason_category", query.reasonCategory());
    appendEqIgnoreCase(where, args, "phase_filter_value", query.testingPhase());
    appendAuthorAssigneeFilters(where, args, query.authorName(), query.assigneeName());
    appendIllegalFilters(where, args, query);
    appendFilterGroup(where, args, query.filterGroup());
    if (query.delayOnly()) {
      where.append(" and (delay_issue = true or is_response_delayed = true or is_resolve_delayed = true)");
    }
    if (query.excludeExcluded()) {
      where.append(" and is_excluded = false");
    }
    return new QueryParts(where.toString(), args);
  }

  private void appendScope(
      StringBuilder where, List<Object> args, IssueFactRecordPageQuery.Scope scope) {
    if (scope == IssueFactRecordPageQuery.Scope.SYSTEM_TEST) {
      appendSystemTestScope(where, args);
      return;
    }
    where.append(" and not (");
    appendSystemTestScopeExpression(where, args);
    where.append(")");
    where.append(" and (project_id = ?");
    args.add(LEGACY_CC_PRODUCT_PROJECT_ID);
    for (String token : CUSTOMER_SCOPE_TOKENS) {
      where.append(" or lower(coalesce(project_name, '')) like ?");
      args.add("%" + token + "%");
      where.append(" or lower(coalesce(milestone_title, '')) like ?");
      args.add("%" + token + "%");
      where.append(" or lower(coalesce(label_names, '')) like ?");
      args.add("%" + token + "%");
    }
    where.append(")");
    where.append(" and (created_at_source is null or created_at_source >= ?)");
    args.add(CUSTOMER_ISSUE_START_DATE.atStartOfDay());
  }

  private void appendSystemTestScope(StringBuilder where, List<Object> args) {
    where.append(" and (");
    appendSystemTestScopeExpression(where, args);
    where.append(")");
  }

  private void appendSystemTestScopeExpression(StringBuilder where, List<Object> args) {
    boolean first = true;
    for (String token : SYSTEM_TEST_SCOPE_TOKENS) {
      if (!first) {
        where.append(" or ");
      }
      first = false;
      where.append("lower(coalesce(testing_phase, '')) like ?");
      args.add("%" + token + "%");
      where.append(" or lower(coalesce(system_test_label, '')) like ?");
      args.add("%" + token + "%");
      where.append(" or lower(coalesce(label_names, '')) like ?");
      args.add("%" + token + "%");
    }
  }

  private void appendBaseFilters(
      StringBuilder where,
      List<Object> args,
      IssueFactRecordListRequest request,
      boolean useDisplayModuleFilter) {
    if (request == null) {
      return;
    }
    appendEq(where, args, "project_id", request.projectId());
    appendIndexedSearch(
        where,
        args,
        List.of("search_text", "search_compact", "search_spell", "search_initials"),
        request.keyword());
    appendIssueIid(where, args, request.issueIid());
    appendIndexedSearch(
        where,
        args,
        List.of(
            "title_search_text",
            "title_search_compact",
            "title_search_spell",
            "title_search_initials"),
        request.title());
    appendEqIgnoreCase(where, args, "project_name", request.projectName());
    appendModuleFilter(where, args, request.moduleName(), useDisplayModuleFilter);
    appendEqIgnoreCase(where, args, "severity_level", request.severityLevel());
    appendEqIgnoreCase(where, args, "priority_level", request.priorityLevel());
    appendEqIgnoreCase(where, args, "issue_state", request.issueState());
    appendEqIgnoreCase(where, args, "bug_status", request.bugStatus());
    appendEqIgnoreCase(where, args, "category", request.category());
    appendEqIgnoreCase(where, args, "milestone_title", request.milestoneTitle());
    appendDateFrom(where, args, "created_at_source", request.createdAtStart());
    appendDateTo(where, args, "created_at_source", request.createdAtEnd());
    appendDateFrom(where, args, "updated_at_source", request.updatedAtStart());
    appendDateTo(where, args, "updated_at_source", request.updatedAtEnd());
  }

  private void appendAuthorAssigneeFilters(
      StringBuilder where, List<Object> args, String authorName, String assigneeName) {
    appendEqIgnoreCase(where, args, "author_name", authorName);
    appendEqIgnoreCase(where, args, "assignee_name", assigneeName);
  }

  private void appendFilterGroup(
      StringBuilder where,
      List<Object> args,
      com.data.collection.platform.entity.statistics.StatisticFilterGroup filterGroup) {
    IssueFactFilterGroupSqlSupport.toSql(filterGroup)
        .filter(filter -> TextQuerySupport.trimToNull(filter.predicate()) != null)
        .ifPresent(
            filter -> {
              where.append(" and (").append(filter.predicate()).append(")");
              args.addAll(filter.args());
            });
  }

  private void appendIllegalFilters(
      StringBuilder where, List<Object> args, IssueFactRecordPageQuery query) {
    if (!query.illegalOnly()) {
      return;
    }
    where.append(" and is_illegal = true");
    if (query.supportedSystemIllegalReasonsOnly()) {
      appendIn(where, args, "illegal_reason", SystemTestIllegalReasonSupport.supportedRawReasons());
      List<String> rawReasons = SystemTestIllegalReasonSupport.rawReasonsFor(query.illegalReason());
      if (!rawReasons.isEmpty()) {
        appendIn(where, args, "illegal_reason", rawReasons);
      }
      return;
    }
    appendEqIgnoreCase(where, args, "illegal_reason", query.illegalReason());
  }

  private void appendEq(StringBuilder where, List<Object> args, String column, Long value) {
    if (value == null) {
      return;
    }
    where.append(" and ").append(column).append(" = ?");
    args.add(value);
  }

  private void appendEqIgnoreCase(StringBuilder where, List<Object> args, String column, String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return;
    }
    where.append(" and lower(coalesce(").append(column).append(", '')) = ?");
    args.add(normalized.toLowerCase(java.util.Locale.ROOT));
  }

  private void appendContainsIgnoreCase(
      StringBuilder where, List<Object> args, String column, String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return;
    }
    where.append(" and lower(coalesce(").append(column).append(", '')) like ?");
    args.add("%" + normalized.toLowerCase(java.util.Locale.ROOT) + "%");
  }

  private void appendIndexedSearch(
      StringBuilder where, List<Object> args, List<String> columns, String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return;
    }
    List<String> candidates = FactSearchIndexSupport.keywordCandidates(normalized);
    if (candidates.isEmpty()) {
      return;
    }
    List<String> predicates = new ArrayList<>();
    for (String candidate : candidates) {
      String pattern = "%" + candidate + "%";
      for (String column : columns) {
        predicates.add(column + " like ?");
        args.add(pattern);
      }
    }
    where.append(" and (").append(String.join(" or ", predicates)).append(")");
  }

  private void appendIssueIid(StringBuilder where, List<Object> args, String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return;
    }
    where.append(" and cast(issue_iid as varchar) like ?");
    args.add("%" + normalized + "%");
  }

  private void appendModuleFilter(
      StringBuilder where, List<Object> args, String moduleName, boolean useDisplayModuleFilter) {
    String normalized = TextQuerySupport.trimToNull(moduleName);
    if (normalized == null) {
      return;
    }
    if (useDisplayModuleFilter
        && SystemTestIllegalReasonSupport.MISSING_MODULE.equals(normalized)) {
      where.append(" and (module_names is null or btrim(module_names) = '')");
      return;
    }
    where.append(" and lower(',' || replace(coalesce(module_names, ''), ', ', ',') || ',') like ?");
    args.add("%," + normalized.toLowerCase(java.util.Locale.ROOT) + ",%");
  }

  private void appendDateFrom(
      StringBuilder where, List<Object> args, String column, String rawValue) {
    LocalDate value = parseDate(rawValue);
    if (value == null) {
      return;
    }
    where.append(" and ").append(column).append(" >= ?");
    args.add(value.atStartOfDay());
  }

  private void appendDateTo(StringBuilder where, List<Object> args, String column, String rawValue) {
    LocalDate value = parseDate(rawValue);
    if (value == null) {
      return;
    }
    where.append(" and ").append(column).append(" < ?");
    args.add(value.plusDays(1).atStartOfDay());
  }

  private LocalDate parseDate(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    return normalized == null ? null : LocalDate.parse(normalized);
  }

  private void appendIn(StringBuilder where, List<Object> args, String column, List<String> values) {
    if (values == null || values.isEmpty()) {
      where.append(" and 1 = 0");
      return;
    }
    where.append(" and ").append(column).append(" in (");
    for (int index = 0; index < values.size(); index++) {
      if (index > 0) {
        where.append(", ");
      }
      where.append("?");
      args.add(values.get(index));
    }
    where.append(")");
  }

  private String sortColumn(String sortField) {
    return SORT_COLUMNS.getOrDefault(sortField, "updated_at_source");
  }

  private String sortOrder(String sortOrder) {
    return "asc".equalsIgnoreCase(sortOrder) ? "asc" : "desc";
  }

  private String nullsClause(String sortOrder) {
    return "asc".equalsIgnoreCase(sortOrder) ? " nulls last" : " nulls first";
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

  private static Map<String, String> createSortColumns() {
    Map<String, String> columns = new LinkedHashMap<>();
    columns.put("issueIid", "issue_iid");
    columns.put("title", "lower(coalesce(title, ''))");
    columns.put("projectName", "lower(coalesce(project_name, ''))");
    columns.put("moduleNames", "lower(coalesce(module_names, ''))");
    columns.put("testingPhase", "lower(coalesce(phase_filter_value, ''))");
    columns.put("reasonCategory", "lower(coalesce(reason_category, ''))");
    columns.put("illegalReason", "lower(coalesce(illegal_reason, ''))");
    columns.put("severityLevel", "lower(coalesce(severity_level, ''))");
    columns.put("priorityLevel", "lower(coalesce(priority_level, ''))");
    columns.put("bugStatus", "lower(coalesce(bug_status, ''))");
    columns.put("issueState", "lower(coalesce(issue_state, ''))");
    columns.put("authorName", "lower(coalesce(author_name, ''))");
    columns.put("assigneeName", "lower(coalesce(assignee_name, ''))");
    columns.put("category", "lower(coalesce(category, ''))");
    columns.put("milestoneTitle", "lower(coalesce(milestone_title, ''))");
    columns.put("createdAt", "created_at_source");
    columns.put("updatedAt", "updated_at_source");
    columns.put("closedAt", "closed_at_source");
    return Map.copyOf(columns);
  }

  private record QueryParts(String where, List<Object> args) {}
}
