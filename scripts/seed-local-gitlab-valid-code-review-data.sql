begin;

-- Local valid MR fixtures for code review / illegal-record testing.
-- Scope:
-- 1. Reuse existing project `ods_gitlab_projects.id = 1`
-- 2. Add deterministic users / labels / merge requests
-- 3. Add reviewer / assignee / metrics / collect form rows
-- 4. Add imported external metrics for comment rate / defect count
-- 5. Keep the script idempotent for repeated local execution

create table if not exists code_review_external_metrics (
    id bigserial primary key,
    project_id bigint not null,
    merge_request_id bigint,
    merge_request_iid bigint not null,
    comment_rate numeric(8, 2),
    comment_rate_source varchar(64),
    defect_count integer,
    defect_count_source varchar(64),
    source_summary varchar(255),
    raw_payload text,
    imported_at timestamp not null default current_timestamp,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    unique (project_id, merge_request_iid)
);

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
    (1001, 'qa.tester@example.com', 'local-seed', 0, timestamp '2026-04-08 09:00:00', timestamp '2026-04-08 09:00:00', 'QA测试员', false, 100, 'qa_tester', true, false, 'active', 1, false, false, false, 48, false, false, 1),
    (1002, 'module.owner@example.com', 'local-seed', 0, timestamp '2026-04-08 09:00:00', timestamp '2026-04-08 09:00:00', '模块负责人', false, 100, 'module_owner', true, false, 'active', 1, false, false, false, 48, false, false, 1),
    (1003, 'review.owner@example.com', 'local-seed', 0, timestamp '2026-04-08 09:00:00', timestamp '2026-04-08 09:00:00', '代码走查人', false, 100, 'review_owner', true, false, 'active', 1, false, false, false, 48, false, false, 1)
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
    (1001, '支付中心', '#1E88E5', 1, timestamp '2026-04-08 09:00:00', timestamp '2026-04-08 09:00:00', '本地测试合法模块标签-支付中心', false),
    (1002, '订单服务', '#43A047', 1, timestamp '2026-04-08 09:00:00', timestamp '2026-04-08 09:00:00', '本地测试合法模块标签-订单服务', false),
    (1003, '报表平台', '#FB8C00', 1, timestamp '2026-04-08 09:00:00', timestamp '2026-04-08 09:00:00', '本地测试合法模块标签-报表平台', false)
on conflict (id) do update
set title = excluded.title,
    color = excluded.color,
    project_id = excluded.project_id,
    updated_at = excluded.updated_at,
    description = excluded.description,
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
    retargeted
)
values
    (10001, 'main', '支付中心缓存优化', 1, 1001, 1002, '支付中心订单查询缓存优化', timestamp '2026-04-08 09:10:00', timestamp '2026-04-08 10:20:00', 'merged', 1, 101, '本地合法测试样例一', false, 1002, false, 3, false, false, false),
    (10002, 'release/1.2', '订单导出字段补齐', 1, 1002, 1003, '订单服务导出字段补齐', timestamp '2026-04-08 09:20:00', timestamp '2026-04-08 10:40:00', 'merged', 1, 102, '本地合法测试样例二', false, 1003, false, 3, false, false, false),
    (10003, 'main', '报表筛选修正', 1, 1001, 1003, '报表平台筛选条件修正', timestamp '2026-04-08 09:30:00', timestamp '2026-04-08 11:00:00', 'merged', 1, 103, '本地合法测试样例三', false, 1003, false, 3, false, false, false)
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
    first_contribution
)
values
    (10001, timestamp '2026-04-08 10:18:00', timestamp '2026-04-08 09:10:00', timestamp '2026-04-08 10:18:00', 1002, 128, 24, 1, 20001, false),
    (10002, timestamp '2026-04-08 10:38:00', timestamp '2026-04-08 09:20:00', timestamp '2026-04-08 10:38:00', 1003, 86, 12, 1, 20002, false),
    (10003, timestamp '2026-04-08 10:58:00', timestamp '2026-04-08 09:30:00', timestamp '2026-04-08 10:58:00', 1003, 42, 8, 1, 20003, false)
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
    mirror_updated_at = current_timestamp;

insert into ods_gitlab_merge_request_reviewers (
    id,
    user_id,
    merge_request_id,
    created_at,
    state
)
values
    (30001, 1003, 10001, timestamptz '2026-04-08 09:15:00+08', 0),
    (30002, 1003, 10002, timestamptz '2026-04-08 09:25:00+08', 0),
    (30003, 1002, 10003, timestamptz '2026-04-08 09:35:00+08', 0)
on conflict (id) do update
set user_id = excluded.user_id,
    merge_request_id = excluded.merge_request_id,
    created_at = excluded.created_at,
    state = excluded.state,
    mirror_updated_at = current_timestamp;

insert into ods_gitlab_merge_request_assignees (
    id,
    user_id,
    merge_request_id,
    created_at
)
values
    (40001, 1002, 10001, timestamptz '2026-04-08 09:16:00+08'),
    (40002, 1003, 10002, timestamptz '2026-04-08 09:26:00+08'),
    (40003, 1002, 10003, timestamptz '2026-04-08 09:36:00+08')
on conflict (id) do update
set user_id = excluded.user_id,
    merge_request_id = excluded.merge_request_id,
    created_at = excluded.created_at,
    mirror_updated_at = current_timestamp;

insert into ods_gitlab_label_links (
    id,
    label_id,
    target_id,
    target_type,
    created_at,
    updated_at
)
values
    (50001, 1001, 10001, 'MergeRequest', timestamp '2026-04-08 09:14:00', timestamp '2026-04-08 09:14:00'),
    (50002, 1002, 10002, 'MergeRequest', timestamp '2026-04-08 09:24:00', timestamp '2026-04-08 09:24:00'),
    (50003, 1003, 10003, 'MergeRequest', timestamp '2026-04-08 09:34:00', timestamp '2026-04-08 09:34:00')
on conflict (id) do update
set label_id = excluded.label_id,
    target_id = excluded.target_id,
    target_type = excluded.target_type,
    created_at = excluded.created_at,
    updated_at = excluded.updated_at,
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
    ('http://172.22.10.233', 1, 101, 'merge_request', '101', 'code_review', '代码走查表', '代码走查人', 35, 90, 92, 88, 91, 89, '本地合法测试样例一：已完成代码走查', false),
    ('http://172.22.10.233', 1, 102, 'merge_request', '102', 'code_review', '代码走查表', '代码走查人', 28, 91, 90, 89, 88, 90, '本地合法测试样例二：已完成代码走查', false),
    ('http://172.22.10.233', 1, 103, 'merge_request', '103', 'code_review', '代码走查表', '模块负责人', 22, 88, 87, 90, 86, 88, '本地合法测试样例三：已完成代码走查', false)
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
    (1, 10001, 101, 33.60, 'CC_TOOL', 0, 'MR_ROBOT', 'CC 注释率来自外部注释率工具回传，缺陷数来自 MR 机器人评论解析', '{"commentRate":"33.60","commentRateSource":"CC_TOOL","defectCount":"0","defectCountSource":"MR_ROBOT"}'),
    (1, 10002, 102, 24.50, 'DGM_ROBOT_COMMENT', 1, 'SONAR_AND_ROBOT', 'DGM 注释率来自 MR 机器人评论解析，缺陷数来自 Sonar 与机器人汇总', '{"commentRate":"24.50","commentRateSource":"DGM_ROBOT_COMMENT","defectCount":"1","defectCountSource":"SONAR_AND_ROBOT"}'),
    (1, 10003, 103, 31.20, 'CC_TOOL', 0, 'MR_COMMENT', 'CC 注释率来自外部注释率工具回传，缺陷数来自 MR 评论人工确认', '{"commentRate":"31.20","commentRateSource":"CC_TOOL","defectCount":"0","defectCountSource":"MR_COMMENT"}')
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

commit;
