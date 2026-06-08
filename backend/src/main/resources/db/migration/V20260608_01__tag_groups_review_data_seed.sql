-- Phase-one review_data tag group seed.
-- This is intentionally a small, idempotent data migration: it creates standard
-- selectable groups/values for the review data page without rebuilding module aliases.

with seed_groups(domain, group_key, label, selection_mode, sort_order, match_strategy_name, remark) as (
  values
    ('review_data', 'module', '评审模块', 'multiple', 10, 'eq', 'Review record module field; no module alias rebuild in this seed'),
    ('review_data', 'review_type', '评审类型', 'multiple', 20, 'eq', 'Review record review_type field'),
    ('review_data', 'review_owner', '评审负责人', 'multiple', 30, 'eq', 'Review record review_owner field'),
    ('review_data', 'problem_status', '问题状态', 'multiple', 40, 'eq', 'Review problem item problem_status field'),
    ('review_data', 'problem_category', '问题类别', 'multiple', 50, 'eq', 'Review problem item problem_category field'),
    ('review_data', 'review_category', '评审类别', 'multiple', 60, 'eq', 'Reserved standard group for review category display')
),
upserted_groups as (
  insert into tag_group (
    domain,
    group_key,
    label,
    selection_mode,
    sort_order,
    match_strategy_name,
    enabled,
    remark
  )
  select domain,
         group_key,
         label,
         selection_mode,
         sort_order,
         match_strategy_name,
         true,
         remark
    from seed_groups
  on conflict (domain, group_key) do update
  set label = excluded.label,
      selection_mode = excluded.selection_mode,
      sort_order = excluded.sort_order,
      match_strategy_name = excluded.match_strategy_name,
      enabled = true,
      remark = excluded.remark,
      updated_at = current_timestamp
  returning id, domain, group_key
),
all_groups as (
  select id, domain, group_key
    from upserted_groups
  union all
  select existing.id, existing.domain, existing.group_key
    from tag_group existing
   where existing.domain = 'review_data'
     and existing.group_key in (
       'module',
       'review_type',
       'review_owner',
       'problem_status',
       'problem_category',
       'review_category'
     )
     and not exists (
       select 1
         from upserted_groups upserted
        where upserted.domain = existing.domain
          and upserted.group_key = existing.group_key
     )
),
seed_values(group_key, value_key, label, value_type, sort_order, source_field) as (
  values
    ('module', 'sketch', '草图', 'standard', 10, 'module_name'),
    ('module', 'surface', '曲面', 'standard', 20, 'module_name'),
    ('module', 'toolbox', '工具箱', 'standard', 30, 'module_name'),
    ('review_type', 'requirements_review', '需求评审', 'standard', 10, 'review_type'),
    ('review_type', 'design_review', '设计评审', 'standard', 20, 'review_type'),
    ('review_type', 'code_review', '代码评审', 'standard', 30, 'review_type'),
    ('review_owner', 'unassigned', '未指定负责人', 'unmapped', 10, 'review_owner'),
    ('problem_status', 'new', '新提交', 'standard', 10, 'problem_status'),
    ('problem_status', 'confirmed', '已确认', 'standard', 20, 'problem_status'),
    ('problem_status', 'fixed', '已修复', 'standard', 30, 'problem_status'),
    ('problem_status', 'rejected', '已拒绝', 'standard', 40, 'problem_status'),
    ('problem_status', 'no_problem', '无问题', 'standard', 50, 'problem_status'),
    ('problem_category', 'requirements', '需求问题', 'standard', 10, 'problem_category'),
    ('problem_category', 'design', '设计问题', 'standard', 20, 'problem_category'),
    ('problem_category', 'implementation', '实现问题', 'standard', 30, 'problem_category'),
    ('problem_category', 'document', '文档问题', 'standard', 40, 'problem_category'),
    ('problem_category', 'no_problem', '无问题', 'standard', 50, 'problem_category'),
    ('review_category', 'independent', '独立评审', 'standard', 10, null),
    ('review_category', 'meeting', '会议评审', 'standard', 20, null)
),
upserted_values as (
  insert into tag_value (
    group_id,
    value_key,
    label,
    value_type,
    sort_order,
    enabled,
    disabled,
    remark
  )
  select groups.id,
         seed_value.value_key,
         seed_value.label,
         seed_value.value_type,
         seed_value.sort_order,
         true,
         false,
         'Phase-one review_data standard seed'
    from seed_values seed_value
    join all_groups groups
      on groups.domain = 'review_data'
     and groups.group_key = seed_value.group_key
  on conflict (group_id, value_key) do update
  set label = excluded.label,
      value_type = excluded.value_type,
      sort_order = excluded.sort_order,
      enabled = true,
      disabled = false,
      remark = excluded.remark,
      updated_at = current_timestamp
  returning id, group_id, value_key
)
insert into tag_value_mapping (
  value_id,
  source_type,
  source_field,
  raw_value,
  match_type,
  source_instance,
  enabled,
  remark
)
select tag_value_ref.id,
       'review_field',
       seed_values.source_field,
       seed_values.label,
       'exact',
       null,
       true,
       'Phase-one review_data exact value mapping'
  from seed_values
  join all_groups groups
    on groups.domain = 'review_data'
   and groups.group_key = seed_values.group_key
  join tag_value tag_value_ref
    on tag_value_ref.group_id = groups.id
   and tag_value_ref.value_key = seed_values.value_key
 where seed_values.source_field is not null
   and seed_values.group_key <> 'module'
   and not exists (
     select 1
       from tag_value_mapping existing
      where existing.value_id = tag_value_ref.id
        and existing.source_type = 'review_field'
        and coalesce(existing.source_field, '') = coalesce(seed_values.source_field, '')
        and existing.raw_value = seed_values.label
        and existing.match_type = 'exact'
        and existing.source_instance is null
   );
