create table if not exists gitlab_sync_configs (
    id bigserial primary key,
    name varchar(128) not null default 'default',
    enabled boolean not null default true,
    source_enabled boolean not null default true,
    source_instance varchar(128) not null default 'default',
    web_base_url varchar(255),
    api_token varchar(255),
    delay_label_writeback_enabled boolean not null default false,
    match_mode_enabled boolean not null default true,
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
    system_hook_secret varchar(255),
    system_hook_enabled boolean not null default false,
    system_hook_project_id bigint,
    compensation_interval_minutes integer not null default 360,
    compensation_schedule_mode varchar(32) not null default 'INTERVAL',
    compensation_time varchar(5) not null default '03:30',
    compensation_window_start varchar(5),
    compensation_window_end varchar(5),
    compensation_missed_window_policy varchar(32) not null default 'SKIP',
    full_compensation_enabled boolean not null default true,
    full_compensation_time varchar(5) not null default '02:00',
    sync_thread_mode varchar(32) not null default 'FIXED',
    sync_thread_value numeric(8, 3) not null default 2,
    max_sync_threads integer,
    last_full_sync_at timestamp,
    last_incremental_sync_at timestamp,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);






create table if not exists gitlab_system_hook_events (
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

create table if not exists sync_runs (
    id bigserial primary key,
    run_id varchar(64) not null unique,
    config_id bigint not null references gitlab_sync_configs(id) on delete cascade,
    source_instance varchar(128) not null,
    run_type varchar(64) not null,
    trigger_type varchar(32) not null,
    status varchar(32) not null,
    priority integer not null default 0,
    exclusive_scope varchar(255) not null,
    cancel_requested boolean not null default false,
    submitted_by varchar(128),
    request_reason text,
    payload_json text,
    thread_mode varchar(32) not null default 'FIXED',
    thread_value numeric(8, 3) not null default 2,
    resolved_worker_count integer not null default 2 check (resolved_worker_count > 0),
    planned_table_count integer not null default 0,
    completed_table_count integer not null default 0,
    scanned_rows bigint not null default 0,
    applied_rows bigint not null default 0,
    heartbeat_at timestamp,
    lease_owner varchar(128),
    lease_until timestamp,
    parent_run_id bigint references sync_runs(id) on delete set null,
    started_at timestamp,
    finished_at timestamp,
    error_message text,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create table if not exists sync_run_table_states (
    id bigserial primary key,
    config_id bigint not null references gitlab_sync_configs(id) on delete cascade,
    source_instance varchar(128) not null,
    source_table varchar(255) not null,
    mirror_table varchar(255) not null,
    primary_key_columns text not null,
    updated_at_column varchar(255),
    row_strategy varchar(32) not null default 'INCREMENTAL',
    cursor_strategy varchar(32) not null default 'NONE'
        check (cursor_strategy in ('TIMESTAMP_KEYSET', 'PRIMARY_KEY_KEYSET', 'NONE')),
    sync_enabled boolean not null default true,
    dirty_flag boolean not null default false,
    dirty_reason text,
    last_success_at timestamp,
    last_full_verified_at timestamp,
    last_watermark_at timestamp,
    last_cursor_pk text,
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

create table if not exists sync_run_table_tasks (
    id bigserial primary key,
    run_id bigint not null references sync_runs(id) on delete cascade,
    config_id bigint not null references gitlab_sync_configs(id) on delete cascade,
    state_id bigint references sync_run_table_states(id) on delete set null,
    source_instance varchar(128) not null,
    source_table varchar(255) not null,
    mirror_table varchar(255) not null,
    task_type varchar(64) not null,
    status varchar(32) not null,
    row_strategy varchar(32) not null,
    task_stage varchar(32) not null default 'SCAN' check (task_stage in ('SCAN', 'RECONCILE')),
    parent_task_id bigint references sync_run_table_tasks(id) on delete set null,
    watermark_at timestamp,
    cursor_updated_at timestamp,
    cursor_pk text,
    scan_upper_bound_at timestamp,
    page_number integer not null default 1 check (page_number > 0),
    lookup_column varchar(255),
    lookup_value text,
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

create table if not exists sync_run_events (
    id bigserial primary key,
    run_id bigint references sync_runs(id) on delete cascade,
    config_id bigint references gitlab_sync_configs(id) on delete cascade,
    source_instance varchar(128),
    event_type varchar(64) not null,
    table_name varchar(255),
    message text,
    payload_json text,
    created_at timestamp not null default current_timestamp
);

create table if not exists sync_worker_leases (
    id bigserial primary key,
    worker_id varchar(128) not null unique,
    worker_type varchar(64) not null,
    hostname varchar(255),
    thread_mode varchar(32) not null default 'FIXED',
    thread_value numeric(8, 3) not null default 2,
    max_threads integer not null default 1,
    active_threads integer not null default 0,
    queue_depth integer not null default 0,
    lease_until timestamp,
    heartbeat_at timestamp,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
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
    form_title varchar(255) not null default '閲囬泦琛ㄥ崟',
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
    not_reach_standard_reason text,
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
    problem_status varchar(128) not null default 'NEW',
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
    scan_status varchar(128),
    scan_bug_count integer,
    annotation_rate_result varchar(128),
    bug_count_result varchar(128),
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
    source_summary varchar(255),
    raw_payload text,
    imported_at timestamp not null default current_timestamp,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    unique (project_id, merge_request_iid)
);

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
    legacy_merged_time_source timestamp,
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
    legacy_source_id varchar(128),
    synced_at timestamp not null default current_timestamp
);

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

create table if not exists code_review_match_mode_db_settings (
    id smallint primary key default 1,
    enabled boolean not null default true,
    sync_enabled boolean not null default true,
    mysql_host varchar(255) not null default '172.22.10.72',
    mysql_port integer not null default 3306,
    mysql_database varchar(255) not null default 'gitlab_spider',
    dgm_mysql_database varchar(255) not null default 'gitlab_spider_dgm',
    mysql_username varchar(255) not null default 'root',
    mysql_password varchar(255) not null default '',
    mysql_table_name varchar(255) not null default 'spider_crowncad_data',
    legacy_api_base_url varchar(512) not null default 'http://172.22.10.72:8091',
    dgm_legacy_api_base_url varchar(512) not null default '',
    selected_table_names text not null default 'spider_crowncad_data',
    mysql_fetch_size integer not null default 1000,
    mongo_uri text default 'mongodb://172.22.10.72/?waitQueueMultiple=20',
    mongo_database varchar(255) not null default 'spider',
    selected_mongo_collection_names text not null default 'reviewReport,problemDetail',
    review_report_collection_name varchar(255) not null default 'reviewReport',
    review_problem_collection_name varchar(255) not null default 'problemDetail',
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint ck_code_review_match_mode_db_settings_singleton check (id = 1),
    constraint ck_code_review_match_mode_db_settings_mysql_port check (mysql_port between 1 and 65535),
    constraint ck_code_review_match_mode_db_settings_fetch_size check (mysql_fetch_size between 1 and 100000)
);

insert into code_review_match_mode_sync_state(id, status, message)
values (1, 'IDLE', '兼容模式尚未同步')
on conflict (id) do nothing;

insert into code_review_match_mode_db_settings(id, enabled, sync_enabled)
values (1, true, true)
on conflict (id) do nothing;

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

create table if not exists legacy_mysql_imported_tables (
    id bigserial primary key,
    table_name varchar(255) not null,
    record_count bigint not null default 0,
    last_synced_at timestamp,
    column_names text,
    synced_at timestamp not null default current_timestamp,
    unique (table_name)
);

create table if not exists legacy_mysql_imported_rows (
    id bigserial primary key,
    table_name varchar(255) not null,
    row_key varchar(512) not null,
    raw_payload jsonb not null,
    synced_at timestamp not null default current_timestamp,
    unique (table_name, row_key)
);

create table if not exists legacy_mongo_imported_collections (
    id bigserial primary key,
    collection_name varchar(255) not null,
    record_count bigint not null default 0,
    last_synced_at timestamp,
    synced_at timestamp not null default current_timestamp,
    unique (collection_name)
);

create table if not exists legacy_mongo_imported_documents (
    id bigserial primary key,
    collection_name varchar(255) not null,
    document_key varchar(512) not null,
    raw_payload jsonb not null,
    synced_at timestamp not null default current_timestamp,
    unique (collection_name, document_key)
);

create table if not exists review_data_match_mode_reports (
    id bigserial primary key,
    legacy_id varchar(128) not null,
    project_name varchar(255),
    title varchar(512),
    module_name varchar(255),
    source_type varchar(128),
    doc_type varchar(128),
    review_type_str varchar(255),
    review_time timestamp,
    review_charger varchar(128),
    review_experts text,
    defect_value integer,
    defect_count_sum integer,
    review_defect_density numeric(10, 2),
    weighted_defect_density numeric(10, 2),
    review_efficiency numeric(10, 2),
    review_rate numeric(10, 2),
    doc_specification integer,
    integrity integer,
    functionality integer,
    feasibility integer,
    not_reach_stand_cause text,
    problem_detail_ids text,
    description_ids text,
    content_ids text,
    create_time timestamp,
    raw_payload jsonb,
    synced_at timestamp not null default current_timestamp,
    unique (legacy_id)
);

create table if not exists review_data_match_mode_problem_details (
    id bigserial primary key,
    legacy_id varchar(128) not null,
    reviewer varchar(128),
    workload numeric(8, 2),
    review_type varchar(128),
    position varchar(255),
    problem_type varchar(128),
    description text,
    suggestion text,
    liable_person varchar(128),
    reason_for_not_accepting text,
    problem_status varchar(128),
    close_time date,
    create_time timestamp,
    update_time date,
    raw_payload jsonb,
    synced_at timestamp not null default current_timestamp,
    unique (legacy_id)
);

create table if not exists review_data_match_mode_descriptions (
    id bigserial primary key,
    legacy_id varchar(128) not null,
    review_product varchar(512),
    version varchar(128),
    author varchar(128),
    review_scale_pages integer,
    unit varchar(64),
    raw_payload jsonb,
    synced_at timestamp not null default current_timestamp,
    unique (legacy_id)
);

create table if not exists review_data_match_mode_edit_links (
    id bigserial primary key,
    match_mode_report_id bigint not null,
    match_mode_report_legacy_id varchar(128) not null,
    review_record_id bigint not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    unique (match_mode_report_id),
    unique (match_mode_report_legacy_id),
    unique (review_record_id)
);

create table if not exists review_data_match_mode_problem_edit_links (
    id bigserial primary key,
    match_mode_problem_id bigint not null,
    match_mode_problem_legacy_id varchar(128) not null,
    review_record_id bigint not null,
    review_problem_item_id bigint not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    unique (match_mode_problem_id),
    unique (match_mode_problem_legacy_id),
    unique (review_problem_item_id)
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
    handler_name text,
    assignee_name text,
    fix_user varchar(128),
    created_at_source timestamp,
    updated_at_source timestamp,
    ods_updated_at timestamp,
    closed_at_source timestamp,
    module_name varchar(255),
    primary_module_name varchar(255),
    module_names text,
    function_name varchar(255),
    customer_names text,
    testing_phase varchar(128),
    severity_level varchar(128),
    severity_alias varchar(128),
    priority_level varchar(64),
    urgency varchar(64),
    bug_status varchar(128),
    category varchar(255),
    reason_category text,
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
     illegal_reasons text,
     has_response boolean not null default false,
    research_template_time timestamp,
    fixed_label_time timestamp,
    response_overdue boolean not null default false,
    is_response_delayed boolean not null default false,
    resolve_sla_days integer not null default 18,
    resolve_deadline_at timestamp,
    planned_resolution_at timestamp,
    planned_resolution_text text,
    planned_merge_version_branch text,
    is_resolve_delayed boolean not null default false,
    is_legacy boolean not null default false,
    deleted boolean not null default false,
    fact_refreshed_at timestamp not null default current_timestamp,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    unique (source_system, source_instance, project_id, issue_id)
);

create table if not exists issue_customer_name_aliases (
    alias_name varchar(255) primary key,
    canonical_name varchar(255) not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    check (nullif(btrim(alias_name), '') is not null),
    check (nullif(btrim(canonical_name), '') is not null)
);

insert into issue_customer_name_aliases(alias_name, canonical_name)
values
    ('新世纪', '郑州新世纪'),
    ('极目数字（苏普耐）', '极目数字'),
    ('极目数字(苏普耐)', '极目数字'),
    ('极目数字（苏普耐）——新版本适配测试', '极目数字'),
    ('极目数字(苏普耐)——新版本适配测试', '极目数字')
on conflict (alias_name)
do update set
    canonical_name = excluded.canonical_name,
    updated_at = current_timestamp;

create table if not exists issue_fact_customer_members (
    source_system varchar(64) not null,
    source_instance varchar(128) not null,
    project_id bigint not null,
    issue_id bigint not null,
    customer_name varchar(255) not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    primary key (source_system, source_instance, project_id, issue_id, customer_name),
    check (nullif(btrim(customer_name), '') is not null)
);

create index if not exists idx_issue_fact_customer_members_customer_lookup
    on issue_fact_customer_members(lower(customer_name), source_instance, project_id, issue_id);

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
    on integration_test_fact(source_instance, project_id, testing_phase)
    where deleted = false;

create index if not exists idx_integration_test_fact_module
    on integration_test_fact(source_instance, project_id, testing_phase, module_name)
    where deleted = false;

create index if not exists idx_integration_test_fact_issue
    on integration_test_fact(source_instance, project_id, issue_iid);

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
    review_exception_reason varchar(128),
    code_walkthrough_date timestamp,
    comment_rate numeric(8, 2),
    comment_rate_source varchar(64),
    defect_count integer,
    defect_count_source varchar(64),
    scan_status varchar(128),
    scan_bug_count integer,
    annotation_rate_result varchar(128),
    bug_count_result varchar(128),
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
    deleted boolean not null default false,
    fact_refreshed_at timestamp not null default current_timestamp,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    unique (source_system, source_instance, project_id, merge_request_id)
);

create table if not exists issue_scope_catalogs (
    id bigserial primary key,
    project_id bigint not null,
    project_name varchar(255) not null,
    dimension varchar(32) not null,
    enabled boolean not null default true,
    remark varchar(255),
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint ck_issue_scope_catalogs_dimension check (dimension in ('TESTING_PHASE', 'MILESTONE')),
    constraint uk_issue_scope_catalogs_project_dimension unique (project_id, dimension)
);

create table if not exists issue_scope_groups (
    id bigserial primary key,
    catalog_id bigint not null references issue_scope_catalogs(id) on delete cascade,
    business_key varchar(128) not null,
    display_name varchar(128) not null,
    sort_order integer not null default 0,
    enabled boolean not null default true,
    remark varchar(255),
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_issue_scope_groups_catalog_business_key unique (catalog_id, business_key),
    constraint uk_issue_scope_groups_id_catalog unique (id, catalog_id)
);

create table if not exists issue_scope_members (
    id bigserial primary key,
    catalog_id bigint not null references issue_scope_catalogs(id) on delete cascade,
    group_id bigint not null,
    source_value varchar(255) not null,
    display_name varchar(255) not null,
    sort_order integer not null default 0,
    active_from timestamp,
    active_until timestamp,
    enabled boolean not null default true,
    source_reference_id bigint,
    remark varchar(255),
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_issue_scope_members_group_catalog foreign key (group_id, catalog_id)
        references issue_scope_groups(id, catalog_id) on delete cascade,
    constraint uk_issue_scope_members_catalog_source_value unique (catalog_id, source_value)
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

create table if not exists label_groups (
    id bigserial primary key,
    name varchar(100) not null,
    value_type varchar(32),
    group_type varchar(16) not null default 'STATIC',
    applicable_scope varchar(16) not null default 'SAME_TYPE',
    source_field_key varchar(100),
    description varchar(500),
    enabled boolean not null default true,
    created_by varchar(100),
    created_at timestamptz not null default now(),
    updated_by varchar(100),
    updated_at timestamptz not null default now(),
    constraint ck_label_groups_type check (group_type in ('STATIC', 'DYNAMIC', 'COMPOSITE')),
    constraint ck_label_groups_scope check (applicable_scope in ('SAME_TYPE', 'SAME_FIELD')),
    constraint uk_label_groups_name unique (name)
);

create table if not exists label_group_members (
    id bigserial primary key,
    group_id bigint not null references label_groups(id) on delete cascade,
    member_value text not null,
    display_name varchar(255) not null,
    sort_order integer not null default 0,
    created_at timestamptz not null default now(),
    constraint uk_label_group_members_value unique (group_id, member_value)
);

create table if not exists label_group_references (
    id bigserial primary key,
    parent_group_id bigint not null references label_groups(id) on delete cascade,
    child_group_id bigint not null references label_groups(id) on delete restrict,
    sort_order integer not null default 0,
    created_at timestamptz not null default now(),
    constraint ck_label_group_references_not_self check (parent_group_id <> child_group_id),
    constraint uk_label_group_references_child unique (parent_group_id, child_group_id)
);

create table if not exists label_group_dynamic_rules (
    id bigserial primary key,
    group_id bigint not null references label_groups(id) on delete cascade,
    rule_template_key varchar(100) not null,
    rule_params_json text not null,
    output_value_type varchar(32),
    last_status varchar(32),
    last_error varchar(500),
    last_computed_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uk_label_group_dynamic_rules_group unique (group_id)
);

create table if not exists statistic_board_snapshots (
    id bigserial primary key,
    board_key varchar(128) not null,
    scope_key varchar(512) not null,
    rule_version varchar(128) not null,
    source_version varchar(128),
    filter_hash varchar(64) not null,
    filter_payload jsonb not null default '{}'::jsonb,
    applied_filter_payload jsonb not null default '{}'::jsonb,
    definition_payload jsonb,
    row_payload jsonb not null default '[]'::jsonb,
    meta_payload jsonb not null default '{}'::jsonb,
    status varchar(32) not null default 'READY',
    error_message text,
    generated_at timestamp not null default current_timestamp,
    refreshed_at timestamp not null default current_timestamp,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_statistic_board_snapshots_scope unique (board_key, scope_key, rule_version, filter_hash)
);

create table if not exists page_record_snapshots (
    id bigserial primary key,
    page_key varchar(128) not null,
    snapshot_type varchar(64) not null,
    scope_key varchar(512) not null,
    rule_version varchar(128) not null,
    source_version varchar(256),
    request_hash varchar(64) not null,
    request_payload jsonb not null default '{}'::jsonb,
    response_payload jsonb not null default '{}'::jsonb,
    status varchar(32) not null default 'READY',
    error_message text,
    generated_at timestamp not null default current_timestamp,
    refreshed_at timestamp not null default current_timestamp,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_page_record_snapshots_scope
        unique (page_key, snapshot_type, scope_key, rule_version, request_hash)
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
alter table gitlab_sync_configs add column if not exists web_base_url varchar(255);
alter table gitlab_sync_configs add column if not exists api_token varchar(255);
alter table gitlab_sync_configs add column if not exists delay_label_writeback_enabled boolean not null default false;
alter table gitlab_sync_configs add column if not exists match_mode_enabled boolean not null default true;
alter table gitlab_sync_configs add column if not exists source_enabled boolean not null default true;
alter table gitlab_sync_configs add column if not exists docker_container_name varchar(255);
alter table gitlab_sync_configs add column if not exists system_hook_enabled boolean not null default false;
alter table gitlab_sync_configs add column if not exists sync_thread_mode varchar(32) not null default 'FIXED';
alter table gitlab_sync_configs add column if not exists sync_thread_value numeric(8, 3) not null default 2;
alter table gitlab_sync_configs add column if not exists max_sync_threads integer;
alter table sync_runs add column if not exists resolved_worker_count integer not null default 2;
alter table sync_run_table_tasks add column if not exists task_stage varchar(32) not null default 'SCAN';
alter table sync_run_table_tasks add column if not exists parent_task_id bigint references sync_run_table_tasks(id) on delete set null;
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

create table if not exists customer_issue_delay_label_writeback_jobs (
    id bigserial primary key,
    source_instance varchar(128) not null,
    project_id bigint not null,
    issue_iid bigint not null,
    issue_id bigint,
    desired_response_delayed boolean not null default false,
    desired_resolve_delayed boolean not null default false,
    current_label_names text,
    add_labels text,
    remove_labels text,
    status varchar(32) not null default 'PENDING',
    attempt_count integer not null default 0,
    max_attempts integer not null default 10,
    next_run_at timestamp not null default current_timestamp,
    lease_owner varchar(128),
    lease_until timestamp,
    last_error text,
    last_http_status integer,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    finished_at timestamp,
    unique (source_instance, project_id, issue_iid)
);

create index if not exists idx_customer_issue_delay_label_jobs_status_next_run
    on customer_issue_delay_label_writeback_jobs(status, next_run_at, id);

create index if not exists idx_customer_issue_delay_label_jobs_issue
    on customer_issue_delay_label_writeback_jobs(project_id, issue_iid);
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
alter table issue_fact add column if not exists fix_user varchar(128);
alter table issue_fact add column if not exists primary_module_name varchar(255);
alter table issue_fact add column if not exists module_names text;
alter table issue_fact add column if not exists function_name varchar(255);
alter table merge_request_fact add column if not exists ods_updated_at timestamp;
alter table issue_fact add column if not exists severity_alias varchar(128);
alter table issue_fact add column if not exists priority_level varchar(64);
alter table issue_fact add column if not exists reason_category text;
alter table issue_fact add column if not exists is_excluded boolean not null default false;
alter table issue_fact add column if not exists exclusion_reason varchar(255);
alter table issue_fact add column if not exists is_fixed boolean not null default false;
alter table issue_fact add column if not exists delay_reason varchar(255);
alter table issue_fact add column if not exists is_regression boolean not null default false;
alter table issue_fact add column if not exists is_crash boolean not null default false;
alter table issue_fact add column if not exists is_level1_other boolean not null default false;
alter table issue_fact add column if not exists is_illegal boolean not null default false;
alter table issue_fact add column if not exists illegal_reason varchar(255);
alter table issue_fact add column if not exists illegal_reasons text;
alter table issue_fact add column if not exists has_response boolean not null default false;
alter table issue_fact add column if not exists research_template_time timestamp;
alter table issue_fact add column if not exists fixed_label_time timestamp;
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
create index if not exists idx_gitlab_mirror_records_table_updated on gitlab_mirror_records(config_id, table_name, updated_at_source);
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
create index if not exists idx_review_records_gitlab_context on review_records(gitlab_project_id, gitlab_resource_type, gitlab_resource_iid) where deleted = false and gitlab_project_id is not null and gitlab_resource_iid is not null;
create index if not exists idx_review_record_experts_record on review_record_experts(review_record_id, deleted, sort_order);
create index if not exists idx_review_record_experts_name on review_record_experts(expert_name, deleted);
create index if not exists idx_review_record_experts_lower_name_record on review_record_experts(lower(coalesce(expert_name, '')), review_record_id) where deleted = false;
create index if not exists idx_review_problem_items_record on review_problem_items(review_record_id, deleted, problem_status, updated_at desc);
create index if not exists idx_review_problem_items_lower_status_record on review_problem_items(lower(coalesce(problem_status, '')), review_record_id) where deleted = false;
create index if not exists idx_review_problem_items_reviewer on review_problem_items(reviewer_name, deleted);
create index if not exists idx_code_review_external_metrics_context on code_review_external_metrics(project_id, merge_request_iid);
create index if not exists idx_code_review_match_mode_records_query
    on code_review_match_mode_records(source_instance, merged_at_source desc, merge_request_iid desc);
create index if not exists idx_code_review_match_mode_records_project
    on code_review_match_mode_records(project_name, target_branch, module_name);
create index if not exists idx_code_review_match_mode_records_scope_repository_merged
    on code_review_match_mode_records(
        lower(coalesce(source_instance, 'default')),
        lower(coalesce(repository_name, '')),
        lower(coalesce(project_name, '')),
        merged_at_source desc,
        merge_request_iid desc)
    where upper(coalesce(merge_request_state, '')) = 'MERGED';
create index if not exists idx_code_review_match_mode_records_scope_fields
    on code_review_match_mode_records(
        lower(coalesce(source_instance, 'default')),
        lower(coalesce(target_branch, '')),
        lower(coalesce(module_name, '')),
        lower(coalesce(author_name, '')),
        lower(coalesce(merge_user_name, '')))
    where upper(coalesce(merge_request_state, '')) = 'MERGED';
create index if not exists idx_review_match_mode_reports_query
    on review_data_match_mode_reports(project_name, module_name, review_time desc, id desc);
create index if not exists idx_review_match_mode_problem_legacy
    on review_data_match_mode_problem_details(legacy_id);
create index if not exists idx_review_match_mode_description_legacy
    on review_data_match_mode_descriptions(legacy_id);
create index if not exists idx_review_match_mode_edit_links_record
    on review_data_match_mode_edit_links(review_record_id);
create index if not exists idx_review_match_mode_problem_edit_links_record
    on review_data_match_mode_problem_edit_links(review_record_id);
create index if not exists idx_legacy_mysql_imported_rows_table
    on legacy_mysql_imported_rows(table_name, id);
create index if not exists idx_legacy_mongo_imported_documents_collection
    on legacy_mongo_imported_documents(collection_name, id);
create index if not exists idx_issue_fact_context on issue_fact(source_system, source_instance, project_id, issue_iid);
create index if not exists idx_issue_fact_state on issue_fact(issue_state, severity_level, priority_level);
create index if not exists idx_issue_fact_module on issue_fact(module_name, testing_phase, bug_status);
create index if not exists idx_issue_fact_function on issue_fact(function_name, project_id);
create index if not exists idx_issue_fact_filters on issue_fact(project_id, severity_level, priority_level, is_excluded, is_fixed);
create index if not exists idx_issue_fact_legacy on issue_fact(issue_state, is_legacy, testing_phase);
create index if not exists idx_issue_fact_list_updated on issue_fact(deleted, updated_at_source desc, issue_iid desc);
create index if not exists idx_issue_fact_project_list_updated on issue_fact(project_id, deleted, updated_at_source desc, issue_iid desc);
create index if not exists idx_issue_fact_illegal_list_updated on issue_fact(is_illegal, is_excluded, deleted, updated_at_source desc, issue_iid desc);
create index if not exists idx_issue_fact_customer_project_updated
    on issue_fact(project_id, updated_at_source desc, issue_iid desc)
    where deleted = false;
create index if not exists idx_issue_fact_customer_project_created_updated
    on issue_fact(project_id, created_at_source, updated_at_source desc, issue_iid desc)
    where deleted = false;
create index if not exists idx_issue_fact_customer_source_project_updated
    on issue_fact(
        lower(coalesce(source_instance, 'default')),
        project_id,
        updated_at_source desc,
        issue_iid desc)
    where deleted = false;
create index if not exists idx_issue_fact_customer_illegal_project_created_updated
    on issue_fact(project_id, created_at_source, updated_at_source desc, issue_iid desc)
    where deleted = false
      and is_illegal = true
      and is_excluded = false;
create index if not exists idx_issue_fact_customer_illegal_reasons_trgm
    on issue_fact using gin (
        lower(',' || replace(coalesce(nullif(illegal_reasons, ''), illegal_reason, ''), ', ', ',') || ',') public.gin_trgm_ops)
    where deleted = false
      and is_illegal = true
      and is_excluded = false;
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
create index if not exists idx_issue_fact_active_testing_phase on issue_fact(testing_phase) where deleted = false;
create index if not exists idx_issue_fact_active_reason_category on issue_fact(reason_category) where deleted = false;
create index if not exists idx_issue_fact_active_phase_severity_excluded on issue_fact(testing_phase, severity_level, is_excluded) where deleted = false;
create index if not exists idx_issue_fact_active_phase_reason_severity on issue_fact(testing_phase, reason_category, severity_level) where deleted = false;
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
create index if not exists idx_issue_scope_catalogs_enabled on issue_scope_catalogs(enabled, project_id, dimension);
create index if not exists idx_issue_scope_groups_catalog_order on issue_scope_groups(catalog_id, enabled, sort_order, id);
create index if not exists idx_issue_scope_members_group_order on issue_scope_members(group_id, enabled, sort_order, id);
create unique index if not exists uk_module_dictionary_global on module_dictionary(dictionary_domain, alias_name) where project_id is null;
create unique index if not exists uk_module_dictionary_project on module_dictionary(dictionary_domain, project_id, alias_name) where project_id is not null;
create index if not exists idx_module_dictionary_context on module_dictionary(dictionary_domain, project_id, enabled, priority desc);
create index if not exists idx_label_group_members_group on label_group_members(group_id);
create index if not exists idx_label_group_references_parent on label_group_references(parent_group_id);
create index if not exists idx_label_group_references_child on label_group_references(child_group_id);
create index if not exists idx_sys_table_registry_config on sys_table_registry(config_id, source_table_name);
create index if not exists idx_sys_table_registry_preview on sys_table_registry(config_id, preview_enabled, source_table_name);
create index if not exists idx_gitlab_hook_events_status on gitlab_hook_events(config_id, status, received_at desc);
create index if not exists idx_sync_runs_dispatch
    on sync_runs(status, priority desc, updated_at, created_at, id)
    where status in ('QUEUED', 'PAUSED');
create index if not exists idx_sync_runs_config_source_status on sync_runs(config_id, source_instance, status);
create index if not exists idx_sync_runs_scope_status on sync_runs(exclusive_scope, status);
create index if not exists idx_sync_runs_parent on sync_runs(parent_run_id, run_type, status);
create index if not exists idx_sync_run_table_states_dirty on sync_run_table_states(config_id, dirty_flag, updated_at desc);
create index if not exists idx_sync_run_table_states_table on sync_run_table_states(config_id, source_instance, source_table);
create index if not exists idx_sync_run_table_tasks_dispatch on sync_run_table_tasks(status, run_after, source_instance, created_at);
create index if not exists idx_sync_run_table_tasks_run on sync_run_table_tasks(run_id, status, created_at);
create index if not exists idx_sync_run_table_tasks_table on sync_run_table_tasks(config_id, source_instance, source_table, status, created_at desc);
create index if not exists idx_sync_run_table_tasks_parent
    on sync_run_table_tasks(parent_task_id)
    where parent_task_id is not null;
create index if not exists idx_sync_run_table_tasks_page
    on sync_run_table_tasks(run_id, source_table, page_number);
create index if not exists idx_statistic_board_snapshots_lookup
    on statistic_board_snapshots(board_key, scope_key, rule_version, source_version, filter_hash, status);
create index if not exists idx_statistic_board_snapshots_refreshed
    on statistic_board_snapshots(board_key, refreshed_at desc);
create index if not exists idx_page_record_snapshots_lookup
    on page_record_snapshots(page_key, snapshot_type, scope_key, rule_version, source_version, request_hash, status);
create index if not exists idx_page_record_snapshots_refreshed
    on page_record_snapshots(page_key, refreshed_at desc);
create index if not exists idx_issue_fact_customer_response_times
    on issue_fact(project_id, milestone_title, research_template_time, fixed_label_time)
    where project_id = 325 and deleted = false;
create index if not exists idx_sync_run_events_run on sync_run_events(run_id, created_at desc);
create index if not exists idx_sync_worker_leases_heartbeat on sync_worker_leases(worker_type, heartbeat_at desc);
create unique index if not exists uk_gitlab_sync_configs_source_instance on gitlab_sync_configs(source_instance);
create unique index if not exists uk_gitlab_sync_configs_system_hook_secret_enabled
    on gitlab_sync_configs(system_hook_secret)
    where source_enabled = true
      and system_hook_enabled = true
      and system_hook_secret is not null
      and btrim(system_hook_secret) <> '';
create index if not exists idx_fact_build_tasks_scope_status on fact_build_tasks(scope, status, created_at desc);
create index if not exists idx_fact_build_tasks_created_at on fact_build_tasks(created_at desc);
create index if not exists idx_fact_build_tasks_dispatch on fact_build_tasks(status, trigger_type, run_after, created_at);
create index if not exists idx_fact_build_tasks_source_fact on fact_build_tasks(config_id, source_instance, fact_type, status, created_at desc);

-- Align CC_Product customer issue scope with the legacy platform:
-- customer issue statistics do not exclude suggestion issues. Only closed
-- 申请否决 / 需求如此 / 设计如此 issues are excluded.
update issue_fact
   set is_excluded = case
         when (lower(coalesce(issue_state, '')) = 'closed' or closed_at_source is not null)
              and (
                coalesce(label_names, '') like '%申请否决%'
                or coalesce(bug_status, '') like '%申请否决%'
              )
           then true
         when (lower(coalesce(issue_state, '')) = 'closed' or closed_at_source is not null)
              and (
                coalesce(label_names, '') like '%需求如此%'
                or coalesce(bug_status, '') like '%需求如此%'
              )
           then true
         when (lower(coalesce(issue_state, '')) = 'closed' or closed_at_source is not null)
              and (
                coalesce(label_names, '') like '%设计如此%'
                or coalesce(bug_status, '') like '%设计如此%'
              )
           then true
         else false
       end,
       exclusion_reason = case
         when (lower(coalesce(issue_state, '')) = 'closed' or closed_at_source is not null)
              and (
                coalesce(label_names, '') like '%申请否决%'
                or coalesce(bug_status, '') like '%申请否决%'
              )
           then '申请否决+Closed'
         when (lower(coalesce(issue_state, '')) = 'closed' or closed_at_source is not null)
              and (
                coalesce(label_names, '') like '%需求如此%'
                or coalesce(bug_status, '') like '%需求如此%'
              )
           then '需求如此+Closed'
         when (lower(coalesce(issue_state, '')) = 'closed' or closed_at_source is not null)
              and (
                coalesce(label_names, '') like '%设计如此%'
                or coalesce(bug_status, '') like '%设计如此%'
              )
           then '设计如此+Closed'
         else null
       end,
       updated_at = current_timestamp
 where project_id = 325
   and deleted = false;
