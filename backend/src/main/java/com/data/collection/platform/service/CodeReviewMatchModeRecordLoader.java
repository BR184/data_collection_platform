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
        and merged_at_source > timestamp '2024-04-01 00:00:00'
        and coalesce(module_name, '') <> '无需标注'
        and coalesce(project_name, '') <> '无需标注'
        and coalesce(label_names, '') not like '%无需走查扫描%'
        and (
          lower(coalesce(project_name, '')) not in ('crowncad', 'dgm')
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

  private QueryParts buildSourceQuery(Map<String, String> filters) {
    StringBuilder where = new StringBuilder(BASE_WHERE);
    List<Object> args = new ArrayList<>();
    appendEq(where, args, "project_id", parseLong(filters.get("projectId")));
    appendEqIgnoreCase(where, args, "project_name", filters.get("projectName"));
    appendContains(where, args, "repository_name", filters.get("repositoryName"));
    appendContains(where, args, "target_branch", filters.get("targetBranch"));
    appendContains(where, args, "module_name", filters.get("moduleName"));
    appendContains(where, args, "author_name", filters.get("author"));
    appendEq(where, args, "merge_request_iid", parseLong(filters.get("mergeRequestIid")));
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
    appendContains(where, args, "repository_name", request.repositoryName());
    appendContains(
        where,
        args,
        "target_branch",
        CodeReviewIllegalRecordQuerySupport.explicitTargetBranch(request.targetBranch()));
    appendContains(where, args, "module_name", request.moduleName());
    appendContains(where, args, "author_name", request.owner());
    appendEq(where, args, "merge_request_iid", parseLong(request.mergeRequestIid()));
    appendDateFrom(where, args, "merged_at_source", request.mergedAtStart());
    appendDateTo(where, args, "merged_at_source", request.mergedAtEnd());
    appendEqIgnoreCase(where, args, "merge_user_name", request.mergedBy());
    if (appendIllegalPredicate) {
      where.append(" and (").append(CodeReviewIllegalRecordSqlSupport.illegalPredicate(request.illegalType(), request.source())).append(")");
    }
    appendFilterGroup(where, args, filterGroup);
    return new QueryParts(where.toString(), args);
  }

  private void appendFilterGroup(StringBuilder where, List<Object> args, StatisticFilterGroup filterGroup) {
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
}
