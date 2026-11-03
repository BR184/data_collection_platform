-- 表单管理项目候选只读投影：由本地 GitLab ODS 目录幂等构建，绑定期间不连接外部 GitLab。
-- 搜索形态与事实影子字段一致：原文/去空白/全拼/首字母，服务端在分页前完成过滤。
create table if not exists form_project_candidates (
    source_config_id bigint not null references gitlab_sync_configs(id) on delete cascade,
    project_id bigint not null,
    project_name text not null,
    project_path text not null,
    namespace_path text not null,
    full_path text not null,
    search_text text not null,
    search_compact text not null,
    search_spell text not null,
    search_initials text not null,
    refreshed_at timestamp not null default current_timestamp,
    primary key (source_config_id, project_id)
);

create index if not exists idx_form_project_candidates_path
    on form_project_candidates(source_config_id, full_path);

comment on table form_project_candidates is
    '表单管理项目候选只读投影：来源配置、项目业务身份、名称/完整路径与搜索影子字段';
