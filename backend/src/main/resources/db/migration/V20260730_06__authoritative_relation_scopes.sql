-- destructive-migration-reviewed: 2026-07-30，开发期内部任务契约直接切换为复合权威范围。
-- destructive-migration-recovery: 旧列不承载独立业务事实；失败时以前向迁移重建列并由发布前数据库备份恢复任务状态。

alter table sync_run_table_tasks
    add column if not exists lookup_scope_json text;

alter table sync_run_table_tasks
    drop column if exists lookup_column;

alter table sync_run_table_tasks
    drop column if exists lookup_value;
