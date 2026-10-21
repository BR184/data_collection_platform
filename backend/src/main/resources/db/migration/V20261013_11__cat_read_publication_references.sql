-- CAT 原快照作为发布内容的受保护引用；活跃页面只保护平台发布身份，统一回收才释放此 FK。
create table read_publication_cat_snapshots (
    publication_id uuid primary key references read_publications(id) on delete cascade,
    snapshot_id uuid not null references bi_cat_test_snapshots(snapshot_id),
    published_version bigint not null check (published_version > 0),
    product_version_id bigint not null check (product_version_id > 0),
    product_version_key varchar(128) not null,
    test_stage varchar(32) not null check (test_stage in ('UNIT_TEST', 'INTEGRATION_TEST')),
    module_count bigint not null check (module_count >= 0),
    function_count bigint not null check (function_count >= 0)
);
create index idx_read_publication_cat_snapshot on read_publication_cat_snapshots(snapshot_id);
create trigger trg_read_cat_reference_immutable before insert or update or delete on read_publication_cat_snapshots
    for each row execute function guard_read_dataset_metadata();
