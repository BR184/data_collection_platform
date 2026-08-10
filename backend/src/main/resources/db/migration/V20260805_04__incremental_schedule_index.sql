create index if not exists idx_sync_runs_incremental_schedule
    on sync_runs(config_id, source_instance, created_at desc, id desc)
    where run_type = 'INCREMENTAL_SYNC';
