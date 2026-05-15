package com.data.collection.platform.service.sync;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class SyncRunDispatcherService {
  private static final String DISPATCHER_OWNER = "sync-dispatcher";

  private final GitlabMirrorProperties properties;
  private final JdbcTemplate jdbcTemplate;
  private final SyncRunWorkerService workerService;

  public SyncRunDispatcherService(
      GitlabMirrorProperties properties,
      JdbcTemplate jdbcTemplate,
      SyncRunWorkerService workerService) {
    this.properties = properties;
    this.jdbcTemplate = jdbcTemplate;
    this.workerService = workerService;
  }

  @Scheduled(fixedDelayString = "${platform.gitlab-mirror.run-dispatcher-delay-ms:2000}")
  public void runOnce() {
    if (!properties.isSchedulerEnabled()) {
      return;
    }
    SyncRun nextRun = claimNextQueuedRun(DISPATCHER_OWNER, Math.max(1, properties.getHeartbeatTimeoutSeconds()));
    if (nextRun == null) {
      return;
    }
    workerService.executeRun(nextRun);
  }

  public SyncRun claimNextQueuedRun(String owner, int leaseSeconds) {
    try {
      return jdbcTemplate.queryForObject(
          """
          update sync_runs
             set status = 'RUNNING',
                 lease_owner = ?,
                 lease_until = current_timestamp + (? * interval '1 second'),
                 heartbeat_at = current_timestamp,
                 started_at = coalesce(started_at, current_timestamp),
                 updated_at = current_timestamp
           where id = (
             select candidate.id
               from sync_runs candidate
              where candidate.status = 'QUEUED'
                and not exists (
                      select 1
                        from sync_runs active
                       where active.exclusive_scope = candidate.exclusive_scope
                         and active.id <> candidate.id
                         and active.status in ('SUBMITTED', 'QUEUED', 'RUNNING', 'RETRYING', 'CANCELLING')
                )
              order by candidate.priority desc, candidate.created_at asc, candidate.id asc
              for update skip locked
              limit 1
           )
           returning *
          """,
          this::mapRun,
          owner,
          Math.max(1, leaseSeconds));
    } catch (EmptyResultDataAccessException ex) {
      return null;
    }
  }

  private SyncRun mapRun(ResultSet rs, int rowNum) throws SQLException {
    SyncRun run = new SyncRun();
    run.setId(rs.getLong("id"));
    run.setRunId(rs.getString("run_id"));
    run.setConfigId(rs.getLong("config_id"));
    run.setSourceInstance(rs.getString("source_instance"));
    run.setRunType(SyncRunType.valueOf(rs.getString("run_type")));
    run.setTriggerType(com.data.collection.platform.entity.SyncTriggerType.valueOf(rs.getString("trigger_type")));
    run.setStatus(SyncRunStatus.valueOf(rs.getString("status")));
    run.setPriority(rs.getInt("priority"));
    run.setExclusiveScope(rs.getString("exclusive_scope"));
    run.setCancelRequested(rs.getBoolean("cancel_requested"));
    run.setSubmittedBy(rs.getString("submitted_by"));
    run.setRequestReason(rs.getString("request_reason"));
    run.setPayloadJson(rs.getString("payload_json"));
    run.setThreadMode(rs.getString("thread_mode"));
    Object threadValue = rs.getObject("thread_value");
    if (threadValue != null) {
      run.setThreadValue(rs.getBigDecimal("thread_value"));
    }
    run.setPlannedTableCount(rs.getInt("planned_table_count"));
    run.setCompletedTableCount(rs.getInt("completed_table_count"));
    run.setScannedRows(rs.getLong("scanned_rows"));
    run.setAppliedRows(rs.getLong("applied_rows"));
    run.setHeartbeatAt(toDateTime(rs.getTimestamp("heartbeat_at")));
    run.setLeaseOwner(rs.getString("lease_owner"));
    run.setLeaseUntil(toDateTime(rs.getTimestamp("lease_until")));
    run.setStartedAt(toDateTime(rs.getTimestamp("started_at")));
    run.setFinishedAt(toDateTime(rs.getTimestamp("finished_at")));
    run.setErrorMessage(rs.getString("error_message"));
    run.setCreatedAt(toDateTime(rs.getTimestamp("created_at")));
    run.setUpdatedAt(toDateTime(rs.getTimestamp("updated_at")));
    return run;
  }

  private LocalDateTime toDateTime(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toLocalDateTime();
  }
}
