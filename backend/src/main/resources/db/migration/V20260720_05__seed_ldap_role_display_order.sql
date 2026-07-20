-- 展示顺序只用于权限设置页面的角色排列，不参与认证、授权或权限计算。
update platform_ldap_roles
set display_order = case role_code
    when 'SUPER_ADMIN' then 10
    when 'ADMIN' then 20
    when 'DIRECT_MANAGER' then 30
    when 'TREE_MANAGER' then 40
    when 'NORMAL_USER' then 50
    else 1000
end
where role_code in ('SUPER_ADMIN', 'ADMIN', 'DIRECT_MANAGER', 'TREE_MANAGER', 'NORMAL_USER');
