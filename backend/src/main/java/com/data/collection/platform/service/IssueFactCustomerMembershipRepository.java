package com.data.collection.platform.service;

import com.data.collection.platform.entity.IssueFact;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/** issue_fact 的客户成员关系持久化入口。 */
@Repository
class IssueFactCustomerMembershipRepository {
  private final JdbcTemplate jdbcTemplate;

  IssueFactCustomerMembershipRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  void replaceForFacts(List<IssueFact> facts) {
    List<IssueFact> validFacts =
        facts == null
            ? List.of()
            : facts.stream().filter(this::hasNaturalKey).toList();
    if (validFacts.isEmpty()) {
      return;
    }

    jdbcTemplate.batchUpdate(
        """
        delete from issue_fact_customer_members
         where source_system = ?
           and source_instance = ?
           and project_id = ?
           and issue_id = ?
        """,
        validFacts,
        validFacts.size(),
        (statement, fact) -> {
          statement.setString(1, fact.getSourceSystem());
          statement.setString(2, fact.getSourceInstance());
          statement.setLong(3, fact.getProjectId());
          statement.setLong(4, fact.getIssueId());
        });

    List<CustomerMember> members = new ArrayList<>();
    for (IssueFact fact : validFacts) {
      Set<String> customerNames = new LinkedHashSet<>(IssueFactValueSupport.split(fact.getCustomerNames()));
      for (String customerName : customerNames) {
        members.add(new CustomerMember(fact, customerName));
      }
    }
    if (members.isEmpty()) {
      return;
    }
    jdbcTemplate.batchUpdate(
        """
        insert into issue_fact_customer_members(
          source_system, source_instance, project_id, issue_id, customer_name, created_at, updated_at
        ) values (?, ?, ?, ?, ?, current_timestamp, current_timestamp)
        on conflict (source_system, source_instance, project_id, issue_id, customer_name)
        do update set updated_at = current_timestamp
        """,
        members,
        members.size(),
        (statement, member) -> {
          IssueFact fact = member.fact();
          statement.setString(1, fact.getSourceSystem());
          statement.setString(2, fact.getSourceInstance());
          statement.setLong(3, fact.getProjectId());
          statement.setLong(4, fact.getIssueId());
          statement.setString(5, member.customerName());
        });
  }

  private boolean hasNaturalKey(IssueFact fact) {
    return fact != null
        && StringUtils.hasText(fact.getSourceSystem())
        && StringUtils.hasText(fact.getSourceInstance())
        && fact.getProjectId() != null
        && fact.getIssueId() != null;
  }

  private record CustomerMember(IssueFact fact, String customerName) {}
}
