# 2026-05-18 Sync Refresh Dedup And Priority Plan

## Goal

Prevent short-window duplicate refreshes from creating repeated mirror and fact runs while preserving the existing priority ordering:

- `FULL_SYNC`: 100
- `INCREMENTAL_SYNC`: 70
- `SYSTEM_HOOK`: 60
- `TABLE_REFRESH`: 40
- `COMPENSATION_SCAN`: 20
- `FACT_REFRESH`: 10

## Findings From Local GitLab Pressure Test

1. Priority ordering works. A later `FULL_SYNC` can start before earlier queued `TABLE_REFRESH` runs.
2. Same-user repeated board refresh is only partially protected. Ten rapid clicks created two `TABLE_REFRESH` runs.
3. Multi-user concurrent board refresh is not protected enough. Eight concurrent sessions created eight `TABLE_REFRESH` runs and eight `FACT_REFRESH` runs.
4. Manual incremental sync had the same risk before the submission-level lock: concurrent submissions could enqueue repeated mirror runs for the same exclusive scope.
5. Fact refresh is parent-run scoped today, so many mirror runs can fan out into many fact refreshes.

## Concrete Design

### 1. Submission-Level Scope Lock

Use `pg_advisory_xact_lock(hashtext(exclusive_scope))` before reading or inserting active `sync_runs`.

Expected behavior:

- Concurrent submissions for the same `exclusive_scope` are serialized.
- The second request sees the first active run and returns a reused/deduped response instead of inserting another row.

### 2. Mirror Run Dedup Rules

For `FULL_SYNC`:

- Reuse an existing queued/running `FULL_SYNC`.
- Mark queued lower-priority mirror runs in the same scope as `MERGED` before inserting the full run.
- Do not cancel an already running lower-priority run; the full run waits for the scope to clear.

For `INCREMENTAL_SYNC`:

- If any mirror run is queued/running for the same scope, return reused/deduped.

For `TABLE_REFRESH`:

- If a `FULL_SYNC` or `INCREMENTAL_SYNC` is queued/running, return deduped because the broader run covers the refresh.
- If a same table refresh is queued/running, return reused/deduped.
- If a different table refresh is active, keep the current behavior unless a broader merge mechanism is added later.

For `SYSTEM_HOOK`:

- If a broader mirror run is active, return deduped.
- Precise hook runs can still queue when there is no broader active mirror run.

### 3. Fact Refresh Merge

For `FACT_REFRESH`:

- Reuse any active fact refresh for the same source/fact scope, not only the same parent run.
- This prevents multiple mirror runs in a burst from creating multiple redundant fact refresh jobs.

### 4. Page Refresh Cooldown

`RealtimeWorkspaceService` keeps a per-`workspaceKey` cooldown after accepting a refresh.

Expected behavior:

- Repeated clicks by the same user within the cooldown return current status and do not call the refresh action again.
- Concurrent clicks by different users for the same board share the same cooldown.
- The cooldown is process-local and intentionally conservative; database-level dedup remains the durable safety layer.

Default cooldown: 15 seconds.

## Execution Checklist

- [x] Update `SyncRunSubmissionService` mirror dedup and full-run absorption rules.
- [x] Update `SyncRunSubmissionService` fact refresh merge rule.
- [x] Update `RealtimeWorkspaceService` cooldown behavior.
- [x] Add focused unit tests for submission dedup, full absorption, fact merge, and cooldown.
- [x] Run targeted Maven tests.
- [x] Run real local GitLab pressure tests:
  - Same-user rapid board refresh should enqueue at most one table refresh in the cooldown window.
  - Multi-user concurrent board refresh should enqueue at most one table refresh in the cooldown window.
  - Concurrent manual incremental sync should enqueue at most one mirror run.
  - Full sync should outrank queued table refresh and merge queued lower-priority runs.

## Verification Results

- Same-user rapid board refresh: 10 clicks created 1 `TABLE_REFRESH` and 1 `FACT_REFRESH`.
- Multi-user concurrent board refresh: 8 sessions created 1 `TABLE_REFRESH` and 1 `FACT_REFRESH`.
- Concurrent manual incremental sync: 8 submissions created 1 `INCREMENTAL_SYNC`; the other 7 returned `DEDUPED`.
- Full sync absorption: queued `TABLE_REFRESH` was marked `MERGED`; `FULL_SYNC` ran next and triggered a single `FACT_REFRESH`.

## Known Non-Code Environment Issue

Local config `dgm` is enabled with an empty GitLab DB password. Its scheduled compensation runs fail independently of this dedup work. It should be fixed or disabled separately to keep health logs clean.

## Permission Boundary Follow-Up

### Context

The sync refresh dedup work reduced duplicate jobs, but it does not decide who is allowed to view source data or trigger expensive refreshes. The external source table area is still incomplete and must be treated as an administrator-only capability until a dedicated product design is finished.

### Permission Model

| Capability | Guest | Approval | Admin |
| --- | --- | --- | --- |
| Public aggregate dashboards | Read-only | Read-only | Read-only |
| Login-only aggregate dashboards | No access | Read-only when not approval-hidden | Read-only |
| Review data records | Temporary read for aggregate reuse | No write access | Read/write |
| GitLab source health, whitelist, diagnostics, configs | No access | No access | Read/write where applicable |
| Database browser, mirror/source table rows | No access | No access | Read/refresh |
| Manual sync, refresh, rebuild, cancel, purge | No access | No access | Execute |
| External collection form | Signed external capability only | Not applicable | Manage |

### Implementation Rules

1. Backend authorization is the source of truth. Frontend menu hiding is only user experience.
2. Source tables, mirror tables, database browser, GitLab sync configuration, source health, diagnostics, whitelist options, and manual refresh endpoints require `ADMIN`.
3. Review data writes and GitLab-context refresh are administrator-only. Record reads remain temporarily available because the quality dashboard still reuses them for aggregate metrics; a dedicated public summary API should replace that dependency.
4. Public dashboards may stay guest-readable only when they return aggregate or curated fact data, not raw source rows or operational metadata.
5. External collection forms must not be modeled as generic guest writes. They need a signed, scoped, expiring link before broader external use.

### Execution Checklist

- [x] Protect backend source-table and sync-operation endpoints with `@RequireRole(AuthRole.ADMIN)`.
- [x] Align frontend page visibility so guest and approval users cannot navigate into administrator-only source data pages.
- [x] Add focused authorization tests for guest, approval, and admin access expectations.
- [ ] Leave signed external form capability as a follow-up design unless required by the current release.
- [ ] Add a follow-up task to split quality-dashboard public summaries away from raw review-data list APIs.

### Verification Results

- Backend targeted authorization tests passed with project-local Maven:
  `tools/maven/apache-maven-3.9.9/bin/mvn.cmd -q "-Dtest=EndpointAuthorizationContractTest,PlatformAuthorizationInterceptorTest" test`
- Frontend targeted access tests passed:
  `npm.cmd test -- feature-manifest-access.test.ts StatisticBoardToolbar.test.ts`
- Frontend typecheck and production build passed:
  `npm.cmd run typecheck`
  `npm.cmd run build`
