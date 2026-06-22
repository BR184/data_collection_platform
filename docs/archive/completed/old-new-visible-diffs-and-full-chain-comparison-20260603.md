
# Old/New Visible Diffs And Full-Chain Comparison - 2026-06-03

This report records the local old-platform vs new-platform data-alignment pass for project `2` / source `cc`.

## Evidence

| Evidence | Path |
| --- | --- |
| Baseline setup used for this pass | `.tmp/old-new-data-alignment-20260603/setup_alignment_baseline.py` |
| Fresh identity comparison | `.tmp/old-new-data-alignment-20260603/data-identity-alignment-report.json` |
| Comparison script | `.tmp/old-new-data-alignment-20260603/alignment_check.py` |
| Earlier API/page smoke bundle | `.tmp/old-new-full-compare-20260603/` |

Fresh run: `2026-06-03T09:37:15`.

## Current Verdict

Hard data identity is now aligned for the checked local scopes.

The acceptance rule for this pass is stricter than totals only: count, identity keys, and key fields such as IID/MR IID/title are compared. Display labels and rule text are still listed separately because the user explicitly accepted loose tag/module parsing for now, but not missing or extra records.

## Root Causes And Fixes

| Area | Root cause | Fix applied | Fresh result |
| --- | --- | --- | --- |
| Source issue facts | Old `spider_issue_data` local baseline had lost the original project `2` system-test rows, leaving only the newly added customer fixture. | Restored old `spider_issue_data` rows `#1..#6` from current new `issue_fact`, then kept customer fixture `#9003`. | `old=7`, `new=7`, keys `1,2,3,4,5,6,9003`, titles aligned. |
| System-test issue list | Earlier count drift was scope mismatch plus missing old fixture rows. | Restored old source rows and kept the old combined regression/system-test phase scope aligned to the new system-test scope. | `old=1`, `new=1`, key `#2`, title aligned. |
| System-test illegal records | Old phase-specific API still returns 500 because the legacy phase definition path generates an invalid empty `IN ()` query. Data itself is present. | Compared illegal keys derived from the same-scope old issue list against new illegal endpoint. | Key `#2` aligned; rule text differs. |
| Integration details | Previous `0/0` only proved empty-set consistency. | Added deterministic local fixture `#9002` to old `spider_integration_data` and new `integration_test_fact`, then compared through real old/new HTTP endpoints. | `old=1`, `new=1`, key `#9002`, title and execute-case metric aligned. |
| Customer issues | Previous `0/0` only proved empty-set consistency. | Added deterministic local fixture `#9003` to old `project_issue_info` and old `spider_issue_data`, plus matching new `issue_fact` customer-scope row. | `old=1`, `new=1`, key `#9003`, title aligned. |
| Code-review illegal records | New platform had MR `!1`; old legacy static table `spider_crowncad_data` had no matching materialized row. The old endpoint also forces CrownCAD target branch `dev` when branch is omitted. | Added old static row for MR `!1` with `target_branch='dev'` and matching title. | `old=1`, `new=1`, MR IID `!1`, title aligned. |
| Review data | New platform had 11 active demo records created by new-only management seed/API flow; old Mongo `spider` review collections were empty. | Soft-deleted the new-only active demo review records `id=2..12` for parity. | `old=0`, `new=0`; no extra new-only review records remain in the comparison scope. |

## Fresh Identity Results

| Check | Result |
| --- | --- |
| `SOURCE-ISSUE-FACTS-PROJECT2` | `PASS_DATA_IDENTITY`: `old=7`, `new=7`, same keys and titles. |
| `PAGE-SYSTEM-ISSUE-LIST` | `PASS_DATA_IDENTITY`: `old=1`, `new=1`, key `#2`, same title. |
| `PAGE-SYSTEM-ILLEGAL-RECORDS` | `PASS_DATA_KEY_WITH_RULE_DIFF`: key `#2` aligned; old/new illegal reason wording differs. |
| `PAGE-INTEGRATION-DETAILS` | `PASS_DATA_IDENTITY`: fixture key `#9002`, title and execute-case aligned. |
| `PAGE-CODE-REVIEW-ILLEGAL` | `PASS_DATA_IDENTITY`: MR `!1`, title aligned. |
| `PAGE-REVIEW-DATA` | `PASS_EMPTY_AFTER_DEMO_CLEANUP`: both sides are empty after removing new-only demo records. |
| `PAGE-CUSTOMER-ISSUES` | `PASS_DATA_IDENTITY`: fixture key `#9003`, title aligned. |

## Remaining Non-Hard-Data Differences

These are not missing/extra-record problems:

- System-test visible fields still differ: module parsing, combined phase label, urgency placeholder, bug status wording, and status casing.
- System-test illegal reason wording still differs: old derived reason says missing severity/module-style legacy text; new reason says missing severity. The affected key remains `#2` on both sides.
- Old `/issueStaticData/getIllegalIssue` with the combined phase still returns 500. The data key was verified through the aligned old issue-list scope, but the legacy endpoint itself still needs a code fix if that route must be used directly.
- Old system-test export remains not comparable for local project `2` because the old export path is hard-coded to the legacy CrownCAD project id.

## Boundary Notes

The integration and customer fixtures are local comparison baselines, not proof of a live upstream GitLab sync path for those domains. They are still queried through real old/new HTTP endpoints after setup.

The review-data fix intentionally removes new-only demo records from the parity scope rather than inventing old Mongo records. If a non-empty review-data demo is required later, both sides should be rebuilt from the same legacy Excel import source.

## 2026-06-04 数据镜像调度语义与 PC 关机影响分析

本节只基于代码走查和当前仓库配置形成结论，尚未读取服务器当天真实运行日志。用户补充的关键背景是：中途运行平台的 PC 关机，一直到 2026-06-04 早上才开机；平台理论上已部署到服务器，因此不应因为这台 PC 关机而停止后端同步。

### 初步结论

- 如果后端服务、数据库、GitLab 源库访问链路都在服务器侧独立运行，PC 关机通常只会中断浏览器页面轮询、前端状态刷新、临时脚本或本机代理，不会中断服务器上的 `GitlabCompensationScheduler`、`GitlabDailyVerificationScheduler`、`SyncRunDispatcherService` 或后台 worker。
- 如果当时仍有任一关键组件实际跑在该 PC 上，例如本机后端、本机数据库、本机 Docker GitLab、SSH 隧道、端口转发、临时同步脚本，则 PC 关机会影响同步。这个需要用服务器进程、部署方式和当天日志二次确认。
- 当前数据新鲜度很好，不能自动推出“1 秒增量同步”。更合理的解释是：页面级“刷新最新数据”、System Hook、自动补偿、全量补偿、事实层刷新共同保证了新鲜度；其中 1 秒主要出现在前端等待/轮询，不是后台自动补偿扫描的周期。

### 三个容易混淆的时间参数

| 参数或行为 | 代码位置 | 当前默认 | 实际语义 | 是否等于 1 秒增量 |
| --- | --- | --- | --- | --- |
| `platform.gitlab-mirror.scheduler-delay-ms` | `GitlabCompensationScheduler.run()` | `60000 ms` | 自动补偿调度器心跳；每 60 秒醒一次，先恢复超时任务，再判断每个配置是否到了补偿时间。 | 否 |
| `platform.gitlab-mirror.run-dispatcher-delay-ms` | `SyncRunDispatcherService.runOnce()` | `2000 ms` | run 队列 dispatcher 每 2 秒尝试领取 `sync_runs` 中的 `QUEUED` run。 | 否 |
| `platform.gitlab-mirror.system-hook-batch-window-seconds` | `GitlabSystemHookAsyncDispatchService` | `3 s` | System Hook 事件批量 flush 窗口。 | 否 |
| 页面刷新等待 | `useStatisticBoardRefreshController.waitForRealtimeRefreshToSettle()`、`CodeReviewIllegalRecordsView.vue` | `1000 ms` | 前端点击“刷新最新数据”后等待/轮询状态的 UI 节拍。 | 否 |

因此，设置页里看到的“自动补偿间隔/每日时间/全量补偿时间”是业务调度策略；`scheduler-delay-ms`、`run-dispatcher-delay-ms`、System Hook flush 和前端轮询是运行时节拍。当前设置页没有把这几类节拍放在同一个解释框架里，确实容易让人误以为系统在做“1 秒增量”。

### 自动补偿模式和全量补偿对账的关系

自动补偿模式对应 `SyncRunType.COMPENSATION_SCAN`：

- 入口：`GitlabCompensationScheduler.run()`。
- 触发：调度器每 60 秒醒一次，但只有 `isDue(config, now)` 成立才会提交 run。
- 业务策略：由设置页中的 `compensationScheduleMode`、`compensationIntervalMinutes`、`compensationTime`、运行窗口和错过窗口策略决定。
- 表规划：如果 payload 没有指定表，优先从已有 `sync_run_table_states` 中挑选可增量运行的表；否则按白名单/全部表配置展开。
- 执行方式：普通增量表任务会先查源表 `max(updated_at)`；如果源端最大更新时间没有超过本地 watermark，则该表直接成功，扫描/写入 0 行。

全量补偿对账对应 `SyncRunType.FULL_COMPENSATION_SCAN`：

- 定时入口：`GitlabDailyVerificationScheduler.run()`，默认 cron 是每分钟检查一次。
- 手动入口：`POST /api/gitlab-sync/full-compensation-sync` 或 `/by-config`。
- 触发：配置启用 `fullCompensationEnabled`，且当前分钟等于 `fullCompensationTime`；手动按钮则直接提交。
- 表规划：按白名单/全部表展开。
- 执行方式：表任务策略是 `FULL_RECONCILE`，会全量读取源端批次并写镜像，最后做源/镜像差异清理；这不是只查 `updated_at` 的轻量扫描。

两者在 API 层都会映射成 `syncType=COMPENSATION`，但 `runType` 不同。前端已有 `COMPENSATION_SCAN` 和 `FULL_COMPENSATION_SCAN` 标签，不过同步日志的主视觉仍容易让两者都看起来像“补偿”。如果日志里没有看到“自动补偿模式/全量补偿对账”，可能有三类原因：未到业务触发时间不会创建 run；创建了但被同源活动 run 复用/去重；或者前端文案只显示 `syncType=COMPENSATION`，没有足够突出 `runType`。

### 为什么“计划表项”会超过源表数量

同步日志里的“计划表项”来自 `SyncRunLogService.taskLogSummary()` 对 `sync_run_table_tasks` 的统计；它不是严格意义上的“源数据库表数量”。

当前表任务默认 `batch_size=500`。当某张表数据量超过一个批次时，`SyncRunTableTaskExecutor` 会通过 `SyncTableContinuationPlanner.enqueueContinuationTask()` 继续排下一页任务。因此：

- 源库可发现表约 4000+ 张，只代表不同源表数量。
- 日志里的计划表项上万，可能是“表任务数量”，包含同一张大表的多批分页 continuation task。
- 全量同步、全量补偿对账、以及大表首次基线同步尤其容易出现“任务数大于表数”。

所以“全量更新才 4000+ 张表，但日志任务上万”不一定异常；需要进一步看 `sync_run_table_tasks` 按 `source_table` 聚合后的分布，确认是少数大表分页导致，还是白名单/全部表配置或重复提交导致。

### “刷新最新数据”不是旧的手动增量同步等价替身

旧的“手动增量同步”现在在数据镜像设置页主按钮上表现为“刷新最新数据”，后端仍是 `INCREMENTAL_SYNC`。但统计页/记录页里的“刷新最新数据”还存在另一类页面级实时刷新：

- 统计页调用 `requestRealtimeRefresh()`。
- 对应服务会按页面声明的 `REALTIME_REFRESH_TABLES` 刷新一组相关镜像表，例如系统测试/客户问题常见是 `issues`、`projects`、`users`、`label_links`、`labels`、`notes`。
- 随后会触发事实层刷新，例如 `factBuildService.rebuildIssueFacts(false)`。
- 前端最多等待 8 次、每次 1 秒，再重新加载页面数据。

这说明“刷新最新数据”按钮的速度取决于页面涉及的表、这些表的数据量、是否需要事实层刷新、当前是否有同源 run 占用互斥 scope，以及大表是否需要分页。它不是“只查所有表 `updated_at` 且 1 秒内完成”的轻量探测。

### 关于“自动补偿扫描应该 1 秒，只查 update_at”

代码现状不是这样的设计。当前自动补偿扫描是“每到业务补偿周期，规划一批可增量表任务；每个任务先用 `max(updated_at)` 快速判断是否无变更；有变更时再按 500 行批次拉取并写入”。也就是说：

- 无变更表可以很快跳过，但仍然需要对每个可运行表做一次源端 `max(updated_at)` 查询。
- 如果表很多，哪怕每张表只做 `max(updated_at)`，总耗时也会随表数量线性增长。
- 如果有变更或首次 watermark 不完整，就会进入批量读取/写入，不再只是 `updated_at` 探测。
- `scheduler-delay-ms=60000` 只控制调度器多久醒一次；不代表补偿 run 的执行耗时，也不代表每张表 60 秒更新一次。

因此，“自动补偿扫描日志里不显示 1 分钟更新”是符合当前实现的：每分钟醒来但未到配置周期时不会创建同步日志；只有实际提交 run、被复用、被跳过、失败或完成时，才更可能在应用日志或同步日志中看到可见记录。

### 下拉明细需要显示页面来源

用户提出“单表刷新要保留，但下拉的详细信息里要显示是哪个页面”。从现有结构看，单表刷新/页面实时刷新提交时已经把 `reason` 或 `boardKey` 传入，例如 `refreshTablesOnDemandDetailed(realtimeRefreshTables, boardKey)`。这为后续在同步日志展开详情中显示“来源页面/看板”提供了数据入口，但当前 `MirrorSyncLogTable` 展开详情主要显示运行编号、触发来源、同步内容、结果、记录数、错误和消息，没有明确的“页面来源”字段。

建议后续实现时把 `requestReason` 或 payload 中的 `boardKey` 规范化展示为“来源页面”，并和 `TABLE_REFRESH`、`INCREMENTAL_SYNC`、`COMPENSATION_SCAN`、`FULL_COMPENSATION_SCAN` 分开显示，避免用户在日志里只看到“补偿/刷新”而不知道是哪一个页面或策略触发。

### 需要后续用真实运行数据确认的问题

1. 2026-06-03 夜间到 2026-06-04 早上的后端进程是否一直在服务器运行。
2. 当时源库连接是否依赖已关机 PC 的隧道、Docker、端口转发或本地 GitLab。
3. 同步日志中上万“计划表项”按 `source_table` 聚合后，是分页 continuation task 造成，还是配置选了全部表并且表状态过多。
4. `COMPENSATION_SCAN` 和 `FULL_COMPENSATION_SCAN` 在最近日志中的触发时间、`runType`、`triggerType`、任务数、扫描行数、写入行数和耗时。
5. 设置页不增加“运行时节拍说明”：该文案容易被误读为否认“增量同步可约 1 秒完成”，已决定删除。

## 2026-06-04 复核与补充：机械层定位和落地方案

本节是对上面 2026-06-04 分析的复核和补充。读完源码后确认上面的概念结论全部成立，但原报告停在“概念区分”，没有落到“具体改哪一行/查哪一条 SQL/盯哪一个字段”，所以用户那种“看日志看不出在做什么”的体感，依旧无法在不二次提问的情况下闭环。下列补充把每个观察绑回到代码位置、数据库查询或最小修改点。

### 复核：与代码一致的部分

| 原报告结论 | 代码佐证 | 状态 |
| --- | --- | --- |
| 自动补偿调度器心跳 60 秒 | [GitlabCompensationScheduler.java:52-78](../backend/src/main/java/com/data/collection/platform/service/GitlabCompensationScheduler.java#L52-L78) `@Scheduled(fixedDelayString="${platform.gitlab-mirror.scheduler-delay-ms:60000}")` | 一致 |
| run dispatcher 2 秒领取 `QUEUED` | [SyncRunDispatcherService.java:35-47](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunDispatcherService.java#L35-L47) | 一致 |
| System Hook 3 秒 flush | [GitlabSystemHookAsyncDispatchService.java:83](../backend/src/main/java/com/data/collection/platform/service/GitlabSystemHookAsyncDispatchService.java#L83) | 一致 |
| 全量补偿对账每分钟检查、`fullCompensationTime` 命中才触发 | [GitlabDailyVerificationScheduler.java:43-64](../backend/src/main/java/com/data/collection/platform/service/GitlabDailyVerificationScheduler.java#L43-L64) | 一致 |
| 自动补偿模式不是“1 秒 / 只查 `updated_at`” | `isDue` 由业务模式（DAILY_TIME / 间隔 / 窗口）决定；不是固定 1 秒 | 一致 |
| 计划表项可以远多于源表数量 | [SyncRunTablePlanningService.java:95,283](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunTablePlanningService.java) 默认 `batchSize=500`；[SyncTableContinuationPlanner.java](../backend/src/main/java/com/data/collection/platform/service/sync/SyncTableContinuationPlanner.java) 分页继续 | 一致 |
| 页面级“刷新最新数据”和镜像设置主按钮“刷新最新数据”是两条路径 | `requestRealtimeRefresh` / `refreshTablesOnDemandDetailed` 与 `INCREMENTAL_SYNC` 入口分离 | 一致 |

### 原报告漏掉的关键事实

1. **每张表分页任务上限 50000**。[SyncTableContinuationPlanner.java:16](../backend/src/main/java/com/data/collection/platform/service/sync/SyncTableContinuationPlanner.java#L16) 设置 `DEFAULT_MAX_CONTINUATION_TASKS_PER_TABLE = 50000`，超过即抛 `BizException` 中止该表后续分页。这意味着“计划表项”最大可观测值受三个因素夹击：白名单/全部表数 × 每表分页数（≤50000） × 当前 run 数。看日志只看一个数字解释不了大小，但 SQL 一次聚合就能拆开（见下面查询模板）。
2. **`run_type` 在前端被收敛为 `syncType=COMPENSATION` 的位置**。后端把 `COMPENSATION_SCAN` 和 `FULL_COMPENSATION_SCAN` 映射到同一个 `syncType=COMPENSATION` 暴露给前端（见 `GitlabSyncControllerResponseMapper`），前端 `MirrorSyncLogTable.vue` 的展开详情不展示 `runType`，因此“补偿/对账”看起来都是“补偿”。这才是“看不出在做什么”的直接原因，不需要重写调度，只需要在响应里加一个 `runType` 字段并在表格展开里显示。
3. **`request_reason` 已经携带页面来源**。统计页和记录页提交单表刷新时已经把 `boardKey` 或来源说明拼到 `request_reason` 里 — 这是 `sync_runs.request_reason` 字段，通过 `SyncRunDispatcherService.mapRun` 一路返回。前端只需要在展开详情多渲染这一列。这不是新需求，是已有数据未渲染。

### 解决方案

按改动从小到大排列：

1. **[最小改动 / 立即落地] 同步日志展开详情新增两列**
   - 后端：`GitlabSyncControllerResponseMapper` 在 `runItemResponse` 输出里追加 `runType` 和 `requestReason`（已存在，未透出）。
   - 前端：[MirrorSyncLogTable.vue](../frontend/src/views/MirrorSyncLogTable.vue) 展开行模板增加“运行类型 / 来源页面”两列。
   - 解决“补偿/对账分不清”和“是哪个页面触发的刷新”两个体感问题。
2. **[已取消] 设置页“运行时节拍说明”面板**
   - 用户已确认不需要增加这段说明。
   - 增量同步在无新数据或少量变更时约 1 秒完成是正常体验；不再用额外说明干扰设置页。
3. **[低风险后端改动] 同步日志按 `run_type` 着色 / 分组**
   - 在 `MirrorRunQueueTable.vue` / `MirrorSyncLogTable.vue` 引入 `runType` 标签，独立于 `syncType`。`SyncRunType` 枚举在前端已存在（系统中可见 `COMPENSATION_SCAN` / `FULL_COMPENSATION_SCAN` 标签），只需让主视觉用 `runType`、副信息用 `syncType`。
4. **[需要排期] 把 `request_reason` 标准化为结构化字段**
   - 现状：`request_reason` 是自由文本，`boardKey` 通过 `payload_json` 传递。
   - 目标：在 `sync_runs` 增加 `source_page_key`、`trigger_surface`（页面/调度器/System Hook/手动按钮）字段，由 `SyncRunSubmissionService` 显式写入。
   - 这一步代价较大，但是日志可观察性的根本修复，前两项改动是它的过渡形态。

### 服务器侧排查清单（用真实数据收尾）

下面这几条 SQL/命令直接回答原报告 §需要后续用真实运行数据确认的问题，不需要再凭印象推断。在平台 Postgres `qaflex` 上执行：

```sql
-- 1) 服务器进程在 6/3 晚至 6/4 早上是否一直在跑
select max(heartbeat_at)                     as last_heartbeat,
       max(finished_at)                      as last_finished,
       count(*) filter (where status='RUNNING')  as still_running
  from sync_runs
 where created_at > '2026-06-03 18:00';
-- last_heartbeat 紧贴 06-04 早上 → 服务器侧未中断；
-- last_heartbeat 长时间停在 06-03 晚上 → 服务器进程或上游连接被中断（结合本机依赖核对）。

-- 2) 计划表项上万的真实分布：是分页 continuation 还是配置了过多表
select source_table, count(*) as task_count
  from sync_run_table_tasks
 where run_id in (
   select id from sync_runs
    where created_at > '2026-06-03 00:00'
      and run_type in ('FULL_SYNC','FULL_COMPENSATION_SCAN')
 )
 group by source_table
 order by task_count desc
 limit 30;
-- 头部少数表 task_count 很大 → 大表分页导致；
-- 大量表都贡献 1~2 个 → 白名单/全部表配置过宽。

-- 3) 最近 24 小时 COMPENSATION_SCAN vs FULL_COMPENSATION_SCAN 触发情况
select run_type, trigger_type,
       count(*)                       as runs,
       sum(planned_table_count)       as planned_tables,
       sum(scanned_rows)              as scanned,
       sum(applied_rows)              as applied,
       avg(extract(epoch from (finished_at - started_at))) as avg_seconds
  from sync_runs
 where created_at > now() - interval '24 hours'
   and run_type in ('COMPENSATION_SCAN','FULL_COMPENSATION_SCAN')
 group by run_type, trigger_type
 order by run_type, trigger_type;
-- 直接看出哪种补偿在跑、扫描了多少、写入了多少、平均多久。

-- 4) 验证“连接被本机依赖牵动”假设：自动补偿是否在 06-03 22:00 之后停过
select date_trunc('hour', created_at) as hour, run_type, count(*)
  from sync_runs
 where created_at > '2026-06-03 18:00'
 group by 1, 2
 order by 1, 2;
```

如果第 1 条 `last_heartbeat` 跨过整个夜间且第 4 条按小时分布连续，则可以判定服务器侧未受 PC 关机影响；剩下的就是 §解决方案 中的可观测性改造。

### 验证清单

- [ ] 同步日志展开详情显示 `runType` 与来源页面后，用户在不询问后端的情况下能直接区分“自动补偿/全量对账/单表刷新/System Hook”。
- [x] 设置页“运行时节拍说明”不再上线，避免把“1 秒完成”误解释成“不存在 1 秒增量体验”。
- [ ] 上述四条 SQL 跑过后，原报告 §需要后续用真实运行数据确认的问题 全部勾掉。

## 2026-06-04 续：「同步日志好像并不全」的根因与修复

### 结论先行

体感不是错觉——**前端展示的同步日志被后端硬编码成最近 10 条**，没有任何分页、加载更多、日期或类型筛选可以越过这个上限。再叠加四节拍下高频的 System Hook 触发，早期的补偿/全量对账记录会在几分钟内就被挤出窗口，UI 自然就"看不见"了。

### 证据链（按调用链从下往上）

| 位置 | 行为 | 影响 |
|------|------|------|
| [SyncRunStatusService.java:101-103](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunStatusService.java#L101-L103) | `recentLogs(config)` 内部直接 `logService.recentLogs(config, 10)` | **唯一调用方写死 10**，没有任何配置项 |
| [SyncRunLogService.java:32-46](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunLogService.java#L32-L46) | `recentLogs(config, limit)` 用 `last("limit " + limit)`，按 `created_at desc, id desc` 取头部 | 只能"看最近"，没有时间窗、没有 offset、没有 runType 过滤 |
| [GitlabSyncController.java:73-79](../backend/src/main/java/com/data/collection/platform/controller/GitlabSyncController.java#L73-L79) | `/api/gitlab-sync/status` 是**唯一**带日志的接口；全控制器没有 `/sync-runs` 这种独立列表端点 | 前端没有"翻页/查更多"的入口可用 |
| [MirrorSettingsView.vue:368, 1064](../frontend/src/views/MirrorSettingsView.vue#L1064) | `recentLogs` 直接来自 status 响应，传给 `MirrorSyncLogTable` | 表格里能看到的全部条数 = 后端给的那 10 条 |
| [MirrorSyncLogTable.vue:38-60](../frontend/src/views/MirrorSyncLogTable.vue#L38-L60) | `typeOptions` / `statusOptions` 从 `props.logs` 现有 10 条聚合；`filteredLogs` 只在这 10 条上过滤 | 想筛 "System Hook" 的下拉里**根本不会出现该选项**（除非这 10 条里恰好包含一条），筛选体验本身就在骗人 |

### 这条限制和体感如何对上

- System Hook 的 `system-hook-batch-window-seconds=3` 配上一个活跃 GitLab 实例，几分钟就能产出数十条 SYSTEM_HOOK 类型的 SyncRun。10 条的窗口会**首先**淹没补偿和全量对账的可见性。
- `created_at desc` 排序意味着只要有新增运行，旧的补偿/全量对账日志会**立刻**消失，不是慢慢消失——这正好对应"我以为补偿没跑"的错觉。
- 不存在任何旁路：诊断接口 [`/diagnostics`](../backend/src/main/java/com/data/collection/platform/controller/GitlabSyncController.java#L101-L113) 只返回当前快照、`/table-sync-diagnostics` 只查任务计数，都不返回历史 run 列表。

### 解决方案（按工作量排序，建议从 ① 开始）

**① 最小修复：把 limit 改可配置 + 提升默认值（半小时）**

- `application.yml` 增加 `platform.gitlab-mirror.recent-logs-limit: 100`（或 200）
- `GitlabMirrorProperties` 加字段；`SyncRunStatusService.recentLogs` 读它而不是写死 10
- 不动接口契约、不动前端，立刻把"丢失感"压下去

**② 中等修复：加一个独立的 `/sync-runs` 列表端点 + 服务端筛选（约半天）**

- 新接口 `GET /api/gitlab-sync/sync-runs?configId=&runType=&triggerType=&since=&until=&page=&size=`
- 复用 `SyncRunLogService.recentLogs` 的映射逻辑，但底层换成带 `WHERE` + `LIMIT/OFFSET` 的 MyBatis 查询
- 前端 `MirrorSyncLogTable` 把 `typeOptions` 改成静态枚举（来自 `SyncRunType`），不再依赖 props 自我聚合
- 加"加载更多"或分页器；筛选改成发请求而非纯前端 filter
- 这一步真正解决"看全所有同步记录"

**③ 完整改造：日志面板独立成页（1 天）**

- 把"最近同步日志"从 `MirrorSettingsView` 拆出，单独路由 `/admin/sync-runs`
- 默认显示当天，可切换 7/30 天；按 runType 分桶展示（System Hook、增量、补偿、全量对账各一栏）
- 配合上一节 §2026-06-04 复核 提到的 `runType` / 来源页面字段，一站式定位"刚才那次到底是哪类"

### 需要警惕的相邻问题

- `sync_runs` 表当前**没有**任何清理/归档逻辑（grep `delete from sync_runs` / `purge.*sync_run` 全无命中）。如果该表已经积累了几个月的高频 System Hook 记录，未加 `WHERE config_id` 或不带索引的 `OFFSET` 大翻页会随时间变慢——做 ② 的时候请确认 `(config_id, source_instance, created_at desc)` 索引存在；不存在就在 Flyway 里补一条迁移。
- 配合 §2026-06-04 复核 的字段补齐，"看全 + 看清"才是同一个改动周期里能闭环的事。

### 验证

- [ ] ① 上线后，用户描述的"日志不全"在常规一天的使用窗口内不再出现。
- [ ] ② 上线后，能用筛选 + 时间窗回放任意一天的所有 SyncRun，包括被挤出原 10 条窗口的 System Hook 与补偿。
- [ ] `EXPLAIN` 验证按 `(config_id, source_instance, created_at desc)` 取最近 N 条走的是索引扫描而不是全表排序。

## 2026-06-04 修订：真正的根因不是"窗口太小"，是有些刷新根本没生成日志行

### 现象再描述

用户截图中的关键观察：进入议题查询页时，"刷新最新数据"按钮**还没点**，议题列表已经是最新值——说明镜像层确实在背景里被刷新过；然而下方"最近同步日志"里**没有任何一行对应这次刷新**。这不是"被挤出 10 条窗口"——上一节假设错了。`sync_runs` 表里就**没插入这条记录**。

### 根因：`SyncRunSubmissionService.submitRun` 有五条 early-return，跳过 `INSERT sync_runs`

入口在 [SyncRunSubmissionService.java:178-279](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunSubmissionService.java#L178-L279)。`syncRunMapper.insert(run)` 在第 264 行——只有走到这里，前端 `recentLogs` 才能查到。但下面这五条 early-return 全部**绕过**了第 264 行：

| 分支 | 触发条件 | 返回 action | 写 sync_runs | 写 sync_run_events |
|---|---|---|---|---|
| [L196-L198](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunSubmissionService.java#L196-L198) | FULL_SYNC 重复提交 | `REUSED_*` | 否 | 否 |
| [L202-L203](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunSubmissionService.java#L202-L203) | FACT_REFRESH 已在跑 | `REUSED_*` | 否 | 否 |
| [L204-L206](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunSubmissionService.java#L204-L206) | COMPENSATION_SCAN / FULL_COMPENSATION_SCAN 已在跑 | `REUSED_*` | 否 | 否 |
| [L207-L218](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunSubmissionService.java#L207-L218) | 任意 mirror 类型，命中 `shouldReuseMirrorRun` | `DEDUPED` | 否 | **仅 TABLE_REFRESH** 写 event，其他类型完全无痕 |
| [L220-L231](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunSubmissionService.java#L220-L231) | TABLE_REFRESH 合并进当前 FULL_SYNC | `DEDUPED` | 否 | 是 |

第四条是和截图最相关的。复用规则在 [shouldReuseMirrorRun L337-L360](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunSubmissionService.java#L337-L360)：只要同源 `exclusive_scope` 上有 FULL_SYNC / INCREMENTAL_SYNC / SYSTEM_HOOK / FULL_COMPENSATION_SCAN 在 active，新的 TABLE_REFRESH 就被吞掉。**没有 INSERT、没有 sync_run_events 之外的记录**——除非该 active run 是 TABLE_REFRESH，才会通过 [recordMergeEvent L378-L408](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunSubmissionService.java#L378-L408) 写一条 event。

### 为什么 event 写了也不显示

[SyncRunLogService.recentLogs L32-L46](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunLogService.java#L32-L46) 只 `SELECT sync_runs`。`sync_run_events` 仅在 [`latestEventMessage` L98-L125](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunLogService.java#L98-L125) 里作为某条 sync_run 的"最新消息"被读一次——event 自己**不会**变成日志表的一行。所以截图里的"搭车刷新"既不算 sync_run 也不被当成独立日志条目，前端无论怎么放大窗口都看不见。

### 第二条隐身路径：FULL_SYNC 合并把 QUEUED 改成 MERGED

[mergeQueuedLowerPriorityMirrorRuns L297-L326](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunSubmissionService.java#L297-L326) 在新 FULL_SYNC 提交时，把所有 QUEUED 的低优先级 run 批量 `UPDATE status = 'MERGED'`。这些行**仍在 sync_runs 表里**，但 [SyncRunStateMachine.java:69](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunStateMachine.java#L69) 把 `MERGED` 映射成 API 状态 `PENDING`——视觉上和"还没跑"很难区分，配合用户对"灰色 = 没跑"的直觉，这些条会被忽略。

### 第三条隐身路径：System Hook 无目标早退

[GitlabSystemHookAsyncDispatchService.accept L45-L53](../backend/src/main/java/com/data/collection/platform/service/GitlabSystemHookAsyncDispatchService.java#L45-L53) 在 `sourceTables.isEmpty()` 时直接 `return`，只打 SLF4J `log.info`。每条不被精确同步规划器识别的 System Hook payload（事件类型未匹配、payload 字段缺失、object_kind 未支持）都是这条路径——前端**完全无感**。

### 综合定位：用户截图的发生路径（最可能）

1. 用户切到议题查询页，前端按 [`useStatisticBoardRefreshController` 等组合](../frontend/src/composables/useStatisticBoardRefreshController.ts) 自动调用 `TABLE_REFRESH(issues 等)`。
2. 此刻同源 `exclusive_scope` 上正有 INCREMENTAL_SYNC 或 SYSTEM_HOOK 在 active（前者来自 2 秒调度器命中、后者来自 3 秒批窗刷出）。
3. 命中 [L207 的 DEDUPED 分支](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunSubmissionService.java#L207-L218)，因为请求类型是 TABLE_REFRESH，[recordMergeEvent](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunSubmissionService.java#L378-L408) 写了 `TABLE_REFRESH_MERGED` event 进 `sync_run_events`。
4. 议题镜像表确实在 INCREMENTAL_SYNC / SYSTEM_HOOK 那条 active run 中被刷新——所以用户看到了最新数据。
5. 同步日志页 [recentLogs](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunLogService.java#L32-L46) 只读 `sync_runs`，没有任何 row 对应这次 TABLE_REFRESH 请求——用户描述的"同步策略里没有显示"。

也可能命中第三条 System Hook 早退——议题更新事件被 GitLab 推过来但 `preciseSyncPlanner` 当下未规划出 sourceTables（例如 object_kind 是 `note` 而当前规划器只覆盖 issue 主体）。这种情况下议题数据来自下一轮 INCREMENTAL_SYNC，但**那条 INCREMENTAL_SYNC 也可能被 §第一段表里的 dedup 规则吞掉**，于是看上去仍然是"刷新发生了，没有日志"。

### 验证 SQL（先证实是哪条路径）

```sql
-- A) 截图时段（举例 2026-06-04 16:30 ± 5min）所有 sync_runs，验证 TABLE_REFRESH 是否真的不存在
select id, run_id, run_type, trigger_type, status,
       created_at, started_at, finished_at, request_reason
  from sync_runs
 where created_at between '2026-06-04 16:25' and '2026-06-04 16:40'
 order by created_at;

-- B) 同时段 sync_run_events 里 TABLE_REFRESH_MERGED 数量——若 A 没有 TABLE_REFRESH 但 B 有 *_MERGED，则确认走了 L207 DEDUPED 路径
select event_type, count(*), min(created_at), max(created_at)
  from sync_run_events
 where created_at between '2026-06-04 16:25' and '2026-06-04 16:40'
 group by event_type
 order by 1;

-- C) 24h 内 MERGED 状态在 sync_runs 占比——评估第二条隐身路径影响面
select run_type,
       count(*) filter (where status = 'MERGED') as merged_runs,
       count(*) as total_runs
  from sync_runs
 where created_at > now() - interval '24 hours'
 group by run_type
 order by merged_runs desc;

-- D) 24h 内 SYSTEM_HOOK 早退（不会出现在 sync_runs，但会写应用日志）——只能在应用 log 里 grep
--    grep "no precise sync target was planned" backend.log | wc -l
```

### 解决方案（按粒度分档）

**① 让"被合并的请求"在日志里有一行（半天工作量，最小但最有效）**

> 这是真正回答"看到所有同步记录"诉求的改动。

在 `SyncRunSubmissionService` 的 5 条 early-return 中，统一做"短日志行"处理：

- 新增表 `sync_run_submissions`（或扩展 sync_run_events 一种 `event_type='SUBMISSION_DEDUPED'`），列：`id`, `config_id`, `source_instance`, `requested_run_type`, `trigger_type`, `target_run_id`（被合并到的那条）, `action`（REUSED / DEDUPED / SKIPPED_NO_TARGET）, `request_reason`, `source_tables`, `created_at`。
- 在 [L197 / L203 / L206 / L211 / L224](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunSubmissionService.java#L197) 五个 early-return 之前都各 INSERT 一行。
- 在 [GitlabSystemHookAsyncDispatchService.accept L45-L53](../backend/src/main/java/com/data/collection/platform/service/GitlabSystemHookAsyncDispatchService.java#L45-L53) 早退分支也 INSERT 一行（`action=SKIPPED_NO_TARGET`）。
- `SyncRunLogService.recentLogs` 改成 `UNION ALL` `sync_runs` 与 `sync_run_submissions`（或保持单表用 view），按 `created_at` 排序。前端用 `runType + action` 组合显示标签，例如 "刷新最新数据 · 已合并到 sr_inc_xxx"。

这一步落地后，"刷新被搭车"和"System Hook 无目标"两条都从隐身变可见，且零业务行为变更——只是把已有的内部状态变成用户可见的日志。

**② 让 MERGED 行在 UI 里清晰可辨（小改动，配合 ①）**

[useMirrorStatusPresentation](../frontend/src/views/useMirrorStatusPresentation.ts) 与 `mirror-settings-helpers` 中的 `logStatusText` / `logStatusType` 给 `SyncStatus.PENDING`-but-source-`MERGED` 的行专门一个标签 + 颜色（如灰底"已合并"），不再和"待执行"共用样式。需要让 `SyncRunLogService.toLogRow` 在 row 中暴露原始 `runStatus`（或 `merged: true`），前端才能区分。

**③ 用上一节 ① 的 limit 配置化作为**辅助**，但不再是主修复**

之前的"扩到 100 条"只能压住"被挤出窗口"那部分（如果存在），对当前根因——"根本没写"——无效。建议作为 ① 的搭配上线，而不是替代品。

### 验证清单（修订）

- [ ] 跑 SQL A、B：能精确指认截图时段走的是哪条 early-return。
- [ ] ① 上线后，再次复现"进议题页 → 数据已新 → 日志没记录"流程，日志表中**必有**一行 `requested_run_type=TABLE_REFRESH, action=DEDUPED, target_run_id=...`。
- [ ] SQL C 跑出的 MERGED 数量，在 UI 上按 ② 的样式可视，能与"待执行"区分。
- [ ] System Hook 收到一条 object_kind 未支持的 payload 时，日志表必有一行 `action=SKIPPED_NO_TARGET`。

## 2026-06-04 终稿：最终解决方案（取代 §修订 中的 ①/②/③）

### 范围澄清

- **System Hook 路径不在本次修复范围**。`GitlabSystemHookAsyncDispatchService.accept` 在 `sourceTables.isEmpty()` 时的早退分支不再处理——团队已决定放弃 System Hook 方向，后续观察其下线/降级。本节仅解决用户主动触发或调度器触发的同步请求 (`TABLE_REFRESH` / `INCREMENTAL_SYNC` / `FULL_SYNC` / `COMPENSATION_SCAN` / `FULL_COMPENSATION_SCAN` / `FACT_REFRESH`) 在日志里的可见性。
- 取代 §修订 中 "新增 `sync_run_submissions` 表" 的方案——那是过度设计。最终方案**不增加表**，只改写 `submitRun` 的早退分支与一处前端展示。

### 核心思路：把"被复用/合并"的请求也写成一行 sync_run

`sync_runs` 已经有 `MERGED` 终态、`parent_run_id` 字段、`request_reason` 字段，足以表达"这次请求被并入 sr_xxx"。改动只是：原来 5 条 early-return 直接 `return`，现在每条都先 `INSERT` 一行 `status=MERGED, parent_run_id=activeRun.id`，再 `return`。`recentLogs` 不动——它本来就只 SELECT `sync_runs`，行数自动变全。

### 后端改动

**1. `SyncRunSubmissionService.submitRun`：5 条 early-return 都补 INSERT**

[L196-L231](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunSubmissionService.java#L196-L231) 中以下 5 处早退前都先调用一个新私有方法 `recordAbsorbedSubmission(...)`：

| 行号 | 现行为 | 新增 |
|---|---|---|
| [L197](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunSubmissionService.java#L197) FULL_SYNC 重复 | `return reusedRun(...)` | INSERT MERGED → 然后 return |
| [L203](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunSubmissionService.java#L203) FACT_REFRESH 重复 | `return reusedRun(...)` | INSERT MERGED → 然后 return |
| [L206](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunSubmissionService.java#L206) COMPENSATION 重复 | `return reusedRun(...)` | INSERT MERGED → 然后 return |
| [L211-L218](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunSubmissionService.java#L211-L218) mirror DEDUPED | `return DEDUPED` | INSERT MERGED → 然后 return（去掉 TABLE_REFRESH 专属的 `recordMergeEvent` 特例分支） |
| [L223-L231](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunSubmissionService.java#L223-L231) 并入 FULL_SYNC | `return DEDUPED` | INSERT MERGED → 然后 return |

`recordAbsorbedSubmission` 实现：

```java
private SyncRun recordAbsorbedSubmission(
    GitlabSyncConfig config,
    String sourceInstance,
    String exclusiveScope,
    SyncRunType requestedRunType,
    SyncTriggerType triggerType,
    String reason,
    List<String> sourceTables,
    String primaryTableName,
    Map<String, Object> extraPayload,
    SyncRun absorbedInto,
    SyncSubmissionAction action,
    LocalDateTime now) {
  SyncRun row = new SyncRun();
  row.setRunId(generateRunId(requestedRunType, sourceInstance));
  row.setConfigId(config.getId());
  row.setSourceInstance(sourceInstance);
  row.setRunType(requestedRunType);
  row.setTriggerType(triggerType);
  row.setStatus(SyncRunStatus.MERGED);
  row.setPriority(policyService.priorityOf(requestedRunType));
  row.setExclusiveScope(exclusiveScope);
  row.setParentRunId(absorbedInto.getId());          // <-- 关键：指向真正执行的那条
  row.setCancelRequested(false);
  row.setRequestReason(reason);
  row.setPayloadJson(buildPayloadJson(
      policyService.toApiType(requestedRunType),
      triggerType, reason, sourceTables, primaryTableName,
      absorbedInto.getId(), null,
      mergeExtra(extraPayload, "absorbedAction", action.name())));
  row.setPlannedTableCount(sourceTables.size());
  row.setCompletedTableCount(0);
  row.setScannedRows(0L);
  row.setAppliedRows(0L);
  row.setCreatedAt(now);
  row.setUpdatedAt(now);
  row.setFinishedAt(now);                            // <-- terminal，不会进调度
  row.setErrorMessage(null);
  syncRunMapper.insert(row);
  return row;
}
```

**2. 删掉 `recordMergeEvent` 的 TABLE_REFRESH 专属事件路径（[L378-L408](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunSubmissionService.java#L378-L408)）**

它是当年补救可见性的旁路——既然现在被合并的请求自己有一行 `sync_run`，event 表不再承担可见性责任。保留 `sync_run_events` 给真正的运行时事件（任务进度/失败明细）使用。

**3. `SyncRunLogService` 不动**。

它已经按 `created_at desc` 取 sync_runs；新行天然出现。需要确保 `toLogRow` 把 `parent_run_id` 也写入返回 map 供前端展示：

```java
row.put("parentRunId", run.getParentRunId());
row.put("absorbedAction", parsedActionFromPayload(run.getPayloadJson()));   // 可选
```

**4. 调度器/工作进程过滤**

确认 `SyncRunDispatcherService.claimNextQueuedRun` 的 `WHERE status = 'QUEUED'`——MERGED 行天然不会被拣到。`SyncRunStateMachine.isTerminal(MERGED) = true` 已经成立 ([L21-L28](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunStateMachine.java#L21-L28))，无需改动。

### 前端改动（一处）

**5. `MirrorSyncLogTable.vue` 展开行 + `mirror-settings-helpers`**

- `logStatusText(status: 'MERGED' / API status PENDING + run.status === 'MERGED')` → 显示 "已合并"
- `logStatusType` → 给 MERGED 一个独立 `info` 灰色标签，与 PENDING（待执行）区分
- 展开行新增一项："已并入 → sr_xxx"（取 `parentRunId`），点击可定位到主行（前端筛选 `runId === parentRunId` 即可）
- `typeOptions` 已经按 `runType` 聚合，自动包含 MERGED 行的类型，无需改

需要扩展 [SyncRunLog 类型](../frontend/src/types/api.ts) 加 `parentRunId?: string`。

### 不变更的内容

- `SyncRunStatus` 枚举不加新值（继续用 `MERGED`，语义自然延伸：所有"未独立执行、被吞入另一条"都叫 MERGED）。
- `SyncStatus` API 枚举不加新值（继续 `PENDING`，前端按 `runStatus === 'MERGED'` 单独展示标签）。
- 不增加表，不动 schema。
- 不动 `submitRun` 上层调用方（`GitlabCompensationScheduler`、`GitlabSystemHookAsyncDispatchService`、`GitlabSyncCommandFacade` 等）。

### 数据迁移

不需要。历史的"看不见的请求"无法追溯。新行从部署后第一次提交开始累计。

### 监控与回滚

- 上线后跑 §修订 §验证 SQL 中的 A/B：A 应能直接看到 `status=MERGED, parent_run_id IS NOT NULL` 的 TABLE_REFRESH 行；B 中 `TABLE_REFRESH_MERGED` 事件数应趋于 0（因为不再写）。
- 如果 sync_runs 写入量飙得太高（高频 dedup 场景），可在 `recordAbsorbedSubmission` 内做"同 source_instance + 同 requested_run_type + 同 parent_run_id 在 N 秒内只插一行"的轻量去重；当前不预先做。
- 回滚：把 5 处 early-return 恢复成原版即可，旧的 MERGED 行变成"出现过、不再产生"——无副作用。

### 验证清单（终稿）

- [ ] 重现用户截图场景：进议题查询页 → 镜像数据已是最新 → "最近同步日志"中**必有一行** `runType=TABLE_REFRESH, status=MERGED, parentRunId=sr_inc_xxx`，展开能看到"已并入 sr_inc_xxx"。
- [ ] 连点 10 次"刷新最新数据"，日志里出现 1 条真正执行的行 + 9 条 MERGED 行；类型筛选选 "刷新最新数据" 时 10 行齐全。
- [ ] FULL_SYNC 启动时被合并的 QUEUED 行（已经存在的 `MERGED` 路径）和新增的早退 `MERGED` 行 UI 样式一致，都标"已合并"。
- [ ] `sync_run_events` 中不再产生 `TABLE_REFRESH_MERGED` 事件类型。
- [ ] 调度器没有拣到任何 status=MERGED 的行（用 `select count(*) from sync_runs where status='MERGED' and started_at is not null` 验证；应为 0）。

## 2026-06-04 补充：增量同步（INCREMENTAL_SYNC）也在同一个黑洞里

### 用户问题

> "我们现在还有增量同步吗？尤其是之前的手动增量同步耗时只有 1 秒左右，但是现在看不到了。"

**结论先行**：增量同步链路完全健在，**1 秒跑完属于正常**（绝大多数表无新行，worker 空转一轮就收尾）；"看不见"与 §修订/§终稿 中分析的 TABLE_REFRESH 黑洞**根因完全相同**——都是 `submitRun` 早退分支跳过了 `syncRunMapper.insert(run)`。

### 链路证据（按调用顺序，全部健在）

| 层 | 文件:行 | 证据 |
|---|---|---|
| 前端按钮 | [MirrorSettingsView.vue:1010](../frontend/src/views/MirrorSettingsView.vue#L1010) | "刷新最新数据" → `startIncrementalSync` |
| API 路由 | [GitlabSyncController.java:162-172](../backend/src/main/java/com/data/collection/platform/controller/GitlabSyncController.java#L162) | `POST /incremental-sync/by-config` |
| Facade | [GitlabSyncCommandFacade.java:66-74](../backend/src/main/java/com/data/collection/platform/controller/GitlabSyncCommandFacade.java#L66) | `submissionService.submitIncrementalSync(config, null, "手动增量同步")` |
| Submit | [SyncRunSubmissionService.java:64-75](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunSubmissionService.java#L64) | 走 `submitRun(... SyncRunType.INCREMENTAL_SYNC ...)` |
| Worker | [SyncRunWorkerService.java:166](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunWorkerService.java#L166) | mirror run 完成事件继续 publish |
| 表规划 | [SyncRunTablePlanningService.java:112](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunTablePlanningService.java#L112) | `FULL_SYNC || INCREMENTAL_SYNC` → 计划全部表 |
| Policy | [SyncRunPolicyService.java:23/36/51/64](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunPolicyService.java#L23) | API 类型/优先级/Scope 全保留 |

### 为什么"看不见"对增量更严重

`shouldReuseMirrorRun` 在 [L351-L355](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunSubmissionService.java#L351) 写得**比 TABLE_REFRESH 更激进**：

```java
if (requestedType == SyncRunType.INCREMENTAL_SYNC
    || requestedType == SyncRunType.SYSTEM_HOOK
    || requestedType == SyncRunType.FULL_COMPENSATION_SCAN) {
  return true;            // 不比对 sourceTables，直接吞
}
```

只要当前存在任意 active mirror run（FULL/INC/SYSTEM_HOOK/TABLE_REFRESH/COMPENSATION_SCAN/FULL_COMPENSATION_SCAN），新提交的 INCREMENTAL_SYNC **直接 DEDUPED**，并且：

- 不调用 `recordMergeEvent`（[L208](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunSubmissionService.java#L208) 的旁路只对 TABLE_REFRESH 触发）
- `sync_runs` 不写、`sync_run_events` 也不写——**纯黑洞**

加上 `GitlabCompensationScheduler` 60s 调度（[L52-L77](../backend/src/main/java/com/data/collection/platform/service/GitlabCompensationScheduler.java#L52)）和 worker dispatcher 2s 调度的差速，手动点击时撞上 RUNNING 状态的 `COMPENSATION_SCAN` 概率不低，撞上即 DEDUPED 即消失。

另一条次要路径：FULL_SYNC 提交时 `mergeQueuedLowerPriorityMirrorRuns`（[L297-L326](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunSubmissionService.java#L297)）把 QUEUED 状态的 INCREMENTAL_SYNC 行批量改成 `MERGED`。这种行 `sync_runs` 里其实存在，但 [SyncRunStateMachine.java:69](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunStateMachine.java#L69) 把 `MERGED → SyncStatus.PENDING`，前端默认"最近 10 条"截断后，看起来就像"那次点击根本没发生"。

### 修复方案

**沿用 §终稿 的 `recordAbsorbedSubmission` 即可，无需新设计**。`submitRun` 的 5 条 early-return 中，DEDUPED 路径（[L207-L218](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunSubmissionService.java#L207)）是 INCREMENTAL_SYNC 的主要黑洞入口；该路径插入一行 `status=MERGED, parent_run_id=activeRun.id, run_type=INCREMENTAL_SYNC` 后立即返回，`recentLogs` 自然能看到。

**唯一新增点**：前端"已合并"标签的 `runType` 文本要正确显示为"刷新最新数据"——`mirror-sync-status-labels.ts` 中 `INCREMENTAL_SYNC: '刷新最新数据'` 已存在（[L15](../frontend/src/views/mirror-sync-status-labels.ts#L15)），无需改动。`SyncRunLogService.toLogRow` 在 §终稿 中追加的 `parentRunId` 字段对增量同样适用——展开行就能看到"已并入 → sr_full_xxx"或"已并入 → sr_cmp_xxx"。

### 增量同步专属验证项（追加到 §终稿 验证清单）

- [ ] 在没有任何 active run 的窗口手动点"刷新最新数据"：日志出现 1 行 `runType=INCREMENTAL_SYNC, status=SUCCESS`，耗时约 1 秒（即"原本就 1 秒"的体验回归）。
- [ ] 在 `COMPENSATION_SCAN` RUNNING 期间手动点"刷新最新数据"：日志出现 1 行 `runType=INCREMENTAL_SYNC, status=MERGED, parentRunId=sr_cmp_xxx`，展开看到"已并入"。
- [ ] FULL_SYNC 启动时若有 QUEUED 的增量行被批量改 MERGED：UI 显示"已合并"灰标（与早退新增的 MERGED 行视觉一致）。
- [ ] 调度器（`SyncRunDispatcherService.claimNextQueuedRun`）不会拣到 `INCREMENTAL_SYNC + MERGED` 的行（`MERGED` 已在 `TERMINAL_STATUSES`）。

### 与 §终稿 的关系

**不是新方案，是同一方案的覆盖范围说明**。`recordAbsorbedSubmission` 一旦落地，TABLE_REFRESH 黑洞与 INCREMENTAL_SYNC 黑洞**同时被堵**——根因就是同一个 `submitRun` 早退集合。System Hook 路径维持 §终稿 中的"放弃方向，不动"立场。

## 2026-06-04 补充 II：443 分钟同步与"PC 关机/睡眠"现象 —— 部署纪律 + 代码加固

### 用户问题

> "PC 关机导致耗时为 400+ 分钟、计划表项与完成表项数量异常庞大。我们平台部署在 Ubuntu 服务器上，正常情况只要服务器不关闭，所有同步策略都不应受任何客户端 PC 影响。过去打开平台的电脑睡眠、关机都影响到了同步（443 分钟），这是不应该的。所有程序都应该跟服务器挂钩，而不是打开平台的 PC。"

### 一句话结论

**唯一会让 PC 关机/睡眠影响同步耗时的可能性是：那次 443 分钟的运行，后端 JVM 实际上是在那台 PC 上跑的（开发机/演示机），而不是在 Ubuntu 服务器上**。代码层面所有同步动作的所有权都在 JVM 进程里——JVM 跑在哪里，同步就活在哪里；前端浏览器只读 API、不持有任何同步状态，关浏览器 ≠ 中断同步。

### 证据：所有同步执行线索都在后端 JVM 内

| 组件 | 文件:行 | 证据 |
|---|---|---|
| 调度入口 | [SyncRunDispatcherService.java:35](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunDispatcherService.java#L35) | `@Scheduled(fixedDelayString="${platform.gitlab-mirror.run-dispatcher-delay-ms:2000}")` —— Spring 定时任务，**寄生于 JVM 主进程**，进程停则停 |
| 工作线程池 | [SyncRunExecutorService.java:154-160](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunExecutorService.java#L154) | `ThreadPoolExecutor` 跑在 JVM 内，进程一停所有 worker 同时停 |
| 心跳续租 | [SyncRunExecutorService.java:116-124](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunExecutorService.java#L116) | `scheduleWithFixedDelay`，每 `leaseSeconds/3` ≈ 60s 写一次 `heartbeat_at + lease_until` |
| 超时回收 | [SyncRunLeaseService.java:31-51](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunLeaseService.java#L31) | `recoverTimedOutRuns()` 把 `lease_until < now` 的 RUNNING/RETRYING/CANCELLING 改成 TIMEOUT |
| 谁触发回收 | [GitlabMirrorSyncService.java:63-68](../backend/src/main/java/com/data/collection/platform/service/GitlabMirrorSyncService.java#L63) → [GitlabCompensationScheduler.java:52-57](../backend/src/main/java/com/data/collection/platform/service/GitlabCompensationScheduler.java#L52) | `recoverTimedOutTasks()` **只**被补偿调度器（60s 一次）调用，**也跑在 JVM 内** |
| Deadline 守护 | [SyncRunDeadlineGuard.java:90-93](../backend/src/main/java/com/data/collection/platform/service/sync/SyncRunDeadlineGuard.java#L90) | `max-run-duration-minutes=720`（12h）才 force cancel；同样寄生于 JVM 调度器 |
| 配置默认 | [application.yml:63-71](../backend/src/main/resources/application.yml#L63) | `scheduler-delay-ms:60000` / `run-dispatcher-delay-ms:2000` / `heartbeat-timeout-seconds:180` / `max-run-duration-minutes:720` |
| 启动方式 | [run-backend.ps1](../backend/run-backend.ps1) | `mvn spring-boot:run` —— **PowerShell 启动脚本**，强烈暗示该后端常被开发者直接在 Windows PC 上跑起来 |

**关键事实**：所有"自愈/超时/取消"全部由 **同一个 JVM 进程内的 `@Scheduled` 调度器** 触发。**当进程暂停（PC 睡眠）或关闭（PC 关机）时，没有任何外部守护把 RUNNING 的 sync_run 改成 TIMEOUT**——只有这个 JVM 自己能改自己。

### 443 分钟时间线复盘（最贴合证据的解释）

假设那次 443 分钟运行 JVM 在 PC 上：

| t | 事件 |
|---|---|
| t=0 | 用户在 PC 上点"刷新最新数据" → JVM 内 dispatcher 拣单 → `started_at = 09:00:00` |
| t=2min | 同步几乎完成，应在 t=4min 收尾 → 但用户合上电脑/PC 进入睡眠 |
| sleep | JVM 被 OS 冻结：worker 线程卡在 `socket read` / `Thread.sleep`；heartbeat 调度器停摆；recoverTimedOut 调度器停摆 |
| t=440min | 用户次日唤醒 PC → JVM 进程恢复 → `Scheduled.fixedDelay` 立刻补跑一次 dispatcher / 一次 recoverTimedOut |
| t=441min | `recoverTimedOutRuns` 看到 `lease_until = 09:03 + 180s < now`，把这一行改 `status=TIMEOUT, finished_at=current_timestamp`（**注意：`started_at` 不变**） |
| t=443min | UI 拉日志 → `finished_at - started_at = 443min` → 屏幕显示"耗时 443 分钟" |

**所以 443 分钟不是真实工作时长，是"开始时刻 → JVM 唤醒后才被发现并标记终态的墙钟差"。"计划表项/完成表项异常庞大"则是因为**：

- 全量/增量计划表项在 `started_at` 那一刻已写入 `planned_table_count`；
- 睡眠期间 worker 没动 → 表任务卡在 `RUNNING` 状态、`completed_table_count` 停留在 sleep 前的瞬时值；
- 唤醒补跑 `recoverTimedOutTasks` 时把仍在 RUNNING 的 task 也改 TIMEOUT，但 `completed_table_count` 不会再追加 → UI 看到"计划 N，完成 K（K << N），但耗时 443 分钟"——视觉上极不合理，逻辑上自洽。

### 为什么"服务器不挂、平台同步绝不应受 PC 影响"

- 浏览器只是 [MirrorSettingsView.vue:1010](../frontend/src/views/MirrorSettingsView.vue#L1010) 上的 HTTP 调用方；POST 一次 `/incremental-sync/by-config` 就走完使命。
- 所有运行权（dispatch / execute / heartbeat / recover / deadline）都在后端 JVM。
- **唯一让 PC 影响同步的物理通路就是"后端 JVM 跑在 PC 上"**。这是部署事故，不是代码缺陷——但代码可以加防御。

### 修复方案

#### A 部署纪律（**根本修复，第一优先级**）

1. **JVM 必须运行在 Ubuntu 服务器**，使用 systemd 托管：
   ```ini
   # /etc/systemd/system/qa-flex-platform.service
   [Unit]
   Description=QA Flex Platform Backend
   After=network-online.target postgresql.service

   [Service]
   Type=simple
   User=qaflex
   WorkingDirectory=/opt/qa-flex-platform/backend
   Environment="DATASOURCE_URL=jdbc:postgresql://<db-host>:5432/qaflex"
   ExecStart=/usr/bin/java -jar /opt/qa-flex-platform/backend/qa-flex-platform.jar
   Restart=always
   RestartSec=10
   StandardOutput=append:/var/log/qa-flex/backend.log
   StandardError=append:/var/log/qa-flex/backend.log

   [Install]
   WantedBy=multi-user.target
   ```
2. 任何 PC 上的 `mvn spring-boot:run` / `run-backend.ps1` 仅限**开发调试**；生产环境的镜像同步配置必须指向服务器上的 JVM。
3. 验收手段（部署侧自检）：服务器 `systemctl status qa-flex-platform.service` 必须 `active (running)`；前端 `MirrorSettingsView` 的"全量补偿对账"按钮触发后立刻关闭浏览器与 PC，第二天直接看 `sync_runs` 表，那一行 `status` 必然在 SUCCESS / TIMEOUT / FAILED 终态——若仍 RUNNING，说明部署错位。
4. **建议把 [run-backend.ps1](../backend/run-backend.ps1) 顶部加注释**：`# 仅用于本地开发；生产部署见 ops/systemd/qa-flex-platform.service`，避免新人误把这个脚本当生产入口。

#### B 代码加固（当前不做）

这组"进程曾停摆 / 含暂停"标注方案已取消。现在 JVM 已部署到 Ubuntu 服务器并由 systemd 托管，原 443 分钟问题的根因应按 **A 部署纪律** 收敛：生产同步只归服务器 JVM 所有，客户端 PC 关机不再参与同步生命周期。

因此本次修复不新增 `wallClockSuspect` 字段，不在前端耗时列追加"含暂停"，也不写入"进程曾长时间停摆"这类面向 PC 睡眠场景的文案。服务器自身重启、OOM、进程被 kill 等运维事件继续由现有 lease / deadline / systemd 重启链路处理；后续如要增强启动恢复，应作为独立运维可靠性任务设计，不混入本次日志可见性修复。

#### C 与 §终稿 / §补充 的协同

- §终稿 的 `recordAbsorbedSubmission` 解决"被吞的提交看不见"。
- §补充 II 当前只保留部署纪律结论：JVM 在 Ubuntu/systemd 下常驻，PC 不再影响同步生命周期。
- 因此本轮代码只落地日志可见性：**有过的请求都在、MERGED 状态正确可辨、来源页面可追踪**。

### 验证清单（§补充 II 专属）

- [ ] **部署侧**：`ps -ef | grep qa-flex` 只在 Ubuntu 服务器返回 1 个 PID；任何 Windows PC 的 `tasklist | findstr java.exe` 不应出现该项目的 jar/进程（除开发模式）。
- [ ] **本地脚本边界**：[run-backend.ps1](../backend/run-backend.ps1) 顶部明确标注仅用于本地开发，生产入口必须是 Ubuntu systemd 服务。
- [ ] **PC 关机验收**：服务器 JVM 运行时，从浏览器触发同步后关闭浏览器与 PC；第二天检查 `sync_runs`，该 run 应由服务器进程推进到 SUCCESS / TIMEOUT / FAILED / PARTIAL_SUCCESS 等终态。

### 不在范围

- 不引入跨进程心跳（如 PostgreSQL `pg_stat_activity` 主动巡检）：当前 lease 表达力够用，新增链路只会增加复杂度。
- 不引入"用户客户端 keepalive"机制：那会反向把同步绑到浏览器，违背本节根本结论。
- System Hook 与 §终稿 同步保持"放弃方向，不动"。
