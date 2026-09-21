# 延期标签写回闸门：事实发布收敛判据修复

## 进度与中间物

- 状态：**已实现，定向验证与真实链路验证均通过**（用户 2026-09-20 批准执行）；默认快速套件结果见下方"实现变更"与"验证证据"。
- 交付物：本方案（根因链、权威判据设计、接口契约、测试与验证方式、已否决做法）。
- 已完成的验证（本地真实链路，2026-09-20）：`CUSTOMER_ISSUE_DELAY_LABEL_WRITEBACK_API_ENABLED=true` 下对本地 GitLab（`gitlab-data-web-1`，project 325）完成 5 条真实写回（HTTP 200）、确认只增不损、镜像回吸后 0 重复重写、超时重试与租约抢占按设计工作。该过程同时暴露本方案要修的缺陷。
- 修复后的真实链路终验（2026-09-20 17:10 起，详见 `docs/progress.md` 同日"真实链路测试"条目）：在本地 GitLab 制造双向差异样本后，`17:12:25` 预写回 `TABLE_REFRESH` **确实应用了增量**（正是旧判据直接跳过的场景），新判据输出 `publication converged, mirrorRunId=3295, factType=ISSUE, waitedMillis=4065` 并在 `17:12:53` 正常入队，随后 `add_labels`(议题 1300) 与 `remove_labels`(议题 2251) 两个分支各自 HTTP 200 成功；两个样本最终标签与测试前基线逐字节一致，全量 1512 条议题中非延期标签零改动。旧实现必红。
- 复现证据：12:30 那轮预写回同步 `applied_rows=0` → 正常入队 57 条；12:36 那轮 `applied_rows=7` → `WARN Customer issue delay pre-writeback sync applied rows but no fact refresh run was queued, mirrorRunId=3262` → 整轮跳过，未入队。
- 实现变更（代码）：`SyncFactPublicationStateService` 新增 `countUnpublishedTargets` 并把私有 `hasPendingTargets` 改为委托；`CustomerIssueDelayPreWritebackSyncService` 改为等待 ISSUE 发布收敛并删除子运行窗口、`parent_run_id` 查询、`applied_rows` 门槛与死结果类型；`CustomerIssueDelayClosureScheduler` 适配布尔返回；`SyncRunSubmissionService.submitFactRefresh` 去掉恒空父运行参数、删除 `sameFactRefreshParent`；`RealtimeWorkspaceRefreshProgress(Service)` 删除失效的父运行关联、事实运行身份字段与不可达分支。文档：`architecture.md` 新增写回前发布收敛判据条目、`decisions.md` 新增 D-14、`platform-page-business-rules.md` 第 14 条改为可执行判据表述。
- 验证证据（测试）：定向 49 项全绿（`CustomerIssueDelayPreWritebackSyncServiceTest` 6、`SyncFactPublicationStateServiceIntegrationTest` 6、`SyncRunSubmissionServiceTest` 28、`RealtimeWorkspaceServiceTest` 4、`SyncRunFactPublicationCoordinatorTest` 2、`CustomerIssueDelayClosureSchedulerTest` 3）。默认快速套件见 §9。
- 环境处置：本机 `qaflex-test-postgres-15433`（测试库）在 Docker 重启后不存在，已按仓库记录的方式重建同名容器（`RestartPolicy=no`，仅本地），否则约 70 个 `@SpringBootTest` 会报环境错。开发库 `qaflex-dev-postgres-15432` 同样已拉起，前后端 18080/18181 正常。
- 未提交工作树改动（评审代码、文档、大量黄金快照）全部保留，与本工作单元无关。
- 关联方案：`docs/plans/response-delay-dual-template-rule-20260920.md`（后续工作；`remove_labels` 分支已在本轮真实链路验证通过）、`docs/plans/delay-writeback-scheduler-starvation-fix-20260920.md`（真实链路测试中发现的下一层阻塞，待审批）。

## 1. 恢复线索

- 调查基点：`main` 的 `7565afd5` 加当前工作树。
- 恢复后首条命令：`git status --short`。随后读取 `AGENTS.md`、`docs/progress.md`、本方案，核对实施指派与工作树变化。
- 相关权威：`docs/architecture.md:43-44,50,54-55`（事实发布模型）、`docs/decisions.md` D-10、D-13（`:145` 已把 `c367258a` 记为待处理目标领取范围放大的来源）。
- 当前下一步：用户审批后，先建工作单元计划再动代码。

## 2. 目标与边界

### 2.1 目标版本

把「延期标签写回前的事实就绪判据」从**已废弃的父子运行假设**改为**现行权威的事实发布收敛判据**，使闸门在任何"本轮镜像确实应用了增量"的周期都能正确放行；同时清除同一根因残留的死参数与同源失效查询，不留兼容分支或双轨逻辑。

### 2.2 明确不做

- 不把 `FACT_REFRESH` 重新绑定到父镜像运行（逆反 `docs/architecture.md:44` 与已确认的权威模型）。
- 不在闸门内自行调用 `FactBuildService` 刷事实（绕过发布门禁与来源互斥域，破坏单一权威所有者）。
- 不用关闭 `customer-issue-delay-pre-writeback-sync-enabled` 绕过（会让写回基于陈旧镜像标签发出多余写回，掩盖根因）。
- 不新增开关、回退分支或兼容重载。
- 不改发布门禁本身（来源级 READY 语义保持不变，见 `docs/decisions.md` D-13「来源级 READY 门禁不变」）。
- 不在本次调整超时默认值（见 §5.6）。

### 2.3 成功标准（可验证）

1. 本地真实链路中，构造 `applied_rows > 0` 的周期，闸门放行且入队条数与事实差异一致；不再出现 `no fact refresh run was queued`（本地验证方式：`spring-boot:run` 带全局开关，观察日志与队列表，见第 9 部分）。
2. 连续两轮（第二轮无增量）不产生重复写回。
3. 收敛判据与闸门行为由新增单元测试与真库集成测试锁定；同一场景在旧实现下被跳过属行为级证据（见 §5.5 说明）。
4. 后端默认快速套件全绿；无端点产出变化，故不触发黄金基线快照更新（该路径无端点且本就 EXCLUDED）。
5. 不触碰与写回无关的既有功能产出。

## 3. 约束与背景

### 3.1 现象

`CUSTOMER_ISSUE_DELAY_LABEL_WRITEBACK_API_ENABLED=true` 时，写回能否发生取决于本轮预写回同步**是否恰好没有产生镜像增量**：增量非零的周期被闸门跳过，只有空转周期才可能入队。实际效果是写回被系统性推迟，在数据活跃的来源上可长期不执行 —— 这正是"功能已实现但内网未真正启动"背后除了开关之外的第二重原因。

### 3.2 闸门现有实现

`backend/src/main/java/com/data/collection/platform/service/CustomerIssueDelayPreWritebackSyncService.java`：

- `:52-84 refreshBeforeWriteback`：提交 `TABLE_REFRESH`（表集合 `issues,notes,label_links,labels`）→ `waitForMirrorRun` → **仅当 `applied_rows > 0`** 才进入事实等待（`:78-80` 为 0 时提前返回成功）。
- `:116-147 waitForIssueFactRefresh`：先以 10 秒窗口等"事实运行出现"（`:24 FACT_TASK_APPEAR_WAIT_SECONDS`、`:118-127`），再等其终态。
- `:149-166 factRefreshRunStatus`：`select status from sync_runs where parent_run_id = ? and run_type = 'FACT_REFRESH'`（`:155`）——**失效点**。
- `:168-170` 总超时取 `customer-issue-delay-pre-writeback-sync-timeout-seconds`（默认 180s）。

### 3.3 现行权威发布模型

- `docs/architecture.md:44`：「只有完整 READY 的事实族可创建或唤醒**无父运行的**来源级 `FACT_REFRESH` 消费者……从全部历史 `mirror_run_id` 合并未发布目标」。
- `docs/architecture.md:50`：`sync_run_fact_targets` 是定向事实发布 outbox，稳定身份 `source_instance + fact_type + GitLab root_id`；`fact_change_heads` 保存最新/已发布版本并提供乱序 fencing。
- `docs/architecture.md:43`：ODS DML、`fact_change_heads` 版本推进与 `sync_run_fact_targets` 的 upsert **位于同一平台事务**。
- 代码侧对应：`service/sync/SyncRunFactPublicationCoordinator.java:54` 以 `submitFactRefresh(config, null, ...)` 提交，`:32-33` 类文档写明「事实消费者不再绑定父镜像运行」。

### 3.4 可复用的既有读接口（现状事实）

| 接口 | 位置 | 语义 | 是否可用于本判据 |
|---|---|---|---|
| `isReady(sourceInstance, FactType)` | `service/sync/SyncFactPublicationStateService.java:73-95` | READY **且存在可消费意图**（full 意图或未发布目标） | 否。它是"允许发布"的正向门禁，语义与"已收敛"相反 |
| `requiredTables(config, factType)` | 同上 `:153` | 该事实族依赖表 | 否，用途不同 |
| `hasUnpublishedTargets(configId, sourceInstance)` | `service/FactBuildTaskService.java:594-622` | 来源级、跨全部历史是否存在未发布目标 | 部分可用，但粒度是来源而非本轮镜像运行 |
| `hasPendingTargets(...)` | `SyncFactPublicationStateService.java:269`、`service/sync/SyncRunPublicationFenceService.java:242` | 目标是否存在未发布 | 均为 private，无公共读法 |

结论：**当前不存在**"指定镜像运行登记的目标是否已全部发布"的公共读接口，需要新增一处权威读法（见 §5.1）。

### 3.5 数据契约事实

- `sync_run_fact_targets`：无实体、无 Mapper，全仓裸 SQL 访问。`mirror_run_id` 非空，在产生变化的镜像事务内写入（`service/FactChangeTargetService.java:124-130`）；`assigned_fact_run_id` 可空，由 `service/FactBuildTaskService.java:284-285` 在领取时写入，再变化或失败释放时清空（`FactChangeTargetService.java:140`）；`publication_status ∈ {PENDING, QUEUED, PUBLISHED}`（check 约束 `resources/db/migration/V20260731_01__incremental_delete_targeted_publication.sql:81`）；`fact_type ∈ {ISSUE, MERGE_REQUEST, INTEGRATION_TEST}`（`entity/FactType.java:4`）。
- `source_fact_publication_states`：唯一读写点为 `SyncFactPublicationStateService`（`recordMirrorCompletion:36` → `upsertFamily:229-267`、`settleAfterFullPublication:118-150`），键为 `config_id + source_instance + fact_type`。
- 发布完成路径：`service/sync/FactRefreshTaskWorkerService.java:64-95`（先过 `isReady` 门禁 `:68`，再 `publish/publishFull` `:72-75`）→ `service/FactTargetPublicationService.java:68-105`（`:243-278` 将目标置 PUBLISHED 并推进 `fact_change_heads.published_version`，`:102 advanceAfterFactPublication`）。
- 镜像终态事件：`service/sync/SyncRunCompletionEvent.java:6-50`（`mirrorRun()`、`successful()`、`requiresFullFactRefresh()`）；仅镜像运行发布（`service/sync/SyncRunWorkerService.java:272-284`，`:286-294` 排除 FACT_REFRESH）。

## 4. 证据与根因

### 4.1 成因链

| 时间 | 提交 | 事实 |
|---|---|---|
| 2026-05-15 | `86a4e167` | 引入事实刷新，提交标题即 "add parent run ID to sync runs"；FACT_REFRESH 挂在镜像运行下 |
| 2026-07-06 | `329b0873` | 闸门 `CustomerIssueDelayPreWritebackSyncService` 诞生，按当时模型查 `parent_run_id` —— **当时正确** |
| 2026-08-03 | `2fd5c34e` | 新建 `SyncRunFactPublicationCoordinator`，该提交内仍传 `mirrorRunId`（`git show` 可验），接口未破 |
| 2026-08-10 | `c367258a` | 改为 `submitFactRefresh(config, null, ...)`，类文档写明事实消费者不再绑定父镜像运行；`docs/decisions.md` D-13`:145` 也从性能角度记录该提交把待处理目标领取扩大为来源范围历史 |

`c367258a` 的提交标题是 CatMirror 设置与映射接口（319 files / +26227 的混合提交），模型变更夹带其中；闸门成为全仓唯一仍按旧模型查询的地方，且没有任何机制提示它已失效。

### 4.2 为什么长期无人发现

1. 总开关默认 false，且打包脚本把 `CUSTOMER_ISSUE_DELAY_LABEL_WRITEBACK_API_ENABLED=false` 钉进内网 `.env` 与 compose ⇒ 该路径从未在真实环境执行。
2. `service/CustomerIssueDelayClosureSchedulerTest.java:53,60-73` 直接把 `refreshBeforeWriteback` 整个 mock 成返回值，`waitForIssueFactRefresh` 在测试代码中**零引用**，那段 SQL 从未对真库执行。
3. 黄金基线按 `docs/architecture.md` 约定关闭调度与回写 worker，该路径不在端点目录中。
4. 缺陷表现依赖 `applied_rows` 的取值，恰好是"有活跃数据就不写回、没有变化才写回"，容易被误读为"偶发"。

## 5. 方案与步骤

### 5.1 建立唯一权威的收敛判据（核心）

在 `service/sync/SyncFactPublicationStateService` 新增公共只读方法：

```java
public long countUnpublishedTargets(String sourceInstance, FactType factType)
```

语义：该来源实例、该事实族下，尚未发布到最新变化版本的目标数量，按 `fact_change_heads.published_version` 与目标 `change_version` 的**版本栅栏**判定；返回 0 表示已收敛。

实现要点（相对本方案初稿的修订）：

- **不引入镜像运行维度**。初稿曾以 `mirror_run_id` + `fact_type` 过滤本轮目标，实现时改为来源级：消费者本身按来源合并全部历史目标，且目标在再次变化时会被新运行改绑 `mirror_run_id`，"本轮目标"集合并不可靠；版本栅栏不受改绑影响，是唯一稳定口径。因此该方法不含运行编号参数。
- **版本栅栏而非状态字段**。目标 `publication_status` 可能与真实发布版本脱节，故判据只看版本。该 SQL 原先已存在于本服务的私有方法 `hasPendingTargets` 中（服务 `recordMirrorCompletion` 用它判断"是否存在可消费意图"），本次把 SQL 上移为该公共方法，私有方法改为委托调用，保证同一语义只有一处实现。
- 事实族 BLOCKED 时其目标必然保持未发布，由收敛判据自然覆盖，不额外增加就绪分支。

### 5.2 闸门改用该判据

`CustomerIssueDelayPreWritebackSyncService`：

- 事实等待改名为 `awaitIssueFactPublication`，判据改为 §5.1 的方法，只等待 `FactType.ISSUE`（延期事实只来自 `issue_fact`）。
- **删除 10 秒"出现窗口"**（`FACT_TASK_APPEAR_WAIT_SECONDS`）与 `factRefreshRunStatus` 的 `parent_run_id` 查询：新模型下没有"子运行"可等，窗口式判定本质不可靠。
- **删除 `applied_rows > 0` 才等待的条件**。该条件原本充当"本轮是否可能有事实变化"的代理，但在新判据下必要性已由收敛状态本身回答；保留它会在"本轮无增量但来源仍有未发布目标"时基于落后事实比对标签，正是要消除的缺陷类别。因此镜像运行成功后一律等待 ISSUE 收敛。
- 结果类型由 `PreWritebackSyncResult(success, factsRefreshed)` 简化为 `boolean`：`factsRefreshed` 全仓无读者，属死状态。
- 总超时语义保持"最多等待多久"，超时后返回失败并跳过本轮（保守：不基于未收敛的事实写标签）；等待收敛与超时都输出结构化日志（`mirrorRunId`、`sourceInstance`、`factType`、未发布目标数、等待时长）。

### 5.3 清除死参数与同源失效查询

- `service/sync/SyncRunSubmissionService.java:189-201` 的 `submitFactRefresh(config, parentRunId, full, reason)`：生产唯一调用方恒传 `null`，`parentRunId` 已是死参数。按"不留兼容层"的仓库纪律**直接删除该参数**（方法签名变为 `submitFactRefresh(config, full, reason)`），同步更新 `service/sync/SyncRunFactPublicationCoordinator.java:54` 与其测试。
- 随之删除 `sameFactRefreshParent` 与"按父运行区分事实刷新"的复用分支：复用条件改为"同一互斥域内存在活跃的 `FACT_REFRESH`"。注意 `exclusiveScopeOf` 对 `FACT_REFRESH` 与镜像类型返回**同一互斥域**，因此复用条件必须同时校验运行类型，不能简化为"存在活跃运行"。生产侧语义与改动前一致（生产创建的事实运行父编号恒为空，原条件实际等价），仅在存在历史父运行数据时由"再插一条消费者"变为"复用唯一消费者"，后者才符合来源级唯一消费者语义。
- `service/RealtimeWorkspaceRefreshProgressService.java:83-90` 的 `left join lateral ... where child.parent_run_id = mirror.id` 是同一失效假设。核查结论：该联接取出的 `fact_run_id`/`fact_status`/事实时间戳在**所有可达路径中均为死值**——事实阶段状态在需要时由 `sync_run_publication_fences` 权威派生，`RealtimeWorkspaceService` 在不需要事实刷新时提前返回，其唯一读取 `factRunId` 的分支（"等待事实刷新任务提交"）因此不可达。故直接删除该联接、事实时间戳与 `factRunId` 字段及不可达分支，事实阶段状态统一由发布栅栏决定；不新增替代联接，避免复活已被架构废止的"镜像运行↔事实运行"耦合。

### 5.4 可观测性

失败、超时与成功日志统一携带：`mirrorRunId`、本轮登记目标数、未发布目标数、事实族就绪状态、实际等待时长。目的有二：内网排障可直接定位；为 §5.6 的超时定值提供真实测量依据。

### 5.5 测试（先加测试，后改实现）

仓库已有本领域真库测试基建：基类 `backend/src/test/java/com/data/collection/platform/service/sync/PostgresIntegrationTestDatabase.java:9-53`（Testcontainers `postgres:16-alpine`，或环境变量 `TEST_POSTGRES_JDBC_URL/USERNAME/PASSWORD` 走外部库，无 Docker 时以 `Assumptions` 跳过），样例 `SyncFactPublicationStateServiceIntegrationTest`、`FactTargetPublicationServiceIntegrationTest`、`SyncFactPublicationFenceServiceIntegrationTest`。

1. **闸门单元测试**（新增 `CustomerIssueDelayPreWritebackSyncServiceTest`，mock 协作者 + 真实 `GitlabMirrorProperties`）：镜像运行成功且 ISSUE 族已收敛 → 放行；未发布目标先有后无 → 等待后放行；超时仍不收敛 → 返回 false；镜像运行失败 → false 且不查询发布状态；未配置来源表 → false 且不提交同步；预写回开关关闭 → 返回 true 且不提交。
2. **收敛判据真库集成测试**（扩展 `SyncFactPublicationStateServiceIntegrationTest`）：同一根存在未发布目标（且状态为 `PUBLISHED`）仍计入 → 证明按版本栅栏而非状态；已发布版本不减计数；其他事实族（`MERGE_REQUEST`）未发布不阻塞 ISSUE 收敛；无 `fact_change_heads` 行的目标不计入（锁定既有内联接语义）；不同 `mirror_run_id` 的同根目标按来源合并计数。
3. **受影响既有测试同步更新**：`CustomerIssueDelayClosureSchedulerTest`（改为布尔返回值）、`SyncRunSubmissionServiceTest`（复用/新建事实运行三条用例改为来源级语义并断言无父运行）、`SyncRunFactPublicationCoordinatorTest`（去掉父运行参数断言）、`RealtimeWorkspaceServiceTest`（记录结构去掉事实运行身份字段）。
4. 默认快速套件保持秒级：真库测试按现有同类方式组织，Docker 不可用时跳过，不得引入非确定性或网络依赖。

> 关于"回归锚点"的准确表述：`test_mirrorAppliedRowsWithoutParentedFactRun_returnsTrue` 锁定修复后的契约；旧实现在同一场景被跳过是**行为级证据**（真实链路 `applied_rows=7` → `no fact refresh run was queued` → 整轮不写回，见 §4.1），不是由该单元测试直接反证——旧实现依赖 `JdbcTemplate` 查询，无法在不引入其依赖的前提下构造对照运行。


### 5.6 超时定值（本次不定值，明确留待实测）

本地观察：排队的同步任务可被其他长任务压到约 3 分钟才被泵执行，而默认超时为 180 秒。但这是本地单实例现象，按仓库纪律不得据此外推内网容量。本次只落地 §5.4 的测量输出；默认值是否调整、调整为多少，待内网按真实测量分布单独决定，并作为独立决策记录。

### 5.7 文档同步

- `docs/architecture.md`：在事实发布与写回相关段落补写"写回前的发布收敛判据"（明确它读 `sync_run_fact_targets` + 就绪状态，不再依赖父子运行）。
- `docs/decisions.md`：新增一条决策（编号顺延 D-14），记录"跨子系统模型变更使下游私有查询静默失效"的教训、新判据的权威归属，以及"删除死参数、不留兼容分支"的处理。
- `docs/platform-page-business-rules.md:390`（第 14 条）：把"必须先增量刷新……并完成事实刷新"细化为可执行判据表述，语义不变。
- 本方案完成后按 AGENTS.md 删除本计划文件，结论压缩进上述权威文档。

## 6. 决策记录

| 项 | 结论 | 理由 |
|---|---|---|
| 判据归属 | 新增读法放在 `SyncFactPublicationStateService` | 它是发布就绪语义的唯一权威，且已持有两张表访问；写在写回侧会产生第二份发布语义 |
| 等待语义 | 由"等子运行出现"改为"等目标收敛" | 新模型无子运行；窗口式判定不可靠 |
| BLOCKED 处理 | 保持等待至超时后跳过 | 保守：宁可不写标签，也不用未收敛事实写标签 |
| 死参数 | 删除 `submitFactRefresh` 的 `parentRunId` | 生产恒为 null；按仓库开发期纪律不留兼容层 |
| 已否决 | 重绑父运行 / 闸门内自调事实构建 / 关闭预同步绕过 / 新增开关或回退分支 / 本次调超时默认值 | 分别违反权威模型、单一权威所有者、根因优先与实测纪律 |

## 7. 接口契约

- 新增公共只读方法：`SyncFactPublicationStateService.countUnpublishedTargets(String sourceInstance, FactType factType) → long`（只读、幂等、无事务副作用；版本栅栏口径；服务内私有 `hasPendingTargets` 委托它，语义只有一处实现）。
- 变更：`SyncRunSubmissionService.submitFactRefresh` 由 `(config, parentRunId, full, reason)` 改为 `(config, full, reason)`；`SyncRunFactPublicationCoordinator` 与测试同步更新，不保留旧重载；随之删除私有 `sameFactRefreshParent`。
- 变更：`RealtimeWorkspaceRefreshProgress` 记录去掉 `factRunId`；事实阶段状态只由 `sync_run_publication_fences` 派生，不要求事实刷新时返回 `null`。
- 无表结构变更、无迁移、无端点新增或删除；`FACT_REFRESH` 运行仍为无父运行。
- 黄金基线：写回路径不与端点绑定（`endpoint-catalog.yml` 无对应条目，`docs/architecture.md` 约定该路径 EXCLUDED），且本次不改任何端点产出 ⇒ **无需更新快照**、不触发全链路门禁。
  - 逐项核对：含 `factStatus` 的 5 个快照（customer-issues 的 records/illegal-records status、code-review 的 multi-board status、question-metrics 的 illegal-records status）记录的均为 `factStatus = null`，与本改动后"不要求事实刷新时返回 null、要求时由发布栅栏派生"的结果一致；`factRunId` 不出现在任何控制器 DTO 或前端类型中（仅内部记录使用），删除它无外部契约影响。
- 配置项：`platform.gitlab-mirror.customer-issue-delay-pre-writeback-sync-timeout-seconds` 语义不变（本次不改默认值）。

## 8. 风险与假设

- **竞态假设（需在测试中固化）**：镜像运行 SUCCESS 即意味着其目标已在同一事务内登记完毕（`docs/architecture.md:43`），因此判据不会因"目标尚未登记"而误判为收敛。若该假设在某条来源表路径上不成立，判据需退化为"就绪水位 ≥ 本轮运行"。
- **重复写回**：发布等待期间议题若再次变化，可能产生第二次写回；`add_labels`/`remove_labels` 具备幂等性，本地实测已确认无害（重投会按最新镜像判定并 SKIPPED）。
- **行为放宽的边界**：修复后写回会在"有增量"的周期发生，这是预期变更；但因此首次真正执行写回的周期可能与历史行为不同，内网启用前仍需按 `docs/platform-page-business-rules.md:390` 的要求获得显式批准。
- 未验证：内网真实同步排队与发布收敛时长的分布（本次只有本地观察，不构成容量结论）。
