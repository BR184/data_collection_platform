package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.mapper.SyncRunMapper;
import com.data.collection.platform.service.GitlabConfigService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SyncRunDeadlineGuardTest {
  private GitlabMirrorProperties properties;
  private SyncRunMapper syncRunMapper;
  private GitlabConfigService configService;
  private SyncRunDeadlineGuard guard;

  @BeforeEach
  void setUp() {
    properties = new GitlabMirrorProperties();
    syncRunMapper = mock(SyncRunMapper.class);
    configService = mock(GitlabConfigService.class);
    guard =
        new SyncRunDeadlineGuard(
            properties,
            syncRunMapper,
            configService,
            Clock.fixed(Instant.parse("2026-06-02T11:01:00Z"), ZoneId.of("UTC")));
  }

  @Test
  void shouldRequestCancellationWhenRunExceedsMaximumRuntime() {
    properties.setMaxRunDurationMinutes(60);
    SyncRun run = run(SyncRunType.FULL_SYNC, LocalDateTime.of(2026, 6, 2, 10, 0));

    boolean cancelled = guard.requestCancellationIfExpired(run);

    assertThat(cancelled).isTrue();
    SyncRun updated = captureUpdatedRun();
    assertThat(updated.getStatus()).isEqualTo(SyncRunStatus.CANCELLING);
    assertThat(updated.getCancelRequested()).isTrue();
    assertThat(updated.getErrorMessage()).contains("maximum runtime");
  }

  @Test
  void shouldRequestCancellationWhenCompensationRunCrossesCalendarDay() {
    properties.setMaxRunDurationMinutes(0);
    SyncRun run = run(SyncRunType.FULL_COMPENSATION_SCAN, LocalDateTime.of(2026, 6, 1, 23, 30));

    boolean cancelled = guard.requestCancellationIfExpired(run);

    assertThat(cancelled).isTrue();
    SyncRun updated = captureUpdatedRun();
    assertThat(updated.getStatus()).isEqualTo(SyncRunStatus.CANCELLING);
    assertThat(updated.getErrorMessage()).contains("calendar day");
  }

  @Test
  void shouldRequestCancellationWhenWindowedCompensationRunPassesWindowEnd() {
    properties.setMaxRunDurationMinutes(0);
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setCompensationScheduleMode("WINDOWED_INTERVAL");
    config.setCompensationWindowStart("09:00");
    config.setCompensationWindowEnd("11:00");
    when(configService.getConfigById(1L)).thenReturn(config);
    SyncRun run = run(SyncRunType.COMPENSATION_SCAN, LocalDateTime.of(2026, 6, 2, 10, 30));

    boolean cancelled = guard.requestCancellationIfExpired(run);

    assertThat(cancelled).isTrue();
    assertThat(captureUpdatedRun().getErrorMessage()).contains("compensation window");
  }

  @Test
  void shouldKeepActiveRunWhenNoDeadlineHasPassed() {
    properties.setMaxRunDurationMinutes(120);
    SyncRun run = run(SyncRunType.INCREMENTAL_SYNC, LocalDateTime.of(2026, 6, 2, 10, 30));

    boolean cancelled = guard.requestCancellationIfExpired(run);

    assertThat(cancelled).isFalse();
    verify(syncRunMapper, never()).updateById(any(SyncRun.class));
  }

  private SyncRun captureUpdatedRun() {
    ArgumentCaptor<SyncRun> captor = ArgumentCaptor.forClass(SyncRun.class);
    verify(syncRunMapper).updateById(captor.capture());
    return captor.getValue();
  }

  private SyncRun run(SyncRunType runType, LocalDateTime startedAt) {
    SyncRun run = new SyncRun();
    run.setId(77L);
    run.setRunId("sr_77");
    run.setConfigId(1L);
    run.setRunType(runType);
    run.setStatus(SyncRunStatus.RUNNING);
    run.setStartedAt(startedAt);
    return run;
  }
}
