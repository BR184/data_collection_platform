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
import java.sql.ResultSet;
import java.time.LocalDateTime;
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
    verify(jdbcTemplate).update(contains("last_error = '表任务租约超时，已进入退避重试'"));
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

    SyncRunTableTask task = leaseService.claimNextRunnableTask(77L, "owner-1", 0);

    assertThat(task).isNull();
    verify(jdbcTemplate)
        .queryForObject(
            contains("candidate.status in ('QUEUED', 'RETRYING')"),
            any(RowMapper.class),
            eq("owner-1"),
            eq(1),
            eq(77L));
  }

  @Test
  void test_claim_only_selects_queued_or_due_retrying_tasks() {
    when(jdbcTemplate.queryForObject(
            contains("for update skip locked"),
            any(RowMapper.class),
            eq("owner-1"),
            eq(30),
            eq(77L)))
        .thenThrow(new EmptyResultDataAccessException(1));

    leaseService.claimNextRunnableTask(77L, "owner-1", 30);

    verify(jdbcTemplate)
        .queryForObject(
            org.mockito.ArgumentMatchers.<String>argThat(
                sql ->
                    sql.contains("candidate.status in ('QUEUED', 'RETRYING')")
                        && sql.contains("candidate.run_after <= current_timestamp")),
            any(RowMapper.class),
            eq("owner-1"),
            eq(30),
            eq(77L));
  }

  @Test
  void test_transient_failure_defers_same_owned_task_without_advancing_cursor() {
    LocalDateTime runAfter = LocalDateTime.of(2026, 8, 10, 12, 0, 5);
    when(jdbcTemplate.update(
            contains("set status = 'RETRYING'"),
            eq(2L),
            eq(1L),
            eq(runAfter),
            eq("连接暂时不可用"),
            eq(501L),
            eq("owner-1")))
        .thenReturn(1);

    boolean deferred =
        leaseService.deferOwnedTask(
            501L, "owner-1", 2L, 1L, runAfter, "连接暂时不可用");

    assertThat(deferred).isTrue();
    verify(jdbcTemplate)
        .update(
            org.mockito.ArgumentMatchers.<String>argThat(
                sql ->
                    sql.contains("retry_count = retry_count + 1")
                        && sql.contains("cursor_updated_at") == false
                        && sql.contains("cursor_pk") == false),
            eq(2L),
            eq(1L),
            eq(runAfter),
            eq("连接暂时不可用"),
            eq(501L),
            eq("owner-1"));
  }

  @Test
  void shouldRetainLookupScopeWhenClaimingQueuedTask() throws Exception {
    ResultSet resultSet = mock(ResultSet.class);
    when(resultSet.getString("status")).thenReturn("RUNNING");
    when(resultSet.getString("task_stage")).thenReturn("SCAN");
    when(resultSet.getString("lookup_scope_json"))
        .thenReturn("{\"target_id\":\"101\",\"target_type\":\"Issue\"}");
    when(jdbcTemplate.queryForObject(
            contains("returning *"),
            any(RowMapper.class),
            eq("owner-1"),
            eq(30),
            eq(77L)))
        .thenAnswer(
            invocation -> {
              RowMapper<SyncRunTableTask> mapper = invocation.getArgument(1);
              return mapper.mapRow(resultSet, 0);
            });

    SyncRunTableTask task = leaseService.claimNextRunnableTask(77L, "owner-1", 30);

    assertThat(task.getLookupScopeJson())
        .isEqualTo("{\"target_id\":\"101\",\"target_type\":\"Issue\"}");
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
    when(jdbcTemplate.update(
            contains("lease_owner = ?"),
            eq("SUCCESS"),
            eq(2L),
            eq(1L),
            eq(null),
            eq("owner-1"),
            eq(501L)))
        .thenReturn(1);

    boolean finished = leaseService.finishOwnedTask(501L, "owner-1", 2L, 1L, "SUCCESS", null);

    assertThat(finished).isTrue();
    verify(jdbcTemplate)
        .update(
            contains("lease_owner = ?"),
            eq("SUCCESS"),
            eq(2L),
            eq(1L),
            eq(null),
            eq("owner-1"),
            eq(501L));
  }

  @Test
  void shouldRequeueReconciliationOnSameTaskAndAccumulatePageCounters() {
    when(jdbcTemplate.update(
            contains("page_number = page_number + 1"),
            eq("[\"200\"]"),
            eq(500L),
            eq(2L),
            eq(501L),
            eq("owner-1")))
        .thenReturn(1);

    boolean requeued =
        leaseService.requeueOwnedReconciliationTask(
            501L, "owner-1", "[\"200\"]", 500L, 2L);

    assertThat(requeued).isTrue();
    verify(jdbcTemplate)
        .update(
            contains("rows_scanned = rows_scanned + ?"),
            eq("[\"200\"]"),
            eq(500L),
            eq(2L),
            eq(501L),
            eq("owner-1"));
  }

  @Test
  void shouldRenewOnlyLeaseOwnedByCurrentWorker() {
    when(jdbcTemplate.update(
            contains("heartbeat_at = current_timestamp"),
            eq(180),
            eq(501L),
            eq("owner-1")))
        .thenReturn(1);

    boolean renewed = leaseService.renewLease(501L, "owner-1", 180);

    assertThat(renewed).isTrue();
    verify(jdbcTemplate)
        .update(
            contains("status = 'RUNNING'"),
            eq(180),
            eq(501L),
            eq("owner-1"));
  }
}
