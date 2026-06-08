-- Legacy issue tag group seed.
-- Mirrors the old platform LabelName dimensions from issue_fact.label_names
-- without changing the issue_fact schema.

with seed_groups(domain, group_key, label, selection_mode, sort_order, match_strategy_name, remark) as (
  values
    ('issue', 'module', '模块', 'multiple', 10, 'split_exact_comma', 'Legacy 模块 + 工具箱 merged into module_names'),
    ('issue', 'software', '软件', 'multiple', 20, 'like', 'Legacy 软件：X labels matched from label_names'),
    ('issue', 'project_label', '项目', 'multiple', 30, 'like', 'Legacy 项目：X labels matched from label_names'),
    ('issue', 'status', '状态', 'multiple', 40, 'eq', 'Issue state field'),
    ('issue', 'phase', '测试阶段', 'multiple', 50, 'eq', 'phase_filter_value field'),
    ('issue', 'severity', '严重程度', 'multiple', 60, 'eq', 'severity_level field'),
    ('issue', 'category', '类别', 'multiple', 70, 'eq', 'category field'),
    ('issue', 'urgency', '紧急程度', 'multiple', 80, 'eq', 'urgency field'),
    ('issue', 'delay_cause', '延期原因', 'multiple', 90, 'eq', 'delay_cause field')
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
   where existing.domain = 'issue'
     and existing.group_key in (
       'module',
       'software',
       'project_label',
       'status',
       'phase',
       'severity',
       'category',
       'urgency',
       'delay_cause'
     )
     and not exists (
       select 1
         from upserted_groups upserted
        where upserted.domain = existing.domain
          and upserted.group_key = existing.group_key
     )
),
legacy_labels as (
  select distinct btrim(raw_label) as label
    from issue_fact fact
   cross join lateral regexp_split_to_table(coalesce(fact.label_names, ''), '\s*,\s*') as raw_label
   where coalesce(fact.deleted, false) = false
     and btrim(raw_label) <> ''
),
prefixed_values as (
  select case split_part(label, '：', 1)
           when '模块' then 'module'
           when '工具箱' then 'module'
           when '软件' then 'software'
           when '项目' then 'project_label'
           when '状态' then 'status'
           when '测试阶段' then 'phase'
           when '严重程度' then 'severity'
           when '类别' then 'category'
         end as group_key,
         split_part(label, '：', 1) as source_field,
         nullif(btrim(substring(label from position('：' in label) + 1)), '') as label,
         label as raw_value
    from legacy_labels
   where position('：' in label) > 1
     and split_part(label, '：', 1) in ('模块', '工具箱', '软件', '项目', '状态', '测试阶段', '严重程度', '类别')
),
keyword_phase_values as (
  select 'phase' as group_key,
         null::text as source_field,
         label,
         label as raw_value
    from legacy_labels
   where label not like '%：%'
     and (label like '%系统测试%' or label like '%回归测试%' or label like '%集成测试%')
),
urgency_values as (
  select 'urgency' as group_key,
         null::text as source_field,
         label,
         label as raw_value
    from legacy_labels
   where label in ('P1', 'P2', 'P3')
),
delay_cause_values as (
  select 'delay_cause' as group_key,
         null::text as source_field,
         label,
         label as raw_value
    from legacy_labels
   where label in ('技术卡点', '方案卡点', '资源卡点', '数据异常', '算法问题', '机制问题', '计算效率')
),
legacy_values as (
  select group_key, source_field, label, raw_value, 'legacy_label_prefix' as source_type
    from prefixed_values
   where label is not null
     and label not like '未设定%'
  union all
  select group_key, source_field, label, raw_value, 'legacy_label_keyword' as source_type
    from keyword_phase_values
   where label not like '未设定%'
  union all
  select group_key, source_field, label, raw_value, 'legacy_label_full' as source_type
    from urgency_values
  union all
  select group_key, source_field, label, raw_value, 'legacy_label_full' as source_type
    from delay_cause_values
),
seed_values as (
  select group_key,
         group_key || '_' || md5(label) as value_key,
         label,
         'standard' as value_type,
         dense_rank() over (partition by group_key order by label) * 10 as sort_order,
         'Legacy issue label seed from issue_fact.label_names' as remark
    from (
      select distinct group_key, label
        from legacy_values
    ) deduped_values
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
         seed_value.remark
    from seed_values seed_value
    join all_groups groups
      on groups.domain = 'issue'
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
),
mapping_values as (
  select distinct seed_values.group_key,
         seed_values.value_key,
         legacy_values.source_type,
         legacy_values.source_field,
         legacy_values.raw_value
    from legacy_values
    join seed_values
      on seed_values.group_key = legacy_values.group_key
     and lower(seed_values.label) = lower(legacy_values.label)
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
       mapping_values.source_type,
       mapping_values.source_field,
       mapping_values.raw_value,
       'exact',
       null,
       true,
       'Legacy issue label mapping from issue_fact.label_names'
  from mapping_values
  join all_groups groups
    on groups.domain = 'issue'
   and groups.group_key = mapping_values.group_key
  join tag_value tag_value_ref
    on tag_value_ref.group_id = groups.id
   and tag_value_ref.value_key = mapping_values.value_key
 where not exists (
   select 1
     from tag_value_mapping existing
    where existing.value_id = tag_value_ref.id
      and existing.source_type = mapping_values.source_type
      and coalesce(existing.source_field, '') = coalesce(mapping_values.source_field, '')
      and existing.raw_value = mapping_values.raw_value
      and existing.match_type = 'exact'
      and existing.source_instance is null
 );
