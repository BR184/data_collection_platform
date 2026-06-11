create table if not exists label_groups (
    id bigserial primary key,
    name varchar(100) not null,
    value_type varchar(32),
    group_type varchar(16) not null default 'STATIC',
    description varchar(500),
    enabled boolean not null default true,
    created_by varchar(100),
    created_at timestamptz not null default now(),
    updated_by varchar(100),
    updated_at timestamptz not null default now(),
    constraint ck_label_groups_type check (group_type in ('STATIC', 'DYNAMIC', 'COMPOSITE')),
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

create index if not exists idx_label_group_members_group on label_group_members(group_id);

create table if not exists label_group_references (
    id bigserial primary key,
    parent_group_id bigint not null references label_groups(id) on delete cascade,
    child_group_id bigint not null references label_groups(id) on delete restrict,
    sort_order integer not null default 0,
    created_at timestamptz not null default now(),
    constraint ck_label_group_references_not_self check (parent_group_id <> child_group_id),
    constraint uk_label_group_references_child unique (parent_group_id, child_group_id)
);

create index if not exists idx_label_group_references_parent on label_group_references(parent_group_id);
create index if not exists idx_label_group_references_child on label_group_references(child_group_id);
