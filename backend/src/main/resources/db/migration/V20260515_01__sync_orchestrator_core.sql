create table if not exists sync_runs (
    id bigserial primary key,
    run_id varchar(64) not null unique,
    config_id bigint not null references gitlab_sync_configs(id) on delete cascade,
    source_instance varchar(128) not null,
    run_type varchar(64) not null,
    trigger_type varchar(32) not null,
    status varchar(32) not null,
    priority integer not null default 0,
    exclusive_scope varchar(255) not null,
    cancel_requested boolean not null default false,
    submitted_by varchar(128),
    request_reason text,
    payload_json text,
    thread_mode varchar(32) not null default 'FIXED',
    thread_value numeric(8, 3) not null default 2,
    planned_table_count integer not null default 0,
    completed_table_count integer not null default 0,
    scanned_rows bigint not null default 0,
    applied_rows bigint not null default 0,
    heartbeat_at timestamp,
    lease_owner varchar(128),
    lease_until timestamp,
    started_at timestamp,
    finished_at timestamp,
    error_message text,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create table if not exists sync_run_table_states (
    id bigserial primary key,
    config_id bigint not null references gitlab_sync_configs(id) on delete cascade,
    source_instance varchar(128) not null,
    source_table varchar(255) not null,
    mirror_table varchar(255) not null,
    primary_key_columns text not null,
    updated_at_column varchar(255),
    row_strategy varchar(32) not null default 'INCREMENTAL',
    sync_enabled boolean not null default true,
    dirty_flag boolean not null default false,
    dirty_reason text,
    last_success_at timestamp,
    last_full_verified_at timestamp,
    last_watermark_at timestamp,
    last_cursor_pk varchar(512),
    source_max_updated_at timestamp,
    source_row_count bigint,
    mirror_row_count bigint,
    schema_fingerprint varchar(128),
    last_error text,
    retry_count integer not null default 0,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    unique (config_id, source_instance, source_table),
    unique (config_id, source_instance, mirror_table)
);

create table if not exists sync_run_table_tasks (
    id bigserial primary key,
    run_id bigint not null references sync_runs(id) on delete cascade,
    config_id bigint not null references gitlab_sync_configs(id) on delete cascade,
    state_id bigint references sync_run_table_states(id) on delete set null,
    source_instance varchar(128) not null,
    source_table varchar(255) not null,
    mirror_table varchar(255) not null,
    task_type varchar(64) not null,
    status varchar(32) not null,
    row_strategy varchar(32) not null,
    watermark_at timestamp,
    cursor_updated_at timestamp,
    cursor_pk varchar(512),
    batch_size integer not null default 500,
    run_after timestamp not null default current_timestamp,
    lease_owner varchar(128),
    lease_until timestamp,
    heartbeat_at timestamp,
    retry_count integer not null default 0,
    max_retry_count integer not null default 3,
    last_error text,
    rows_scanned bigint not null default 0,
    rows_applied bigint not null default 0,
    started_at timestamp,
    finished_at timestamp,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create table if not exists sync_run_events (
    id bigserial primary key,
    run_id bigint references sync_runs(id) on delete cascade,
    config_id bigint references gitlab_sync_configs(id) on delete cascade,
    source_instance varchar(128),
    event_type varchar(64) not null,
    table_name varchar(255),
    message text,
    payload_json text,
    created_at timestamp not null default current_timestamp
);

create table if not exists sync_worker_leases (
    id bigserial primary key,
    worker_id varchar(128) not null unique,
    worker_type varchar(64) not null,
    hostname varchar(255),
    thread_mode varchar(32) not null default 'FIXED',
    thread_value numeric(8, 3) not null default 2,
    max_threads integer not null default 1,
    active_threads integer not null default 0,
    queue_depth integer not null default 0,
    lease_until timestamp,
    heartbeat_at timestamp,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create index if not exists idx_sync_runs_dispatch
    on sync_runs(status, priority desc, created_at);

create index if not exists idx_sync_runs_config_source_status
    on sync_runs(config_id, source_instance, status);

create index if not exists idx_sync_runs_scope_status
    on sync_runs(exclusive_scope, status);

create index if not exists idx_sync_run_table_states_dirty
    on sync_run_table_states(config_id, dirty_flag, updated_at desc);

create index if not exists idx_sync_run_table_states_table
    on sync_run_table_states(config_id, source_instance, source_table);

create index if not exists idx_sync_run_table_tasks_dispatch
    on sync_run_table_tasks(status, run_after, source_instance, created_at);

create index if not exists idx_sync_run_table_tasks_run
    on sync_run_table_tasks(run_id, status, created_at);

create index if not exists idx_sync_run_table_tasks_table
    on sync_run_table_tasks(config_id, source_instance, source_table, status, created_at desc);

create index if not exists idx_sync_run_events_run
    on sync_run_events(run_id, created_at desc);

create index if not exists idx_sync_worker_leases_heartbeat
    on sync_worker_leases(worker_type, heartbeat_at desc);
