package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import java.time.LocalDateTime;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SyncRunLeaseServiceTest {
  private static final int LEASE_WINDOW_SECONDS = 180;

  private JdbcTemplate jdbcTemplate;
  private SyncRunLeaseService leaseService;

  @BeforeEach
  void setUp() {
    jdbcTemplate = mock(JdbcTemplate.class);
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setHeartbeatTimeoutSeconds(LEASE_WINDOW_SECONDS);
    leaseService = new SyncRunLeaseService(jdbcTemplate, properties);
  }

  @Test
  void shouldExtendRunLeaseForActiveRun() {
    when(jdbcTemplate.update(
            contains("heartbeat_at = current_timestamp"), eq(30), eq(11L), eq("owner-11")))
        .thenReturn(1);

    int updated = leaseService.heartbeat(11L, "owner-11", 30);

    assertThat(updated).isEqualTo(1);
    verify(jdbcTemplate)
        .update(
            contains("status in ('RUNNING', 'RETRYING', 'CANCELLING')"),
            eq(30),
            eq(11L),
            eq("owner-11"));
  }

  @Test
  void shouldRequireLiveLeaseWhenRenewing() {
    leaseService.heartbeat(11L, "owner-11", 30);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).update(sqlCaptor.capture(), eq(30), eq(11L), eq("owner-11"));
    assertThat(sqlCaptor.getValue()).contains("lease_until >= current_timestamp");
  }

  @Test
  void shouldNotHeartbeatMissingRunId() {
    assertThat(leaseService.heartbeat(null, "owner-11", 30)).isZero();
    assertThat(leaseService.heartbeat(11L, null, 30)).isZero();
    verify(jdbcTemplate, never()).update(any(String.class), any(), any(), any());
  }

  @Test
  void shouldFinishOnlyRunOwnedByCurrentExecutionToken() {
    SyncRun run = run(11L, "owner-11", SyncRunStatus.SUCCESS);

    when(jdbcTemplate.update(
            contains("lease_owner = ?"),
            eq("SUCCESS"),
            eq(4),
            eq(4),
            eq(20L),
            eq(18L),
            eq(run.getFinishedAt()),
            org.mockito.ArgumentMatchers.isNull(),
            eq(run.getUpdatedAt()),
            eq(11L),
            eq("owner-11"),
            eq("SUCCESS")))
        .thenReturn(1);

    assertThat(leaseService.finishOwnedRun(run)).isEqualTo(1);
  }

  @Test
  void shouldRequireLiveLeaseAndCancelFinalizationWhenFinishing() {
    SyncRun run = run(11L, "owner-11", SyncRunStatus.SUCCESS);

    leaseService.finishOwnedRun(run);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate)
        .update(
            sqlCaptor.capture(),
            eq("SUCCESS"),
            eq(4),
            eq(4),
            eq(20L),
            eq(18L),
            eq(run.getFinishedAt()),
            org.mockito.ArgumentMatchers.isNull(),
            eq(run.getUpdatedAt()),
            eq(11L),
            eq("owner-11"),
            eq("SUCCESS"));
    assertThat(sqlCaptor.getValue()).contains("lease_until >= current_timestamp");
    assertThat(sqlCaptor.getValue()).contains("(status <> 'CANCELLING' or ? = 'CANCELLED')");
  }

  @Test
  void shouldReleaseOnlyClaimOwnedByRejectedExecutor() {
    SyncRun run = new SyncRun();
    run.setId(12L);
    run.setLeaseOwner("owner-12");
    when(jdbcTemplate.update(contains("status = 'QUEUED'"), eq(12L), eq("owner-12"))).thenReturn(1);

    assertThat(leaseService.releaseOwnedRun(run)).isEqualTo(1);
  }

  @Test
  void shouldRecoverBothExpiredAndLeaseLessActiveRuns() {
    when(jdbcTemplate.update(contains("lease_until < current_timestamp"))).thenReturn(2);
    when(jdbcTemplate.update(contains("held no lease while active"), eq(LEASE_WINDOW_SECONDS)))
        .thenReturn(1);
    when(jdbcTemplate.update(contains("Parent sync run lease timed out"))).thenReturn(5);

    int recovered = leaseService.recoverTimedOutRuns();

    assertThat(recovered).isEqualTo(3);
    verify(jdbcTemplate).update(contains("Parent sync run lease timed out"));
  }

  @Test
  void shouldQualifyTaskFinishedAtWhenMarkingTimedOutTasks() {
    when(jdbcTemplate.update(contains("lease_until < current_timestamp"))).thenReturn(1);
    when(jdbcTemplate.update(contains("held no lease while active"), eq(LEASE_WINDOW_SECONDS)))
        .thenReturn(0);
    when(jdbcTemplate.update(contains("Parent sync run lease timed out"))).thenReturn(1);

    leaseService.recoverTimedOutRuns();

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate, org.mockito.Mockito.times(2)).update(sqlCaptor.capture());
    assertThat(sqlCaptor.getAllValues().get(1))
        .contains("finished_at = coalesce(task.finished_at, current_timestamp)");
  }

  @Test
  void shouldSkipTaskUpdateWhenNoRunTimedOut() {
    when(jdbcTemplate.update(contains("lease_until < current_timestamp"))).thenReturn(0);
    when(jdbcTemplate.update(contains("held no lease while active"), eq(LEASE_WINDOW_SECONDS)))
        .thenReturn(0);

    int recovered = leaseService.recoverTimedOutRuns();

    assertThat(recovered).isZero();
    verify(jdbcTemplate, never()).update(contains("Parent sync run lease timed out"));
  }

  private SyncRun run(long id, String leaseOwner, SyncRunStatus status) {
    SyncRun run = new SyncRun();
    run.setId(id);
    run.setLeaseOwner(leaseOwner);
    run.setStatus(status);
    run.setPlannedTableCount(4);
    run.setCompletedTableCount(4);
    run.setScannedRows(20L);
    run.setAppliedRows(18L);
    run.setFinishedAt(LocalDateTime.of(2026, 7, 29, 10, 0));
    run.setUpdatedAt(LocalDateTime.of(2026, 7, 29, 10, 0));
    return run;
  }
}
