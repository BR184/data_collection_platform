create table if not exists label_group_default_filters (
    id bigserial primary key,
    page_key varchar(100) not null,
    field_key varchar(100) not null,
    operator varchar(32) not null default 'intersects',
    label_group_id bigint references label_groups(id) on delete set null,
    enabled boolean not null default true,
    description varchar(500),
    created_by varchar(100),
    created_at timestamptz not null default now(),
    updated_by varchar(100),
    updated_at timestamptz not null default now(),
    constraint uk_label_group_default_filters_page_field unique (page_key, field_key)
);

create index if not exists idx_label_group_default_filters_group
    on label_group_default_filters(label_group_id);

