package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunSubmissionResult;
import com.data.collection.platform.mapper.SyncRunMapper;
import com.data.collection.platform.service.sync.SyncRunSubmissionService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class CustomerIssueDelayPreWritebackSyncService {
  private static final long POLL_INTERVAL_MS = 1000L;
  private static final long FACT_TASK_APPEAR_WAIT_SECONDS = 10L;
  private static final Set<SyncRunStatus> ACTIVE_RUN_STATUSES =
      Set.of(
          SyncRunStatus.SUBMITTED,
          SyncRunStatus.QUEUED,
          SyncRunStatus.RUNNING,
          SyncRunStatus.RETRYING,
          SyncRunStatus.CANCELLING);
  private static final Set<String> ACTIVE_FACT_RUN_STATUSES =
      Set.of("SUBMITTED", "QUEUED", "RUNNING", "RETRYING", "CANCELLING");

  private final SyncRunSubmissionService submissionService;
  private final SyncRunMapper syncRunMapper;
  private final JdbcTemplate jdbcTemplate;
  private final GitlabMirrorProperties properties;

  public CustomerIssueDelayPreWritebackSyncService(
      SyncRunSubmissionService submissionService,
      SyncRunMapper syncRunMapper,
      JdbcTemplate jdbcTemplate,
      GitlabMirrorProperties properties) {
    this.submissionService = submissionService;
    this.syncRunMapper = syncRunMapper;
    this.jdbcTemplate = jdbcTemplate;
    this.properties = properties;
  }

  public PreWritebackSyncResult refreshBeforeWriteback(GitlabSyncConfig config) {
    if (!properties.isCustomerIssueDelayPreWritebackSyncEnabled()) {
      return PreWritebackSyncResult.success(false);
    }
    List<String> sourceTables = preWritebackSyncTables();
    if (sourceTables.isEmpty()) {
      log.warn("Customer issue delay pre-writeback sync skipped because no source tables were configured");
      return PreWritebackSyncResult.failed();
    }
    SyncRunSubmissionResult submission =
        submissionService.submitTableRefresh(
            config,
            sourceTables,
            "客户问题延期标签写回前增量刷新");
    SyncRun run = waitForMirrorRun(submission.runId());
    if (run == null) {
      return PreWritebackSyncResult.failed();
    }
    if (run.getStatus() != SyncRunStatus.SUCCESS) {
      log.warn(
          "Customer issue delay pre-writeback sync did not finish successfully, runId={}, status={}, error={}",
          run.getId(),
          run.getStatus(),
          run.getErrorMessage());
      return PreWritebackSyncResult.failed();
    }
    if (run.getAppliedRows() == null || run.getAppliedRows() <= 0L) {
      return PreWritebackSyncResult.success(false);
    }
    return waitForIssueFactRefresh(run.getId())
        ? PreWritebackSyncResult.success(true)
        : PreWritebackSyncResult.failed();
  }

  private List<String> preWritebackSyncTables() {
    String configuredTables = properties.getCustomerIssueDelayPreWritebackSyncTables();
    if (!StringUtils.hasText(configuredTables)) {
      return List.of();
    }
    return java.util.Arrays.stream(configuredTables.split(","))
        .map(String::trim)
        .filter(StringUtils::hasText)
        .map(table -> table.toLowerCase(Locale.ROOT))
        .distinct()
        .toList();
  }

  private SyncRun waitForMirrorRun(Long runId) {
    if (runId == null) {
      return null;
    }
    Instant deadline = Instant.now().plus(waitTimeout());
    SyncRun run = syncRunMapper.selectById(runId);
    while (run != null && ACTIVE_RUN_STATUSES.contains(run.getStatus())) {
      if (Instant.now().isAfter(deadline)) {
        log.warn("Customer issue delay pre-writeback sync timed out, runId={}, status={}", runId, run.getStatus());
        return null;
      }
      sleep();
      run = syncRunMapper.selectById(runId);
    }
    return run;
  }

  private boolean waitForIssueFactRefresh(Long mirrorRunId) {
    Instant deadline = Instant.now().plus(waitTimeout());
    Instant runAppearDeadline = Instant.now().plusSeconds(FACT_TASK_APPEAR_WAIT_SECONDS);
    FactRunStatus status = factRefreshRunStatus(mirrorRunId);
    while (status.missing() && Instant.now().isBefore(runAppearDeadline)) {
      sleep();
      status = factRefreshRunStatus(mirrorRunId);
    }
    if (status.missing()) {
      log.warn("Customer issue delay pre-writeback sync applied rows but no fact refresh run was queued, mirrorRunId={}", mirrorRunId);
      return false;
    }
    while (status.active()) {
      if (Instant.now().isAfter(deadline)) {
        log.warn(
            "Customer issue delay pre-writeback ISSUE fact refresh timed out, mirrorRunId={}, status={}",
            mirrorRunId,
            status.status());
        return false;
      }
      sleep();
      status = factRefreshRunStatus(mirrorRunId);
    }
    if (!"SUCCESS".equals(status.status())) {
      log.warn(
          "Customer issue delay pre-writeback ISSUE fact refresh did not finish successfully, mirrorRunId={}, status={}",
          mirrorRunId,
          status.status());
      return false;
    }
    return true;
  }

  private FactRunStatus factRefreshRunStatus(Long mirrorRunId) {
    List<String> rows =
        jdbcTemplate.query(
            """
            select status
              from sync_runs
             where parent_run_id = ?
               and run_type = 'FACT_REFRESH'
             order by created_at desc, id desc
             limit 1
            """,
            (rs, rowNum) -> rs.getString("status"),
            mirrorRunId);
    if (rows.isEmpty()) {
      return new FactRunStatus(null);
    }
    return new FactRunStatus(rows.getFirst());
  }

  private Duration waitTimeout() {
    return Duration.ofSeconds(Math.max(1, properties.getCustomerIssueDelayPreWritebackSyncTimeoutSeconds()));
  }

  private void sleep() {
    try {
      Thread.sleep(POLL_INTERVAL_MS);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("客户问题延期标签写回前同步等待被中断", error);
    }
  }

  private record FactRunStatus(String status) {
    boolean missing() {
      return !StringUtils.hasText(status);
    }

    boolean active() {
      return ACTIVE_FACT_RUN_STATUSES.contains(status == null ? "" : status.trim().toUpperCase(Locale.ROOT));
    }
  }

  public record PreWritebackSyncResult(boolean success, boolean factsRefreshed) {
    static PreWritebackSyncResult success(boolean factsRefreshed) {
      return new PreWritebackSyncResult(true, factsRefreshed);
    }

    static PreWritebackSyncResult failed() {
      return new PreWritebackSyncResult(false, false);
    }
  }
}
