# 响应已延期口径：响应模板须有计划解决时间 ∨ 回复模板

## 进度与中间物

- 状态：**2026-09-28 代码实施、定向回归、后端默认套件与真实链路验证均已完成**；剩余黄金门禁快照重建与真实标签写回待单独授权。判据 D1～D7 已全部裁定并落地；沿用 2026-09-20 同一需求的原方案文档，未另建重复文件。
- 实施记录（2026-09-28）：`IssueSlaRules` 新增私有 `isResponseExempt`，两个 `isResponseDelayed` 重载改用它（签名不变）；`IssueResponseTemplateParser` 新增 `hasParseablePlanSolutionTime`（最新优先 + 回退，复用严格判据，与非法模板判定同源）；`IssueResponsePlanFieldRules` 不改、不扩展。`has_response` / `research_template_time` / `fix_user` / 响应效率全部未动；调用点、Mapper、SQL、表结构未动，无新增迁移。规则版本升级四处：`customer-issue-delay-issues@2026-09-28-v6`、`customer-issue-records@2026-09-28-v9`（CC_PRODUCT议题）、`customer-issue-records@2026-09-28-v5`（延期专题）、`customer-issue-customer-statistics@2026-09-28-v2`；延期看板规则说明文案同步为新判据。定向回归（`IssueFactNormalizationRulesTest` 36、`IssueResponseTemplateParserTest` 8、`FactBuildServiceCustomerIssueDelayFlagsTest` 5，合计 49 项全绿，`.tmp-logs/delayrule-targeted-20260928.log`）；受影响记录页与延期看板（`CustomerIssueRecordServiceTest` 13、`CustomerIssueDelayIssuesBoardServiceTest` 2、`CustomerIssueDelayIssuesBoardGoldenMasterTest` 1）与全仓同批通过：**后端默认快速套件 1552 项、0 失败、0 错误、1 跳过（BUILD SUCCESS，`.tmp-logs/backend-full-20260928-delayrule.log`）**。决策已留痕 `docs/decisions.md` D-21。工作树含其他单元的未提交改动，本单元未触碰其文件。
- 真实链路验证记录（2026-09-28，隔离克隆库 + 本地 GitLab，未触碰开发库与 18080/18181）：证据目录 `.tmp/delay-rule-real-chain-20260928/`（克隆库与隔离后端已清理）。①增量镜像 run 3780 真实读取本地 GitLab 四张表并 SUCCESS。②周期重算路径：875 条在辖议题中 100 条翻转（加延期 70、转豁免 30），判定不一致 0、范围外写入 0、解决延期变化 0，与 §4 只读预测完全一致（`.tmp/delay-rule-real-chain-20260928/phaseA-recompute.log`，`RESULT=PASS`）。③主路径全量重建 run 3782 的 `ISSUE` task SUCCESS（affected_rows=8009）后，逐行核对判定与重算路径 0 差异；剔除 3 个延期列与时间戳后的全行哈希 0 处不符、`has_response` 变化 0、行数不变（`phaseB-rebuild.log`）。④隔离实例规则说明返回 `customer-issue-delay-issues@2026-09-28-v6`，开发实例仍为 `@2026-09-08-v5`。核对脚本为一次性预言机程序（用生产 `IssueFactNormalizationRules` 逐行计算期望值），不进入仓库正式测试。
- 复跑与人工核对（2026-09-28 第二轮，独立克隆库 `qaflex_delayverify2`）：①改成**无基线预言机**核对——由生产代码分别算出旧/新两套期望值再与库中事实比对，875 条在辖议题**0 处不一致**、`response_overdue` 与 `is_response_delayed` 全等、无 ±3h 边界行；翻转仍为**加延期 70（P1 60/P2 9/P3 1）、转豁免 30（P1 22/P2 6/未设定 2）**。②启动重算对克隆库**零写入**（三列与 `updated_at` 变化全 0），即新判据下重算幂等。③真实增量镜像再次成功（镜像 16:27:36–16:27:42 读取本地 GitLab 四张表）。④`ISSUE` 全量重建再次 SUCCESS，重建后核对仍 0 不一致，剔除 3 个延期列与时间戳的**全行哈希 0 处不符**、行数 8009 不变。⑤隔离实例与开发实例延期看板产出**完全一致**（30 行、响应延期合计 287＝P1 261+P2 26、解决延期合计 237）。⑥人工核对清单（每行的地址、优先级、已超期时长、计划解决时间原文摘录、现有标签）见 `.tmp/delay-rule-real-chain-20260928/人工核对清单.md` 与 `flip-issues.csv`。
- 真实写回验证（2026-09-28，用户授权后在**本地**执行；本地无老平台写回进程）：隔离克隆库 + 本地 GitLab，写前取 875 条在辖议题标签快照，走生产链路（前置镜像刷新 → 发布收敛 → 重算 → 登记候选 → worker 调 API）真实写标签。**恰好 70 条加、30 条摘「响应已延期」，其余 775 条零变动、无任何其他标签被改写**（写前/写后逐条比对 `RESULT=PASS`，`pre-state.txt`/`post-state.txt`/`verify_writeback.py`）；#1492 加标、#2077 摘标均生效。另用 GitLab 自身 `resource_label_events` 审计交叉核对：近 3 小时内该标签变更涉及 **100 个不同议题**，**范围外 0 个**（多出的 2 条移除事件是 #1998/#2009 本地库里同标签的重复 link 行被一并清除，议题去重后仍为 30）。写回后再跑一轮编排得 `enqueued=0`、零新增写入（不反复增删）。过程中发现配置内 GitLab token 已于 2026-09-27 过期（写回 401、作业 DEAD 且不改标签，读链路无感），已补 `deploy/runbooks/gitlab-sync-orchestrator-runbook.md` 的上线前凭据核对清单；本次验证用新建的本地 PAT（`qaflex-local-writeback-verify-20260928`，2026-10-28 到期，可吊销）。内网真实写回仍未执行。
- 开发环境现状（2026-09-28 16:01，**非本单元操作**）：开发后端已被重启并载入本工作树代码（PID 20916 启动于 16:01:35，规则说明返回 `@2026-09-28-v6`），其启动重算把新判据写入开发库事实（恰好 100 行、736→776）；写回开关仍为关（全局 `delay-label-writeback-api-enabled=false`、数据源 `delay_label_writeback_enabled=false`），因此开发库自身不会写回；本地 GitLab 的标签变化来自上条经授权的本地写回验证。
- 整体回归（2026-09-28 收口轮）：后端默认套件 **1552 项 / 0 失败 / 0 错误 / 1 跳过（BUILD SUCCESS）**；前端全量 **146 文件 / 680 用例 → 677 通过、3 失败**（失败项为 `DatabaseBrowserView.test.ts` QUEUED/DEDUPED 与 `customer-issue-illegal-records.mount-smoke.test.ts`，经受控还原实验归属数据库浏览器与平台评审整改单元的进行中改动，非本单元）；`eslint`/`tsc --noEmit`/`vite build` exit 0；13 项仓库护栏与 `git diff --check` exit 0。黄金基线门禁（经用户授权跑**比对模式**）`Tests run 196 / Failures 19 / Errors 5`：本单元直接产出 8 个快照差异（延期诊断 `responseDelayedCount` 736→754、延期记录页 `total` 813→795 与导出行集、候选作者 26→25、延期专题规则版本 v4→v5、延期看板数据与导出），其余 11 项差异与 5 个缺失快照归属其他单元「已提交但未重建」的产物（权限 75→77、评审类型 5→6、同步配置列、`is_customer_requirement`、看板 `collection(s)` 协议、客户统计新端点）；重建须另获授权。
- 真实链路发现的既有现象（本单元未改动相关代码，留待确认是否属预期）：①`/api/facts/rebuild` 在未紧跟全量镜像时对 ISSUE/INTEGRATION_TEST 报 `事实来源依赖代际尚未就绪`——`SyncFactPublicationStateService.isReady` 要求 `full_publication_requested=true` 或存在未发布目标，而该标志只由镜像结算 `recordMirrorCompletion` 写入；克隆内置真该标志后两者全量重建成功。②MERGE_REQUEST 全量重建在 `delete from merge_request_commit_fact ... not exists` 处超过 30s 查询超时失败（`PLATFORM_QUERY_TIMEOUT_SECONDS`）。
- 需求来源分三段，**来源不同必须分别留痕**，不得混写：
  1. **2026-09-20 用户需求**：「CCProduct 上的『响应已延期』标签规则改一下。照响应模板**或回复模板**回复的，都不需要打响应已延期。」
  2. **2026-09-28 用户复述确认**：上条「或」口径保留，回复模板存在即豁免。
  3. **2026-09-28 领导新增（用户转述的原话）**：「现在响应模板中，很多都不加计划解决时间。为解决这个问题，需要咱们再增加一项检查，即使有了响应模板回复，计划解决时间没有写时间的，也打响应已延期标签。计划解决时间格式就按照咱们 wiki 中的要求即可。」
  4. **2026-09-28 追加（领导统一格式）**：「『计划解决时间』字段的合法格式就是 wiki 文档中的，凡是不符合 wiki 的则被标记为非法格式。」——据此 D5 定为**严格口径**（见 §2.1），原「存在性／情况二豁免」思路作废。
- 三段的关系：第 3 条只针对**响应模板**，是本次判据的收紧项；第 1 条把**回复模板**纳入豁免，是用户需求而非领导口径。两者叠加成 §2.1 的判据。
- 交付物：本方案、实现与测试（见实施记录）、`docs/progress.md`、`docs/platform-page-business-rules.md` 5.3、`docs/architecture.md` 事实与统计、`docs/decisions.md` D-21。**数据库结构、配置、黄金快照均未修改**；用户已有的客户统计/BI、事实发布、平台评审整改、备份、下拉等改动全部保留。
- 证据分层：§3.2、§4 的模板身份与本地样本统计来自 2026-09-20 调查记录；**§4 的规模数据为 2026-09-28 对本地开发库（`127.0.0.1:15432/qaflex`，project 325）的只读实测**（该调查阶段未启动应用、未触发同步、未重建事实、未写回标签）；实施后的真实链路验证改在隔离克隆库执行，见上「真实链路验证记录」。
- 前置修复已落地：`98ff4b4c` 含来源级发布收敛（D-14）、三列窄写（D-15）与运行终态驱动编排、异步执行器隔离（D-16）。`remove_labels` 已实测通过，不是本次新增分支。
- 2026-09-28 新查明的既有隐患（**本单元不修，只记录**）：`IssueTemplateParsingSupport.planSolutionTime`（`:98-110`）从数组尾部向前扫描，在「备注按创建时间倒序聚合」的输入上取到的是**最旧**一份模板；而老平台 `IssueServiceImpl.getPlanSolutionTime` 取**最新**一份。该函数只服务 18 天解决期限（`IssueSlaRules.resolveDeadline`），因此**解决延期在新老平台本就方向不一致**。修它会改变解决延期结果，超出本单元范围，见 D8。

## 1. 恢复线索

- 原调查基点：`7565afd5` 加当时工作树；本方案已随 `c818e720` 入库。本次重审基点：`main@de7bff4b` 加 2026-09-28 当前未提交工作树，不能只凭 commit 重现全部状态。
- 恢复后首条命令：`git status --short`。随后读取 `AGENTS.md`、`docs/progress.md`、本方案，核对实施指派与工作树变化；前置修复以 D-14～D-16 及当前代码为准，不重复实施旧修复计划。
- 相关权威：`docs/platform-page-business-rules.md` 5.3、5.4、11.4、11.5 节；`docs/decisions.md` D-03、D-07、D-14～D-16；`docs/architecture.md` 事实与统计章节；wiki 原件（用户资产，见 §3.4）。
- 当前下一步：代码、测试、文档已实施并验证完毕；**剩余黄金门禁快照重建与真实标签写回（加标 70/摘标 30）两件，均需单独授权**——快照须先展示差异再以更新模式重建，写回须先停老平台写回（见上现场前提）。D8 是记录的既有隐患，不在本单元范围。

## 2. 目标与边界

### 2.1 目标版本（判据已由用户 2026-09-28 裁定）

```
豁免响应延期 = 存在回复模板（正文含 `### 1、修复状态`）
             ∨ (存在响应模板 ∧ 该最新响应模板的“计划解决时间”字段可解析出日期)

is_response_delayed = ¬豁免 ∧ 已超过响应期限
响应期限：P1 24h / P2 48h / P3 或未设定紧急程度 72h（不变）
```

「可解析」的取值方向与回退语义（**D6，已裁定**）：备注按创建时间倒序聚合，从**最新**一份响应模板开始查；该份缺失或不可解析时**回退到更早的响应模板**，取第一份可解析者。与老平台 `getPlanSolutionTime` 一致。

「可解析」的字段判据（**D5，2026-09-28 领导统一格式后裁定为严格口径**）：「计划解决时间」的合法格式以 wiki（规则总表 5.4、11.4）为唯一标准——**唯一且完整的一个日期**，分隔符白名单 `.,，、/·\`` `年月日`，年份 2020–2040，日历有效。**凡不符合者一律视为「没有写时间」**（留空、`暂无/待定`、`2026年5`、`20260615`、`202X年0X月XX日`、`2026年6月18号`、多个日期等），按未豁免处理 → 打标签。因此豁免判据与非法模板判定**完全同源**，直接复用 `IssueResponsePlanFieldRules.parsePlannedResolutionAt`，不新增第二套口径。

行为表（**用户 2026-09-28 逐行确认**）：

| 议题情况 | 是否打「响应已延期」 |
|---|---|
| 只有响应模板，计划解决时间合法 | 不打 |
| 只有响应模板，计划解决时间缺失/留空/不可解析 | **打** |
| 只有回复模板 | 不打 |
| 两种模板都有 | **不打**（回复模板优先，不再看响应模板的计划解决时间） |
| 两种模板都无且已超期 | 打（现状不变） |

### 2.2 明确不做

- **不改「解决已延期」判定**（`isResolveDelayed` 的两个重载）、其闭环条件与 18 天口径；不改 `hasFixCaseNote` 在解决侧与缺陷原因归类、`fix_user` 中的既有用途。
- 不改 SLA 时长（P1 24h / P2 48h / P3 72h）、2026-01-01 创建时间下限、项目 325 范围、建议类排除口径。
- 不改标签名与写回协议。写回仍只允许 `add_labels` / `remove_labels` 增删 `响应已延期`、`解决已延期`。
- 不改 `has_response` 的既有语义（只表达调研模板存在性）、`research_template_time`、`fix_user`（**E，用户 2026-09-28 未反对，按「不动」执行**）。响应效率指标样本与公式因此保持不变。
- 不引入跨模板优先级、新旧比较或「按最新模板类型分流」的判据（用户 2026-09-28 已否决该路线，改为「回复模板存在即豁免」）。
- 不新增 token 副本、规则开关、并行写回通路，不为本需求重构全仓模板解析目录。
- 不修 D8 记录的解决期限方向隐患。

### 2.3 成功标准（可验证）

1. 同一输入经事实构建与周期重算得到相同的响应延期判定，均由 `IssueSlaRules` 负责；2 参与 5 参重载的豁免条件一致。
2. §2.1 行为表 5 行全部成立，且「最新没写、上一份写了」按 D6 回退为**不打**。
3. `has_response`、`research_template_time`、`fix_user`、响应效率、解决延期与其他项目输出不发生非预期变化。
4. 相关后端默认快速套件全绿；受影响端点与快照按黄金门禁纪律处理，未获授权不得更新快照或为本次调查运行黄金链。

## 3. 约束与背景（现状实现事实）

### 3.1 判定链路与唯一落点

以下路径均相对 `backend/src/main/java/com/data/collection/platform/`，行号按 2026-09-28 工作树核对。

- **唯一改动点**：`service/IssueSlaRules.java`
  - `hasResponse(notesText)` `:21-23` → `templateSnapshot(notesText).hasTemplateReply()`，实质是整串 `contains`（token 见 `:11-12`）。
  - 2 参重载 `:25-27`：`!hasResponse(notesText) && containsAnyLabel(RESPONSE_DELAY_LABELS)`。
  - 5 参重载 `:29-42`：`hasResponse` 成立立即返回 false，否则按 `Duration.toHours() > responseSlaHours(...)`。
  - `hasFixCaseNote(notesText)` `:71-73`：`containsToken(FIX_CASE_NOTE_TOKENS)`，token 见 `:13`。
  - 两个重载都把豁免条件从 `hasResponse(notesText)` 换成新的 `isResponseExempt(notesText)` 即可，**签名不变**。
- **两个调用点，均无需改动**（都传 `notesText`）：
  - 事实构建 `service/IssueFactSourceRowMapper.java:141-149`（`has_response` 于 `:141` 单独派生，保持原语义）。
  - 周期重算 `service/FactBuildService.java:345-369`，三列窄写与范围 generation 原子推进见 `:370-432`。
- 全 `backend/src/main` 检索确认：**没有第二处**计算响应延期。下游全部只读 `issue_fact.is_response_delayed`（消费面见 §5.5）。

### 3.2 两类模板的真实身份（2026-09-20 已核实，结论沿用）

**三个叫法指向同一份响应模板正文，不存在第三类模板。**

| 叫法 | 出现位置 | 所指模板正文 |
|---|---|---|
| 「缺陷调研模板」 | 老平台 CC 延期页/响应效率页文案；新平台 5.4.1 非法类型名 | `# 问题调研情况说明`（H1）+ `##` 小节 |
| 「响应模板」 | 新平台自身术语（D-03 标题、业务规则 11.4/11.5、`IssueResponseTemplateParser` 类注释） | 同上 |
| 「问题调研情况说明」 | 模板正文首行，也是两平台的判定 token | 同上 |

- **响应模板**小节：`## 问题类型：`、`## 问题原因：`、`## 修改方案：`、`## 一级缺陷的修改方案请模块负责人签字确认：`、`## 计划解决时间：`、`## 计划合并的版本分支：`。
- **回复模板** = 《【模板】缺陷修改的回复模板》，正文**首行即** `### 1、修复状态`（无前置标题），小节：`### 1、修复状态`（已解决 / 部分解决 / 申请延期 / 无法复现）、`### 2、缺陷原因分析`、`### 3、请描述具体原因：`、`### 4、修改方案：`、`### 5、是否由修改其他缺陷引起`、`### 6、修改该缺陷可能影响的功能：`、`### 7、是否对可能影响的功能进行了测试`、`### 8、有无遗留问题或潜在的影响？`、`### 9、是否更新了关联关系表`。来源：老平台 `CauseUtil` 的 `getFixStatus/getModification/getSpecificReason/…` 与 `IssueExcelBo:120-160` 的导出列。
- **回复模板不含任何时间字段**（无「计划解决时间」、无「计划合并的版本分支」），因此无法对它做同类检查——这是「两者都有 → 回复模板优先」成立的前提。
- 两类模板的主体事实：老平台全仓只有两个模板常量（`IssueServiceImpl.java:84` 的 `ISSUE_RESEARCH_TEMPLATE`、`:68` 的 `ISSUE_NOTE_CAUSE_HEADER`）；项目 325 的 `issues_template` 为空；「响应模板」「回复模板」在 CC 议题评论中出现 0 次。故只能用上述语义定位，不能靠字符串匹配「响应模板」。

**老平台的模板匹配语义先例（实现时必须对齐）**：豁免判定一律「出现即算」——响应用 `contains("# 问题调研情况说明")`，回复豁免用 `hasIssueCaseNote`（任一评论 `contains("### 1、修复状态")`）；**内容规范性只用于「非法模板」判定，从不参与豁免**。归属/解析另有更严口径（`fix_user` 与缺陷原因归类要求评论**首行**命中 `### 1、修复状态`）。新平台现状与该分工一致，因此回复模板只需接入豁免判据，不改解析侧。

### 3.3 「计划解决时间」的现有解析器：三处口径与陷阱

| 位置 | 语义 | 用途 |
|---|---|---|
| `service/IssueResponsePlanFieldRules.parsePlannedResolutionAt` `:27-46` | **严格**：字段内容整串匹配唯一日期，分隔符白名单 `.,，、/·\`` `年月日`，年份 2020–2040，`LocalDate.of` 校验日历 | ① 事实列 `planned_resolution_at`（经 `IssueResponseTemplateParser:27-28`）；② 非法类型「计划解决时间非法」（`IssueClassificationRules:293-296`、`CustomerIssueIllegalRecordService:411`） |
| `service/IssueTemplateParsingSupport.planSolutionTime` `:98-110` + `parseDateValue` `:148-167` | **宽松**：从数组尾部向前扫，取第一份模板里第一个能解析的日期，不校验唯一性 | 仅供 18 天解决期限 `IssueSlaRules.resolveDeadline`（`:55-69` 调用 `templateSnapshot(...).planSolutionTime()`） |
| `service/IssueResponseTemplateParser.latestTemplate` `:36-47` | 正序取第一份含 token 的评论 = **最新**一份模板 | 产出 `planned_resolution_at/_text` 与计划合并分支 |

三个不可混淆的点：

1. **方向相反**：`latestTemplate` 正序（最新），`planSolutionTime` 倒序（最旧）。后者是本单元**不能复用**的（见 D8）。
2. **严格 vs 宽松并存**：严格那份同时是非法判定与事实列；宽松那份只服务解决期限。本次新增的豁免判据按 D5 选择口径，必须显式声明与哪一处对齐，不得静默取用。
3. `planned_resolution_at` 只对 CC_PRODUCT 计算（`IssueFactSourceRowMapper:57-67` 的 `customerProject` 门禁），且 `is_response_delayed`/`response_overdue` 还受 `openCustomerIssue` 限制（`:142-149`），影响面限于项目 325 的 open 客户问题。

### 3.4 wiki（用户资产）与本需求的关系

wiki 原件位于用户桌面《数据采集平台规则汇总》的「客户问题-延期问题」段（仓库内**无 wiki 目录**；权威副本为 `docs/platform-page-business-rules.md`）。其现状口径为：

- 「议题响应：从议题创建开始，P1 24h / P2 48h / P3 72h 内，需要按照要求回复**缺陷调研模板**，否则添加『响应已延期』标签；按照缺陷调研模板回复后，取消『响应已延期』标签。」
- 「响应检测规则：检测响应模板的表头 `# 问题调研情况说明` 是否在评论区中出现，**检测到表头即认为已响应**。」

两点推论：

1. 领导本次新增检查**修改了 wiki 的响应检测规则**（从「表头出现即已响应」收紧为「表头出现且计划解决时间可解析」）。落地时必须同步改 wiki 与规则总表 5.3 第 4～5 条；wiki 是用户资产，改法需用户确认。
2. wiki 把「缺陷原因分析内容」明确放在**解决**侧（「解决检测规则：…且没有按照要求填写缺陷原因分析内容，则添加『解决已延期』标签」），即回复模板在 wiki 中原本属于解决侧。因此第 1 段「回复模板也豁免响应延期」是**用户需求对 wiki 的扩展**，需要在规则说明里把「响应已延期」的含义写清：`未在时限内按响应模板或回复模板回复`。

### 3.5 老平台对照（本次是相对老平台的口径变更）

- 老平台响应判定**完全不看计划解决时间**：`IssueServiceImpl.checkResponseDelay:864-881` 的条件只有 `hoursPassed > maxHours && researchTemplateTime == null`。
- 老平台计划解决时间的三类边界处理（供 D5 参考）：
  - 非法校验 `validateTimeFormat:1183-1192`：留空 → 「错误：计划解决时间**缺少具体值**」；格式不匹配（含多个日期）→ 「错误：计划解决时间**格式不正确**」。即老平台在非法层面**区分「没填」与「填错」**，但不单列「多日期」。
  - 解决期限取值 `getPlanSolutionTime:889-911` + `parseTimeStrToDate:976-1000`：三类都取不到值 → **回退更早模板** → 最终 null → 按 18 天。即老平台把「格式不正确」当作「没有计划时间」，不视为错误。
- 老平台非法校验白名单比 wiki 窄（只允许 `年 . , ，`，缺 `、/·\``），且与自身期限解析的白名单不一致；新平台与 wiki 一致。这是既有偏差，本单元不改老平台。
- 「响应已延期 / 解决已延期」属客户问题延期模块；系统测试的「申请延期缺陷原因分析」来自标签 `delay_cause`，不因本需求改变。

## 4. 影响规模（2026-09-28 开发库只读实测）

口径：`issue_fact` 中 `project_id=325`、`deleted=false`、`issue_state<>'closed'`、`created_at_source >= 2026-01-01`，共 **875** 条。模板识别用与代码同源的字符串判定（响应模板 token、`### 1、修复状态`）；计划解决时间按 **D5 严格口径**统计（与 `planned_resolution_at is not null` 复算一致，共 48 条；D6 的回退语义在本样本中不产生额外结果）。

| 响应模板 | 回复模板 | 条数 | 当前 `is_response_delayed` | 可解析计划解决时间 | 变更后 |
|---|---|---|---|---|---|
| 无 | 无 | 706 | 706 | 0 | 不变（仍延期） |
| 无 | 有 | 30 | 30 | 0 | **豁免 → 摘标 30** |
| 有 | 无 | 93 | 0 | 23 | 其中 **70 条不可解析 → 加标 70** |
| 有 | 有 | 46 | 0 | 25 | 全部豁免（回复模板优先）→ 不变 |

**净效果：加标 70、摘标 30。**

不合格的「计划解决时间」字段实写值分布（91 条，前 12 类已覆盖 87 条）：真留空 `：` 46；写空话 `暂无` 24、`待定` 9、`未知` 1、`无` 1、`等待客户提供模型` 1；残写 `2026年5` 2；无分隔符 `20260615` 1；占位符 `202X年0X月XX日` 2；另有日期后带非白名单字符的写法（如 `2026年6月18号`）。按 D5 严格口径，**上述全部视为「没有写时间」→ 打标签**；样本中没有「两个完整日期」的实例。

「两者都有」的 21 条中，最新一份模板是回复模板的 **19 条**、是响应模板的 **2 条**——这是 D7 否决「按最新模板类型分流」路线的量化依据（该路线只影响这 2 条）。

这些数字**只是本地开发库样本**，不是内网待处理数量。模板集合不等于标签差异：只有仍在写回范围内、按新判据改判且当前标签不一致的议题才产生真实增删。

## 5. 方案与步骤

### 5.1 代码落点（最小改动面）

1. `service/IssueSlaRules.java`：新增私有 `isResponseExempt(String notesText)`，两个 `isResponseDelayed` 重载的豁免判断改调它；`hasResponse` / `hasFixCaseNote` 保持原语义不动。
   ```java
   /** 响应延期豁免：回复模板存在，或响应模板存在且其计划解决时间可解析（最新优先，缺失则回退更早模板）。 */
   private static boolean isResponseExempt(String notesText) {
     return hasFixCaseNote(notesText)
         || IssueResponseTemplateParser.hasParseablePlanSolutionTime(notesText);
   }
   ```
2. `service/IssueResponseTemplateParser.java`：新增包级方法 `hasParseablePlanSolutionTime(String notesText)`——按创建时间倒序逐份响应模板查「计划解决时间」字段，返回第一份可解析者；与 `IssueSlaRules` 同包，无需改可见性。
3. `service/IssueResponsePlanFieldRules.parsePlannedResolutionAt` **不改、不扩展**：D5 采用严格口径后，豁免判据与 `planned_resolution_at` 事实列、非法模板判定共用同一方法，**不需要新增第二套字段级判据**。禁止为豁免另加宽松解析（那会重新制造与非法页的分叉）。
4. **调用点、`IssueFactNormalizationRules` 包装、Mapper、SQL、表结构一律不动。**

### 5.2 判据细节（须逐条测试锁定）

- 回退：最新模板不可解析 → 继续查更早模板，取第一份可解析者（D6）。
- 「可解析」按 D5 严格口径（唯一且完整的 wiki 日期）；`2026年5`（残缺）、`202X年0X月XX日`、`20260615`（无分隔符）、`暂无/待定/未知/无`、`2026年6月18号`（`号` 不在白名单）、**多个日期**（如 `预计 2026.03.31，最晚 2026.04.01`）**全部**判为「没有写时间」→ 打标签（领导 2026-09-28 统一格式：凡不符合 wiki 即非法格式）。即情况一、二、三**一律打标签**。
- 识别 token 与现有实现一致：响应模板 `containsToken`（忽略大小写，`# 问题调研情况说明` / `问题调研情况说明`）；回复模板 `contains("### 1、修复状态")`（忽略大小写，整串扫描）。
- 迟到回复同样解除延期（与「按模板回复后取消标签」的现状语义一致），不新增历史迟响应惩罚或永久保留标签规则。
- 紧急程度取 `priorityLevel` 或 P1/P2/P3 标签，未设定按 P3 72h（`IssueSlaRules.responseSlaHours:105-114`，本次不改）。

### 5.3 版本号与存量收敛

- 逐个核对实际消费 `is_response_delayed` 的看板/记录页并升级其规则版本（已确认消费：`CustomerIssueDelayIssuesBoardService` 当前 `v5`、`CustomerIssueRecordService` 的 `DELAY_RULE_VERSION@2026-07-22-v4` 与 `CC_PRODUCT_RULE_VERSION@2026-08-03-v8`、`CustomerIssueByFunctionBoardService` 当前 `v3`、`CustomerIssueCustomerStatisticsBoardService` 当前 `v1`、`IssueFactDiagnosticsService`）。**不机械升级无关看板**；`BiCustomerIssuePageService` 等需实施时核对是否对外暴露该布尔再定。
- 规则版本升级只负责废弃旧规则缓存，**不能替代事实重算**。存量 open 议题由现有周期重算按当前时间更新三列；窄写与范围 generation 原子推进保持不变，不得恢复整行 upsert。
- 本单元不新增迁移、不改 schema、不改模板来源字段，不要求全量 GitLab 同步或扩大事实重建范围。发布时须确认来源评论已同步并等待重算/发布链路收敛后再验收；只更新代码不等待事实更新不算生效。

### 5.4 测试（先加复现，再改实现）

所有测试放在既有 `backend/src/test/`，以下均为待实施验证，不是本次通过结果。

| 层次 | 覆盖范围与验收标准 |
|---|---|
| 规则单测 `IssueFactNormalizationRulesTest` | §2.1 行为表 5 行；两参/五参重载结果各自符合契约；**仅响应模板且无合法计划时间、且已超期的用例在旧实现下必红**；回退用例（最新不可解析、上一份可解析 → 不打）；按 D5 严格口径判「打」：空话（`暂无/待定`）、残写（`2026年5`）、无分隔符（`20260615`）、占位符（`202X年0X月XX日`）、非白名单尾随字符（`2026年6月18号`）、多个日期；SLA 临界（恰好等于时限不延期）、缺时间、无紧急程度、迟到回复行为保持 |
| 解析单测 `IssueResponseTemplateParserTest` | `hasParseablePlanSolutionTime`：字段在下一行、加粗/反引号修饰、无 `# 问题调研情况说明` 时不豁免；「通过」用例覆盖 wiki 全部白名单分隔符（`.,，、/·\`` `年月日` 及其组合）；「不通过」用例见上；**并断言豁免判据与 `parsePlannedResolutionAt` 结果一致（同源）** |
| 映射与事实链 `IssueFactSourceRowMapper` 相关测试、`IssueFactSourceInstancePipelineTest` | 真实调用映射后 `is_response_delayed` 与 `response_overdue` 一致；`has_response`、`research_template_time`、`fix_user`、`planned_resolution_at/_text`、计划合并分支与其他项目结果保持原语义 |
| 周期重算 `FactBuildServiceTest`、`FactBuildServiceCustomerIssueDelayFlagsTest` | 补「仅响应模板无计划时间」由 false→true、「补写计划时间」由 true→false 的双向回归；沿用既有窄写、范围外不变、无变化不写入的断言 |
| 范围版本 `CustomerIssueDelayRefreshScopeVersionTest` | 变化只推进实际写入范围，无变化不推进，事务失败不留部分状态 |
| 队列与 worker | 用真实规则输出接 planner/worker：`false→true` 生成 `add_labels`；`true→false` 生成 `remove_labels`；两者都有或被回复模板豁免时不动作；解决标签仍按原事实，其他标签不被触碰；写回开关关闭时不发 HTTP |
| 页面与导出 `CustomerIssueRecordServiceTest`、延期看板测试 | 延期计数、明细、记录标识、delayOnly 与导出一致；仍解决延期的议题不因响应豁免而从所有延期集合消失；响应效率与修复人保持原值 |

前端 `frontend/src/utils/rule-explanation-copy.ts` 及后端规则说明若含旧单模板表述须同步修改并补相邻测试；涉及页面说明的改动实施后须启动开发服务做浏览器验收，不能只以类型检查代替。

### 5.5 消费面核对

| 消费方 | 预期影响 |
|---|---|
| 延期问题看板 `resp_delay_p1/p2/p3/sum`（`CustomerIssueDelayIssuesBoardService`） | 命中判据的样本进出计数与明细；不是全部模板样本都变化 |
| 客户问题记录页 delayOnly 与响应延期标识（`CustomerIssueRecordService`、`IssueFactRecordConditionBuilder/Repository`） | 随事实变化；仍命中解决延期的记录继续保留 |
| 按功能展示缺陷数量（`CustomerIssueByFunctionBoardService`） | 随事实变化，需核对版本号与快照 |
| 客户维度统计（`CustomerIssueStatisticsCalculator` / `CustomerIssueStatisticMetricCatalog`） | 随事实变化，需核对版本号与快照 |
| `/api/facts/issue-diagnostics`（`IssueFactDiagnosticsService`） | 响应延期布尔与计数改变；不改 `has_response` 原语义 |
| 标签写回（`CustomerIssueDelayLabelWritebackQueueService/Planner/Service`） | 按事实差异双向增删；受两个开关与授权约束 |
| 响应效率、修复人及相关质量指标 | 全部保持 |
| `delay_issue` / `delay_reason` / `delay_cause` / `fixed_label_time` / `is_resolve_delayed` / BI 口径 | 各自独立计算，不因本次规则变更改变 |

### 5.6 文档收口

1. `docs/platform-page-business-rules.md` 5.3 第 4～5 条：把「检测到表头即认为已响应」改为「检测到响应模板表头**且计划解决时间合法**；另按 2026-09-23 用户需求，回复模板存在同样豁免」，并写明「两者都有时不看响应模板的计划解决时间」。5.4、11.4 的严格合法性口径**不变**，并在 5.3 明确引用它——**豁免判据与非法判定同源（D5 严格口径），凡不符合 wiki 格式即视为「没有写时间」**。
2. `docs/architecture.md` 事实与统计章节：补响应延期豁免说明与事实字段契约，明确它与调研响应周期（`research_template_time`）的区别。
3. `docs/decisions.md`：按落地时实际末位编号续号新增一条决策（记录三段需求来源、5 行行为表、D5/D6 结论、写回双向性），不复用已占用的 D-14；`docs/progress.md` 记当前阶段与验证证据。
4. wiki（用户资产，位于用户桌面《数据采集平台规则汇总》）：按 §3.4 修改响应检测规则文字，需用户确认改法后由用户或经授权执行。
5. 实施后依仓库规则处理本活动计划：确认内容压缩归入权威文档后再删除。

### 5.7 黄金基线（需单独授权）

- 本变更改变产出：受影响端点的响应/导出与 `issue_fact` 表状态（`is_response_delayed`、`response_overdue`）会与冻结快照产生差异，属**有意修改产出**。按门禁纪律：先向用户展示差异并确认是有意变更 → `-Dgolden.update=true` 重建受影响快照 → 用户审阅；不得手改快照、不得放宽掩码或调整目录分类。
- 初筛受影响家族：`customer-issue-delay-issues/`、`customer-issues/`（记录/导出/规则说明）、按功能展示、客户维度统计、`/api/facts/issue-diagnostics`；具体文件与变化以冻结夹具实际 diff 为准。
- 运行门禁前后必须停/起本机开发服务（18080、18181）并验证 `/actuator/health` 为 UP、18181 可访问。**本单元在未获授权前不运行黄金链、不更新任何快照。**

### 5.8 写回授权（需单独授权，且以现场前提为条件）

- 判定变化带来**双向**写回：加标 70、摘标 30（§4）。摘标是本功能此前未真实执行过的方向，属新增风险面。
- **老平台写回无法在运行期关闭（2026-09-28 只读代码核实）**：`Main` 无条件 `@EnableScheduling`；`schedule/CCProductScheduleTask.java:65` 的小时任务在爬取后**无条件**遍历 project 325 的 open 议题调用 `IssueServiceImpl.updateIssueDelayLabels:1369-1421`（只有数据合法性 guard，无 feature flag），最终走 `GitLabApiTool.setIssueLabels:395`——**覆盖式**写全部标签，源码自标「高危」。全仓无 `@ConditionalOnProperty`/`@Profile`/`@Conditional`，无 controller 开关（`doSchedule` 类端点只用于手动触发任务），配置打包在 jar 内且 `start_gs.sh` 不传外部配置路径。
- **陷阱**：`application-prod1.properties` 的 `do.schedule=true`（prod2/test 为 false）**没有任何代码读取**；唯一被读取的 `@Value("${do.mergerequest.note}")` 落在 `ApiService` 名为 `doSchedule` 的字段上，管的是合并请求评论推送。改 `do.schedule` **不会**关闭写回。
- 因此上线前提是：先让老平台**停止写回**（改代码重新打包部署，或停其进程——但停进程会同时断掉它给新平台提供的代码走查非法数据/评审数据），否则它每小时按「有模板即豁免」口径的覆盖式写入会抹掉新平台按新判据加的标签，反之亦然，必然反复增删。
- 正式启用写回前还须：确认新平台写回开关状态（现为数据源在镜像设置页的唯一开关，见 `docs/decisions.md` D-23）、保存标签基线、按事前清单逐条审阅预期差异。不得从「历史测试开过开关」推断现在获准写入。

### 5.9 实施顺序

1. 用户指派实施后，重新检查工作树与并行改动；锁定本单元差异，不混入他人文件。
2. 先加旧实现必红的测试（先复现），再改 `IssueSlaRules` 与新解析方法。
3. 跑定向规则/解析/映射/重算/范围/写回测试，再跑默认后端套件与受影响前端测试；结果写回本方案。
4. 审阅预期产出差异；黄金链与快照更新按 §5.7 单独授权执行。
5. 按 §5.6 收口文档；按仓库规则处理本计划文件。

## 6. 决策记录

以下均为**待审批/待确认**，不表示已获实施或写回授权。

| 编号 | 事项 | 状态与依据 |
|---|---|---|
| D1 | 两种模板身份 | **已核实关闭**：响应模板为 `# 问题调研情况说明`；回复模板首行 `### 1、修复状态`，见 §3.2。不再增加第三种猜测模板 |
| D2 | 响应模板是否要求规范填写才豁免 | **已被 2026-09-28 领导新增检查取代**：原稿推荐「出现即算」，现改为「响应模板须有计划解决时间可解析」。原口径作废，不得再引用 |
| D3 | 是否同步扩展响应效率 | **维持选项 B（用户 2026-09-28 未反对）**：只改延期豁免，`has_response`、`research_template_time`、`fix_user` 与响应效率指标全部不动；A 属额外指标需求，须另立方案批准 |
| D4 | 存量标签与写回责任方 | **未变**：先验收新事实与差异清单；真实增删标签、开关启用与责任方切换须单独授权。不得把本地样本数当作内网待处理清单 |
| D5 | 「计划解决时间」的合法格式与豁免判据 | **2026-09-28 领导统一格式后裁定为严格口径**：合法格式以 wiki（规则总表 5.4、11.4）为唯一标准，**凡不符合即视为「没有写时间」** → 打标签（情况一、二、三一律打）。因此豁免判据与非法模板判定**同源**，复用 `IssueResponsePlanFieldRules.parsePlannedResolutionAt`，不新增第二套判据、不存在两口径分叉。（此前的「存在性／情况二豁免」思路作废，不得再引用） |
| D6 | 取哪一份模板的计划解决时间 | **已裁定（用户 2026-09-28）**：最新一份优先，不可解析则回退更早模板，对齐老平台 `getPlanSolutionTime` |
| D7 | 两者都有时的口径 | **已裁定（用户 2026-09-28）**：回复模板存在即豁免，**不引入**「按最新模板类型分流」。依据 §4：该分流只影响本地 2 条，且无任何平台先例，还会造成「已解决议题被后发的无日期调研模板打回响应已延期」 |
| D8 | 解决期限 `planSolutionTime` 方向隐患 | **本单元不修，记录待裁**：`IssueTemplateParsingSupport.planSolutionTime` 取最旧模板，老平台取最新，导致解决延期方向新老不一致。修它会改变解决延期结果，须另立工作单元 |

明确否决的做法：新增回复模板 token 副本；在队列/HTTP 层加过滤补丁；为两模板逻辑或重构全仓解析目录；用「同源」理由扩大修复人语义；按最新模板类型分流豁免；把标签改名（GitLab 历史标签与老平台在用，破坏性变更）。

## 7. 接口契约

- `IssueSlaRules.isResponseDelayed(List<String>, String)` 与 5 参重载**签名不变**，只改变豁免条件；`hasResponse` / `hasFixCaseNote` 语义不变。
- 新增包级方法（无公共 API 变化）：
  - `IssueResponseTemplateParser.hasParseablePlanSolutionTime(String notesText) -> boolean`：备注倒序聚合下，任一份响应模板的「计划解决时间」字段可解析即 true；最新优先、缺失回退更早模板（D6）。
  - **不新增**字段级判据：豁免直接复用 `IssueResponsePlanFieldRules.parsePlannedResolutionAt != null`，与 `planned_resolution_at` 事实列、非法模板判定同源（D5 严格口径）。
- 推荐范围**不新增类、表结构、端点或迁移**；`has_response`（列）、`research_template_time`、`fix_user`、`planned_resolution_at/_text`、`planned_merge_version_branch` 语义保持。
- `CustomerIssueDelayLabelWritebackService.LabelChange` 与 `add_labels` / `remove_labels` 协议不变，仍只允许两种延期标签。符合写回范围的议题会**新增**标签（`false→true`）或**移除**已有标签（`true→false`），不是仅停止未来添加。

## 8. 风险与假设

- **新旧平台写回冲突（已确认为硬约束）**：老平台写回无法在运行期关闭，且用**覆盖式** `setIssueLabels` 写全部标签（见 §5.8）；新平台判据又比老平台更严（老平台完全不看计划解决时间）。两者并行必然对同一议题反复增删并互相覆盖，因此新判据上线的前置条件是先停掉老平台的写回。
- **摘标方向的真实副作用**：30 条摘标与 70 条加标都是真实 GitLab 变更，会留下事件与通知，不是无副作用回滚。先保存标签基线、限定授权样本并审阅预期差异。
- **格式口径已统一（消除分叉）**：D5 采用严格口径后，「计划解决时间非法」与「响应延期豁免」使用**同一判据**，不再存在「非法数据页判非法、延期问题页判已响应」的矛盾。规则说明与 `docs/architecture.md` 事实契约须写明二者同源，避免后人新增第二套解析。
- **范围限制**：已关闭、2026 前创建、接口报错等范围外议题不会被现有队列清理历史标签。若要求这些存量也处理，须另行确认范围。
- **存量收敛依赖**：发布时来源评论须已同步，且须等待周期重算与发布链路收敛；只更新代码不等待事实更新不算生效。
- **待验证**：D-14～D-16 的实测只证明写回机制可用，不证明新判据语义已通过。仍须补新语义测试、真实链路验收与获授权后的发布门禁。
- **并行工作树**：当前 `FactBuildService`、范围版本及多个统计服务已有未提交改动；本次仅修订方案，不重启服务、不触发迁移、不运行黄金链、不提交或推送。
- **假设**：`raw_payload` 对所有目标议题已含完整评论聚合（与 `research_template_time`/`fix_user` 同源），因此存量议题无需重建事实即可由周期重算收敛；该假设须在实施验收时用实际收敛结果确认。
