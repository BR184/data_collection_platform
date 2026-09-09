package com.data.collection.platform.service.backup;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class BackupRunStateRepositoryTest {
  private static final ZoneId ZONE = BackupOrchestrationService.PLATFORM_ZONE;
  private static final Instant NOW = Instant.parse("2026-09-10T02:00:00Z");

  @Autowired private BackupRunRepository runRepository;
  @Autowired private BackupStateRepository stateRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    jdbcTemplate.update("delete from backup_runs");
    jdbcTemplate.update(
        "update backup_state set active_run_id = null, lease_expires_at = null where id = 1");
  }

  @Test
  void test_runLifecycle_persistsStagesAndTerminalStates() {
    long runId = runRepository.nextRunId();
    runRepository.insertRunning(runId, "MANUAL", "LOCAL", NOW);
    runRepository.updateStage(runId, BackupOrchestrationService.STAGE_DUMP);

    assertThat(runRepository.get(runId)).hasValueSatisfying(run -> {
      assertThat(run.status()).isEqualTo("RUNNING");
      assertThat(run.stage()).isEqualTo("DUMP");
      assertThat(run.triggerType()).isEqualTo("MANUAL");
    });

    Instant finished = NOW.plusSeconds(30);
    runRepository.finishSuccess(
        runId, "/opt/qaflex-backups/testinst/x.dump", "x.dump", 123L, "abc", "16.4", "20260903.01",
        NOW, finished);
    assertThat(runRepository.get(runId)).hasValueSatisfying(run -> {
      assertThat(run.status()).isEqualTo("SUCCESS");
      assertThat(run.fileBytes()).isEqualTo(123L);
      assertThat(run.durationMs()).isEqualTo(30_000L);
      assertThat(run.sha256()).isEqualTo("abc");
    });
    assertThat(runRepository.lastFinished()).hasValueSatisfying(run -> assertThat(run.id()).isEqualTo(runId));
    assertThat(runRepository.maxSuccessfulBytes()).isEqualTo(OptionalLong.of(123L));
  }

  @Test
  void test_scheduleDueCheck_respectsDayBoundsInPlatformZone() {
    long runId = runRepository.nextRunId();
    // 2026-09-09T19:30:00Z = 平台时区 2026-09-10 03:30
    runRepository.insertRunning(runId, "SCHEDULE", "LOCAL", Instant.parse("2026-09-09T19:30:00Z"));

    assertThat(runRepository.existsScheduleTriggeredOn(LocalDate.of(2026, 9, 10), ZONE)).isTrue();
    assertThat(runRepository.existsScheduleTriggeredOn(LocalDate.of(2026, 9, 9), ZONE)).isFalse();
    assertThat(runRepository.existsScheduleTriggeredOn(LocalDate.of(2026, 9, 11), ZONE)).isFalse();
  }

  @Test
  void test_stateRunGuard_acquireHeartbeatReleaseRoundTrip() {
    Instant leaseUntil = NOW.plusSeconds(300);
    assertThat(stateRepository.tryStartRun(101L, leaseUntil, NOW)).isTrue();
    assertThat(stateRepository.activeRunId()).isEqualTo(Optional.of(101L));
    assertThat(stateRepository.tryStartRun(102L, leaseUntil, NOW)).isFalse();

    stateRepository.heartbeat(101L, NOW.plusSeconds(600), NOW);
    assertThat(stateRepository.expiredActiveRunId(NOW)).isEmpty();

    stateRepository.release(101L, NOW);
    assertThat(stateRepository.activeRunId()).isEmpty();
    assertThat(stateRepository.tryStartRun(102L, leaseUntil, NOW)).isTrue();
  }

  @Test
  void test_stateRunGuard_expiredLease_isVisibleToOrphanRecovery() {
    Instant expiredLease = NOW.minusSeconds(1);
    assertThat(stateRepository.tryStartRun(201L, expiredLease, NOW.minusSeconds(600))).isTrue();

    assertThat(stateRepository.expiredActiveRunId(NOW)).isEqualTo(Optional.of(201L));
  }

  @Test
  void test_historyPagination_ordersByStartedAtDesc() {
    long first = runRepository.nextRunId();
    long second = runRepository.nextRunId();
    runRepository.insertRunning(first, "MANUAL", "LOCAL", NOW);
    runRepository.insertRunning(second, "MANUAL", "LOCAL", NOW.plusSeconds(10));

    assertThat(runRepository.page(1, 10).stream().map(BackupRun::id).toList())
        .containsExactly(second, first);
    assertThat(runRepository.count()).isEqualTo(2);
    // 每页 1 条取第 2 页 = 剩余的最后一条；第 2 页每页 2 条超出总数为空。
    assertThat(runRepository.page(2, 1).stream().map(BackupRun::id).toList())
        .containsExactly(first);
    assertThat(runRepository.page(2, 2)).isEmpty();
  }
}
