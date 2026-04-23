begin;

-- Local demo fixtures for the two implemented statistic pages:
-- 1. 系统测试缺陷汇总
-- 2. 代码走查非法记录
--
-- Design goals:
-- - Use only formal ODS / fact-source tables already used by the project
-- - Keep the script idempotent
-- - Seed both valid and invalid business cases

insert into ods_gitlab_users (
    id,
    email,
    encrypted_password,
    sign_in_count,
    created_at,
    updated_at,
    name,
    admin,
    projects_limit,
    username,
    can_create_group,
    can_create_team,
    state,
    color_scheme_id,
    otp_required_for_login,
    auditor,
    require_two_factor_authentication_from_group,
    two_factor_grace_period,
    private_profile,
    onboarding_in_progress,
    color_mode_id
)
values
    (1101, 'system.qa@example.com', 'local-seed', 0, timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', '系统测试QA', false, 100, 'system_qa', true, false, 'active', 1, false, false, false, 48, false, false, 1),
    (1102, 'module.owner@example.com', 'local-seed', 0, timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', '模块负责人', false, 100, 'module_owner_demo', true, false, 'active', 1, false, false, false, 48, false, false, 1),
    (1103, 'reviewer.one@example.com', 'local-seed', 0, timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', '走查责任人A', false, 100, 'review_owner_a', true, false, 'active', 1, false, false, false, 48, false, false, 1),
    (1104, 'reviewer.two@example.com', 'local-seed', 0, timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', '走查责任人B', false, 100, 'review_owner_b', true, false, 'active', 1, false, false, false, 48, false, false, 1)
on conflict (id) do update
set email = excluded.email,
    encrypted_password = excluded.encrypted_password,
    updated_at = excluded.updated_at,
    name = excluded.name,
    username = excluded.username,
    state = excluded.state,
    mirror_updated_at = current_timestamp;

insert into ods_gitlab_labels (
    id,
    title,
    color,
    project_id,
    created_at,
    updated_at,
    description,
    lock_on_merge
)
values
    (2001, 'CC2026R1第一轮系统测试', '#3949AB', 1, timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', '系统测试范围标签', false),
    (2002, 'CC2026R1回归测试', '#5E35B1', 1, timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', '回归测试范围标签', false),
    (2003, '一级缺陷', '#E53935', 1, timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', '严重程度一级', false),
    (2004, '二级缺陷', '#FB8C00', 1, timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', '严重程度二级', false),
    (2005, '三级缺陷', '#FDD835', 1, timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', '严重程度三级', false),
    (2006, 'P1', '#D81B60', 1, timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', '优先级P1', false),
    (2007, 'P2', '#8E24AA', 1, timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', '优先级P2', false),
    (2008, 'P3', '#1E88E5', 1, timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', '优先级P3', false),
    (2009, '工程图', '#00897B', 1, timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', '模块标签-工程图', false),
    (2010, '草图', '#43A047', 1, timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', '模块标签-草图', false),
    (2011, '已修复', '#2E7D32', 1, timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', '修复标签', false),
    (2012, '待合并', '#00ACC1', 1, timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', '待合并标签', false),
    (2013, '申请延期', '#6D4C41', 1, timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', '申请延期标签', false),
    (2014, '功能屏蔽', '#546E7A', 1, timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', '排除标签-功能屏蔽', false),
    (2015, '建议', '#9E9D24', 1, timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', '建议类标签', false),
    (2016, '需求如此', '#8D6E63', 1, timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', '排除标签-需求如此', false),
    (2017, '已拒绝', '#757575', 1, timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', '排除标签-已拒绝', false),
    (2018, '申请否决', '#6A1B9A', 1, timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', '排除标签-申请否决', false),
    (2019, '未复现', '#039BE5', 1, timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', '未复现标签', false),
    (2020, '已修复/完成', '#2E7D32', 1, timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', '已修复完成标签', false),
    (2021, '新增理解偏差数量', '#EF6C00', 1, timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', '缺陷原因-需求理解偏差', false),
    (2022, '编译/打包/部署问题', '#7CB342', 1, timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', '缺陷原因-环境部署问题', false),
    (2023, '数据异常', '#455A64', 1, timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', '数据异常标签', false),
    (2024, '响应已延期', '#C62828', 1, timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', '响应延期标签', false),
    (2025, '支付中心', '#1E88E5', 1, timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', '代码走查模块-支付中心', false),
    (2026, '订单服务', '#43A047', 1, timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', '代码走查模块-订单服务', false),
    (2027, '报表平台', '#FB8C00', 1, timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', '代码走查模块-报表平台', false)
on conflict (id) do update
set title = excluded.title,
    color = excluded.color,
    project_id = excluded.project_id,
    updated_at = excluded.updated_at,
    description = excluded.description,
    mirror_updated_at = current_timestamp;

insert into testing_phase_calendar (
    project_id,
    testing_phase,
    phase_start_at,
    phase_end_at,
    enabled,
    remark
)
values
    (1, 'CC2026R1第一轮系统测试', timestamp '2026-04-05 00:00:00', null, true, '本地系统测试样例阶段'),
    (1, 'CC2026R1回归测试', timestamp '2026-04-08 00:00:00', null, true, '本地回归测试样例阶段')
on conflict (project_id, testing_phase) do update
set phase_start_at = excluded.phase_start_at,
    phase_end_at = excluded.phase_end_at,
    enabled = excluded.enabled,
    remark = excluded.remark,
    updated_at = current_timestamp;

insert into ods_gitlab_issues (
    id,
    title,
    author_id,
    project_id,
    created_at,
    updated_at,
    description,
    iid,
    confidential,
    state_id,
    blocking_issues_count,
    upvotes_count,
    source_updated_at,
    closed_at
)
values
    (9001, '支付中心回退后订单状态异常', 1101, 1, timestamp '2026-04-06 09:00:00', timestamp '2026-04-09 10:00:00', '系统测试合法样例-一级回退', 801, false, 2, 0, 0, timestamp '2026-04-09 10:00:00', timestamptz '2026-04-09 10:00:00+08'),
    (9002, '草图导出时出现挂机现象', 1101, 1, timestamp '2026-04-06 10:00:00', timestamp '2026-04-09 10:05:00', '系统测试合法样例-一级挂机', 802, false, 2, 0, 0, timestamp '2026-04-09 10:05:00', timestamptz '2026-04-09 10:05:00+08'),
    (9003, '工程图尺寸标注偏移', 1102, 1, timestamp '2026-04-07 09:30:00', timestamp '2026-04-09 10:10:00', '系统测试合法样例-二级待合并', 803, false, 1, 0, 0, timestamp '2026-04-09 10:10:00', null),
    (9004, '草图角度显示错误', 1102, 1, timestamp '2026-04-08 11:00:00', timestamp '2026-04-09 10:15:00', '系统测试合法样例-三级关闭', 804, false, 2, 0, 0, timestamp '2026-04-09 10:15:00', timestamptz '2026-04-09 10:15:00+08'),
    (9005, '工程图建议优化提示文案', 1101, 1, timestamp '2026-04-08 13:00:00', timestamp '2026-04-09 10:20:00', '系统测试排除样例-建议类', 805, false, 1, 0, 0, timestamp '2026-04-09 10:20:00', null),
    (9006, '草图尺寸误差待否决', 1101, 1, timestamp '2026-04-07 15:00:00', timestamp '2026-04-09 10:25:00', '系统测试排除样例-申请否决并关闭', 806, false, 2, 0, 0, timestamp '2026-04-09 10:25:00', timestamptz '2026-04-09 10:25:00+08'),
    (9007, '工程图保存后提示异常但未标严重程度', 1102, 1, timestamp '2026-04-08 16:00:00', timestamp '2026-04-09 10:30:00', '系统测试非法样例-缺失严重程度', 807, false, 1, 0, 0, timestamp '2026-04-09 10:30:00', null),
    (9008, '工程图批注显示异常且流程未完成', 1102, 1, timestamp '2026-04-08 18:00:00', timestamp '2026-04-09 10:35:00', '系统测试非法样例-已修复完成但无模板回复', 808, false, 1, 0, 0, timestamp '2026-04-09 10:35:00', null),
    (9009, '草图约束未生效需要持续跟踪', 1101, 1, timestamp '2026-04-01 09:00:00', timestamp '2026-04-09 10:40:00', '系统测试合法样例-历史遗留和延期', 809, false, 1, 0, 0, timestamp '2026-04-09 10:40:00', null)
on conflict (id) do update
set title = excluded.title,
    author_id = excluded.author_id,
    project_id = excluded.project_id,
    created_at = excluded.created_at,
    updated_at = excluded.updated_at,
    description = excluded.description,
    iid = excluded.iid,
    confidential = excluded.confidential,
    state_id = excluded.state_id,
    blocking_issues_count = excluded.blocking_issues_count,
    upvotes_count = excluded.upvotes_count,
    source_updated_at = excluded.source_updated_at,
    closed_at = excluded.closed_at,
    mirror_updated_at = current_timestamp;

insert into ods_gitlab_notes (
    id,
    note,
    noteable_type,
    author_id,
    created_at,
    updated_at,
    project_id,
    noteable_id,
    system,
    internal,
    source_updated_at
)
values
    (90001, E'# 问题调研情况说明\n预计解决时间：5天\n新增理解偏差数量：1\n已完成定位并提交修复。', 'Issue', 1101, timestamp '2026-04-09 09:30:00', timestamp '2026-04-09 09:30:00', 1, 9001, false, false, timestamp '2026-04-09 09:30:00'),
    (90002, E'该问题已在系统测试中复现，关闭后待进一步观察。', 'Issue', 1101, timestamp '2026-04-09 09:35:00', timestamp '2026-04-09 09:35:00', 1, 9002, false, false, timestamp '2026-04-09 09:35:00'),
    (90003, E'# 问题调研情况说明\n预计解决时间：3天\n编译/打包/部署问题：1\n待合并后回归验证。', 'Issue', 1102, timestamp '2026-04-09 09:40:00', timestamp '2026-04-09 09:40:00', 1, 9003, false, false, timestamp '2026-04-09 09:40:00'),
    (90004, E'三级缺陷样例，无需特殊回复。', 'Issue', 1102, timestamp '2026-04-09 09:45:00', timestamp '2026-04-09 09:45:00', 1, 9004, false, false, timestamp '2026-04-09 09:45:00'),
    (90005, E'建议类样例。', 'Issue', 1101, timestamp '2026-04-09 09:50:00', timestamp '2026-04-09 09:50:00', 1, 9005, false, false, timestamp '2026-04-09 09:50:00'),
    (90006, E'申请否决并关闭的样例。', 'Issue', 1101, timestamp '2026-04-09 09:55:00', timestamp '2026-04-09 09:55:00', 1, 9006, false, false, timestamp '2026-04-09 09:55:00'),
    (90007, E'# 问题调研情况说明\n未设定严重程度的非法样例。', 'Issue', 1102, timestamp '2026-04-09 10:00:00', timestamp '2026-04-09 10:00:00', 1, 9007, false, false, timestamp '2026-04-09 10:00:00'),
    (90008, E'仅标记已修复完成，但未按模板回复。', 'Issue', 1102, timestamp '2026-04-09 10:05:00', timestamp '2026-04-09 10:05:00', 1, 9008, false, false, timestamp '2026-04-09 10:05:00'),
    (90009, E'# 问题调研情况说明\n预计解决时间：7天\n申请延期说明：算法问题\n仍需继续跟踪。', 'Issue', 1101, timestamp '2026-04-09 10:10:00', timestamp '2026-04-09 10:10:00', 1, 9009, false, false, timestamp '2026-04-09 10:10:00')
on conflict (id) do update
set note = excluded.note,
    noteable_type = excluded.noteable_type,
    author_id = excluded.author_id,
    created_at = excluded.created_at,
    updated_at = excluded.updated_at,
    project_id = excluded.project_id,
    noteable_id = excluded.noteable_id,
    system = excluded.system,
    internal = excluded.internal,
    source_updated_at = excluded.source_updated_at,
    mirror_updated_at = current_timestamp;

insert into ods_gitlab_label_links (
    id,
    label_id,
    target_id,
    target_type,
    created_at,
    updated_at,
    source_updated_at
)
values
    (51001, 2001, 9001, 'Issue', timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00'),
    (51002, 2003, 9001, 'Issue', timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00'),
    (51003, 2006, 9001, 'Issue', timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00'),
    (51004, 2009, 9001, 'Issue', timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00'),
    (51005, 2020, 9001, 'Issue', timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00', timestamp '2026-04-09 09:00:00'),

    (51006, 2001, 9002, 'Issue', timestamp '2026-04-09 09:01:00', timestamp '2026-04-09 09:01:00', timestamp '2026-04-09 09:01:00'),
    (51007, 2003, 9002, 'Issue', timestamp '2026-04-09 09:01:00', timestamp '2026-04-09 09:01:00', timestamp '2026-04-09 09:01:00'),
    (51008, 2007, 9002, 'Issue', timestamp '2026-04-09 09:01:00', timestamp '2026-04-09 09:01:00', timestamp '2026-04-09 09:01:00'),
    (51009, 2010, 9002, 'Issue', timestamp '2026-04-09 09:01:00', timestamp '2026-04-09 09:01:00', timestamp '2026-04-09 09:01:00'),

    (51010, 2002, 9003, 'Issue', timestamp '2026-04-09 09:02:00', timestamp '2026-04-09 09:02:00', timestamp '2026-04-09 09:02:00'),
    (51011, 2004, 9003, 'Issue', timestamp '2026-04-09 09:02:00', timestamp '2026-04-09 09:02:00', timestamp '2026-04-09 09:02:00'),
    (51012, 2008, 9003, 'Issue', timestamp '2026-04-09 09:02:00', timestamp '2026-04-09 09:02:00', timestamp '2026-04-09 09:02:00'),
    (51013, 2009, 9003, 'Issue', timestamp '2026-04-09 09:02:00', timestamp '2026-04-09 09:02:00', timestamp '2026-04-09 09:02:00'),
    (51014, 2012, 9003, 'Issue', timestamp '2026-04-09 09:02:00', timestamp '2026-04-09 09:02:00', timestamp '2026-04-09 09:02:00'),

    (51015, 2002, 9004, 'Issue', timestamp '2026-04-09 09:03:00', timestamp '2026-04-09 09:03:00', timestamp '2026-04-09 09:03:00'),
    (51016, 2005, 9004, 'Issue', timestamp '2026-04-09 09:03:00', timestamp '2026-04-09 09:03:00', timestamp '2026-04-09 09:03:00'),
    (51017, 2010, 9004, 'Issue', timestamp '2026-04-09 09:03:00', timestamp '2026-04-09 09:03:00', timestamp '2026-04-09 09:03:00'),

    (51018, 2001, 9005, 'Issue', timestamp '2026-04-09 09:04:00', timestamp '2026-04-09 09:04:00', timestamp '2026-04-09 09:04:00'),
    (51019, 2015, 9005, 'Issue', timestamp '2026-04-09 09:04:00', timestamp '2026-04-09 09:04:00', timestamp '2026-04-09 09:04:00'),
    (51020, 2009, 9005, 'Issue', timestamp '2026-04-09 09:04:00', timestamp '2026-04-09 09:04:00', timestamp '2026-04-09 09:04:00'),

    (51021, 2001, 9006, 'Issue', timestamp '2026-04-09 09:05:00', timestamp '2026-04-09 09:05:00', timestamp '2026-04-09 09:05:00'),
    (51022, 2004, 9006, 'Issue', timestamp '2026-04-09 09:05:00', timestamp '2026-04-09 09:05:00', timestamp '2026-04-09 09:05:00'),
    (51023, 2010, 9006, 'Issue', timestamp '2026-04-09 09:05:00', timestamp '2026-04-09 09:05:00', timestamp '2026-04-09 09:05:00'),
    (51024, 2018, 9006, 'Issue', timestamp '2026-04-09 09:05:00', timestamp '2026-04-09 09:05:00', timestamp '2026-04-09 09:05:00'),

    (51025, 2001, 9007, 'Issue', timestamp '2026-04-09 09:06:00', timestamp '2026-04-09 09:06:00', timestamp '2026-04-09 09:06:00'),
    (51026, 2009, 9007, 'Issue', timestamp '2026-04-09 09:06:00', timestamp '2026-04-09 09:06:00', timestamp '2026-04-09 09:06:00'),
    (51027, 2006, 9007, 'Issue', timestamp '2026-04-09 09:06:00', timestamp '2026-04-09 09:06:00', timestamp '2026-04-09 09:06:00'),

    (51028, 2001, 9008, 'Issue', timestamp '2026-04-09 09:07:00', timestamp '2026-04-09 09:07:00', timestamp '2026-04-09 09:07:00'),
    (51029, 2004, 9008, 'Issue', timestamp '2026-04-09 09:07:00', timestamp '2026-04-09 09:07:00', timestamp '2026-04-09 09:07:00'),
    (51030, 2010, 9008, 'Issue', timestamp '2026-04-09 09:07:00', timestamp '2026-04-09 09:07:00', timestamp '2026-04-09 09:07:00'),
    (51031, 2020, 9008, 'Issue', timestamp '2026-04-09 09:07:00', timestamp '2026-04-09 09:07:00', timestamp '2026-04-09 09:07:00'),
    (51032, 2007, 9008, 'Issue', timestamp '2026-04-09 09:07:00', timestamp '2026-04-09 09:07:00', timestamp '2026-04-09 09:07:00'),

    (51033, 2001, 9009, 'Issue', timestamp '2026-04-09 09:08:00', timestamp '2026-04-09 09:08:00', timestamp '2026-04-09 09:08:00'),
    (51034, 2004, 9009, 'Issue', timestamp '2026-04-09 09:08:00', timestamp '2026-04-09 09:08:00', timestamp '2026-04-09 09:08:00'),
    (51035, 2007, 9009, 'Issue', timestamp '2026-04-09 09:08:00', timestamp '2026-04-09 09:08:00', timestamp '2026-04-09 09:08:00'),
    (51036, 2010, 9009, 'Issue', timestamp '2026-04-09 09:08:00', timestamp '2026-04-09 09:08:00', timestamp '2026-04-09 09:08:00'),
    (51037, 2013, 9009, 'Issue', timestamp '2026-04-09 09:08:00', timestamp '2026-04-09 09:08:00', timestamp '2026-04-09 09:08:00')
on conflict (id) do update
set label_id = excluded.label_id,
    target_id = excluded.target_id,
    target_type = excluded.target_type,
    created_at = excluded.created_at,
    updated_at = excluded.updated_at,
    source_updated_at = excluded.source_updated_at,
    mirror_updated_at = current_timestamp;

insert into ods_gitlab_merge_requests (
    id,
    target_branch,
    source_branch,
    source_project_id,
    author_id,
    assignee_id,
    title,
    created_at,
    updated_at,
    merge_status,
    target_project_id,
    iid,
    description,
    merge_when_pipeline_succeeds,
    merge_user_id,
    squash,
    state_id,
    draft,
    override_requested_changes,
    retargeted,
    source_updated_at
)
values
    (10001, 'main', '支付中心缓存优化', 1, 1101, 1102, '支付中心订单查询缓存优化', timestamp '2026-04-09 11:00:00', timestamp '2026-04-09 12:00:00', 'merged', 1, 101, '代码走查合法样例-支付中心', false, 1102, false, 3, false, false, false, timestamp '2026-04-09 12:00:00'),
    (10002, 'release/1.2', '订单导出字段补齐', 1, 1102, 1103, '订单服务导出字段补齐', timestamp '2026-04-09 11:05:00', timestamp '2026-04-09 12:05:00', 'merged', 1, 102, '代码走查合法样例-订单服务', false, 1103, false, 3, false, false, false, timestamp '2026-04-09 12:05:00'),
    (10003, 'main', '报表筛选修正', 1, 1101, 1103, '报表平台筛选条件修正', timestamp '2026-04-09 11:10:00', timestamp '2026-04-09 12:10:00', 'merged', 1, 103, '代码走查合法样例-报表平台', false, 1103, false, 3, false, false, false, timestamp '2026-04-09 12:10:00'),
    (10004, 'dev', '缺失模块标签', 1, 1101, 1103, '缺失模块标签的代码走查样例', timestamp '2026-04-09 11:15:00', timestamp '2026-04-09 12:15:00', 'merged', 1, 104, '非法样例-缺失模块标签', false, 1103, false, 3, false, false, false, timestamp '2026-04-09 12:15:00'),
    (10005, 'dev', '缺失责任人', 1, 1101, null, '缺失责任人的代码走查样例', timestamp '2026-04-09 11:20:00', timestamp '2026-04-09 12:20:00', 'merged', 1, 105, '非法样例-缺失责任人', false, 1103, false, 3, false, false, false, timestamp '2026-04-09 12:20:00'),
    (10006, 'dev', '缺失外部指标', 1, 1102, 1103, '缺失外部指标的代码走查样例', timestamp '2026-04-09 11:25:00', timestamp '2026-04-09 12:25:00', 'merged', 1, 106, '非法样例-缺失注释率和缺陷数', false, 1103, false, 3, false, false, false, timestamp '2026-04-09 12:25:00'),
    (10007, 'dev', '缺失新增代码行数', 1, 1102, 1104, '缺失新增代码行数的代码走查样例', timestamp '2026-04-09 11:30:00', timestamp '2026-04-09 12:30:00', 'merged', 1, 107, '非法样例-缺失新增代码行数', false, 1104, false, 3, false, false, false, timestamp '2026-04-09 12:30:00')
on conflict (id) do update
set target_branch = excluded.target_branch,
    source_branch = excluded.source_branch,
    source_project_id = excluded.source_project_id,
    author_id = excluded.author_id,
    assignee_id = excluded.assignee_id,
    title = excluded.title,
    created_at = excluded.created_at,
    updated_at = excluded.updated_at,
    merge_status = excluded.merge_status,
    target_project_id = excluded.target_project_id,
    iid = excluded.iid,
    description = excluded.description,
    merge_when_pipeline_succeeds = excluded.merge_when_pipeline_succeeds,
    merge_user_id = excluded.merge_user_id,
    squash = excluded.squash,
    state_id = excluded.state_id,
    draft = excluded.draft,
    override_requested_changes = excluded.override_requested_changes,
    retargeted = excluded.retargeted,
    source_updated_at = excluded.source_updated_at,
    mirror_updated_at = current_timestamp;

insert into ods_gitlab_merge_request_metrics (
    merge_request_id,
    merged_at,
    created_at,
    updated_at,
    merged_by_id,
    added_lines,
    removed_lines,
    target_project_id,
    id,
    first_contribution,
    source_updated_at
)
values
    (10001, timestamp '2026-04-09 11:58:00', timestamp '2026-04-09 11:00:00', timestamp '2026-04-09 11:58:00', 1102, 128, 24, 1, 20001, false, timestamp '2026-04-09 11:58:00'),
    (10002, timestamp '2026-04-09 12:03:00', timestamp '2026-04-09 11:05:00', timestamp '2026-04-09 12:03:00', 1103, 86, 12, 1, 20002, false, timestamp '2026-04-09 12:03:00'),
    (10003, timestamp '2026-04-09 12:08:00', timestamp '2026-04-09 11:10:00', timestamp '2026-04-09 12:08:00', 1103, 42, 8, 1, 20003, false, timestamp '2026-04-09 12:08:00'),
    (10004, timestamp '2026-04-09 12:13:00', timestamp '2026-04-09 11:15:00', timestamp '2026-04-09 12:13:00', 1103, 56, 10, 1, 20004, false, timestamp '2026-04-09 12:13:00'),
    (10005, timestamp '2026-04-09 12:18:00', timestamp '2026-04-09 11:20:00', timestamp '2026-04-09 12:18:00', 1103, 64, 9, 1, 20005, false, timestamp '2026-04-09 12:18:00'),
    (10006, timestamp '2026-04-09 12:23:00', timestamp '2026-04-09 11:25:00', timestamp '2026-04-09 12:23:00', 1103, 72, 14, 1, 20006, false, timestamp '2026-04-09 12:23:00'),
    (10007, timestamp '2026-04-09 12:28:00', timestamp '2026-04-09 11:30:00', timestamp '2026-04-09 12:28:00', 1104, null, 6, 1, 20007, false, timestamp '2026-04-09 12:28:00')
on conflict (id) do update
set merge_request_id = excluded.merge_request_id,
    merged_at = excluded.merged_at,
    created_at = excluded.created_at,
    updated_at = excluded.updated_at,
    merged_by_id = excluded.merged_by_id,
    added_lines = excluded.added_lines,
    removed_lines = excluded.removed_lines,
    target_project_id = excluded.target_project_id,
    first_contribution = excluded.first_contribution,
    source_updated_at = excluded.source_updated_at,
    mirror_updated_at = current_timestamp;

insert into ods_gitlab_merge_request_reviewers (
    id,
    user_id,
    merge_request_id,
    created_at,
    state,
    source_updated_at
)
values
    (30001, 1103, 10001, timestamptz '2026-04-09 11:02:00+08', 0, timestamp '2026-04-09 11:02:00'),
    (30002, 1103, 10002, timestamptz '2026-04-09 11:07:00+08', 0, timestamp '2026-04-09 11:07:00'),
    (30003, 1102, 10003, timestamptz '2026-04-09 11:12:00+08', 0, timestamp '2026-04-09 11:12:00'),
    (30004, 1104, 10004, timestamptz '2026-04-09 11:17:00+08', 0, timestamp '2026-04-09 11:17:00'),
    (30006, 1104, 10006, timestamptz '2026-04-09 11:27:00+08', 0, timestamp '2026-04-09 11:27:00'),
    (30007, 1104, 10007, timestamptz '2026-04-09 11:32:00+08', 0, timestamp '2026-04-09 11:32:00')
on conflict (id) do update
set user_id = excluded.user_id,
    merge_request_id = excluded.merge_request_id,
    created_at = excluded.created_at,
    state = excluded.state,
    source_updated_at = excluded.source_updated_at,
    mirror_updated_at = current_timestamp;

insert into ods_gitlab_merge_request_assignees (
    id,
    user_id,
    merge_request_id,
    created_at,
    source_updated_at
)
values
    (40001, 1102, 10001, timestamptz '2026-04-09 11:03:00+08', timestamp '2026-04-09 11:03:00'),
    (40002, 1103, 10002, timestamptz '2026-04-09 11:08:00+08', timestamp '2026-04-09 11:08:00'),
    (40003, 1102, 10003, timestamptz '2026-04-09 11:13:00+08', timestamp '2026-04-09 11:13:00'),
    (40004, 1103, 10004, timestamptz '2026-04-09 11:18:00+08', timestamp '2026-04-09 11:18:00'),
    (40006, 1103, 10006, timestamptz '2026-04-09 11:28:00+08', timestamp '2026-04-09 11:28:00'),
    (40007, 1104, 10007, timestamptz '2026-04-09 11:33:00+08', timestamp '2026-04-09 11:33:00')
on conflict (id) do update
set user_id = excluded.user_id,
    merge_request_id = excluded.merge_request_id,
    created_at = excluded.created_at,
    source_updated_at = excluded.source_updated_at,
    mirror_updated_at = current_timestamp;

insert into collect_form_records (
    gitlab_base_url,
    project_id,
    request_iid,
    resource_type,
    resource_id,
    template_code,
    form_title,
    reviewer,
    review_duration_minutes,
    specification_score,
    logic_score,
    performance_score,
    design_score,
    other_score,
    remark,
    deleted
)
values
    ('http://172.22.10.233', 1, 101, 'merge_request', '101', 'code_review', '代码走查表单', '走查责任人A', 35, 90, 92, 88, 91, 89, '合法样例：支付中心', false),
    ('http://172.22.10.233', 1, 102, 'merge_request', '102', 'code_review', '代码走查表单', '走查责任人A', 28, 91, 90, 89, 88, 90, '合法样例：订单服务', false),
    ('http://172.22.10.233', 1, 103, 'merge_request', '103', 'code_review', '代码走查表单', '模块负责人', 22, 88, 87, 90, 86, 88, '合法样例：报表平台', false),
    ('http://172.22.10.233', 1, 104, 'merge_request', '104', 'code_review', '代码走查表单', '走查责任人B', 20, 85, 86, 84, 83, 82, '非法样例：缺失模块标签', false),
    ('http://172.22.10.233', 1, 106, 'merge_request', '106', 'code_review', '代码走查表单', '走查责任人B', 18, 84, 83, 82, 81, 80, '非法样例：缺失外部指标', false),
    ('http://172.22.10.233', 1, 107, 'merge_request', '107', 'code_review', '代码走查表单', '走查责任人B', 16, 83, 82, 81, 80, 79, '非法样例：缺失新增代码行数', false)
on conflict (gitlab_base_url, project_id, resource_type, resource_id, template_code) do update
set request_iid = excluded.request_iid,
    form_title = excluded.form_title,
    reviewer = excluded.reviewer,
    review_duration_minutes = excluded.review_duration_minutes,
    specification_score = excluded.specification_score,
    logic_score = excluded.logic_score,
    performance_score = excluded.performance_score,
    design_score = excluded.design_score,
    other_score = excluded.other_score,
    remark = excluded.remark,
    deleted = excluded.deleted,
    updated_at = current_timestamp;

insert into code_review_external_metrics (
    project_id,
    merge_request_id,
    merge_request_iid,
    comment_rate,
    comment_rate_source,
    defect_count,
    defect_count_source,
    source_summary,
    raw_payload
)
values
    (1, 10001, 101, 33.60, 'CC_TOOL', 0, 'MR_ROBOT', '合法样例：CC 注释率工具 + MR 机器人', '{"commentRate":"33.60","commentRateSource":"CC_TOOL","defectCount":"0","defectCountSource":"MR_ROBOT"}'),
    (1, 10002, 102, 24.50, 'DGM_ROBOT_COMMENT', 1, 'SONAR_AND_ROBOT', '合法样例：MR 机器人 + Sonar', '{"commentRate":"24.50","commentRateSource":"DGM_ROBOT_COMMENT","defectCount":"1","defectCountSource":"SONAR_AND_ROBOT"}'),
    (1, 10003, 103, 31.20, 'CC_TOOL', 0, 'MR_COMMENT', '合法样例：外部注释率 + MR 评论', '{"commentRate":"31.20","commentRateSource":"CC_TOOL","defectCount":"0","defectCountSource":"MR_COMMENT"}'),
    (1, 10004, 104, 27.80, 'CC_TOOL', 0, 'MR_ROBOT', '非法样例：模块标签缺失，但指标齐全', '{"commentRate":"27.80","commentRateSource":"CC_TOOL","defectCount":"0","defectCountSource":"MR_ROBOT"}'),
    (1, 10005, 105, 22.10, 'CC_TOOL', 2, 'MR_ROBOT', '非法样例：责任人缺失，但指标齐全', '{"commentRate":"22.10","commentRateSource":"CC_TOOL","defectCount":"2","defectCountSource":"MR_ROBOT"}'),
    (1, 10007, 107, 18.40, 'DGM_ROBOT_COMMENT', 3, 'SONAR_AND_ROBOT', '非法样例：新增代码行数缺失', '{"commentRate":"18.40","commentRateSource":"DGM_ROBOT_COMMENT","defectCount":"3","defectCountSource":"SONAR_AND_ROBOT"}')
on conflict (project_id, merge_request_iid) do update
set merge_request_id = excluded.merge_request_id,
    comment_rate = excluded.comment_rate,
    comment_rate_source = excluded.comment_rate_source,
    defect_count = excluded.defect_count,
    defect_count_source = excluded.defect_count_source,
    source_summary = excluded.source_summary,
    raw_payload = excluded.raw_payload,
    imported_at = current_timestamp,
    updated_at = current_timestamp;

insert into ods_gitlab_label_links (
    id,
    label_id,
    target_id,
    target_type,
    created_at,
    updated_at,
    source_updated_at
)
values
    (52001, 2025, 10001, 'MergeRequest', timestamp '2026-04-09 11:01:00', timestamp '2026-04-09 11:01:00', timestamp '2026-04-09 11:01:00'),
    (52002, 2026, 10002, 'MergeRequest', timestamp '2026-04-09 11:06:00', timestamp '2026-04-09 11:06:00', timestamp '2026-04-09 11:06:00'),
    (52003, 2027, 10003, 'MergeRequest', timestamp '2026-04-09 11:11:00', timestamp '2026-04-09 11:11:00', timestamp '2026-04-09 11:11:00'),
    (52005, 2025, 10005, 'MergeRequest', timestamp '2026-04-09 11:21:00', timestamp '2026-04-09 11:21:00', timestamp '2026-04-09 11:21:00'),
    (52006, 2026, 10006, 'MergeRequest', timestamp '2026-04-09 11:26:00', timestamp '2026-04-09 11:26:00', timestamp '2026-04-09 11:26:00'),
    (52007, 2027, 10007, 'MergeRequest', timestamp '2026-04-09 11:31:00', timestamp '2026-04-09 11:31:00', timestamp '2026-04-09 11:31:00')
on conflict (id) do update
set label_id = excluded.label_id,
    target_id = excluded.target_id,
    target_type = excluded.target_type,
    created_at = excluded.created_at,
    updated_at = excluded.updated_at,
    source_updated_at = excluded.source_updated_at,
    mirror_updated_at = current_timestamp;

commit;
