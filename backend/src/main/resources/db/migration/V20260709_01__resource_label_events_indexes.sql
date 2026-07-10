-- GitLab label add/remove event mirror table is optional for older deployments.
-- When present, it is the old-platform-aligned source for the "已修复/完成" label time.
DO $$
BEGIN
  IF to_regclass('ods_gitlab_resource_label_events') IS NOT NULL
      AND EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = 'ods_gitlab_resource_label_events'
           AND column_name IN ('resource_type', 'action', 'label_id', 'resource_id', 'created_at', 'mirror_deleted')
         GROUP BY table_name
        HAVING count(DISTINCT column_name) = 6
      ) THEN
    CREATE INDEX IF NOT EXISTS idx_ods_gitlab_resource_label_events_issue_fix
    ON ods_gitlab_resource_label_events(resource_type, action, label_id, resource_id, created_at DESC)
    WHERE mirror_deleted = false;
  END IF;

  IF to_regclass('ods_gitlab_resource_label_events') IS NOT NULL
      AND EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = 'ods_gitlab_resource_label_events'
           AND column_name IN ('mirror_task_id', 'resource_type', 'resource_id', 'mirror_deleted')
         GROUP BY table_name
        HAVING count(DISTINCT column_name) = 4
      ) THEN
    CREATE INDEX IF NOT EXISTS idx_ods_gitlab_resource_label_events_mirror_task
    ON ods_gitlab_resource_label_events(mirror_task_id, resource_type, resource_id)
    WHERE mirror_deleted = false;
  END IF;
END $$;
