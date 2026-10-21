-- 非 GitLab 事实来源共用一个发布生命周期；本表保存归一后的完整行，业务编辑表仍为写权威。
create table read_dataset_rows (
    id bigserial primary key,
    domain_id uuid not null references read_publication_domains(id),
    epoch_id uuid not null,
    table_key varchar(255) not null,
    row_key text not null,
    row_payload jsonb not null,
    created_by_publication_id uuid not null references read_publications(id),
    visible_from bigint,
    visible_to bigint,
    constraint ck_read_dataset_row_interval check (
        (visible_from is null and visible_to is null)
        or (visible_from > 0 and (visible_to is null or visible_to > visible_from)))
);
create unique index uq_read_dataset_candidate_row
    on read_dataset_rows(created_by_publication_id, table_key, row_key);
create unique index uq_read_dataset_open_row
    on read_dataset_rows(domain_id, epoch_id, table_key, row_key)
    where visible_from is not null and visible_to is null;
create index idx_read_dataset_visible_rows
    on read_dataset_rows(epoch_id, table_key, visible_from, visible_to, id);

-- 每个版本持有完整关系闭包和真实字段类型；空表也必须登记，不能把表缺失当成完整空集合。
create table read_dataset_tables (
    publication_id uuid not null references read_publications(id) on delete cascade,
    table_key varchar(255) not null,
    relation_name varchar(255) not null,
    schema_payload jsonb not null,
    key_columns jsonb not null,
    ignored_ingest_columns jsonb not null,
    captured_row_count bigint not null check (captured_row_count >= 0),
    captured_at timestamptz not null default current_timestamp,
    primary key(publication_id, table_key)
);
create table read_dataset_changes (
    publication_id uuid not null references read_publications(id) on delete cascade,
    table_key varchar(255) not null,
    row_key text not null,
    operation varchar(16) not null check (operation in ('UPSERT', 'DELETE')),
    previous_revision_id bigint,
    next_revision_id bigint,
    primary key(publication_id, table_key, row_key)
);

-- 镜像分页候选只能在整表成功后发布；失败运行保留旧公开指针，未公开修订可独立清理。
create table read_dataset_producer_intents (
    domain_id uuid primary key references read_publication_domains(id),
    owner_kind varchar(32) not null,
    owner_run_id varchar(128) not null,
    candidate_publication_id uuid not null references read_publications(id),
    complete_input boolean not null default false,
    error_detail text,
    updated_at timestamptz not null default current_timestamp
);

create table read_dataset_capture_proofs (
    publication_id uuid primary key references read_publications(id) on delete cascade,
    source_seal jsonb not null,
    definition_snapshot_id uuid not null references read_definition_snapshots(id),
    table_manifest jsonb not null,
    prepared_at timestamptz not null default current_timestamp
);

-- 已冻结输入不可再插入或改写业务负载；发布只允许修改可见区间。
create function guard_read_dataset_payload() returns trigger language plpgsql as $$
begin
    if tg_op = 'INSERT' or new.row_payload is distinct from old.row_payload
       or new.row_key is distinct from old.row_key or new.table_key is distinct from old.table_key
       or new.epoch_id is distinct from old.epoch_id or new.domain_id is distinct from old.domain_id
       or new.created_by_publication_id is distinct from old.created_by_publication_id then
        if new.visible_from is not null or not exists (
            select 1 from read_publications p where p.id = new.created_by_publication_id and p.state = 'BUILDING') then
            raise exception '数据集业务负载只能由 BUILDING 候选生成';
        end if;
    end if;
    return new;
end $$;
create trigger trg_read_dataset_immutable_payload before insert or update on read_dataset_rows
    for each row execute function guard_read_dataset_payload();
