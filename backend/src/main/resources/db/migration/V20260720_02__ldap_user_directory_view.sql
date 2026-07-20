-- LDAP 用户只读展示视图：保持用户与角色关系规范化，同时在数据库查看中提供完整基础信息。
create or replace view platform_ldap_user_directory as
select
    u.user_id,
    u.ldap_id,
    u.real_name,
    u.email,
    u.intranet_email,
    u.mobile,
    u.employee_no,
    u.dept_name,
    u.dept_code,
    u.job_title,
    u.direct_leader_raw,
    u.leader_ref,
    u.account_status,
    u.status,
    u.employment_status,
    u.ldap_dn,
    u.last_login_at,
    u.source_synced_at,
    string_agg(distinct r.role_code, ',' order by r.role_code) as role_codes,
    string_agg(distinct r.role_name, ',' order by r.role_name) as role_names
from platform_ldap_users u
left join platform_ldap_user_roles ur on ur.user_id = u.user_id
left join platform_ldap_roles r on r.role_code = ur.role_code
group by u.user_id, u.ldap_id, u.real_name, u.email, u.intranet_email, u.mobile,
         u.employee_no, u.dept_name, u.dept_code, u.job_title, u.direct_leader_raw,
         u.leader_ref, u.account_status, u.status, u.employment_status, u.ldap_dn,
         u.last_login_at, u.source_synced_at;
