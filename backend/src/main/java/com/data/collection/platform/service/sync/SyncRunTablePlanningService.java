package com.data.collection.platform.service.sync;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunTableState;
import com.data.collection.platform.entity.sync.SyncRunTableTask;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.mapper.SyncRunMapper;
import com.data.collection.platform.mapper.SyncRunTableStateMapper;
import com.data.collection.platform.mapper.SyncRunTableTaskMapper;
import com.data.collection.platform.service.GitlabConfigService;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import com.data.collection.platform.service.GitlabWhitelistService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class SyncRunTablePlanningService {
  private static final LocalDateTime INITIAL_WATERMARK = LocalDateTime.of(1970, 1, 1, 0, 0);

  private final SyncRunMapper syncRunMapper;
  private final SyncRunTableStateMapper stateMapper;
  private final SyncRunTableTaskMapper taskMapper;
  private final JsonUtils jsonUtils;
  private final GitlabConfigService configService;
  private final GitlabWhitelistService whitelistService;

  public SyncRunTablePlanningService(
      SyncRunMapper syncRunMapper,
      SyncRunTableStateMapper stateMapper,
      SyncRunTableTaskMapper taskMapper,
      JsonUtils jsonUtils,
      GitlabConfigService configService,
      GitlabWhitelistService whitelistService) {
    this.syncRunMapper = syncRunMapper;
    this.stateMapper = stateMapper;
    this.taskMapper = taskMapper;
    this.jsonUtils = jsonUtils;
    this.configService = configService;
    this.whitelistService = whitelistService;
  }

  public int planRunTables(Long runId) {
    SyncRun run = syncRunMapper.selectById(runId);
    if (run == null) {
      return 0;
    }
    Map<String, Object> payload = jsonUtils.toMap(run.getPayloadJson());
    List<String> sourceTables = extractSourceTables(payload);
    List<PreciseTarget> preciseTargets = extractPreciseTargets(payload);
    if (run.getRunType() == SyncRunType.SYSTEM_HOOK && !preciseTargets.isEmpty()) {
      return planPreciseTargets(run, preciseTargets);
    }
    if (shouldPlanFromWhitelist(run, sourceTables)) {
      return planWhitelistTables(run, sourceTables);
    }
    LocalDateTime now = LocalDateTime.now();
    int planned = 0;
    for (String sourceTable : sourceTables) {
      SyncRunTableState state = resolveRunnableState(run, sourceTable);
      SyncRunTableTask task = new SyncRunTableTask();
      task.setRunId(run.getId());
      task.setConfigId(run.getConfigId());
      task.setStateId(state.getId());
      task.setSourceInstance(run.getSourceInstance());
      task.setSourceTable(sourceTable);
      task.setMirrorTable(state.getMirrorTable());
      task.setTaskType(run.getRunType().name());
      task.setStatus(SyncRunStatus.QUEUED);
      task.setRowStrategy("INCREMENTAL");
      task.setWatermarkAt(state.getLastWatermarkAt());
      task.setBatchSize(500);
      task.setRunAfter(now);
      task.setRetryCount(0);
      task.setMaxRetryCount(3);
      task.setRowsScanned(0L);
      task.setRowsApplied(0L);
      task.setCreatedAt(now);
      task.setUpdatedAt(now);
      taskMapper.insert(task);
      planned++;
    }
    log.info("Planned {} table tasks for run {}", planned, runId);
    return planned;
  }

  private boolean shouldPlanFromWhitelist(SyncRun run, List<String> sourceTables) {
    if (run.getRunType() == SyncRunType.FULL_SYNC || run.getRunType() == SyncRunType.INCREMENTAL_SYNC) {
      return true;
    }
    return sourceTables.isEmpty()
        && (run.getRunType() == SyncRunType.COMPENSATION_SCAN || run.getRunType() == SyncRunType.SYSTEM_HOOK);
  }

  private int planWhitelistTables(SyncRun run, List<String> requestedTables) {
    GitlabSyncConfig config = configService.getConfigById(run.getConfigId());
    List<TableWhitelistOption> options = whitelistService.resolveOptions(config);
    if (requestedTables != null && !requestedTables.isEmpty()) {
      options =
          options.stream()
              .filter(option -> requestedTables.contains(GitlabSourceInstanceSupport.normalizeSourceTableName(option.tableName())))
              .toList();
    }
    LocalDateTime now = LocalDateTime.now();
    int planned = 0;
    boolean fullSync = run.getRunType() == SyncRunType.FULL_SYNC;
    for (TableWhitelistOption option : options) {
      if (!isRunnableForRun(fullSync, option)) {
        log.info("Skipped table without runnable key columns, runId={}, sourceTable={}", run.getId(), option.tableName());
        continue;
      }
      SyncRunTableState state = upsertState(run, config, option, now);
      taskMapper.insert(createTask(run, state, resolveTaskWatermark(run, state), now));
      planned++;
    }
    log.info("Planned {} whitelist table tasks for run {}", planned, run.getId());
    return planned;
  }

  private int planPreciseTargets(SyncRun run, List<PreciseTarget> targets) {
    GitlabSyncConfig config = configService.getConfigById(run.getConfigId());
    Map<String, TableWhitelistOption> optionsByTable =
        whitelistService.resolveOptions(config).stream()
            .collect(
                Collectors.toMap(
                    option -> GitlabSourceInstanceSupport.normalizeSourceTableName(option.tableName()),
                    option -> option,
                    (first, ignored) -> first));
    LocalDateTime now = LocalDateTime.now();
    int planned = 0;
    for (PreciseTarget target : targets) {
      TableWhitelistOption option = optionsByTable.get(target.tableName());
      if (option == null || isBlank(option.primaryKey()) || isBlank(target.lookupColumn()) || isBlank(target.lookupValue())) {
        log.info("Skipped precise target without runnable lookup, runId={}, sourceTable={}", run.getId(), target.tableName());
        continue;
      }
      SyncRunTableState state = upsertState(run, config, option, now);
      SyncRunTableTask task = createTask(run, state, INITIAL_WATERMARK, now);
      task.setRowStrategy("PRECISE");
      task.setLookupColumn(target.lookupColumn());
      task.setLookupValue(target.lookupValue());
      taskMapper.insert(task);
      planned++;
    }
    log.info("Planned {} precise table tasks for run {}", planned, run.getId());
    return planned;
  }

  private boolean isIncrementalCapable(TableWhitelistOption option) {
    return option != null && !isBlank(option.primaryKey()) && !isBlank(option.updatedAtColumn());
  }

  private boolean isRunnableForRun(boolean fullSync, TableWhitelistOption option) {
    if (option == null || isBlank(option.primaryKey())) {
      return false;
    }
    return fullSync || !isBlank(option.updatedAtColumn());
  }

  private SyncRunTableState upsertState(
      SyncRun run,
      GitlabSyncConfig config,
      TableWhitelistOption option,
      LocalDateTime now) {
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    String sourceTable = GitlabSourceInstanceSupport.normalizeSourceTableName(option.tableName());
    SyncRunTableState state =
        stateMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SyncRunTableState>()
            .eq(SyncRunTableState::getConfigId, run.getConfigId())
            .eq(SyncRunTableState::getSourceInstance, sourceInstance)
            .eq(SyncRunTableState::getSourceTable, sourceTable)
            .last("limit 1"));
    if (state == null) {
      state = new SyncRunTableState();
      state.setConfigId(run.getConfigId());
      state.setSourceInstance(sourceInstance);
      state.setSourceTable(sourceTable);
      state.setMirrorTable(GitlabSourceInstanceSupport.buildMirrorTableName(sourceTable, sourceInstance));
      state.setPrimaryKeyColumns(option.primaryKey());
      state.setUpdatedAtColumn(option.updatedAtColumn());
      state.setRowStrategy(rowStrategyForState(run, option));
      state.setSyncEnabled(true);
      state.setDirtyFlag(false);
      state.setRetryCount(0);
      state.setCreatedAt(now);
      state.setUpdatedAt(now);
      stateMapper.insert(state);
      return state;
    }
    state.setMirrorTable(GitlabSourceInstanceSupport.buildMirrorTableName(sourceTable, sourceInstance));
    state.setPrimaryKeyColumns(option.primaryKey());
    state.setUpdatedAtColumn(option.updatedAtColumn());
    state.setRowStrategy(rowStrategyForState(run, option));
    state.setSyncEnabled(true);
    state.setUpdatedAt(now);
    stateMapper.updateById(state);
    return state;
  }

  private SyncRunTableTask createTask(
      SyncRun run,
      SyncRunTableState state,
      LocalDateTime watermark,
      LocalDateTime now) {
    SyncRunTableTask task = new SyncRunTableTask();
    task.setRunId(run.getId());
    task.setConfigId(run.getConfigId());
    task.setStateId(state.getId());
    task.setSourceInstance(state.getSourceInstance());
    task.setSourceTable(state.getSourceTable());
    task.setMirrorTable(state.getMirrorTable());
    task.setTaskType(run.getRunType().name());
    task.setStatus(SyncRunStatus.QUEUED);
    task.setRowStrategy(rowStrategyForTask(run));
    task.setWatermarkAt(watermark);
    task.setBatchSize(500);
    task.setRunAfter(now);
    task.setRetryCount(0);
    task.setMaxRetryCount(3);
    task.setRowsScanned(0L);
    task.setRowsApplied(0L);
    task.setCreatedAt(now);
    task.setUpdatedAt(now);
    return task;
  }

  private LocalDateTime resolveTaskWatermark(SyncRun run, SyncRunTableState state) {
    if (run.getRunType() == SyncRunType.FULL_SYNC) {
      return INITIAL_WATERMARK;
    }
    return state.getLastWatermarkAt() == null ? INITIAL_WATERMARK : state.getLastWatermarkAt();
  }

  private String rowStrategyForTask(SyncRun run) {
    return run.getRunType() == SyncRunType.FULL_SYNC ? "FULL" : "INCREMENTAL";
  }

  private String rowStrategyForState(SyncRun run, TableWhitelistOption option) {
    if (isBlank(option.updatedAtColumn())) {
      return "FULL_ONLY";
    }
    return "INCREMENTAL";
  }

  private SyncRunTableState resolveRunnableState(SyncRun run, String sourceTable) {
    SyncRunTableState state =
        stateMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SyncRunTableState>()
            .eq(SyncRunTableState::getConfigId, run.getConfigId())
            .eq(SyncRunTableState::getSourceInstance, run.getSourceInstance())
            .eq(SyncRunTableState::getSourceTable, sourceTable)
            .eq(SyncRunTableState::getSyncEnabled, true)
            .last("limit 1"));
    if (state == null) {
      throw new BizException("Manual table refresh cannot run before mirror table state exists: " + sourceTable);
    }
    if (isBlank(state.getPrimaryKeyColumns())) {
      throw new BizException("Manual table refresh requires known primary key columns: " + sourceTable);
    }
    if (isBlank(state.getUpdatedAtColumn())) {
      throw new BizException("Manual table refresh requires an updated_at column: " + sourceTable);
    }
    if (state.getLastWatermarkAt() == null) {
      throw new BizException("Manual table refresh requires a completed full sync baseline first: " + sourceTable);
    }
    return state;
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private List<String> extractSourceTables(Map<String, Object> payload) {
    Object rawTables = payload.get("sourceTables");
    if (!(rawTables instanceof List<?> tables) || tables.isEmpty()) {
      return List.of();
    }
    return tables.stream()
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .map(GitlabSourceInstanceSupport::normalizeSourceTableName)
        .filter(table -> !table.isBlank())
        .distinct()
        .toList();
  }

  private List<PreciseTarget> extractPreciseTargets(Map<String, Object> payload) {
    Object rawTargets = payload.get("preciseTargets");
    if (!(rawTargets instanceof List<?> targets) || targets.isEmpty()) {
      return List.of();
    }
    return targets.stream()
        .filter(Map.class::isInstance)
        .map(Map.class::cast)
        .map(
            target ->
                new PreciseTarget(
                    GitlabSourceInstanceSupport.normalizeSourceTableName(String.valueOf(target.get("tableName"))),
                    String.valueOf(target.get("lookupColumn")),
                    String.valueOf(target.get("lookupValue"))))
        .filter(target -> !target.tableName().isBlank() && !target.lookupColumn().isBlank() && !target.lookupValue().isBlank())
        .distinct()
        .toList();
  }

  private record PreciseTarget(String tableName, String lookupColumn, String lookupValue) {}
}
