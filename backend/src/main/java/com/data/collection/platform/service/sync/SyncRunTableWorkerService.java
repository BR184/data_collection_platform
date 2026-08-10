package com.data.collection.platform.service.sync;

import com.data.collection.platform.common.logging.SyncRunLogContext;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunTableTask;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class SyncRunTableWorkerService {
  private final JdbcTemplate jdbcTemplate;
  private final SyncRunTableTaskLeaseService taskLeaseService;
  private final SyncRunTableTaskHeartbeatService heartbeatService;
  private final SyncRunYieldService yieldService;
  private final SyncRunTableTaskExecutor taskExecutor;

  public SyncRunTableWorkerService(
      JdbcTemplate jdbcTemplate,
      SyncRunTableTaskLeaseService taskLeaseService,
      SyncRunTableTaskHeartbeatService heartbeatService,
      SyncRunYieldService yieldService,
      SyncRunTableTaskExecutor taskExecutor) {
    this.jdbcTemplate = jdbcTemplate;
    this.taskLeaseService = taskLeaseService;
    this.heartbeatService = heartbeatService;
    this.yieldService = yieldService;
    this.taskExecutor = taskExecutor;
  }

  public DrainResult drainRunTasks(SyncRun run, int workerCount) {
    if (run == null || run.getId() == null) {
      return new DrainResult(0, false);
    }
    Long runId = run.getId();
    int workers = Math.max(1, workerCount);
    int processed = 0;
    while (!isRunCancellationRequested(runId)) {
      AtomicBoolean yieldRequested = new AtomicBoolean();
      int passProcessed =
          workers == 1
              ? drainRunTasksSerial(run, createWorkerOwner(runId, 1), yieldRequested)
              : drainRunTasksParallelPass(run, workers, yieldRequested);
      processed += passProcessed;
      if (yieldRequested.get() && yieldService.pauseIfRequested(run)) {
        return new DrainResult(processed, true);
      }
      if (isRunCancellationRequested(runId)) {
        cancelQueuedTasks(runId);
        break;
      }
      if (!yieldRequested.get() || passProcessed <= 0) {
        break;
      }
      log.info("Continuing sync table drain after a withdrawn yield request, runId={}", runId);
    }
    if (isRunCancellationRequested(runId)) {
      cancelQueuedTasks(runId);
    }
    return new DrainResult(processed, false);
  }

  private int drainRunTasksSerial(
      SyncRun run, String owner, AtomicBoolean yieldRequested) {
    try (SyncRunLogContext.Scope runContext = SyncRunLogContext.openRun(run, null)) {
      Long runId = run.getId();
      int processed = 0;
      SyncRunTableTask task;
      while (!yieldRequested.get()
          && !isRunCancellationRequested(runId)
          && (task = claimNextRunnableTask(runId, owner, heartbeatService.leaseSeconds())) != null) {
        if (isRunCancellationRequested(runId)) {
          taskLeaseService.finishOwnedTask(
              task.getId(),
              task.getLeaseOwner(),
              task.getRowsScanned(),
              task.getRowsApplied(),
              "CANCELLED",
              "同步运行已取消");
          cancelQueuedTasks(runId);
          break;
        }
        try (SyncRunLogContext.Scope taskContext = SyncRunLogContext.openTask(task);
            SyncRunLogContext.Scope action = SyncRunLogContext.action("Table_Task_Execute")) {
          taskExecutor.executeTask(task);
        }
        processed++;
        if (yieldService.shouldYieldAfterTableTask(run, task)) {
          yieldRequested.set(true);
        }
      }
      if (isRunCancellationRequested(runId)) {
        cancelQueuedTasks(runId);
      }
      return processed;
    }
  }

  private int drainRunTasksParallelPass(
      SyncRun run, int workerCount, AtomicBoolean yieldRequested) {
    AtomicInteger processed = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(workerCount, new TableWorkerThreadFactory());
    List<Future<?>> futures = new ArrayList<>(workerCount);
    try {
      for (int index = 0; index < workerCount; index++) {
        String owner = createWorkerOwner(run.getId(), index + 1);
        futures.add(
            executor.submit(
                () -> processed.addAndGet(drainRunTasksSerial(run, owner, yieldRequested))));
      }
      for (Future<?> future : futures) {
        future.get();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("处理同步表任务时被中断", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("处理同步表任务失败", e.getCause());
    } finally {
      executor.shutdownNow();
    }
    return processed.get();
  }

  private String createWorkerOwner(Long runId, int workerSlot) {
    return "table-worker-" + runId + "-" + workerSlot + "-" + UUID.randomUUID();
  }

  public RunTableTaskSummary summarizeRun(Long runId) {
    if (runId == null) {
      return new RunTableTaskSummary(0, 0, 0L, 0L);
    }
    return jdbcTemplate.queryForObject(
        """
        select count(*) as planned_tasks,
               count(*) filter (where status in ('SUCCESS', 'PARTIAL_SUCCESS')) as completed_tasks,
               count(*) filter (where status = 'FAILED') as failed_tasks,
               count(*) filter (where status = 'TIMEOUT') as timed_out_tasks,
               count(*) filter (where status = 'CANCELLED') as cancelled_tasks,
               count(*) filter (where status = 'QUEUED') as pending_tasks,
               count(*) filter (where status = 'RUNNING') as running_tasks,
               count(*) filter (where status = 'RETRYING') as retrying_tasks,
               min(run_after) filter (where status = 'RETRYING') as next_run_after,
               coalesce(sum(rows_scanned), 0) as scanned_rows,
               coalesce(sum(rows_applied), 0) as applied_rows
          from sync_run_table_tasks
         where run_id = ?
        """,
        (rs, rowNum) ->
            new RunTableTaskSummary(
                rs.getInt("planned_tasks"),
                rs.getInt("completed_tasks"),
                rs.getLong("scanned_rows"),
                rs.getLong("applied_rows"),
                rs.getInt("failed_tasks"),
                rs.getInt("timed_out_tasks"),
                rs.getInt("cancelled_tasks"),
                rs.getInt("pending_tasks"),
                rs.getInt("running_tasks"),
                rs.getInt("retrying_tasks"),
                rs.getTimestamp("next_run_after") == null
                    ? null
                    : rs.getTimestamp("next_run_after").toLocalDateTime()),
        runId);
  }

  public int recoverTimedOutTasks() {
    return taskLeaseService.recoverTimedOutTasks();
  }

  public int terminalizeActiveTasksForRun(Long runId, SyncRunStatus runStatus, String message) {
    return taskLeaseService.terminalizeActiveTasksForRun(runId, runStatus, message);
  }

  public boolean isRunCancellationRequested(Long runId) {
    return taskLeaseService.isRunCancellationRequested(runId);
  }

  public void cancelQueuedTasks(Long runId) {
    taskLeaseService.cancelQueuedTasks(runId);
  }

  /** 领取指定运行中已到期的排队或重试表任务。 */
  public SyncRunTableTask claimNextRunnableTask(Long runId, String owner, int leaseSeconds) {
    return taskLeaseService.claimNextRunnableTask(runId, owner, leaseSeconds);
  }

  public record RunTableTaskSummary(
      int plannedTasks,
      int completedTasks,
      long scannedRows,
      long appliedRows,
      int failedTasks,
      int timedOutTasks,
      int cancelledTasks,
      int pendingTasks,
      int runningTasks,
      int retryingTasks,
      java.time.LocalDateTime nextRunAfter) {
    public RunTableTaskSummary(int plannedTasks, int completedTasks, long scannedRows, long appliedRows) {
      this(plannedTasks, completedTasks, scannedRows, appliedRows, 0, 0, 0, 0, 0, 0, null);
    }

    public RunTableTaskSummary(
        int plannedTasks,
        int completedTasks,
        long scannedRows,
        long appliedRows,
        int failedTasks,
        int timedOutTasks,
        int cancelledTasks,
        int pendingTasks,
        int runningTasks,
        int retryingTasks) {
      this(
          plannedTasks,
          completedTasks,
          scannedRows,
          appliedRows,
          failedTasks,
          timedOutTasks,
          cancelledTasks,
          pendingTasks,
          runningTasks,
          retryingTasks,
          null);
    }
  }

  /** 单次运行任务排空结果。 */
  public record DrainResult(int processedTasks, boolean yielded) {
  }

  private static final class TableWorkerThreadFactory implements ThreadFactory {
    private final AtomicInteger counter = new AtomicInteger();

    @Override
    public Thread newThread(Runnable runnable) {
      Thread thread = new Thread(runnable, "sync-table-worker-" + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    }
  }
}
