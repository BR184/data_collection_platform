# 同步与读侧六类缺陷的最终修复设计（P1–P6 + 互斥域 + 前端等待）

## 进度与中间物

- 当前阶段：设计审查修订稿，尚未实施业务修复。用户要求复核审查发现、直接修订原文、特殊标注并推送；本轮按最新 `origin/main` 的 `73a6869a` 核实 R1–R12，修正原文中的恢复死锁、取消误报、重试误伤、实时读侧误放行等设计问题。
- 追加范围：P5 最近同步日志与 P6 不可见事实工作仍存在相应代码缺口。P6 是可导致现场现象的路径；缺少 0918 部署现场的数据库、进程与日志证据，不能据此认定那次近一小时运行的唯一成因。
- 已完成文件/变更清单：本计划与 `docs/progress.md` 的进度入口；所有实质修订使用 **【审查修订 R编号｜2026-09-30】** 标记，正文、决策与接口一起替换，不保留冲突方案作为执行指令。
- 测试通过/失败状态：审查基线现有前端 5 文件 / 31 项、后端 9 类 / 61 项通过；4 个隔离 PostgreSQL 临时表探针证实旧方案风险。它们验证现状，不能证明修订方案已实现。工作树产物、运行产物位置、文本空白与 `git diff --check` 四项文档门禁通过；浏览器修复验收、固定负载基准与发布 compare 尚未执行。
- 当前交付：修订与文档校验已完成，按本轮授权提交并推送 `origin/main`。业务实施、迁移、部署与黄金快照重建均不属于本轮文档任务。

### 恢复线索

- 当前阶段：设计已修订，下一业务单元按 F7 的依赖顺序实施；本轮只交付文档。
- 恢复后建议执行的首条命令：`git status --short --branch`，确认最新代码后从 F7 第 1 阶段开始，不直接按旧顺序实施 F1/F2。
- 相关计划：`docs/plans/fact-publication-authoritative-rework-20260930.md`（已完成的前一单元）。
- 审查基线 commit：`73a6869a`（本轮开始时工作树干净）；证据定位以类名与方法名为准，下文旧行号只辅助阅读，不能代替按该基线重新定位。

## 目标与边界

- 用户原始需求：对内网测试发现的四个问题（P1 BI 需求指标空、P2 状态永挂「事实刷新中」、P3 全量补偿对账期间页面无数据、P4 手动事实层重建失败）给出确定的修复；参考业内成熟做法，给出最终设计与调优方案，不要止血式补丁。
- 可验证的成功标准：
  1. 【审查修订 R2/R5】取消 FACT_REFRESH 后，遗留投影成为可见、可操作的待处理项；自动工作停止。维护人员明确继续后，投影由可见的新运行完成并使栅栏收敛；取消本次执行不会伪报「已展示最新事实数据」。
  2. 手动提交全量事实重建时若来源依赖尚未就绪，任务等待而不是失败；依赖收敛后自动完成。
  3. 【审查修订 R7】全量补偿期间，已保存完整快照的系统测试与客户问题看板继续展示同一范围、规则、筛选身份的上一完整数据与原因横幅。实时 BI、筛选、下钻与导出不得读取构建中间态；没有同版完整产出的路径明确说明不可用，不能承诺它们也已有历史数据可回退。
  4. 【审查修订 R12】MR 与 ISSUE 全量清理覆盖空快照、关系和成员删除，固定负载下验证物化与删除阶段均满足实际语句预算；夜间核验不再因原大 DELETE 固定失败。能否满足内网容量必须实测。
  5. `issue` 全量事实重建成功后，BI 客户问题页「未知事实数」归零，需求指标与交付状态恢复。
  6. 后端全量快速套件、黄金基线 compare 门禁全绿。
  7. 【审查修订 R4/R8/R10】日志模块具有自己的双向滚动；标准桌面默认至少 8 行，小屏全部行也可滚动到达；最后一行及长文本展开完整可达。表、范围、事实构建、投影四类异常/待处理项均有定位与错误原文；第 6 项以后能在同次运行详情中查全。界面提供库中已有线索，不承诺恢复从未保存的堆栈或每次尝试历史。
  8. 【审查修订 R3/R5】不再出现「页面显示没有同步进程、事实层却在长时间工作」：自动工作对应可见活动运行，合法同步 MANUAL 调用对应可见独立活动任务，遗留项对应可见待处理记录；由维护人员继续或取消，不再有不可见后台执行。
  9. 页面刷新按钮在几秒内结束（未收敛时展示上一份完整快照并在状态栏说明），15 分钟仅作兜底上限；同一时刻不再出现整页遮罩导致页面不可用。
- 明确禁止的行为：不清空任务表、不绕过服务手工标记任务或栅栏、不伪造已发布版本、不保留旧实现双轨、不为假想需求增加开关、未经用户确认不重建黄金快照。本轮用户已授权文档推送，目标为 `origin/main`。

## 约束与背景

- 本单元涉及的问题中，P2、P3、P4a、P4b、P5、P6 的相关路径均在审查基线中存在；历史归因须由 Git 历史或现场证据支持，不能仅由 `git diff HEAD` 为空推出。P3 的覆盖范围涉及上一单元 S4 的取舍。修复必须直接改原实现与全部调用点，不留兼容层（AGENTS.md「开发期代码演进红线」）。
- 【审查修订 R3/R5】P2 与 P6 统一验收：自动执行中的事实/投影任务必须归属可见且有效的活动运行；人工停放归属可见的待处理记录，不保留可被自动派发的执行权。已有同步 MANUAL 任务也需在任务视图可见，不能因为没有 FACT_REFRESH 父运行就误暂停合法同步调用。
- 黄金基线门禁：本单元改动读侧语义（F3）与统计输出，发布前必须运行 compare 模式门禁；如快照需要重建，必须先向用户展示差异并获确认。
- 性能改动必须带固定负载基准（F4 的清理删除重写），容量结论以内网实测为准。
- 【审查修订 R2/R5】修订提案统一采用 DEC-G 的人工决定取向：取消运行撤销其事实与投影执行权；遗留工作显式待处理，投影幂等性不构成取消后静默执行的理由。正常活动父运行下的自动投影与重试照常进行。此处是本次建议，不能把原 DEC-B 的未裁定选项写成已有用户批准。

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
- 退避与预算：`FactBuildTaskService.failOwnedTask` 的退避为 `power(2, retry_count)` 秒（1 秒、2 秒），`max_retry_count` 默认 3。两次退避合计约 3 秒；实际耗尽时间还受派发周期、同域占用与执行耗时影响，不能当作实测总时长。依赖等待仍会错误消耗这份有限失败预算。
- 影响面收窄确认：`SyncFactRefreshRunExecutor.execute:123-127` 与兜底 worker 共用同一个 `FactRefreshTaskWorkerService.execute`，因此该缺陷影响**所有**事实构建任务，不限于手动重建。
- 该 worker 与基线逐字一致，属既有缺陷。

### P4b MR 全量重建清理 DELETE 超时（根因：单条全表反连接删除维护全部索引）

- `MergeRequestFactPersistenceService.deleteFactsNotInSnapshot:134-211`：`delete ... where not exists (select 1 from merge_request_snapshot_ids s where s.project_id is not distinct from f.project_id and s.merge_request_id is not distinct from f.merge_request_id)`。`is not distinct from` 不是普通等值，规划器难以走索引；`merge_request_fact` 有大量索引（`V20260506_01` 单个迁移即 17 条 `create index`，含 5 个 `gin_trgm_ops`，内网实测 28 个索引、12 个 trgm），删除每行都要维护全部索引。
- 超时来源：`application.yml:31-33` `spring.jdbc.template.query-timeout`（Spring `Statement.setQueryTimeout`）。现场日志措辞 "canceling statement due to user request" 正是 JDBC 取消路径（PG 的 `statement_timeout` 措辞为 "due to statement timeout"）。
- 【审查修订 R12】已核：清理是全量重建最后一步，分块 upsert 每块独立提交；失败回滚清理事务，但此前已提交的 upsert 不回滚，不能说“整个原数据完好”。同一清理方法也用于夜间全量核验；现场固定失败的原因仍需对应 SQL 与耗时证据确认。
- 遗漏补报：`IssueFactPersistenceService.deleteFactsNotInSnapshot:100-145` 与 `merge_request_commit_fact` 的清理是同一形态（`is not distinct from` 反连接），ISSUE 全量重建成功后同样会超时。本设计统一修复。
- 谓词可改的依据已核实：`issue_fact.project_id`、`issue_fact.issue_id`（`V20260506_01:62-80`）与 `merge_request_fact.project_id`、`merge_request_fact.merge_request_id`（`:153-170`）均为 `bigint not null`，`is not distinct from` 可安全改为普通等值。

### 路径 A 镜像互斥域（交接材料新增，本设计按前提硬化）

- 已核实：`SyncRunDispatcherService.claimNextQueuedRun` 的互斥判断对同域 `RUNNING`/`CANCELLING` 行不检查租约有效性（仅对 RETRYING 检查）；`SyncRunLeaseService.recoverTimedOutRuns` 要求 `lease_until is not null`。
- 【审查修订 R6】收窄：`deferOwnedRun` 合法置空 PAUSED/RETRYING 的租约并保留 `run_after`；等待超过租约窗口也不代表故障。「RUNNING/CANCELLING 且空租约」属于异常候选，现场是否发生仍待验证。过期运行的互斥放行必须先撤销旧执行权并阻止其 heartbeat 复活；仅忽略过期行不能称为无害修复。

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

【审查修订 R4】补充两类线索：事实构建原始错误在 `fact_build_tasks.error_message`，投影错误在 `fact_projection_refresh_tasks.error_message`；分别由两个 worker 写入，运行级只有重试上限概括。现有构建重试事件截到 200 字，不能代替任务原文。F9 必须补取这两类数据与人工待处理标记。最低故障线索主要可以通过已有错误列取得；F10 的人工处置语义仍需要少量持久元数据，不能据此宣称整个方案无需迁移。逐次尝试留痕仍不在本项范围。

### P6 事实层后台工作对页面完全不可见（根因：系统接管的执行不建立运行记录）

用户现场现象：0918 部署后数据镜像设置页显示没有任何同步进程，但事实层刷新确实在运行且接近一小时，日志对此毫无体现（行显示「已完成」或提示待处理），进程结束后平台恢复正常。

- 这是 P2 的镜像面：`fact_build_tasks.run_id` 与 `sync_runs.id` 的关联，以及投影的 `fact_run_id`，都可能与父运行活动状态脱钩，于是有工作执行而页面没有活动运行。事实任务 `run_id` 为字符串，MANUAL 同步路径也可能使用 UUID；诊断与迁移不得把所有值直接强转 bigint。
- 执行者有两个：运行执行器 `SyncFactRefreshRunExecutor.drainFactTasks:112-130`（`claimNextQueuedTaskForFactRun(run.getId(), ...)`，只领本运行的任务）与兜底 worker `FactRefreshTaskWorkerService.runOnce:50-62`（每 5 秒一轮，`claimNextQueuedTask`，每轮只执行一个任务）。
- 兜底 worker 的认领条件正好是「父 FACT_REFRESH 运行不是活动状态」：`FactBuildTaskService.claimNextQueuedTask:400-437` 的 `not exists (… run.run_type = 'FACT_REFRESH' and run.status in ('SUBMITTED','QUEUED','RUNNING','RETRYING','PAUSED','CANCELLING'))`。它对「运行已终态」和「运行行不存在」一视同仁。
- 该路径执行任务时不创建也不更新任何 `sync_runs` 行：`FactRefreshTaskWorkerService.execute:64-95` 只有「领取 → 构建/发布 → 回写任务终态」，没有运行级写入。
- 页面的两处数据源都只有 `sync_runs`：当前任务 `SyncRunStatusService.findCurrentRun:85-102`（config + 来源 + 活动状态），最近同步日志 `SyncRunLogService.recentLogs:42-56`（同样只读 `sync_runs`）。因此系统接管的事实构建既不是当前任务，也不会出现在日志里。
- 任务与运行脱钩是这些路径的常态而非例外：运行被取消、被期限保护改判、租约超时被回收、进程重启后执行者消失，都会让运行进入终态而把 `fact_build_tasks` 留在 `QUEUED`/`RETRY_WAITING`；`SyncRunWorkerService.finishRun:258-271` 只终态化镜像表任务（`terminalizeActiveTasksForRun`）与权威范围，不处理事实构建任务，遗留因此保留。
- 为什么能持续近一小时：全量构建按 `GITLAB_FACT_FULL_BUILD_CHUNK_SIZE`（`application.yml:127`，默认 2000）分批提交、逐批续期任务租约并写进度事件（`FactTargetPublicationService.publishFull:109-137`、`progressReporter:139-145`），单任务在 内网 规模下可以跑很久；兜底 worker 每轮只执行一个任务。工作完成、待发布栅栏收敛后页面恢复正常，与用户观察一致。
- 日志行「已完成却仍显示事实构建消息」的成因：进度事件按 `task.factRunId()` 写入，而该值就是那条已经终态的父运行编号，`latestEventMessage`（`SyncRunLogService.java:177-204`）不筛运行状态，于是终态行会显示仍在进行的事实构建消息。
- 归属核对：相关路径早于本次包，`FactRefreshTaskWorkerService` 的分批发布治理可追溯至 `ea5093ec`，兜底认领形态可追溯至 `e00c5998`；最新审查基线仍存在缺口。这支持问题并未完全消除，但不替代对 0918 现场运行身份与耗时的核实。
- 【审查修订 R3】另一条独立触发路径：`SyncRunCancellationService.requestCancel` 只用镜像表任务判断活体；FACT_REFRESH 可能直接进入 CANCELLED，而事实任务续租/最终结算与投影快照写入只检查任务执行权，没有父运行取消约束。只关闭兜底领取不能停止这类已启动任务，F10 必须覆盖正在执行者。

## 审查修订索引

以下 R 编号与本轮审查发现一一对应，均已回到代码核实。标记表示设计已修正，不表示业务代码已经修复。

| 特殊标注 | 已核实的问题 | 修正入口 |
| --- | --- | --- |
| R1 | PAUSED 任务保留根归属，普通新运行既领不到根也领不到旧任务 | F10 事务接管、汇总、FULL/TARGETED 意图 |
| R2 | 取消/释放归属不改变未发布版本 | F10 取消语义、接口的真实陈旧状态 |
| R3 | 已启动任务可在父运行取消后续租和提交 | F10 取消协议、父子执行权、提交屏障 |
| R4 | F9 漏事实/投影/待处理与范围唯一标识 | F9 四类诊断、接口字段 |
| R5 | F2 静默投影与 F10 冲突，长工作进入调度线程 | F2/F10 统一人工处置、DEC-B |
| R6 | 合法 RETRYING 空租约被误判超时，旧 owner 可续租复活 | F5 精确回收和提交 fencing |
| R7 | 共享门控放宽会暴露实时消费者的构建中间态 | F3 快照回退与实时读取能力分离 |
| R8 | 第 6 条历史错误不可达，最早事件/进度事件挤掉故障 | F9 数据库限量、同运行分页、相关事件 |
| R9 | started_at + 30 分钟误判正常长任务为依赖等待超时 | F1 删除新增时限、正值退避、父运行派发 |
| R10 | 原横条使用视口坐标，直接 sticky 会偏移并影响共享表 | F8 模块局部几何、浏览器验收 |
| R11 | 放开 loading 后可叠加轮询，共享调用者和路由失效未覆盖 | F6 独立生命周期、全部调用点 |
| R12 | 分语句不等于分事务；遗漏空快照与物化超时 | F4 单事务原子性、完整清理与测量 |

附带一致性修订：更新审查基线；撤销相互矛盾的 DEC-B/“已否决”/“待定：无”；修正依赖顺序、接口字段与“不新增迁移”的错误承诺。修订取舍是本次设计建议，不能反向编造此前用户裁定。

## 方案与步骤

依赖顺序见 F7。F2/F10 是同一控制面改造，F9 必须覆盖其状态；F6 的旧数据展示依赖 F3。F4 与 F8 可以独立实施和验证，但不能在状态闭环完成前宣布整套问题解决。

### F1 等待与失败分离（修 P4a）

> **【审查修订 R9｜2026-09-30】** 依据 `FactBuildTaskService` 的首次 started_at、`failOwnedTask` 与 `SyncFactRefreshRunExecutor.execute`：首次启动时间不是连续依赖等待起点，删除新增的 30 分钟失败规则。

1. 就绪检查不通过，调用 `deferOwnedTask(task, deferSeconds)`：校验任务 owner、未过期任务租约及有效父运行；任务回 QUEUED，retry_count 不变，清 owner/lease/heartbeat，run_after 推迟。等待原因与时间进入诊断，保留此前真实错误；依赖等待不生成失败异常或消耗预算。
2. deferSeconds 必须为正，派发间隔复用现有 5 秒节奏，不增加运维配置。父运行汇总必须包含等待任务数与最早 run_after；本轮无可执行项时按现有 PAUSED 自动等待语义释放运行租约，下一次领取不早于有效 run_after，避免 executor 紧循环。
3. 不增加无负载依据的依赖等待期限。沿用现有运行期限策略；触发期限时按真实原因取消/超时并进入 F10 处置，不能把 started_at 用作依赖阶段计时。真实执行异常继续使用原有限重试预算。
4. 适用于运行执行器和所有合法事实 worker 调用；父运行已失效时转 F10，不继续 defer。长期等待可见且可取消。

验收：依赖未就绪不计失败；就绪后完成；首次开始超过 30 分钟而本次仅短暂等待不会失败；真实异常仍耗预算；汇总与再次派发不遗漏 QUEUED 的延迟任务；未到 run_after 不空转。

### F2 投影遗留任务显式处置（修 P2，与 F10 合并）

> **【审查修订 R5｜2026-09-30】** 依据 `FactProjectionTaskService.claimNext/recoverExpiredTasks`、`FactProjectionTaskRecoveryScheduler` 及架构 D-16：删除 `claimOrphanedNext` 和“调度器执行最多 4 个孤儿”的方案。

1. 活动父运行下的投影领取、generation superseded no-op、有限重试继续使用正常执行路径。恢复调度器只做有界 SQL 巡检/状态移交，不执行统计或记录预热，不把长工作放入 `@Scheduled`。
2. 父运行已终态的 QUEUED/RETRY_WAITING 投影，以及父运行失效后失租的 RUNNING 投影，撤销执行权后变为 FAILED + `manual_disposition=REQUIRES_DECISION`，保留原始错误并附处置原因。现有 FAILED 遗留投影也纳入可见的失败/人工处置视图；正常失败与“失去父运行”原因分开显示。
3. 投影状态 CHECK 目前只允许 QUEUED/RUNNING/RETRY_WAITING/SUCCESS/FAILED（`V20260731_01`）；本设计沿用这些状态，以持久处置字段区分人工待处理，不直接写 PAUSED/CANCELLED 到投影表。
4. 用户明确继续后，按 F10 将选中任务移交给可见的新 FACT_REFRESH 运行，由已有具名执行器完成。未决定期间显示“投影待处理”，不能长期展示成“事实仍在运行”。若另一条正常运行已成功投影到同 scope 的不低于目标 generation，可按真实覆盖证据消除旧待处理项并记录原因，不执行旧任务、不假造覆盖。

验收：终态父运行的 queued/retry/失租 running/failed 各有可见结果；未点击继续时无自动预热；新运行可见并完成；慢投影不堵调度；更高 generation 与人工恢复并发时旧结果不能覆盖新结果。

### F3 读侧：返回已保存的完整发布点（修 P3）

> **【审查修订 R7｜2026-09-30】** 依据 `StatisticBoardSnapshotService.readOrRefresh/withinConsistentSourceRead`、`BiCustomerIssuePageService.readSnapshot` 与客户统计筛选/明细路径：快照可返回不等于当前事实可计算。

1. 来源资格显式区分 `currentFactReadable`（可从当前事实读取完整产出）与 `savedSnapshotReadable`（完整性证据允许返回已经保存的完整产出）。不把依赖等待、全量未结算、标签核验中的共享 qualification 一律改为 readable；事实构建的 isReady 与快照写回门禁保持完整就绪要求。
2. 只有 savedSnapshotReadable 的 `readOrRefresh` 按 boardKey、scopeKey、ruleVersion、filterHash 和实际来源身份查上一完整 READY；返回该快照原 sourceVersion 与数据时点。完整性须可证，已知在未完整构建窗口产生、无法确认完整性的历史 READY 不能直接使用。范围、规则、筛选或来源不一致的旧结果不能冒充本视图的完整结果。无合格历史快照则明确不可用，不能改用当前中间态或零值。
3. `withinConsistentSourceRead` 的实时 action 继续要求 currentFactReadable。逐一覆盖 BI 客户问题、客户统计筛选候选、下钻、记录与导出调用者：已有同版完整保存产出可读取它；没有该能力则明确拒绝该项并给维护原因。旧主表不与当前明细/导出混用；前端保留主表与已加载数据，对不可用操作说明原因。
4. 本单元的持续可用承诺限于已经保存完整产出的视图。要让无历史产出的实时页面也在维护期间完整可用，需要另行补齐同版历史产出能力；不能把该缺口写成已由资格门控解决，也不在本单元擅自重建整套 BI 缓存。
5. 陈旧判断独立于 pendingUpdates 数值；即使待更新计数为 0，DEPENDENCY_SETTLING、FULL_PUBLICATION_IN_FLIGHT、LABEL_HISTORY_VERIFYING 仍触发快照回退。多来源保留全部原因，不能被一个来源的“可读”掩盖另一个未就绪来源。
6. dataAsOf 取实际返回快照的生成/刷新时间，不取最新镜像同步时间。陈旧响应不生成/覆盖 READY 缓存。新完整产出就绪后，F6 的跟踪者按同一请求身份更新数据与提示。
7. 自动和手工 FULL 的所有入口，在首个事实写入批次前通过既有 `source_fact_publication_states.full_publication_requested` 登记该事实族的未结算意图；不得只靠“有未发布根”判断完整性。取消/失败不清该标记，只有完整重建成功结算后清除；即使 pendingUpdates=0 也不允许把半次全量数据产出为 READY。

验收：逐个验证缓存看板、实时 BI、筛选、下钻、导出；全量多批提交期间不生成混合 READY；旧主表与明细保持同版或明细明确不可用；无快照、pending=0 仍陈旧、多来源、规则/筛选变化均覆盖；正常完整读取与写侧就绪行为不变。

### F4 全量清理分批删除（修 P4b）

> **【审查修订 R12｜2026-09-30】** 依据两种 `deleteFactsNotInSnapshot` 的 `@Transactional`、`ON COMMIT DROP` 和空集合分支：明确采用单个清理事务内的分批语句，保持现有清理原子回滚。

1. MR、MR commit、ISSUE、ISSUE customer members 都覆盖：装载来源快照身份临时表 → 物化待删除业务键到临时表 → 每批候选 2000 个父/关系键，先删除从属成员/关系再删除父事实 → 移除已经处理的临时键并循环。所有步骤仍在同一清理事务、同一连接；任一批失败或失去执行权，整个清理回滚。此前已独立提交的 upsert 批次沿用现有可恢复语义。
2. 空快照也走该有界清理路径；不能保留直接 DELETE 全来源的大语句。空 MR commit 快照要删除相应旧提交关系；成员/关系可能一对多，2000 个父键不等于最多删除 2000 行，基准须按实际子行数测量。
3. 普通等值仅用于已证实非空且类型一致的身份列：issue/MR 的 project_id、issue_id/merge_request_id，以及 `V20260805_01` 中 MR commit 的 commit_sha 等完整复合键。临时表使用相同类型并对输入身份预检；不静默丢弃 NULL，也不留双轨 NULL-safe 回退。
4. 保留全部既有搜索索引。分批降低单条 DELETE 的候选量，**不会缩小整个清理事务的持锁或回滚范围，也不会减少总索引维护量**。物化 SELECT、临时表装载和成员删除同样受查询预算约束，必要的临时键索引/ANALYZE 以执行计划证据决定。
5. 固定负载至少包括 50 万事实、实际索引集、不同删除比例、空快照及高子行数；分别记录物化耗时、最大单批耗时、总耗时、锁与回滚耗时。内网 300 万级容量须实测，不能以固定 2000 候选键预先保证任何查询超时设置下都成功。
6. 每个删除批次和最终 FULL_EPOCH 结算都执行 F10 的执行权校验。取消先阻止新的清理批次；清理已开始的事务须完成可靠中止/回滚或受锁保护的提交，再结束运行级活动状态。

验收：快照内身份保留、外部事实/关系删尽、其他来源不变、空来源正确清理；中途异常与取消不留下半次清理；与旧正确语义对拍；基准满足实际预算后才宣告超时缺陷解决。

### F5 镜像互斥域租约感知

> **【审查修订 R6｜2026-09-30】** 依据 `SyncRunLeaseService.deferOwnedRun/heartbeat` 和 dispatcher：合法释放租约的 RETRYING/PAUSED 不属于超时回收对象。

1. 空租约兜底只处理按协议应持有执行权的异常 RUNNING/CANCELLING，结合 owner、heartbeat、updated_at 与既有租约窗口判定。合法 owner=NULL、lease=NULL 的 RETRYING/PAUSED 保留，尊重 run_after；排队、容量等待或同域占用再久也不因此记 TIMEOUT。
2. 非空过期租约按既有超时规则回收。放行同域新运行前，原子撤销旧 owner/运行状态及受其授权的子任务执行权；撤销失败仍按占用处理，不能先忽略旧阻塞行后再异步收拾旧执行者。
3. heartbeat、最终运行完成、事实与投影续租/提交都要求当前 owner 且租约尚有效；不能用过期旧 token 续期复活。取消状态只允许取消收尾，不允许恢复正常写入。旧 SQL/批次已经进入提交临界区时，撤销与提交按一致锁协议串行，不能仅靠事务外检查。
4. 正常有效运行继续互斥，不扩大跨来源限制，不增加运行参数。现场空租约异常是否真实出现仍需数据证据，不能把本项称作已验证无害硬化。

验收：合法 RETRYING 长等待未被回收；异常 RUNNING/CANCELLING 空租约可回收；过期 owner 不能 heartbeat/finish/write；回收、新领取、旧提交并发时只有一个有效提交身份；有效租约及其他来源无回归。

### F6 前端刷新生命周期（独立缺陷）

> **【审查修订 R11｜2026-09-30】** 依据刷新控制器、无界等待函数和全部六个页面入口：拆开提交、取数和后台跟踪，更新所有调用者。

1. 状态拆为 `submitBusy`、`fetchLoading`、`refreshPending`。按 workspace + config/source 只保留一条刷新跟踪；数据请求、后台轮询与按钮状态不再共用 loading 去重。请求身份还绑定路由与筛选代次，旧结果不能覆盖新范围。
2. 提交后只在已有裁定的 10 秒窗口占用刷新按钮；已收敛则加载新数据，未收敛则保留当前数据/按 F3 取完整快照，释放按钮并由单一跟踪者继续轮询。10 秒是交互常量，不新增运维配置；已有数据取数失败时保留它并显示原因。
3. 同一刷新自首次接受/开始跟踪起最多跟踪 15 分钟，重复点击不重置该时点。到期停止该跟踪并显示“仍未完成，查看同步日志”，不改变服务端业务状态、不伪装 READY；再次明确刷新或常规页面加载可以读取实际最新状态。
4. 后端提交结果须区分 ACCEPTED 与 ALREADY_REFRESHING/COOLDOWN，并带可用的跟踪身份；冷却未接受新请求时不能提示“已开始刷新”。refreshPending 为真时复用现有跟踪，不叠加请求/等待链。
5. 去掉刷新引发的整页遮罩；首次加载可保留原取数提示。路由/来源/筛选切换与组件卸载使旧跟踪失效，清定时器、取消可取消的请求；完成回调只更新其匹配身份的主表及当前已打开明细。
6. 同步更新 `StatisticBoardView`、`SystemTestMultiBoardView`、`SystemTestIssueSearchView`、`CustomerIssueRecordsView`、`CodeReviewIllegalRecordsView`、`IssueIllegalRecordsPage` 及共享 composable。等待结果使用明确的状态返回，不再把“按钮窗口结束”当成“刷新成功”；自动刷新入口也走唯一生命周期。

验收：10 秒/15 分钟边界、重复点击、冷却、后台收敛、真实失败、无历史快照、路由/筛选/卸载、已开明细与自动刷新；六个入口一致且无旧回调污染，页面可操作。

### F8 日志表模块自身双向滚动（修 P5a）

> **【审查修订 R10｜2026-09-30】** 依据 `useFloatingHorizontalScrollbar` 的视口坐标与五个调用组件：复用拖拽同步，日志模块使用局部布局。

1. 移除日志 shell 的 240px 裁剪与禁止纵滚，给 el-table 正确 max-height。按实际行高/表头测定桌面高度（约 420px，最终以真实样式为准），默认至少 8 条完整行；小屏随可用高度缩小但全行可滚动。模块底部预留横条空间，不盖最后一行。
2. 纵向使用 Element Plus 内置条；横向沿用平台轨道/滑块样式，在模块底部停靠。移除该组件 Teleport；横条 left/width/bottom 按本地容器与表体 clientWidth 计算，不绑定原视口 fixed style。
3. 共享 composable 明确内部定位模式 `viewport/container`，默认保持其他四个表现有视口行为，仅日志表选择 container。这个模式是落实两种现有布局的内部参数，不是新的用户或运维开关。
4. 展开/收起、筛选、窗口 resize、纵滚和宽度变化后重算；普通滚轮纵向正常，横拖同步、键盘可达，模块离开视口时横条不会漂浮在页面其他区域。原始长错误文本可折行/展开并复制全文。

验收：Vitest 验证状态与 DOM 后，还必须用浏览器验证 8+ 行、底部多行展开、长文本、双向拖动、模块部分离屏、窄屏和另外四个共享表。DOM 渲染断言不能代替滚动可达性证据。

### F9 日志详情用于故障定位（修 P5b/P6）

> **【审查修订 R4/R8｜2026-09-30】** 依据四张任务表、`SyncRunLogService` 与现有抽屉的当前任务过滤：补全四类诊断，同运行异常不因摘要上限丢失。

1. **运行结论**：显示 sync_runs 实际状态、原始运行级错误、编号、来源、触发、起止与耗时；历史运行结果不因后来待处理而改写。另显示“该运行已结束，尚有 N 项人工待处理/仍有待更新数据”，区分运行结束、任务处置和数据追平。
2. **四类定位项**：
   - TABLE_TASK：FAILED/TIMEOUT，以及有错误的 RETRYING，含 sourceTable、taskStage、taskId、预算、错误与扫描/应用计数。
   - AUTHORITATIVE_SCOPE：FAILED/RETRY_WAITING，含 childTable、relationKey、**scopeSignature**、lookupScope、预算与错误；同表同关系的不同范围必须能区分。
   - FACT_BUILD：FAILED、带错误的 RETRY_WAITING、人工 PAUSED/待处理，以及保留原错误或处置原因的 SKIPPED + RESUMED/CANCELLED，含 taskId、sourceInstance、factType、FULL/TARGETED、scope、原/当前运行、affectedRows、实际根数与任务意图。继续/取消之后的历史错误仍可查到，不能因状态变为 SKIPPED 从清单消失。
   - PROJECTION：FAILED、带错误的 RETRY_WAITING、人工待处理，含 taskId、factBuildTaskId、factType、scopeType/scopeKey、targetGeneration、原/当前运行与错误。
3. 每项均提供 startedAt、finishedAt、errorObservedAt、elapsedMs、实际状态、retryCount/maxRetryCount、rawError、处置原因与可执行动作；有 heartbeat/lease/progress 则同时展示其时间。进行中耗时用响应 observedAt 减 startedAt；未知起点/失败时间保持空值，不伪造。updated_at 只能标“记录更新时间”，不能充当未保存的原始失败时刻。
4. rawError 优先取任务错误列，展示全文与对象/阶段上下文；运行概括、翻译文案和 200 字事件不能替代。没有保存堆栈/内部 SQL 的任务如实注明线索边界，不保证 UI 能独立给出唯一根因。
5. 默认日志仍最多 100 条运行。每运行摘要最多 5 个异常/待处理项、最近 5 条相关事件，同时给完整总数。四类摘要均在数据库按 run 分区限量并计数，不先将 100 条运行的全部任务拉到 Java 截断，不逐运行 N+1。
6. 在既有 `GET /api/gitlab-sync/status` 上增加可选详情查询参数，用相同 config/source 授权与归属校验，按选中 run 分页查看全部诊断与事件；旧运行即便离开最近 100 条，只要记录仍在也可由其 runId 查询。页面的“查看全部 N 项”在日志展开区打开同运行详情；不再引导到无法查历史的表任务抽屉。找不到明细时说明没有保留，不能从当前表错误拼接历史错误。
7. 生命周期/失败/人工处置事件与进度分开。相关事件先按 created_at DESC、id DESC 取最近 5 条，再正序显示；显示 eventCount 和分页入口。FACT_BUILD_PROGRESS 等高频进度只提供最新进度与时间，不挤掉取消/失败/接管线索。更多相关事件使用同运行分页，稳定排序。
8. F10 的投影移交保留原运行的不可变诊断事件快照（见 F10），历史详情合并该快照并按该 run 内 kind/taskId 去重，原诊断与移交结果一起显示，不能用移交后的成功任务覆盖原失败证据。failureCount 与 manualAttentionCount 可重叠，不相加当作任务总数；diagnosticCount 是去重后的可查明细总数。普通重试仍沿用原保存范围；不新增每次尝试历史、跨运行因果推断或自动错误总结。
9. 成功且无异常/待处理时展示完成数、写入数与追平结论，取消/超时/部分成功仍显示运行级原因。已有可见的活动事实/投影进度独立展示，不把历史成功行误渲染为当前仍在构建。

验收：四类异常、重试、待处理、只有运行级错误、成功、终态仍有任务；至少 6 个失败表、多个同表同关系范围、数百进度事件后的取消/故障、分页查旧 run、全文复制、投影接管后仍保留旧错误；非法跨配置查询拒绝。

### F10 事实/投影可见、可继续、可取消（修 P6）

> **【审查修订 R1/R2/R3/R5｜2026-09-30】** 依据根领取排除、任务领取/续租、运行取消和发布事务：人工处置是执行权转移协议，不能只改一个状态或提交普通刷新。

#### 1. 状态与可见性

- 自动事实/投影执行必须有有效、可见的 FACT_REFRESH 运行；状态接口同时给出活动任务、等待原因、耗时/进度和人工待处理总数。待处理列表不受最近 100 条日志限制，按配置和来源分页；缺失父运行的事实任务也能直接按 taskId 看见，不伪造历史运行。
- 兜底 worker 停止认领非活动父运行下的 MIRROR_SYNC 任务；短巡检把未完成遗留项转为人工待处理。事实任务用 PAUSED + REQUIRES_DECISION，并保留持有根；投影用 FAILED + REQUIRES_DECISION。把人工 PAUSED 加入服务常量、查询、汇总和 UI，不能当成自动等待。
- `sync_runs.PAUSED` 仍是 run_after 到期可自动派发的运行状态；人工停放用任务的 manual_disposition，不能直接借该运行状态表达人工暂停。只剩人工待处理的历史运行保持原终态；新自动运行遇到人工保留根/全量意图时只处理允许的工作，必要时 PARTIAL_SUCCESS 并说明人工等待，不能每 5 秒永久重派。
- 人工停放限制在原 source/factType/root 集合；FULL 停放覆盖其原事实族全量意图，不无差别冻结其他来源/事实族。持有根不被自动重新分配；显式全量提交与已有同族停放冲突时提示先处置，不偷偷覆盖它。其他正常增量、来源与活动父运行的重试继续工作。
- 已有 runGuarded 的合法同步 MANUAL 调用仍遵守事实锁/任务 owner，并进入独立任务可见视图；不能仅因 run_id 是 UUID 或没有 FACT_REFRESH 父运行就终止它。新的自动路径不得利用该例外绕开运行归属。

#### 2. 撤销活动执行权与取消运行

1. FACT_REFRESH 取消覆盖事实和投影，先进入 CANCELLING、停止新领取；判定活体同时检查两类任务，不能只看 sync_run_table_tasks。
2. 续租、每个事实 upsert/清理批次及最终发布、投影最终快照写入/finish 都校验父运行 token、租约、cancel_requested 与任务 token/租约。取消请求提交后不再授权新批次；已经进入临界区的事务按锁协议串行完成或回滚。
3. 所有发布/撤销路径统一为父运行 → 按固定类型/主键排序的任务 → 版本头/generation。提交屏障对运行/任务采用 `FOR KEY SHARE` 并验证有效执行权；续租只更新非键租约字段，避免长清理事务阻塞合法心跳。撤销/恢复必须显式取得 `FOR UPDATE` 再改变 owner/状态，与在途提交串行；普通 UPDATE 的隐式非键锁不能代替撤销屏障。取消申请可先设置 cancel_requested/CANCELLING 让界面可见，正式撤销仍经过屏障。现有先锁 generation 后锁 task 的 guard 及其他写入/回收路径须一起调整并实测锁图，不能只给单一路径加父锁。长查询/清理的独立运行与任务心跳要复用现有执行资源、不依赖某个批次完成才续租；取消后停止新批次，收尾阶段按实际状态续期，不授予新写权限。
4. 长来源查询或清理未到安全边界时页面继续显示 CANCELLING 与阶段；只有执行者确认停止或执行权已经通过提交屏障可靠撤销后，运行才能终态。旧计算可能在内存/查询中收尾，但不得继续续租或写入，且视图说明正在收尾，不能提前显示“无进程”。
5. 已提交的全量 upsert 不回退为旧事实，但不推进未完整构建的 FULL_EPOCH/已发布版本。页面只读已保存完整产出；后续继续按原 FULL 意图幂等重做。失租/重启适用相同屏障。

#### 3. “继续”的原子协议

- 命令必须显式选择 kind + taskId + expectedTaskRunId（界面所见任务原 run_id/fact_run_id 的字符串），校验 config/source、权限、人工待处理或可人工重试的终态 FAILED，且执行权已撤销。原归属已变化则返回既有接管结果/明确冲突，不再次启动；这也防止投影原行后来再次失败时，旧点击误触发第二次继续。复用同来源互斥域；有正常活动运行时返回明确冲突，不启动重叠执行。
- 创建可见的新 FACT_REFRESH，payload 明确选中任务和恢复模式；executor 只处理这些选中意图，不顺便执行整个来源的其他待发布根或重新调用全部事实族 full enqueue。人为选择恢复不等于普通页面刷新。
- **事实任务**：在事务内锁旧任务与其根/来源意图，建立同来源、事实族、FULL/TARGETED 的新 QUEUED 任务；旧任务转 SKIPPED + RESUMED，保留错误/时间/原运行；新任务 `resumed_from_task_id` 指向旧任务。TARGETED 根关联先从旧任务移走再归新任务，全部在同一事务提交，根数量与集合不变，外部不观察到可被抢领的中间空档。FULL 保留原全量标志、scope、冻结上界策略，不能降成增量。
- 新建 TARGETED 任务在现有 payload_json 保存有界 rootIds 意图，暂停旧任务前也可从持有根补存；发布/是否未发布的权威仍是 roots/heads，不把 payload 升为第二套控制面。历史 FAILED 若已释放根且未保存 rootIds，默认 ORIGINAL 模式明确拒绝原意图恢复；界面可显式选择 CURRENT_PENDING（当前待发布根刷新）或 FULL（该事实族全量重建），不可静默扩大范围。已被其他有效任务拥有的根不能强抢。
- **投影任务**：保留原 fact_build_task_id、scope 与 targetGeneration，复用同一任务行，把 fact_run_id 原子移交新运行并回 QUEUED，清旧执行权、初始化新一轮预算。移交前在旧运行的 sync_run_events 中严格写入不可变结构化诊断（完整原错误/时间/预算/旧新运行/taskId），并在新运行记录接管；事件与移交同事务，写入失败则全部回滚，不能使用吞异常的 best-effort recorder。事件仅保存审计，不用于是否可领取/发布的判定。
- 投影不能简单克隆一行：当前唯一键是 `(fact_build_task_id, scope_type, scope_key)`，新父运行也无法避开它。本设计采用原行移交，保留唯一键与现有 ON CONFLICT 身份，更新所有按 fact_run_id 的查询/汇总并按 F9 读取旧诊断。纯投影恢复不重复事实构建。
- 重复/并发继续通过锁、expectedTaskRunId 与旧处置状态保证一次有效接管；已 RESUMED 的事实任务返回既有新任务/运行，投影由原行当前归属及严格接管事件返回既有结果。接管事件可用于查审计/重复命令结果，不用于领取或发布资格。新任务 retry_count 重置为新的一次人工执行预算，旧失败预算保留在旧事实行/投影诊断事件，不覆盖历史。

#### 4. “取消待处理项”的语义

- 明确取消的是**本次执行意图**。事实任务 SKIPPED + CANCELLED，投影 FAILED + CANCELLED；清执行权并释放该任务根归属，不删除版本头，不推进 published_version，不把投影标 SUCCESS。
- `manual_disposition=CANCELLED` 区分人为取消与真实故障，监控待处理数可减少，来源仍真实报告未发布/陈旧；显示“已取消本次任务，仍有待更新数据”。发布栅栏/刷新进度给取消/失败原因，不继续误报 RUNNING，也不误报最新。
- 取消不永久禁止该来源以后刷新。明确的后续刷新或新镜像变化可由正常新运行重新消费未发布版本；当前取消/巡检流程本身不得立即创建替代运行重新执行。若用户需要长期禁止该项自动处理，应保留人工停放，不用“取消本次”暗示永久冻结。
- 多项处置各自校验来源/类型、锁与权限；没有未完成任务的终态运行不制造待处理项。待处理原因放在 message/持久处置字段，已有 error_message 原文保留，不能被新说明覆盖。

验收：TARGETED 持根继续、FULL 继续、纯投影继续、重复/并发点击、无父记录、UUID 手工路径、跨来源拒绝；构建/清理/投影中取消、租约撤销、重启、父行/任务行/版本锁并发；根唯一归属与数量守恒；取消后未发布版本不变且不报最新；人工停放不静默执行也不冻住无关任务。

### F7 实施与交付顺序

> **【审查修订 R1–R12｜2026-09-30】** 原稿把 F2/F9/F10 与 F3/F6 视为无依赖，实施顺序调整如下。

1. **状态与数据协议**：实现 F2/F10 的处置字段、人工状态、父子执行权和运行汇总；补必要 Flyway 迁移及旧任务识别。先建立取消/失租提交屏障，才能启用新的接管与互斥放行。
2. **控制面闭环**：F1 正常等待、F5 回收、F10 人工继续/取消、F2 投影处置；保持正常活动任务的自动路径。状态查询与四类诊断 F9 同步完成，恢复中的工作必须可见。
3. **读与交互**：F3 快照回退后完成 F6 唯一后台刷新；F8 模块滚动与 F9 完整明细入口一起浏览器验收。F4 可并行实施，但合入时必须接入 F10 提交屏障。
4. **实施验证**：定向回归、真实 PostgreSQL 所有权/取消/投影并发测试、前端类型与交互测试、完整快速套件；F4 固定负载与内网容量结论独立记录，未实测不宣称性能达标。
5. **发布门禁**：实际实施并准备打包时运行黄金基线 compare；展示有意产出差异，按仓库规则处理确认与重建。文档提交本身不跑全链路，也不重建快照。
6. **部署与 P1 恢复**：绑定现场实际应用/数据库基线，备份并验证新增迁移与旧任务待处理数量，沿用保数据更新包流程；部署后在来源就绪时明确提交 issue 全量重建，核实未知数与需求指标。不清库、不把 is_customer_requirement 的 NULL 默认改 false、不改 BI 公式。

## 决策记录

- DEC-A：保留“已有完整发布点可服务 + 新鲜度提示”的目标；【审查修订 R7】只放开已保存完整产出的读取，不无区分放开实时计算。当前事实、下钻和导出保持同版边界。
- DEC-B：**本次修订建议采用人工显式处置**，与 DEC-G 一致；【审查修订 R5】删除取消后静默收割投影。正常父运行下的投影自动执行保留。原文此前的“待裁定/已否决终态化/待定无”互相矛盾，不构成用户批准；本稿记录统一建议。
- DEC-C：【审查修订 R12】保留既有 GIN/trgm 等功能依赖索引，单个清理事务内分批语句；不再宣称缩小整体锁/事务范围或必然消除所有负载的超时。
- DEC-D：保留秒级按钮返回、10 秒交互窗口和 15 分钟兜底；【审查修订 R11】新增明确后台跟踪生命周期与全部调用者处理，不增加可配置等待开关。
- DEC-E：保留统一平台横条样式与模块内停靠；【审查修订 R10】使用局部坐标与专门定位参数，其他共享表行为不变。
- DEC-F：保留基础档“本次记录的原文与事件”，不做跨运行归因、逐次尝试留痕或自动总结；【审查修订 R4/R8】四类任务都在范围内，摘要上限不阻止查全。沿现有 status 路由扩展详情查询，不新增日志查询路由；人工命令单独明确契约。
- DEC-G：保留可见、由人继续或取消的方向；【审查修订 R1/R2/R3】增加原子归属转移、原 FULL 意图、执行者停止屏障及真实未发布状态。
- 明确否决：无父身份静默执行；只改页面状态冒充停止；删除/推进版本头冒充追平；普通增量刷新代替未完成 FULL；started_at 推断连续等待；合法 RETRYING 空租约判 TIMEOUT；实时门控全面放宽；超额错误只给总数；不经测量删功能索引。
- 待验证而非已裁定：F4 内网容量、P6 那次现场运行的精确身份、无历史完整产出的实时页面能否增加同版产出能力；这些不阻塞本轮文档修订，不得在实施交付时冒充已验证。

## 接口契约

> **【审查修订 R1–R12｜2026-09-30】** 以下均为目标契约，尚未落地；实现、调用者、正文与验收必须一致。

### 执行与处置

- `deferOwnedTask(task, deferSeconds)`：deferSeconds > 0，要求当前有效执行权，成功返回 1；QUEUED、预算不变、未来 run_after、清租约，保留错误原文并写等待原因。失去执行权返回 0/明确失租结果，不用 30 分钟计时判失败。
- 删除拟议 `claimOrphanedNext`；终态父运行的自动认领不再存在。巡检返回新待处理数量，长工作只由正常运行执行器承载。
- `resolvePendingTask(configId, kind, taskId, expectedTaskRunId, action, resumeMode)`：kind 为 FACT_BUILD/PROJECTION，action 为 RESUME/CANCEL；resumeMode 默认为 ORIGINAL，仅事实继续时可显式选择 CURRENT_PENDING/FULL，投影保持原 scope/generation。返回 taskId、originalRunId（可空）、newRunId（继续时）、disposition、remainingPendingUpdates。来源校验、原归属校验、一次接管与提交屏障是服务端契约。
- 状态汇总区分 active、dependencyWaiting、retryWaiting、manualAttention、failed、cancelled；planned/completed 不把人工停止算成成功。pendingUpdates 仍来自版本头，不能以 manualAttention=0 推导“已是最新”。

### 持久数据与迁移

- 两类任务新增 `manual_disposition varchar(32) not null default 'NONE'`，约束枚举 NONE/REQUIRES_DECISION/RESUMED/CANCELLED；事实任务增加可空 `resumed_from_task_id` 自引用。事实人工暂停增加服务端 PAUSED 常量，投影沿用现有状态 CHECK，不增加投影 PAUSED/CANCELLED 状态。
- TARGETED 意图使用已有 payload_json 保存有界根集合；根归属仍只由 fact_build_task_roots 维护，发布权威仍为 fact_change_heads。投影移交复用原行，既有唯一键不修改；移交前严格保存旧运行诊断事件快照。
- 必须新增前向 Flyway 迁移，不修改已发布迁移；迁移默认 NONE，不把所有终态或 UUID MANUAL 记录盲目暂停。通过运行/任务状态与执行权核实遗留项，运行时巡检先撤销执行权再收敛；迁移 SQL 不启动工作。
- 必须验证迁移 smoke、约束/默认值、首次巡检、应用回滚与 schema 前向兼容。旧应用不理解人工停放，直接回退会重新启用旧兜底行为，不能仅以启动成功宣称回滚语义安全；发布前给出受控恢复步骤。

### 日志、待处理查询与命令

- `GET /api/gitlab-sync/status?configId=...` 默认日志行提供：`failureCount`、`manualAttentionCount`、`diagnosticCount`、`diagnostics[]`（摘要最多 5 项）、`eventCount/eventTrail[]`（相关事件最多 5 项），及存在时的最新 progress/progressAt。空数组/计数语义统一为无记录；不保留旧失败结构双轨。
- eventTrail 每项提供 eventId、eventType、message、createdAt；接管诊断使用既有 payload_json 保存严格结构化原记录，故障明细依 F9 展示其完整内容，不将所有任意事件 payload 直接混作错误。
- diagnostic：`kind`（TABLE_TASK/AUTHORITATIVE_SCOPE/FACT_BUILD/PROJECTION）、`taskId`、`status/manualDisposition`、`sourceInstance`、`originalRunId/currentRunId`、`retryCount/maxRetryCount`、`rawError/dispositionReason`、`startedAt/finishedAt/errorObservedAt/elapsedMs/observedAt`；类型专属字段按 F9 提供，包含 scopeSignature、scopeType/scopeKey、targetGeneration、factType/fullBuild、affectedRows、heartbeat/lease/progress 时间。无法从库中证明的值为空。
- 可选 `detailsRunId`（sync_runs 数据库 ID）、`detailsSection=DIAGNOSTICS|EVENTS`、`detailsOffset`、`detailsLimit`：limit 默认 20、范围 1–100，offset 非负；响应 details 含 runId、total、items、offset、limit、hasMore。诊断稳定按 kind/taskId/记录 ID 排序，事件按 createdAt/id；轮询期间计数允许变化，页面重新取第一页。没有有效 config/source 归属或权限则拒绝。
- 可选 `pendingOffset/pendingLimit` 按同配置来源查人工待处理列表，分页约束与 details 相同，包含无父运行事实任务并给 expectedTaskRunId；不依赖 detailsRunId 或最近 100 条。来源身份无法证明的历史记录只在有管理权限的独立任务视图披露，不能硬归当前配置。
- 人工命令采用 `POST /api/gitlab-sync/fact-tasks/resolve`，结构化请求 `{configId, kind, taskId, expectedTaskRunId, action, resumeMode}`，action 为 RESUME/CANCEL，resumeMode 见上述约束。现有刷新只按页面/来源提交，现有取消只找当前活动运行，均不能精确处置终态历史 task；因此增加一个明确命令路由，不复用模糊的 config 级取消去误取消别的运行。沿用现有运维权限、CSRF 与同来源互斥，登记黄金端点目录及默认覆盖护栏。
- 不新增日志查询路由；不得用“不新增任何端点”阻断已要求的人工处置能力。普通页面刷新、事实 rebuild、现有 cancel 的契约与调用者保持原用途。

### 陈旧读取与刷新跟踪

- 来源资格携带 `currentFactReadable/savedSnapshotReadable`、`staleReasons[]`、pendingUpdates；统计响应使用真实快照 `dataAsOf/sourceVersion`、`staleReasons[]`。原因码为 DEPENDENCY_SETTLING/FULL_PUBLICATION_IN_FLIGHT/LABEL_HISTORY_VERIFYING/PENDING_TARGETS/MANUAL_ATTENTION_REQUIRED/REFRESH_CANCELLED；多来源去重保存原因，不能依赖 pendingUpdates>0 触发陈旧。
- 页面刷新提交响应增加明确 `submissionOutcome`（ACCEPTED/ALREADY_REFRESHING/COOLDOWN）、跟踪所需 workspace/config/source/运行身份。跟踪结果区分 CONVERGED、PENDING、FAILED、CANCELLED、CLIENT_TIMEOUT、DISPOSED；CLIENT_TIMEOUT 仅客户端，服务端不被改 READY。
- 同步更新所有 API 类型、六个页面入口和状态文案；有意响应变化在实际实施的发布门禁中展示并处理，不能由本轮文档提交改黄金快照。

## 验证证据与风险

> **【审查修订 R1–R12｜2026-09-30】** 区分已证实的旧设计风险与尚待实现的验证。

- **已核实版本**：`73a6869a`。前端 5 文件 31 项、后端 9 类 61 项现有定向测试通过；后端包含 8 项实际 PostgreSQL 投影租约/发布 guard 集成测试。通过说明当前基线可运行，不覆盖本稿新增处置协议，也不能证明没有这些缺陷。
- **测试定位**：前端 `MirrorSyncLogTable`、`useMirrorStatusPresentation`、`useRealtimeWorkspaceStatus`、`useStatisticBoardRefreshController`、`StatisticBoardView.route-refresh` 的 `.test.ts`；后端 `SyncRunLogServiceTest`、`SyncRunLeaseServiceTest`、`SyncRunDispatcherServiceTest`、`SyncRunCancellationServiceTest`、`SyncFactRefreshRunExecutorTest`、`FactRefreshTaskWorkerServiceTest`、`FactBuildTaskServiceTest`、`StatisticBoardSourceQualificationTest`、`FactProjectionTaskLeaseIntegrationTest`。分别用项目 Vitest 与 Maven 定向入口复验；最后一类需要隔离 PostgreSQL。本轮文档未改业务代码，没有无端重跑相同套件。
- **可复现旧设计探针**：隔离 PostgreSQL 临时表、事务末尾 ROLLBACK：① PAUSED 根归属未释放时沿当前领取 SQL 可分配 0，释放后 1；② 删除归属后未发布根仍 1；③ 正常无租约 RETRYING 等待 4 分钟命中旧 F5 超时谓词；④ 首次启动 45 分钟前、刚开始依赖等待的任务命中旧 F1 30 分钟失败谓词。探针没有改生产数据。
- **历史线索边界**：已有任务错误通常保留到最终失败，可直接补取；成功重试已清空的中间异常、未落库堆栈、旧版无任务行的运行无法凭空恢复。新投影移交必须严格保存原诊断，不能因移交把历史错误抹掉。
- **P6 现场待核实**：收集 config/source、sync_runs、fact_build_tasks、projection tasks 的 ID/状态/token/lease/进度，以及进程/SQL 耗时；拆分构建、清理、投影三段。可见性修复不等于把一小时构建优化到秒级，性能另按真实负载证据处理。
- **并发实施风险**：新增父运行 fencing 必须同时调整取消、心跳、回收、事实分批事务、投影 guard 与 finish 的锁顺序；只改续租或只在事务外检查无法阻止陈旧提交。必须实际 PostgreSQL 并发验证，不能只用 Mockito 的调用次数证明安全。
- **人工停放边界**：只停原范围；重试预算耗尽的正常 FAILED 保留真实结果，不因历史存在失败记录就冻结整来源。对遗留根集合已经丢失的 FAILED 不承诺精确重放，需明确用户选择当前待发布或全量意图。
- **维护期实时页面边界**：本单元保证保存快照的视图持续显示完整旧数据；实时 BI/筛选/明细/导出若没有同版历史产出，仍明确不可用。产品要求的所有路径无缝服务上一版尚需额外产出能力，不能以门控放宽掩盖差距。
- **性能证据边界**：F4 的临时物化与分批删除都可能超时；现有索引和单事务锁成本不消失。内网查询超时值与真实负载未知，不能预先承诺保持任意配置必成功。浏览器滚动验收与 F4 基准尚未执行。
- **升级可控性**：首次巡检可能出现待处理项，README 需说明数量、范围、继续/取消含义及回滚限制。先建立停止/撤销屏障，再运行遗留收敛；不能在旧 worker 仍能续租时直接批量改 PAUSED。不清理任务/栅栏表、不手工结算版本。
- **发布验证**：本轮是文档修订，未改变应用、数据库或黄金快照。实际业务实施后必须覆盖上述验收并按 F7 发布门禁验证；文档获审阅不等于实现与部署已经安全。
