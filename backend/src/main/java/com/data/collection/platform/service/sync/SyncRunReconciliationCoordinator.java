package com.data.collection.platform.service.sync;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在运行级静止屏障后为每张全表范围源表创建唯一删除对账任务。 */
@Service
public class SyncRunReconciliationCoordinator {
  private final JdbcTemplate jdbcTemplate;

  public SyncRunReconciliationCoordinator(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 锁定运行的阶段规划边界。
   *
   * <p>页事务必须在创建后续扫描/权威任务以及推进当前任务终态前调用，确保最终扫描页不会与
   * 迟到的 producer 并发判空。
   *
   * @param runId 镜像运行数据库 ID
   */
  public void lockStageMutation(Long runId) {
    if (runId == null || runId <= 0L) {
      throw new IllegalArgumentException("删除对账阶段协调必须包含镜像运行 ID");
    }
    List<Long> rows =
        jdbcTemplate.queryForList(
            "select id from sync_runs where id = ? and run_type <> 'FACT_REFRESH' for update",
            Long.class,
            runId);
    if (rows.size() != 1) {
      throw new IllegalStateException("删除对账阶段协调找不到镜像运行：" + runId);
    }
  }

  /**
   * 在所有扫描 producer 和权威范围均成功后，幂等创建每表唯一的可续跑对账任务。
   *
   * <p>调用者必须已在当前事务持有运行行锁。普通增量、手动刷新和精确事件不触发全表
   * 删除探测；只有显式全量同步和全量补偿走该阶段屏障。
   *
   * @param runId 镜像运行数据库 ID
   * @return 本次创建的表级对账任务数
   */
  @Transactional
  public int planIfReady(Long runId) {
    if (runId == null || runId <= 0L) {
      throw new IllegalArgumentException("删除对账阶段协调必须包含镜像运行 ID");
    }
    lockStageMutation(runId);
    return jdbcTemplate.update(
        """
        insert into sync_run_table_tasks(
            run_id, config_id, state_id, source_instance, source_table, mirror_table,
            task_type, status, row_strategy, task_stage, parent_task_id,
            watermark_at, cursor_updated_at, cursor_pk, scan_upper_bound_at,
            page_number, lookup_scope_json, batch_size, run_after,
            retry_count, max_retry_count, rows_scanned, rows_applied,
            created_at, updated_at)
        select candidate.run_id,
               candidate.config_id,
               candidate.state_id,
               candidate.source_instance,
               candidate.source_table,
               candidate.mirror_table,
               candidate.task_type,
               'QUEUED',
               candidate.row_strategy,
               'RECONCILE',
               candidate.id,
               null,
               null,
               null,
               null,
               1,
               null,
               candidate.batch_size,
               current_timestamp,
               0,
               candidate.max_retry_count,
               0,
               0,
               current_timestamp,
               current_timestamp
          from (
            select distinct on (task.source_table) task.*
              from sync_run_table_tasks task
              join sync_runs run on run.id = task.run_id
             where task.run_id = ?
               and run.run_type in ('FULL_SYNC', 'FULL_COMPENSATION_SCAN')
               and task.task_stage = 'SCAN'
               and task.row_strategy = 'FULL_RECONCILE'
               and coalesce(task.lookup_scope_json, '') = ''
             order by task.source_table, task.id
          ) candidate
         where not exists (
                 select 1
                   from sync_run_table_tasks producer
                  where producer.run_id = candidate.run_id
                    and producer.task_stage = 'SCAN'
                    and producer.status <> 'SUCCESS')
           and not exists (
                 select 1
                   from sync_run_authoritative_scopes scope
                  where scope.run_id = candidate.run_id
                    and scope.status <> 'SUCCESS')
           and not exists (
                 select 1
                   from sync_run_table_tasks existing
                  where existing.run_id = candidate.run_id
                    and existing.source_table = candidate.source_table
                    and existing.task_stage = 'RECONCILE')
        """,
        runId);
  }
}
