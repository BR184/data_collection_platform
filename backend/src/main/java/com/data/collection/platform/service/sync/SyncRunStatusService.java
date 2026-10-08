package com.data.collection.platform.service.sync;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.MirrorStatusResponse;
import com.data.collection.platform.entity.SyncProgress;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.mapper.SyncRunMapper;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SyncRunStatusService {
  private final SyncRunMapper syncRunMapper;
  private final JdbcTemplate jdbcTemplate;
  private final SyncRunPolicyService policyService;
  private final SyncRunLogService logService;
  private final GitlabMirrorProperties properties;
  private final SyncRunFailureDiagnosticsService failureDiagnosticsService;

  public SyncRunStatusService(
      SyncRunMapper syncRunMapper,
      JdbcTemplate jdbcTemplate,
      SyncRunPolicyService policyService,
      SyncRunLogService logService,
      GitlabMirrorProperties properties,
      SyncRunFailureDiagnosticsService failureDiagnosticsService) {
    this.syncRunMapper = syncRunMapper;
    this.jdbcTemplate = jdbcTemplate;
    this.policyService = policyService;
    this.logService = logService;
    this.properties = properties;
    this.failureDiagnosticsService = failureDiagnosticsService;
  }

  /** 无详情查询的默认入口。 */
  public MirrorStatusResponse getStatus(GitlabSyncConfig config) {
    return getStatus(config, null, null);
  }

  /**
   * 状态查询；可选地附加指定运行的分页明细与人工待处理列表。
   *
   * @param details 指定运行的分页明细查询；为空时响应不含该区块
   * @param pending 人工待处理列表查询；为空时响应不含该区块
   */
  public MirrorStatusResponse getStatus(
      GitlabSyncConfig config, DetailsQuery details, PendingQuery pending) {
    Map<String, Object> detailsBlock = details == null ? null : buildDetails(config, details);
    Map<String, Object> pendingBlock = pending == null ? null : buildPending(config, pending);
    SyncRun currentRun = findCurrentRun(config);
    if (currentRun == null) {
      return new MirrorStatusResponse(
          config,
          null,
          SyncStatus.IDLE,
          "当前没有正在执行的同步任务",
          null,
          null,
          recentLogs(config),
          null,
          null,
          null,
          null,
          detailsBlock,
          pendingBlock);
    }
    SyncProgress progress = buildProgress(currentRun);
    return new MirrorStatusResponse(
        config,
        buildCurrentTask(currentRun),
        policyService.toApiStatus(currentRun),
        currentMessage(currentRun),
        currentRun.getStartedAt(),
        progress,
        recentLogs(config),
        null,
        null,
        null,
        currentRun.getResolvedWorkerCount(),
        detailsBlock,
        pendingBlock);
  }

  /**
   * 按运行编号分页读取可在界面展开的全部定位项或相关事件。
   *
   * <p>运行必须属于当前配置与来源；旧运行即使已不在最近日志列表内，只要记录仍在即可查询。
   */
  private Map<String, Object> buildDetails(GitlabSyncConfig config, DetailsQuery query) {
    SyncRun run = syncRunMapper.selectById(query.runId());
    if (run == null
        || config == null
        || !config.getId().equals(run.getConfigId())
        || !GitlabSourceInstanceSupport.sourceInstanceOf(config).equals(run.getSourceInstance())) {
      throw new BizException("该运行不属于当前数据源，无法查看明细");
    }
    boolean events = DetailsQuery.SECTION_EVENTS.equals(query.section());
    List<Map<String, Object>> items =
        events
            ? failureDiagnosticsService.events(query.runId(), query.offset(), query.limit())
            : failureDiagnosticsService.diagnostics(query.runId(), query.offset(), query.limit());
    long total =
        events
            ? failureDiagnosticsService.eventCount(query.runId())
            : failureDiagnosticsService.diagnosticCount(query.runId());
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runId", query.runId());
    details.put("section", query.section());
    details.put("offset", query.offset());
    details.put("limit", query.limit());
    details.put("total", total);
    details.put("items", items);
    details.put("hasMore", query.offset() + items.size() < total);
    return details;
  }

  private Map<String, Object> buildPending(GitlabSyncConfig config, PendingQuery query) {
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    List<Map<String, Object>> items =
        failureDiagnosticsService.pendingTasks(
            config.getId(), sourceInstance, query.offset(), query.limit());
    long total = failureDiagnosticsService.pendingTaskCount(config.getId(), sourceInstance);
    Map<String, Object> pending = new LinkedHashMap<>();
    pending.put("offset", query.offset());
    pending.put("limit", query.limit());
    pending.put("total", total);
    pending.put("items", items);
    pending.put("hasMore", query.offset() + items.size() < total);
    return pending;
  }

  /** 运行详情查询参数；{@code runId} 是 sync_runs 数据库主键。 */
  public record DetailsQuery(Long runId, String section, int offset, int limit) {
    public static final String SECTION_DIAGNOSTICS = "DIAGNOSTICS";
    public static final String SECTION_EVENTS = "EVENTS";

    /** 解析查询参数；runId 为空表示不查询明细。 */
    public static DetailsQuery of(Long runId, String section, Integer offset, Integer limit) {
      if (runId == null) {
        return null;
      }
      return new DetailsQuery(
          runId,
          SyncRunFailureDiagnosticsService.normalizeSection(section),
          SyncRunFailureDiagnosticsService.normalizeOffset(offset),
          SyncRunFailureDiagnosticsService.normalizeLimit(limit));
    }
  }

  /** 人工待处理列表查询参数。 */
  public record PendingQuery(int offset, int limit) {
    /** 解析查询参数；两个参数都为空表示不查询列表。 */
    public static PendingQuery of(Integer offset, Integer limit) {
      if (offset == null && limit == null) {
        return null;
      }
      return new PendingQuery(
          SyncRunFailureDiagnosticsService.normalizeOffset(offset),
          SyncRunFailureDiagnosticsService.normalizeLimit(limit));
    }
  }

  private List<Map<String, Object>> recentLogs(GitlabSyncConfig config) {
    return logService == null ? List.of() : logService.recentLogs(config, recentLogsLimit());
  }

  private int recentLogsLimit() {
    if (properties == null) {
      return 100;
    }
    return Math.max(1, properties.getRecentLogsLimit());
  }

  private SyncRun findCurrentRun(GitlabSyncConfig config) {
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    List<SyncRun> runs =
        syncRunMapper.selectList(
            new LambdaQueryWrapper<SyncRun>()
                .eq(SyncRun::getConfigId, config.getId())
                .eq(SyncRun::getSourceInstance, sourceInstance)
                .in(SyncRun::getStatus, SyncRunStateMachine.activeStatuses())
                .orderByAsc(SyncRun::getCreatedAt)
                .orderByAsc(SyncRun::getId));
    if (runs == null || runs.isEmpty()) {
      return null;
    }
    return runs.stream()
        .filter(run -> run.getStatus() == SyncRunStatus.RUNNING || run.getStatus() == SyncRunStatus.CANCELLING)
        .findFirst()
        .orElse(runs.getFirst());
  }

  private Map<String, Object> buildCurrentTask(SyncRun run) {
    Map<String, Object> task = new LinkedHashMap<>();
    task.put("id", run.getId());
    task.put("runId", run.getRunId());
    task.put("taskType", policyService.toApiType(run.getRunType()).name());
    task.put("triggerType", run.getTriggerType() == null ? null : run.getTriggerType().name());
    task.put("sourceMode", null);
    task.put("scopeKey", run.getExclusiveScope());
    task.put("dedupeKey", run.getExclusiveScope());
    task.put("parentRunId", run.getParentRunId());
    task.put("status", policyService.toApiStatus(run).name());
    task.put("cancelRequested", Boolean.TRUE.equals(run.getCancelRequested()));
    task.put("pendingResync", false);
    task.put("retryCount", 0);
    task.put("queuedAt", run.getCreatedAt());
    task.put("startedAt", run.getStartedAt());
    task.put("finishedAt", run.getFinishedAt());
    task.put("heartbeatAt", run.getHeartbeatAt());
    task.put("lockOwner", run.getLeaseOwner());
    task.put("payloadJson", run.getPayloadJson());
    task.put("resolvedWorkerCount", run.getResolvedWorkerCount());
    return task;
  }

  private SyncProgress buildProgress(SyncRun run) {
    if (run.getRunType() == SyncRunType.FACT_REFRESH) {
      return buildFactRefreshProgress(run);
    }
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            """
            select status,
                   count(*) as count,
                   coalesce(sum(rows_scanned), 0) as rows_scanned,
                   coalesce(sum(rows_applied), 0) as rows_applied
              from sync_run_table_tasks
             where run_id = ?
             group by status
            """,
            run.getId());
    long totalTables = 0;
    long completedTables = 0;
    long runningTables = 0;
    long failedTables = 0;
    long rowsScanned = 0;
    long rowsApplied = 0;
    for (Map<String, Object> row : rows) {
      String status = String.valueOf(row.get("status"));
      long count = numberValue(row.get("count"));
      totalTables += count;
      if (isCompletedStatus(status)) {
        completedTables += count;
      }
      if (SyncRunStatus.RUNNING.name().equals(status) || SyncRunStatus.RETRYING.name().equals(status)) {
        runningTables += count;
      }
      if (SyncRunStatus.FAILED.name().equals(status) || SyncRunStatus.TIMEOUT.name().equals(status)) {
        failedTables += count;
      }
      rowsScanned += numberValue(row.get("rows_scanned"));
      rowsApplied += numberValue(row.get("rows_applied"));
    }
    List<String> activeTables = activeTables(run.getId());
    SyncProgress progress = new SyncProgress();
    progress.setPhase(run.getRunType() == null ? null : run.getRunType().name());
    progress.setTotalTables(Math.toIntExact(totalTables));
    progress.setCompletedTables(Math.toIntExact(completedTables));
    progress.setRunningTables(Math.toIntExact(runningTables));
    progress.setFailedTables(Math.toIntExact(failedTables));
    progress.setSyncedRecords(Math.toIntExact(rowsApplied));
    progress.setScannedRows(rowsScanned);
    progress.setAppliedRows(rowsApplied);
    progress.setRecordsPerSecond(recordsPerSecond(run, rowsApplied));
    progress.setEstimatedRemainingSeconds(estimatedRemainingSeconds(run, totalTables, completedTables, rowsApplied));
    progress.setFactRefreshStatus(null);
    progress.setActiveTableTasks(activeTables);
    progress.setCurrentTable(String.join(", ", activeTables));
    progress.setStartedAt(run.getStartedAt() == null ? LocalDateTime.now() : run.getStartedAt());
    return progress;
  }

  private SyncProgress buildFactRefreshProgress(SyncRun run) {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            """
            select status,
                   count(*) as count,
                   coalesce(sum(affected_rows), 0) as affected_rows
              from fact_build_tasks
             where run_id = ?
             group by status
            """,
            String.valueOf(run.getId()));
    long totalTasks = 0L;
    long completedTasks = 0L;
    long runningTasks = 0L;
    long failedTasks = 0L;
    long affectedRows = 0L;
    for (Map<String, Object> row : rows) {
      String status = String.valueOf(row.get("status"));
      long count = numberValue(row.get("count"));
      totalTasks += count;
      if (isCompletedStatus(status)) {
        completedTasks += count;
      }
      if (SyncRunStatus.RUNNING.name().equals(status) || SyncRunStatus.RETRYING.name().equals(status)) {
        runningTasks += count;
      }
      if (SyncRunStatus.FAILED.name().equals(status) || SyncRunStatus.TIMEOUT.name().equals(status)) {
        failedTasks += count;
      }
      affectedRows += numberValue(row.get("affected_rows"));
    }
    SyncProgress progress = new SyncProgress();
    progress.setPhase(SyncRunType.FACT_REFRESH.name());
    progress.setTotalTables(Math.toIntExact(totalTasks));
    progress.setCompletedTables(Math.toIntExact(completedTasks));
    progress.setRunningTables(Math.toIntExact(runningTasks));
    progress.setFailedTables(Math.toIntExact(failedTasks));
    progress.setSyncedRecords(Math.toIntExact(affectedRows));
    progress.setAppliedRows(affectedRows);
    progress.setRecordsPerSecond(recordsPerSecond(run, affectedRows));
    progress.setEstimatedRemainingSeconds(estimatedRemainingSeconds(run, totalTasks, completedTasks, affectedRows));
    progress.setFactRefreshStatus(run.getStatus() == null ? null : run.getStatus().name());
    progress.setActiveTableTasks(activeFactTasks(run.getId()));
    progress.setCurrentTable(String.join(", ", progress.getActiveTableTasks()));
    progress.setStartedAt(run.getStartedAt() == null ? LocalDateTime.now() : run.getStartedAt());
    return progress;
  }

  private List<String> activeFactTasks(Long runId) {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            """
            select fact_type
              from fact_build_tasks
             where run_id = ?
               and status in ('RUNNING', 'RETRYING')
             order by started_at nulls last, id
             limit ?
            """,
            String.valueOf(runId),
            5);
    return rows.stream()
        .map(row -> String.valueOf(row.get("fact_type")))
        .filter(value -> value != null && !value.isBlank() && !"null".equals(value))
        .toList();
  }

  private String currentMessage(SyncRun currentRun) {
    if (currentRun.getRunType() == SyncRunType.FACT_REFRESH) {
      return "事实刷新运行 " + currentRun.getRunId() + " 当前状态：" + currentRun.getStatus().name();
    }
    return "同步运行 " + currentRun.getRunId() + " 当前状态：" + currentRun.getStatus().name();
  }

  private List<String> activeTables(Long runId) {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            """
            select source_table, status, rows_applied
              from sync_run_table_tasks
             where run_id = ?
               and status in ('RUNNING', 'RETRYING')
             order by started_at nulls last, id
             limit ?
            """,
            runId,
            5);
    return rows.stream()
        .map(row -> String.valueOf(row.get("source_table")))
        .filter(value -> value != null && !value.isBlank() && !"null".equals(value))
        .toList();
  }

  private boolean isCompletedStatus(String status) {
    if (status == null || status.isBlank()) {
      return false;
    }
    try {
      return SyncRunStateMachine.isCompleted(SyncRunStatus.valueOf(status));
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }

  private long numberValue(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value == null) {
      return 0;
    }
    return Long.parseLong(String.valueOf(value));
  }

  private double recordsPerSecond(SyncRun run, long rowsApplied) {
    if (rowsApplied <= 0 || run.getStartedAt() == null) {
      return 0;
    }
    long seconds = Math.max(1, Duration.between(run.getStartedAt(), LocalDateTime.now()).toSeconds());
    return rowsApplied / (double) seconds;
  }

  private Long estimatedRemainingSeconds(
      SyncRun run,
      long totalTables,
      long completedTables,
      long rowsApplied) {
    if (run.getStartedAt() == null || rowsApplied <= 0 || totalTables <= completedTables || completedTables <= 0) {
      return null;
    }
    long elapsedSeconds = Math.max(1, Duration.between(run.getStartedAt(), LocalDateTime.now()).toSeconds());
    double secondsPerTable = elapsedSeconds / (double) completedTables;
    return Math.max(1L, Math.round((totalTables - completedTables) * secondsPerTable));
  }
}
