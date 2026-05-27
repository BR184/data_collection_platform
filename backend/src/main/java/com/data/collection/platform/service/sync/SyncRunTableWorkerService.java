package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.sync.SyncRunTableTask;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class SyncRunTableWorkerService {
  private final JdbcTemplate jdbcTemplate;
  private final SyncRunTableTaskLeaseService taskLeaseService;
  private final SyncRunTableTaskExecutor taskExecutor;

  public SyncRunTableWorkerService(
      JdbcTemplate jdbcTemplate,
      SyncRunTableTaskLeaseService taskLeaseService,
      SyncRunTableTaskExecutor taskExecutor) {
    this.jdbcTemplate = jdbcTemplate;
    this.taskLeaseService = taskLeaseService;
    this.taskExecutor = taskExecutor;
  }

  public int drainRunTasks(Long runId) {
    return drainRunTasks(runId, 1);
  }

  public int drainRunTasks(Long runId, int workerCount) {
    int workers = Math.max(1, workerCount);
    if (workers == 1) {
      return drainRunTasksSerial(runId, "table-worker");
    }
    return drainRunTasksParallel(runId, workers);
  }

  private int drainRunTasksSerial(Long runId, String owner) {
    int processed = 0;
    SyncRunTableTask task;
    while (!isRunCancellationRequested(runId) && (task = claimNextQueuedTask(runId, owner, 30)) != null) {
      if (isRunCancellationRequested(runId)) {
        finishTask(task.getId(), task.getRowsScanned(), task.getRowsApplied(), "CANCELLED", "同步运行已取消");
        cancelQueuedTasks(runId);
        break;
      }
      taskExecutor.executeTask(task);
      processed++;
    }
    if (isRunCancellationRequested(runId)) {
      cancelQueuedTasks(runId);
    }
    return processed;
  }

  private int drainRunTasksParallel(Long runId, int workerCount) {
    AtomicInteger processed = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(workerCount, new TableWorkerThreadFactory());
    List<Future<?>> futures = new ArrayList<>(workerCount);
    try {
      for (int index = 0; index < workerCount; index++) {
        String owner = "table-worker-" + (index + 1);
        futures.add(executor.submit(() -> processed.addAndGet(drainRunTasksSerial(runId, owner))));
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
                rs.getInt("retrying_tasks")),
        runId);
  }

  public int recoverTimedOutTasks() {
    return taskLeaseService.recoverTimedOutTasks();
  }

  public boolean isRunCancellationRequested(Long runId) {
    return taskLeaseService.isRunCancellationRequested(runId);
  }

  public void cancelQueuedTasks(Long runId) {
    taskLeaseService.cancelQueuedTasks(runId);
  }

  public SyncRunTableTask claimNextQueuedTask(Long runId, String owner, int leaseSeconds) {
    return taskLeaseService.claimNextQueuedTask(runId, owner, leaseSeconds);
  }

  public void finishTask(Long taskId, Long rowsScanned, Long rowsApplied, String status, String errorMessage) {
    taskLeaseService.finishTask(taskId, rowsScanned, rowsApplied, status, errorMessage);
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
      int retryingTasks) {
    public RunTableTaskSummary(int plannedTasks, int completedTasks, long scannedRows, long appliedRows) {
      this(plannedTasks, completedTasks, scannedRows, appliedRows, 0, 0, 0, 0, 0, 0);
    }
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
