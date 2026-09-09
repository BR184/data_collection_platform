package com.data.collection.platform.service.backup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 备份后台调度：①每日判期——启用调度、到达配置时刻且当日尚无定时触发尝试时经统一流水线补跑
 * （当日尝试过即不再补，失败等次日，与"失败不自动重试"一致）；②孤儿回收——运行权租约过期后把
 * RUNNING 运行标 FAILED 并清理临时文件，防止服务重启留下永久 RUNNING 记录。两项巡检共用固定
 * 节拍，均受 platform.background-jobs.enabled 总开关约束。
 */
@Component
public class BackupScheduler {
  private static final Logger log = LoggerFactory.getLogger(BackupScheduler.class);

  private final BackupSettingsRepository settingsRepository;
  private final BackupRunRepository runRepository;
  private final BackupStateRepository stateRepository;
  private final BackupOrchestrationService orchestration;
  private final BackupConfigurationProperties properties;
  private final Clock clock;

  @Autowired
  public BackupScheduler(
      BackupSettingsRepository settingsRepository,
      BackupRunRepository runRepository,
      BackupStateRepository stateRepository,
      BackupOrchestrationService orchestration,
      BackupConfigurationProperties properties) {
    this(
        settingsRepository,
        runRepository,
        stateRepository,
        orchestration,
        properties,
        Clock.systemUTC());
  }

  BackupScheduler(
      BackupSettingsRepository settingsRepository,
      BackupRunRepository runRepository,
      BackupStateRepository stateRepository,
      BackupOrchestrationService orchestration,
      BackupConfigurationProperties properties,
      Clock clock) {
    this.settingsRepository = settingsRepository;
    this.runRepository = runRepository;
    this.stateRepository = stateRepository;
    this.orchestration = orchestration;
    this.properties = properties;
    this.clock = clock;
  }

  /** 每分钟级判期巡检：到期且当日未尝试则触发一次定时备份。 */
  @Scheduled(
      fixedDelayString = "${platform.backup.scheduler-delay-ms:60000}",
      initialDelayString = "${platform.backup.scheduler-initial-delay-ms:15000}")
  public void scheduleIfDue() {
    try {
      BackupSettings settings = settingsRepository.load().orElse(BackupSettings.defaults());
      if (!settings.enabled()) {
        return;
      }
      ZoneId zone = BackupOrchestrationService.PLATFORM_ZONE;
      ZonedDateTime now = clock.instant().atZone(zone);
      if (runRepository.existsScheduleTriggeredOn(now.toLocalDate(), zone)) {
        return;
      }
      if (now.toLocalTime().isBefore(settings.scheduleTime())) {
        return;
      }
      orchestration.triggerScheduled();
    } catch (RuntimeException failure) {
      log.warn("backup_schedule_check_failed message={}", failure.getMessage());
    }
  }

  /** 孤儿回收巡检：租约过期的 RUNNING 运行标 FAILED 并清理 staging 临时文件。 */
  @Scheduled(
      fixedDelayString = "${platform.backup.scheduler-delay-ms:60000}",
      initialDelayString = "${platform.backup.scheduler-initial-delay-ms:15000}")
  public void recoverOrphans() {
    try {
      Instant now = clock.instant();
      stateRepository
          .expiredActiveRunId(now)
          .ifPresent(
              runId -> {
                runRepository
                    .get(runId)
                    .filter(run -> BackupRun.STATUS_RUNNING.equals(run.status()))
                    .ifPresent(
                        run ->
                            runRepository.finishFailure(
                                runId,
                                "备份执行进程异常中断（服务重启或进程被终止），请重新触发备份",
                                run.startedAt(),
                                now));
                stateRepository.release(runId, now);
                cleanupStagingFor(runId);
                log.warn("backup_orphan_recovered runId={}", runId);
              });
    } catch (RuntimeException failure) {
      log.warn("backup_orphan_recovery_failed message={}", failure.getMessage());
    }
  }

  private void cleanupStagingFor(long runId) {
    try {
      String label = BackupFileSupport.sanitizeLabel(properties.getInstanceLabel());
      Path stagingDir =
          Path.of(properties.getRoot()).resolve(label).resolve(BackupFileSupport.STAGING_DIR_NAME);
      Path orphanFile = stagingDir.resolve("tmp-" + runId + ".dump");
      Files.deleteIfExists(orphanFile);
    } catch (RuntimeException | java.io.IOException cleanupFailure) {
      log.warn("backup_orphan_cleanup_failed runId={} message={}", runId, cleanupFailure.getMessage());
    }
  }
}
