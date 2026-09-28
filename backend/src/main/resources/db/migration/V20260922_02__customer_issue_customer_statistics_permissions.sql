-- 客户问题统计（按客户维度）页面权限登记。
-- 沿 V20260720_01 的登记方式：先补权限目录，再按同一角色规则授予，不引入新的授权模型。

insert into platform_permissions(permission_code, permission_name, module_name, description, sort_order)
values
    ('customer_issue.customer.view', '查看客户问题统计', '客户问题', '访问客户问题按客户、客户×模块、客户×功能统计及详情', 5080),
    ('customer_issue.customer.export', '导出客户问题统计', '客户问题', '导出客户问题统计当前范围数据', 5180)
on conflict (permission_code) do update
set permission_name = excluded.permission_name,
    module_name = excluded.module_name,
    description = excluded.description,
    sort_order = excluded.sort_order,
    enabled = true,
    updated_at = current_timestamp;

insert into platform_role_permissions(role_code, permission_code)
select role_code, permission_code
from (values ('SUPER_ADMIN'), ('ADMIN')) roles(role_code)
cross join platform_permissions
where permission_code in ('customer_issue.customer.view', 'customer_issue.customer.export')
on conflict do nothing;

insert into platform_role_permissions(role_code, permission_code)
select role_code, permission_code
from (values ('DIRECT_MANAGER'), ('TREE_MANAGER')) roles(role_code)
cross join platform_permissions
where permission_code in ('customer_issue.customer.view', 'customer_issue.customer.export')
on conflict do nothing;

insert into platform_role_permissions(role_code, permission_code)
select 'NORMAL_USER', permission_code
from platform_permissions
where permission_code in ('customer_issue.customer.view', 'customer_issue.customer.export')
on conflict do nothing;
