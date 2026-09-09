package com.data.collection.platform.service.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupSchedulerTest {
  private static final Instant DUE_LOCAL_0330 = Instant.parse("2026-09-09T19:30:00Z");
  private static final Instant BEFORE_DUE_LOCAL_0230 = Instant.parse("2026-09-09T18:30:00Z");

  @TempDir Path tempRoot;

  private BackupSettingsRepository settingsRepository;
  private BackupRunRepository runRepository;
  private BackupStateRepository stateRepository;
  private BackupOrchestrationService orchestration;
  private BackupConfigurationProperties properties;
  private BackupScheduler scheduler;

  @BeforeEach
  void setUp() {
    settingsRepository = mock(BackupSettingsRepository.class);
    runRepository = mock(BackupRunRepository.class);
    stateRepository = mock(BackupStateRepository.class);
    orchestration = mock(BackupOrchestrationService.class);
    properties = new BackupConfigurationProperties();
    properties.setRoot(tempRoot.toString());
    properties.setInstanceLabel("testinst");
    scheduler = new BackupScheduler(
        settingsRepository, runRepository, stateRepository, orchestration, properties,
        Clock.fixed(DUE_LOCAL_0330, ZoneOffset.UTC));
  }

  @Test
  void test_scheduleIfDue_whenDisabled_neverTriggers() {
    when(settingsRepository.load()).thenReturn(Optional.of(settings(false, "03:00")));

    scheduler.scheduleIfDue();

    verify(orchestration, never()).triggerScheduled();
  }

  @Test
  void test_scheduleIfDue_whenDueAndNotAttempted_triggersScheduledRun() {
    when(settingsRepository.load()).thenReturn(Optional.of(settings(true, "03:00")));
    when(runRepository.existsScheduleTriggeredOn(any(), any())).thenReturn(false);

    scheduler.scheduleIfDue();

    verify(orchestration).triggerScheduled();
  }

  @Test
  void test_scheduleIfDue_whenAlreadyAttemptedToday_neverRetries() {
    when(settingsRepository.load()).thenReturn(Optional.of(settings(true, "03:00")));
    when(runRepository.existsScheduleTriggeredOn(any(), any())).thenReturn(true);

    scheduler.scheduleIfDue();

    verify(orchestration, never()).triggerScheduled();
  }

  @Test
  void test_scheduleIfDue_beforeConfiguredTime_neverTriggers() {
    scheduler = new BackupScheduler(
        settingsRepository, runRepository, stateRepository, orchestration, properties,
        Clock.fixed(BEFORE_DUE_LOCAL_0230, ZoneOffset.UTC));
    when(settingsRepository.load()).thenReturn(Optional.of(settings(true, "03:00")));
    when(runRepository.existsScheduleTriggeredOn(any(), any())).thenReturn(false);

    scheduler.scheduleIfDue();

    verify(orchestration, never()).triggerScheduled();
  }

  @Test
  void test_recoverOrphans_whenLeaseExpired_marksFailedReleasesAndCleansStaging() throws Exception {
    Path staging = tempRoot.resolve("testinst").resolve(".staging");
    Files.createDirectories(staging);
    Files.writeString(staging.resolve("tmp-7.dump"), "interrupted");
    when(stateRepository.expiredActiveRunId(any())).thenReturn(Optional.of(7L));
    when(runRepository.get(7L)).thenReturn(Optional.of(runningRun()));

    scheduler.recoverOrphans();

    verify(runRepository).finishFailure(anyLong(), any(), any(), any());
    verify(stateRepository).release(eq(7L), any());
    assertThat(Files.exists(staging.resolve("tmp-7.dump"))).isFalse();
  }

  @Test
  void test_recoverOrphans_whenLeaseStillValid_doesNothing() {
    when(stateRepository.expiredActiveRunId(any())).thenReturn(Optional.empty());

    scheduler.recoverOrphans();

    verify(runRepository, never()).finishFailure(anyLong(), any(), any(), any());
    verify(stateRepository, never()).release(anyLong(), any());
  }

  private BackupSettings settings(boolean enabled, String scheduleTime) {
    return new BackupSettings(
        enabled, LocalTime.parse(scheduleTime), 14, "LOCAL", null, null, 22, null, null, null, null,
        0, null, null);
  }

  private BackupRun runningRun() {
    return new BackupRun(
        7L, "SCHEDULE", "RUNNING", "LOCAL", "DUMP", null, null, null, null, null, null,
        Instant.parse("2026-09-09T19:00:00Z"), null, null, null);
  }
}
