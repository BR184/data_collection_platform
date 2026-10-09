# 黄金门禁三项差分收口：提交事实全量构建缺失 + 业务日漂移 + F9 快照重建（2026-10-08）

> 用户指令："再次复审检查问题是否真存在、是否有其他没发现的问题或发现错的问题，然后参考业内成熟方案，给出修复更新调优最终的设计方案。"本计划是该工作单元的唯一实施与恢复入口，待用户批准后实施。

## 进度与中间物

- 状态：**F1/F2/F3 已实施并通过定向验证，F4 待执行**（用户已批准"按 F1→F4 顺序实施"）。
- 已完成（本会话取证，全部有代码级证据）：
  - 门禁实跑红：`mvn test -Pgolden-baseline -Dtest=GoldenBaselineChainTest`（compare 模式，未带 update）= **196 项跑完 4 项失败**；运行前已停 18080/18181、跑完已用标准脚本重启并验证 UP（`/actuator/health`=`{"status":"UP"}`、18181 `200`）。
  - ① `gitlab-sync/get___status__by-config.json`：用户已确认为 F9 有意变更。
  - ② 两张日期快照：用户已裁定"因为日期不同而红肯定是不对的"。
  - ③ `bi/get___coding__day-all.json`：根因定位并复核成立（见「证据与根因」）。
- 已完成（实施）：
  - **F1**：`FactBuildService` 提交关系装载器拆为 `loadMergeRequestCommitFacts(String)`（全量、走未收窄入口）与 `loadMergeRequestCommitFactsByRootIds(String, List<Long>)`（按根、空根抛异常）；删除"空根即早退"守卫；三处调用点（full 分支、按根构建、增量分支）同步更新。`mergeRequestCommitSourceSql()` 恢复被生产调用。
  - **F2**：新增 `GitlabFactMergeRequestCommitSourceRootScopeEquivalenceTest`（真实 PG，4 例：全量 vs 按根逐行一致、按根只保留最新 Diff 且剔除旧 Diff/已删除/缺时间提交、空根抛异常、无未解析占位符）；`FactBuildServiceTest` 补两例调用点断言（全量路径发出一次未收窄提交查询且不含 `target_roots`、按根路径含 `target_roots`）。已做 RED→GREEN 验证（临时恢复缺陷时新断言恰在 `FactBuildServiceTest:356` 失败，回退后通过）。
    - **偏差记录（T2）**：计划中的 T2"真实 PG 全量 MR 事实构建集成测试"未按原样实现。原因：`rebuildMergeRequestFactsForConfig` 依赖 `MERGE_REQUEST_SOURCE_SQL` 的十余张 ODS 表联合，硬造完整夹具等于复制黄金夹具；而**黄金基线本身就是该路径的端到端集成断言**（③ 正是由它发现）。故以 T1（SQL 等价）+ T3（调用点接线，含 RED→GREEN）覆盖缺陷的两种成因（SQL 语义、接线错误），端到端留给黄金门禁。此偏差已向用户报告。
  - **F3**：新增 `BusinessDateProvider`（业务日单一来源接口）与 `SystemBusinessDateProvider`（`@Component`，默认 `LocalDate.now()`）；`BiCustomerIssuePageService`（删除 `select current_date` 与 `JdbcTemplate` 依赖）、`CustomerIssueCustomerStatisticsBoardService`（`defaultControlParams`、`parseBusinessDate` 缺省分支）、`BiDashboardRuntimeFactory` 全部改用注入来源；两个 board/页面测试补第 9 个构造参数（`new SystemBusinessDateProvider()`）、`BiCustomerIssuePageServiceTest` 改为注入 mock provider；`GoldenBaselineChainTest` 新增 `@TestConfiguration`（`@Primary`）从 `baseline-manifest.json` 的 `frozen_at` 派生固定业务日；`GoldenBaselineSupport.frozenBusinessDate()` 提供该派生。
  - 定向验证：`BiCustomerIssuePageServiceTest`、`CustomerIssueCustomerStatisticsBoardServiceTest`、`CustomerIssueCustomerStatisticsDetailContractTest`、`FactBuildServiceTest`、`GitlabFactMergeRequestCommitSourceRootScopeEquivalenceTest` 合跑 **30/0/0 全绿**；`mvn test-compile` 通过。
  - 全量默认快速套件：`mvn test` **1681 项通过、0 失败 0 错误**（1 跳过，6 分 16 秒，较此前 1675 增加 6 项新增用例）。
- F4 执行记录（顺序实跑）：
  1. 停 18080/18181（PID 3284 java / 29264 node，均属本仓库，已验端口关闭）。
  2. **compare 判读通过**：196 项 3 失败（③ 已转绿），余三条为预期差异 [11] `statistic-boards/…customer-issue-customer-statistics.json`、[159] `bi/get___customer-issues__default.json`、[141] `gitlab-sync/get___status__by-config.json`，无第四条。日志 `golden-baseline-compare-20261009.log`。
  3. **update 重建**：196/0/0，BUILD SUCCESS；但 update 模式重写了 **71 份**快照，其中仅 3 份为语义变更（另 68 份仅比对期已掩码的易变字段：`generatedAt`/`queryDurationMs`/`lastSyncTime`/JSON 键序）。日志 `golden-baseline-update-20261009.log`。
  4. **复跑 compare（首次）**：196 项 1 失败 1 错误。失败 [141] 暴露一个此前未覆盖的缺口——F9 新增的逐运行时间戳 `$.data.logs[*].latestProgressAt`、`$.data.logs[*].eventTrail[*].createdAt` 未被目录掩码覆盖（既有一组同属运行态易变的 `runId/queuedAt/startedAt/finishedAt` 已掩码），故快照无法稳定；错误 [97] 为**已知间歇** `POST /api/code-review/illegal-records/rule-config/preview :: default-rule` 的 120s 读超时（该端点在 2026-09-28 记录过同类间歇，且本轮 compare/update 两跑均未复现）。
  5. **掩码补齐**：在 `endpoint-catalog.yml` 的 `/api/gitlab-sync/status` 条目新增上述两条 `ignore-paths`（与同组既有掩码同类，属运行态易变时间戳，非放宽语义比对）。
  6. **快照收口（用户裁定）**：只保留 3 份语义快照，其余 68 份以 `git restore` 还原为仓库既有内容（用户明选"只提交 3 份语义快照"）。
  7. **复跑 compare（收口轮）**：进行中 → 预期 196/0。
- 待实施：**F4 剩余**（收口轮 compare 转绿 → 重启 18080/18181 并验 UP → 六项守卫 + `git diff --check` → 提交）。


## 恢复线索

- 当前阶段：F1/F2/F3 已实施并通过定向验证，F4 待执行。
- 恢复后建议执行的首条命令：`git status --short --branch`；随后执行 F4（停 18080/18181 → compare → update → 复跑 compare → 重启服务并验 UP）。
- 审查基线 commit：`1a43c834`（本轮开始时工作树干净）；证据定位以类名与方法名为准，行号只辅助阅读。

## 目标与边界

- 用户原始需求：复审三项差分是否真存在、有无漏查或误判；参考业内成熟做法给出最终修复/更新/调优设计；不打补丁、不堆特例。
- 可验证的成功标准：
  1. 全量 MR 事实构建在真实 PostgreSQL 上发布提交关系（`merge_request_commit_fact` 落行），按根构建只影响指定根；两者由集成测试锁定。
  2. MR 提交来源 SQL「全量 vs 按根」在同一份夹具上逐行逐列等价，空根集合抛异常；测试形态对齐既有 `GitlabFactIssueSourceRootScopeEquivalenceTest`。
  3. 黄金环境业务日固定（由 `baseline-manifest.json` 的 `frozen_at` 派生），compare 在任意运行日期结果一致；生产环境业务日仍为当日。
  4. 默认快速套件全绿（1675 + 新增用例）、守卫脚本与 `git diff --check` 全绿。
  5. 黄金收口：③ 在 compare 中转绿；② 两张快照一次性差异经获批 update 重建；① F9 快照经同轮 update 重建；最终 compare 全绿。
- 明确禁止：不给空集合加特例；不改生产期业务日语义；不手工编辑快照；update 只在本单元收口轮执行且先于 compare 复跑；不推送任何远端；不运行 benchmark profile。

## 约束与背景

- 黄金运行契约：backend 目录 `../tools/maven/apache-maven-3.9.9/bin/mvn.cmd test -Pgolden-baseline -Dtest=GoldenBaselineChainTest`；运行前停 18080/18181、跑完用 `pwsh -File backend/run-backend.ps1` / `frontend/run-frontend.ps1` 重启并验证 UP；重建快照必须显式 `-Dgolden.update=true` 且事后 git diff 人工审阅。
- 共享环境：工作树可能同时存在其他工作单元改动，本单元只触碰「接口契约」所列文件；后端与前端全量套件串行跑（并行会让前端 mount 用例触发 5 秒默认超时）。
- 夹具版本：`baseline-manifest.json` 的 `frozen_at` = **2026-09-01T13:22:08+0800**，`repo_commit` = `c58ff5ac…`；夹具自 2026-09-29 零改动。
- 依赖已就位：既有等价对拍测试 `GitlabFactIssueSourceRootScopeEquivalenceTest`（ISSUE 侧，真实 PG + 小夹具逐行对拍）是 F2-T1 的直接模板。

## 证据与根因

### 复审结论总表

| 差分 | 快照 | 复审结论 |
|---|---|---|
| ① | `gitlab-sync/get___status__by-config.json` | 真存在，系 F9 有意变更（用户已确认） |
| ② | `statistic-boards/…customer-issue-customer-statistics.json`、`bi/get___customer-issues__default.json` | 真存在，系基线把运行当日写进快照的设计缺陷（用户已裁定要修） |
| ③ | `bi/get___coding__day-all.json` | 真存在，系 `e359c8bc` 引入的真实回归 |

门禁 196 项中其余 192 项全绿，无其他隐藏差异；复核未发现新的问题，也未发现此前结论错误。

### ③ 根因链（每环可验证）

1. 全量 MR 事实构建的唯一生产触发点：`FactRefreshTaskWorkerService.rebuildFullFacts`（`:139`）→ `FactBuildService.rebuildMergeRequestFactsForQueuedTask(config, true, progress)` → `rebuildMergeRequestFactsInternal(full=true, …)`。
2. full 分支调用 `loadMergeRequestCommitFacts(sourceInstance, List.of())`，以空根集合表示"全部"（该调用两版逐字相同）。
3. `e359c8bc` 把该方法改成"根集合为空即早退返回空列表"（`FactBuildService:895-900`，`git blame` 指 `e359c8bc`；方法签名属 `c367258a6`）。改动前空根集合表示"不追加根谓词、走未收窄查询取全部"。
4. 于是 `publishFullMergeRequestSnapshot(sourceInstance, facts, commitFacts=[])` 以空提交快照收尾调用 `deleteFactsNotInSnapshot(…, [])`，其契约明确"空提交快照会删除相应旧提交关系"（`MergeRequestFactPersistenceService:135` 注释，方案里另有 30 万行基准场景佐证）→ 全量构建不仅不写、还会清空已有提交关系。
5. `BiCodingCommitFactRepository` 读 `merge_request_commit_fact ⋈ merge_request_fact` → 空 → `submission-trend` 分区 `READY`→`INCOMPLETE`、`submissionTrend` 194 点→0。
6. 为何只在黄金链路暴露：黄金链路 = 全新库 + 全量构建（日志 `"fullBuild":true` ×12）；本机开发库的 31,819 行 `fact_refreshed_at` 全部 ≤ 2026-09-29（早于 `e359c8bc`），即增量/按根路径未见异常是因为这些行由更早写入留下。

### ③ 复核中新确认的三个边界事实

- **同文件内已有正确先例**：ISSUE 侧是两个方法 `loadIssueFacts`（全量）/`loadIssueFactsByRootIds`（按根），只有 MR 提交侧被并成一个带早退的方法。本修复就是让 MR 提交侧与 ISSUE 侧同形态。
- **无同类第二处**：`GitlabFactSourceSqlProvider` 共 3 个 `ForRoots` 方法，调用点分别位于 `:474`（ISSUE）、`:603`（MR 父事实）、`:904`（MR 提交）；`FactBuildService` 内其余 `return List.of()`（`labelsOf`、`partition`、`distinctRootIds`）均无"空 = 全部"语义；未收窄入口 `mergeRequestCommitSourceSql()` 当前为孤儿（生产零调用，仅测试断言形状），修复后恢复使用。
- **测试缺口可复述**：`FactBuildServiceTest` 为纯 Mockito（执行器/SQL 提供器全部替身）；`GitlabFactSourceSqlProviderTest` 只断言 SQL 形状与空根抛异常；ISSUE 有等价对拍而 MR 提交没有——"全量构建发布提交关系"这一行为此前**零测试覆盖**，故 1675 条套件全绿仍漏检。

### ② 业务日的全部来源（三处）

1. `BiCustomerIssuePageService:152`：`select current_date`（DB 时钟）。
2. `CustomerIssueCustomerStatisticsBoardService:1365`：`defaultControlParams()` 用 `LocalDate.now()`（Java 时钟）。
3. `CustomerIssueCustomerStatisticsBoardService:1416`：`parseBusinessDate` 缺省分支用 `LocalDate.now()`。

`GET /api/bi/customer-issues` 不接受业务日参数（`:139-157`），统计板端点虽有 `BUSINESS_DATE_PARAM` 但缺省为当日——故"目录用例传固定参数"只能覆盖其一，两服务必须共用单一注入来源。本次 compare 中其余日期派生字段均被既有掩码覆盖或未出现差异，② 的范围确认就是这两张快照。

### ① F9 变更的构成

`GitlabSyncControllerResponseMapper:39-40` 的 `status.details()`/`status.pending()` 经 `git blame` 属 `982b62fe`（F9）；目录里该端点只有 `by-config` 一个用例且是唯一带 `logs` 的样例；既有掩码（`createdAt/updatedAt/dbPort/lastFullSyncAt`、`logs[*]` 时间字段）解释了为何只有结构性新增被报出。纯新增、无删改值。

## 方案与步骤

### F1 修复 ③（根因）

把 `FactBuildService.loadMergeRequestCommitFacts` 拆成与 ISSUE 侧同形态的两个入口：

- `loadMergeRequestCommitFacts(String sourceInstance)`：全量装载，调用既有未收窄入口 `mergeRequestCommitSourceSql()`（修复后不再是孤儿）。
- `loadMergeRequestCommitFactsByRootIds(String sourceInstance, List<Long> rootIds)`：按根装载，调用 `mergeRequestCommitSourceSqlForRoots(safeRootIds)`；`distinctRootIds` 后为空即抛 `IllegalArgumentException`（与 `issueSourceSqlForRoots` 的严格契约一致）。
- 全量路径（`rebuildMergeRequestFactsInternal` full 分支）改调全量入口；两处按根路径（`rebuildMergeRequestFactsByRootIds`、增量分支）改调按根入口。
- 删除"空根即早退返回空列表"的守卫（它既与 provider 的 `rootCount` 抛异常重复，又把契约冲突变成静默空结果）。

### F2 补 ③ 的回归测试（补上漏检的那三层）

- T1（SQL 等价对拍，真实 PG）：新增 `GitlabFactMergeRequestCommitSourceRootScopeEquivalenceTest`，镜像 `GitlabFactIssueSourceRootScopeEquivalenceTest`——小夹具上全量 vs 按根在根集合内逐行逐列一致、按根保留每根自身数据、空根抛异常。
- T2（装载层行为，真实 PG）：新增全量 MR 事实构建集成测试——种最小 ODS 行（`merge_requests` + `merge_request_diffs` + `merge_request_diff_commits`），跑全量构建断言 `merge_request_commit_fact` 落行且与 ODS 一致；再跑按根构建断言只影响指定根。**这条就是当初缺失、导致缺陷漏网的那条测试。**
- T3（调用点约束，Mockito）：`FactBuildServiceTest` 补断言——全量路径调用未收窄入口（防止以后再次把 full 路径接到按根装载器上）。

### F3 收口 ②（业务日单一来源）

- 新增 `BusinessDateProvider`（业务日单一来源）与 `SystemBusinessDateProvider`（默认 `LocalDate.now()`）；替换上述三处取日逻辑，删除 `select current_date`。
- 黄金链路：`GoldenBaselineChainTest` 的测试配置以 `@TestConfiguration` + `@Primary` 注入固定业务日，取值 = `baseline-manifest.json` 的 `frozen_at` 转日期（当前 = 2026-09-01）；生成与比较两阶段一致。
- 效果：compare 在任意运行日期稳定；[11]/[159] 在 F4 的获批 update 轮一次性重建为钉死日期内容（窗口 2026-01-01→2026-09-01、数据行不变），此后不再随日历漂移。

### F4 门禁收口（顺序固定）

1. 停 18080/18181。
2. compare：预期 [155] 转绿（F1 生效），仅剩 [11]/[159]（F3 的一次性钉死差异）与 [141]（F9）三条预期差异——任何第四条差异都按无意差异停下修实现。
3. update（`-Dgolden.update=true`）重建这三份快照 → `git diff` 人工审阅（数据非空、字段符合契约）。
4. 去 update 复跑 compare：196/0 全绿。
5. 重启 18080/18181 并验证 UP；六项守卫 + `git diff --check`；提交。

## 决策记录

- **采用"双入口"而非恢复"空 = 全部"**：业内惯例（SQL `IN ()` 为空、Spring Data `findByXxxIn(emptyList)` 返回空集合）认定"空集合 = 空结果"，旧版"空 = 全部"是隐式通配；但把空集合一律判为空结果会让全量路径无声断供——故"全部"必须是一等入口。同文件 ISSUE 侧 `loadIssueFacts`/`loadIssueFactsByRootIds` 是现成先例，直接对齐。
- **删除早退守卫而非在装载器里重复抛异常**：provider 的 `rootCount` 已严格抛异常并有测试锁定；装载器重复判定既冗余又有害（静默吞掉契约冲突）。
- **② 选钉死业务日**：用户已裁定日期漂移为红不对；否决掩码（趋势数组每天变长，掩码 = 丢弃整段断言，违背门禁目的）；否决窗口锚定数据最大日期（改变产品显示语义，且落后数据时曲线形态变化需另行评审）。注入值取 `frozen_at`（随夹具版本自更新，不引入散落常量）。
- **① 快照重建压到收口轮**：update 模式只写不比，提前执行会把 ③ 的当前坏输出写进快照；三项差异在同轮一次性收口。
- **生产行为零变化**：F1 是恢复全量构建的预期行为；F3 生产路径仍为当日，仅测试环境注入固定值。
- **补掩码而非改响应（F4 新发现）**：`/api/gitlab-sync/status` 的 F9 新增字段里，`logs[*].latestProgressAt` 与 `logs[*].eventTrail[*].createdAt` 是逐运行刷新的事件/进度时间，快照无法稳定。修法是在目录补两条 `ignore-paths`，与同组已掩码的 `runId/queuedAt/startedAt/finishedAt` 完全同类（运行态易变时间戳），不改变任何语义断言；否决"改响应去掉时间"（会改产品输出）与"把端点挪入 EXCLUDED"（丢掉整端点的结构断言）。
- **快照提交范围（用户裁定）**：update 模式重写 71 份，只保留 3 份语义快照，其余 68 份（仅掩码内易变字段与键序差异，compare 语义等价）以 `git restore` 还原。理由：提交差异可逐行审阅，且掩码字段的值不参与 compare，还原不改变门禁结果。

## 接口契约

- `FactBuildService`（私有方法变更，无对外 API 变化）：删除 `loadMergeRequestCommitFacts(String, List<Long>)`，新增 `loadMergeRequestCommitFacts(String)` 与 `loadMergeRequestCommitFactsByRootIds(String, List<Long>)`；三处调用点同步更新。
- 新增（业务日单一来源）：`BusinessDateProvider`（`LocalDate businessDate()`）与 `SystemBusinessDateProvider`（默认当日）；`BiCustomerIssuePageService`、`CustomerIssueCustomerStatisticsBoardService` 改用注入来源。
- 黄金 harness：`GoldenBaselineChainTest` 新增 `@TestConfiguration` 固定业务日（`frozen_at` 派生）。
- 无表结构变化、无 Flyway 迁移、无对外 API 契约变化；无需登记新迁移校验和。

## 风险与假设

- 假设 `frozen_at` 的日期不早于夹具数据的最大业务日（夹具 2026-09-01 生成，数据上界即当日）；夹具刷新时快照随新 `frozen_at` 一次性重建。
- [11]/[159] 的一次性快照重建属有意变更，必须人工审阅 git diff（重点：数据行不变、窗口按钉死日收敛）。
- F1 修复后，任何曾被"空提交快照"清理过的环境需要一次全量 MR 事实重建补齐提交关系（部署后数据步骤，写入交付说明）。
- 假设 `merge_request_commit_fact` 的消费方只有 BI 编码页（已全仓核对：写入/清理 `MergeRequestFactPersistenceService`、读取 `BiCodingCommitFactRepository`、能力判定 `GitlabMergeRequestCommitFactCapability`、静态血缘声明 `BiCodingCalculator`）。
