-- BI 看板权限按页面拆分：由「模块级 view/download 两个码」改为「每个页面各自持有查看与下载码」，
-- 与统计板 page view/export 成对范式一致；页面身份只存在于端点路径常量与下载请求体，无路由变量。
--
-- 授权口径：新码的持有角色 = 拆分前 `bi.dashboard.view`/`bi.dashboard.download` 的持有角色，逐角色复制；
-- `platform_role_permissions` 与 `platform_default_role_permissions` 使用同一角色集合，因此
-- ① 升级前后逐角色可见性完全一致（既没有人掉权限，也没有人凭空多权限）；
-- ② 「恢复默认权限」不会把这些新码从角色上清空，也不会把内网既有定制改回平台默认。
-- 随后删除两个旧码的授权与目录行，不做别名、转发或双码并存。

insert into platform_permissions(permission_code, permission_name, module_name, description, sort_order)
values
    ('bi.dashboard.requirements.view', '查看 BI 需求页', 'BI 看板', '访问 BI 看板需求评审页面', 8010),
    ('bi.dashboard.design.view', '查看 BI 设计页', 'BI 看板', '访问 BI 看板设计评审页面', 8011),
    ('bi.dashboard.coding.view', '查看 BI 编码页', 'BI 看板', '访问 BI 看板代码走查与研发效能页面', 8012),
    ('bi.dashboard.unit_test.view', '查看 BI 单元测试页', 'BI 看板', '访问 BI 看板 CAT 单元测试质量达成页面', 8013),
    ('bi.dashboard.integration_test.view', '查看 BI 集成测试页', 'BI 看板', '访问 BI 看板 CAT 集成测试质量达成页面', 8014),
    ('bi.dashboard.system_test.view', '查看 BI 系统测试页', 'BI 看板', '访问 BI 看板系统测试质量页面', 8015),
    ('bi.dashboard.customer_issues.view', '查看 BI 客户问题页', 'BI 看板', '访问 BI 看板客户问题缺陷与需求页面', 8016),
    ('bi.dashboard.requirements.download', '下载 BI 需求页图表', 'BI 看板', '下载需求评审页面授权图表的 PNG 与 Excel 数据表', 8020),
    ('bi.dashboard.design.download', '下载 BI 设计页图表', 'BI 看板', '下载设计评审页面授权图表的 PNG 与 Excel 数据表', 8021),
    ('bi.dashboard.coding.download', '下载 BI 编码页图表', 'BI 看板', '下载代码走查与研发效能页面授权图表的 PNG 与 Excel 数据表', 8022),
    ('bi.dashboard.unit_test.download', '下载 BI 单元测试页图表', 'BI 看板', '下载单元测试质量达成页面授权图表的 PNG 与 Excel 数据表', 8023),
    ('bi.dashboard.integration_test.download', '下载 BI 集成测试页图表', 'BI 看板', '下载集成测试质量达成页面授权图表的 PNG 与 Excel 数据表', 8024),
    ('bi.dashboard.system_test.download', '下载 BI 系统测试页图表', 'BI 看板', '下载系统测试质量页面授权图表的 PNG 与 Excel 数据表', 8025),
    ('bi.dashboard.customer_issues.download', '下载 BI 客户问题页图表', 'BI 看板', '下载客户问题页面授权图表的 PNG 与 Excel 数据表', 8026)
on conflict (permission_code) do update
set permission_name = excluded.permission_name,
    module_name = excluded.module_name,
    description = excluded.description,
    sort_order = excluded.sort_order,
    enabled = true,
    updated_at = current_timestamp;

insert into platform_role_permissions(role_code, permission_code)
select holder.role_code, page.view_code
from (select distinct role_code from platform_role_permissions where permission_code = 'bi.dashboard.view') holder
cross join (values
    ('bi.dashboard.requirements.view'),
    ('bi.dashboard.design.view'),
    ('bi.dashboard.coding.view'),
    ('bi.dashboard.unit_test.view'),
    ('bi.dashboard.integration_test.view'),
    ('bi.dashboard.system_test.view'),
    ('bi.dashboard.customer_issues.view')
) page(view_code)
on conflict do nothing;

insert into platform_role_permissions(role_code, permission_code)
select holder.role_code, page.download_code
from (select distinct role_code from platform_role_permissions where permission_code = 'bi.dashboard.download') holder
cross join (values
    ('bi.dashboard.requirements.download'),
    ('bi.dashboard.design.download'),
    ('bi.dashboard.coding.download'),
    ('bi.dashboard.unit_test.download'),
    ('bi.dashboard.integration_test.download'),
    ('bi.dashboard.system_test.download'),
    ('bi.dashboard.customer_issues.download')
) page(download_code)
on conflict do nothing;

insert into platform_default_role_permissions(role_code, permission_code)
select holder.role_code, page.view_code
from (select distinct role_code from platform_role_permissions where permission_code = 'bi.dashboard.view') holder
cross join (values
    ('bi.dashboard.requirements.view'),
    ('bi.dashboard.design.view'),
    ('bi.dashboard.coding.view'),
    ('bi.dashboard.unit_test.view'),
    ('bi.dashboard.integration_test.view'),
    ('bi.dashboard.system_test.view'),
    ('bi.dashboard.customer_issues.view')
) page(view_code)
on conflict do nothing;

insert into platform_default_role_permissions(role_code, permission_code)
select holder.role_code, page.download_code
from (select distinct role_code from platform_role_permissions where permission_code = 'bi.dashboard.download') holder
cross join (values
    ('bi.dashboard.requirements.download'),
    ('bi.dashboard.design.download'),
    ('bi.dashboard.coding.download'),
    ('bi.dashboard.unit_test.download'),
    ('bi.dashboard.integration_test.download'),
    ('bi.dashboard.system_test.download'),
    ('bi.dashboard.customer_issues.download')
) page(download_code)
on conflict do nothing;

-- 补齐客户问题统计页的默认授权口径：V20260922_02 只写了角色授权未写 defaults，
-- 而「恢复默认权限」会先清空角色授权再按 defaults 重播种，缺 defaults 会把该权限从所有角色（含管理员）清掉。
-- 沿用其现有角色授权集合，即回到该权限的实际默认口径。
insert into platform_default_role_permissions(role_code, permission_code)
select role_code, permission_code
from platform_role_permissions
where permission_code in ('customer_issue.customer.view', 'customer_issue.customer.export')
on conflict do nothing;

delete from platform_role_permissions
where permission_code in ('bi.dashboard.view', 'bi.dashboard.download');

delete from platform_default_role_permissions
where permission_code in ('bi.dashboard.view', 'bi.dashboard.download');

delete from platform_permissions
where permission_code in ('bi.dashboard.view', 'bi.dashboard.download');
