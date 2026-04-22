begin;

-- Local demo fixtures for customer issue source data.
-- This script seeds only ODS source tables and does not write issue_fact directly.
-- After running it, rebuild issue facts with:
--   POST http://localhost:18080/api/facts/rebuild?scope=issue&full=true

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
    (3201, 'customer.qa@example.com', 'local-seed', 0, timestamp '2026-04-12 09:00:00', timestamp '2026-04-12 09:00:00', 'customer issue qa', false, 100, 'customer_issue_qa', true, false, 'active', 1, false, false, false, 48, false, false, 1),
    (3202, 'customer.owner@example.com', 'local-seed', 0, timestamp '2026-04-12 09:00:00', timestamp '2026-04-12 09:00:00', 'customer issue owner', false, 100, 'customer_issue_owner', true, false, 'active', 1, false, false, false, 48, false, false, 1)
on conflict (id) do update
set email = excluded.email,
    encrypted_password = excluded.encrypted_password,
    updated_at = excluded.updated_at,
    name = excluded.name,
    username = excluded.username,
    state = excluded.state,
    mirror_updated_at = current_timestamp;

insert into ods_gitlab_projects (
    id,
    name,
    path,
    created_at,
    updated_at,
    namespace_id,
    visibility_level,
    archived,
    mirror,
    star_count,
    approvals_before_merge,
    shared_runners_enabled,
    build_allow_git_fetch,
    build_timeout,
    mirror_trigger_builds,
    public_builds,
    only_allow_merge_if_pipeline_succeeds,
    repository_storage,
    request_access_enabled,
    printing_merge_request_link_enabled,
    auto_cancel_pending_pipelines,
    hidden
)
values
    (325, 'CC_Product', 'cc_product', timestamp '2026-04-12 09:00:00', timestamp '2026-04-12 09:00:00', 1, 0, false, false, 0, 0, true, true, 3600, false, true, false, 'default', true, true, 1, false)
on conflict (id) do update
set name = excluded.name,
    path = excluded.path,
    updated_at = excluded.updated_at,
    namespace_id = excluded.namespace_id,
    visibility_level = excluded.visibility_level,
    archived = excluded.archived,
    mirror = excluded.mirror,
    star_count = excluded.star_count,
    approvals_before_merge = excluded.approvals_before_merge,
    shared_runners_enabled = excluded.shared_runners_enabled,
    build_allow_git_fetch = excluded.build_allow_git_fetch,
    build_timeout = excluded.build_timeout,
    mirror_trigger_builds = excluded.mirror_trigger_builds,
    public_builds = excluded.public_builds,
    only_allow_merge_if_pipeline_succeeds = excluded.only_allow_merge_if_pipeline_succeeds,
    repository_storage = excluded.repository_storage,
    request_access_enabled = excluded.request_access_enabled,
    printing_merge_request_link_enabled = excluded.printing_merge_request_link_enabled,
    auto_cancel_pending_pipelines = excluded.auto_cancel_pending_pipelines,
    hidden = excluded.hidden,
    mirror_updated_at = current_timestamp;

insert into ods_gitlab_milestones (
    id,
    title,
    project_id,
    created_at,
    updated_at,
    state,
    iid,
    start_date,
    due_date,
    lock_version
)
values
    (32501, 'CC2026R1-M1', 325, timestamp '2026-04-12 09:00:00', timestamp '2026-04-12 09:00:00', 'active', 1, date '2026-04-01', date '2026-04-30', 0),
    (32502, 'CC2026R1-M2', 325, timestamp '2026-04-12 09:00:00', timestamp '2026-04-12 09:00:00', 'active', 2, date '2026-05-01', date '2026-05-31', 0)
on conflict (id) do update
set title = excluded.title,
    project_id = excluded.project_id,
    updated_at = excluded.updated_at,
    state = excluded.state,
    iid = excluded.iid,
    start_date = excluded.start_date,
    due_date = excluded.due_date,
    lock_version = excluded.lock_version,
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
    (325101, '一级缺陷', '#E53935', 325, timestamp '2026-04-12 09:00:00', timestamp '2026-04-12 09:00:00', 'severity-level-1', false),
    (325102, '二级缺陷', '#FB8C00', 325, timestamp '2026-04-12 09:00:00', timestamp '2026-04-12 09:00:00', 'severity-level-2', false),
    (325103, 'P1', '#D81B60', 325, timestamp '2026-04-12 09:00:00', timestamp '2026-04-12 09:00:00', 'priority-p1', false),
    (325104, 'P2', '#8E24AA', 325, timestamp '2026-04-12 09:00:00', timestamp '2026-04-12 09:00:00', 'priority-p2', false),
    (325105, '工程图', '#00897B', 325, timestamp '2026-04-12 09:00:00', timestamp '2026-04-12 09:00:00', 'module-gongchengtu', false),
    (325106, '草图', '#43A047', 325, timestamp '2026-04-12 09:00:00', timestamp '2026-04-12 09:00:00', 'module-caotu', false),
    (325107, '平台', '#1E88E5', 325, timestamp '2026-04-12 09:00:00', timestamp '2026-04-12 09:00:00', 'module-pingtai', false),
    (325108, '已修复/完成', '#2E7D32', 325, timestamp '2026-04-12 09:00:00', timestamp '2026-04-12 09:00:00', 'fixed-and-done', false),
    (325109, '申请延期', '#6D4C41', 325, timestamp '2026-04-12 09:00:00', timestamp '2026-04-12 09:00:00', 'delay-request', false),
    (325110, '响应已延期', '#C62828', 325, timestamp '2026-04-12 09:00:00', timestamp '2026-04-12 09:00:00', 'response-delayed', false),
    (325111, '数据异常', '#455A64', 325, timestamp '2026-04-12 09:00:00', timestamp '2026-04-12 09:00:00', 'data-anomaly', false),
    (325112, '未复现', '#039BE5', 325, timestamp '2026-04-12 09:00:00', timestamp '2026-04-12 09:00:00', 'not-reproduced', false)
on conflict (id) do update
set title = excluded.title,
    color = excluded.color,
    project_id = excluded.project_id,
    updated_at = excluded.updated_at,
    description = excluded.description,
    mirror_updated_at = current_timestamp;

insert into ods_gitlab_issues (
    id,
    title,
    author_id,
    project_id,
    created_at,
    updated_at,
    description,
    milestone_id,
    iid,
    confidential,
    state_id,
    blocking_issues_count,
    upvotes_count,
    source_updated_at,
    closed_at
)
values
    (92501, 'customer issue sample 1', 3201, 325, timestamp '2026-04-12 10:00:00', timestamp '2026-04-13 09:00:00', 'valid customer issue sample with template reply', 32501, 1201, false, 2, 0, 0, timestamp '2026-04-13 09:00:00', timestamptz '2026-04-13 09:00:00+08'),
    (92502, 'customer issue sample 2', 3202, 325, timestamp '2026-04-14 10:30:00', timestamp '2026-04-15 11:00:00', 'valid customer issue sample with delay label', 32501, 1202, false, 1, 0, 0, timestamp '2026-04-15 11:00:00', null),
    (92503, 'customer issue sample 3', 3201, 325, timestamp '2026-04-16 14:00:00', timestamp '2026-04-17 09:30:00', 'illegal sample without template reply', 32502, 1203, false, 1, 0, 0, timestamp '2026-04-17 09:30:00', null),
    (92504, 'customer issue sample 4', 3202, 325, timestamp '2026-04-18 09:15:00', timestamp '2026-04-18 16:00:00', 'illegal sample without module label', 32502, 1204, false, 1, 0, 0, timestamp '2026-04-18 16:00:00', null),
    (92505, 'customer issue sample 5', 3201, 325, timestamp '2026-04-19 11:00:00', timestamp '2026-04-20 10:00:00', 'valid customer issue sample with new requirement reason', 32502, 1205, false, 2, 0, 0, timestamp '2026-04-20 10:00:00', timestamptz '2026-04-20 10:00:00+08')
on conflict (id) do update
set title = excluded.title,
    author_id = excluded.author_id,
    project_id = excluded.project_id,
    created_at = excluded.created_at,
    updated_at = excluded.updated_at,
    description = excluded.description,
    milestone_id = excluded.milestone_id,
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
    (925001, E'# 问题调研情况说明\n计划解决时间：3天\n新增理解偏差数量：1\n已完成修复并回归验证。', 'Issue', 3201, timestamp '2026-04-12 14:00:00', timestamp '2026-04-12 14:00:00', 325, 92501, false, false, timestamp '2026-04-12 14:00:00'),
    (925002, E'# 问题调研情况说明\n计划解决时间：7天\n编译/打包/部署问题：1\n当前需要申请延期并持续跟踪。', 'Issue', 3202, timestamp '2026-04-14 16:00:00', timestamp '2026-04-14 16:00:00', 325, 92502, false, false, timestamp '2026-04-14 16:00:00'),
    (925003, E'已确认客户现场可稳定复现，仍待进一步分析。', 'Issue', 3201, timestamp '2026-04-16 16:00:00', timestamp '2026-04-16 16:00:00', 325, 92503, false, false, timestamp '2026-04-16 16:00:00'),
    (925004, E'# 问题调研情况说明\n计划解决时间：5天\n编译/打包/部署问题：1\n当前缺少模块标签，用于验证非法数据识别。', 'Issue', 3202, timestamp '2026-04-18 12:00:00', timestamp '2026-04-18 12:00:00', 325, 92504, false, false, timestamp '2026-04-18 12:00:00'),
    (925005, E'# 问题调研情况说明\n计划解决时间：4天\n新增需求问题：1\n需求方已确认需要补充字段映射。', 'Issue', 3201, timestamp '2026-04-19 15:00:00', timestamp '2026-04-19 15:00:00', 325, 92505, false, false, timestamp '2026-04-19 15:00:00')
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
    (625001, 325101, 92501, 'Issue', timestamp '2026-04-12 10:00:00', timestamp '2026-04-12 10:00:00', timestamp '2026-04-12 10:00:00'),
    (625002, 325103, 92501, 'Issue', timestamp '2026-04-12 10:00:00', timestamp '2026-04-12 10:00:00', timestamp '2026-04-12 10:00:00'),
    (625003, 325105, 92501, 'Issue', timestamp '2026-04-12 10:00:00', timestamp '2026-04-12 10:00:00', timestamp '2026-04-12 10:00:00'),
    (625004, 325108, 92501, 'Issue', timestamp '2026-04-12 10:00:00', timestamp '2026-04-12 10:00:00', timestamp '2026-04-12 10:00:00'),

    (625005, 325102, 92502, 'Issue', timestamp '2026-04-14 10:30:00', timestamp '2026-04-14 10:30:00', timestamp '2026-04-14 10:30:00'),
    (625006, 325104, 92502, 'Issue', timestamp '2026-04-14 10:30:00', timestamp '2026-04-14 10:30:00', timestamp '2026-04-14 10:30:00'),
    (625007, 325106, 92502, 'Issue', timestamp '2026-04-14 10:30:00', timestamp '2026-04-14 10:30:00', timestamp '2026-04-14 10:30:00'),
    (625008, 325109, 92502, 'Issue', timestamp '2026-04-14 10:30:00', timestamp '2026-04-14 10:30:00', timestamp '2026-04-14 10:30:00'),

    (625009, 325101, 92503, 'Issue', timestamp '2026-04-16 14:00:00', timestamp '2026-04-16 14:00:00', timestamp '2026-04-16 14:00:00'),
    (625010, 325103, 92503, 'Issue', timestamp '2026-04-16 14:00:00', timestamp '2026-04-16 14:00:00', timestamp '2026-04-16 14:00:00'),
    (625011, 325107, 92503, 'Issue', timestamp '2026-04-16 14:00:00', timestamp '2026-04-16 14:00:00', timestamp '2026-04-16 14:00:00'),
    (625012, 325110, 92503, 'Issue', timestamp '2026-04-16 14:00:00', timestamp '2026-04-16 14:00:00', timestamp '2026-04-16 14:00:00'),

    (625013, 325102, 92504, 'Issue', timestamp '2026-04-18 09:15:00', timestamp '2026-04-18 09:15:00', timestamp '2026-04-18 09:15:00'),
    (625014, 325104, 92504, 'Issue', timestamp '2026-04-18 09:15:00', timestamp '2026-04-18 09:15:00', timestamp '2026-04-18 09:15:00'),
    (625015, 325108, 92504, 'Issue', timestamp '2026-04-18 09:15:00', timestamp '2026-04-18 09:15:00', timestamp '2026-04-18 09:15:00'),

    (625016, 325102, 92505, 'Issue', timestamp '2026-04-19 11:00:00', timestamp '2026-04-19 11:00:00', timestamp '2026-04-19 11:00:00'),
    (625017, 325104, 92505, 'Issue', timestamp '2026-04-19 11:00:00', timestamp '2026-04-19 11:00:00', timestamp '2026-04-19 11:00:00'),
    (625018, 325107, 92505, 'Issue', timestamp '2026-04-19 11:00:00', timestamp '2026-04-19 11:00:00', timestamp '2026-04-19 11:00:00'),
    (625019, 325108, 92505, 'Issue', timestamp '2026-04-19 11:00:00', timestamp '2026-04-19 11:00:00', timestamp '2026-04-19 11:00:00')
on conflict (id) do update
set label_id = excluded.label_id,
    target_id = excluded.target_id,
    target_type = excluded.target_type,
    created_at = excluded.created_at,
    updated_at = excluded.updated_at,
    source_updated_at = excluded.source_updated_at,
    mirror_updated_at = current_timestamp;

commit;
