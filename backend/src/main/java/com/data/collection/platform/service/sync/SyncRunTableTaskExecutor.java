package com.data.collection.platform.service.sync;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.MirrorBatchWriteResult;
import com.data.collection.platform.entity.MirrorPrimaryKeyBatch;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunTableState;
import com.data.collection.platform.entity.sync.SyncRunTableTask;
import com.data.collection.platform.mapper.SyncRunTableStateMapper;
import com.data.collection.platform.mapper.SyncRunTableTaskMapper;
import com.data.collection.platform.service.GitlabConfigService;
import com.data.collection.platform.service.GitlabMirrorSchemaService;
import com.data.collection.platform.service.PrimaryKeySignatureSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class SyncRunTableTaskExecutor {
  private static final LocalDateTime INITIAL_WATERMARK = LocalDateTime.of(1970, 1, 1, 0, 0);
  private static final int DEFAULT_MAX_CONTINUATION_TASKS_PER_TABLE = 50000;

  private final SyncRunTableTaskMapper taskMapper;
  private final SyncRunTableStateMapper stateMapper;
  private final SyncRunTableTaskLeaseService taskLeaseService;
  private final GitlabConfigService configService;
  private final SourceTableReader sourceTableReader;
  private final GitlabMirrorSchemaService mirrorSchemaService;
  private final MirrorTableWriter mirrorTableWriter;
  private final GitlabMirrorProperties mirrorProperties;

  public SyncRunTableTaskExecutor(
      SyncRunTableTaskMapper taskMapper,
      SyncRunTableStateMapper stateMapper,
      SyncRunTableTaskLeaseService taskLeaseService,
      GitlabConfigService configService,
      SourceTableReader sourceTableReader,
      GitlabMirrorSchemaService mirrorSchemaService,
      MirrorTableWriter mirrorTableWriter,
      GitlabMirrorProperties mirrorProperties) {
    this.taskMapper = taskMapper;
    this.stateMapper = stateMapper;
    this.taskLeaseService = taskLeaseService;
    this.configService = configService;
    this.sourceTableReader = sourceTableReader;
    this.mirrorSchemaService = mirrorSchemaService;
    this.mirrorTableWriter = mirrorTableWriter;
    this.mirrorProperties = mirrorProperties;
  }

  public void executeTask(SyncRunTableTask task) {
    SyncRunTableState state = findState(task);
    try {
      if (state == null) {
        throw new IllegalStateException("表同步状态缺失");
      }
      if (!Boolean.TRUE.equals(state.getSyncEnabled())) {
        throw new IllegalStateException("表同步状态已停用");
      }
      GitlabSyncConfig config = configService.getConfigById(task.getConfigId());
      TableWhitelistOption option = tableOption(state);
      GitlabMirrorSchemaService.PreparedMirrorTable preparedMirrorTable =
          mirrorSchemaService.getPreparedMirrorTableForSync(config, option);
      mirrorSchemaService.markTableSyncing(config.getId(), state.getSourceTable());
      if (isRunCancellationRequested(task.getRunId())) {
        finishTask(task.getId(), 0L, 0L, "CANCELLED", "同步运行已取消");
        mirrorSchemaService.markTableIdle(config.getId(), state.getSourceTable(), LocalDateTime.now());
        return;
      }

      int batchSize = resolveBatchSize(task);
      LocalDateTime scanStart = task.getWatermarkAt() == null ? INITIAL_WATERMARK : task.getWatermarkAt();
      boolean fullTask = "FULL".equalsIgnoreCase(task.getRowStrategy());
      boolean fullReconcileTask = "FULL_RECONCILE".equalsIgnoreCase(task.getRowStrategy());
      boolean preciseTask = "PRECISE".equalsIgnoreCase(task.getRowStrategy());
      if (!fullTask && !fullReconcileTask && !preciseTask && !"INCREMENTAL".equalsIgnoreCase(state.getRowStrategy())) {
        throw new IllegalStateException("当前表任务不能由增量同步执行器处理");
      }
      if (!fullTask && !fullReconcileTask && !preciseTask && task.getCursorUpdatedAt() == null && task.getCursorPk() == null) {
        LocalDateTime sourceMaxUpdatedAt = sourceTableReader.findMaxUpdatedAt(config, option);
        if (sourceMaxUpdatedAt != null
            && state.getLastWatermarkAt() != null
            && !sourceMaxUpdatedAt.isAfter(state.getLastWatermarkAt())) {
          markSuccess(task, state, 0, 0, new RowCursor(state.getLastWatermarkAt(), ""), false);
          mirrorSchemaService.markTableIdle(config.getId(), state.getSourceTable(), LocalDateTime.now());
          return;
        }
      }
      List<Map<String, Object>> rows =
          fullTask || fullReconcileTask
              ? sourceTableReader.readFullBatch(
                  config, option, preparedMirrorTable.mirrorSchema(), task.getCursorPk(), batchSize)
              : preciseTask
                  ? sourceTableReader.readPrecise(config, option, task.getLookupColumn(), task.getLookupValue())
                  : sourceTableReader.readIncrementalBatch(
                      config, option, scanStart, task.getCursorUpdatedAt(), task.getCursorPk(), batchSize);
      if (isRunCancellationRequested(task.getRunId())) {
        finishTask(task.getId(), 0L, 0L, "CANCELLED", "同步运行已取消");
        mirrorSchemaService.markTableIdle(config.getId(), state.getSourceTable(), LocalDateTime.now());
        return;
      }
      MirrorBatchWriteResult writeResult =
          fullReconcileTask
              ? mirrorTableWriter.writeBatch(preparedMirrorTable.mirrorSchema(), rows, task.getId(), true)
              : mirrorTableWriter.writeBatch(preparedMirrorTable.mirrorSchema(), rows, task.getId());
      if (isRunCancellationRequested(task.getRunId())) {
        finishTask(task.getId(), (long) rows.size(), (long) writeResult.appliedRows(), "CANCELLED", "同步运行已取消");
        mirrorSchemaService.markTableIdle(config.getId(), state.getSourceTable(), LocalDateTime.now());
        return;
      }
      RowCursor lastCursor = lastCursor(rows, state, fullTask || fullReconcileTask);
      boolean hasMore =
          !preciseTask
              && rows.size() >= batchSize
              && ((fullTask || fullReconcileTask) ? !lastCursor.primaryKey().isBlank() : lastCursor.updatedAt() != null);
      long scannedRows = rows.size();
      long appliedRows = writeResult.appliedRows();
      if (fullReconcileTask && !hasMore) {
        ReconciliationResult reconciliationResult =
            reconcileMirrorExtras(config, option, preparedMirrorTable.mirrorSchema(), task, batchSize);
        scannedRows += reconciliationResult.scannedRows();
        appliedRows += reconciliationResult.deletedRows();
      }
      RowCursor completionCursor = (fullTask || fullReconcileTask) && !hasMore
          ? new RowCursor(findFullSyncWatermark(config, option, state), lastCursor.primaryKey())
          : lastCursor;
      markSuccess(task, state, scannedRows, appliedRows, completionCursor, hasMore);
      if (hasMore && !isRunCancellationRequested(task.getRunId())) {
        int maxContinuationTasks = resolveMaxContinuationTasksPerTable();
        long existingTaskCount = taskMapper.selectCount(
            new LambdaQueryWrapper<SyncRunTableTask>()
                .eq(SyncRunTableTask::getRunId, task.getRunId())
                .eq(SyncRunTableTask::getSourceTable, task.getSourceTable()));
        if (existingTaskCount >= maxContinuationTasks) {
          log.error("Exceeded max continuation tasks ({}) for table {}, runId={}, aborting further pagination",
              maxContinuationTasks, task.getSourceTable(), task.getRunId());
          markFailure(task, state, new BizException(
              "表 %2$s 超过连续分页任务上限（%1$d）".formatted(
                  maxContinuationTasks, task.getSourceTable())));
          return;
        }
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

  private boolean isRunCancellationRequested(Long runId) {
    return taskLeaseService.isRunCancellationRequested(runId);
  }

  private void finishTask(Long taskId, Long rowsScanned, Long rowsApplied, String status, String errorMessage) {
    taskLeaseService.finishTask(taskId, rowsScanned, rowsApplied, status, errorMessage);
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
      long scannedRows,
      long appliedRows,
      RowCursor lastCursor,
      boolean hasMore) {
    LocalDateTime now = LocalDateTime.now();
    finishTask(task.getId(), scannedRows, appliedRows, "SUCCESS", null);

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

  private ReconciliationResult reconcileMirrorExtras(
      GitlabSyncConfig config,
      TableWhitelistOption option,
      com.data.collection.platform.entity.SourceTableSchema mirrorSchema,
      SyncRunTableTask task,
      int batchSize) {
    String cursor = null;
    long scannedRows = 0L;
    long deletedRows = 0L;
    do {
      if (isRunCancellationRequested(task.getRunId())) {
        return new ReconciliationResult(scannedRows, deletedRows);
      }
      MirrorPrimaryKeyBatch batch = mirrorTableWriter.listActivePrimaryKeys(mirrorSchema, cursor, batchSize);
      if (batch == null || batch.keys() == null || batch.keys().isEmpty()) {
        return new ReconciliationResult(scannedRows, deletedRows);
      }
      scannedRows += batch.keys().size();
      Set<String> existingSourceKeys = sourceTableReader.findExistingPrimaryKeySignatures(config, option, batch.keys());
      List<Map<String, Object>> mirrorOnlyRows =
          batch.keys().stream()
              .filter(keyRow -> !existingSourceKeys.contains(PrimaryKeySignatureSupport.signature(mirrorSchema.primaryKeys(), keyRow)))
              .toList();
      if (!mirrorOnlyRows.isEmpty()) {
        deletedRows += mirrorTableWriter.markRowsDeletedByPrimaryKeys(mirrorSchema, mirrorOnlyRows, task.getId());
      }
      cursor = batch.nextCursor();
    } while (cursor != null && !cursor.isBlank());
    return new ReconciliationResult(scannedRows, deletedRows);
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

  private int resolveMaxContinuationTasksPerTable() {
    if (mirrorProperties == null) {
      return DEFAULT_MAX_CONTINUATION_TASKS_PER_TABLE;
    }
    int configured = mirrorProperties.getMaxContinuationTasksPerTable();
    return configured > 0 ? configured : DEFAULT_MAX_CONTINUATION_TASKS_PER_TABLE;
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
        .reduce((left, right) -> left + "" + right)
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

  private LocalDateTime toLocalDateTime(Object value) {
    if (value instanceof LocalDateTime localDateTime) {
      return localDateTime;
    }
    if (value instanceof Timestamp timestamp) {
      return timestamp.toInstant().atOffset(ZoneOffset.UTC).toLocalDateTime();
    }
    if (value instanceof OffsetDateTime offsetDateTime) {
      return offsetDateTime.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
    if (value instanceof Instant instant) {
      return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
    return null;
  }

  private record RowCursor(LocalDateTime updatedAt, String primaryKey) {
    static RowCursor empty() {
      return new RowCursor(null, "");
    }
  }

  private record ReconciliationResult(long scannedRows, long deletedRows) {
  }
}
