package com.data.collection.platform.service.backup;

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
 * （当日尝试过即不再补，失败等次日，与"失败不自动重试"一致）；②孤儿回收——租约过期后先撤销执行 token，
 * 协调停止 worker/子进程/远程会话，再删除该运行唯一暂存文件并收敛历史状态。两项巡检共用固定
 * 节拍，均受 platform.background-jobs.enabled 总开关约束。
 */
@Component
public class BackupScheduler {
  private static final Logger log = LoggerFactory.getLogger(BackupScheduler.class);

  private final BackupSettingsRepository settingsRepository;
  private final BackupRunRepository runRepository;
  private final BackupStateRepository stateRepository;
  private final BackupOrchestrationService orchestration;
  private final Clock clock;

  @Autowired
  public BackupScheduler(
      BackupSettingsRepository settingsRepository,
      BackupRunRepository runRepository,
      BackupStateRepository stateRepository,
      BackupOrchestrationService orchestration) {
    this(
        settingsRepository,
        runRepository,
        stateRepository,
        orchestration,
        Clock.systemUTC());
  }

  BackupScheduler(
      BackupSettingsRepository settingsRepository,
      BackupRunRepository runRepository,
      BackupStateRepository stateRepository,
      BackupOrchestrationService orchestration,
      Clock clock) {
    this.settingsRepository = settingsRepository;
    this.runRepository = runRepository;
    this.stateRepository = stateRepository;
    this.orchestration = orchestration;
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

  /** 孤儿回收巡检：先撤销、停止并确认执行，再清理身份化 staging 文件与运行权。 */
  @Scheduled(
      fixedDelayString = "${platform.backup.scheduler-delay-ms:60000}",
      initialDelayString = "${platform.backup.scheduler-initial-delay-ms:15000}")
  public void recoverOrphans() {
    try {
      Instant now = clock.instant();
      stateRepository
          .revokeExpiredExecution(now)
          .ifPresent(execution -> {
            if (!orchestration.stopExpiredExecution(execution)) {
              stateRepository.recordRecoveryPending(
                  execution,
                  "备份租约已撤销，但无法确认外部执行已停止；保留运行权与暂存文件等待后续恢复巡检，"
                      + "长时间未收敛时按 runbook 的孤儿备份受控结算步骤处置",
                  now);
              log.error("backup_orphan_stop_unconfirmed runId={}", execution.runId());
              return;
            }
            if (!orchestration.cleanupExpiredStaging(execution)) {
              stateRepository.recordRecoveryPending(
                  execution,
                  "备份执行已停止，但本次暂存文件清理失败；保留运行权等待后续恢复巡检",
                  now);
              log.error("backup_orphan_cleanup_unconfirmed runId={}", execution.runId());
              return;
            }
            if (stateRepository.settleOwnedRun(
                execution.runId(),
                execution.executionToken(),
                "备份执行租约过期，恢复器已确认执行停止并完成本次暂存清理",
                now)) {
              log.warn("backup_orphan_recovered runId={}", execution.runId());
            }
          });
    } catch (RuntimeException failure) {
      log.warn("backup_orphan_recovery_failed message={}", failure.getMessage());
    }
  }

}
