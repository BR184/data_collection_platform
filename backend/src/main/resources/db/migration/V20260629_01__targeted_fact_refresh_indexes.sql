-- 精准事实刷新会根据本次镜像任务写入的 mirror_task_id 反查受影响 issue/MR。
-- 这些索引只作用于平台本地 ODS 镜像表，不访问 GitLab 源库。
DO $$
BEGIN
  IF to_regclass('ods_gitlab_issues') IS NOT NULL THEN
    CREATE INDEX IF NOT EXISTS idx_ods_gitlab_issues_mirror_task
    ON ods_gitlab_issues(mirror_task_id, project_id, iid)
    WHERE mirror_deleted = false;
  END IF;

  IF to_regclass('ods_gitlab_merge_requests') IS NOT NULL THEN
    CREATE INDEX IF NOT EXISTS idx_ods_gitlab_merge_requests_mirror_task
    ON ods_gitlab_merge_requests(mirror_task_id, target_project_id, iid)
    WHERE mirror_deleted = false;
  END IF;

  IF to_regclass('ods_gitlab_notes') IS NOT NULL THEN
    CREATE INDEX IF NOT EXISTS idx_ods_gitlab_notes_mirror_task
    ON ods_gitlab_notes(mirror_task_id, noteable_type, noteable_id)
    WHERE mirror_deleted = false;
  END IF;

  IF to_regclass('ods_gitlab_label_links') IS NOT NULL THEN
    CREATE INDEX IF NOT EXISTS idx_ods_gitlab_label_links_mirror_task
    ON ods_gitlab_label_links(mirror_task_id, target_type, target_id)
    WHERE mirror_deleted = false;
  END IF;

  IF to_regclass('ods_gitlab_issue_assignees') IS NOT NULL THEN
    CREATE INDEX IF NOT EXISTS idx_ods_gitlab_issue_assignees_mirror_task
    ON ods_gitlab_issue_assignees(mirror_task_id, issue_id)
    WHERE mirror_deleted = false;
  END IF;

  IF to_regclass('ods_gitlab_merge_request_metrics') IS NOT NULL THEN
    CREATE INDEX IF NOT EXISTS idx_ods_gitlab_merge_request_metrics_mirror_task
    ON ods_gitlab_merge_request_metrics(mirror_task_id, merge_request_id)
    WHERE mirror_deleted = false;
  END IF;

  IF to_regclass('ods_gitlab_merge_request_reviewers') IS NOT NULL THEN
    CREATE INDEX IF NOT EXISTS idx_ods_gitlab_merge_request_reviewers_mirror_task
    ON ods_gitlab_merge_request_reviewers(mirror_task_id, merge_request_id)
    WHERE mirror_deleted = false;
  END IF;

  IF to_regclass('ods_gitlab_merge_request_assignees') IS NOT NULL THEN
    CREATE INDEX IF NOT EXISTS idx_ods_gitlab_merge_request_assignees_mirror_task
    ON ods_gitlab_merge_request_assignees(mirror_task_id, merge_request_id)
    WHERE mirror_deleted = false;
  END IF;
END $$;
