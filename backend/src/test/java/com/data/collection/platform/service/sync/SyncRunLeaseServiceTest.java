package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SyncRunLeaseServiceTest {
  private JdbcTemplate jdbcTemplate;
  private SyncRunLeaseService leaseService;

  @BeforeEach
  void setUp() {
    jdbcTemplate = mock(JdbcTemplate.class);
    leaseService = new SyncRunLeaseService(jdbcTemplate);
  }

  @Test
  void shouldExtendRunLeaseForActiveRun() {
    when(jdbcTemplate.update(org.mockito.ArgumentMatchers.contains("heartbeat_at = current_timestamp"), eq(30), eq(11L)))
        .thenReturn(1);

    int updated = leaseService.heartbeat(11L, 30);

    assertThat(updated).isEqualTo(1);
    verify(jdbcTemplate)
        .update(
            org.mockito.ArgumentMatchers.contains("status in ('RUNNING', 'RETRYING', 'CANCELLING')"),
            eq(30),
            eq(11L));
  }

  @Test
  void shouldNotHeartbeatMissingRunId() {
    assertThat(leaseService.heartbeat(null, 30)).isZero();
    verify(jdbcTemplate, never()).update(any(String.class), any(), any());
  }

  @Test
  void shouldRecoverTimedOutRunsAndMarkTheirTableTasks() {
    when(jdbcTemplate.update(org.mockito.ArgumentMatchers.contains("set status = 'TIMEOUT'"))).thenReturn(2);
    when(jdbcTemplate.update(org.mockito.ArgumentMatchers.contains("Parent sync run lease timed out"))).thenReturn(5);

    int recovered = leaseService.recoverTimedOutRuns();

    assertThat(recovered).isEqualTo(2);
    verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.contains("lease_until < current_timestamp"));
    verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.contains("Parent sync run lease timed out"));
  }

  @Test
  void shouldQualifyTaskFinishedAtWhenMarkingTimedOutTasks() {
    when(jdbcTemplate.update(org.mockito.ArgumentMatchers.contains("set status = 'TIMEOUT'"))).thenReturn(1);
    when(jdbcTemplate.update(org.mockito.ArgumentMatchers.contains("Parent sync run lease timed out"))).thenReturn(1);

    leaseService.recoverTimedOutRuns();

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate, org.mockito.Mockito.times(2)).update(sqlCaptor.capture());
    assertThat(sqlCaptor.getAllValues().get(1)).contains("finished_at = coalesce(task.finished_at, current_timestamp)");
  }

  @Test
  void shouldSkipTaskUpdateWhenNoRunTimedOut() {
    when(jdbcTemplate.update(org.mockito.ArgumentMatchers.contains("set status = 'TIMEOUT'"))).thenReturn(0);

    int recovered = leaseService.recoverTimedOutRuns();

    assertThat(recovered).isZero();
    verify(jdbcTemplate, never()).update(org.mockito.ArgumentMatchers.contains("Parent sync run lease timed out"));
  }
}
