-- N4 P3 捕获基础：head 捕获快照与持久权威资源索引。
--
-- 捕获表保存一次消费构建的集合修订/哈希、版本清单、head 修订与 pending 逐项快照；
-- 结算在同一事务内复核这些值，任一漂移零公开零结算。表只追加/更新单行，不保存每轮历史副本。
-- head 的 pending 源集合列已由 V20261029_01 建立，本迁移不重复定义。

create table if not exists form_head_captures (
    head_id                    bigint primary key references form_change_heads(id) on delete cascade,
    capture_token              uuid not null default gen_random_uuid(),
    source_set_revision        bigint not null,
    source_set_hash            char(64) not null,
    versions                   jsonb not null,
    latest_change_version      bigint not null,
    authority_revision         bigint,
    pending_record_id          bigint,
    pending_record_version     integer,
    pending_storage_version_id bigint,
    pending_source_set_revision bigint,
    pending_source_set_hash    char(64),
    pending_authority_revision bigint,
    lease_owner                varchar(128) not null,
    fencing_version            bigint not null,
    status                     varchar(16) not null default 'CAPTURED'
        check (status in ('CAPTURED', 'CONSUMED', 'DISCARDED')),
    captured_at                timestamp not null default current_timestamp,
    consumed_at                timestamp
);

create index if not exists idx_form_head_captures_status
    on form_head_captures(status, captured_at);

-- 消费按完整来源身份查询持久权威；唯一键以 source_config_id 开头，按来源实例批查需要独立索引。
create index if not exists idx_form_resource_authorities_source_resource
    on form_resource_authorities(source_instance, project_id, resource_type, resource_id);
