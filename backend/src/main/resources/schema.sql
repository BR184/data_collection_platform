create table if not exists gitlab_sync_configs (
    id bigserial primary key,
    name varchar(128) not null default 'default',
    enabled boolean not null default true,
    source_enabled boolean not null default true,
    source_instance varchar(128) not null default 'default',
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
    webhook_enabled boolean not null default false,
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

create table if not exists gitlab_sync_jobs (
    id bigserial primary key,
    run_id varchar(64) not null,
    config_id bigint not null references gitlab_sync_configs(id) on delete cascade,
    source_instance varchar(128) not null,
    job_type varchar(64) not null,
    trigger_type varchar(32) not null,
    status varchar(32) not null,
    priority integer not null default 0,
    run_after timestamp not null default current_timestamp,
    heartbeat_at timestamp,
    lease_owner varchar(128),
    lease_until timestamp,
    retry_count integer not null default 0,
    max_retry_count integer not null default 3,
    started_at timestamp,
    finished_at timestamp,
    error_code varchar(64),
    error_message text,
    payload_json text,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create table if not exists gitlab_table_sync_states (
    id bigserial primary key,
    config_id bigint not null references gitlab_sync_configs(id) on delete cascade,
    source_instance varchar(128) not null,
    source_table varchar(255) not null,
    mirror_table varchar(255) not null,
    primary_key_columns text not null,
    updated_at_column varchar(255),
    row_strategy varchar(32) not null default 'INCREMENTAL',
    sync_enabled boolean not null default true,
    dirty_flag boolean not null default false,
    last_success_at timestamp,
    last_full_verified_at timestamp,
    last_watermark_at timestamp,
    last_cursor_pk varchar(512),
    source_max_updated_at timestamp,
    source_row_count bigint,
    mirror_row_count bigint,
    schema_fingerprint varchar(128),
    last_error text,
    retry_count integer not null default 0,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    unique (config_id, source_instance, source_table),
    unique (config_id, source_instance, mirror_table)
);

create table if not exists gitlab_table_sync_tasks (
    id bigserial primary key,
    job_id bigint not null references gitlab_sync_jobs(id) on delete cascade,
    config_id bigint not null references gitlab_sync_configs(id) on delete cascade,
    source_instance varchar(128) not null,
    source_table varchar(255) not null,
    mirror_table varchar(255) not null,
    task_type varchar(64) not null,
    status varchar(32) not null,
    row_strategy varchar(32) not null,
    watermark_at timestamp,
    cursor_updated_at timestamp,
    cursor_pk varchar(512),
    batch_size integer not null default 500,
    run_after timestamp not null default current_timestamp,
    lease_owner varchar(128),
    lease_until timestamp,
    heartbeat_at timestamp,
    retry_count integer not null default 0,
    max_retry_count integer not null default 3,
    last_error text,
    rows_scanned bigint not null default 0,
    rows_applied bigint not null default 0,
    started_at timestamp,
    finished_at timestamp,
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

create table if not exists gitlab_hook_events (
    id bigserial primary key,
    config_id bigint references gitlab_sync_configs(id) on delete set null,
    source_instance varchar(128) not null default 'default',
    event_type varchar(128),
    project_id bigint,
    object_kind varchar(128),
    dedupe_key varchar(512),
    dirty_scope varchar(512),
    coalesced_count integer not null default 1,
    status varchar(32) not null default 'RECEIVED',
    payload text not null,
    received_at timestamp not null default current_timestamp,
    processed_at timestamp
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

create table if not exists fact_build_tasks (
    id bigserial primary key,
    run_id varchar(64) not null,
    scope varchar(128) not null,
    config_id bigint references gitlab_sync_configs(id) on delete set null,
    source_instance varchar(128) not null default 'default',
    fact_type varchar(64) not null default 'ALL',
    full_build boolean not null default false,
    status varchar(32) not null,
    trigger_type varchar(32) not null default 'MANUAL',
    lock_owner varchar(128),
    run_after timestamp not null default current_timestamp,
    heartbeat_at timestamp,
    lease_until timestamp,
    retry_count integer not null default 0,
    max_retry_count integer not null default 3,
    affected_rows integer not null default 0,
    message text,
    error_message text,
    payload_json text,
    started_at timestamp,
    finished_at timestamp,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create table if not exists operation_audit_logs (
    id bigserial primary key,
    username varchar(128) not null default 'guest',
    role varchar(32) not null default 'GUEST',
    http_method varchar(16) not null,
    request_path varchar(512) not null,
    remote_address varchar(128),
    response_status integer not null,
    error_message text,
    request_summary text,
    created_at timestamp not null default current_timestamp
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

create table if not exists collect_form_record_audit_logs (
    id bigserial primary key,
    record_id bigint references collect_form_records(id) on delete set null,
    action varchar(32) not null,
    editor_id varchar(128),
    editor_username varchar(128),
    reviewer varchar(128),
    remote_address varchar(128),
    user_agent text,
    snapshot_json jsonb not null,
    created_at timestamp not null default current_timestamp
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
    gitlab_project_id bigint,
    gitlab_resource_iid bigint,
    gitlab_resource_type varchar(64),
    search_text text,
    search_compact text,
    search_spell text,
    search_initials text,
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
    parse_status varchar(32) not null default 'PARTIAL',
    validation_reason varchar(255),
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
alter table gitlab_sync_configs add column if not exists source_instance varchar(128) not null default 'default';
alter table gitlab_sync_configs add column if not exists source_enabled boolean not null default true;
alter table gitlab_sync_configs add column if not exists docker_container_name varchar(255);
alter table gitlab_sync_configs add column if not exists webhook_enabled boolean not null default false;
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
alter table fact_build_tasks add column if not exists run_id varchar(64);
alter table fact_build_tasks add column if not exists scope varchar(32);
alter table fact_build_tasks alter column scope type varchar(128);
alter table fact_build_tasks add column if not exists config_id bigint references gitlab_sync_configs(id) on delete set null;
alter table fact_build_tasks add column if not exists source_instance varchar(128) not null default 'default';
alter table fact_build_tasks add column if not exists fact_type varchar(64) not null default 'ALL';
alter table fact_build_tasks add column if not exists full_build boolean not null default false;
alter table fact_build_tasks add column if not exists status varchar(32);
alter table fact_build_tasks add column if not exists trigger_type varchar(32) not null default 'MANUAL';
alter table fact_build_tasks add column if not exists lock_owner varchar(128);
alter table fact_build_tasks add column if not exists run_after timestamp not null default current_timestamp;
alter table fact_build_tasks add column if not exists heartbeat_at timestamp;
alter table fact_build_tasks add column if not exists lease_until timestamp;
alter table fact_build_tasks add column if not exists retry_count integer not null default 0;
alter table fact_build_tasks add column if not exists max_retry_count integer not null default 3;
alter table fact_build_tasks add column if not exists affected_rows integer not null default 0;
alter table fact_build_tasks add column if not exists message text;
alter table fact_build_tasks add column if not exists error_message text;
alter table fact_build_tasks add column if not exists payload_json text;
alter table fact_build_tasks add column if not exists started_at timestamp;
alter table fact_build_tasks add column if not exists finished_at timestamp;
alter table fact_build_tasks add column if not exists created_at timestamp not null default current_timestamp;
alter table fact_build_tasks add column if not exists updated_at timestamp not null default current_timestamp;
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
alter table merge_request_fact add column if not exists search_text text;
alter table merge_request_fact add column if not exists search_compact text;
alter table merge_request_fact add column if not exists search_spell text;
alter table merge_request_fact add column if not exists search_initials text;
alter table merge_request_fact add column if not exists owner_search_text text;
alter table merge_request_fact add column if not exists owner_search_compact text;
alter table merge_request_fact add column if not exists owner_search_spell text;
alter table merge_request_fact add column if not exists owner_search_initials text;
alter table integration_test_fact add column if not exists parse_status varchar(32) not null default 'PARTIAL';
alter table integration_test_fact add column if not exists validation_reason varchar(255);
alter table review_records add column if not exists search_text text;
alter table review_records add column if not exists search_compact text;
alter table review_records add column if not exists search_spell text;
alter table review_records add column if not exists search_initials text;
alter table review_records add column if not exists title_search_text text;
alter table review_records add column if not exists title_search_compact text;
alter table review_records add column if not exists title_search_spell text;
alter table review_records add column if not exists title_search_initials text;

create extension if not exists pg_trgm with schema public;
create index if not exists idx_operation_audit_logs_created_at on operation_audit_logs(created_at desc);
create index if not exists idx_operation_audit_logs_request_path on operation_audit_logs(request_path, created_at desc);
create index if not exists idx_gitlab_mirror_records_table on gitlab_mirror_records(config_id, table_name);
create index if not exists idx_collect_form_records_context on collect_form_records(project_id, resource_type, resource_id, template_code);
create index if not exists idx_collect_form_record_audit_logs_record on collect_form_record_audit_logs(record_id, created_at desc);
create index if not exists idx_collect_form_record_audit_logs_editor on collect_form_record_audit_logs(editor_username, editor_id, created_at desc);
create index if not exists idx_review_records_main on review_records(project_name, module_name, review_owner, review_type, review_date);
create index if not exists idx_review_records_active_updated on review_records(updated_at desc, id asc) where deleted = false;
create index if not exists idx_review_records_active_review_date on review_records(review_date, id asc) where deleted = false;
create index if not exists idx_review_records_active_scale on review_records(review_scale_pages, id asc) where deleted = false;
create index if not exists idx_review_records_lower_title on review_records(lower(coalesce(title, ''))) where deleted = false;
create index if not exists idx_review_records_lower_project on review_records(lower(coalesce(project_name, ''))) where deleted = false;
create index if not exists idx_review_records_lower_module on review_records(lower(coalesce(module_name, ''))) where deleted = false;
create index if not exists idx_review_records_lower_owner on review_records(lower(coalesce(review_owner, ''))) where deleted = false;
create index if not exists idx_review_records_lower_type on review_records(lower(coalesce(review_type, ''))) where deleted = false;
create index if not exists idx_review_records_search_text_trgm on review_records using gin (search_text public.gin_trgm_ops) where deleted = false;
create index if not exists idx_review_records_search_compact_trgm on review_records using gin (search_compact public.gin_trgm_ops) where deleted = false;
create index if not exists idx_review_records_search_spell_trgm on review_records using gin (search_spell public.gin_trgm_ops) where deleted = false;
create index if not exists idx_review_records_search_initials_trgm on review_records using gin (search_initials public.gin_trgm_ops) where deleted = false;
create index if not exists idx_review_records_title_search_text_trgm on review_records using gin (title_search_text public.gin_trgm_ops) where deleted = false;
create index if not exists idx_review_records_title_search_compact_trgm on review_records using gin (title_search_compact public.gin_trgm_ops) where deleted = false;
create index if not exists idx_review_records_title_search_spell_trgm on review_records using gin (title_search_spell public.gin_trgm_ops) where deleted = false;
create index if not exists idx_review_records_title_search_initials_trgm on review_records using gin (title_search_initials public.gin_trgm_ops) where deleted = false;
create index if not exists idx_review_records_search_missing on review_records(id) where deleted = false and search_text is null;
create index if not exists idx_review_records_title_search_missing on review_records(id) where deleted = false and title_search_text is null;
create index if not exists idx_review_record_experts_record on review_record_experts(review_record_id, deleted, sort_order);
create index if not exists idx_review_record_experts_name on review_record_experts(expert_name, deleted);
create index if not exists idx_review_record_experts_lower_name_record on review_record_experts(lower(coalesce(expert_name, '')), review_record_id) where deleted = false;
create index if not exists idx_review_problem_items_record on review_problem_items(review_record_id, deleted, problem_status, updated_at desc);
create index if not exists idx_review_problem_items_lower_status_record on review_problem_items(lower(coalesce(problem_status, '')), review_record_id) where deleted = false;
create index if not exists idx_review_problem_items_reviewer on review_problem_items(reviewer_name, deleted);
create index if not exists idx_code_review_external_metrics_context on code_review_external_metrics(project_id, merge_request_iid);
create index if not exists idx_issue_fact_context on issue_fact(source_system, source_instance, project_id, issue_iid);
create index if not exists idx_issue_fact_state on issue_fact(issue_state, severity_level, priority_level);
create index if not exists idx_issue_fact_module on issue_fact(module_name, testing_phase, bug_status);
create index if not exists idx_issue_fact_function on issue_fact(function_name, project_id);
create index if not exists idx_issue_fact_filters on issue_fact(project_id, severity_level, priority_level, is_excluded, is_fixed);
create index if not exists idx_issue_fact_legacy on issue_fact(issue_state, is_legacy, testing_phase);
create index if not exists idx_issue_fact_list_updated on issue_fact(deleted, updated_at_source desc, issue_iid desc);
create index if not exists idx_issue_fact_project_list_updated on issue_fact(project_id, deleted, updated_at_source desc, issue_iid desc);
create index if not exists idx_issue_fact_illegal_list_updated on issue_fact(is_illegal, is_excluded, deleted, updated_at_source desc, issue_iid desc);
create index if not exists idx_issue_fact_scope_project_name_trgm on issue_fact using gin (lower(coalesce(project_name, '')) public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_scope_milestone_trgm on issue_fact using gin (lower(coalesce(milestone_title, '')) public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_scope_label_trgm on issue_fact using gin (lower(coalesce(label_names, '')) public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_scope_testing_phase_trgm on issue_fact using gin (lower(coalesce(testing_phase, '')) public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_scope_system_label_trgm on issue_fact using gin (lower(coalesce(system_test_label, '')) public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_module_names_trgm on issue_fact using gin (lower(coalesce(module_names, '')) public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_search_text_trgm on issue_fact using gin (search_text public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_search_compact_trgm on issue_fact using gin (search_compact public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_search_spell_trgm on issue_fact using gin (search_spell public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_search_initials_trgm on issue_fact using gin (search_initials public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_title_search_text_trgm on issue_fact using gin (title_search_text public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_title_search_compact_trgm on issue_fact using gin (title_search_compact public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_title_search_spell_trgm on issue_fact using gin (title_search_spell public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_title_search_initials_trgm on issue_fact using gin (title_search_initials public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_module_search_text_trgm on issue_fact using gin (module_search_text public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_module_search_compact_trgm on issue_fact using gin (module_search_compact public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_module_search_spell_trgm on issue_fact using gin (module_search_spell public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_module_search_initials_trgm on issue_fact using gin (module_search_initials public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_milestone_search_text_trgm on issue_fact using gin (milestone_search_text public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_milestone_search_compact_trgm on issue_fact using gin (milestone_search_compact public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_milestone_search_spell_trgm on issue_fact using gin (milestone_search_spell public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_milestone_search_initials_trgm on issue_fact using gin (milestone_search_initials public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_author_search_text_trgm on issue_fact using gin (author_search_text public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_author_search_compact_trgm on issue_fact using gin (author_search_compact public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_author_search_spell_trgm on issue_fact using gin (author_search_spell public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_author_search_initials_trgm on issue_fact using gin (author_search_initials public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_assignee_search_text_trgm on issue_fact using gin (assignee_search_text public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_assignee_search_compact_trgm on issue_fact using gin (assignee_search_compact public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_assignee_search_spell_trgm on issue_fact using gin (assignee_search_spell public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_assignee_search_initials_trgm on issue_fact using gin (assignee_search_initials public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_phase_filter on issue_fact(phase_filter_value) where deleted = false;
create index if not exists idx_issue_fact_phase_search_text_trgm on issue_fact using gin (phase_search_text public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_phase_search_compact_trgm on issue_fact using gin (phase_search_compact public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_phase_search_spell_trgm on issue_fact using gin (phase_search_spell public.gin_trgm_ops) where deleted = false;
create index if not exists idx_issue_fact_phase_search_initials_trgm on issue_fact using gin (phase_search_initials public.gin_trgm_ops) where deleted = false;
create index if not exists idx_merge_request_fact_context on merge_request_fact(source_system, source_instance, project_id, merge_request_iid);
create index if not exists idx_merge_request_fact_owner on merge_request_fact(owner_name, module_name, merge_request_state);
create index if not exists idx_merge_request_fact_metrics on merge_request_fact(comment_rate, defect_count, review_duration_minutes);
create index if not exists idx_merge_request_fact_list_merged on merge_request_fact(deleted, merged_at_source desc, merge_request_iid desc);
create index if not exists idx_merge_request_fact_project_list_merged on merge_request_fact(project_id, deleted, merged_at_source desc, merge_request_iid desc);
create index if not exists idx_merge_request_fact_source_list_merged on merge_request_fact(source_instance, deleted, merged_at_source desc, merge_request_iid desc);
create index if not exists idx_merge_request_fact_repository_trgm on merge_request_fact using gin (repository_name public.gin_trgm_ops) where deleted = false;
create index if not exists idx_merge_request_fact_project_name_trgm on merge_request_fact using gin (project_name public.gin_trgm_ops) where deleted = false;
create index if not exists idx_merge_request_fact_target_branch_trgm on merge_request_fact using gin (target_branch public.gin_trgm_ops) where deleted = false;
create index if not exists idx_merge_request_fact_module_trgm on merge_request_fact using gin (module_name public.gin_trgm_ops) where deleted = false;
create index if not exists idx_merge_request_fact_search_text_trgm on merge_request_fact using gin (search_text public.gin_trgm_ops) where deleted = false;
create index if not exists idx_merge_request_fact_search_compact_trgm on merge_request_fact using gin (search_compact public.gin_trgm_ops) where deleted = false;
create index if not exists idx_merge_request_fact_search_spell_trgm on merge_request_fact using gin (search_spell public.gin_trgm_ops) where deleted = false;
create index if not exists idx_merge_request_fact_search_initials_trgm on merge_request_fact using gin (search_initials public.gin_trgm_ops) where deleted = false;
create index if not exists idx_merge_request_fact_owner_search_text_trgm on merge_request_fact using gin (owner_search_text public.gin_trgm_ops) where deleted = false;
create index if not exists idx_merge_request_fact_owner_search_compact_trgm on merge_request_fact using gin (owner_search_compact public.gin_trgm_ops) where deleted = false;
create index if not exists idx_merge_request_fact_owner_search_spell_trgm on merge_request_fact using gin (owner_search_spell public.gin_trgm_ops) where deleted = false;
create index if not exists idx_merge_request_fact_owner_search_initials_trgm on merge_request_fact using gin (owner_search_initials public.gin_trgm_ops) where deleted = false;
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
create index if not exists idx_gitlab_sync_jobs_dispatch on gitlab_sync_jobs(status, run_after, priority desc, created_at);
create index if not exists idx_gitlab_sync_jobs_config on gitlab_sync_jobs(config_id, job_type, created_at desc);
create index if not exists idx_gitlab_table_sync_states_dirty on gitlab_table_sync_states(config_id, dirty_flag, updated_at desc);
create index if not exists idx_gitlab_table_sync_states_table on gitlab_table_sync_states(config_id, source_table);
create index if not exists idx_gitlab_table_sync_tasks_dispatch on gitlab_table_sync_tasks(status, run_after, source_instance, created_at);
create index if not exists idx_gitlab_table_sync_tasks_table on gitlab_table_sync_tasks(config_id, source_table, status, created_at desc);
create index if not exists idx_gitlab_hook_events_status on gitlab_hook_events(config_id, status, received_at desc);
create unique index if not exists uk_gitlab_sync_configs_source_instance on gitlab_sync_configs(source_instance);
create unique index if not exists uk_gitlab_sync_configs_webhook_secret_enabled
    on gitlab_sync_configs(webhook_secret)
    where source_enabled = true
      and webhook_enabled = true
      and webhook_secret is not null
      and btrim(webhook_secret) <> '';
create index if not exists idx_fact_build_tasks_scope_status on fact_build_tasks(scope, status, created_at desc);
create index if not exists idx_fact_build_tasks_created_at on fact_build_tasks(created_at desc);
create index if not exists idx_fact_build_tasks_dispatch on fact_build_tasks(status, trigger_type, run_after, created_at);
create index if not exists idx_fact_build_tasks_source_fact on fact_build_tasks(config_id, source_instance, fact_type, status, created_at desc);
