package com.data.collection.platform.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** SQL 侧客户问题动态滞留时长范围谓词。 */
final class IssueRetentionDurationSqlSupport {
  private IssueRetentionDurationSqlSupport() {}

  static SqlPredicate range(Long minimum, Long maximum, LocalDateTime asOf) {
    if (minimum == null && maximum == null) {
      return new SqlPredicate("", List.of());
    }
    if (asOf == null) {
      throw new IllegalArgumentException("滞留时长筛选缺少请求计算时点");
    }
    SqlPredicate closureStatuses =
        IssueStatusMemberSqlSupport.matchesAny(CustomerIssueClosureRules.closureStatuses());
    List<String> predicates = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    if (minimum != null) {
      appendBound(predicates, args, closureStatuses, asOf, ">=", minimum);
    }
    if (maximum != null) {
      appendBound(predicates, args, closureStatuses, asOf, "<=", maximum);
    }
    return new SqlPredicate("(" + String.join(" and ", predicates) + ")", args);
  }

  private static void appendBound(
      List<String> predicates,
      List<Object> args,
      SqlPredicate closureStatuses,
      LocalDateTime asOf,
      String operator,
      Long boundary) {
    predicates.add(retentionHoursExpression(closureStatuses.predicate()) + " " + operator + " ?");
    args.addAll(closureStatuses.args());
    args.add(asOf);
    args.add(boundary);
  }

  private static String retentionHoursExpression(String closureStatusPredicate) {
    return """
        (case
           when closed_at_source is not null
             or lower(btrim(coalesce(issue_state, ''))) = 'closed'
             or (%s)
             then 0::bigint
           when created_at_source is null then null
           else greatest(
             0,
             floor(extract(epoch from (cast(? as timestamp) - created_at_source)) / 3600)
           )::bigint
         end)
        """.formatted(closureStatusPredicate);
  }
}
