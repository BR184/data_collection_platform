alter table sync_run_table_tasks
    add column if not exists shard_key varchar(32);

alter table sync_run_table_tasks
    add column if not exists shard_key_length integer;

create index if not exists idx_sync_run_table_tasks_run_table_shard
    on sync_run_table_tasks(run_id, source_table, shard_key, status, run_after, created_at);
