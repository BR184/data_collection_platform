with fact_values as (
    select distinct
           btrim(f.milestone_title) as source_value,
           case
               when btrim(f.milestone_title) ~* '^CC\s*[0-9]{4}\s*R\s*[0-9]+$'
                   then upper(regexp_replace(btrim(f.milestone_title), '\s+', '', 'g'))
               else btrim(f.milestone_title)
           end as business_key
      from issue_fact f
     where f.project_id = 325
       and f.deleted = false
       and nullif(btrim(f.milestone_title), '') is not null
), catalogs as (
    select c.id
      from issue_scope_catalogs c
     where c.project_id = 325
       and c.dimension = 'MILESTONE'
       and c.enabled = true
       and not exists (
           select 1
             from issue_scope_groups g
            where g.catalog_id = c.id
       )
), group_values as (
    select c.id as catalog_id,
           min(f.business_key) as business_key,
           (array_agg(
               f.source_value
               order by
                   case when f.source_value ~* '^CC\s*[0-9]{4}\s+R\s*[0-9]+$' then 0 else 1 end,
                   f.source_value
           ))[1] as display_name
      from catalogs c
     join fact_values f on length(f.business_key) <= 128
                        and length(f.source_value) <= 255
      group by c.id, lower(f.business_key)
), ordered_groups as (
    select catalog_id,
           business_key,
           display_name,
           row_number() over (
               order by
                   case when business_key ~ '^CC[0-9]{4}R[0-9]+$'
                       then substring(business_key from '^CC([0-9]{4})R')::integer
                       else null
                   end desc nulls last,
                   case when business_key ~ '^CC[0-9]{4}R[0-9]+$'
                       then substring(business_key from 'R([0-9]+)$')::integer
                       else null
                   end desc nulls last,
                   business_key desc
           )::integer as sort_order
      from group_values
), inserted_groups as (
    insert into issue_scope_groups(
        catalog_id,
        business_key,
        display_name,
        sort_order,
        enabled,
        remark
    )
    select catalog_id,
           business_key,
           display_name,
           sort_order,
           true,
           '由已有客户问题事实初始化'
      from ordered_groups
    returning id, catalog_id, business_key
)
insert into issue_scope_members(
    catalog_id,
    group_id,
    source_value,
    display_name,
    sort_order,
    enabled,
    remark
)
select groups.catalog_id,
       groups.id,
       facts.source_value,
       facts.source_value,
       row_number() over (
           partition by groups.id
           order by facts.source_value
       )::integer,
       true,
       '由已有客户问题事实初始化'
  from fact_values facts
  join inserted_groups groups
    on lower(groups.business_key) = lower(facts.business_key)
   and length(facts.business_key) <= 128
   and length(facts.source_value) <= 255
on conflict (catalog_id, source_value) do nothing;
