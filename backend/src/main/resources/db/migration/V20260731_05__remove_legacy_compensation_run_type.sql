update sync_run_table_tasks
   set task_type = 'FULL_COMPENSATION_SCAN',
       updated_at = current_timestamp
 where task_type = 'COMPENSATION_SCAN';

update sync_runs
   set run_type = 'FULL_COMPENSATION_SCAN',
       updated_at = current_timestamp
 where run_type = 'COMPENSATION_SCAN';
