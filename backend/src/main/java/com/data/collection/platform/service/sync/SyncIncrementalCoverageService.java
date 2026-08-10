package com.data.collection.platform.service.sync;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 校验一次快速增量是否真正覆盖必需来源上界和权威范围。 */
@Service
public class SyncIncrementalCoverageService {
  private final JdbcTemplate jdbcTemplate;

  public SyncIncrementalCoverageService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** 返回运行覆盖结论；执行成功与数据追平使用该结论分离。 */
  public CoverageResult evaluate(Long runId) {
    if (runId == null || runId <= 0L) {
      return CoverageResult.incomplete("快速增量缺少运行 ID");
    }
    List<CoverageCounts> rows =
        jdbcTemplate.query(
            """
            with root_tasks as (
              select task.*
                from sync_run_table_tasks task
               where task.run_id = ?
                 and task.task_stage = 'SCAN'
                 and task.parent_task_id is null
                 and coalesce(task.lookup_scope_json, '') = ''
            ), latest_tasks as (
              select distinct on (task.source_table)
                     task.source_table,
                     task.status,
                     task.cursor_updated_at,
                     task.cursor_pk
                from sync_run_table_tasks task
               where task.run_id = ?
                 and task.task_stage = 'SCAN'
                 and coalesce(task.lookup_scope_json, '') = ''
               order by task.source_table, task.page_number desc nulls last, task.id desc
            )
            select
              count(*) as root_count,
              count(*) filter (where root.source_table = 'issues') as issues_count,
              count(*) filter (where root.source_table = 'resource_label_events') as label_event_count,
              count(*) filter (
                where root.row_strategy not in ('INCREMENTAL', 'MONOTONIC_PRIMARY_KEY')
              ) as invalid_strategy_count,
              count(*) filter (
                where root.row_strategy = 'INCREMENTAL'
                  and root.scan_upper_bound_at is null
              ) as missing_time_upper_count,
              count(*) filter (
                where root.row_strategy = 'MONOTONIC_PRIMARY_KEY'
                  and nullif(root.scan_upper_bound_pk, '') is null
              ) as missing_pk_upper_count,
              count(*) filter (
                where latest.status <> 'SUCCESS'
                   or (root.row_strategy = 'INCREMENTAL'
                       and latest.cursor_updated_at is distinct from root.scan_upper_bound_at)
                   or (root.row_strategy = 'MONOTONIC_PRIMARY_KEY'
                       and latest.cursor_pk is distinct from root.scan_upper_bound_pk)
              ) as uncovered_checkpoint_count,
              (select count(*)
                 from sync_run_table_tasks task
                where task.run_id = ?
                  and task.task_stage = 'RECONCILE') as reconcile_count,
              (select count(*)
                 from sync_run_authoritative_scopes scope
                where scope.run_id = ?
                  and scope.status <> 'SUCCESS') as incomplete_scope_count
              from root_tasks root
              left join latest_tasks latest on latest.source_table = root.source_table
            """,
            (rs, rowNum) ->
                new CoverageCounts(
                    rs.getInt("root_count"),
                    rs.getInt("issues_count"),
                    rs.getInt("label_event_count"),
                    rs.getInt("invalid_strategy_count"),
                    rs.getInt("missing_time_upper_count"),
                    rs.getInt("missing_pk_upper_count"),
                    rs.getInt("uncovered_checkpoint_count"),
                    rs.getInt("reconcile_count"),
                    rs.getInt("incomplete_scope_count")),
            runId,
            runId,
            runId,
            runId);
    if (rows.isEmpty()) {
      return CoverageResult.incomplete("快速增量覆盖校验没有返回结果");
    }
    CoverageCounts counts = rows.getFirst();
    if (counts.rootCount() == 0) {
      return CoverageResult.incomplete("快速增量未规划任何快速增量表");
    }
    if (counts.issuesCount() != 1 || counts.labelEventCount() != 1) {
      return CoverageResult.incomplete("快速增量缺少 issues 或 resource_label_events 必需表");
    }
    if (counts.invalidStrategyCount() > 0 || counts.reconcileCount() > 0) {
      return CoverageResult.incomplete("快速增量混入了非快速扫描或全表删除对账");
    }
    if (counts.missingTimeUpperCount() > 0 || counts.missingPkUpperCount() > 0) {
      return CoverageResult.incomplete("快速增量存在未固化的来源扫描上界");
    }
    if (counts.uncoveredCheckpointCount() > 0 || counts.incompleteScopeCount() > 0) {
      return CoverageResult.incomplete("快速增量 checkpoint 或权威范围尚未覆盖来源上界");
    }
    return CoverageResult.fresh();
  }

  public record CoverageResult(boolean complete, String message) {
    public static CoverageResult fresh() {
      return new CoverageResult(true, "快速增量数据已追平");
    }

    public static CoverageResult incomplete(String message) {
      return new CoverageResult(false, message);
    }
  }

  private record CoverageCounts(
      int rootCount,
      int issuesCount,
      int labelEventCount,
      int invalidStrategyCount,
      int missingTimeUpperCount,
      int missingPkUpperCount,
      int uncoveredCheckpointCount,
      int reconcileCount,
      int incompleteScopeCount) {}
}
