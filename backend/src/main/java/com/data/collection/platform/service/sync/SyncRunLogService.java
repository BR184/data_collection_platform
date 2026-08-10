package com.data.collection.platform.service.sync;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.mapper.SyncRunMapper;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SyncRunLogService {
  private final SyncRunMapper syncRunMapper;
  private final JdbcTemplate jdbcTemplate;
  private final SyncRunPolicyService policyService;
  private final JsonUtils jsonUtils;
  private final SyncIncrementalCoverageService incrementalCoverageService;

  public SyncRunLogService(
      SyncRunMapper syncRunMapper,
      JdbcTemplate jdbcTemplate,
      SyncRunPolicyService policyService,
      JsonUtils jsonUtils,
      SyncIncrementalCoverageService incrementalCoverageService) {
    this.syncRunMapper = syncRunMapper;
    this.jdbcTemplate = jdbcTemplate;
    this.policyService = policyService;
    this.jsonUtils = jsonUtils;
    this.incrementalCoverageService = incrementalCoverageService;
  }

  private record TaskLogSummary(int totalTasks, int completedTasks) {}

  public List<Map<String, Object>> recentLogs(GitlabSyncConfig config, int limit) {
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    List<SyncRun> runs =
        syncRunMapper.selectList(
            new LambdaQueryWrapper<SyncRun>()
                .eq(SyncRun::getConfigId, config.getId())
                .eq(SyncRun::getSourceInstance, sourceInstance)
                .orderByDesc(SyncRun::getCreatedAt)
                .orderByDesc(SyncRun::getId)
                .last("limit " + Math.max(1, limit)));
    if (runs == null || runs.isEmpty()) {
      return List.of();
    }
    return runs.stream().map(this::toLogRow).toList();
  }

  private Map<String, Object> toLogRow(SyncRun run) {
    TaskLogSummary taskSummary = taskLogSummary(run);
    int tableCount =
        taskSummary.totalTasks() > 0
            ? taskSummary.totalTasks()
            : run.getPlannedTableCount() == null ? 0 : run.getPlannedTableCount();
    int completedTableCount =
        taskSummary.totalTasks() > 0
            ? taskSummary.completedTasks()
            : run.getCompletedTableCount() == null ? 0 : run.getCompletedTableCount();
    Map<String, Object> payload = payloadMap(run);
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", run.getId());
    row.put("runId", run.getRunId());
    row.put("syncType", policyService.toApiType(run.getRunType()).name());
    row.put("runType", run.getRunType() == null ? null : run.getRunType().name());
    row.put("runStatus", run.getStatus() == null ? null : run.getStatus().name());
    row.put(
        "triggerType",
        run.getTriggerType() == null ? stringValue(payload.get("triggerType")) : run.getTriggerType().name());
    row.put("requestReason", run.getRequestReason());
    row.put("sourcePageKey", stringValue(payload.get("sourcePageKey")));
    row.put("triggerSurface", stringValue(payload.get("triggerSurface")));
    row.put("sourceTables", stringList(payload.get("sourceTables")));
    row.put("primaryTableName", stringValue(payload.get("primaryTableName")));
    row.put("parentRunId", run.getParentRunId() == null ? payload.get("parentRunId") : run.getParentRunId());
    row.put("parentRunRunId", stringValue(payload.get("parentRunRunId")));
    row.put("fullBuild", payload.get("fullBuild"));
    row.put("status", policyService.toApiStatus(run).name());
    row.put("freshnessStatus", freshnessStatus(run));
    row.put("deleteReconciliationStatus", deleteReconciliationStatus(run));
    row.put("message", latestEventMessage(run));
    row.put("tableCount", tableCount);
    row.put("completedTableCount", completedTableCount);
    row.put("recordCount", run.getAppliedRows() == null ? 0L : run.getAppliedRows());
    row.put("startedAt", run.getStartedAt());
    row.put("finishedAt", run.getFinishedAt());
    row.put("queuedAt", run.getCreatedAt());
    row.put("errorSummary", run.getErrorMessage());
    return row;
  }

  private String freshnessStatus(SyncRun run) {
    if (run == null || run.getRunType() != SyncRunType.INCREMENTAL_SYNC) {
      return "NOT_APPLICABLE";
    }
    if (run.getStatus() == SyncRunStatus.SUCCESS) {
      SyncIncrementalCoverageService.CoverageResult coverage =
          incrementalCoverageService.evaluate(run.getId());
      return coverage.complete() ? "CAUGHT_UP" : "NOT_CAUGHT_UP";
    }
    return SyncRunStateMachine.isActive(run.getStatus()) ? "VERIFYING" : "NOT_CAUGHT_UP";
  }

  private String deleteReconciliationStatus(SyncRun run) {
    if (run == null || run.getRunType() != SyncRunType.DELETE_RECONCILIATION) {
      return "NOT_APPLICABLE";
    }
    if (run.getStatus() == SyncRunStatus.SUCCESS) {
      return "COMPLETED";
    }
    return SyncRunStateMachine.isActive(run.getStatus()) ? "RUNNING" : "INCOMPLETE";
  }

  private Map<String, Object> payloadMap(SyncRun run) {
    if (run == null || run.getPayloadJson() == null || run.getPayloadJson().isBlank() || jsonUtils == null) {
      return Map.of();
    }
    try {
      return jsonUtils.toMap(run.getPayloadJson());
    } catch (IllegalStateException ignored) {
      return Map.of();
    }
  }

  private String stringValue(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
  }

  private List<String> stringList(Object value) {
    if (!(value instanceof List<?> values)) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    for (Object item : values) {
      String text = stringValue(item);
      if (text != null) {
        result.add(text);
      }
    }
    return List.copyOf(result);
  }

  private TaskLogSummary taskLogSummary(SyncRun run) {
    if (run == null || run.getId() == null) {
      return new TaskLogSummary(0, 0);
    }
    try {
      TaskLogSummary summary = jdbcTemplate.queryForObject(
          """
          select count(*) as total_tasks,
                 count(*) filter (
                   where status in ('SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'TIMEOUT', 'CANCELLED')
                 ) as completed_tasks
            from sync_run_table_tasks
           where run_id = ?
          """,
          (rs, rowNum) -> new TaskLogSummary(rs.getInt("total_tasks"), rs.getInt("completed_tasks")),
          run.getId());
      return summary == null ? new TaskLogSummary(0, 0) : summary;
    } catch (EmptyResultDataAccessException ignored) {
      return new TaskLogSummary(0, 0);
    }
  }

  private String latestEventMessage(SyncRun run) {
    try {
      String message =
          jdbcTemplate.queryForObject(
              """
              select message
                from sync_run_events
               where run_id = ?
                 and nullif(btrim(message), '') is not null
               order by created_at desc, id desc
               limit 1
              """,
              String.class,
              run.getId());
      if (message != null && !message.isBlank()) {
        return message;
      }
    } catch (EmptyResultDataAccessException ignored) {
      // Fall through to the run-level message.
    }
    if (run.getErrorMessage() != null && !run.getErrorMessage().isBlank()) {
      return run.getErrorMessage();
    }
    if (run.getRequestReason() != null && !run.getRequestReason().isBlank()) {
      return run.getRequestReason();
    }
    return "同步运行 " + run.getRunId() + " " + policyService.toApiStatus(run).name();
  }
}
