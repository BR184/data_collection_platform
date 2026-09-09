# 质量指标目标值更新：设计评审与代码走查缺陷密度（20260909）

## 进度与中间物

- 状态：**工作单元已完成（2026-09-09）**。提交 `2bd626ee`（81 文件：7 后端代码/测试 + 66 golden 快照 + 5 前端 + 3 文档），未推送（远端待用户指定）。全部门禁绿：后端默认套件 1258、前端 Vitest 462、golden compare 复跑 180/180、仓库四项门禁。
- 收尾留痕：`frontend/src/components.d.ts`（dts 再生成漂移，恢复 ElPopover 声明，与生产构建真实状态相反）与 `docs/plans/fresh-package-env-direct-20260907.md` 为其他单元/生成物遗留，未纳入本提交。
- 默认套件验证（2026-09-09）：后端 mvn 默认套件 **1258 全绿**（BUILD SUCCESS，1 skipped 为既有）；前端 Vitest **462 全绿（125 文件）**。首次运行因测试缺 `AnalyticsDashboardResponse` 导入编译失败（`entity.analytics` 包），补导入后复跑全绿。
- golden compare 第一次：**180 例中恰好 7 文件失败**，与"证据与根因"预期清单逐字一致，无清单外差异。
- golden 更新模式重建：180/180 BUILD SUCCESS（更新模式只写不比）。
- 66 个快照文件 diff 全量归一化审计（脚本键序归一 + 叶子路径分类，方法复用 `golden-baseline-rebuild-release-gate-20260908.md`）结论：
  - **7 个预期文件 = 全部有意变更**：quality-rd dashboard 仅 DGM 2.12 status `success→danger`；rules 恰 5 条 target 文案；2 个 overview + 3 个 dashboard 各恰 3 条 targetDescription 文案。其余叶子零变化。
  - 3 文件纯键序布局漂移（归一化后零差异，与上次门禁同类）。
  - 56 文件纯时间/端口噪声（lastSyncedAt/lastVerifiedAt/db_port/runId 等更新模式重写的易变字段）+ 客户议题导出 U 列 +23 小时墙钟老化（基线 09-08 14:01 → 本次 09-09 12:50，与上次门禁 +25/+26 同类）。
  - **零未解释语义差异**。
- 夹具、`baseline-manifest.json`、`endpoint-catalog.yml` 未动（git status 核验）。
- 进行点：compare 复跑全绿后 → 用户审阅快照 diff → progress.md 更新 → 本地提交（不推送，远端待用户指定）。

## 恢复线索

- 当前阶段：默认套件验证中；套件绿后执行 golden 流程（步骤 7）。
- 恢复后建议首条命令：`../tools/maven/apache-maven-3.9.9/bin/mvn.cmd test -Pgolden-baseline -Dtest=GoldenBaselineChainTest`（backend 目录，Docker 须在运行）。
- 本计划为当前工作单元唯一权威计划。

## 目标与边界

- 用户原始需求（2026-09-09）：
  1. 设计评审缺陷密度目标区间 `[0.20, 0.60]` → `[0.30, 0.80]`；
  2. CC 代码走查缺陷密度与 DGM 代码走查缺陷密度目标区间 `[2.00, 10.00]` → `[3.00, 12.00]`（两者统一）；
  3. 需求评审缺陷密度保持 `[0.20, 0.60]` 不变。
- 可验证的成功标准：
  1. 后端达标判定（BI 计算器、分析看板 metricStatus）、看板目标文案、前端目标带图元、核对表文档全部按新区间一致；
  2. 后端默认测试套件与前端 Vitest 全绿，且新增区间边界断言（含旧行为反转用例）；
  3. golden 基线：compare 模式确认差异仅限预期 7 文件 → `-Dgolden.update=true` 重建受影响快照 → git diff 经用户审阅 → 复跑全绿。
- 明确禁止：
  - 改动需求评审区间或其任何展示；
  - 改动 golden 夹具或 `baseline-manifest.json`（快照-only 变更，不构成新基线版本）；
  - 手工编辑快照文件；
  - 顺手重构无关代码或"整理"目录。

## 约束与背景

- 黄金基线门禁（AGENTS.md）：有意产出变更须先经用户确认（本需求即确认来源），更新模式重建后必须 git diff 人工审阅，再 compare 复跑全绿；严禁用更新模式掩盖未解释差异。
- `docs/bi-dashboard/BI看板数据来源与计算口径核对表.md` 是用户人工确认的业务语义权威，DS/CD 对应行必须同工作单元同步更新。
- BI 页面（`/api/bi/*`）不在 golden 基线目录内，本次 BI 快照无影响；受影响快照仅 quality-board 与 analytics-dashboards 两域。
- 20001 生产环境已冻结 20260908 更新包；本变更不在该包内，随下一个更新包发布（不在本工作单元范围）。

## 证据与根因（全量定位清单）

目标值的单一事实源分散在 7 处代码位置（"同一事实多份拷贝"是现状，本单元不做合并重构，只同步更新）：

| # | 位置 | 现值 | 作用 |
|---|---|---|---|
| 1 | `backend/.../bi/domain/BiReviewCalculator.java:20-21` | `MIN_DENSITY=0.20`、`MAX_DENSITY=0.60` | BI 需求页+设计页共用的达标判定（:250 `achieved()`）；需求/设计靠 `pageKey` 区分（:211），常量未区分 → **需拆分** |
| 2 | `backend/.../bi/domain/BiReviewCalculator.java:229` | trace 文案"密度区间 [0.20, 0.60] 达标" | 规则溯源文案，需按 pageKey 区分 |
| 3 | `backend/.../bi/domain/BiCodingCalculator.java:25-26` | `MIN_REVIEW_DENSITY=2.00`、`MAX_REVIEW_DENSITY=10.00` | BI 编码页达标判定（:495） |
| 4 | `backend/.../bi/domain/BiCodingCalculator.java:628` | trace 文案"密度区间[2,10]达标" | 规则溯源文案 |
| 5 | `backend/.../service/QualityBoardRdService.java:90-92` | 设计 `[0.20, 0.60]`、CC/DGM `[2.00, 10.00]`（:89 需求不动） | 质量看板 overview/dashboard 指标 targetDescription 文案 |
| 6 | `backend/.../service/analytics/QualityRdAnalyticsDashboardProvider.java:563-576` | `metricStatus`：需求+设计共用 `inRange(0.2, 0.6)`，CC/DGM 共用 `inRange(2, 10)` | 分析看板指标 status（success/danger）判定 → **需拆分** |
| 7 | `QualityRdAnalyticsDashboardProvider.java:285,292,299,334,341` | 设计 `[0.20, 0.60]`、CC/DGM/按走查人/按被走查人 `[2.00, 10.00] KLOC` | 分析看板规则 target 文案（:278 需求不动） |
| 8 | `frontend/src/views/quality-board.ts:92,98,104` | `resolveBandTone(…, 0.2, 0.6)`（设计）、`(…, 2, 10)`（CC/DGM）（:86 需求不动） | 质量看板前端卡片色调带 |
| 9 | `frontend/src/features/bi-dashboard/stages/ReviewStageContent.vue:21-22` | `densityRange: [0.2, 0.6]` | BI 需求页+设计页共用组件的目标带/辅助线（module 级常量，pageKey 可区分）→ **需按 pageKey 拆分** |
| 10 | `frontend/src/features/bi-dashboard/stages/CodingStageContent.vue:44-45` | `densityRange: [2, 10]` | BI 编码页目标带/辅助线 |
| 11 | `docs/bi-dashboard/BI看板数据来源与计算口径核对表.md` | DS-07/08/09=`0.20/0.60`、CD-20/23/24/25=`2.00/10.00` | 业务口径权威文档（RQ-09/10/11、RQ-22 不动） |

不受影响（已核实）：`QualityBoardWorkbookExportService`（无目标文案）、`SystemTestHorizontalComparisonExportService`（仅列名）、`CodeReviewMultiBoardTopic`（仅描述）、`code-review-multi-board.ts`（无目标带）、`chart-contract.test.ts`（显式传参的通用图表测试）、BI 页 golden 快照（不在基线目录）。

**Golden 快照影响（7 文件，全部为预期差异）：**

| 文件 | 差异内容 |
|---|---|
| `snapshots/quality-board/get___rd_overview__default.json` | 4 条 targetDescription 中 3 条文案（需求条不变） |
| `snapshots/quality-board/get___rd_overview__cc2026r4.json` | 同上 |
| `snapshots/quality-board/get___rd_dashboard__cc-default.json` | metrics 内 targetDescription 文案 |
| `snapshots/quality-board/get___rd_dashboard__cc-cc2026r4.json` | 同上（CC 3.64 / DGM 5.72 均落新区间，仅文案变） |
| `snapshots/quality-board/get___rd_dashboard__dgm-default.json` | 同上 |
| `snapshots/analytics-dashboards/get___{dashboardKey}_rules__quality-rd.json` | 5 条规则 target 文案（需求条不变） |
| `snapshots/analytics-dashboards/get___{dashboardKey}__quality-rd.json` | DGM=2.12 的 status 由 `success` 翻转为 `danger`（2.12 < 3） |

## 方案与步骤

总体策略：不做常量集中化重构（那是另一个工作单元），只把 7 处定义按新口径同步更新，并新增边界测试锁行为。需求/设计共用处按 `pageKey` 拆分。

1. **后端 BI 计算器**（`BiReviewCalculator`）：
   - 新增 `DESIGN_MIN_DENSITY=0.30`、`DESIGN_MAX_DENSITY=0.80`；保留 `MIN_DENSITY/MAX_DENSITY` 语义为需求评审（核对表 RQ-09/10 引用名不变）；
   - `calculate(pageKey, source)` 开头按 `"design".equals(pageKey)` 选带，`achieved()`、`ModuleAccumulator.toData()`、`points()` 全部走选中带；
   - `traces(pageKey)` 公式文案按页输出 `[0.30, 0.80]` 或 `[0.20, 0.60]`；
   - `RULE_VERSION` `bi-review-v2` → `bi-review-v3`（达标语义变化）。
2. **后端 BI 编码计算器**（`BiCodingCalculator`）：常量改 `3.00`/`12.00`，trace 文案改 `[3,12]`，`RULE_VERSION` → `bi-coding-v3`。
3. **后端看板服务**：`QualityBoardRdService:90-92` 文案改 `[0.30, 0.80]`、`[3.00, 12.00]`；`QualityRdAnalyticsDashboardProvider` 的 `metricStatus` 拆分（需求 `0.2, 0.6` / 设计 `0.3, 0.8` / CC、DGM `3, 12`），规则 target 文案 5 处同步。
4. **前端**：`quality-board.ts` 设计带 `(0.3, 0.8)`、CC/DGM 带 `(3, 12)`；`ReviewStageContent.vue` 的两个图表 `densityRange` 改为按 `props.pageKey === 'design'` 选择（建议提取 `reviewDensityRange(pageKey)` 纯函数以便单测）；`CodingStageContent.vue` 改 `[3, 12]`。
5. **测试**：
   - `BiReviewCalculatorTest`：设计页密度 0.25 → `achieved=false`（旧行为反转，锁新带下限）、0.50 → `true`；需求页 0.25 → `true`（回归保护，锁需求带不变）；
   - `BiCodingCalculatorTest`：密度 2.50 → `false`（旧行为反转）、5.00 → `true`、12.50 → `false`；
   - `QualityRdAnalyticsDashboardProviderTest`：新增/调整 metricStatus 新带断言与 4 条规则 target 文案断言；
   - 前端：`reviewDensityRange` 单测（design/requirements 两分支）；`chart-contract.test.ts` 不动。
6. **文档**：核对表 DS-07/08/09（`0.30`/`0.80`，来源列改指 `BiReviewCalculator.DESIGN_MIN_DENSITY/DESIGN_MAX_DENSITY`）、CD-20/23/24/25（`3.00`/`12.00`）。
7. **验证与收尾**：
   - 后端默认套件 + 前端 Vitest 全绿；
   - golden compare 跑一次确认差异仅限上表 7 文件（差异符合预期即"有意变更"已由本需求确认）；
   - `-Dgolden.update=true` 重建 → `git diff` 交用户审阅 → compare 复跑全绿；
   - 更新 `docs/progress.md`；提交（Conventional Commits）。

## 决策记录

- 已确认（用户 2026-09-09）：需求评审保持 `[0.20, 0.60]`；设计评审 `[0.30, 0.80]`；CC/DGM 统一 `[3.00, 12.00]`。
- 待确认（默认按此执行）：
  1. **展示格式**：沿用两位小数文案 `[0.30, 0.80]`、`[3.00, 12.00]`（用户口述为 0.3/0.8、3/12，与现有文案风格统一）。
  2. **按走查人/按被走查人缺陷密度两条规则**（provider :334/:341）的 target 文案同步改为 `[3.00, 12.00] KLOC`（同一指标族，不同人分组口径）。
  3. **`RULE_VERSION` 升版**：`bi-review-v2→v3`、`bi-coding-v2→v3`（达标语义变化应留痕于响应的规则版本字段；该字段仅透传展示，无消费逻辑依赖具体值）。
- 已否决：把 7 处目标值集中为单一配置源的重构（超出本单元范围，避免与口径变更耦合）；同步改需求评审区间（用户明确否决）。

## 接口契约

- 无新增/删除 API，无表结构变更。
- 响应内容变化（均为有意变更）：
  - `GET /api/quality-board/rd/overview`、`/dashboard`：`metrics[].targetDescription` 文案（设计、CC、DGM 3 条）；
  - `GET /api/analytics-dashboards/{dashboardKey}`（quality-rd）：`metrics[].status`（DGM 落在 [2,3) 的值由 success→danger）；
  - `GET /api/analytics-dashboards/{dashboardKey}/rules`（quality-rd）：5 条 `rule.target` 文案；
  - `GET /api/bi/pages/*`（requirements/design/coding）：`summary/module/point.achieved` 可能翻转、trace 公式文案、`ruleVersion` 字段值。
- 关键常量：`BiReviewCalculator.DESIGN_MIN_DENSITY=0.30`、`DESIGN_MAX_DENSITY=0.80`、`MIN_DENSITY=0.20`、`MAX_DENSITY=0.60`；`BiCodingCalculator.MIN_REVIEW_DENSITY=3.00`、`MAX_REVIEW_DENSITY=12.00`。

## 风险与假设

- 待验证推测：受影响快照清单按当前快照内容静态推断为 7 文件；compare 模式实跑若出现清单外差异，一律停下按回归处理，不得直接更新。
- 已知易错点：`metricStatus` 与前端 `resolveBandTone` 两处判定带必须同步，否则看板色调与分析看板状态互相矛盾；`ReviewStageContent` 图表实例在 setup 期按 pageKey 一次性构造，pageKey 不会运行时切换（BiDashboardView 每页独立挂载），无需响应式。
- 敏感只读数据：golden 夹具与 `baseline-manifest.json` 不动。
- 视觉验收缺口：目标带/辅助线位置属视觉变化，实施时需启动前端在 BI 设计页/编码页截图确认（或由用户在内网验收）。
