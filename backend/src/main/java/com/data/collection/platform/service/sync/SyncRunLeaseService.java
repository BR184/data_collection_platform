package com.data.collection.platform.service.sync;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SyncRunLeaseService {
  private final JdbcTemplate jdbcTemplate;

  public SyncRunLeaseService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public int heartbeat(Long runId, int leaseSeconds) {
    if (runId == null) {
      return 0;
    }
    return jdbcTemplate.update(
        """
        update sync_runs
           set heartbeat_at = current_timestamp,
               lease_until = current_timestamp + (? * interval '1 second'),
               updated_at = current_timestamp
         where id = ?
           and status in ('RUNNING', 'RETRYING', 'CANCELLING')
        """,
        Math.max(1, leaseSeconds),
        runId);
  }

  public int recoverTimedOutRuns() {
    int timedOutRuns =
        jdbcTemplate.update(
            """
            update sync_runs
               set status = 'TIMEOUT',
                   lease_owner = null,
                   lease_until = null,
                   heartbeat_at = null,
                   finished_at = coalesce(finished_at, current_timestamp),
                   error_message = coalesce(error_message, 'Sync run lease timed out'),
                   updated_at = current_timestamp
             where status in ('RUNNING', 'RETRYING', 'CANCELLING')
               and lease_until is not null
               and lease_until < current_timestamp
            """);
    if (timedOutRuns > 0) {
      markTimedOutRunTasks();
    }
    return timedOutRuns;
  }

  private void markTimedOutRunTasks() {
    jdbcTemplate.update(
        """
        update sync_run_table_tasks task
           set status = 'TIMEOUT',
               lease_owner = null,
               lease_until = null,
               heartbeat_at = null,
               last_error = coalesce(last_error, 'Parent sync run lease timed out'),
               finished_at = coalesce(task.finished_at, current_timestamp),
               updated_at = current_timestamp
          from sync_runs run
         where task.run_id = run.id
           and run.status = 'TIMEOUT'
           and task.status in ('QUEUED', 'RUNNING', 'RETRYING')
        """);
  }
}
