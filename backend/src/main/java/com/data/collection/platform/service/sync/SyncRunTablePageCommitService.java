package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.MirrorMutationResult;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.sync.SyncRunTableState;
import com.data.collection.platform.entity.sync.SyncRunTableTask;
import com.data.collection.platform.mapper.SyncRunTableStateMapper;
import com.data.collection.platform.service.FactChangeTargetService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 将一个同步分页的本地副作用作为不可分割事务提交。 */
@Service
public class SyncRunTablePageCommitService {
  private final SyncRunTableTaskLeaseService leaseService;
  private final SyncRunTableStateMapper stateMapper;
  private final SyncTableContinuationPlanner continuationPlanner;
  private final SyncRunAuthoritativeScopePlanner authoritativeScopePlanner;
  private final MirrorTableWriter mirrorTableWriter;
  private final FactChangeTargetService factChangeTargetService;
  private final SyncRunReconciliationCoordinator reconciliationCoordinator;

  public SyncRunTablePageCommitService(
      SyncRunTableTaskLeaseService leaseService,
      SyncRunTableStateMapper stateMapper,
      SyncTableContinuationPlanner continuationPlanner,
      SyncRunAuthoritativeScopePlanner authoritativeScopePlanner,
      MirrorTableWriter mirrorTableWriter,
      FactChangeTargetService factChangeTargetService,
      SyncRunReconciliationCoordinator reconciliationCoordinator) {
    this.leaseService = leaseService;
    this.stateMapper = stateMapper;
    this.continuationPlanner = continuationPlanner;
    this.authoritativeScopePlanner = authoritativeScopePlanner;
    this.mirrorTableWriter = mirrorTableWriter;
    this.factChangeTargetService = factChangeTargetService;
    this.reconciliationCoordinator = reconciliationCoordinator;
  }

  /**
   * 原子提交扫描页，并在需要时创建下一扫描页或通过阶段屏障规划删除对账。
   */
  @Transactional
  public PageCommitResult commitScanPage(
      SyncRunTableTask task,
      SyncRunTableState state,
      SourceTableSchema mirrorSchema,
      List<Map<String, Object>> rows,
      LocalDateTime cursorUpdatedAt,
      String cursorPk,
      int batchSize,
      boolean hasMore,
      boolean forceUpdate,
      boolean fullReconcile) {
    leaseService.lockOwnedTask(task.getId(), task.getLeaseOwner());
    MirrorMutationResult mutationResult =
        forceUpdate
            ? mirrorTableWriter.writeBatch(mirrorSchema, rows, task.getId(), true)
            : mirrorTableWriter.writeBatch(mirrorSchema, rows, task.getId());
    registerFactTargets(task, mutationResult, fullReconcile);
    reconciliationCoordinator.lockStageMutation(task.getRunId());
    authoritativeScopePlanner.enqueueFromParentRows(task, rows);
    if (hasMore) {
      continuationPlanner.enqueueContinuationTask(task, cursorUpdatedAt, cursorPk, batchSize);
    }
    if (!leaseService.finishOwnedTask(
        task.getId(),
        task.getLeaseOwner(),
        (long) rows.size(),
        (long) mutationResult.appliedRows(),
        "SUCCESS",
        null,
        cursorUpdatedAt,
        cursorPk)) {
      throw new SyncTaskLeaseLostException(task.getId());
    }
    updateScanState(task, state, cursorUpdatedAt, cursorPk, hasMore);
    if ("FULL_RECONCILE".equalsIgnoreCase(task.getRowStrategy())) {
      reconciliationCoordinator.planIfReady(task.getRunId());
    }
    return new PageCommitResult(rows.size(), mutationResult.appliedRows());
  }

  /** 在不写镜像行时原子完成已确认无变化的增量任务。 */
  @Transactional
  public void completeUnchangedTask(
      SyncRunTableTask task,
      SyncRunTableState state,
      LocalDateTime cursorUpdatedAt,
      String cursorPk) {
    leaseService.lockOwnedTask(task.getId(), task.getLeaseOwner());
    reconciliationCoordinator.lockStageMutation(task.getRunId());
    if (!leaseService.finishOwnedTask(
        task.getId(), task.getLeaseOwner(), 0L, 0L, "SUCCESS", null, cursorUpdatedAt, cursorPk)) {
      throw new SyncTaskLeaseLostException(task.getId());
    }
    updateScanState(task, state, cursorUpdatedAt, cursorPk, false);
    if ("FULL_RECONCILE".equalsIgnoreCase(task.getRowStrategy())) {
      reconciliationCoordinator.planIfReady(task.getRunId());
    }
  }

  /** 原子提交一页删除对账结果，并重排当前任务或完成整表验证。 */
  @Transactional
  public PageCommitResult commitReconciliationPage(
      SyncRunTableTask task,
      SyncRunTableState state,
      SourceTableSchema mirrorSchema,
      List<Map<String, Object>> mirrorOnlyRows,
      int scannedRows,
      String nextCursor,
      int batchSize) {
    leaseService.lockOwnedTask(task.getId(), task.getLeaseOwner());
    MirrorMutationResult mutationResult = mirrorOnlyRows.isEmpty()
        ? MirrorMutationResult.empty()
        : mirrorTableWriter.markRowsDeletedByPrimaryKeys(mirrorSchema, mirrorOnlyRows, task.getId());
    registerFactTargets(
        task,
        mutationResult,
        "FULL_RECONCILE".equalsIgnoreCase(task.getRowStrategy()));
    boolean hasMore = nextCursor != null && !nextCursor.isBlank() && scannedRows >= batchSize;
    long totalScanned = accumulated(task.getRowsScanned(), scannedRows);
    long totalApplied = accumulated(task.getRowsApplied(), mutationResult.appliedRows());
    if (hasMore) {
      if (!leaseService.requeueOwnedReconciliationTask(
          task.getId(),
          task.getLeaseOwner(),
          nextCursor,
          scannedRows,
          mutationResult.appliedRows())) {
        throw new SyncTaskLeaseLostException(task.getId());
      }
    } else {
      if (!leaseService.finishOwnedTask(
          task.getId(),
          task.getLeaseOwner(),
          totalScanned,
          totalApplied,
          "SUCCESS",
          null,
          null,
          nextCursor)) {
        throw new SyncTaskLeaseLostException(task.getId());
      }
    }
    LocalDateTime now = LocalDateTime.now();
    state.setDirtyFlag(hasMore);
    state.setLastSuccessAt(now);
    if (!hasMore) {
      state.setLastDeleteReconciledAt(now);
      if ("FULL_RECONCILE".equalsIgnoreCase(task.getRowStrategy())) {
        state.setLastFullVerifiedAt(now);
      }
    }
    state.setLastError("");
    state.setRetryCount(0);
    state.setUpdatedAt(now);
    stateMapper.updateById(state);
    return new PageCommitResult(scannedRows, mutationResult.appliedRows());
  }

  private long accumulated(Long currentValue, long pageValue) {
    return Math.addExact(currentValue == null ? 0L : currentValue, pageValue);
  }

  private void registerFactTargets(
      SyncRunTableTask task,
      MirrorMutationResult mutationResult,
      boolean fullPublication) {
    if (fullPublication || mutationResult.changes().isEmpty()) {
      return;
    }
    factChangeTargetService.registerChanges(
        task.getRunId(),
        task.getId(),
        task.getSourceInstance(),
        task.getSourceTable(),
        mutationResult.changes());
  }

  private void updateScanState(
      SyncRunTableTask task,
      SyncRunTableState state,
      LocalDateTime cursorUpdatedAt,
      String cursorPk,
      boolean hasMore) {
    LocalDateTime now = LocalDateTime.now();
    state.setLastSuccessAt(now);
    boolean timestampScan = "INCREMENTAL".equalsIgnoreCase(task.getRowStrategy());
    boolean monotonicScan =
        "MONOTONIC_PRIMARY_KEY".equalsIgnoreCase(task.getRowStrategy());
    boolean fullScan = "FULL_RECONCILE".equalsIgnoreCase(task.getRowStrategy());
    boolean fullMonotonicScan =
        fullScan && "MONOTONIC_PRIMARY_KEY".equalsIgnoreCase(state.getRowStrategy());
    if (fullScan) {
      state.setDirtyFlag(true);
    } else if ((timestampScan || monotonicScan) && !hasMore) {
      state.setDirtyFlag(false);
    }
    if (timestampScan && !hasMore && cursorUpdatedAt != null) {
      state.setLastWatermarkAt(cursorUpdatedAt);
      state.setLastCursorPk(cursorPk);
    }
    if ((monotonicScan || fullMonotonicScan) && !hasMore) {
      state.setLastCursorPk(cursorPk == null || cursorPk.isBlank() ? "[]" : cursorPk);
    }
    state.setLastError("");
    state.setRetryCount(0);
    state.setUpdatedAt(now);
    stateMapper.updateById(state);
  }

  public record PageCommitResult(long scannedRows, long appliedRows) {
  }
}
