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

alter table integration_test_fact add column if not exists parse_status varchar(32) not null default 'PARTIAL';
alter table integration_test_fact add column if not exists validation_reason varchar(255);
alter table sys_table_registry add column if not exists preview_enabled boolean not null default true;

create index if not exists idx_code_review_external_metrics_context on code_review_external_metrics(project_id, merge_request_iid);
create index if not exists idx_integration_test_fact_phase on integration_test_fact(project_id, testing_phase);
create index if not exists idx_integration_test_fact_module on integration_test_fact(project_id, testing_phase, module_name);
create index if not exists idx_testing_phase_calendar_context on testing_phase_calendar(project_id, testing_phase, enabled);
create unique index if not exists uk_module_dictionary_global on module_dictionary(dictionary_domain, alias_name) where project_id is null;
create unique index if not exists uk_module_dictionary_project on module_dictionary(dictionary_domain, project_id, alias_name) where project_id is not null;
create index if not exists idx_module_dictionary_context on module_dictionary(dictionary_domain, project_id, enabled, priority desc);
create index if not exists idx_sys_table_registry_config on sys_table_registry(config_id, source_table_name);
create index if not exists idx_sys_table_registry_preview on sys_table_registry(config_id, preview_enabled, source_table_name);
