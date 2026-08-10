alter table gitlab_sync_configs
    add column if not exists incremental_rerun_requested_at timestamp;

alter table gitlab_sync_configs
    add column if not exists incremental_rerun_trigger_count integer not null default 0;

alter table gitlab_sync_configs
    drop constraint if exists chk_gitlab_sync_configs_incremental_rerun_trigger_count;

alter table gitlab_sync_configs
    add constraint chk_gitlab_sync_configs_incremental_rerun_trigger_count
    check (incremental_rerun_trigger_count >= 0);

alter table sync_run_table_tasks
    add column if not exists scan_upper_bound_pk text;

create index if not exists idx_gitlab_sync_configs_incremental_rerun
    on gitlab_sync_configs(incremental_rerun_requested_at)
    where incremental_rerun_requested_at is not null;
