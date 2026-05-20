package com.data.collection.platform.service.sync;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

  public SyncRunStatusService(
      SyncRunMapper syncRunMapper,
      JdbcTemplate jdbcTemplate,
      SyncRunPolicyService policyService,
      SyncRunLogService logService) {
    this.syncRunMapper = syncRunMapper;
    this.jdbcTemplate = jdbcTemplate;
    this.policyService = policyService;
    this.logService = logService;
  }

  public MirrorStatusResponse getStatus(GitlabSyncConfig config) {
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
          null);
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
        null);
  }

  public Map<String, Object> tableDiagnostics(GitlabSyncConfig config) {
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("configId", config.getId());
    response.put("sourceInstance", sourceInstance);
    response.put("generatedAt", LocalDateTime.now().toString());
    response.put("status", diagnosticsStatus(config));
    response.put("message", "统一同步运行诊断");
    response.put("tableCount", countFor(config, sourceInstance, "select count(*) from sync_run_table_states where config_id = ? and source_instance = ?"));
    response.put(
        "dirtyTableCount",
        countFor(config, sourceInstance, "select count(*) from sync_run_table_states where config_id = ? and source_instance = ? and dirty_flag = true"));
    response.put(
        "pendingTaskCount",
        countTasks(config, sourceInstance, "QUEUED"));
    response.put(
        "runningTaskCount",
        countTasks(config, sourceInstance, "RUNNING"));
    response.put(
        "retryingTaskCount",
        countTasks(config, sourceInstance, "RETRYING"));
    response.put(
        "failedTaskCount",
        countTasks(config, sourceInstance, "FAILED"));
    response.put(
        "timedOutTaskCount",
        countTasks(config, sourceInstance, "TIMEOUT"));
    response.put("tables", List.of());
    return response;
  }

  private List<Map<String, Object>> recentLogs(GitlabSyncConfig config) {
    return logService == null ? List.of() : logService.recentLogs(config, 10);
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

  private String diagnosticsStatus(GitlabSyncConfig config) {
    SyncRun currentRun = findCurrentRun(config);
    return currentRun == null ? "IDLE" : policyService.toApiStatus(currentRun).name();
  }

  private int countTasks(GitlabSyncConfig config, String sourceInstance, String status) {
    Integer count =
        jdbcTemplate.queryForObject(
            """
            select count(*)
              from sync_run_table_tasks
             where config_id = ?
               and source_instance = ?
               and status = ?
            """,
            Integer.class,
            config.getId(),
            sourceInstance,
            status);
    return count == null ? 0 : count;
  }

  private int countFor(GitlabSyncConfig config, String sourceInstance, String sql) {
    Integer count = jdbcTemplate.queryForObject(sql, Integer.class, config.getId(), sourceInstance);
    return count == null ? 0 : count;
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
