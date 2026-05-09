package com.data.collection.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.data.collection.platform.entity.GitlabMirrorTableRegistry;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSyncJob;
import com.data.collection.platform.entity.GitlabSyncJobType;
import com.data.collection.platform.entity.GitlabTableRowStrategy;
import com.data.collection.platform.entity.GitlabTableSyncState;
import com.data.collection.platform.entity.GitlabTableSyncTask;
import com.data.collection.platform.entity.GitlabTableSyncTaskType;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.mapper.GitlabMirrorTableRegistryMapper;
import com.data.collection.platform.mapper.GitlabSyncJobMapper;
import com.data.collection.platform.mapper.GitlabTableSyncStateMapper;
import com.data.collection.platform.mapper.GitlabTableSyncTaskMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GitlabTableSyncPlanningService {
  private static final int DEFAULT_BATCH_SIZE = 500;
  private static final int DEFAULT_MAX_RETRY_COUNT = 3;
  private static final String DAILY_VERIFY_MESSAGE =
      "Missing updated_at column; table is deferred to daily verification.";

  private final GitlabSyncJobMapper jobMapper;
  private final GitlabTableSyncStateMapper stateMapper;
  private final GitlabTableSyncTaskMapper taskMapper;
  private final GitlabMirrorTableRegistryMapper registryMapper;
  private final GitlabExternalDbService externalDbService;

  public GitlabTableSyncPlanningService(
      GitlabSyncJobMapper jobMapper,
      GitlabTableSyncStateMapper stateMapper,
      GitlabTableSyncTaskMapper taskMapper,
      GitlabMirrorTableRegistryMapper registryMapper,
      GitlabExternalDbService externalDbService) {
    this.jobMapper = jobMapper;
    this.stateMapper = stateMapper;
    this.taskMapper = taskMapper;
    this.registryMapper = registryMapper;
    this.externalDbService = externalDbService;
  }

  @Transactional
  public CompensationPlanResult createCompensationScanPlan(
      GitlabSyncConfig config,
      List<TableWhitelistOption> whitelistOptions) {
    Objects.requireNonNull(config, "config must not be null");
    if (config.getId() == null) {
      throw new IllegalArgumentException("config id must not be null");
    }

    List<TableWhitelistOption> options = whitelistOptions == null ? List.of() : whitelistOptions;
    LocalDateTime now = LocalDateTime.now();
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    GitlabSyncJob job = createJob(
        config.getId(),
        sourceInstance,
        GitlabSyncJobType.COMPENSATION_SCAN,
        SyncTriggerType.SCHEDULE,
        0,
        now);
    jobMapper.insert(job);

    int plannedTasks = 0;
    int verifyOnlyTables = 0;
    for (TableWhitelistOption option : options) {
      if (option == null || isBlank(option.tableName())) {
        continue;
      }
      GitlabTableSyncState state = upsertState(config.getId(), sourceInstance, option, now);
      if (!state.isSyncEnabled() || state.getRowStrategy() != GitlabTableRowStrategy.INCREMENTAL) {
        verifyOnlyTables++;
      } else if (shouldPlanIncrementalTask(config, option, state, now)) {
        taskMapper.insert(createTableTask(job, state, GitlabTableSyncTaskType.COMPENSATION_INCREMENTAL, now));
        plannedTasks++;
      }
    }
    if (plannedTasks == 0) {
      job.setStatus(SyncStatus.SUCCESS);
      job.setFinishedAt(LocalDateTime.now());
      job.setUpdatedAt(job.getFinishedAt());
      jobMapper.updateById(job);
    }
    return new CompensationPlanResult(job.getId(), options.size(), plannedTasks, verifyOnlyTables);
  }

  public boolean hasActiveJob(Long configId, GitlabSyncJobType jobType) {
    if (configId == null || jobType == null) {
      return false;
    }
    Long count = jobMapper.selectCount(new LambdaQueryWrapper<GitlabSyncJob>()
        .eq(GitlabSyncJob::getConfigId, configId)
        .eq(GitlabSyncJob::getJobType, jobType)
        .in(GitlabSyncJob::getStatus, List.of(SyncStatus.PENDING, SyncStatus.RUNNING)));
    return count != null && count > 0;
  }

  public SyncStatus findJobStatus(Long jobId) {
    if (jobId == null) {
      return SyncStatus.PENDING;
    }
    GitlabSyncJob job = jobMapper.selectById(jobId);
    return job == null ? SyncStatus.PENDING : job.getStatus();
  }

  @Transactional
  public CompensationPlanResult createDailyVerificationPlan(
      GitlabSyncConfig config,
      List<TableWhitelistOption> whitelistOptions) {
    return createVerificationPlan(config, whitelistOptions, SyncTriggerType.SCHEDULE);
  }

  @Transactional
  public CompensationPlanResult createManualVerificationPlan(
      GitlabSyncConfig config,
      List<TableWhitelistOption> whitelistOptions) {
    return createVerificationPlan(config, whitelistOptions, SyncTriggerType.MANUAL);
  }

  private CompensationPlanResult createVerificationPlan(
      GitlabSyncConfig config,
      List<TableWhitelistOption> whitelistOptions,
      SyncTriggerType triggerType) {
    Objects.requireNonNull(config, "config must not be null");
    if (config.getId() == null) {
      throw new IllegalArgumentException("config id must not be null");
    }
    List<TableWhitelistOption> options = whitelistOptions == null ? List.of() : whitelistOptions;
    LocalDateTime now = LocalDateTime.now();
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    GitlabSyncJob job = createJob(
        config.getId(),
        sourceInstance,
        GitlabSyncJobType.DAILY_VERIFY,
        triggerType,
        -10,
        now);
    jobMapper.insert(job);

    int plannedTasks = 0;
    int verifyOnlyTables = 0;
    for (TableWhitelistOption option : options) {
      if (option == null || isBlank(option.tableName())) {
        continue;
      }
      GitlabTableSyncState state = upsertState(config.getId(), sourceInstance, option, now);
      taskMapper.insert(createTableTask(job, state, GitlabTableSyncTaskType.DAILY_VERIFY, now));
      plannedTasks++;
      if (state.getRowStrategy() == GitlabTableRowStrategy.VERIFY_ONLY) {
        verifyOnlyTables++;
      }
    }
    if (plannedTasks == 0) {
      job.setStatus(SyncStatus.SUCCESS);
      job.setFinishedAt(LocalDateTime.now());
      job.setUpdatedAt(job.getFinishedAt());
      jobMapper.updateById(job);
    }
    return new CompensationPlanResult(job.getId(), options.size(), plannedTasks, verifyOnlyTables);
  }

  @Transactional
  public CompensationPlanResult createManualRefreshPlan(
      GitlabSyncConfig config,
      List<TableWhitelistOption> whitelistOptions,
      List<String> sourceTableNames,
      String reason) {
    Objects.requireNonNull(config, "config must not be null");
    if (config.getId() == null) {
      throw new IllegalArgumentException("config id must not be null");
    }
    List<String> targetTables = sourceTableNames == null ? List.of() : sourceTableNames.stream()
        .filter(tableName -> !isBlank(tableName))
        .map(GitlabSourceInstanceSupport::normalizeSourceTableName)
        .toList();
    List<TableWhitelistOption> options = (whitelistOptions == null ? List.<TableWhitelistOption>of() : whitelistOptions)
        .stream()
        .filter(option -> targetTables.contains(GitlabSourceInstanceSupport.normalizeSourceTableName(option.tableName())))
        .toList();
    LocalDateTime now = LocalDateTime.now();
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    GitlabSyncJob job = createJob(
        config.getId(),
        sourceInstance,
        GitlabSyncJobType.MANUAL_REFRESH,
        SyncTriggerType.MANUAL,
        100,
        now);
    job.setPayloadJson(reason == null ? "" : reason);
    jobMapper.insert(job);

    int plannedTasks = 0;
    int verifyOnlyTables = 0;
    for (TableWhitelistOption option : options) {
      GitlabTableSyncState state = upsertState(config.getId(), sourceInstance, option, now);
      if (!state.isSyncEnabled() || state.getRowStrategy() != GitlabTableRowStrategy.INCREMENTAL) {
        verifyOnlyTables++;
      } else {
        taskMapper.insert(createTableTask(job, state, GitlabTableSyncTaskType.MANUAL_REFRESH, now));
        plannedTasks++;
      }
    }
    if (plannedTasks == 0) {
      job.setStatus(SyncStatus.SUCCESS);
      job.setFinishedAt(LocalDateTime.now());
      job.setUpdatedAt(job.getFinishedAt());
      jobMapper.updateById(job);
    }
    return new CompensationPlanResult(job.getId(), options.size(), plannedTasks, verifyOnlyTables);
  }

  private GitlabSyncJob createJob(
      Long configId,
      String sourceInstance,
      GitlabSyncJobType jobType,
      SyncTriggerType triggerType,
      int priority,
      LocalDateTime now) {
    GitlabSyncJob job = new GitlabSyncJob();
    job.setRunId(UUID.randomUUID().toString());
    job.setConfigId(configId);
    job.setSourceInstance(sourceInstance);
    job.setJobType(jobType);
    job.setTriggerType(triggerType);
    job.setStatus(SyncStatus.PENDING);
    job.setPriority(priority);
    job.setRunAfter(now);
    job.setRetryCount(0);
    job.setMaxRetryCount(DEFAULT_MAX_RETRY_COUNT);
    job.setCreatedAt(now);
    job.setUpdatedAt(now);
    return job;
  }

  private boolean shouldPlanIncrementalTask(
      GitlabSyncConfig config,
      TableWhitelistOption option,
      GitlabTableSyncState state,
      LocalDateTime now) {
    if (state.isDirtyFlag() || state.getLastWatermarkAt() == null) {
      return true;
    }
    try {
      LocalDateTime sourceMaxUpdatedAt = externalDbService.findMaxUpdatedAt(config, option);
      state.setSourceMaxUpdatedAt(sourceMaxUpdatedAt);
      state.setUpdatedAt(now);
      stateMapper.updateById(state);
      return sourceMaxUpdatedAt != null && sourceMaxUpdatedAt.isAfter(state.getLastWatermarkAt());
    } catch (RuntimeException e) {
      state.setDirtyFlag(true);
      state.setLastError(e.getMessage());
      state.setUpdatedAt(now);
      stateMapper.updateById(state);
      return true;
    }
  }

  private GitlabTableSyncState upsertState(
      Long configId,
      String sourceInstance,
      TableWhitelistOption option,
      LocalDateTime now) {
    String sourceTable = GitlabSourceInstanceSupport.normalizeSourceTableName(option.tableName());
    GitlabTableSyncState state = stateMapper.selectOne(new LambdaQueryWrapper<GitlabTableSyncState>()
        .eq(GitlabTableSyncState::getConfigId, configId)
        .eq(GitlabTableSyncState::getSourceInstance, sourceInstance)
        .eq(GitlabTableSyncState::getSourceTable, sourceTable)
        .last("limit 1"));
    boolean isNew = state == null;
    boolean wasDirty = !isNew && state.isDirtyFlag();
    if (isNew) {
      state = new GitlabTableSyncState();
      state.setConfigId(configId);
      state.setSourceInstance(sourceInstance);
      state.setSourceTable(sourceTable);
      state.setCreatedAt(now);
      state.setRetryCount(0);
      GitlabMirrorTableRegistry registry = findExistingRegistry(configId, sourceTable);
      if (registry != null) {
        state.setLastWatermarkAt(registry.getLastSyncTime());
        state.setSchemaFingerprint(registry.getSchemaFingerprint());
      }
    }

    GitlabTableRowStrategy rowStrategy = resolveRowStrategy(option);
    state.setMirrorTable(GitlabSourceInstanceSupport.buildMirrorTableName(sourceTable, sourceInstance));
    state.setPrimaryKeyColumns(normalizePrimaryKey(option.primaryKey()));
    state.setUpdatedAtColumn(normalizeText(option.updatedAtColumn()));
    state.setRowStrategy(rowStrategy);
    state.setSyncEnabled(rowStrategy == GitlabTableRowStrategy.INCREMENTAL);
    state.setDirtyFlag(rowStrategy == GitlabTableRowStrategy.INCREMENTAL && (isNew || wasDirty));
    state.setLastError(rowStrategy == GitlabTableRowStrategy.INCREMENTAL ? "" : DAILY_VERIFY_MESSAGE);
    state.setUpdatedAt(now);

    if (isNew) {
      stateMapper.insert(state);
    } else {
      stateMapper.updateById(state);
    }
    return state;
  }

  private GitlabTableSyncTask createTableTask(
      GitlabSyncJob job,
      GitlabTableSyncState state,
      GitlabTableSyncTaskType taskType,
      LocalDateTime now) {
    GitlabTableSyncTask task = new GitlabTableSyncTask();
    task.setJobId(job.getId());
    task.setConfigId(state.getConfigId());
    task.setSourceInstance(state.getSourceInstance());
    task.setSourceTable(state.getSourceTable());
    task.setMirrorTable(state.getMirrorTable());
    task.setTaskType(taskType);
    task.setStatus(SyncStatus.PENDING);
    task.setRowStrategy(GitlabTableRowStrategy.INCREMENTAL);
    task.setWatermarkAt(state.getLastWatermarkAt());
    task.setBatchSize(DEFAULT_BATCH_SIZE);
    task.setRunAfter(now);
    task.setRetryCount(0);
    task.setMaxRetryCount(DEFAULT_MAX_RETRY_COUNT);
    task.setRowsScanned(0L);
    task.setRowsApplied(0L);
    task.setCreatedAt(now);
    task.setUpdatedAt(now);
    return task;
  }

  private GitlabMirrorTableRegistry findExistingRegistry(Long configId, String sourceTable) {
    return registryMapper.selectOne(new LambdaQueryWrapper<GitlabMirrorTableRegistry>()
        .eq(GitlabMirrorTableRegistry::getConfigId, configId)
        .eq(GitlabMirrorTableRegistry::getSourceTableName, sourceTable)
        .last("limit 1"));
  }

  private GitlabTableRowStrategy resolveRowStrategy(TableWhitelistOption option) {
    return isBlank(option.updatedAtColumn())
        ? GitlabTableRowStrategy.VERIFY_ONLY
        : GitlabTableRowStrategy.INCREMENTAL;
  }

  private String normalizePrimaryKey(String rawPrimaryKey) {
    return isBlank(rawPrimaryKey) ? "id" : rawPrimaryKey.trim();
  }

  private String normalizeText(String value) {
    return isBlank(value) ? "" : value.trim();
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  public record CompensationPlanResult(
      Long jobId,
      int discoveredTables,
      int plannedTasks,
      int verifyOnlyTables) {
  }
}
