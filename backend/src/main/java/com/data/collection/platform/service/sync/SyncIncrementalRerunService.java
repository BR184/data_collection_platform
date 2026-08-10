package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.mapper.SyncRunMapper;
import com.data.collection.platform.entity.SyncTriggerType;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 持久合并活动增量之后的触发，并在运行终态创建一个尾部补跑。 */
@Service
public class SyncIncrementalRerunService {
  private final JdbcTemplate jdbcTemplate;
  private final SyncRunMapper syncRunMapper;
  private final Clock clock;

  @Autowired
  public SyncIncrementalRerunService(
      JdbcTemplate jdbcTemplate, SyncRunMapper syncRunMapper) {
    this(jdbcTemplate, syncRunMapper, Clock.systemDefaultZone());
  }

  SyncIncrementalRerunService(
      JdbcTemplate jdbcTemplate, SyncRunMapper syncRunMapper, Clock clock) {
    this.jdbcTemplate = jdbcTemplate;
    this.syncRunMapper = syncRunMapper;
    this.clock = clock;
  }

  /** 记录活动增量之后至少发生过一次新触发。 */
  public void requestRerun(
      SyncRun activeRun, SyncTriggerType triggerType, String reason) {
    if (activeRun == null
        || activeRun.getId() == null
        || activeRun.getConfigId() == null
        || activeRun.getRunType() != SyncRunType.INCREMENTAL_SYNC) {
      throw new IllegalArgumentException("尾部补跑请求必须关联活动增量运行");
    }
    int updated =
        jdbcTemplate.update(
            """
            update gitlab_sync_configs
               set incremental_rerun_requested_at =
                     coalesce(incremental_rerun_requested_at, current_timestamp),
                   incremental_rerun_trigger_count = incremental_rerun_trigger_count + 1,
                   updated_at = current_timestamp
             where id = ?
            """,
            activeRun.getConfigId());
    if (updated != 1) {
      throw new IllegalStateException("无法持久化增量尾部补跑请求");
    }
    int eventInserted =
        jdbcTemplate.update(
        """
        insert into sync_run_events(
            run_id, config_id, source_instance, event_type, message, payload_json, created_at)
        select ?, ?, ?, 'RERUN_REQUESTED', ?,
               jsonb_build_object(
                 'targetRunId', ?,
                 'requestedAt', config.incremental_rerun_requested_at,
                 'triggerCount', config.incremental_rerun_trigger_count,
                 'triggerType', ?,
                 'reason', ?,
                 'triggeredAt', current_timestamp),
               current_timestamp
          from gitlab_sync_configs config
         where config.id = ?
        """,
        activeRun.getId(),
        activeRun.getConfigId(),
        activeRun.getSourceInstance(),
        "增量运行期间收到新触发，已合并为一个尾部补跑",
        activeRun.getId(),
        triggerType == null ? SyncTriggerType.MANUAL.name() : triggerType.name(),
        reason,
        activeRun.getConfigId());
    if (eventInserted != 1) {
      throw new IllegalStateException("无法记录增量尾部补跑触发事件");
    }
  }

  /**
   * 在当前终态事务中消费 pending，并创建恰好一个使用新扫描上界的增量运行。
   *
   * @return 新建运行；没有 pending 时为空
   */
  public SyncRun enqueuePendingRerun(SyncRun completedRun) {
    if (completedRun == null
        || completedRun.getConfigId() == null
        || completedRun.getRunType() != SyncRunType.INCREMENTAL_SYNC) {
      return null;
    }
    PendingRerun request = lockPendingRerun(completedRun.getConfigId());
    if (request == null) {
      return null;
    }
    LocalDateTime now = LocalDateTime.now(clock);
    SyncRun rerun = cloneForRerun(completedRun, now);
    syncRunMapper.insert(rerun);
    clearPendingRerun(completedRun.getConfigId(), request);
    jdbcTemplate.update(
        """
        insert into sync_run_events(
            run_id, config_id, source_instance, event_type, message, payload_json, created_at)
        values (?, ?, ?, 'RERUN_QUEUED', ?,
                jsonb_build_object(
                  'rerunId', ?,
                  'triggerCount', ?,
                  'requestedAt', ?),
                current_timestamp)
        """,
        completedRun.getId(),
        completedRun.getConfigId(),
        completedRun.getSourceInstance(),
        "已创建合并后的增量尾部补跑",
        rerun.getId(),
        request.triggerCount(),
        request.requestedAt());
    return rerun;
  }

  /**
   * 让新建增量接管异常终态遗留的 pending，避免调度恢复后重复创建第二个尾部补跑。
   *
   * <p>调用方必须在同一事务内先持有来源提交锁并已持久化 {@code queuedRun}。
   */
  public boolean adoptPendingRerun(SyncRun queuedRun) {
    if (queuedRun == null
        || queuedRun.getId() == null
        || queuedRun.getConfigId() == null
        || queuedRun.getRunType() != SyncRunType.INCREMENTAL_SYNC) {
      return false;
    }
    PendingRerun request = lockPendingRerun(queuedRun.getConfigId());
    if (request == null) {
      return false;
    }
    clearPendingRerun(queuedRun.getConfigId(), request);
    int eventInserted =
        jdbcTemplate.update(
            """
            insert into sync_run_events(
                run_id, config_id, source_instance, event_type, message, payload_json, created_at)
            values (?, ?, ?, 'RERUN_QUEUED', ?,
                    jsonb_build_object(
                      'rerunId', ?,
                      'triggerCount', ?,
                      'requestedAt', ?,
                      'recovered', true),
                    current_timestamp)
            """,
            queuedRun.getId(),
            queuedRun.getConfigId(),
            queuedRun.getSourceInstance(),
            "异常终态遗留的增量尾部补跑已由新运行接管",
            queuedRun.getId(),
            request.triggerCount(),
            request.requestedAt());
    if (eventInserted != 1) {
      throw new IllegalStateException("无法记录恢复的增量尾部补跑事件");
    }
    return true;
  }

  private PendingRerun lockPendingRerun(Long configId) {
    List<PendingRerun> pending =
        jdbcTemplate.query(
            """
            select incremental_rerun_requested_at, incremental_rerun_trigger_count
              from gitlab_sync_configs
             where id = ?
               and incremental_rerun_requested_at is not null
             for update
            """,
            (rs, rowNum) ->
                new PendingRerun(
                    rs.getTimestamp("incremental_rerun_requested_at").toLocalDateTime(),
                    rs.getInt("incremental_rerun_trigger_count")),
            configId);
    return pending == null || pending.isEmpty() ? null : pending.getFirst();
  }

  private void clearPendingRerun(Long configId, PendingRerun request) {
    int cleared =
        jdbcTemplate.update(
            """
            update gitlab_sync_configs
               set incremental_rerun_requested_at = null,
                   incremental_rerun_trigger_count = 0,
                   updated_at = current_timestamp
             where id = ?
               and incremental_rerun_requested_at = ?
            """,
            configId,
            request.requestedAt());
    if (cleared != 1) {
      throw new IllegalStateException("增量尾部补跑 pending 状态发生并发变化");
    }
  }

  private SyncRun cloneForRerun(SyncRun completedRun, LocalDateTime now) {
    SyncRun rerun = new SyncRun();
    rerun.setRunId(
        SyncRunIdGenerator.generate(
            SyncRunType.INCREMENTAL_SYNC, completedRun.getSourceInstance()));
    rerun.setConfigId(completedRun.getConfigId());
    rerun.setSourceInstance(completedRun.getSourceInstance());
    rerun.setRunType(SyncRunType.INCREMENTAL_SYNC);
    rerun.setTriggerType(com.data.collection.platform.entity.SyncTriggerType.SCHEDULE);
    rerun.setStatus(SyncRunStatus.QUEUED);
    rerun.setPriority(completedRun.getPriority());
    rerun.setExclusiveScope(completedRun.getExclusiveScope());
    rerun.setCancelRequested(false);
    rerun.setRequestReason("合并触发的增量尾部补跑");
    rerun.setPayloadJson(completedRun.getPayloadJson());
    rerun.setThreadMode(completedRun.getThreadMode());
    rerun.setThreadValue(completedRun.getThreadValue());
    rerun.setResolvedWorkerCount(completedRun.getResolvedWorkerCount());
    rerun.setPlannedTableCount(0);
    rerun.setCompletedTableCount(0);
    rerun.setScannedRows(0L);
    rerun.setAppliedRows(0L);
    rerun.setRunAfter(now);
    rerun.setCreatedAt(now);
    rerun.setUpdatedAt(now);
    return rerun;
  }

  private record PendingRerun(LocalDateTime requestedAt, int triggerCount) {}
}
