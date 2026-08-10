-- 事实发布改为来源级依赖代际；同时修正 BI 提交事实和审计字段契约。
create table source_fact_dependency_states (
    config_id bigint not null references gitlab_sync_configs(id) on delete cascade,
    source_instance varchar(128) not null,
    fact_type varchar(64) not null,
    source_table varchar(255) not null,
    latest_mirror_run_id bigint not null references sync_runs(id),
    readiness_status varchar(16) not null,
    error_message text,
    updated_at timestamp not null default current_timestamp,
    primary key (config_id, source_instance, fact_type, source_table),
    constraint ck_source_fact_dependency_states_type
        check (fact_type in ('ISSUE', 'MERGE_REQUEST', 'INTEGRATION_TEST')),
    constraint ck_source_fact_dependency_states_status
        check (readiness_status in ('READY', 'BLOCKED'))
);

create table source_fact_publication_states (
    config_id bigint not null references gitlab_sync_configs(id) on delete cascade,
    source_instance varchar(128) not null,
    fact_type varchar(64) not null,
    latest_mirror_run_id bigint not null references sync_runs(id),
    ready_mirror_run_id bigint references sync_runs(id) on delete set null,
    blocked_mirror_run_id bigint references sync_runs(id) on delete set null,
    readiness_status varchar(16) not null,
    full_publication_requested boolean not null default false,
    error_message text,
    updated_at timestamp not null default current_timestamp,
    primary key (config_id, source_instance, fact_type),
    constraint ck_source_fact_publication_states_type
        check (fact_type in ('ISSUE', 'MERGE_REQUEST', 'INTEGRATION_TEST')),
    constraint ck_source_fact_publication_states_status
        check (readiness_status in ('READY', 'BLOCKED'))
);

create index idx_source_fact_publication_ready
    on source_fact_publication_states(config_id, source_instance, fact_type)
    where readiness_status = 'READY';

alter table sync_run_authoritative_scopes
    add column recovered_by_run_id bigint references sync_runs(id),
    add column recovered_at timestamp;

create index idx_authoritative_scope_failed_recovery
    on sync_run_authoritative_scopes(source_instance, child_table, id)
    where status = 'FAILED' and recovered_by_run_id is null;

drop index if exists idx_merge_request_commit_fact_scope;
alter table merge_request_commit_fact drop column project_name;
create index idx_merge_request_commit_fact_scope
    on merge_request_commit_fact(source_instance, project_id, merge_request_id, committed_at_source);

alter table operation_audit_logs alter column role type text;
