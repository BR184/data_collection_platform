create table if not exists label_groups (
    id bigserial primary key,
    name varchar(100) not null,
    dimension_key varchar(64) not null,
    group_type varchar(16) not null default 'STATIC',
    description varchar(500),
    enabled boolean not null default true,
    created_by varchar(100),
    created_at timestamptz not null default now(),
    updated_by varchar(100),
    updated_at timestamptz not null default now(),
    constraint ck_label_groups_type check (group_type in ('STATIC', 'DYNAMIC')),
    constraint uk_label_groups_dimension_name unique (dimension_key, name)
);

create table if not exists label_group_members (
    id bigserial primary key,
    group_id bigint not null references label_groups(id) on delete cascade,
    dimension_key varchar(64) not null,
    member_value varchar(255) not null,
    display_name varchar(255) not null,
    sort_order integer not null default 0,
    created_at timestamptz not null default now(),
    constraint uk_label_group_members_value unique (group_id, member_value)
);

create index if not exists idx_label_groups_dimension on label_groups(dimension_key);
create index if not exists idx_label_group_members_group on label_group_members(group_id);
create index if not exists idx_label_group_members_dimension_value
    on label_group_members(dimension_key, member_value);
