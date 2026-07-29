alter table sync_runs
    add column if not exists resolved_worker_count integer not null default 2;

alter table sync_run_table_tasks
    add column if not exists task_stage varchar(32) not null default 'SCAN';

alter table sync_run_table_tasks
    add column if not exists parent_task_id bigint references sync_run_table_tasks(id) on delete set null;

alter table sync_runs
    drop constraint if exists chk_sync_runs_resolved_worker_count;

alter table sync_runs
    add constraint chk_sync_runs_resolved_worker_count check (resolved_worker_count > 0);

alter table sync_run_table_tasks
    drop constraint if exists chk_sync_run_table_tasks_stage;

alter table sync_run_table_tasks
    add constraint chk_sync_run_table_tasks_stage check (task_stage in ('SCAN', 'RECONCILE'));

create index if not exists idx_sync_run_table_tasks_parent
    on sync_run_table_tasks(parent_task_id)
    where parent_task_id is not null;

drop index if exists idx_sync_runs_dispatch;

create index idx_sync_runs_dispatch
    on sync_runs(status, priority desc, updated_at, created_at, id)
    where status in ('QUEUED', 'PAUSED');
