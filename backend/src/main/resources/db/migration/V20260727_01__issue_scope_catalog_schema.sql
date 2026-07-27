create table issue_scope_catalogs (
    id bigserial primary key,
    project_id bigint not null,
    project_name varchar(255) not null,
    dimension varchar(32) not null,
    enabled boolean not null default true,
    remark varchar(255),
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint ck_issue_scope_catalogs_dimension
        check (dimension in ('TESTING_PHASE', 'MILESTONE')),
    constraint uk_issue_scope_catalogs_project_dimension
        unique (project_id, dimension)
);

create table issue_scope_groups (
    id bigserial primary key,
    catalog_id bigint not null references issue_scope_catalogs(id) on delete cascade,
    business_key varchar(128) not null,
    display_name varchar(128) not null,
    sort_order integer not null default 0,
    enabled boolean not null default true,
    remark varchar(255),
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_issue_scope_groups_catalog_business_key
        unique (catalog_id, business_key),
    constraint uk_issue_scope_groups_id_catalog
        unique (id, catalog_id)
);

create table issue_scope_members (
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
    constraint fk_issue_scope_members_group_catalog
        foreign key (group_id, catalog_id)
        references issue_scope_groups(id, catalog_id)
        on delete cascade,
    constraint uk_issue_scope_members_catalog_source_value
        unique (catalog_id, source_value)
);

create index idx_issue_scope_catalogs_enabled
    on issue_scope_catalogs(enabled, project_id, dimension);

create index idx_issue_scope_groups_catalog_order
    on issue_scope_groups(catalog_id, enabled, sort_order, id);

create index idx_issue_scope_members_group_order
    on issue_scope_members(group_id, enabled, sort_order, id);

