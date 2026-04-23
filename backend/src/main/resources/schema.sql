create table if not exists gitlab_sync_configs (
    id bigserial primary key,
    name varchar(128) not null default 'default',
    enabled boolean not null default true,
    auto_sync_enabled boolean not null default true,
    source_mode varchar(32) not null default 'DOCKER',
    whitelist_mode varchar(32) not null default 'RECOMMENDED',
    whitelist_tables text,
    db_host varchar(255) not null default 'localhost',
    db_port integer not null default 5432,
    db_name varchar(255) not null,
    db_username varchar(255) not null,
    db_password varchar(255) not null,
    docker_container_name varchar(255),
    webhook_secret varchar(255),
    webhook_project_id bigint,
    compensation_interval_minutes integer not null default 10,
    last_full_sync_at timestamp,
    last_incremental_sync_at timestamp,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create table if not exists gitlab_sync_logs (
    id bigserial primary key,
    config_id bigint not null references gitlab_sync_configs(id) on delete cascade,
    sync_type varchar(32) not null,
    status varchar(32) not null,
    message text,
    whitelist_snapshot text,
    table_count integer not null default 0,
    record_count integer not null default 0,
    started_at timestamp not null default current_timestamp,
    finished_at timestamp
);

create table if not exists gitlab_sync_tasks (
    id bigserial primary key,
    run_id varchar(64) not null,
    config_id bigint not null references gitlab_sync_configs(id) on delete cascade,
    task_type varchar(32) not null,
    trigger_type varchar(32) not null,
    source_mode varchar(32) not null,
    scope_key varchar(512) not null,
    dedupe_key varchar(512) not null,
    status varchar(32) not null,
    cancel_requested boolean not null default false,
    pending_resync boolean not null default false,
    retry_count integer not null default 0,
    cooldown_until timestamp,
    heartbeat_at timestamp,
    queued_at timestamp,
    run_after timestamp,
    started_at timestamp,
    finished_at timestamp,
    finished_reason text,
    lock_owner varchar(128),
    version integer not null default 0,
    payload_json text,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create table if not exists gitlab_webhook_events (
    id bigserial primary key,
    config_id bigint references gitlab_sync_configs(id) on delete set null,
    event_type varchar(128),
    project_id bigint,
    object_kind varchar(128),
    payload text not null,
    received_at timestamp not null default current_timestamp,
    processed boolean not null default false
);

create table if not exists gitlab_mirror_records (
    id bigserial primary key,
    config_id bigint not null references gitlab_sync_configs(id) on delete cascade,
    table_name varchar(128) not null,
    record_key varchar(512) not null,
    updated_at_source timestamp,
    row_data jsonb not null,
    synced_at timestamp not null default current_timestamp,
    created_at timestamp not null default current_timestamp,
    unique (config_id, table_name, record_key)
);

create table if not exists collect_form_records (
    id bigserial primary key,
    gitlab_base_url varchar(255) not null,
    project_id bigint not null,
    request_iid bigint,
    resource_type varchar(64) not null,
    resource_id varchar(255) not null,
    template_code varchar(128) not null,
    form_title varchar(255) not null default '采集表单',
    reviewer varchar(128),
    review_duration_minutes integer not null default 0,
    specification_score integer not null default 0,
    logic_score integer not null default 0,
    performance_score integer not null default 0,
    design_score integer not null default 0,
    other_score integer not null default 0,
    remark text,
    deleted boolean not null default false,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    unique (gitlab_base_url, project_id, resource_type, resource_id, template_code)
);

create table if not exists review_records (
    id bigserial primary key,
    project_name varchar(255) not null,
    title varchar(512) not null,
    module_name varchar(255) not null,
    review_type varchar(128) not null,
    review_date date,
    review_owner varchar(128) not null,
    review_scale_pages integer not null default 0,
    review_product varchar(255) not null,
    author_name varchar(128) not null,
    review_version varchar(128) not null,
    deleted boolean not null default false,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create table if not exists review_record_experts (
    id bigserial primary key,
    review_record_id bigint not null references review_records(id) on delete cascade,
    expert_name varchar(128) not null,
    sort_order integer not null default 0,
    deleted boolean not null default false,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    unique (review_record_id, expert_name)
);

create table if not exists review_problem_items (
    id bigserial primary key,
    review_record_id bigint not null references review_records(id) on delete cascade,
    reviewer_name varchar(128) not null,
    workload_hours numeric(8, 2) not null default 0,
    review_category varchar(128) not null,
    document_position varchar(255),
    problem_category varchar(128) not null,
    problem_description text not null default '',
    suggested_solution text,
    owner_name varchar(128),
    rejection_reason text,
    problem_status varchar(128) not null default '新提交',
    deleted boolean not null default false,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

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

create table if not exists integration_test_fact (
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
    issuable_reference varchar(128),
    title varchar(512) not null default '',
    issue_state varchar(64),
    author_name varchar(128),
    assignee_name varchar(128),
    created_at_source timestamp,
    updated_at_source timestamp,
    ods_updated_at timestamp,
    note_id bigint,
    note_created_at_source timestamp,
    note_updated_at_source timestamp,
    module_name varchar(255),
    function_name varchar(255),
    executor varchar(128),
    testing_phase varchar(128),
    execute_case integer,
    pass_case integer,
    not_pass_case integer,
    not_pass_case_now integer,
    problem_case integer,
    exception_count integer,
    pass_rate numeric(8, 2),
    legal boolean not null default false,
    label_names text,
    function_labels text,
    deleted boolean not null default false,
    fact_refreshed_at timestamp not null default current_timestamp,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    unique (source_system, source_instance, project_id, issue_id)
);

create index if not exists idx_integration_test_fact_phase
    on integration_test_fact(project_id, testing_phase);

create index if not exists idx_integration_test_fact_module
    on integration_test_fact(project_id, testing_phase, module_name);

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

create table if not exists testing_phase_calendar (
    id bigserial primary key,
    project_id bigint not null,
    testing_phase varchar(128) not null,
    phase_start_at timestamp not null,
    phase_end_at timestamp,
    enabled boolean not null default true,
    remark varchar(255),
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    unique (project_id, testing_phase)
);

create table if not exists module_dictionary (
    id bigserial primary key,
    dictionary_domain varchar(64) not null default 'COMMON',
    project_id bigint,
    standard_module_name varchar(255) not null,
    alias_name varchar(255) not null,
    enabled boolean not null default true,
    priority integer not null default 0,
    remark varchar(255),
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create table if not exists sys_table_registry (
    id bigserial primary key,
    config_id bigint not null references gitlab_sync_configs(id) on delete cascade,
    source_table_name varchar(255) not null,
    mirror_table_name varchar(255) not null,
    schema_fingerprint varchar(128) not null,
    is_initialized boolean not null default false,
    last_sync_time timestamp,
    last_schema_check_time timestamp,
    sync_status varchar(32) not null default 'IDLE',
    preview_enabled boolean not null default true,
    column_snapshot jsonb not null,
    primary_key_columns text not null,
    updated_at_column varchar(255),
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    unique (config_id, source_table_name),
    unique (config_id, mirror_table_name)
);

alter table gitlab_sync_configs add column if not exists source_mode varchar(32) not null default 'DOCKER';
alter table gitlab_sync_configs add column if not exists docker_container_name varchar(255);
alter table gitlab_sync_tasks add column if not exists run_id varchar(64);
alter table gitlab_sync_tasks add column if not exists trigger_type varchar(32) default 'MANUAL';
alter table gitlab_sync_tasks add column if not exists source_mode varchar(32) default 'DOCKER';
alter table gitlab_sync_tasks add column if not exists scope_key varchar(512) default '';
alter table gitlab_sync_tasks add column if not exists dedupe_key varchar(512) default '';
alter table gitlab_sync_tasks add column if not exists cancel_requested boolean not null default false;
alter table gitlab_sync_tasks add column if not exists pending_resync boolean not null default false;
alter table gitlab_sync_tasks add column if not exists retry_count integer not null default 0;
alter table gitlab_sync_tasks add column if not exists cooldown_until timestamp;
alter table gitlab_sync_tasks add column if not exists heartbeat_at timestamp;
alter table gitlab_sync_tasks add column if not exists queued_at timestamp;
alter table gitlab_sync_tasks add column if not exists run_after timestamp;
alter table gitlab_sync_tasks add column if not exists started_at timestamp;
alter table gitlab_sync_tasks add column if not exists finished_at timestamp;
alter table gitlab_sync_tasks add column if not exists finished_reason text;
alter table gitlab_sync_tasks add column if not exists lock_owner varchar(128);
alter table gitlab_sync_tasks add column if not exists version integer not null default 0;
alter table gitlab_sync_tasks add column if not exists payload_json text;
alter table gitlab_sync_tasks add column if not exists created_at timestamp not null default current_timestamp;
alter table gitlab_sync_tasks add column if not exists updated_at timestamp not null default current_timestamp;
alter table sys_table_registry add column if not exists preview_enabled boolean not null default true;
alter table issue_fact add column if not exists ods_updated_at timestamp;
alter table issue_fact add column if not exists primary_module_name varchar(255);
alter table issue_fact add column if not exists module_names text;
alter table issue_fact add column if not exists function_name varchar(255);
alter table merge_request_fact add column if not exists ods_updated_at timestamp;
alter table issue_fact add column if not exists severity_alias varchar(128);
alter table issue_fact add column if not exists priority_level varchar(64);
alter table issue_fact add column if not exists reason_category varchar(255);
alter table issue_fact add column if not exists is_excluded boolean not null default false;
alter table issue_fact add column if not exists exclusion_reason varchar(255);
alter table issue_fact add column if not exists is_fixed boolean not null default false;
alter table issue_fact add column if not exists delay_reason varchar(255);
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

create index if not exists idx_gitlab_mirror_records_table on gitlab_mirror_records(config_id, table_name);
create index if not exists idx_collect_form_records_context on collect_form_records(project_id, resource_type, resource_id, template_code);
create index if not exists idx_review_records_main on review_records(project_name, module_name, review_owner, review_type, review_date);
create index if not exists idx_review_record_experts_record on review_record_experts(review_record_id, deleted, sort_order);
create index if not exists idx_review_record_experts_name on review_record_experts(expert_name, deleted);
create index if not exists idx_review_problem_items_record on review_problem_items(review_record_id, deleted, problem_status, updated_at desc);
create index if not exists idx_review_problem_items_reviewer on review_problem_items(reviewer_name, deleted);
create index if not exists idx_code_review_external_metrics_context on code_review_external_metrics(project_id, merge_request_iid);
create index if not exists idx_issue_fact_context on issue_fact(source_system, source_instance, project_id, issue_iid);
create index if not exists idx_issue_fact_state on issue_fact(issue_state, severity_level, priority_level);
create index if not exists idx_issue_fact_module on issue_fact(module_name, testing_phase, bug_status);
create index if not exists idx_issue_fact_function on issue_fact(function_name, project_id);
create index if not exists idx_issue_fact_filters on issue_fact(project_id, severity_level, priority_level, is_excluded, is_fixed);
create index if not exists idx_issue_fact_legacy on issue_fact(issue_state, is_legacy, testing_phase);
create index if not exists idx_merge_request_fact_context on merge_request_fact(source_system, source_instance, project_id, merge_request_iid);
create index if not exists idx_merge_request_fact_owner on merge_request_fact(owner_name, module_name, merge_request_state);
create index if not exists idx_merge_request_fact_metrics on merge_request_fact(comment_rate, defect_count, review_duration_minutes);
create index if not exists idx_testing_phase_calendar_context on testing_phase_calendar(project_id, testing_phase, enabled);
create unique index if not exists uk_module_dictionary_global on module_dictionary(dictionary_domain, alias_name) where project_id is null;
create unique index if not exists uk_module_dictionary_project on module_dictionary(dictionary_domain, project_id, alias_name) where project_id is not null;
create index if not exists idx_module_dictionary_context on module_dictionary(dictionary_domain, project_id, enabled, priority desc);
create index if not exists idx_sys_table_registry_config on sys_table_registry(config_id, source_table_name);
create index if not exists idx_sys_table_registry_preview on sys_table_registry(config_id, preview_enabled, source_table_name);
create index if not exists idx_gitlab_sync_logs_config on gitlab_sync_logs(config_id, started_at desc);
create index if not exists idx_gitlab_sync_tasks_config on gitlab_sync_tasks(config_id, created_at desc);
create index if not exists idx_gitlab_sync_tasks_scope_status on gitlab_sync_tasks(scope_key, status, created_at desc);
create index if not exists idx_gitlab_sync_tasks_dedupe on gitlab_sync_tasks(dedupe_key, created_at desc);
