-- 下拉框选项设置：配置实体与字段绑定分离。
-- 配置可被多个字段共用（改一处多字段生效），拆分=新建配置并改绑；无绑定行即直通现状。
create table dropdown_option_configs (
    id                  bigserial primary key,
    rules_json          jsonb not null default '{"acquiredRules":[],"manualRules":[]}'::jsonb,
    manual_options_json jsonb not null default '[]'::jsonb,
    version             bigint not null default 0,
    updated_by          varchar(64),
    created_at          timestamptz not null default current_timestamp,
    updated_at          timestamptz not null default current_timestamp
);

create table dropdown_option_field_bindings (
    field_key   varchar(128) primary key,
    config_id   bigint not null references dropdown_option_configs(id),
    bound_at    timestamptz not null default current_timestamp,
    bound_by    varchar(64)
);

create index idx_dropdown_option_field_bindings_config on dropdown_option_field_bindings(config_id);

-- 读取与维护均为管理员能力，避免普通用户探测配置面。
insert into platform_permissions(permission_code, permission_name, module_name, description, sort_order)
values
    ('system.dropdown_option.view', '查看下拉框选项设置', '系统设置', '查看各下拉字段选项配置与最终预览', 8210),
    ('system.dropdown_option.manage', '维护下拉框选项设置', '系统设置', '配置黑白名单规则、手动选项与字段绑定', 8220)
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
    ('system.dropdown_option.view'),
    ('system.dropdown_option.manage')
) permissions(permission_code)
on conflict do nothing;

insert into platform_default_role_permissions(role_code, permission_code)
select role_code, permission_code
from (values
    ('SUPER_ADMIN'),
    ('ADMIN')
) roles(role_code)
cross join (values
    ('system.dropdown_option.view'),
    ('system.dropdown_option.manage')
) permissions(permission_code)
on conflict do nothing;
