create table if not exists page_record_snapshots (
    id bigserial primary key,
    page_key varchar(128) not null,
    snapshot_type varchar(64) not null,
    scope_key varchar(512) not null,
    rule_version varchar(128) not null,
    source_version varchar(256),
    request_hash varchar(64) not null,
    request_payload jsonb not null default '{}'::jsonb,
    response_payload jsonb not null default '{}'::jsonb,
    status varchar(32) not null default 'READY',
    error_message text,
    generated_at timestamp not null default current_timestamp,
    refreshed_at timestamp not null default current_timestamp,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_page_record_snapshots_scope
        unique (page_key, snapshot_type, scope_key, rule_version, request_hash)
);

create index if not exists idx_page_record_snapshots_lookup
    on page_record_snapshots(page_key, snapshot_type, scope_key, rule_version, source_version, request_hash, status);

create index if not exists idx_page_record_snapshots_refreshed
    on page_record_snapshots(page_key, refreshed_at desc);
