package com.data.collection.platform.service;

import com.data.collection.platform.entity.MergeRequestFact;
import com.data.collection.platform.entity.MergeRequestCommitFact;
import com.data.collection.platform.mapper.MergeRequestFactMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 以同一事务提交合并请求目标范围的事实替换。 */
@Service
public class MergeRequestFactPersistenceService {
  private static final int BATCH_SIZE = 200;
  private static final String COMMIT_UPSERT_SQL = """
      insert into merge_request_commit_fact(
        source_system, source_instance, project_id,
        merge_request_id, merge_request_iid, commit_sha, committed_at_source,
        fact_refreshed_at, created_at, updated_at
      ) values (?, ?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp, current_timestamp)
      on conflict (source_system, source_instance, project_id, merge_request_id, commit_sha)
      do update set
        merge_request_iid = excluded.merge_request_iid,
        committed_at_source = excluded.committed_at_source,
        fact_refreshed_at = current_timestamp,
        updated_at = current_timestamp
      """;

  private final MergeRequestFactMapper factMapper;
  private final JdbcTemplate jdbcTemplate;

  public MergeRequestFactPersistenceService(
      MergeRequestFactMapper factMapper, JdbcTemplate jdbcTemplate) {
    this.factMapper = factMapper;
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 批量 upsert 当前仍存在的合并请求事实。
   *
   * @param facts 同一事实构建批次的合并请求事实
   */
  @Transactional
  public void upsertFacts(List<MergeRequestFact> facts) {
    if (facts == null || facts.isEmpty()) {
      return;
    }
    for (int offset = 0; offset < facts.size(); offset += BATCH_SIZE) {
      factMapper.batchUpsert(
          facts.subList(offset, Math.min(offset + BATCH_SIZE, facts.size())));
    }
  }

  /**
   * 用当前来源结果替换指定合并请求目标的事实投影。
   *
   * @param sourceSystem 事实来源系统
   * @param sourceInstance 事实来源实例
   * @param rootIds GitLab MR 数据库根 ID 的非空集合
   * @param facts 目标范围当前仍存在的 MR 事实；空集合表示删除旧事实
   * @param commitFacts 目标范围最新 Diff 的提交关系事实
   */
  @Transactional
  public void replaceRootFacts(
      String sourceSystem,
      String sourceInstance,
      List<Long> rootIds,
      List<MergeRequestFact> facts,
      List<MergeRequestCommitFact> commitFacts) {
    List<Long> safeRootIds = sanitizeRootIds(rootIds);
    if (safeRootIds.isEmpty()) {
      throw new IllegalArgumentException("合并请求事实目标替换必须指定非空目标范围");
    }
    List<Object> args = new ArrayList<>(2 + safeRootIds.size());
    args.add(sourceSystem);
    args.add(sourceInstance);
    args.addAll(safeRootIds);
    String predicate = " and merge_request_id in ("
        + String.join(", ", java.util.Collections.nCopies(safeRootIds.size(), "?")) + ")";
    jdbcTemplate.update(
        "delete from merge_request_fact where source_system = ? and source_instance = ?"
            + predicate,
        args.toArray());
    jdbcTemplate.update(
        "delete from merge_request_commit_fact where source_system = ? and source_instance = ?"
            + predicate,
        args.toArray());
    upsertFacts(facts);
    upsertCommitFacts(commitFacts);
  }

  /**
   * 批量 upsert 合并请求提交关系事实。
   *
   * @param facts 同一批次的提交关系事实
   */
  @Transactional
  public void upsertCommitFacts(List<MergeRequestCommitFact> facts) {
    if (facts == null || facts.isEmpty()) {
      return;
    }
    jdbcTemplate.batchUpdate(
        COMMIT_UPSERT_SQL,
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
        });
  }

  /**
   * 删除完整来源快照之外的同源合并请求事实、提交关系事实。
   *
   * <p>与分批 upsert 配合替代旧的「删全部再重插」全量替换：快照内的行经 upsert 保留行身份，
   * 快照外的行（上游已删除的 MR 或已从 Diff 消失的提交）在此清除，最终状态与全量替换一致。
   *
   * @param sourceSystem 事实来源系统
   * @param sourceInstance 事实来源实例
   * @param snapshotFacts 完整来源 MR 事实快照；空集合表示清空该实例全部 MR 事实
   * @param snapshotCommits 完整来源提交关系快照
   */
  @Transactional
  public void deleteFactsNotInSnapshot(
      String sourceSystem,
      String sourceInstance,
      List<MergeRequestFact> snapshotFacts,
      List<MergeRequestCommitFact> snapshotCommits) {
    if (snapshotFacts == null || snapshotFacts.isEmpty()) {
      jdbcTemplate.update(
          "delete from merge_request_commit_fact where source_system = ? and source_instance = ?",
          sourceSystem,
          sourceInstance);
      jdbcTemplate.update(
          "delete from merge_request_fact where source_system = ? and source_instance = ?",
          sourceSystem,
          sourceInstance);
      return;
    }
    jdbcTemplate.execute(
        "create temp table merge_request_snapshot_ids (project_id bigint, merge_request_id bigint) on commit drop");
    jdbcTemplate.execute(
        "create temp table merge_request_snapshot_commit_ids (project_id bigint, merge_request_id bigint, commit_sha varchar) on commit drop");
    List<Object[]> mrIdentities = new ArrayList<>(snapshotFacts.size());
    for (MergeRequestFact fact : snapshotFacts) {
      mrIdentities.add(new Object[] {fact.getProjectId(), fact.getMergeRequestId()});
    }
    jdbcTemplate.batchUpdate(
        "insert into merge_request_snapshot_ids(project_id, merge_request_id) values (?, ?)",
        mrIdentities);
    List<Object[]> commitIdentities =
        new ArrayList<>(snapshotCommits == null ? 0 : snapshotCommits.size());
    if (snapshotCommits != null) {
      for (MergeRequestCommitFact commit : snapshotCommits) {
        commitIdentities.add(
            new Object[] {commit.projectId(), commit.mergeRequestId(), commit.commitSha()});
      }
      jdbcTemplate.batchUpdate(
          "insert into merge_request_snapshot_commit_ids(project_id, merge_request_id, commit_sha) values (?, ?, ?)",
          commitIdentities);
    }
    jdbcTemplate.update(
        """
        delete from merge_request_commit_fact commit_fact
         where commit_fact.source_system = ?
           and commit_fact.source_instance = ?
           and not exists (
             select 1
               from merge_request_snapshot_commit_ids snapshot
              where snapshot.project_id is not distinct from commit_fact.project_id
                and snapshot.merge_request_id is not distinct from commit_fact.merge_request_id
                and snapshot.commit_sha is not distinct from commit_fact.commit_sha
           )
        """,
        sourceSystem,
        sourceInstance);
    jdbcTemplate.update(
        """
        delete from merge_request_fact fact
         where fact.source_system = ?
           and fact.source_instance = ?
           and not exists (
             select 1
               from merge_request_snapshot_ids snapshot
              where snapshot.project_id is not distinct from fact.project_id
                and snapshot.merge_request_id is not distinct from fact.merge_request_id
           )
        """,
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
}
