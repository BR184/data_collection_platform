create table if not exists semantic_scope_definition (
    scope_key varchar(128) primary key,
    scope_type varchar(64) not null,
    entity_type varchar(64) not null,
    description text not null,
    sql_template_key varchar(128) not null,
    rule_doc_reference varchar(255),
    rule_doc_hash varchar(64),
    enabled boolean not null default true,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create table if not exists semantic_tag_group (
    id bigserial primary key,
    domain varchar(64) not null,
    group_key varchar(128) not null,
    label varchar(255) not null,
    description text,
    source_mode varchar(32) not null,
    rule_policy_key varchar(128),
    selection_mode varchar(32) not null default 'MULTIPLE',
    match_strategy_name varchar(128),
    scope_key varchar(128) references semantic_scope_definition(scope_key) on delete set null,
    cache_ttl_seconds integer not null default 3600,
    cache_invalidation_trigger varchar(255) not null default 'FACT_BUILD_COMPLETE,MANUAL',
    last_computed_at timestamp,
    last_checked_at timestamp,
    last_data_watermark timestamp,
    computing_lock_key varchar(255),
    rule_doc_version varchar(64),
    rule_doc_hash varchar(64),
    admin_override_allowed boolean not null default false,
    schema_hash varchar(64),
    enabled boolean not null default true,
    sort_order integer not null default 0,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    unique (domain, group_key)
);

create table if not exists semantic_tag_value (
    id bigserial primary key,
    group_id bigint not null references semantic_tag_group(id) on delete cascade,
    value_key varchar(128) not null,
    label varchar(255) not null,
    description text,
    value_type varchar(64) not null default 'STRING',
    canonical_value varchar(255),
    enabled boolean not null default true,
    disabled_reason varchar(255),
    sort_order integer not null default 0,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    unique (group_id, value_key)
);

create table if not exists semantic_tag_value_mapping (
    id bigserial primary key,
    value_id bigint not null references semantic_tag_value(id) on delete cascade,
    source_instance varchar(128) not null default 'default',
    entity_type varchar(64) not null,
    source_field varchar(128) not null,
    match_type varchar(32) not null default 'EXACT',
    raw_value varchar(512) not null,
    normalized_value varchar(512),
    enabled boolean not null default true,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    unique (value_id, source_instance, entity_type, source_field, match_type, raw_value)
);

create table if not exists semantic_tag_group_build_run (
    id bigserial primary key,
    group_id bigint not null references semantic_tag_group(id) on delete cascade,
    source_instance varchar(128) not null default 'default',
    scope_key varchar(128),
    scope_hash varchar(64),
    status varchar(32) not null,
    source_data_watermark timestamp,
    schema_hash varchar(64),
    value_count integer not null default 0,
    started_at timestamp not null default current_timestamp,
    finished_at timestamp,
    error_message text,
    created_at timestamp not null default current_timestamp
);

create table if not exists segment_definition (
    id bigserial primary key,
    name varchar(255) not null,
    segment_type varchar(32) not null,
    entity_type varchar(64) not null,
    scenario_key varchar(128) not null,
    scope_key varchar(128) references semantic_scope_definition(scope_key) on delete set null,
    rule_json text,
    refresh_policy_json text,
    member_source varchar(32) not null,
    source_segment_id bigint references segment_definition(id) on delete set null,
    tag_schema_hash varchar(64),
    active_run_id bigint,
    execution_plan_json text,
    execution_cost_estimate_json text,
    estimated_cost numeric(18, 4),
    max_execution_time_ms integer not null default 30000,
    max_allowed_members integer not null default 50000,
    enabled boolean not null default true,
    created_by varchar(128),
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create table if not exists segment_compute_run (
    id bigserial primary key,
    segment_id bigint not null references segment_definition(id) on delete cascade,
    status varchar(32) not null,
    started_at timestamp not null default current_timestamp,
    finished_at timestamp,
    source_data_watermark timestamp,
    member_count integer not null default 0,
    error_message text,
    created_at timestamp not null default current_timestamp
);

do $$
begin
    if not exists (
        select 1
          from pg_constraint
         where conname = 'fk_segment_definition_active_run'
    ) then
        alter table segment_definition
            add constraint fk_segment_definition_active_run
            foreign key (active_run_id) references segment_compute_run(id) on delete set null;
    end if;
end $$;

create table if not exists segment_member_current (
    segment_id bigint not null references segment_definition(id) on delete cascade,
    entity_type varchar(64) not null,
    entity_id varchar(255) not null,
    display_name varchar(255),
    member_payload_json text,
    member_source varchar(32) not null,
    pinned boolean not null default false,
    added_by varchar(128),
    added_reason text,
    computed_run_id bigint references segment_compute_run(id) on delete set null,
    computed_at timestamp not null default current_timestamp,
    created_at timestamp not null default current_timestamp,
    primary key (segment_id, entity_type, entity_id)
);

create table if not exists segment_member_stage (
    segment_id bigint not null references segment_definition(id) on delete cascade,
    computed_run_id bigint not null references segment_compute_run(id) on delete cascade,
    entity_type varchar(64) not null,
    entity_id varchar(255) not null,
    display_name varchar(255),
    member_payload_json text,
    member_source varchar(32) not null,
    computed_at timestamp not null default current_timestamp,
    created_at timestamp not null default current_timestamp,
    primary key (computed_run_id, segment_id, entity_type, entity_id)
);

create table if not exists segment_member_audit (
    id bigserial primary key,
    segment_id bigint not null references segment_definition(id) on delete cascade,
    entity_type varchar(64) not null,
    entity_id varchar(255) not null,
    action varchar(32) not null,
    reason text,
    operator varchar(128),
    computed_run_id bigint references segment_compute_run(id) on delete set null,
    created_at timestamp not null default current_timestamp
);

create table if not exists segment_snapshot (
    id bigserial primary key,
    source_segment_id bigint references segment_definition(id) on delete set null,
    name varchar(255) not null,
    entity_type varchar(64) not null,
    snapshot_reason text,
    created_by varchar(128),
    created_at timestamp not null default current_timestamp
);

create table if not exists segment_snapshot_member (
    snapshot_id bigint not null references segment_snapshot(id) on delete cascade,
    entity_type varchar(64) not null,
    entity_id varchar(255) not null,
    display_name varchar(255),
    member_payload_json text,
    created_at timestamp not null default current_timestamp,
    primary key (snapshot_id, entity_type, entity_id)
);

create index if not exists idx_semantic_scope_entity
    on semantic_scope_definition(entity_type, enabled, scope_type);
create index if not exists idx_semantic_tag_group_domain_key
    on semantic_tag_group(domain, group_key);
create index if not exists idx_semantic_tag_value_group
    on semantic_tag_value(group_id, enabled, sort_order, value_key);
create index if not exists idx_semantic_tag_value_mapping_lookup
    on semantic_tag_value_mapping(value_id, enabled, source_instance, entity_type, source_field, match_type);
create index if not exists idx_semantic_tag_group_build_run_status
    on semantic_tag_group_build_run(group_id, status, started_at desc);
create index if not exists idx_segment_definition_entity_scenario
    on segment_definition(entity_type, scenario_key, enabled);
create index if not exists idx_segment_definition_scope
    on segment_definition(scope_key, enabled);
create index if not exists idx_segment_compute_run_segment_status
    on segment_compute_run(segment_id, status, started_at desc);
create index if not exists idx_segment_member_current_run
    on segment_member_current(segment_id, computed_run_id, entity_type, entity_id);
create index if not exists idx_segment_member_stage_run
    on segment_member_stage(computed_run_id, segment_id, entity_type, entity_id);
create index if not exists idx_segment_member_audit_segment
    on segment_member_audit(segment_id, entity_type, entity_id, created_at desc);
create index if not exists idx_segment_snapshot_source
    on segment_snapshot(source_segment_id, created_at desc);
