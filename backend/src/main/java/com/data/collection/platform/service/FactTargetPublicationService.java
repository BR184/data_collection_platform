package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.FactProjectionScope;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.ProjectionScopeType;
import com.data.collection.platform.entity.QueuedFactBuildTask;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.data.collection.platform.service.sync.SyncFactPublicationStateService;
import com.data.collection.platform.service.sync.SyncRunEventRecorder;

/** 原子提交一个有界根批次的事实、版本头、outbox 和任务终态。 */
@Service
public class FactTargetPublicationService {
  private final JdbcTemplate jdbcTemplate;
  private final FactProjectionScopeResolver scopeResolver;
  private final FactProjectionGenerationService generationService;
  private final com.data.collection.platform.service.sync.SyncRunPublicationFenceService
      publicationFenceService;
  private final SyncFactPublicationStateService publicationStateService;
  private final FactPublicationTransaction publicationTransaction;
  private final FactBuildTaskService taskService;
  private final SyncRunEventRecorder eventRecorder;
  private final GitlabMirrorProperties mirrorProperties;

  public FactTargetPublicationService(
      JdbcTemplate jdbcTemplate,
      FactProjectionScopeResolver scopeResolver,
      FactProjectionGenerationService generationService,
      com.data.collection.platform.service.sync.SyncRunPublicationFenceService
          publicationFenceService,
      SyncFactPublicationStateService publicationStateService,
      FactPublicationTransaction publicationTransaction,
      FactBuildTaskService taskService,
      SyncRunEventRecorder eventRecorder,
      GitlabMirrorProperties mirrorProperties) {
    this.jdbcTemplate = jdbcTemplate;
    this.scopeResolver = scopeResolver;
    this.generationService = generationService;
    this.publicationFenceService = publicationFenceService;
    this.publicationStateService = publicationStateService;
    this.publicationTransaction = publicationTransaction;
    this.taskService = taskService;
    this.eventRecorder = eventRecorder;
    this.mirrorProperties = mirrorProperties;
  }

  /** 全量事实构建动作；构建内部以分批事务提交，批间通过进度回调续租并上报进度。 */
  @FunctionalInterface
  public interface FullFactBuildAction {
    FactBuildResponse build(FactBuildProgress progress);
  }

  /**
   * 在稳定根版本锁内按当前 ODS 发布事实。
   *
   * <p>因更高版本撤销归属或已被交错运行覆盖的空批次按幂等成功完成；任一事实 DML 或版本更新失败时
   * 整个事务回滚。
   */
  @Transactional
  public FactBuildResponse publish(
      QueuedFactBuildTask task, Function<List<Long>, FactBuildResponse> factAction) {
    if (task == null || task.id() == null || task.leaseOwner() == null) {
      throw new IllegalArgumentException("事实发布需要已领取且带 owner 的任务");
    }
    List<LockedHead> lockedHeads = lockTaskHeads(task);
    List<Long> rootIds =
        lockedHeads.stream()
            .filter(head -> head.publishedVersion() < head.latestChangeVersion())
            .map(LockedHead::rootId)
            .distinct()
            .sorted()
            .toList();
    FactType factType = FactType.valueOf(task.factType());
    Set<FactProjectionScope> beforeScopes =
        scopeResolver.resolveCurrentScopes(task.sourceInstance(), factType, rootIds);
    FactBuildResponse response =
        rootIds.isEmpty()
            ? new FactBuildResponse(task.scope(), false, 0, "事实目标已由当前版本覆盖")
            : factAction.apply(rootIds);
    Set<FactProjectionScope> affectedScopes = new LinkedHashSet<>(beforeScopes);
    affectedScopes.addAll(
        scopeResolver.resolveCurrentScopes(task.sourceInstance(), factType, rootIds));
    generationService.advanceAndQueue(task, affectedScopes);
    java.util.Set<Long> publishedRootIds = java.util.Set.copyOf(rootIds);
    for (LockedHead head : lockedHeads) {
      if (publishedRootIds.contains(head.rootId())) {
        publishHead(task, head);
      }
    }
    publicationFenceService.advanceAfterFactPublication(task.sourceInstance(), factType);
    finishOwnedTask(task, response);
    return response;
  }

  /**
   * 编排全量事实发布：分批构建后，在单一短事务内完成 FULL_EPOCH 推进、任务终态与发布状态结算。
   *
   * <p>构建批次各自独立提交，批间续期任务租约并写入用户可见进度事件；结算事务失败时已提交批次
   * 保留，由任务重试幂等重做收敛。
   */
  public FactBuildResponse publishFull(QueuedFactBuildTask task, FullFactBuildAction action) {
    if (task == null || task.id() == null || task.leaseOwner() == null || !task.full()) {
      throw new IllegalArgumentException("全量事实发布需要已领取的全量任务");
    }
    FactType factType = FactType.valueOf(task.factType());
    long coveredVersion =
        publicationStateService.publicationUpperBound(task.sourceInstance(), factType);
    FactBuildResponse response = action.build(progressReporter(task));
    FactProjectionScope fullEpoch =
        new FactProjectionScope(
            task.sourceInstance(),
            factType,
            ProjectionScopeType.FULL_EPOCH,
            FactProjectionScopeKeyCodec.SINGLETON_SCOPE_KEY);
    publicationTransaction.execute(() -> {
      generationService.advanceAndQueue(task, Set.of(fullEpoch));
      finishOwnedTask(task, response);
      publicationStateService.settleAfterFullPublication(
          task.sourceInstance(), factType, task.id(), coveredVersion);
      return null;
    });
    eventRecorder.record(
        task.factRunId(),
        task.configId(),
        task.sourceInstance(),
        "FACT_BUILD_COMPLETED",
        "全量事实构建完成（" + response.affectedRows() + " 行）");
    return response;
  }

  private FactBuildProgress progressReporter(QueuedFactBuildTask task) {
    int leaseSeconds = Math.max(1, mirrorProperties.getHeartbeatTimeoutSeconds());
    return (completedChunks, totalChunks, processedRows) -> {
      if (!taskService.renewTaskLease(task, leaseSeconds)) {
        throw new IllegalStateException("事实任务租约已失效：" + task.id());
      }
      eventRecorder.record(
          task.factRunId(),
          task.configId(),
          task.sourceInstance(),
          "FACT_BUILD_PROGRESS",
          "全量事实构建进度 "
              + completedChunks
              + "/"
              + totalChunks
              + " 批次（"
              + processedRows
              + " 行）");
    };
  }

  private List<LockedHead> lockTaskHeads(QueuedFactBuildTask task) {
    return jdbcTemplate.query(
        """
        select head.root_id, head.latest_change_version, head.published_version
          from fact_change_heads head
         where head.source_instance = ?
           and head.fact_type = ?
           and head.root_id in (
             select roots.root_id from fact_build_task_roots roots where roots.task_id = ?)
         order by head.root_id
         for update
        """,
        (resultSet, rowNum) ->
            new LockedHead(
                resultSet.getLong("root_id"),
                resultSet.getLong("latest_change_version"),
                resultSet.getLong("published_version")),
        task.sourceInstance(),
        task.factType(),
        task.id());
  }

  private void publishHead(QueuedFactBuildTask task, LockedHead head) {
    jdbcTemplate.update(
        """
        update fact_change_heads
           set published_version = greatest(published_version, ?),
               published_by_fact_build_task_id = ?,
               updated_at = current_timestamp
         where source_instance = ? and fact_type = ? and root_id = ?
        """,
        head.latestChangeVersion(),
        task.id(),
        task.sourceInstance(),
        task.factType(),
        head.rootId());
  }

  private void finishOwnedTask(QueuedFactBuildTask task, FactBuildResponse response) {
    int updated =
        jdbcTemplate.update(
            """
            update fact_build_tasks
               set status = 'SUCCESS',
                   affected_rows = ?,
                   message = ?,
                   error_message = null,
                   lock_owner = null,
                   heartbeat_at = null,
                   lease_until = null,
                   finished_at = current_timestamp,
                   updated_at = current_timestamp
             where id = ? and status = 'RUNNING' and lock_owner = ?
            """,
            response.affectedRows(),
            response.message(),
            task.id(),
            task.leaseOwner());
    if (updated != 1) {
      throw new IllegalStateException("事实任务租约已失效：" + task.id());
    }
    taskService.releaseTaskRoots(task.id());
  }

  private record LockedHead(long rootId, long latestChangeVersion, long publishedVersion) {}
}
