package com.data.collection.platform.service.sync;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.entity.sync.IncrementalReadMode;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunTableState;
import com.data.collection.platform.entity.sync.SyncRunTableTask;
import com.data.collection.platform.entity.sync.SyncRunTableTaskStage;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.mapper.SyncRunMapper;
import com.data.collection.platform.mapper.SyncRunTableStateMapper;
import com.data.collection.platform.mapper.SyncRunTableTaskMapper;
import com.data.collection.platform.service.GitlabConfigService;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import com.data.collection.platform.service.GitlabWhitelistService;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
  private final GitlabMirrorProperties mirrorProperties;
  private final SyncRunAuthoritativeScopePlanner authoritativeScopePlanner;
  private final SyncRunAuthoritativeScopeRepository authoritativeScopeRepository;

  public SyncRunTablePlanningService(
      SyncRunMapper syncRunMapper,
      SyncRunTableStateMapper stateMapper,
      SyncRunTableTaskMapper taskMapper,
      JsonUtils jsonUtils,
      GitlabConfigService configService,
      GitlabWhitelistService whitelistService,
      GitlabMirrorProperties mirrorProperties,
      SyncRunAuthoritativeScopePlanner authoritativeScopePlanner,
      SyncRunAuthoritativeScopeRepository authoritativeScopeRepository) {
    this.syncRunMapper = syncRunMapper;
    this.stateMapper = stateMapper;
    this.taskMapper = taskMapper;
    this.jsonUtils = jsonUtils;
    this.configService = configService;
    this.whitelistService = whitelistService;
    this.mirrorProperties = mirrorProperties;
    this.authoritativeScopePlanner = authoritativeScopePlanner;
    this.authoritativeScopeRepository = authoritativeScopeRepository;
  }

  public int planRunTables(Long runId) {
    SyncRun run = syncRunMapper.selectById(runId);
    if (run == null) {
      return 0;
    }
    Set<String> existingTaskKeys = existingTaskKeys(runId);
    SyncRunPayload payload = parsePayload(run);
    List<String> sourceTables = payload.normalizedSourceTables();
    List<SyncRunPayload.PreciseTarget> preciseTargets = payload.runnablePreciseTargets();
    if (run.getRunType() == SyncRunType.SYSTEM_HOOK && !preciseTargets.isEmpty()) {
      return planPreciseTargets(run, preciseTargets, existingTaskKeys);
    }
    if (shouldPlanFromWhitelist(run, sourceTables)) {
      return planWhitelistTables(run, sourceTables, existingTaskKeys);
    }
    LocalDateTime now = LocalDateTime.now();
    int planned = existingTaskKeys.size();
    for (String sourceTable : sourceTables) {
      SyncRunTableState state = resolveRunnableState(run, sourceTable);
      if (existingTaskKeys.contains(taskKey(state.getSourceTable(), ""))) {
        continue;
      }
      taskMapper.insert(createTask(run, state, resolveTaskWatermark(run, state), now));
      planned++;
      existingTaskKeys.add(taskKey(state.getSourceTable(), ""));
    }
    log.info("Planned {} table tasks for run {}", planned, runId);
    return planned;
  }

  private boolean shouldPlanFromWhitelist(SyncRun run, List<String> sourceTables) {
    if (run.getRunType() == SyncRunType.FULL_SYNC || run.getRunType() == SyncRunType.INCREMENTAL_SYNC) {
      return true;
    }
    return sourceTables.isEmpty()
        && (run.getRunType() == SyncRunType.FULL_COMPENSATION_SCAN
            || run.getRunType() == SyncRunType.DELETE_RECONCILIATION
            || run.getRunType() == SyncRunType.SYSTEM_HOOK);
  }

  private int planWhitelistTables(SyncRun run, List<String> requestedTables, Set<String> existingTaskKeys) {
    GitlabSyncConfig config = configService.getConfigById(run.getConfigId());
    ensureSourceConfigured(config);
    List<TableWhitelistOption> options = whitelistService.resolveOptions(config);
    if (requestedTables != null && !requestedTables.isEmpty()) {
      options =
          options.stream()
              .filter(option -> requestedTables.contains(GitlabSourceInstanceSupport.normalizeSourceTableName(option.tableName())))
              .toList();
    }
    validateRequiredIncrementalTables(run, options);
    snapshotSelectedSourceTables(run, options);
    LocalDateTime now = LocalDateTime.now();
    int planned = existingTaskKeys.size();
    for (TableWhitelistOption discoveredOption : options) {
      TableWhitelistOption option = effectiveOption(discoveredOption);
      if (run.getRunType() == SyncRunType.INCREMENTAL_SYNC
          && readMode(option) == IncrementalReadMode.RECONCILE_ONLY) {
        continue;
      }
      if (!isRunnableForRun(option)) {
        throw new BizException("GitLab 来源表缺少可执行主键：" + option.tableName());
      }
      SyncRunTableState state = upsertState(run, config, option, now);
      if (run.getRunType() == SyncRunType.DELETE_RECONCILIATION
          && !isDeleteReconciliationDue(state, now)) {
        continue;
      }
      if (existingTaskKeys.contains(taskKey(state.getSourceTable(), ""))) {
        continue;
      }
      SyncRunTableTask task =
          run.getRunType() == SyncRunType.DELETE_RECONCILIATION
              ? createDeleteReconciliationTask(run, state, now)
              : createTask(run, state, resolveTaskWatermark(run, state), now);
      taskMapper.insert(task);
      planned++;
      existingTaskKeys.add(taskKey(state.getSourceTable(), ""));
    }
    log.info("Planned {} whitelist table tasks for run {}", planned, run.getId());
    return planned;
  }

  private int planPreciseTargets(SyncRun run, List<SyncRunPayload.PreciseTarget> targets, Set<String> existingTaskKeys) {
    GitlabSyncConfig config = configService.getConfigById(run.getConfigId());
    ensureSourceConfigured(config);
    Map<String, TableWhitelistOption> optionsByTable =
        whitelistService.resolveOptions(config).stream()
            .collect(
                Collectors.toMap(
                    option -> GitlabSourceInstanceSupport.normalizeSourceTableName(option.tableName()),
                    option -> option,
                    (first, ignored) -> first));
    LocalDateTime now = LocalDateTime.now();
    int planned = 0;
    for (SyncRunPayload.PreciseTarget target : targets) {
      TableWhitelistOption option = optionsByTable.get(target.tableName());
      Map<String, String> lookupScope = target.lookupScope();
      if (option == null || isBlank(option.primaryKey()) || lookupScope == null || lookupScope.isEmpty()) {
        log.info("Skipped precise target without runnable lookup, runId={}, sourceTable={}", run.getId(), target.tableName());
        continue;
      }
      String normalizedTable =
          GitlabSourceInstanceSupport.normalizeSourceTableName(target.tableName());
      Map<String, Object> normalizedScope = new java.util.TreeMap<>();
      lookupScope.forEach(normalizedScope::put);
      if (GitlabSourceLineageCatalog.isAuthoritativeTarget(normalizedTable, normalizedScope)) {
        planned +=
            authoritativeScopePlanner.enqueueDeclaredScope(
                run.getId(),
                run.getSourceInstance(),
                normalizedTable,
                "system-hook:" + normalizedTable,
                normalizedScope);
        continue;
      }
      SyncRunTableState state = upsertState(run, config, option, now);
      String scopeJson = jsonUtils.toJson(new java.util.TreeMap<>(normalizedScope));
      String taskKey = taskKey(state.getSourceTable(), scopeSignature(normalizedScope));
      if (existingTaskKeys.contains(taskKey)) {
        continue;
      }
      SyncRunTableTask task = createTask(run, state, INITIAL_WATERMARK, now);
      task.setRowStrategy("PRECISE");
      task.setCursorUpdatedAt(null);
      task.setCursorPk(null);
      task.setLookupScopeJson(scopeJson);
      taskMapper.insert(task);
      planned++;
      existingTaskKeys.add(taskKey);
    }
    log.info("Planned {} precise table tasks for run {}", planned, run.getId());
    return planned;
  }

  private void ensureSourceConfigured(GitlabSyncConfig config) {
    if (configService.isSourceConfigured(config)) {
      return;
    }
    throw new BizException("GitLab 数据源连接配置不完整，跳过外部元数据发现");
  }

  private boolean isRunnableForRun(TableWhitelistOption option) {
    return option != null && !isBlank(option.primaryKey());
  }

  private void validateRequiredIncrementalTables(
      SyncRun run, List<TableWhitelistOption> options) {
    if (run.getRunType() != SyncRunType.INCREMENTAL_SYNC) {
      return;
    }
    Set<String> available =
        options.stream()
            .map(option -> GitlabSourceInstanceSupport.normalizeSourceTableName(option.tableName()))
            .collect(Collectors.toSet());
    List<String> missing =
        GitlabSourceLineageCatalog.sources().stream()
            .filter(GitlabSourceLineageCatalog.SourceDefinition::requiredForIncremental)
            .map(GitlabSourceLineageCatalog.SourceDefinition::tableName)
            .filter(table -> !available.contains(table))
            .toList();
    if (!missing.isEmpty()) {
      throw new BizException("快速增量缺少必需来源表：" + String.join(",", missing));
    }
  }

  private void snapshotSelectedSourceTables(
      SyncRun run, List<TableWhitelistOption> options) {
    if (run.getRunType() != SyncRunType.INCREMENTAL_SYNC) {
      return;
    }
    authoritativeScopeRepository.snapshotSelectedSourceTables(
        run.getId(),
        options.stream()
            .map(TableWhitelistOption::tableName)
            .map(GitlabSourceInstanceSupport::normalizeSourceTableName)
            .toList());
  }

  private TableWhitelistOption effectiveOption(TableWhitelistOption option) {
    GitlabSourceLineageCatalog.SourceDefinition definition =
        GitlabSourceLineageCatalog.findSource(option.tableName()).orElse(null);
    if (definition == null) {
      return option;
    }
    String updatedAtColumn = definition.incrementalUpdatedAtColumn();
    if (definition.incrementalReadMode() == IncrementalReadMode.UPDATED_AT
        && !updatedAtColumn.equals(option.updatedAtColumn())) {
      throw new BizException(
          "GitLab 来源表缺少声明的增量列："
              + option.tableName()
              + "."
              + updatedAtColumn);
    }
    com.data.collection.platform.entity.SourceCursorStrategy cursorStrategy =
        definition.incrementalReadMode() == IncrementalReadMode.MONOTONIC_PRIMARY_KEY
            ? com.data.collection.platform.entity.SourceCursorStrategy.PRIMARY_KEY_KEYSET
            : definition.incrementalReadMode() == IncrementalReadMode.RECONCILE_ONLY
                ? com.data.collection.platform.entity.SourceCursorStrategy.NONE
                : option.cursorStrategy();
    return new TableWhitelistOption(
        option.tableName(),
        option.label(),
        option.primaryKey(),
        updatedAtColumn,
        cursorStrategy,
        option.recommended());
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
      state.setSourceInstance(GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE);
      state.setSourceTable(sourceTable);
      state.setMirrorTable(GitlabSourceInstanceSupport.buildMirrorTableName(sourceTable));
      state.setPrimaryKeyColumns(option.primaryKey());
      state.setUpdatedAtColumn(option.updatedAtColumn());
      state.setRowStrategy(rowStrategyForState(option));
      state.setCursorStrategy(option.cursorStrategy());
      state.setSyncEnabled(true);
      state.setDirtyFlag(false);
      state.setRetryCount(0);
      state.setCreatedAt(now);
      state.setUpdatedAt(now);
      stateMapper.insert(state);
      return state;
    }
    state.setMirrorTable(GitlabSourceInstanceSupport.buildMirrorTableName(sourceTable));
    state.setSourceInstance(GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE);
    state.setPrimaryKeyColumns(option.primaryKey());
    state.setUpdatedAtColumn(option.updatedAtColumn());
    state.setRowStrategy(rowStrategyForState(option));
    state.setCursorStrategy(option.cursorStrategy());
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
    task.setRowStrategy(rowStrategyForTask(run, state));
    task.setTaskStage(SyncRunTableTaskStage.SCAN);
    task.setWatermarkAt(watermark);
    if (readMode(state) == IncrementalReadMode.MONOTONIC_PRIMARY_KEY
        && !isFullTableRun(run)) {
      task.setCursorPk(state.getLastCursorPk());
    }
    task.setScanUpperBoundAt(null);
    task.setScanUpperBoundPk(null);
    task.setPageNumber(1);
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

  private SyncRunTableTask createDeleteReconciliationTask(
      SyncRun run, SyncRunTableState state, LocalDateTime now) {
    SyncRunTableTask task = createTask(run, state, null, now);
    task.setRowStrategy("RECONCILE_ONLY");
    task.setTaskStage(SyncRunTableTaskStage.RECONCILE);
    task.setBatchSize(
        mirrorProperties == null
            ? 500
            : Math.max(1, mirrorProperties.getDeleteReconciliationPageSize()));
    return task;
  }

  private boolean isDeleteReconciliationDue(
      SyncRunTableState state, LocalDateTime now) {
    if (state.getLastDeleteReconciledAt() == null) {
      return true;
    }
    int intervalMinutes =
        mirrorProperties == null
            ? 60
            : Math.max(1, mirrorProperties.getDeleteReconciliationIntervalMinutes());
    return !state.getLastDeleteReconciledAt().isAfter(now.minusMinutes(intervalMinutes));
  }

  private LocalDateTime resolveTaskWatermark(SyncRun run, SyncRunTableState state) {
    if (isFullTableRun(run)) {
      return INITIAL_WATERMARK;
    }
    if (state.getLastWatermarkAt() == null) {
      return INITIAL_WATERMARK;
    }
    int lookbackMinutes = mirrorProperties == null
        ? 0
        : Math.max(0, mirrorProperties.getIncrementalLookbackMinutes());
    return state.getLastWatermarkAt().minusMinutes(lookbackMinutes);
  }

  private String rowStrategyForTask(SyncRun run, SyncRunTableState state) {
    if (run.getRunType() == SyncRunType.FULL_SYNC
        || run.getRunType() == SyncRunType.FULL_COMPENSATION_SCAN) {
      return "FULL_RECONCILE";
    }
    return switch (readMode(state)) {
      case UPDATED_AT -> "INCREMENTAL";
      case MONOTONIC_PRIMARY_KEY -> "MONOTONIC_PRIMARY_KEY";
      case RECONCILE_ONLY -> "RECONCILE_ONLY";
    };
  }

  private String rowStrategyForState(TableWhitelistOption option) {
    return readMode(option).name();
  }

  private IncrementalReadMode readMode(TableWhitelistOption option) {
    return GitlabSourceLineageCatalog.findSource(option.tableName())
        .map(GitlabSourceLineageCatalog.SourceDefinition::incrementalReadMode)
        .orElseGet(
            () ->
                isBlank(option.updatedAtColumn())
                    ? IncrementalReadMode.RECONCILE_ONLY
                    : IncrementalReadMode.UPDATED_AT);
  }

  private IncrementalReadMode readMode(SyncRunTableState state) {
    try {
      return IncrementalReadMode.valueOf(state.getRowStrategy());
    } catch (IllegalArgumentException | NullPointerException ignored) {
      return isBlank(state.getUpdatedAtColumn())
          ? IncrementalReadMode.RECONCILE_ONLY
          : IncrementalReadMode.UPDATED_AT;
    }
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
      throw new BizException("镜像表状态创建前不能执行手动刷新：" + sourceTable);
    }
    if (isBlank(state.getPrimaryKeyColumns())) {
      throw new BizException("手动刷新表需要已识别的主键列：" + sourceTable);
    }
    if (!isBlank(state.getUpdatedAtColumn()) && state.getLastWatermarkAt() == null) {
      throw new BizException("手动刷新表需要先完成一次全量同步基线：" + sourceTable);
    }
    return state;
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private boolean isFullTableRun(SyncRun run) {
    return run != null
        && (run.getRunType() == SyncRunType.FULL_SYNC
            || run.getRunType() == SyncRunType.FULL_COMPENSATION_SCAN);
  }

  private Set<String> existingTaskKeys(Long runId) {
    if (runId == null) {
      return new LinkedHashSet<>();
    }
    List<SyncRunTableTask> existingTasks =
        taskMapper.selectList(
            new QueryWrapper<SyncRunTableTask>()
                .eq("run_id", runId)
                .select("source_table", "lookup_scope_json"));
    if (existingTasks == null || existingTasks.isEmpty()) {
      return new LinkedHashSet<>();
    }
    return existingTasks.stream()
        .map(task -> taskKey(
            task.getSourceTable(), scopeSignature(jsonUtils.toMap(task.getLookupScopeJson()))))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private String taskKey(String sourceTable, String scopeSignature) {
    return String.join(
        "|",
        GitlabSourceInstanceSupport.normalizeSourceTableName(sourceTable),
        scopeSignature == null ? "" : scopeSignature);
  }

  private String scopeSignature(Map<String, ?> scope) {
    if (scope == null || scope.isEmpty()) {
      return "";
    }
    return new java.util.TreeMap<>(scope).entrySet().stream()
        .map(entry -> entry.getKey() + "=" + String.valueOf(entry.getValue()))
        .collect(Collectors.joining(","));
  }

  private SyncRunPayload parsePayload(SyncRun run) {
    SyncRunPayload payload = jsonUtils.fromJson(run.getPayloadJson(), SyncRunPayload.typeReference());
    return payload == null ? SyncRunPayload.empty() : payload;
  }
}
