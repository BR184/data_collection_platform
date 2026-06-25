-- 性能优化索引迁移。
-- Flyway 默认在事务中执行迁移，PostgreSQL 不允许在事务块内 CREATE INDEX CONCURRENTLY。
-- 这里仅为平台稳定表建立索引；动态 GitLab 镜像表索引由镜像 schema 管理链路在表存在后维护。

-- 优化系统测试统计查询（问题12：缺陷原因分析、议题阶段统计等）
CREATE INDEX IF NOT EXISTS idx_issue_fact_testing_phase_scope
ON issue_fact(testing_phase)
WHERE deleted = false;

CREATE INDEX IF NOT EXISTS idx_issue_fact_reason_category_scope
ON issue_fact(reason_category)
WHERE deleted = false;

-- 复合索引优化常见的组合查询
CREATE INDEX IF NOT EXISTS idx_issue_fact_composite_stats
ON issue_fact(deleted, testing_phase, severity_level, is_excluded);

-- 优化按模块名查询，匹配现有 lower(',' || replace(module_names, ', ', ',') || ',') like ? 口径。
CREATE INDEX IF NOT EXISTS idx_issue_fact_module_text_trgm
ON issue_fact USING gin (lower(',' || replace(coalesce(module_names, ''), ', ', ',') || ',') gin_trgm_ops)
WHERE deleted = false;

CREATE INDEX IF NOT EXISTS idx_issue_fact_label_text_trgm
ON issue_fact USING gin (lower(coalesce(label_names, '')) gin_trgm_ops)
WHERE deleted = false;

CREATE INDEX IF NOT EXISTS idx_issue_fact_title_search_trgm
ON issue_fact USING gin (title_search_compact gin_trgm_ops)
WHERE deleted = false;

CREATE INDEX IF NOT EXISTS idx_issue_fact_search_trgm
ON issue_fact USING gin (search_compact gin_trgm_ops)
WHERE deleted = false;

CREATE INDEX IF NOT EXISTS idx_issue_fact_phase_filter
ON issue_fact(phase_filter_value)
WHERE deleted = false;

CREATE INDEX IF NOT EXISTS idx_issue_fact_project_updated
ON issue_fact(project_id, updated_at_source DESC)
WHERE deleted = false;

CREATE INDEX IF NOT EXISTS idx_issue_fact_project_created
ON issue_fact(project_id, created_at_source DESC)
WHERE deleted = false;

CREATE INDEX IF NOT EXISTS idx_issue_fact_source_identity
ON issue_fact(source_instance, project_id, issue_iid)
WHERE deleted = false;

CREATE INDEX IF NOT EXISTS idx_sync_run_table_tasks_active_lookup
ON sync_run_table_tasks(config_id, source_table, status, run_id)
WHERE status in ('QUEUED', 'RUNNING', 'RETRYING');

CREATE INDEX IF NOT EXISTS idx_sync_runs_active_status
ON sync_runs(status, id)
WHERE status in ('SUBMITTED', 'QUEUED', 'RUNNING', 'RETRYING', 'CANCELLING');

CREATE INDEX IF NOT EXISTS idx_sys_table_registry_syncing_lookup
ON sys_table_registry(config_id, source_table_name)
WHERE sync_status = 'SYNCING';

-- 为事实构建源查询使用的默认镜像表建立条件索引；表尚未同步创建时跳过。
DO $$
BEGIN
  IF to_regclass('ods_gitlab_label_links') IS NOT NULL THEN
    CREATE INDEX IF NOT EXISTS idx_ods_gitlab_label_links_issue_active
    ON ods_gitlab_label_links(target_type, target_id, label_id)
    WHERE mirror_deleted = false;
  END IF;

  IF to_regclass('ods_gitlab_labels') IS NOT NULL THEN
    CREATE INDEX IF NOT EXISTS idx_ods_gitlab_labels_title_active
    ON ods_gitlab_labels(title)
    WHERE mirror_deleted = false;
  END IF;

  IF to_regclass('ods_gitlab_issues') IS NOT NULL THEN
    CREATE INDEX IF NOT EXISTS idx_ods_gitlab_issues_project_updated_active
    ON ods_gitlab_issues(project_id, updated_at DESC)
    WHERE mirror_deleted = false;
  END IF;

  IF to_regclass('ods_gitlab_notes') IS NOT NULL THEN
    CREATE INDEX IF NOT EXISTS idx_ods_gitlab_notes_issue_active
    ON ods_gitlab_notes(noteable_type, noteable_id, updated_at, created_at)
    WHERE mirror_deleted = false;
  END IF;

  IF to_regclass('ods_gitlab_merge_requests') IS NOT NULL THEN
    CREATE INDEX IF NOT EXISTS idx_ods_gitlab_merge_requests_target_updated_active
    ON ods_gitlab_merge_requests(target_project_id, updated_at DESC)
    WHERE mirror_deleted = false;
  END IF;
END $$;

-- 优化事实表的常见筛选条件
CREATE INDEX IF NOT EXISTS idx_issue_fact_project_phase
ON issue_fact(project_id, testing_phase, deleted, is_excluded)
WHERE deleted = false;

CREATE INDEX IF NOT EXISTS idx_issue_fact_severity_fixed
ON issue_fact(severity_level, is_fixed)
WHERE deleted = false AND is_excluded = false;
