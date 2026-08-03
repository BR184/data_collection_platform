package com.data.collection.platform.service.sync;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.mapper.SyncRunMapper;
import com.data.collection.platform.service.GitlabConfigService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class SyncRunDeadlineGuard {
  private static final List<SyncRunStatus> DEADLINE_CHECK_STATUSES =
      List.of(SyncRunStatus.RUNNING, SyncRunStatus.RETRYING);
  private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

  private final GitlabMirrorProperties properties;
  private final SyncRunMapper syncRunMapper;
  private final GitlabConfigService configService;
  private final Clock clock;

  @Autowired
  public SyncRunDeadlineGuard(
      GitlabMirrorProperties properties,
      SyncRunMapper syncRunMapper,
      GitlabConfigService configService) {
    this(properties, syncRunMapper, configService, Clock.systemDefaultZone());
  }

  SyncRunDeadlineGuard(
      GitlabMirrorProperties properties,
      SyncRunMapper syncRunMapper,
      GitlabConfigService configService,
      Clock clock) {
    this.properties = properties;
    this.syncRunMapper = syncRunMapper;
    this.configService = configService;
    this.clock = clock;
  }

  public int requestCancellationForExpiredRuns() {
    List<SyncRun> runs =
        syncRunMapper.selectList(
            new LambdaQueryWrapper<SyncRun>()
                .in(SyncRun::getStatus, DEADLINE_CHECK_STATUSES)
                .isNotNull(SyncRun::getStartedAt));
    if (runs == null || runs.isEmpty()) {
      return 0;
    }
    int cancelled = 0;
    for (SyncRun run : runs) {
      if (requestCancellationIfExpired(run)) {
        cancelled++;
      }
    }
    return cancelled;
  }

  public boolean requestCancellationIfExpired(SyncRun run) {
    String reason = deadlineReason(run);
    if (reason == null) {
      return false;
    }
    int updated =
        syncRunMapper.update(
            null,
            new UpdateWrapper<SyncRun>()
                .eq("id", run.getId())
                .in("status", SyncRunStatus.RUNNING.name(), SyncRunStatus.RETRYING.name())
                .set("cancel_requested", true)
                .set("status", SyncRunStatus.CANCELLING.name())
                .set("error_message", reason)
                .set("updated_at", now()));
    if (updated != 1) {
      return false;
    }
    run.setCancelRequested(true);
    run.setStatus(SyncRunStatus.CANCELLING);
    run.setErrorMessage(reason);
    run.setUpdatedAt(now());
    log.warn("Requested sync run cancellation after deadline, runId={}, reason={}", run.getRunId(), reason);
    return true;
  }

  private String deadlineReason(SyncRun run) {
    if (run == null || run.getId() == null || run.getStartedAt() == null) {
      return null;
    }
    LocalDateTime current = now();
    int maxRunDurationMinutes = properties.getMaxRunDurationMinutes();
    if (maxRunDurationMinutes > 0
        && !current.isBefore(run.getStartedAt().plusMinutes(maxRunDurationMinutes))) {
      return "Sync run exceeded maximum runtime of " + maxRunDurationMinutes + " minutes";
    }
    if (!isCompensationRun(run.getRunType())) {
      return null;
    }
    if (properties.isCancelCompensationRunsAtDayBoundary()
        && current.toLocalDate().isAfter(run.getStartedAt().toLocalDate())) {
      return "Compensation sync crossed calendar day boundary";
    }
    LocalDateTime windowDeadline = compensationWindowDeadline(run);
    if (windowDeadline != null && !current.isBefore(windowDeadline)) {
      return "Compensation sync exceeded configured compensation window ending at "
          + windowDeadline.toLocalTime().format(TIME_FORMATTER);
    }
    return null;
  }

  private LocalDateTime compensationWindowDeadline(SyncRun run) {
    GitlabSyncConfig config = configFor(run);
    if (config == null || !"WINDOWED_INTERVAL".equalsIgnoreCase(config.getCompensationScheduleMode())) {
      return null;
    }
    LocalTime start = parseTime(config.getCompensationWindowStart());
    LocalTime end = parseTime(config.getCompensationWindowEnd());
    if (start == null || end == null || start.equals(end)) {
      return null;
    }
    return firstWindowEndAfter(run.getStartedAt(), end);
  }

  private GitlabSyncConfig configFor(SyncRun run) {
    try {
      return configService.getConfigById(run.getConfigId());
    } catch (RuntimeException error) {
      log.warn("Failed to load config while checking sync run deadline, runId={}", run.getRunId(), error);
      return null;
    }
  }

  private LocalDateTime firstWindowEndAfter(LocalDateTime startedAt, LocalTime end) {
    for (int offset = -1; offset <= 2; offset++) {
      LocalDateTime candidate = startedAt.toLocalDate().plusDays(offset).atTime(end);
      if (candidate.isAfter(startedAt)) {
        return candidate;
      }
    }
    return startedAt.plus(Duration.ofDays(1)).toLocalDate().atTime(end);
  }

  private LocalTime parseTime(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalTime.parse(value.trim(), TIME_FORMATTER);
    } catch (DateTimeParseException error) {
      log.warn("Ignoring invalid compensation window time while checking sync run deadline: {}", value);
      return null;
    }
  }

  private boolean isCompensationRun(SyncRunType runType) {
    return runType == SyncRunType.FULL_COMPENSATION_SCAN;
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }
}
