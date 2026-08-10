update sync_run_table_states
   set row_strategy = case
         when source_table = 'resource_label_events'
           then 'MONOTONIC_PRIMARY_KEY'
         when source_table in (
           'user_details', 'issue_assignees', 'merge_request_assignees',
           'merge_request_reviewers', 'merge_request_diff_commits')
           then 'RECONCILE_ONLY'
         when nullif(btrim(updated_at_column), '') is not null
           then 'UPDATED_AT'
         else 'RECONCILE_ONLY'
       end,
       updated_at = current_timestamp
 where row_strategy is distinct from case
         when source_table = 'resource_label_events'
           then 'MONOTONIC_PRIMARY_KEY'
         when source_table in (
           'user_details', 'issue_assignees', 'merge_request_assignees',
           'merge_request_reviewers', 'merge_request_diff_commits')
           then 'RECONCILE_ONLY'
         when nullif(btrim(updated_at_column), '') is not null
           then 'UPDATED_AT'
         else 'RECONCILE_ONLY'
       end;

update sync_run_table_tasks
   set row_strategy = 'RECONCILE_ONLY',
       updated_at = current_timestamp
 where row_strategy = 'DELETE_ONLY'
   and status in ('SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELLED', 'TIMEOUT', 'MERGED');
