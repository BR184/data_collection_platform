package com.data.collection.platform.service.sync;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.mapper.SyncRunMapper;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
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

  public SyncRunLogService(
      SyncRunMapper syncRunMapper,
      JdbcTemplate jdbcTemplate,
      SyncRunPolicyService policyService) {
    this.syncRunMapper = syncRunMapper;
    this.jdbcTemplate = jdbcTemplate;
    this.policyService = policyService;
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
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", run.getId());
    row.put("runId", run.getRunId());
    row.put("syncType", policyService.toApiType(run.getRunType()).name());
    row.put("triggerType", run.getTriggerType() == null ? null : run.getTriggerType().name());
    row.put("status", policyService.toApiStatus(run).name());
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
    return "Sync run " + run.getRunId() + " " + policyService.toApiStatus(run).name();
  }
}
