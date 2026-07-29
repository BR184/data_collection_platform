package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.MirrorBatchWriteResult;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.sync.SyncRunTableState;
import com.data.collection.platform.entity.sync.SyncRunTableTask;
import com.data.collection.platform.mapper.SyncRunTableStateMapper;
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
  private final SyncRunTablePlanningService tablePlanningService;
  private final MirrorTableWriter mirrorTableWriter;

  public SyncRunTablePageCommitService(
      SyncRunTableTaskLeaseService leaseService,
      SyncRunTableStateMapper stateMapper,
      SyncTableContinuationPlanner continuationPlanner,
      SyncRunTablePlanningService tablePlanningService,
      MirrorTableWriter mirrorTableWriter) {
    this.leaseService = leaseService;
    this.stateMapper = stateMapper;
    this.continuationPlanner = continuationPlanner;
    this.tablePlanningService = tablePlanningService;
    this.mirrorTableWriter = mirrorTableWriter;
  }

  /**
   * 原子提交扫描页，并在需要时创建下一扫描页或首个删除对账页。
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
      boolean authoritative,
      boolean fullReconcile) {
    leaseService.lockOwnedTask(task.getId(), task.getLeaseOwner());
    MirrorBatchWriteResult writeResult =
        authoritative
            ? mirrorTableWriter.replaceAuthoritativeScope(
                mirrorSchema, task.getLookupColumn(), task.getLookupValue(), rows, task.getId())
            : forceUpdate
                ? mirrorTableWriter.writeBatch(mirrorSchema, rows, task.getId(), true)
                : mirrorTableWriter.writeBatch(mirrorSchema, rows, task.getId());
    tablePlanningService.planAuthoritativeRelatedTasks(task, rows);
    if (hasMore) {
      continuationPlanner.enqueueContinuationTask(task, cursorUpdatedAt, cursorPk, batchSize);
    } else if (fullReconcile) {
      continuationPlanner.enqueueReconciliationTask(task, null, batchSize);
    }
    if (!leaseService.finishOwnedTask(
        task.getId(),
        task.getLeaseOwner(),
        (long) rows.size(),
        (long) writeResult.appliedRows(),
        "SUCCESS",
        null,
        cursorUpdatedAt,
        cursorPk)) {
      throw new SyncTaskLeaseLostException(task.getId());
    }
    updateScanState(task, state, cursorUpdatedAt, cursorPk, hasMore, fullReconcile);
    return new PageCommitResult(rows.size(), writeResult.appliedRows());
  }

  /** 在不写镜像行时原子完成已确认无变化的增量任务。 */
  @Transactional
  public void completeUnchangedTask(
      SyncRunTableTask task,
      SyncRunTableState state,
      LocalDateTime cursorUpdatedAt,
      String cursorPk) {
    leaseService.lockOwnedTask(task.getId(), task.getLeaseOwner());
    if (!leaseService.finishOwnedTask(
        task.getId(), task.getLeaseOwner(), 0L, 0L, "SUCCESS", null, cursorUpdatedAt, cursorPk)) {
      throw new SyncTaskLeaseLostException(task.getId());
    }
    updateScanState(task, state, cursorUpdatedAt, cursorPk, false, false);
  }

  /** 原子提交一页删除对账结果，并创建下一对账页或完成整表验证。 */
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
    int deletedRows = mirrorOnlyRows.isEmpty()
        ? 0
        : mirrorTableWriter.markRowsDeletedByPrimaryKeys(mirrorSchema, mirrorOnlyRows, task.getId());
    boolean hasMore = nextCursor != null && !nextCursor.isBlank() && scannedRows >= batchSize;
    if (hasMore) {
      continuationPlanner.enqueueReconciliationTask(task, nextCursor, batchSize);
    }
    if (!leaseService.finishOwnedTask(
        task.getId(),
        task.getLeaseOwner(),
        (long) scannedRows,
        (long) deletedRows,
        "SUCCESS",
        null,
        null,
        nextCursor)) {
      throw new SyncTaskLeaseLostException(task.getId());
    }
    LocalDateTime now = LocalDateTime.now();
    state.setDirtyFlag(hasMore);
    state.setLastSuccessAt(now);
    if (!hasMore) {
      state.setLastFullVerifiedAt(now);
    }
    state.setLastError("");
    state.setRetryCount(0);
    state.setUpdatedAt(now);
    stateMapper.updateById(state);
    return new PageCommitResult(scannedRows, deletedRows);
  }

  private void updateScanState(
      SyncRunTableTask task,
      SyncRunTableState state,
      LocalDateTime cursorUpdatedAt,
      String cursorPk,
      boolean hasMore,
      boolean fullReconcile) {
    LocalDateTime now = LocalDateTime.now();
    state.setDirtyFlag(fullReconcile || hasMore);
    state.setLastSuccessAt(now);
    boolean globalScan = "INCREMENTAL".equalsIgnoreCase(task.getRowStrategy())
        || "FULL".equalsIgnoreCase(task.getRowStrategy())
        || "FULL_RECONCILE".equalsIgnoreCase(task.getRowStrategy());
    if (globalScan && !hasMore && cursorUpdatedAt != null) {
      state.setLastWatermarkAt(cursorUpdatedAt);
      state.setLastCursorPk(cursorPk);
    }
    if (!fullReconcile && !hasMore && "FULL".equalsIgnoreCase(task.getRowStrategy())) {
      state.setLastFullVerifiedAt(now);
    }
    state.setLastError("");
    state.setRetryCount(0);
    state.setUpdatedAt(now);
    stateMapper.updateById(state);
  }

  public record PageCommitResult(long scannedRows, long appliedRows) {
  }
}
