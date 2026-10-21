-- 20001 事实刷新只读取证；适用于 141807 / Flyway 20261015.03。
-- 必填 psql 参数：run_id=sync_runs.id（数字），fact_type=ISSUE|MERGE_REQUEST|INTEGRATION_TEST。
-- 不自动选择最新运行，不输出来源载荷、客户明细、令牌或连接配置。
-- 本脚本仅描述观测状态；任何计数归零均不证明全部发布校验已经通过。
\set ON_ERROR_STOP on
\pset pager off
\pset null '<NULL>'
\timing on

BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '30s';
SET LOCAL lock_timeout = '3s';

-- 必须恰好选中一个 FACT_REFRESH。不存在/类型错误/参数缺失均停止，不回退选其它运行。
SELECT id AS id, run_id AS run_key, config_id AS config_id, source_instance AS source_instance
  FROM sync_runs
 WHERE id = :'run_id'::bigint
   AND run_type = 'FACT_REFRESH'
   AND :'fact_type' IN ('ISSUE', 'MERGE_REQUEST', 'INTEGRATION_TEST')
\gset target_

\echo Q0_FIXED_CONTEXT
SELECT transaction_timestamp() AS observed_at, current_setting('transaction_read_only') AS read_only,
       :'target_id'::bigint AS run_id, :'target_run_key' AS run_key,
       :'target_source_instance' AS source_instance, :'fact_type' AS fact_type;

\echo Q1_FLYWAY
SELECT installed_rank, version, description, success, installed_on
  FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;

\echo Q2_SELECTED_RUN
SELECT id, run_id, config_id, source_instance, run_type, status, cancel_requested,
       planned_table_count, completed_table_count, scanned_rows, applied_rows,
       lease_owner, lease_until, heartbeat_at, run_after, started_at, finished_at, updated_at,
       error_message IS NOT NULL AS has_error
  FROM sync_runs WHERE id = :'target_id'::bigint;

\echo Q3_SOURCE_PUBLICATION_STATE
SELECT config_id, source_instance, fact_type, readiness_status, latest_mirror_run_id,
       ready_mirror_run_id, blocked_mirror_run_id, full_publication_requested,
       full_request_revision, updated_at, error_message IS NOT NULL AS has_error
  FROM source_fact_publication_states
 WHERE config_id = :'target_config_id'::bigint
   AND source_instance = :'target_source_instance' AND fact_type = :'fact_type';

\echo Q3_SOURCE_DEPENDENCIES
SELECT source_table, latest_mirror_run_id, readiness_status, updated_at,
       error_message IS NOT NULL AS has_error
  FROM source_fact_dependency_states
 WHERE config_id = :'target_config_id'::bigint
   AND source_instance = :'target_source_instance' AND fact_type = :'fact_type'
 ORDER BY source_table;

-- 权威集合直接来自 heads。没有审计记录也会计入，不把事实运行号当镜像运行号。
-- 此处为当前来源的待发布根；不是某候选冻结水位的完成判据。
\echo Q3_SOURCE_UNPUBLISHED_HEADS
SELECT count(*) AS source_unpublished_roots,
       max(latest_change_version) AS highest_unpublished_change_version,
       max(latest_change_version - published_version) AS largest_version_gap
  FROM fact_change_heads
 WHERE source_instance = :'target_source_instance' AND fact_type = :'fact_type'
   AND published_version < latest_change_version;

-- 同一来源/事实族的全部活动域，覆盖目标运行及会令其让位的其它生产者。
-- 两种父运行身份关联保持一致；不使用全库 LIMIT 遮挡目标候选。
\echo Q4_ACTIVE_CANDIDATES
SELECT d.id AS domain_id, d.producer_kind, d.table_key, d.current_publication_id,
       p.id AS publication_id, p.state, p.producer_run_id, p.producer_task_id,
       (p.producer_run_id = :'target_id'
        OR EXISTS (SELECT 1 FROM fact_build_tasks t
                    WHERE t.id = p.producer_task_id AND t.run_id = :'target_run_key'))
         AS belongs_to_selected_run,
       p.base_publication_id,
       p.base_publication_id IS NOT DISTINCT FROM d.current_publication_id AS base_matches_current,
       p.covered_change_version, p.captured_full_request_revision,
       p.projection_input_captured_at, p.created_at, p.updated_at,
       (SELECT count(*) FROM read_publication_root_inputs i
         WHERE i.publication_id = p.id) AS captured_roots,
       (SELECT count(*) FROM read_publication_root_inputs i
         WHERE i.publication_id = p.id AND i.built_at IS NULL) AS unbuilt_roots,
       (SELECT count(*) FROM read_publication_scope_generations g
         WHERE g.publication_id = p.id) AS registered_scopes,
       (SELECT count(*) FROM read_publication_scope_generations g
         WHERE g.publication_id = p.id AND g.prepared_at IS NULL) AS unprepared_scopes
  FROM read_publication_domains d JOIN read_publications p ON p.domain_id = d.id
 WHERE d.source_instance = :'target_source_instance' AND d.fact_type = :'fact_type'
   AND p.state IN ('BUILDING', 'PROJECTING', 'READY_TO_PUBLISH')
 ORDER BY d.id, p.id;

-- 对捕获根独立比较水位；更高的新变化不会被误认为必须由本候选完成。
\echo Q4_CAPTURED_INPUT_WATERMARKS
SELECT p.id AS publication_id, count(i.root_id) AS captured_roots,
       count(i.root_id) FILTER (WHERE i.built_at IS NULL) AS unbuilt_roots,
       count(i.root_id) FILTER (WHERE h.root_id IS NULL) AS missing_heads,
       count(i.root_id) FILTER (WHERE h.published_version < i.captured_change_version)
         AS captured_watermarks_not_yet_settled,
       count(i.root_id) FILTER (WHERE h.latest_change_version > i.captured_change_version)
         AS heads_newer_than_candidate
  FROM read_publications p JOIN read_publication_domains d ON d.id = p.domain_id
  LEFT JOIN read_publication_root_inputs i ON i.publication_id = p.id
  LEFT JOIN fact_change_heads h ON h.source_instance = i.source_instance
       AND h.fact_type = i.fact_type AND h.root_id = i.root_id
 WHERE d.source_instance = :'target_source_instance' AND d.fact_type = :'fact_type'
   AND p.state IN ('BUILDING', 'PROJECTING', 'READY_TO_PUBLISH')
 GROUP BY p.id ORDER BY p.id;

-- 只列准备元数据，不运行 verifyForPublication，不把 prepared_at 当全部产物证明。
\echo Q4_PROJECTION_PREPARATION
SELECT p.id AS publication_id, g.scope_type, g.scope_key, g.candidate_generation,
       g.prepared_at, g.prepared_definition_snapshot_id,
       g.prepared_source_seal IS NOT DISTINCT FROM p.source_seal AS source_seal_matches,
       g.prepared_definition_snapshot_id IS NOT DISTINCT FROM p.definition_snapshot_id
         AS frozen_definition_matches
  FROM read_publications p JOIN read_publication_domains d ON d.id = p.domain_id
  JOIN read_publication_scope_generations g ON g.publication_id = p.id
 WHERE d.source_instance = :'target_source_instance' AND d.fact_type = :'fact_type'
   AND p.state IN ('BUILDING', 'PROJECTING', 'READY_TO_PUBLISH')
 ORDER BY p.id, g.scope_type, g.scope_key;

\echo Q4_SELECTED_RUN_HISTORY
SELECT p.id, p.state, p.producer_run_id, p.producer_task_id, p.abort_cleanup_pending,
       p.abort_reason IS NOT NULL AS has_abort_reason, p.published_at, p.updated_at
  FROM read_publications p JOIN read_publication_domains d ON d.id = p.domain_id
 WHERE d.source_instance = :'target_source_instance' AND d.fact_type = :'fact_type'
   AND (p.producer_run_id = :'target_id'
        OR EXISTS (SELECT 1 FROM fact_build_tasks t
                    WHERE t.id = p.producer_task_id AND t.run_id = :'target_run_key'))
   AND p.state IN ('PUBLISHED', 'ABORTED', 'RETIRING', 'RETIRED')
 ORDER BY p.updated_at DESC, p.id LIMIT 20;

\echo Q7_FACT_BUILD_TASKS
SELECT id, run_id, fact_type, status, full_build, publication_id, manual_disposition,
       lock_owner, lease_until, heartbeat_at, retry_count, max_retry_count, recovery_count,
       run_after, started_at, finished_at, updated_at, error_message IS NOT NULL AS has_error,
       left(wait_reason, 300) AS wait_reason
  FROM fact_build_tasks
 WHERE run_id = :'target_run_key' AND source_instance = :'target_source_instance'
   AND fact_type = :'fact_type'
 ORDER BY id;

\echo Q7_PROJECTION_TASKS
SELECT t.id, t.status, t.scope_type, t.scope_key, t.target_generation,
       t.publication_id, t.fact_build_task_id, t.manual_disposition,
       t.lease_owner, t.lease_until, t.heartbeat_at, t.retry_count, t.max_retry_count,
       t.recovery_count, t.run_after, t.updated_at, t.error_message IS NOT NULL AS has_error
  FROM fact_projection_refresh_tasks t
 WHERE t.source_instance = :'target_source_instance' AND t.fact_type = :'fact_type'
   AND (t.fact_run_id = :'target_id'::bigint
        OR t.fact_build_task_id IN
           (SELECT id FROM fact_build_tasks WHERE run_id = :'target_run_key')
        OR t.publication_id IN
           (SELECT p.id FROM read_publications p
             WHERE p.producer_run_id = :'target_id'
                OR p.producer_task_id IN
                   (SELECT id FROM fact_build_tasks WHERE run_id = :'target_run_key')))
 ORDER BY t.id;

-- 不输出 query 文本，避免泄露请求载荷；无权限可见的字段可能为空，不能据此断定无等待。
\echo Q7_DATABASE_WAITS
SELECT pid, state, wait_event_type, wait_event, xact_start, query_start,
       pg_blocking_pids(pid) AS blocking_pids
  FROM pg_stat_activity
 WHERE datname = current_database() AND backend_type = 'client backend' AND pid <> pg_backend_pid()
 ORDER BY pid;

\echo Q8_STORAGE
SELECT relname, n_live_tup, n_dead_tup, pg_total_relation_size(relid) AS total_bytes,
       pg_relation_size(relid) AS main_bytes, pg_indexes_size(relid) AS index_bytes
  FROM pg_stat_user_tables
 WHERE relname IN ('issue_fact', 'merge_request_fact', 'integration_test_fact',
                   'read_dataset_rows', 'read_publications', 'sync_run_fact_targets',
                   'fact_change_heads', 'fact_build_tasks', 'fact_projection_refresh_tasks')
 ORDER BY pg_total_relation_size(relid) DESC, relname;
SELECT pg_database_size(current_database()) AS database_bytes;

COMMIT;
