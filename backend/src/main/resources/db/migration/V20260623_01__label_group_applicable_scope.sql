alter table label_groups
    add column if not exists applicable_scope varchar(16) not null default 'SAME_TYPE';

alter table label_groups
    add column if not exists source_field_key varchar(100);

do $$
begin
    if not exists (
        select 1
        from pg_constraint
        where conname = 'ck_label_groups_scope'
    ) then
        alter table label_groups
            add constraint ck_label_groups_scope check (applicable_scope in ('SAME_TYPE', 'SAME_FIELD'));
    end if;
end $$;
