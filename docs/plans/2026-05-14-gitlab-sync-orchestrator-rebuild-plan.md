# GitLab Sync Orchestrator Rebuild Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 重构 GitLab 数据镜像同步体系，建立统一的任务调度、优先级、取消、进度、日志和监视能力，解决全量同步被插队、状态丢失、日志不可信、单表刷新边界不清和统计下钻无法跳转 GitLab 的问题。

**Architecture:** 先删除旧同步模型及其入口，再建设新的统一 `sync_runs` 顶层运行单元，承载全量、增量、单表刷新、System Hook、补偿扫描和事实层刷新。所有同步操作只提交 `sync_runs`，由后台调度器按数据源锁、优先级、线程预算和合并规则执行；前端只展示统一运行视图，严禁从旧任务表、新任务表、日志表等多个状态来源拼接当前状态。

**Tech Stack:** Spring Boot 3.5, Java 21, MyBatis Plus, PostgreSQL/Flyway, JdbcTemplate, Vue 3, Element Plus, Vitest, JUnit 5.

---

## Confirmed Problems From Code Reading

当前系统同时存在两套任务模型。对本方案而言，这两套都属于需要清理的旧/过渡模型，不能作为新运行时继续保留：

- 旧模型：`gitlab_sync_tasks` / `gitlab_sync_logs`，保留了排队、取消、状态展示等逻辑。
- 新模型：`gitlab_sync_jobs` / `gitlab_table_sync_tasks` / `gitlab_table_sync_states`，实际全量、增量、单表刷新主要走这里。

核心断裂点：

- `/api/gitlab-sync/full-sync` 和 `/api/gitlab-sync/incremental-sync` 通过 `GitlabMirrorSyncService` 创建表级 job，并在请求线程内调用 `GitlabTableSyncWorkerService.drainReadyTasksForJob(...)`。
- `/api/gitlab-sync/cancel` 仍然只调用旧 `GitlabSyncTaskService.requestCancelLatest(...)`，所以无法取消新模型中的运行中 job。
- 单表刷新通过 `DatabaseBrowserService.refreshTable(...)` 直接创建 `MANUAL_REFRESH` job，没有和全量同步互斥、排队或合并。
- 进度展示只选一个 display job，且 `currentTable` 从活跃 task 中推导，多个线程/续批任务下会跳动。
- 最近同步日志只对部分全量/增量路径可靠，单表刷新和长任务过程缺少统一日志。
- 统计下钻只返回纯文本 `iid`，前端明细弹窗不支持链接渲染。

## Target Operating Model

所有同步行为必须统一进入以下生命周期：

1. `SUBMITTED`: 用户或系统提交运行请求。
2. `QUEUED`: 调度器接受请求并排队。
3. `RUNNING`: worker 获取租约并执行。
4. `CANCELLING`: 用户请求取消，worker 在批次边界安全退出。
5. `TERMINAL`: `SUCCESS` / `PARTIAL_SUCCESS` / `FAILED` / `CANCELLED` / `TIMEOUT`。

互斥和优先级原则：

- 同一 `config_id/source_instance` 同时只允许一个“镜像写入 run”处于 `RUNNING`。
- 全量同步是 source 级独占任务。
- 单表刷新默认不能插队全量；可选择合并到全量完成后的增量补偿，或排队等待。
- System Hook 允许高优先级，但必须按窗口合并，不允许无限创建小任务。
- 事实层刷新只在镜像任务达到终态后触发，并显示独立阶段。

## Hard Cutover Constraint

This plan must be implemented as a hard cutover, not a compatibility migration.

Mandatory rules:

- Do not keep old and new schedulers runnable at the same time.
- Do not keep controller branches such as "if old task exists, use old task; otherwise use sync run".
- Do not keep UI fallback logic that reads legacy task/log tables for current status.
- Do not keep request-thread direct drain behavior.
- Do not keep old write paths behind feature flags.
- Do not add `sync_runs` while continuing to submit work into `gitlab_sync_tasks` or `gitlab_sync_jobs`.
- Historical data can be backed up before destructive cleanup, but runtime code must not read it as part of normal status, cancellation, progress, or logging.

The implementation order is therefore:

1. Delete or neutralize old model code and schema.
2. Make the project compile with old model references removed.
3. Add the new unified model.
4. Implement every sync path only against the new model.

## Task 0: Remove Legacy Sync Models Before Building the New One

**Files:**

- Delete: `backend/src/main/java/com/data/collection/platform/entity/GitlabSyncTask.java`
- Delete: `backend/src/main/java/com/data/collection/platform/entity/GitlabSyncLog.java`
- Delete: `backend/src/main/java/com/data/collection/platform/entity/GitlabSyncJob.java`
- Delete: `backend/src/main/java/com/data/collection/platform/entity/GitlabSyncJobType.java`
- Delete: `backend/src/main/java/com/data/collection/platform/entity/GitlabTableSyncTask.java`
- Delete: `backend/src/main/java/com/data/collection/platform/entity/GitlabTableSyncState.java`
- Delete: `backend/src/main/java/com/data/collection/platform/entity/GitlabTableSyncTaskType.java`
- Delete: `backend/src/main/java/com/data/collection/platform/entity/GitlabTableSyncDiagnosticsResponse.java`
- Delete: `backend/src/main/java/com/data/collection/platform/entity/GitlabTableSyncStateDiagnostics.java`
- Delete: `backend/src/main/java/com/data/collection/platform/entity/MirrorStatusTaskView.java`
- Delete: `backend/src/main/java/com/data/collection/platform/entity/MirrorStatusLogView.java`
- Delete: `backend/src/main/java/com/data/collection/platform/mapper/GitlabSyncTaskMapper.java`
- Delete: `backend/src/main/java/com/data/collection/platform/mapper/GitlabSyncLogMapper.java`
- Delete: `backend/src/main/java/com/data/collection/platform/mapper/GitlabSyncJobMapper.java`
- Delete: `backend/src/main/java/com/data/collection/platform/mapper/GitlabTableSyncTaskMapper.java`
- Delete: `backend/src/main/java/com/data/collection/platform/mapper/GitlabTableSyncStateMapper.java`
- Delete: `backend/src/main/java/com/data/collection/platform/service/GitlabSyncTaskService.java`
- Delete: `backend/src/main/java/com/data/collection/platform/service/GitlabSyncLogService.java`
- Delete: `backend/src/main/java/com/data/collection/platform/service/GitlabTableSyncPlanningService.java`
- Delete: `backend/src/main/java/com/data/collection/platform/service/GitlabTableSyncWorkerService.java`
- Delete: `backend/src/main/java/com/data/collection/platform/service/GitlabTableSyncDiagnosticsService.java`
- Create: `backend/src/main/resources/db/migration/V20260515_00__remove_legacy_gitlab_sync_models.sql`
- Modify: `backend/src/main/java/com/data/collection/platform/controller/GitlabSyncController.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/GitlabMirrorSyncService.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/GitlabSourceHealthService.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/GitlabCompensationScheduler.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/GitlabDailyVerificationScheduler.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/FactBuildTaskService.java`
- Test: remove or rewrite legacy tests under `backend/src/test/java/com/data/collection/platform/service/GitlabSyncTaskServiceTest.java`
- Test: update affected tests under `backend/src/test/java/com/data/collection/platform/controller/GitlabSyncControllerTest.java`

**Step 1: Inventory all legacy references**

Run:

```bash
rg "GitlabSyncTask|GitlabSyncLog|GitlabSyncJob|GitlabSyncJobType|GitlabTableSync|GitlabSyncTaskService|GitlabSyncLogService|GitlabSyncJobMapper|gitlab_sync_tasks|gitlab_sync_logs|gitlab_sync_jobs|gitlab_table_sync_tasks|gitlab_table_sync_states" backend/src
```

Expected: list all old model references before cleanup.

**Step 2: Add a destructive cleanup migration for old runtime tables**

Create `V20260515_00__remove_legacy_gitlab_sync_models.sql`.

Minimum cleanup:

```sql
drop table if exists gitlab_table_sync_tasks cascade;
drop table if exists gitlab_table_sync_states cascade;
drop table if exists gitlab_sync_jobs cascade;
drop table if exists gitlab_sync_tasks cascade;
drop table if exists gitlab_sync_logs cascade;
```

If table-state data must be preserved for diagnosis, export it before running this migration. Do not keep these tables as runtime dependencies.

**Step 3: Delete old Java model, mapper, and service files**

Delete the files listed in this task. Do not leave deprecated wrappers.

**Step 4: Remove old dependencies from constructors**

Temporarily replace old runtime behavior with explicit unsupported placeholders while the new `sync_runs` services are added in later tasks.

Acceptable temporary behavior:

- `/status` returns `IDLE` with empty run/log arrays until `SyncRunStatusService` exists.
- `/cancel` returns a clear message that cancellation is unavailable until `SyncRunCancellationService` is wired.

Unacceptable temporary behavior:

- Calling old task services.
- Reading old task/log/job tables.
- Directly draining old table job tasks.

**Step 5: Prove no legacy references remain**

Run:

```bash
rg "GitlabSyncTask|GitlabSyncLog|GitlabSyncJob|GitlabSyncJobType|GitlabTableSync|GitlabSyncTaskService|GitlabSyncLogService|GitlabSyncJobMapper|gitlab_sync_tasks|gitlab_sync_logs|gitlab_sync_jobs|gitlab_table_sync_tasks|gitlab_table_sync_states" backend/src
```

Expected: no matches, except inside this plan or intentionally archived docs outside `backend/src`.

**Step 6: Compile to expose remaining coupling**

Run:

```bash
cd backend
mvn -DskipTests compile
```

Expected: pass after all legacy references have been removed or replaced by new-model TODO placeholders.

## Task 1: Add Unified Run Schema

**Files:**

- Create: `backend/src/main/resources/db/migration/V20260515_01__sync_orchestrator_core.sql`
- Create: `backend/src/main/java/com/data/collection/platform/entity/sync/SyncRun.java`
- Create: `backend/src/main/java/com/data/collection/platform/entity/sync/SyncRunTableTask.java`
- Create: `backend/src/main/java/com/data/collection/platform/entity/sync/SyncRunTableState.java`
- Create: `backend/src/main/java/com/data/collection/platform/entity/sync/SyncRunType.java`
- Create: `backend/src/main/java/com/data/collection/platform/entity/sync/SyncRunStatus.java`
- Create: `backend/src/main/java/com/data/collection/platform/entity/sync/SyncRunPriority.java`
- Create: `backend/src/main/java/com/data/collection/platform/mapper/SyncRunMapper.java`
- Create: `backend/src/main/java/com/data/collection/platform/mapper/SyncRunTableTaskMapper.java`
- Create: `backend/src/main/java/com/data/collection/platform/mapper/SyncRunTableStateMapper.java`
- Test: `backend/src/test/java/com/data/collection/platform/service/FlywayMigrationSmokeTest.java`

**Step 1: Write migration smoke coverage**

Add assertions that the new tables exist after Flyway migration:

- `sync_runs`
- `sync_run_table_tasks`
- `sync_run_table_states`
- `sync_run_events`
- `sync_worker_leases`

Expected core columns:

- `sync_runs.id`
- `sync_runs.config_id`
- `sync_runs.source_instance`
- `sync_runs.run_type`
- `sync_runs.status`
- `sync_runs.priority`
- `sync_runs.cancel_requested`
- `sync_runs.thread_mode`
- `sync_runs.thread_value`
- `sync_runs.created_at`
- `sync_runs.started_at`
- `sync_runs.finished_at`

Run:

```bash
cd backend
mvn -Dtest=FlywayMigrationSmokeTest test
```

Expected: fail before migration exists, pass after migration.

**Step 2: Create migration**

Create `sync_runs` as the only runtime source of truth for top-level status. The old task/log/job tables must already be removed by Task 0, so this migration must not include compatibility views, fallback triggers, or bridge tables back to legacy models.

Minimum schema:

```sql
create table if not exists sync_runs (
    id bigserial primary key,
    run_id varchar(64) not null unique,
    config_id bigint not null references gitlab_sync_configs(id) on delete cascade,
    source_instance varchar(128) not null,
    run_type varchar(64) not null,
    trigger_type varchar(32) not null,
    status varchar(32) not null,
    priority integer not null default 0,
    exclusive_scope varchar(255) not null,
    cancel_requested boolean not null default false,
    submitted_by varchar(128),
    request_reason text,
    payload_json text,
    thread_mode varchar(32) not null default 'FIXED',
    thread_value numeric(8, 3) not null default 2,
    planned_table_count integer not null default 0,
    completed_table_count integer not null default 0,
    scanned_rows bigint not null default 0,
    applied_rows bigint not null default 0,
    heartbeat_at timestamp,
    lease_owner varchar(128),
    lease_until timestamp,
    started_at timestamp,
    finished_at timestamp,
    error_message text,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);
```

Add indexes:

- `(status, priority desc, created_at)`
- `(config_id, source_instance, status)`
- `(exclusive_scope, status)`

Create `sync_run_table_tasks` and `sync_run_table_states` as new tables. They must not reuse the old `gitlab_table_sync_tasks` or `gitlab_table_sync_states` names.

**Step 3: Create Java model and mapper**

Map enum values conservatively:

- `FULL_SYNC`
- `INCREMENTAL_SYNC`
- `TABLE_REFRESH`
- `SYSTEM_HOOK`
- `COMPENSATION_SCAN`
- `FACT_REFRESH`

Terminal statuses should match existing UI vocabulary where possible:

- `SUCCESS`
- `PARTIAL_SUCCESS`
- `FAILED`
- `CANCELLED`
- `TIMEOUT`

**Step 4: Run migration smoke**

Run:

```bash
cd backend
mvn -Dtest=FlywayMigrationSmokeTest test
```

Expected: pass.

## Task 2: Build SyncRun Submission Service

**Files:**

- Create: `backend/src/main/java/com/data/collection/platform/service/sync/SyncRunSubmissionService.java`
- Create: `backend/src/main/java/com/data/collection/platform/service/sync/SyncRunPolicyService.java`
- Create: `backend/src/main/java/com/data/collection/platform/entity/sync/SyncRunSubmissionResult.java`
- Test: `backend/src/test/java/com/data/collection/platform/service/sync/SyncRunSubmissionServiceTest.java`

**Step 1: Write tests for full-sync exclusivity**

Test cases:

- When a `FULL_SYNC` run is `RUNNING`, a new `TABLE_REFRESH` request is not started immediately.
- The `TABLE_REFRESH` request becomes `QUEUED` or `MERGED`, depending on policy.
- A second `FULL_SYNC` request reuses the active full run instead of creating another one.

**Step 2: Implement submission policy**

Rules:

- `FULL_SYNC`: priority `100`, exclusive scope `source:{configId}:{sourceInstance}:mirror`.
- `TABLE_REFRESH`: priority `40`, same exclusive scope as full sync.
- `SYSTEM_HOOK`: priority `60`, same exclusive scope, coalesced by event scope.
- `COMPENSATION_SCAN`: priority `20`.
- `FACT_REFRESH`: separate scope `source:{configId}:{sourceInstance}:fact`.

**Step 3: Add merge semantics**

If a full sync is active:

- Single-table refresh should record a child request/event against the active run.
- It should not create an independently runnable mirror write run.
- UI response should say: "已合并到当前全量同步，完成后将以全量结果为准。"

**Step 4: Run tests**

```bash
cd backend
mvn -Dtest=SyncRunSubmissionServiceTest test
```

Expected: pass.

## Task 3: Replace Request-Thread Drain With Background Dispatcher

**Files:**

- Create: `backend/src/main/java/com/data/collection/platform/service/sync/SyncRunDispatcherService.java`
- Create: `backend/src/main/java/com/data/collection/platform/service/sync/SyncRunWorkerService.java`
- Create: `backend/src/main/java/com/data/collection/platform/service/sync/SyncRunTablePlanningService.java`
- Create: `backend/src/main/java/com/data/collection/platform/service/sync/SyncRunTableWorkerService.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/GitlabMirrorSyncService.java`
- Test: `backend/src/test/java/com/data/collection/platform/service/sync/SyncRunDispatcherServiceTest.java`
- Test: `backend/src/test/java/com/data/collection/platform/service/sync/SyncRunWorkerServiceTest.java`

**Step 1: Write dispatcher claim tests**

Expected behavior:

- Claims highest priority queued run.
- Uses `for update skip locked` semantics through mapper SQL or JdbcTemplate.
- Does not claim a queued run if another run with same exclusive scope is running.

**Step 2: Implement dispatcher**

Scheduled loop:

```java
@Scheduled(fixedDelayString = "${platform.gitlab-mirror.run-dispatcher-delay-ms:2000}")
public void runOnce() {
  if (!properties.isSchedulerEnabled()) {
    return;
  }
  claim and execute next eligible run;
}
```

**Step 3: Remove direct draining from submission path**

Change full/incremental/table refresh service methods so they only submit a `sync_run` and return quickly.

Do not call:

- `tableSyncWorkerService.drainReadyTasksForJob(...)`
- any method named `drainReadyTasksForJob(...)`

from controller request paths.

**Step 4: Run targeted tests**

```bash
cd backend
mvn -Dtest=SyncRunDispatcherServiceTest,SyncRunWorkerServiceTest,GitlabMirrorSyncServiceTest test
```

Expected: pass after updating old assertions that expected immediate drain.

## Task 4: Add Configurable Thread Budget

**Status:** Completed on 2026-05-15. Backend resolver/config persistence and frontend controls are implemented; targeted backend tests, frontend smoke test, and frontend typecheck passed.

**Files:**

- Modify: `backend/src/main/java/com/data/collection/platform/config/GitlabMirrorProperties.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/java/com/data/collection/platform/entity/GitlabSyncConfig.java`
- Create: `backend/src/main/resources/db/migration/V20260515_02__gitlab_sync_thread_config.sql`
- Modify: `backend/src/main/java/com/data/collection/platform/controller/GitlabSyncController.java`
- Modify: `frontend/src/types/api.ts`
- Modify: `frontend/src/views/MirrorSettingsView.vue`
- Test: `backend/src/test/java/com/data/collection/platform/service/sync/SyncThreadBudgetResolverTest.java`
- Test: `frontend/src/views/mirror-settings.mount-smoke.test.ts`

**Step 1: Add failing backend tests**

Cases:

- Fixed thread mode with value `4` resolves to `4`.
- CPU ratio mode with value `0.8` and `availableProcessors=16` resolves to `12`.
- Values below minimum clamp to `1`.
- Values above max clamp to configured max.

**Step 2: Add config fields**

Suggested config fields:

- `syncThreadMode`: `FIXED` or `CPU_RATIO`
- `syncThreadValue`: number
- `maxSyncThreads`: optional hard cap

**Step 3: Add frontend controls**

In data mirror settings:

- Segmented control: fixed thread count / dynamic CPU ratio.
- Numeric input for fixed count.
- Decimal input for CPU ratio, e.g. `0.8`.
- Helper text showing resolved value after status load.

**Step 4: Run tests**

```bash
cd backend
mvn -Dtest=SyncThreadBudgetResolverTest test

cd ../frontend
npm.cmd test -- src/views/mirror-settings.mount-smoke.test.ts
npm.cmd run typecheck
```

Expected: pass.

## Task 5: Implement Run-Level Cancellation

**Files:**

- Create: `backend/src/main/java/com/data/collection/platform/service/sync/SyncRunCancellationService.java`
- Modify: `backend/src/main/java/com/data/collection/platform/controller/GitlabSyncController.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/sync/SyncRunTableWorkerService.java`
- Modify: `frontend/src/views/useMirrorSyncActionsController.ts`
- Test: `backend/src/test/java/com/data/collection/platform/service/sync/SyncRunCancellationServiceTest.java`
- Test: `backend/src/test/java/com/data/collection/platform/controller/GitlabSyncControllerTest.java`

**Step 1: Write cancel tests**

Cases:

- Cancelling an active `sync_run` sets `cancel_requested=true` and status `CANCELLING`.
- Cancelling a queued run marks it `CANCELLED`.
- Worker sees cancellation before starting the next table task and stops.
- API no longer returns "没有可中止任务" when a table-level run is active.

**Step 2: Add worker cancellation checks**

Check cancellation:

- Before claiming a table task.
- After source scan.
- After mirror write.
- Before creating continuation task.

**Step 3: Finish terminal state**

When cancellation is honored:

- Running table task becomes `CANCELLED`.
- Run becomes `CANCELLED`.
- Log event records who/when/why cancelled.

**Step 4: Run tests**

```bash
cd backend
mvn -Dtest=SyncRunCancellationServiceTest,GitlabSyncControllerTest,SyncRunTableWorkerServiceTest test
```

Expected: pass.

## Task 6: Rebuild Progress, Logs, and Diagnostics Around SyncRun

**Files:**

- Create: `backend/src/main/java/com/data/collection/platform/entity/sync/SyncRunProgressResponse.java`
- Create: `backend/src/main/java/com/data/collection/platform/entity/sync/SyncRunLogResponse.java`
- Create: `backend/src/main/java/com/data/collection/platform/service/sync/SyncRunStatusService.java`
- Create: `backend/src/main/java/com/data/collection/platform/service/sync/SyncRunLogService.java`
- Modify: `backend/src/main/java/com/data/collection/platform/controller/GitlabSyncController.java`
- Modify: `frontend/src/types/api.ts`
- Modify: `frontend/src/views/useMirrorStatusPresentation.ts`
- Modify: `frontend/src/views/MirrorSyncStatusCard.vue`
- Modify: `frontend/src/views/MirrorSyncLogTable.vue`
- Test: `backend/src/test/java/com/data/collection/platform/service/sync/SyncRunStatusServiceTest.java`
- Test: `frontend/src/views/useMirrorStatusPresentation.test.ts`

**Step 1: Define trustworthy progress**

Progress must include:

- `runId`
- `runType`
- `status`
- `queuedRunsAhead`
- `totalTables`
- `runningTables`
- `completedTables`
- `failedTables`
- `dirtyTables`
- `scannedRows`
- `appliedRows`
- `recordsPerSecond`
- `estimatedRemainingSeconds`
- `factRefreshStatus`
- `activeTableTasks`

**Step 2: Stop exposing one unstable current table**

Replace "当前表" with:

- "正在处理表": list first N running table names.
- "活跃线程": running worker count.
- "最近完成表": latest successful table tasks.

This avoids table name jumping.

**Step 3: Make logs run-based**

Recent sync log rows should come from `sync_runs` and `sync_run_events`, not mixed old/new task tables.

Each row should show:

- Run type.
- Trigger.
- Status.
- Table count.
- Applied rows.
- Duration.
- Queue wait time.
- Error summary.

**Step 4: Run tests**

```bash
cd backend
mvn -Dtest=SyncRunStatusServiceTest test

cd ../frontend
npm.cmd test -- src/views/useMirrorStatusPresentation.test.ts src/views/MirrorSyncLogTable.test.ts
npm.cmd run typecheck
```

Expected: pass.

## Task 7: Clarify Dirty Table Semantics

**Status:** Completed on 2026-05-15. Backend table diagnostics now exposes dirty reason, blocking run, row drift and watermark semantics; frontend displays the dirty/running/fact-lag explanation and table drift details.

**Files:**

- Create: `backend/src/main/java/com/data/collection/platform/entity/sync/SyncRunTableStateDiagnostics.java`
- Create: `backend/src/main/java/com/data/collection/platform/service/sync/SyncRunTableDiagnosticsService.java`
- Modify: `frontend/src/types/api.ts`
- Modify: `frontend/src/views/MirrorSettingsView.vue`
- Test: `backend/src/test/java/com/data/collection/platform/service/sync/SyncRunTableDiagnosticsServiceTest.java`
- Test: `frontend/src/views/mirror-settings.mount-smoke.test.ts`

**Step 1: Add diagnostic fields**

For each table expose:

- `dirty`: boolean.
- `dirtyReason`: enum/text.
- `blockingRunId`: current run if table is being processed.
- `lastVerifiedAt`.
- `lastAppliedAt`.
- `lastWatermarkAt`.
- `sourceRows`.
- `mirrorRows`.
- `driftSummary`.

**Step 2: UI copy**

Show:

- "脏表 = 源表和镜像表可能不一致，需增量修复或全量校验。"
- "运行中 = 已进入当前 run 的处理队列。"
- "事实层滞后 = 镜像已更新，但统计事实表还没刷新。"

**Step 3: Run tests**

```bash
cd backend
mvn -Dtest=SyncRunTableDiagnosticsServiceTest test

cd ../frontend
npm.cmd test -- src/views/mirror-settings.mount-smoke.test.ts
```

Expected: pass.

## Task 8: Enforce Single-Table Refresh Boundaries

**Status:** Completed on 2026-05-15.

**Files:**

- Modify: `backend/src/main/java/com/data/collection/platform/service/DatabaseBrowserService.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/GitlabMirrorSyncService.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/sync/SyncRunTablePlanningService.java`
- Modify: `frontend/src/api-client/database-browser-api.ts`
- Test: `backend/src/test/java/com/data/collection/platform/service/DatabaseBrowserServiceTest.java`
- Test: `backend/src/test/java/com/data/collection/platform/service/GitlabMirrorSyncServiceTest.java`

**Step 1: Write boundary tests**

Cases:

- Table outside whitelist is rejected.
- Table without `updated_at` cannot run manual incremental refresh.
- Table with no baseline watermark returns "需要先完成首次全量同步".
- Active full sync causes table refresh to merge or queue, never immediate drain.
- Submitted table refresh response includes `runId` and queue/merge status.

**Step 2: Implement strict policy**

Manual table refresh must require:

- Existing mirror registry.
- Known primary key.
- Known `updated_at`.
- Existing `last_watermark_at`.

Otherwise return actionable message, not silent zero-task success.

**Step 3: Run tests**

```bash
cd backend
mvn -Dtest=DatabaseBrowserServiceTest,GitlabMirrorSyncServiceTest test
cd ../frontend
npm.cmd run typecheck
```

Expected: pass.

**Verification:** Passed on 2026-05-15 with:

```bash
cd backend
mvn -Dtest=DatabaseBrowserServiceTest,GitlabMirrorSyncServiceTest test
cd ../frontend
npm.cmd run typecheck
```

## Task 9: Integrate Fact Refresh Into the Same Monitor

**Status:** Completed on 2026-05-15.

**Files:**

- Modify: `backend/src/main/java/com/data/collection/platform/service/FactBuildTaskService.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/FactRefreshTaskWorkerService.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/sync/SyncRunWorkerService.java`
- Modify: `frontend/src/views/MirrorSyncStatusCard.vue`
- Modify: `backend/src/main/resources/db/migration/V20260515_02__sync_run_fact_refresh_parent.sql`
- Test: `backend/src/test/java/com/data/collection/platform/service/FactBuildTaskServiceTest.java`
- Test: `backend/src/test/java/com/data/collection/platform/service/FactRefreshTaskWorkerServiceTest.java`

**Step 1: Write tests for two-stage completion**

Expected:

- Mirror run can be `SUCCESS` while fact refresh is `PENDING` or `RUNNING`.
- Overall UI status should show "镜像完成，事实层刷新中".
- Source health should not claim business data is fully current until fact refresh finishes.

**Step 2: Create fact refresh sync runs**

When mirror run completes and applied rows > 0:

- Submit `FACT_REFRESH` run scoped to same config/source.
- Link `parent_run_id` to mirror run.

**Step 3: Run tests**

```bash
cd backend
mvn -Dtest=FactBuildTaskServiceTest,FactRefreshTaskWorkerServiceTest,SyncRunWorkerServiceTest test
```

Expected: pass.

**Verification:** Passed on 2026-05-15 with:

```bash
cd backend
mvn -Dplatform.auth.secure-config-required=false -Dtest=FactBuildTaskServiceTest,FactRefreshTaskWorkerServiceTest,SyncRunWorkerServiceTest test

cd ../frontend
npm.cmd run typecheck
```

## Task 10: Build Data Mirror Monitor UI

**Files:**

- Create: `frontend/src/views/MirrorRunMonitorPanel.vue`
- Create: `frontend/src/views/MirrorRunQueueTable.vue`
- Create: `frontend/src/views/MirrorRunWorkerPanel.vue`
- Create: `frontend/src/views/MirrorRunTableTaskDrawer.vue`
- Modify: `frontend/src/views/MirrorSettingsView.vue`
- Modify: `frontend/src/api-client/mirror-api.ts`
- Modify: `frontend/src/types/api.ts`
- Test: `frontend/src/views/mirror-settings.mount-smoke.test.ts`
- Test: `frontend/src/views/MirrorRunMonitorPanel.test.ts`

**Step 1: UI layout**

Add a monitor section to data mirror settings:

- Active run summary.
- Queue table.
- Worker/thread usage.
- Active table tasks.
- Dirty table summary.
- Recent terminal runs.

**Step 2: Expected operator actions**

Buttons:

- Refresh status.
- Cancel selected run.
- Retry failed run.
- Open table task drawer.

Do not add broad destructive actions in this task.

**Step 3: Run frontend tests**

```bash
cd frontend
npm.cmd test -- src/views/MirrorRunMonitorPanel.test.ts src/views/mirror-settings.mount-smoke.test.ts
npm.cmd run typecheck
```

Expected: pass.

## Task 11: Add Statistic Drilldown GitLab Links

**Files:**

- Modify: `backend/src/main/java/com/data/collection/platform/entity/statistics/StatisticDetailColumn.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/statistics/CustomerIssueDefectSummaryBoardService.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/statistics/CustomerIssueByFunctionBoardService.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/statistics/SystemTestDefectSummaryBoardService.java`
- Modify: `frontend/src/types/api.ts`
- Modify: `frontend/src/components/StatisticBoardDetailDialog.vue`
- Modify: `frontend/src/composables/useStatisticBoardDetail.ts`
- Test: `backend/src/test/java/com/data/collection/platform/controller/StatisticBoardControllerTest.java`
- Test: `frontend/src/components/StatisticBoardDetailDialog.test.ts`

**Step 1: Backend link contract**

Extend detail records to include:

- `issueIid`
- `issueUrl`
- `projectId`
- `projectName`

For display column `iid`, return a structured value:

```json
{
  "label": "123",
  "href": "https://gitlab.example.com/<project>/-/issues/123"
}
```

**Step 2: Frontend link renderer**

`StatisticBoardDetailDialog` should render object values with `href` as external links.

Rules:

- Open in new tab.
- Preserve plain text fallback when `href` is missing.
- Keep sorting bound to the underlying column key.

**Step 3: Run tests**

```bash
cd backend
mvn -Dtest=StatisticBoardControllerTest test

cd ../frontend
npm.cmd test -- src/components/StatisticBoardDetailDialog.test.ts src/composables/useStatisticBoardDetail.test.ts
npm.cmd run typecheck
```

Expected: pass.

## Task 12: Prove Single-Model Runtime and Document Operations

**Files:**

- Modify: `backend/src/main/java/com/data/collection/platform/controller/GitlabSyncController.java`
- Create: `backend/src/test/java/com/data/collection/platform/architecture/NoLegacySyncModelTest.java`
- Create: `docs/gitlab-sync-orchestrator-runbook.md`
- Test: `backend/src/test/java/com/data/collection/platform/controller/GitlabSyncControllerTest.java`

**Step 1: Add architecture guard test**

Create a test that fails if legacy model symbols return to runtime code.

Forbidden strings under `backend/src/main/java`:

- `GitlabSyncTask`
- `GitlabSyncLog`
- `GitlabSyncJob`
- `GitlabSyncJobType`
- `GitlabTableSync`
- `GitlabSyncTaskService`
- `GitlabSyncLogService`
- `GitlabSyncJobMapper`
- `gitlab_sync_tasks`
- `gitlab_sync_logs`
- `gitlab_sync_jobs`
- `gitlab_table_sync_tasks`
- `gitlab_table_sync_states`

**Step 2: Verify endpoint implementation has no fallback branches**

Existing endpoint paths may stay:

- `/api/gitlab-sync/full-sync`
- `/api/gitlab-sync/incremental-sync`
- `/api/gitlab-sync/cancel`
- `/api/gitlab-sync/status`

But their implementation must route through `SyncRunSubmissionService`, `SyncRunCancellationService`, and `SyncRunStatusService`.

Forbidden controller patterns:

- Reading old task/log/job mappers.
- Returning status from old task/log/job tables.
- Branching by old task existence.
- Calling request-thread direct drain.

**Step 3: Add runbook**

Runbook must include:

- How to inspect active runs.
- How to inspect blocked queue.
- How to cancel safely.
- How to tune thread count.
- How to interpret dirty table status.
- What to collect for incident reports.
- Explicit note that legacy task/log/job tables and services were removed and are not operational fallbacks.

**Step 4: Run regression tests**

```bash
cd backend
mvn test

cd ../frontend
npm.cmd test
npm.cmd run build
```

Expected: pass.

## Rollout Plan

1. Stop sync schedulers and block manual sync operations in the target environment.
2. Back up the database if historical old task/log data must be retained outside runtime.
3. Apply the destructive cleanup migration from Task 0.
4. Deploy code with only the `sync_runs` runtime model.
5. Run full sync on staging with million-level data.
6. Validate:
   - No single-table refresh interrupts full sync.
   - Cancel works on active full sync.
   - Recent logs show running and terminal runs.
   - Record counts match `rows_applied`.
   - Fact layer refresh is visible after mirror completion.
   - Dirty table count matches diagnostics.
7. Run the architecture guard that proves no old model references exist.
8. Enable for production-like test source.

## Acceptance Criteria

- Full sync cannot be visually or operationally overwritten by a single-table refresh.
- Single-table refresh never inserts work ahead of an active full sync unless explicitly configured and visible.
- Cancel works for running full sync, incremental sync, table refresh and queued runs.
- Status page shows all active/queued runs, not only the newest one.
- Recent sync logs include long-running tasks and completed runs with trustworthy table/record counts.
- Table progress distinguishes running, completed, failed, dirty and pending tables.
- Dirty table state has a reason and remediation hint.
- Thread count can be fixed or CPU-ratio based.
- Frontend shows worker/thread usage and queue depth.
- Statistic drilldown issue numbers open the corresponding GitLab issue page.
- No runtime code references legacy sync task/log/job services, mappers, entities, or tables.
- No endpoint uses a legacy fallback branch for current status, cancellation, progress, or logs.

## Verification Matrix

Backend targeted:

```bash
cd backend
rg "GitlabSyncTask|GitlabSyncLog|GitlabSyncJob|GitlabSyncJobType|GitlabTableSync|GitlabSyncTaskService|GitlabSyncLogService|GitlabSyncJobMapper|gitlab_sync_tasks|gitlab_sync_logs|gitlab_sync_jobs|gitlab_table_sync_tasks|gitlab_table_sync_states" src/main/java
mvn -Dtest=SyncRunSubmissionServiceTest,SyncRunDispatcherServiceTest,SyncRunWorkerServiceTest,SyncRunCancellationServiceTest,SyncRunStatusServiceTest test
mvn -Dtest=GitlabMirrorSyncServiceTest,SyncRunTableWorkerServiceTest,SyncRunTableDiagnosticsServiceTest test
mvn -Dtest=DatabaseBrowserServiceTest,FactBuildTaskServiceTest,FactRefreshTaskWorkerServiceTest test
```

Expected for the `rg` command: no output.

Frontend targeted:

```bash
cd frontend
npm.cmd test -- src/views/mirror-settings.mount-smoke.test.ts src/views/useMirrorStatusPresentation.test.ts src/views/MirrorRunMonitorPanel.test.ts src/components/StatisticBoardDetailDialog.test.ts
npm.cmd run typecheck
```

Full regression:

```bash
cd backend
mvn test

cd ../frontend
npm.cmd test
npm.cmd run build
```

Manual million-level scenario:

1. Start full sync for a source with 1M+ records.
2. During full sync, click single-table refresh for `issues`.
3. Confirm UI shows merge/queue, not overwrite.
4. Confirm active full run remains visible.
5. Click cancel and confirm run transitions to `CANCELLING` then `CANCELLED`.
6. Restart full sync and let it complete.
7. Confirm fact refresh runs after mirror completion.
8. Confirm recent logs show applied rows, duration, queue wait and terminal status.
9. Open statistic drilldown and click issue number.

## Risks

- Existing tests may encode old request-thread drain behavior and need deliberate updates.
- Long-running PostgreSQL writes need transaction boundaries reviewed to avoid holding locks too long.
- Dynamic thread count must protect source GitLab PostgreSQL from overload.
- Link generation for GitLab issues depends on having enough project path or project id data in facts.

## Non-Goals

- Do not preserve old `gitlab_sync_tasks`, `gitlab_sync_logs`, `gitlab_sync_jobs`, `gitlab_table_sync_tasks`, or `gitlab_table_sync_states` behavior for new execution.
- Do not optimize every mirror table query in this phase.
- Do not add arbitrary SQL execution to the database browser.
- Do not build compatibility adapters from legacy tables into the new status model.
