package com.data.collection.platform.service.backup;

import com.data.collection.platform.entity.backup.BackupRunResponse;
import com.data.collection.platform.entity.backup.BackupRunsPageResponse;
import com.data.collection.platform.entity.backup.BackupStatusResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 备份执行状态与历史的读取侧：当前运行、最近结果、下次计划时刻与分页历史。 */
@Service
public class BackupMonitorService {
  private static final DateTimeFormatter SCHEDULE_TIME = DateTimeFormatter.ofPattern("HH:mm");

  private final BackupSettingsRepository settingsRepository;
  private final BackupRunRepository runRepository;
  private final BackupStateRepository stateRepository;
  private final Clock clock;

  @Autowired
  public BackupMonitorService(
      BackupSettingsRepository settingsRepository,
      BackupRunRepository runRepository,
      BackupStateRepository stateRepository) {
    this(settingsRepository, runRepository, stateRepository, Clock.systemUTC());
  }

  BackupMonitorService(
      BackupSettingsRepository settingsRepository,
      BackupRunRepository runRepository,
      BackupStateRepository stateRepository,
      Clock clock) {
    this.settingsRepository = settingsRepository;
    this.runRepository = runRepository;
    this.stateRepository = stateRepository;
    this.clock = clock;
  }

  /** 当前执行状态：运行中的运行、最近一次已结束运行与启用调度时的下次计划时刻。 */
  public BackupStatusResponse status() {
    BackupSettings settings = settingsRepository.load().orElse(BackupSettings.defaults());
    BackupRunResponse currentRun =
        stateRepository
            .activeRunId()
            .flatMap(runRepository::get)
            .filter(run -> BackupRun.STATUS_RUNNING.equals(run.status()))
            .map(this::toResponse)
            .orElse(null);
    BackupRunResponse lastCompleted = runRepository.lastFinished().map(this::toResponse).orElse(null);
    return new BackupStatusResponse(
        currentRun != null,
        currentRun,
        lastCompleted,
        settings.enabled(),
        settings.scheduleTime().format(SCHEDULE_TIME),
        nextRunAt(settings));
  }

  /** 备份历史分页（开始时间倒序）。 */
  public BackupRunsPageResponse history(int page, int size) {
    int safePage = Math.max(1, page);
    int safeSize = Math.min(100, Math.max(1, size));
    long total = runRepository.count();
    List<BackupRunResponse> records =
        runRepository.page(safePage, safeSize).stream().map(this::toResponse).toList();
    return new BackupRunsPageResponse(total, safePage, safeSize, records);
  }

  private Instant nextRunAt(BackupSettings settings) {
    if (!settings.enabled()) {
      return null;
    }
    ZoneId zone = BackupOrchestrationService.PLATFORM_ZONE;
    LocalDate today = LocalDate.now(zone);
    boolean attemptedToday = runRepository.existsScheduleTriggeredOn(today, zone);
    // 当日已尝试（无论成败）→ 下次为明日；未尝试 → 今日时刻（已过期表示调度器将立即补跑）。
    LocalDate target = attemptedToday ? today.plusDays(1) : today;
    return target.atTime(settings.scheduleTime()).atZone(zone).toInstant();
  }

  private BackupRunResponse toResponse(BackupRun run) {
    return new BackupRunResponse(
        run.id(),
        run.triggerType(),
        run.status(),
        run.storageMode(),
        run.stage(),
        run.targetPath(),
        run.fileName(),
        run.fileBytes(),
        run.sha256(),
        run.pgServerVersion(),
        run.flywayVersion(),
        run.startedAt(),
        run.finishedAt(),
        run.durationMs(),
        run.errorMessage());
  }
}
