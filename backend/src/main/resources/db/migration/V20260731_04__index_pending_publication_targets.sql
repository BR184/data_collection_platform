create index idx_sync_run_fact_targets_pending_fence
    on sync_run_fact_targets(source_instance, fact_type, change_version, project_id)
    where publication_status <> 'PUBLISHED';
