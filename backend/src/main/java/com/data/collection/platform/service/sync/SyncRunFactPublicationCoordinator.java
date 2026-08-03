package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.mapper.SyncRunMapper;
import com.data.collection.platform.service.GitlabConfigService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 确保镜像 outbox 由该父运行唯一的事实子运行持续发布。 */
@Service
@Slf4j
public class SyncRunFactPublicationCoordinator {
  private static final String DEFAULT_REASON = "镜像变化已提交，发布定向事实";

  private final SyncRunMapper syncRunMapper;
  private final JdbcTemplate jdbcTemplate;
  private final GitlabConfigService configService;
  private final SyncRunSubmissionService submissionService;

  public SyncRunFactPublicationCoordinator(
      SyncRunMapper syncRunMapper,
      JdbcTemplate jdbcTemplate,
      GitlabConfigService configService,
      SyncRunSubmissionService submissionService) {
    this.syncRunMapper = syncRunMapper;
    this.jdbcTemplate = jdbcTemplate;
    this.configService = configService;
    this.submissionService = submissionService;
  }

  /**
   * 为镜像运行创建或唤醒唯一事实子运行。
   *
   * <p>定向模式只有存在未发布目标时才创建运行。调用者可位于 ODS 页事务内，使目标与子运行
   * 一同提交；已失败的子运行不会被自动复活，目标继续保留供显式恢复。
   *
   * @param mirrorRunId 镜像父运行数据库 ID
   * @param full 是否要求全量事实发布
   * @param reason 运行原因；空值使用统一原因
   */
  @Transactional
  public void ensurePublication(Long mirrorRunId, boolean full, String reason) {
    SyncRun mirrorRun = lockMirrorRun(mirrorRunId);
    if (!full && !hasUnpublishedTargets(mirrorRunId)) {
      return;
    }
    SyncRun existing = findFactRun(mirrorRunId);
    if (existing != null) {
      reuseExisting(existing, full);
      return;
    }
    GitlabSyncConfig config = configService.getConfigById(mirrorRun.getConfigId());
    submissionService.submitFactRefresh(
        config,
        mirrorRunId,
        full,
        reason == null || reason.isBlank() ? DEFAULT_REASON : reason);
  }

  private SyncRun lockMirrorRun(Long mirrorRunId) {
    if (mirrorRunId == null || mirrorRunId <= 0L) {
      throw new IllegalArgumentException("事实发布协调必须关联镜像父运行");
    }
    List<Long> rows =
        jdbcTemplate.queryForList(
            """
            select id
              from sync_runs
             where id = ?
               and run_type in (
                   'FULL_SYNC', 'INCREMENTAL_SYNC', 'TABLE_REFRESH',
                   'SYSTEM_HOOK', 'FULL_COMPENSATION_SCAN')
             for update
            """,
            Long.class,
            mirrorRunId);
    if (rows.size() != 1) {
      throw new IllegalStateException("事实发布协调找不到镜像父运行：" + mirrorRunId);
    }
    SyncRun run = syncRunMapper.selectById(mirrorRunId);
    if (run == null) {
      throw new IllegalStateException("事实发布协调找不到镜像父运行：" + mirrorRunId);
    }
    return run;
  }

  private boolean hasUnpublishedTargets(Long mirrorRunId) {
    Boolean exists =
        jdbcTemplate.queryForObject(
            """
             select exists(
               select 1
                 from sync_run_fact_targets target
                 join fact_change_heads head
                   on head.source_instance = target.source_instance
                  and head.fact_type = target.fact_type
                  and head.root_id = target.root_id
                where target.mirror_run_id = ?
                  and head.published_version < target.change_version
             )
            """,
            Boolean.class,
            mirrorRunId);
    return Boolean.TRUE.equals(exists);
  }

  private SyncRun findFactRun(Long mirrorRunId) {
    List<SyncRun> runs =
        syncRunMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SyncRun>()
                .eq(SyncRun::getRunType, SyncRunType.FACT_REFRESH)
                .eq(SyncRun::getParentRunId, mirrorRunId)
                .orderByAsc(SyncRun::getId)
                .last("limit 1 for update"));
    return runs == null || runs.isEmpty() ? null : runs.getFirst();
  }

  private void reuseExisting(SyncRun run, boolean full) {
    if (run.getStatus() == SyncRunStatus.PAUSED || run.getStatus() == SyncRunStatus.RETRYING) {
      int updated =
          jdbcTemplate.update(
              """
              update sync_runs
                 set status = 'QUEUED', run_after = current_timestamp,
                     error_message = null, updated_at = current_timestamp
               where id = ?
                 and status in ('PAUSED', 'RETRYING')
                 and (lease_owner is null or lease_until < current_timestamp)
              """,
              run.getId());
      if (updated > 0) {
        run.setStatus(SyncRunStatus.QUEUED);
        run.setRunAfter(LocalDateTime.now());
      }
    } else if (!full
        && (run.getStatus() == SyncRunStatus.SUCCESS
            || run.getStatus() == SyncRunStatus.PARTIAL_SUCCESS)) {
      int updated =
          jdbcTemplate.update(
              """
              update sync_runs
                 set status = 'QUEUED',
                     run_after = current_timestamp,
                     finished_at = null,
                     error_message = null,
                     updated_at = current_timestamp
               where id = ?
                 and status in ('SUCCESS', 'PARTIAL_SUCCESS')
              """,
              run.getId());
      if (updated > 0) {
        run.setStatus(SyncRunStatus.QUEUED);
        run.setFinishedAt(null);
        run.setRunAfter(LocalDateTime.now());
      }
    } else if (!SyncRunStateMachine.activeStatuses().contains(run.getStatus())
        && run.getStatus() != SyncRunStatus.SUCCESS) {
      log.warn(
          "Fact publication remains pending because the unique fact run requires explicit recovery, mirrorRunId={}, factRunId={}, status={}",
          run.getParentRunId(),
          run.getId(),
          run.getStatus());
    }
  }
}
