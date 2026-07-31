package com.data.collection.platform.service;

import com.data.collection.platform.entity.IssueFact;
import com.data.collection.platform.mapper.IssueFactMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 以同一事务提交议题事实及其客户成员关系。 */
@Service
public class IssueFactPersistenceService {
  private static final int BATCH_SIZE = 200;

  private final IssueFactMapper issueFactMapper;
  private final IssueFactCustomerMembershipRepository customerMembershipRepository;
  private final JdbcTemplate jdbcTemplate;

  public IssueFactPersistenceService(
      IssueFactMapper issueFactMapper,
      IssueFactCustomerMembershipRepository customerMembershipRepository,
      JdbcTemplate jdbcTemplate) {
    this.issueFactMapper = issueFactMapper;
    this.customerMembershipRepository = customerMembershipRepository;
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 批量写入事实和客户成员。成员写入与事实更新必须同时成功，避免展示字段与客户筛选范围不一致。
   *
   * @param facts 同一事实构建批次的议题事实
   */
  @Transactional
  public void upsertIssueFacts(List<IssueFact> facts) {
    if (facts == null || facts.isEmpty()) {
      return;
    }
    for (int offset = 0; offset < facts.size(); offset += BATCH_SIZE) {
      List<IssueFact> batch = facts.subList(offset, Math.min(offset + BATCH_SIZE, facts.size()));
      issueFactMapper.batchUpsert(batch);
      customerMembershipRepository.replaceForFacts(batch);
    }
  }

  /**
   * 用当前来源结果替换指定议题目标的事实投影。
   *
   * <p>删除客户成员与旧事实后再写入仍存在的事实；空 {@code facts} 表示来源实体已删除，必须保留删除结果。
   *
   * @param sourceSystem 事实来源系统
   * @param sourceInstance 事实来源实例
   * @param targets 以项目 ID 和 Issue IID 标识的非空目标集合
   * @param facts 目标范围当前仍存在的事实
   */
  @Transactional
  public void replaceTargetFacts(
      String sourceSystem,
      String sourceInstance,
      List<FactRefreshImpactScopeService.Target> targets,
      List<IssueFact> facts) {
    List<FactRefreshImpactScopeService.Target> safeTargets = sanitizeTargets(targets);
    if (safeTargets.isEmpty()) {
      throw new IllegalArgumentException("议题事实目标替换必须指定非空目标范围");
    }
    List<Object> args = targetArgs(sourceSystem, sourceInstance, safeTargets);
    String predicate = targetPredicate(safeTargets, "fact.project_id", "fact.issue_iid");
    jdbcTemplate.update(
        """
        delete from issue_fact_customer_members member
        using issue_fact fact
         where member.source_system = fact.source_system
           and member.source_instance = fact.source_instance
           and member.project_id = fact.project_id
           and member.issue_id = fact.issue_id
           and fact.source_system = ?
           and fact.source_instance = ?
        """ + predicate,
        args.toArray());
    jdbcTemplate.update(
        "delete from issue_fact fact where fact.source_system = ? and fact.source_instance = ?"
            + predicate,
        args.toArray());
    upsertIssueFacts(facts);
  }

  /**
   * 用完整来源快照替换指定实例的全部议题事实及客户成员。
   *
   * @param sourceSystem 事实来源系统
   * @param sourceInstance 事实来源实例
   * @param facts 完整来源快照；空集合表示清空该实例事实
   */
  @Transactional
  public void replaceAllFacts(
      String sourceSystem, String sourceInstance, List<IssueFact> facts) {
    jdbcTemplate.update(
        "delete from issue_fact_customer_members where source_system = ? and source_instance = ?",
        sourceSystem,
        sourceInstance);
    jdbcTemplate.update(
        "delete from issue_fact where source_system = ? and source_instance = ?",
        sourceSystem,
        sourceInstance);
    upsertIssueFacts(facts);
  }

  private List<FactRefreshImpactScopeService.Target> sanitizeTargets(
      List<FactRefreshImpactScopeService.Target> targets) {
    if (targets == null || targets.isEmpty()) {
      return List.of();
    }
    return targets.stream()
        .filter(target -> target != null && target.projectId() != null && target.iid() != null)
        .distinct()
        .toList();
  }

  private List<Object> targetArgs(
      String sourceSystem,
      String sourceInstance,
      List<FactRefreshImpactScopeService.Target> targets) {
    List<Object> args = new ArrayList<>(2 + targets.size() * 2);
    args.add(sourceSystem);
    args.add(sourceInstance);
    for (FactRefreshImpactScopeService.Target target : targets) {
      args.add(target.projectId());
      args.add(target.iid());
    }
    return args;
  }

  private String targetPredicate(
      List<FactRefreshImpactScopeService.Target> targets,
      String projectColumn,
      String iidColumn) {
    return targets.stream()
        .map(ignored -> "(" + projectColumn + " = ? and " + iidColumn + " = ?)")
        .collect(java.util.stream.Collectors.joining(" or ", " and (", ")"));
  }
}
