# 事实构建分批发布与租约治理工作单元（2026-09-07）

## 进度与中间物

状态：**实施与验证全部完成，待用户确认提交。**

- 目标：根治内网 30001 暴露的「全量事实构建无进度 + 假超时反复重试 + 互锁回滚」问题（根因链见 `docs/plans/intranet-test-issues-resolution-20260904.md` 问题 3 与其第 7 条时间线）。
- 用户指令（2026-09-07）：只修 fact build 这一个问题，其他待批项（D-2/D-5/D-6）不动；直接改出目标版本，不留兼容屎山；不得影响其他表格与功能。
- 最终验证（全部通过）：
  - 全量默认套件 1247 项，唯一失败 = 同事 untracked WIP `ReviewDataRecordReadSupportTest`（预期）。
  - Checkstyle 0 违规、SpotBugs 0 问题、仓库四项门禁全绿（顺带清理 HEAD 既存 3 个无用 import）。
  - 黄金基线：更新模式 180/180 → git diff 语义树终审（60 文件差异 = 掩码噪声 + `Map.of` 属性序文本噪声 + 唯一实质差异 status 日志文案）→ 比对模式复跑 180/180 全绿。golden 门禁拦截并锁定了分片互删清理缺陷（详见下方事件记录），修复后由 1200 行跨分片真实库回归测试永久锁定。
- 提交范围（待用户确认）：主代码 13 文件改/建、测试 9 文件改/建、golden 快照 60 文件重建、文档 4 文件（architecture/decisions/progress/计划）。
- 实施过程关键事件（已闭环，留档防重蹈）：
  1. runGuarded 事务包装移除 → 增量分支隐式原子性裸露 → 构建层补显式单事务（ISSUE：batchUpsertIssueFacts+搜索列+目录对账；MR：replaceRootFacts+提交关系+搜索列），D-10「边界」精确化；两个直测旧包装的测试按新契约重写/删除。
  2. **golden 门禁拦截严重回归**：更新模式实测 `issue_fact`=0（预期 ≥1200）而 FACT_REFRESH 运行与任务均 SUCCESS、无任何 WARN。经运行中 DB 探针（`qaflex_test` schema，探针直查 public 会假象空库）抓到 `TASK|ISSUE|full=true|SUCCESS|rows=1200` + `FACT_BUILD_PROGRESS 1/1（1200 行）` 后 `FACTS|0` 的证据链，锁定根因：`deleteFactsNotInSnapshot` 按 1000 行身份分片循环执行「删除不在本片身份集的行」，快照 >1000 行时各片互删对方身份的行（1200/1500 行快照整体清空；≤1000 行即全部既有单测不可见）。修复 = 会话临时表（`on commit drop`）+ 单条 `NOT EXISTS`（`IS NOT DISTINCT FROM` 空值安全）全集反连接，内网 4.6 万行规模安全。
  3. 新增真实库回归 `IssueFactSnapshotCleanupIntegrationTest`/`MergeRequestFactSnapshotCleanupIntegrationTest`（各 1200 行快照+快照外行）；harness 教训：`PostgresIntegrationTestDatabase.dataSource()` 每次返回新实例，事务管理器与 JdbcTemplate 必须共享同一实例，否则事务绑定失效、临时表跨连接不可见。
  4. 快照 diff 语义终审方法论：JSONUnit 比对对对象属性序不敏感，`Map.of`（JDK SALT 每次 JVM 随机，实测 10 次 4 种排列）只造成更新模式文本噪声，不构成比对风险；掩码易变字段（时间戳/UUID/端口/retentionHours/queryDurationMs）更新模式照写、比对忽略。

## 恢复线索

- 当前阶段：验证收尾。恢复后首条命令 = `git status` 确认工作树（同事 untracked `ReviewDataRecordReadSupportTest.java` 不属于本单元，其失败可忽略）。
- 计划文档：本文档；根因与历史：`docs/plans/intranet-test-issues-resolution-20260904.md`。
- 运行入口：`backend/` 下 `../tools/maven/apache-maven-3.9.9/bin/mvn.cmd test`（默认快速套件）；golden 更新模式须显式 `-Pgolden-baseline -Dtest=GoldenBaselineChainTest -Dgolden.update=true`（需 Docker）。

## 目标与边界

**用户原始需求**：修复 fact build 无进度/反复重试；优雅成熟完善，不要屎山，不要兼容层；不影响其他表格与功能。

**成功标准**：
1. 全量事实构建（ISSUE/MR/集成测试）不再有分钟级单一大事务：分批事务提交，批间推进可观测。
2. 运行中的事实任务租约按批续期，不再被假超时判死重试。
3. run 心跳不再因单线程调度器饿死而假超时。
4. 重试/超时/进度写入 `sync_run_events`（UI 运行日志可见）。
5. 全量构建最终状态与旧 `replaceAllFacts` 严格等价（黄金基线背书）。
6. 定向（增量）构建路径行为不变（`replaceRootFacts` 语义原样保留）。

**明确禁止**：
- 不引入新旧双轨/兼容分支——`replaceAllFacts` 被分批发布直接替代并删除。
- 不动黄金基线夹具/目录分类/掩码；快照只允许按「有意变更」纪律以更新模式重建（预期差异仅 `/api/gitlab-sync/status` 的 `logs[*].message`）。
- 不动定向路径、评审数据、标签组、BI 等无关模块。

## 约束与背景

- 事务原语：`FactPublicationTransaction.execute(Supplier)`（REQUIRED 传播的通用事务包装）是既有惯用法；内层 `@Transactional` 方法在外层事务内会合并。
- `IssueFactMapper.batchUpsert` 不含 search_* 列 → 搜索列刷新（`FactSearchIndexRepairService.refresh*InBatches`，对事实行本表 UPDATE）必须与该批 upsert 同事务。
- `sync_run_events.run_id` 有 FK → 手工构建任务（run_id=UUID）不能记事件，仅记应用日志；队列任务（MIRROR_SYNC）的 `factRunId` 必为真实运行。
- 手工任务（trigger_type=MANUAL）不参与租约/回收体系（`recoverTimedOutQueuedTasks` 仅处理 MIRROR_SYNC），靠全局 advisory lock 互斥 → 手工路径用 NO_OP 进度回调即可。
- `heartbeatTimeoutSeconds=180`（application.yml 硬编码）同时决定任务租约与 run 心跳周期，本单元不改其值（分批+续租后无需调大）。
- 分批大小新增配置 `fact-full-build-chunk-size`（默认 2000），既定模式：application.yml 用环境占位符。

## 证据与根因（实施依据）

- 旧 `replaceAllFacts`（issue：删成员→删全部事实→重插；MR：删 MR 事实+提交事实→重插）是单事务全量替换；`publishFull`（@Transactional）再包 FULL_EPOCH+owner 围栏终态+发布状态结算 → 17 分钟无提交窗口。
- 任务租约认领时一次性设置，执行中零续租；run 心跳 = 全运行共享单线程调度器 → 假超时级联（详见 intranet-test-issues-resolution 问题 3）。
- 定向路径（`publish`+`replaceRootFacts`）每任务一小事务（实测 0.4–0.9 分钟），无需改造。
- 等价性论证：空库首建时「upsert 快照+反连接清理」与「删全+重插」结果逐行一致（黄金基线环境即空库首建）；存量库上分批方案保留既有行 id（优于旧方案重建 id）。
- 黄金基线影响：`/api/facts/rebuild` 为 EXCLUDED；`refresh-one` 锁行级最终状态（定向路径未动）→ 无影响；唯一预期差异 = `/api/gitlab-sync/status` 的 `logs[*].message`（fact 运行最新事件从 fallback 变为确定性完成事件文案）。

## 方案与步骤

### 设计（已定稿）

1. **分批全量构建**（FactBuildService，ISSUE/MR 两处 full 分支重写）：
   - 读取阶段不变（整表读 ODS → 内存事实列表，与现状一致）。
   - 批次循环：每批（`fact-full-build-chunk-size`，默认 2000 行）在 `FactPublicationTransaction.execute` 内「upsert 事实（+客户成员/提交关系）+ 搜索列刷新」原子提交；批提交后回调进度。
   - 收尾反连接清理（新方法，单事务）：删除快照之外的同源事实与成员/提交关系（替代旧「删全部」语义）。ISSUE 按 (project_id, issue_id)、MR 按 (project_id, merge_request_id) + 提交事实按 (project_id, merge_request_id, commit_sha)，VALUES 分片反连接。
   - 里程碑目录对账（reconcile）保持在构建后调用（其自身 @Transactional，全量路径下为独立短事务）。
2. **publishFull 重构**（FactTargetPublicationService）：去 @Transactional，变编排者——调构建动作（带进度回调）→ 短结算事务（FULL_EPOCH 推进 + finishOwnedTask + settleAfterFullPublication）→ 完成事件。进度回调 = 任务租约续期（失败即抛「租约已失效」中止，防做无用功）+ sync_run_events 进度事件。
3. **任务租约续期**（FactBuildTaskService 新增 `renewTaskLease`）：`update ... where id=? and status='RUNNING' and lock_owner=?`，owner 围栏与 finishOwnedTask 同款。
4. **run 心跳扩容**（SyncRunExecutorService）：`newSingleThreadScheduledExecutor` → `newScheduledThreadPool(max(2, maxSyncThreads))`，同前缀线程工厂。
5. **可观测性**（新 `SyncRunEventRecorder`）：统一写 `sync_run_events`；进度/完成（构建层）+ 重试（worker 失败处）+ 租约超时重排/重试耗尽（`recoverTimedOutQueuedTasks` 改 RETURNING 逐任务记事件）。手工路径不记事件（FK）。
6. **runGuarded 去外层事务**：`publicationTransaction.execute(action)` → `action.get()`（构建内部自管事务）；手工路径靠全局 advisory lock 互斥不变。
7. **删除被替代实现**：`IssueFactPersistenceService.replaceAllFacts`、`MergeRequestFactPersistenceService.replaceAllFacts`；`upsertCommitFacts` 转公有。
8. **配置**：`GitlabMirrorProperties.factFullBuildChunkSize=2000` + application.yml `fact-full-build-chunk-size: ${GITLAB_FACT_FULL_BUILD_CHUNK_SIZE:2000}`。

### 实施步骤

1. 新建 `FactBuildProgress`（函数接口 + NO_OP）与 `SyncRunEventRecorder`。
2. FactBuildTaskService：renewTaskLease + recoverTimedOutQueuedTasks RETURNING 化与事件。
3. FactTargetPublicationService.publishFull 重构 + FullFactBuildAction。
4. FactBuildService：ISSUE/MR full 分支分批化 + 清理调用 + 进度穿参（含 rebuildAllFactsInternal/rebuild*ForQueuedTask 签名）；runGuarded 去外层事务；incremental 分支显式小事务。
5. 持久化服务：新增反连接清理，删 replaceAllFacts，upsertCommitFacts 公有化。
6. IntegrationTestFactBuildService：进度回调（单批）。
7. FactRefreshTaskWorkerService：接线 + 重试事件。
8. SyncRunExecutorService：心跳池扩容。
9. 测试：新增续租/清理等价/进度回调/结算回滚用例；更新受影响既有测试。
10. 验证与收尾：默认套件 → golden 更新模式重建 status 快照（人工审阅 diff）→ 比对复跑全绿 → decisions.md（新 D 条目：事实构建分批发布语义）/architecture.md/progress.md。

## 决策记录

- 已选：批间回调续租（无新增后台调度器）——批次事务短且确定，续租与数据推进同源；不为「批次卡死>180s」再建后台心跳（该场景下判死+幂等重做本就是正确行为）。
- 已选：清理用反连接删除而非 temp table——无状态、与池化连接无耦合。
- 已选：不迁移既有 4 处 ad-hoc sync_run_events 插入（取消/重跑服务）——超出本问题边界，避免无关回归面。
- 已否决：advisory-lock 防抢占机制（原 F4）——分批+续租后「原事务仍存活时重试被派发」仅剩 DB 长时不可用一种诱因，此时幂等重做可收敛，单独机制属过度设计。
- 已否决：fact_build_tasks 加进度列——进度经 sync_run_events 已 UI 可见，加列触发无谓的迁移与快照面扩大。
- 待定：无。

## 接口契约

- `FactBuildProgress.chunkCommitted(int completedChunks, int totalChunks, long processedRows)`：每批事务提交后回调；实现方可抛异常中止构建（如租约丢失）。
- `FactTargetPublicationService.FullFactBuildAction.build(FactBuildProgress)`：替代 `Supplier<FactBuildResponse>`。
- `FactBuildTaskService.renewTaskLease(QueuedFactBuildTask, int leaseSeconds)`：返回 false = 租约已易主。
- 事件类型：`FACT_BUILD_PROGRESS` / `FACT_BUILD_COMPLETED` / `FACT_BUILD_RETRY` / `FACT_BUILD_TIMEOUT_RETRY` / `FACT_BUILD_TIMEOUT_FAILED`；文案为确定性中文（黄金快照可锁定）。
- 无新增 API/表结构变更；`fact_build_tasks` schema 不变。

## 风险与假设

1. **等价性风险（最高优先）**：分批 upsert+清理 与 删全+重插 的最终状态必须逐行等价——由「黄金基线空库首建」+新增等价性单测双重背书；若 golden 出现 issue_fact/MR 事实差异，一律按回归处理停下排查，不得改快照。
2. 中断恢复语义变化：旧=构建中崩溃全回滚；新=已提交批次保留、重试幂等重做收敛。已论证等价且优于旧语义（可见进度+无 17 分钟空窗）。
3. 批间续租失败即中止：中止时已提交批次保留，任务由重试链收敛（幂等）。
4. golden 更新模式重建 status 快照属「有意变更」纪律动作，重建后必须 git diff 人工审阅，且仅允许 logs[*].message 差异。
5. 手工构建路径（runGuarded）去外层事务后，增量分支的原子性由显式小事务保持；全量分支分批提交属本单元目标语义。
6. 事件写入失败不阻塞构建（recorder 内捕获记 warn）——事件是可观测性通道，不是事实一致性的一部分。
