with assignee_merge_users as (
    select mr.id as merge_request_id,
           mr.target_project_id as project_id,
           coalesce(
             nullif(btrim(assignees.assignee_names), ''),
             nullif(btrim(metric_user.name), ''),
             nullif(btrim(mr_user.name), '')
           ) as merge_user_name
      from ods_gitlab_merge_requests mr
      left join (
        select ma.merge_request_id,
               string_agg(distinct u.name, ', ' order by u.name) as assignee_names
          from ods_gitlab_merge_request_assignees ma
          join ods_gitlab_users u
            on u.id = ma.user_id
           and coalesce(u.mirror_deleted, false) = false
         where coalesce(ma.mirror_deleted, false) = false
         group by ma.merge_request_id
      ) assignees
        on assignees.merge_request_id = mr.id
      left join ods_gitlab_merge_request_metrics metrics
        on metrics.merge_request_id = mr.id
       and coalesce(metrics.mirror_deleted, false) = false
      left join ods_gitlab_users metric_user
        on metric_user.id = metrics.merged_by_id
       and coalesce(metric_user.mirror_deleted, false) = false
      left join ods_gitlab_users mr_user
        on mr_user.id = mr.merge_user_id
       and coalesce(mr_user.mirror_deleted, false) = false
     where coalesce(mr.mirror_deleted, false) = false
)
update merge_request_fact fact
   set merge_user_name = assignee_merge_users.merge_user_name,
       updated_at = current_timestamp
  from assignee_merge_users
 where fact.source_system = 'GITLAB'
   and fact.merge_request_id = assignee_merge_users.merge_request_id
   and fact.project_id = assignee_merge_users.project_id
   and assignee_merge_users.merge_user_name is not null
   and coalesce(fact.merge_user_name, '') <> assignee_merge_users.merge_user_name;
