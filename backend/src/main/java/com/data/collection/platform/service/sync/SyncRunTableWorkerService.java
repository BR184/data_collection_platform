package com.data.collection.platform.service.sync;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.MirrorBatchWriteResult;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunTableState;
import com.data.collection.platform.entity.sync.SyncRunTableTask;
import com.data.collection.platform.mapper.SyncRunTableTaskMapper;
import com.data.collection.platform.mapper.SyncRunTableStateMapper;
import com.data.collection.platform.service.GitlabConfigService;
import com.data.collection.platform.service.GitlabMirrorSchemaService;
import com.data.collection.platform.service.GitlabMirrorTableStorageService;
import java.time.Instant;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class SyncRunTableWorkerService {
  private static final LocalDateTime INITIAL_WATERMARK = LocalDateTime.of(1970, 1, 1, 0, 0);

  private final SyncRunTableTaskMapper taskMapper;
  private final SyncRunTableStateMapper stateMapper;
  private final JdbcTemplate jdbcTemplate;
  private final GitlabConfigService configService;
  private final SourceTableReader sourceTableReader;
  private final GitlabMirrorSchemaService mirrorSchemaService;
  private final GitlabMirrorTableStorageService storageService;

  public SyncRunTableWorkerService(
      SyncRunTableTaskMapper taskMapper,
      SyncRunTableStateMapper stateMapper,
      JdbcTemplate jdbcTemplate,
      GitlabConfigService configService,
      SourceTableReader sourceTableReader,
      GitlabMirrorSchemaService mirrorSchemaService,
      GitlabMirrorTableStorageService storageService) {
    this.taskMapper = taskMapper;
    this.stateMapper = stateMapper;
    this.jdbcTemplate = jdbcTemplate;
    this.configService = configService;
    this.sourceTableReader = sourceTableReader;
    this.mirrorSchemaService = mirrorSchemaService;
    this.storageService = storageService;
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
        finishTask(task.getId(), task.getRowsScanned(), task.getRowsApplied(), "CANCELLED", "Sync run cancelled");
        cancelQueuedTasks(runId);
        break;
      }
      executeTask(task);
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
      throw new IllegalStateException("Interrupted while draining sync table tasks", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("Failed to drain sync table tasks", e.getCause());
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
    int retried =
        jdbcTemplate.update(
            """
            update sync_run_table_tasks
               set status = 'QUEUED',
                   retry_count = retry_count + 1,
                   lease_owner = null,
                   lease_until = null,
                   heartbeat_at = null,
                   last_error = 'Table task lease timed out; retry queued',
                   run_after = current_timestamp,
                   updated_at = current_timestamp
             where status = 'RUNNING'
               and lease_until is not null
               and lease_until < current_timestamp
               and retry_count < max_retry_count
            """);
    int timedOut =
        jdbcTemplate.update(
            """
            update sync_run_table_tasks
               set status = 'TIMEOUT',
                   lease_owner = null,
                   lease_until = null,
                   heartbeat_at = null,
                   last_error = 'Table task lease timed out',
                   finished_at = current_timestamp,
                   updated_at = current_timestamp
             where status = 'RUNNING'
               and lease_until is not null
               and lease_until < current_timestamp
               and retry_count >= max_retry_count
            """);
    return retried + timedOut;
  }

  public boolean isRunCancellationRequested(Long runId) {
    if (runId == null) {
      return false;
    }
    try {
      Boolean cancelled =
          jdbcTemplate.queryForObject(
              """
              select cancel_requested or status in ('CANCELLING', 'CANCELLED')
                from sync_runs
               where id = ?
              """,
              Boolean.class,
              runId);
      return Boolean.TRUE.equals(cancelled);
    } catch (EmptyResultDataAccessException ex) {
      return false;
    }
  }

  public void cancelQueuedTasks(Long runId) {
    jdbcTemplate.update(
        """
        update sync_run_table_tasks
           set status = 'CANCELLED',
               last_error = coalesce(last_error, 'Sync run cancelled'),
               finished_at = current_timestamp,
               updated_at = current_timestamp
         where run_id = ?
           and status = 'QUEUED'
        """,
        runId);
  }

  public SyncRunTableTask claimNextQueuedTask(Long runId, String owner, int leaseSeconds) {
    try {
      return jdbcTemplate.queryForObject(
          """
          update sync_run_table_tasks
             set status = 'RUNNING',
                 lease_owner = ?,
                 lease_until = current_timestamp + (? * interval '1 second'),
                 heartbeat_at = current_timestamp,
                 started_at = coalesce(started_at, current_timestamp),
                 updated_at = current_timestamp
           where id = (
             select candidate.id
               from sync_run_table_tasks candidate
              where candidate.run_id = ?
                and candidate.status = 'QUEUED'
              order by candidate.run_after asc, candidate.created_at asc, candidate.id asc
              for update skip locked
              limit 1
           )
           returning *
          """,
          this::mapTask,
          owner,
          Math.max(1, leaseSeconds),
          runId);
    } catch (EmptyResultDataAccessException ex) {
      return null;
    }
  }

  public void finishTask(Long taskId, Long rowsScanned, Long rowsApplied, String status, String errorMessage) {
    jdbcTemplate.update(
        """
        update sync_run_table_tasks
           set status = ?,
               rows_scanned = coalesce(?, rows_scanned),
               rows_applied = coalesce(?, rows_applied),
               last_error = ?,
               finished_at = current_timestamp,
               updated_at = current_timestamp
         where id = ?
        """,
        status,
        rowsScanned,
        rowsApplied,
        errorMessage,
        taskId);
  }

  private void executeTask(SyncRunTableTask task) {
    SyncRunTableState state = findState(task);
    try {
      if (state == null) {
        throw new IllegalStateException("Table sync state is missing");
      }
      if (!Boolean.TRUE.equals(state.getSyncEnabled())) {
        throw new IllegalStateException("Table sync state is disabled");
      }
      GitlabSyncConfig config = configService.getConfigById(task.getConfigId());
      TableWhitelistOption option = tableOption(state);
      GitlabMirrorSchemaService.PreparedMirrorTable preparedMirrorTable =
          mirrorSchemaService.getPreparedMirrorTableForSync(config, option);
      mirrorSchemaService.markTableSyncing(config.getId(), state.getSourceTable());
      if (isRunCancellationRequested(task.getRunId())) {
        finishTask(task.getId(), 0L, 0L, "CANCELLED", "Sync run cancelled");
        mirrorSchemaService.markTableIdle(config.getId(), state.getSourceTable(), LocalDateTime.now());
        return;
      }

      int batchSize = resolveBatchSize(task);
      LocalDateTime scanStart = task.getWatermarkAt() == null ? INITIAL_WATERMARK : task.getWatermarkAt();
      boolean fullTask = "FULL".equalsIgnoreCase(task.getRowStrategy());
      boolean preciseTask = "PRECISE".equalsIgnoreCase(task.getRowStrategy());
      if (!fullTask && !preciseTask && !"INCREMENTAL".equalsIgnoreCase(state.getRowStrategy())) {
        throw new IllegalStateException("Table task is not executable by incremental worker");
      }
      List<Map<String, Object>> rows =
          fullTask
              ? sourceTableReader.readFullBatch(
                  config, option, preparedMirrorTable.mirrorSchema(), task.getCursorPk(), batchSize)
              : preciseTask
                  ? sourceTableReader.readPrecise(config, option, task.getLookupColumn(), task.getLookupValue())
                  : sourceTableReader.readIncrementalBatch(
                      config, option, scanStart, task.getCursorUpdatedAt(), task.getCursorPk(), batchSize);
      if (isRunCancellationRequested(task.getRunId())) {
        finishTask(task.getId(), 0L, 0L, "CANCELLED", "Sync run cancelled");
        mirrorSchemaService.markTableIdle(config.getId(), state.getSourceTable(), LocalDateTime.now());
        return;
      }
      MirrorBatchWriteResult writeResult =
          storageService.upsertBatch(preparedMirrorTable.mirrorSchema(), rows, task.getId());
      if (isRunCancellationRequested(task.getRunId())) {
        finishTask(task.getId(), (long) rows.size(), (long) writeResult.appliedRows(), "CANCELLED", "Sync run cancelled");
        mirrorSchemaService.markTableIdle(config.getId(), state.getSourceTable(), LocalDateTime.now());
        return;
      }
      RowCursor lastCursor = lastCursor(rows, state, fullTask);
      boolean hasMore =
          !preciseTask
              && rows.size() >= batchSize
              && (fullTask ? !lastCursor.primaryKey().isBlank() : lastCursor.updatedAt() != null);
      RowCursor completionCursor = fullTask && !hasMore
          ? new RowCursor(findFullSyncWatermark(config, option, state), lastCursor.primaryKey())
          : lastCursor;
      markSuccess(task, state, rows.size(), writeResult.appliedRows(), completionCursor, hasMore);
      if (hasMore && !isRunCancellationRequested(task.getRunId())) {
        taskMapper.insert(createContinuationTask(task, lastCursor, batchSize));
      }
      mirrorSchemaService.markTableIdle(config.getId(), state.getSourceTable(), LocalDateTime.now());
    } catch (Exception e) {
      markFailure(task, state, e);
      log.warn(
          "Sync run table task failed, taskId={}, configId={}, sourceTable={}",
          task.getId(),
          task.getConfigId(),
          task.getSourceTable(),
          e);
    }
  }

  private SyncRunTableState findState(SyncRunTableTask task) {
    if (task == null) {
      return null;
    }
    if (task.getStateId() != null) {
      SyncRunTableState state = stateMapper.selectById(task.getStateId());
      if (state != null) {
        return state;
      }
    }
    return stateMapper.selectOne(
        new LambdaQueryWrapper<SyncRunTableState>()
            .eq(SyncRunTableState::getConfigId, task.getConfigId())
            .eq(SyncRunTableState::getSourceInstance, task.getSourceInstance())
            .eq(SyncRunTableState::getSourceTable, task.getSourceTable())
            .last("limit 1"));
  }

  private void markSuccess(
      SyncRunTableTask task,
      SyncRunTableState state,
      int scannedRows,
      int appliedRows,
      RowCursor lastCursor,
      boolean hasMore) {
    LocalDateTime now = LocalDateTime.now();
    finishTask(task.getId(), (long) scannedRows, (long) appliedRows, "SUCCESS", null);

    state.setDirtyFlag(hasMore);
    state.setLastSuccessAt(now);
    if (lastCursor.updatedAt() != null) {
      state.setLastWatermarkAt(lastCursor.updatedAt());
      state.setLastCursorPk(lastCursor.primaryKey());
    }
    state.setLastError("");
    state.setRetryCount(0);
    state.setUpdatedAt(now);
    stateMapper.updateById(state);
  }

  private void markFailure(SyncRunTableTask task, SyncRunTableState state, Exception e) {
    String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    mirrorSchemaService.markTableError(task.getConfigId(), task.getSourceTable());
    finishTask(task.getId(), task.getRowsScanned(), task.getRowsApplied(), "FAILED", message);
    if (state == null) {
      return;
    }
    state.setDirtyFlag(true);
    state.setLastError(message);
    state.setRetryCount(state.getRetryCount() == null ? 1 : state.getRetryCount() + 1);
    state.setUpdatedAt(LocalDateTime.now());
    stateMapper.updateById(state);
  }

  private SyncRunTableTask createContinuationTask(SyncRunTableTask previousTask, RowCursor cursor, int batchSize) {
    LocalDateTime now = LocalDateTime.now();
    SyncRunTableTask task = new SyncRunTableTask();
    task.setRunId(previousTask.getRunId());
    task.setConfigId(previousTask.getConfigId());
    task.setStateId(previousTask.getStateId());
    task.setSourceInstance(previousTask.getSourceInstance());
    task.setSourceTable(previousTask.getSourceTable());
    task.setMirrorTable(previousTask.getMirrorTable());
    task.setTaskType(previousTask.getTaskType());
    task.setStatus(SyncRunStatus.QUEUED);
    task.setRowStrategy(previousTask.getRowStrategy());
    task.setWatermarkAt(previousTask.getWatermarkAt());
    task.setCursorUpdatedAt(cursor.updatedAt());
    task.setCursorPk(cursor.primaryKey());
    task.setLookupColumn(previousTask.getLookupColumn());
    task.setLookupValue(previousTask.getLookupValue());
    task.setBatchSize(batchSize);
    task.setRunAfter(now);
    task.setRetryCount(0);
    task.setMaxRetryCount(previousTask.getMaxRetryCount());
    task.setRowsScanned(0L);
    task.setRowsApplied(0L);
    task.setCreatedAt(now);
    task.setUpdatedAt(now);
    return task;
  }

  private TableWhitelistOption tableOption(SyncRunTableState state) {
    return new TableWhitelistOption(
        state.getSourceTable(), state.getSourceTable(), state.getPrimaryKeyColumns(), state.getUpdatedAtColumn(), true);
  }

  private RowCursor lastCursor(List<Map<String, Object>> rows, SyncRunTableState state, boolean fullTask) {
    if (rows == null || rows.isEmpty()) {
      return RowCursor.empty();
    }
    Map<String, Object> row = rows.get(rows.size() - 1);
    if (fullTask) {
      return new RowCursor(null, primaryKeySignature(state.getPrimaryKeyColumns(), row));
    }
    if (state.getUpdatedAtColumn() == null || state.getUpdatedAtColumn().isBlank()) {
      return RowCursor.empty();
    }
    LocalDateTime updatedAt = toLocalDateTime(row.get(state.getUpdatedAtColumn()));
    Object primaryKey = row.get(firstPrimaryKey(state.getPrimaryKeyColumns()));
    return new RowCursor(updatedAt, primaryKey == null ? "" : String.valueOf(primaryKey));
  }

  private LocalDateTime findFullSyncWatermark(
      GitlabSyncConfig config,
      TableWhitelistOption option,
      SyncRunTableState state) {
    if (state.getUpdatedAtColumn() == null || state.getUpdatedAtColumn().isBlank()) {
      return null;
    }
    return sourceTableReader.findMaxUpdatedAt(config, option);
  }

  private int resolveBatchSize(SyncRunTableTask task) {
    return task.getBatchSize() == null || task.getBatchSize() < 1 ? 500 : task.getBatchSize();
  }

  private String firstPrimaryKey(String primaryKeys) {
    if (primaryKeys == null || primaryKeys.isBlank()) {
      return "id";
    }
    return List.of(primaryKeys.split(",")).stream()
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .findFirst()
        .orElse("id");
  }

  private String primaryKeySignature(String primaryKeys, Map<String, Object> row) {
    return primaryKeyColumns(primaryKeys).stream()
        .map(primaryKey -> {
          Object value = row.get(primaryKey);
          return value == null ? "" : String.valueOf(value);
        })
        .reduce((left, right) -> left + "\u001F" + right)
        .orElse("");
  }

  private List<String> primaryKeyColumns(String primaryKeys) {
    if (primaryKeys == null || primaryKeys.isBlank()) {
      return List.of("id");
    }
    List<String> columns = List.of(primaryKeys.split(",")).stream()
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .toList();
    return columns.isEmpty() ? List.of("id") : columns;
  }

  private SyncRunTableTask mapTask(ResultSet rs, int rowNum) throws SQLException {
    SyncRunTableTask task = new SyncRunTableTask();
    task.setId(rs.getLong("id"));
    task.setRunId(rs.getLong("run_id"));
    task.setConfigId(rs.getLong("config_id"));
    task.setStateId(rs.getObject("state_id") == null ? null : rs.getLong("state_id"));
    task.setSourceInstance(rs.getString("source_instance"));
    task.setSourceTable(rs.getString("source_table"));
    task.setMirrorTable(rs.getString("mirror_table"));
    task.setTaskType(rs.getString("task_type"));
    task.setStatus(SyncRunStatus.valueOf(rs.getString("status")));
    task.setRowStrategy(rs.getString("row_strategy"));
    task.setWatermarkAt(toDateTime(rs.getTimestamp("watermark_at")));
    task.setCursorUpdatedAt(toDateTime(rs.getTimestamp("cursor_updated_at")));
    task.setCursorPk(rs.getString("cursor_pk"));
    task.setLookupColumn(rs.getString("lookup_column"));
    task.setLookupValue(rs.getString("lookup_value"));
    task.setBatchSize(rs.getInt("batch_size"));
    task.setRunAfter(toDateTime(rs.getTimestamp("run_after")));
    task.setLeaseOwner(rs.getString("lease_owner"));
    task.setLeaseUntil(toDateTime(rs.getTimestamp("lease_until")));
    task.setHeartbeatAt(toDateTime(rs.getTimestamp("heartbeat_at")));
    task.setRetryCount(rs.getInt("retry_count"));
    task.setMaxRetryCount(rs.getInt("max_retry_count"));
    task.setLastError(rs.getString("last_error"));
    task.setRowsScanned(rs.getLong("rows_scanned"));
    task.setRowsApplied(rs.getLong("rows_applied"));
    task.setStartedAt(toDateTime(rs.getTimestamp("started_at")));
    task.setFinishedAt(toDateTime(rs.getTimestamp("finished_at")));
    task.setCreatedAt(toDateTime(rs.getTimestamp("created_at")));
    task.setUpdatedAt(toDateTime(rs.getTimestamp("updated_at")));
    return task;
  }

  private LocalDateTime toDateTime(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toLocalDateTime();
  }

  private LocalDateTime toLocalDateTime(Object value) {
    if (value instanceof LocalDateTime localDateTime) {
      return localDateTime;
    }
    if (value instanceof Timestamp timestamp) {
      return timestamp.toLocalDateTime();
    }
    if (value instanceof OffsetDateTime offsetDateTime) {
      return offsetDateTime.toLocalDateTime();
    }
    if (value instanceof Instant instant) {
      return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
    return null;
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

  private record RowCursor(LocalDateTime updatedAt, String primaryKey) {
    static RowCursor empty() {
      return new RowCursor(null, "");
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
