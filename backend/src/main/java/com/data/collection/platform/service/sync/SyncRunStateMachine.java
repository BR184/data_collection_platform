package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import java.util.EnumSet;
import java.util.Set;

public final class SyncRunStateMachine {
  private static final Set<SyncRunStatus> ACTIVE_STATUSES =
      EnumSet.of(
          SyncRunStatus.SUBMITTED,
          SyncRunStatus.QUEUED,
          SyncRunStatus.RUNNING,
          SyncRunStatus.RETRYING,
          SyncRunStatus.CANCELLING);

  private static final Set<SyncRunStatus> COMPLETED_STATUSES =
      EnumSet.of(SyncRunStatus.SUCCESS, SyncRunStatus.PARTIAL_SUCCESS);

  private static final Set<SyncRunStatus> TERMINAL_STATUSES =
      EnumSet.of(
          SyncRunStatus.SUCCESS,
          SyncRunStatus.PARTIAL_SUCCESS,
          SyncRunStatus.FAILED,
          SyncRunStatus.CANCELLED,
          SyncRunStatus.TIMEOUT,
          SyncRunStatus.MERGED);

  private SyncRunStateMachine() {}

  public static Set<SyncRunStatus> activeStatuses() {
    return EnumSet.copyOf(ACTIVE_STATUSES);
  }

  public static boolean isActive(SyncRunStatus status) {
    return status != null && ACTIVE_STATUSES.contains(status);
  }

  public static boolean isCompleted(SyncRunStatus status) {
    return status != null && COMPLETED_STATUSES.contains(status);
  }

  public static boolean isTerminal(SyncRunStatus status) {
    return status != null && TERMINAL_STATUSES.contains(status);
  }

  public static SyncStatus toApiStatus(SyncRun run) {
    if (run == null) {
      return SyncStatus.IDLE;
    }
    return toApiStatus(run.getStatus());
  }

  public static SyncStatus toApiStatus(SyncRunStatus status) {
    if (status == null) {
      return SyncStatus.IDLE;
    }
    return switch (status) {
      case SUBMITTED, QUEUED -> SyncStatus.QUEUED;
      case RUNNING -> SyncStatus.RUNNING;
      case RETRYING -> SyncStatus.RETRYING;
      case CANCELLING -> SyncStatus.CANCELLING;
      case SUCCESS -> SyncStatus.SUCCESS;
      case PARTIAL_SUCCESS -> SyncStatus.PARTIAL_SUCCESS;
      case FAILED -> SyncStatus.FAILED;
      case CANCELLED -> SyncStatus.CANCELLED;
      case TIMEOUT -> SyncStatus.TIMEOUT;
      case MERGED -> SyncStatus.PENDING;
    };
  }
}
