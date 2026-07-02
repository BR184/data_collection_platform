with merge_users as (
    select mr.id as merge_request_id,
           mr.target_project_id as project_id,
           coalesce(nullif(btrim(metric_user.name), ''), nullif(btrim(mr_user.name), '')) as merge_user_name
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
     where coalesce(mr.mirror_deleted, false) = false
),
module_labels as (
    select ll.target_id as merge_request_id,
           string_agg(distinct module_value, ' & ' order by module_value) as module_name
      from (
        select ll.target_id,
               nullif(
                 btrim(
                   regexp_replace(
                     l.title,
                     '^[[:space:]]*(模块|工具箱)[[:space:]]*[:：][[:space:]]*',
                     ''
                   )
                 ),
                 ''
               ) as module_value
          from ods_gitlab_label_links ll
          join ods_gitlab_labels l
            on l.id = ll.label_id
           and coalesce(l.mirror_deleted, false) = false
         where coalesce(ll.mirror_deleted, false) = false
           and ll.target_type = 'MergeRequest'
           and l.title ~ '^[[:space:]]*(模块|工具箱)[[:space:]]*[:：]'
      ) label_values
      join ods_gitlab_label_links ll
        on ll.target_id = label_values.target_id
       and coalesce(ll.mirror_deleted, false) = false
     where module_value is not null
     group by ll.target_id
)
update merge_request_fact fact
   set merge_user_name = coalesce(merge_users.merge_user_name, fact.merge_user_name),
       module_name = coalesce(module_labels.module_name, fact.module_name),
       updated_at = current_timestamp
  from merge_users
  left join module_labels
    on module_labels.merge_request_id = merge_users.merge_request_id
 where fact.source_system = 'GITLAB'
   and fact.merge_request_id = merge_users.merge_request_id
   and fact.project_id = merge_users.project_id
   and (
     (nullif(btrim(coalesce(fact.merge_user_name, '')), '') is null and merge_users.merge_user_name is not null)
     or (nullif(btrim(coalesce(fact.module_name, '')), '') is null and module_labels.module_name is not null)
   );
