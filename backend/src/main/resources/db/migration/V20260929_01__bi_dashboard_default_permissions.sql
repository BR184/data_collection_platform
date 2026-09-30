-- 修正 BI 页面码的默认授权口径：默认 = 全员可见（平台托管的五个角色）。
--
-- V20260928_01 的 defaults 是按旧模块码「当前持有角色」复制的。若某环境在升级前已把 BI 权限从部分角色撤销
-- （上线前为避免使用未完成版本的临时隐藏，例如开发库的 NORMAL_USER 就没有 `bi.dashboard.view`），
-- 该临时隐藏会被写进默认口径，使「恢复默认权限」无法回到全员可见——与「默认全员可见、隐藏只是临时手段」的
-- 既定口径冲突。
--
-- 本迁移只归位 defaults，不动角色授权：升级前后逐角色可见性完全不变（隐藏仍然生效），
-- 而「恢复默认权限」按平台既有语义回到全员可见。
insert into platform_default_role_permissions(role_code, permission_code)
select role.role_code, page.permission_code
from (values
    ('SUPER_ADMIN'),
    ('ADMIN'),
    ('DIRECT_MANAGER'),
    ('TREE_MANAGER'),
    ('NORMAL_USER')
) role(role_code)
cross join (values
    ('bi.dashboard.requirements.view'),
    ('bi.dashboard.design.view'),
    ('bi.dashboard.coding.view'),
    ('bi.dashboard.unit_test.view'),
    ('bi.dashboard.integration_test.view'),
    ('bi.dashboard.system_test.view'),
    ('bi.dashboard.customer_issues.view'),
    ('bi.dashboard.requirements.download'),
    ('bi.dashboard.design.download'),
    ('bi.dashboard.coding.download'),
    ('bi.dashboard.unit_test.download'),
    ('bi.dashboard.integration_test.download'),
    ('bi.dashboard.system_test.download'),
    ('bi.dashboard.customer_issues.download')
) page(permission_code)
on conflict do nothing;
