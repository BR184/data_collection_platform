create table if not exists statistic_board_snapshots (
    id bigserial primary key,
    board_key varchar(128) not null,
    scope_key varchar(512) not null,
    rule_version varchar(128) not null,
    source_version varchar(128),
    filter_hash varchar(64) not null,
    filter_payload jsonb not null default '{}'::jsonb,
    applied_filter_payload jsonb not null default '{}'::jsonb,
    row_payload jsonb not null default '[]'::jsonb,
    meta_payload jsonb not null default '{}'::jsonb,
    status varchar(32) not null default 'READY',
    error_message text,
    generated_at timestamp not null default current_timestamp,
    refreshed_at timestamp not null default current_timestamp,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_statistic_board_snapshots_scope unique (board_key, scope_key, rule_version, filter_hash)
);

create index if not exists idx_statistic_board_snapshots_lookup
    on statistic_board_snapshots(board_key, scope_key, rule_version, source_version, filter_hash, status);

create index if not exists idx_statistic_board_snapshots_refreshed
    on statistic_board_snapshots(board_key, refreshed_at desc);
