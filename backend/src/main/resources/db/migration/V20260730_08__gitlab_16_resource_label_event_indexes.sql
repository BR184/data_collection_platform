-- GitLab CE 16.11 使用 issue_id / merge_request_id 表示标签事件目标，action 以 1/2 表示 add/remove。
-- 镜像表由来源结构动态创建，因此仅在所需列完整存在时建立索引。
DO $$
BEGIN
  IF to_regclass('ods_gitlab_resource_label_events') IS NOT NULL
      AND EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = 'ods_gitlab_resource_label_events'
           AND column_name IN ('issue_id', 'action', 'label_id', 'created_at', 'mirror_deleted')
         GROUP BY table_name
        HAVING count(DISTINCT column_name) = 5
      ) THEN
    CREATE INDEX IF NOT EXISTS idx_ods_gitlab_resource_label_events_issue_fix_gitlab16
    ON ods_gitlab_resource_label_events(issue_id, action, label_id, created_at DESC)
    WHERE mirror_deleted = false AND issue_id IS NOT NULL;
  END IF;

  IF to_regclass('ods_gitlab_resource_label_events') IS NOT NULL
      AND EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = 'ods_gitlab_resource_label_events'
           AND column_name IN ('mirror_task_id', 'issue_id', 'merge_request_id')
         GROUP BY table_name
        HAVING count(DISTINCT column_name) = 3
      ) THEN
    CREATE INDEX IF NOT EXISTS idx_ods_gitlab_resource_label_events_task_issue
    ON ods_gitlab_resource_label_events(mirror_task_id, issue_id)
    WHERE issue_id IS NOT NULL;

    CREATE INDEX IF NOT EXISTS idx_ods_gitlab_resource_label_events_task_mr
    ON ods_gitlab_resource_label_events(mirror_task_id, merge_request_id)
    WHERE merge_request_id IS NOT NULL;
  END IF;
END $$;
