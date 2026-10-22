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

延期标签写回通过 GitLab REST API 执行；代码走查指标补齐等功能也可能使用 API。
DOCKER 模式下核心镜像读取使用来源数据库，因此镜像同步、事实和统计正常并不能证明
写回令牌有效，必须单独核验写回凭据。

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
   queue) or `platform.gitlab-mirror.scheduler-enabled=false` and
   restart the backend. The scheduler switch stops this worker; it does not disable
   the separately scheduled delay recalculation trigger.
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
5. Switch state: the per-source `delay_label_writeback_enabled` switch is on (since D-23
   there is no deployment-level global switch). A writeback job only adds or removes
   `响应已延期`/`解决已延期`; it never rewrites unrelated labels.
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

## 老平台延期标签写回接替边界

本节是延期写回接替的运维入口；业务口径见 `docs/platform-page-business-rules.md` 5.3，
机制缺口见 `docs/architecture.md`“事实与统计”，验证与接替状态见 `docs/progress.md`。
以下基于 2026-10-14 本地源码审查，不代表已核验生产部署版本或获准生产切换。
用户已于本日选定“旧平台局部停写、新平台独占延期标签维护”，不采用旧写完后再由新平台纠正的双写方式；
新平台修复仍处于方案确认阶段，未执行旧平台停写部署。

### 老平台停写范围及附带影响

老平台 `D:/projects/spidergitdata-dev` 中，延期写回共用
`IssueServiceImpl.updateIssueDelayLabels`，有三个调用入口：

| 入口 | 行为与停用边界 |
| --- | --- |
| `CCProductScheduleTask.updateCCProductIssueInfo2SpiderIssueData` | 每小时从 GitLab API 采集项目 325 的议题、评论与标签事件，解析后按项目删除/重填旧 MySQL `spider_issue_data`，采集时间写旧 MongoDB；再筛 open 且无 GitLab 接口错误的议题，向 GitLab 写延期标签。只停最后的写回部分，保留采集与上下文清理。 |
| `GET /IssueAnalysis/updateIssueLabelById` | 从 GitLab API 读取指定议题及解析所需评论/标签事件，生成内存对象后写延期标签；不保存旧 MySQL 议题数据。关闭旧前端不等于关闭此接口。 |
| `GET /IssueAnalysis/updateIssueLabelsByProjectId` | 从 GitLab API 采集指定项目、按项目删除/重填旧 MySQL `spider_issue_data`，再筛选并批量写延期标签。接口可传项目，但延期计算只对项目 325 产生写回目标。 |

同类中的另一个小时任务 `updateCCProductIssueInfo` 只把项目 325 解析结果更新到旧 MySQL
`project_issue_info` 并记录采集时间，不调用延期写回方法。
上述旧 MySQL/MongoDB 数据均不是新平台系统测试、客户问题的采集来源：新平台直接由 GitLab
进入自身 ODS、事实与统计链路。双方在本功能上的交点是同一个 GitLab 议题标签；旧平台写回后，
新平台可通过自己的镜像看到标签变化，不能把这个交点误认为新平台依赖旧采集结果。

局部停用统一写回方法的实际副作用可以覆盖三个入口；若删除调用点，应一并清理服务接口/实现并编译验证。
只注释定时任务中的一行不能保证完全停写。不要以停用整个 `@EnableScheduling`、整项采集任务或旧后端进程
替代局部停写：会扩大到仍依赖的代码走查与采集能力；为替换程序进行的短暂重启见下一节。
不要关闭共用 `GitLabApiTool.setIssueLabels`：
`SpiderCrowncadDataService.replaceLabelName` 的项目 9 批量标签替换也使用它。
`do.schedule` 配置无代码读取，不能用来停写。

统一写回方法只读入议题/标签、计算标签差异并调用 GitLab PUT，没有 MySQL/Mongo 持久化、代码走查更新或通知副作用。
因此源码上局部停写不会切断新平台仍依赖的老平台代码走查数据；旧平台自身的延期计算仍继续。
新平台接管前有停写空档时，GitLab 的两类延期标签保留原值、停止自动纠正；已有标签不会因停写自行删除。
新平台自身的镜像同步和按时间/模板计算延期事实继续运行；GitLab 页面及按原始标签展示/筛选的结果
仍会保留未更新的标签，因此事实判定与 GitLab 标签可能暂时不一致。
旧后端重部署仍可能短暂影响其接口，应单独安排窗口，不能把“代码依赖独立”解释为“部署零中断”。

### 替换旧后端程序的短暂重启

只停延期写回的变更可以通过“旧进程完全退出 → 同配置启动新包”部署；不是要求旧进程永久停机。
期间新平台系统测试/客户问题自己的采集链路不依赖该旧进程，旧 MySQL/MongoDB 持久化数据也不会因进程退出自行删除；
但代码走查“单条刷新”等调用旧 HTTP 的操作会暂时失败，重启成功后可重新操作。

本地源码的代码走查大批采集为每天 `00:00`、`12:00` 的 cron（以旧进程时区为准），不是启动后每 12 小时。
错过 cron 时点不会因重启自动补跑该轮；应避开触发点与正在执行的采集/写库阶段。
同时存在每 200 分钟的非法 MR 扫描、每 100 分钟的项目刷新及每小时 CC_PRODUCT 采集等 fixedRate/fixedDelay 任务，
均未设 initialDelay，重启后可能立即触发，不能据“多数任务 12 小时”推断启动后没有任务运行。
`CodeWalkThroughService.updateWalkThrough` 存在按项目删除后逐条插入，非法 MR 刷新也按议题删除再保存，
这些跨步骤流程未见整体事务；强制中断可留下部分数据，且自定义异步线程池未显式配置停机等待业务完成。
先从日志确认关键写库任务空闲，再停进程；确认旧进程退出及端口释放后才启动新包，避免两个版本同时运行。
启动后确认代码走查旧接口恢复、无采集错误、所有延期入口都不再调用 GitLab 标签 PUT。
本结论来自源码，未实际执行生产停启或证明现场停机耗时；现场包与启动参数仍需与本地版本核对。

### 切换前必须满足的条件

1. 先解决架构文档登记的写回候选修订可见性与重算后发布交接缺口，补充真实数据库集成回归；
   不得以 2026-09-28 旧版本的真实写回通过代替当前版本验证。
2. 单独审阅既有 D8 解决期限取值方向：当前新平台取最旧响应模板，老平台取最新可解析模板。
   例如创建于 10 月 1 日、旧计划 10 月 5 日、新计划 10 月 15 日，在 10 月 10 日会产生相反的解决延期判定。
   D-21 仅确认响应豁免新口径，不代表已确认此解决延期差异；修正需独立处理其产出影响。
3. 对目标环境只读生成去重后的预期加标/摘标清单，确认新旧判据差异；本地 70/30 不是生产变更数量。
4. 核对生产新旧部署版本、GitLab 实例地址、令牌有效期/权限、后台调度与 worker；
   令牌修正后检查历史 `DEAD` 任务，不能只换令牌后等待自动恢复。
5. 在旧平台统一写回边界停写并确认三个入口都不能发出延期 PUT；保留其他采集和代码走查服务。
   通过发布门禁后，再按明确的生产写回授权启用新平台数据源开关。
6. 保存目标范围写前标签基线，验收新平台双向变更、范围外与非延期标签零改动，下一轮无差异；
   失败先关闭新平台开关。旧规则不能在新规则运行时同时恢复，否则会反复加摘标签。

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


## 老平台延期标签写回接替边界

本节用于把 `响应已延期`/`解决已延期` 的维护权从老平台切到新平台。切换前必须确认老平台所有
写标签入口都已停写；新平台只管这两个标签，老平台其它采集与代码走查功能保留。

### 1. 老平台停写

1. 停掉老平台定时入口 `CCProductScheduleTask -> IssueServiceImpl.updateIssueDelayLabels`
   （约每小时无条件覆盖式写回），并确认没有部署级补偿、手工任务或外部脚本仍调用
   `setIssueLabels` 写这两个标签。
2. 停写后至少观察两个完整周期，确认目标议题的两个延期标签不再变化；只停 UI 或只关闭页面
   开关不算停写，老平台该写回没有运行期开关。
3. 保留老平台其它采集、代码走查和页面功能；本轮不修改老平台仓库或生产环境。

### 2. 新平台接管前置

1. 目标实例已升级到包含 `V20261022_03__customer_issue_delay_closure_progress.sql` 的版本，
   Flyway 成功；旧迁移未被修改。
2. 数据源已启用、`web_base_url` 与 `api_token` 齐备、页面「延期标签写回」开关为开；
   地址与令牌齐备不代表可以跳过来源资格检查。
3. 确认没有未结算的全量事实意图、没有长时间未收敛的根；否则新平台会停在 `WAIT_FACT`，
   这是预期等待，不要手工改阶段或直接塞队列。
4. `CUSTOMER_ISSUE_DELAY_PRE_WRITEBACK_SYNC_ENABLED` 必须为 true；关闭时本轮只重算延期事实，
   不会写标签。

### 3. 开启与验收

开启开关后，编排按 `REQUESTED -> WAIT_MIRROR -> WAIT_FACT -> ENQUEUE -> IDLE` 推进。检查：

```sql
-- 平台库：来源级进度（每个 source_instance 至多一行）
select source_instance, config_id, stage, request_revision, processed_revision,
       completed_revision, mirror_run_id, authorized_fact_version,
       authorization_revision, enqueue_cursor_issue_id, failure_count,
       next_check_at, lease_owner, last_error
  from customer_issue_delay_closure_requests;

-- 平台库：队列状态
select status, count(*) from customer_issue_delay_label_writeback_jobs group by status;

-- 平台库：最近作业与 HTTP 结果
select id, issue_iid, status, attempt_count, authorization_revision, task_revision,
       round_mirror_run_id, last_http_status, last_error, updated_at
  from customer_issue_delay_label_writeback_jobs
 order by updated_at desc
 limit 20;
```

首轮验收必须同时满足：

- `mirror_run_id` 对应的运行是 `SUCCESS`，且 `sync_run_table_tasks` 中 `issues`、`notes`、
  `label_links`、`labels` 均为 `SUCCESS`；
- 进度先经过 `WAIT_FACT` 再进入 `ENQUEUE/IDLE`，不能在镜像未终态或事实未收敛时直接入队；
- 作业的 `authorization_revision` 与进度一致，`required_fact_version` 与当前
  `fact_change_heads` 的 ISSUE 版本上界一致；
- 只出现 `add_labels`/`remove_labels` 对两个延期标签的增删，非延期标签和范围外议题不变；
- 下一轮镜像与事实收敛后，已收敛议题不再产生新的写回差异（`enqueued` 为 0 或仅剩真实差异）。

### 4. 失败与回退

1. 关闭页面开关：配置保存与授权撤销同事务提交，worker 发送前复核会停止后续发送；已入队
   未发送的作业会跳过或等待下一轮重新评估。
2. 清空 `api_token` 或 `web_base_url`；或设置
   `CUSTOMER_ISSUE_DELAY_WRITEBACK_WORKER_ENABLED=false` /
   `platform.gitlab-mirror.scheduler-enabled=false` 后重启。
3. 在 GitLab 侧撤销令牌：写回 401 转 `DEAD`，不改标签。
4. 回退到老平台写回前必须先确认新平台已完全停写，并评估两边判据差异（尤其 D8 解决期限
   取值差异），避免双写互相覆盖。不要用批量改库直接复活 `DEAD` 作业；先核对失败原因和令牌，
   再由运维显式处理。

### 5. 已知差异与未承诺项

- D8 解决期限取值差异仍为独立风险，本单元未修改延期公式，不能声称新平台与老平台逐条完全一致。
- GitHub/GitLab 网络请求发出后无法与本地数据库撤销组成原子事务；超时按“发送结果未知”重试，
  由下一轮重读标签收敛，不承诺绝对只发送一次。
- 真实内网容量、标签基线和令牌有效期必须在现场核对，本 runbook 不替代现场验收。
