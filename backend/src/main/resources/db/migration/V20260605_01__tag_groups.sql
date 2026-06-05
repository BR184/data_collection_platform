create table if not exists tag_group (
    id bigserial primary key,
    domain varchar(64) not null,
    group_key varchar(128) not null,
    label varchar(255) not null,
    selection_mode varchar(32) not null default 'multiple',
    sort_order integer not null default 0,
    match_strategy_name varchar(64) not null default 'eq',
    enabled boolean not null default true,
    remark varchar(255),
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    unique (domain, group_key)
);

create table if not exists tag_value (
    id bigserial primary key,
    group_id bigint not null references tag_group(id) on delete cascade,
    value_key varchar(128) not null,
    label varchar(255) not null,
    value_type varchar(32) not null default 'standard',
    sort_order integer not null default 0,
    enabled boolean not null default true,
    disabled boolean not null default false,
    unmapped_reason varchar(255),
    remark varchar(255),
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    unique (group_id, value_key)
);

create table if not exists tag_value_mapping (
    id bigserial primary key,
    value_id bigint not null references tag_value(id) on delete cascade,
    source_type varchar(64) not null default 'business_field',
    source_field varchar(128),
    raw_value varchar(255) not null,
    match_type varchar(32) not null default 'exact',
    source_instance varchar(128),
    enabled boolean not null default true,
    remark varchar(255),
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create index if not exists idx_tag_group_domain
    on tag_group(lower(domain), enabled, sort_order, group_key);
create index if not exists idx_tag_value_group
    on tag_value(group_id, enabled, disabled, sort_order, value_key);
create index if not exists idx_tag_value_mapping_value
    on tag_value_mapping(value_id, enabled, source_instance, match_type);
create index if not exists idx_tag_value_mapping_raw
    on tag_value_mapping(lower(raw_value), source_instance)
    where enabled = true;
