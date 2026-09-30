# 同步与读侧六类缺陷的最终修复设计（P1–P6 + 互斥域 + 前端等待）

## 进度与中间物

- 当前阶段：设计评审稿。已按用户要求对 2026-09-30 包上线后内网测试发现的四个问题完成代码级复核，并合并用户提供的《页面「刷新最新数据」等待永不结束的成因与同步模块近况》交接材料中的路径 A（镜像互斥域）与前端独立缺陷。全部证据已经仓库代码逐一核对，未发现与先前结论矛盾之处，但有若干收窄与补充（见「证据与根因」）。
- 追加范围：用户随后报出第五个问题（数据镜像设置页「最近同步日志」模块难以使用），已按同一标准完成代码取证，结论见 P5、方案 F8/F9、裁定项 DEC-E/DEC-F；以及第六个问题（页面显示无同步进程但事实层刷新实际在运行近一小时），取证见 P6、方案 F10、裁定项 DEC-G。二者均已确认在最新代码中存在。
- 已完成文件/变更清单：仅本计划文档。
- 测试通过/失败状态：未实施，无测试。
- 当前阻塞或进行点：等待用户对方案与裁定项的批复。

### 恢复线索

- 当前阶段：等待用户批复后开始实施。
- 恢复后建议执行的首条命令：`git status --short --branch`，然后按「方案与步骤」的顺序实施 F1。
- 相关计划：`docs/plans/fact-publication-authoritative-rework-20260930.md`（已完成的前一单元）。
- 对应 commit：`de7bff4b`（工作树含在途多单元改动，本单元实施时只改本方案列出的文件）。

## 目标与边界

- 用户原始需求：对内网测试发现的四个问题（P1 BI 需求指标空、P2 状态永挂「事实刷新中」、P3 全量补偿对账期间页面无数据、P4 手动事实层重建失败）给出确定的修复；参考业内成熟做法，给出最终设计与调优方案，不要止血式补丁。
- 可验证的成功标准：
  1. 取消一次 FACT_REFRESH 后，其遗留投影任务被自动收割执行，受影响工作区的「刷新最新数据」在无新数据的情况下也能收敛到「已展示最新事实数据」。
  2. 手动提交全量事实重建时若来源依赖尚未就绪，任务等待而不是失败；依赖收敛后自动完成。
  3. 全量补偿对账运行期间，系统测试与客户问题页持续显示上一份完整数据并带原因横幅，不出现空白与 400。
  4. MR 与 ISSUE 的全量重建清理删除不再触发语句超时；夜间 02:03 全量核验的 MR 构建不再固定失败。
  5. `issue` 全量事实重建成功后，BI 客户问题页「未知事实数」归零，需求指标与交付状态恢复。
  6. 后端全量快速套件、黄金基线 compare 门禁全绿。
  7. 数据镜像设置页「最近同步日志」模块有属于自己的纵向与横向滚动条，默认可见行数不少于 8 行，展开任意一行（含最后一行）都能看到完整详情；失败或待处理运行给出运行级结果与本次记录的错误原文（含对象、阶段、重试次数、失败时间、耗时），不依赖后端日志即可判断问题出在哪。
  8. 不再出现「页面显示没有同步进程、事实层却在长时间工作」：系统中任何事实层工作都对应可见的活动运行或可见的待处理记录；待处理由维护人员决定继续或取消；不再有不可见的后台执行。
  9. 页面刷新按钮在几秒内结束（未收敛时展示上一份完整快照并在状态栏说明），15 分钟仅作兜底上限；同一时刻不再出现整页遮罩导致页面不可用。
- 明确禁止的行为：不清空任何任务表、不手工标记任务或栅栏状态、不为旧实现保留双轨路径、不以「以后可能需要」为由新增开关或配置项、不推送远端（远端由用户指定）、未经用户确认不重建黄金基线快照。

## 约束与背景

- 本单元涉及的问题中，P2、P3、P4a、P4b、P5、P6 均为既有代码缺陷（已用 `git diff HEAD` 逐文件核实：对应文件与基线一致或语义未变），只有 P3 的覆盖范围属于上一单元 S4 的取舍遗留。修复必须直接改原实现与全部调用点，不留兼容层（AGENTS.md「开发期代码演进红线」）。
- P2 与 P6 是同一控制面缺口的两面：事实构建与投影任务的归属与 `sync_runs` 的活动状态脱钩。F2 与 F10 必须合并成一个不变量来验收——正在执行或等待执行的事实/投影任务，必然归属于一条活动运行。
- 黄金基线门禁：本单元改动读侧语义（F3）与统计输出，发布前必须运行 compare 模式门禁；如快照需要重建，必须先向用户展示差异并获确认。
- 性能改动必须带固定负载基准（F4 的清理删除重写），容量结论以内网实测为准。
- 取消语义界定（本设计的判据）：取消一次运行，停止的是写侧（事实构建与同步扫描）；投影预热是已发布事实的读侧派生，幂等，不属于被取消的写侧工作。该界定是 F2 的前提，列入决策记录交用户确认。

## 证据与根因

以下结论全部经仓库代码核对；行号以当前工作树为准。

### P1 BI 需求指标为空（根因：字段未回填，依赖 P4 修复后人工重建）

- `bi/application/BiCustomerIssuePageService.java:254-264`：`unknownRequirementIdentityCount() > 0` 时，`requirement-overview` 与 `module-requirements` 两个分区置为 `INCOMPLETE`，且数据本身被替换为未知占位（`bi/domain/BiCustomerIssueCalculator.java:62-80`：`requirementMetrics` 换成 `unknownRequirementMetrics()`、`moduleDemand` 返回空列表）。
- 未知数定义已核实为「未被排除且 `customerRequirement() == null` 的事实条数」（`BiCustomerIssueCalculator.java:51-55`）。
- `issue_fact.is_customer_requirement` 由 `V20260922_01` 新增，迁移注释明确「null 表示尚未重建；禁止默认 false 伪造历史分类」；唯一写入点是 `IssueFactSourceRowMapper.java:109`（事实构建时派生）。
- 增量刷新只重算发生变化的根，因此这 844 行不会被动收敛；唯一收敛手段是一次成功的 `issue` 全量事实重建。P1 是 P4 的下游后果，本单元不改任何计算逻辑。

### P2 状态永挂「事实刷新中」（根因：投影任务孤儿无任何认领路径）

- 领取：`FactProjectionTaskService.claimNext:52-58` 要求父运行 `status = 'RUNNING'` 且租约匹配。父运行终态（如 CANCELLED）后，其 `QUEUED`/`RETRY_WAITING` 任务无法被领取。唯一调用方是 `SyncFactRefreshRunExecutor.java:135`，即只有活动执行者能领取，**不存在独立的投影任务认领者**。
- 回收：`FactProjectionTaskService.recoverExpiredTasks:106-107` 的 CTE 只过滤 `task.status = 'RUNNING'`；每 5 秒触发的 `FactProjectionTaskRecoveryScheduler` 复用同一方法。其 Javadoc 写明「父运行已经终止或正在取消时，任务直接失败，避免留下无人再领取的活动记录」，但谓词漏掉了 QUEUED/RETRY_WAITING 两种状态。
- 栅栏：`SyncRunPublicationFenceService.synchronizeOutstandingScopes:268-322` 把「已发布根对应的、`status <> 'SUCCESS'` 的投影任务」收编为栅栏 scope 的应完成工作；`synchronizeScopeStatuses:324-365` 只在找到 `target_generation >= required_generation` 且 SUCCESS 的任务时才把 scope 置为 SUCCESS，否则一律 PENDING；`loadScopeSummary` 计入 `pending`。
- 状态：`RealtimeWorkspaceRefreshProgressService` 的 `loadFenceSummary` 在 `pending > 0` 时返回 `RUNNING`；`RealtimeWorkspaceService.progressState:254-272` 据此恒返回「事实刷新中」；前端等待循环无上限（见「前端独立缺陷」）。
- 自愈机制为何只救回大部分：后来有新变更的项目会生成更高 generation 的新投影任务并 SUCCESS，`synchronizeScopeStatuses` 按「SUCCESS 优先」选中它；无新变更的 scope（本次为 PROJECT 164/189/209）所需 generation 恰好等于孤儿任务，永久卡住。
- 归因：与基线逐文件一致（`git diff HEAD` 为空或语义未变），属既有缺陷；上一单元只把栅栏的数据源从目标表换成版本头，收编语义未变。

### P3 全量补偿对账期间页面无数据（根因：拒绝先于快照查询，且补偿运行期间门控必然命中）

- `StatisticBoardSnapshotService.requireReadableSources:214-228`：`if (!qualification.readable()) throw new BizException(...)`，位于 `withinConsistentSourceRead` 内、早于一切快照查询；只有 `degraded`（「仍有 N 个目标未发布」）才走 `findLatestReady` 返回上一完整发布点。
- `SyncFactPublicationStateService.qualification:119-165` 共五条拒绝路径，补偿对账期间命中两条：
  - `labelEventHistoryVerified:312-328`（仅 ISSUE）：要求 `sync_run_table_states.resource_label_events` 满足 `dirty_flag = false and last_full_verified_at is not null`。`SyncRunTablePageCommitService` 在 FULL_RECONCILE 扫描的**每一页提交时**即置 `dirty_flag = true`（:197-201），只有整表扫完才置 `last_full_verified_at`（:150-157）。因此门控在整个补偿运行期间必然命中，而不是只在开始时。
  - `readiness_status <> 'READY'`（:141-143）：取消对账后遗留待修复表时命中。
- 上一单元 S4 只把「仍有 N 个目标未发布」改为降级，其余按 DEC-1 保持硬拒绝。补偿对账是平台自身的可预期维护操作，期间整组页面空白属于行为回归（0918 版无门控）。

### P4a ISSUE 手动重建被门控拒绝（根因：等待被误判为失败，且影响全部任务而非仅手动重建）

- `FactRefreshTaskWorkerService.execute:68-70`：领取任务后先查 `isReady`，不通过即 `throw new IllegalStateException("事实来源依赖代际尚未就绪")`，落入同一个 `catch (Exception)` → `failOwnedTask`。
- 退避与预算：`FactBuildTaskService.failOwnedTask:502-507` 的退避为 `power(2, retry_count)` 秒（1 秒、2 秒），`max_retry_count` 默认 3（迁移 `V20260509_02` 等）。整个预算约 3 秒耗尽，而现场依赖未就绪窗口约 4 分钟。
- 影响面收窄确认：`SyncFactRefreshRunExecutor.execute:123-127` 与兜底 worker 共用同一个 `FactRefreshTaskWorkerService.execute`，因此该缺陷影响**所有**事实构建任务，不限于手动重建。
- 该 worker 与基线逐字一致，属既有缺陷。

### P4b MR 全量重建清理 DELETE 超时（根因：单条全表反连接删除维护全部索引）

- `MergeRequestFactPersistenceService.deleteFactsNotInSnapshot:134-211`：`delete ... where not exists (select 1 from merge_request_snapshot_ids s where s.project_id is not distinct from f.project_id and s.merge_request_id is not distinct from f.merge_request_id)`。`is not distinct from` 不是普通等值，规划器难以走索引；`merge_request_fact` 有大量索引（`V20260506_01` 单个迁移即 17 条 `create index`，含 5 个 `gin_trgm_ops`，内网实测 28 个索引、12 个 trgm），删除每行都要维护全部索引。
- 超时来源：`application.yml:31-33` `spring.jdbc.template.query-timeout`（Spring `Statement.setQueryTimeout`）。现场日志措辞 "canceling statement due to user request" 正是 JDBC 取消路径（PG 的 `statement_timeout` 措辞为 "due to statement timeout"）。
- 已核：清理是全量重建最后一步，分块 upsert 每块独立提交（`FactBuildService.java:730-741`），失败只回滚清理，原数据完好；同一方法也是夜间 02:03 全量核验 MR 构建固定失败的路径。
- 遗漏补报：`IssueFactPersistenceService.deleteFactsNotInSnapshot:100-145` 与 `merge_request_commit_fact` 的清理是同一形态（`is not distinct from` 反连接），ISSUE 全量重建成功后同样会超时。本设计统一修复。
- 谓词可改的依据已核实：`issue_fact.project_id`、`issue_fact.issue_id`（`V20260506_01:62-80`）与 `merge_request_fact.project_id`、`merge_request_fact.merge_request_id`（`:153-170`）均为 `bigint not null`，`is not distinct from` 可安全改为普通等值。

### 路径 A 镜像互斥域（交接材料新增，本设计按前提硬化）

- 已核实：`SyncRunDispatcherService.claimNextQueuedRun` 的互斥判断对同域 `RUNNING`/`CANCELLING` 行不检查租约有效性（仅对 RETRYING 检查）；`SyncRunLeaseService.recoverTimedOutRuns` 要求 `lease_until is not null`。
- 收窄：`deferOwnedRun` 置空租约的行是 PAUSED/RETRYING（不阻塞互斥、可被重新领取）；`SyncRunDeadlineGuard` 与手动取消改判 CANCELLING 时保留原租约（过期后会被 `recoverTimedOutRuns` 回收）。因此「永久占住」需要的精确形态是「RUNNING/CANCELLING 且 lease_until 为 null」或「执行者存活但永不推进」，其在现场是否已发生属待验证（按交接材料 6.4 判读表）。但调度器互斥对 RUNNING/CANCELLING 不查租约这一点属实，按租约感知修复在任何情况下都是安全的硬化。

### 前端独立缺陷（交接材料已取证，复核一致）

- `useRealtimeWorkspaceStatus.ts:26-51`：等待循环无超时、无次数上限、无取消入口；`useStatisticBoardRefreshController.ts:17-32` 等待期间 `loading = true`；`StatisticBoardView.vue:964` 整页 `v-loading` 遮罩，页面不可操作。
- `RealtimeWorkspaceService.java:284-287` 的 15 秒冷却：冷却期内重复点击不会真正发起新刷新，却仍提示成功；等待期间重复点击会叠加多个等待。

### P5 最近同步日志模块不可用（根因：容器裁剪行高 + 自绘滚动条脱离模块；失败原因只有一句概括）

#### P5a 布局：垂直方向被硬裁剪，水平方向没有属于模块的滚动条

- 容器高度被写死并禁止滚动：`styles.css:1765-1767` `.sync-log-table-shell { max-height: 240px }` 与组件内 `MirrorSyncLogTable.vue:301-307` 的 `overflow-x: hidden; overflow-y: hidden` 同时生效；`el-table` 未设置 `height`/`max-height`（`MirrorSyncLogTable.vue:161-169`），其表体按内容全高渲染后被 240px 容器裁掉。扣掉表头后正好只剩约 5 行，这正是「一次性只能完整 5 条」的来源；第 6 条起在鼠标下不可达（容器的滚轮只在 `shift` 按下时被处理，`useFloatingHorizontalScrollbar.ts:49-60`，普通滚轮只会滚动页面）。
- 水平方向的滚动条被强制隐藏且不属于模块：`MirrorSyncLogTable.vue:321-323` 用 `display: none !important` 关掉 Element Plus 自带横向条，改由自绘浮动条承担；该浮动条被 `Teleport` 到 `document.body` 并以 `position: fixed` 固定在**视口底部**（`MirrorSyncLogTable.vue:263-285`、`styles.css:2924-2936`），轨道透明、滑块常态不透明度 0.45（`:2958-2967`）。它不跟随模块，视觉上几乎不可见，用户因此判定「它自己没有左右和上下滚动条」。
- 展开行同样被裁剪：展开详情渲染在同一个 240px 裁剪容器内，`handleExpandChange` 只做 `doLayout` 与滚动条重算（`MirrorSyncLogTable.vue:109-113`），无法让容器滚动，所以底部几行展开后看不到完整内容。
- 与同页既有做法不一致：同一页面内的 `MirrorRunQueueTable.vue:48`（`max-height="220"`）与 `MirrorRunTableTaskDrawer.vue:66`（`height="calc(100vh - 220px)"`）都直接使用 Element Plus 自带滚动条，没有任何自绘浮动条。日志表是这一页里唯一例外。

#### P5b 信息价值：展开详情大半是行内已有字段，失败原因不可自解

- 数据量不是瓶颈：后端默认返回 100 条（`GitlabMirrorProperties.java:23`、`application.yml:118` `recent-logs-limit`），「只有 5 条」纯属 P5a 的裁剪。
- 展开区 11 项里 9 项与行内列或筛选重复（`MirrorSyncLogTable.vue:172-217`：触发来源、同步内容、当前结果、数据追平、删除对账、已并入、来源页面、写入记录、运行编号）。真正新增信息的只有两项，而这两项都答不出「怎么错的、哪里错的」：
  - `错误信息` = `sync_runs.error_message`（`SyncRunLogService.java:96`）。镜像类运行的该字段由 `SyncRunWorkerService.tableRunErrorMessage` 生成，取值只有三种概括：`一个或多个表任务失败`、`一个或多个权威关系范围失败`、`一个或多个表任务未完成`；没有任何表名、阶段或异常内容。
  - `完整消息` = 该运行最新一条 `sync_run_events.message`，为空时回退运行错误或请求原因（`SyncRunLogService.java:177-204`）。镜像同步的运行事件只覆盖生命周期节点，没有任何逐表失败事件：写入点仅有增量尾部补跑（`SyncIncrementalRerunService.java:61`、`:109`、`:148` 的 `RERUN_REQUESTED`/`RERUN_QUEUED`）与取消（`SyncRunCancellationService.java:190` 的 `RUN_CANCELLATION_REQUESTED`/`RUN_CANCELLED_BEFORE_START`），其余事件写入点都在事实相关代码（`FactBuildTaskService.java:306`、`:340`，`FactRefreshTaskWorkerService.java:82`，`FactTargetPublicationService.java:130`、`:145`）。因此该字段对表任务失败运行只显示运行错误那句概括；该句也通不过 `translateSyncMessage` 的翻译表，会原样显示（`sync-run-message-translator.ts:99`）。
- 可定位的失败证据在库里存在，但取不到运行维度：
  - `sync_run_table_tasks` 保存逐表任务的 `status`、`task_stage`（`SCAN`/`RECONCILE`，`V20260729_01:5`）、`retry_count`/`max_retry_count`、`last_error`、`finished_at`；`last_error` 由 `SyncRunTableTaskExecutor.markFailure` 写入真实异常文本（`:322-324`：`e.getMessage()` 或异常类名）。运行终态时的 `coalesce(last_error, ?)`（`SyncRunTableTaskLeaseService.java:186`）只在为空时补概括语，不覆盖真实错误。
  - 但现有诊断接口是「表维度、非运行维度」：`SyncRunTableDiagnosticsService:114` 只给出表状态级 `last_error`，`:128` 只给出**当前活动**任务的错误；对一次已结束的失败运行，没有任何端点能回答「这次运行哪张表、哪个阶段、原始异常是什么」。用户只能去读后端日志，这正是他的结论。
- 失败文本本身也缺少定位上下文：`markFailure` 存的是驱动原始消息（如 `canceling statement due to user request`），不含表名与阶段；表名与阶段需要由界面用同一条记录的 `source_table`/`task_stage` 组合呈现，而不是改写入内容。

#### P5c 「留下线索」的现状清点（验收基线，用户已确认这是最低要求）

把「能看到问题出在哪、可以据此溯源」拆成可核对的判据后，四类失败各自的线索现状如下。判定标准是：**只要线索在库里存在但界面取不到，就属于断链，必须补；线索根本没落库的，必须先把是否要落库作为裁定项，不能默认略过。**

| 失败类型 | 现在详情能看到 | 库里实际持有的线索 | 判定 |
| --- | --- | --- | --- |
| 表任务失败（`FAILED`/`TIMEOUT`，运行多为 `PARTIAL_SUCCESS`） | 只有「一个或多个表任务失败」 | `sync_run_table_tasks`：`source_table`、`task_stage`（`SCAN`/`RECONCILE`）、`retry_count`/`max_retry_count`、`last_error`（真实异常）、`rows_scanned`/`rows_applied`、`started_at`/`finished_at`、`status` | 断链：数据齐备，界面没取 |
| 权威关系范围失败 | 只有「一个或多个权威关系范围失败」 | `sync_run_authoritative_scopes`：`child_table`、`relation_key`、`scope_signature`、`lookup_scope_json`、`status`、`retry_count`/`max_retry_count`、`error_message`、`finished_at`；终态化用 `coalesce(error_message, ?)`（`SyncRunAuthoritativeScopeRepository.java:445-455`）不会覆盖真实错误 | 完全断链：既无界面也无端点，只有一句概括 |
| 运行级原因（超时、超时长、跨天、超出补偿窗口、快速增量覆盖不完整/未规划表） | `错误信息` 已显示具体原因 | `sync_runs.error_message`，文本具体可溯源：`Sync run exceeded maximum runtime of N minutes`、`Compensation sync crossed calendar day boundary`、`Compensation sync exceeded configured compensation window ending at HH:mm`（`SyncRunDeadlineGuard.java:98-124`）、`快速增量未规划任何快速增量表`、`快速增量缺少 issues 或 resource_label_events 必需表`、`快速增量存在未固化的来源扫描上界`、`快速增量 checkpoint 或权威范围尚未覆盖来源上界`（`SyncIncrementalCoverageService.java:96-108`）；取消时原样沿用该原因（`SyncRunWorkerService.java:254-256`） | 够用，但必须与上面的明细同屏显著展示，不能被概括句淹没 |
| 运行生命周期节点（取消申请、增量尾部补跑） | `完整消息` 可能显示最近一条 | `sync_run_events`：`RUN_CANCELLATION_REQUESTED`、`RUN_CANCELLED_BEFORE_START`、`RERUN_REQUESTED`、`RERUN_QUEUED`（含 payload） | 部分可用：只显示最新一条，时间顺序看不到 |
| 表任务每次尝试的过程（同一张表先后因不同原因失败、重试后最终成功） | 无 | 无：`deferOwnedTask` 与 `finishOwnedTask` 都是更新同一行的 `last_error`，成功时置空（`SyncRunTableTaskLeaseService.java:448`），中间尝试被覆盖；`sync_run_events` 目前不记录逐表失败 | 无线索可选：要保留过程必须新增事件写入，属裁定项 |

结论：用户的最低要求（至少留下线索）在**前三类**都能通过「补取已有数据」满足，不需要新增存储；只有「过程留痕」需要新增写入，并且 `sync_run_events` 当前没有保留期清理逻辑，新增写入必须先定保留策略。

### P6 事实层后台工作对页面完全不可见（根因：系统接管的执行不建立运行记录）

用户现场现象：0918 部署后数据镜像设置页显示没有任何同步进程，但事实层刷新确实在运行且接近一小时，日志对此毫无体现（行显示「已完成」或提示待处理），进程结束后平台恢复正常。

- 这是 P2 的镜像面：同一个控制面缺口的两种表现——事实构建与投影任务的归属（`fact_build_tasks.run_id`）与 `sync_runs` 的运行状态脱钩，于是「有工作在执行」与「页面显示活动运行」可以同时为真和为假。
- 执行者有两个：运行执行器 `SyncFactRefreshRunExecutor.drainFactTasks:112-130`（`claimNextQueuedTaskForFactRun(run.getId(), ...)`，只领本运行的任务）与兜底 worker `FactRefreshTaskWorkerService.runOnce:50-62`（每 5 秒一轮，`claimNextQueuedTask`，每轮只执行一个任务）。
- 兜底 worker 的认领条件正好是「父 FACT_REFRESH 运行不是活动状态」：`FactBuildTaskService.claimNextQueuedTask:400-437` 的 `not exists (… run.run_type = 'FACT_REFRESH' and run.status in ('SUBMITTED','QUEUED','RUNNING','RETRYING','PAUSED','CANCELLING'))`。它对「运行已终态」和「运行行不存在」一视同仁。
- 该路径执行任务时不创建也不更新任何 `sync_runs` 行：`FactRefreshTaskWorkerService.execute:64-95` 只有「领取 → 构建/发布 → 回写任务终态」，没有运行级写入。
- 页面的两处数据源都只有 `sync_runs`：当前任务 `SyncRunStatusService.findCurrentRun:85-102`（config + 来源 + 活动状态），最近同步日志 `SyncRunLogService.recentLogs:42-56`（同样只读 `sync_runs`）。因此系统接管的事实构建既不是当前任务，也不会出现在日志里。
- 任务与运行脱钩是这些路径的常态而非例外：运行被取消、被期限保护改判、租约超时被回收、进程重启后执行者消失，都会让运行进入终态而把 `fact_build_tasks` 留在 `QUEUED`/`RETRY_WAITING`；`SyncRunWorkerService.finishRun:258-271` 只终态化镜像表任务（`terminalizeActiveTasksForRun`）与权威范围，不处理事实构建任务，遗留因此保留。
- 为什么能持续近一小时：全量构建按 `GITLAB_FACT_FULL_BUILD_CHUNK_SIZE`（`application.yml:127`，默认 2000）分批提交、逐批续期任务租约并写进度事件（`FactTargetPublicationService.publishFull:109-137`、`progressReporter:139-145`），单任务在 内网 规模下可以跑很久；兜底 worker 每轮只执行一个任务。工作完成、待发布栅栏收敛后页面恢复正常，与用户观察一致。
- 日志行「已完成却仍显示事实构建消息」的成因：进度事件按 `task.factRunId()` 写入，而该值就是那条已经终态的父运行编号，`latestEventMessage`（`SyncRunLogService.java:177-204`）不筛运行状态，于是终态行会显示仍在进行的事实构建消息。
- 归属核对：该行为不是本包引入的回退。`FactRefreshTaskWorkerService` 最近一次改动是 `ea5093ec`（2026-09-07，分批发布与租约治理），`claimNextQueuedTask` 的形态自 `e00c5998`（2026-05-09）即存在；0918 包与当前 HEAD 包含同一形态。**因此该问题在最新代码中依然存在。**

## 方案与步骤

实施顺序：F1 → F2 → F4 →（打包、部署、人工重建，P1 自动恢复）。F3（DEC-A 已裁定）、F5、F6（DEC-D 已裁定）、F8（DEC-E 已裁定）、F9（DEC-F 已裁定）、F10（DEC-G 已裁定）与前述项无依赖，可并入同一单元实施；F2 的孤儿处理方式仍待 DEC-B 裁定。

### F1 等待与失败分离（修 P4a）

1. `FactBuildTaskService` 新增 `deferOwnedTask(task, deferSeconds)`：状态回 `QUEUED`、`run_after` 推迟、`retry_count` 不变、清租约、不写错误终态；防饿死判据复用 `started_at`（首次尝试起超过 30 分钟仍未就绪，则以「依赖代际在 30 分钟内未就绪」为原因 FAILED），不新增表列、不新增迁移。
2. `FactRefreshTaskWorkerService.execute` 的 `isReady` 不通过分支改为调用 `deferOwnedTask` 并返回，不进入失败路径；executor 与兜底 worker 自动同时生效。
3. 测试（RED→GREEN）：未就绪时任务回 QUEUED、retry_count 不变、不写 error；就绪后同一任务成功；超过防饿死时限后以明确原因 FAILED。

### F2 投影任务孤儿自愈（修 P2 / 路径 B）

1. `FactProjectionTaskService` 新增 `claimOrphanedNext(owner, leaseSeconds)`：原子认领一条 `QUEUED`/`RETRY_WAITING`、`run_after <= now`、父运行处于终态（SUCCESS/PARTIAL_SUCCESS/FAILED/CANCELLED/TIMEOUT/MERGED）的任务，设置任务级租约。保留 `claimNext` 的父运行 RUNNING 条件不变（防陈旧执行者的不变量）。
2. `FactProjectionTaskRecoveryScheduler` 每轮在现有失租回收之后，循环认领并执行上限 4 个孤儿任务，复用 `FactProjectionTaskWorkerService.execute`（已含 generation 超前 no-op、租约校验、失败重试语义）；执行成功走 `finishOwned` → `advanceAfterProjectionTask` → 栅栏收敛 → 状态清零，无需用户操作。
3. 测试：取消一个已产生投影任务的运行 → 孤儿被认领执行 → 栅栏 scope 由 PENDING 转 SUCCESS、状态接口不再返回「事实刷新中」；generation 已超前的孤儿 no-op 成功；真实失败按既有重试预算收敛 FAILED（栅栏 FAILED、页面显示可读原因）。

### F3 读侧：有完整发布点即可读（修 P3，含裁定 DEC-A）

1. `qualification` 的「依赖代际未就绪」「全量重建未结算」「标签事件未全量核验」三类由硬拒绝改为「陈旧可读」；「有投影无代际」保持拒绝（完整性无从证实）。
2. `requireReadableSources`/`readOrRefresh` 统一规则：`findLatestReady(request)` 存在完整发布点快照即返回快照并附新鲜度披露；只有从未有过完整发布点才拒绝。陈旧快照响应不写缓存（沿用 S4 已有保证）。
3. 新鲜度披露（按 DEC-A 裁定）：响应沿用 `dataAsOf`（快照对应时点）并新增 `staleReason`（机读原因码）；前端按原因在状态栏给出提示「全量核验中 / 同步进行中 / 全量重建中，展示 X 时刻的完整数据」，不要求用户手动刷新——新一份完整发布点就绪后，页面按既有轮询自动更新为最新数据，状态栏提示同时消失。
4. 测试：构造 `dirty_flag = true`（模拟补偿运行中）→ 页面返回上一完整快照 + 状态栏提示、无 400；从未有完整发布点的视图仍拒绝；陈旧响应不被写回缓存；最新完整发布点就绪后页面自动显示新数据且提示消失。

### F4 全量清理分批删除（修 P4b，含遗漏的 ISSUE 同型路径）

1. `MergeRequestFactPersistenceService` 与 `IssueFactPersistenceService` 的 `deleteFactsNotInSnapshot`（含 `merge_request_commit_fact`、`issue_fact_customer_members`）统一重写：
   ① 快照 id 写临时表（保留现状）；② 用普通等值（键列均已核 not null）纯 SELECT 把待删 id 物化到临时表 `delete_ids`——纯读、不维护业务索引；③ 按批（每批 2000 条）从 `delete_ids` 删除，每批一条独立语句，循环至删完；待删为 0 时直接跳过。
2. 移除 `is not distinct from`。
3. 基准：固定负载（50 万事实行 + 与生产一致的索引集）对比改前/改后单批与总耗时，含 GIN trgm 索引维护代价；容量结论留内网实测。
4. 测试：快照内行身份保留、快照外行按批删尽、与旧实现在多组夹具下结果集完全一致（对拍）。

### F5 镜像互斥域租约感知（路径 A 前提硬化）

1. `claimNextQueuedRun` 的互斥判断对 `RUNNING`/`CANCELLING` 增加与 RETRYING 相同的租约有效性条件。
2. `recoverTimedOutRuns` 增加兜底：活动状态（RUNNING/RETRYING/CANCELLING）且 `lease_until is null` 且 `updated_at` 早于租约超时的行，同样回收为 TIMEOUT。
3. 测试：遗留 RUNNING/CANCELLING 行（租约过期或为空）不再阻塞后续同域运行；正常活动的行不被误回收。

### F6 前端等待可用性（独立缺陷，按 DEC-D 裁定修订）

用户裁定：刷新按钮的预期是几秒内结束，没人会等 15 分钟；15 分钟只作为兜底上限。据此把「等待」与「按钮占用」解耦：

1. 刷新请求提交后，等待只在有限窗口内进行（默认 10 秒，可配置）：窗口内收敛则直接显示新数据；窗口内未收敛则按 DEC-A 展示上一份完整快照 + 状态栏提示「正在刷新，已展示 X 时刻的数据」，按钮立刻恢复可用，后台继续轮询，收敛后自动更新。
2. 15 分钟仅作为兜底：超过后停止等待并给出明确提示（「刷新在 15 分钟内未收敛，已展示 X 时刻数据；原因见同步日志」），不再无限等待。
3. 等待期间不整页遮罩：去掉 `StatisticBoardView.vue` 的整页 `v-loading`，改为刷新按钮置忙 + 状态栏提示，页面其余部分可操作。
4. 重复点击去抖：冷却期内点击显示「正在刷新」而不是「已开始刷新」；等待期间忽略重复触发，不叠加等待。
5. 测试：秒级返回分支（未收敛时按钮恢复且展示旧数据 + 提示）、15 分钟兜底分支、冷却期文案、遮罩行为各一组 Vitest。

### F8 日志表恢复模块自身的双向滚动（修 P5a，按 DEC-E 裁定修订）

用户裁定：可以用 Element Plus 自带滚动条，但必须与平台整体风格统一、不突兀。核对后需要把这条裁定落到具体做法上：

- 纵向：Element Plus 自带的纵向条本来就没有被平台隐藏，给 `el-table` 设 `max-height` 即可得到与平台其它表格一致的纵向条。删除 `styles.css:1765-1767` 的 240px 裁剪与组件内 `overflow-x/y: hidden`，容器不再裁行。
- 横向：平台在 `styles.css:2904-2906` 全局隐藏了 Element Plus 自带横向条（`.el-table__body-wrapper .el-scrollbar__bar.is-horizontal { display: none !important }`），全站统一的横向滚动条是自绘浮动条（`useFloatingHorizontalScrollbar` + `.platform-floating-horizontal-*`，另有 4 处组件在用）。因此「只在这一张表放开内置横向条」会造成该页与全站不一致；「全站放开」等于一次横跨 5 个组件的改造。本项采用折中：横向继续使用平台统一的自绘条，但把它从「钉在视口底部」改回「停靠在模块内部」（`070a2cb3` 之前的 `position: sticky` 形态），保留现有唤醒与拖拽逻辑，只改定位与内边距；模块因此拥有自己的、可见的横向滚动条，且与全站样式完全一致。
- 组件改动：删除 `MirrorSyncLogTable.vue` 的 `Teleport` 视口定位块与相关 `:deep` 强制隐藏规则，改为模块内停靠；`handleExpandChange` 保留 `doLayout`。
- 测试更新（RED→GREEN）：容器高度受限时最后一行可达、展开详情完整渲染、横向条在模块内可见并可拖动。

### F9 日志详情改为故障定位（修 P5b，含裁定 DEC-F）

验收基线（用户已确认）：详情至少让人看出问题出在哪并能据此溯源，即使原因是多个叠加的，也要留下线索。据此把详情固定为三块，缺一不可：

1. **运行级结论**：显示 `sync_runs.error_message` 的原始文本，以及触发来源与运行编号。运行时长的期限类原因、补偿窗口原因、快速增量覆盖不完整原因都在这里原样出现，这是「没有逐表失败时」的唯一线索。
2. **失败清单**：合并两张表的最终失败行，每项带定位标识、阶段、重试次数与原始错误：
   - 表任务：`sync_run_table_tasks` 中 `status in ('FAILED','TIMEOUT')` 的行，取 `source_table`、`task_stage`、`retry_count`/`max_retry_count`、`last_error`、`finished_at`。
   - 权威关系范围：`sync_run_authoritative_scopes` 中 `status = 'FAILED'` 的行，取 `child_table`、`relation_key`、`scope_signature`、`retry_count`/`max_retry_count`、`error_message`、`finished_at`。
   - 实现方式：在 `SyncRunLogService.recentLogs` 对本次返回的运行集合各做一次批量查询（表任务走既有索引 `idx_sync_run_table_tasks_run(run_id, status, created_at)`；范围表按 `run_id` 过滤），每运行最多返回 5 条并在超出时给出总数。无失败行时两个字段都不出现。
3. **运行事件顺序**：把该运行在 `sync_run_events` 里的生命周期事件按时间正序取最多 5 条（取消申请、取消于启动前、增量尾部补跑请求与排队），替代现在只显示最新一条的做法，让「先收到新触发、随后被取消」这类叠加原因按时间关系读得出来。

前端展开区按此重排：失败时三块都显示，成功时只显示表项完成数/计划数、写入行数、数据追平与删除对账结论；删除与行内列重复的详情项（触发来源置入「运行级结论」次要行，同步内容、当前结果、数据追平、删除对账、已并入、来源页面、写入记录不再重复展示）。原始错误文本不做翻译改写、不截断信息，保持可展开看全；失败表超过 5 条时给出「共 N 张失败表」并指引到同页「数据镜像监控 → 表任务」抽屉。

「每次尝试的过程留痕」不在本项范围内（用户裁定 DEC-F 只做基础档：见下）。

按 DEC-F 裁定收窄后的范围声明：本项只呈现「运行级结果 + 本次运行记录到的错误原文 + 该运行自身的事件」，即一个日志系统的本分。不做跨运行的因果推断、不做多原因归并、不做归纳总结，也不新增按运行查询端点（DEC-F ②③ 两档不实施）。原始错误文本可能是上游异常透传下来的（例如某张表因来源不可达而报错），界面**原样显示本次记录的错误信息**，不替换成解释性措辞。

测试（RED→GREEN）：运行含 2 张失败表时响应带失败清单与总数、且不含范围失败项；运行仅范围失败时清单来自 `sync_run_authoritative_scopes`；运行因期限/覆盖原因失败而无任何失败行时，详情仍能显示运行级原因；运行成功时不带任何失败区块；事件顺序按时间正序返回。

### F10 事实层后台工作必须可见且由维护人员决定（修 P6，按 DEC-G 裁定修订）

用户裁定：事实层构建在 300 万数据量下不应耗时很长；问题不是「遗留任务要不要继续」，而是**先让维护人员知道有这件事，再由维护人员决定**；系统可以先把它标记为失败或暂停，继续还是取消由当时的人决定。

不变量（保留）：任何正在执行或等待执行的事实构建/投影任务，必须归属于一条可见的运行或一条可见的待处理记录；系统中不允许存在「无人知晓、自己在干活」的事实层工作。

1. 兜底 worker 停止静默执行：`FactRefreshTaskWorkerService.runOnce` 不再领取「父运行已非活动」的任务去执行。它改为把这些任务收敛为**可见的待处理状态**（`PAUSED`，并写 `message` 说明「原运行 <runId> 已终止，本任务未完成，等待人工决定」），并写运行事件，使该状态在数据的两个既有视图里可见。
2. 可见性与操作入口：
   - 最近同步日志的对应运行行（按 F9 的失败/待处理清单）显示这些待处理任务及其原运行；
   - 页面提供「待处理事实任务」的查看与操作入口：**继续**（重新提交一次该来源的事实刷新/重建，由活动运行接管这些任务）与**取消**（把任务标记为取消并清理待发布归属，页面状态随之收敛）。
   - 数据镜像监控区显示待处理数量，避免用户必须翻日志才发现。
3. 代价与配套：在维护人员决定之前，相关来源的「待发布」不会前进（栅栏保持 PENDING 或按 F3 降级为陈旧可读），页面按 DEC-A 继续展示上一份完整快照并在状态栏说明原因；不再出现「页面无进程、后台却跑一小时」。这个取舍是用户明确选择的：宁可见地等待人工决定，也不要不可见的自动执行。
4. 耗时可见：`fact_build_tasks` 已有 `started_at`/`finished_at`/`affected_rows`，F9 的清单同时显示每项耗时，用于回答「300 万数据为何要一小时」；若实测显示构建本身过慢，另立性能单元处理（不并入本项）。
5. 测试（RED→GREEN）：制造「运行终态 + 仍有 QUEUED 事实任务」→ 兜底轮次不执行任务，而是把任务收敛为可见的待处理状态并写事件；页面能看到待处理项与原因；「继续」能重新提交并完成，「取消」能收敛状态；运行已终态且无遗留任务时不产生任何待处理记录。

### F7 交付顺序

F1、F2、F4、F8、F9、F10 完成后打包（沿用增量更新包流程，直接基线为内网当前运行版本）；部署后由用户在来源就绪窗口提交一次 `issue` 全量事实重建，P1 随之恢复（未知事实数归零）。F3、F5、F6 可并入同一发布。

## 决策记录

- DEC-A（已裁定，采纳建议）：P3 的读侧口径统一为「只要存在完整发布点就服务它 + 状态栏提示」，只有从未有完整发布点才拒绝；不要求用户手动刷新，最新完整发布点就绪后页面按既有轮询自动更新（F3）。
- DEC-B（待用户裁定，本次已提供影响对照）：取消后遗留投影任务的处理方式——① 收割执行（读侧幂等预热，系统自愈、无需人工，快照被预热）对 ② 终态化（栅栏记为 FAILED，状态栏显示「事实刷新未完成，已展示当前可用数据」，新数据需人工重新触发刷新/重建才会前进；`synchronizeScopeStatuses` 的 FAILED 分支、`loadScopeSummary.failed`、`RealtimeWorkspaceService.progressState` 三处已核实该路径成立）。取向提示：用户对 DEC-G 的裁定倾向「显式化 + 人工决定」，若希望两条规则一致则选 ②；若认为读侧派生工作应当自愈、不打扰人则选 ①。
- DEC-C（已解释，结论待用户确认）：F4 不改 GIN/trgm 索引，理由见「P4b」与「F4」：这些索引是记录页搜索（多组 trgm 模糊搜索）的功能依赖，不是可选优化；删除它们是拿功能换速度，且大表 `drop index` 本身就是长操作。分批只降低单条语句的代价与锁/事务范围，从而不再触发 JDBC 语句超时，总索引维护量不变——这是本项要解决的故障（超时），不是要优化总耗时。
- DEC-D（已裁定，采纳修订）：刷新按钮按秒级返回设计，等待窗口默认 10 秒，未收敛则展示上一份完整快照并在状态栏说明；15 分钟仅作兜底上限；等待期间不整页遮罩（F6）。
- DEC-E（已裁定，采纳修订）：纵向使用 Element Plus 自带滚动条（与全站一致）；横向沿用平台统一的自绘条，但停靠回模块内部，因为平台在 `styles.css:2904-2906` 全局隐藏了内置横向条，单表放开会造成突兀（F8）。
- DEC-F（已裁定）：只做基础档——运行级结果 + 本次运行记录到的错误原文 + 该运行自身的事件；不做跨运行因果推断、不做归纳、不新增按运行端点（②③ 不实施）（F9）。
- DEC-G（已裁定，采纳修订）：兜底 worker 不再静默执行遗留事实任务；先把它们收敛为可见的待处理状态（暂停/失败 + 原因 + 耗时），由维护人员在页面上决定继续或取消（F10）。
- 已否决：在取消运行时把遗留投影任务直接终态化（见 DEC-B）；为防饿死新增表列（改用 `started_at` 推算，不加迁移）；手工修改现场任务或栅栏状态（违反状态权威原则）；把失败原因改写成改写后的中文摘要存库（原始异常是排查依据，定位上下文由界面用同一条记录的 `source_table`/`task_stage` 组合，不改写入内容）；由系统代替维护人员自动继续执行遗留事实任务（见 DEC-G）。
- 待定：无。

## 接口契约

- `FactBuildTaskService.deferOwnedTask(QueuedFactBuildTask task, int deferSeconds)`：owner fencing 成功返回 1；状态 QUEUED、retry_count 不变、run_after 推迟 deferSeconds、清租约与心跳；超过 `started_at + 30 分钟` 的连续等待以明确原因置 FAILED。
- `FactProjectionTaskService.claimOrphanedNext(String owner, int leaseSeconds)`：认领条件为 `QUEUED`/`RETRY_WAITING` 且 `run_after <= now` 且父运行终态；原子 UPDATE 设置任务租约；无可认领任务返回 null。
- 统计响应新增 `staleReason`（字符串、可空、非空时与 `dataAsOf` 同现）；取值集合：`DEPENDENCY_SETTLING`、`FULL_PUBLICATION_IN_FLIGHT`、`LABEL_HISTORY_VERIFYING`、`PENDING_TARGETS`（沿用）。`@JsonInclude(NON_NULL)`。
- `/api/gitlab-sync/status` 的日志行新增三个可空字段（无失败、无事件时不出现）：
  - `failureCount`（整数，该运行失败与超时项总数）、`failures[]`（数组，每项含 `kind`（`TABLE_TASK`/`AUTHORITATIVE_SCOPE`）、`label`（表任务为 `sourceTable`，范围任务为 `childTable` + `relationKey`）、`stage`（表任务为 `SCAN`/`RECONCILE`，范围任务为空）、`status`、`retryCount`、`maxRetryCount`、`lastError`（表任务取 `last_error`，范围任务取 `error_message`）、`finishedAt`；每运行最多 5 项）。
  - `eventTrail[]`（数组，最多 5 项、时间正序，每项含 `eventType`、`message`、`createdAt`），替代只取最新一条事件的现状。
- 不新增端点（DEC-F 已裁定不新增）。F10 需要「继续/取消待处理事实任务」的操作能力：优先复用既有的刷新提交入口（页面「刷新最新数据」）与既有取消能力，只有确实无法覆盖时才新增命令端点，并在实施时按黄金基线端点目录登记流程处理。
- 无新增 Flyway 迁移。

## 风险与假设

- F2 收割执行与取消语义的边界已在「约束与背景」界定并列入 DEC-B；若用户对取消语义有不同裁定，F2 改为终态化方案（实施面相同、执行动作替换为终态化）。
- F3 是读侧语义变更：发布前必须运行黄金基线 compare；若出现快照差异，按门禁纪律先向用户展示并获确认后才可重建。
- F4 的谓词改动已核实键列非空，但执行计划改善程度必须由固定负载基准确认；若基准不达标，退化为仅分批、保留 NULL-safe 谓词的版本（实施时以基准数据定）。
- 内网 `PLATFORM_QUERY_TIMEOUT_SECONDS` 的实际取值需现场确认（5 分钟对应 300）；F4 完成后即使保持该值也不会再超时。
- F5 的现场发生率未经现场数据证实（交接材料 6.4 判读表），修改属无害硬化；不因此改动任何运行参数。
- F9 改动 `/api/gitlab-sync/status` 的响应形状，该端点已在黄金基线端点目录（`endpoint-catalog.yml:1242`，`cases: by-config`）；发布前必须运行 compare，快照出现新增字段差异时按门禁纪律先展示给用户确认，再用更新模式重建，不得直接改快照。
- F9 依赖的历史数据已经保留：现场既有失败运行的 `sync_run_table_tasks.last_error` 未被终态流程覆盖（`coalesce(last_error, ?)`），因此更新包部署后历史失败运行同样能看到明细；但更早版本迁移前的运行若无逐表任务行，则不显示明细，只显示概括与运行编号。
- F9 展示的是驱动原始异常文本，可能是英文且较长；界面按「不截断信息、可展开看全」处理，不做二次翻译，避免把排查依据改写成失真的中文。
- F8 按 DEC-E 折中实施后，仍需在真机核对横向条在模块内的可见性与拖拽手感，以及展开行不再被裁剪。
- F10 首次上线会把现场原本正被兜底 worker 静默执行的遗留任务一次性收敛为可见的待处理项，升级后可能立即出现若干待处理记录，需要维护人员逐项决定继续或取消；这是预期行为，必须写入部署 README 与进度记录。
- F10 与 DEC-B 的取向需要一致：若 DEC-B 选「收割执行」，则读侧投影自愈、事实构建待人工决定；若 DEC-B 选「终态化」，两者都走人工决定。实施前必须确认两条规则不互相矛盾。
- F10 只做可见化与人工决定入口；若实测显示事实层构建本身耗时过长，属另一个性能问题（需现场拆分构建、清理、投影三段耗时），不在本单元顺手改构建策略。
- 假设：内网现场无 `RUNNING/CANCELLING 且 lease_until 为 null` 的历史行；若有，F5 的兜底回收会将其收敛为 TIMEOUT，属预期行为。
