-- 默认权限快照只用于“恢复默认设置”，不参与当前角色授权计算。
-- 后续调整默认权限时新增迁移更新此快照，不修改已执行的历史迁移。
create table if not exists platform_default_role_permissions (
    role_code varchar(128) not null,
    permission_code varchar(160) not null references platform_permissions(permission_code) on delete cascade,
    created_at timestamp not null default current_timestamp,
    primary key (role_code, permission_code)
);

insert into platform_default_role_permissions(role_code, permission_code)
select role_code, permission_code
from (values ('SUPER_ADMIN'), ('ADMIN')) roles(role_code)
cross join platform_permissions
on conflict do nothing;

insert into platform_default_role_permissions(role_code, permission_code)
select role_code, permission_code
from (values ('DIRECT_MANAGER'), ('TREE_MANAGER')) roles(role_code)
cross join platform_permissions
where permission_code not like 'system.permission.%'
  and permission_code not like 'system.mirror.%'
  and permission_code not like 'system.match_mode.%'
  and permission_code not like 'system.database.%'
  and permission_code <> 'system.fact.rebuild'
on conflict do nothing;

insert into platform_default_role_permissions(role_code, permission_code)
select 'NORMAL_USER', permission_code
from platform_permissions
where permission_code not like 'system.%'
  and permission_code not in (
      'review.record.delete_any',
      'review.problem.delete_any',
      'code_review.form.delete'
  )
on conflict do nothing;
