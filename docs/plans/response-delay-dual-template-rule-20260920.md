# 响应已延期口径：响应模板 ∨ 回复模板

## 进度与中间物

- 状态：调查完成，**模板身份已核实**（用户 2026-09-20 指示以老平台源码为参考），业务与实现口径待审批；**未开始实现**，未修改任何代码、配置、数据库或既有文档（本方案自身除外）。
- 需求来源：领导 2026-09-20 原话「CCProduct 上的『响应已延期』标签打的规则改一下吧。按照响应模板或回复模板回复的，都不需要打响应已延期。」
- 交付物：本方案（口径目标版本、单一权威模型设计、消费面与影响、待裁决项）。
- 本轮证据：只读代码走查 + 本地真库（`qaflex-dev-postgres-15432`，project 325 镜像）只读统计 + 老平台源码（`D:/projects/spidergitdata-dev`）与冻结夹具的模板身份核实。未运行应用、未跑测试、未触发同步或事实重建；不把静态走查表述为功能验证。用户对「响应模板」身份提出质疑后已重做交叉核实（新老平台源码 + 路由 + 用户可见规则文案 + 规则文档 + 真实评论结构枚举），结论未变，证据见 §3.2。
- 未提交工作树改动（评审代码、文档、大量黄金快照）全部保留，与本工作单元无关。
- 前置依赖：`docs/plans/delay-label-writeback-prewriteback-gate-fix-20260920.md`（写回闸门缺陷已修复并完成真实链路验证，`remove_labels` 分支已于 2026-09-20 在本地 GitLab 实测通过）。
- 待裁决：**D1 已核实关闭**（三个叫法同一模板：`# 问题调研情况说明` 正文；「响应模板」= 新平台自身术语 D-03/11.4/11.5，「缺陷调研模板」= 老平台 CC 页面文案；回复模板 = 《【模板】缺陷修改的回复模板》首行 `### 1、修复状态`）；D2（是否要求按规范填写，建议「出现即算」）、D3（响应时间列与响应效率口径是否同步放宽）、D4（存量 30 余条与写回开关的处理时机）仍待裁决。

## 1. 恢复线索

- 调查基点：`main` 的 `7565afd5` 加当前工作树；不能仅凭该 commit 重现工作树中已有的未提交状态。
- 恢复后首条命令：`git status --short`。随后读取 `AGENTS.md`、`docs/progress.md`、本方案与前置方案，核对实施指派与工作树变化。
- 相关权威：`docs/platform-page-business-rules.md` 5.3 节（:366-391）、`docs/decisions.md` D-03/D-07、`docs/architecture.md` 响应判定与事实发布相关段落。
- 当前下一步：用户裁决 D1〜D4 并指派实施；批准方案不等于自动开始编码。

## 2. 目标与边界

### 2.1 目标版本

把「响应已延期」中「已响应」的判据，从当前单一的「问题调研情况说明」扩展为「**响应模板 ∨ 回复模板**」，并把该判据收敛为全仓**唯一权威定义**：现在同一个模板身份散落在至少 5 处、语义互不一致（见 §3.3），本次一并消除，不新增第二套并行定义。

### 2.2 明确不做

- 不改「解决已延期」判定。该侧已用 `### 1、修复状态`（`hasFixCaseNote`）参与解决闭环豁免，属既有正确行为，本次不动。
- 不改 SLA 时长（P1 24h / P2 48h / P3 72h）、不改 2026-01-01 创建时间下限、不改项目 325 范围、不改建议类排除口径。
- 不改标签名与写回协议。写回仍只允许 `add_labels` / `remove_labels` 增删 `响应已延期`、`解决已延期`。
- 不为兼容旧行为保留新旧双套 token 列表、开关或回退分支。

### 2.3 成功标准（可验证）

1. 同一份评论文本，Java 判定「已响应」的结果在全仓**只有一个来源**，`IssueSlaRules`、`IssueClassificationRules`、模板解析与展示解析不再各自持有 token 副本。
2. 「仅回复模板、无调研模板」的议题不再被判为响应已延期；「两者都无」的议题行为与现状完全一致。
3. 相关后端默认快速套件全绿；受影响端点的既有测试与黄金快照按门禁流程同步更新并经用户审阅。
4. 未触碰任何与响应判定无关的既有功能产出（解决延期、延期原因、BI、评审域等）。

## 3. 约束与背景（现状实现事实）

### 3.1 判定链路

- 生产唯一入口：`backend/src/main/java/com/data/collection/platform/service/IssueSlaRules.java:29-42`（5 参重载）。先 `hasResponse` 短路为「不延期」，否则比 `now - created_at_source` 是否超过响应 SLA（`:14-16`、`:105-114`）。
- 两处计算点共用该入口：同步构建 `service/IssueFactSourceRowMapper.java:139-147`、每小时兜底 `service/FactBuildService.java:308-344`（仅三个布尔发生变化才回写）。**口径只需改一处即两处生效。**
- `hasResponse` 当前语义（宽松）：`IssueSlaRules.java:21-23` → `service/IssueTemplateParsingSupport.java:65` → `service/IssueRuleSupport.java:31-43,54-59`，即对**整串聚合评论**做 `trim().toLowerCase()` 后 `contains`。后果：不要求任何小节被填写、空模板算已响应、任何人在评论里提到这句话也算、全角字符不匹配、无评论边界。
- 评论输入：`service/GitlabFactSourceSqlProvider.java:70` 把 `ods_gitlab_notes.note` 按 `created_at desc, id desc` 用 `\n---\n` **全量倒序拼接**（不过滤系统评论与作者、无截断），落入 `issue_fact.raw_payload`。

### 3.2 两类模板的真实身份（已用新老平台源码、规则文档与真实数据交叉核实）

**结论：三个叫法指向同一个模板正文，不存在第三类模板。**

| 叫法 | 出现位置 | 所指模板正文 |
|---|---|---|
| 「缺陷调研模板」 | 老平台 CC 延期页与响应效率页的用户可见规则文案；新平台 5.4.1 非法类型名 | `# 问题调研情况说明`（H1）+ `##` 小节 |
| 「响应模板」 | 新平台自身术语：`docs/decisions.md` D-03 标题「CC_PRODUCT 客户成员与**响应模板**事实」、业务规则 11.4/11.5、`IssueResponseTemplateParser` 类注释 | 同上 |
| 「问题调研情况说明」 | 模板正文首行，也是两平台的判定 token | 同上 |

- **响应模板**（领导的叫法）正文：首行 `# 问题调研情况说明`（H1），小节用 `##`：`## 问题类型：`（`* [x] 缺陷` / `* [ ] 需求`）、`## 问题原因：`、`## 修改方案：`、`## 一级缺陷的修改方案请模块负责人签字确认：`、`## 计划解决时间：`、`## 计划合并的版本分支：`。
- **回复模板** = 《【模板】缺陷修改的回复模板》，正文**首行即** `### 1、修复状态`（无前置标题），小节用 `###` 编号：`### 1、修复状态`（已解决 / 部分解决 / 申请延期 / 无法复现）、`### 2、缺陷原因分析`、`### 3、请描述具体原因：`、`### 4、修改方案：`、`### 5、是否由修改其他缺陷引起`、`### 6、修改该缺陷可能影响的功能：`、`### 7、是否对可能影响的功能进行了测试`、`### 8、有无遗留问题或潜在的影响？`、`### 9、是否更新了关联关系表`。

**该模板确实在客户问题模块使用（回答"缺陷调研模板在客户问题模块也用吗"）：**

1. 老平台 CC 延期问题页 `/getDelayIssueCCProduct`（`Router.js:133-135`）→ `DelayIssueTable.vue`，页面硬编码 `projectId: '325'`（:168），其规则提示（:18、:21）写明「4. 缺陷响应条件：按照"**缺陷调研模板**"进行了回复」「7. 缺陷解决期限：超过了预计解决时间（**模板中填写的计划解决时间**、最长18天）」。
2. 老平台 CC 缺陷响应效率页 `IssueRespEfficiency.vue`，同样硬编码 `projectId: '325'`（:84），规则提示（:15-16）写明「1. 响应条件：仅统计"已响应"的缺陷（**回复调研模板**）」「2. 响应周期：从议题创建到"**第一条调研模板创建**"的时间」。
3. 新平台客户问题模块规则 5.3 第 4 条：**「响应检测规则：评论区出现 `# 问题调研情况说明` 即认为已响应」**；5.3 第 7 条解决期限同样取缺陷调研模板的计划解决时间；5.4 第 1 条把「未按照要求填写 `缺陷调研模板`」列为**客户问题**非法类型。
4. 新平台代码侧：`GitlabFactSourceSqlProvider:71` 的 `research_template_time`、`IssueResponseTemplateParser` 的 CC 计划解决时间/计划合并分支解析，都用同一 token。
5. 老平台全仓只有两个模板常量：`ISSUE_RESEARCH_TEMPLATE = "# 问题调研情况说明"`（`IssueServiceImpl.java:84`）、`ISSUE_NOTE_CAUSE_HEADER = "### 1、修复状态"`（:68，:73-74 的提醒评论把它明确称为《【模板】缺陷修改的回复模板》）。

**真实数据实测（本地 dev 库 project 325：CC_Product，created_at >= 2026-01-01）：**

- 议题 1156（open 876）；含 `# 问题调研情况说明` 148 条；含 `### 1、修复状态` 83 条；**仅含回复模板而无调研模板 31 条（其中 open 28 条）**。
- 对 CC 议题评论枚举全部级别标题，只出现上述两类模板：`# 问题调研情况说明` 150 次及所属 `##` 小节，`### 1、修复状态`~`### 9、是否更新了关联关系表` 各 61~82 次。**没有第三类模板结构。**
- 字面量「响应模板」「回复模板」「缺陷调研模板」在 CC 议题评论中出现 **0 次**——它们是业务叫法，不是模板正文；项目 325 的 `issues_template` 也为空。故不能靠字符串匹配找「响应模板」，只能用上述语义证据定位。
- 冻结夹具同样印证：`问题调研情况说明` 166 次、`1、修复状态` 118 次、「响应模板」0 次；标题带 `【模板】` 的议题只有《【模板】缺陷修改的回复模板》。
- 现状：**只有响应模板参与「已响应」判定**；回复模板仅参与解决闭环豁免与缺陷原因归类（`IssueSlaRules.java:13,71-73`、`IssueClassificationRules.java:44,182-184`）。
- 老平台对两类模板的**匹配语义先例**（实现时须对齐）：豁免判定一律用"出现即算"——响应用 `content.contains("# 问题调研情况说明")`（大小写敏感、必须带 `# `），回复豁免用 `hasIssueCaseNote`（任一评论 `contains("### 1、修复状态")`）；**内容规范性只用于"非法模板"判定，从不参与豁免**。归属/解析另有更严口径：`fix_user` 与缺陷原因归类要求**评论首行**命中 `### 1、修复状态`。新平台现状与这一分工一致（`IssueSlaRules` contains 用于豁免、`IssueTemplateParsingSupport:213` 与 SQL `fix_user` 首行用于解析），因此回复模板只需接入豁免判据，不需改动解析侧。

### 3.3 同一身份的重复定义（本次要收敛的屎山）

| 位置 | 定义 | 语义 |
|---|---|---|
| `service/IssueSlaRules.java:11-12` | `["# 问题调研情况说明", "问题调研情况说明"]` | 整串 contains，忽略大小写 |
| `service/IssueSlaRules.java:13` | `["### 1、修复状态"]` | 整串 contains，忽略大小写 |
| `service/IssueClassificationRules.java:44` | `FIX_TEMPLATE_HEADER_TOKENS = ["### 1、修复状态"]` | 同上（副本） |
| `service/IssueClassificationRules.java:45-46` | `RESEARCH_TEMPLATE_HEADER_TOKENS` / `RESEARCH_TEMPLATE_HEADER` | 副本 + 严格变体 |
| `service/IssueTemplateParsingSupport.java:24` | `FIX_TEMPLATE_HEADER = "### 1、修复状态"` | 副本 |
| `service/IssueResponseTemplateParser.java:9` | `TEMPLATE_HEADERS` | 副本（展示用，取首段，与解析侧取末段方向相反） |
| `service/GitlabFactSourceSqlProvider.java:71` | `note like '%# 问题调研情况说明%'` | **大小写敏感、必须带 `# `**，与 Java 口径不同 |
| `service/GitlabFactSourceSqlProvider.java:78-84` | `fix_user` 取首行 `= '### 1、修复状态'` | **要求首行精确匹配**，与 Java 的 contains 不同 |

### 3.4 老平台对照与模块边界

- 老平台响应判定只认 `# 问题调研情况说明`，且比新平台严格（区分大小写、必须带 `# `）：`D:/projects/spidergitdata-dev` 的 `IssueServiceImpl.java:84,864-881,1002-1021`。老平台不认回复模板——**本次是相对老平台的口径变更**，涉及新旧并存期的职责边界（见 §8）。
- 「响应已延期 / 解决已延期」是**客户问题模块专有口径**：新平台业务规则只在 5.3（客户问题 → 延期问题）定义响应检测；系统测试模块（第 4 章）只有「申请延期缺陷原因分析」，口径来自标签 `delay_cause`，与响应判定无关。老平台同样只有 `/getDelayIssueCCProduct` 一个延期页，且硬编码 `projectId=325`。
- 事实列 `is_response_delayed` / `has_response` 按数据源全量计算（含项目 9 等非 CC 来源），但消费方只有客户问题侧：`CustomerIssueDelayIssuesBoardService`、`CustomerIssueByFunctionBoardService`、`CustomerIssueDelayLabelWritebackQueueService`、`CustomerIssueRecordService`（`delayOnly` 筛选）与 `IssueFactDiagnosticsService` 诊断。系统测试页面不消费该列，故本次口径变更的可见影响面限定在客户问题模块内。

## 4. 证据与影响规模

本地真库只读统计（project 325，`created_at >= 2026-01-01`，2026-09-20 复核）：

| 指标 | 数量 |
|---|---|
| 议题 | 1156（open 876） |
| 含响应模板（`# 问题调研情况说明`） | 148 |
| 含回复模板（`### 1、修复状态`） | 83 |
| **仅有回复模板、无响应模板** | **31（open 28）** |

口径变更的实际增量即这类议题：它们会从「维持/新增标签」翻转为「**删除标签**」，写回侧 `remove_labels` 分支首次被真实触发，内网等同于批量摘除标签。这不是文案级改动，需明确确认为预期效果。上一版记录的 30 条为同一量级（镜像数据随后续同步自然增长）。

## 5. 方案与步骤

### 5.1 建立单一权威模型（核心）

新增一个包内可见的模板目录职责（建议命名 `IssueTemplateCatalog`，置于 `service` 包），唯一定义两类模板：

- `RESPONSE`（响应模板）：问题调研情况说明；
- `REPLY`（回复模板）：`### 1、修复状态`。

对外只暴露判据方法，例如 `hasResponseReply(notesText)` 与 `hasReplyReply(notesText)`，并在类文档中**显式写明匹配语义**（是否忽略大小写、是否要求独立评论边界或首行命中、是否要求小节非空）。全仓其余定义改为引用该目录，删除副本：`IssueSlaRules:11-13`、`IssueClassificationRules:44-46`、`IssueTemplateParsingSupport:24`、`IssueResponseTemplateParser:9`。匹配语义与「是否要求规范填写」由 **D2** 决定；token 集合已由 D1 核实确定。

### 5.2 判据变更

`IssueSlaRules.isResponseDelayed` 的短路条件由「有响应模板」改为「响应模板 ∨ 回复模板」。方法签名保持不变，两处计算点零改动。

匹配语义按 §3.2 核实到的老平台先例落地（D2 建议「出现即算」）：

- 两类模板共用**同一个**匹配函数（忽略大小写、整串 contains），严格度差异只体现在各自的 token 列表，不引入第二套匹配逻辑。
- **响应模板必须保持现有双 token（`# 问题调研情况说明` 与 `问题调研情况说明`）与忽略大小写语义，本次不得收紧**：现有 139 条命中里包含仅出现无 `#` 变体的评论，收紧会让部分议题掉出豁免、把标签范围反向扩大，超出领导要求的变更边界。老平台更严（大小写敏感且必须带 `# `）属既有差异，不在本次一并收紧。
- 回复模板采用老平台同款 token `### 1、修复状态`；解析侧（`fix_user`、缺陷原因归类）继续用"评论首行命中"的更严口径，豁免侧用 contains——这一分工与老平台完全一致，不改。

### 5.3 事实层与展示层口径一致性（D3）

`research_template_time`（`GitlabFactSourceSqlProvider.java:71`）是响应效率看板「调研模板回复时间」的一等输入（`statistics/CustomerIssueResponseEfficiencyBoardService.java:86,104,423,577,854,864`）。若只改布尔而不动该列，会出现「已响应但无响应时间」的自相矛盾状态（30 条议题不进响应效率明细，却被豁免响应延期）。两个选项：

- **选项 A（推荐）**：以新权威模型重写 `research_template_time` 与 `fix_user` 两列语义，使事实层与判定层同源。代价：响应效率看板的行集合与「回复时间」取值口径随之变化，属**业务指标语义变更**，需领导确认。
- 选项 B：仅改布尔判定，两列保留旧语义并记为「展示口径」。缺点是制造半新半旧的双口径，后续维护必须同时理解两套语义，与本次「消除重复定义」的目标相冲突，不推荐。

### 5.4 版本号与缓存

口径变化必须 bump 规则版本，否则统计与记录快照读旧结果：

- `statistics/CustomerIssueDelayIssuesBoardService.java:50` `RULE_VERSION`（当前 `customer-issue-delay-issues@2026-09-08-v5`）；
- `service/CustomerIssueRecordService.java:33-34` `CC_PRODUCT_RULE_VERSION` / `DELAY_RULE_VERSION`。

### 5.5 测试（先加测试，后改实现）

- `service/IssueFactNormalizationRulesTest.java:414-476` 已有「出现模板即取消延期」的断言，扩展覆盖双模板组合。
- 补上当前空白：`FactSourceRowMapperTest`、`FactBuildServiceTest`、`IssueFactSourceInstancePipelineTest` 对 `is_response_delayed` **零断言**，需先加可复现测试再改实现。
- 边界用例：仅回复模板、两模板都有、两模板都无、空模板、正文随手提及、大小写混排、全角字符、首行命中与非首行命中（取决于 D2 语义）。
- 前端：`utils/rule-explanation-copy.ts:63` 的规则说明文案需与后端语义一致。

### 5.6 消费面核对（不改逻辑，只确认无意外）

| 消费方 | 影响 |
|---|---|
| 延期问题看板 `resp_delay_p1/p2/p3/sum` 与延期类型文案 | 30 条议题计数与明细变化（`statistics/CustomerIssueDelayIssuesBoardService.java:600-603,830-841`） |
| 客户问题列表「仅看延期」筛选 | 行集合变化（`IssueFactRecordConditionBuilder.java:49-51,84-86` 的 `delayOnly`） |
| 记录页红 tag「响应延期」 | 展示变化（`frontend/src/views/CustomerIssueRecordsView.vue:570`） |
| `/api/facts/issue-diagnostics` | `responseDelayedCount` 变化（当前 736） |
| 响应效率看板 | 仅当 D3 选 A 时变化 |
| BI 看板 | **不消费**该布尔（`docs/bi-dashboard/data-contracts.md:36` 用 `delay_issue` + `delay_cause`） |
| `delay_issue` / `delay_reason` / `delay_cause` / `fixed_label_time` / `is_resolve_delayed` | 各自独立计算，取值不受本次影响 |

### 5.7 文档同步

- `docs/platform-page-business-rules.md:26`、`:375`（第 4 条）、`:376`（第 5 条）改写为双模板口径；
- `:390`（第 14 条写回规则）补注「删除分支因口径变更现已可达」；
- `docs/architecture.md` 响应判定段落同步；
- `docs/decisions.md` 新增一条决策（口径权威与单一模板目录），编号顺延 D-14；
- `scripts/contracts/fact-field-contract.md` 目前**未登记** `is_response_delayed` / `has_response`，本次补齐登记。

## 6. 决策记录

| 编号 | 待裁决事项 | 建议 | 影响 |
|---|---|---|---|
| D1 | 「响应模板」与「回复模板」分别指什么 | **已核实关闭**：二者同属客户问题模块使用的两个模板。「响应模板」= `# 问题调研情况说明` 正文（新平台 D-03/11.4/11.5 自称「响应模板」，老平台 CC 页面称「缺陷调研模板」）；「回复模板」= 《【模板】缺陷修改的回复模板》首行 `### 1、修复状态`。CC 议题评论中不存在第三类模板结构，字面量「响应模板」出现 0 次 | 决定 token 集合；依据见 §3.2 |
| D2 | 「按模板回复」是否要求内容按规范填写才豁免 | **建议沿用"出现即算"**（与老平台先例一致，且方向性理由：改成"必须规范填写"会使豁免面变小、被标"响应已延期"的议题反而变多，与领导意图相反）；内容规范性只继续服务 `is_illegal` | 决定空模板/错填模板是否仍算已响应；影响豁免范围 |
| D3 | 是否同步放宽 `research_template_time` 与响应效率口径 | 选项 A（同源一致） | 选项 A 改变业务指标语义；选项 B 留下双口径 |
| D4 | 存量 30 条与写回开关的处理时机 | 本次只落口径、写回开关保持关闭，等正式接管时统一执行 | 决定内网是否立刻批量删标签 |

已否决：在 `IssueSlaRules` 上再挂一份回复模板 token 列表（新增副本，屎山）；在写回侧做特例过滤（错位的补丁）；给老平台加兼容分支或双轨判定（开发期禁止）。

## 7. 接口契约

- `IssueSlaRules.isResponseDelayed(List, String, LocalDateTime, String, LocalDateTime)` 签名不变，调用点不变。
- 新增模板目录类为**包内可见**，不新增公开 API、不新增表结构、不新增端点。
- 若 D3 选 A：`research_template_time` 与 `fix_user` 的语义变化属事实层契约变化，须同时在 `scripts/contracts/fact-field-contract.md` 登记。
- 端点目录不变（无新增/删除端点），但既有端点响应内容变化。受影响黄金快照（需按门禁流程更新并经用户审阅）：`customer-issues/get___records__delay-default.json`、`__delay-page1.json`、`__cc-product-default.json`、`get___records_export__delay.json`、`get___records_filter-options__delay.json`、`get___records_rule-explanation__delay.json`、`statistic-boards/get___{boardKey}__customer-issue-delay-issues.json` 及其 `_export`、`facts/get___issue-diagnostics__default.json`；`golden/customer-issue-delay-issues/*.json` 9 个 golden-master 文件含 `resp_delay` 字段。

## 8. 风险与假设

- **新旧平台并存冲突（高）**：内网写回目前仍由老平台承担，老平台只认 `# 问题调研情况说明`。只改新平台口径会导致新平台判定「这 30 条不该延期」而老平台继续保留标签，两端长期不一致；若新平台同时开写回，则与老平台小时级任务互相拉扯。落地前必须明确写回责任方（改老平台 or 切换接管）。
- **批量删标签不可逆（中）**：`remove_labels` 在生产 GitLab 上会真实摘除 30 个标签，需人工恢复成本；建议按 D4 先关写回、只落口径。
- 已排除的假设：D1 已由三重证据核实（§3.2），领导所指「响应模板」「回复模板」与本仓库两类模板文本一致，不再是待验证假设。
- 未验证：口径变更后的真实链路行为需在前置缺陷修复后，于本地 GitLab 用真实链路实测（含 `remove_labels` 分支），不得以静态走查代替。
- 风险：若 D2 选择「要求规范填写」，需确认 `validateCustomerResearchTemplate` 现有校验失败路径不会把「已响应」误判为「未响应」而扩大标签打回范围。
