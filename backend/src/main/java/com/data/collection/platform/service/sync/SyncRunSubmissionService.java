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
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class SyncRunSubmissionService {
  private static final int RUN_ID_MAX_LENGTH = 64;
  private static final int RUN_ID_SOURCE_SEGMENT_MAX_LENGTH = 24;

  private final SyncRunMapper syncRunMapper;
  private final SyncRunPolicyService policyService;
  private final JdbcTemplate jdbcTemplate;
  private final JsonUtils jsonUtils;
  private final SyncThreadBudgetResolver threadBudgetResolver;
  private final SyncRunPublicationFenceService publicationFenceService;

  public SyncRunSubmissionService(
      SyncRunMapper syncRunMapper,
      SyncRunPolicyService policyService,
      JdbcTemplate jdbcTemplate,
      JsonUtils jsonUtils,
      SyncThreadBudgetResolver threadBudgetResolver,
      SyncRunPublicationFenceService publicationFenceService) {
    this.syncRunMapper = syncRunMapper;
    this.policyService = policyService;
    this.jdbcTemplate = jdbcTemplate;
    this.jsonUtils = jsonUtils;
    this.threadBudgetResolver = threadBudgetResolver;
    this.publicationFenceService = publicationFenceService;
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

  @Transactional
  public SyncRunSubmissionResult submitFactRefresh(
      GitlabSyncConfig config, Long parentRunId, boolean full, String reason) {
    return submitRun(
        config,
        SyncType.COMPENSATION,
        SyncRunType.FACT_REFRESH,
        SyncTriggerType.SCHEDULE,
        reason,
        List.of(),
        null,
        parentRunId,
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
    lockSourceSubmission(config.getId(), sourceInstance);
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
        && sameFactRefreshParent(activeRun, parentRunId)) {
      return reusedRun(activeRun, apiType, "当前镜像任务的事实刷新已提交，已复用现有任务。");
    } else if (runType == SyncRunType.FULL_COMPENSATION_SCAN && activeRun != null) {
      return reusedRun(activeRun, apiType, "补偿同步已在队列中或正在执行，跳过重复提交。");
    } else if (!isForegroundRun(runType)
        && isMirrorRun(runType)
        && activeRun != null
        && shouldReuseMirrorRun(activeRun, runType, sourceTables)) {
      return new SyncRunSubmissionResult(
          activeRun.getId(),
          apiType,
          policyService.toApiStatus(activeRun),
          SyncSubmissionAction.DEDUPED,
          now,
          "本次刷新请求已合并到同一数据源正在执行的同步任务中。");
    }

    SyncRun run = new SyncRun();
    run.setRunId(generateRunId(runType, sourceInstance));
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

  private void lockSourceSubmission(Long configId, String sourceInstance) {
    String sourceKey = "source:" + configId + ":" + sourceInstance + ":submission";
    jdbcTemplate.queryForObject("select pg_advisory_xact_lock(hashtext(?))", Object.class, sourceKey);
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
               and run_type in ('INCREMENTAL_SYNC', 'TABLE_REFRESH', 'SYSTEM_HOOK', 'FULL_COMPENSATION_SCAN')
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

  private boolean isMirrorRun(SyncRunType runType) {
    return runType == SyncRunType.FULL_SYNC
        || runType == SyncRunType.INCREMENTAL_SYNC
        || runType == SyncRunType.TABLE_REFRESH
        || runType == SyncRunType.SYSTEM_HOOK
        || runType == SyncRunType.FULL_COMPENSATION_SCAN;
  }

  private boolean shouldReuseMirrorRun(SyncRun activeRun, SyncRunType requestedType, List<String> requestedTables) {
    if (activeRun == null || activeRun.getRunType() == null) {
      return false;
    }
    if (activeRun.getRunType() == SyncRunType.FULL_SYNC
        || activeRun.getRunType() == SyncRunType.INCREMENTAL_SYNC
        || activeRun.getRunType() == SyncRunType.SYSTEM_HOOK
        || activeRun.getRunType() == SyncRunType.FULL_COMPENSATION_SCAN) {
      return true;
    }
    if (requestedType == SyncRunType.INCREMENTAL_SYNC
        || requestedType == SyncRunType.SYSTEM_HOOK
        || requestedType == SyncRunType.FULL_COMPENSATION_SCAN) {
      return true;
    }
    if (requestedType != SyncRunType.TABLE_REFRESH || activeRun.getRunType() != SyncRunType.TABLE_REFRESH) {
      return false;
    }
    return normalizeTables(sourceTablesOf(activeRun)).equals(normalizeTables(requestedTables));
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

  private boolean sameFactRefreshParent(SyncRun activeRun, Long parentRunId) {
    return activeRun.getRunType() == SyncRunType.FACT_REFRESH
        && Objects.equals(activeRun.getParentRunId(), parentRunId);
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

  private String generateRunId(SyncRunType runType, String sourceInstance) {
    String randomPart = UUID.randomUUID().toString().replace("-", "");
    String sourceSegment = sourceInstance == null ? "default" : sourceInstance;
    if (sourceSegment.length() > RUN_ID_SOURCE_SEGMENT_MAX_LENGTH) {
      sourceSegment = sourceSegment.substring(0, RUN_ID_SOURCE_SEGMENT_MAX_LENGTH);
    }
    String runId = "sr_" + runTypeAlias(runType) + "_" + sourceSegment + "_" + randomPart;
    if (runId.length() <= RUN_ID_MAX_LENGTH) {
      return runId;
    }
    int allowedSourceLength =
        RUN_ID_MAX_LENGTH
            - "sr_".length()
            - runTypeAlias(runType).length()
            - 2
            - randomPart.length();
    sourceSegment = sourceSegment.substring(0, Math.max(1, allowedSourceLength));
    return "sr_" + runTypeAlias(runType) + "_" + sourceSegment + "_" + randomPart;
  }

  private String runTypeAlias(SyncRunType runType) {
    return switch (runType) {
      case FULL_SYNC -> "fs";
      case INCREMENTAL_SYNC -> "is";
      case TABLE_REFRESH -> "tr";
      case SYSTEM_HOOK -> "sh";
      case FULL_COMPENSATION_SCAN -> "fc";
      case FACT_REFRESH -> "fr";
    };
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
}
