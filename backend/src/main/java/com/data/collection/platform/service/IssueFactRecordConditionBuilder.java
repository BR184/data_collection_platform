package com.data.collection.platform.service;

import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 把 {@link IssueFactRecordPageQuery} 编译为 issue_fact 查询的 where 片段与参数列表。
 *
 * <p>本类是纯函数构建器：不访问数据库、不持有状态；where 片段的拼接顺序即参数顺序，
 * 调用方必须按返回顺序绑定参数。系统测试与客户问题两个作用域的差异全部在此收口。
 */
@Component
class IssueFactRecordConditionBuilder {
  private static final List<String> SYSTEM_TEST_SCOPE_TOKENS =
      List.of("系统测试", "回归测试");
  static final long LEGACY_CC_PRODUCT_PROJECT_ID = 325L;
  static final LocalDate CUSTOMER_ISSUE_START_DATE = LocalDate.of(2026, 1, 1);

  /** 编译后的 where 片段与按序参数；where 以 " where" 开头，可为空查询直接拼接。 */
  record QueryParts(String where, List<Object> args) {}

  QueryParts build(IssueFactRecordPageQuery query) {
    StringBuilder where = new StringBuilder(" where deleted = false");
    List<Object> args = new ArrayList<>();
    appendScope(where, args, query.scope());
    appendSourceInstance(where, args, query.listRequest());
    appendBaseFilters(where, args, query.listRequest(), query.useDisplayModuleFilter());
    IssueCustomerMembershipSqlSupport.appendSelection(
        where, args, query.ccProductFilters().customerName());
    appendEqIgnoreCase(where, args, "reason_category", query.reasonCategory());
    appendTestingPhaseEquals(where, args, query.directTestingPhase());
    appendEqIgnoreCase(where, args, "fix_user", query.fixUser());
    appendDelayCauseFilter(where, args, query.delayCause());
    appendCcProductFilters(where, args, query);
    String testingPhaseColumn = testingPhaseColumn(query);
    appendInIgnoreCase(where, args, testingPhaseColumn, query.testingPhases());
    if (query.testingPhases().isEmpty()) {
      appendEqIgnoreCase(where, args, testingPhaseColumn, query.testingPhase());
    }
    appendAuthorHandlerAssigneeFilters(
        where, args, query.authorName(), query.handlerName(), query.assigneeName());
    appendIllegalFilters(where, args, query);
    appendFilterGroup(where, args, query);
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

  /** 客户问题记录筛选候选值的固定谓词与参数；供 repository 的候选值查询复用同一作用域口径。 */
  QueryParts customerIssueRecordValuesPredicate(
      boolean customerOperationsScope,
      boolean excludeExcluded,
      boolean excludeRejectedBugStatus,
      boolean delayOnly,
      String sourceInstance) {
    StringBuilder predicate = new StringBuilder("deleted = false and project_id = ?");
    List<Object> args = new ArrayList<>();
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
    return new QueryParts(predicate.toString(), args);
  }

  /** 客户问题非法记录筛选候选值的固定谓词与参数。 */
  QueryParts customerIssueIllegalValuesPredicate(String sourceInstance) {
    StringBuilder predicate = new StringBuilder(
        "deleted = false and project_id = ? and (created_at_source is null or created_at_source >= ?)"
            + " and is_excluded = false and is_illegal = true");
    List<Object> args = new ArrayList<>();
    args.add(LEGACY_CC_PRODUCT_PROJECT_ID);
    args.add(CUSTOMER_ISSUE_START_DATE.atStartOfDay());
    appendCustomerSourceInstancePredicate(predicate, args, sourceInstance);
    appendIllegalReasonsContainsAny(
        predicate,
        args,
        CustomerIssueIllegalReasonSupport.SUPPORTED_REASONS);
    return new QueryParts(predicate.toString(), args);
  }

  /** 系统测试非法记录筛选候选值的固定谓词与参数。 */
  QueryParts systemTestIllegalValuesPredicate(Long projectId) {
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
    return new QueryParts(supportedReasonPredicate.toString(), args);
  }

  private void appendCcProductFilters(
      StringBuilder where, List<Object> args, IssueFactRecordPageQuery query) {
    CustomerIssueRecordFilters.CcProductFilters filters = query.ccProductFilters();
    appendDateFrom(
        where, args, "planned_resolution_at", filters.plannedResolutionAtStart());
    appendDateTo(
        where, args, "planned_resolution_at", filters.plannedResolutionAtEnd());
    appendPredicate(
        where,
        args,
        CustomerIssuePlannedMergeBranchSqlSupport.matches(
            "planned_merge_version_branch", filters.plannedMergeVersionBranch()));
    appendPredicate(
        where,
        args,
        IssueRetentionDurationSqlSupport.range(
            filters.retentionHoursMin(), filters.retentionHoursMax(), query.retentionAsOf()));
  }

  private void appendPredicate(
      StringBuilder where, List<Object> args, SqlPredicate predicate) {
    if (predicate == null || predicate.predicate().isBlank()) {
      return;
    }
    where.append(" and ").append(predicate.predicate());
    args.addAll(predicate.args());
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

  private void appendCustomerSourceInstancePredicate(
      StringBuilder predicate, List<Object> args, String sourceInstance) {
    String normalized = TextQuerySupport.trimToNull(sourceInstance);
    if (normalized == null) {
      return;
    }
    predicate.append(" and lower(coalesce(source_instance, 'default')) = ?");
    args.add(GitlabSourceInstanceSupport.normalizeSourceInstance(normalized));
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
               "handler_name",
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
               "handler_name",
               "assignee_name"),
              true,
              request.keyword());
    }
  }

  private void appendAuthorHandlerAssigneeFilters(
      StringBuilder where,
      List<Object> args,
      String authorName,
      String handlerName,
      String assigneeName) {
    appendEqIgnoreCase(where, args, "author_name", authorName);
    appendEqIgnoreCase(where, args, "handler_name", handlerName);
    appendEqIgnoreCase(where, args, "assignee_name", assigneeName);
  }

  private void appendTestingPhaseEquals(StringBuilder where, List<Object> args, String value) {
    if (CustomerIssueTestingPhaseSupport.isUnspecifiedFilter(value)) {
      where.append(" and nullif(btrim(coalesce(testing_phase, '')), '') is null");
      return;
    }
    appendEqIgnoreCase(where, args, "testing_phase", value);
  }

  private void appendFilterGroup(StringBuilder where, List<Object> args, IssueFactRecordPageQuery query) {
    boolean customerScope =
        query.scope() == IssueFactRecordPageQuery.Scope.CUSTOMER
            || query.scope() == IssueFactRecordPageQuery.Scope.CUSTOMER_PROJECT;
    StatisticFilterGroup filterGroup = query.filterGroup();
    (customerScope
            ? IssueFactFilterGroupSqlSupport.toCustomerIssueSql(filterGroup)
            : IssueFactFilterGroupSqlSupport.toSql(filterGroup, query.useFullTestingPhaseFilter()))
        .filter(predicate -> TextQuerySupport.trimToNull(predicate.predicate()) != null)
        .ifPresent(
            predicate -> {
              where.append(" and (").append(predicate.predicate()).append(")");
              args.addAll(predicate.args());
            });
  }

  private void appendIllegalFilters(
      StringBuilder where, List<Object> args, IssueFactRecordPageQuery query) {
    if (!query.illegalOnly()) {
      return;
    }
    where.append(" and is_illegal = true");
    if (query.supportedSystemIllegalReasonsOnly()) {
      appendIllegalReasonsContainsAny(where, args, SystemTestIllegalReasonSupport.supportedRawReasons());
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
    args.add(normalized.toLowerCase(Locale.ROOT));
  }

  private void appendDelayCauseFilter(StringBuilder where, List<Object> args, String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return;
    }
    SqlPredicate predicate = IssueDelayCauseMemberSqlSupport.matches("delay_cause", normalized);
    where.append(" and ").append(predicate.predicate());
    args.addAll(predicate.args());
  }

  private void appendInIgnoreCase(StringBuilder where, List<Object> args, String column, List<String> values) {
    if (values == null || values.isEmpty()) {
      return;
    }
    List<String> normalizedValues =
        values.stream()
            .map(TextQuerySupport::trimToNull)
            .filter(value -> value != null)
            .map(value -> value.toLowerCase(Locale.ROOT))
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
    args.add("%" + normalized.toLowerCase(Locale.ROOT) + "%");
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
    String rawPattern = "%" + normalized.toLowerCase(Locale.ROOT) + "%";
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
    args.add("%," + normalized.toLowerCase(Locale.ROOT) + ",%");
  }

  private void appendLegacyBugStatusFilter(StringBuilder where, List<Object> args, String bugStatus) {
    String normalized = TextQuerySupport.trimToNull(bugStatus);
    if (normalized == null) {
      return;
    }
    SqlPredicate predicate = IssueStatusMemberSqlSupport.matches(normalized);
    where.append(" and ").append(predicate.predicate());
    args.addAll(predicate.args());
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
      args.add("%," + values.get(index).toLowerCase(Locale.ROOT) + ",%");
    }
    where.append(")");
  }

  static LocalDateTime customerIssueStartDate() {
    return CUSTOMER_ISSUE_START_DATE.atStartOfDay();
  }
}
