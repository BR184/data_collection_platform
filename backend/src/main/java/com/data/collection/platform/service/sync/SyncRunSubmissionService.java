package com.data.collection.platform.service.sync;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.data.collection.platform.common.JsonUtils;
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

  public SyncRunSubmissionService(
      SyncRunMapper syncRunMapper,
      SyncRunPolicyService policyService,
      JdbcTemplate jdbcTemplate,
      JsonUtils jsonUtils,
      SyncThreadBudgetResolver threadBudgetResolver) {
    this.syncRunMapper = syncRunMapper;
    this.policyService = policyService;
    this.jdbcTemplate = jdbcTemplate;
    this.jsonUtils = jsonUtils;
    this.threadBudgetResolver = threadBudgetResolver;
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
    List<String> normalizedTables = normalizeTables(sourceTables);
    return submitRun(
        config,
        SyncType.INCREMENTAL,
        SyncRunType.TABLE_REFRESH,
        SyncTriggerType.MANUAL,
        reason,
        normalizedTables,
        normalizedTables.isEmpty() ? null : normalizedTables.getFirst());
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
    lockExclusiveScope(exclusiveScope);

    SyncRun activeRun = findActiveRun(config.getId(), sourceInstance, exclusiveScope);
    if (runType == SyncRunType.FULL_SYNC && activeRun != null && activeRun.getRunType() == SyncRunType.FULL_SYNC) {
      return reusedRun(activeRun, apiType, "当前全量同步正在执行，已复用现有运行单元。");
    }

    if (runType == SyncRunType.FULL_SYNC) {
      mergeQueuedLowerPriorityMirrorRuns(config.getId(), sourceInstance, exclusiveScope, now);
    } else if (runType == SyncRunType.FACT_REFRESH && activeRun != null) {
      return reusedRun(activeRun, apiType, "事实刷新已在队列中或正在执行，已复用现有任务。");
    } else if ((runType == SyncRunType.COMPENSATION_SCAN || runType == SyncRunType.FULL_COMPENSATION_SCAN)
        && activeRun != null) {
      return reusedRun(activeRun, apiType, "补偿同步已在队列中或正在执行，跳过重复提交。");
    } else if (isMirrorRun(runType) && activeRun != null && shouldReuseMirrorRun(activeRun, runType, sourceTables)) {
      if (runType == SyncRunType.TABLE_REFRESH) {
        recordMergeEvent(activeRun, config, sourceTables, reason, primaryTableName, now);
      }
      return new SyncRunSubmissionResult(
          activeRun.getId(),
          apiType,
          policyService.toApiStatus(activeRun),
          SyncSubmissionAction.DEDUPED,
          now,
          "本次刷新请求已合并到同一数据源正在执行的同步任务中。");
    }

    if (runType == SyncRunType.TABLE_REFRESH
        && activeRun != null
        && policyService.shouldMergeTableRefresh(activeRun)) {
      recordMergeEvent(activeRun, config, sourceTables, reason, primaryTableName, now);
      return new SyncRunSubmissionResult(
          activeRun.getId(),
          apiType,
          policyService.toApiStatus(activeRun),
          SyncSubmissionAction.DEDUPED,
          now,
          "已合并到当前全量同步，完成后将以全量结果为准。");
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
        activeRun.getStatus() == SyncRunStatus.QUEUED
            ? SyncSubmissionAction.REUSED_QUEUED
            : SyncSubmissionAction.REUSED_ACTIVE,
        LocalDateTime.now(),
        message);
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
               and run_type in ('INCREMENTAL_SYNC', 'TABLE_REFRESH', 'SYSTEM_HOOK', 'COMPENSATION_SCAN', 'FULL_COMPENSATION_SCAN')
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
        || runType == SyncRunType.COMPENSATION_SCAN
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

  private void recordMergeEvent(
      SyncRun activeRun,
      GitlabSyncConfig config,
      List<String> sourceTables,
      String reason,
      String primaryTableName,
      LocalDateTime now) {
    Map<String, Object> payload = new java.util.LinkedHashMap<>();
    payload.put("requestType", SyncRunType.TABLE_REFRESH.name());
    payload.put("mergedIntoRunId", activeRun.getId());
    payload.put("configId", config.getId());
    payload.put("sourceInstance", activeRun.getSourceInstance());
    payload.put("sourceTables", sourceTables);
    if (reason != null) {
      payload.put("reason", reason);
    }
    String payloadJson = jsonUtils.toJson(payload);
    jdbcTemplate.update(
        """
        insert into sync_run_events (run_id, config_id, source_instance, event_type, table_name, message, payload_json, created_at)
        values (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        activeRun.getId(),
        config.getId(),
        activeRun.getSourceInstance(),
        "TABLE_REFRESH_MERGED",
        primaryTableName,
        "已合并到当前全量同步，完成后将以全量结果为准。",
        payloadJson,
        now);
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
      case COMPENSATION_SCAN -> "cs";
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
