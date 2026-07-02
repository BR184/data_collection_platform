with source_states as (
    select mr.id as merge_request_id,
           mr.target_project_id as project_id,
           case
             when mr.state_id = 3 then 'merged'
             when mr.state_id = 2 then 'closed'
             when mr.state_id = 1 then 'opened'
             when metrics.merged_at is not null then 'merged'
             else 'opened'
           end as merge_request_state
      from ods_gitlab_merge_requests mr
      left join ods_gitlab_merge_request_metrics metrics
        on metrics.merge_request_id = mr.id
       and coalesce(metrics.mirror_deleted, false) = false
     where coalesce(mr.mirror_deleted, false) = false
)
update merge_request_fact fact
   set merge_request_state = source_states.merge_request_state,
       updated_at = current_timestamp
  from source_states
 where fact.source_system = 'GITLAB'
   and fact.merge_request_id = source_states.merge_request_id
   and fact.project_id = source_states.project_id
   and lower(coalesce(fact.merge_request_state, '')) <> source_states.merge_request_state;
