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
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class SyncRunCancellationServiceTest {
  private SyncRunMapper syncRunMapper;
  private JdbcTemplate jdbcTemplate;
  private SyncRunCancellationService cancellationService;

  @BeforeEach
  void setUp() {
    syncRunMapper = org.mockito.Mockito.mock(SyncRunMapper.class);
    jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    cancellationService = new SyncRunCancellationService(syncRunMapper, jdbcTemplate);
  }

  @Test
  void shouldMarkRunningRunAsCancelling() {
    SyncRun running = run(7L, SyncRunStatus.RUNNING);
    when(syncRunMapper.selectList(any())).thenReturn(List.of(running));

    var result = cancellationService.requestCancel(1L, "admin", "manual stop");

    ArgumentCaptor<SyncRun> runCaptor = ArgumentCaptor.forClass(SyncRun.class);
    verify(syncRunMapper).updateById(runCaptor.capture());
    SyncRun saved = runCaptor.getValue();
    assertThat(saved.getCancelRequested()).isTrue();
    assertThat(saved.getStatus()).isEqualTo(SyncRunStatus.CANCELLING);
    assertThat(saved.getFinishedAt()).isNull();
    assertThat(result.accepted()).isTrue();
    assertThat(result.runId()).isEqualTo(7L);
    assertThat(result.status()).isEqualTo(SyncRunStatus.CANCELLING);
    verify(jdbcTemplate).update(
        contains("insert into sync_run_events"),
        eq(7L),
        eq(1L),
        eq("alpha"),
        eq("RUN_CANCELLATION_REQUESTED"),
        eq("Cancellation requested"),
        contains("manual stop"),
        any());
  }

  @Test
  void shouldMarkQueuedRunAsCancelledImmediately() {
    SyncRun queued = run(8L, SyncRunStatus.QUEUED);
    when(syncRunMapper.selectList(any())).thenReturn(List.of(queued));

    var result = cancellationService.requestCancel(1L, "admin", "manual stop");

    ArgumentCaptor<SyncRun> runCaptor = ArgumentCaptor.forClass(SyncRun.class);
    verify(syncRunMapper).updateById(runCaptor.capture());
    SyncRun saved = runCaptor.getValue();
    assertThat(saved.getCancelRequested()).isTrue();
    assertThat(saved.getStatus()).isEqualTo(SyncRunStatus.CANCELLED);
    assertThat(saved.getFinishedAt()).isNotNull();
    assertThat(result.accepted()).isTrue();
    assertThat(result.status()).isEqualTo(SyncRunStatus.CANCELLED);
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

    var result = cancellationService.requestCancel(1L, "admin", "manual stop");

    assertThat(result.runId()).isEqualTo(9L);
    assertThat(running.getStatus()).isEqualTo(SyncRunStatus.CANCELLING);
    assertThat(queued.getStatus()).isEqualTo(SyncRunStatus.QUEUED);
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
