# GitLab Direct Sync and System Hook Runbook

## Purpose

This document records the GitLab mirror sync hardening work for direct database access and System Hook-driven updates.
It is intended for release review, internal deployment, and future debugging.

Key conclusion:

- Direct database mode can continue to use System Hook-triggered sync.
- Direct mode does not support automatic System Hook registration through Docker/GitLab Rails commands.
- In direct mode, register the GitLab system hook manually in GitLab, pointing to the platform System Hook receiver URL.
- Use the diagnostics endpoint before release to verify database access, whitelist discovery, and System Hook receiver readiness.

## Scope

The flow covers:

- Docker-to-direct source mode switch.
- Whitelist boundary behavior.
- Source health status and fact-layer freshness.
- Table-level job/task diagnostics for planned, processed, scanned, and applied work.
- Page-level manual refresh smoke checks.
- Review-data GitLab context refresh smoke checks.
- API timezone sample checks.
- System Hook precise sync and fallback behavior.
- Delete system hook tombstone handling.
- Direct SQL safety for source identifiers.
- Async System Hook queue lock cleanup.
- Operational diagnostics and manual verification.

## Implementation Phases

### Phase 1: System Hook Precise Target Whitelist Boundary

Problem:

- System Hook precise sync may plan source tables that are not in the current whitelist.
- Without filtering, System Hook sync can bypass the selected mirror scope.

Decision:

- Filter System Hook precise targets against the resolved whitelist before execution.
- If all planned targets are outside the whitelist, fall back to incremental sync.
- If only some targets are outside the whitelist, sync eligible targets and log skipped tables.

Main files:

- `backend/src/main/java/com/data/collection/platform/service/GitlabMirrorSyncService.java`
- `backend/src/test/java/com/data/collection/platform/service/GitlabMirrorSyncServiceTest.java`

Expected behavior:

- Whitelisted System Hook target: precise sync executes.
- Non-whitelisted System Hook target: incremental fallback is submitted.
- Mixed targets: whitelisted subset executes, skipped tables are logged.

### Phase 2: Delete System Hook Tombstone

Problem:

- A delete system hook may no longer find the row in the source database.
- Treating an empty precise query as "nothing to do" leaves deleted mirror rows visible.

Decision:

- Detect explicit delete/destroy/remove system hook actions.
- For delete System Hook events, mark matching mirror rows as `mirror_deleted = true`.
- Do not tombstone rows for normal update events that happen to return zero rows.

Main files:

- `backend/src/main/java/com/data/collection/platform/service/GitlabMirrorSyncService.java`
- `backend/src/main/java/com/data/collection/platform/service/GitlabMirrorTableStorageService.java`
- `backend/src/test/java/com/data/collection/platform/service/GitlabMirrorSyncServiceTest.java`
- `backend/src/test/java/com/data/collection/platform/service/GitlabMirrorTableStorageServiceTest.java`

Expected behavior:

- Delete system hook: mirror row is soft-deleted.
- Non-delete system hook with empty source result: no tombstone is written.
- Fact-building queries continue to ignore `mirror_deleted` rows.

### Phase 3: Direct SQL Identifier Safety and Whitelist Boundary

Problem:

- Direct mode executes PostgreSQL SQL against GitLab source tables.
- Table/column identifiers may contain uppercase letters, spaces, or quotes.
- Custom whitelist entries must not become an arbitrary source table access path.

Decision:

- Generate scan SQL through dedicated builder methods.
- Quote schema, table, and column identifiers using PostgreSQL double-quote escaping.
- Keep custom whitelist resolution constrained to discovered source tables.

Main files:

- `backend/src/main/java/com/data/collection/platform/service/GitlabExternalDbService.java`
- `backend/src/test/java/com/data/collection/platform/service/GitlabExternalDbServiceTest.java`
- `backend/src/test/java/com/data/collection/platform/service/GitlabWhitelistServiceTest.java`

Expected behavior:

- Full scan uses `"public"."tableName"`.
- Time window scan quotes the updated-at column.
- Precise scan quotes the lookup column.
- Embedded quotes in identifiers are escaped.
- Unknown custom whitelist table names are ignored.

### Phase 4: System Hook Async Object Lock Cleanup

Problem:

- The async System Hook dispatcher used one lock per object key.
- Locks were stored in a long-lived map and were not removed after execution.
- A long-running process receiving many unique issue/MR events could accumulate locks.

Decision:

- Replace bare locks with reference-counted object lock holders.
- Retain a holder before locking.
- Release and remove the holder after the queued System Hook finishes if no other task uses it.
- Keep same-object system hook execution serialized across flushes.

Main files:

- `backend/src/main/java/com/data/collection/platform/service/GitlabSystemHookAsyncDispatchService.java`
- `backend/src/test/java/com/data/collection/platform/service/GitlabSystemHookAsyncDispatchServiceTest.java`

Expected behavior:

- Same object key still executes serially.
- Object lock count returns to zero after the queued System Hook finishes.

### Phase 5: Operational Diagnostics API

Problem:

- The existing connection-test endpoint only returned `checked=true`.
- For direct-mode rollout, operators need one endpoint that reports direct DB access, whitelist discovery, and System Hook readiness.

Decision:

- Add diagnostics response type.
- Add admin-only diagnostics endpoints.
- Capture connection, whitelist, and System Hook registration errors into structured response fields instead of returning 500.
- Add frontend API typing and client method.

Backend endpoints:

- `POST /api/gitlab-sync/diagnostics`
- `POST /api/gitlab-sync/diagnostics/by-config?configId={id}`

Main files:

- `backend/src/main/java/com/data/collection/platform/controller/GitlabSyncController.java`
- `backend/src/main/java/com/data/collection/platform/entity/GitlabSyncDiagnosticsResponse.java`
- `backend/src/test/java/com/data/collection/platform/controller/GitlabSyncControllerTest.java`
- `frontend/src/api-client/mirror-api.ts`
- `frontend/src/types/api.ts`

Response fields:

```json
{
  "configId": 1,
  "sourceInstance": "default",
  "sourceMode": "DIRECT",
  "connectionOk": true,
  "connectionMessage": "GitLab PostgreSQL connection succeeded",
  "whitelistOk": true,
  "whitelistMessage": "GitLab whitelist options resolved",
  "whitelistOptionCount": 22,
  "systemHookReceiverUrl": "http://platform.example.com/api/gitlab-sync/system-hook",
  "systemHookAutoRegistrationSupported": false,
  "systemHookAutoRegistered": false,
  "systemHookMessage": "Direct mode does not support automatic System Hook registration"
}
```

Expected behavior:

- Direct DB failure returns `connectionOk=false` and the failure message.
- Whitelist discovery failure returns `whitelistOk=false` and the failure message.
- System Hook registration status failure returns `systemHookAutoRegistrationSupported=false` and the failure message.
- Direct mode can still expose `systemHookReceiverUrl` for manual GitLab System Hook registration.

## Direct Mode Rollout Procedure

### 1. Prepare Network and Credentials

Confirm the platform host can reach GitLab PostgreSQL:

- Host: GitLab database host or internal service DNS.
- Port: usually `5432`.
- Database: usually `gitlabhq_production`.
- Username/password: read-only user is preferred.
- Firewall/security group allows platform host to connect.

Recommended database permission:

- Read-only access to the required GitLab source tables.
- No write permission to GitLab production tables.

### 2. Configure Source

In GitLab sync config, use:

- `sourceMode`: `DIRECT`
- `dbHost`: internal GitLab PostgreSQL host
- `dbPort`: PostgreSQL port
- `dbName`: GitLab database name
- `dbUsername`: database user
- `dbPassword`: database password
- `whitelistMode`: `RECOMMENDED`, `ALL`, or `CUSTOM`
- `systemHookSecret`: shared secret for GitLab System Hook token validation
- `systemHookProjectId`: project id, useful for documentation/status, but automatic registration is only supported in Docker mode

Do not rely on `dockerContainerName` in direct mode.

### 3. Run Diagnostics

Use the diagnostics endpoint after saving config.

Recommended script:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\gitlab-direct-sync-check.ps1 `
  -BaseUrl "http://localhost:18080" `
  -ConfigId 1
```

Optional simulated system hook check:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\gitlab-direct-sync-check.ps1 `
  -BaseUrl "http://localhost:18080" `
  -ConfigId 1 `
  -SimulateSystemHook `
  -SystemHookSecret "replace-with-system-hook-secret" `
  -SystemHookProjectId 10 `
  -SystemHookObjectId 101
```

Optional incremental sync smoke:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\gitlab-direct-sync-check.ps1 `
  -BaseUrl "http://localhost:18080" `
  -ConfigId 1 `
  -StartIncrementalSync
```

Optional page refresh smoke:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\gitlab-direct-sync-check.ps1 `
  -BaseUrl "http://localhost:18080" `
  -ConfigId 1 `
  -RunPageRefreshSmoke `
  -PageRefreshBoardKey "system-test-defect-summary"
```

Expected:

- `GET /api/statistic-boards/{boardKey}/status` returns successfully.
- `POST /api/statistic-boards/{boardKey}/refresh` returns mirror/fact status fields.
- Response includes `jobId`, `sourceTables`, `plannedTasks`, `unsupportedTables`, and `factRefreshPlanned` when available.
- The page should not report "already latest" when mirror sync succeeded but fact refresh failed.

Optional review-data GitLab context refresh smoke:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\gitlab-direct-sync-check.ps1 `
  -BaseUrl "http://localhost:18080" `
  -ConfigId 1 `
  -RunReviewDataContextSmoke `
  -ReviewDataContextResourceType "merge_request"
```

Expected:

- `POST /api/review-data/records/gitlab-context/refresh` returns successfully.
- `manualFieldsTouched=false`.
- `resourceTypes` and `sourceTables` show the GitLab context scope.
- If a `jobId` is returned, `GET /api/review-data/records/gitlab-context/refresh/{jobId}` can read current status.
- This operation refreshes GitLab-derived context only and must not overwrite manual review fields.

Dry run without sending requests:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\gitlab-direct-sync-check.ps1 `
  -DryRun `
  -RunPageRefreshSmoke `
  -RunReviewDataContextSmoke `
  -SimulateSystemHook `
  -SystemHookSecret "dummy"
```

Manual API call:

PowerShell:

```powershell
$baseUrl = "http://localhost:18080"
$configId = 1
Invoke-RestMethod `
  -Method Post `
  -Uri "$baseUrl/api/gitlab-sync/diagnostics/by-config?configId=$configId"
```

Expected direct-mode result:

- `connectionOk = true`
- `whitelistOk = true`
- `whitelistOptionCount > 0`
- `sourceMode = DIRECT`
- `systemHookReceiverUrl` is not blank
- `systemHookAutoRegistrationSupported = false`

Interpretation:

- `systemHookAutoRegistrationSupported=false` is normal for direct mode.
- It only means the platform will not execute Docker/GitLab Rails commands to register the hook.
- GitLab can still call the platform System Hook receiver.

### 3.1 Verify Runtime Health and Table-Level Metrics

The direct sync check also reads:

- `GET /api/gitlab-sync/source-health`
- `GET /api/gitlab-sync/table-sync-diagnostics?configId={id}`
- `GET /api/gitlab-sync/status?configId={id}`

Expected health result:

- `healthStatus` is `OK` or an understood `DEGRADED` state.
- `healthStatus=BLOCKED` must be treated as a release blocker unless the source is intentionally disabled.
- `healthMessage` explains missing credentials, connection failures, whitelist failures, or missing required mirror tables.
- `factLayerLagging=false` when the mirror and fact tables are already aligned.

Expected table diagnostics:

- `tableCount` is the planned table count.
- Processed table count is derived from terminal latest task statuses.
- `latestTaskRowsScanned` and `latestTaskRowsApplied` explain zero-change runs.
- A no-change run should show planned/processed work instead of only "affected records = 0".
- `failedTaskCount` and `timedOutTaskCount` should be zero before release.

Timezone sample:

- The script prints current UTC and Beijing timestamps.
- API timestamps should include the `+08:00` offset after backend serialization.

### 4. Verify Whitelist Discovery

Use:

```powershell
Invoke-RestMethod `
  -Method Get `
  -Uri "$baseUrl/api/gitlab-sync/whitelist-options?configId=$configId"
```

Expected:

- Recommended tables such as `issues`, `merge_requests`, `notes`, `projects`, and `users` are present if the database user can read metadata.
- Tables not discovered from the source database are not selectable through custom whitelist resolution.

### 5. Run Manual Sync Smoke Test

Start a small incremental sync first:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri "$baseUrl/api/gitlab-sync/incremental-sync/by-config?configId=$configId"
```

Then check status:

```powershell
Invoke-RestMethod `
  -Method Get `
  -Uri "$baseUrl/api/gitlab-sync/status?configId=$configId"
```

Expected:

- Task is `PENDING`, `RUNNING`, `QUEUED`, or eventually `SUCCESS`.
- Logs show direct source mode and table sync progress.
- Mirror tables are created or updated.

If this is the first sync or schema has changed, run full sync during a controlled window:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri "$baseUrl/api/gitlab-sync/full-sync/by-config?configId=$configId"
```

### 6. Register System Hook Manually in GitLab

In direct mode, register the system hook manually in GitLab project settings.

Use the receiver URL from diagnostics:

```text
http://platform.example.com/api/gitlab-sync/system-hook
```

Configure:

- URL: `systemHookReceiverUrl`
- Secret token: same value as platform `systemHookSecret`
- Events:
  - Issues events
  - Merge request events
  - Note events
  - Pipeline events
  - Job events
  - Release events
- SSL verification: follow internal TLS policy

Important:

- The platform validates `X-Gitlab-Token` against configured `systemHookSecret`.
- If GitLab cannot reach the platform URL, direct database sync can work while system hook delivery still fails.

### 7. Send Simulated System Hook Payload

Use this only in a test environment or with a safe object id.

Issue update sample:

```powershell
$baseUrl = "http://localhost:18080"
$secret = "replace-with-system-hook-secret"
$payload = @{
  object_kind = "issue"
  event_type = "issue"
  project_id = 10
  object_attributes = @{
    id = 101
    iid = 12
    title = "Simulated issue from system hook"
    action = "update"
  }
} | ConvertTo-Json -Depth 8

Invoke-RestMethod `
  -Method Post `
  -Uri "$baseUrl/api/gitlab-sync/system-hook" `
  -Headers @{
    "X-Gitlab-Event" = "Issue Hook"
    "X-Gitlab-Token" = $secret
  } `
  -ContentType "application/json" `
  -Body $payload
```

Expected response:

```json
{
  "success": true,
  "data": {
    "accepted": true
  }
}
```

Then check status/logs:

```powershell
Invoke-RestMethod `
  -Method Get `
  -Uri "$baseUrl/api/gitlab-sync/status?configId=$configId"
```

Expected behavior:

- If precise target is whitelisted, System Hook precise sync executes.
- If precise target is outside whitelist, incremental fallback is submitted.
- Logs may include skipped target table names when the planner returns mixed whitelist/non-whitelist targets.

### 8. Verify Delete System Hook Behavior

Use this only when the object can be safely tombstoned in mirror data.

Delete payload pattern:

```json
{
  "object_kind": "issue",
  "event_type": "issue",
  "object_attributes": {
    "id": 101,
    "action": "delete"
  }
}
```

Expected:

- Matching mirror row is marked `mirror_deleted = true`.
- Fact/review queries should not include the deleted mirror row.

## Troubleshooting

| Symptom | Likely Cause | Check | Action |
| --- | --- | --- | --- |
| `connectionOk=false` | Network, credentials, DB host, DB user permission | Diagnostics `connectionMessage` | Fix host/port/password/firewall/read-only user permission |
| `whitelistOk=false` | Metadata query failed or DB user cannot inspect tables | Diagnostics `whitelistMessage` | Grant metadata/table read permissions |
| `healthStatus=BLOCKED` | Missing direct DB config, Docker container name, auth failure, permission failure, or whitelist failure | Source health `healthMessage` | Fix source config or credentials before release |
| `factLayerLagging=true` | Mirror sync succeeded but dependent fact table is older | Source health `factLayerMessage`; latest fact task | Run or troubleshoot fact refresh task |
| Table diagnostics show failed/timed-out tasks | Single table sync failed or worker lease expired | `/table-sync-diagnostics` latest task fields | Inspect `latestTaskError`, rerun page refresh or compensation scan |
| Page refresh returns unsupported tables | Page depends on verify-only or unsupported source tables | Refresh response `unsupportedTables` | Wait for daily verification or add a controlled table strategy |
| Review-data context refresh skipped | No selected/filter resource has GitLab context | Refresh response `resourceTypes` and message | Reload local list only or select records with GitLab context |
| `whitelistOptionCount=0` | No discovered tables or wrong database/schema | Diagnostics and `/whitelist-options` | Confirm `dbName`, schema, and GitLab DB user |
| Direct diagnostics shows system hook auto registration unsupported | Normal direct-mode behavior | Diagnostics `sourceMode` | Register system hook manually in GitLab |
| System Hook returns unauthorized/failed | Secret mismatch | `X-Gitlab-Token` and config `systemHookSecret` | Align GitLab secret token and platform config |
| System Hook accepted but no precise update | Target outside whitelist or planner cannot resolve payload | Sync logs | Add table to whitelist or rely on fallback incremental sync |
| Delete system hook accepted but row still visible | Payload not recognized as delete, or fact cache not rebuilt | Payload `action/state/event_name`; mirror row `mirror_deleted` | Confirm delete marker and rebuild dependent facts if needed |
| Queue overflow warning | System Hook burst exceeds configured queue size | Logs `System Hook precise queue is full` | Increase `GITLAB_SYSTEM_HOOK_MAX_QUEUE_SIZE` or rely on fallback incremental sync |
| Full backend tests fail on Flyway checksum locally | Persisted local test DB migration history mismatch | `FlywayMigrationSmokeTest` output | Reset local test schema or run non-Flyway regression command |

## Verification Commands

Backend focused sync regression:

```powershell
$env:JAVA_HOME = "C:\Program Files\JetBrains\PyCharm 2025.3.2\jbr"
$env:PATH = "$env:JAVA_HOME\bin;C:\Users\admin\.vscode\extensions\oracle.oracle-java-25.1.0\nbcode\java\maven\bin;$env:PATH"
mvn test "-Dtest=GitlabSyncControllerTest,GitlabExternalDbServiceTest,GitlabWhitelistServiceTest,GitlabExternalDbServiceDirectIntegrationTest,GitlabMirrorSyncServiceTest,GitlabMirrorTableStorageServiceTest,GitlabSystemHookRegistrationServiceTest,GitlabSystemHookServiceTest,GitlabSystemHookAsyncDispatchServiceTest,GitlabSystemHookPreciseSyncPlannerTest"
```

Backend full regression excluding known local Flyway smoke:

```powershell
$env:JAVA_HOME = "C:\Program Files\JetBrains\PyCharm 2025.3.2\jbr"
$env:PATH = "$env:JAVA_HOME\bin;C:\Users\admin\.vscode\extensions\oracle.oracle-java-25.1.0\nbcode\java\maven\bin;$env:PATH"
mvn test "-Dtest=!FlywayMigrationSmokeTest" "-Dplatform.auth.admin-password=test-admin-password" "-Dplatform.auth.approval-password=test-approval-password" "-Dplatform.gitlab-mirror.web-base-url=https://gitlab.example.test"
```

Frontend typecheck:

```powershell
& "C:\Program Files\nodejs\npm.cmd" run typecheck
```

Local release dry run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\verify-local.ps1 `
  -SkipDatabase
```

The local verification script runs `gitlab-direct-sync-check.ps1` in dry-run mode with incremental sync, page refresh, review-data context refresh, and system hook smoke paths enabled. This verifies command coverage without sending requests.

Direct integration tests:

- `GitlabExternalDbServiceDirectIntegrationTest` is skipped unless the required local/system property switch is enabled.
- Enable only when a real GitLab PostgreSQL test endpoint is available.

## Release Checklist

- [ ] Direct DB credentials are read-only.
- [ ] Diagnostics returns `connectionOk=true`.
- [ ] Diagnostics returns `whitelistOk=true`.
- [ ] `whitelistOptionCount` is greater than zero.
- [ ] Source health is not `BLOCKED`.
- [ ] Table diagnostics show no failed or timed-out tasks.
- [ ] No-change runs show planned/processed/scanned/applied metrics, not only zero affected records.
- [ ] Manual incremental sync succeeds.
- [ ] Page refresh smoke returns mirror/fact status fields.
- [ ] Review-data GitLab context refresh smoke returns `manualFieldsTouched=false`.
- [ ] API timestamp samples include the expected Beijing offset.
- [ ] System Hook receiver URL is reachable from GitLab.
- [ ] GitLab system hook is manually registered for direct mode.
- [ ] System Hook secret matches platform config.
- [ ] Simulated issue/MR system hook is accepted.
- [ ] Sync status/logs show precise sync or expected fallback.
- [ ] Delete event behavior is verified in a safe test object.
- [ ] Backend focused sync regression passes.
- [ ] Frontend typecheck passes if frontend API/types changed.

## Notes for Future Changes

- Keep direct source access constrained by discovered table metadata and whitelist settings.
- Do not add raw table-name concatenation for direct SQL; use quoted identifier helpers.
- Do not treat empty precise query results as delete unless the system hook explicitly indicates delete/destroy/remove.
- Preserve system hook fallback behavior; falling back to incremental sync is safer than silently dropping an event.
- Keep diagnostics non-destructive and safe to run repeatedly.
