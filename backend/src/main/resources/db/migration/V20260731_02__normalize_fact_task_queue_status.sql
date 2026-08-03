update fact_build_tasks
   set status = 'QUEUED',
       updated_at = current_timestamp
 where status = 'PENDING'
   and trigger_type = 'MIRROR_SYNC';
