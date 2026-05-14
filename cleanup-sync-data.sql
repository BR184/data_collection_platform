DO $$
DECLARE
    r record;
BEGIN
    FOR r IN
        SELECT tablename
        FROM pg_tables
        WHERE schemaname = current_schema()
          AND tablename LIKE 'ods_gitlab_%'
    LOOP
        EXECUTE format('DROP TABLE IF EXISTS %I CASCADE', r.tablename);
    END LOOP;
END $$;

TRUNCATE TABLE gitlab_sync_logs RESTART IDENTITY CASCADE;
TRUNCATE TABLE gitlab_sync_tasks RESTART IDENTITY CASCADE;
TRUNCATE TABLE gitlab_system_hook_events RESTART IDENTITY CASCADE;
TRUNCATE TABLE gitlab_mirror_records RESTART IDENTITY CASCADE;
TRUNCATE TABLE sys_table_registry RESTART IDENTITY CASCADE;

UPDATE gitlab_sync_configs
SET last_full_sync_at = NULL,
    last_incremental_sync_at = NULL,
    updated_at = CURRENT_TIMESTAMP;
