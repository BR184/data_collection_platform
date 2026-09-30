package com.data.collection.platform.service.backup;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BackupSchedulerTest {
  private static final Instant DUE_LOCAL_0330 = Instant.parse("2026-09-09T19:30:00Z");
  private static final Instant BEFORE_DUE_LOCAL_0230 = Instant.parse("2026-09-09T18:30:00Z");

  private BackupSettingsRepository settingsRepository;
  private BackupRunRepository runRepository;
  private BackupStateRepository stateRepository;
  private BackupOrchestrationService orchestration;
  private BackupScheduler scheduler;

  @BeforeEach
  void setUp() {
    settingsRepository = mock(BackupSettingsRepository.class);
    runRepository = mock(BackupRunRepository.class);
    stateRepository = mock(BackupStateRepository.class);
    orchestration = mock(BackupOrchestrationService.class);
    scheduler = new BackupScheduler(
        settingsRepository, runRepository, stateRepository, orchestration,
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
        settingsRepository, runRepository, stateRepository, orchestration,
        Clock.fixed(BEFORE_DUE_LOCAL_0230, ZoneOffset.UTC));
    when(settingsRepository.load()).thenReturn(Optional.of(settings(true, "03:00")));
    when(runRepository.existsScheduleTriggeredOn(any(), any())).thenReturn(false);

    scheduler.scheduleIfDue();

    verify(orchestration, never()).triggerScheduled();
  }

  @Test
  void test_recoverOrphans_whenLeaseExpired_stopsThenCompletesRecovery() {
    BackupStateRepository.ExpiredExecution execution =
        new BackupStateRepository.ExpiredExecution(
            7L, UUID.randomUUID().toString(), 12345L, Instant.parse("2026-09-09T18:00:00Z"));
    when(stateRepository.revokeExpiredExecution(any())).thenReturn(Optional.of(execution));
    when(orchestration.stopExpiredExecution(execution)).thenReturn(true);
    when(orchestration.cleanupExpiredStaging(execution)).thenReturn(true);
    when(stateRepository.settleOwnedRun(eq(7L), eq(execution.executionToken()), any(), any()))
        .thenReturn(true);

    scheduler.recoverOrphans();

    verify(orchestration).stopExpiredExecution(execution);
    verify(orchestration).cleanupExpiredStaging(execution);
    verify(stateRepository).settleOwnedRun(eq(7L), eq(execution.executionToken()), anyString(), any());
  }

  @Test
  void test_recoverOrphans_whenStopCannotBeConfirmed_preservesRunAndStaging() {
    BackupStateRepository.ExpiredExecution execution =
        new BackupStateRepository.ExpiredExecution(
            8L, UUID.randomUUID().toString(), 12346L, DUE_LOCAL_0330);
    when(stateRepository.revokeExpiredExecution(any())).thenReturn(Optional.of(execution));
    when(orchestration.stopExpiredExecution(execution)).thenReturn(false);

    scheduler.recoverOrphans();

    verify(stateRepository).recordRecoveryPending(eq(execution), anyString(), any());
    verify(orchestration, never()).cleanupExpiredStaging(any());
    verify(stateRepository, never()).settleOwnedRun(anyLong(), anyString(), any(), any());
  }

  @Test
  void test_recoverOrphans_whenLegacyExecutionHasNoRecordedProcess_keepsRecoveryPending() {
    BackupStateRepository.ExpiredExecution execution =
        new BackupStateRepository.ExpiredExecution(10L, "legacy-10", null, null);
    when(stateRepository.revokeExpiredExecution(any())).thenReturn(Optional.of(execution));
    when(orchestration.stopExpiredExecution(execution)).thenReturn(false);

    scheduler.recoverOrphans();

    verify(stateRepository).recordRecoveryPending(eq(execution), anyString(), any());
    verify(orchestration, never()).cleanupExpiredStaging(any());
    verify(stateRepository, never()).settleOwnedRun(anyLong(), anyString(), any(), any());
  }

  @Test
  void test_recoverOrphans_whenStagingCleanupFails_keepsRevokedOwnerForRetry() {
    BackupStateRepository.ExpiredExecution execution =
        new BackupStateRepository.ExpiredExecution(9L, UUID.randomUUID().toString(), null, null);
    when(stateRepository.revokeExpiredExecution(any())).thenReturn(Optional.of(execution));
    when(orchestration.stopExpiredExecution(execution)).thenReturn(true);
    when(orchestration.cleanupExpiredStaging(execution)).thenReturn(false);

    scheduler.recoverOrphans();

    verify(stateRepository).recordRecoveryPending(eq(execution), anyString(), any());
    verify(stateRepository, never()).settleOwnedRun(anyLong(), anyString(), any(), any());
  }

  @Test
  void test_recoverOrphans_whenLeaseStillValid_doesNothing() {
    when(stateRepository.revokeExpiredExecution(any())).thenReturn(Optional.empty());

    scheduler.recoverOrphans();

    verify(orchestration, never()).stopExpiredExecution(any());
    verify(stateRepository, never()).settleOwnedRun(anyLong(), anyString(), any(), any());
  }

  private BackupSettings settings(boolean enabled, String scheduleTime) {
    return new BackupSettings(
        enabled, LocalTime.parse(scheduleTime), 14, "LOCAL", null, null, 22, null, null, null, null,
        0, null, null);
  }
}
