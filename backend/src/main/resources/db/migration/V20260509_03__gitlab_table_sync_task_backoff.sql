alter table gitlab_table_sync_tasks
    add column if not exists run_after timestamp not null default current_timestamp;

drop index if exists idx_gitlab_table_sync_tasks_dispatch;

create index if not exists idx_gitlab_table_sync_tasks_dispatch
    on gitlab_table_sync_tasks(status, run_after, source_instance, created_at);
