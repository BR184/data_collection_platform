package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.mapper.SyncRunMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SyncRunCancellationServiceTest {
  private SyncRunMapper syncRunMapper;
  private JdbcTemplate jdbcTemplate;
  private SyncRunTableTaskLeaseService tableTaskLeaseService;
  private SyncRunCancellationService cancellationService;

  @BeforeEach
  void setUp() {
    syncRunMapper = org.mockito.Mockito.mock(SyncRunMapper.class);
    jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    tableTaskLeaseService = org.mockito.Mockito.mock(SyncRunTableTaskLeaseService.class);
    cancellationService = new SyncRunCancellationService(syncRunMapper, jdbcTemplate, tableTaskLeaseService);
  }

  @Test
  void shouldMarkRunningRunAsCancelling() {
    SyncRun running = run(7L, SyncRunStatus.RUNNING);
    when(syncRunMapper.selectList(any())).thenReturn(List.of(running));
    when(jdbcTemplate.update(
            contains("update sync_runs"),
            any(LocalDateTime.class),
            eq(7L),
            eq("RUNNING")))
        .thenReturn(1);
    when(tableTaskLeaseService.hasLiveRunningTask(7L)).thenReturn(true);

    var result = cancellationService.requestCancel(1L, "admin", "manual stop");

    assertThat(running.getCancelRequested()).isTrue();
    assertThat(running.getStatus()).isEqualTo(SyncRunStatus.CANCELLING);
    assertThat(running.getFinishedAt()).isNull();
    verify(syncRunMapper, never()).updateById(any(SyncRun.class));
    assertThat(result.accepted()).isTrue();
    assertThat(result.runId()).isEqualTo(7L);
    assertThat(result.status()).isEqualTo(SyncRunStatus.CANCELLING);
    verify(jdbcTemplate).update(
        contains("insert into sync_run_events"),
        eq(7L),
        eq(1L),
        eq("alpha"),
        eq("RUN_CANCELLATION_REQUESTED"),
        eq("已请求取消同步任务"),
        contains("manual stop"),
        any());
  }

  @Test
  void shouldMarkQueuedRunAsCancelledImmediately() {
    SyncRun queued = run(8L, SyncRunStatus.QUEUED);
    when(syncRunMapper.selectList(any())).thenReturn(List.of(queued));
    when(jdbcTemplate.update(
            contains("update sync_runs"),
            any(LocalDateTime.class),
            any(LocalDateTime.class),
            eq(8L),
            eq("QUEUED")))
        .thenReturn(1);

    var result = cancellationService.requestCancel(1L, "admin", "manual stop");

    assertThat(queued.getCancelRequested()).isTrue();
    assertThat(queued.getStatus()).isEqualTo(SyncRunStatus.CANCELLED);
    assertThat(queued.getFinishedAt()).isNotNull();
    assertThat(result.accepted()).isTrue();
    assertThat(result.status()).isEqualTo(SyncRunStatus.CANCELLED);
    verify(tableTaskLeaseService).cancelActiveTasksForRun(8L);
  }

  @Test
  void shouldCancelPausedRunImmediately() {
    SyncRun paused = run(10L, SyncRunStatus.PAUSED);
    when(syncRunMapper.selectList(any())).thenReturn(List.of(paused));
    when(jdbcTemplate.update(
            contains("update sync_runs"),
            any(LocalDateTime.class),
            any(LocalDateTime.class),
            eq(10L),
            eq("PAUSED")))
        .thenReturn(1);

    var result = cancellationService.requestCancel(1L, "admin", "manual stop");

    assertThat(result.status()).isEqualTo(SyncRunStatus.CANCELLED);
    assertThat(paused.getFinishedAt()).isNotNull();
    verify(tableTaskLeaseService).cancelActiveTasksForRun(10L);
  }

  @Test
  void shouldRejectWhenNoCancellableRunExists() {
    when(syncRunMapper.selectList(any())).thenReturn(List.of());

    var result = cancellationService.requestCancel(1L, "admin", "manual stop");

    assertThat(result.accepted()).isFalse();
    verify(syncRunMapper, never()).updateById(any(SyncRun.class));
    verify(jdbcTemplate, never()).update(any(String.class), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void shouldPreferRunningRunOverOlderQueuedRun() {
    SyncRun queued = run(8L, SyncRunStatus.QUEUED);
    queued.setCreatedAt(LocalDateTime.now().minusMinutes(10));
    SyncRun running = run(9L, SyncRunStatus.RUNNING);
    running.setCreatedAt(LocalDateTime.now());
    when(syncRunMapper.selectList(any())).thenReturn(List.of(queued, running));
    when(jdbcTemplate.update(
            contains("update sync_runs"),
            any(LocalDateTime.class),
            eq(9L),
            eq("RUNNING")))
        .thenReturn(1);
    when(tableTaskLeaseService.hasLiveRunningTask(9L)).thenReturn(true);

    var result = cancellationService.requestCancel(1L, "admin", "manual stop");

    assertThat(result.runId()).isEqualTo(9L);
    assertThat(running.getStatus()).isEqualTo(SyncRunStatus.CANCELLING);
    assertThat(queued.getStatus()).isEqualTo(SyncRunStatus.QUEUED);
  }

  @Test
  void shouldRejectWithoutMutatingRunWhenConcurrentStatusChangeWins() {
    SyncRun running = run(11L, SyncRunStatus.RUNNING);
    when(syncRunMapper.selectList(any())).thenReturn(List.of(running));

    var result = cancellationService.requestCancel(1L, "admin", "manual stop");

    assertThat(result.accepted()).isFalse();
    assertThat(running.getStatus()).isEqualTo(SyncRunStatus.RUNNING);
    assertThat(running.getCancelRequested()).isFalse();
    verify(tableTaskLeaseService, never()).cancelActiveTasksForRun(11L);
  }

  private SyncRun run(Long id, SyncRunStatus status) {
    SyncRun run = new SyncRun();
    run.setId(id);
    run.setRunId("sr_" + id);
    run.setConfigId(1L);
    run.setSourceInstance("alpha");
    run.setRunType(SyncRunType.FULL_SYNC);
    run.setStatus(status);
    run.setCancelRequested(false);
    run.setCreatedAt(LocalDateTime.now().minusMinutes(1));
    return run;
  }
}
