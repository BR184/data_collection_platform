package com.data.collection.platform.service.sync;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.config.GitlabMirrorProperties;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

  public SyncRunTablePlanningService(
      SyncRunMapper syncRunMapper,
      SyncRunTableStateMapper stateMapper,
      SyncRunTableTaskMapper taskMapper,
      JsonUtils jsonUtils,
      GitlabConfigService configService,
      GitlabWhitelistService whitelistService,
      GitlabMirrorProperties mirrorProperties) {
    this.syncRunMapper = syncRunMapper;
    this.stateMapper = stateMapper;
    this.taskMapper = taskMapper;
    this.jsonUtils = jsonUtils;
    this.configService = configService;
    this.whitelistService = whitelistService;
    this.mirrorProperties = mirrorProperties;
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
    if (run.getRunType() == SyncRunType.COMPENSATION_SCAN && sourceTables.isEmpty()) {
      int existingTaskCount = existingTaskKeys.size();
      int plannedFromStates = planCompensationFromExistingStates(run, existingTaskKeys);
      if (plannedFromStates > existingTaskCount) {
        return plannedFromStates;
      }
    }
    if (shouldPlanFromWhitelist(run, sourceTables)) {
      return planWhitelistTables(run, sourceTables, existingTaskKeys);
    }
    LocalDateTime now = LocalDateTime.now();
    int planned = existingTaskKeys.size();
    for (String sourceTable : sourceTables) {
      SyncRunTableState state = resolveRunnableState(run, sourceTable);
      int shardPlanned = planShardTasksIfNeeded(run, state, existingTaskKeys, now);
      if (shardPlanned > 0) {
        planned += shardPlanned;
        continue;
      }
      if (existingTaskKeys.contains(taskKey(state.getSourceTable(), null, null, null))) {
        continue;
      }
      taskMapper.insert(createTask(run, state, state.getLastWatermarkAt(), now));
      planned++;
      existingTaskKeys.add(taskKey(state.getSourceTable(), null, null, null));
    }
    log.info("Planned {} table tasks for run {}", planned, runId);
    return planned;
  }

  private boolean shouldPlanFromWhitelist(SyncRun run, List<String> sourceTables) {
    if (run.getRunType() == SyncRunType.FULL_SYNC || run.getRunType() == SyncRunType.INCREMENTAL_SYNC) {
      return true;
    }
    return sourceTables.isEmpty()
        && (run.getRunType() == SyncRunType.COMPENSATION_SCAN
            || run.getRunType() == SyncRunType.FULL_COMPENSATION_SCAN
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
    LocalDateTime now = LocalDateTime.now();
    int planned = existingTaskKeys.size();
    boolean fullSync = isFullTableRun(run);
    for (TableWhitelistOption option : options) {
      if (!isRunnableForRun(fullSync, option)) {
        log.info("Skipped table without runnable key columns, runId={}, sourceTable={}", run.getId(), option.tableName());
        continue;
      }
      SyncRunTableState state = upsertState(run, config, option, now);
      int shardPlanned = planShardTasksIfNeeded(run, state, existingTaskKeys, now);
      if (shardPlanned > 0) {
        planned += shardPlanned;
        continue;
      }
      if (existingTaskKeys.contains(taskKey(state.getSourceTable(), null, null, null))) {
        continue;
      }
      taskMapper.insert(createTask(run, state, resolveTaskWatermark(run, state), now));
      planned++;
      existingTaskKeys.add(taskKey(state.getSourceTable(), null, null, null));
    }
    log.info("Planned {} whitelist table tasks for run {}", planned, run.getId());
    return planned;
  }

  private int planCompensationFromExistingStates(SyncRun run, Set<String> existingTaskKeys) {
    List<SyncRunTableState> states =
        stateMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SyncRunTableState>()
                .eq(SyncRunTableState::getConfigId, run.getConfigId())
                .eq(SyncRunTableState::getSourceInstance, run.getSourceInstance())
                .eq(SyncRunTableState::getSyncEnabled, true));
    if (states == null || states.isEmpty()) {
      return existingTaskKeys.size();
    }
    LocalDateTime now = LocalDateTime.now();
    int planned = existingTaskKeys.size();
    for (SyncRunTableState state : states) {
      if (!isRunnableIncrementalState(state)) {
        continue;
      }
      int shardPlanned = planShardTasksIfNeeded(run, state, existingTaskKeys, now);
      if (shardPlanned > 0) {
        planned += shardPlanned;
        continue;
      }
      if (existingTaskKeys.contains(taskKey(state.getSourceTable(), null, null, null))) {
        continue;
      }
      taskMapper.insert(createTask(run, state, resolveTaskWatermark(run, state), now));
      planned++;
      existingTaskKeys.add(taskKey(state.getSourceTable(), null, null, null));
    }
    log.info("Planned {} compensation table tasks from existing states for run {}", planned, run.getId());
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
      if (option == null || isBlank(option.primaryKey()) || isBlank(target.lookupColumn()) || isBlank(target.lookupValue())) {
        log.info("Skipped precise target without runnable lookup, runId={}, sourceTable={}", run.getId(), target.tableName());
        continue;
      }
      SyncRunTableState state = upsertState(run, config, option, now);
      String taskKey = taskKey(state.getSourceTable(), target.lookupColumn(), target.lookupValue(), null);
      if (existingTaskKeys.contains(taskKey)) {
        continue;
      }
      SyncRunTableTask task = createTask(run, state, INITIAL_WATERMARK, now);
      task.setRowStrategy("PRECISE");
      task.setCursorUpdatedAt(null);
      task.setCursorPk(null);
      task.setLookupColumn(target.lookupColumn());
      task.setLookupValue(target.lookupValue());
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
      state.setSourceInstance(GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE);
      state.setSourceTable(sourceTable);
      state.setMirrorTable(GitlabSourceInstanceSupport.buildMirrorTableName(sourceTable));
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
    state.setMirrorTable(GitlabSourceInstanceSupport.buildMirrorTableName(sourceTable));
    state.setSourceInstance(GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE);
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
    seedIncrementalCursor(task, state, watermark);
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

  private int planShardTasksIfNeeded(
      SyncRun run,
      SyncRunTableState state,
      Set<String> existingTaskKeys,
      LocalDateTime now) {
    if (!shouldPlanSharded(run, state)) {
      return 0;
    }
    int shardKeyLength = shardKeyLength();
    int planned = 0;
    for (String shardKey : shardKeys(shardKeyLength)) {
      String taskKey = taskKey(state.getSourceTable(), null, null, shardKey);
      if (existingTaskKeys.contains(taskKey)) {
        continue;
      }
      SyncRunTableTask task = createTask(run, state, resolveTaskWatermark(run, state), now);
      task.setShardKey(shardKey);
      task.setShardKeyLength(shardKeyLength);
      task.setCursorUpdatedAt(null);
      task.setCursorPk(null);
      taskMapper.insert(task);
      existingTaskKeys.add(taskKey);
      planned++;
    }
    if (planned > 0) {
      log.info(
          "Planned {} shard tasks for table {}, runId={}",
          planned,
          state.getSourceTable(),
          run.getId());
    }
    return planned;
  }

  private boolean shouldPlanSharded(SyncRun run, SyncRunTableState state) {
    return mirrorProperties != null
        && mirrorProperties.isLargeTableShardSyncEnabled()
        && !isFullTableRun(run)
        && run != null
        && run.getRunType() != SyncRunType.SYSTEM_HOOK
        && state != null
        && !isBlank(state.getPrimaryKeyColumns())
        && !isBlank(state.getUpdatedAtColumn())
        && shardTableNames().contains(GitlabSourceInstanceSupport.normalizeSourceTableName(state.getSourceTable()));
  }

  private Set<String> shardTableNames() {
    if (mirrorProperties == null || isBlank(mirrorProperties.getLargeTableShardTables())) {
      return Set.of();
    }
    return List.of(mirrorProperties.getLargeTableShardTables().split(",")).stream()
        .map(GitlabSourceInstanceSupport::normalizeSourceTableName)
        .filter(value -> !value.isBlank())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private int shardKeyLength() {
    if (mirrorProperties == null) {
      return 1;
    }
    return Math.max(1, Math.min(2, mirrorProperties.getLargeTableShardKeyLength()));
  }

  private List<String> shardKeys(int length) {
    List<String> result = new ArrayList<>();
    appendShardKeys(result, "", length);
    return result;
  }

  private void appendShardKeys(List<String> result, String prefix, int remaining) {
    if (remaining <= 0) {
      result.add(prefix);
      return;
    }
    for (char c : "0123456789abcdef".toCharArray()) {
      appendShardKeys(result, prefix + c, remaining - 1);
    }
  }

  private void seedIncrementalCursor(
      SyncRunTableTask task,
      SyncRunTableState state,
      LocalDateTime watermark) {
    if (task == null
        || state == null
        || !"INCREMENTAL".equalsIgnoreCase(task.getRowStrategy())
        || watermark == null
        || state.getLastWatermarkAt() == null
        || !watermark.equals(state.getLastWatermarkAt())
        || isBlank(state.getLastCursorPk())) {
      return;
    }
    task.setCursorUpdatedAt(state.getLastWatermarkAt());
    task.setCursorPk(state.getLastCursorPk());
  }

  private LocalDateTime resolveTaskWatermark(SyncRun run, SyncRunTableState state) {
    if (isFullTableRun(run)) {
      return INITIAL_WATERMARK;
    }
    return state.getLastWatermarkAt() == null ? INITIAL_WATERMARK : state.getLastWatermarkAt();
  }

  private String rowStrategyForTask(SyncRun run) {
    if (run.getRunType() == SyncRunType.FULL_SYNC) {
      return "FULL";
    }
    if (run.getRunType() == SyncRunType.FULL_COMPENSATION_SCAN) {
      return "FULL_RECONCILE";
    }
    return "INCREMENTAL";
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
      throw new BizException("镜像表状态创建前不能执行手动刷新：" + sourceTable);
    }
    if (isBlank(state.getPrimaryKeyColumns())) {
      throw new BizException("手动刷新表需要已识别的主键列：" + sourceTable);
    }
    if (isBlank(state.getUpdatedAtColumn())) {
      throw new BizException("手动刷新表需要 updated_at 列：" + sourceTable);
    }
    if (state.getLastWatermarkAt() == null) {
      throw new BizException("手动刷新表需要先完成一次全量同步基线：" + sourceTable);
    }
    return state;
  }

  private boolean isRunnableIncrementalState(SyncRunTableState state) {
    return state != null
        && !isBlank(state.getPrimaryKeyColumns())
        && !isBlank(state.getUpdatedAtColumn());
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
                .select("source_table", "lookup_column", "lookup_value", "shard_key"));
    if (existingTasks == null || existingTasks.isEmpty()) {
      return new LinkedHashSet<>();
    }
    return existingTasks.stream()
        .map(task -> taskKey(task.getSourceTable(), task.getLookupColumn(), task.getLookupValue(), task.getShardKey()))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private String taskKey(String sourceTable, String lookupColumn, String lookupValue, String shardKey) {
    return String.join(
        "|",
        GitlabSourceInstanceSupport.normalizeSourceTableName(sourceTable),
        lookupColumn == null ? "" : lookupColumn,
        lookupValue == null ? "" : lookupValue,
        shardKey == null ? "" : shardKey.toLowerCase(Locale.ROOT));
  }

  private SyncRunPayload parsePayload(SyncRun run) {
    SyncRunPayload payload = jsonUtils.fromJson(run.getPayloadJson(), SyncRunPayload.typeReference());
    return payload == null ? SyncRunPayload.empty() : payload;
  }
}
