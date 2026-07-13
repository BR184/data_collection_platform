do $$
begin
  if to_regclass('ods_gitlab_merge_requests') is null
      or to_regclass('ods_gitlab_label_links') is null
      or to_regclass('ods_gitlab_labels') is null
      or to_regclass('merge_request_fact') is null then
    return;
  end if;

  with project_labels as (
    select
      ll.target_id as merge_request_id,
      (
        array_agg(
          btrim(regexp_replace(btrim(l.title), '^项目[[:space:]]*[:：][[:space:]]*', ''))
          order by coalesce(ll.source_updated_at, ll.updated_at, ll.created_at) asc nulls last, ll.id asc
        ) filter (
          where btrim(l.title) ~ '^项目[[:space:]]*[:：]'
            and btrim(regexp_replace(btrim(l.title), '^项目[[:space:]]*[:：][[:space:]]*', '')) <> ''
            and btrim(regexp_replace(btrim(l.title), '^项目[[:space:]]*[:：][[:space:]]*', '')) !~ '^[:：]'
        )
      )[1] as project_name
    from ods_gitlab_label_links ll
    join ods_gitlab_labels l
      on l.id = ll.label_id
     and coalesce(l.mirror_deleted, false) = false
    where coalesce(ll.mirror_deleted, false) = false
      and ll.target_type = 'MergeRequest'
    group by ll.target_id
  ), normalized as (
    select
      mr.id as merge_request_id,
      coalesce(nullif(project_labels.project_name, ''), '未标注项目名') as project_name
    from ods_gitlab_merge_requests mr
    left join project_labels
      on project_labels.merge_request_id = mr.id
    where mr.target_project_id = 9
  )
  update merge_request_fact fact
     set project_name = normalized.project_name,
         fact_refreshed_at = current_timestamp
    from normalized
   where fact.source_instance = 'default'
     and fact.project_id = 9
     and fact.merge_request_id = normalized.merge_request_id
     and fact.project_name is distinct from normalized.project_name;
end
$$;
