package com.data.collection.platform.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * issue_fact 记录页统一查询入口：组合条件构建器、排序白名单、候选值查询与行映射。
 * 本类只负责查询编排；查询失败必须传播，不能把异常伪装为空产出。SQL 语义分布在各协作者中。
 */
@Service
public class IssueFactRecordRepository implements com.data.collection.platform.service.read.FactReadScoped<IssueFactRecordRepository> {
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
             coalesce(handler_name, '') as handler_name,
             coalesce(assignee_name, '') as assignee_name,
             coalesce(fix_user, '') as fix_user,
             coalesce(module_names, '') as module_names,
             coalesce(function_name, '') as function_name,
             coalesce(customer_names, '') as customer_names,
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
             closed_at_source,
             planned_resolution_at,
             coalesce(planned_resolution_text, '') as planned_resolution_text,
             coalesce(planned_merge_version_branch, '') as planned_merge_version_branch
        from issue_fact
      """;
  // order by id 固定全量读取的行序：规则说明 samples 与候选值顺序依赖列表前缀，无排序时随查询计划漂移。
  // 排序必须经 suffixSql 传入：query() 会在 selectSql 末尾继续追加 and 条件。
  private static final String FACT_SQL = FACT_SELECT_SQL + " where deleted = false";
  private static final String FACT_SQL_ORDER = "order by business_order_id";

  private final IssueFactQueryService issueFactQueryService;
  private final IssueFactRecordConditionBuilder conditionBuilder;
  private final IssueFactRecordRowMapper rowMapper;
  private final IssueFactFilterValuesQuerySupport filterValuesQuerySupport;
  private final com.data.collection.platform.entity.read.FactReadSelection factReadSelection;

  @org.springframework.beans.factory.annotation.Autowired
  public IssueFactRecordRepository(
      IssueFactQueryService issueFactQueryService,
      IssueFactRecordConditionBuilder conditionBuilder,
      IssueFactRecordRowMapper rowMapper,
      IssueFactFilterValuesQuerySupport filterValuesQuerySupport) {
    this.issueFactQueryService = issueFactQueryService;
    this.conditionBuilder = conditionBuilder;
    this.rowMapper = rowMapper;
    this.filterValuesQuerySupport = filterValuesQuerySupport;
    this.factReadSelection = null;
  }

  /** 创建一次读取持有的不可变记录查询对象，列表/计数/候选共享同一父修订集合。 */
  @Override
  public IssueFactRecordRepository forRead(com.data.collection.platform.entity.read.FactReadSelection selection) {
    return new IssueFactRecordRepository(this, selection);
  }

  private IssueFactRecordRepository(IssueFactRecordRepository factory,
      com.data.collection.platform.entity.read.FactReadSelection selection) {
    this.issueFactQueryService = factory.issueFactQueryService;
    this.conditionBuilder = factory.conditionBuilder;
    this.rowMapper = factory.rowMapper;
    this.filterValuesQuerySupport = factory.filterValuesQuerySupport;
    this.factReadSelection = java.util.Objects.requireNonNull(selection);
  }

  private com.data.collection.platform.entity.read.FactReadSelection readSelection() {
    return java.util.Objects.requireNonNull(factReadSelection, "记录查询必须持有固定版本身份");
  }

  public List<IssueFactRecord> findByProjectId(Long projectId) {
    Map<String, String> filters = new LinkedHashMap<>();
    if (projectId != null) {
      filters.put("projectId", String.valueOf(projectId));
    }
    return findByFilters(filters);
  }

  /** 按完整业务身份读取固定版本的一条议题，不使用会随修订变化的事实表自增 ID。 */
  public java.util.Optional<IssueFactRecord> findByIdentity(String sourceInstance, long projectId, long issueId) {
    if (sourceInstance == null || sourceInstance.isBlank() || projectId <= 0 || issueId <= 0) {
      throw new IllegalArgumentException("记录身份必须指定来源、项目和议题");
    }
    List<IssueFactRecord> rows = issueFactQueryService.query(readSelection(),
        FACT_SQL + " and source_system = 'GITLAB' and source_instance = ? and project_id = ? and issue_id = ?",
        List.of(sourceInstance, projectId, issueId), rowMapper);
    if (rows.size() > 1) { throw new IllegalStateException("完整版本内出现重复记录身份"); }
    return rows.stream().findFirst();
  }

  /** 查询故障必须传播，不能返回空列表冒充成功的完整数据。 */
  public List<IssueFactRecord> findByFilters(Map<String, String> filters) {
    return issueFactQueryService.query(readSelection(),
        FACT_SQL, filters == null ? Map.of() : filters, null, List.of(), FACT_SQL_ORDER, rowMapper);
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
    IssueFactRecordConditionBuilder.QueryParts parts =
        conditionBuilder.build(
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
                null,
                null,
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
                "desc",
                CustomerIssueRecordFilters.CcProductFilters.empty(),
                null));
    // 候选值选项按事实行序取首次出现顺序，必须与全量路径同样 order by id 固定行序，
    // 否则 assigneeNames 等候选顺序随查询计划漂移（黄金基线复跑实测漂移）。
    return issueFactQueryService.query(readSelection(),
        FACT_SELECT_SQL + parts.where() + " " + FACT_SQL_ORDER, parts.args(), rowMapper);
  }

  public SystemTestIllegalFilterValues findSystemTestIllegalFilterValues(Long projectId) {
    IssueFactRecordConditionBuilder.QueryParts predicateParts =
        conditionBuilder.systemTestIllegalValuesPredicate(projectId);
    String sql = filterValuesQuerySupport.systemTestIllegalValuesSql(predicateParts.where());
    List<SystemTestIllegalFilterValues> rows =
        issueFactQueryService.query(readSelection(), sql, predicateParts.args(), this::mapSystemTestIllegalFilterValues);
    return rows.isEmpty() ? SystemTestIllegalFilterValues.empty() : rows.get(0);
  }

  public CustomerIssueFilterValues findCustomerIssueRecordFilterValues(
      boolean customerOperationsScope,
      boolean delayOnly,
      boolean excludeExcluded,
      boolean excludeRejectedBugStatus,
      String sourceInstance) {
    IssueFactRecordConditionBuilder.QueryParts predicateParts =
        conditionBuilder.customerIssueRecordValuesPredicate(
            customerOperationsScope, excludeExcluded, excludeRejectedBugStatus, delayOnly, sourceInstance);
    return findCustomerIssueFilterValues(predicateParts);
  }

  public CustomerIssueFilterValues findCustomerIssueIllegalFilterValues(String sourceInstance) {
    IssueFactRecordConditionBuilder.QueryParts predicateParts =
        conditionBuilder.customerIssueIllegalValuesPredicate(sourceInstance);
    return findCustomerIssueFilterValues(predicateParts);
  }

  private CustomerIssueFilterValues findCustomerIssueFilterValues(
      IssueFactRecordConditionBuilder.QueryParts predicateParts) {
    String sql = filterValuesQuerySupport.customerIssueValuesSql(predicateParts.where());
    List<CustomerIssueFilterValues> rows =
        issueFactQueryService.query(readSelection(), sql, predicateParts.args(), this::mapCustomerIssueFilterValues);
    return rows.isEmpty() ? CustomerIssueFilterValues.empty() : rows.get(0);
  }

  public PageSlice<IssueFactRecord> findPage(IssueFactRecordPageQuery query) {
    IssueFactRecordConditionBuilder.QueryParts parts = conditionBuilder.build(query);
    long total = issueFactQueryService.count(readSelection(), "select count(*) from issue_fact" + parts.where(), parts.args());
    if (total == 0) {
      return new PageSlice<>(List.of(), 0, query.page(), query.size());
    }
    List<Object> pageArgs = new ArrayList<>(parts.args());
    pageArgs.add(query.size());
    pageArgs.add((long) (query.page() - 1) * query.size());
    List<IssueFactRecord> records =
        issueFactQueryService.query(readSelection(),
            FACT_SELECT_SQL
                + parts.where()
                + " order by "
                + IssueFactRecordSortSupport.sortColumn(query.sortField())
                + " "
                + IssueFactRecordSortSupport.sortOrder(query.sortOrder())
                + IssueFactRecordSortSupport.nullsClause(query.sortOrder())
                + ", issue_iid "
                + IssueFactRecordSortSupport.sortOrder(query.sortOrder())
                + " limit ? offset ?",
            pageArgs,
            rowMapper);
    return new PageSlice<>(records, total, query.page(), query.size());
  }

  private SystemTestIllegalFilterValues mapSystemTestIllegalFilterValues(
      java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    return new SystemTestIllegalFilterValues(
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
        splitAggregatedValues(rs.getString("milestone_titles")));
  }

  private CustomerIssueFilterValues mapCustomerIssueFilterValues(
      java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    return new CustomerIssueFilterValues(
        splitAggregatedValues(rs.getString("project_names")),
        splitAggregatedValues(rs.getString("module_names")),
        splitAggregatedValues(rs.getString("function_names")),
        splitAggregatedValues(rs.getString("customer_names")),
        splitAggregatedValues(rs.getString("reason_categories")),
        splitAggregatedValues(rs.getString("severity_levels")),
        splitAggregatedValues(rs.getString("priority_levels")),
        splitAggregatedValues(rs.getString("issue_states")),
        splitAggregatedValues(rs.getString("bug_statuses")),
        splitAggregatedValues(rs.getString("categories")),
        splitAggregatedValues(rs.getString("author_names")),
        splitAggregatedValues(rs.getString("handler_names")),
         splitAggregatedValues(rs.getString("assignee_names")),
         splitAggregatedValues(rs.getString("testing_phases")),
         splitAggregatedValues(rs.getString("fix_users")),
         splitAggregatedValues(rs.getString("delay_causes")),
         splitAggregatedValues(rs.getString("planned_merge_version_branches")),
         splitAggregatedValues(rs.getString("milestone_titles")),
        splitAggregatedValues(rs.getString("illegal_reasons")));
  }

  private static List<String> splitAggregatedValues(String value) {
    return IssueFactFilterValuesQuerySupport.splitAggregatedValues(value);
  }

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
      List<String> customerNames,
      List<String> reasonCategories,
      List<String> severityLevels,
      List<String> priorityLevels,
      List<String> issueStates,
      List<String> bugStatuses,
      List<String> categories,
      List<String> authorNames,
      List<String> handlerNames,
      List<String> assigneeNames,
      List<String> testingPhases,
      List<String> fixUsers,
      List<String> delayCauses,
      List<String> plannedMergeVersionBranches,
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
