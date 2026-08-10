package com.data.collection.platform.service.sync;

import com.data.collection.platform.common.logging.SyncRunLogContext;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.mapper.SyncRunMapper;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class SyncRunWorkerService {
  private final SyncRunMapper syncRunMapper;
  private final SyncRunLeaseService leaseService;
  private final SyncRunTablePlanningService tablePlanningService;
  private final SyncRunTableWorkerService tableWorkerService;
  private final SyncRunAuthoritativeScopeWorkerService authoritativeScopeWorkerService;
  private final SyncRunReconciliationCoordinator reconciliationCoordinator;
  private final ApplicationEventPublisher eventPublisher;
  private final SyncFactRefreshRunExecutor factRefreshRunExecutor;
  private final SyncRunDeadlineGuard deadlineGuard;
  private final SyncRunCompletionCommitService completionCommitService;
  private final SyncIncrementalCoverageService incrementalCoverageService;

  public SyncRunWorkerService(
      SyncRunMapper syncRunMapper,
      SyncRunLeaseService leaseService,
      SyncRunTablePlanningService tablePlanningService,
      SyncRunTableWorkerService tableWorkerService,
      SyncRunAuthoritativeScopeWorkerService authoritativeScopeWorkerService,
      SyncRunReconciliationCoordinator reconciliationCoordinator,
      ApplicationEventPublisher eventPublisher,
      SyncFactRefreshRunExecutor factRefreshRunExecutor,
      SyncRunDeadlineGuard deadlineGuard,
      SyncRunCompletionCommitService completionCommitService,
      SyncIncrementalCoverageService incrementalCoverageService) {
    this.syncRunMapper = syncRunMapper;
    this.leaseService = leaseService;
    this.tablePlanningService = tablePlanningService;
    this.tableWorkerService = tableWorkerService;
    this.authoritativeScopeWorkerService = authoritativeScopeWorkerService;
    this.reconciliationCoordinator = reconciliationCoordinator;
    this.eventPublisher = eventPublisher;
    this.factRefreshRunExecutor = factRefreshRunExecutor;
    this.deadlineGuard = deadlineGuard;
    this.completionCommitService = completionCommitService;
    this.incrementalCoverageService = incrementalCoverageService;
  }

  public void executeRun(SyncRun run) {
    if (run == null || run.getId() == null) {
      return;
    }
    try (SyncRunLogContext.Scope runContext = SyncRunLogContext.openRun(run, null);
        SyncRunLogContext.Scope action = SyncRunLogContext.action("Run_Execute")) {
      initializeRunningSnapshot(run);
      if (isCancellationRequested(run) || isDeadlineExpired(run)) {
        finishRun(run, SyncRunStatus.CANCELLED, 0, 0, cancellationMessage(run, "同步运行在处理前已取消"));
        return;
      }
      if (run.getRunType() == SyncRunType.FACT_REFRESH) {
        executeFactRefreshRun(run);
        return;
      }
      if (isMirrorRun(run)) {
        if (!executeTableRefreshRun(run)) {
          return;
        }
      } else {
        finishRun(run, SyncRunStatus.SUCCESS, 0, 0, null);
      }
      publishRunCompletion(run);
    } catch (SyncRunLeaseLostException e) {
      log.info(
          "Stopped sync run after lease ownership changed, runId={}", run.getRunId());
    } catch (Exception e) {
      try {
        finishRun(run, SyncRunStatus.FAILED, 0, 0, e.getMessage());
        publishRunCompletion(run);
      } catch (SyncRunLeaseLostException leaseLost) {
        log.info(
            "Did not overwrite sync run failure after lease ownership changed, runId={}",
            run.getRunId());
      }
      log.error("Sync run failed, runId={}", run.getRunId(), e);
    }
  }

  private boolean executeTableRefreshRun(SyncRun run) {
    int planned = tablePlanningService.planRunTables(run.getId());
    authoritativeScopeWorkerService.adoptFailedScopes(
        run.getId(), run.getSourceInstance());
    if (run.getRunType() == SyncRunType.INCREMENTAL_SYNC && planned == 0) {
      finishRun(
          run,
          SyncRunStatus.FAILED,
          0,
          0,
          "快速增量未规划任何快速增量表");
      return true;
    }
    if (isCancellationRequested(run) || isDeadlineExpired(run)) {
      finishRun(run, SyncRunStatus.CANCELLED, planned, 0, cancellationMessage(run, "同步运行在表任务执行前已取消"));
      return true;
    }
    SyncRunTableWorkerService.DrainResult drainResult =
        tableWorkerService.drainRunTasks(run, resolveTableWorkerCount(run));
    if (drainResult.yielded()) {
      return false;
    }
    SyncRunTableWorkerService.RunTableTaskSummary summary =
        tableWorkerService.summarizeRun(run.getId());
    if (summary.retryingTasks() > 0) {
      deferMirrorRunForTables(run, summary);
      return false;
    }
    SyncRunAuthoritativeScopeWorkerService.DrainResult scopeDrain =
        authoritativeScopeWorkerService.drainRunScopes(run, resolveTableWorkerCount(run));
    if (scopeDrain.yielded()) {
      return false;
    }
    SyncRunAuthoritativeScopeRepository.ScopeSummary scopeSummary =
        authoritativeScopeWorkerService.summarize(run.getId());
    if (scopeSummary.failed() == 0
        && (scopeSummary.queued() > 0
            || scopeSummary.running() > 0
            || scopeSummary.retryWaiting() > 0)) {
      deferMirrorRunForScopes(run, scopeSummary);
      return false;
    }
    if (scopeSummary.failed() == 0) {
      int plannedReconciliations = reconciliationCoordinator.planIfReady(run.getId());
      if (plannedReconciliations > 0) {
        SyncRunTableWorkerService.DrainResult reconciliationDrain =
            tableWorkerService.drainRunTasks(run, resolveTableWorkerCount(run));
        if (reconciliationDrain.yielded()) {
          return false;
        }
        summary = tableWorkerService.summarizeRun(run.getId());
      }
    }
    planned = Math.max(planned, summary.plannedTasks());
    run.setScannedRows(summary.scannedRows());
    run.setAppliedRows(summary.appliedRows());
    if (isCancellationRequested(run) || isDeadlineExpired(run)) {
      finishRun(run, SyncRunStatus.CANCELLED, planned, summary.completedTasks(), cancellationMessage(run, "同步运行已取消"));
      return true;
    }
    SyncRunStatus status = tableRunStatus(summary, scopeSummary);
    String errorMessage = tableRunErrorMessage(status, summary, scopeSummary);
    if (status == SyncRunStatus.SUCCESS && run.getRunType() == SyncRunType.INCREMENTAL_SYNC) {
      SyncIncrementalCoverageService.CoverageResult coverage =
          incrementalCoverageService.evaluate(run.getId());
      if (!coverage.complete()) {
        status = SyncRunStatus.FAILED;
        errorMessage = coverage.message();
      }
    }
    finishRun(
        run,
        status,
        planned,
        summary.completedTasks(),
        errorMessage);
    return true;
  }

  private void deferMirrorRunForTables(
      SyncRun run, SyncRunTableWorkerService.RunTableTaskSummary summary) {
    LocalDateTime runAfter =
        summary.nextRunAfter() == null
            ? LocalDateTime.now().plusSeconds(5)
            : summary.nextRunAfter();
    String message = "表任务等待重试";
    run.setScannedRows(summary.scannedRows());
    run.setAppliedRows(summary.appliedRows());
    run.setStatus(SyncRunStatus.RETRYING);
    run.setRunAfter(runAfter);
    run.setErrorMessage(message);
    if (leaseService.deferOwnedRun(run, SyncRunStatus.RETRYING, runAfter, message) != 1) {
      throw new SyncRunLeaseLostException(run.getId());
    }
    run.setLeaseOwner(null);
    run.setLeaseUntil(null);
  }

  private void deferMirrorRunForScopes(
      SyncRun run, SyncRunAuthoritativeScopeRepository.ScopeSummary scopeSummary) {
    LocalDateTime runAfter =
        scopeSummary.nextRunAfter() == null
            ? LocalDateTime.now().plusSeconds(5)
            : scopeSummary.nextRunAfter();
    String message = "权威关系范围等待重试";
    run.setStatus(SyncRunStatus.RETRYING);
    run.setRunAfter(runAfter);
    run.setErrorMessage(message);
    if (leaseService.deferOwnedRun(run, SyncRunStatus.RETRYING, runAfter, message) != 1) {
      throw new SyncRunLeaseLostException(run.getId());
    }
    run.setLeaseOwner(null);
    run.setLeaseUntil(null);
  }

  private void executeFactRefreshRun(SyncRun run) {
    SyncFactRefreshRunExecutor.Result result = factRefreshRunExecutor.execute(run);
    run.setAppliedRows(result.affectedRows());
    if (result.status() == SyncRunStatus.PAUSED || result.status() == SyncRunStatus.RETRYING) {
      run.setStatus(result.status());
      run.setRunAfter(result.runAfter());
      run.setErrorMessage(result.errorMessage());
      if (leaseService.deferOwnedRun(
              run, result.status(), result.runAfter(), result.errorMessage())
          != 1) {
        throw new SyncRunLeaseLostException(run.getId());
      }
      run.setLeaseOwner(null);
      run.setLeaseUntil(null);
      return;
    }
    finishRun(run, result.status(), result.plannedTasks(), result.completedTasks(), result.errorMessage());
  }

  private void initializeRunningSnapshot(SyncRun run) {
    run.setStatus(SyncRunStatus.RUNNING);
    run.setStartedAt(run.getStartedAt() == null ? LocalDateTime.now() : run.getStartedAt());
    run.setHeartbeatAt(LocalDateTime.now());
    run.setUpdatedAt(LocalDateTime.now());
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

  private boolean isDeadlineExpired(SyncRun run) {
    return deadlineGuard.requestCancellationIfExpired(run);
  }

  private String cancellationMessage(SyncRun run, String fallback) {
    return run.getErrorMessage() == null || run.getErrorMessage().isBlank() ? fallback : run.getErrorMessage();
  }

  private void finishRun(SyncRun run, SyncRunStatus status, int planned, int finished, String errorMessage) {
    run.setStatus(status);
    run.setPlannedTableCount(planned);
    run.setCompletedTableCount(finished);
    run.setFinishedAt(LocalDateTime.now());
    run.setErrorMessage(errorMessage);
    run.setUpdatedAt(LocalDateTime.now());
    completionCommitService.finishOwnedRun(run);
    run.setLeaseOwner(null);
    run.setLeaseUntil(null);
    tableWorkerService.terminalizeActiveTasksForRun(run.getId(), status, errorMessage);
    authoritativeScopeWorkerService.terminalizeRun(
        run.getId(), errorMessage == null ? "父镜像运行已经终态" : errorMessage);
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
            || run.getRunType() == SyncRunType.FULL_COMPENSATION_SCAN
            || run.getRunType() == SyncRunType.DELETE_RECONCILIATION);
  }

  private SyncRunStatus tableRunStatus(
      SyncRunTableWorkerService.RunTableTaskSummary summary,
      SyncRunAuthoritativeScopeRepository.ScopeSummary scopeSummary) {
    if (summary.failedTasks() > 0
        || summary.timedOutTasks() > 0
        || scopeSummary.failed() > 0) {
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
      SyncRunTableWorkerService.RunTableTaskSummary summary,
      SyncRunAuthoritativeScopeRepository.ScopeSummary scopeSummary) {
    if (status == SyncRunStatus.SUCCESS) {
      return null;
    }
    if (scopeSummary.failed() > 0) {
      return "一个或多个权威关系范围失败";
    }
    if (summary.failedTasks() > 0 || summary.timedOutTasks() > 0) {
      return "一个或多个表任务失败";
    }
    if (summary.cancelledTasks() > 0) {
      return "同步运行已取消";
    }
    return "一个或多个表任务未完成";
  }

  private int resolveTableWorkerCount(SyncRun run) {
    return run.getResolvedWorkerCount() == null
        ? 1
        : Math.max(1, run.getResolvedWorkerCount());
  }
}
