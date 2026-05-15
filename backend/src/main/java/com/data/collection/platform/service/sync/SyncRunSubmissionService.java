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
import java.util.EnumSet;
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
  private static final Set<SyncRunStatus> ACTIVE_STATUSES =
      EnumSet.of(
          SyncRunStatus.SUBMITTED,
          SyncRunStatus.QUEUED,
          SyncRunStatus.RUNNING,
          SyncRunStatus.RETRYING,
          SyncRunStatus.CANCELLING);

  private final SyncRunMapper syncRunMapper;
  private final SyncRunPolicyService policyService;
  private final JdbcTemplate jdbcTemplate;
  private final JsonUtils jsonUtils;

  public SyncRunSubmissionService(
      SyncRunMapper syncRunMapper,
      SyncRunPolicyService policyService,
      JdbcTemplate jdbcTemplate,
      JsonUtils jsonUtils) {
    this.syncRunMapper = syncRunMapper;
    this.policyService = policyService;
    this.jdbcTemplate = jdbcTemplate;
    this.jsonUtils = jsonUtils;
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
  public SyncRunSubmissionResult submitRun(
      GitlabSyncConfig config,
      SyncType apiType,
      SyncRunType runType,
      SyncTriggerType triggerType,
      String reason,
      List<String> sourceTables,
      String primaryTableName) {
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    String exclusiveScope = policyService.exclusiveScopeOf(config, runType);
    LocalDateTime now = LocalDateTime.now();

    SyncRun activeRun = findActiveRun(config.getId(), sourceInstance, exclusiveScope);
    if (runType == SyncRunType.FULL_SYNC && activeRun != null && activeRun.getRunType() == SyncRunType.FULL_SYNC) {
      return reusedRun(activeRun, apiType, "当前全量同步正在执行，已复用现有运行单元。");
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
    run.setTriggerType(triggerType);
    run.setStatus(SyncRunStatus.QUEUED);
    run.setPriority(policyService.priorityOf(runType));
    run.setExclusiveScope(exclusiveScope);
    run.setCancelRequested(false);
    run.setSubmittedBy(null);
    run.setRequestReason(reason);
    run.setPayloadJson(buildPayloadJson(apiType, triggerType, reason, sourceTables, primaryTableName));
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

  private SyncRun findActiveRun(Long configId, String sourceInstance, String exclusiveScope) {
    List<SyncRun> runs =
        syncRunMapper.selectList(
            new LambdaQueryWrapper<SyncRun>()
                .eq(SyncRun::getConfigId, configId)
                .eq(SyncRun::getSourceInstance, sourceInstance)
                .eq(SyncRun::getExclusiveScope, exclusiveScope)
                .in(SyncRun::getStatus, ACTIVE_STATUSES)
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
      String primaryTableName) {
    Map<String, Object> payload = new java.util.LinkedHashMap<>();
    payload.put("syncType", apiType.name());
    if (triggerType != null) {
      payload.put("triggerType", triggerType.name());
    }
    if (reason != null) {
      payload.put("reason", reason);
    }
    payload.put("sourceTables", sourceTables);
    if (primaryTableName != null) {
      payload.put("primaryTableName", primaryTableName);
    }
    return jsonUtils.toJson(payload);
  }

  private String generateRunId(SyncRunType runType, String sourceInstance) {
    return "sr_" + runType.name().toLowerCase() + "_" + sourceInstance + "_" + UUID.randomUUID().toString().replace("-", "");
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
