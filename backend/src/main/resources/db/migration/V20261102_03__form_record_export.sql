-- 表单记录按版导出：权限目录与审计表。
-- 本轮只登记权限键，不向任何角色授予；正式授予待目标库导出权清单确认后另行迁移。
insert into platform_permissions(
    permission_code, permission_name, module_name, description, sort_order)
values (
    'form.record.export',
    '导出表单记录',
    '表单采集',
    '按已发布版本导出获准范围内的有效表单记录',
    3345)
on conflict (permission_code) do update
set permission_name = excluded.permission_name,
    module_name = excluded.module_name,
    description = excluded.description,
    sort_order = excluded.sort_order,
    enabled = true,
    updated_at = current_timestamp;

create table if not exists form_record_export_logs (
    id bigserial primary key,
    scope_id bigint not null references form_template_scopes(id),
    version_id bigint not null,
    project_ids jsonb not null,
    row_count bigint not null check (row_count >= 0),
    byte_size bigint not null check (byte_size >= 0),
    file_sha256 char(64) not null,
    operator_id varchar(128) not null,
    created_at timestamp not null default current_timestamp
);

create index if not exists idx_form_record_export_logs_version
    on form_record_export_logs(version_id, created_at desc);

comment on table form_record_export_logs is
    '表单记录按版导出审计：范围、版本、项目集合、行数、字节数、文件摘要与操作人';
