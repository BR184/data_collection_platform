-- LDAP 只提供身份与角色主数据；数据采集平台在本地维护细粒度权限映射。
-- “一级/二级/三级”不进入模型，初始化差异只体现在下面的角色权限种子数据中。
create table if not exists platform_ldap_users (
    user_id varchar(128) primary key,
    ldap_id bigint,
    real_name varchar(128),
    email varchar(255),
    intranet_email varchar(255),
    mobile varchar(64),
    employee_no varchar(64),
    dept_name varchar(255),
    dept_code varchar(128),
    job_title varchar(255),
    direct_leader_raw varchar(255),
    leader_ref varchar(128),
    account_status varchar(64),
    status integer,
    employment_status varchar(64),
    ldap_dn varchar(512),
    last_login_at timestamp,
    source_synced_at timestamp not null default current_timestamp,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create table if not exists platform_ldap_roles (
    role_code varchar(128) primary key,
    ldap_id bigint,
    role_name varchar(128) not null,
    status integer,
    built_in integer,
    remark varchar(512),
    source_synced_at timestamp not null default current_timestamp,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create table if not exists platform_ldap_user_roles (
    user_id varchar(128) not null references platform_ldap_users(user_id) on delete cascade,
    role_code varchar(128) not null,
    created_at timestamp not null default current_timestamp,
    primary key (user_id, role_code)
);

create index if not exists idx_platform_ldap_user_roles_role
    on platform_ldap_user_roles(role_code, user_id);

create table if not exists platform_permissions (
    permission_code varchar(160) primary key,
    permission_name varchar(160) not null,
    module_name varchar(128) not null,
    description varchar(512) not null default '',
    sort_order integer not null default 0,
    enabled boolean not null default true,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create table if not exists platform_role_permissions (
    role_code varchar(128) not null,
    permission_code varchar(160) not null references platform_permissions(permission_code) on delete cascade,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    primary key (role_code, permission_code)
);

create index if not exists idx_platform_role_permissions_permission
    on platform_role_permissions(permission_code, role_code);

create table if not exists platform_permission_audit_logs (
    id bigserial primary key,
    operator_user_id varchar(128) not null,
    target_role_code varchar(128) not null,
    before_permissions text not null default '',
    after_permissions text not null default '',
    created_at timestamp not null default current_timestamp
);

create index if not exists idx_platform_permission_audit_role_time
    on platform_permission_audit_logs(target_role_code, created_at desc);

create table if not exists platform_ldap_sync_state (
    state_key varchar(64) primary key,
    state_value varchar(255) not null default '',
    updated_at timestamp not null default current_timestamp
);

insert into platform_ldap_sync_state(state_key, state_value)
values ('initial_full_sync_completed', 'false')
on conflict (state_key) do nothing;

insert into platform_ldap_roles(role_code, role_name, built_in, remark)
values
    ('SUPER_ADMIN', '超级管理员', 1, 'LDAP 内置角色'),
    ('ADMIN', '管理员', 1, 'LDAP 内置角色'),
    ('DIRECT_MANAGER', '直属上级', 1, 'LDAP 内置角色'),
    ('TREE_MANAGER', '部门经理', 1, 'LDAP 内置角色'),
    ('NORMAL_USER', '普通用户', 1, 'LDAP 内置角色')
on conflict (role_code) do nothing;

alter table review_records add column if not exists created_by varchar(128);
alter table review_problem_items add column if not exists created_by varchar(128);

create index if not exists idx_review_records_created_by
    on review_records(created_by) where deleted = false;

create index if not exists idx_review_problem_items_created_by
    on review_problem_items(created_by) where deleted = false;

insert into platform_permissions(permission_code, permission_name, module_name, description, sort_order)
values
    ('quality.rd.view', '查看研发质量看板', '质量看板', '访问研发质量看板及其详情', 1010),
    ('quality.other.view', '查看其他看板', '质量看板', '访问其他质量看板及其详情', 1020),
    ('review.data.view', '查看评审数据', '评审数据', '访问评审数据管理页面及详情', 2010),
    ('code_review.illegal.view', '查看代码走查非法数据', '代码走查', '访问代码走查非法数据页面', 3010),
    ('code_review.board.view', '查看代码走查多元看板', '代码走查', '访问代码走查多元看板及详情', 3020),
    ('system_test.summary.view', '查看系统测试缺陷汇总', '系统测试', '访问系统测试缺陷汇总及详情', 4010),
    ('system_test.board.view', '查看系统测试议题多元看板', '系统测试', '访问系统测试议题多元看板及详情', 4020),
    ('system_test.delay.view', '查看申请延期缺陷分析', '系统测试', '访问申请延期缺陷分析及详情', 4030),
    ('system_test.illegal.view', '查看系统测试非法数据', '系统测试', '访问系统测试非法数据页面', 4040),
    ('system_test.cause.view', '查看系统测试缺陷原因分析', '系统测试', '访问系统测试缺陷原因分析及详情', 4050),
    ('system_test.phase.view', '查看系统测试议题阶段统计', '系统测试', '访问系统测试议题阶段统计', 4060),
    ('system_test.issue.view', '查看系统测试议题查询', '系统测试', '访问系统测试议题查询页面', 4070),
    ('customer_issue.summary.view', '查看客户问题缺陷汇总', '客户问题', '访问客户问题缺陷汇总及详情', 5010),
    ('customer_issue.illegal.view', '查看客户问题非法数据', '客户问题', '访问客户问题非法数据页面', 5020),
    ('customer_issue.cause.view', '查看客户问题缺陷原因分析', '客户问题', '访问客户问题缺陷原因分析及详情', 5030),
    ('customer_issue.record.view', '查看客户问题议题', '客户问题', '访问客户问题议题查询页面', 5040),
    ('customer_issue.delay.view', '查看客户问题延期问题', '客户问题', '访问客户问题延期问题及详情', 5050),
    ('customer_issue.efficiency.view', '查看客户问题缺陷响应效率', '客户问题', '访问客户问题缺陷响应效率及详情', 5060),
    ('customer_issue.function.view', '查看客户问题功能统计', '客户问题', '访问客户问题按功能统计及详情', 5070),
    ('system.label_group.view', '查看标签组管理', '系统设置', '访问标签组管理页面', 6010),
    ('system.testing_phase.view', '查看议题测试阶段定义', '系统设置', '访问议题测试阶段定义页面', 6020),
    ('system.permission.view', '查看权限设置', '系统设置', '访问权限设置页面', 6030),
    ('system.mirror.view', '查看数据镜像设置', '系统设置', '访问数据镜像设置页面', 6040),
    ('system.match_mode.view', '查看数据库兼容模式设置', '系统设置', '访问数据库兼容模式临时设置页面', 6050),
    ('system.database.view', '查看数据库', '系统设置', '访问数据库查看页面', 6060),
    ('quality.rd.export', '导出研发质量看板', '质量看板', '导出研发质量看板数据', 1110),
    ('quality.other.export', '导出其他看板', '质量看板', '导出其他看板数据', 1120),
    ('review.record.export', '导出评审列表', '评审数据', '导出评审记录列表', 2110),
    ('review.problem.export', '导出评审问题', '评审数据', '导出评审问题列表和详情', 2120),
    ('review.template.download', '下载评审模板', '评审数据', '下载评审数据导入模板', 2130),
    ('code_review.illegal.export', '导出代码走查非法数据', '代码走查', '导出代码走查非法数据', 3110),
    ('code_review.board.export', '导出代码走查看板', '代码走查', '导出代码走查多元看板数据', 3120),
    ('system_test.summary.export', '导出系统测试缺陷汇总', '系统测试', '导出缺陷汇总、议题数据和横向对比', 4110),
    ('system_test.board.export', '导出系统测试多元看板', '系统测试', '导出议题多元看板图表数据', 4120),
    ('system_test.delay.export', '导出申请延期缺陷分析', '系统测试', '导出申请延期缺陷分析数据', 4130),
    ('system_test.illegal.export', '导出系统测试非法数据', '系统测试', '导出系统测试非法数据', 4140),
    ('system_test.cause.export', '导出系统测试缺陷原因', '系统测试', '导出系统测试缺陷原因分析', 4150),
    ('system_test.issue.export', '导出系统测试议题', '系统测试', '导出系统测试议题查询数据', 4160),
    ('customer_issue.summary.export', '导出客户问题缺陷汇总', '客户问题', '导出缺陷汇总和议题数据', 5110),
    ('customer_issue.illegal.export', '导出客户问题非法数据', '客户问题', '导出客户问题非法数据', 5120),
    ('customer_issue.cause.export', '导出客户问题缺陷原因', '客户问题', '导出客户问题缺陷原因分析', 5130),
    ('customer_issue.record.export', '导出客户问题议题', '客户问题', '导出客户问题议题数据', 5140),
    ('customer_issue.delay.export', '导出客户问题延期问题', '客户问题', '导出客户问题延期问题', 5150),
    ('customer_issue.efficiency.export', '导出客户问题响应效率', '客户问题', '导出客户问题缺陷响应效率', 5160),
    ('customer_issue.function.export', '导出客户问题功能统计', '客户问题', '导出客户问题按功能统计', 5170),
    ('business_data.refresh', '刷新业务数据', '数据维护', '刷新统计、记录和非法数据页面的最新数据', 7010),
    ('review.record.create', '新增评审记录', '评审数据', '新增评审主记录', 2210),
    ('review.record.edit', '编辑评审记录', '评审数据', '编辑评审主记录', 2220),
    ('review.record.delete_any', '删除任意评审记录', '评审数据', '删除任意创建人的评审主记录', 2230),
    ('review.record.delete_own', '删除本人评审记录', '评审数据', '删除本人创建的评审主记录', 2240),
    ('review.problem.create', '新增评审问题', '评审数据', '新增评审问题项', 2250),
    ('review.problem.edit', '编辑评审问题', '评审数据', '编辑评审问题项', 2260),
    ('review.problem.delete_any', '删除任意评审问题', '评审数据', '删除任意创建人的评审问题项', 2270),
    ('review.problem.delete_own', '删除本人评审问题', '评审数据', '删除本人创建的评审问题项', 2280),
    ('review.legacy_import', '导入老平台评审数据', '评审数据', '预览并确认导入老平台评审 Excel', 2290),
    ('code_review.form.create', '新增代码走查表单', '代码走查', '通过代码走查表单新增记录', 3210),
    ('code_review.form.edit', '编辑代码走查表单', '代码走查', '编辑代码走查表单记录', 3220),
    ('code_review.form.delete', '删除代码走查表单', '代码走查', '删除代码走查表单记录', 3230),
    ('system.label_group.manage', '管理标签组', '系统设置', '新增、修改、删除和预览标签组', 6110),
    ('system.testing_phase.manage', '管理议题测试阶段', '系统设置', '新增、修改、启停和删除测试阶段及分组', 6120),
    ('system.permission.manage', '管理角色权限', '系统设置', '修改 LDAP 角色在数据采集平台的权限', 6130),
    ('system.mirror.config', '配置数据镜像', '系统设置', '修改数据镜像连接、白名单和 Hook 配置', 6140),
    ('system.mirror.sync', '执行数据镜像同步', '系统设置', '测试连接、同步、补偿、重试、注册 Hook 和中止任务', 6150),
    ('system.mirror.purge', '删除镜像数据', '系统设置', '删除本地镜像数据', 6160),
    ('system.fact.rebuild', '重建事实数据', '系统设置', '重建事实层并查看事实诊断', 6170),
    ('system.match_mode.config', '配置数据库兼容模式', '系统设置', '修改兼容模式数据库和项目源配置', 6180),
    ('system.match_mode.sync', '同步兼容模式数据', '系统设置', '执行兼容数据同步和选项刷新', 6190),
    ('system.match_mode.formal_import', '转入正式数据源', '系统设置', '将兼容数据转入正式数据源', 6200),
    ('system.database.refresh', '刷新数据库表', '系统设置', '刷新数据库查看中的允许表', 6210)
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
on conflict do nothing;

insert into platform_role_permissions(role_code, permission_code)
select role_code, permission_code
from (values ('DIRECT_MANAGER'), ('TREE_MANAGER')) roles(role_code)
cross join platform_permissions
where permission_code not like 'system.permission.%'
  and permission_code not like 'system.mirror.%'
  and permission_code not like 'system.match_mode.%'
  and permission_code not like 'system.database.%'
  and permission_code <> 'system.fact.rebuild'
on conflict do nothing;

insert into platform_role_permissions(role_code, permission_code)
select 'NORMAL_USER', permission_code
from platform_permissions
where permission_code not like 'system.%'
  and permission_code not in (
      'review.record.delete_any',
      'review.problem.delete_any',
      'code_review.form.delete'
  )
on conflict do nothing;
