# BI 内网两缺陷修复方案：读取指纹窗口报错 与 代码走查缺陷密度恒空

> **文档版本**：v1.1（2026-09-14，问题二工作单元 B 已实施；问题一单元 A 与工作单元 C 待审批）
> **文档性质**：独立工作单元实施计划（跨 BI 读取层 + 口径/基线；数据侧根治单独立项不在本单元）
> **权威依据**：`docs/bi-dashboard/progress.md` 与 `docs/progress.md` 2026-09-14 条目（内网实测根因）、《BI看板数据来源与计算口径核对表.md》、本仓源码实证
> **审查对象**：研发主管、测试负责人

---

## 进度与中间物

- **状态**：工作单元 B（问题二，密度覆盖率语义）已实施并验证全绿；工作单元 A（问题一）与 C（同步侧根治）仍待审批。
- **恢复线索**：当前阶段=B 已落地待提交/发布门禁重建快照；恢复后首条命令=`git status`（工作树含大量他人在途改动，提交时只选择本单元文件）；上一份计划=`docs/plans/bi-dashboard-feedback-and-fixes-comprehensive-20260910.md`（v2.1）。
- **已完成文件/变更清单（本单元 B）**：
  - `backend/.../bi/domain/BiCodingCalculator.java`：删除 `densityTrendComplete` 全有全无门槛；`reviewDensityTrend` 恒基于合法子集；quality-trend 分区改用 `coverageSection`；`validDensityTrendInputs` 新增稳定 MR 身份要求（CD-39 同 MR 去重前提，无身份记录无法可靠出数，按覆盖率剔除并披露）；`ReviewAccumulator.add` 对 null 工时安全（密度趋势不依赖工时，其余消费方均有非空门槛）；`RULE_VERSION` 升为 `bi-coding-v4`。不留双轨。
  - `BiCodingCalculatorTest`：23 项全绿；新增“坏行只剔自身”“冲突 MR 只剔冲突记录”“工时缺失仍出数”3 用例，并强化全合法用例的 100% 覆盖率/空消息断言。
  - `CodingStageContent.test.ts`：新增前端回归用例锁定“密度子集渲染 + 覆盖率披露”；fixture ruleVersion 同步 v4。前端组件无结构改动（`sectionPresentation` 已透传状态/消息）。
  - 《BI看板数据来源与计算口径核对表》CD-39 登记覆盖率语义（2026-09-14 经用户确认）。
  - 归因修正（经代码实证）：BI 走查 `added_lines` 直读 `code_review_formal/match_mode_records`（老平台镜像/导入），与 `ods_gitlab_merge_request_diffs` 无因果（该表只喂 `merge_request_commit_fact`）；S2-C 数据侧清洗应面向走查记录历史数据，不是补镜像表。
- **测试状态**：后端默认套件 1351 项全绿（EXIT=0）；前端 vitest 133 文件 523 项全绿；`vite build` 成功；eslint 0 错；五项仓库门禁与 `git diff --check`（本单元文件）全部 exit 0。`npm run typecheck` 仅报 `SystemTestStageContent.test.ts` 3 处 `status: 'FAILED'` 错误，属他人在途未提交改动，与本单元无关，未触碰。
- **当前阻塞/进行点**：① `/api/bi/coding` 产出有意变更（reviewDensityTrend 由空变非空、quality-trend 消息改覆盖率披露、ruleVersion v4），黄金基线 `snapshots/bi/get___coding__*.json` 需在发布门禁或用户明确要求时以 `-Dgolden.update=true` 重建并人工审阅（日常纪律禁止顺手重跑，本轮未运行）；② 工作单元 A（S1-A 索引 + S1-B 有界重试）与 C（C1~C6）仍待 D1/D4 审批；③ 提交需用户指示（工作树混在途改动，不得混提交）。

---

## 目标与边界

### 用户原始需求
内网使用暴露两个问题，出具解决方案、审批后再实现：
1. BI 看板切换不同阶段页面偶发报错「coding 页面读取期间来源版本发生变化，请重新刷新整页」；
2. 编码页「代码走查缺陷密度」曲线恒为空（注释率有、密度空）。

### 可验证的成功标准
1. 问题一：在 FACT_REFRESH 正常运行（含秒级~分钟级重建）期间反复切换编码页，不再出现用户可见的「来源版本发生变化」硬错误；短刷新窗口内读取自愈（透明重试命中稳定窗口）。
2. 问题二：在存在 added_lines=0 与同 MR 行数冲突记录的真实数据下，编码页密度趋势曲线**不再恒空**，按覆盖率语义给出基于合法子集的密度，并在分区状态/Tooltip 披露「基于 X/Y 条走查记录」。
3. 全量快速套件（后端默认 + 前端 vitest/typecheck/lint/build）与五项仓库门禁全绿；BI 产出变更经黄金基线更新模式重建并人工审阅。

### 明确禁止（红线）
- 不做双轨/兼容层：不保留「全有全无」旧门槛与「覆盖率」新门槛并存；直接改成目标版本并更新全部调用者与测试。
- 不为尚未出现的需求做抽象：指纹范围化（S1-C）默认不做，除非评估证明 coding 页对 merge_request 事实确为范围化消费。
- 数据侧根治（修 FACT_REFRESH 慢、回填 added_lines）**不在本单元实施**，单独立项；本单元只做 BI/读取层可闭环部分。
- 不手工编辑黄金基线快照；只用 `-Dgolden.update=true` 更新模式生成并交用户审阅。

---

## 约束与背景

- BI 强包边界 `com.data.collection.platform.bi`（api/application/domain/infrastructure）；读取适配器在 `infrastructure`，计算器在 `domain`。
- 黄金基线门禁：BI 的 7 个页面 GET 已登记 READ（掩码 generatedAt/sourceVersion/snapshotId）；**任何改变 `/api/bi/coding` 产出的改动都必须重建 `snapshots/bi/` 并人工审阅**（日常不跑 7 分钟全链路，仅发布/内网验收前或用户要求时跑更新模式）。
- 口径权威：《BI看板数据来源与计算口径核对表.md》；改变密度趋势口径必须登记/修订对应编号并经用户确认，不得自行发挥。
- 排序/空值全局规则：无数据恒置底、禁止哨兵坍缩（`utils/missing-value-sorting.ts`）；本单元不触碰排序。
- Flyway 迁移不可变/破坏性审查门禁（`check_flyway_destructive_migrations.py`、`check_flyway_migration_immutability.py`）：新增索引迁移须通过。

---

## 证据与根因（本仓源码实证，非转述）

### 问题一：读取指纹窗口报错
- 读取链路：`BiPlatformCodingSourceAdapter.load()` 阶段一 `sourceContextFactory.capture()` → 阶段二执行**全表** SQL（`FORMAL_QUERY`/`COMPATIBILITY_QUERY`，select 整张 `code_review_formal_records`/`code_review_match_mode_records` 后在内存按产品版本过滤，耗时数秒~数十秒）→ 阶段四 `sourceContextFactory.verifyDataVersion(context)`。
- 指纹构成：`BiPlatformCodeReviewSourceContextFactory.dataVersion()` = `PageRecordSnapshotService.codeReviewSourceVersion()` + `|review:<mode>` + `|commits:<v>`；其中 `codeReviewSourceVersion()` = `mergeRequestFactSourceVersion()`（=`FactProjectionVersionService.globalSourceVersion(DEFAULT, MERGE_REQUEST)`，即 FULL_EPOCH + GLOBAL_VIEW generation 的 SHA-256）+ match-mode 段（`code_review_match_mode_records` 的 count + max(synced_at)）。
- 触发者：每 15 分钟 INCREMENTAL_SYNC 写 match_mode 表（推 count/max synced_at）并触发 FACT_REFRESH 推 merge_request GLOBAL_VIEW generation；任一推进即改变 dataVersion。capture→verify 跨越该推进即抛 `BiSourceVersionChangedException("coding")`。
- 窗口为何长：① 全表 SQL 使单次读取耗时长；② FACT_REFRESH 自身由 ~53s 退化到 17~24min（机制与放大器见下「问题三」），使「版本持续变化」窗口被拉长。
- 结论：这是平台强一致性保护（避免撕裂读），非故障；用户可见报错是「窗口过宽 × 读取过慢」的乘积效应。

### 问题二：密度恒空
- `BiCodingCalculator`：`densityTrendValues = removeConflictingReviewLines(filter(validDensityTrendInputs))`；`densityTrendComplete` 要求 `densityTrendValues.size() == densityTrendRecords.size()` 且全部记录合法（176-181 行）＝**全有全无**。
- 门控：`reviewDensityTrend` 仅当 `densityTrendComplete` 才返回，否则 `List.of()`（248-249 行）；而 `commentRatePoints(commentValues)` 恒返回（247 行）→ 「注释率有、密度空」。
- 数据实测（内网）：27,787 条基础走查记录中 added_lines=0 共 1,446 行、同 MR added_lines 冲突共 745 个 MR，坏行每月都有 → `densityTrendComplete` 恒 false → 曲线恒空。
- 平台已有对照哲学：static-scan 与 comment-rate 用 `coverageSection`/`coverage(total, used)` 做「部分覆盖仍出数 + 披露」；quality-trend 却用 `dataSection(..., qualityTrendComplete, ...)` 全有全无，二者不一致。

### 问题三：FACT_REFRESH 由 ~53s 退化到 17~24min（同步层；2026-09-14 本地代码复核，更正内网与本人初版归因）
- **近因（数据触发，非部署）**：9/14 08:10 起每轮 `sync_run_fact_targets` 从正常 0~281 ISSUE / 1~804 MR 膨胀到 3.7~4.06 万 ISSUE / 3~4.5 万 MR（9 号项目 33,269 条 issue 被整体标记、其余 7 项目亦然），把增量刷新变成近似全量处理；08:10 无部署，属运行时数据翻转。
- **放大机制（本地代码确认）**：① 镜像 upsert 以全部来源列做 `row(...) is distinct from row(...)`（`GitlabMirrorTableStorageService.buildConflictGuard`），任一业务列变化即产生 `MirrorRowChange`；② 维表反查放大（`GitlabFactChangeResolver`）：`projects`→全部 Issue+MR、`namespaces`→仅 MR、`milestones`→仅 Issue、`labels`→仅实际关联根、`users`→按作者/处理人/评论人等角色反查；③ 定向刷新按 `factTargetBatchSize=200` 拆批，`SyncFactRefreshRunExecutor.drainFactTasks` 在活跃 FACT_REFRESH 内**连续同步**领任务执行（非“每批等 5s”；5s 仅是孤立 worker 调度/PAUSED 重试间隔）；④ 每批把 200 根 ID 追加在完整 Issue/MR CTE **末尾** `IN (...)`，labels/notes/assignees/diff 聚合无法从根集合提前过滤、每批重跑重型 CTE（单批耗时需内网慢 SQL/`EXPLAIN ANALYZE` 证实）；⑤ **遗漏放大器**：`FactTargetPublicationService.lockCurrentHeads` 对每根逐条 `SELECT … FOR UPDATE` 锁版本头（非集合锁），4 万根≈4 万次额外锁查询/事实类型；⑥ 定向路径 `replaceRootFacts` 只删当前根集合后 upsert，**不含**全源反连接删除（全源 `NOT EXISTS` 反连接仅在显式全量构建 `deleteFactsNotInSnapshot` 执行）——本人初版“增量也有全源反连接”为误判。
- **版本归属（更正）**：全列差异判定+根展开+批任务机制由 `2fd5c34e`（2026-08-03）引入；2026-08-10 `c367258a`（随 `qaflex-update-20260810T065129Z-4ca35ca63f73` 版本线发布）新增默认 MR commit facts 定向查询和来源范围历史待处理目标领取，是既有定向过载路径的明确放大因素；同日稍后的 `3a3a4ff3` 主要修维表反查批量化，不能替代 `c367258a` 的版本归属。9/4、9/7 仍改过事实构建代码，9/7 `ea5093ec` 修全量构建分批提交与租约、**未消除定向过载路径**，故“8/3 后未再改动”不成立。9/10 镜像 `94cd3a4` 不在本地对象库、无法证实；且 diff/diff_commits 直接影响 `merge-request-commit-fact-source-query`，而非主 `merge-request-fact-target-query`。完整版本边界以 `docs/decisions.md` D-13 为准。
- **触发源仍未锁定（需内网）**：先聚合 `sync_run_fact_targets.last_task_id → sync_run_table_tasks.source_table` 定位制造洪峰的源表；具体哪一列变化需上游审计/备份比对，或补充不泄露取值的字段变更统计。
- **近期提交史与因果判定**：8/3 `2fd5c34e` 引入全列差异+根展开+批任务机制；8/10 `c367258a` 引入 MR commit facts 定向查询和来源范围历史目标领取，构成增量路径的明确放大因素；同日 `3a3a4ff3` fix(sync) 修维表自动同步失败与反查性能（其批量化部分倾向优化，不能作为 `c367258a` 的替代归因）；8/21 `d15ac937` God class 解耦；9/4 `b66fe7dd` 解耦第二轮接线（FactBuildService/IssueFactRecordRepository 拆分重构，不改变更检测/根展开语义）；9/7 `ea5093ec` 全量构建分批提交+租约治理+末端 temp-table NOT EXISTS 反连接清理（修全量无进度/假超时互锁，决策 D-10）；9/9 `8159c49b` 备份域（与同步无关）。**因果判定（2026-09-14 更正：周末混淆）**：曾以“9/12-13 正常”排除近期提交/9/10 镜像，但 9/12=周六、9/13=周日，低活跃本身就会使目标集小、刷新快，该排除法**不成立**，已撤回。真实模式为**工作日爆炸/周末安静**，即触发与开发活动强相关。本地核查：b66fe7dd 仅碰前端镜像健康呈现、ea5093ec 仅全量路径、后端无 `last_activity_at`，故本地近期提交暂无“活动驱动整项目翻转”的直接证据；但 9/10 镜像 `94cd3a4` 不在本地对象库、可能含本地 main 之外的改动，**不能排除**。触发源收敛为“活动驱动的维表行变化（projects/users 等）或活动驱动的重拉使比对列翻转”，经 8/3 全列 distinct + 粗血缘放大。Fix 0 须改为**对比工作日轮 vs 周末轮**的逐列 diff 以隔离活动驱动翻转列/表。`ea5093ec` 全量末端反连接在夜间（02:03）超时仍为其独立副作用（全量路径），与增量爆炸分属两问题；平台通用版本归因以 `docs/decisions.md` D-13 为准。
- **本地 main 维表比对集核查**：`GitlabSourceSchemaGuard` 对 `ods_gitlab_projects` 的必需/比对列仅 `id,name,path,namespace_id,mirror_deleted`（无 `last_activity_at` 等活动列），projects/users/issues 增量游标为 `updated_at`（TIMESTAMP_KEYSET）→ 本地 main 单独并不产生“活动驱动整项目翻转”；故工作日爆炸更可能源于 9/10 镜像侧的 schema/source-SELECT 差异或纯数据翻转，须由内网 Fix 0（工作日 vs 周末逐列 diff + `last_task_id→source_table` 聚合）裁定。

---

## 方案与步骤

### 工作单元 A：问题一（读取指纹窗口）——本单元实施 S1-A + S1-B

**S1-A 缩短读取窗口（DDL，低危）**
1. 新增 Flyway 迁移，为读取热路径建复合索引：
   - `code_review_formal_records (target_branch, merged_at_source)`；
   - `code_review_match_mode_records (merge_request_state, target_branch, merged_at_source)`。
2. 目的：把全表扫变索引范围扫，capture→verify 窗口从数十秒降到秒级/亚秒级，直接降低撞窗概率。
3. 通过 `check_flyway_destructive_migrations.py` / `check_flyway_migration_immutability.py` 与 `check_flyway_profile_smoke_coverage.py`。

**S1-B 有界透明重试（读取层自愈）**
1. 在 BI 页面读取的应用层边界（`BiDashboardRuntime`/控制器调用 port 处）对 `BiSourceVersionChangedException` 做**有界重试**：最多重试 2 次，每次重新 `capture()` 再读；仍失败才向上抛（用户才见错误）。
2. 重试不改变对外契约（仍返回最终一致的 `sourceVersion`），仅内部重读；对短刷新（几十秒）场景第二次即命中稳定窗口，用户无感。
3. 单测：mock port 第一次抛 `BiSourceVersionChangedException`、第二次成功，断言返回成功且只重读有限次数；连续 3 次抛则断言最终抛出。

**S1-C 指纹范围化（默认不做，待评估）**
- `FactProjectionVersionService.sourceVersion(..., requestedScopes)` 已支持按 scope 级 generation 计算版本。若评估证明 coding 页对 merge_request 事实仅消费其产品版本范围内项目，可改 scope 级指纹使无关项目刷新不再误伤。
- 因 commits 来自 `commitRepository`（可能全局消费），GLOBAL_VIEW 或仍必要；**本单元不实施**，避免过度设计。列为后续评估项。

**S1-D 同步/事实层根治（工作单元 C；2026-09-14 复核更正后的实施清单，取代本人初版 Fix 1~4）**

前置诊断（内网，无代码）：聚合 `sync_run_fact_targets.last_task_id → sync_run_table_tasks.source_table` 锁定洪峰源表；对嫌疑表做 before/after 逐列 diff 计数定位翻转列（或上游审计/备份比对、补充不泄露取值的字段变更统计）。

- **C1 根因：为派生源表声明“影响事实的字段集合”**：在 `GitlabSourceLineageCatalog.SourceDefinition` 增字段集（如 `factRelevantColumns`）；ODS 仍保存全部变化，但仅相关字段、主键/关联键变化、删除/恢复才登记事实目标；未知表/字段必须显式登记、不得静默忽略（沿用现有“必需字段/消费者”强校验风格）。
- **C2 重写定向 SQL**：把 200 根 ID 放到查询**开头**的 `target_roots` CTE/VALUES，使 labels/notes/assignees/diff 聚合从根集合开始过滤；废除“完整 CTE 末尾追加 `IN (...)`”（`GitlabFactSourceSqlProvider`）。
- **C3 版本头锁改集合锁**：`FactTargetPublicationService.lockCurrentHeads` 由逐根 `FOR UPDATE` 改为一次集合 `FOR UPDATE`，保留稳定排序与版本围栏语义，消除每批 200 次往返。
- **C4 过载规划器（需架构决策）**：目标数超基准阈值时改走受控“批量事实发布”，而非数百定向任务；**不得**粗暴 `full=true`——必须冻结版本上界、构建期间新版本继续保留待发布以免丢变化；现有架构明确“目标数不自动转全量”，故此项须作为一次明确架构决策更新 `docs/decisions.md`。
- **C5 并发与运维纪律**：仅在 C1~C4 优化并基准后考虑有限并发；直接增大 batch/worker 数会加重 CTE、锁与 DB 争用；**禁止**清空 outbox 或手工标记 `PUBLISHED`（造成事实遗漏）。
- **C6 保留 BI 侧兜底**：S1-A 索引 + S1-B 重试保留，作为刷新窗口再变长时的 BI 读兜底。

验收：无关字段变化不产生目标；相关字段及删除/关系迁移仍完整刷新；4 万根固定负载基准；目标 SQL `EXPLAIN ANALYZE`；版本围栏并发测试；发布验收时黄金基线。复核方已跑 25 项镜像/目标登记/发布/FACT_REFRESH 定向测试全绿、未改项目文件。

### 工作单元 B：问题二（密度覆盖率语义）——本单元实施 S2-A + S2-B

**S2-A 改为覆盖率语义（对齐既有 coverageSection 哲学，不留双轨）——已实施**
1. `BiCodingCalculator`：已删除 `densityTrendComplete` 的「全量合法才出数」判定；`reviewDensityTrend` 恒基于 `densityTrendValues`（已去冲突、已过滤合法子集）计算，子集为空时自然返回空列表。
2. quality-trend 分区已改用 `coverageSection`（与 static-scan/comment-rate 一致）：全覆盖 READY 且消息为空；部分覆盖 INCOMPLETE 并披露「有效观测 X/Y；其余记录缺少合法注释率或有效密度输入（走查日期、被走查行数大于 0、缺陷数非负且同 MR 行数一致）」；`reviewDensityCoverage` 字段继暴露精确密度覆盖率。
3. 实施中强化的两点（均经测试固定）：① `validDensityTrendInputs` 要求稳定 MR 身份——CD-39 的同 MR 行数只计一次对无身份记录无法可靠执行，按覆盖率剔除并披露而非让 null 键合并不同记录；② `ReviewAccumulator.add` 对 null 工时安行——密度趋势不消费工时，不得因此抛 NPE 也不得误剔合法记录；其余消费方（review-quality/module/scatter）均有非空工时门槛，行为不变。
4. 前端 `CodingStageContent.vue` 无结构改动（已验证：`qualityTrend` 对缺日期补 null、`sectionPresentation` 透传状态/消息）；新增 `CodingStageContent.test.ts` 回归用例锁定密度子集渲染与覆盖率披露。
5. `BiCodingCalculatorTest` 已更新：全合法用例增加 100% 覆盖率/空消息断言；新增坏行、冲突 MR、缺工时三用例；原“坏行→整条空”用例（缺日期/全冲突/无身份）在新语义下仍成立（子集恰为空）未改语义只验断言。

**S2-B 口径登记与基线重建（必须，因改变 BI 产出）——口径已登记，基线待发布门禁**
1. 已在《BI看板数据来源与计算口径核对表.md》CD-39 登记：「仅纳入走查日期非空、具备稳定 MR 身份、被走查行数 > 0、缺陷数非负且同 MR 行数一致的记录；坏行只剔除自身、按合法子集出数并以‘有效观测 X/Y’披露覆盖率，不再因坏行整条置空（2026-09-14 经用户确认）」；`RULE_VERSION` 升为 `bi-coding-v4`。
2. `/api/bi/coding` 产出变化（reviewDensityTrend 由空变非空、quality-trend 消息与 ruleVersion 变化）：按门禁纪律，黄金基线 `snapshots/bi/get___coding__*.json` 在发布打包/内网验收前或用户明确要求时以 `-Dgolden.update=true` 重建并交用户审阅；日常纪律禁止主动运行 7 分钟全链路，本轮未执行（默认套件不含该比对，已验全绿）。

**S2-C 数据侧根治（单独立项，不在本单元）**
- 回填 added_lines=0（GitLab diff 源）、统一同 MR 行数口径；导出 1,446 条 + 745 个冲突 MR 清单核对。

### 依赖与顺序
1. 先 A（S1-A 索引迁移 → S1-B 重试）再 B（S2-A 计算器 → S2-B 口径+基线），二者相互独立可并行但建议串行以便基线一次重建。
2. S2-B 基线重建必须在 S2-A 代码落地后、提交前完成并审阅。

---

## 决策记录

| 编号 | 决策事项 | 建议 | 理由 / 否决备选 |
|---|---|---|---|
| **D1** | 问题一本单元范围 | 只做 S1-A（索引）+ S1-B（有界重试） | 低危、可验证、直接降低用户报错。否决：S1-C 指纹范围化（收益不确定、恐过度设计，列待评估）；S1-D 数据侧根治跨同步层需内网数据，单独立项。 |
| **D2** | 问题二语义 | 改覆盖率语义（S2-A），不再全有全无——**已批准并实施（2026-09-14）** | 对齐平台既有 coverageSection 哲学（scan/comment-rate 已如此）；全有全无在坏行常态化下等于永久空图。否决：维持全有全无（等于不修）。 |
| **D3** | 密度新口径是否需核对表登记+用户确认 | 需要（S2-B）——**口径已登记，基线待发布门禁重建** | 改变 BI 产出与口径属业务语义变更，必须登记并经确认；同时触发黄金基线重建审阅。 |
| **D4** | 数据/同步侧根治（工作单元 C） | C1~C5（本仓代码）+ 前置诊断（内网）；C4 过载规划器须先更新 `docs/decisions.md` 架构决策 | 机制已代码确认、属真实回归且是 BI 报错直接上游，必须修；触发源定位需内网数据，与代码加固解耦。否决本人初版 Fix 2（直接调大 batch/worker）与 Fix 3（收窄增量反连接——增量本无反连接，属误判）。 |
| **D5** | 是否采纳内网 AI 建议#1（停集成测试客户端）为止血 | **不采纳为止血手段**；02:03 commit-fact 反连接失败属**全量构建**路径（`deleteFactsNotInSnapshot`），与定向过载无关，单独运维处理 | `integration_test_fact` 为事实输出表、不喂变更推导，停它不能减少 4 万级目标；误停会掩盖真实根因。 |

---

## 接口契约

- 不新增对外 HTTP 端点；不改变 `BiExcelExportRequest`/下载授权契约。
- 后端内部变更：
  - `BiCodingCalculator`：`densityTrendComplete` 判定与 quality-trend 分区构造改为覆盖率语义；`reviewDensityTrend` 门控移除。
  - 读取应用层：新增对 `BiSourceVersionChangedException` 的有界重试包装（内部，不暴露新 API）。
  - Flyway：新增 1 个索引迁移（两表复合索引）。
- 前端：`CodingStageContent.vue` 仅复用既有 `sectionPresentation` 披露覆盖率文案，无新 prop/新组件。
- 基线：`snapshots/bi/get___coding__*.json` 由更新模式重建（非手工）。

---

## 风险与假设

- **假设**：内网实测的 1,446/745 坏行分布与「坏行每月都有」成立，故覆盖率语义在真实数据下必然非空；若内网数据后续被 S2-C 清洗，覆盖率趋近 100%，行为退化为旧全量语义，无兼容问题。
- **风险 1**：S1-A 索引在超大表上建索引耗时/锁表——使用 `CREATE INDEX CONCURRENTLY`（若 Flyway 支持事务外）或选择维护窗口；迁移须可重入。
- **风险 2**：S1-B 重试在 FACT_REFRESH 长跑（16-23 分钟）内仍会失败——属预期，长跑由 S1-D 根治；本单元只消除短窗口报错。
- **风险 3**：S2-A 改变密度数值口径（剔除 added_lines=0 行的缺陷不计入分子）——已在 S2-B 要求核对表登记+用户确认+基线审阅，确保口径变更受控。
- **敏感只读数据**：内网 `code_review_*` 与 `ods_gitlab_merge_request_diffs` 仅只读分析，不在本单元写入。

---

## 验证与验收

1. 后端：`mvn -o -DskipTests test-compile` EXIT=0；默认套件全绿；BI 包定向全绿；`BiCodingCalculatorTest` 新增/更新用例通过；重试包装单测通过。
2. Flyway 门禁：`check_flyway_destructive_migrations.py`、`check_flyway_migration_immutability.py`、`check_flyway_profile_smoke_coverage.py` 通过。
3. 前端：`npm run lint/typecheck/test/build` 全绿；`CodingStageContent` 相关 vitest 通过。
4. 黄金基线：S2-A 落地后 `-Dgolden.update=true` 重建 `snapshots/bi/`，git diff 人工审阅（确认 reviewDensityTrend 非空、quality-trend 覆盖率字段合理），去更新模式复跑全绿。
5. 仓库门禁：`check_api_contract_drift.py`、`check_frontend_api_boundary.py`、`check_worktree_artifacts.py`、`check_runtime_artifact_locations.py`、`check_text_whitespace.py`、`git diff --check` 全绿。
6. 界面/内网验收：问题一在 FACT_REFRESH 运行期反复切换编码页无硬错误；问题二密度曲线非空且披露覆盖率（需内网或等价数据环境）。
