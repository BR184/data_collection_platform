create extension if not exists pg_trgm;

create table if not exists review_records (
    id bigserial primary key,
    project_name varchar(255) not null default '',
    title varchar(512) not null default '',
    module_name varchar(255) not null default '',
    review_type varchar(128) not null default '',
    review_date date,
    review_owner varchar(128) not null default '',
    review_scale_pages integer not null default 0,
    review_product varchar(255) not null default '',
    author_name varchar(128) not null default '',
    review_version varchar(128) not null default '',
    search_text text,
    search_compact text,
    search_spell text,
    search_initials text,
    title_search_text text,
    title_search_compact text,
    title_search_spell text,
    title_search_initials text,
    deleted boolean not null default false,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create table if not exists review_record_experts (
    id bigserial primary key,
    review_record_id bigint not null references review_records(id) on delete cascade,
    expert_name varchar(128) not null default '',
    sort_order integer not null default 0,
    deleted boolean not null default false,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    unique (review_record_id, expert_name)
);

create table if not exists review_problem_items (
    id bigserial primary key,
    review_record_id bigint not null references review_records(id) on delete cascade,
    reviewer_name varchar(128) not null default '',
    workload_hours numeric(8, 2) not null default 0,
    review_category varchar(128) not null default '',
    document_position varchar(255),
    problem_category varchar(128) not null default '',
    problem_description text not null default '',
    suggested_solution text,
    owner_name varchar(128),
    rejection_reason text,
    problem_status varchar(128) not null default '新提交',
    deleted boolean not null default false,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create table if not exists issue_fact (
    id bigserial primary key,
    source_system varchar(64) not null default 'GITLAB',
    source_instance varchar(128) not null default 'default',
    ingest_channel varchar(64) not null default 'MIRROR',
    source_summary varchar(255),
    raw_payload text,
    project_id bigint not null,
    project_name varchar(255),
    issue_id bigint not null,
    issue_iid bigint not null,
    title varchar(512) not null default '',
    issue_state varchar(64),
    issue_type varchar(128),
    milestone_title varchar(255),
    author_name varchar(128),
    assignee_name varchar(128),
    created_at_source timestamp,
    updated_at_source timestamp,
    ods_updated_at timestamp,
    closed_at_source timestamp,
    module_name varchar(255),
    primary_module_name varchar(255),
    module_names text,
    function_name varchar(255),
    testing_phase varchar(128),
    severity_level varchar(128),
    severity_alias varchar(128),
    priority_level varchar(64),
    urgency varchar(64),
    bug_status varchar(128),
    category varchar(255),
    reason_category varchar(255),
    system_test_label varchar(255),
    label_names text,
    primary_phase_label varchar(255),
    phase_filter_value varchar(255),
    search_text text,
    search_compact text,
    search_spell text,
    search_initials text,
    title_search_text text,
    title_search_compact text,
    title_search_spell text,
    title_search_initials text,
    module_search_text text,
    module_search_compact text,
    module_search_spell text,
    module_search_initials text,
    milestone_search_text text,
    milestone_search_compact text,
    milestone_search_spell text,
    milestone_search_initials text,
    author_search_text text,
    author_search_compact text,
    author_search_spell text,
    author_search_initials text,
    assignee_search_text text,
    assignee_search_compact text,
    assignee_search_spell text,
    assignee_search_initials text,
    phase_search_text text,
    phase_search_compact text,
    phase_search_spell text,
    phase_search_initials text,
    is_excluded boolean not null default false,
    exclusion_reason varchar(255),
    is_fixed boolean not null default false,
    delay_issue boolean not null default false,
    delay_reason varchar(255),
    delay_cause varchar(255),
    is_regression boolean not null default false,
    is_crash boolean not null default false,
    is_level1_other boolean not null default false,
    is_illegal boolean not null default false,
    illegal_reason varchar(255),
    has_response boolean not null default false,
    response_overdue boolean not null default false,
    is_response_delayed boolean not null default false,
    resolve_sla_days integer not null default 18,
    resolve_deadline_at timestamp,
    is_resolve_delayed boolean not null default false,
    is_legacy boolean not null default false,
    deleted boolean not null default false,
    fact_refreshed_at timestamp not null default current_timestamp,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    unique (source_system, source_instance, project_id, issue_id)
);

create table if not exists merge_request_fact (
    id bigserial primary key,
    source_system varchar(64) not null default 'GITLAB',
    source_instance varchar(128) not null default 'default',
    ingest_channel varchar(64) not null default 'MIRROR',
    source_summary varchar(255),
    raw_payload text,
    project_id bigint not null,
    project_name varchar(255),
    repository_name varchar(255),
    merge_request_id bigint not null,
    merge_request_iid bigint not null,
    title varchar(512) not null default '',
    merge_request_state varchar(64),
    target_branch varchar(255),
    source_branch varchar(255),
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
    owner_search_text text,
    owner_search_compact text,
    owner_search_spell text,
    owner_search_initials text,
    created_at_source timestamp,
    updated_at_source timestamp,
    ods_updated_at timestamp,
    merged_at_source timestamp,
    review_status varchar(128),
    review_duration_minutes integer,
    comment_rate numeric(8, 2),
    comment_rate_source varchar(64),
    defect_count integer,
    defect_count_source varchar(64),
    scan_status varchar(128),
    scan_bug_count integer,
    added_lines integer,
    deleted boolean not null default false,
    fact_refreshed_at timestamp not null default current_timestamp,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    unique (source_system, source_instance, project_id, merge_request_id)
);

alter table review_records add column if not exists search_text text;
alter table review_records add column if not exists search_compact text;
alter table review_records add column if not exists search_spell text;
alter table review_records add column if not exists search_initials text;
alter table review_records add column if not exists title_search_text text;
alter table review_records add column if not exists title_search_compact text;
alter table review_records add column if not exists title_search_spell text;
alter table review_records add column if not exists title_search_initials text;

alter table issue_fact add column if not exists ods_updated_at timestamp;
alter table issue_fact add column if not exists primary_module_name varchar(255);
alter table issue_fact add column if not exists module_names text;
alter table issue_fact add column if not exists function_name varchar(255);
alter table issue_fact add column if not exists severity_alias varchar(128);
alter table issue_fact add column if not exists priority_level varchar(64);
alter table issue_fact add column if not exists reason_category varchar(255);
alter table issue_fact add column if not exists is_excluded boolean not null default false;
alter table issue_fact add column if not exists exclusion_reason varchar(255);
alter table issue_fact add column if not exists is_fixed boolean not null default false;
alter table issue_fact add column if not exists delay_issue boolean not null default false;
alter table issue_fact add column if not exists delay_reason varchar(255);
alter table issue_fact add column if not exists delay_cause varchar(255);
alter table issue_fact add column if not exists is_regression boolean not null default false;
alter table issue_fact add column if not exists is_crash boolean not null default false;
alter table issue_fact add column if not exists is_level1_other boolean not null default false;
alter table issue_fact add column if not exists is_illegal boolean not null default false;
alter table issue_fact add column if not exists illegal_reason varchar(255);
alter table issue_fact add column if not exists has_response boolean not null default false;
alter table issue_fact add column if not exists response_overdue boolean not null default false;
alter table issue_fact add column if not exists is_response_delayed boolean not null default false;
alter table issue_fact add column if not exists resolve_sla_days integer not null default 18;
alter table issue_fact add column if not exists resolve_deadline_at timestamp;
alter table issue_fact add column if not exists is_resolve_delayed boolean not null default false;
alter table issue_fact add column if not exists is_legacy boolean not null default false;
alter table issue_fact add column if not exists primary_phase_label varchar(255);
alter table issue_fact add column if not exists phase_filter_value varchar(255);
alter table issue_fact add column if not exists search_text text;
alter table issue_fact add column if not exists search_compact text;
alter table issue_fact add column if not exists search_spell text;
alter table issue_fact add column if not exists search_initials text;
alter table issue_fact add column if not exists title_search_text text;
alter table issue_fact add column if not exists title_search_compact text;
alter table issue_fact add column if not exists title_search_spell text;
alter table issue_fact add column if not exists title_search_initials text;
alter table issue_fact add column if not exists module_search_text text;
alter table issue_fact add column if not exists module_search_compact text;
alter table issue_fact add column if not exists module_search_spell text;
alter table issue_fact add column if not exists module_search_initials text;
alter table issue_fact add column if not exists milestone_search_text text;
alter table issue_fact add column if not exists milestone_search_compact text;
alter table issue_fact add column if not exists milestone_search_spell text;
alter table issue_fact add column if not exists milestone_search_initials text;
alter table issue_fact add column if not exists author_search_text text;
alter table issue_fact add column if not exists author_search_compact text;
alter table issue_fact add column if not exists author_search_spell text;
alter table issue_fact add column if not exists author_search_initials text;
alter table issue_fact add column if not exists assignee_search_text text;
alter table issue_fact add column if not exists assignee_search_compact text;
alter table issue_fact add column if not exists assignee_search_spell text;
alter table issue_fact add column if not exists assignee_search_initials text;
alter table issue_fact add column if not exists phase_search_text text;
alter table issue_fact add column if not exists phase_search_compact text;
alter table issue_fact add column if not exists phase_search_spell text;
alter table issue_fact add column if not exists phase_search_initials text;

alter table merge_request_fact add column if not exists ods_updated_at timestamp;
alter table merge_request_fact add column if not exists search_text text;
alter table merge_request_fact add column if not exists search_compact text;
alter table merge_request_fact add column if not exists search_spell text;
alter table merge_request_fact add column if not exists search_initials text;
alter table merge_request_fact add column if not exists owner_search_text text;
alter table merge_request_fact add column if not exists owner_search_compact text;
alter table merge_request_fact add column if not exists owner_search_spell text;
alter table merge_request_fact add column if not exists owner_search_initials text;

create index if not exists idx_review_records_main on review_records(project_name, module_name, review_owner, review_type, review_date);
create index if not exists idx_review_records_active_updated on review_records(updated_at desc, id asc) where deleted = false;
create index if not exists idx_review_records_active_review_date on review_records(review_date, id asc) where deleted = false;
create index if not exists idx_review_records_active_scale on review_records(review_scale_pages, id asc) where deleted = false;
create index if not exists idx_review_records_lower_title on review_records(lower(coalesce(title, ''))) where deleted = false;
create index if not exists idx_review_records_lower_project on review_records(lower(coalesce(project_name, ''))) where deleted = false;
create index if not exists idx_review_records_lower_module on review_records(lower(coalesce(module_name, ''))) where deleted = false;
create index if not exists idx_review_records_lower_owner on review_records(lower(coalesce(review_owner, ''))) where deleted = false;
create index if not exists idx_review_records_lower_type on review_records(lower(coalesce(review_type, ''))) where deleted = false;
create index if not exists idx_review_records_search_text_trgm on review_records using gin (search_text gin_trgm_ops) where deleted = false;
create index if not exists idx_review_records_search_compact_trgm on review_records using gin (search_compact gin_trgm_ops) where deleted = false;
create index if not exists idx_review_records_search_spell_trgm on review_records using gin (search_spell gin_trgm_ops) where deleted = false;
create index if not exists idx_review_records_search_initials_trgm on review_records using gin (search_initials gin_trgm_ops) where deleted = false;
create index if not exists idx_review_records_title_search_text_trgm on review_records using gin (title_search_text gin_trgm_ops) where deleted = false;
create index if not exists idx_review_records_title_search_compact_trgm on review_records using gin (title_search_compact gin_trgm_ops) where deleted = false;
create index if not exists idx_review_records_title_search_spell_trgm on review_records using gin (title_search_spell gin_trgm_ops) where deleted = false;
create index if not exists idx_review_records_title_search_initials_trgm on review_records using gin (title_search_initials gin_trgm_ops) where deleted = false;
create index if not exists idx_review_records_search_missing on review_records(id) where deleted = false and search_text is null;
create index if not exists idx_review_records_title_search_missing on review_records(id) where deleted = false and title_search_text is null;
create index if not exists idx_review_record_experts_record on review_record_experts(review_record_id, deleted, sort_order);
create index if not exists idx_review_record_experts_name on review_record_experts(expert_name, deleted);
create index if not exists idx_review_record_experts_lower_name_record on review_record_experts(lower(coalesce(expert_name, '')), review_record_id) where deleted = false;
create index if not exists idx_review_problem_items_record on review_problem_items(review_record_id, deleted, problem_status, updated_at desc);
create index if not exists idx_review_problem_items_lower_status_record on review_problem_items(lower(coalesce(problem_status, '')), review_record_id) where deleted = false;
create index if not exists idx_review_problem_items_reviewer on review_problem_items(reviewer_name, deleted);

create index if not exists idx_issue_fact_context on issue_fact(source_system, source_instance, project_id, issue_iid);
create index if not exists idx_issue_fact_state on issue_fact(issue_state, severity_level, priority_level);
create index if not exists idx_issue_fact_module on issue_fact(module_name, testing_phase, bug_status);
create index if not exists idx_issue_fact_function on issue_fact(function_name, project_id);
create index if not exists idx_issue_fact_filters on issue_fact(project_id, severity_level, priority_level, is_excluded, is_fixed);
create index if not exists idx_issue_fact_legacy on issue_fact(issue_state, is_legacy, testing_phase);
create index if not exists idx_issue_fact_list_updated on issue_fact(deleted, updated_at_source desc, issue_iid desc);
create index if not exists idx_issue_fact_project_list_updated on issue_fact(project_id, deleted, updated_at_source desc, issue_iid desc);
create index if not exists idx_issue_fact_illegal_list_updated on issue_fact(is_illegal, is_excluded, deleted, updated_at_source desc, issue_iid desc);
create index if not exists idx_issue_fact_scope_project_name_trgm on issue_fact using gin (lower(coalesce(project_name, '')) gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_scope_milestone_trgm on issue_fact using gin (lower(coalesce(milestone_title, '')) gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_scope_label_trgm on issue_fact using gin (lower(coalesce(label_names, '')) gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_scope_testing_phase_trgm on issue_fact using gin (lower(coalesce(testing_phase, '')) gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_scope_system_label_trgm on issue_fact using gin (lower(coalesce(system_test_label, '')) gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_module_names_trgm on issue_fact using gin (lower(coalesce(module_names, '')) gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_search_text_trgm on issue_fact using gin (search_text gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_search_compact_trgm on issue_fact using gin (search_compact gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_search_spell_trgm on issue_fact using gin (search_spell gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_search_initials_trgm on issue_fact using gin (search_initials gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_title_search_text_trgm on issue_fact using gin (title_search_text gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_title_search_compact_trgm on issue_fact using gin (title_search_compact gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_title_search_spell_trgm on issue_fact using gin (title_search_spell gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_title_search_initials_trgm on issue_fact using gin (title_search_initials gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_module_search_text_trgm on issue_fact using gin (module_search_text gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_module_search_compact_trgm on issue_fact using gin (module_search_compact gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_module_search_spell_trgm on issue_fact using gin (module_search_spell gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_module_search_initials_trgm on issue_fact using gin (module_search_initials gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_milestone_search_text_trgm on issue_fact using gin (milestone_search_text gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_milestone_search_compact_trgm on issue_fact using gin (milestone_search_compact gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_milestone_search_spell_trgm on issue_fact using gin (milestone_search_spell gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_milestone_search_initials_trgm on issue_fact using gin (milestone_search_initials gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_author_search_text_trgm on issue_fact using gin (author_search_text gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_author_search_compact_trgm on issue_fact using gin (author_search_compact gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_author_search_spell_trgm on issue_fact using gin (author_search_spell gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_author_search_initials_trgm on issue_fact using gin (author_search_initials gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_assignee_search_text_trgm on issue_fact using gin (assignee_search_text gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_assignee_search_compact_trgm on issue_fact using gin (assignee_search_compact gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_assignee_search_spell_trgm on issue_fact using gin (assignee_search_spell gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_assignee_search_initials_trgm on issue_fact using gin (assignee_search_initials gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_phase_filter on issue_fact(phase_filter_value) where deleted = false;
create index if not exists idx_issue_fact_phase_search_text_trgm on issue_fact using gin (phase_search_text gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_phase_search_compact_trgm on issue_fact using gin (phase_search_compact gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_phase_search_spell_trgm on issue_fact using gin (phase_search_spell gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_phase_search_initials_trgm on issue_fact using gin (phase_search_initials gin_trgm_ops) where deleted = false;

create index if not exists idx_merge_request_fact_context on merge_request_fact(source_system, source_instance, project_id, merge_request_iid);
create index if not exists idx_merge_request_fact_owner on merge_request_fact(owner_name, module_name, merge_request_state);
create index if not exists idx_merge_request_fact_metrics on merge_request_fact(comment_rate, defect_count, review_duration_minutes);
create index if not exists idx_merge_request_fact_list_merged on merge_request_fact(deleted, merged_at_source desc, merge_request_iid desc);
create index if not exists idx_merge_request_fact_project_list_merged on merge_request_fact(project_id, deleted, merged_at_source desc, merge_request_iid desc);
create index if not exists idx_merge_request_fact_repository_trgm on merge_request_fact using gin (repository_name gin_trgm_ops) where deleted = false;
create index if not exists idx_merge_request_fact_project_name_trgm on merge_request_fact using gin (project_name gin_trgm_ops) where deleted = false;
create index if not exists idx_merge_request_fact_target_branch_trgm on merge_request_fact using gin (target_branch gin_trgm_ops) where deleted = false;
create index if not exists idx_merge_request_fact_module_trgm on merge_request_fact using gin (module_name gin_trgm_ops) where deleted = false;
create index if not exists idx_merge_request_fact_search_text_trgm on merge_request_fact using gin (search_text gin_trgm_ops) where deleted = false;
create index if not exists idx_merge_request_fact_search_compact_trgm on merge_request_fact using gin (search_compact gin_trgm_ops) where deleted = false;
create index if not exists idx_merge_request_fact_search_spell_trgm on merge_request_fact using gin (search_spell gin_trgm_ops) where deleted = false;
create index if not exists idx_merge_request_fact_search_initials_trgm on merge_request_fact using gin (search_initials gin_trgm_ops) where deleted = false;
create index if not exists idx_merge_request_fact_owner_search_text_trgm on merge_request_fact using gin (owner_search_text gin_trgm_ops) where deleted = false;
create index if not exists idx_merge_request_fact_owner_search_compact_trgm on merge_request_fact using gin (owner_search_compact gin_trgm_ops) where deleted = false;
create index if not exists idx_merge_request_fact_owner_search_spell_trgm on merge_request_fact using gin (owner_search_spell gin_trgm_ops) where deleted = false;
create index if not exists idx_merge_request_fact_owner_search_initials_trgm on merge_request_fact using gin (owner_search_initials gin_trgm_ops) where deleted = false;
