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
        || runType == SyncRunType.COMPENSATION_SCAN;
  }

  public boolean successful() {
    return status == SyncRunStatus.SUCCESS;
  }

  public boolean fullSync() {
    return runType == SyncRunType.FULL_SYNC;
  }

  public long appliedRowCount() {
    return appliedRows == null ? 0L : appliedRows;
  }
}
