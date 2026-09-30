# 数据同步模块根治方案：待发布权威单一化 + 字段级血缘（2026-09-30）

> 用户指令："我不要止血，打补丁的修复。我要的是能够彻底的完美的修复数据同步模块问题的方案……一针见血、不堆屎山的完美修复方案。"本计划是该工作单元的唯一实施与恢复入口，待用户批准后实施。

## 进度与中间物

- 状态：**待用户审批**（用户 2026-09-30 指派本 AI 实施）。S1–S5 已全部实施，S6 本地验证完成；黄金基线 compare、内网升级与 S0 基准复测按发布流程待授权。
- 已完成变更清单（按 S 段）：
  - **S1 字段级血缘**：`GitlabSourceLineageCatalog.SourceDefinition` 增 `factRelevantColumns`（5 张维表显式声明；构造期校验维表不得为空且含主键、非维表不得声明）＋ `changeAffectsFactRoots`；`GitlabFactChangeResolver` 按变化列求交（删除/恢复恒相关）。测试 `GitlabFactChangeResolverRelevantColumnsTest`、`GitlabSourceLineageCatalogTest`。
  - **S2 控制面单一权威化**：迁移 `V20260930_01__fact_publication_authoritative_heads.sql`（heads 待发布部分索引 + `project_id` 列 + `fact_build_task_roots`，外键 `on delete cascade`）；`FactBuildTaskService.assignPendingSourceTargetBatches` 改从 heads 领取并写 `fact_build_task_roots`，领取排除在途领取；`FactTargetPublicationService`（`lockTaskHeads` 集合锁、`publishHead`、`publishFull` 冻结覆盖版本后结算）；`SyncFactPublicationStateService`（`countUnpublishedTargets`／`isReady`／`hasUnpublishedTargets`／`publicationUpperBound`／`settleAfterFullPublication`）；`SyncRunPublicationFenceService` 三处改读 heads；`SyncRunFactPublicationCoordinator` 删除失败释放调用；`FactChangeTargetService` 日志只插入、不再写状态列。
  - **S3 单批成本**：`GitlabFactSourceSqlProvider` 单模板 + `target_roots` CTE 前置收窄（全量变体输出历史文本不变）；测试 `GitlabFactIssueSourceRootScopeEquivalenceTest` 逐列对拍；`FactTargetPublicationServiceIntegrationTest.test_concurrent_publishers_on_same_root_one_advances_and_one_yields`（审批期间按计划 S3.3 补交）：两个并发发布者竞争同批根，根行集合锁让先到者推进、后到者按"已由当前版本覆盖"幂等完成，无死锁。
  - **S4 门控降级与前端指引**：`SourceQualification` 三态（`healthy`／`degraded(n)`／`refused(reason)`）；`StatisticBoardResponse` 增 `dataAsOf`／`pendingUpdates`（`NON_NULL`，"两者同在或同缺"＋正数不变量）；`StatisticBoardSnapshotService.readOrRefresh` 降级取上一完整发布点、不写快照、无完整发布点则拒绝；前端新增 `StatisticBoardFreshnessBanner` 与工具栏降级文案、FAILED 指引、轮询继续。测试 `StatisticBoardSourceQualificationTest`、`SourceFreshnessJsonContractTest`、前端横幅/工具栏 11 项。
  - **S5 审计日志归档**：`FactTargetJournalArchiveService`（CTE 分页删除，判据 = 超保留窗口 ∧ 根版本栅栏已发布到不低于该行版本，与 `publication_status` 无关）＋ `FactTargetJournalArchiveScheduler`（受 `platform.background-jobs.enabled` 约束）＋ 三个配置项。测试 `FactTargetJournalArchiveServiceIntegrationTest`。
- 实施期方案修订（已并入 `docs/decisions.md` D-24）：① 领取查询必须排除在途任务已领取的根（`fact_build_task_roots` 即领取记录），否则同一批根会在单轮内被重复派发到每轮上限并在来源无待发布工作后把运行停在 `RETRYING`；② 全量发布改为构建前冻结覆盖版本、构建后按该上界结算（原实现构建完成后才取上界，手工全量重建永不收敛）；③ 计划要求删除的 `lockPendingRootIds`／`loadAssignedRootIds` 保留名称但实现改为 heads／根批次表，targets 版本整段删除，无同义双轨；④ S5 归档判据不采用原方案的 `publication_status='PUBLISHED'` 条件——日志降级后没有代码再把它从 `PENDING` 改写，以它为条件会让新写入的行永不可归档，实际判据是「超过保留窗口 ∧ 该根版本栅栏已发布到不低于该行登记版本」；⑤ S4 在"降级但该视图从未有过完整发布点"时按方案 S4.2 拒绝并说明，不返回进行中的混合视图。
- 测试与门禁状态：见 `docs/progress.md` 本单元条目（全量快速套件、五项仓库门禁、定向 RED→GREEN、本地性能冒烟）。
- 当前阻塞：无。黄金基线 compare、内网升级与 S0 基准复测待用户授权。
- 恢复线索：本工作单元已实施完毕，等待用户审批；审批通过后按 AGENTS.md 删除本计划（长期内容已归入 `docs/decisions.md` D-24 与 `docs/architecture.md`"事实与统计"）。

## 目标与边界

### 用户原始需求（三条不可接受的表现）

1. 事实层刷新极慢：一轮增量从历史的 5-10 分钟退化到 20-30 分钟乃至小时级。
2. 200 行真实变动被处理成 3.4 万级目标量（内网实测 INTEGRATION_TEST 34,209 待处理、门控报 6,009 未发布）。
3. 上版本（0929 包）加入的新门控导致刷新期间统计页面**全空**，只给提示、不给上一个可用快照。

### 可验证的成功标准

1. **目标量回归真实变动量**：构造"维表（projects/users/labels）非事实字段变化 1 行 + 业务表变化 N 行"的固定负载，登记的事实目标数 = 真实影响根数（量级验证，非绝对相等）；行为测试锁定"非相关字段变化不产生目标、相关字段/主键/关联键/删除恢复仍产生目标"。
2. **控制面查询与表体积解耦**：待发布计数、领取、收敛判定全部走 `fact_change_heads`（行数 = 根数×事实族，有界）+ 部分索引；`sync_run_fact_targets` 从控制面退役后按版本栅栏安全归档，26 GB 单调增长被结构性消除。
3. **单批成本下降**：定向事实 SQL 以 `target_roots` CTE 前置；版本头集合锁一次 `for update`；以固定负载基准记录每批耗时（本地正确性 + 性能冒烟，容量结论留内网实测）。
4. **刷新期间页面不再全空**：门控③（仍有未发布目标）降级为"返回上一可用快照 + 顶部醒目提示（数据截至时刻 + 待更新数）"；门控①②④（完整性未知）保持拒绝。失败运行后页面继续给出可执行指引，不再静默停摆。
5. 全量套件、五项仓库门禁、黄金基线 compare **零差异**（门禁内来源全部收敛，降级路径不被快照捕获，预期无快照变化；若出现差异按无意差异修实现）。

### 明确禁止（红线）

- **禁止双轨**：targets 上的控制面查询（领取/归属/结算/释放/收敛计数）全部删除，不保留"新 heads 路径 + 旧 targets 路径"并存；开发期演进红线（AGENTS.md）适用。
- 禁止清空 outbox、手工标记 PUBLISHED、无基准地调大批次或 worker 数（D-13 边界原文有效）。
- 不改 ISSUE/MR 事实正确性、乱序 fencing、空来源删除、来源级 READY 门禁四条不变量（D-13 原文）。
- 未获用户确认不触碰黄金基线快照；不推送任何远端；内网生产库一切操作先经只读诊断、DDL 经升级包发布流程。

## 约束与背景

- 权威归因：`docs/decisions.md` D-13（版本归因与边界）、D-14（写回就绪=来源级发布收敛）、D-16（异步执行器与调度线程池分离）。本计划实施后须新增 D-19 记录权威模型单一化决策。
- 门禁版本归属已查实：读时门控 `qualification` 由 `b3dd8e2a`（客户维度统计 S11）引入并随 0929 更新包上线；旧 0910 包无此门控——用户"上个版本加入了新逻辑"的观察准确。
- 运行互斥约束（保持不变）：同一来源全部运行类型共用一个互斥域（`SyncRunPolicyService.exclusiveScopeOf:59-69`），事实刷新优先级最低且不可让行（`:44-57`、`SyncRunYieldService`）。本方案不改变互斥模型——单消费者前提正是"领取状态可以不落库"的依据（见方案核心）。
- 环境约束：本地无法复现内网 26 GB/5,800 万行容量（AGENTS.md：容量结论必须内网实测）；本地只做正确性验证与性能冒烟。
- 黄金基线运行契约：`backend/` 下 `../tools/maven/apache-maven-3.9.9/bin/mvn.cmd -o -B test -Pgolden-baseline -Dtest=GoldenBaselineChainTest`；运行前后停/起 18080/18181。

## 证据与根因（本地代码逐条取证）

### 链一：200 行变动 → 3.4 万目标（血缘爆炸）

- `GitlabFactChangeResolver.java:44-65`：镜像行变化→根目标的展开是**全表反查**，无字段级筛选——`projects` 1 行变化展开该项目全部 Issue+MR（`:164-169`）；`users` 按全部角色关联展开（`:196-239`）；`labels`/`milestones`/`namespaces` 同理。
- `:100-108`：每个 Issue 根同时登记 **ISSUE 与 INTEGRATION_TEST 两个事实族**目标——内网 34,209 ≈ 受影响项目 issue 总数 × 双登记，与 D-13 记录的 9/14 洪峰 33,269 同源。
- `GitlabSourceLineageCatalog.java:22-51`：血缘目录只有表级 `factConsumers`，**没有列级声明**——这是爆炸比不受控的目录层根因。
- 镜像层全列差异判定（`GitlabMirrorTableStorageService.buildConflictGuard` 全列 `is distinct from`）使维表任何列变化都产生 `MirrorRowChange`，交给 resolver 后一律全量展开。

### 链二：26 GB 单调增长（按轮累积的日志被当成了控制面）

- `V20260731_01__…sql:59-84`：`sync_run_fact_targets` 主键 `(mirror_run_id, source_instance, fact_type, root_id)`——**每轮镜像各插一套，从无清理**；内网 5,800 万行、99.9% 为已发布历史行。
- 控制面全部压在这张日志表上：待发布计数 `countUnpublishedTargets`（`SyncFactPublicationStateService.java:253-269`，刻意不过滤状态，Javadoc 称版本栅栏为权威口径）、领取 `lockPendingRootIds`（`FactBuildTaskService.java:217-243`）、归属状态机 QUEUED/PENDING/assigned_* 列、结算 `settleAfterFullPublication`（`:209-240`）、失败释放 `releaseFailedFactAssignments`（`:187-205`）。
- 后果一：部分索引 `idx_sync_run_fact_targets_pending_fence`（`V20260731_04`，`where publication_status <> 'PUBLISHED'`）对不过滤状态的 `countUnpublishedTargets` **不可用**——每次页面读取/每轮镜像终态在 26 GB 上扫 1-2 秒（内网实测恰好压在慢日志阈值下）。
- 后果二：设计缺陷的本质——**权威待发布信号其实早就存在**：`fact_change_heads`（每根每事实族一行，有界，`latest_change_version`/`published_version`，`FactChangeTargetService.registerBatch:109-123` 以 `greatest()` 单调推进）。"published < latest" 这一谓词**根本不需要 targets 表**；把按轮累积的日志表提升为控制面，是一切容量问题的单一设计根因。

### 链三：清积压 30-60 分钟（每批成本 × 串行批数）

- `SyncFactRefreshRunExecutor.drainFactTasks:112-130`：运行内串行领批执行（这是对的，单消费者模型）；每批 200 根（`GitlabMirrorProperties:42`）。
- 每批成本三重放大（D-13 已录）：① 根 ID 追加在完整 Issue/MR CTE **末尾** `IN (...)`（`GitlabFactSourceSqlProvider`，labels/notes/assignees 聚合无法从根集合提前过滤）；② `FactTargetPublicationService.lockCurrentHeads:208-241` **逐根** `for update`，每批 200 次锁往返，4 万根 ≈ 4 万次；③ `ranked_diffs` 窗口排序（`:393` 起，2026-08-10 `c367258a` 引入）随 MR 批次重跑。
- 兜底 worker `FactRefreshTaskWorkerService.runOnce:50-60` 每 5 秒领 1 个任务——失败运行遗留任务按每 tick 1 个的速度消耗，且投影任务因父运行终态被 `FactProjectionTaskService.claimNext:52-58` 的 RUNNING+租约条件永久拒领；0929 包的 `FactProjectionTaskRecoveryScheduler` 只把失租任务收敛为 FAILED 终态、**不重新执行**。

### 链四：页面全空（门控全有全无、无回退路径）

- `StatisticBoardSnapshotService.requireReadableSources:162-171`：每次统计板读取执行 `qualification`，命中任一门控直接抛 `BizException`——代码中**不存在**回退旧快照的分支。
- 门控四条（`SyncFactPublicationStateService.java:139-168`）：①有投影无代际 ②全量重建未结算 ③仍有 N 目标未发布 ④标签事件未全量核验。
- 拒绝窗口长度完全取决于链一/二/三的清理速度——三条链互相放大，"完整性保护窗口"退化为"页面长时间全空"。
- 快照缓存按 `(board, scope, ruleVersion, sourceVersion, filterHash, status='READY')` 存取（`findReady:67-90`），**回退上一可用快照结构上现成可用**。
- 前端：`StatisticBoardToolbar.vue:113` `activeStatuses` 不含 FAILED → 停止轮询，`:133-134` 只显示"已展示当前可用数据"，无下一步指引。

### 内网 AI 报告逐条裁定（参考件，不作结论）

| 内网 AI 结论 | 裁定 |
| --- | --- |
| 等整轮完成即恢复 | 对，但仅等待，不解决复发 |
| 门控 exists/count 在 26 GB 表上扫描 | 方向对、细节不准：不是"每批一次"，是每次页面读取+每轮镜像终态；根因是计数不过滤状态、部分索引不可用（链二） |
| 小变动→整项目重标，需归档/清理 | 对：分别是链一（字段血缘）与链二（归档） |
| 展示"完整快照+提示"替代空白 | 对，即门控③降级；须显式提示、不得静默冒充最新 |

## 方案与步骤

### 方案核心：一个权威模型，不是四个补丁

四条根因链收敛为一个设计错误：**按轮累积的审计日志被当成了控制面**。根治 = 把控制面还给唯一有界的权威 `fact_change_heads`，血缘在目录层声明到字段，读取在门控层区分"未知完整性"与"已知暂未收敛"。

由此结构性消除（而非缓解）：

- 26 GB 增长：控制面不再读 targets，targets 降级为纯审计日志并按版本栅栏归档（S5）。
- 门控慢查询：`published < latest` 谓词走 heads 部分索引（行数 = 未发布根数，稳态≈0）。
- 失败孤儿：**领取状态不再落库**——同来源单 FACT_REFRESH 消费者的运行租约就是领取（互斥域已保证唯一消费者，`SyncRunPolicyService:59-69`）；失败运行丢租约后，下一轮自然接管全部 `published < latest` 的根。`releaseFailedFactAssignments`、QUEUED/PENDING 归属状态机、assigned_* 列随之**整段删除**，问题 B（失败后无人自救）在结构上不再存在。
- 目标爆炸：字段级血缘（S1）让维表的非事实列变化不再展开。

### S0 内网基准采集（实施前，用户执行）

`scripts/diagnose-fact-refresh-backlog.sql` 全六节输出存档为 before 基线（目标量、每批耗时、门控计数耗时、表体积）；实施后同脚本复测对照。D-13"先基准后修复"约束由此满足。

### S1 字段级血缘（链一根因，目录层）

1. `GitlabSourceLineageCatalog.SourceDefinition` 新增 `factRelevantColumns`（每表显式声明事实消费列集合）；主键、关联键、删除/恢复恒为相关。
2. `GitlabFactChangeResolver.resolve`：`MirrorRowChange` 按变化列与集合求交，非相关列变化的行**不展开根**；维表反查仅在相关列变化时执行。
3. 列集合来源 = 逐表审计 `GitlabFactSourceSqlProvider` 各事实查询对该表实际消费的列（人工审计 + 行为测试锁定，见验收）。
4. 安全侧不变量：相关列、主键/关联键、删除/恢复变化仍完整产生目标（与原方案 C1 同）。
5. 行为测试（RED→GREEN）：①非相关字段变化 → 0 目标；②相关字段变化 → 目标产生；③删除/恢复 → 目标产生；④双登记（ISSUE+INTEGRATION_TEST）保持。

### S2 控制面单一权威化（链二根因 + 失败孤儿）

1. **Flyway 迁移**：① `fact_change_heads` 部分索引 `on (source_instance, fact_type) where published_version < latest_change_version`（稳态近空）；② 新表 `fact_build_task_roots(task_id, source_instance, fact_type, root_id)` 承载"批次包含哪些根"（随任务重试/终态生命周期，有界）；③ 校验和登记 `scripts/flyway-migration-checksums.json`。
2. **领取重写**：`assignPendingSourceTargetBatches` 改为从 heads 取 `published < latest` 的根（按 root_id 稳定序 limit 批大小），写入 `fact_build_task_roots`；不再更新 targets 的归属/状态列。删除 `lockPendingRootIds`、`loadAssignedRootIds`（targets 版）、`settleCoveredTargets`。
3. **结算重写**：任务发布后直接推进 `head.published_version`（现有 `publishHeadAndCoveredTargets` 保留并改为集合路径）；`settleAfterFullPublication` 同口径收敛 heads + 清 `full_publication_requested`；targets 不再有状态翻转。
4. **门控与收敛计数重写**：`countUnpublishedTargets`、`isReady`、`hasUnpublishedTargets` 全部改读 heads 谓词（`SyncFactRefreshRunExecutor:93` 的 PAUSED 判定、`CustomerIssueDelayClosureOrchestrator:133` 写回收敛判据、`FactRefreshTaskWorkerService:68` 就绪检查随之自动统一——D-14 判据口径不变，读法换到有界权威）。
5. **删除失败释放路径**：`releaseFailedFactAssignments` 整段删除及其调用点（`SyncRunFactPublicationCoordinator:49`）；`recoverTimedOutQueuedTasks` 保留（任务级重试仍需要）但不再触碰 targets 归属。
6. **targets 降级**：`FactChangeTargetService.registerBatch` 继续按轮写日志（审计归因：哪轮镜像登记了哪个根），但**只插入、不更新状态**；控制面零引用。
7. 集成测试：批次领取→发布→heads 推进→收敛为 0；运行中途失败→新运行直接接管全部 pending 根（孤儿场景的正向测试）；全量构建结算路径。
8. **【实施期发现的方案修订 · 围栏依赖】** 实现前核对发现 `SyncRunPublicationFenceService` 亦有三处读 targets（水位 `max(target.change_version)` :187-198、项目维度待发布判定 :242-264、已发布→投影任务归属联接 :266-320），而 heads 缺 `project_id`。按"单一权威"同一原则一并迁移：① 迁移为 `fact_change_heads` 增 `project_id`（`registerBatch` 以 `coalesce` 写入，避免置空）；② 水位改读 `max(latest_change_version)` 并按 `project_id` 过滤；③ 待发布判定改为 `exists head where published_version < least(latest_change_version, watermark)`（语义等价：`published_version` 表示该根截至该版本的变化均已发布）；④ 归属联接改读 `head.published_by_fact_build_task_id` + `head.published_version <= watermark`。修订后主代码对 targets 的引用仅剩"注册写入"与归档，符合方案原意。
9. 围栏迁移的等价性测试：同构场景下新旧判定结果一致（含项目维度选择器、投影任务未终态、水位边界），以及"已发布但版本脱节"的行仍计为待发布（版本栅栏权威口径不因迁移放宽）。

### S3 单批成本（链三，C2+C3）

1. `GitlabFactSourceSqlProvider`：定向查询把根集合改为开头 `target_roots` CTE（`values` 或数组），labels/notes/assignees/diff 聚合从根集合起过滤；`ranked_diffs` 窗口查询同口径。
2. `FactTargetPublicationService.lockCurrentHeads`：逐根 `for update` 循环改为一次集合 `for update`（`where (source_instance, fact_type, root_id) in (select …)` + 稳定排序），保留版本围栏语义（乱序 fencing 不变量不变）。
3. 行为测试：同根集合下新旧 SQL 产出逐列一致（用固定夹具对拍）；锁并发测试（两消费者竞争同批，一个推进、一个让位）。
4. 批大小维持 200、worker 形态不动——参数调整留待内网基准数据（D-13 边界）。

### S4 门控降级与前端指引（链四 + 问题 B 的页面侧）

1. `qualification` 拆分语义：门控③改为返回 `degraded(reason, unpublishedCount)`；①②④维持 `refuse(reason)`。依据：③时上次发布是**整体收敛点**（每根发布原子、heads 有界推进），"上一可用快照 + 明示截至时刻"严格优于旧版（旧版直接返回混合进行中的数据）。
2. `requireReadableSources` 对 `degraded`：按当前请求键取最近一个 `READY` 快照返回，响应附 `dataAsOf`/`pendingUpdates` 提示字段（接口契约见下节）；无可用快照（首次接入）时仍拒绝并说明。
3. 前端：工具栏对降级态显示"数据截至 X 时刻，仍有 N 项待更新"横幅（warning 色），**轮询继续**；FAILED 终态显示"本轮同步失败，建议在镜像设置页重新触发刷新"的可执行指引（替换现行"已展示当前可用数据"孤立文案）。`activeStatuses` 增补降级态。
4. 测试：门控③下命中旧快照+横幅字段断言；①②④仍拒绝；前端组件测试锁定横幅与轮询行为。

### S5 审计日志归档（收尾，防止 targets 复发膨胀）

1. 新增低优先级归档 job（复用 `platform.background-jobs.enabled` 总开关 + 备份围栏同款运行门禁思路，避开重量级运行并发窗口）：删除 `publication_status='PUBLISHED'` 且 `head.published_version >= change_version` 的 targets 行——join 条件保证删掉的恰是版本栅栏永远数不到的行，语义零变化。
2. 批量、限速、分页提交（参照 `delete-reconciliation-page-size` 形态），首批上线后把 26 GB 收敛到日志窗口内常驻量。
3. 集成测试：归档后 `countUnpublishedTargets`（heads 版）不变、未发布切片完整保留。

### S6 回归与验收

1. 定向单测/集成（S1-S5 各自 RED→GREEN）。
2. 全量快速套件 + 五项仓库门禁。
3. 黄金基线 compare（发布门禁时机或用户指令）：预期零差异；出现差异按无意差异修实现。
4. 性能冒烟（本地）：固定负载（如 5 万根夹具）下 heads 门控计数毫秒级、单批 SQL 前后耗时对照记录；**容量结论标注"待内网 S0 同脚本复测"**。
5. 文档收口：`docs/decisions.md` 新增 D-19（权威模型单一化 + 门控降级语义）；`docs/architecture.md` 事实与统计章节同步；`docs/progress.md` 留痕；本计划完成后删除。

### S7 打包与内网验证（另经授权）

含本修复的更新包走标准打包流程；内网升级后执行 S0 同脚本复测，对照基线出容量结论；`docs/progress.md` 记录内网验收数据。

## 决策记录

- **采用"heads 为唯一权威"而非"targets 优化"**：targets 的按轮累积本性意味着任何基于它的索引/查询优化都随时间退化；heads 行数有界且谓词天然可部分索引。这是"一针见血"的那一针。
- **采用"运行租约即领取"而非"归属状态机"**：同来源单 FACT_REFRESH 消费者由互斥域结构性保证（`SyncRunPolicyService:59-69`），把领取状态落库是冗余状态机，且正是失败孤子的来源。删除而非修补。
- **门控③降级而非全放行**：完整性未知的①②④必须继续拒绝（评审整改确立的"不得把未知当可读"原则保持）；③属于"已知暂未收敛"，上一收敛点快照是可证明的整体一致状态。此项为**产品决策项 DEC-1**。
- **字段血缘默认"未声明列不展开"而非"未知列展开"**：与目录"显式穷举、无默认忽略路径"的既有风格一致；安全性由 S1 行为测试 + 列集合审计锁死。若内网实测发现事实漂移，按缺陷处理补声明（宁可显式补，不做隐式全展开）。
- **C4（过载转批量全量发布）延后**：字段血缘上线后目标爆炸源头已断；是否需要受控批量发布由 S0/S6 基准数据决定，本期不引入额外架构复杂度。
- **否决：调大批次/加 worker 并发**（D-13 边界 + 加重 CTE/锁争用）；**否决：手工清理 targets 数据**（升级前由归档 job 按语义安全条件收敛）；**否决：门控加计时缓存**（治标且引入陈旧判定窗口）。
- **待用户裁定的三项决策**（已全部裁定，2026-09-30）：
  - **DEC-1**：门控③降级为"返回上一可用快照 + 顶部横幅提示"——用户原话"必须保留快照，否则非常影响使用"；①②④继续拒绝不变。S4 按此实施。
  - **DEC-2**：targets 审计日志归档保留窗口定为 **30 天**——功能零影响，窗口只决定事后追查深度；本次事故归因与修复观察期未结束，稍长追查深度成本低（表有界、控制面不读它），运行稳定后可随时收紧。
  - **DEC-3**：S1→S2→S3→S4→S5 **一个工作单元连续实施、整批提交**——与用户既有工作方式一致；S2 控制面切换不可拆，任何拆分都会造成中间态双轨。
- **实施分工（用户 2026-09-30 指派）**：实现由其他 AI 员工执行；本 AI 职责 = 本方案权威解释 + 按"明确禁止"与"接口契约"逐项审查交付。实现者交付时须按 S6 提交全量验证证据（测试、门禁、对拍结果），审查按"一票否决红线"执行：出现双轨路径、targets 控制面残留读写、无测试锁定不变量、黄金快照未经确认重建，任一项即退回。

## 接口契约

- 无新增对外 REST 端点。`/api/statistic-boards/*` 与 BI 读接口的响应**新增可选提示字段**：`dataAsOf`（时间戳，仅降级态返回）、`pendingUpdates`（未发布目标数，仅降级态返回）；正常态响应不变。
- 表变更（Flyway）：`fact_change_heads` 新增部分索引 + 新增 `project_id` 列（围栏迁移所需，见 S2.8）；新表 `fact_build_task_roots(task_id bigint references fact_build_tasks(id) on delete cascade, source_instance, fact_type, root_id, primary key(task_id, root_id))`；`sync_run_fact_targets` 结构不动（降级为只插入日志，由归档 job 收敛）。
- 删除的内部符号（实施时全仓搜索确认零引用）：`releaseFailedFactAssignments`、`lockPendingRootIds`、`loadAssignedRootIds`（targets 版）、targets 的 `assigned_fact_run_id`/`assigned_fact_build_task_id`/`publication_status` 控制面读写（列保留在表中但不再被代码引用，归档后随日志窗口消亡——**不另发 drop 迁移**，避免对 26 GB 表做高风险 DDL；这是日志降级的代价，非双轨：控制面读法为零）。
- 不变量（实施后必须有测试锁定）：heads 版本单调不回退（`greatest`）；每根发布原子；乱序 fencing；空来源删除；来源级 READY 门禁；写回收敛判据（D-14）口径不变。

## 风险与假设

- **假设**：同来源 FACT_REFRESH 单消费者在所有现有入口成立（互斥域覆盖手动重建/补偿/页面刷新）。验证方式：S2 集成测试覆盖"运行中再提交"的复用路径；若发现绕过互斥域的入口，停下来先修入口，不引入归属状态机兜底。
- **风险**：字段血缘列集合审计遗漏导致事实漂移。缓解：行为测试逐表锁定 + 内网升级后对照期（升级前后统计板数值 diff 审阅）；发现漂移按缺陷补声明并重放该表目标。
- **风险**：`target_roots` CTE 重写改变执行计划为更差（PG 优化器偶发）。缓解：S3 用固定夹具对拍 + 内网 `EXPLAIN (ANALYZE, BUFFERS)` 复核；保留原 SQL 于测试对拍基准（不入生产路径）。
- **风险**：归档 job 与备份围栏/重量级运行并发。缓解：复用既有运行门禁与批内限速；上线首批在低峰窗口由运维触发首次归档。
- **敏感只读数据**：S0 诊断全部 SELECT；内网生产库不做任何手工 DML/DDL；一切变更只经升级包迁移。
- **黄金基线**：门禁夹具规模小，S2-S4 预期零快照差异；如降级提示字段出现在快照中须逐项定性（新字段属有意变更，须用户确认后以更新模式处理，不得顺手重建）。
