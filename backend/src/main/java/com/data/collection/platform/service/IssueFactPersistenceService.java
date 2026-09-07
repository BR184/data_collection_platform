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
   * @param rootIds GitLab Issue 数据库根 ID 的非空集合
   * @param facts 目标范围当前仍存在的事实
   */
  @Transactional
  public void replaceRootFacts(
      String sourceSystem,
      String sourceInstance,
      List<Long> rootIds,
      List<IssueFact> facts) {
    List<Long> safeRootIds = sanitizeRootIds(rootIds);
    if (safeRootIds.isEmpty()) {
      throw new IllegalArgumentException("议题事实目标替换必须指定非空目标范围");
    }
    List<Object> args = targetArgs(sourceSystem, sourceInstance, safeRootIds);
    String predicate = rootPredicate(safeRootIds, "fact.issue_id");
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
   * 删除完整来源快照之外的同源事实与客户成员。
   *
   * <p>与分批 upsert 配合替代旧的「删全部再重插」全量替换：快照内的行经 upsert 保留行身份，
   * 快照外的行（上游已删除的议题）在此清除，最终状态与全量替换一致。快照身份先写入会话级临时表
   * 再以单条 NOT EXISTS 反连接删除，保证任意快照规模（含内网数万行）下只清除全集之外的行，
   * 且空值身份按 IS NOT DISTINCT FROM 精确匹配。
   *
   * @param sourceSystem 事实来源系统
   * @param sourceInstance 事实来源实例
   * @param snapshotFacts 完整来源快照；空集合表示清空该实例全部事实
   */
  @Transactional
  public void deleteFactsNotInSnapshot(
      String sourceSystem, String sourceInstance, List<IssueFact> snapshotFacts) {
    if (snapshotFacts == null || snapshotFacts.isEmpty()) {
      jdbcTemplate.update(
          "delete from issue_fact_customer_members where source_system = ? and source_instance = ?",
          sourceSystem,
          sourceInstance);
      jdbcTemplate.update(
          "delete from issue_fact where source_system = ? and source_instance = ?",
          sourceSystem,
          sourceInstance);
      return;
    }
    jdbcTemplate.execute(
        "create temp table issue_fact_snapshot_ids (project_id bigint, issue_id bigint) on commit drop");
    List<Object[]> identities = new ArrayList<>(snapshotFacts.size());
    for (IssueFact fact : snapshotFacts) {
      identities.add(new Object[] {fact.getProjectId(), fact.getIssueId()});
    }
    jdbcTemplate.batchUpdate(
        "insert into issue_fact_snapshot_ids(project_id, issue_id) values (?, ?)", identities);
    String outsideSnapshot =
        """
        and not exists (
          select 1
            from issue_fact_snapshot_ids snapshot
           where snapshot.project_id is not distinct from fact.project_id
             and snapshot.issue_id is not distinct from fact.issue_id
        )
        """;
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
        """
            + outsideSnapshot,
        sourceSystem,
        sourceInstance);
    jdbcTemplate.update(
        """
        delete from issue_fact fact
         where fact.source_system = ?
           and fact.source_instance = ?
        """
            + outsideSnapshot,
        sourceSystem,
        sourceInstance);
  }

  private List<Long> sanitizeRootIds(List<Long> rootIds) {
    if (rootIds == null || rootIds.isEmpty()) {
      return List.of();
    }
    return rootIds.stream()
        .filter(rootId -> rootId != null && rootId > 0L)
        .distinct()
        .sorted()
        .toList();
  }

  private List<Object> targetArgs(
      String sourceSystem,
      String sourceInstance,
      List<Long> rootIds) {
    List<Object> args = new ArrayList<>(2 + rootIds.size());
    args.add(sourceSystem);
    args.add(sourceInstance);
    args.addAll(rootIds);
    return args;
  }

  private String rootPredicate(List<Long> rootIds, String rootColumn) {
    return " and " + rootColumn + " in ("
        + String.join(", ", java.util.Collections.nCopies(rootIds.size(), "?")) + ")";
  }
}
