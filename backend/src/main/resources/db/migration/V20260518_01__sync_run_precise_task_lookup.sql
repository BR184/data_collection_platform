alter table sync_run_table_tasks
    add column if not exists lookup_column varchar(255),
    add column if not exists lookup_value text;
