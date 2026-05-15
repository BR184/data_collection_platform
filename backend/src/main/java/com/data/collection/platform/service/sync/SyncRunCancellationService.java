package com.data.collection.platform.service.sync;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.mapper.SyncRunMapper;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SyncRunCancellationService {
  private static final Set<SyncRunStatus> CANCELLABLE_STATUSES =
      EnumSet.of(
          SyncRunStatus.SUBMITTED,
          SyncRunStatus.QUEUED,
          SyncRunStatus.RUNNING,
          SyncRunStatus.RETRYING,
          SyncRunStatus.CANCELLING);

  private final SyncRunMapper syncRunMapper;
  private final JdbcTemplate jdbcTemplate;

  public SyncRunCancellationService(SyncRunMapper syncRunMapper, JdbcTemplate jdbcTemplate) {
    this.syncRunMapper = syncRunMapper;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Transactional
  public SyncRunCancellationResult requestCancel(Long configId, String requestedBy, String reason) {
    SyncRun run = findCancellableRun(configId);
    if (run == null) {
      return SyncRunCancellationResult.rejected("No cancellable sync run is available");
    }

    LocalDateTime now = LocalDateTime.now();
    boolean queued = run.getStatus() == SyncRunStatus.SUBMITTED || run.getStatus() == SyncRunStatus.QUEUED;
    run.setCancelRequested(true);
    run.setStatus(queued ? SyncRunStatus.CANCELLED : SyncRunStatus.CANCELLING);
    run.setUpdatedAt(now);
    if (queued) {
      run.setFinishedAt(now);
      run.setErrorMessage("Cancelled before worker start");
    }
    syncRunMapper.updateById(run);
    recordCancellationEvent(run, requestedBy, reason, now, queued);
    return new SyncRunCancellationResult(
        true,
        run.getId(),
        run.getRunId(),
        run.getStatus(),
        queued ? "Queued sync run cancelled" : "Cancellation requested");
  }

  private SyncRun findCancellableRun(Long configId) {
    if (configId == null) {
      return null;
    }
    List<SyncRun> runs =
        syncRunMapper.selectList(
            new LambdaQueryWrapper<SyncRun>()
                .eq(SyncRun::getConfigId, configId)
                .in(SyncRun::getStatus, CANCELLABLE_STATUSES)
                .orderByAsc(SyncRun::getCreatedAt)
                .orderByAsc(SyncRun::getId));
    if (runs == null || runs.isEmpty()) {
      return null;
    }
    return runs.stream()
        .min(
            Comparator.comparingInt((SyncRun run) -> cancellationRank(run.getStatus()))
                .thenComparing(SyncRun::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(SyncRun::getId, Comparator.nullsLast(Comparator.naturalOrder())))
        .orElse(null);
  }

  private int cancellationRank(SyncRunStatus status) {
    if (status == SyncRunStatus.RUNNING || status == SyncRunStatus.RETRYING || status == SyncRunStatus.CANCELLING) {
      return 0;
    }
    return 1;
  }

  private void recordCancellationEvent(
      SyncRun run,
      String requestedBy,
      String reason,
      LocalDateTime now,
      boolean queued) {
    jdbcTemplate.update(
        """
        insert into sync_run_events (run_id, config_id, source_instance, event_type, message, payload_json, created_at)
        values (?, ?, ?, ?, ?, ?, ?)
        """,
        run.getId(),
        run.getConfigId(),
        run.getSourceInstance(),
        queued ? "RUN_CANCELLED_BEFORE_START" : "RUN_CANCELLATION_REQUESTED",
        queued ? "Queued sync run cancelled" : "Cancellation requested",
        "{\"requestedBy\":\""
            + escapeJson(requestedBy)
            + "\",\"reason\":\""
            + escapeJson(reason)
            + "\"}",
        now);
  }

  private String escapeJson(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  public record SyncRunCancellationResult(
      boolean accepted,
      Long runId,
      String externalRunId,
      SyncRunStatus status,
      String message) {
    static SyncRunCancellationResult rejected(String message) {
      return new SyncRunCancellationResult(false, null, null, null, message);
    }
  }
}
