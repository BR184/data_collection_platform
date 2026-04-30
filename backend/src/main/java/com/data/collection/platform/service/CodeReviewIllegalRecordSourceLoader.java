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
  private static final String FACT_SQL = """
      select
        merge_request_id,
        merge_request_iid,
        project_id,
        title as merge_request_content,
        project_name,
        repository_name,
        merged_at_source as merged_at,
        merge_user_name as merged_by,
        owner_name as owner,
        target_branch,
        module_name,
        coalesce(label_names, '') as label_names,
        review_status,
        review_duration_minutes,
        scan_status,
        scan_bug_count,
        comment_rate,
        defect_count,
        added_lines
      from merge_request_fact
      where deleted = false
      """;
  private static final Map<String, String> SORT_COLUMNS = createSortColumns();

  private final FactBuildService factBuildService;
  private final MergeRequestFactQueryService mergeRequestFactQueryService;

  public CodeReviewIllegalRecordSourceLoader(
      FactBuildService factBuildService,
      MergeRequestFactQueryService mergeRequestFactQueryService) {
    this.factBuildService = factBuildService;
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
      factBuildService.rebuildMergeRequestFacts(true);
      return loadDefaultIllegalPageOnce(query);
    } catch (DataAccessException e) {
      log.warn("Failed to load paged merge request illegal facts", e);
      return new PageSlice<>(List.of(), 0, query.page(), query.size());
    }
  }

  private List<CodeReviewIllegalRecordSource> ensureFactsReady(Map<String, String> filters) {
    List<CodeReviewIllegalRecordSource> facts = mergeRequestFactQueryService.query(FACT_SQL, filters, this::mapFactSource);
    if (!facts.isEmpty()) {
      return facts;
    }
    factBuildService.rebuildMergeRequestFacts(true);
    return mergeRequestFactQueryService.query(FACT_SQL, filters, this::mapFactSource);
  }

  private PageSlice<CodeReviewIllegalRecordSource> loadDefaultIllegalPageOnce(
      CodeReviewIllegalRecordSourcePageQuery query) {
    QueryParts parts = buildPageQuery(query);
    long total =
        mergeRequestFactQueryService.count(
            "select count(*) from merge_request_fact" + parts.where(), parts.args());
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
    StringBuilder where = new StringBuilder(" where deleted = false");
    List<Object> args = new ArrayList<>();
    appendEq(where, args, "project_id", request.projectId());
    appendIndexedSearch(
        where,
        args,
        List.of("search_text", "search_compact", "search_spell", "search_initials"),
        request.keyword());
    appendContains(where, args, "project_name", request.projectName());
    appendContains(where, args, "repository_name", request.repositoryName());
    appendContains(where, args, "target_branch", request.targetBranch());
    appendContains(where, args, "module_name", request.moduleName());
    appendContains(where, args, "owner_name", request.owner());
    appendEq(where, args, "merge_request_iid", parseLong(request.mergeRequestIid()));
    appendDateFrom(where, args, "merged_at_source", request.mergedAtStart());
    appendDateTo(where, args, "merged_at_source", request.mergedAtEnd());
    appendEqIgnoreCase(where, args, "merge_user_name", request.mergedBy());
    appendIllegalPredicate(where, request.illegalType());
    appendFilterGroup(where, args, query.filterGroup());
    return new QueryParts(where.toString(), args);
  }

  private void appendIllegalPredicate(StringBuilder where, String illegalType) {
    String predicate = CodeReviewIllegalRecordSqlSupport.illegalPredicate(illegalType);
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

  private CodeReviewIllegalRecordSource mapFactSource(ResultSet rs, int rowNum) throws SQLException {
    return new CodeReviewIllegalRecordSource(
        rs.getLong("merge_request_id"),
        rs.getInt("merge_request_iid"),
        rs.getLong("project_id"),
        rs.getString("merge_request_content"),
        rs.getString("project_name"),
        rs.getString("repository_name"),
        rs.getTimestamp("merged_at") == null ? null : rs.getTimestamp("merged_at").toLocalDateTime(),
        rs.getString("merged_by"),
        rs.getString("owner"),
        rs.getString("target_branch"),
        rs.getString("module_name"),
        splitLabels(rs.getString("label_names")),
        rs.getString("review_status"),
        (Integer) rs.getObject("review_duration_minutes"),
        rs.getString("scan_status"),
        (Integer) rs.getObject("scan_bug_count"),
        toDouble(rs.getObject("comment_rate")),
        (Integer) rs.getObject("defect_count"),
        (Integer) rs.getObject("added_lines"));
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
      return where.substring(" where deleted = false".length());
    }
  }
}
