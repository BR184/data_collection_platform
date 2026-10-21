-- 捕获冻结后禁止删除/修改候选负载；中止先进入 ABORTED，回收先锁定并进入 RETIRING。
-- 发布只能将区间推进到本域本次已分配的新序号，不允许回写已服务过的历史区间。
create or replace function guard_read_dataset_payload() returns trigger language plpgsql as $$
declare
    owner_state varchar(32);
    allocated_sequence bigint;
begin
    if tg_op = 'DELETE' then
        select state into owner_state from read_publications where id = old.created_by_publication_id;
        if owner_state not in ('BUILDING', 'ABORTED', 'RETIRING', 'RETIRED') then
            raise exception '数据集负载必须先中止或取得回收资格才能删除';
        end if;
        return old;
    end if;
    if tg_op = 'INSERT' or new.row_payload is distinct from old.row_payload
       or new.row_key is distinct from old.row_key or new.table_key is distinct from old.table_key
       or new.epoch_id is distinct from old.epoch_id or new.domain_id is distinct from old.domain_id
       or new.created_by_publication_id is distinct from old.created_by_publication_id then
        if new.visible_from is not null or not exists (
            select 1 from read_publications p where p.id = new.created_by_publication_id and p.state = 'BUILDING') then
            raise exception '数据集业务负载只能由 BUILDING 候选生成';
        end if;
    end if;
    if tg_op = 'UPDATE' then
        if old.visible_from is not null and new.visible_from is distinct from old.visible_from
           or old.visible_to is not null and new.visible_to is distinct from old.visible_to then
            raise exception '已发布数据集的历史可见区间不可改写';
        end if;
        if new.visible_from is distinct from old.visible_from or new.visible_to is distinct from old.visible_to then
            select next_sequence - 1 into allocated_sequence from read_publication_domains where id = new.domain_id;
            if not exists(select 1 from read_publications candidate
                where candidate.domain_id = new.domain_id and candidate.state = 'READY_TO_PUBLISH')
               or (new.visible_from is distinct from old.visible_from and new.visible_from is distinct from allocated_sequence)
               or (new.visible_to is distinct from old.visible_to and new.visible_to is distinct from allocated_sequence) then
                raise exception '数据集可见区间只能在唯一发布事务推进到本次序号';
            end if;
        end if;
    end if;
    return new;
end $$;
drop trigger trg_read_dataset_immutable_payload on read_dataset_rows;
create trigger trg_read_dataset_immutable_payload before insert or update or delete on read_dataset_rows
    for each row execute function guard_read_dataset_payload();

alter table read_publications add column abort_reason text;
