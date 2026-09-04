package com.data.collection.platform.service;

import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class CodeReviewIllegalRecordSqlQueryBuilder {
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
  private static final String FACT_SELECT = """
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
      """;
  private static final Map<String, String> SORT_COLUMNS = createSortColumns();

  private CodeReviewIllegalRecordSqlQueryBuilder() {}

  static String factSql() {
    return FACT_SELECT + LEGACY_ILLEGAL_BASE_WHERE;
  }

  static QueryParts buildPageQuery(CodeReviewIllegalRecordSourcePageQuery query) {
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

  static QueryParts buildFilterOptionQuery(
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

  static QueryParts buildAllExportQuery(
      CodeReviewIllegalRecordQueryRequest request, StatisticFilterGroup filterGroup) {
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
    appendFilterGroup(where, args, filterGroup);
    return new QueryParts(where.toString(), args);
  }

  static Map<String, String> scopedOptionColumns() {
    Map<String, String> columns = new LinkedHashMap<>();
    columns.put("targetBranches", "target_branch");
    columns.put("owners", "author_name");
    columns.put("mergedBys", "merge_user_name");
    columns.put("moduleNames", "module_name");
    return columns;
  }

  static boolean matchesRequestType(String requestType) {
    String normalized = TextQuerySupport.normalizeForMatch(requestType);
    return normalized == null || "merge_request".equals(normalized);
  }

  static String orderByClause(String sortField, String sortOrder) {
    String order = sortOrder(sortOrder);
    return " order by "
        + sortColumn(sortField)
        + " "
        + order
        + nullsClause(sortOrder)
        + ", merged_at_source "
        + order
        + nullsClause(sortOrder)
        + ", merge_request_iid "
        + order;
  }

  static String sortColumn(String sortField) {
    // sortField 为 null 是合法输入（导出请求可不带排序字段）；不可变映射对 null 键查询会抛 NPE。
    return sortField == null
        ? "merged_at_source"
        : SORT_COLUMNS.getOrDefault(sortField, "merged_at_source");
  }

  static String sortOrder(String sortOrder) {
    return "asc".equalsIgnoreCase(sortOrder) ? "asc" : "desc";
  }

  static String nullsClause(String sortOrder) {
    return "asc".equalsIgnoreCase(sortOrder) ? " nulls last" : " nulls first";
  }

  private static void appendIllegalPredicate(StringBuilder where, String illegalType, String source) {
    String predicate = CodeReviewIllegalRecordSqlSupport.illegalPredicate(illegalType, source);
    where.append(" and (").append(predicate).append(")");
  }

  private static void appendFilterGroup(
      StringBuilder where, List<Object> args, StatisticFilterGroup filterGroup) {
    CodeReviewIllegalRecordSqlSupport.toSql(filterGroup)
        .filter(filter -> TextQuerySupport.trimToNull(filter.predicate()) != null)
        .ifPresent(
            filter -> {
              where.append(" and (").append(filter.predicate()).append(")");
              args.addAll(filter.args());
            });
  }

  private static void appendEq(StringBuilder where, List<Object> args, String column, Long value) {
    if (value == null) {
      return;
    }
    where.append(" and ").append(column).append(" = ?");
    args.add(value);
  }

  private static void appendContains(
      StringBuilder where, List<Object> args, String column, String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return;
    }
    where.append(" and ").append(column).append(" like ?");
    args.add("%" + normalized + "%");
  }

  private static void appendIndexedSearch(
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

  private static void appendEqIgnoreCase(
      StringBuilder where, List<Object> args, String column, String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return;
    }
    where.append(" and lower(coalesce(").append(column).append(", '')) = ?");
    args.add(normalized.toLowerCase(Locale.ROOT));
  }

  private static void appendSourceInstance(
      StringBuilder where, List<Object> args, String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return;
    }
    String source = GitlabSourceInstanceSupport.normalizeSourceInstance(normalized);
    where.append(" and lower(coalesce(business_source, 'cc')) = ?");
    args.add("default".equals(source) ? "cc" : source);
  }

  private static void appendDateFrom(
      StringBuilder where, List<Object> args, String column, String rawValue) {
    LocalDate value = parseDate(rawValue);
    if (value == null) {
      return;
    }
    where.append(" and ").append(column).append(" >= ?");
    args.add(value.atStartOfDay());
  }

  private static void appendDateTo(
      StringBuilder where, List<Object> args, String column, String rawValue) {
    LocalDate value = parseDate(rawValue);
    if (value == null) {
      return;
    }
    where.append(" and ").append(column).append(" < ?");
    args.add(value.plusDays(1).atStartOfDay());
  }

  private static LocalDate parseDate(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    return normalized == null ? null : LocalDate.parse(normalized);
  }

  private static Long parseLong(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    return normalized == null ? null : Long.parseLong(normalized);
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

  record QueryParts(String where, List<Object> args) {
    String tailWhere() {
      return where.substring(LEGACY_ILLEGAL_BASE_WHERE.length());
    }
  }
}
