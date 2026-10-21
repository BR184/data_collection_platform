-- 模式和捕获证明与发布负载一样不可改写，否则旧读取身份会被当前表结构重新解释。
create function guard_read_dataset_metadata() returns trigger language plpgsql as $$
declare
    owner_id uuid;
    owner_state varchar(32);
begin
    owner_id := case when tg_op = 'DELETE' then old.publication_id else new.publication_id end;
    select state into owner_state from read_publications where id = owner_id for share;
    if tg_op = 'DELETE' then
        if owner_state is not null and owner_state not in ('BUILDING', 'ABORTED', 'RETIRING', 'RETIRED') then
            raise exception '数据集模式必须先中止或取得回收资格才能删除';
        end if;
        return old;
    end if;
    if owner_state is distinct from 'BUILDING' then
        raise exception '数据集模式与捕获证明只能由 BUILDING 候选生成';
    end if;
    if tg_op = 'UPDATE' and new.publication_id is distinct from old.publication_id then
        raise exception '数据集模式和捕获证明不可更改归属';
    end if;
    return new;
end $$;
create trigger trg_read_dataset_schema_immutable before insert or update or delete on read_dataset_tables
    for each row execute function guard_read_dataset_metadata();
create trigger trg_read_dataset_proof_immutable before insert or update or delete on read_dataset_capture_proofs
    for each row execute function guard_read_dataset_metadata();
