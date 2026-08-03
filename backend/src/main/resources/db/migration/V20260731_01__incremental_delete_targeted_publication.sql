create sequence fact_change_version_seq;

alter table sync_runs
    add column run_after timestamp not null default current_timestamp;

alter table sync_run_table_states
    add column last_delete_reconciled_at timestamp;

alter table fact_build_tasks
    add column recovery_count integer not null default 0;

alter table issue_scope_groups
    add column definition_generation bigint not null default 1;

create table sync_run_authoritative_scopes (
    id bigserial primary key,
    run_id bigint not null references sync_runs(id) on delete cascade,
    source_instance varchar(128) not null,
    child_table varchar(255) not null,
    relation_key varchar(128) not null,
    scope_signature varchar(1024) not null,
    lookup_scope_json text not null,
    status varchar(32) not null default 'QUEUED',
    task_id bigint references sync_run_table_tasks(id) on delete set null,
    lease_owner varchar(128),
    lease_expires_at timestamp,
    heartbeat_at timestamp,
    retry_count integer not null default 0,
    max_retry_count integer not null default 3,
    recovery_count integer not null default 0,
    run_after timestamp not null default current_timestamp,
    error_message text,
    started_at timestamp,
    finished_at timestamp,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint ck_sync_run_authoritative_scopes_status
        check (status in ('QUEUED', 'RUNNING', 'RETRY_WAITING', 'SUCCESS', 'FAILED')),
    constraint uk_sync_run_authoritative_scopes_identity
        unique (run_id, child_table, relation_key, scope_signature)
);

create table fact_change_heads (
    source_instance varchar(128) not null,
    fact_type varchar(64) not null,
    root_id bigint not null,
    latest_change_version bigint not null,
    published_version bigint not null default 0,
    published_by_fact_build_task_id bigint references fact_build_tasks(id) on delete set null,
    updated_at timestamp not null default current_timestamp,
    primary key (source_instance, fact_type, root_id),
    constraint ck_fact_change_heads_type
        check (fact_type in ('ISSUE', 'MERGE_REQUEST', 'INTEGRATION_TEST')),
    constraint ck_fact_change_heads_versions
        check (latest_change_version > 0 and published_version >= 0
            and published_version <= latest_change_version)
);

create table sync_run_fact_targets (
    mirror_run_id bigint not null references sync_runs(id) on delete cascade,
    source_instance varchar(128) not null,
    fact_type varchar(64) not null,
    root_id bigint not null,
    change_version bigint not null,
    project_id bigint,
    iid bigint,
    first_task_id bigint references sync_run_table_tasks(id) on delete set null,
    last_task_id bigint references sync_run_table_tasks(id) on delete set null,
    publication_status varchar(32) not null default 'PENDING',
    assigned_fact_run_id bigint references sync_runs(id) on delete set null,
    assigned_fact_build_task_id bigint references fact_build_tasks(id) on delete set null,
    published_version bigint,
    published_by_fact_build_task_id bigint references fact_build_tasks(id) on delete set null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    published_at timestamp,
    primary key (mirror_run_id, source_instance, fact_type, root_id),
    constraint ck_sync_run_fact_targets_type
        check (fact_type in ('ISSUE', 'MERGE_REQUEST', 'INTEGRATION_TEST')),
    constraint ck_sync_run_fact_targets_status
        check (publication_status in ('PENDING', 'QUEUED', 'PUBLISHED')),
    constraint ck_sync_run_fact_targets_versions
        check (change_version > 0 and (published_version is null or published_version >= change_version))
);

create table fact_projection_generations (
    source_instance varchar(128) not null,
    fact_type varchar(64) not null,
    scope_type varchar(64) not null,
    scope_key varchar(512) not null,
    generation bigint not null,
    updated_at timestamp not null default current_timestamp,
    primary key (source_instance, fact_type, scope_type, scope_key),
    constraint ck_fact_projection_generations_type
        check (fact_type in ('ISSUE', 'MERGE_REQUEST', 'INTEGRATION_TEST')),
    constraint ck_fact_projection_generations_scope
        check (scope_type in ('FULL_EPOCH', 'GLOBAL_VIEW', 'PROJECT', 'ISSUE_SCOPE_GROUP')),
    constraint ck_fact_projection_generations_value check (generation > 0)
);

create table fact_projection_refresh_tasks (
    id bigserial primary key,
    fact_run_id bigint not null references sync_runs(id) on delete cascade,
    fact_build_task_id bigint not null references fact_build_tasks(id) on delete cascade,
    source_instance varchar(128) not null,
    fact_type varchar(64) not null,
    scope_type varchar(64) not null,
    scope_key varchar(512) not null,
    target_generation bigint not null,
    status varchar(32) not null default 'QUEUED',
    lease_owner varchar(128),
    lease_until timestamp,
    heartbeat_at timestamp,
    retry_count integer not null default 0,
    max_retry_count integer not null default 3,
    recovery_count integer not null default 0,
    run_after timestamp not null default current_timestamp,
    error_message text,
    started_at timestamp,
    finished_at timestamp,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint ck_fact_projection_refresh_tasks_type
        check (fact_type in ('ISSUE', 'MERGE_REQUEST', 'INTEGRATION_TEST')),
    constraint ck_fact_projection_refresh_tasks_scope
        check (scope_type in ('FULL_EPOCH', 'GLOBAL_VIEW', 'PROJECT', 'ISSUE_SCOPE_GROUP')),
    constraint ck_fact_projection_refresh_tasks_status
        check (status in ('QUEUED', 'RUNNING', 'RETRY_WAITING', 'SUCCESS', 'FAILED')),
    constraint uk_fact_projection_refresh_tasks_scope
        unique (fact_build_task_id, scope_type, scope_key)
);

create table sync_run_publication_fences (
    id bigserial primary key,
    run_id bigint not null references sync_runs(id) on delete cascade,
    workspace_key varchar(128) not null,
    source_instance varchar(128) not null,
    fact_type varchar(64) not null,
    target_selector_type varchar(64) not null,
    target_selector_key varchar(512) not null,
    required_change_version bigint not null default 0,
    status varchar(32) not null default 'PENDING',
    error_message text,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    completed_at timestamp,
    constraint ck_sync_run_publication_fences_type
        check (fact_type in ('ISSUE', 'MERGE_REQUEST', 'INTEGRATION_TEST')),
    constraint ck_sync_run_publication_fences_selector
        check (target_selector_type in ('GLOBAL', 'PROJECT', 'ISSUE_SCOPE_GROUP')),
    constraint ck_sync_run_publication_fences_status
        check (status in ('PENDING', 'SUCCESS', 'FAILED')),
    constraint uk_sync_run_publication_fences_identity
        unique (run_id, workspace_key, fact_type, target_selector_type, target_selector_key)
);

create table sync_run_publication_fence_scopes (
    fence_id bigint not null references sync_run_publication_fences(id) on delete cascade,
    scope_type varchar(64) not null,
    scope_key varchar(512) not null,
    required_generation bigint not null,
    projection_task_id bigint references fact_projection_refresh_tasks(id) on delete set null,
    status varchar(32) not null default 'PENDING',
    updated_at timestamp not null default current_timestamp,
    primary key (fence_id, scope_type, scope_key),
    constraint ck_sync_run_publication_fence_scopes_scope
        check (scope_type in ('FULL_EPOCH', 'GLOBAL_VIEW', 'PROJECT', 'ISSUE_SCOPE_GROUP')),
    constraint ck_sync_run_publication_fence_scopes_status
        check (status in ('PENDING', 'SUCCESS', 'FAILED')),
    constraint ck_sync_run_publication_fence_scopes_generation check (required_generation > 0)
);

create index idx_sync_runs_ready_dispatch
    on sync_runs(status, run_after, priority desc, updated_at, created_at, id)
    where status in ('QUEUED', 'PAUSED', 'RETRYING');

create unique index uk_sync_runs_fact_refresh_parent
    on sync_runs(parent_run_id)
    where run_type = 'FACT_REFRESH' and parent_run_id is not null;

create index idx_sync_run_authoritative_scopes_dispatch
    on sync_run_authoritative_scopes(status, run_after, run_id, child_table, id)
    where status in ('QUEUED', 'RETRY_WAITING');

create index idx_sync_run_authoritative_scopes_run_status
    on sync_run_authoritative_scopes(run_id, status, child_table, id);

create index idx_sync_run_authoritative_scopes_task
    on sync_run_authoritative_scopes(task_id)
    where task_id is not null;

create index idx_sync_run_table_tasks_stage_barrier
    on sync_run_table_tasks(run_id, task_stage, status, source_table, id);

create index idx_sync_run_fact_targets_publication
    on sync_run_fact_targets(mirror_run_id, publication_status, fact_type, root_id)
    where publication_status <> 'PUBLISHED';

create index idx_sync_run_fact_targets_root_version
    on sync_run_fact_targets(source_instance, fact_type, root_id, change_version);

create index idx_sync_run_fact_targets_assignment
    on sync_run_fact_targets(assigned_fact_build_task_id, publication_status)
    where assigned_fact_build_task_id is not null;

create index idx_fact_change_heads_publication_task
    on fact_change_heads(published_by_fact_build_task_id)
    where published_by_fact_build_task_id is not null;

create index idx_fact_projection_refresh_tasks_dispatch
    on fact_projection_refresh_tasks(status, run_after, fact_run_id, id)
    where status in ('QUEUED', 'RETRY_WAITING');

create index idx_fact_projection_refresh_tasks_run_status
    on fact_projection_refresh_tasks(fact_run_id, status, id);

create index idx_sync_run_publication_fences_run_status
    on sync_run_publication_fences(run_id, status, id);

create index idx_sync_run_publication_fence_scopes_task
    on sync_run_publication_fence_scopes(projection_task_id, status)
    where projection_task_id is not null;
