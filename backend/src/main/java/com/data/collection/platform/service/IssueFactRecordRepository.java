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
             coalesce(source_instance, 'default') as source_instance,
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
             coalesce(function_name, '') as function_name,
             coalesce(label_names, '') as label_names,
             coalesce(delay_issue, false) as delay_issue,
             coalesce(delay_reason, '') as delay_reason,
             coalesce(delay_cause, '') as delay_cause,
             coalesce(is_response_delayed, false) as is_response_delayed,
             coalesce(is_resolve_delayed, false) as is_resolve_delayed,
             coalesce(is_illegal, false) as is_illegal,
             coalesce(illegal_reason, '') as illegal_reason,
             coalesce(illegal_reasons, '') as illegal_reasons,
             created_at_source,
             updated_at_source,
             closed_at_source
        from issue_fact
      """;
  private static final String FACT_SQL = FACT_SELECT_SQL + " where deleted = false";
  private static final List<String> SYSTEM_TEST_SCOPE_TOKENS =
      List.of("\u7cfb\u7edf\u6d4b\u8bd5", "\u56de\u5f52\u6d4b\u8bd5");
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

  public List<IssueFactRecord> findForFilterOptions(IssueFactRecordListRequest request) {
    IssueFactRecordListRequest safeRequest =
        request == null
            ? new IssueFactRecordListRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                1,
                20,
                "updatedAt",
                "desc")
            : request;
    QueryParts parts =
        buildPageQuery(
            new IssueFactRecordPageQuery(
                IssueFactRecordPageQuery.Scope.ALL,
                safeRequest,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                1,
                20,
                "updatedAt",
                "desc"));
    try {
      return issueFactQueryService.query(FACT_SELECT_SQL + parts.where(), parts.args(), this::mapIssueFact);
    } catch (DataAccessException error) {
      return List.of();
    }
  }

  public SystemTestIllegalFilterValues findSystemTestIllegalFilterValues(Long projectId) {
    List<Object> args = new ArrayList<>();
    Long safeProjectId = projectId == null ? 9L : projectId;
    args.add(safeProjectId);
    args.add("%系统测试%");
    args.add("%回归测试%");
    StringBuilder supportedReasonPredicate = new StringBuilder("1 = 1");
    appendIllegalReasonsContainsAny(
        supportedReasonPredicate,
        args,
        SystemTestIllegalReasonSupport.supportedRawReasons());
    String sql =
        """
        with base as (
          select coalesce(project_name, '') as project_name,
                 coalesce(module_names, '') as module_names,
                 coalesce(testing_phase, '') as testing_phase,
                 coalesce(illegal_reason, '') as illegal_reason,
                 coalesce(illegal_reasons, '') as illegal_reasons,
                 coalesce(author_name, '') as author_name,
                 coalesce(assignee_name, '') as assignee_name,
                 coalesce(issue_state, '') as issue_state,
                 coalesce(severity_level, '') as severity_level,
                 coalesce(bug_status, '') as bug_status,
                 coalesce(category, '') as category,
                 coalesce(milestone_title, '') as milestone_title
            from issue_fact
           where deleted = false
             and project_id = ?
             and is_illegal = true
             and is_excluded = false
             and (lower(coalesce(testing_phase, '')) like ? or lower(coalesce(testing_phase, '')) like ?)
        """
            + "     and "
            + supportedReasonPredicate
            + """
        )
        select
          (select string_agg(value, E'\n') from (select distinct nullif(btrim(project_name), '') as value from base) t where value is not null) as project_names,
          (select string_agg(value, E'\n') from (
             select distinct nullif(btrim(module_name), '') as value
               from base
               cross join lateral regexp_split_to_table(coalesce(module_names, ''), ',') as modules(module_name)
             union
             select '未设定模块' where exists (select 1 from base where nullif(btrim(module_names), '') is null)
           ) t where value is not null) as module_names,
          (select string_agg(value, E'\n') from (select distinct nullif(btrim(testing_phase), '') as value from base) t where value is not null) as testing_phases,
          (select string_agg(value, E'\n') from (
             select distinct nullif(btrim(reason), '') as value
               from base
               cross join lateral regexp_split_to_table(coalesce(nullif(illegal_reasons, ''), illegal_reason, ''), ',') as reasons(reason)
           ) t where value is not null) as illegal_reasons,
          (select string_agg(value, E'\n') from (select distinct nullif(btrim(author_name), '') as value from base) t where value is not null) as author_names,
          (select string_agg(value, E'\n') from (select distinct nullif(btrim(assignee_name), '') as value from base) t where value is not null) as assignee_names,
          (select string_agg(value, E'\n') from (select distinct nullif(btrim(issue_state), '') as value from base) t where value is not null) as issue_states,
          (select string_agg(value, E'\n') from (select distinct nullif(btrim(severity_level), '') as value from base) t where value is not null) as severity_levels,
          (select string_agg(value, E'\n') from (select distinct nullif(btrim(bug_status), '') as value from base) t where value is not null) as bug_statuses,
          (select string_agg(value, E'\n') from (select distinct nullif(btrim(category), '') as value from base) t where value is not null) as categories,
          (select string_agg(value, E'\n') from (select distinct nullif(btrim(milestone_title), '') as value from base) t where value is not null) as milestone_titles
        """;
    try {
      List<SystemTestIllegalFilterValues> rows =
          issueFactQueryService.query(
              sql,
              args,
              (rs, rowNum) ->
                  new SystemTestIllegalFilterValues(
                      splitAggregatedValues(rs.getString("project_names")),
                      splitAggregatedValues(rs.getString("module_names")),
                      splitAggregatedValues(rs.getString("testing_phases")),
                      splitAggregatedValues(rs.getString("illegal_reasons")),
                      splitAggregatedValues(rs.getString("author_names")),
                      splitAggregatedValues(rs.getString("assignee_names")),
                      splitAggregatedValues(rs.getString("issue_states")),
                      splitAggregatedValues(rs.getString("severity_levels")),
                      splitAggregatedValues(rs.getString("bug_statuses")),
                      splitAggregatedValues(rs.getString("categories")),
                      splitAggregatedValues(rs.getString("milestone_titles"))));
      return rows.isEmpty() ? SystemTestIllegalFilterValues.empty() : rows.get(0);
    } catch (DataAccessException error) {
      return SystemTestIllegalFilterValues.empty();
    }
  }

  public CustomerIssueFilterValues findCustomerIssueRecordFilterValues(
      boolean customerOperationsScope,
      boolean delayOnly,
      boolean excludeExcluded,
      boolean excludeRejectedBugStatus,
      String sourceInstance) {
    List<Object> args = new ArrayList<>();
    StringBuilder predicate = new StringBuilder("deleted = false and project_id = ?");
    args.add(LEGACY_CC_PRODUCT_PROJECT_ID);
    appendCustomerSourceInstancePredicate(predicate, args, sourceInstance);
    if (customerOperationsScope) {
      predicate.append(" and (created_at_source is null or created_at_source >= ?)");
      args.add(CUSTOMER_ISSUE_START_DATE.atStartOfDay());
    }
    if (excludeExcluded) {
      predicate.append(" and is_excluded = false");
    }
    if (excludeRejectedBugStatus) {
      predicate.append(" and lower(coalesce(bug_status, '')) not like ?");
      args.add("%已拒绝%");
    }
    if (delayOnly) {
      predicate.append(" and (delay_issue = true or is_response_delayed = true or is_resolve_delayed = true)");
    }
    return findCustomerIssueFilterValues(predicate.toString(), args);
  }

  public CustomerIssueFilterValues findCustomerIssueIllegalFilterValues(String sourceInstance) {
    List<Object> args = new ArrayList<>();
    StringBuilder predicate = new StringBuilder(
        "deleted = false and project_id = ? and (created_at_source is null or created_at_source >= ?)"
            + " and is_excluded = false and is_illegal = true");
    args.add(LEGACY_CC_PRODUCT_PROJECT_ID);
    args.add(CUSTOMER_ISSUE_START_DATE.atStartOfDay());
    appendCustomerSourceInstancePredicate(predicate, args, sourceInstance);
    appendIllegalReasonsContainsAny(
        predicate,
        args,
        CustomerIssueIllegalReasonSupport.SUPPORTED_REASONS);
    return findCustomerIssueFilterValues(predicate.toString(), args);
  }

  private CustomerIssueFilterValues findCustomerIssueFilterValues(String predicate, List<Object> args) {
    String sql =
        """
        with base as (
          select coalesce(project_name, '') as project_name,
                 coalesce(module_names, '') as module_names,
                 coalesce(function_name, '') as function_name,
                 coalesce(reason_category, '') as reason_category,
                 coalesce(severity_level, '') as severity_level,
                 coalesce(priority_level, '') as priority_level,
                 coalesce(issue_state, '') as issue_state,
                 coalesce(bug_status, '') as bug_status,
                 coalesce(category, '') as category,
                 coalesce(author_name, '') as author_name,
                 coalesce(assignee_name, '') as assignee_name,
                 coalesce(milestone_title, '') as milestone_title,
                 coalesce(illegal_reason, '') as illegal_reason,
                 coalesce(illegal_reasons, '') as illegal_reasons
            from issue_fact
           where
        """
            + predicate
            + """
        )
        select
          (select string_agg(value, E'\n') from (select distinct nullif(btrim(project_name), '') as value from base) t where value is not null) as project_names,
          (select string_agg(value, E'\n') from (
             select distinct nullif(btrim(module_name), '') as value
               from base
               cross join lateral regexp_split_to_table(coalesce(module_names, ''), ',') as modules(module_name)
           ) t where value is not null) as module_names,
          (select string_agg(value, E'\n') from (select distinct nullif(btrim(function_name), '') as value from base) t where value is not null) as function_names,
          (select string_agg(value, E'\n') from (select distinct nullif(btrim(reason_category), '') as value from base) t where value is not null) as reason_categories,
          (select string_agg(value, E'\n') from (select distinct nullif(btrim(severity_level), '') as value from base) t where value is not null) as severity_levels,
          (select string_agg(value, E'\n') from (select distinct nullif(btrim(priority_level), '') as value from base) t where value is not null) as priority_levels,
          (select string_agg(value, E'\n') from (select distinct nullif(btrim(issue_state), '') as value from base) t where value is not null) as issue_states,
          (select string_agg(value, E'\n') from (select distinct nullif(btrim(bug_status), '') as value from base) t where value is not null) as bug_statuses,
          (select string_agg(value, E'\n') from (select distinct nullif(btrim(category), '') as value from base) t where value is not null) as categories,
          (select string_agg(value, E'\n') from (select distinct nullif(btrim(author_name), '') as value from base) t where value is not null) as author_names,
          (select string_agg(value, E'\n') from (select distinct nullif(btrim(assignee_name), '') as value from base) t where value is not null) as assignee_names,
          (select string_agg(value, E'\n') from (select distinct nullif(btrim(milestone_title), '') as value from base) t where value is not null) as milestone_titles,
          (select string_agg(value, E'\n') from (
             select distinct nullif(btrim(reason), '') as value
               from base
               cross join lateral regexp_split_to_table(coalesce(nullif(illegal_reasons, ''), illegal_reason, ''), ',') as reasons(reason)
           ) t where value is not null) as illegal_reasons
        """;
    try {
      List<CustomerIssueFilterValues> rows =
          issueFactQueryService.query(
              sql,
              args,
              (rs, rowNum) ->
                  new CustomerIssueFilterValues(
                      splitAggregatedValues(rs.getString("project_names")),
                      splitAggregatedValues(rs.getString("module_names")),
                      splitAggregatedValues(rs.getString("function_names")),
                      splitAggregatedValues(rs.getString("reason_categories")),
                      splitAggregatedValues(rs.getString("severity_levels")),
                      splitAggregatedValues(rs.getString("priority_levels")),
                      splitAggregatedValues(rs.getString("issue_states")),
                      splitAggregatedValues(rs.getString("bug_statuses")),
                      splitAggregatedValues(rs.getString("categories")),
                      splitAggregatedValues(rs.getString("author_names")),
                      splitAggregatedValues(rs.getString("assignee_names")),
                      splitAggregatedValues(rs.getString("milestone_titles")),
                      splitAggregatedValues(rs.getString("illegal_reasons"))));
      return rows.isEmpty() ? CustomerIssueFilterValues.empty() : rows.get(0);
    } catch (DataAccessException error) {
      return CustomerIssueFilterValues.empty();
    }
  }

  private void appendCustomerSourceInstancePredicate(
      StringBuilder predicate, List<Object> args, String sourceInstance) {
    String normalized = TextQuerySupport.trimToNull(sourceInstance);
    if (normalized == null) {
      return;
    }
    predicate.append(" and lower(coalesce(source_instance, 'default')) = ?");
    args.add(GitlabSourceInstanceSupport.normalizeSourceInstance(normalized));
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
    appendSourceInstance(where, args, query.listRequest());
    appendBaseFilters(where, args, query.listRequest(), query.useDisplayModuleFilter());
    appendEqIgnoreCase(where, args, "reason_category", query.reasonCategory());
    String testingPhaseColumn = testingPhaseColumn(query);
    appendInIgnoreCase(where, args, testingPhaseColumn, query.testingPhases());
    if (query.testingPhases().isEmpty()) {
      appendEqIgnoreCase(where, args, testingPhaseColumn, query.testingPhase());
    }
    appendAuthorAssigneeFilters(where, args, query.authorName(), query.assigneeName());
    appendIllegalFilters(where, args, query);
    appendFilterGroup(where, args, query.filterGroup(), query.useFullTestingPhaseFilter());
    if (query.delayOnly()) {
      where.append(" and (delay_issue = true or is_response_delayed = true or is_resolve_delayed = true)");
    }
    if (query.excludeExcluded()) {
      where.append(" and is_excluded = false");
    }
    if (query.excludeRejectedBugStatus()) {
      where.append(" and lower(coalesce(bug_status, '')) not like ?");
      args.add("%已拒绝%");
    }
    return new QueryParts(where.toString(), args);
  }

  private void appendScope(
      StringBuilder where, List<Object> args, IssueFactRecordPageQuery.Scope scope) {
    if (scope == IssueFactRecordPageQuery.Scope.ALL) {
      return;
    }
    if (scope == IssueFactRecordPageQuery.Scope.SYSTEM_TEST) {
      appendSystemTestScope(where, args);
      return;
    }
    where.append(" and project_id = ?");
    args.add(LEGACY_CC_PRODUCT_PROJECT_ID);
    if (scope == IssueFactRecordPageQuery.Scope.CUSTOMER_PROJECT) {
      return;
    }
    where.append(" and (created_at_source is null or created_at_source >= ?)");
    args.add(CUSTOMER_ISSUE_START_DATE.atStartOfDay());
  }

  private String testingPhaseColumn(IssueFactRecordPageQuery query) {
    if (query.scope() == IssueFactRecordPageQuery.Scope.CUSTOMER
        || query.scope() == IssueFactRecordPageQuery.Scope.CUSTOMER_PROJECT) {
      return "milestone_title";
    }
    return query.useFullTestingPhaseFilter() ? "testing_phase" : "phase_filter_value";
  }

  private void appendSystemTestScope(StringBuilder where, List<Object> args) {
    where.append(" and (");
    appendSystemTestScopeExpression(where, args);
    where.append(")");
  }

  private void appendSystemTestScopeExpression(StringBuilder where, List<Object> args) {
    where.append("project_id = ?");
    args.add(9L);
    where.append(" and (");
    boolean first = true;
    for (String token : SYSTEM_TEST_SCOPE_TOKENS) {
      if (!first) {
        where.append(" or ");
      }
      first = false;
      where.append("lower(coalesce(testing_phase, '')) like ?");
      args.add("%" + token + "%");
    }
    where.append(")");
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
    appendKeywordSearch(where, args, request, useDisplayModuleFilter);
    appendIssueIid(where, args, request.issueIid());
    appendIndexedSearchWithRawFallback(
        where,
        args,
        List.of(
            "title_search_text",
            "title_search_compact",
            "title_search_spell",
            "title_search_initials"),
        List.of("title"),
        false,
        request.title());
    appendEqIgnoreCase(where, args, "project_name", request.projectName());
    appendModuleFilter(where, args, request.moduleName(), useDisplayModuleFilter);
    appendContainsIgnoreCase(where, args, "function_name", request.functionName());
    appendEqIgnoreCase(where, args, "severity_level", request.severityLevel());
    appendEqIgnoreCase(where, args, "priority_level", request.priorityLevel());
    appendEqIgnoreCase(where, args, "issue_state", request.issueState());
    appendLegacyBugStatusFilter(where, args, request.bugStatus());
    appendLegacyCategoryFilter(where, args, request.category());
    appendEqIgnoreCase(where, args, "milestone_title", request.milestoneTitle());
    appendDateFrom(where, args, "created_at_source", request.createdAtStart());
    appendDateTo(where, args, "created_at_source", request.createdAtEnd());
    appendDateFrom(where, args, "updated_at_source", request.updatedAtStart());
    appendDateTo(where, args, "updated_at_source", request.updatedAtEnd());
  }

  private void appendSourceInstance(
      StringBuilder where, List<Object> args, IssueFactRecordListRequest request) {
    if (request == null) {
      return;
    }
    String sourceInstance = TextQuerySupport.trimToNull(request.sourceInstance());
    if (sourceInstance == null) {
      return;
    }
    where.append(" and lower(coalesce(source_instance, 'default')) = ?");
    args.add(GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance));
  }

  private void appendKeywordSearch(
      StringBuilder where,
      List<Object> args,
      IssueFactRecordListRequest request,
      boolean useDisplayModuleFilter) {
    String searchType = TextQuerySupport.trimToNull(request.searchType());
    if (searchType == null || "all".equalsIgnoreCase(searchType) || "comprehensive".equalsIgnoreCase(searchType)) {
      appendIndexedSearchWithRawFallback(
          where,
          args,
          List.of("search_text", "search_compact", "search_spell", "search_initials"),
          List.of(
              "title",
              "project_name",
              "module_names",
              "milestone_title",
              "author_name",
              "assignee_name"),
          true,
          request.keyword());
      return;
    }
    switch (searchType) {
      case "issueIid" -> appendIssueIid(where, args, request.keyword());
      case "title" ->
          appendIndexedSearchWithRawFallback(
              where,
              args,
              List.of(
                  "title_search_text",
                  "title_search_compact",
                  "title_search_spell",
                  "title_search_initials"),
              List.of("title"),
              false,
              request.keyword());
      case "moduleName" -> appendModuleFilter(where, args, request.keyword(), useDisplayModuleFilter);
      case "milestoneTitle" -> appendContainsIgnoreCase(where, args, "milestone_title", request.keyword());
      case "authorName" -> appendContainsIgnoreCase(where, args, "author_name", request.keyword());
      case "assigneeName" -> appendContainsIgnoreCase(where, args, "assignee_name", request.keyword());
      default ->
          appendIndexedSearchWithRawFallback(
              where,
              args,
              List.of("search_text", "search_compact", "search_spell", "search_initials"),
              List.of(
                  "title",
                  "project_name",
                  "module_names",
                  "milestone_title",
                  "author_name",
                  "assignee_name"),
              true,
              request.keyword());
    }
  }

  private void appendAuthorAssigneeFilters(
      StringBuilder where, List<Object> args, String authorName, String assigneeName) {
    appendEqIgnoreCase(where, args, "author_name", authorName);
    appendEqIgnoreCase(where, args, "assignee_name", assigneeName);
  }

  private void appendFilterGroup(
      StringBuilder where,
      List<Object> args,
      com.data.collection.platform.entity.statistics.StatisticFilterGroup filterGroup,
      boolean useFullTestingPhaseFilter) {
    IssueFactFilterGroupSqlSupport.toSql(filterGroup, useFullTestingPhaseFilter)
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
      appendIllegalReasonsContainsAny(where, args, SystemTestIllegalReasonSupport.SUPPORTED_REASONS);
      List<String> rawReasons = SystemTestIllegalReasonSupport.rawReasonsFor(query.illegalReason());
      if (!rawReasons.isEmpty()) {
        appendIllegalReasonsContainsAny(where, args, rawReasons);
      }
      return;
    }
    if (query.supportedCustomerIllegalReasonsOnly()) {
      appendIllegalReasonsContainsAny(where, args, CustomerIssueIllegalReasonSupport.SUPPORTED_REASONS);
      List<String> rawReasons = CustomerIssueIllegalReasonSupport.rawReasonsFor(query.illegalReason());
      if (TextQuerySupport.trimToNull(query.illegalReason()) != null && rawReasons.isEmpty()) {
        where.append(" and 1 = 0");
        return;
      }
      if (!rawReasons.isEmpty()) {
        appendIllegalReasonsContainsAny(where, args, rawReasons);
      }
      return;
    }
    String normalizedReason = TextQuerySupport.trimToNull(query.illegalReason());
    if (normalizedReason == null) {
      return;
    }
    List<String> rawReasons = new ArrayList<>(SystemTestIllegalReasonSupport.rawReasonsFor(normalizedReason));
    if (rawReasons.isEmpty()) {
      rawReasons.add(normalizedReason);
    }
    appendIllegalReasonsContainsAny(where, args, rawReasons);
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

  private void appendInIgnoreCase(StringBuilder where, List<Object> args, String column, List<String> values) {
    if (values == null || values.isEmpty()) {
      return;
    }
    List<String> normalizedValues =
        values.stream()
            .map(TextQuerySupport::trimToNull)
            .filter(value -> value != null)
            .map(value -> value.toLowerCase(java.util.Locale.ROOT))
            .distinct()
            .toList();
    if (normalizedValues.isEmpty()) {
      return;
    }
    where.append(" and lower(coalesce(").append(column).append(", '')) in (");
    for (int index = 0; index < normalizedValues.size(); index++) {
      if (index > 0) {
        where.append(", ");
      }
      where.append("?");
      args.add(normalizedValues.get(index));
    }
    where.append(")");
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

  private void appendIndexedSearchWithRawFallback(
      StringBuilder where,
      List<Object> args,
      List<String> indexedColumns,
      List<String> rawTextColumns,
      boolean includeIssueIid,
      String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return;
    }
    List<String> candidates = FactSearchIndexSupport.keywordCandidates(normalized);
    List<String> predicates = new ArrayList<>();
    for (String candidate : candidates) {
      String pattern = "%" + candidate + "%";
      for (String column : indexedColumns) {
        predicates.add(column + " like ?");
        args.add(pattern);
      }
    }
    String rawPattern = "%" + normalized.toLowerCase(java.util.Locale.ROOT) + "%";
    if (includeIssueIid) {
      predicates.add("cast(issue_iid as varchar) like ?");
      args.add("%" + normalized + "%");
    }
    for (String column : rawTextColumns) {
      predicates.add("lower(coalesce(" + column + ", '')) like ?");
      args.add(rawPattern);
    }
    if (!predicates.isEmpty()) {
      where.append(" and (").append(String.join(" or ", predicates)).append(")");
    }
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
    if ("曲线".equals(normalized) || "曲面".equals(normalized)) {
      where.append(" and lower(coalesce(module_names, '')) not like ?");
      args.add("%曲线曲面%");
    }
    where.append(" and lower(',' || replace(coalesce(module_names, ''), ', ', ',') || ',') like ?");
    args.add("%," + normalized.toLowerCase(java.util.Locale.ROOT) + ",%");
  }

  private void appendLegacyBugStatusFilter(StringBuilder where, List<Object> args, String bugStatus) {
    String normalized = TextQuerySupport.trimToNull(bugStatus);
    if (normalized == null) {
      return;
    }
    if ("已修复".equals(normalized)) {
      where.append(
          " and (lower(coalesce(bug_status, '')) like ? or lower(coalesce(bug_status, '')) like ? or lower(coalesce(bug_status, '')) like ?)");
      args.add("%待合并%");
      args.add("%已修复%");
      args.add("%未更新%");
      return;
    }
    appendContainsIgnoreCase(where, args, "bug_status", normalized);
  }

  private void appendLegacyCategoryFilter(StringBuilder where, List<Object> args, String category) {
    String normalized = TextQuerySupport.trimToNull(category);
    if (normalized == null) {
      return;
    }
    if ("建议和需求".equals(normalized)) {
      where.append(" and (lower(coalesce(category, '')) like ? or lower(coalesce(category, '')) like ?)");
      args.add("%建议%");
      args.add("%需求%");
      return;
    }
    appendEqIgnoreCase(where, args, "category", normalized);
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

  private void appendIllegalReasonsContainsAny(StringBuilder where, List<Object> args, List<String> values) {
    if (values == null || values.isEmpty()) {
      where.append(" and 1 = 0");
      return;
    }
    where.append(" and (");
    for (int index = 0; index < values.size(); index++) {
      if (index > 0) {
        where.append(" or ");
      }
      where.append(
          "lower(',' || replace(coalesce(nullif(illegal_reasons, ''), illegal_reason, ''), ', ', ',') || ',') like ?");
      args.add("%," + values.get(index).toLowerCase(java.util.Locale.ROOT) + ",%");
    }
    where.append(")");
  }

  private static List<String> splitAggregatedValues(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    return java.util.Arrays.stream(value.split("\\R"))
        .map(TextQuerySupport::trimToNull)
        .filter(item -> item != null)
        .distinct()
        .toList();
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
        IssueFactValueSupport.text(rs.getString("source_instance")),
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
        IssueFactValueSupport.text(rs.getString("function_name")),
        IssueFactValueSupport.split(rs.getString("label_names")),
        rs.getBoolean("delay_issue"),
        IssueFactValueSupport.text(rs.getString("delay_reason")),
        IssueFactValueSupport.text(rs.getString("delay_cause")),
        rs.getBoolean("is_response_delayed"),
        rs.getBoolean("is_resolve_delayed"),
        rs.getBoolean("is_illegal"),
        IssueFactValueSupport.text(rs.getString("illegal_reason")),
        IssueFactValueSupport.split(rs.getString("illegal_reasons")),
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
    columns.put("functionName", "lower(coalesce(function_name, ''))");
    columns.put("testingPhase", "lower(coalesce(testing_phase, ''))");
    columns.put("reasonCategory", "lower(coalesce(reason_category, ''))");
    columns.put("illegalReason", "lower(coalesce(nullif(illegal_reasons, ''), illegal_reason, ''))");
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

  public record SystemTestIllegalFilterValues(
      List<String> projectNames,
      List<String> moduleNames,
      List<String> testingPhases,
      List<String> illegalReasons,
      List<String> authorNames,
      List<String> assigneeNames,
      List<String> issueStates,
      List<String> severityLevels,
      List<String> bugStatuses,
      List<String> categories,
      List<String> milestoneTitles) {
    static SystemTestIllegalFilterValues empty() {
      return new SystemTestIllegalFilterValues(
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of());
      }
  }

  public record CustomerIssueFilterValues(
      List<String> projectNames,
      List<String> moduleNames,
      List<String> functionNames,
      List<String> reasonCategories,
      List<String> severityLevels,
      List<String> priorityLevels,
      List<String> issueStates,
      List<String> bugStatuses,
      List<String> categories,
      List<String> authorNames,
      List<String> assigneeNames,
      List<String> milestoneTitles,
      List<String> illegalReasons) {
    static CustomerIssueFilterValues empty() {
      return new CustomerIssueFilterValues(
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of());
    }
  }
}
