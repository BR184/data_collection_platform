package com.data.collection.platform.service.sync;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncSubmissionAction;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunSubmissionResult;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.mapper.SyncRunMapper;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class SyncRunSubmissionService {
  private final SyncRunMapper syncRunMapper;
  private final SyncRunPolicyService policyService;
  private final JdbcTemplate jdbcTemplate;
  private final JsonUtils jsonUtils;
  private final SyncThreadBudgetResolver threadBudgetResolver;
  private final SyncRunPublicationFenceService publicationFenceService;
  private final SyncIncrementalRerunService incrementalRerunService;
  private final SyncSourceSubmissionLockService sourceSubmissionLockService;

  public SyncRunSubmissionService(
      SyncRunMapper syncRunMapper,
      SyncRunPolicyService policyService,
      JdbcTemplate jdbcTemplate,
      JsonUtils jsonUtils,
      SyncThreadBudgetResolver threadBudgetResolver,
      SyncRunPublicationFenceService publicationFenceService,
      SyncIncrementalRerunService incrementalRerunService,
      SyncSourceSubmissionLockService sourceSubmissionLockService) {
    this.syncRunMapper = syncRunMapper;
    this.policyService = policyService;
    this.jdbcTemplate = jdbcTemplate;
    this.jsonUtils = jsonUtils;
    this.threadBudgetResolver = threadBudgetResolver;
    this.publicationFenceService = publicationFenceService;
    this.incrementalRerunService = incrementalRerunService;
    this.sourceSubmissionLockService = sourceSubmissionLockService;
  }

  @Transactional
  public SyncRunSubmissionResult submitFullSync(GitlabSyncConfig config, String reason) {
    return submitRun(
        config,
        SyncType.FULL,
        SyncRunType.FULL_SYNC,
        SyncTriggerType.MANUAL,
        reason,
        List.of(),
        null);
  }

  @Transactional
  public SyncRunSubmissionResult submitIncrementalSync(
      GitlabSyncConfig config, SyncTriggerType triggerType, String reason) {
    return submitRun(
        config,
        SyncType.INCREMENTAL,
        SyncRunType.INCREMENTAL_SYNC,
        triggerType,
        reason,
        List.of(),
        null);
  }

  @Transactional
  public SyncRunSubmissionResult submitFullCompensationSync(GitlabSyncConfig config, SyncTriggerType triggerType, String reason) {
    return submitRun(
        config,
        SyncType.COMPENSATION,
        SyncRunType.FULL_COMPENSATION_SCAN,
        triggerType,
        reason,
        List.of(),
        null);
  }

  /** 提交独立低优先级物理删除反熵运行。 */
  @Transactional
  public SyncRunSubmissionResult submitDeleteReconciliation(
      GitlabSyncConfig config, String reason) {
    return submitRun(
        config,
        SyncType.COMPENSATION,
        SyncRunType.DELETE_RECONCILIATION,
        SyncTriggerType.SCHEDULE,
        reason,
        List.of(),
        null);
  }

  /** 判断当前来源是否存在超过删除反熵目标周期的表。 */
  @Transactional(readOnly = true)
  public boolean hasDueDeleteReconciliation(
      GitlabSyncConfig config, LocalDateTime now, int intervalMinutes) {
    if (config == null || config.getId() == null || now == null) {
      return false;
    }
    Boolean due =
        jdbcTemplate.queryForObject(
            """
            select exists(
              select 1
                from sync_run_table_states state
               where state.config_id = ?
                 and state.source_instance = ?
                 and state.sync_enabled = true
                 and (state.last_delete_reconciled_at is null
                      or state.last_delete_reconciled_at <= ?)
            )
            """,
            Boolean.class,
            config.getId(),
            GitlabSourceInstanceSupport.sourceInstanceOf(config),
            now.minusMinutes(Math.max(1, intervalMinutes)));
    return Boolean.TRUE.equals(due);
  }

  @Transactional
  public SyncRunSubmissionResult submitTableRefresh(
      GitlabSyncConfig config, List<String> sourceTables, String reason) {
    return submitTableRefresh(config, sourceTables, reason, null);
  }

  @Transactional
  public SyncRunSubmissionResult submitTableRefresh(
      GitlabSyncConfig config, List<String> sourceTables, String reason, Map<String, Object> extraPayload) {
    List<String> normalizedTables = normalizeTables(sourceTables);
    SyncRunSubmissionResult result = submitRun(
        config,
        SyncType.INCREMENTAL,
        SyncRunType.TABLE_REFRESH,
        SyncTriggerType.MANUAL,
        reason,
        normalizedTables,
        normalizedTables.isEmpty() ? null : normalizedTables.getFirst(),
        extraPayload);
    SyncRunPayload.WorkspaceRefreshSpec refresh = workspaceRefreshOf(extraPayload);
    if (refresh != null
        && !publicationFenceService.registerRequest(
            result.runId(), GitlabSourceInstanceSupport.sourceInstanceOf(config), refresh)) {
      result = submitRun(
          config,
          SyncType.INCREMENTAL,
          SyncRunType.TABLE_REFRESH,
          SyncTriggerType.MANUAL,
          reason,
          normalizedTables,
          normalizedTables.isEmpty() ? null : normalizedTables.getFirst(),
          extraPayload);
      if (!publicationFenceService.registerRequest(
          result.runId(), GitlabSourceInstanceSupport.sourceInstanceOf(config), refresh)) {
        throw new IllegalStateException("页面刷新运行在栅栏登记前已经结束");
      }
    }
    return result;
  }

  private SyncRunPayload.WorkspaceRefreshSpec workspaceRefreshOf(
      Map<String, Object> extraPayload) {
    if (extraPayload == null) {
      return null;
    }
    Object value = extraPayload.get("workspaceRefresh");
    return value instanceof SyncRunPayload.WorkspaceRefreshSpec refresh
        ? refresh.normalized()
        : null;
  }

  /**
   * 提交来源级事实发布运行。
   *
   * <p>事实消费者不绑定父镜像运行：它按来源实例合并全部历史镜像运行的未发布目标，
   * 因此这里不接收也不落库父运行编号。
   *
   * @param config 当前数据源配置
   * @param full 是否需要全量事实重建
   * @param reason 提交原因
   * @return 已入队或被复用的事实发布运行结果
   */
  @Transactional
  public SyncRunSubmissionResult submitFactRefresh(
      GitlabSyncConfig config, boolean full, String reason) {
    return submitRun(
        config,
        SyncType.COMPENSATION,
        SyncRunType.FACT_REFRESH,
        SyncTriggerType.SCHEDULE,
        reason,
        List.of(),
        null,
        null,
        full);
  }

  /**
   * 提交当前数据源的手工全量事实重建。
   *
   * <p>执行器以明确 payload 选择统一全量构建入口，确保所有 ODS 源在任一事实表写入前完成预检。
   *
   * @param config 当前已保存的数据源配置
   * @return 已入队的事实刷新运行结果
   */
  @Transactional
  public SyncRunSubmissionResult submitManualFullFactRebuild(GitlabSyncConfig config) {
    return submitRun(
        config,
        SyncType.COMPENSATION,
        SyncRunType.FACT_REFRESH,
        SyncTriggerType.MANUAL,
        "手动重建当前数据源全部事实层",
        List.of(),
        null,
        null,
        true,
        Map.of("manualFullRebuild", true));
  }

  @Transactional(readOnly = true)
  public boolean hasActiveFullCompensationRun(GitlabSyncConfig config) {
    if (config == null || config.getId() == null) {
      return false;
    }
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    String exclusiveScope = policyService.exclusiveScopeOf(config, SyncRunType.FULL_COMPENSATION_SCAN);
    List<SyncRun> runs =
        syncRunMapper.selectList(
            new LambdaQueryWrapper<SyncRun>()
                .eq(SyncRun::getConfigId, config.getId())
                .eq(SyncRun::getSourceInstance, sourceInstance)
                .eq(SyncRun::getExclusiveScope, exclusiveScope)
                .eq(SyncRun::getRunType, SyncRunType.FULL_COMPENSATION_SCAN)
                .in(SyncRun::getStatus, SyncRunStateMachine.activeStatuses())
                .last("limit 1"));
    return runs != null && !runs.isEmpty();
  }

  /**
   * 返回自动增量调度所需的最近提交状态。
   *
   * <p>最近提交时间用于区分真实调度间隔与调度器轮询；活动状态用于避免已有尾部补跑时重复提交。
   */
  @Transactional(readOnly = true)
  public IncrementalScheduleState incrementalScheduleState(GitlabSyncConfig config) {
    if (config == null || config.getId() == null) {
      return new IncrementalScheduleState(null, false);
    }
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    List<SyncRun> latestRuns =
        syncRunMapper.selectList(
            new LambdaQueryWrapper<SyncRun>()
                .eq(SyncRun::getConfigId, config.getId())
                .eq(SyncRun::getSourceInstance, sourceInstance)
                .eq(SyncRun::getRunType, SyncRunType.INCREMENTAL_SYNC)
                .orderByDesc(SyncRun::getCreatedAt)
                .orderByDesc(SyncRun::getId)
                .last("limit 1"));
    List<SyncRun> activeRuns =
        syncRunMapper.selectList(
            new LambdaQueryWrapper<SyncRun>()
                .eq(SyncRun::getConfigId, config.getId())
                .eq(SyncRun::getSourceInstance, sourceInstance)
                .eq(SyncRun::getRunType, SyncRunType.INCREMENTAL_SYNC)
                .in(SyncRun::getStatus, SyncRunStateMachine.activeStatuses())
                .last("limit 1"));
    LocalDateTime lastSubmittedAt =
        latestRuns == null || latestRuns.isEmpty()
            ? null
            : latestRuns.getFirst().getCreatedAt();
    return new IncrementalScheduleState(
        lastSubmittedAt, activeRuns != null && !activeRuns.isEmpty());
  }

  @Transactional
  public SyncRunSubmissionResult submitRun(
      GitlabSyncConfig config,
      SyncType apiType,
      SyncRunType runType,
      SyncTriggerType triggerType,
      String reason,
      List<String> sourceTables,
      String primaryTableName) {
    return submitRun(config, apiType, runType, triggerType, reason, sourceTables, primaryTableName, null, null);
  }

  @Transactional
  public SyncRunSubmissionResult submitRun(
      GitlabSyncConfig config,
      SyncType apiType,
      SyncRunType runType,
      SyncTriggerType triggerType,
      String reason,
      List<String> sourceTables,
      String primaryTableName,
      Map<String, Object> extraPayload) {
    return submitRun(config, apiType, runType, triggerType, reason, sourceTables, primaryTableName, null, null, extraPayload);
  }

  @Transactional
  public SyncRunSubmissionResult submitRun(
      GitlabSyncConfig config,
      SyncType apiType,
      SyncRunType runType,
      SyncTriggerType triggerType,
      String reason,
      List<String> sourceTables,
      String primaryTableName,
      Long parentRunId,
      Boolean fullBuild) {
    return submitRun(
        config, apiType, runType, triggerType, reason, sourceTables, primaryTableName, parentRunId, fullBuild, null);
  }

  @Transactional
  public SyncRunSubmissionResult submitRun(
      GitlabSyncConfig config,
      SyncType apiType,
      SyncRunType runType,
      SyncTriggerType triggerType,
      String reason,
      List<String> sourceTables,
      String primaryTableName,
      Long parentRunId,
      Boolean fullBuild,
      Map<String, Object> extraPayload) {
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    String exclusiveScope = policyService.exclusiveScopeOf(config, runType);
    LocalDateTime now = LocalDateTime.now();
    SyncTriggerType effectiveTriggerType = triggerType == null ? SyncTriggerType.MANUAL : triggerType;
    sourceSubmissionLockService.lock(config.getId(), sourceInstance);
    lockExclusiveScope(exclusiveScope);

    boolean manualFullRebuild = isManualFullRebuild(runType, extraPayload);
    List<SyncRun> activeSourceRuns = findActiveRunsForSource(config.getId(), sourceInstance);
    if (manualFullRebuild && !activeSourceRuns.isEmpty()) {
      throw new BizException("当前数据源存在同步或事实刷新任务，请等待任务完成后再重建事实层");
    }
    if (!manualFullRebuild && activeSourceRuns.stream().anyMatch(this::isManualFullRebuild)) {
      throw new BizException("当前数据源正在重建事实层，请等待任务完成后再提交同步或刷新任务");
    }

    SyncRun activeRun = findActiveRun(config.getId(), sourceInstance, exclusiveScope);
    SyncRun reusableForegroundRun =
        findReusableForegroundRun(activeSourceRuns, runType, sourceTables, exclusiveScope);
    if (reusableForegroundRun != null) {
      if (runType == SyncRunType.INCREMENTAL_SYNC) {
        incrementalRerunService.requestRerun(
            reusableForegroundRun, effectiveTriggerType, reason);
      }
      return reusedRun(
          reusableForegroundRun,
          apiType,
          "相同的前台刷新已在队列中或正在执行，已复用现有运行单元。");
    }
    if (runType == SyncRunType.FULL_SYNC && activeRun != null && activeRun.getRunType() == SyncRunType.FULL_SYNC) {
      return reusedRun(activeRun, apiType, "当前全量同步正在执行，已复用现有运行单元。");
    }

    if (runType == SyncRunType.FULL_SYNC) {
      mergeQueuedLowerPriorityMirrorRuns(config.getId(), sourceInstance, exclusiveScope, now);
    } else if (runType == SyncRunType.FACT_REFRESH
        && activeRun != null
        && activeRun.getRunType() == SyncRunType.FACT_REFRESH) {
      return reusedRun(activeRun, apiType, "当前数据源的事实发布任务已在队列中或正在执行，已复用现有任务。");
    } else if (runType == SyncRunType.FULL_COMPENSATION_SCAN) {
      SyncRun sameRun = activeRunOfType(activeSourceRuns, runType);
      if (sameRun != null) {
        return reusedRun(sameRun, apiType, "补偿同步已在队列中或正在执行，跳过重复提交。");
      }
    } else if (runType == SyncRunType.DELETE_RECONCILIATION) {
      SyncRun sameRun = activeRunOfType(activeSourceRuns, runType);
      if (sameRun != null) {
        return reusedRun(sameRun, apiType, "删除反熵已在队列中或正在执行，跳过重复提交。");
      }
    }

    SyncRun run = new SyncRun();
    run.setRunId(SyncRunIdGenerator.generate(runType, sourceInstance));
    run.setConfigId(config.getId());
    run.setSourceInstance(sourceInstance);
    run.setRunType(runType);
    run.setTriggerType(effectiveTriggerType);
    run.setStatus(SyncRunStatus.QUEUED);
    run.setPriority(policyService.priorityOf(runType));
    run.setExclusiveScope(exclusiveScope);
    run.setParentRunId(parentRunId);
    run.setCancelRequested(false);
    run.setSubmittedBy(null);
    run.setRequestReason(reason);
    run.setPayloadJson(
        buildPayloadJson(
            apiType,
            effectiveTriggerType,
            reason,
            sourceTables,
            primaryTableName,
            parentRunId,
            fullBuild,
            extraPayload));
    run.setThreadMode(threadBudgetResolver.effectiveMode(config));
    run.setThreadValue(threadBudgetResolver.effectiveValue(config));
    run.setResolvedWorkerCount(threadBudgetResolver.resolve(config));
    run.setPlannedTableCount(sourceTables.size());
    run.setCompletedTableCount(0);
    run.setScannedRows(0L);
    run.setAppliedRows(0L);
    run.setCreatedAt(now);
    run.setUpdatedAt(now);
    syncRunMapper.insert(run);
    if (runType == SyncRunType.INCREMENTAL_SYNC) {
      incrementalRerunService.adoptPendingRerun(run);
    }

    log.info(
        "Queued sync run, runId={}, type={}, scope={}, sourceTables={}",
        run.getRunId(),
        runType,
        exclusiveScope,
        sourceTables);
    return new SyncRunSubmissionResult(
        run.getId(),
        apiType,
        SyncStatus.QUEUED,
        SyncSubmissionAction.QUEUED,
        now,
        "同步已提交，等待调度器执行。");
  }

  private SyncRunSubmissionResult reusedRun(SyncRun activeRun, SyncType apiType, String message) {
    return new SyncRunSubmissionResult(
        activeRun.getId(),
        apiType,
        policyService.toApiStatus(activeRun),
        reuseAction(activeRun),
        LocalDateTime.now(),
        message);
  }

  private SyncSubmissionAction reuseAction(SyncRun activeRun) {
    return activeRun != null
            && (activeRun.getStatus() == SyncRunStatus.QUEUED
                || activeRun.getStatus() == SyncRunStatus.PAUSED)
        ? SyncSubmissionAction.REUSED_QUEUED
        : SyncSubmissionAction.REUSED_ACTIVE;
  }

  private void lockExclusiveScope(String exclusiveScope) {
    jdbcTemplate.queryForObject("select pg_advisory_xact_lock(hashtext(?))", Object.class, exclusiveScope);
  }

  private void mergeQueuedLowerPriorityMirrorRuns(
      Long configId,
      String sourceInstance,
      String exclusiveScope,
      LocalDateTime now) {
    int merged =
        jdbcTemplate.update(
            """
            update sync_runs
               set status = 'MERGED',
                   finished_at = coalesce(finished_at, ?),
                   updated_at = ?,
                   error_message = 'Merged into a full sync submitted for the same source'
             where config_id = ?
               and source_instance = ?
               and exclusive_scope = ?
               and status = 'QUEUED'
               and priority < ?
               and run_type in (
                 'INCREMENTAL_SYNC', 'TABLE_REFRESH', 'SYSTEM_HOOK',
                 'FULL_COMPENSATION_SCAN', 'DELETE_RECONCILIATION')
            """,
            now,
            now,
            configId,
            sourceInstance,
            exclusiveScope,
            policyService.priorityOf(SyncRunType.FULL_SYNC));
    if (merged > 0) {
      log.info("Merged {} queued lower-priority mirror run(s) into submitted full sync, scope={}", merged, exclusiveScope);
    }
  }

  private SyncRun findReusableForegroundRun(
      List<SyncRun> activeRuns,
      SyncRunType requestedType,
      List<String> requestedTables,
      String exclusiveScope) {
    if (!isForegroundRun(requestedType) || activeRuns == null || activeRuns.isEmpty()) {
      return null;
    }
    List<String> normalizedRequestedTables = normalizeTables(requestedTables);
    return activeRuns.stream()
        .filter(run -> Objects.equals(exclusiveScope, run.getExclusiveScope()))
        .filter(run -> run.getRunType() == requestedType)
        .filter(
            run ->
                requestedType != SyncRunType.TABLE_REFRESH
                    || normalizeTables(sourceTablesOf(run)).equals(normalizedRequestedTables))
        .findFirst()
        .orElse(null);
  }

  private boolean isForegroundRun(SyncRunType runType) {
    return runType == SyncRunType.INCREMENTAL_SYNC || runType == SyncRunType.TABLE_REFRESH;
  }

  private SyncRun findActiveRun(Long configId, String sourceInstance, String exclusiveScope) {
    List<SyncRun> runs =
        syncRunMapper.selectList(
            new LambdaQueryWrapper<SyncRun>()
                .eq(SyncRun::getConfigId, configId)
                .eq(SyncRun::getSourceInstance, sourceInstance)
                .eq(SyncRun::getExclusiveScope, exclusiveScope)
                .in(SyncRun::getStatus, SyncRunStateMachine.activeStatuses())
                .orderByAsc(SyncRun::getCreatedAt)
                .last("limit 1"));
    if (runs == null || runs.isEmpty()) {
      return null;
    }
    return runs.getFirst();
  }

  private List<SyncRun> findActiveRunsForSource(Long configId, String sourceInstance) {
    List<SyncRun> runs =
        syncRunMapper.selectList(
            new LambdaQueryWrapper<SyncRun>()
                .eq(SyncRun::getConfigId, configId)
                .eq(SyncRun::getSourceInstance, sourceInstance)
                .in(SyncRun::getStatus, SyncRunStateMachine.activeStatuses())
                .orderByAsc(SyncRun::getCreatedAt)
                .orderByAsc(SyncRun::getId));
    if (runs == null || runs.isEmpty()) {
      return List.of();
    }
    return List.copyOf(runs);
  }

  private boolean isManualFullRebuild(SyncRunType runType, Map<String, Object> extraPayload) {
    return runType == SyncRunType.FACT_REFRESH
        && extraPayload != null
        && Boolean.TRUE.equals(extraPayload.get("manualFullRebuild"));
  }

  private boolean isManualFullRebuild(SyncRun run) {
    if (run == null || run.getRunType() != SyncRunType.FACT_REFRESH) {
      return false;
    }
    SyncRunPayload payload = jsonUtils.fromJson(run.getPayloadJson(), SyncRunPayload.typeReference());
    return payload != null && payload.manualFullRebuildEnabled();
  }

  private SyncRun activeRunOfType(List<SyncRun> activeRuns, SyncRunType runType) {
    if (activeRuns == null || activeRuns.isEmpty()) {
      return null;
    }
    return activeRuns.stream()
        .filter(run -> run.getRunType() == runType)
        .findFirst()
        .orElse(null);
  }

  private String buildPayloadJson(
      SyncType apiType,
      SyncTriggerType triggerType,
      String reason,
      List<String> sourceTables,
      String primaryTableName,
      Long parentRunId,
      Boolean fullBuild,
      Map<String, Object> extraPayload) {
    SyncRunPayload payload =
        SyncRunPayload.create(apiType, triggerType, reason, sourceTables, primaryTableName, parentRunId, fullBuild);
    return jsonUtils.toJson(payload.toMap(extraPayload));
  }

  private List<String> sourceTablesOf(SyncRun run) {
    if (run == null || run.getPayloadJson() == null || run.getPayloadJson().isBlank()) {
      return List.of();
    }
    SyncRunPayload payload = jsonUtils.fromJson(run.getPayloadJson(), SyncRunPayload.typeReference());
    if (payload == null) {
      return List.of();
    }
    return payload.normalizedSourceTables();
  }

  private List<String> normalizeTables(List<String> sourceTables) {
    if (sourceTables == null || sourceTables.isEmpty()) {
      return List.of();
    }
    Set<String> normalized = new LinkedHashSet<>();
    for (String sourceTable : sourceTables) {
      if (sourceTable == null || sourceTable.isBlank()) {
        continue;
      }
      normalized.add(GitlabSourceInstanceSupport.normalizeSourceTableName(sourceTable));
    }
    return List.copyOf(normalized);
  }

  /** 自动增量调度的持久运行快照。 */
  public record IncrementalScheduleState(
      LocalDateTime lastSubmittedAt, boolean activeIncremental) {}
}
