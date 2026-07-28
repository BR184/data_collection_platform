with fact_values as (
    select distinct
           btrim(milestone_title) as source_value,
           case
               when btrim(milestone_title) ~* '^CC\s*[0-9]{4}\s*R\s*[0-9]+$'
                   then upper(regexp_replace(btrim(milestone_title), '\s+', '', 'g'))
               else lower(btrim(milestone_title))
           end as business_key
      from issue_fact
     where project_id = 325
       and deleted = false
       and nullif(btrim(milestone_title), '') is not null
), missing_members as (
    select c.id as catalog_id,
           g.id as group_id,
           f.source_value,
           row_number() over (partition by g.id order by f.source_value) as member_offset
      from fact_values f
      join issue_scope_catalogs c
        on c.project_id = 325
       and c.dimension = 'MILESTONE'
       and c.enabled = true
      join issue_scope_groups g
        on g.catalog_id = c.id
       and g.enabled = true
       and case
               when btrim(g.business_key) ~* '^CC\s*[0-9]{4}\s*R\s*[0-9]+$'
                   then upper(regexp_replace(btrim(g.business_key), '\s+', '', 'g'))
               else lower(btrim(g.business_key))
           end = f.business_key
     where not exists (
         select 1
           from issue_scope_members m
          where m.catalog_id = c.id
            and lower(m.source_value) = lower(f.source_value)
     )
), group_orders as (
    select group_id, coalesce(max(sort_order), 0) as max_sort_order
      from issue_scope_members
     group by group_id
)
insert into issue_scope_members(
    catalog_id, group_id, source_value, display_name, sort_order, enabled, remark
)
select missing.catalog_id,
       missing.group_id,
       missing.source_value,
       missing.source_value,
       coalesce(orders.max_sort_order, 0) + missing.member_offset,
       true,
       '由已发布客户事实自动补齐'
  from missing_members missing
  left join group_orders orders on orders.group_id = missing.group_id
on conflict (catalog_id, source_value) do nothing;
