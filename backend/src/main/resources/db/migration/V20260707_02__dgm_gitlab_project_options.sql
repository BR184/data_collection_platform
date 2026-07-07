create table if not exists code_review_dgm_gitlab_project_source_settings (
    id bigint primary key,
    enabled boolean not null default false,
    gitlab_base_url varchar(512) not null default '',
    access_token text,
    group_path varchar(512) not null default '',
    include_subgroups boolean not null default true,
    include_archived boolean not null default false,
    sync_interval_minutes integer not null default 60,
    last_sync_status varchar(32) not null default 'IDLE',
    last_sync_message text,
    last_sync_record_count bigint not null default 0,
    last_sync_started_at timestamp,
    last_sync_finished_at timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint ck_code_review_dgm_gitlab_project_source_singleton check (id = 1),
    constraint ck_code_review_dgm_gitlab_project_sync_interval check (sync_interval_minutes between 1 and 10080)
);

insert into code_review_dgm_gitlab_project_source_settings(id)
values (1)
on conflict (id) do nothing;

create table if not exists code_review_dgm_project_options (
    id bigserial primary key,
    source_instance varchar(32) not null default 'dgm',
    gitlab_project_id bigint not null,
    name varchar(512) not null,
    path varchar(512),
    path_with_namespace varchar(1024),
    web_url varchar(1024),
    namespace_name varchar(512),
    namespace_full_path varchar(1024),
    archived boolean not null default false,
    visibility varchar(64),
    active boolean not null default true,
    last_seen_at timestamp not null default current_timestamp,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_code_review_dgm_project_options_source_project unique (source_instance, gitlab_project_id)
);

create index if not exists idx_code_review_dgm_project_options_active_name
    on code_review_dgm_project_options(source_instance, active, lower(name));
