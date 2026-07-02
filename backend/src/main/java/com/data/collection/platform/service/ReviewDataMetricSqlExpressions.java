package com.data.collection.platform.service;

final class ReviewDataMetricSqlExpressions {
  private ReviewDataMetricSqlExpressions() {}

  static final String PROBLEM_DENSITY =
      "case when coalesce(r.review_scale_pages, 0) <= 0 then 0 "
          + "else floor((coalesce(problem.problem_count, 0)::numeric / r.review_scale_pages) * 100) / 100 end";

  static final String REVIEW_EFFICIENCY =
      "case when coalesce(problem.total_workload_hours, 0) <= 0 then 0 "
          + "else floor((coalesce(problem.problem_count, 0)::numeric / problem.total_workload_hours) * 100) / 100 end";

  static final String REVIEW_RATE =
      "case when coalesce(problem.total_workload_hours, 0) <= 0 then 0 "
          + "else floor((coalesce(r.review_scale_pages, 0)::numeric / problem.total_workload_hours) * 100) / 100 end";
}
