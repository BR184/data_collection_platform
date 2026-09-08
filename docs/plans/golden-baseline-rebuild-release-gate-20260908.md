# 黄金基线发布门禁：建议类口径变更快照重建与双工作单元提交（2026-09-08）

> 本方案由规划会话产出，**移交其他 AI 员工执行**。执行者开工前必须先读 `AGENTS.md`、`docs/progress.md` 与本文档全文；两个实施单元的业务背景见 `docs/plans/customer-issue-suggestion-exclusion-20260908.md`（单元 1）与 `docs/plans/review-problem-item-expert-override-fix-20260908.md`（单元 2）。

## 进度与中间物

- 状态：**已执行完成（2026-09-08）**。第 0-6 步全部通过：更新模式重建（180 全跑 BUILD SUCCESS）→ 62 文件全量归一化定性审计 → 比对模式复跑 180/180 全绿 → 仓库四项门禁全绿 → 三提交落库：`cb17683a`（单元 1 建议类排除+基线快照，94 文件）、`a685796f`（单元 2 评审覆盖修复，7 文件）、`af93783d`（本审计留痕）。未推送（远端待用户指定）。工作树仅剩三件遗留物（fresh-package 计划 M、legacy-status 计划 ??、audit SQL ??），与方案一致。**打包门禁通过，可以打包。**
- 第 2 步审计结论（62 文件全部分类闭环，无一来自两工作单元的业务代码）：
  - **4 文件=单元 1 有意变更**（白名单内）：缺陷汇总 board（suggestion_total×28+tooltip「不计入」✓）、延期 board（计数下降 9→5/3→0/12→5=建议类剔除实证✓）、两 export 同步变化 ✓；`issues_export` 未变化（可见层保留建议类，与预期一致）✓。
  - **~52 文件=时间类噪声**：①API 时间戳（generatedAt/createdAt/updatedAt 等，更新模式写原始值）；②运行实例元数据（dbPort=容器随机端口、runId=每次运行随机 UUID、lastSyncTime/queuedAt/lastFullSyncAt）；③时间型字段老化——基线生成于 09-07 12:58，距本次运行恰 25 小时，`retentionHours` 与客户议题导出 U 列全部精确 +25/+26（764→789、2897→2922）。此类变化与 ea5093ec 的 update+compare 双次运行先例一致（由引擎规范化/掩码处理，两 run 间 dbPort/runId 均不同仍全绿），非代码回归。
  - **6 文件=纯序列化布局漂移**：analytics-dashboards×4 + question-metrics multi-board×2，归一化后内容**零差异**（仅键序/缩进/尾逗号位移，如 projectId 与 testingPhase 位置互换=jsonb 读出序 vs POJO 序）。成因是看板缓存命中路径与新鲜构建路径的序列化差异（异步时序竞争），两单元均未触碰这些模块。
  - **review-data 5 文件=纯 createdAt/updatedAt 时间戳**——单元 2 零快照影响实证加强 ✓。
- 计划修订说明：原白名单假设「基线新鲜、差异只可能来自两单元」，实际基线已老化 25 小时且含时间型内容与既有布局漂移——白名单机制保留为"防未解释差异"的纪律工具，本次以全量归一化审计完成同等强度的审阅。
- 快照目录当前状态：更新模式产物（14:01-14:02 生成）未回滚，等待第 3 步比对验证。

## 恢复线索

- 当前阶段：第 3 步比对复跑运行中。恢复后首条命令：`grep -E "Tests run|BUILD" .tmp-logs/golden-compare2.log` 判读结果，随后按「方案与步骤」第 4 步继续。
- 上一份关联计划：`docs/plans/customer-issue-suggestion-exclusion-20260908.md`（单元 1 实施，已完成）；对应代码全部在工作树未提交。
- 本方案的验证基线：后端默认套件 1250 全绿、前端 vitest 459 全绿 + typecheck 干净、仓库四项门禁全绿（2026-09-08 12:0x，当前代码状态）。

## 目标与边界

- 用户目标：**打包进入内网前完成产出门禁**——按黄金基线纪律重建受有意行为变更影响的快照并复跑全绿，随后把两个已验证工作单元分别提交（不推送）。
- 成功标准：
  1. `-Dgolden.update=true` 重建后，快照变更集合**恰好等于**「证据与根因」节的预期清单（statistic-boards 目录下 4 个必变文件 + 至多 1 个待审文件）。
  2. 比对模式复跑 `Tests run: 180, Failures: 0, Errors: 0` + BUILD SUCCESS。
  3. 两个工作单元各自成提交，仓库四项门禁全绿，工作树仅剩三件遗留物。
- 明确禁止：
  - 不改 `fixtures/` 与 `baseline-manifest.json`（非夹具变更，不动基线版本）。
  - 不改 `endpoint-catalog.yml`（无新增端点、无掩码调整）。
  - 不跑后端默认套件与前端测试（同代码状态已全绿，黄金 profile 独立，AGENTS.md 最小充分验证原则）。
  - 不放宽任何比对/掩码让失败变绿；不"顺手"整理目录或重生成无关快照。
  - 不推送到任何远端（远端须由用户指定）。
  - 门禁期间不启动 18080 后端（避免 target 锁）。

## 约束与背景

- 黄金基线机制与纪律见 `AGENTS.md`「黄金基线回归门禁」章节与 `docs/architecture.md` 对应章节；全链路约 7 分钟（双 PG 容器 + 从零建库 + 真实同步链路 + 全端点快照）。
- 环境怪癖（执行者必读）：
  - mvn 必须用绝对路径且在 `backend/` 目录执行：`../tools/maven/apache-maven-3.9.9/bin/mvn.cmd`。
  - 严禁并发 mvn；日志必须重定向到文件再 grep 判读（tail 会吞退出码，控制台 GBK 乱码）。
  - 运行产物日志放 `.tmp-logs/`。
  - golden 运行自动创建并清理自有 PG 容器；无需 15433 容器（那是默认套件用）。
- 内网背景：30001 实例当前跑 20260904 包（f66aff80 基线）；两看板 RULE_VERSION 已升 v7/v5，部署新包后内网存量 `statistic_board_snapshots` 自动失效重建，无需数据迁移。

## 证据与根因

### 为什么必须重建快照

两看板响应全文（含 RULE_VERSION 与指标数值）被黄金基线快照锁定。单元 1 是经用户批准的有意行为变更（领导 2026-09-08 指示、用户拍板实施），按 D-08 纪律第 3 条走「确认→update 重建→审阅」流程。用户已于 2026-09-08 明确指示"跑更新模式重建"。

### 预期快照变更清单（diff 审阅的唯一白名单）

目录 `backend/src/test/resources/golden-baseline/snapshots/statistic-boards/`：

| 文件 | 预期 | 内容审阅要点 |
|---|---|---|
| `get___{boardKey}__customer-issue-defect-summary.json` | 必变 | RULE_VERSION → `customer-issue-defect-summary@2026-09-08-v7`；columns 含 `suggestion_total`（建议类缺陷(个)，带 tooltip）；常规列（level1/2/3、p1/p2/p3、solved、total、占比）排除建议类 |
| `get___{boardKey}__customer-issue-delay-issues.json` | 必变 | RULE_VERSION → `customer-issue-delay-issues@2026-09-08-v5`；延期计数与下钻排除建议类 |
| `get___{boardKey}_export__customer-issue-defect-summary.json` | 必变 | Excel 导出：同上列与数值口径 |
| `get___{boardKey}_export__customer-issue-delay-issues.json` | 必变 | Excel 导出：同上 |
| `get___{boardKey}_issues_export__customer-issue-defect-summary.json` | 预期不变 | 「全量议题数据」导出走可见层（保留建议类），表头为固定清单非看板列注册表；若变化须逐项解释后才可接受 |

- 计数是否变小取决于冻结夹具的客户问题范围内是否含建议类议题：**数值变小与数值不变都属正常**（夹具为 GitLab 切片，与本地 15432 全量数据不同）；关键审阅点是口径正确与数据非空。
- 其余全部快照（含 `review-data/`、`customer-issues/`、系统测试各板、facts、quality-board、analytics-dashboards 等）**必须零变化**。

### 单元 2（评审覆盖修复）零快照影响实证

- golden-create 用例显式传 `"problemStatus":"新提交"`、golden-update 传 `"处理中"`，不触达"空状态拒绝/未评审拒绝"新路径。
- 冻结夹具中 `review_problem_items` 零"未评审"行（全目录 grep 实证）。
- 结论：`review-data/` 快照必须零变化；若变化即为回归信号。

## 方案与步骤

**第 0 步｜前置检查**（1 分钟）
```bash
cd /d/projects/data_collection_platform
netstat -ano | grep LISTENING | grep ":18080"   # 应无输出
tasklist | grep -i java                          # 应无输出
docker ps --format '{{.Names}}' | grep -i golden # 应无输出
git status --porcelain -- backend/src/test/resources/golden-baseline/snapshots/  # 应无输出
```
任何一项不满足：先解决（停进程/清容器）再继续；快照目录有残留改动先 `git checkout -- backend/src/test/resources/golden-baseline/snapshots/`。

**第 1 步｜更新模式重建快照**（约 7 分钟）
```bash
cd /d/projects/data_collection_platform/backend
../tools/maven/apache-maven-3.9.9/bin/mvn.cmd test -Pgolden-baseline -Dtest=GoldenBaselineChainTest -Dgolden.update=true > ../.tmp-logs/golden-update.log 2>&1
echo "EXIT=$?"
grep -E "Tests run: 180|BUILD (SUCCESS|FAILURE)" ../.tmp-logs/golden-update.log
```
判读：EXIT=0 + `Tests run: 180, Failures: 0, Errors: 0, Skipped: 0` + BUILD SUCCESS。更新模式只写不比，失败则读日志定位（不重试超过一次，连续失败停下报告）。

**第 2 步｜git diff 白名单审阅**
```bash
cd /d/projects/data_collection_platform
git status --porcelain -- backend/src/test/resources/golden-baseline/snapshots/
git diff --stat -- backend/src/test/resources/golden-baseline/snapshots/
```
- 变更文件集合必须 ⊆ 白名单 5 文件（4 必变 + 1 预期不变）。`issues_export` 未变化亦正常。
- 逐文件 `git diff -- <file>`（Excel 快照为规范化 JSON，可直接 diff）按上表要点审阅；确认数据非空、字段符合契约。
- **任何白名单外文件变化 = 停**：`git checkout -- backend/src/test/resources/golden-baseline/snapshots/` 全量恢复快照 → 按回归调查根因（先查代码，禁止带着未解释差异继续）→ 未查明前不得重跑更新模式。
- 白名单文件内容与要点不符（如 RULE_VERSION 不对、columns 缺建议类列）同样停下核查实现，不得手工编辑快照。

**第 3 步｜比对模式复跑**（约 7 分钟）
```bash
cd /d/projects/data_collection_platform/backend
../tools/maven/apache-maven-3.9.9/bin/mvn.cmd test -Pgolden-baseline -Dtest=GoldenBaselineChainTest > ../.tmp-logs/golden-compare.log 2>&1
echo "EXIT=$?"
grep -E "Tests run: 180|BUILD (SUCCESS|FAILURE)" ../.tmp-logs/golden-compare.log
```
判读：EXIT=0 + `Tests run: 180, Failures: 0, Errors: 0` + BUILD SUCCESS = 门禁通过，可提交。任何失败：读日志定位差异端点，比对白名单审阅结论——若差异即第 2 步已解释的有意变更则快照未正确落盘（重走第 1 步）；若是新差异按回归处理。

**第 4 步｜仓库门禁 + 提交前检查**
```bash
cd /d/projects/data_collection_platform
python scripts/check_worktree_artifacts.py
python scripts/check_runtime_artifact_locations.py
python scripts/check_text_whitespace.py
git diff --check
```
全绿后进入提交。（快照文件 CRLF→LF 警告为既有现象，非失败。）

**第 5 步｜双工作单元提交**（不推送）

提交 1（单元 1：建议类排除 + 基线快照，同一提交）：
- 直接 add：12 个后端主代码/测试 java 文件、`backend/src/test/resources/golden/` 下 26 个金标期望文件、`backend/src/test/resources/golden-baseline/snapshots/` 全部变更、`docs/plans/customer-issue-suggestion-exclusion-20260908.md`。
- `git add -p docs/platform-page-business-rules.md`：只选 5.1 第 4 条 / 5.2 第 6 条 / 5.3 第 15 条 hunks。
- `git add -p docs/progress.md`：只选「2026-09-08 客户问题统计排除建议类」段落及其风险条目相关 hunks。
- 提交说明：`feat(customer-issue): 缺陷汇总与延期统计排除建议类并重建黄金基线快照`（正文注明领导 2026-09-08 口径、RULE_VERSION v7/v5、快照白名单审阅通过、比对模式 180/180 全绿）。

提交 2（单元 2：评审问题覆盖修复）：
- 直接 add：`ReviewDataRecordCommandService.java`、`ReviewDataProblemItemSaveRequest.java`、`ReviewDataRecordCommandServiceTest.java`、`frontend/src/views/review-data/ReviewProblemItemFormDialog.vue`、`docs/plans/review-problem-item-expert-override-fix-20260908.md`。
- `git add -p` 两个共享文档：选走剩余 hunks（业务规则 6.3 第 13 条、progress「评审问题同名专家覆盖缺陷修复」段落）。
- 提交说明：`fix(review-data): 评审问题状态必填并收紧占位劫持守卫，根治同名专家覆盖`（正文注明空/未评审状态 400、isDefaultPendingProblemItem 守卫、同专家多条放开、默认套件 1250 全绿、零快照影响）。
- 提交后 `git status` 复核：剩余应恰为三件遗留物（两个 untracked + fresh-package 计划 M）。
- hunk 粘连拆不开时：停下问用户，不得把两单元混为单提交。

**第 6 步｜progress.md 风险条目更新**（随提交 2 后补一个 docs 提交，或并入提交 1 前完成）
- 「客户问题统计排除建议类」条目的 [风险] 段：黄金基线快照已重建（更新模式+白名单审阅+比对 180/180 全绿）→ 移除"待发布打包前门禁时机重建"待办，保留"待用户浏览器复核数字"。
- 若采用"并入提交前完成"路径，第 5 步的 add -p 范围随之包含该修订。

**第 7 步｜收尾汇报**
- 汇报：快照变更清单、比对结果、两个提交哈希、遗留物清单、门禁结论 = **可以打包**。
- 打包本身（`deploy/intranet-offline-packaging-standard.md` 流程）不在本方案范围，由用户另行发起。

## 决策记录

- **直接更新模式、不再先跑比对模式**：用户 2026-09-08 明确指示"跑更新模式重建"；差异意图已由三层证据锁定（单元测试断言新口径、26 个单测金标已按新行为重建、代码影响面逐文件审查），比对模式的差异确认价值被 diff 审阅（第 2 步白名单机制）替代。规划会话曾启动过一次比对模式，中途被用户叫停切换，未产生结果。
- **无需 D-08 留痕**：仅快照重建，夹具与 baseline-manifest 未动；沿用 f66aff80 惯例（该次快照更新同样未动 decisions.md）。D-08 第 3 条流程（有意变更→重建→审阅）已完整走完。
- **无新增 API、无目录变更**：两单元均未增删端点，endpoint-catalog.yml 零改动。

## 接口契约

- 无新增/删除 API。快照文件为唯一新增变更物：`snapshots/statistic-boards/` 下 4-5 个 JSON（board 响应与规范化 Excel 导出），内容嵌入两看板 v7/v5 RULE_VERSION 与建议类口径。
- 行为契约（已在实现中生效，本方案不改代码）：缺陷汇总/延期两看板统计层排除建议类（三口径判定与 `SuggestionMetricSupport` 一致）；`POST /api/review-data/records/{recordId}/problem-items` 的 `problemStatus` 必填且不得为"未评审"。

## 风险与假设

- 假设：冻结夹具客户问题范围含建议类议题与否未知 → 快照计数变小或不变均正常（第 2 步已给两可判读）。
- 风险：更新模式先写后审——若重建后才在 diff 中发现意外文件，快照已被覆盖；恢复路径= `git checkout -- snapshots/` 全量恢复后按回归调查（第 2 步已内置该纪律）。
- 风险：`git add -p` hunk 拆分依赖两单元在共享文档中改动相距较远（5.x vs 6.3、两个独立日期段）；若意外粘连，停下问用户而非混合提交。
- 敏感约束：不推送任何远端；不动 `D:/projects/spidergitdata-dev`；三件遗留物保持原样。
- 验证缺口：用户浏览器复核两看板数字、评审问题新增交互（新增必填/多条/守卫）尚未完成——不阻塞门禁与提交，30001 部署后一并验收。
