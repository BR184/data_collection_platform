package com.data.collection.platform.service;

import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class CodeReviewMatchModeRecordLoader {
  private static final String BASE_WHERE = """
       where upper(coalesce(merge_request_state, '')) = 'MERGED'
        and coalesce(legacy_merged_time_source, merged_at_source) > timestamp '2024-04-01 00:00:00'
        and coalesce(module_name, '') <> '无需标注'
        and (
          lower(coalesce(repository_name, '')) not in ('crowncad', 'dgm')
          or lower(coalesce(target_branch, '')) = 'dev'
        )
      """;
  private static final String SELECT_SQL = """
      select
        source_instance,
        merge_request_id,
        merge_request_iid,
        project_id,
        title as merge_request_content,
        project_name,
        repository_name,
        merged_at_source as merged_at,
        author_name as author,
        merge_user_name as merged_by,
        owner_name as owner,
        reviewer_names,
        assignee_names,
        target_branch,
        module_name,
        coalesce(label_names, '') as label_names,
        review_status,
        review_duration_minutes,
        review_exception_reason,
        code_walkthrough_date,
        scan_status,
        scan_bug_count,
        annotation_rate_result,
        bug_count_result,
        comment_rate,
        defect_count,
        added_lines,
        deleted_lines,
        code_specification_count,
        code_logic_specification_count,
        performance_specification_count,
        design_specification_count,
        other_specification_count,
        review_speed_loc_per_hour,
        review_speed_kloc_per_hour,
        review_defect_density_per_kloc,
        review_efficiency_per_hour,
        commit_count,
        commit_rate,
        function_name,
        clang_added_line_count
      from code_review_match_mode_records
      """ + BASE_WHERE;
  private static final Map<String, String> SORT_COLUMNS = createSortColumns();

  private final JdbcTemplate jdbcTemplate;

  public CodeReviewMatchModeRecordLoader(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  //兼容模式-MatchMode
  public List<CodeReviewIllegalRecordSource> loadSources(Map<String, String> filters) {
    QueryParts parts = buildSourceQuery(filters == null ? Map.of() : filters);
    return jdbcTemplate.query(SELECT_SQL + parts.tailWhere() + " order by merged_at_source desc nulls last", this::mapSource, parts.args().toArray());
  }

  //兼容模式-MatchMode
  public CodeReviewIllegalRecordFilterOptionValues loadFilterOptions(
      CodeReviewIllegalRecordFilterOptionsRequest request) {
    CodeReviewIllegalRecordFilterOptionsRequest safeRequest =
        request == null
            ? new CodeReviewIllegalRecordFilterOptionsRequest(null, null, null, null)
            : request;
    QueryParts projectParts = buildFilterOptionQuery(null, null, null, safeRequest.source());
    //兼容模式-MatchMode：老平台下拉候选按当前 CC/DGM 与 name/nameSearch 取数，
    //不被已选 projectName 反向缩窄，否则项目名称下拉会只剩当前值，切源后也容易残留旧条件。
    QueryParts scopedParts =
        buildFilterOptionQuery(
            safeRequest.projectId(),
            safeRequest.repositoryName(),
            null,
            safeRequest.source());
    Map<String, List<String>> projectValues =
        queryOptionValues(projectParts, Map.of("repositoryNames", "repository_name"));
    Map<String, List<String>> scopedValues =
        queryOptionValues(scopedParts, scopedOptionColumns());
    return new CodeReviewIllegalRecordFilterOptionValues(
        queryProjectOptions(projectParts),
        projectValues.getOrDefault("repositoryNames", List.of()),
        scopedValues.getOrDefault("targetBranches", List.of()),
        scopedValues.getOrDefault("owners", List.of()),
        scopedValues.getOrDefault("mergedBys", List.of()),
        scopedValues.getOrDefault("moduleNames", List.of()),
        scopedValues.getOrDefault("projectNames", List.of()));
  }

  //兼容模式-MatchMode
  public PageSlice<CodeReviewIllegalRecordSource> loadDefaultIllegalPage(CodeReviewIllegalRecordSourcePageQuery query) {
    if (!matchesRequestType(query.request().requestType())) {
      return new PageSlice<>(List.of(), 0, query.page(), query.size());
    }
    QueryParts parts = buildPageQuery(query.request(), query.filterGroup(), true);
    Long total = jdbcTemplate.queryForObject("select count(*) from code_review_match_mode_records" + parts.where(), Long.class, parts.args().toArray());
    if (total == null || total == 0) {
      return new PageSlice<>(List.of(), 0, query.page(), query.size());
    }
    List<Object> args = new ArrayList<>(parts.args());
    args.add(query.size());
    args.add((long) (query.page() - 1) * query.size());
    List<CodeReviewIllegalRecordSource> records =
        jdbcTemplate.query(
            SELECT_SQL
                + parts.tailWhere()
                + " order by "
                + sortColumn(query.sortField())
                + " "
                + sortOrder(query.sortOrder())
                + nullsClause(query.sortOrder())
                + ", merged_at_source "
                + sortOrder(query.sortOrder())
                + nullsClause(query.sortOrder())
                + ", merge_request_iid "
                + sortOrder(query.sortOrder())
                + " limit ? offset ?",
            this::mapSource,
            args.toArray());
    return new PageSlice<>(records, total, query.page(), query.size());
  }

  //兼容模式-MatchMode
  public List<CodeReviewIllegalRecordSource> loadLegacyAllExportSources(
      CodeReviewIllegalRecordQueryRequest request,
      StatisticFilterGroup filterGroup) {
    QueryParts parts = buildPageQuery(request, filterGroup, false);
    return jdbcTemplate.query(
        SELECT_SQL + parts.tailWhere() + " order by merge_request_iid desc nulls last, merged_at_source desc nulls last",
        this::mapSource,
        parts.args().toArray());
  }

  //兼容模式-MatchMode
  public List<CodeReviewIllegalRecordSource> loadDefaultIllegalExportSources(
      CodeReviewIllegalRecordQueryRequest request,
      StatisticFilterGroup filterGroup) {
    if (!matchesRequestType(request.requestType())) {
      return List.of();
    }
    QueryParts parts = buildPageQuery(request, filterGroup, true);
    return jdbcTemplate.query(
        SELECT_SQL + parts.tailWhere() + orderByClause(request.sortField(), request.sortOrder()),
        this::mapSource,
        parts.args().toArray());
  }

  private QueryParts buildSourceQuery(Map<String, String> filters) {
    StringBuilder where = new StringBuilder(BASE_WHERE);
    List<Object> args = new ArrayList<>();
    appendEq(where, args, "project_id", parseLong(filters.get("projectId")));
    appendEqIgnoreCase(where, args, "project_name", filters.get("projectName"));
    appendEqIgnoreCase(where, args, "repository_name", filters.get("repositoryName"));
    appendSourceInstance(where, args, filters.get("sourceInstance"));
    appendEqIgnoreCase(where, args, "target_branch", filters.get("targetBranch"));
    appendEqIgnoreCase(where, args, "module_name", filters.get("moduleName"));
    appendEqIgnoreCase(where, args, "author_name", filters.get("author"));
    appendMergeRequestIidLike(where, args, filters.get("mergeRequestIid"));
    appendDateFrom(where, args, "merged_at_source", filters.get("mergedAtStart"));
    appendDateTo(where, args, "merged_at_source", filters.get("mergedAtEnd"));
    return new QueryParts(where.toString(), args);
  }

  private QueryParts buildPageQuery(
      CodeReviewIllegalRecordQueryRequest request,
      StatisticFilterGroup filterGroup,
      boolean appendIllegalPredicate) {
    StringBuilder where = new StringBuilder(BASE_WHERE);
    List<Object> args = new ArrayList<>();
    appendEq(where, args, "project_id", request.projectId());
    appendIndexedSearch(where, args, request.keyword());
    appendEqIgnoreCase(where, args, "project_name", request.projectName());
    appendEqIgnoreCase(where, args, "repository_name", request.repositoryName());
    appendSourceInstance(where, args, request.source());
    appendEqIgnoreCase(
        where,
        args,
        "target_branch",
        CodeReviewIllegalRecordQuerySupport.explicitTargetBranch(request.targetBranch()));
    appendEqIgnoreCase(where, args, "module_name", request.moduleName());
    appendEqIgnoreCase(where, args, "author_name", request.owner());
    appendMergeRequestIidLike(where, args, request.mergeRequestIid());
    appendDateFrom(where, args, "merged_at_source", request.mergedAtStart());
    appendDateTo(where, args, "merged_at_source", request.mergedAtEnd());
    appendEqIgnoreCase(where, args, "merge_user_name", request.mergedBy());
    if (appendIllegalPredicate) {
      where.append(" and (").append(legacyIllegalPredicate(request.illegalType())).append(")");
    }
    appendFilterGroup(where, args, filterGroup);
    return new QueryParts(where.toString(), args);
  }

  private QueryParts buildFilterOptionQuery(
      Long projectId, String repositoryName, String projectName, String source) {
    StringBuilder where = new StringBuilder(BASE_WHERE);
    List<Object> args = new ArrayList<>();
    appendEq(where, args, "project_id", projectId);
    appendEqIgnoreCase(where, args, "repository_name", repositoryName);
    appendEqIgnoreCase(where, args, "project_name", projectName);
    appendSourceInstance(where, args, source);
    return new QueryParts(where.toString(), args);
  }

  private List<CodeReviewIllegalRecordFilterProjectOption> queryProjectOptions(QueryParts parts) {
    return jdbcTemplate.query(
        "select project_id, project_name from code_review_match_mode_records "
            + parts.where()
            + """
               and project_id is not null
             group by project_id, project_name
             order by lower(coalesce(project_name, '')), project_id
            """,
        (rs, rowNum) ->
            new CodeReviewIllegalRecordFilterProjectOption(
                rs.getLong("project_id"), rs.getString("project_name")),
        parts.args().toArray());
  }

  private Map<String, List<String>> queryOptionValues(
      QueryParts parts, Map<String, String> optionColumns) {
    if (optionColumns.isEmpty()) {
      return Map.of();
    }
    String selectColumns =
        optionColumns.values().stream().distinct().reduce((left, right) -> left + ", " + right).orElse("");
    String unionSql =
        optionColumns.entrySet().stream()
            .map(entry -> "select '" + entry.getKey() + "' as option_group, " + entry.getValue() + " as option_value from scoped")
            .reduce((left, right) -> left + "\nunion all\n" + right)
            .orElse("");
    List<OptionGroupValue> rows =
        jdbcTemplate.query(
            "with scoped as (select "
                + selectColumns
                + " from code_review_match_mode_records "
                + parts.where()
                + ") select option_group, option_value from ("
                + unionSql
                + """
                  ) option_values
                 where nullif(btrim(coalesce(option_value, '')), '') is not null
                 group by option_group, option_value
                 order by option_group, lower(option_value)
                """,
            (rs, rowNum) -> new OptionGroupValue(rs.getString("option_group"), rs.getString("option_value")),
            parts.args().toArray());
    Map<String, List<String>> result = new LinkedHashMap<>();
    for (OptionGroupValue row : rows) {
      result.computeIfAbsent(row.group(), ignored -> new ArrayList<>()).add(row.value());
    }
    return result;
  }

  private Map<String, String> scopedOptionColumns() {
    Map<String, String> columns = new LinkedHashMap<>();
    columns.put("targetBranches", "target_branch");
    columns.put("owners", "author_name");
    columns.put("mergedBys", "merge_user_name");
    columns.put("moduleNames", "module_name");
    columns.put("projectNames", "project_name");
    return columns;
  }

  private void appendFilterGroup(StringBuilder where, List<Object> args, StatisticFilterGroup filterGroup) {
    CodeReviewIllegalRecordSqlSupport.toSql(filterGroup, this::legacyIllegalPredicate)
        .filter(filter -> TextQuerySupport.trimToNull(filter.predicate()) != null)
        .ifPresent(
            filter -> {
              where.append(" and (").append(filter.predicate()).append(")");
              args.addAll(filter.args());
            });
  }

  private boolean matchesRequestType(String requestType) {
    String normalized = TextQuerySupport.normalizeForMatch(requestType);
    return normalized == null || "merge_request".equals(normalized);
  }

  //兼容模式-MatchMode
  private String legacyIllegalPredicate(String illegalType) {
    String normalized = TextQuerySupport.trimToNull(illegalType);
    if (normalized == null) {
      return String.join(
          " or ",
          legacyMissingProjectPredicate(),
          legacyMissingModulePredicate(),
          legacyMissingReviewPredicate(),
          legacyNotScannedPredicate(),
          legacyOpenScanIssuePredicate(),
          legacyCommentRateNotPassPredicate(),
          legacyScanFailedPredicate(),
          legacyClangResultFalsePredicate(),
          legacyGitlabErrorPredicate());
    }
    if (CodeReviewIllegalRuleRegistry.MISSING_PROJECT_LABEL.equals(normalized)
        || CodeReviewIllegalRuleRegistry.LEGACY_MISSING_PROJECT_FILTER_LABEL.equals(normalized)) {
      return legacyMissingProjectPredicate();
    }
    if (CodeReviewIllegalRuleRegistry.MISSING_MODULE_LABEL.equals(normalized)
        || CodeReviewIllegalRuleRegistry.LEGACY_MISSING_MODULE_FILTER_LABEL.equals(normalized)) {
      return legacyMissingModulePredicate();
    }
    if (CodeReviewIllegalRuleRegistry.MISSING_REVIEW_LABEL.equals(normalized)
        || CodeReviewIllegalRuleRegistry.LEGACY_MISSING_REVIEW_FILTER_LABEL.equals(normalized)) {
      return legacyMissingReviewPredicate();
    }
    if (CodeReviewIllegalRuleRegistry.NOT_SCANNED_LABEL.equals(normalized)
        || CodeReviewIllegalRuleRegistry.LEGACY_NOT_SCANNED_FILTER_LABEL.equals(normalized)) {
      return legacyNotScannedPredicate();
    }
    if (CodeReviewIllegalRuleRegistry.OPEN_SCAN_ISSUE_LABEL.equals(normalized)) {
      return legacyOpenScanIssuePredicate();
    }
    if (CodeReviewIllegalRuleRegistry.COMMENT_RATE_NOT_PASS_LABEL.equals(normalized)) {
      return legacyCommentRateNotPassPredicate();
    }
    if (CodeReviewIllegalRuleRegistry.SCAN_FAILED_LABEL.equals(normalized)) {
      return legacyScanFailedPredicate();
    }
    if (CodeReviewIllegalRuleRegistry.CLANG_RESULT_FALSE_LABEL.equals(normalized)) {
      return legacyClangResultFalsePredicate();
    }
    if (CodeReviewIllegalRuleRegistry.GITLAB_ERROR_LABEL.equals(normalized)) {
      return legacyGitlabErrorPredicate();
    }
    return "1 = 0";
  }

  private String legacyMissingProjectPredicate() {
    return "project_name = '未标注项目名'";
  }

  private String legacyMissingModulePredicate() {
    return "module_name = '未标注模块名'";
  }

  private String legacyMissingReviewPredicate() {
    StringBuilder sql = new StringBuilder("reviewer_names in (");
    List<String> reasons = CodeReviewIllegalRuleRegistry.LEGACY_REVIEW_EXCEPTION_REASONS;
    for (int index = 0; index < reasons.size(); index++) {
      if (index > 0) {
        sql.append(", ");
      }
      sql.append("'").append(reasons.get(index).replace("'", "''")).append("'");
    }
    sql.append(")");
    return sql.toString();
  }

  private String legacyNotScannedPredicate() {
    return "scan_status = '未进行代码扫描'";
  }

  private String legacyOpenScanIssuePredicate() {
    return "bug_count_result = '静态扫描问题未关闭'";
  }

  private String legacyCommentRateNotPassPredicate() {
    return "annotation_rate_result = '代码注释量未达标'";
  }

  private String legacyScanFailedPredicate() {
    return "bug_count_result = '静态扫描失败'";
  }

  private String legacyClangResultFalsePredicate() {
    return "annotation_rate_result = '注释率分析工具Clang分析错误'";
  }

  private String legacyGitlabErrorPredicate() {
    return "reviewer_names = 'GitLab 接口报错' or scan_status = 'GitLab 接口报错' or target_branch = 'GitLab 接口报错'";
  }

  private void appendEq(StringBuilder where, List<Object> args, String column, Long value) {
    if (value == null) {
      return;
    }
    where.append(" and ").append(column).append(" = ?");
    args.add(value);
  }

  private void appendMergeRequestIidLike(StringBuilder where, List<Object> args, String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return;
    }
    where.append(" and cast(merge_request_iid as text) like ?");
    args.add("%" + normalized + "%");
  }

  private void appendContains(StringBuilder where, List<Object> args, String column, String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return;
    }
    where.append(" and ").append(column).append(" like ?");
    args.add("%" + normalized + "%");
  }

  private void appendIndexedSearch(StringBuilder where, List<Object> args, String value) {
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
      for (String column : List.of("search_text", "search_compact", "search_spell", "search_initials")) {
        predicates.add(column + " like ?");
        args.add(pattern);
      }
    }
    where.append(" and (").append(String.join(" or ", predicates)).append(")");
  }

  private void appendEqIgnoreCase(StringBuilder where, List<Object> args, String column, String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return;
    }
    where.append(" and lower(coalesce(").append(column).append(", '')) = ?");
    args.add(normalized.toLowerCase(Locale.ROOT));
  }

  private void appendSourceInstance(StringBuilder where, List<Object> args, String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return;
    }
    String source = GitlabSourceInstanceSupport.normalizeSourceInstance(normalized);
    if ("cc".equals(source) || "default".equals(source)) {
      where.append(" and lower(coalesce(source_instance, 'default')) in ('cc', 'default')");
      return;
    }
    where.append(" and lower(coalesce(source_instance, 'default')) = ?");
    args.add(source);
  }

  private void appendDateFrom(StringBuilder where, List<Object> args, String column, String rawValue) {
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

  private Long parseLong(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    return normalized == null ? null : Long.parseLong(normalized);
  }

  private String sortColumn(String sortField) {
    return SORT_COLUMNS.getOrDefault(sortField, "merged_at_source");
  }

  private String orderByClause(String sortField, String sortOrder) {
    String order = sortOrder(sortOrder);
    return " order by "
        + sortColumn(sortField)
        + " "
        + order
        + nullsClause(order)
        + ", merged_at_source "
        + order
        + nullsClause(order)
        + ", merge_request_iid "
        + order;
  }

  private String sortOrder(String sortOrder) {
    return "asc".equalsIgnoreCase(sortOrder) ? "asc" : "desc";
  }

  private String nullsClause(String sortOrder) {
    return "asc".equalsIgnoreCase(sortOrder) ? " nulls last" : " nulls first";
  }

  private CodeReviewIllegalRecordSource mapSource(ResultSet rs, int rowNum) throws SQLException {
    return new CodeReviewIllegalRecordSource(
        rs.getString("source_instance"),
        rs.getLong("merge_request_id"),
        rs.getInt("merge_request_iid"),
        rs.getLong("project_id"),
        rs.getString("merge_request_content"),
        rs.getString("project_name"),
        rs.getString("repository_name"),
        rs.getTimestamp("merged_at") == null ? null : rs.getTimestamp("merged_at").toLocalDateTime(),
        rs.getString("author"),
        rs.getString("merged_by"),
        rs.getString("owner"),
        rs.getString("reviewer_names"),
        rs.getString("assignee_names"),
        rs.getString("target_branch"),
        rs.getString("module_name"),
        splitLabels(rs.getString("label_names")),
        rs.getString("review_status"),
        (Integer) rs.getObject("review_duration_minutes"),
        rs.getString("review_exception_reason"),
        rs.getTimestamp("code_walkthrough_date") == null ? null : rs.getTimestamp("code_walkthrough_date").toLocalDateTime(),
        rs.getString("scan_status"),
        (Integer) rs.getObject("scan_bug_count"),
        rs.getString("annotation_rate_result"),
        rs.getString("bug_count_result"),
        toDouble(rs.getObject("comment_rate")),
        (Integer) rs.getObject("defect_count"),
        (Integer) rs.getObject("added_lines"),
        (Integer) rs.getObject("deleted_lines"),
        (Integer) rs.getObject("code_specification_count"),
        (Integer) rs.getObject("code_logic_specification_count"),
        (Integer) rs.getObject("performance_specification_count"),
        (Integer) rs.getObject("design_specification_count"),
        (Integer) rs.getObject("other_specification_count"),
        (Integer) rs.getObject("review_speed_loc_per_hour"),
        toDouble(rs.getObject("review_speed_kloc_per_hour")),
        toDouble(rs.getObject("review_defect_density_per_kloc")),
        toDouble(rs.getObject("review_efficiency_per_hour")),
        (Integer) rs.getObject("commit_count"),
        (Integer) rs.getObject("commit_rate"),
        rs.getString("function_name"),
        (Integer) rs.getObject("clang_added_line_count"));
  }

  private Double toDouble(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    return Double.valueOf(String.valueOf(value));
  }

  private List<String> splitLabels(String labelNames) {
    if (!StringUtils.hasText(labelNames)) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    for (String value : labelNames.split(",")) {
      String normalized = TextQuerySupport.trimToNull(value);
      if (normalized != null) {
        result.add(normalized);
      }
    }
    return result;
  }

  private static Map<String, String> createSortColumns() {
    Map<String, String> columns = new LinkedHashMap<>();
    columns.put("mergeRequestIid", "merge_request_iid");
    columns.put("mergeRequestContent", "lower(coalesce(title, ''))");
    columns.put("author", "lower(coalesce(author_name, ''))");
    columns.put("owner", "lower(coalesce(owner_name, ''))");
    columns.put("projectName", "lower(coalesce(project_name, ''))");
    columns.put("repositoryName", "lower(coalesce(repository_name, ''))");
    columns.put("mergedAt", "merged_at_source");
    columns.put("mergedBy", "lower(coalesce(merge_user_name, ''))");
    columns.put("moduleName", "lower(coalesce(module_name, ''))");
    columns.put("targetBranch", "lower(coalesce(target_branch, ''))");
    columns.put("commentRate", "comment_rate");
    columns.put("defectCount", "defect_count");
    columns.put("addedLines", "added_lines");
    return Map.copyOf(columns);
  }

  private record QueryParts(String where, List<Object> args) {
    String tailWhere() {
      return where.substring(BASE_WHERE.length());
    }
  }

  private record OptionGroupValue(String group, String value) {}
}
