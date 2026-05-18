package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.mapper.SyncRunMapper;
import com.data.collection.platform.service.GitlabConfigService;
import com.data.collection.platform.entity.GitlabSyncConfig;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class SyncRunWorkerService {
  private final SyncRunMapper syncRunMapper;
  private final SyncRunTablePlanningService tablePlanningService;
  private final SyncRunTableWorkerService tableWorkerService;
  private final GitlabConfigService configService;
  private final SyncThreadBudgetResolver threadBudgetResolver;
  private final ApplicationEventPublisher eventPublisher;
  private final SyncFactRefreshRunExecutor factRefreshRunExecutor;

  public SyncRunWorkerService(
      SyncRunMapper syncRunMapper,
      SyncRunTablePlanningService tablePlanningService,
      SyncRunTableWorkerService tableWorkerService,
      GitlabConfigService configService,
      SyncThreadBudgetResolver threadBudgetResolver,
      ApplicationEventPublisher eventPublisher,
      SyncFactRefreshRunExecutor factRefreshRunExecutor) {
    this.syncRunMapper = syncRunMapper;
    this.tablePlanningService = tablePlanningService;
    this.tableWorkerService = tableWorkerService;
    this.configService = configService;
    this.threadBudgetResolver = threadBudgetResolver;
    this.eventPublisher = eventPublisher;
    this.factRefreshRunExecutor = factRefreshRunExecutor;
  }

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
      if (isMirrorRun(run)) {
        executeTableRefreshRun(run);
      } else {
        finishRun(run, SyncRunStatus.SUCCESS, 0, 0, null);
      }
      updateSyncTimestamps(run);
      publishRunCompletion(run);
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
    tableWorkerService.drainRunTasks(run.getId(), resolveTableWorkerCount(run));
    SyncRunTableWorkerService.RunTableTaskSummary summary = tableWorkerService.summarizeRun(run.getId());
    run.setScannedRows(summary.scannedRows());
    run.setAppliedRows(summary.appliedRows());
    if (isCancellationRequested(run)) {
      finishRun(run, SyncRunStatus.CANCELLED, planned, summary.completedTasks(), "Sync run cancelled");
      return;
    }
    SyncRunStatus status = tableRunStatus(summary);
    finishRun(run, status, planned, summary.completedTasks(), tableRunErrorMessage(status, summary));
  }

  private void executeFactRefreshRun(SyncRun run) {
    SyncFactRefreshRunExecutor.Result result = factRefreshRunExecutor.execute(run);
    run.setAppliedRows(result.affectedRows());
    finishRun(run, result.status(), result.plannedTasks(), result.completedTasks(), result.errorMessage());
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
    if (run.getStatus() != SyncRunStatus.SUCCESS && run.getStatus() != SyncRunStatus.PARTIAL_SUCCESS) {
      return;
    }
    boolean fullSync = run.getRunType() == SyncRunType.FULL_SYNC;
    configService.updateSyncTime(run.getConfigId(), fullSync);
  }

  private void publishRunCompletion(SyncRun run) {
    if (!isMirrorRun(run)) {
      return;
    }
    eventPublisher.publishEvent(
        new SyncRunCompletionEvent(
            run.getId(),
            run.getConfigId(),
            run.getSourceInstance(),
            run.getRunType(),
            run.getStatus(),
            run.getAppliedRows()));
  }

  private boolean isMirrorRun(SyncRun run) {
    return run != null
        && (run.getRunType() == SyncRunType.FULL_SYNC
            || run.getRunType() == SyncRunType.INCREMENTAL_SYNC
            || run.getRunType() == SyncRunType.TABLE_REFRESH
            || run.getRunType() == SyncRunType.SYSTEM_HOOK
            || run.getRunType() == SyncRunType.COMPENSATION_SCAN);
  }

  private SyncRunStatus tableRunStatus(SyncRunTableWorkerService.RunTableTaskSummary summary) {
    if (summary.failedTasks() > 0 || summary.timedOutTasks() > 0) {
      return summary.completedTasks() > 0 || summary.appliedRows() > 0L
          ? SyncRunStatus.PARTIAL_SUCCESS
          : SyncRunStatus.FAILED;
    }
    if (summary.cancelledTasks() > 0) {
      return SyncRunStatus.CANCELLED;
    }
    if (summary.pendingTasks() > 0 || summary.runningTasks() > 0 || summary.retryingTasks() > 0) {
      return summary.completedTasks() > 0 ? SyncRunStatus.PARTIAL_SUCCESS : SyncRunStatus.FAILED;
    }
    return SyncRunStatus.SUCCESS;
  }

  private String tableRunErrorMessage(
      SyncRunStatus status,
      SyncRunTableWorkerService.RunTableTaskSummary summary) {
    if (status == SyncRunStatus.SUCCESS) {
      return null;
    }
    if (summary.failedTasks() > 0 || summary.timedOutTasks() > 0) {
      return "One or more table tasks failed";
    }
    if (summary.cancelledTasks() > 0) {
      return "Sync run cancelled";
    }
    return "One or more table tasks did not complete";
  }

  private int resolveTableWorkerCount(SyncRun run) {
    GitlabSyncConfig config = configService.getConfigById(run.getConfigId());
    if (config == null) {
      config = new GitlabSyncConfig();
    }
    if (run.getThreadMode() != null && !run.getThreadMode().isBlank()) {
      config.setSyncThreadMode(run.getThreadMode());
    }
    if (run.getThreadValue() != null) {
      config.setSyncThreadValue(run.getThreadValue());
    }
    return threadBudgetResolver.resolve(config);
  }
}
