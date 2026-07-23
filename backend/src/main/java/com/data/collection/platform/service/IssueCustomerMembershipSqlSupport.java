package com.data.collection.platform.service;

import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** 客户成员关系在 issue_fact 页面查询中的 SQL 谓词。 */
final class IssueCustomerMembershipSqlSupport {
  private IssueCustomerMembershipSqlSupport() {}

  static void appendSelection(StringBuilder where, List<Object> args, String customerName) {
    String normalized = TextQuerySupport.trimToNull(customerName);
    if (normalized == null) {
      return;
    }
    where.append(" and ").append(memberExists("lower(customer_member.customer_name) = ?"));
    args.add(normalized.toLowerCase(Locale.ROOT));
  }

  static Optional<SqlPredicate> condition(StatisticFilterCondition condition) {
    if (condition == null || condition.usesLabelGroup()) {
      return Optional.empty();
    }
    String normalized = TextQuerySupport.trimToNull(condition.value());
    return switch (condition.operator()) {
      case "eq" ->
          normalized == null
              ? Optional.empty()
              : Optional.of(
                  new SqlPredicate(
                      memberExists("lower(customer_member.customer_name) = ?"),
                      List.of(normalized.toLowerCase(Locale.ROOT))));
      case "ne" ->
          normalized == null
              ? Optional.empty()
              : Optional.of(
                  new SqlPredicate(
                      "not " + memberExists("lower(customer_member.customer_name) = ?"),
                      List.of(normalized.toLowerCase(Locale.ROOT))));
      case "contains" ->
          normalized == null
              ? Optional.empty()
              : Optional.of(
                  new SqlPredicate(
                      memberExists("lower(customer_member.customer_name) like ?"),
                      List.of("%" + normalized.toLowerCase(Locale.ROOT) + "%")));
      case "notContains" ->
          normalized == null
              ? Optional.empty()
              : Optional.of(
                  new SqlPredicate(
                      "not " + memberExists("lower(customer_member.customer_name) like ?"),
                      List.of("%" + normalized.toLowerCase(Locale.ROOT) + "%")));
      case "isEmpty" -> Optional.of(new SqlPredicate("not " + memberExists("1 = 1"), List.of()));
      case "isNotEmpty" -> Optional.of(new SqlPredicate(memberExists("1 = 1"), List.of()));
      default -> Optional.empty();
    };
  }

  private static String memberExists(String memberPredicate) {
    return "exists (select 1 from issue_fact_customer_members customer_member"
        + " where customer_member.source_system = issue_fact.source_system"
        + " and customer_member.source_instance = issue_fact.source_instance"
        + " and customer_member.project_id = issue_fact.project_id"
        + " and customer_member.issue_id = issue_fact.issue_id"
        + " and "
        + memberPredicate
        + ")";
  }
}
