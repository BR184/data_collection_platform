do $$
begin
  if to_regclass('public.merge_request_fact') is null
      or to_regclass('public.ods_gitlab_merge_requests') is null
      or to_regclass('public.ods_gitlab_merge_request_metrics') is null
      or to_regclass('public.ods_gitlab_users') is null then
    return;
  end if;

  with merge_users as (
      select mr.id as merge_request_id,
             mr.target_project_id as project_id,
             coalesce(
               nullif(btrim(metric_user.name), ''),
               nullif(btrim(mr_user.name), ''),
               nullif(btrim(author_user.name), '')
             ) as merge_user_name
        from ods_gitlab_merge_requests mr
        left join ods_gitlab_merge_request_metrics metrics
          on metrics.merge_request_id = mr.id
         and coalesce(metrics.mirror_deleted, false) = false
        left join ods_gitlab_users metric_user
          on metric_user.id = metrics.merged_by_id
         and coalesce(metric_user.mirror_deleted, false) = false
        left join ods_gitlab_users mr_user
          on mr_user.id = mr.merge_user_id
         and coalesce(mr_user.mirror_deleted, false) = false
        left join ods_gitlab_users author_user
          on author_user.id = mr.author_id
         and coalesce(author_user.mirror_deleted, false) = false
       where coalesce(mr.mirror_deleted, false) = false
  )
  update merge_request_fact fact
     set merge_user_name = merge_users.merge_user_name,
         updated_at = current_timestamp
    from merge_users
   where fact.source_system = 'GITLAB'
     and fact.merge_request_id = merge_users.merge_request_id
     and fact.project_id = merge_users.project_id
     and nullif(btrim(coalesce(fact.merge_user_name, '')), '') is null
     and merge_users.merge_user_name is not null;
end $$;
