-- 逻辑中止先释放事实候选，未公开父子修订由既有维护调度分批清理。
-- 只增加发布控制元数据，不改写事实表或已公开版本。
alter table read_publications
    add column abort_cleanup_pending boolean not null default false,
    add constraint ck_read_publications_abort_cleanup
        check (not abort_cleanup_pending or state = 'ABORTED');

create index idx_read_publications_abort_cleanup
    on read_publications(domain_id, updated_at, id)
    where abort_cleanup_pending;
