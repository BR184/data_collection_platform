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
  /** 单个清理批次处理的父/关系身份数量；分批只降低单条 DELETE 的候选量。 */
  private static final int CLEANUP_BATCH_SIZE = 2000;

  private final IssueFactMapper issueFactMapper;
  private final IssueFactCustomerMembershipRepository customerMembershipRepository;
  private final JdbcTemplate jdbcTemplate;
  private final FactTaskExecutionGuard executionGuard;

  public IssueFactPersistenceService(
      IssueFactMapper issueFactMapper,
      IssueFactCustomerMembershipRepository customerMembershipRepository,
      JdbcTemplate jdbcTemplate,
      FactTaskExecutionGuard executionGuard) {
    this.issueFactMapper = issueFactMapper;
    this.customerMembershipRepository = customerMembershipRepository;
    this.jdbcTemplate = jdbcTemplate;
    this.executionGuard = executionGuard;
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
   * 快照外的行（上游已删除的议题）在此清除，最终状态与全量替换一致。
   *
   * <p>清理在单个事务内分批执行：先把快照身份与待删除身份各自物化到会话级临时表，再按固定批次
   * 先删客户成员、后删父事实。空快照同样走这条有界路径，不保留直接删除全来源的大语句。分批只降低
   * 单条 DELETE 的候选量，不缩小整个清理事务的持锁与回滚范围，也不减少索引维护总量。每一批开始前
   * 校验当前事实任务仍持有执行权，取消后不再开始新的清理批次。
   *
   * <p>身份列在协议上非空，因此匹配一律使用普通等值；出现空身份行说明数据损坏，直接失败而不是
   * 静默跳过或保留一条并发删除路径。
   *
   * @param sourceSystem 事实来源系统
   * @param sourceInstance 事实来源实例
   * @param snapshotFacts 完整来源快照；空集合表示清空该实例全部事实
   */
  @Transactional
  public void deleteFactsNotInSnapshot(
      String sourceSystem, String sourceInstance, List<IssueFact> snapshotFacts) {
    List<IssueFact> safeSnapshot = snapshotFacts == null ? List.of() : snapshotFacts;
    requireNoNullIdentities(sourceSystem, sourceInstance);
    jdbcTemplate.execute(
        "create temp table issue_fact_snapshot_ids (project_id bigint, issue_id bigint) on commit drop");
    if (!safeSnapshot.isEmpty()) {
      List<Object[]> identities = new ArrayList<>(safeSnapshot.size());
      for (IssueFact fact : safeSnapshot) {
        if (fact.getProjectId() == null || fact.getIssueId() == null) {
          throw new IllegalArgumentException("议题事实快照身份不得为空：projectId/issueId");
        }
        identities.add(new Object[] {fact.getProjectId(), fact.getIssueId()});
      }
      jdbcTemplate.batchUpdate(
          "insert into issue_fact_snapshot_ids(project_id, issue_id) values (?, ?)", identities);
    }
    jdbcTemplate.update(
        """
        create temp table issue_fact_delete_ids on commit drop as
        select row_number() over () as batch_order, candidate.project_id, candidate.issue_id
          from (
            select distinct fact.project_id, fact.issue_id
              from issue_fact fact
              left join issue_fact_snapshot_ids snapshot
                on snapshot.project_id = fact.project_id
               and snapshot.issue_id = fact.issue_id
             where fact.source_system = ?
               and fact.source_instance = ?
               and snapshot.project_id is null
          ) candidate
        """,
        sourceSystem,
        sourceInstance);
    Long candidateCount =
        jdbcTemplate.queryForObject("select count(*) from issue_fact_delete_ids", Long.class);
    long candidates = candidateCount == null ? 0L : candidateCount;
    jdbcTemplate.execute(
        "create temp table issue_fact_cleanup_batch (project_id bigint, issue_id bigint) on commit drop");
    for (long offset = 0L; offset < candidates; offset += CLEANUP_BATCH_SIZE) {
      executionGuard.requireCurrentTaskAuthorization();
      jdbcTemplate.execute("truncate table issue_fact_cleanup_batch");
      jdbcTemplate.update(
          """
          insert into issue_fact_cleanup_batch(project_id, issue_id)
          select project_id, issue_id
            from issue_fact_delete_ids
           where batch_order > ? and batch_order <= ?
          """,
          offset,
          offset + CLEANUP_BATCH_SIZE);
      jdbcTemplate.update(
          """
          delete from issue_fact_customer_members member
           using issue_fact_cleanup_batch batch
           where member.source_system = ?
             and member.source_instance = ?
             and member.project_id = batch.project_id
             and member.issue_id = batch.issue_id
          """,
          sourceSystem,
          sourceInstance);
      jdbcTemplate.update(
          """
          delete from issue_fact fact
           using issue_fact_cleanup_batch batch
           where fact.source_system = ?
             and fact.source_instance = ?
             and fact.project_id = batch.project_id
             and fact.issue_id = batch.issue_id
          """,
          sourceSystem,
          sourceInstance);
    }
  }

  private void requireNoNullIdentities(String sourceSystem, String sourceInstance) {
    Long nullIdentities =
        jdbcTemplate.queryForObject(
            """
            select (select count(*) from issue_fact
                     where source_system = ? and source_instance = ?
                       and (project_id is null or issue_id is null))
                 + (select count(*) from issue_fact_customer_members
                     where source_system = ? and source_instance = ?
                       and (project_id is null or issue_id is null))
            """,
            Long.class,
            sourceSystem,
            sourceInstance,
            sourceSystem,
            sourceInstance);
    if (nullIdentities != null && nullIdentities > 0L) {
      throw new IllegalStateException(
          "议题事实存在空身份行，无法按非空身份列清理：count=" + nullIdentities);
    }
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
