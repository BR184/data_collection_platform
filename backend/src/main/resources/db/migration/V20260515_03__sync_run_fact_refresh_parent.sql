alter table sync_runs
    add column if not exists parent_run_id bigint references sync_runs(id) on delete set null;

create index if not exists idx_sync_runs_parent
    on sync_runs(parent_run_id, run_type, status);
