package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.sync.SyncRunTableTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class SyncRunTableTaskLeaseServiceTest {
  private JdbcTemplate jdbcTemplate;
  private SyncRunTableTaskLeaseService leaseService;

  @BeforeEach
  void setUp() {
    jdbcTemplate = mock(JdbcTemplate.class);
    leaseService = new SyncRunTableTaskLeaseService(jdbcTemplate);
  }

  @Test
  void shouldRecoverTimedOutRunningTableTasks() {
    when(jdbcTemplate.update(contains("retry_count = retry_count + 1"))).thenReturn(2);
    when(jdbcTemplate.update(contains("set status = 'TIMEOUT'"))).thenReturn(1);

    int recovered = leaseService.recoverTimedOutTasks();

    assertThat(recovered).isEqualTo(3);
    verify(jdbcTemplate).update(contains("last_error = '表任务租约超时，已重新排队'"));
    verify(jdbcTemplate).update(contains("last_error = '表任务租约超时'"));
  }

  @Test
  void shouldClaimQueuedTaskWithSafeLeaseSeconds() {
    when(jdbcTemplate.queryForObject(
            contains("for update skip locked"),
            any(RowMapper.class),
            eq("owner-1"),
            eq(1),
            eq(77L)))
        .thenThrow(new EmptyResultDataAccessException(1));

    SyncRunTableTask task = leaseService.claimNextQueuedTask(77L, "owner-1", 0);

    assertThat(task).isNull();
    verify(jdbcTemplate)
        .queryForObject(
            contains("candidate.status = 'QUEUED'"),
            any(RowMapper.class),
            eq("owner-1"),
            eq(1),
            eq(77L));
  }

  @Test
  void shouldTreatMissingRunAsNotCancelled() {
    when(jdbcTemplate.queryForObject(contains("select cancel_requested"), eq(Boolean.class), eq(99L)))
        .thenThrow(new EmptyResultDataAccessException(1));

    assertThat(leaseService.isRunCancellationRequested(99L)).isFalse();
  }

  @Test
  void shouldNotQueryCancellationForMissingRunId() {
    assertThat(leaseService.isRunCancellationRequested(null)).isFalse();
    verify(jdbcTemplate, never()).queryForObject(any(String.class), eq(Boolean.class), any());
  }

  @Test
  void shouldFinishTaskWithRowCountersAndErrorMessage() {
    leaseService.finishTask(501L, 2L, 1L, "SUCCESS", null);

    verify(jdbcTemplate)
        .update(
            contains("rows_scanned = coalesce"),
            eq("SUCCESS"),
            eq(2L),
            eq(1L),
            eq(null),
            eq(501L));
  }
}
