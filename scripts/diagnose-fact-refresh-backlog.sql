-- ============================================================================
-- 只读诊断：FACT_REFRESH 单轮耗时过长（事实目标积压）
-- ============================================================================
-- 用途：在实例上定位「每轮标记数万条事实目标 → 事实重建 17~24 分钟」的成因。
--
-- 安全性：本文件**只含 SELECT**，不含任何 DDL/DML，可直接在内网生产库执行。
-- 建议用只读账号执行，逐节运行；每节的判读结论见文件末尾「判定表」。
--
-- 背景：FACT_REFRESH 耗时 ∝ 本轮待发布目标数。正常基线为每轮 1~5 个批次、
--       0~几十行、约 53 秒；退化后为每轮 3.7 万~4.5 万目标、17~24 分钟。
--       目标由增量扫描返回的「父行」派生（SyncRunAuthoritativeScopePlanner
--       .enqueueFromParentRows），因此目标数暴涨意味着增量每轮返回了几乎全部行。
-- ============================================================================


-- ---------------------------------------------------------------------------
-- 第 1 节：积压规模与起始时刻
-- 判读：PENDING 的 created_at 最小值 ≈ 积压开始累积的时刻。正常应为空或仅近十几分钟。
-- ---------------------------------------------------------------------------
select fact_type,
       publication_status,
       count(*)                        as rows,
       count(distinct root_id)         as distinct_roots,
       count(distinct mirror_run_id)   as spanning_mirror_runs,
       min(created_at)                 as oldest,
       max(created_at)                 as newest
  from sync_run_fact_targets
 group by fact_type, publication_status
 order by fact_type, publication_status;

-- 1b. 真正会被认领的那批（跨全部历史 PENDING 且未认领）——这是每轮的重放量
select fact_type,
       count(distinct root_id) as claimable_roots,
       count(distinct mirror_run_id) as spanning_runs,
       min(created_at) as oldest
  from sync_run_fact_targets
 where publication_status = 'PENDING'
   and assigned_fact_build_task_id is null
 group by fact_type;


-- ---------------------------------------------------------------------------
-- 第 2 节：事实构建任务为何追不上（失败/超时/重试）
-- 判读：若 MERGE_REQUEST 持续 FAILED 且 error_message 指向缺表，即为积压触发点。
-- ---------------------------------------------------------------------------
select id, fact_type, status, trigger_type, full_build,
       retry_count, max_retry_count, recovery_count, affected_rows,
       left(coalesce(error_message, message, ''), 300) as reason,
       to_char(created_at, 'MM-DD HH24:MI')   as created,
       to_char(finished_at, 'MM-DD HH24:MI')  as finished
  from fact_build_tasks
 where status <> 'SUCCESS'
 order by id desc
 limit 30;

-- 2b. 按天统计失败分布（看是否某天开始持续失败）
select to_char(created_at, 'YYYY-MM-DD') as day,
       fact_type, status, count(*)
  from fact_build_tasks
 where created_at > current_date - interval '30 days'
 group by 1, 2, 3
 order by 1 desc, 2, 3;


-- ---------------------------------------------------------------------------
-- 第 3 节【核心】：每表水位是否推进
-- 判读：last_success_at 每轮刷新、但 last_watermark_at 长期不动 = 「报成功但未收敛」，
--       每轮都会重扫自水位以来的全部数据。相隔十几分钟采两次即可看出。
-- ---------------------------------------------------------------------------
select source_table,
       cursor_strategy,
       row_strategy,
       dirty_flag,
       last_watermark_at,
       last_success_at,
       last_full_verified_at,
       now() - last_watermark_at as watermark_age,
       last_error
  from sync_run_table_states
 order by last_watermark_at nulls first;


-- ---------------------------------------------------------------------------
-- 第 4 节：主键扫描降级（索引缺口）
-- 判读：cursor_strategy = PRIMARY_KEY_KEYSET 且表存在 updated_at_column，
--       说明更新时间列缺少「有效非部分 B-tree 前导索引」，退化为每轮全表主键扫描。
--       正常应为 TIMESTAMP_KEYSET。
-- ---------------------------------------------------------------------------
select source_table,
       cursor_strategy,
       updated_at_column,
       primary_key_columns
  from sync_run_table_states
 where updated_at_column is not null
 order by cursor_strategy, source_table;

-- 4b. 关键表的索引清单：确认是否存在「非部分、updated_at 为前导列」的索引
select tablename, indexname, indexdef
  from pg_indexes
 where tablename in ('ods_gitlab_merge_requests', 'ods_gitlab_merge_request_diffs',
                     'ods_gitlab_issues', 'ods_gitlab_resource_label_events')
 order by tablename, indexname;


-- ---------------------------------------------------------------------------
-- 第 5 节：MR 提交事实增强表的开关与存在性
-- 判读：whitelist_tables 同时选中 merge_request_diffs 与 merge_request_diff_commits 时
--       commitFactsEnabled 为真，事实构建会先校验这两张 ODS 表；缺表则每轮失败。
-- ---------------------------------------------------------------------------
select id, name, enabled, auto_sync_enabled, whitelist_mode,
       whitelist_tables,
       compensation_interval_minutes,
       full_compensation_enabled,
       incremental_rerun_requested_at,
       incremental_rerun_trigger_count
  from gitlab_sync_configs;

-- 5b. 相关 ODS 表是否存在（期望两行都在）
select c.relname as table_name, c.reltuples::bigint as approx_rows
  from pg_class c
  join pg_namespace n on n.oid = c.relnamespace
 where n.nspname = 'public'
   and c.relname in ('ods_gitlab_merge_request_diffs',
                     'ods_gitlab_merge_request_diff_commits',
                     'ods_gitlab_merge_requests');

-- 5c. 事实发布就绪门禁（READY/BLOCKED）：BLOCKED 会阻断对应事实族发布
select config_id, source_instance, fact_type, readiness_status,
       blocked_mirror_run_id, latest_mirror_run_id, ready_mirror_run_id,
       updated_at
  from source_fact_publication_states
 order by fact_type;


-- ---------------------------------------------------------------------------
-- 第 6 节：单轮耗时 vs 目标数对照（证明耗时与目标数成正比）
-- ---------------------------------------------------------------------------
select r.id,
       r.run_type, r.status,
       to_char(r.started_at, 'MM-DD HH24:MI') as started,
       round(extract(epoch from (r.finished_at - r.started_at))) as seconds,
       t.targets,
       r.planned_table_count as planned_tables,
       r.scanned_rows,
       r.applied_rows
  from sync_runs r
  left join (
        select mirror_run_id, count(*) as targets
          from sync_run_fact_targets
         group by mirror_run_id
       ) t on t.mirror_run_id = r.id
 where r.run_type in ('INCREMENTAL_SYNC', 'FACT_REFRESH')
 order by r.id desc
 limit 40;

-- 6b. FACT_REFRESH 单轮耗时趋势（按天平均）
select to_char(started_at, 'YYYY-MM-DD') as day,
       count(*) as runs,
       round(avg(extract(epoch from (finished_at - started_at)))) as avg_seconds,
       round(max(extract(epoch from (finished_at - started_at)))) as max_seconds
  from sync_runs
 where run_type = 'FACT_REFRESH'
   and finished_at is not null
   and started_at > current_date - interval '30 days'
 group by 1
 order by 1 desc;


-- ============================================================================
-- 判定表
-- ============================================================================
-- 第 1 节：PENDING 的 oldest 明显早于今天 → 积压已持续累积，属"只增不减"型；
--          若 PENDING 为空且耗时仍高 → 瓶颈在单批查询本身（查第 4 节与慢 SQL）。
-- 第 2 节：MERGE_REQUEST 持续 FAILED/TIMEOUT 且 reason 指向缺表 →
--          触发点确认，先补齐源表再谈其他优化。
-- 第 3 节：last_watermark_at 远早于 last_success_at → 水位不推进，每轮重扫；
--          这是"目标数暴涨"的直接上游原因。
-- 第 4 节：cursor_strategy = PRIMARY_KEY_KEYSET 而 updated_at_column 非空 →
--          索引缺口导致降级；需补「非部分、updated_at 前导」索引。
-- 第 5 节：whitelist_tables 选中两表但第 5b 节缺表 → commitFactsEnabled 打开却无源，
--          每轮构建失败；或第 5c 节 readiness_status = BLOCKED → 该事实族发布被阻断。
-- 第 6 节：seconds 与 targets 强正相关 → 佐证"耗时 ∝ 目标数"，优化须先降目标数。
-- ============================================================================
