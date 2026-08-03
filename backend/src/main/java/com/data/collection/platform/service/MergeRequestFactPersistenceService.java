package com.data.collection.platform.service;

import com.data.collection.platform.entity.MergeRequestFact;
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
   * @param facts 目标范围当前仍存在的事实；空集合表示删除旧事实
   */
  @Transactional
  public void replaceRootFacts(
      String sourceSystem,
      String sourceInstance,
      List<Long> rootIds,
      List<MergeRequestFact> facts) {
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
    upsertFacts(facts);
  }

  /**
   * 用完整来源快照替换指定实例的全部合并请求事实。
   *
   * @param sourceSystem 事实来源系统
   * @param sourceInstance 事实来源实例
   * @param facts 完整来源快照；空集合表示清空该实例事实
   */
  @Transactional
  public void replaceAllFacts(
      String sourceSystem, String sourceInstance, List<MergeRequestFact> facts) {
    jdbcTemplate.update(
        "delete from merge_request_fact where source_system = ? and source_instance = ?",
        sourceSystem,
        sourceInstance);
    upsertFacts(facts);
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
