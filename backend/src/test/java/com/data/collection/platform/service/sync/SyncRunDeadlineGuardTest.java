package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
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
    when(syncRunMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
  }

  @Test
  void shouldRequestCancellationWhenRunExceedsMaximumRuntime() {
    properties.setMaxRunDurationMinutes(60);
    SyncRun run = run(SyncRunType.FULL_SYNC, LocalDateTime.of(2026, 6, 2, 10, 0));

    boolean cancelled = guard.requestCancellationIfExpired(run);

    assertThat(cancelled).isTrue();
    assertThat(run.getStatus()).isEqualTo(SyncRunStatus.CANCELLING);
    assertThat(run.getCancelRequested()).isTrue();
    assertThat(run.getErrorMessage()).contains("maximum runtime");
    verify(syncRunMapper).update(isNull(), any(UpdateWrapper.class));
  }

  @Test
  void shouldRequestCancellationWhenCompensationRunCrossesCalendarDay() {
    properties.setMaxRunDurationMinutes(0);
    SyncRun run = run(SyncRunType.FULL_COMPENSATION_SCAN, LocalDateTime.of(2026, 6, 1, 23, 30));

    boolean cancelled = guard.requestCancellationIfExpired(run);

    assertThat(cancelled).isTrue();
    assertThat(run.getStatus()).isEqualTo(SyncRunStatus.CANCELLING);
    assertThat(run.getErrorMessage()).contains("calendar day");
  }

  @Test
  void shouldRequestCancellationWhenWindowedCompensationRunPassesWindowEnd() {
    properties.setMaxRunDurationMinutes(0);
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setCompensationScheduleMode("WINDOWED_INTERVAL");
    config.setCompensationWindowStart("09:00");
    config.setCompensationWindowEnd("11:00");
    when(configService.getConfigById(1L)).thenReturn(config);
    SyncRun run = run(SyncRunType.FULL_COMPENSATION_SCAN, LocalDateTime.of(2026, 6, 2, 10, 30));

    boolean cancelled = guard.requestCancellationIfExpired(run);

    assertThat(cancelled).isTrue();
    assertThat(run.getErrorMessage()).contains("compensation window");
  }

  @Test
  void shouldKeepActiveRunWhenNoDeadlineHasPassed() {
    properties.setMaxRunDurationMinutes(120);
    SyncRun run = run(SyncRunType.INCREMENTAL_SYNC, LocalDateTime.of(2026, 6, 2, 10, 30));

    boolean cancelled = guard.requestCancellationIfExpired(run);

    assertThat(cancelled).isFalse();
    verify(syncRunMapper, never()).update(isNull(), any(UpdateWrapper.class));
  }

  @Test
  void shouldNotOverwriteRunWhenConcurrentStateChangeWins() {
    properties.setMaxRunDurationMinutes(60);
    SyncRun run = run(SyncRunType.FULL_SYNC, LocalDateTime.of(2026, 6, 2, 10, 0));
    when(syncRunMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(0);

    boolean cancelled = guard.requestCancellationIfExpired(run);

    assertThat(cancelled).isFalse();
    assertThat(run.getStatus()).isEqualTo(SyncRunStatus.RUNNING);
    assertThat(run.getCancelRequested()).isNull();
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
