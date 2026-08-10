-- GitLab 提交事实随 MR 事实发布；BI 人工走查兼容快照保持独立数据域。
create table merge_request_commit_fact (
    source_system varchar(32) not null,
    source_instance varchar(128) not null,
    project_id bigint not null,
    project_name varchar(255),
    merge_request_id bigint not null,
    merge_request_iid bigint,
    commit_sha varchar(64) not null,
    committed_at_source timestamp not null,
    fact_refreshed_at timestamp not null default current_timestamp,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    primary key (source_system, source_instance, project_id, merge_request_id, commit_sha),
    constraint ck_merge_request_commit_fact_identity
        check (project_id > 0 and merge_request_id > 0 and length(btrim(commit_sha)) > 0)
);

create index idx_merge_request_commit_fact_scope
    on merge_request_commit_fact(source_instance, project_name, committed_at_source);

create index idx_merge_request_commit_fact_stable_commit
    on merge_request_commit_fact(source_instance, project_id, commit_sha);

create table bi_code_review_compatibility_records (
    id bigserial primary key,
    source_instance varchar(128) not null,
    legacy_source_id bigint not null,
    project_name varchar(255),
    repository_name varchar(255),
    merge_request_iid bigint,
    merge_request_state varchar(64),
    target_branch varchar(255),
    author_name varchar(255),
    reviewer_names varchar(512),
    module_name varchar(255),
    merged_at_source timestamp,
    code_walkthrough_date timestamp,
    review_duration_minutes numeric(12, 2),
    added_lines bigint,
    deleted_lines bigint,
    defect_count bigint,
    code_specification_count bigint,
    code_logic_specification_count bigint,
    performance_specification_count bigint,
    design_specification_count bigint,
    other_specification_count bigint,
    scan_status varchar(255),
    scan_bug_count bigint,
    comment_rate numeric(12, 4),
    comment_rate_source varchar(255),
    synced_at timestamp not null default current_timestamp,
    constraint uk_bi_code_review_compatibility_source
        unique (source_instance, legacy_source_id),
    constraint ck_bi_code_review_compatibility_source_id
        check (length(btrim(source_instance)) > 0 and legacy_source_id > 0)
);

create index idx_bi_code_review_compatibility_scope
    on bi_code_review_compatibility_records(
        source_instance, project_name, target_branch, code_walkthrough_date);

create table bi_code_review_compatibility_records_loading (
    sync_run_id uuid not null,
    source_instance varchar(128) not null,
    legacy_source_id bigint not null,
    project_name varchar(255),
    repository_name varchar(255),
    merge_request_iid bigint,
    merge_request_state varchar(64),
    target_branch varchar(255),
    author_name varchar(255),
    reviewer_names varchar(512),
    module_name varchar(255),
    merged_at_source timestamp,
    code_walkthrough_date timestamp,
    review_duration_minutes numeric(12, 2),
    added_lines bigint,
    deleted_lines bigint,
    defect_count bigint,
    code_specification_count bigint,
    code_logic_specification_count bigint,
    performance_specification_count bigint,
    design_specification_count bigint,
    other_specification_count bigint,
    scan_status varchar(255),
    scan_bug_count bigint,
    comment_rate numeric(12, 4),
    comment_rate_source varchar(255),
    loaded_at timestamp not null default current_timestamp,
    primary key (sync_run_id, source_instance, legacy_source_id),
    constraint ck_bi_code_review_compatibility_loading_source_id
        check (length(btrim(source_instance)) > 0 and legacy_source_id > 0)
);

create table bi_code_review_compatibility_sync_state (
    id smallint primary key default 1,
    status varchar(32) not null default 'IDLE',
    message text,
    record_count bigint not null default 0,
    published_version bigint not null default 0,
    active_run_id uuid,
    started_at timestamp,
    finished_at timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint ck_bi_code_review_compatibility_state_singleton check (id = 1),
    constraint ck_bi_code_review_compatibility_state_status
        check (status in ('IDLE', 'RUNNING', 'SUCCESS', 'FAILED')),
    constraint ck_bi_code_review_compatibility_state_version check (published_version >= 0)
);

insert into bi_code_review_compatibility_sync_state(id, status, message)
values (1, 'IDLE', 'BI 人工走查兼容数据尚未同步')
on conflict (id) do nothing;
