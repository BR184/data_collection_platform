# GitLab Sync Runtime Gap Resolution Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 修复 GitLab 同步模块在运行态暴露出的状态、指标、页面级刷新和效率差异，让用户能判断链路是否真的跑通，并在业务页面按需刷新最新 GitLab 上下文。

**Architecture:** 继续以 `docs/plans/2026-05-08-gitlab-sync-production-plan.md` 为主方案，不回到已废弃的 Phase 0 旧设计。方案保持“四层准确性模型”：补偿扫描是日常追数主力，每日全量校验兜底，用户手动刷新只刷新当前业务页面依赖表，System Hook 只做 dirty/wakeup 信号。本轮重点补齐运行态可解释性、表级/事实级状态聚合、用户页面刷新入口、评审数据管理的 GitLab 上下文刷新策略，以及 0 变更场景下的效率优化。

**Tech Stack:** Spring Boot, MyBatis, PostgreSQL/Flyway, Vue 3, TypeScript, Vitest, Maven, Docker Compose.

---

## 1. 本轮差异与结论

### 1.1 全量同步 46 秒且影响表数/记录数为 0

结论：这不是一个可以直接判定为“链路没跑通”的现象，但目前的 UI 指标不可解释，必须修。

可能存在两种情况：

- 链路实际跑通：系统做了源表 probe、表级任务判定、镜像校验或事实层检查，但没有发现需要 upsert 的变化，所以 `changedRows/appliedRows` 为 0。
- 链路没有有效跑通：没有创建表级任务，或表级任务失败/跳过，但源级日志仍然展示为完成，导致“耗时 46 秒、影响 0”掩盖真实失败。

因此不能再用“影响表数/记录数”作为唯一结果字段。必须拆成：

- `plannedTables`：计划处理的源表数。
- `processedTables`：实际进入终态的表数。
- `scannedRows`：源侧扫描/校验过的行数。
- `appliedRows`：写入或更新镜像的行数。
- `changedRows`：业务上真正变化的行数。
- `factTasksPlanned` / `factTasksCompleted`：事实层刷新任务数。
- `noChange`：明确表达“校验完成但没有变化”。

46 秒是否偏长：如果它是每日全量校验并包含分片摘要、删除一致性检查和事实刷新，46 秒可以接受；如果它是用户点击的“首次全量同步”且没有任何变化、没有任何修复任务，则偏长。目标是先把耗时拆解到阶段，再优化 no-change 快路径。

### 1.2 “首次全量同步”语义需要纠正

当前用户看到“首次全量同步”，但如果镜像表已存在且水位/行数已经完整，它本质上不是首次灌库，而是“全量校验/重校验”。页面必须区分：

- `FIRST_LOAD`：镜像表为空或从未成功同步，需要实际灌入数据。
- `REVERIFY`：镜像已有成功状态，本次只做一致性校验。
- `REPAIR`：校验发现差异，创建分片修复或删除 reconcile 任务。

用户看到 0 记录时，应显示“已校验，未发现新增/更新”，而不是“影响记录数 0”。

### 1.3 立即增量同步 0 秒

0 秒不一定是错误。可能是：

- 已有同源活跃 job，接口复用了当前 job。
- 表级 probe 判断没有 dirty 表。
- 没有可增量的表，全部为 `VERIFY_ONLY` / `UNSUPPORTED`。
- strict planning 没有创建任务。

但当前缺少结构化原因。立即增量同步必须返回 `skipReason` 或 `reuseReason`，例如 `NO_DIRTY_TABLES`、`ACTIVE_JOB_REUSED`、`NO_INCREMENTAL_TABLES`、`SOURCE_BLOCKED`。

### 1.4 一直显示“执行中”、时间戳对不上

之前验证发现表级任务会自然收敛，但用户页仍可能看到执行中，核心原因是状态聚合和时间展示不可信：

- 页面刷新状态部分来自内存态，而不是持久化 job/task/fact task。
- `lastSyncedAt` 可能来自配置表的旧 `lastIncrementalSyncAt/lastFullSyncAt`，而不是实际表级任务终态。
- 容器/数据库/API/前端展示的时区不一致，UTC 时间被用户理解为北京时间。

本轮必须把状态源切到持久化任务聚合，并统一以 `Instant/OffsetDateTime` 存储、API 明确返回 offset，前端按用户时区展示。

### 1.5 页面级刷新定义

用户方案中的“每个表单独刷新”指业务页面当前展示表格或看板依赖的数据刷新，不是“系统设置 -> 数据库查看”里的物理数据库表刷新。

数据库查看页面保留为开发/运维排查入口，不作为普通用户的刷新最新数据能力。普通用户入口应位于：

- 缺陷/统计看板页面。
- 代码评审违规记录页面。
- 其他展示 GitLab 派生数据的业务表格页面。
- 评审数据管理页仅在刷新“关联 GitLab 上下文”时提供入口，不能误导为刷新手工评审字段。

### 1.6 评审数据管理是否需要刷新最新数据

结论：需要一个更窄、更明确的能力，名称不应叫通用“刷新最新数据”，建议叫“同步关联 GitLab 上下文”。

原因：

- 评审数据管理的主数据是本地手工创建/维护的评审记录，刷新 GitLab 不应该覆盖人工字段。
- 该模块里存在 GitLab 关联上下文字段，例如 `gitlab_base_url`、`project_id`、`resource_type`、`resource_id`、`template_code`。
- 如果页面展示 MR/Issue 标题、状态、作者、项目、标签、评论等 GitLab 派生信息，就需要用户可主动刷新关联上下文。

行为边界：

- 本地列表刷新：只重新请求 `/api/review-data/records`，不触发 GitLab 同步。
- 关联上下文刷新：只对当前筛选结果或选中记录涉及的 GitLab 资源建局部刷新任务。
- 不覆盖手工字段：人工录入的评审结论、问题项、专家、备注等字段不被 GitLab 刷新改写。

## 2. 本轮设计目标

优先级固定为：

1. 稳定性：任务状态必须可恢复、可重试、可诊断。
2. 准确率：最终一致优先，不能用 0 指标掩盖未执行或失败。
3. 可解释性：用户和运维能知道跑了哪些表、为什么 0 变更、事实层是否刷新。
4. 半实时性：通过业务页面手动刷新当前依赖数据加速新鲜度，但不承诺严格实时。
5. 效率：no-change 场景走快路径，避免每次都做重校验或事实重建。

## 3. 覆盖主方案功能的差异清单

| 主方案能力 | 当前差异 | 本轮处理 |
| --- | --- | --- |
| 表级持久化任务 | 已有 job/task/state，但 UI 聚合不足 | 补充指标聚合与终态判断 |
| 每日全量校验 | 可运行，但 no-change 耗时和 0 指标不可解释 | 拆分校验/修复/变更指标，增加快路径 |
| 补偿扫描 | 已作为日常追数主力，但需要展示原因 | 增加 skip/reuse/dirty 原因 |
| 用户页面手动刷新 | 看板/代码评审已有入口，状态不够完整 | 补齐持久化状态、事实层状态、前端展示 |
| 数据库查看刷新 | 目前易和用户刷新混淆 | 明确为运维入口，不作为业务能力 |
| 评审数据管理刷新 | 当前只有本地列表 reload | 增加可选“同步关联 GitLab 上下文” |
| 事实层刷新 | 已有 `fact_build_tasks`，页面不一定能感知 | 纳入刷新状态与完成判定 |
| Webhook 持久化管道 | 仍有未完成差异 | 标为后续独立阶段，不作为本轮阻塞项 |
| 多源健康 | `dgm` 等异常源需要更明确 BLOCKED | 本轮补健康状态与错误展示 |
| 时区一致性 | UTC/北京时间混淆 | 本轮统一存储和展示口径 |

## 4. 实施任务

### Task 1: 增加同步结果指标模型

**Files:**
- Modify: `backend/src/main/java/com/data/collection/platform/entity/GitlabSyncJob.java`
- Modify: `backend/src/main/java/com/data/collection/platform/entity/GitlabTableSyncTask.java`
- Modify: `backend/src/main/java/com/data/collection/platform/mapper/GitlabSyncJobMapper.java`
- Modify: `backend/src/main/java/com/data/collection/platform/mapper/GitlabTableSyncTaskMapper.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/GitlabMirrorSyncService.java`
- Test: `backend/src/test/java/com/data/collection/platform/service/GitlabMirrorSyncServiceTest.java`

**Step 1: Write failing tests**

新增测试覆盖：

- 表级任务 rows_scanned > 0、rows_applied = 0 时，源级 job 返回 `noChange=true`。
- plannedTables > 0 且 processedTables > 0 时，不允许 UI 只看到 affected=0。
- plannedTables = 0 时必须有 `skipReason`。

Run:

```powershell
mvn -q "-Dtest=GitlabMirrorSyncServiceTest" test
```

Expected: 新断言失败，因为当前没有完整指标聚合。

**Step 2: Add metric fields or derive from existing task fields**

优先复用现有字段：

- `gitlab_table_sync_tasks.rows_scanned`
- `gitlab_table_sync_tasks.rows_applied`
- task `status`
- task `task_type`

如果源级 job 缺少快照字段，优先在响应 DTO 聚合，不急于扩表；只有 UI 需要历史列表稳定展示时，再增加 job 汇总字段。

**Step 3: Aggregate terminal state from table tasks**

在 `GitlabMirrorSyncService` 中增加统一聚合方法：

- 统计计划表数。
- 统计成功/失败/跳过/不支持表数。
- 汇总 scanned/applied。
- 区分 `SUCCESS_NO_CHANGE` 与 `SUCCESS_WITH_CHANGES` 的展示语义。

**Step 4: Run tests**

Run:

```powershell
mvn -q "-Dtest=GitlabMirrorSyncServiceTest,GitlabTableSyncWorkerServiceTest" test
```

Expected: PASS.

### Task 2: 修正全量同步和增量同步的用户可见语义

**Files:**
- Modify: `backend/src/main/java/com/data/collection/platform/controller/GitlabSyncController.java`
- Modify: `backend/src/main/java/com/data/collection/platform/entity/GitlabSyncDiagnosticsResponse.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/GitlabTableSyncPlanningService.java`
- Modify: `frontend/src/api-client/gitlab-sync-api.ts` if present, otherwise the existing GitLab sync API client file
- Modify: GitLab mirror settings page component under `frontend/src`
- Test: `backend/src/test/java/com/data/collection/platform/controller/GitlabSyncControllerTest.java`

**Step 1: Write controller tests**

覆盖三种返回：

- `FIRST_LOAD`：镜像无成功状态。
- `REVERIFY`：镜像已有成功状态且没有差异。
- `REPAIR`：校验发现差异并创建修复任务。

立即增量同步覆盖：

- `ACTIVE_JOB_REUSED`
- `NO_DIRTY_TABLES`
- `NO_INCREMENTAL_TABLES`
- `SOURCE_BLOCKED`

Run:

```powershell
mvn -q "-Dtest=GitlabSyncControllerTest,GitlabTableSyncPlanningServiceTest" test
```

Expected: FAIL.

**Step 2: Extend response contract**

建议返回字段：

```json
{
  "jobId": 123,
  "mode": "REVERIFY",
  "status": "SUCCESS",
  "plannedTables": 6,
  "processedTables": 6,
  "scannedRows": 18220,
  "appliedRows": 0,
  "changedRows": 0,
  "skipReason": null,
  "reuseReason": null,
  "message": "校验完成，未发现新增或更新"
}
```

**Step 3: Update frontend copy**

替换“影响表数/影响记录数”的单一表达：

- “计划表数”
- “已处理表数”
- “扫描行数”
- “写入行数”
- “业务变化”
- “本次无变化”

**Step 4: Run tests**

Run:

```powershell
mvn -q "-Dtest=GitlabSyncControllerTest,GitlabTableSyncPlanningServiceTest" test
& "C:\Program Files\nodejs\npm.cmd" run typecheck
```

Expected: PASS.

### Task 3: 建立持久化页面级刷新状态

**Files:**
- Modify: `backend/src/main/java/com/data/collection/platform/service/RealtimeWorkspaceService.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/GitlabMirrorSyncService.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/statistics/RealtimeStatisticBoardSupport.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/statistics/StatisticBoardRegistry.java`
- Modify: `backend/src/main/java/com/data/collection/platform/controller/StatisticBoardController.java`
- Modify: `backend/src/main/java/com/data/collection/platform/controller/CodeReviewController.java`
- Test: `backend/src/test/java/com/data/collection/platform/controller/StatisticBoardControllerTest.java`
- Test: `backend/src/test/java/com/data/collection/platform/controller/CodeReviewControllerTest.java`

**Step 1: Write failing tests**

覆盖：

- 页面刷新接口返回 `jobId`、`sourceTables`、`plannedTasks`、`unsupportedTables`、`factRefreshPlanned`。
- 刷新状态查询从 `gitlab_sync_jobs`、`gitlab_table_sync_tasks`、`fact_build_tasks` 聚合，而不是只读内存。
- 镜像任务成功但事实任务失败时，页面状态为 `PARTIAL_SUCCESS` 或 `FACT_FAILED`。

Run:

```powershell
mvn -q "-Dtest=StatisticBoardControllerTest,CodeReviewControllerTest" test
```

Expected: FAIL.

**Step 2: Introduce page refresh status DTO**

建议状态字段：

- `pageKey`
- `configId`
- `jobId`
- `mirrorStatus`
- `factStatus`
- `status`
- `startedAt`
- `finishedAt`
- `lastSyncedAt`
- `sourceTables`
- `plannedTasks`
- `unsupportedTables`
- `message`

**Step 3: Persist and poll by job**

页面刷新触发后：

- 创建或复用 `MANUAL_REFRESH` job。
- 返回 `jobId`。
- 前端轮询状态接口。
- 状态接口每次从持久化任务聚合。

**Step 4: Run tests**

Run:

```powershell
mvn -q "-Dtest=StatisticBoardControllerTest,CodeReviewControllerTest,GitlabMirrorSyncServiceTest" test
```

Expected: PASS.

### Task 4: 明确数据库查看刷新与业务页面刷新的边界

**Files:**
- Modify: `frontend/src/components/DatabaseBrowserView.vue`
- Modify: GitLab mirror settings/admin page components under `frontend/src`
- Modify: `frontend/src/composables/useStatisticBoardRefreshController.ts`
- Modify: `frontend/src/components/StatisticBoardToolbar.vue`
- Test: `frontend/src/composables/useStatisticBoardRefreshController.test.ts`
- Test: `frontend/src/components/StatisticBoardToolbar.test.ts`

**Step 1: Write failing frontend tests**

覆盖：

- 数据库查看页刷新按钮只 reload 当前查看数据，不触发 GitLab mirror sync。
- 业务看板刷新按钮调用页面级 refresh endpoint。
- 页面级 refresh UI 展示 mirror/fact 两段状态。

Run:

```powershell
& "C:\Program Files\nodejs\npm.cmd" run test -- --run src/components/StatisticBoardToolbar.test.ts src/composables/useStatisticBoardRefreshController.test.ts
```

Expected: FAIL.

**Step 2: Update copy and controls**

数据库查看页文案建议：

- “重新加载表数据”
- “仅用于运维查看，不触发 GitLab 同步”

业务页面文案建议：

- “刷新最新数据”
- 状态展示：“镜像同步中 / 事实刷新中 / 已是最新 / 部分失败”

**Step 3: Run frontend checks**

Run:

```powershell
& "C:\Program Files\nodejs\npm.cmd" run typecheck
& "C:\Program Files\nodejs\npm.cmd" run test -- --run src/components/StatisticBoardToolbar.test.ts src/composables/useStatisticBoardRefreshController.test.ts
```

Expected: PASS.

### Task 5: 为评审数据管理增加“同步关联 GitLab 上下文”

**Files:**
- Modify: `backend/src/main/java/com/data/collection/platform/controller/ReviewDataController.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/ReviewDataRecordService.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/ReviewDataRecordQueryService.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/GitlabTableSyncPlanningService.java`
- Modify: `frontend/src/views/ReviewDataManagementView.vue`
- Modify: `frontend/src/views/review-data/useReviewDataPageActions.ts`
- Modify: `frontend/src/api-client/review-data-api.ts`
- Test: `backend/src/test/java/com/data/collection/platform/controller/ReviewDataControllerTest.java`
- Test: `backend/src/test/java/com/data/collection/platform/service/ReviewDataRecordServiceTest.java`
- Test: `frontend/src/views/review-data/useReviewDataPageActions.test.ts`

**Step 1: Write backend tests**

覆盖：

- 没有 GitLab 关联的评审记录不能触发 GitLab 上下文刷新，只返回本地 reload。
- `resource_type=merge_request` 时只规划 MR 相关依赖表。
- `resource_type=issue` 时只规划 issue 相关依赖表。
- 刷新不会改写手工字段。

建议依赖表：

- Merge Request: `merge_requests`, `merge_request_metrics`, `merge_request_reviewers`, `merge_request_assignees`, `projects`, `users`, `namespaces`.
- Issue: `issues`, `projects`, `users`, `labels`, `label_links`, `notes`.

Run:

```powershell
mvn -q "-Dtest=ReviewDataControllerTest,ReviewDataRecordServiceTest,GitlabTableSyncPlanningServiceTest" test
```

Expected: FAIL.

**Step 2: Add endpoint**

建议新增：

```text
POST /api/review-data/records/gitlab-context/refresh
GET  /api/review-data/records/gitlab-context/refresh/{jobId}
```

请求范围支持：

- 当前筛选条件。
- 选中记录 ID 列表。
- 单条记录详情页。

**Step 3: Frontend behavior**

保留现有本地刷新：

- “刷新列表”：只重新查询本地评审记录。

新增 GitLab 上下文刷新：

- “同步关联 GitLab 上下文”：只在当前结果存在 GitLab 关联时可用。
- 刷新完成后 reload 本地列表。
- UI 明确提示不覆盖人工评审字段。

**Step 4: Run tests**

Run:

```powershell
mvn -q "-Dtest=ReviewDataControllerTest,ReviewDataRecordServiceTest,GitlabTableSyncPlanningServiceTest" test
& "C:\Program Files\nodejs\npm.cmd" run test -- --run src/views/review-data/useReviewDataPageActions.test.ts
& "C:\Program Files\nodejs\npm.cmd" run typecheck
```

Expected: PASS.

### Task 6: 修复时间戳与时区展示

**Files:**
- Modify: `backend/src/main/resources/application*.yml`
- Modify: Docker compose or deployment config files that define `dcp-target-backend`
- Modify: API response DTOs that expose sync timestamps
- Modify: frontend date formatting utility if present
- Test: `backend/src/test/java/com/data/collection/platform/controller/GitlabSyncControllerTest.java`
- Test: frontend timestamp display tests if present

**Step 1: Write tests**

覆盖：

- API 返回带 offset 的 ISO 时间。
- 前端展示北京时间时不再把 UTC 当本地时间。
- `lastSyncedAt` 来自实际 job/task/fact terminal time，不来自旧 config 字段。

Run:

```powershell
mvn -q "-Dtest=GitlabSyncControllerTest,StatisticBoardControllerTest" test
```

Expected: FAIL.

**Step 2: Standardize time policy**

策略：

- 数据库存储统一用 `Instant` 或明确 UTC。
- API 返回 `OffsetDateTime` ISO 字符串。
- 容器设置 `TZ=Asia/Shanghai` 仅用于日志和人工排查。
- JVM 增加 `-Duser.timezone=Asia/Shanghai` 仅作为运行环境一致性保护，不替代 API 明确 offset。

**Step 3: Run tests**

Run:

```powershell
mvn -q "-Dtest=GitlabSyncControllerTest,StatisticBoardControllerTest,CodeReviewControllerTest" test
& "C:\Program Files\nodejs\npm.cmd" run typecheck
```

Expected: PASS.

### Task 7: 优化 no-change 全量校验效率

**Files:**
- Modify: `backend/src/main/java/com/data/collection/platform/service/GitlabMirrorSyncService.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/GitlabTableSyncPlanningService.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/GitlabTableSyncWorkerService.java`
- Modify: `backend/src/main/java/com/data/collection/platform/config/GitlabMirrorProperties.java`
- Test: `backend/src/test/java/com/data/collection/platform/service/GitlabMirrorSyncServiceTest.java`
- Test: `backend/src/test/java/com/data/collection/platform/service/GitlabTableSyncPlanningServiceTest.java`

**Step 1: Write performance-oriented unit tests**

覆盖：

- count/max_updated_at/schema fingerprint 未变化时，不创建 shard repair。
- 镜像层无变化时，不重复创建事实层重建任务。
- 同源同表短窗口内重复全量校验合并或复用 job。

Run:

```powershell
mvn -q "-Dtest=GitlabMirrorSyncServiceTest,GitlabTableSyncPlanningServiceTest" test
```

Expected: FAIL.

**Step 2: Add fast path**

no-change 快路径：

1. 先读取表级 state。
2. 源侧 probe 只查 `count`、`max(updated_at)`、pk range、schema fingerprint。
3. 与 state 一致时标记 `VERIFIED_NO_CHANGE`。
4. 不创建增量 scan、不创建 shard repair、不创建事实重建。

**Step 3: Add duration breakdown**

记录阶段耗时：

- `probeDurationMs`
- `planningDurationMs`
- `mirrorTaskDurationMs`
- `factDurationMs`
- `totalDurationMs`

**Step 4: Acceptance target**

验收目标不是硬编码测试，而是手动验证口径：

- no-change 的重校验应明显快于 46 秒。
- 如果仍超过 30 秒，必须能看到耗时集中在哪个阶段。
- 真正首次灌库或发现差异修复可以超过该时间，但必须显示扫描/写入/修复量。

### Task 8: 源健康 BLOCKED 与异常源隔离

**Files:**
- Modify: `backend/src/main/java/com/data/collection/platform/service/GitlabSourceHealthService.java`
- Modify: `backend/src/main/java/com/data/collection/platform/entity/GitlabSourceHealthResponse.java`
- Modify: `backend/src/test/java/com/data/collection/platform/service/GitlabSourceHealthServiceTest.java`

**Step 1: Write tests**

覆盖：

- GitLab DB 密码为空或认证失败时，源健康为 `BLOCKED`。
- 缺少镜像表时，健康状态返回具体 missing table。
- 异常源不会让其他 config 的同步状态变成执行中。

Run:

```powershell
mvn -q "-Dtest=GitlabSourceHealthServiceTest,GitlabSyncControllerTest" test
```

Expected: FAIL.

**Step 2: Implement health classification**

建议状态：

- `OK`
- `DEGRADED`
- `BLOCKED`
- `DISABLED`

认证失败、配置缺失、白名单不可读属于 `BLOCKED`。

**Step 3: Run tests**

Run:

```powershell
mvn -q "-Dtest=GitlabSourceHealthServiceTest,GitlabSyncControllerTest" test
```

Expected: PASS.

### Task 9: 补齐发布前联调验证脚本

**Files:**
- Modify: `scripts/gitlab-direct-sync-check.ps1`
- Modify: `scripts/verify-local.ps1`
- Modify: `docs/gitlab-direct-sync-webhook-runbook.md`
- Test: manual verification

**Step 1: Add script checks**

脚本应输出：

- 当前 config 健康状态。
- 最新 full/incremental/manual refresh job。
- planned/processed/scanned/applied/changed 指标。
- fact task 状态。
- 时区样本。
- 页面刷新 endpoint smoke。
- 评审数据 GitLab 上下文刷新 smoke。

**Step 2: Manual run**

Run:

```powershell
.\scripts\gitlab-direct-sync-check.ps1 -BaseUrl http://localhost:18080 -ConfigId 1
```

Expected:

- 看到 full sync 和 incremental sync 的结构化结果。
- no-change 不再只显示影响 0。
- 页面刷新能看到 mirror/fact 两段状态。

## 5. 验收矩阵

### 5.1 稳定性

- [ ] Full sync、incremental sync、manual page refresh 都返回持久化 `jobId`。
- [ ] 页面刷新状态从数据库聚合，不依赖服务内存。
- [ ] 表级任务失败只影响单表，源级 job 汇总为 `PARTIAL_SUCCESS`。
- [ ] 异常源为 `BLOCKED`，不污染其他源。

### 5.2 准确率

- [ ] 0 变更场景显示为“校验完成，无变化”，而不是无法解释的“影响 0”。
- [ ] 镜像成功但事实刷新失败时，业务页面不显示“已最新”。
- [ ] 评审数据管理不会用 GitLab 刷新覆盖人工字段。
- [ ] 无法增量的表返回 `unsupportedTables`。

### 5.3 半实时性

- [ ] 看板和代码评审记录页可以触发当前页面依赖表刷新。
- [ ] 评审数据管理可以按选中记录或当前筛选范围同步关联 GitLab 上下文。
- [ ] 数据库查看页刷新只 reload 运维查看数据，不触发普通业务刷新语义。

### 5.4 效率

- [ ] no-change 全量校验优先走 probe 快路径。
- [ ] 重复点击刷新会复用或合并短窗口内相同页面 job。
- [ ] 镜像无变化时不重复创建事实重建任务。
- [ ] 如果耗时仍偏长，接口能展示阶段耗时定位瓶颈。

### 5.5 时间一致性

- [ ] 容器日志、API 返回、前端展示能解释同一时间点。
- [ ] `lastSyncedAt` 来自任务终态，不来自旧配置字段。

## 6. 最小发布顺序

推荐按以下顺序实现，避免前端先改后没有可靠状态源：

1. Task 1：同步指标模型。
2. Task 2：全量/增量结果语义。
3. Task 3：页面级刷新持久化状态。
4. Task 6：时区与 `lastSyncedAt`。
5. Task 4：数据库查看与业务刷新边界。
6. Task 5：评审数据管理 GitLab 上下文刷新。
7. Task 7：no-change 效率优化。
8. Task 8：源健康 BLOCKED。
9. Task 9：联调脚本与 runbook。

## 7. 本轮不直接承诺完成的事项

Webhook 持久化管道仍是主方案中的重要功能，但本轮问题集中在手动全量、立即增量、页面刷新和运行态可解释性。Webhook 管道建议单独开后续计划，不应混进本轮导致同步状态和页面刷新问题继续拖延。

## 8. 最终完成定义

本方案完成后，用户应能回答以下问题：

- 全量同步 46 秒到底花在哪里。
- 影响数为 0 是没有变化，还是任务没有创建/失败/被跳过。
- 立即增量同步 0 秒是复用、无变化、无可增量表，还是源被阻塞。
- 当前业务页面刷新了哪些 GitLab 源表，事实层是否也刷新完成。
- 评审数据管理刷新的是本地列表，还是关联 GitLab 上下文。
- 页面展示时间与日志时间为什么一致，差异如何换算。

