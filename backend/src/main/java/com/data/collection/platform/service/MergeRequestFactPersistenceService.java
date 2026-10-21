package com.data.collection.platform.service;

import com.data.collection.platform.entity.MergeRequestCommitFact;
import com.data.collection.platform.entity.MergeRequestFact;
import com.data.collection.platform.entity.read.FactRevisionTarget;
import com.data.collection.platform.mapper.MergeRequestFactMapper;
import com.data.collection.platform.service.read.ReadPublicationRepository;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 以同一事务在候选修订身份内写入合并请求事实与其提交关系。
 *
 * <p>P2 起事实不再原地 upsert：每次构建写入候选代际内的新修订，提交关系绑定到该 MR 修订；来源已消失的
 * 根只登记删除证明。已发布修订不被改写，旧完整版本保持可读。
 */
@Service
public class MergeRequestFactPersistenceService {
  private static final int BATCH_SIZE = 200;

  private final MergeRequestFactMapper factMapper;
  private final ReadPublicationRepository publicationRepository;
  private final JdbcTemplate jdbcTemplate;

  public MergeRequestFactPersistenceService(
      MergeRequestFactMapper factMapper,
      ReadPublicationRepository publicationRepository,
      JdbcTemplate jdbcTemplate) {
    this.factMapper = factMapper;
    this.publicationRepository = publicationRepository;
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 在候选内批量写入合并请求事实修订；同一候选重试幂等覆盖同一修订行。
   *
   * @param target 候选修订写入目标
   * @param facts 同一事实构建批次的合并请求事实
   */
  @Transactional
  public void upsertFacts(FactRevisionTarget target, List<MergeRequestFact> facts) {
    if (facts == null || facts.isEmpty()) {
      return;
    }
    publicationRepository.requireWritableTarget(target);
    for (int offset = 0; offset < facts.size(); offset += BATCH_SIZE) {
      factMapper.batchUpsert(
          facts.subList(offset, Math.min(offset + BATCH_SIZE, facts.size())),
          target.epochId(),
          target.publicationId());
    }
  }

  /**
   * 用当前来源结果替换指定合并请求目标：写入仍存在根的候选修订与提交关系，来源已消失的根登记删除证明。
   *
   * @param target 候选修订写入目标
   * @param rootIds GitLab MR 数据库根 ID 的非空集合
   * @param facts 目标范围当前仍存在的 MR 事实；空集合表示来源已删除
   * @param commitFacts 目标范围最新 Diff 的提交关系事实
   */
  @Transactional
  public void replaceRootFacts(
      FactRevisionTarget target,
      List<Long> rootIds,
      List<MergeRequestFact> facts,
      List<MergeRequestCommitFact> commitFacts) {
    List<Long> safeRootIds = sanitizeRootIds(rootIds);
    if (safeRootIds.isEmpty()) {
      throw new IllegalArgumentException("合并请求事实目标替换必须指定非空目标范围");
    }
    List<MergeRequestFact> safeFacts = facts == null ? List.of() : facts;
    publicationRepository.requireWritableTarget(target);
    upsertFacts(target, safeFacts);
    deleteCandidateCommitRelations(target, safeRootIds);
    upsertCommitFacts(target, commitFacts == null ? List.of() : commitFacts);
    // 删除判定交给仓储按完整业务身份比较：本候选未写出对应身份的考虑过的根，才构成删除或跨项目移出。
    publicationRepository.recordDeletionsForRoots(
        target.table(),
        target.publicationId(),
        target.sourceSystem(),
        target.sourceInstance(),
        safeRootIds,
        target.coveredChangeVersion());
  }

  /**
   * 在候选内写入合并请求提交关系，关系绑定到本候选的 MR 修订。
   *
   * <p>同一候选重试幂等覆盖同一提交行；换候选时形成新的 MR 修订，因此旧修订的提交关系不被波及。
   *
   * @param target 候选修订写入目标
   * @param facts 同一批次的提交关系事实
   */
  @Transactional
  public void upsertCommitFacts(FactRevisionTarget target, List<MergeRequestCommitFact> facts) {
    if (facts == null || facts.isEmpty()) {
      return;
    }
    publicationRepository.requireWritableTarget(target);
    jdbcTemplate.batchUpdate(
        """
        insert into merge_request_commit_fact(
          source_system, source_instance, project_id,
          merge_request_id, merge_request_iid, commit_sha, committed_at_source,
          merge_request_revision_id, fact_refreshed_at, created_at, updated_at
        )
        select ?, ?, ?, ?, ?, ?, ?, fact.id, current_timestamp, current_timestamp, current_timestamp
          from merge_request_fact fact
         where fact.created_by_publication_id = ?
           and fact.source_system = ?
           and fact.source_instance = ?
           and fact.project_id = ?
           and fact.merge_request_id = ?
        on conflict (merge_request_revision_id, commit_sha)
          where merge_request_revision_id is not null
        do update set
          merge_request_iid = excluded.merge_request_iid,
          committed_at_source = excluded.committed_at_source,
          fact_refreshed_at = current_timestamp,
          updated_at = current_timestamp
        """,
        facts,
        BATCH_SIZE,
        (statement, fact) -> {
          statement.setString(1, fact.sourceSystem());
          statement.setString(2, fact.sourceInstance());
          statement.setLong(3, fact.projectId());
          statement.setLong(4, fact.mergeRequestId());
          if (fact.mergeRequestIid() == null) {
            statement.setNull(5, java.sql.Types.BIGINT);
          } else {
            statement.setLong(5, fact.mergeRequestIid());
          }
          statement.setString(6, fact.commitSha());
          statement.setTimestamp(7, java.sql.Timestamp.valueOf(fact.committedAtSource()));
          statement.setObject(8, target.publicationId());
          statement.setString(9, fact.sourceSystem());
          statement.setString(10, fact.sourceInstance());
          statement.setLong(11, fact.projectId());
          statement.setLong(12, fact.mergeRequestId());
        });
  }

  /** 删除本候选在这些 MR 根上已写入修订的提交关系（替换前清理，仅限本候选修订）。 */
  private void deleteCandidateCommitRelations(FactRevisionTarget target, List<Long> safeRootIds) {
    jdbcTemplate.update(
        """
        delete from merge_request_commit_fact commit_fact
         using merge_request_fact fact
         where fact.created_by_publication_id = ?
           and fact.source_system = ?
           and fact.source_instance = ?
           and fact.merge_request_id = any (?::bigint[])
           and commit_fact.merge_request_revision_id = fact.id
        """,
        target.publicationId(),
        target.sourceSystem(),
        target.sourceInstance(),
        longArrayLiteral(safeRootIds));
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

  private static String longArrayLiteral(List<Long> values) {
    return values.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",", "{", "}"));
  }
}
