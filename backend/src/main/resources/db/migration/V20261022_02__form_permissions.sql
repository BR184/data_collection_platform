-- 统一表单权限目录、当前角色授予与恢复默认快照。
-- 旧 code_review.form.* 在 W2 删除旧写入口的同一工作单元退役，本迁移只新增。
insert into platform_permissions(
    permission_code, permission_name, module_name, description, sort_order)
values
    ('form.record.create', '新增表单记录', '表单采集', '访问表单填写页并新增本人记录', 3310),
    ('form.record.edit_own', '编辑本人表单记录', '表单采集', '编辑本人创建的表单记录', 3320),
    ('form.record.delete_own', '作废本人表单记录', '表单采集', '作废本人创建的表单记录', 3330),
    ('form.template.manage', '管理表单模板', '表单管理', '维护字段草稿、范围绑定、预览、发布与退役', 3340)
on conflict (permission_code) do update
set permission_name = excluded.permission_name,
    module_name = excluded.module_name,
    description = excluded.description,
    sort_order = excluded.sort_order,
    enabled = true,
    updated_at = current_timestamp;

insert into platform_role_permissions(role_code, permission_code)
select role_code, 'form.record.create'
from platform_role_permissions
where permission_code = 'code_review.form.create'
on conflict do nothing;

insert into platform_role_permissions(role_code, permission_code)
select role_code, 'form.record.edit_own'
from platform_role_permissions
where permission_code = 'code_review.form.edit'
on conflict do nothing;

insert into platform_role_permissions(role_code, permission_code)
select role_code, 'form.record.delete_own'
from (values ('SUPER_ADMIN'), ('ADMIN'), ('DIRECT_MANAGER'), ('TREE_MANAGER'), ('NORMAL_USER'))
    roles(role_code)
on conflict do nothing;

insert into platform_role_permissions(role_code, permission_code)
select role_code, 'form.template.manage'
from (values ('SUPER_ADMIN'), ('ADMIN')) roles(role_code)
on conflict do nothing;

insert into platform_default_role_permissions(role_code, permission_code)
select role_code, permission_code
from (values ('SUPER_ADMIN'), ('ADMIN'), ('DIRECT_MANAGER'), ('TREE_MANAGER'), ('NORMAL_USER'))
    roles(role_code)
cross join (values
    ('form.record.create'),
    ('form.record.edit_own'),
    ('form.record.delete_own')
) permissions(permission_code)
on conflict do nothing;

insert into platform_default_role_permissions(role_code, permission_code)
select role_code, 'form.template.manage'
from (values ('SUPER_ADMIN'), ('ADMIN')) roles(role_code)
on conflict do nothing;
