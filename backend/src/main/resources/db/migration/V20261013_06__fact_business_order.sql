-- 物理主键属于修订；既有默认行序属于业务根。新修订沿用业务根第一次出现的顺序。
alter table issue_fact add column business_order_id bigint;
alter table merge_request_fact add column business_order_id bigint;
alter table integration_test_fact add column business_order_id bigint;
update issue_fact set business_order_id = id;
update merge_request_fact set business_order_id = id;
update integration_test_fact set business_order_id = id;
alter table issue_fact alter column business_order_id set not null;
alter table merge_request_fact alter column business_order_id set not null;
alter table integration_test_fact alter column business_order_id set not null;

create function preserve_fact_business_order() returns trigger language plpgsql as $$
declare
    previous_order bigint;
    root_id bigint;
begin
    if tg_op = 'UPDATE' then
        if new.business_order_id is distinct from old.business_order_id then
            raise exception 'fact business order is immutable';
        end if;
        return new;
    end if;
    root_id := (to_jsonb(new)->>tg_argv[0])::bigint;
    execute format('select min(business_order_id) from %I where source_system = $1 and source_instance = $2 and project_id = $3 and %I = $4',
                   tg_table_name, tg_argv[0])
        into previous_order using new.source_system, new.source_instance, new.project_id, root_id;
    new.business_order_id := coalesce(previous_order, new.id);
    return new;
end;
$$;
create trigger issue_fact_business_order before insert or update on issue_fact
    for each row execute function preserve_fact_business_order('issue_id');
create trigger merge_request_fact_business_order before insert or update on merge_request_fact
    for each row execute function preserve_fact_business_order('merge_request_id');
create trigger integration_test_fact_business_order before insert or update on integration_test_fact
    for each row execute function preserve_fact_business_order('issue_id');
