# 同步与读侧六类缺陷的最终修复设计（P1–P6 + 互斥域 + 前端等待）

## 进度与中间物

- 当前阶段：设计审查修订稿，尚未实施业务修复。用户要求复核审查发现、直接修订原文、特殊标注并推送；本轮按最新 `origin/main` 的 `73a6869a` 核实 R1–R12，修正原文中的恢复死锁、取消误报、重试误伤、实时读侧误放行等设计问题。
- 追加范围：P5 最近同步日志与 P6 不可见事实工作仍存在相应代码缺口。P6 是可导致现场现象的路径；缺少 0918 部署现场的数据库、进程与日志证据，不能据此认定那次近一小时运行的唯一成因。
- 已完成文件/变更清单：本计划与 `docs/progress.md` 的进度入口；所有实质修订使用 **【审查修订 R编号｜2026-09-30】** 标记，正文、决策与接口一起替换，不保留冲突方案作为执行指令。
- 测试通过/失败状态：审查基线现有前端 5 文件 / 31 项、后端 9 类 / 61 项通过；4 个隔离 PostgreSQL 临时表探针证实旧方案风险。它们验证现状，不能证明修订方案已实现。工作树产物、运行产物位置、文本空白与 `git diff --check` 四项文档门禁通过；浏览器修复验收、固定负载基准与发布 compare 尚未执行。
- 当前交付：修订与文档校验已完成，按本轮授权提交并推送 `origin/main`。业务实施、迁移、部署与黄金快照重建均不属于本轮文档任务。

### 实施进度（F7 第 1 阶段 a：状态与数据协议与旧任务识别，已完成）

- 已完成：新增前向迁移 `V20260930_02__fact_task_manual_disposition.sql`（`fact_build_tasks.manual_disposition/wait_reason/resumed_from_task_id`、`fact_projection_refresh_tasks.manual_disposition`、两条枚举 CHECK、两条人工待处理部分索引），校验和已登记（138 项，既有迁移零改动）；新增 `FactManualDisposition` 词表枚举（`wait_reason` 的 Java 词表随 F1 加入）；`RunTaskSummary`（事实与投影各一份）扩展出 `dependencyWaiting/manualAttention/cancelled` 并新增 `awaitsManualDecision()`，`hasActiveTasks()` 不再把人工停放算作活动；全部领取、回收、汇总 SQL 增加 `manual_disposition = 'NONE'` 谓词。
- 已完成（旧任务识别 + 停止不可见执行，兼修 P2/P6）：`parkOrphanedTasksForManualDecision()`（事实与投影各一份，单轮 200 条、`FOR UPDATE SKIP LOCKED`）把父运行已终态或缺失的排队/等待重试任务转为人工待处理——事实 `PAUSED + REQUIRES_DECISION` 并保留持有根与原始错误；投影 `FAILED + REQUIRES_DECISION`、`coalesce` 保留原始错误、推进发布栅栏。兜底 worker 删除 `claimNextQueuedTask` 认领路径，只保留「回收 + 巡检」；事实自动执行自此只存在于持有活动 `FACT_REFRESH` 运行的执行器中。运行执行器终态判定改为：失败→`FAILED`、等待重试→`RETRYING`、有活动任务→`PAUSED`（延迟取最早任务 `run_after`）、仅剩人工待处理→`PARTIAL_SUCCESS`（不再因未发布根每 5 秒永久重派）。
- 实现期裁定（需用户确认）：①新增 `wait_reason` 列而不是用「`QUEUED` 且 `run_after` 在未来」隐式推断依赖等待，理由是运行汇总需要稳定的等待原因码，且界面文案不得成为判定依据；该列当前只由 DDL 的枚举 CHECK 与 SQL 字面量表达，Java 词表随 F1 的 `deferOwnedTask` 一起加入；②停放不写运行事件——父运行可能已被清理，向它追加事件会触发 `sync_run_events.run_id` 外键失败并连带中止整个停放事务，处置结论只落在任务行上（F9 从任务行读取）。
- 本节之后仍未完成（已由后续小节补齐）：父子执行权/提交屏障（第 1 阶段 b）、F1 等待分离（第 2 阶段 a）、F5 精确回收、F2/F10 的继续与取消命令、F9 四类诊断与 `/status` 扩展、F3/F6/F8/F4。本轮未跑黄金基线门禁（非发布时机）。
- 验证状态：定向测试 41 项通过（`FactBuildTaskServiceTest`、`FactRefreshTaskWorkerServiceTest`、`SyncFactRefreshRunExecutorTest`、`FactProjectionTaskLeaseIntegrationTest`，含新增的停放/人工待处理/依赖等待用例）；新迁移经测试库 Flyway 真实应用。全套件回归结果见本轮收尾记录。

### 实施进度（F7 第 1 阶段 b：父子执行权与提交屏障，已完成）

- 任务两侧都携带父运行执行令牌：`QueuedFactBuildTask`/`QueuedFactProjectionTask` 新增 `factRunLeaseToken`。事实任务认领（`claimNextQueuedTaskForFactRun`）改为要求父运行仍由该令牌持有、处于 `RUNNING/RETRYING`、`cancel_requested = false`、租约未过期；投影认领原本已要求父运行令牌。
- 新增 `FactTaskExecutionContext`（线程内任务身份）与 `FactTaskExecutionGuard`：在事务内对父运行行与任务行取 `for key share` 并校验两层执行权；心跳等非键列更新与之兼容，撤销执行权必须取 `for update`，因此撤销等待在途批次、在途批次的下一次校验看到撤销结果。
- 屏障落点：`FactPublicationTransaction.execute`（事实批次提交的唯一收口，5 个调用点全部覆盖）、`FactTargetPublicationService.publish`/`publishFull` 的入口与批间进度回调；`FactRefreshTaskWorkerService.execute` 打开任务上下文，并在执行权失效时以 `FactTaskLeaseLostException` 停止且不消耗任务重试预算。手工同步构建没有任务上下文，按计划保持不受限。
- 投影侧按方案统一锁序为「父运行 → generation → 任务」：`FactProjectionPublicationGuardService.writeSnapshot` 增加父运行 `for key share` 校验；`finishOwned`/`renewOwned`/`failOwned` 的谓词增加父运行授权，取消申请后不再续租、不再提交终态。
- 证据：新增 `FactTaskExecutionGuardIntegrationTest` 六项（含真实 PostgreSQL 并发：撤销取 `for update` 时出现在 `pg_stat_activity` 锁等待队列、批次释放后才获锁；以及父运行取消/租约过期/任务租约被接管三种情形的整批回滚、无上下文手工路径不受限）；`FactProjectionTaskLeaseIntegrationTest` 增加父运行取消、令牌变更、终态与续租拒绝三项；定向集合 63 项通过。
- 尚未完成（归入第 2 阶段）：撤销/继续命令本身（`for update` 撤销协议、`RESUME/CANCEL` 接口与端点登记）、fence 服务与回收路径的锁图逐一调整与实测、投影回收路径的父运行锁、F1/F5/F9。

### 实施进度（F7 第 2 阶段 a：F1 等待与失败分离，已完成）

- `wait_reason` 的 Java 词表 `FactTaskWaitReason`（当前只有 `DEPENDENCY_SETTLING`）已加入，`deferOwnedTask` 与运行汇总共用同一枚举，界面不再靠文案或 `run_after` 位置推断等待原因。
- 新增 `FactBuildTaskService.deferOwnedTask(task, deferSeconds)`：在任务租约与父运行授权同时有效的谓词下，把已领取任务退回 `QUEUED + wait_reason = DEPENDENCY_SETTLING`，清空 `lock_owner/heartbeat/lease_until`，按等待步长设置 `run_after`，并返回该时刻供界面展示；谓词不成立时抛 `FactTaskLeaseLostException`，由巡检收敛为人工待处理。
- 依赖未就绪不再消耗重试预算：`FactRefreshTaskWorkerService.execute` 用 `DEPENDENCY_DEFER_SECONDS = 5`（复用既有派发节奏，不新增运维配置）调用 `deferOwnedTask` 并返回 `null`，不再抛错、不再调用 `failOwnedTask`，因此既不会累计失败次数，也不会触发运行级 FAILED/RETRYING 判定。
- `FactTaskLeaseLostException` 在 `execute` 中被单独捕获：只记录日志并返回 `null`，与业务异常路径分离，撤销/转移执行权不占用失败配额。
- 证据：`FactBuildTaskServiceTest` 新增依赖等待用例（真实 PostgreSQL：等待后状态/原因/重试计数/租约/文案、汇总 `dependencyWaiting=1` 且 `queued=0`、`nextRunAfter` 等于返回时刻、未到 `run_after` 不可重新认领；父运行取消后再次 `deferOwnedTask` 抛 `FactTaskLeaseLostException`）与等待/就绪队列分离用例；`FactRefreshTaskWorkerServiceTest` 新增依赖未就绪用例（断言调用 `deferOwnedTask(task, 5)`、不调用 `failOwnedTask`、不触碰构建与发布服务、返回 `null`）。

### 实施进度（F7 第 2 阶段 b：F5 镜像互斥域租约感知，已完成）

- `SyncRunLeaseService` 注入 `GitlabMirrorProperties`，用既有租约窗口（`heartbeat-timeout-seconds`，默认 180 秒）作为空租约异常的判定窗口，不新增配置项。
- 空租约兜底只处理异常行：`RUNNING`/`CANCELLING` 且 `lease_until is null`、且 `coalesce(heartbeat_at, updated_at)` 已超过一个租约窗口，才判 `TIMEOUT`（原因 `Sync run held no lease while active`）。合法释放租约的 `RETRYING`/`PAUSED`（`deferOwnedRun`、yield 的产物）与 `QUEUED` 排队不再可能被误记超时，等待时间再长也不回收；`RETRYING` 也不再因空租约被误判——它早先就不在互斥阻塞集合里。
- `recoverTimedOutRuns()` 改为 `@Transactional`：运行终态写入与在途表任务执行权撤销在同一事务提交。因此同域新运行的放行只可能发生在旧 owner、旧状态与受其授权的子任务全部撤销之后；任一步失败整体回滚，旧行继续保持互斥占用，不存在"先忽略旧阻塞行、旧执行者稍后再收拾"的中间态。
- 续租与终态写入增加"租约仍有效"前提（`lease_until is not null and lease_until >= current_timestamp`）：过期令牌不能通过心跳复活执行权，也不能在运行被回收后改写终态。运行处于 `CANCELLING` 时只接受 `CANCELLED` 收尾，取消中的运行不能被改写成成功；`FAILED`/`TIMEOUT` 等真实结果仍可写入，取消收尾不再被阻塞。
- 证据：新增 `SyncRunLeaseRecoveryIntegrationTest` 10 项真实 PostgreSQL 用例（合法 RETRYING/PAUSED 不被回收、异常空租约按窗口判定、新鲜心跳不被误伤、有效租约不受影响、过期租约同时撤销运行与活动表任务而成功任务保持、撤销失败整体回滚、过期 owner 不能续租/不能终态、CANCELLING 拒绝成功终态但接受取消终态）；`SyncRunLeaseServiceTest` 扩到 9 项覆盖谓词与两条回收语句；定向集合 19 项通过。

### 实施进度（F7 第 2 阶段 c：F10 继续/取消命令与 F2 投影处置，已完成）

- 新增 `FactTaskResolutionService.resolvePendingTask(configId, kind, taskId, expectedTaskRunId, action, resumeMode)`，并落到新端点 `POST /api/gitlab-sync/fact-tasks/resolve`（沿用运维权限与 CSRF；已在 `endpoint-catalog.yml` 登记 EXCLUDED 及原因，覆盖护栏通过）。命令在单事务内完成：来源与配置归属校验、任务行 `for update` 锁定、原运行身份（`expectedTaskRunId`）校验、执行权已撤销校验、意图移交与严格诊断写入。
- **取消**只取消本次执行意图：事实任务 `SKIPPED + CANCELLED` 并释放持有根、不删版本头、不推进 `published_version`；投影任务 `FAILED + CANCELLED`，原 `error_message` 原文保留（`coalesce`），随后推进发布栅栏。台账口径由 `manual_disposition` 区分人为取消与真实故障。
- **继续**建立可见的新 `FACT_REFRESH` 运行并只执行被移交的意图：`submitRun` 携带 `resumedTask` 意图（kind/taskId/originalRunId/mode），执行器识别该意图后不重新入队整个来源的全量任务、不分配其他待发布根；同来源互斥命中他人活动运行时返回明确冲突，不启动重叠执行。
- 事实继续：ORIGINAL 只恢复原任务实际持有的根，根集合为空（已释放且未保存）时明确拒绝并提示改用当前待发布根或全量；`CURRENT_PENDING` 登记该事实族当前待发布的根（同事务内先释放原任务归属再登记，避免根永久被 SKIPPED 任务占用）；`FULL` 保留全量标志。新任务写 `resumed_from_task_id`，旧任务转 `SKIPPED + RESUMED` 并保留原错误。根只在 `fact_build_task_roots` 内移动、同事务提交，外部看不到"无人持有"的中间态。
- 投影继续复用原行（保持唯一键与 `fact_build_task_id`/scope/target generation），`fact_run_id` 原子移交新运行、状态回 `QUEUED`、清执行权与旧错误、重开一轮自动预算；移交前在旧运行严格写入结构化诊断事件（原错误/预算/范围/generation/起止），并在新运行记录接管。重复命令按运行 payload 的接管意图返回既有新运行，不产生第二次接管。
- 新增 `SyncRunEventRecorder.recordStrict`（不吞异常，与移交同事务），区别于既有的 best-effort `record`；手工 `runGuarded` 的 UUID 运行没有事件挂载行时如实跳过，不伪造运行级历史。
- 证据：新增 `FactTaskResolutionServiceIntegrationTest` 9 项真实 PostgreSQL 用例（持根继续/取消释放根且版本头不变/原意图缺根被拒/当前待发布根恢复/全量意图保持/他人活动运行冲突/原运行身份不符拒绝/投影取消/投影移交与重复命令幂等并保留原诊断）；`SyncFactRefreshRunExecutorTest` 新增"人工继续运行只执行被移交意图"用例（断言不调用全量入队与待发布根分配）；三者与 `GitlabSyncControllerTest` 共 28 项通过。

### 实施进度（F7 第 2 阶段 d：F9 四类诊断与状态查询扩展，后端已完成并在隔离库复验）

- 新增 `SyncRunFailureDiagnosticsService`：四类定位项用**单条 UNION 查询**构造（TABLE_TASK/AUTHORITATIVE_SCOPE 取 `FAILED/TIMEOUT/带错误 RETRY_WAITING`；FACT_BUILD 另含 `PAUSED`、人工处置与 `SKIPPED + RESUMED/CANCELLED` 的历史错误；PROJECTION 另含人工待处理），公共列显式对齐、类型专属字段由各分支用 `jsonb_build_object` 显式构造，不用通用字段袋承载专属语义。运行归属统一用文本键比较，因此手工 `runGuarded` 路径的 UUID 事实任务不会被强转数字。
- 摘要按运行分区在库内限量与计数：每类最多 5 项、相关事件最近 5 条、同时给出各自总数；`failureCount/manualAttentionCount/diagnosticCount` 语义分离（前两者可重叠，不相加当作任务总数）。日志列表一次批量取回全部运行的四类摘要，不逐运行查询。
- 运行结论与进度分离：相关事件排除高频 `FACT_BUILD_PROGRESS`，进度另以 `latestProgressMessage/latestProgressAt` 提供；未保存的原始失败时刻保持空值（`errorObservedAt` 只取 `finished_at`，`updated_at` 仅作记录更新时间）。
- 历史证据不被移交抹掉：投影/事实继续时写入旧运行的结构化快照事件（类型 `*_SNAPSHOT`，与运行内接管记录分开），明细与计数按 `kind + taskId` 去重后与实时行合并展示。
- 状态查询扩展：`GET /api/gitlab-sync/status` 支持可选 `detailsRunId/detailsSection/detailsOffset/detailsLimit`（section 为 `DIAGNOSTICS`/`EVENTS`，limit 默认 20、上限 100，offset 非负，非法值拒绝）与 `pendingOffset/pendingLimit`；响应新增 `details`（runId/section/offset/limit/total/items/hasMore）与 `pending`（offset/limit/total/items/hasMore）区块，不传参时两者均为空、默认响应形状不变。明细按运行归属校验，跨配置/来源拒绝；人工待处理列表包含没有父运行行的事实任务并给出 `expectedRunId`（即继续/取消命令所需的 `expectedTaskRunId`）。
- 证据：`SyncRunStatusServiceTest` 扩到 5 项（跨数据源明细拒绝、分页参数规范化与非法值拒绝），`SyncRunLogServiceTest` 4 项、`GitlabSyncControllerTest` 11 项、黄金端点覆盖护栏 2 项通过；新增 `SyncRunFailureDiagnosticsServiceIntegrationTest` 5 项真实 PostgreSQL 用例（分区限量与计数、稳定分页与类型专属字段、移交快照去重合并、事件倒序选择正序展示、含无父运行事实任务的待处理列表）已在隔离库复验通过。复验同时修掉 5 个真实缺陷：①`details` 是 jsonb 列，`queryForList` 返回 `PGobject` 而非字符串，详情恒为空——改为在 SQL 内 `jsonb_build_object(...)::text`；②事件分页先按 `event_id` 升序取 limit，取到的是最旧而非最新——改为按 `row_number` 窗口 `row_order > offset and row_order <= offset+limit` 选择、`order by run_key, row_order desc` 正序展示；③`latestProgressAt` 读 `createdAt` 而查询列名是 `created_at`，恒为空；④移交快照的 `taskId/retryCount` 直取 JSON 解析值（`Integer`），与实时行的 `Long` 不一致——配置点统一为 `asLong/asInt`，`details` 内整数统一 `Long`；⑤`pendingTasks` 原样返回下划线列名，与日志行驼峰契约不一致——改为 RowMapper 映射。`FactTaskResolutionServiceIntegrationTest` 的两处断言仍停用事件类型拆分前的旧口径（旧运行应断言 `*_SNAPSHOT`），已同步修正。全部通过：隔离库 52 项 + 全套件 1663 项（0 失败 0 错误 1 跳过，6:33）。

### 实施进度（F7 第 3 阶段 a：F3 读侧资格拆分与快照回退，已完成并验证）

- `SyncFactPublicationStateService.SourceQualification` 由单一 `readable/degraded` 拆成两种能力：`currentFactReadable`（可从当前事实读取完整产出）与 `savedSnapshotReadable`（完整性证据允许返回已保存的完整产出）。`qualification` 把"有未发布根、全量重建未结算、标签事件历史未完成全量核验"归为**已知未完整窗口**（前者假、后者真，并聚合全部原因，不再按最早的单一原因短路）；依赖未就绪（`readiness_status <> 'READY'`）与"有事实投影却没有发布记录"仍为两者皆假、必须拒绝。用户裁定：回退范围限定为方案列出的三类，不扩大到镜像失败。
- `StatisticBoardSnapshotService` 按能力分开入口：`withinConsistentSourceRead` 降为只保证已保存产出可返回（供快照回退链路），新增 `withinConsistentCurrentFactRead` 供必须读取当前事实者使用（来源未完整时以聚合的维护原因拒绝）。`evaluateSources` 保留多来源的全部原因与待更新数量之和。`readOrRefresh` 的回退判定改为独立于待更新计数的 `snapshotFallbackRequired()`（即使计数为 0，未完整窗口仍回退上一完整发布点）；无可用完整发布点时给出维护原因而非通用提示。
- 逐一覆盖读当前事实的调用者：`BiCustomerIssuePageService`（BI 客户问题页读取与下载校验）、`CustomerIssueCustomerStatisticsBoardService`（客户统计筛选候选、下钻明细）改用严格入口；看板与记录页经由 `readOrRefresh` 自动分流。此处明确记录：无历史完整产出的实时页面在本单元仍不可用（方案未授权擅自重建整套 BI 缓存），只是从"静默读中间态"改为"明确拒绝并给原因"。
- `SourceRead` 新增 `currentFactReadable` 与 `unsettledReason`，以 `snapshotFallbackRequired()` 取代原来的 `degraded()`；`dataAsOf/pendingUpdates` 的"同时存在或同时缺失、计数为正"契约保持不变，因此计数为 0 的未完整窗口回退只返回快照、不带新鲜度提示。
- 补齐 F3 第 7 条：新增 `SyncFactPublicationStateService.requestFullPublication`，手工全量重建入口 `SyncRunSubmissionService.submitManualFullFactRebuild` 在提交事实运行前登记 `full_publication_requested`。已有发布记录的事实族只置标记、保留 `readiness_status`（避免凭空断言就绪）；尚无发布记录的事实族按依赖未就绪登记。自动全量此前已由镜像终态路径（`SyncRunFactPublicationCoordinator.onMirrorCompleted`）登记，手工入口是本条唯一缺口。
- 证据：`StatisticBoardSourceQualificationTest` 6、`SyncFactPublicationStateServiceIntegrationTest` 15（含新增的"登记保留就绪状态并阻断当前事实读取"与"重复登记幂等"两项真实 PostgreSQL 用例）、`StatisticBoardConsistencyBoundaryTest` 4（原"全量重建必须拒绝下一次读取"改为"按能力区分：仍可返回已保存产出，但不得再读当前事实"）、`SyncRunSubmissionServiceTest` 28、`BiCustomerIssuePageServiceTest` 4、`CustomerIssueCustomerStatisticsDetailContractTest` 13、`SourceFreshnessJsonContractTest` 4 通过；全量快速套件 **1665 项通过、0 失败 0 错误**（1 跳过，5 分 35 秒）。黄金基线门禁未跑（非发布时机）。

### 实施进度（F7 第 3 阶段 b：F4 全量清理分批删除 + 固定负载基准，已完成并验证）

- 四类清理全部改为**单个清理事务内的分批语句**：`IssueFactPersistenceService.deleteFactsNotInSnapshot`（ISSUE 父事实 + 客户成员）与 `MergeRequestFactPersistenceService.deleteFactsNotInSnapshot`（MR 父事实 + 提交关系）各自先把快照身份与待删除身份物化到会话级临时表，`row_number() over ()` 给出稳定批序，再按 `CLEANUP_BATCH_SIZE = 2000` 个父/关系键一批推进：先删从属（客户成员 / 提交关系）再删父事实。原「单条 `is not distinct from` 反连接整表删除」与空快照分支的直接 `delete ... where source_system = ?` 已删除，空快照同样走该有界路径。
- 匹配一律使用普通等值：`issue_fact.project_id/issue_id`、`merge_request_fact.project_id/merge_request_id`、`merge_request_commit_fact` 的 `(project_id, merge_request_id, commit_sha)` 在协议上均为非空身份列。临时表沿用与身份列相同的类型；快照输入身份在写入前预检，事实/关系表中若存在空身份行则**显式失败**（不静默跳过、不保留 NULL-safe 双轨），失败时整个清理事务回滚。
- 执行权屏障：每个删除批次开始前调用 `FactTaskExecutionGuard.requireCurrentTaskAuthorization()`，取消后不再开始新的清理批次；FULL_EPOCH 结算此前已在同一屏障内（`FactTargetPublicationService` 在结算前后各校验一次），本单元不改其语义。分批只降低单条 DELETE 的候选量，**不缩小整个清理事务的持锁与回滚范围，也不减少索引维护总量**；既有搜索索引全部保留。
- 证据：`IssueFactPersistenceServiceTest` 5、`MergeRequestFactPersistenceServiceTest` 4（空快照仍走有界批次、提交身份先物化、空身份显式失败且回滚）；`IssueFactSnapshotCleanupIntegrationTest` 2 与 `MergeRequestFactSnapshotCleanupIntegrationTest` 1 的真实 PostgreSQL 回归把夹具从 1200 提升到 2500 个父键以跨过 2000 的批边界，验证多批次循环不互相清除、快照内行全保留、外部事实与关系删尽、空身份失败整体回滚；全量快速套件 **1667 项通过、0 失败 0 错误**（1 跳过，6 分 24 秒）。
- **固定负载基准（方案第 5 条，2026-10-08 实测）**：新增 `backend/src/test/java/com/data/collection/platform/benchmark/FactCleanupLoadBenchmarkTest.java`（`@Tag("benchmark")`），用 Flyway 在本机隔离 `postgres:16-alpine` 上跑完全部 138 个迁移，因此表结构、唯一约束与全部既有索引（含 GIN trgm）都是生产形状。`pom.xml` 默认套件 `excludedGroups` 增加 `benchmark` 并新增 `benchmark` profile：默认快速套件与 golden 门禁都不运行它，只有 `mvn test -Pbenchmark` 会跑（本次 `Tests run: 2, Failures: 0, Errors: 0`，473s）。固定负载：`issue_fact` 50 万 + `issue_fact_customer_members` 2 万（1000 议题 × 20）+ `merge_request_fact` 10 万 + `merge_request_commit_fact` 30 万，另加同表对照来源 1000 行；用 `InstrumentedJdbcTemplate` 按 SQL 语义分解耗时，并按批次统计从属行数。
- 实测数字（ms，单次采样，本机 Testcontainers + 上述分布，**非目标容量**；锁窗口 = 包裹清理的单个事务从开始到提交/回滚返回，即持锁上界）：
  - **ISSUE 删除 40%（保留 60%，候选 20 万）**：物化 536 / 批次键装载 1247 / 批次删除 1689 / 最大单批 39.8 / 总耗时 5839 / 锁窗口 5853（100 批）。
  - **ISSUE 删除 5%（候选 2.5 万）**：物化 379 / 批次键装载 34.6 / 批次删除 3126 / 最大单批 377.7 / 总耗时 7583 / 锁窗口 7601（13 批）。
  - **ISSUE 空快照（删尽 50 万父行 + 2 万从属行）**：物化 762 / 批次键装载 6773 / 批次删除 33780 / 最大单批 1747.6 / 总耗时 41808 / 锁窗口 41824（250 批）。
  - **MR 删除 60%（父保留 40%，删 6 万父 + 18 万提交关系）**：物化 534 / 批次键装载 1444 / 批次删除 14529 / 最大单批 453.8 / 总耗时 18247 / 锁窗口 18275（120 批）。
  - **MR 空提交快照（父全保留、提交关系删尽 30 万）**：物化 579 / 批次键装载 4946 / 批次删除 2088 / 最大单批 83.6 / 总耗时 9342 / 锁窗口 9371（150 批）。
  - **中途失去执行权回滚（放行 125/250 批后抛 `FactTaskLeaseLostException`）**：回滚 27142；断言 50 万父行 + 2 万从属行原样保留、对照来源未受影响。
- 回归复验（第 4 阶段）：默认快速套件 **1671 项通过、0 失败 0 错误**（1 跳过，6 分 53 秒），与 F6 单元基线一致；日志中无 `Benchmark` 字样，证实 `benchmark` 标签被默认套件排除（`-Pbenchmark` 才会运行）。13 项仓库守卫脚本与 `git diff --check` 全绿。
- 由这组数字得到的结论（作为方案 R12 的实测佐证，但**不等于**缺陷已解决）：①锁窗口恒等于总耗时，证实分批只降低单条 DELETE 的候选量，**没有**缩小整个清理事务的持锁与回滚范围——50 万规模的空快照清理在本机连续持锁约 42 秒，回滚同样约 27 秒。②"2000 父键 ≠ 最多 2000 行"成立：空快照场景有一个批次一次删除 2 万个客户成员行（1000 个父键 × 20），即 R12 要求基准按实际子行数测量的原因。③50 万规模的最坏单批 1.75 秒，远高于 2000 键的名义规模。④**内网 300 万级容量与高文本填充分布仍未实测**（本夹具的 `search_*`/`*_search_*` 等 trgm 列留空，GIN trgm 维护成本未纳入），故不得据本机结果预先认定任何查询超时设置在目标容量下都成功；该实测须在目标环境补齐后才能宣告超时缺陷解决。

### 实施进度（F7 第 3 阶段 c：F6 前端刷新生命周期，已完成并验证）

- 新增共享 composable `useRealtimeRefreshLifecycle`，取代原 `useStatisticBoardRefreshController` 与无界等待函数 `waitForRealtimeWorkspaceRefresh`（两者连同旧测试一并删除，不留第二套机制）：状态拆为 `submitBusy`（提交在途）、`fetchLoading`（收敛后取数）、`refreshPending`（后台跟踪）与仅由前两者决定的 `refreshButtonBusy`；提交请求、数据取数与后台轮询不再共用同一 loading 去重。
- 生命周期：提交（或复用）后按钮只占用 `REFRESH_BUTTON_WINDOW_MS = 10 秒`；未收敛即释放按钮，由唯一跟踪者按 `REFRESH_STATUS_POLL_INTERVAL_MS = 1 秒` 继续轮询。同一刷新自首次接受起最多跟踪 `REFRESH_TRACKING_LIMIT_MS = 15 分钟`；以服务端 `trackingId` 判定“同一刷新”，重复提交既不叠加请求也不重置起算时点。收敛后加载最新数据；终态失败保留当前数据并说明原因；到期只停止跟踪并提示查看同步日志，不改变服务端业务状态、不伪装 READY；状态读取失败不终止跟踪，仍受 15 分钟上限约束。三个常量都是交互常量，不新增运维配置。
- 提交结论落到响应：`RealtimeWorkspaceStatusResponse` 新增 `submissionOutcome`（ACCEPTED/ALREADY_REFRESHING/COOLDOWN）与 `trackingId`，两者带 `@JsonInclude(NON_NULL)`，因此 GET /status 的响应逐字不变。ALREADY_REFRESHING/COOLDOWN 时提交方复用现有跟踪或给出冷却提示，绝不再提示“已开始刷新”；`supported=false` 视为 UNSUPPORTED。
- 失效与归属：路由查询（项目/来源/筛选/分页/排序）变化、明细关闭与组件卸载都使旧跟踪失效（清定时器、释放按钮窗口、丢弃跟踪身份、按代次丢弃迟到回调）；收敛回调只更新当前范围的筛选项、主表与当前已打开明细。`useRouteTableState` 暴露 `watchedQuerySignature`，供记录页在查询组合变化时失效旧跟踪。
- 覆盖入口：`StatisticBoardView`（工具栏刷新按钮新增 `refresh-button-busy`，并把刷新链路从整页 `v-loading` 上摘除）、`SystemTestMultiBoardView`、`SystemTestIssueSearchView`、`CustomerIssueRecordsView`、`CodeReviewIllegalRecordsView`、`CodeReviewMultiBoardView`、`IssueIllegalRecordsPage`。方案正文列举六个入口，实际同模式入口为七个——`CodeReviewMultiBoardView` 同样存在“按钮跟随服务端 `refreshing` 长期占用”的缺陷，故一并统一，避免残留第二套刷新实现。`StatisticBoardView` 的进入页面自动刷新改走同一生命周期（不弹提示）。
- 证据：前端 `useRealtimeRefreshLifecycle.test.ts` 11 项（10 秒窗口与释放、收敛取数、重复点击不重置 15 分钟起点、冷却不报“已开始”、复用后端在跟踪的刷新、终态失败保留数据、状态读取失败继续但受上限约束、失效停止、自动刷新不打扰、提交传输失败、不支持工作区）+ `useRealtimeWorkspaceStatus.test.ts` 3 项 + `StatisticBoardToolbar.test.ts` 新增 1 项（按钮占用随 `refreshButtonBusy` 变化）；后端 `RealtimeWorkspaceServiceTest` 5 项与新增 `RealtimeWorkspaceStatusJsonContractTest` 3 项；**全量快速套件 1671 项通过、0 失败 0 错误（1 跳过，6 分 01 秒）**。前端 `tsc`、ESLint 通过，九个改动入口模块经开发服务器（18181）转换返回 200，守卫脚本与 `git diff --check` 全绿。测试环境说明：本机测试库容器 `qaflex-test-postgres-15433` 因 Docker 重启处于 `Exited`，首次全量运行出现 117 个 `ApplicationContext` 加载错误（全部为 `localhost:15433` 连接被拒、0 个断言失败），`docker start` 该容器后复跑全绿；这不是本单元引入的回归。
- **浏览器交互验收（2026-10-08 完成）**：以本地认证实例（`PLATFORM_AUTH_PROVIDER=local`，`admin/admin123`）跑开发栈前端 18181 + 后端 18080 实测。①非法记录页不带 `milestoneTitle` 直接打开：地址被默认值补丁改写为 `?page=1&milestoneTitle=CC2026R3`，主表渲染 788 行、`共 788 条`——证明刷新期间不再整页 `v-loading`，表格始终可用；②点击“刷新最新数据”：按钮进入 `is-loading` 且同步徽标变“同步中”，主表行数保持不变（无整页遮罩）；③`REFRESH_BUTTON_WINDOW_MS` 到期后按钮释放（`disabled=false`、无 `is-loading`），徽标仍“同步中”，跟踪继续按 1 秒轮询；④收敛后自动重取 `filter-options` 与主表并刷新数据；⑤在页面内劫持 `fetch` 取得 POST `/refresh` 的真实响应体：`submissionOutcome=ACCEPTED`、`trackingId=customer-issue-illegal-records#4`；⑥同会话内两次连续 POST `/refresh`：首次 `ACCEPTED` + `trackingId=…#5`，紧接的第二次 `ALREADY_REFRESHING` 且 `trackingId` 相同、文案“刷新仍在进行中，继续跟踪本次刷新”——重复提交复用同一刷新身份、不叠加执行；⑦`GET /status` 实测响应不含 `submissionOutcome`/`trackingId`，NON_NULL 契约在运行实例上成立。本机该工作区刷新收敛均在 11 秒内，故“按钮已释放但仍在跟踪时再点”的边界未能在实机复现（由 `useRealtimeRefreshLifecycle.test.ts` 单元级覆盖）。
- **修复：`customer-issue-illegal-records.mount-smoke.test.ts` 首个用例的真实页面缺陷（2026-10-08）**：此前的失败并非与环境无关的既有问题，而是 `IssueIllegalRecordsPage.vue` 的真缺陷——首屏默认值补丁 `applyPrimaryFilterDefaults()` 会用 `patchQuery` 改写路由查询，触发 `useRouteTableState` 的签名观察者 `reload()`；但补丁在途期间加载器的 `primaryDefaultPatchInFlight` 守卫会拒绝该次取数，而补丁完成后原代码 `if (!patchedDefault && primaryFilterDefaultsReady.value) await reload()` 在“确实打过补丁”时跳过显式取数，导致 `pageInitialized` 永为 false、页面停在骨架。修复为补丁落定后无条件按就绪状态取数一次；上述 ① 即在真实浏览器中对同一路径的复验。

### 实施进度（F7 第 3 阶段 d-1：F8 日志表模块自身双向滚动，代码与单测已完成，浏览器验收待与 F9 一并进行）

- 共享 composable `useFloatingHorizontalScrollbar` 新增内部定位模式 `positioning: 'viewport' | 'container'`（默认 `viewport`）：`viewport` 保持原有「跟随视口固定在页面底部、滚出视口即隐藏」行为，`record-table`/`stat-matrix`/`stat-detail`/`review-problem` 四处共享表一字未改；`container` 定位改由本地容器与表体 `clientWidth` 计算——`position: absolute`、`left` 取表体相对外壳的偏移、`width` 取表体 `clientWidth`（已排除纵向条）、`bottom: 0` 对齐外壳 padding 盒下沿。这是落实两种既有布局的内部参数，不是新的用户或运维开关。
- `MirrorSyncLogTable.vue`：移除 `<Teleport to="body">`，横条改为外壳内的绝对定位子节点，随模块滚动、不再漂浮到页面其他区域；外壳加 `isolation: isolate`，使横条原有的高 `z-index` 只在本模块内生效；外壳加 `padding-bottom: 18px` 为横条预留空间，不盖住最后一行；`el-table` 绑定响应式 `:max-height`（桌面 420px、矮屏按 55vh 收缩、下限 220px，随 `resize` 重算），高度与筛选一并进入 `watchedSources`，展开/收起仍走 `doLayout + scheduleHorizontalScrollbarUpdate`。
- `styles.css`：删除 `.sync-log-table-shell { max-height: 240px; }`（P5a 的裁剪根因）；纵向改用 Element Plus 内置滚动条（全局规则只隐藏 `.is-horizontal`），横向继续沿用平台自绘轨道/滑块并停靠模块底部。
- 证据：新增 `src/composables/useFloatingHorizontalScrollbar.test.ts` 3 项（container 定位数值、viewport 行为回归、无横向溢出时隐藏）；`MirrorSyncLogTable.test.ts` 增至 5 项，新增用例断言不再 Teleport、不再 `position: fixed`、`positioning: 'container'`、`padding-bottom: 18px`、`:max-height="tableMaxHeight"`，并在 DOM 上断言横条是外壳子节点、表格收到 ≥220 的 max-height。**全量前端套件 152 文件 720 项全部通过**；`npm run typecheck` 与 ESLint 通过。
- 浏览器验收（2026-10-08，与 F9 一起做）：`/system-settings/mirror-settings` 实机实测——外壳 `padding-bottom: 18px`、`isolation: isolate`，表格 `max-height` 随视口收缩（553px 高视口下为 304px，桌面上限 420px）；日志表 100 行全部渲染，`body-wrapper` `scrollHeight 4000 / clientHeight 283` 可纵向滚动（改造前是 240px 裁剪 + 双向 hidden，只剩约 5 行且滚不到）；横条 `position: absolute`、父节点即外壳（无 Teleport），`barRect.bottom == 外壳 bottom == 4484`，而当时视口底部仅 553 —— 证明横条停靠模块而非漂浮在视口；把外壳滚入视口后横条位于外壳底部内（423–439 ⊆ 115–439）；横向拉到最右后滑块 `translateX` 由 0 变为 288.9px（拖动/同步有效）；表体宽度 447 / 滚动宽度 1264（确有横向溢出）。展开一行后外壳高度不变、横条仍在外壳内。

### 实施进度（F7 第 3 阶段 d-2：F9 明细入口前端，已完成并验证）

- 契约与类型：`types/api/sync.ts` 新增 `SyncRunDiagnosticKind`、`SyncRunLogDetailSection`、`SyncRunDiagnosticItem`（含 `rawError`/`dispositionReason`/`scopeSignature`/`targetGeneration`/`heartbeatAt`/`leaseUntil`/`recordUpdatedAt`/`details` 等按 kind 取用的字段）、`SyncRunEventTrailItem`、`SyncRunDetailBlock`；`SyncRunLog` 补齐 `failureCount`/`manualAttentionCount`/`diagnosticCount`/`diagnostics`/`eventCount`/`eventTrail`/`latestProgressMessage`/`latestProgressAt`；`MirrorStatusResponse` 新增仅在显式传参时出现的 `details`/`pending`。`mirror-api.ts` 新增 `getRunLogDetails(configId, runId, section, offset, limit)`，发往既有 `GET /api/gitlab-sync/status` 的 `detailsRunId/detailsSection/detailsOffset/detailsLimit`（同一配置/来源归属校验，旧运行可按 runId 查）。
- 新组件 `MirrorRunLogDetailDrawer.vue`：标题带运行编号；运行结论卡显示实际状态、同步内容、触发、起止、写入记录、失败/待处理；运行已结束且仍有待人工处置时给出「该运行已结束，尚有 N 项人工待处理」；运行级错误原文与每条任务 `rawError` 均可折行查看并「复制全文」；定位项与相关事件各自分页（每页 20，`el-pagination` 用 `total` 显示完整总数），无保留时明确写「没有保留」；进行中耗时按响应时刻减 `startedAt`，缺起点/失败时间时保持空值。分类专属上下文按服务端实际键名（`sourceTable`/`taskStage`/`rowsScanned`/`rowsApplied`/`affectedRows`/`rootCount`/`scope` 等）落位，未认识的键原样列出，不吞线索、不伪造。
- 入口与摘要：日志表展开区新增「失败项/待人工处置/可查明细/相关事件」计数与「查看全部 N 项明细」按钮（emit `openDetails`），并展示最多 5 条定位项、最多 5 条相关事件与最新进度；`MirrorSettingsView` 承载抽屉并把 `configId` 与选中的运行传入。新增任务状态文案（`PAUSED`/`RETRY_WAITING`/`SKIPPED`）与人工处置、诊断分类、等待原因文案映射。
- 证据：新增 `MirrorRunLogDetailDrawer.test.ts` 4 项（两区段请求参数、运行结论与四类分类渲染、任务错误原文可复制、分页续查同一运行、无保留空态）与 `MirrorSyncLogTable.test.ts` 新增 1 项（摘要计数 + 按钮 emit）；**全量前端套件 153 文件 725 项全部通过**，`npm run typecheck`、ESLint 与四项守卫脚本、`git diff --check` 全绿。
- 浏览器验收（2026-10-08，与 F8 同时做，临时 `PLATFORM_AUTH_PROVIDER=local` 实例）：展开日志行出现计数与「查看全部 0 项明细」；点击后抽屉打开，实测网络请求为 `GET /api/gitlab-sync/status?detailsRunId=4059&detailsSection=DIAGNOSTICS|EVENTS&detailsOffset=0&detailsLimit=20&configId=1`（与后端契约一致）；抽屉渲染运行编号/运行结果/同步内容/触发来源/起止/写入记录/失败·待处理，两区段分别显示「共 0 项/条」与「没有保留」提示。四类定位项的**数据契约**在运行实例上按旧运行直查验证：`detailsRunId=3477` 返回 15 条 `TABLE_TASK`（含 `sourceTable`/`taskStage`/`rowsScanned`/`rowsApplied`/`retryCount`/`maxRetryCount`/`rawError="Parent sync run lease timed out"`），`detailsRunId=3830` 返回 1 条 `FACT_BUILD`（含 `factType`/`fullBuild`/`scope`/`rootCount`/`affectedRows`、完整 SQL `rawError` 与处置原因）。**未做**：因开发库最近 100 条运行全部是成功的增量同步（无定位项、无相关事件，失败项集中在排名 551 之后的历史运行），四类定位项与事件列表的**渲染**只能在单测中看到，未在浏览器中看到有内容的抽屉；这是数据可得性限制，不是功能缺口。验收环境已按用户裁定回退 LDAP。

### 实施进度（F7 评审整改：F9 表任务重试分支与投影可处置判据，已完成并验证）

> 来源：2026-10-08 独立评审报告的 2 项"建议修正"（无阻断项）。

- **F9 表任务定位项补"带错误的重试中"分支**：`DIAGNOSTIC_UNION` 的 TABLE_TASK 分支由 `status in ('FAILED','TIMEOUT')` 扩为 `status in ('FAILED','TIMEOUT') or (status = 'RETRYING' and last_error is not null)`。表任务的退避重试状态是 `RETRYING`（`SyncRunTableTaskLeaseService.deferOwnedTask` 与租约超时路径都会写入 `last_error`），此前重试窗口内的真实错误在明细里完全不可见，与 F9 第 2 条"FAILED/TIMEOUT，以及有错误的 RETRYING"不符（FACT_BUILD/PROJECTION 两分支早已覆盖该形态，TABLE_TASK 独缺）。
- **计数与明细同口径**：`countDiagnostics` 的 `kind_failures` 由 `status = 'RETRY_WAITING' and raw_error is not null` 扩为 `status in ('RETRYING','RETRY_WAITING') and raw_error is not null`，使新增的 `RETRYING` 失败项同样计入 `failureCount`，四类不再出现"列表有、计数无"。
- **投影可处置判据收回单一权威**：`FactTaskResolutionService` 的投影分支改用与事实侧同一个 `resolvableTask(status, manualDisposition)`（`REQUIRES_DECISION`，或 `NONE` + 终态 `FAILED`），删除投影快照上过宽的 `resolvable()`（原判据"非 `QUEUED`/`RETRY_WAITING` 即可处置"会把 `SUCCESS` 与无租约 `RUNNING` 一并放行）与事实快照上已无调用方的同名方法；全仓自此只有一处判据。终态 `FAILED + NONE` 仍可处置，回收路径的操作意图不受影响。
- 证据：`SyncRunFailureDiagnosticsServiceIntegrationTest` 新增 `table_task_diagnostics_show_retrying_with_error_and_skip_clean_retrying`（`RETRYING` 带错误进明细并计入 `failureCount`、`RETRYING` 无错误不进明细），`FactTaskResolutionServiceIntegrationTest` 新增 `projection_resolution_rejects_tasks_that_are_not_failed_or_awaiting_decision`（`SUCCESS` 与无租约 `RUNNING` 投影被拒且状态不被改写），两文件合计 16 项通过；随后全量默认快速套件 **1675 项通过、0 失败 0 错误**（1 跳过，= 本轮前 1673 + 新增 2），守卫脚本（worktree 产物、运行产物位置、文本空白、后端测试卫生）与 `git diff --check` 全绿。前端无需改动：`RETRYING` 已有状态文案（`mirror-sync-status-labels.ts` 的 `TABLE_TASK_STATUS_LABELS`）。
- 评审的 3 项提示未改代码：`deferOwnedTask` 谓词未显式校验任务租约未过期（唯一调用点紧跟认领，且现有 `lock_owner` + 父运行授权已覆盖执行权；补租约判据会让已过期租约的等待反而失败、留给回收判定超时，未采纳）；失租 `RUNNING` 投影回收为 `FAILED + NONE` 的文案差异（`FAILED + NONE` 经本次判据仍可处置，操作意图保留）；继续/取消仅有 API 入口，是否补前端按钮待用户裁定。

### 恢复线索

- 当前阶段：实施中，F7 第 1 阶段、第 2 阶段 a/b/c/d、第 3 阶段 a/b（F3、F4，F4 含 50 万固定负载基准）与第 3 阶段 c/d（F6 刷新生命周期、F8 日志表双向滚动、F9 明细入口前端，三者均已浏览器验收）已落地；第 4 阶段完整快速套件复验已完成（**1671 项通过、0 失败 0 错误、1 跳过**，6:53）；2026-10-08 独立评审的 2 项"建议修正"（F9 表任务带错误重试分支、投影可处置判据统一）已修复并复验（**1675 项通过、0 失败 0 错误、1 跳过**）。**第 1–4 阶段全部完成**；剩余只有第 5 阶段发布门禁（实际打包/部署前跑黄金基线 compare）；F4 的内网 300 万级容量实测须在目标环境补做后才能宣告超时缺陷解决。声明式留待项：DEC-B（遗留投影任务收割 vs 终态化）仍无独立用户批准；评审提示项"继续/取消是否需要前端操作入口"待用户裁定。
- 提交状态（2026-10-08）：本设计的后端整体提交为 `982b62fe`（含 `V20260930_02` 迁移与 `scripts/flyway-migration-checksums.json` 登记、`benchmark` profile、`resolve` 端点目录登记），前端提交为 `75ab9eb7`；两单元合并态复验为后端默认快速套件 1675/0/0（1 跳过）、前端 154 文件 729 项全绿、typecheck 干净、六项守卫脚本与 `git diff --check` 全绿。
- 恢复后建议执行的首条命令：`git status --short --branch`，然后按本文「F7 实施与交付顺序」第 2 阶段继续；撤销/继续命令必须沿用已建立的锁序「父运行 → 任务 → 版本头/generation」。
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
- 页面刷新提交响应增加明确 `submissionOutcome`（ACCEPTED/ALREADY_REFRESHING/COOLDOWN）与 `trackingId`；`workspaceKey` 与 `jobId` 继续作为跟踪所需的 workspace 与运行身份，config/source 由调用方按其请求范围自行持有。两个新字段带 `@JsonInclude(NON_NULL)`，读状态响应不出现它们。
- 跟踪结果区分 CONVERGED、PENDING、FAILED、CANCELLED、CLIENT_TIMEOUT、DISPOSED；CLIENT_TIMEOUT 仅客户端，服务端不被改 READY。实现对应：CONVERGED＝状态不再 refreshing 且非失败终态（随后加载最新数据）；PENDING＝仍 refreshing（继续按 1 秒轮询）；FAILED＝终态 FAILED/UNSUPPORTED（保留当前数据并给出原因）；CLIENT_TIMEOUT＝达到 15 分钟跟踪上限或状态读取在期限内始终失败（只停客户端跟踪）；DISPOSED＝路由/来源/筛选切换或组件卸载（静默停止）；CANCELLED＝服务端报告取消后不再 refreshing，按非失败终态重新取数（当前服务端 `RealtimeWorkspaceService` 不产出该状态）。
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
- **性能证据边界**：F4 的临时物化与分批删除都可能超时；现有索引和单事务锁成本不消失。本机 50 万固定负载已实测（见第 3 阶段 b 小节：分批不缩小锁窗口，空快照清理连续持锁约 42 秒），但**内网 300 万级容量与高文本填充分布仍未实测**，内网查询超时值未知，不能预先承诺保持任意配置必成功。F8 浏览器滚动验收已执行（第 3 阶段 d-1）；F4 基准已执行，但内网容量实测仍待补。
- **升级可控性**：首次巡检可能出现待处理项，README 需说明数量、范围、继续/取消含义及回滚限制。先建立停止/撤销屏障，再运行遗留收敛；不能在旧 worker 仍能续租时直接批量改 PAUSED。不清理任务/栅栏表、不手工结算版本。
- **发布验证**：本轮是文档修订，未改变应用、数据库或黄金快照。实际业务实施后必须覆盖上述验收并按 F7 发布门禁验证；文档获审阅不等于实现与部署已经安全。
