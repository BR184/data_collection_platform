insert into platform_permissions(permission_code, permission_name, module_name, description, sort_order)
values
    ('bi.dashboard.view', '查看 BI 看板', 'BI 看板', '访问 BI 看板六个阶段页面', 8010),
    ('bi.dashboard.download', '下载 BI 图表', 'BI 看板', '下载当前授权页面完整数据图表 PNG', 8020)
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
    ('ADMIN'),
    ('DIRECT_MANAGER'),
    ('TREE_MANAGER'),
    ('NORMAL_USER')
) roles(role_code)
cross join (values
    ('bi.dashboard.view'),
    ('bi.dashboard.download')
) permissions(permission_code)
on conflict do nothing;

insert into platform_default_role_permissions(role_code, permission_code)
select role_code, permission_code
from (values
    ('SUPER_ADMIN'),
    ('ADMIN'),
    ('DIRECT_MANAGER'),
    ('TREE_MANAGER'),
    ('NORMAL_USER')
) roles(role_code)
cross join (values
    ('bi.dashboard.view'),
    ('bi.dashboard.download')
) permissions(permission_code)
on conflict do nothing;
