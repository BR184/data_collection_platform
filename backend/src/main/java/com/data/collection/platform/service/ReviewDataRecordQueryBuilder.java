package com.data.collection.platform.service;

import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
final class ReviewDataRecordQueryBuilder {
  private static final String BASE_LIST_SQL =
      """
      select
        r.id,
        r.project_name,
        r.title,
        r.module_name,
        r.review_type,
        r.review_date,
        r.review_owner,
        r.review_scale_pages,
        r.review_product,
        r.author_name,
        r.review_version,
        r.not_reach_standard_reason,
        r.source_file_name,
        """
          + ReviewDataMetricSqlExpressions.WEIGHTED_DEFECT_DENSITY
          + " as weighted_defect_density,\n        "
          + """
        r.gitlab_project_id,
        r.gitlab_resource_iid,
        r.gitlab_resource_type,
        r.created_at,
        r.updated_at,
        r.created_by,
        r.deleted,
        coalesce(expert.expert_names, '') as review_experts_summary,
        coalesce(problem.problem_count, 0) as problem_count,
        coalesce(problem.total_workload_hours, 0) as total_workload_hours,
        coalesce(problem.review_category_summary, '') as review_category_summary,
        coalesce(problem.doc_specification_count, 0) as doc_specification_count,
        coalesce(problem.integrity_count, 0) as integrity_count,
        coalesce(problem.functionality_count, 0) as functionality_count,
        coalesce(problem.feasibility_count, 0) as feasibility_count,
        coalesce(problem.independent_review_workload, 0) as independent_review_workload,
        coalesce(problem.independent_review_problem_count, 0) as independent_review_problem_count,
        coalesce(problem.meeting_review_workload, 0) as meeting_review_workload,
        coalesce(problem.meeting_review_problem_count, 0) as meeting_review_problem_count,
        """
          + ReviewDataMetricSqlExpressions.REVIEW_EFFICIENCY
          + " as review_efficiency,\n        "
          + ReviewDataMetricSqlExpressions.REVIEW_RATE
          + " as review_rate\n"
          + """
      from review_records r
      left join (
        select
          review_record_id,
          string_agg(expert_name, '、' order by sort_order asc, id asc) as expert_names
        from review_record_experts
        where deleted = false
        group by review_record_id
      ) expert on expert.review_record_id = r.id
      left join (
        select
          review_record_id,
          count(*) filter (where problem_status not in ('已拒绝', '未评审', '无问题') and problem_category <> '无问题')::integer as problem_count,
          coalesce(sum(workload_hours), 0) as total_workload_hours,
          string_agg(distinct nullif(review_category, ''), '、') as review_category_summary,
          count(*) filter (where problem_status not in ('已拒绝', '未评审', '无问题') and problem_category = '文档规范')::integer as doc_specification_count,
          count(*) filter (where problem_status not in ('已拒绝', '未评审', '无问题') and problem_category = '完整性')::integer as integrity_count,
          count(*) filter (where problem_status not in ('已拒绝', '未评审', '无问题') and problem_category = '功能性')::integer as functionality_count,
          count(*) filter (where problem_status not in ('已拒绝', '未评审', '无问题') and problem_category = '可行性')::integer as feasibility_count,
          coalesce(sum(workload_hours) filter (where review_category = '独立评审'), 0) as independent_review_workload,
          count(*) filter (
            where review_category = '独立评审'
              and problem_status not in ('已拒绝', '未评审', '无问题')
              and problem_category <> '无问题'
          )::integer as independent_review_problem_count,
          coalesce(sum(workload_hours) filter (where review_category = '会议评审'), 0) as meeting_review_workload,
          count(*) filter (
            where review_category = '会议评审'
              and problem_status not in ('已拒绝', '未评审', '无问题')
              and problem_category <> '无问题'
          )::integer as meeting_review_problem_count
        from review_problem_items
        where deleted = false
        group by review_record_id
      ) problem on problem.review_record_id = r.id
      where r.deleted = false
      """;

  String baseListSql() {
    return BASE_LIST_SQL;
  }

  SqlParts buildListQuery(
      String title,
      String projectName,
      String moduleName,
      String reviewOwner,
      String reviewType,
      String problemStatus,
      String reviewExpert,
      String keyword) {
    StringBuilder sql = new StringBuilder(BASE_LIST_SQL);
    List<Object> args = new ArrayList<>();
    appendContains(sql, args, "r.title", title);
    appendEqText(sql, args, "r.project_name", projectName);
    appendEqText(sql, args, "r.module_name", moduleName);
    appendContains(sql, args, "r.review_owner", reviewOwner);
    appendEqText(sql, args, "r.review_type", reviewType);
    appendProblemStatusFilter(sql, args, problemStatus);
    appendReviewExpertFilter(sql, args, reviewExpert);
    appendKeywordSearch(sql, args, keyword);
    return new SqlParts(sql.toString(), args);
  }

  SqlParts buildFilteredFromSql(
      String title,
      String projectName,
      String moduleName,
      String reviewOwner,
      String reviewType,
      String problemStatus,
      String reviewExpert,
      String keyword,
      StatisticFilterGroup filterGroup) {
    StringBuilder sql =
        new StringBuilder(
            """
             from review_records r
             left join lateral (
               select
                 count(*) filter (
                   where problem_status not in ('已拒绝', '未评审', '无问题')
                     and problem_category <> '无问题'
                 )::integer as problem_count,
                 coalesce(sum(workload_hours), 0) as total_workload_hours,
                 string_agg(distinct nullif(review_category, ''), '、') as review_category_summary,
                 count(*) filter (where problem_status not in ('已拒绝', '未评审', '无问题') and problem_category = '文档规范')::integer as doc_specification_count,
                 count(*) filter (where problem_status not in ('已拒绝', '未评审', '无问题') and problem_category = '完整性')::integer as integrity_count,
                 count(*) filter (where problem_status not in ('已拒绝', '未评审', '无问题') and problem_category = '功能性')::integer as functionality_count,
                 count(*) filter (where problem_status not in ('已拒绝', '未评审', '无问题') and problem_category = '可行性')::integer as feasibility_count,
                 coalesce(sum(workload_hours) filter (where review_category = '独立评审'), 0) as independent_review_workload,
                 count(*) filter (
                   where review_category = '独立评审'
                     and problem_status not in ('已拒绝', '未评审', '无问题')
                     and problem_category <> '无问题'
                 )::integer as independent_review_problem_count,
                 coalesce(sum(workload_hours) filter (where review_category = '会议评审'), 0) as meeting_review_workload,
                 count(*) filter (
                   where review_category = '会议评审'
                     and problem_status not in ('已拒绝', '未评审', '无问题')
                     and problem_category <> '无问题'
                 )::integer as meeting_review_problem_count
               from review_problem_items
               where review_record_id = r.id and deleted = false
             ) problem on true
             where r.deleted = false
            """);
    List<Object> args = new ArrayList<>();
    appendContains(sql, args, "r.title", title);
    appendEqText(sql, args, "r.project_name", projectName);
    appendEqText(sql, args, "r.module_name", moduleName);
    appendContains(sql, args, "r.review_owner", reviewOwner);
    appendEqText(sql, args, "r.review_type", reviewType);
    appendProblemStatusFilter(sql, args, problemStatus);
    appendReviewExpertFilter(sql, args, reviewExpert);
    appendKeywordSearch(sql, args, keyword);
    appendFilterGroup(sql, args, filterGroup);
    return new SqlParts(sql.toString(), args);
  }

  String buildWindowOrderBy(String sortField, String sortOrder) {
    String direction = "asc".equalsIgnoreCase(sortOrder) ? "asc" : "desc";
    String expression =
        switch (sortField) {
          case "title" -> "fr.title";
          case "projectName" -> "fr.project_name";
          case "moduleName" -> "fr.module_name";
          case "reviewType" -> "fr.review_type";
          case "reviewDate" -> "fr.review_date";
          case "reviewOwner" -> "fr.review_owner";
          case "reviewScalePages" -> "fr.review_scale_pages";
          case "problemCount" -> "fr.problem_count";
          case "problemDensity" -> "fr.problem_density";
          case "reviewEfficiency" -> "fr.review_efficiency";
          case "reviewRate" -> "fr.review_rate";
          case "weightedDefectDensity" -> "fr.weighted_defect_density";
          case "reviewCategorySummary" -> "fr.review_category_summary";
          case "docSpecificationCount" -> "fr.doc_specification_count";
          case "integrityCount" -> "fr.integrity_count";
          case "functionalityCount" -> "fr.functionality_count";
          case "feasibilityCount" -> "fr.feasibility_count";
          case "independentReviewWorkload" -> "fr.independent_review_workload";
          case "independentReviewProblemCount" -> "fr.independent_review_problem_count";
          case "meetingReviewWorkload" -> "fr.meeting_review_workload";
          case "meetingReviewProblemCount" -> "fr.meeting_review_problem_count";
          case "reachStandard" ->
              "case when fr.problem_density >= 0.2 and fr.problem_density <= 0.6 then 1 else 0 end";
          case "createdAt" -> "fr.created_at";
          default -> "fr.updated_at";
        };
    return " order by " + expression + " " + direction + " nulls last, fr.id asc";
  }

  private void appendContains(StringBuilder sql, List<Object> args, String column, String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return;
    }
    sql.append(" and lower(coalesce(").append(column).append(", '')) like ?");
    args.add("%" + normalized.toLowerCase(Locale.ROOT) + "%");
  }

  private void appendEqText(StringBuilder sql, List<Object> args, String column, String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return;
    }
    sql.append(" and ").append(column).append(" = ?");
    args.add(normalized);
  }

  private void appendProblemStatusFilter(
      StringBuilder sql, List<Object> args, String problemStatus) {
    String normalized = TextQuerySupport.trimToNull(problemStatus);
    if (normalized == null) {
      return;
    }
    sql.append(
        """
         and exists (
          select 1
          from review_problem_items problem_filter
          where problem_filter.review_record_id = r.id
            and problem_filter.deleted = false
            and problem_filter.problem_status = ?
        )
        """);
    args.add(normalized);
  }

  private void appendReviewExpertFilter(
      StringBuilder sql, List<Object> args, String reviewExpert) {
    String normalized = TextQuerySupport.trimToNull(reviewExpert);
    if (normalized == null) {
      return;
    }
    sql.append(
        """
         and exists (
          select 1
          from review_record_experts expert_filter
          where expert_filter.review_record_id = r.id
            and expert_filter.deleted = false
            and expert_filter.expert_name = ?
        )
        """);
    args.add(normalized);
  }

  private void appendKeywordSearch(StringBuilder sql, List<Object> args, String keyword) {
    List<String> candidates = ReviewDataSearchIndexSupport.keywordCandidates(keyword);
    if (candidates.isEmpty()) {
      return;
    }
    List<String> predicates = new ArrayList<>();
    for (String ignored : candidates) {
      predicates.add("r.search_text like ?");
      predicates.add("r.search_compact like ?");
      predicates.add("r.search_spell like ?");
      predicates.add("r.search_initials like ?");
    }
    sql.append(" and (").append(String.join(" or ", predicates)).append(")");
    for (String candidate : candidates) {
      String pattern = "%" + candidate + "%";
      args.add(pattern);
      args.add(pattern);
      args.add(pattern);
      args.add(pattern);
    }
  }

  private void appendFilterGroup(
      StringBuilder sql, List<Object> args, StatisticFilterGroup filterGroup) {
    ReviewDataFilterGroupSqlSupport.toSql(filterGroup)
        .filter(filter -> TextQuerySupport.trimToNull(filter.predicate()) != null)
        .ifPresent(
            filter -> {
              sql.append(" and (").append(filter.predicate()).append(")");
              args.addAll(filter.args());
            });
  }

  record SqlParts(String sql, List<Object> args) {}
}
