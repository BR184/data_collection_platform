create table if not exists gitlab_sync_jobs (
    id bigserial primary key,
    run_id varchar(64) not null,
    config_id bigint not null references gitlab_sync_configs(id) on delete cascade,
    source_instance varchar(128) not null,
    job_type varchar(64) not null,
    trigger_type varchar(32) not null,
    status varchar(32) not null,
    priority integer not null default 0,
    run_after timestamp not null default current_timestamp,
    heartbeat_at timestamp,
    lease_owner varchar(128),
    lease_until timestamp,
    retry_count integer not null default 0,
    max_retry_count integer not null default 3,
    started_at timestamp,
    finished_at timestamp,
    error_code varchar(64),
    error_message text,
    payload_json text,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create table if not exists gitlab_table_sync_states (
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

create table if not exists gitlab_table_sync_tasks (
    id bigserial primary key,
    job_id bigint not null references gitlab_sync_jobs(id) on delete cascade,
    config_id bigint not null references gitlab_sync_configs(id) on delete cascade,
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

create table if not exists gitlab_hook_events (
    id bigserial primary key,
    config_id bigint references gitlab_sync_configs(id) on delete set null,
    source_instance varchar(128) not null default 'default',
    event_type varchar(128),
    project_id bigint,
    object_kind varchar(128),
    dedupe_key varchar(512),
    dirty_scope varchar(512),
    coalesced_count integer not null default 1,
    status varchar(32) not null default 'RECEIVED',
    payload text not null,
    received_at timestamp not null default current_timestamp,
    processed_at timestamp
);

create index if not exists idx_gitlab_sync_jobs_dispatch
    on gitlab_sync_jobs(status, run_after, priority desc, created_at);

create index if not exists idx_gitlab_sync_jobs_config
    on gitlab_sync_jobs(config_id, job_type, created_at desc);

create index if not exists idx_gitlab_table_sync_states_dirty
    on gitlab_table_sync_states(config_id, dirty_flag, updated_at desc);

create index if not exists idx_gitlab_table_sync_states_table
    on gitlab_table_sync_states(config_id, source_table);

create index if not exists idx_gitlab_table_sync_tasks_dispatch
    on gitlab_table_sync_tasks(status, source_instance, created_at);

create index if not exists idx_gitlab_table_sync_tasks_table
    on gitlab_table_sync_tasks(config_id, source_table, status, created_at desc);

create index if not exists idx_gitlab_hook_events_status
    on gitlab_hook_events(config_id, status, received_at desc);
