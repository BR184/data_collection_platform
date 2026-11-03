-- 表单项目候选目录状态：区分未同步、成功为空、最近刷新失败与来源身份不可用。
-- 失败时保留上一次成功投影作为可读快照，状态与行数分离，不把存在旧行误判为刷新成功。
create table if not exists form_project_candidate_states (
    source_config_id bigint primary key references gitlab_sync_configs(id) on delete cascade,
    status varchar(16) not null
        check (status in ('UNSYNCED', 'READY', 'EMPTY', 'FAILED', 'UNAVAILABLE')),
    message text,
    last_success_at timestamp,
    last_attempt_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

comment on table form_project_candidate_states is
    '表单项目候选目录状态：最近一次刷新结果、错误与最后一次成功时间';
