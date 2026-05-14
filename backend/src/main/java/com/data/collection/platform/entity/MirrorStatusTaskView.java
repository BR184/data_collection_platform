package com.data.collection.platform.entity;

import java.time.LocalDateTime;

public record MirrorStatusTaskView(
    Long id,
    String runId,
    SyncType taskType,
    SyncTriggerType triggerType,
    SourceMode sourceMode,
    SyncStatus status,
    boolean cancelRequested,
    boolean pendingResync,
    Integer retryCount,
    LocalDateTime queuedAt,
    LocalDateTime startedAt,
    LocalDateTime finishedAt,
    String finishedReason) {

  public static MirrorStatusTaskView from(GitlabSyncTask task) {
    return new MirrorStatusTaskView(
        task.getId(),
        task.getRunId(),
        task.getTaskType(),
        task.getTriggerType(),
        task.getSourceMode(),
        task.getStatus(),
        task.isCancelRequested(),
        task.isPendingResync(),
        task.getRetryCount(),
        task.getQueuedAt(),
        task.getStartedAt(),
        task.getFinishedAt(),
        task.getFinishedReason());
  }

  public static MirrorStatusTaskView fromJob(GitlabSyncJob job, SourceMode sourceMode) {
    if (job == null) {
      return null;
    }
    return new MirrorStatusTaskView(
        job.getId(),
        job.getRunId(),
        taskTypeFromJob(job.getJobType()),
        job.getTriggerType(),
        sourceMode,
        job.getStatus(),
        false,
        false,
        job.getRetryCount(),
        job.getRunAfter(),
        job.getStartedAt(),
        job.getFinishedAt(),
        job.getErrorMessage());
  }

  private static SyncType taskTypeFromJob(GitlabSyncJobType jobType) {
    if (jobType == null) {
      return SyncType.INCREMENTAL;
    }
    return switch (jobType) {
      case DAILY_VERIFY -> SyncType.FULL;
      case MANUAL_REFRESH, FACT_REFRESH -> SyncType.INCREMENTAL;
      case COMPENSATION_SCAN -> SyncType.COMPENSATION;
      case HOOK_WAKEUP -> SyncType.SYSTEM_HOOK;
    };
  }
}
