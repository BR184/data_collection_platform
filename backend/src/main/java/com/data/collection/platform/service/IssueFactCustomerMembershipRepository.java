package com.data.collection.platform.service;

import com.data.collection.platform.entity.IssueFact;
import com.data.collection.platform.entity.read.FactRevisionTarget;
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

  /**
   * 用给定事实重建其客户成员关系，关系绑定到候选修订的物理标识。
   *
   * <p>只作用于本候选在 {@code target} 内写入的修订：先删这些修订上的旧成员，再按当前
   * {@code customerNames} 重新插入。已发布修订的关系不被触及，因此旧版本仍能按其父修订读到原成员。
   *
   * @param target 候选修订写入目标
   * @param facts 同一批次的议题事实（必须已在本候选内写入修订）
   */
  void replaceForFacts(FactRevisionTarget target, List<IssueFact> facts) {
    List<IssueFact> validFacts =
        facts == null ? List.of() : facts.stream().filter(this::hasNaturalKey).toList();
    if (validFacts.isEmpty()) {
      return;
    }

    jdbcTemplate.batchUpdate(
        """
        delete from issue_fact_customer_members member
         using issue_fact fact
         where fact.created_by_publication_id = ?
           and fact.source_system = ?
           and fact.source_instance = ?
           and fact.project_id = ?
           and fact.issue_id = ?
           and member.issue_fact_revision_id = fact.id
        """,
        validFacts,
        validFacts.size(),
        (statement, fact) -> {
          statement.setObject(1, target.publicationId());
          statement.setString(2, fact.getSourceSystem());
          statement.setString(3, fact.getSourceInstance());
          statement.setLong(4, fact.getProjectId());
          statement.setLong(5, fact.getIssueId());
        });

    List<CustomerMember> members = new ArrayList<>();
    for (IssueFact fact : validFacts) {
      Set<String> customerNames =
          new LinkedHashSet<>(IssueFactValueSupport.split(fact.getCustomerNames()));
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
          source_system, source_instance, project_id, issue_id, customer_name,
          issue_fact_revision_id, created_at, updated_at
        )
        select ?, ?, ?, ?, ?, fact.id, current_timestamp, current_timestamp
          from issue_fact fact
         where fact.created_by_publication_id = ?
           and fact.source_system = ?
           and fact.source_instance = ?
           and fact.project_id = ?
           and fact.issue_id = ?
        on conflict (issue_fact_revision_id, customer_name)
          where issue_fact_revision_id is not null
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
          statement.setObject(6, target.publicationId());
          statement.setString(7, fact.getSourceSystem());
          statement.setString(8, fact.getSourceInstance());
          statement.setLong(9, fact.getProjectId());
          statement.setLong(10, fact.getIssueId());
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
