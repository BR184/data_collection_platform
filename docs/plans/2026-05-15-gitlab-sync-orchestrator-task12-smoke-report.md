# GitLab Sync Orchestrator Task 12 Smoke Report

Date: 2026-05-15

Scope:

- Task 12 architecture guard and endpoint-routing checks.
- GitLab sync orchestrator runbook verification.
- Targeted frontend smoke tests for mirror status/run monitor/statistic drilldown.
- Full frontend regression and build.
- Full backend regression attempt.

## Summary

Task 12 targeted verification passed. Full frontend regression and production
build passed. Full backend regression did not complete cleanly in this local
environment; issues are recorded below and were not fixed during smoke, per the
requested smoke-test policy.

## Commands Run

### Legacy Runtime Symbol Scan

```powershell
rg "GitlabSyncTask|GitlabSyncLog|GitlabSyncJob|GitlabSyncJobType|GitlabTableSync|GitlabSyncTaskService|GitlabSyncLogService|GitlabSyncJobMapper|gitlab_sync_tasks|gitlab_sync_logs|gitlab_sync_jobs|gitlab_table_sync_tasks|gitlab_table_sync_states" backend\src\main\java
```

Result:

- No output.
- `rg` exit code was `1`, which means no matches were found.

### Backend Targeted Task 12 Tests

```powershell
cd backend
. ..\scripts\dev-env.ps1
mvn -q "-Dtest=GitlabSyncControllerTest,NoLegacySyncModelTest" test
```

Result:

- Passed.

### Frontend Targeted Smoke

```powershell
cd frontend
npm.cmd test -- src/views/mirror-settings.mount-smoke.test.ts src/views/useMirrorStatusPresentation.test.ts src/views/MirrorRunMonitorPanel.test.ts src/components/StatisticBoardDetailDialog.test.ts
npm.cmd run typecheck
```

Result:

- Targeted smoke passed: 4 files, 13 tests.
- Typecheck passed.

### Frontend Full Regression

```powershell
cd frontend
npm.cmd test
npm.cmd run build
```

Result:

- Full test suite passed: 75 files, 214 tests.
- Production build passed.

### Backend Full Regression

```powershell
cd backend
. ..\scripts\dev-env.ps1
mvn -q test
```

Result:

- Failed. See open issues.

## Open Issues

### SMOKE-001: Backend Full Regression Requires Local PostgreSQL on `localhost:15432`

Status: Open

Evidence:

- `StatisticBoardControllerTest` failed to load Spring context.
- `FactBuildTaskServiceTest` failed to load Spring context.
- Root cause in surefire reports:

```text
org.postgresql.util.PSQLException: Connection to localhost:15432 refused.
```

Impact:

- Backend full regression cannot complete in this workstation state.
- This blocks end-to-end verification of SpringBootTest classes that depend on
  `TEST_DATASOURCE_URL` / `jdbc:postgresql://localhost:15432/qaflex?currentSchema=qaflex_test`.

Recommended resolution:

1. Start the expected local PostgreSQL test database on port `15432`.
2. Ensure schema `qaflex_test` exists and the configured test user can connect.
3. Re-run:

```powershell
cd backend
. ..\scripts\dev-env.ps1
mvn -q test
```

Longer-term option:

- Move these SpringBootTest suites to Testcontainers or a documented local DB
  bootstrap command so CI/agent smoke tests are not dependent on an implicit
  workstation service.

### SMOKE-002: `DatabaseBrowserControllerTest.shouldRefreshCurrentTable` NPE

Status: Open

Evidence:

Surefire report:

```text
DatabaseBrowserControllerTest.shouldRefreshCurrentTable
NullPointerException: Cannot invoke "GitlabMirrorSyncService$OnDemandRefreshResult.plannedTasks()" because "result" is null
DatabaseBrowserController.java:51
```

Observed detail:

- Source search in `backend/src/test/java` did not locate
  `DatabaseBrowserControllerTest.java`, while surefire still reported it.
- The report suggests the controller received `null` from
  `databaseBrowserService.refreshTableDetailed(tableName)`.

Impact:

- Full backend regression reports an additional controller test error unrelated
  to Task 12's unified sync endpoint routing.
- If the test source is still present through generated/stale test output, it
  may create noisy failures until cleaned.

Recommended resolution:

1. Locate the active source for `DatabaseBrowserControllerTest`:

```powershell
cd backend
rg "DatabaseBrowserControllerTest|shouldRefreshCurrentTable" src test target -n
```

2. If it is stale compiled output, run a clean build:

```powershell
cd backend
. ..\scripts\dev-env.ps1
mvn -q clean test
```

3. If the test source exists elsewhere, update the test fixture to return a
   non-null `GitlabMirrorSyncService.OnDemandRefreshResult` from
   `refreshTableDetailed`.
4. Consider adding a defensive null guard in `DatabaseBrowserController` only if
   `refreshTableDetailed` can legitimately return null in production; otherwise
   keep the service contract non-null and fix the test/mock.

## Task 12 Verification Notes

- `GitlabSyncController` status, full sync, incremental sync, cancel, and table
  diagnostics are covered by targeted tests against unified sync-run services.
- `NoLegacySyncModelTest` guards runtime Java code against legacy sync task/log/job
  symbols and legacy table names.
- `docs/gitlab-sync-orchestrator-runbook.md` documents active run inspection,
  blocked queue inspection, safe cancellation, thread tuning, dirty table
  interpretation, incident collection, and the explicit no-legacy-fallback rule.
