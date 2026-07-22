package com.data.collection.platform.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class CodeReviewIllegalRecordSourceLoader {
  private static final String LEGACY_ILLEGAL_BASE_WHERE = """
       where upper(coalesce(merge_request_state, '')) = 'MERGED'
         and merged_at_source > timestamp '2024-04-01 00:00:00'
        and coalesce(module_name, '') <> '无需标注'
        and coalesce(project_name, '') <> '无需标注'
        and coalesce(label_names, '') not like '%无需走查扫描%'
         and (
          lower(btrim(coalesce(repository_name, ''))) not in ('crowncad', 'dgm')
          or lower(btrim(coalesce(target_branch, ''))) = 'dev'
        )
      """;
  private static final String FACT_SQL = """
      select
        business_source as source_instance,
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
      from code_review_formal_records
      """ + LEGACY_ILLEGAL_BASE_WHERE;
  private static final String ALL_EXPORT_FACT_SQL = """
      select
        business_source as source_instance,
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
      from code_review_formal_records
      """ + LEGACY_ILLEGAL_BASE_WHERE;
  private static final Map<String, String> SORT_COLUMNS = createSortColumns();

  private final MergeRequestFactQueryService mergeRequestFactQueryService;

  public CodeReviewIllegalRecordSourceLoader(
      MergeRequestFactQueryService mergeRequestFactQueryService) {
    this.mergeRequestFactQueryService = mergeRequestFactQueryService;
  }

  public List<CodeReviewIllegalRecordSource> loadSources(Map<String, String> filters) {
    try {
      List<CodeReviewIllegalRecordSource> facts = ensureFactsReady(filters);
      if (!facts.isEmpty()) {
        return facts;
      }
    } catch (DataAccessException e) {
      log.warn("Failed to load merge request facts", e);
      return List.of();
    }
    return List.of();
  }

  public CodeReviewIllegalRecordFilterOptionValues loadFilterOptions(
      CodeReviewIllegalRecordFilterOptionsRequest request) {
    CodeReviewIllegalRecordFilterOptionsRequest safeRequest =
        request == null
            ? new CodeReviewIllegalRecordFilterOptionsRequest(null, null, null, null)
            : request;
    QueryParts projectParts = buildFilterOptionQuery(null, null, null, safeRequest.source());
    QueryParts scopedParts =
        buildFilterOptionQuery(
            safeRequest.projectId(),
            safeRequest.repositoryName(),
            safeRequest.projectName(),
            safeRequest.source());
    Map<String, List<String>> projectValues =
        queryOptionValues(projectParts, Map.of("repositoryNames", "repository_name", "projectNames", "project_name"));
    Map<String, List<String>> scopedValues =
        queryOptionValues(scopedParts, scopedOptionColumns());
    return new CodeReviewIllegalRecordFilterOptionValues(
        queryProjectOptions(projectParts),
        projectValues.getOrDefault("repositoryNames", List.of()),
        scopedValues.getOrDefault("targetBranches", List.of()),
        scopedValues.getOrDefault("owners", List.of()),
        scopedValues.getOrDefault("mergedBys", List.of()),
        scopedValues.getOrDefault("moduleNames", List.of()),
        projectValues.getOrDefault("projectNames", List.of()));
  }

  public PageSlice<CodeReviewIllegalRecordSource> loadDefaultIllegalPage(
      CodeReviewIllegalRecordSourcePageQuery query) {
    if (!matchesRequestType(query.request().requestType())) {
      return new PageSlice<>(List.of(), 0, query.page(), query.size());
    }
    try {
      PageSlice<CodeReviewIllegalRecordSource> page = loadDefaultIllegalPageOnce(query);
      if (page.total() > 0) {
        return page;
      }
      return page;
    } catch (DataAccessException e) {
      log.warn("Failed to load paged merge request illegal facts", e);
      return new PageSlice<>(List.of(), 0, query.page(), query.size());
    }
  }

  public List<CodeReviewIllegalRecordSource> loadLegacyAllExportSources(
      CodeReviewIllegalRecordQueryRequest request,
      com.data.collection.platform.entity.statistics.StatisticFilterGroup filterGroup) {
    QueryParts parts = buildAllExportQuery(request, filterGroup);
    return mergeRequestFactQueryService.query(
        ALL_EXPORT_FACT_SQL
            + parts.tailWhere(ALL_EXPORT_FACT_SQL)
            + " order by merge_request_iid desc nulls last, merged_at_source desc nulls last",
        parts.args(),
        this::mapFactSource);
  }

  public List<CodeReviewIllegalRecordSource> loadDefaultIllegalExportSources(
      CodeReviewIllegalRecordQueryRequest request,
      com.data.collection.platform.entity.statistics.StatisticFilterGroup filterGroup) {
    if (!matchesRequestType(request.requestType())) {
      return List.of();
    }
    QueryParts parts =
        buildPageQuery(
            new CodeReviewIllegalRecordSourcePageQuery(
                request, filterGroup, 1, 1, request.sortField(), request.sortOrder()));
    return mergeRequestFactQueryService.query(
        FACT_SQL + parts.tailWhere() + orderByClause(request.sortField(), request.sortOrder()),
        parts.args(),
        this::mapFactSource);
  }

  private List<CodeReviewIllegalRecordSource> ensureFactsReady(Map<String, String> filters) {
    List<CodeReviewIllegalRecordSource> facts = mergeRequestFactQueryService.query(FACT_SQL, filters, this::mapFactSource);
    if (!facts.isEmpty()) {
      return facts;
    }
    return List.of();
  }

  private PageSlice<CodeReviewIllegalRecordSource> loadDefaultIllegalPageOnce(
      CodeReviewIllegalRecordSourcePageQuery query) {
    QueryParts parts = buildPageQuery(query);
    long total =
        mergeRequestFactQueryService.count(
            "select count(*) from code_review_formal_records" + parts.where(), parts.args());
    if (total == 0) {
      return new PageSlice<>(List.of(), 0, query.page(), query.size());
    }
    List<Object> pageArgs = new ArrayList<>(parts.args());
    pageArgs.add(query.size());
    pageArgs.add((long) (query.page() - 1) * query.size());
    List<CodeReviewIllegalRecordSource> records =
        mergeRequestFactQueryService.query(
            FACT_SQL
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
            pageArgs,
            this::mapFactSource);
    return new PageSlice<>(records, total, query.page(), query.size());
  }

  private QueryParts buildPageQuery(CodeReviewIllegalRecordSourcePageQuery query) {
    CodeReviewIllegalRecordQueryRequest request = query.request();
    StringBuilder where = new StringBuilder(LEGACY_ILLEGAL_BASE_WHERE);
    List<Object> args = new ArrayList<>();
    appendEq(where, args, "project_id", request.projectId());
    appendIndexedSearch(
        where,
        args,
        List.of("search_text", "search_compact", "search_spell", "search_initials"),
        request.keyword());
    appendEqIgnoreCase(where, args, "project_name", request.projectName());
    appendContains(where, args, "repository_name", request.repositoryName());
    appendContains(
        where,
        args,
        "target_branch",
        CodeReviewIllegalRecordQuerySupport.explicitTargetBranch(request.targetBranch()));
    appendContains(where, args, "module_name", request.moduleName());
    appendContains(where, args, "author_name", request.owner());
    appendSourceInstance(where, args, request.source());
    appendEq(where, args, "merge_request_iid", parseLong(request.mergeRequestIid()));
    appendDateFrom(where, args, "merged_at_source", request.mergedAtStart());
    appendDateTo(where, args, "merged_at_source", request.mergedAtEnd());
    appendEqIgnoreCase(where, args, "merge_user_name", request.mergedBy());
    appendIllegalPredicate(where, request.illegalType(), request.source());
    appendFilterGroup(where, args, query.filterGroup());
    return new QueryParts(where.toString(), args);
  }

  private QueryParts buildFilterOptionQuery(
      Long projectId, String repositoryName, String projectName, String source) {
    StringBuilder where = new StringBuilder(LEGACY_ILLEGAL_BASE_WHERE);
    List<Object> args = new ArrayList<>();
    appendEq(where, args, "project_id", projectId);
    appendContains(where, args, "repository_name", repositoryName);
    appendContains(where, args, "project_name", projectName);
    appendSourceInstance(where, args, source);
    appendIllegalPredicate(where, null, source);
    return new QueryParts(where.toString(), args);
  }

  private List<CodeReviewIllegalRecordFilterProjectOption> queryProjectOptions(QueryParts parts) {
    return mergeRequestFactQueryService.query(
        "select project_id, project_name from code_review_formal_records "
            + parts.where()
            + """
               and project_id is not null
             group by project_id, project_name
             order by lower(coalesce(project_name, '')), project_id
            """,
        parts.args(),
        (rs, rowNum) ->
            new CodeReviewIllegalRecordFilterProjectOption(
                rs.getLong("project_id"), rs.getString("project_name")));
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
        mergeRequestFactQueryService.query(
            "with scoped as (select "
                + selectColumns
                + " from code_review_formal_records "
                + parts.where()
                + ") select option_group, option_value from ("
                + unionSql
                + """
                  ) option_values
                 where nullif(btrim(coalesce(option_value, '')), '') is not null
                 group by option_group, option_value
                 order by option_group, lower(option_value)
                """,
            parts.args(),
            (rs, rowNum) -> new OptionGroupValue(rs.getString("option_group"), rs.getString("option_value")));
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
    return columns;
  }

  private QueryParts buildAllExportQuery(
      CodeReviewIllegalRecordQueryRequest request,
      com.data.collection.platform.entity.statistics.StatisticFilterGroup filterGroup) {
    String baseWhere = ALL_EXPORT_FACT_SQL.substring(ALL_EXPORT_FACT_SQL.indexOf("where "));
    StringBuilder where = new StringBuilder(baseWhere);
    List<Object> args = new ArrayList<>();
    appendEq(where, args, "project_id", request.projectId());
    appendIndexedSearch(
        where,
        args,
        List.of("search_text", "search_compact", "search_spell", "search_initials"),
        request.keyword());
    appendEqIgnoreCase(where, args, "project_name", request.projectName());
    appendContains(where, args, "repository_name", request.repositoryName());
    appendContains(
        where,
        args,
        "target_branch",
        CodeReviewIllegalRecordQuerySupport.explicitTargetBranch(request.targetBranch()));
    appendContains(where, args, "module_name", request.moduleName());
    appendContains(where, args, "author_name", request.owner());
    appendSourceInstance(where, args, request.source());
    appendEq(where, args, "merge_request_iid", parseLong(request.mergeRequestIid()));
    appendDateFrom(where, args, "merged_at_source", request.mergedAtStart());
    appendDateTo(where, args, "merged_at_source", request.mergedAtEnd());
    appendEqIgnoreCase(where, args, "merge_user_name", request.mergedBy());
    appendFilterGroup(where, args, filterGroup);
    return new QueryParts(where.toString(), args);
  }

  private void appendIllegalPredicate(StringBuilder where, String illegalType, String source) {
    String predicate = CodeReviewIllegalRecordSqlSupport.illegalPredicate(illegalType, source);
    where.append(" and (").append(predicate).append(")");
  }

  private void appendFilterGroup(
      StringBuilder where,
      List<Object> args,
      com.data.collection.platform.entity.statistics.StatisticFilterGroup filterGroup) {
    CodeReviewIllegalRecordSqlSupport.toSql(filterGroup)
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

  private void appendEq(StringBuilder where, List<Object> args, String column, Long value) {
    if (value == null) {
      return;
    }
    where.append(" and ").append(column).append(" = ?");
    args.add(value);
  }

  private void appendContains(StringBuilder where, List<Object> args, String column, String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return;
    }
    where.append(" and ").append(column).append(" like ?");
    args.add("%" + normalized + "%");
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
    where.append(" and lower(coalesce(business_source, 'cc')) = ?");
    args.add("default".equals(source) ? "cc" : source);
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

  private CodeReviewIllegalRecordSource mapFactSource(ResultSet rs, int rowNum) throws SQLException {
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
      return where.substring(LEGACY_ILLEGAL_BASE_WHERE.length());
    }

    String tailWhere(String selectSql) {
      String baseWhere = selectSql.substring(selectSql.indexOf("where "));
      return where.substring(baseWhere.length());
    }
  }

  private record OptionGroupValue(String group, String value) {}
}
