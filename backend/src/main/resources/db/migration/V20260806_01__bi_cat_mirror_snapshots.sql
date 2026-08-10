create table bi_cat_mirror_configs (
    id smallint primary key,
    enabled boolean not null default false,
    base_url varchar(2048) not null default 'http://172.22.10.56:88',
    auto_sync_enabled boolean not null default false,
    sync_interval_minutes integer not null default 20,
    full_compensation_enabled boolean not null default true,
    full_compensation_time time not null default '02:00',
    connect_timeout_ms integer not null default 3000,
    read_timeout_ms integer not null default 15000,
    max_response_bytes integer not null default 5242880,
    retained_snapshot_count integer not null default 10,
    updated_at timestamp with time zone not null default current_timestamp,
    constraint ck_bi_cat_mirror_config_singleton check (id = 1),
    constraint ck_bi_cat_mirror_sync_interval
        check (sync_interval_minutes between 5 and 10080),
    constraint ck_bi_cat_mirror_connect_timeout
        check (connect_timeout_ms between 1 and 120000),
    constraint ck_bi_cat_mirror_read_timeout
        check (read_timeout_ms between 1 and 120000),
    constraint ck_bi_cat_mirror_response_limit
        check (max_response_bytes between 1 and 52428800),
    constraint ck_bi_cat_mirror_retention
        check (retained_snapshot_count between 2 and 100)
);

insert into bi_cat_mirror_configs(id)
values (1);

create table bi_cat_scope_mappings (
    product_version_id bigint primary key,
    product_version_key varchar(128) not null,
    cat_project_id varchar(128) not null,
    cat_version_id varchar(128) not null,
    unit_testing_phase_id varchar(128),
    integration_testing_phase_id varchar(128),
    updated_at timestamp with time zone not null default current_timestamp,
    constraint uk_bi_cat_scope_mapping_key unique (product_version_key),
    constraint ck_bi_cat_scope_mapping_product_id check (product_version_id > 0),
    constraint ck_bi_cat_scope_mapping_stage
        check (unit_testing_phase_id is not null or integration_testing_phase_id is not null)
);

create table bi_cat_sync_runs (
    run_id uuid primary key,
    run_type varchar(32) not null,
    trigger_type varchar(32) not null,
    status varchar(32) not null,
    started_at timestamp with time zone not null,
    finished_at timestamp with time zone,
    catalog_snapshot_id uuid,
    published_stage_count integer not null default 0,
    failed_stage_count integer not null default 0,
    message text not null default '',
    constraint ck_bi_cat_sync_run_type
        check (run_type in ('FULL', 'FULL_COMPENSATION')),
    constraint ck_bi_cat_sync_trigger_type
        check (trigger_type in ('MANUAL', 'SCHEDULED')),
    constraint ck_bi_cat_sync_status
        check (status in ('RUNNING', 'SUCCEEDED', 'PARTIAL_SUCCESS', 'FAILED')),
    constraint ck_bi_cat_sync_counts
        check (published_stage_count >= 0 and failed_stage_count >= 0)
);

create index idx_bi_cat_sync_runs_started
    on bi_cat_sync_runs(started_at desc);

create table bi_cat_sync_state (
    id smallint primary key,
    active_run_id uuid,
    lease_expires_at timestamp with time zone,
    next_scheduled_at timestamp with time zone,
    last_run_id uuid,
    last_compensation_at timestamp with time zone,
    last_success_at timestamp with time zone,
    last_status varchar(32) not null default 'IDLE',
    last_message text not null default '',
    updated_at timestamp with time zone not null default current_timestamp,
    constraint ck_bi_cat_sync_state_singleton check (id = 1),
    constraint ck_bi_cat_sync_state_status
        check (last_status in ('IDLE', 'RUNNING', 'SUCCEEDED', 'PARTIAL_SUCCESS', 'FAILED'))
);

insert into bi_cat_sync_state(id)
values (1);

create table bi_cat_catalog_snapshots (
    snapshot_id uuid primary key,
    run_id uuid not null references bi_cat_sync_runs(run_id),
    collected_at timestamp with time zone not null,
    published_at timestamp with time zone not null,
    project_count integer not null,
    version_count integer not null,
    phase_count integer not null,
    constraint ck_bi_cat_catalog_counts
        check (project_count >= 0 and version_count >= 0 and phase_count >= 0)
);

create table bi_cat_catalog_projects (
    snapshot_id uuid not null references bi_cat_catalog_snapshots(snapshot_id) on delete cascade,
    cat_project_id varchar(128) not null,
    name text not null,
    note text,
    create_time text,
    create_user_id varchar(128),
    default_project boolean,
    primary key (snapshot_id, cat_project_id)
);

create table bi_cat_catalog_nodes (
    snapshot_id uuid not null references bi_cat_catalog_snapshots(snapshot_id) on delete cascade,
    cat_project_id varchar(128) not null,
    node_type varchar(32) not null,
    cat_node_id varchar(128) not null,
    parent_node_id varchar(128),
    name text not null,
    create_time text,
    note text,
    end_time text,
    current_version boolean,
    disabled boolean,
    default_project boolean,
    group_id varchar(128),
    version_id varchar(128),
    primary key (snapshot_id, cat_project_id, node_type, cat_node_id),
    constraint ck_bi_cat_catalog_node_type
        check (node_type in ('VERSION', 'TEST_PHASE'))
);

create index idx_bi_cat_catalog_nodes_parent
    on bi_cat_catalog_nodes(snapshot_id, cat_project_id, parent_node_id);

create table bi_cat_catalog_publication (
    id smallint primary key,
    snapshot_id uuid not null references bi_cat_catalog_snapshots(snapshot_id),
    published_version bigint not null default 1,
    published_at timestamp with time zone not null,
    constraint ck_bi_cat_catalog_publication_singleton check (id = 1),
    constraint ck_bi_cat_catalog_publication_version check (published_version > 0)
);

create table bi_cat_test_snapshots (
    snapshot_id uuid primary key,
    run_id uuid not null references bi_cat_sync_runs(run_id),
    product_version_id bigint not null,
    product_version_key varchar(128) not null,
    test_stage varchar(32) not null,
    cat_project_id varchar(128) not null,
    cat_version_id varchar(128) not null,
    cat_testing_phase_id varchar(128) not null,
    data_status varchar(16) not null,
    overall_pass_rate numeric(7, 2),
    attained_function_count bigint,
    total_function_count bigint,
    collection_started_at timestamp with time zone not null,
    collection_finished_at timestamp with time zone not null,
    published_at timestamp with time zone not null,
    constraint ck_bi_cat_test_stage
        check (test_stage in ('UNIT_TEST', 'INTEGRATION_TEST')),
    constraint ck_bi_cat_test_data_status
        check (data_status in ('READY', 'EMPTY')),
    constraint ck_bi_cat_test_rate
        check (overall_pass_rate is null or overall_pass_rate between 0 and 100),
    constraint ck_bi_cat_test_counts
        check (
            (attained_function_count is null and total_function_count is null)
            or (attained_function_count >= 0
                and total_function_count >= attained_function_count)
        )
);

create index idx_bi_cat_test_snapshots_scope
    on bi_cat_test_snapshots(product_version_id, test_stage, published_at desc);

create table bi_cat_test_modules (
    snapshot_id uuid not null references bi_cat_test_snapshots(snapshot_id) on delete cascade,
    cat_module_id varchar(128) not null,
    module_name text not null,
    attained_function_count bigint not null,
    total_function_count bigint not null,
    test_pass_rate numeric(7, 2) not null,
    display_order integer not null,
    primary key (snapshot_id, cat_module_id),
    constraint ck_bi_cat_module_counts
        check (attained_function_count >= 0
            and total_function_count >= attained_function_count),
    constraint ck_bi_cat_module_rate check (test_pass_rate between 0 and 100),
    constraint ck_bi_cat_module_order check (display_order >= 0)
);

create table bi_cat_test_functions (
    snapshot_id uuid not null references bi_cat_test_snapshots(snapshot_id) on delete cascade,
    cat_module_id varchar(128) not null,
    cat_function_id varchar(128) not null,
    function_name text not null,
    function_label text,
    test_pass_rate numeric(7, 2) not null,
    display_order integer not null,
    primary key (snapshot_id, cat_module_id, cat_function_id),
    foreign key (snapshot_id, cat_module_id)
        references bi_cat_test_modules(snapshot_id, cat_module_id) on delete cascade,
    constraint ck_bi_cat_function_rate check (test_pass_rate between 0 and 100),
    constraint ck_bi_cat_function_order check (display_order >= 0)
);

create table bi_cat_test_publications (
    product_version_id bigint not null,
    test_stage varchar(32) not null,
    snapshot_id uuid not null references bi_cat_test_snapshots(snapshot_id),
    published_version bigint not null,
    published_at timestamp with time zone not null,
    primary key (product_version_id, test_stage),
    constraint ck_bi_cat_test_publication_stage
        check (test_stage in ('UNIT_TEST', 'INTEGRATION_TEST')),
    constraint ck_bi_cat_test_publication_version check (published_version > 0)
);

create table bi_cat_raw_responses (
    response_id bigserial primary key,
    run_id uuid not null references bi_cat_sync_runs(run_id) on delete cascade,
    snapshot_id uuid,
    operation varchar(64) not null,
    request_key text not null default '',
    request_payload jsonb,
    response_payload jsonb not null,
    captured_at timestamp with time zone not null default current_timestamp
);

create index idx_bi_cat_raw_responses_run
    on bi_cat_raw_responses(run_id, response_id);

create index idx_bi_cat_raw_responses_snapshot
    on bi_cat_raw_responses(snapshot_id, response_id);
