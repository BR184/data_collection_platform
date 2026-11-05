-- 统一表单存储基础：元数据、范围、版本、审计、发布、权威与变更 head。
-- 版本实例/记录表由受控发布按 field_spec 生成，不在通用 Flyway 中创建。
create table form_template_definitions (
    template_key varchar(64) primary key,
    display_name varchar(128) not null,
    status varchar(16) not null check (status in ('ACTIVE', 'DISABLED')),
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create table form_template_scopes (
    id bigserial primary key,
    template_key varchar(64) not null references form_template_definitions(template_key),
    scope_key varchar(64) not null,
    source_config_id bigint,
    source_instance varchar(128),
    canonical_gitlab_base_url varchar(255),
    project_ids jsonb not null default '[]'::jsonb
        check (jsonb_typeof(project_ids) = 'array'),
    version_project_id bigint check (version_project_id > 0),
    resource_type varchar(64) not null,
    current_version_id bigint,
    status varchar(16) not null check (status in ('DRAFT', 'ACTIVE', 'RETIRED')),
    updated_at timestamp not null default current_timestamp,
    write_fenced boolean not null default false,
    write_fence_reason varchar(64),
    write_fence_publication_id bigint,
    write_fence_token varchar(64),
    write_fence_at timestamp,
    binding_revision bigint not null default 0 check (binding_revision >= 0),
    binding_hash char(64),
    unique (template_key, scope_key),
    check (status <> 'ACTIVE' or
        (source_config_id is not null and source_instance is not null
         and canonical_gitlab_base_url is not null and version_project_id is not null
         and jsonb_array_length(project_ids) > 0 and current_version_id is not null
         and binding_hash is not null))
);

create table form_template_versions (
    id bigserial primary key,
    scope_id bigint not null references form_template_scopes(id),
    version_label varchar(64),
    storage_kind varchar(16) not null check (storage_kind in ('LEGACY', 'RECORDS')),
    instance_table varchar(63) not null,
    record_table varchar(63),
    field_spec jsonb not null,
    migration_spec jsonb,
    version_status varchar(16) not null
        check (version_status in ('INSTALLED', 'PUBLISHED', 'RETIRED')),
    published_at timestamp,
    unique (scope_id, id),
    check ((storage_kind = 'LEGACY' and record_table is null)
        or (storage_kind = 'RECORDS' and record_table is not null)),
    check (storage_kind = 'LEGACY' or version_status <> 'PUBLISHED'
        or (version_label is not null and published_at is not null))
);

alter table form_template_scopes add constraint form_scope_current_version_fk
    foreign key (id, current_version_id) references form_template_versions(scope_id, id);

create sequence form_record_id_seq as bigint;

create table form_template_scope_drafts (
    scope_id bigint primary key references form_template_scopes(id) on delete cascade,
    draft_revision bigint not null default 1 check (draft_revision > 0),
    field_spec jsonb not null,
    source_config_id bigint,
    source_instance varchar(128),
    canonical_gitlab_base_url varchar(255),
    project_ids jsonb not null default '[]'::jsonb
        check (jsonb_typeof(project_ids) = 'array'),
    version_project_id bigint,
    version_label varchar(64),
    version_evidence jsonb,
    binding_hash char(64) not null,
    migration_spec jsonb,
    updated_by varchar(128) not null,
    updated_at timestamp not null
);

create table form_template_preview_evidence (
    id bigserial primary key,
    scope_id bigint not null references form_template_scopes(id) on delete cascade,
    draft_revision bigint not null,
    field_spec_hash char(64) not null,
    binding_hash char(64) not null,
    preview_hash char(64) not null,
    preview_json jsonb not null,
    blocking_errors jsonb not null default '[]'::jsonb,
    created_by varchar(128) not null,
    created_at timestamp not null,
    unique (scope_id, draft_revision)
);

create table form_record_audit_logs (
    id bigserial primary key,
    record_id bigint not null,
    template_key varchar(64) not null,
    scope_id bigint not null references form_template_scopes(id),
    storage_version_id bigint not null references form_template_versions(id),
    record_version integer not null,
    operation varchar(16) not null check (operation in ('CREATE', 'UPDATE', 'VOID')),
    snapshot_json jsonb not null,
    operator_id varchar(128) not null,
    operator_name varchar(128),
    client_ip varchar(64),
    user_agent varchar(512),
    created_at timestamp not null,
    unique (record_id, record_version, operation)
);

create table form_template_migration_logs (
    id bigserial primary key,
    scope_id bigint not null references form_template_scopes(id),
    from_version_id bigint not null references form_template_versions(id),
    to_version_id bigint not null references form_template_versions(id),
    migrated_rows bigint not null,
    verification_summary jsonb not null,
    executed_at timestamp not null,
    unique (scope_id, from_version_id, to_version_id)
);

create table form_template_publications (
    id bigserial primary key,
    scope_id bigint not null references form_template_scopes(id),
    request_key varchar(64) not null,
    request_hash char(64) not null,
    draft_revision bigint not null,
    preview_id bigint not null references form_template_preview_evidence(id),
    publication_kind varchar(24) not null
        check (publication_kind in ('INITIAL_EMPTY', 'INITIAL_MIGRATION', 'MIGRATION')),
    expected_current_version_id bigint,
    from_version_id bigint,
    to_version_id bigint not null,
    frozen_field_spec jsonb not null,
    frozen_migration_spec jsonb,
    frozen_binding jsonb not null,
    binding_hash char(64) not null,
    version_evidence jsonb not null,
    ddl_summary jsonb not null,
    ddl_hash char(64) not null,
    selection_counts jsonb not null,
    selection_captured_at timestamp,
    copy_checksum jsonb,
    verification_summary jsonb,
    stage varchar(16) not null
        check (stage in ('PREPARED', 'COPYING', 'VERIFIED', 'PUBLISHED', 'FAILED')),
    attempt_no integer not null default 0 check (attempt_no >= 0),
    recovery_cycle integer not null default 0 check (recovery_cycle >= 0),
    task_version bigint not null default 0 check (task_version >= 0),
    lease_owner varchar(128),
    lease_until timestamp,
    fencing_version bigint not null default 0 check (fencing_version >= 0),
    fence_token varchar(64),
    next_attempt_at timestamp,
    recovery_verified_at timestamp,
    recovery_evidence jsonb,
    last_error text,
    operator_id varchar(128) not null,
    operator_name varchar(128),
    created_at timestamp not null,
    updated_at timestamp not null,
    published_at timestamp,
    unique (scope_id, request_key),
    unique (to_version_id),
    foreign key (scope_id, expected_current_version_id)
        references form_template_versions(scope_id, id),
    foreign key (scope_id, from_version_id)
        references form_template_versions(scope_id, id),
    foreign key (scope_id, to_version_id)
        references form_template_versions(scope_id, id),
    check (
        (publication_kind = 'INITIAL_EMPTY'
         and from_version_id is null and expected_current_version_id is null)
        or (publication_kind = 'INITIAL_MIGRATION'
            and from_version_id is not null and expected_current_version_id is null)
        or (publication_kind = 'MIGRATION'
            and from_version_id is not null
            and expected_current_version_id = from_version_id)
    ),
    check (stage <> 'PUBLISHED' or published_at is not null),
    check (stage <> 'COPYING' or fence_token is not null)
);

create unique index uq_form_publications_active
    on form_template_publications(scope_id)
    where stage in ('PREPARED', 'COPYING', 'VERIFIED');

create index idx_form_publications_claim
    on form_template_publications(stage, next_attempt_at, lease_until)
    where stage in ('PREPARED', 'COPYING', 'VERIFIED');

create table form_template_publication_attempts (
    id bigserial primary key,
    publication_id bigint not null references form_template_publications(id) on delete cascade,
    attempt_no integer not null check (attempt_no > 0),
    phase varchar(16) not null
        check (phase in ('COPY', 'VERIFY', 'PUBLISH', 'FAILED')),
    selection_captured_at timestamp not null,
    selection_counts jsonb not null,
    copy_checksum jsonb,
    verification_summary jsonb,
    error text,
    started_at timestamp not null,
    finished_at timestamp,
    unique (publication_id, attempt_no)
);

create table form_authority_cutover_manifests (
    id bigserial primary key,
    manifest_key varchar(64) not null unique,
    source_config_id bigint not null,
    source_instance varchar(128) not null,
    canonical_gitlab_base_url varchar(255) not null,
    template_key varchar(64) not null references form_template_definitions(template_key),
    scope_id bigint not null references form_template_scopes(id),
    effective_at timestamp not null,
    project_ids jsonb not null
        check (jsonb_typeof(project_ids) = 'array' and jsonb_array_length(project_ids) > 0),
    resource_rule jsonb not null,
    rule_version varchar(64) not null,
    status varchar(16) not null check (status in ('DRAFT', 'APPLIED', 'RETIRED')),
    operator_id varchar(128) not null,
    operator_name varchar(128),
    created_at timestamp not null,
    applied_at timestamp
);

create table form_authority_cutover_items (
    id bigserial primary key,
    manifest_id bigint not null references form_authority_cutover_manifests(id) on delete cascade,
    project_id bigint not null check (project_id > 0),
    resource_type varchar(64) not null
        check (resource_type in ('merge_request', 'issue')),
    resource_id bigint check (resource_id > 0),
    authority varchar(16) not null check (authority in ('COMMENT', 'FORM')),
    created_at timestamp not null,
    unique nulls not distinct (manifest_id, project_id, resource_type, resource_id)
);

create table form_resource_authorities (
    id bigserial primary key,
    source_config_id bigint not null,
    source_instance varchar(128) not null,
    canonical_gitlab_base_url varchar(255) not null,
    project_id bigint not null check (project_id > 0),
    resource_type varchar(64) not null
        check (resource_type in ('merge_request', 'issue')),
    resource_id bigint not null check (resource_id > 0),
    template_key varchar(64) not null references form_template_definitions(template_key),
    scope_id bigint not null references form_template_scopes(id),
    authority varchar(16) not null check (authority in ('COMMENT', 'FORM')),
    authority_revision bigint not null default 1 check (authority_revision > 0),
    cutover_manifest_id bigint references form_authority_cutover_manifests(id),
    effective_at timestamp not null,
    created_at timestamp not null,
    updated_at timestamp not null,
    unique (source_config_id, project_id, resource_type, resource_id, template_key)
);

create table form_change_heads (
    id bigserial primary key,
    source_config_id bigint not null,
    source_instance varchar(128) not null,
    canonical_gitlab_base_url varchar(255) not null,
    project_id bigint not null check (project_id > 0),
    resource_type varchar(64) not null,
    resource_id bigint not null check (resource_id > 0),
    template_key varchar(64) not null references form_template_definitions(template_key),
    scope_id bigint not null references form_template_scopes(id),
    latest_change_version bigint not null default 0 check (latest_change_version >= 0),
    consumed_change_version bigint not null default 0
        check (consumed_change_version >= 0),
    latest_audit_id bigint references form_record_audit_logs(id),
    pending_record_id bigint,
    pending_record_version integer,
    pending_storage_version_id bigint references form_template_versions(id),
    pending_authority_revision bigint,
    status varchar(16) not null
        check (status in ('PENDING', 'LEASED', 'RETRY_WAIT', 'CONSUMED', 'FAILED')),
    lease_owner varchar(128),
    lease_until timestamp,
    fencing_version bigint not null default 0 check (fencing_version >= 0),
    next_attempt_at timestamp,
    last_error text,
    created_at timestamp not null,
    updated_at timestamp not null,
    unique (source_config_id, project_id, resource_type, resource_id, template_key),
    check (consumed_change_version <= latest_change_version),
    check (resource_type in ('merge_request', 'issue'))
);

create index idx_form_change_heads_claim
    on form_change_heads(status, next_attempt_at, lease_until)
    where status in ('PENDING', 'RETRY_WAIT', 'LEASED');
create index idx_form_change_heads_scope
    on form_change_heads(scope_id, status);
create index idx_form_record_audit_logs_record
    on form_record_audit_logs(record_id, created_at desc);
create index idx_form_resource_authorities_scope
    on form_resource_authorities(scope_id, template_key, authority);
create index idx_form_template_versions_scope_status
    on form_template_versions(scope_id, version_status);

insert into form_template_definitions(template_key, display_name, status)
values
    ('code_review', '代码走查', 'ACTIVE'),
    ('issue_response', 'Issue 响应', 'ACTIVE'),
    ('issue_reply', 'Issue 回复', 'ACTIVE')
on conflict (template_key) do update
set display_name = excluded.display_name,
    status = excluded.status,
    updated_at = current_timestamp;
