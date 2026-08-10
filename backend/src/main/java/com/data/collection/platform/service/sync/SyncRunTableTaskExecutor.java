package com.data.collection.platform.service.sync;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.MirrorPrimaryKeyBatch;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.entity.sync.SyncRunTableState;
import com.data.collection.platform.entity.sync.SyncRunTableTask;
import com.data.collection.platform.entity.sync.SyncRunTableTaskStage;
import com.data.collection.platform.mapper.SyncRunTableStateMapper;
import com.data.collection.platform.service.GitlabConfigService;
import com.data.collection.platform.service.GitlabMirrorSchemaService;
import com.data.collection.platform.service.PrimaryKeySignatureSupport;
import java.math.BigInteger;
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

  private final SyncRunTableStateMapper stateMapper;
  private final JsonUtils jsonUtils;
  private final SyncRunTableTaskLeaseService taskLeaseService;
  private final SyncRunTableTaskHeartbeatService heartbeatService;
  private final SyncRunTablePageCommitService pageCommitService;
  private final GitlabConfigService configService;
  private final SourceTableReader sourceTableReader;
  private final GitlabMirrorSchemaService mirrorSchemaService;
  private final MirrorTableWriter mirrorTableWriter;
  private final SyncTableTaskFailurePolicy failurePolicy;

  public SyncRunTableTaskExecutor(
      SyncRunTableStateMapper stateMapper,
      JsonUtils jsonUtils,
      SyncRunTableTaskLeaseService taskLeaseService,
      SyncRunTableTaskHeartbeatService heartbeatService,
      SyncRunTablePageCommitService pageCommitService,
      GitlabConfigService configService,
      SourceTableReader sourceTableReader,
      GitlabMirrorSchemaService mirrorSchemaService,
      MirrorTableWriter mirrorTableWriter,
      SyncTableTaskFailurePolicy failurePolicy) {
    this.stateMapper = stateMapper;
    this.jsonUtils = jsonUtils;
    this.taskLeaseService = taskLeaseService;
    this.heartbeatService = heartbeatService;
    this.pageCommitService = pageCommitService;
    this.configService = configService;
    this.sourceTableReader = sourceTableReader;
    this.mirrorSchemaService = mirrorSchemaService;
    this.mirrorTableWriter = mirrorTableWriter;
    this.failurePolicy = failurePolicy;
  }

  public void executeTask(SyncRunTableTask task) {
    SyncRunTableState state = findState(task);
    try (SyncRunTableTaskHeartbeatService.LeaseGuard leaseGuard = heartbeatService.monitor(task)) {
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
      GitlabSourceLineageCatalog.validatePhysicalSchema(
          task.getSourceTable(), preparedMirrorTable.mirrorSchema());
      mirrorSchemaService.markTableSyncing(config.getId(), state.getSourceTable());
      if (isRunCancellationRequested(task.getRunId())) {
        finishOwnedOrThrow(task, 0L, 0L, "CANCELLED", "同步运行已取消");
        mirrorSchemaService.markTableIdle(config.getId(), state.getSourceTable(), LocalDateTime.now());
        return;
      }

      int batchSize = resolveBatchSize(task);
      if (task.getTaskStage() == SyncRunTableTaskStage.RECONCILE) {
        executeReconciliationPage(
            task,
            state,
            config,
            option,
            preparedMirrorTable.mirrorSchema(),
            batchSize,
            leaseGuard);
        mirrorSchemaService.markTableIdle(config.getId(), state.getSourceTable(), LocalDateTime.now());
        return;
      }
      LocalDateTime scanStart = task.getWatermarkAt() == null ? INITIAL_WATERMARK : task.getWatermarkAt();
      boolean fullReconcileTask = "FULL_RECONCILE".equalsIgnoreCase(task.getRowStrategy());
      boolean preciseTask = "PRECISE".equalsIgnoreCase(task.getRowStrategy());
      boolean monotonicPrimaryKeyTask =
          "MONOTONIC_PRIMARY_KEY".equalsIgnoreCase(task.getRowStrategy());
      if ("RECONCILE_ONLY".equalsIgnoreCase(task.getRowStrategy())) {
        throw new IllegalStateException(
            "仅删除反熵来源不能创建普通扫描任务：" + task.getSourceTable());
      }
      Map<String, Object> lookupScope = preciseTask
          ? jsonUtils.toMap(task.getLookupScopeJson())
          : Map.of();
      if (preciseTask && lookupScope.isEmpty()) {
        throw new IllegalStateException("精确任务缺少完整范围：" + task.getSourceTable());
      }
      boolean scopedTask = preciseTask;
      if (!fullReconcileTask
          && !scopedTask
          && !monotonicPrimaryKeyTask
          && !"UPDATED_AT".equalsIgnoreCase(state.getRowStrategy())
          && !"INCREMENTAL".equalsIgnoreCase(state.getRowStrategy())) {
        throw new IllegalStateException("当前表任务不能由增量同步执行器处理");
      }
      LocalDateTime cursorUpdatedAt = task.getCursorUpdatedAt();
      String cursorPk = task.getCursorPk();
      if (monotonicPrimaryKeyTask && normalizeCursor(cursorPk).equals("[]")) {
        cursorPk = normalizeCursor(
            mirrorTableWriter.findMaxActivePrimaryKeyCursor(
                preparedMirrorTable.mirrorSchema()));
        task.setCursorPk(cursorPk);
      }
      LocalDateTime scanUpperBound = scopedTask
          ? null
          : monotonicPrimaryKeyTask || fullReconcileTask
              ? null
              : resolveScanUpperBound(task, state, config, option, leaseGuard);
      String scanUpperBoundPk =
          monotonicPrimaryKeyTask
              ? resolveScanUpperBoundPk(task, config, option, leaseGuard)
              : null;
      if (!fullReconcileTask
          && !scopedTask
          && !monotonicPrimaryKeyTask
          && !scanUpperBound.isAfter(scanStart)) {
        leaseGuard.requireOwnership();
        pageCommitService.completeUnchangedTask(task, state, scanUpperBound, "");
        mirrorSchemaService.markTableIdle(config.getId(), state.getSourceTable(), LocalDateTime.now());
        return;
      }
      int monotonicCursorOrder =
          monotonicPrimaryKeyTask
              ? compareMonotonicCursors(cursorPk, scanUpperBoundPk)
              : -1;
      if (monotonicPrimaryKeyTask && monotonicCursorOrder > 0) {
        throw new IllegalStateException(
            "单调主键来源上界发生回退：" + task.getSourceTable());
      }
      if (monotonicPrimaryKeyTask && monotonicCursorOrder == 0) {
        leaseGuard.requireOwnership();
        pageCommitService.completeUnchangedTask(task, state, null, scanUpperBoundPk);
        mirrorSchemaService.markTableIdle(config.getId(), state.getSourceTable(), LocalDateTime.now());
        return;
      }
      List<Map<String, Object>> rows =
          fullReconcileTask
              ? sourceTableReader.readFullBatch(
                  config, option, preparedMirrorTable.mirrorSchema(), task.getCursorPk(), batchSize)
              : scopedTask
                  ? sourceTableReader.readPrecise(config, option, lookupScope)
                  : monotonicPrimaryKeyTask
                      ? sourceTableReader.readMonotonicPrimaryKeyBatch(
                          config,
                          option,
                          preparedMirrorTable.mirrorSchema(),
                          cursorPk,
                          scanUpperBoundPk,
                          batchSize)
                      : sourceTableReader.readIncrementalBatch(
                      config,
                      option,
                      preparedMirrorTable.mirrorSchema(),
                      scanStart,
                      scanUpperBound,
                      cursorUpdatedAt,
                      cursorPk,
                      batchSize);
      if (isRunCancellationRequested(task.getRunId())) {
        finishOwnedOrThrow(task, 0L, 0L, "CANCELLED", "同步运行已取消");
        mirrorSchemaService.markTableIdle(config.getId(), state.getSourceTable(), LocalDateTime.now());
        return;
      }
      RowCursor lastCursor =
          lastCursor(
              rows,
              preparedMirrorTable.mirrorSchema(),
              state,
              fullReconcileTask,
              monotonicPrimaryKeyTask);
      boolean hasMore =
          !scopedTask
              && rows.size() >= batchSize
              && !lastCursor.primaryKey().isBlank();
      RowCursor completionCursor = !scopedTask && !hasMore
          ? monotonicPrimaryKeyTask
              ? new RowCursor(null, scanUpperBoundPk)
              : new RowCursor(scanUpperBound, "")
          : lastCursor;
      leaseGuard.requireOwnership();
      pageCommitService.commitScanPage(
          task,
          state,
          preparedMirrorTable.mirrorSchema(),
          rows,
          completionCursor.updatedAt(),
          completionCursor.primaryKey(),
          batchSize,
          hasMore,
          fullReconcileTask,
          fullReconcileTask);
      mirrorSchemaService.markTableIdle(config.getId(), state.getSourceTable(), LocalDateTime.now());
    } catch (SyncTaskLeaseLostException e) {
      log.info(
          "Stopped table task after lease ownership changed, taskId={}, sourceTable={}",
          task.getId(),
          task.getSourceTable());
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

  private void executeReconciliationPage(
      SyncRunTableTask task,
      SyncRunTableState state,
      GitlabSyncConfig config,
      TableWhitelistOption option,
      com.data.collection.platform.entity.SourceTableSchema mirrorSchema,
      int batchSize,
      SyncRunTableTaskHeartbeatService.LeaseGuard leaseGuard) {
    MirrorPrimaryKeyBatch batch =
        mirrorTableWriter.listActivePrimaryKeys(mirrorSchema, task.getCursorPk(), batchSize);
    List<Map<String, Object>> keys = batch.keys();
    Set<String> existingSourceKeys =
        keys.isEmpty()
            ? Set.of()
            : sourceTableReader.findExistingPrimaryKeySignatures(
                config, option, mirrorSchema, keys);
    List<Map<String, Object>> mirrorOnlyRows =
        keys.stream()
            .filter(
                keyRow ->
                    !existingSourceKeys.contains(
                        PrimaryKeySignatureSupport.signature(mirrorSchema.primaryKeys(), keyRow)))
            .toList();
    leaseGuard.requireOwnership();
    pageCommitService.commitReconciliationPage(
        task,
        state,
        mirrorSchema,
        mirrorOnlyRows,
        keys.size(),
        batch.nextCursor(),
        batchSize);
  }

  private boolean isRunCancellationRequested(Long runId) {
    return taskLeaseService.isRunCancellationRequested(runId);
  }

  private boolean finishTask(
      SyncRunTableTask task,
      Long rowsScanned,
      Long rowsApplied,
      String status,
      String errorMessage) {
    return taskLeaseService.finishOwnedTask(
        task.getId(), task.getLeaseOwner(), rowsScanned, rowsApplied, status, errorMessage);
  }

  private void finishOwnedOrThrow(
      SyncRunTableTask task,
      Long rowsScanned,
      Long rowsApplied,
      String status,
      String errorMessage) {
    if (!finishTask(task, rowsScanned, rowsApplied, status, errorMessage)) {
      throw new SyncTaskLeaseLostException(task.getId());
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

  private void markFailure(SyncRunTableTask task, SyncRunTableState state, Exception e) {
    String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    SyncTableTaskFailurePolicy.Decision decision =
        failurePolicy.evaluate(
            e,
            task.getRetryCount() == null ? 0 : task.getRetryCount(),
            task.getMaxRetryCount() == null ? 0 : task.getMaxRetryCount(),
            LocalDateTime.now());
    if (decision.retryable()
        && taskLeaseService.deferOwnedTask(
            task.getId(),
            task.getLeaseOwner(),
            counter(task.getRowsScanned()),
            counter(task.getRowsApplied()),
            decision.runAfter(),
            message)) {
      mirrorSchemaService.markTableError(task.getConfigId(), task.getSourceTable());
      log.info(
          "Deferred transient table task failure, taskId={}, sourceTable={}, retryCount={}, runAfter={}",
          task.getId(),
          task.getSourceTable(),
          task.getRetryCount() == null ? 1 : task.getRetryCount() + 1,
          decision.runAfter());
      return;
    }
    boolean owned = finishTask(task, task.getRowsScanned(), task.getRowsApplied(), "FAILED", message);
    if (!owned) {
      return;
    }
    mirrorSchemaService.markTableError(task.getConfigId(), task.getSourceTable());
    if (state == null) {
      return;
    }
    state.setDirtyFlag(true);
    state.setLastError(message);
    state.setRetryCount(state.getRetryCount() == null ? 1 : state.getRetryCount() + 1);
    state.setUpdatedAt(LocalDateTime.now());
    stateMapper.updateById(state);
  }

  private long counter(Long value) {
    return value == null ? 0L : value;
  }

  private TableWhitelistOption tableOption(SyncRunTableState state) {
    return new TableWhitelistOption(
        state.getSourceTable(),
        state.getSourceTable(),
        state.getPrimaryKeyColumns(),
        state.getUpdatedAtColumn(),
        state.getCursorStrategy(),
        true);
  }

  private RowCursor lastCursor(
      List<Map<String, Object>> rows,
      com.data.collection.platform.entity.SourceTableSchema mirrorSchema,
      SyncRunTableState state,
      boolean fullTask,
      boolean monotonicPrimaryKeyTask) {
    if (rows == null || rows.isEmpty()) {
      return RowCursor.empty();
    }
    Map<String, Object> row = rows.get(rows.size() - 1);
    String primaryKeyCursor = PrimaryKeySignatureSupport.encodeCursor(
        jsonUtils, PrimaryKeySignatureSupport.primaryKeyColumns(mirrorSchema), row);
    if (fullTask || monotonicPrimaryKeyTask) {
      return new RowCursor(null, primaryKeyCursor);
    }
    if (state.getUpdatedAtColumn() == null || state.getUpdatedAtColumn().isBlank()) {
      return RowCursor.empty();
    }
    LocalDateTime updatedAt = toLocalDateTime(row.get(state.getUpdatedAtColumn()));
    return new RowCursor(updatedAt, primaryKeyCursor);
  }

  private LocalDateTime resolveScanUpperBound(
      SyncRunTableTask task,
      SyncRunTableState state,
      GitlabSyncConfig config,
      TableWhitelistOption option,
      SyncRunTableTaskHeartbeatService.LeaseGuard leaseGuard) {
    if (task.getScanUpperBoundAt() != null) {
      return task.getScanUpperBoundAt();
    }
    if (state.getUpdatedAtColumn() == null || state.getUpdatedAtColumn().isBlank()) {
      return null;
    }
    LocalDateTime upperBound = sourceTableReader.findMaxUpdatedAt(config, option);
    if (upperBound == null) {
      upperBound = task.getWatermarkAt() == null ? INITIAL_WATERMARK : task.getWatermarkAt();
    } else if (task.getWatermarkAt() != null && upperBound.isBefore(task.getWatermarkAt())) {
      upperBound = task.getWatermarkAt();
    }
    leaseGuard.requireOwnership();
    if (!taskLeaseService.initializeOwnedScanUpperBound(
        task.getId(), task.getLeaseOwner(), upperBound)) {
      throw new SyncTaskLeaseLostException(task.getId());
    }
    task.setScanUpperBoundAt(upperBound);
    return upperBound;
  }

  private String resolveScanUpperBoundPk(
      SyncRunTableTask task,
      GitlabSyncConfig config,
      TableWhitelistOption option,
      SyncRunTableTaskHeartbeatService.LeaseGuard leaseGuard) {
    if (task.getScanUpperBoundPk() != null && !task.getScanUpperBoundPk().isBlank()) {
      return task.getScanUpperBoundPk();
    }
    String upperBound = sourceTableReader.findMaxPrimaryKeyCursor(config, option);
    if (upperBound == null || upperBound.isBlank()) {
      upperBound = "[]";
    }
    leaseGuard.requireOwnership();
    if (!taskLeaseService.initializeOwnedScanUpperBoundPk(
        task.getId(), task.getLeaseOwner(), upperBound)) {
      throw new SyncTaskLeaseLostException(task.getId());
    }
    task.setScanUpperBoundPk(upperBound);
    return upperBound;
  }

  private String normalizeCursor(String cursor) {
    return cursor == null || cursor.isBlank() ? "[]" : cursor;
  }

  private int compareMonotonicCursors(String currentCursor, String upperBoundCursor) {
    List<String> currentValues =
        PrimaryKeySignatureSupport.decodeCursor(jsonUtils, normalizeCursor(currentCursor));
    List<String> upperBoundValues =
        PrimaryKeySignatureSupport.decodeCursor(jsonUtils, normalizeCursor(upperBoundCursor));
    if (currentValues.isEmpty() && upperBoundValues.isEmpty()) {
      return 0;
    }
    if (currentValues.isEmpty()) {
      return -1;
    }
    if (upperBoundValues.isEmpty()) {
      return 1;
    }
    if (currentValues.size() != 1 || upperBoundValues.size() != 1) {
      throw new IllegalStateException("单调主键游标必须包含一个整数值");
    }
    try {
      return new BigInteger(currentValues.getFirst())
          .compareTo(new BigInteger(upperBoundValues.getFirst()));
    } catch (NumberFormatException error) {
      throw new IllegalStateException("单调主键游标不是有效整数", error);
    }
  }

  private int resolveBatchSize(SyncRunTableTask task) {
    return task.getBatchSize() == null || task.getBatchSize() < 1 ? 500 : task.getBatchSize();
  }

  private LocalDateTime toLocalDateTime(Object value) {
    if (value instanceof LocalDateTime localDateTime) {
      return localDateTime;
    }
    if (value instanceof Timestamp timestamp) {
      return timestamp.toLocalDateTime();
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

}
