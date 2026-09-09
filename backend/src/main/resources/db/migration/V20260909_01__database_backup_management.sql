-- 数据库备份管理：配置（单行）、运行历史与运行状态（单行抢运行权）。
-- 备份对平台库只读（pg_dump 客户端连接），本迁移只新增表与权限，不触碰既有表。
create table backup_settings (
    id                          integer primary key default 1 check (id = 1),
    enabled                     boolean not null default false,
    schedule_time               time not null default '03:00',
    retention_copies            integer not null default 14 check (retention_copies between 1 and 365),
    storage_mode                varchar(16) not null default 'LOCAL' check (storage_mode in ('LOCAL', 'REMOTE')),
    local_subdirectory          varchar(128),
    remote_host                 varchar(255),
    remote_port                 integer not null default 22,
    remote_username             varchar(128),
    remote_password_cipher      text,
    remote_directory            varchar(512),
    remote_host_key_fingerprint varchar(128),
    version                     bigint not null default 0,
    updated_by                  varchar(64),
    created_at                  timestamptz not null default current_timestamp,
    updated_at                  timestamptz not null default current_timestamp
);

create table backup_runs (
    id                bigserial primary key,
    trigger_type      varchar(16) not null check (trigger_type in ('MANUAL', 'SCHEDULE')),
    status            varchar(16) not null check (status in ('RUNNING', 'SUCCESS', 'FAILED')),
    storage_mode      varchar(16) not null,
    stage             varchar(32) not null default 'PRECHECK',
    target_path       text,
    file_name         varchar(255),
    file_bytes        bigint,
    sha256            varchar(64),
    pg_server_version varchar(32),
    flyway_version    varchar(32),
    started_at        timestamptz not null default current_timestamp,
    finished_at       timestamptz,
    duration_ms       bigint,
    error_message     text
);

create index idx_backup_runs_started_at on backup_runs (started_at desc);

create table backup_state (
    id               integer primary key default 1 check (id = 1),
    active_run_id    bigint,
    lease_expires_at timestamptz,
    updated_at       timestamptz not null default current_timestamp
);

insert into backup_state (id) values (1) on conflict (id) do nothing;

-- 读取与维护均为管理员能力，与系统设置既有页面保持一致。
insert into platform_permissions(permission_code, permission_name, module_name, description, sort_order)
values
    ('system.backup.view', '查看备份管理', '系统设置', '查看数据库备份配置、执行状态与历史记录', 8230),
    ('system.backup.manage', '维护备份管理', '系统设置', '修改备份配置、测试连接与手动触发备份', 8240)
on conflict (permission_code) do update
set permission_name = excluded.permission_name,
    module_name = excluded.module_name,
    description = excluded.description,
    sort_order = excluded.sort_order,
    enabled = true,
    updated_at = current_timestamp;

insert into platform_role_permissions(role_code, permission_code)
select role_code, permission_code
from (values
    ('SUPER_ADMIN'),
    ('ADMIN')
) roles(role_code)
cross join (values
    ('system.backup.view'),
    ('system.backup.manage')
) permissions(permission_code)
on conflict do nothing;

insert into platform_default_role_permissions(role_code, permission_code)
select role_code, permission_code
from (values
    ('SUPER_ADMIN'),
    ('ADMIN')
) roles(role_code)
cross join (values
    ('system.backup.view'),
    ('system.backup.manage')
) permissions(permission_code)
on conflict do nothing;
