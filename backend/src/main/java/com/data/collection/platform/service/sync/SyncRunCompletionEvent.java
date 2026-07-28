package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;

public record SyncRunCompletionEvent(
    Long runId,
    Long configId,
    String sourceInstance,
    SyncRunType runType,
    SyncRunStatus status,
    Long appliedRows) {

  public boolean mirrorRun() {
    return runType == SyncRunType.FULL_SYNC
        || runType == SyncRunType.INCREMENTAL_SYNC
        || runType == SyncRunType.TABLE_REFRESH
        || runType == SyncRunType.SYSTEM_HOOK
        || runType == SyncRunType.COMPENSATION_SCAN
        || runType == SyncRunType.FULL_COMPENSATION_SCAN;
  }

  public boolean successful() {
    return status == SyncRunStatus.SUCCESS;
  }

  /**
   * 判断镜像运行是否已完成并具备可用于事实刷新的一部分成功结果。
   *
   * @return 整体成功或部分成功时返回 {@code true}
   */
  public boolean factRefreshEligible() {
    return status == SyncRunStatus.SUCCESS || status == SyncRunStatus.PARTIAL_SUCCESS;
  }

  /**
   * 判断本次镜像结果是否要求全量发布事实。全量补偿可能删除来源已物理删除的主实体，
   * 这些实体无法再从活动 ODS 行反查精确目标，因此必须使用全量事实快照收敛。
   *
   * @return 全量同步或全量补偿扫描时返回 {@code true}
   */
  public boolean requiresFullFactRefresh() {
    return runType == SyncRunType.FULL_SYNC
        || runType == SyncRunType.FULL_COMPENSATION_SCAN;
  }

  public long appliedRowCount() {
    return appliedRows == null ? 0L : appliedRows;
  }
}
