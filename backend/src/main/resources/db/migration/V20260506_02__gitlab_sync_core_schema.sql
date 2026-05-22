-- GitLab 同步核心迁移纳管数据入口相关表：配置、任务、日志、Webhook 和镜像记录。
-- 这些表是后续关闭 schema.sql 初始化前必须先由 Flyway 独立建出的基础骨架。
create table if not exists gitlab_sync_configs (
    id bigserial primary key,
    name varchar(128) not null default 'default',
    enabled boolean not null default true,
    auto_sync_enabled boolean not null default true,
    source_mode varchar(32) not null default 'DOCKER',
    whitelist_mode varchar(32) not null default 'RECOMMENDED',
    whitelist_tables text,
    db_host varchar(255) not null default 'localhost',
    db_port integer not null default 5432,
    db_name varchar(255) not null,
    db_username varchar(255) not null,
    db_password varchar(255) not null,
    docker_container_name varchar(255),
    webhook_secret varchar(255),
    webhook_project_id bigint,
    compensation_interval_minutes integer not null default 360,
    last_full_sync_at timestamp,
    last_incremental_sync_at timestamp,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

-- 同步日志只记录每次任务执行摘要，不承载镜像数据本身。
create table if not exists gitlab_sync_logs (
    id bigserial primary key,
    config_id bigint not null references gitlab_sync_configs(id) on delete cascade,
    sync_type varchar(32) not null,
    status varchar(32) not null,
    message text,
    whitelist_snapshot text,
    table_count integer not null default 0,
    record_count integer not null default 0,
    started_at timestamp not null default current_timestamp,
    finished_at timestamp
);

-- 同步任务表保存去重键、冷却时间、心跳和锁信息，用于避免重复调度和长任务失联。
create table if not exists gitlab_sync_tasks (
    id bigserial primary key,
    run_id varchar(64) not null,
    config_id bigint not null references gitlab_sync_configs(id) on delete cascade,
    task_type varchar(32) not null,
    trigger_type varchar(32) not null,
    source_mode varchar(32) not null,
    scope_key varchar(512) not null,
    dedupe_key varchar(512) not null,
    status varchar(32) not null,
    cancel_requested boolean not null default false,
    pending_resync boolean not null default false,
    retry_count integer not null default 0,
    cooldown_until timestamp,
    heartbeat_at timestamp,
    queued_at timestamp,
    run_after timestamp,
    started_at timestamp,
    finished_at timestamp,
    finished_reason text,
    lock_owner varchar(128),
    version integer not null default 0,
    payload_json text,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

-- Webhook 事件先落本地表，再由任务链路异步处理，避免请求入口直接承担重同步成本。
create table if not exists gitlab_webhook_events (
    id bigserial primary key,
    config_id bigint references gitlab_sync_configs(id) on delete set null,
    event_type varchar(128),
    project_id bigint,
    object_kind varchar(128),
    payload text not null,
    received_at timestamp not null default current_timestamp,
    processed boolean not null default false
);

-- 镜像记录表用 jsonb 保存外部 GitLab 行数据，是事实构建读取 ODS 数据的统一入口。
create table if not exists gitlab_mirror_records (
    id bigserial primary key,
    config_id bigint not null references gitlab_sync_configs(id) on delete cascade,
    table_name varchar(128) not null,
    record_key varchar(512) not null,
    updated_at_source timestamp,
    row_data jsonb not null,
    synced_at timestamp not null default current_timestamp,
    created_at timestamp not null default current_timestamp,
    unique (config_id, table_name, record_key)
);

-- 采集表单记录来自外部评审入口，后续事实构建会把它合并到代码走查和评审分析口径中。
create table if not exists collect_form_records (
    id bigserial primary key,
    gitlab_base_url varchar(255) not null,
    project_id bigint not null,
    request_iid bigint,
    resource_type varchar(64) not null,
    resource_id varchar(255) not null,
    template_code varchar(128) not null,
    form_title varchar(255) not null default '采集表单',
    reviewer varchar(128),
    review_duration_minutes integer not null default 0,
    specification_score integer not null default 0,
    logic_score integer not null default 0,
    performance_score integer not null default 0,
    design_score integer not null default 0,
    other_score integer not null default 0,
    remark text,
    deleted boolean not null default false,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    unique (gitlab_base_url, project_id, resource_type, resource_id, template_code)
);

-- 过渡期补列兼容旧库：旧 schema.sql 版本可能已经建表但缺少新调度字段。
alter table gitlab_sync_configs add column if not exists source_mode varchar(32) not null default 'DOCKER';
alter table gitlab_sync_configs add column if not exists docker_container_name varchar(255);
alter table gitlab_sync_configs add column if not exists webhook_secret varchar(255);
alter table gitlab_sync_configs add column if not exists webhook_project_id bigint;
alter table gitlab_sync_configs add column if not exists compensation_interval_minutes integer not null default 360;
alter table gitlab_sync_configs add column if not exists last_full_sync_at timestamp;
alter table gitlab_sync_configs add column if not exists last_incremental_sync_at timestamp;

alter table gitlab_sync_tasks add column if not exists run_id varchar(64) default '';
alter table gitlab_sync_tasks add column if not exists trigger_type varchar(32) default 'MANUAL';
alter table gitlab_sync_tasks add column if not exists source_mode varchar(32) default 'DOCKER';
alter table gitlab_sync_tasks add column if not exists scope_key varchar(512) default '';
alter table gitlab_sync_tasks add column if not exists dedupe_key varchar(512) default '';
alter table gitlab_sync_tasks add column if not exists cancel_requested boolean not null default false;
alter table gitlab_sync_tasks add column if not exists pending_resync boolean not null default false;
alter table gitlab_sync_tasks add column if not exists retry_count integer not null default 0;
alter table gitlab_sync_tasks add column if not exists cooldown_until timestamp;
alter table gitlab_sync_tasks add column if not exists heartbeat_at timestamp;
alter table gitlab_sync_tasks add column if not exists queued_at timestamp;
alter table gitlab_sync_tasks add column if not exists run_after timestamp;
alter table gitlab_sync_tasks add column if not exists started_at timestamp;
alter table gitlab_sync_tasks add column if not exists finished_at timestamp;
alter table gitlab_sync_tasks add column if not exists finished_reason text;
alter table gitlab_sync_tasks add column if not exists lock_owner varchar(128);
alter table gitlab_sync_tasks add column if not exists version integer not null default 0;
alter table gitlab_sync_tasks add column if not exists payload_json text;
alter table gitlab_sync_tasks add column if not exists created_at timestamp not null default current_timestamp;
alter table gitlab_sync_tasks add column if not exists updated_at timestamp not null default current_timestamp;

-- 同步索引覆盖任务列表、去重判断、日志查询和镜像记录按表读取。
create index if not exists idx_gitlab_mirror_records_table on gitlab_mirror_records(config_id, table_name);
create index if not exists idx_collect_form_records_context on collect_form_records(project_id, resource_type, resource_id, template_code);
create index if not exists idx_gitlab_sync_logs_config on gitlab_sync_logs(config_id, started_at desc);
create index if not exists idx_gitlab_sync_tasks_config on gitlab_sync_tasks(config_id, created_at desc);
create index if not exists idx_gitlab_sync_tasks_scope_status on gitlab_sync_tasks(scope_key, status, created_at desc);
create index if not exists idx_gitlab_sync_tasks_dedupe on gitlab_sync_tasks(dedupe_key, created_at desc);
