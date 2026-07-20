-- 展示顺序只用于权限设置页面的角色排列，不参与认证、授权或权限计算。
alter table platform_ldap_roles
    add column if not exists display_order integer not null default 1000;
