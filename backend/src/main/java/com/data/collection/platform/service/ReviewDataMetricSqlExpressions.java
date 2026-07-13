package com.data.collection.platform.service;

final class ReviewDataMetricSqlExpressions {
  private ReviewDataMetricSqlExpressions() {}

  static final String PROBLEM_DENSITY =
      "case when coalesce(r.review_scale_pages, 0) <= 0 then 0 "
          + "else round(coalesce(problem.problem_count, 0)::numeric / r.review_scale_pages, 2) end";

  static final String REVIEW_EFFICIENCY =
      "case when coalesce(problem.total_workload_hours, 0) <= 0 then 0 "
          + "else round(coalesce(problem.problem_count, 0)::numeric / problem.total_workload_hours, 2) end";

  static final String REVIEW_RATE =
      "case when coalesce(problem.total_workload_hours, 0) <= 0 then 0 "
          + "else round(coalesce(r.review_scale_pages, 0)::numeric / problem.total_workload_hours, 2) end";

  static final String WEIGHTED_DEFECT_DENSITY =
      "case when coalesce(problem.problem_count, 0) <= 0 "
          + "or coalesce(r.review_scale_pages, 0) <= 0 "
          + "or (coalesce(r.review_scale_pages, 0) / nullif(coalesce(problem.problem_count, 0), 0)) <= 0 "
          + "then 0 else round(("
          + "coalesce(problem.doc_specification_count, 0)::numeric "
          + "+ coalesce(problem.integrity_count, 0)::numeric * 1.5 "
          + "+ coalesce(problem.functionality_count, 0)::numeric * 2 "
          + "+ coalesce(problem.feasibility_count, 0)::numeric * 2"
          + ") / (coalesce(r.review_scale_pages, 0) / nullif(coalesce(problem.problem_count, 0), 0)), 2) end";
}
