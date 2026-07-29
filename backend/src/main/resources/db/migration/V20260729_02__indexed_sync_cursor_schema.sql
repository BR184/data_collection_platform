-- destructive-migration-reviewed: 用户于 2026-07-29 确认直接替换不可扩展的哈希分片模型。
-- destructive-migration-recovery: 发布前平台库备份可恢复旧任务字段；应用回退须使用备份或前向迁移重建字段。

alter table sync_run_table_states
    add column if not exists cursor_strategy varchar(32) not null default 'NONE';

alter table sync_run_table_states
    alter column last_cursor_pk type text;

alter table sync_run_table_states
    drop constraint if exists chk_sync_run_table_states_cursor_strategy;

alter table sync_run_table_states
    add constraint chk_sync_run_table_states_cursor_strategy
        check (cursor_strategy in ('TIMESTAMP_KEYSET', 'PRIMARY_KEY_KEYSET', 'NONE'));

alter table sync_run_table_tasks
    add column if not exists scan_upper_bound_at timestamp;

alter table sync_run_table_tasks
    add column if not exists page_number integer not null default 1;

alter table sync_run_table_tasks
    alter column cursor_pk type text;

alter table sync_run_table_tasks
    drop constraint if exists chk_sync_run_table_tasks_page_number;

alter table sync_run_table_tasks
    add constraint chk_sync_run_table_tasks_page_number check (page_number > 0);

drop index if exists idx_sync_run_table_tasks_run_table_shard;

alter table sync_run_table_tasks
    drop column if exists shard_key;

alter table sync_run_table_tasks
    drop column if exists shard_key_length;

create index if not exists idx_sync_run_table_tasks_page
    on sync_run_table_tasks(run_id, source_table, page_number);
