# GitLab Sync Orchestrator Runbook

## Scope

This runbook covers the unified GitLab mirror sync runtime backed by `sync_runs`,
`sync_run_table_tasks`, `sync_run_table_states`, `sync_run_events`, and
`sync_worker_leases`.

Legacy task/log/job tables and services were removed from the runtime path. They
are not operational fallbacks for status, cancellation, progress, logs, or table
diagnostics.

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
