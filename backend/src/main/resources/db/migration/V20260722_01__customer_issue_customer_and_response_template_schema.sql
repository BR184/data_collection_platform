alter table issue_fact add column if not exists customer_names text;
alter table issue_fact add column if not exists planned_resolution_at timestamp;
alter table issue_fact add column if not exists planned_resolution_text text;
alter table issue_fact add column if not exists planned_merge_version_branch text;

create table if not exists issue_customer_name_aliases (
    alias_name varchar(255) primary key,
    canonical_name varchar(255) not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    check (nullif(btrim(alias_name), '') is not null),
    check (nullif(btrim(canonical_name), '') is not null)
);

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
