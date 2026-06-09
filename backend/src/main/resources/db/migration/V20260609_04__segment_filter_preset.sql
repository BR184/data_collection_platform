create table if not exists segment_filter_preset (
    id bigserial primary key,
    preset_name varchar(255) not null,
    owner_user_id varchar(128) not null,
    visibility varchar(32) not null default 'PRIVATE',
    entity_type varchar(64) not null,
    scenario_key varchar(128) not null,
    scope_key varchar(128) not null,
    dsl_json text not null,
    dsl_hash varchar(64) not null,
    tag_schema_hash varchar(64) not null,
    source_data_watermark_at_save timestamp,
    last_used_at timestamp,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create index if not exists idx_segment_filter_preset_owner_scenario_updated
    on segment_filter_preset(owner_user_id, scenario_key, updated_at desc);
create index if not exists idx_segment_filter_preset_visibility_scenario_updated
    on segment_filter_preset(visibility, scenario_key, updated_at desc);
create index if not exists idx_segment_filter_preset_dsl_hash
    on segment_filter_preset(dsl_hash);
