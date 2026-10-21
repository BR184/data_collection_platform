-- 提交后的完整输入变更按拥有者合并；事务回滚不会留下虚假刷新请求。
create table read_dataset_refresh_requests (
    producer_kind varchar(32) not null,
    source_instance varchar(128) not null default 'default',
    table_key varchar(255) not null default '',
    requested_revision bigint not null default 1,
    completed_revision bigint not null default 0,
    lease_owner uuid,
    lease_until timestamptz,
    last_error text,
    retry_after timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    primary key (producer_kind, source_instance, table_key),
    check (requested_revision > 0 and completed_revision >= 0 and completed_revision <= requested_revision)
);

create function request_dataset_refresh() returns trigger language plpgsql as $$
begin
    insert into read_dataset_refresh_requests (producer_kind)
    values (tg_argv[0])
    on conflict (producer_kind, source_instance, table_key) do update
        set requested_revision = read_dataset_refresh_requests.requested_revision + 1,
            retry_after = current_timestamp, updated_at = current_timestamp;
    return null;
end;
$$;

-- 原子换名的走查主表不保留触发器，因此由稳定的来源装载清单触发整批请求。
create trigger request_walkthrough_dataset after insert or update or delete on legacy_mysql_imported_tables
    for each statement execute function request_dataset_refresh('COMPAT_WALKTHROUGH');
create trigger request_review_dataset after insert or update or delete on legacy_mongo_imported_collections
    for each statement execute function request_dataset_refresh('COMPAT_REVIEW');
do $$
declare relation_name text;
begin
    foreach relation_name in array array['review_records', 'review_record_experts', 'review_problem_items',
        'review_record_descriptions', 'review_record_contents', 'code_review_external_metrics',
        'code_review_match_mode_db_settings'] loop
        execute format('create trigger request_formal_dataset after insert or update or delete on %I'
            || ' for each statement execute function request_dataset_refresh(''FORMAL_RECORD'')', relation_name);
    end loop;
end;
$$;
