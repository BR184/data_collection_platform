-- N4 R04：表单捕获与实际 read-publication 候选绑定。
--
-- 捕获只在构建前冻结输入身份；候选公开事务内按绑定逐项复核 head、集合、权威、租约与
-- 捕获令牌，指针推进与 consumed 结算同事务提交；任一漂移回滚公开与结算。

create table if not exists form_publication_captures (
    publication_id             uuid not null references read_publications(id) on delete cascade,
    head_id                    bigint not null references form_change_heads(id),
    scope_id                   bigint not null references form_template_scopes(id),
    capture_token              uuid not null,
    source_set_revision        bigint not null,
    source_set_hash            char(64) not null,
    latest_change_version      bigint not null,
    authority_revision         bigint not null,
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
    consumed_at                timestamp,
    primary key (publication_id, head_id)
);

create index if not exists idx_form_publication_captures_head
    on form_publication_captures(head_id, status);
