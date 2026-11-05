-- v6 N1：多版本定位目录、范围源集合、版本角色/实例模式、目录事件与记录序列高水位。
-- 蓝本为 N0 五审批准合同 §1.2—§1.4（探针 revision.sql M1—M3），仅依赖
-- V20261022_01__unified_form_storage_foundation.sql；发布协议、消费捕获与导出审计
-- 属后续阶段迁移，不在本文件。

-- ===== 范围集合修订（只由发布切换/登记事务递增） =====
alter table form_template_scopes
    add column source_set_revision bigint not null default 0
        check (source_set_revision >= 0),
    add column source_set_hash char(64);

-- ===== 版本角色 / 实例模式 / 旧来源表（角色唯一权威） =====
alter table form_template_versions
    add column record_source_role varchar(24) not null default 'AUTHORITATIVE',
    add column instance_mode varchar(16) not null default 'MATERIALIZED',
    add column source_table varchar(63);

alter table form_template_versions alter column instance_table drop not null;

alter table form_template_versions
    add constraint ck_ftv_role check (
        record_source_role in ('AUTHORITATIVE', 'LEGACY_SNAPSHOT', 'TARGET')),
    add constraint ck_ftv_instance_mode check (
        instance_mode in ('MATERIALIZED', 'NONE')),
    add constraint ck_ftv_instance_shape check (
        (record_source_role <> 'TARGET'
         and ((storage_kind = 'RECORDS' and record_table is not null
               and instance_mode = 'MATERIALIZED' and instance_table is not null
               and source_table is null)
           or (storage_kind = 'LEGACY' and record_table is null
               and instance_mode = 'MATERIALIZED' and instance_table is not null
               and source_table is null)
           or (storage_kind = 'LEGACY' and record_table is null
               and instance_mode = 'NONE' and instance_table is null
               and source_table is not distinct from 'collect_form_records')))
        or (record_source_role = 'TARGET' and storage_kind = 'RECORDS'
            and record_table is not null and instance_mode = 'MATERIALIZED'
            and instance_table is not null and source_table is null)),
    add constraint ck_ftv_none_role check (
        instance_mode <> 'NONE' or record_source_role = 'AUTHORITATIVE'),
    add constraint uq_ftv_id_instance_mode unique (id, instance_mode);

create function form_spec_hash(p_spec jsonb) returns char(64)
language sql immutable as $$
    select encode(sha256(convert_to(p_spec::text, 'UTF8')), 'hex')
$$;

-- 可读集合哈希：仅 AUTHORITATIVE 版本，按版本ID升序；绑定角色与规格哈希。
create function form_source_set_hash(p_scope_id bigint) returns char(64)
language sql stable as $$
    select encode(sha256(convert_to(coalesce(string_agg(
        v.id::text || ':' || v.record_source_role || ':' || form_spec_hash(v.field_spec),
        ',' order by v.id), ''), 'UTF8')), 'hex')
    from form_template_versions v
    where v.scope_id = p_scope_id and v.record_source_role = 'AUTHORITATIVE'
$$;

-- 首次请求指纹：与 v5 FormFieldValueService.fingerprint 逐字节一致。
-- Jackson ObjectNode 插入顺序固定为 scopeId、context、storageVersionId、actor、fields；
-- p_fields 必须是规范字段的紧凑 JSON 文本（json 类型保留键顺序），不接受 jsonb 重排。
create function form_create_fingerprint(
    p_scope_id text, p_gitlab_base_url text, p_project_id text,
    p_resource_type text, p_resource_id text, p_template_key text,
    p_storage_version_id text, p_actor text, p_fields json) returns char(64)
language sql immutable as $$
    select encode(sha256(convert_to(
        '{"scopeId":' || to_json(p_scope_id)::text ||
        ',"context":{"gitlabBaseUrl":' || to_json(p_gitlab_base_url)::text ||
        ',"projectId":' || to_json(p_project_id)::text ||
        ',"resourceType":' || to_json(p_resource_type)::text ||
        ',"resourceId":' || to_json(p_resource_id)::text ||
        ',"templateKey":' || to_json(p_template_key)::text ||
        '},"storageVersionId":' || to_json(p_storage_version_id)::text ||
        ',"actor":' || to_json(p_actor)::text ||
        ',"fields":' || p_fields::text || '}', 'UTF8')), 'hex')
$$;

-- 可读集合只由版本列派生，不建来源角色副本表。
create view form_scope_readable_versions as
select v.scope_id, v.id as version_id, v.version_label, v.version_status,
       v.storage_kind, v.instance_mode, v.instance_table, v.record_table,
       v.source_table, v.field_spec, v.record_source_role
from form_template_versions v
where v.record_source_role = 'AUTHORITATIVE'
  and v.version_status in ('PUBLISHED', 'RETIRED');

-- ===== 稳定语义登记（IV-01 执行位置；不改写 field_spec） =====
-- 每个范围/版本/字段一行：stableId 与语义声明一次性登记，declared_type 必须等于
-- 该版冻结 field_spec 中该字段的真实 type；应用层登记与读取边界共同校验。
create table form_field_comparable_registrations (
    id            bigserial primary key,
    scope_id      bigint not null references form_template_scopes(id),
    version_id    bigint not null,
    field_name    varchar(128) not null,
    stable_id     varchar(64) not null,
    semantic_key  varchar(128) not null,
    declared_type varchar(16) not null
        check (declared_type in ('string', 'integer', 'enum', 'enum-array', 'date', 'boolean')),
    unit          varchar(32),
    meaning       varchar(255) not null,
    registered_by varchar(128) not null,
    registered_at timestamp not null default current_timestamp,
    unique (version_id, field_name),
    unique (version_id, stable_id),
    unique (version_id, semantic_key),
    foreign key (scope_id, version_id) references form_template_versions(scope_id, id)
);

-- ===== 定位目录（record_id 全局定位 + origin/完整C/首次指纹证据） =====
create table form_record_locations (
    record_id          bigint primary key,
    scope_id           bigint not null references form_template_scopes(id),
    storage_version_id bigint not null,
    origin_version_id  bigint not null,
    instance_mode      varchar(16) not null,
    instance_id        bigint,
    source_config_id   bigint not null,
    source_instance    varchar(128) not null,
    gitlab_base_url    varchar(255) not null,
    project_id         bigint not null check (project_id > 0),
    resource_type      varchar(64) not null check (resource_type in ('merge_request', 'issue')),
    resource_id        varchar(255) not null,
    request_key        varchar(64),
    created_by         varchar(128),
    origin_spec_hash   char(64) not null,
    create_payload_hash char(64),
    created_at         timestamp not null default current_timestamp,
    constraint fk_frl_version_scope foreign key (scope_id, storage_version_id)
        references form_template_versions(scope_id, id),
    constraint fk_frl_origin_scope foreign key (scope_id, origin_version_id)
        references form_template_versions(scope_id, id),
    constraint fk_frl_version_mode foreign key (storage_version_id, instance_mode)
        references form_template_versions(id, instance_mode),
    constraint ck_frl_request_key check (
        request_key is null or btrim(request_key) <> ''),
    constraint ck_frl_create_evidence check (
        (request_key is null) = (create_payload_hash is null)),
    constraint ck_frl_resource check (btrim(resource_id) <> ''),
    constraint ck_frl_instance check (
        (instance_mode = 'MATERIALIZED' and instance_id is not null and instance_id > 0)
        or (instance_mode = 'NONE' and instance_id is null))
);

create unique index uq_frl_scope_request
    on form_record_locations(scope_id, request_key)
    where request_key is not null;
create index idx_frl_version on form_record_locations(storage_version_id);
create index idx_frl_instance on form_record_locations(storage_version_id, instance_id)
    where instance_id is not null;
create index idx_frl_resource on form_record_locations(
    scope_id, resource_type, resource_id, project_id);
create index idx_frl_origin on form_record_locations(origin_version_id);

-- ===== 目录事件 / 游标 / 记录ID高水位 =====
create table form_directory_events (
    id                  bigserial primary key,
    scope_id            bigint not null references form_template_scopes(id),
    event_type          varchar(32) not null,
    source_set_revision bigint not null,
    payload             jsonb not null default '{}'::jsonb,
    operator_id         varchar(128),
    created_at          timestamp not null default current_timestamp,
    status              varchar(16) not null default 'PENDING',
    attempt_no          integer not null default 0 check (attempt_no >= 0),
    lease_owner         varchar(128),
    lease_until         timestamp,
    fencing_version     bigint not null default 0 check (fencing_version >= 0),
    next_attempt_at     timestamp,
    last_error          text,
    consumed_at         timestamp,
    constraint ck_fde_type check (event_type in
        ('VERSION_ADDED', 'CURRENT_CHANGED', 'LEGACY_REGISTERED', 'REGISTRATION_CORRECTED')),
    constraint ck_fde_status check (status in
        ('PENDING', 'LEASED', 'RETRY_WAIT', 'CONSUMED', 'FAILED')),
    constraint uq_fde_scope_revision unique (scope_id, source_set_revision, event_type)
);
create index idx_fde_claim on form_directory_events(status, next_attempt_at, lease_until)
    where status in ('PENDING', 'RETRY_WAIT', 'LEASED');

create table form_directory_event_cursors (
    scope_id            bigint primary key references form_template_scopes(id),
    last_consumed_event_id bigint not null default 0,
    updated_at          timestamp not null default current_timestamp
);

create table form_record_id_watermarks (
    source_ref varchar(128) primary key,
    high_water bigint not null check (high_water >= 0),
    updated_at timestamp not null default current_timestamp
);

-- 高水位只进不退：按“下一可分配值”比较，正确处理 is_called=false 且高水位相等。
-- 调用者须已持有 hashtext('form_record_id_seq') 事务锁（同事务可重入）。
create function form_advance_record_sequence(p_high bigint) returns bigint
language plpgsql as $$
declare
    v_last bigint;
    v_called boolean;
    v_next bigint;
begin
    if p_high is null or p_high < 0 then
        raise exception 'form_advance_record_sequence: high water must be >= 0'
            using errcode = '22023';
    end if;
    perform pg_advisory_xact_lock(hashtext('form_record_id_seq'));
    select last_value, is_called into v_last, v_called from form_record_id_seq;
    v_next := v_last + case when v_called then 1 else 0 end;
    if p_high >= v_next then
        perform setval('form_record_id_seq', p_high, true);
        return p_high;
    end if;
    return v_next - 1;
end $$;
