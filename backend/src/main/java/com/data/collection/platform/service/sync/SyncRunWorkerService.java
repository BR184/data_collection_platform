package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.mapper.SyncRunMapper;
import com.data.collection.platform.service.FactBuildTaskService;
import com.data.collection.platform.service.FactRefreshTaskWorkerService;
import com.data.collection.platform.service.GitlabConfigService;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.QueuedFactBuildTask;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class SyncRunWorkerService {
  private final SyncRunMapper syncRunMapper;
  private final SyncRunTablePlanningService tablePlanningService;
  private final SyncRunTableWorkerService tableWorkerService;
  private final GitlabConfigService configService;
  private final SyncRunSubmissionService submissionService;
  private final FactBuildTaskService factBuildTaskService;
  private final FactRefreshTaskWorkerService factRefreshTaskWorkerService;

  public SyncRunWorkerService(
      SyncRunMapper syncRunMapper,
      SyncRunTablePlanningService tablePlanningService,
      SyncRunTableWorkerService tableWorkerService,
      GitlabConfigService configService,
      SyncRunSubmissionService submissionService,
      FactBuildTaskService factBuildTaskService,
      FactRefreshTaskWorkerService factRefreshTaskWorkerService) {
    this.syncRunMapper = syncRunMapper;
    this.tablePlanningService = tablePlanningService;
    this.tableWorkerService = tableWorkerService;
    this.configService = configService;
    this.submissionService = submissionService;
    this.factBuildTaskService = factBuildTaskService;
    this.factRefreshTaskWorkerService = factRefreshTaskWorkerService;
  }

  @Transactional
  public void executeRun(SyncRun run) {
    if (run == null || run.getId() == null) {
      return;
    }
    markRunning(run);
    try {
      if (isCancellationRequested(run)) {
        finishRun(run, SyncRunStatus.CANCELLED, 0, 0, "Sync run cancelled before processing");
        return;
      }
      if (run.getRunType() == SyncRunType.FACT_REFRESH) {
        executeFactRefreshRun(run);
        return;
      }
      if (run.getRunType() == SyncRunType.TABLE_REFRESH) {
        executeTableRefreshRun(run);
      } else {
        finishRun(run, SyncRunStatus.SUCCESS, 0, 0, null);
      }
      updateSyncTimestamps(run);
      submitFactRefreshIfNeeded(run);
    } catch (Exception e) {
      finishRun(run, SyncRunStatus.FAILED, 0, 0, e.getMessage());
      log.error("Sync run failed, runId={}", run.getRunId(), e);
    }
  }

  private void executeTableRefreshRun(SyncRun run) {
    int planned = tablePlanningService.planRunTables(run.getId());
    if (isCancellationRequested(run)) {
      finishRun(run, SyncRunStatus.CANCELLED, planned, 0, "Sync run cancelled before table execution");
      return;
    }
    tableWorkerService.drainRunTasks(run.getId());
    SyncRunTableWorkerService.RunTableTaskSummary summary = tableWorkerService.summarizeRun(run.getId());
    run.setScannedRows(summary.scannedRows());
    run.setAppliedRows(summary.appliedRows());
    if (isCancellationRequested(run)) {
      finishRun(run, SyncRunStatus.CANCELLED, planned, summary.completedTasks(), "Sync run cancelled");
      return;
    }
    finishRun(run, SyncRunStatus.SUCCESS, planned, summary.completedTasks(), null);
  }

  private void executeFactRefreshRun(SyncRun run) {
    GitlabSyncConfig config = configService.getConfigById(run.getConfigId());
    boolean full = factRefreshFullBuild(run);
    int planned = factBuildTaskService.enqueueMirrorRefreshTasks(config, full, run.getId());
    int completed = 0;
    long affectedRows = 0L;
    QueuedFactBuildTask task;
    while ((task = factBuildTaskService.claimNextQueuedTaskForRun(run.getId(), "fact-run-worker", 30)) != null) {
      FactBuildResponse response = factRefreshTaskWorkerService.execute(task);
      if (response != null) {
        completed++;
        affectedRows += response.affectedRows();
      }
    }
    run.setAppliedRows(affectedRows);
    SyncRunStatus status = completed < planned ? SyncRunStatus.PARTIAL_SUCCESS : SyncRunStatus.SUCCESS;
    finishRun(run, status, planned, completed, status == SyncRunStatus.SUCCESS ? null : "One or more fact refresh tasks failed");
  }

  private void markRunning(SyncRun run) {
    run.setStatus(SyncRunStatus.RUNNING);
    run.setStartedAt(run.getStartedAt() == null ? LocalDateTime.now() : run.getStartedAt());
    run.setHeartbeatAt(LocalDateTime.now());
    run.setUpdatedAt(LocalDateTime.now());
    syncRunMapper.updateById(run);
  }

  private boolean isCancellationRequested(SyncRun run) {
    SyncRun latest = syncRunMapper.selectById(run.getId());
    if (latest == null) {
      latest = run;
    }
    boolean cancelRequested =
        Boolean.TRUE.equals(latest.getCancelRequested())
            || latest.getStatus() == SyncRunStatus.CANCELLING
            || latest.getStatus() == SyncRunStatus.CANCELLED;
    if (cancelRequested) {
      run.setCancelRequested(true);
      run.setStatus(latest.getStatus() == SyncRunStatus.CANCELLED ? SyncRunStatus.CANCELLED : SyncRunStatus.CANCELLING);
    }
    return cancelRequested;
  }

  private void finishRun(SyncRun run, SyncRunStatus status, int planned, int finished, String errorMessage) {
    run.setStatus(status);
    run.setPlannedTableCount(planned);
    run.setCompletedTableCount(finished);
    run.setFinishedAt(LocalDateTime.now());
    run.setErrorMessage(errorMessage);
    run.setUpdatedAt(LocalDateTime.now());
    syncRunMapper.updateById(run);
  }

  private void updateSyncTimestamps(SyncRun run) {
    if (!isMirrorRun(run)) {
      return;
    }
    boolean fullSync = run.getRunType() == SyncRunType.FULL_SYNC;
    configService.updateSyncTime(run.getConfigId(), fullSync);
  }

  private void submitFactRefreshIfNeeded(SyncRun run) {
    if (!isMirrorRun(run) || run.getStatus() != SyncRunStatus.SUCCESS || appliedRows(run) <= 0L) {
      return;
    }
    GitlabSyncConfig config = configService.getConfigById(run.getConfigId());
    submissionService.submitFactRefresh(
        config,
        run.getId(),
        run.getRunType() == SyncRunType.FULL_SYNC,
        "Mirror run completed; refresh fact layer");
  }

  private boolean isMirrorRun(SyncRun run) {
    return run != null
        && (run.getRunType() == SyncRunType.FULL_SYNC
            || run.getRunType() == SyncRunType.INCREMENTAL_SYNC
            || run.getRunType() == SyncRunType.TABLE_REFRESH
            || run.getRunType() == SyncRunType.SYSTEM_HOOK
            || run.getRunType() == SyncRunType.COMPENSATION_SCAN);
  }

  private long appliedRows(SyncRun run) {
    return run.getAppliedRows() == null ? 0L : run.getAppliedRows();
  }

  private boolean factRefreshFullBuild(SyncRun run) {
    String payload = run.getPayloadJson();
    return payload != null && payload.replace(" ", "").contains("\"fullBuild\":true");
  }
}
