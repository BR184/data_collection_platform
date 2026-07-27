insert into issue_scope_catalogs(project_id, project_name, dimension, enabled, remark)
values
    (
        9,
        coalesce(
            (select max(nullif(btrim(project_name), '')) from issue_fact where project_id = 9),
            'CrownCAD'
        ),
        'TESTING_PHASE',
        true,
        '由系统测试阶段定义迁移'
    ),
    (
        325,
        coalesce(
            (select max(nullif(btrim(project_name), '')) from issue_fact where project_id = 325),
            'CCProduct'
        ),
        'MILESTONE',
        true,
        '由客户问题真实里程碑初始化'
    );

insert into issue_scope_groups(
    catalog_id,
    business_key,
    display_name,
    sort_order,
    enabled,
    remark,
    created_at,
    updated_at
)
select catalog.id,
       legacy_group.name,
       legacy_group.name,
       legacy_group.sort_order,
       legacy_group.enabled,
       legacy_group.remark,
       legacy_group.created_at,
       legacy_group.updated_at
  from testing_phase_groups legacy_group
  join issue_scope_catalogs catalog
    on catalog.project_id = legacy_group.project_id
   and catalog.dimension = 'TESTING_PHASE';

insert into issue_scope_groups(
    catalog_id,
    business_key,
    display_name,
    sort_order,
    enabled,
    remark
)
select catalog.id,
       legacy_child.legacy_phase_name,
       legacy_child.legacy_phase_name,
       coalesce(min(legacy_child.legacy_sort_order), 0),
       bool_or(legacy_child.enabled),
       '由未分组系统测试阶段迁移'
  from testing_phase_calendar legacy_child
  join issue_scope_catalogs catalog
    on catalog.project_id = legacy_child.project_id
   and catalog.dimension = 'TESTING_PHASE'
  left join issue_scope_groups target_group
    on target_group.catalog_id = catalog.id
   and target_group.business_key = legacy_child.legacy_phase_name
 where legacy_child.legacy_phase_name is not null
   and btrim(legacy_child.legacy_phase_name) <> ''
   and target_group.id is null
 group by catalog.id, legacy_child.legacy_phase_name;

insert into issue_scope_members(
    catalog_id,
    group_id,
    source_value,
    display_name,
    sort_order,
    active_from,
    active_until,
    enabled,
    source_reference_id,
    remark,
    created_at,
    updated_at
)
select catalog.id,
       target_group.id,
       legacy_child.testing_phase,
       legacy_child.testing_phase,
       coalesce(legacy_child.child_sort_order, legacy_child.legacy_sort_order, 0),
       legacy_child.phase_start_at,
       legacy_child.phase_end_at,
       legacy_child.enabled,
       legacy_child.legacy_source_id,
       legacy_child.remark,
       legacy_child.created_at,
       legacy_child.updated_at
  from testing_phase_calendar legacy_child
  join issue_scope_catalogs catalog
    on catalog.project_id = legacy_child.project_id
   and catalog.dimension = 'TESTING_PHASE'
  join issue_scope_groups target_group
    on target_group.catalog_id = catalog.id
   and target_group.business_key = legacy_child.legacy_phase_name;

with milestone_values as (
    select distinct
           btrim(milestone_title) as source_value,
           case
               when btrim(milestone_title) ~* '^CC\s*[0-9]{4}\s*R\s*[0-9]+$'
                   then upper(regexp_replace(btrim(milestone_title), '\s+', '', 'g'))
               else btrim(milestone_title)
           end as business_key
      from issue_fact
     where project_id = 325
       and deleted = false
       and nullif(btrim(milestone_title), '') is not null
),
milestone_groups as (
    select business_key,
           (array_agg(
               source_value
               order by
                   case when source_value ~* '^CC\s*[0-9]{4}\s+R\s*[0-9]+$' then 0 else 1 end,
                   source_value
           ))[1] as display_name
      from milestone_values
     group by business_key
),
ordered_groups as (
    select business_key,
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
      from milestone_groups
)
insert into issue_scope_groups(
    catalog_id,
    business_key,
    display_name,
    sort_order,
    enabled,
    remark
)
select catalog.id,
       ordered.business_key,
       ordered.display_name,
       ordered.sort_order,
       true,
       '由客户问题事实里程碑初始化'
  from ordered_groups ordered
  cross join issue_scope_catalogs catalog
 where catalog.project_id = 325
   and catalog.dimension = 'MILESTONE';

with milestone_values as (
    select distinct
           btrim(milestone_title) as source_value,
           case
               when btrim(milestone_title) ~* '^CC\s*[0-9]{4}\s*R\s*[0-9]+$'
                   then upper(regexp_replace(btrim(milestone_title), '\s+', '', 'g'))
               else btrim(milestone_title)
           end as business_key
      from issue_fact
     where project_id = 325
       and deleted = false
       and nullif(btrim(milestone_title), '') is not null
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
select catalog.id,
       target_group.id,
       milestone.source_value,
       milestone.source_value,
       row_number() over (
           partition by target_group.id
           order by milestone.source_value
       )::integer,
       true,
       '由客户问题事实里程碑初始化'
  from milestone_values milestone
  cross join issue_scope_catalogs catalog
  join issue_scope_groups target_group
    on target_group.catalog_id = catalog.id
   and target_group.business_key = milestone.business_key
 where catalog.project_id = 325
   and catalog.dimension = 'MILESTONE';

