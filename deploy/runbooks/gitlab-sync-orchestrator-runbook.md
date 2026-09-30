<!-- DOC_STATUS_START -->
> 文档状态：常驻 Runbook
> 说明：当前运维和排查说明，继续有效。
<!-- DOC_STATUS_END -->

# GitLab Sync Orchestrator Runbook
## Scope

This runbook covers the unified GitLab mirror sync runtime backed by `sync_runs`,
`sync_run_table_tasks`, `sync_run_table_states`, `sync_run_events`, and
`sync_worker_leases`.

Legacy task/log/job tables and services were removed from the runtime path. They
are not operational fallbacks for status, cancellation, progress, logs, or table
diagnostics.

## Legacy Sync Model Status

The following names are historical only and must not be used for new runtime code:

- `gitlab_sync_tasks`
- `gitlab_sync_logs`
- `gitlab_sync_jobs`
- `gitlab_table_sync_tasks`
- `gitlab_table_sync_states`
- `GitlabSyncTask*`
- `GitlabSyncLog*`
- `GitlabSyncJob*`
- `GitlabTableSync*`

They may still appear in old Flyway migrations, historical plans, or audit
documents because those files describe the migration path from the pre-orchestrator
runtime. Current Java runtime code is protected by
`NoLegacySyncModelTest`; if one of those symbols appears in active runtime code,
treat it as a regression.

## Inspect Active Runs

Use the status API for the current source:

```http
GET /api/gitlab-sync/status?configId=<configId>
```

The response `currentTask` is the active or oldest queued run selected from
`sync_runs`. `currentStatus`, `progress`, `logs`, and `resolvedSyncThreads`
should all describe the same unified run model.

Useful SQL:

```sql
select id,
       run_id,
       config_id,
       source_instance,
       run_type,
       trigger_type,
       status,
       priority,
       exclusive_scope,
       cancel_requested,
       planned_table_count,
       completed_table_count,
       scanned_rows,
       applied_rows,
       heartbeat_at,
       lease_owner,
       lease_until,
       started_at,
       finished_at,
       error_message,
       created_at,
       updated_at
  from sync_runs
 where status in ('SUBMITTED', 'QUEUED', 'RUNNING', 'RETRYING', 'CANCELLING')
 order by created_at asc, id asc;
```

## Inspect Blocked Queue

Blocked work is usually a queued run sharing an `exclusive_scope` with an active
run, or table tasks waiting behind running/retrying work.

```sql
select exclusive_scope,
       count(*) filter (where status in ('RUNNING', 'RETRYING', 'CANCELLING')) as active_count,
       count(*) filter (where status in ('SUBMITTED', 'QUEUED')) as queued_count,
       min(created_at) as oldest_queued_at
  from sync_runs
 where status in ('SUBMITTED', 'QUEUED', 'RUNNING', 'RETRYING', 'CANCELLING')
 group by exclusive_scope
 order by oldest_queued_at asc;
```

```sql
select run_id,
       source_table,
       task_type,
       status,
       run_after,
       lease_owner,
       lease_until,
       heartbeat_at,
       retry_count,
       last_error,
       rows_scanned,
       rows_applied,
       created_at
  from sync_run_table_tasks
 where status in ('QUEUED', 'RUNNING', 'RETRYING')
 order by case status when 'RUNNING' then 0 when 'RETRYING' then 1 else 2 end,
          run_after asc,
          created_at asc;
```

## Cancel Safely

Use the cancellation API instead of updating tables manually:

```http
POST /api/gitlab-sync/cancel?configId=<configId>
```

Expected behavior:

- Queued runs move directly to `CANCELLED`.
- Running runs move to `CANCELLING`; workers observe `cancel_requested` and end
  in a terminal state.
- The response includes `accepted`, `runId`, `externalRunId`, `status`, and
  `message`.

After cancelling, verify:

```sql
select run_id, status, cancel_requested, finished_at, error_message
  from sync_runs
 where config_id = <configId>
 order by updated_at desc
 limit 10;
```

## Tune Thread Count

Thread budget is resolved from each `gitlab_sync_configs` row and surfaced by
`GET /api/gitlab-sync/status`.

Fields:

- `sync_thread_mode`: fixed or ratio based mode.
- `sync_thread_value`: fixed thread count or CPU ratio input.
- `max_sync_threads`: upper bound.

Use the config API to change these settings:

```http
PUT /api/gitlab-sync/config
```

Check worker leases after changes:

```sql
select worker_id,
       worker_type,
       thread_mode,
       thread_value,
       max_threads,
       active_threads,
       queue_depth,
       heartbeat_at,
       lease_until
  from sync_worker_leases
 order by updated_at desc;
```

## Interpret Dirty Table Status

Use table diagnostics:

```http
GET /api/gitlab-sync/table-sync-diagnostics?configId=<configId>
```

Important fields:

- `dirtyTableCount`: number of tables requiring remediation.
- `tables[].dirtyFlag`: true when the table state should not be trusted.
- `tables[].dirtyReason`: reason to inspect first.
- `tables[].blockingRunId`: active run currently blocking or owning table work.
- `tables[].latestTaskStatus` and `tables[].latestTaskError`: latest task signal.
- `tables[].driftSummary`: source/mirror row-count comparison when available.

Common remediation:

- Missing primary key or updated-at metadata: refresh whitelist/schema metadata,
  then run a full sync baseline.
- Dirty after failed task: inspect `latestTaskError`, fix the source/schema
  cause, then submit a table refresh or full sync.
- Dirty with row-count drift: verify mirror writes and rerun the affected table
  once the source is stable.

## Delay Label Writeback Credential Preflight

Delay label writeback is the only runtime path that calls the GitLab REST API. Source
reads use the source database (DOCKER source mode) instead, so an invalid or expired
token produces no read-side error: sync, facts and statistics all keep working, and the
problem stays invisible until the first label write is attempted.

Where the credential lives:

- `gitlab_sync_configs.api_token` (plain text in the platform database; masked in API
  responses and in the database browser) and `web_base_url` (API base).
- A single switch gates the path: the per-source `delay_label_writeback_enabled` column,
  edited as the "延期标签写回" toggle on the mirror settings page (default off). There is
  no deployment-level global switch: once the toggle is on and saved, the platform really
  calls the GitLab API of the configured `web_base_url` with the configured token.

Stopping writeback without the frontend (all of these are independent of the UI):

1. `update gitlab_sync_configs set delay_label_writeback_enabled = false;` — effective
   within one worker tick (the worker re-checks the switch before each job and marks the
   remaining queue rows as skipped); no restart needed.
2. Clear `api_token` or `web_base_url` for that source — the same check then fails for
   the same reason.
3. Set `CUSTOMER_ISSUE_DELAY_WRITEBACK_WORKER_ENABLED=false` (worker stops draining the
   queue) or `platform.gitlab-mirror.scheduler-enabled=false` (no cycles at all) and
   restart the backend.
4. Revoke or expire the token in GitLab — writeback then fails with 401 and the job moves
   to `DEAD` without touching any label.

Failure signature (observed 2026-09-28 on the local stack, token `expires_at` already
passed):

- Job rows carry `last_http_status = 401` and
  `last_error = GitLab label update failed: HTTP 401 {"error":"invalid_token",...}`,
  then move to `DEAD`. No label is modified: nothing is corrupted, but nothing is
  written either, and the queue drains silently.

Preflight checks before enabling writeback:

1. Expiry: confirm `personal_access_tokens.expires_at` for the configured token is in
   the future and `revoked = false`. Expiry is silent for the read path, so rotate
   ahead of time instead of reacting to a 401.
2. Scope: the token needs the `api` scope; `read_api` is enough for the checks below
   but not for writing.
3. Identity: the owning user must be able to edit issues of the source project.
   Confirm with `GET /api/v4/projects/<projectId>/issues/<iid>` using the same token
   and compare the returned `project_id`/`iid` with the fact rows.
4. Endpoint consistency: `web_base_url` must point at the same GitLab instance the
   mirror reads from, and be reachable from the platform host. In DOCKER source mode a
   mismatch is not caught by the sync diagnostics.
5. Switch state: global flag on plus per-source switch on. A writeback job only adds or
   removes `响应已延期`/`解决已延期`; it never rewrites unrelated labels.
6. Queue drain: after rotating the credential, requeue the failed rows and confirm the
   next attempts return HTTP 200:

```sql
-- source GitLab database: token inventory
select id, user_id, name, scopes, revoked, expires_at
  from personal_access_tokens
 where revoked = false
 order by expires_at asc;

-- platform database: requeue deterministic credential failures
update customer_issue_delay_label_writeback_jobs
   set status = 'PENDING', attempt_count = 0, next_run_at = current_timestamp,
       lease_owner = null, lease_until = null, last_error = null,
       last_http_status = null, finished_at = null
 where status in ('DEAD', 'RETRY_WAIT');

-- inspection: who is still failing on credentials
select status, count(*), count(*) filter (where last_http_status = 401) as http_401
  from customer_issue_delay_label_writeback_jobs
 group by status;
```

Because `api_token` is stored as plain text, it is also copied by every platform
database backup; treat the backup archive as a credential store (see
`database-backup-secret-key.md`).

## Incident Report Checklist

Collect these before changing data:

- `configId`, `sourceInstance`, run `run_id`, and affected source tables.
- `/api/gitlab-sync/status` response.
- `/api/gitlab-sync/table-sync-diagnostics` response.
- Active `sync_runs` rows and related `sync_run_table_tasks`.
- `sync_run_events` for the run:

```sql
select event_type, table_name, message, payload_json, created_at
  from sync_run_events
 where run_id = <sync_runs.id>
 order by created_at asc, id asc;
```

- Worker leases from `sync_worker_leases`.
- Recent application logs around the run submission, dispatch, worker heartbeat,
  cancellation, and terminal event.
- Source database connection/metadata diagnostics if the failure involves schema
  discovery or source reads.

## No Legacy Fallback

Do not use or recreate legacy sync task/log/job tables or services to recover a
run. The runtime model is `sync_runs` plus its table tasks, states, events, and
worker leases. If a legacy symbol appears in runtime Java code, the architecture
guard test must fail before release.
