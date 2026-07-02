alter table gitlab_sync_configs
    add column if not exists match_mode_enabled boolean not null default true;

create table if not exists code_review_match_mode_records (
    id bigserial primary key,
    source_instance varchar(128) not null default 'cc',
    project_id bigint not null default 0,
    project_name varchar(255),
    repository_name varchar(255),
    merge_request_id bigint not null,
    merge_request_iid bigint not null,
    title varchar(512) not null default '',
    merge_request_state varchar(64),
    target_branch varchar(255),
    author_name varchar(128),
    merge_user_name varchar(128),
    owner_name varchar(255),
    reviewer_names varchar(512),
    assignee_names varchar(512),
    module_name varchar(255),
    label_names text,
    search_text text,
    search_compact text,
    search_spell text,
    search_initials text,
    merged_at_source timestamp,
    code_walkthrough_date timestamp,
    review_status varchar(128),
    review_duration_minutes integer,
    review_exception_reason varchar(128),
    scan_status varchar(128),
    scan_bug_count integer,
    annotation_rate_result varchar(128),
    bug_count_result varchar(128),
    comment_rate numeric(8, 2),
    defect_count integer,
    added_lines integer,
    deleted_lines integer,
    code_specification_count integer,
    code_logic_specification_count integer,
    performance_specification_count integer,
    design_specification_count integer,
    other_specification_count integer,
    review_speed_loc_per_hour integer,
    review_speed_kloc_per_hour numeric(10, 2),
    review_defect_density_per_kloc numeric(10, 2),
    review_efficiency_per_hour numeric(10, 2),
    commit_count integer,
    commit_rate integer,
    function_name varchar(255),
    clang_added_line_count integer,
    synced_at timestamp not null default current_timestamp,
    unique (source_instance, project_id, merge_request_id)
);

create index if not exists idx_code_review_match_mode_records_query
    on code_review_match_mode_records(source_instance, merged_at_source desc, merge_request_iid desc);

create index if not exists idx_code_review_match_mode_records_project
    on code_review_match_mode_records(project_name, target_branch, module_name);

create table if not exists code_review_match_mode_sync_state (
    id smallint primary key default 1,
    status varchar(32) not null default 'IDLE',
    message text,
    record_count bigint not null default 0,
    started_at timestamp,
    finished_at timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint ck_code_review_match_mode_sync_state_singleton check (id = 1)
);

insert into code_review_match_mode_sync_state(id, status, message)
values (1, 'IDLE', '兼容模式尚未同步')
on conflict (id) do nothing;
