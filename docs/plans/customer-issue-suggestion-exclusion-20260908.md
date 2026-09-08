# 客户问题统计排除建议类问题（2026-09-08）

## 进度与中间物

- 状态：**方案已批准，实施进行中。**
- 已完成实施（2026-09-08）：
  - P3 共享类改名：`SystemTestSuggestionMetricSupport` → `SuggestionMetricSupport`（git mv 保留历史），6 个引用文件全部更新，旧名零残留。
  - P1 缺陷汇总：FACT_SQL+mapIssueFact 接入 exclusion_reason；IssueSource 三口径判定方法；matchesMetric 常规列全部 regular 前置+suggestion_total 三口径；toRowData 整块 regular 拆分+regularOverall 占比分母；规则流/指标定义文案；建议类列 headerTooltip；RULE_VERSION→2026-09-08-v7。附带清理：删除死代码 toIssueSource、isNewIssue、isSolvedLike、isClosedResolved；补 module_total 下钻 case（单元格与下钻口径一致，系统测试侧存在同款既有不一致未动，见风险 6）。
  - P2 延期看板：FACT_SQL+IssueSource 补 severity_level/exclusion_reason；exclude-filter visible=isRegularMetricIssue；删除死字段 rowSources；规则说明文案；RULE_VERSION→2026-09-08-v5。
  - P4 测试：两 ServiceTest 补 stub+重构为每用例数据 stub+新增建议类用例 2 个（延期排除+汇总排除含"建议+二级"混合场景）；两 GoldenMaster 期望重建（延期金标与旧版零内容差异，仅缺陷汇总 tooltip 变化）；删除延期金标死 matches stub。statistics 包 56 测试全绿。
  - P6 文档：业务规则 5.1 第 4 条/5.2 第 6 条/5.3 第 15 条修订完成。
- 进行中：后端默认套件全量验证（后台运行）。
- 待办：本地端到端验证（18080 实测两看板+本地库数字复核）；progress.md 记录；黄金基线留待发布打包前门禁时机。

## 恢复线索

- 当前阶段：实施收尾——默认套件验证与本地端到端验证。
- 首条命令（恢复时）：`cd backend && ../tools/maven/apache-maven-3.9.9/bin/mvn.cmd test -Dtest='com.data.collection.platform.service.statistics.**'`（statistics 包 56 测试应全绿）。
- 上一份相关计划：`docs/plans/intranet-test-issues-resolution-20260904.md`；领导前轮调查结论在记忆 `project-leader-requests.md`。

## 目标与边界

**用户原始需求（转述领导指示）**：
1. "客户议题数据统计，未按时响应、未按时解决的数据，需要排除'建议类'问题" → 客户问题延期问题看板（`customer-issue-delay-issues`）的「响应延期的缺陷数量」「解决延期的缺陷数量」全部计数列。
2. "各模块议题及修复率的数据，是否不包括'建议类'，如果包括了，也需要去掉" → 客户问题缺陷汇总看板（`customer-issue-defect-summary`）的「模块总缺陷数(个)」「修复率(%)」及其自洽连带指标。

**可验证的成功标准**：
1. 建议类议题不出现在延期看板任何计数列及其下钻明细。
2. 缺陷汇总看板：模块总缺陷数、缺陷占比、已修复/未更新、修复率、关闭率、未关闭数、P1/P2/P3、新发议题、遗留率等全部常规指标排除建议类；「建议类缺陷(个)」列继续展示建议类计数；常规列下钻不含建议类、建议类列下钻显示建议类。
3. 规则流、规则说明、指标定义文案与新口径一致；RULE_VERSION 升级。
4. 默认套件全绿；黄金基线按门禁流程确认差异后重建并全绿。
5. 本地实测：325 样本修复率从 32.9% 升至约 42.6%（本地库 1054 样本中 322 条建议类）。

**明确禁止**：
- 不动事实层 `issue_fact.is_excluded`（保持只排 closed+申请否决/需求如此/设计如此；建议类排除是看板统计层口径）。
- 不动其余三个客户问题看板（响应效率 `customer-issue-response-efficiency`、缺陷原因 `customer-issue-defect-cause`、按功能 `customer-issue-by-function`）——领导未点名，保持现状含建议类。
- 不动 `CC_PRODUCT议题` 记录页与客户问题缺陷非法数据页（记录/非法页不套用统计排除，业务规则 5.1 第 8 条已隔离）。
- 不动系统测试侧任何行为（仅把共享判定类改名并保持其语义不变）。
- 前端零改动（列结构不变，数字由后端计算）。

## 约束与背景

- **口径依据**：系统测试侧已有"领导确认后的新平台口径"（2026-07-10 `7ee4ac19` 落地 `SystemTestSuggestionMetricSupport`；业务规则 4.3 第 5 条、4.4 第 4 条权威记录）：建议类只在「建议类缺陷(个)」独立列展示，不进入一二三级、P1/P2/P3、总计、占比、修复率、关闭率等常规指标。
- **客户问题侧现状依据**：2026-07-08 `eb7bcd02`（`V20260708_01` 迁移）专门把 325 的建议类从排除名单移除以对齐老平台（老平台客户问题统计含建议类）；业务规则 5.1 第 4 条现行文字"不排除建议类问题"。**本次变更是领导新指示覆盖旧决策**，两模块口径将统一为系统测试侧口径，与新老平台对齐口径产生有意分歧（系统测试侧已有"故意不 1:1 对齐老平台"先例）。
- **黄金基线纪律**：有意行为变更 → 展示差异 → 用户确认 → `-Dgolden.update=true` 重建受影响快照 → git diff 人工审阅 → 比对模式复跑全绿。夹具不动（夹具 GitLab 切片中 325 有建议类标签 `类别：建议` id=505，缺陷汇总快照总计行 suggestion_total=29、module_total=350，重建后 diff 真实可见）。
- 测试位置：`backend/src/test/`；后端 18080；本地库 `127.0.0.1:15432/qaflex`。

## 证据与根因

1. `IssueLabelRules.java:22,28-30,107-136`：系统测试项目排"功能屏蔽/已拒绝/建议"+closed 申请否决/需求如此；客户项目 325 只排 closed+申请否决/需求如此/设计如此，注释明确"不排除建议，与老平台一致"。
2. `CustomerIssueDelayIssuesBoardService.java:364-441`：规则流 `visible = !excluded()`，exclude-filter 文案自述"不排除建议类"；延期判定 `IssueSlaRules` 只看超时+回复/闭环，不看建议类 → 建议类超期即计入延期数量。
3. `CustomerIssueDefectSummaryBoardService.java:491-497,914-996`：`module_total = issues.size()` 全量、`fix_rate = solved/total`，分母均含建议类；`suggestion_total` 仅为独立展示列（category 含"建议"）。
4. `SystemTestSuggestionMetricSupport.java`（同包共享类）：三口径合一判定（severity='SUGGESTION' OR category 含'建议' OR exclusion_reason='建议'）；`isRegularMetricIssue`/`isSuggestionColumnIssue`/`regularMetricSql`/`suggestionMetricSql`；系统测试缺陷汇总、延期分析、缺陷原因、多板四个看板全部接入。325 的 exclusion_reason 永远不会是'建议'，第三口径恒假，实际生效前两口径，与系统测试行为一致。
5. 系统测试侧可见层与统计层分离：`exportIssueRecordsWorkbook`（全量议题数据）→ `loadBoardScopedSources` → `finalSources()` 走 `isVisibleForRegularOrSuggestionColumn`（regular+suggestion 都保留）；只有 `AggregateBucket`/`matchesMetric` 计数层排除建议类。
6. 本地库实证（1512 条 325 议题，severity='SUGGESTION' 363 条）：延期样本 815 条中建议类 309（响应延期 736 中 290=39%，解决延期 592 中 298=50%，带 P1/P2/P3 进表格计数的 772 中 302）；缺陷汇总样本 1054 中建议类 322=31%，修复率当前 32.9%、剔除建议类后 42.6%；一/二/三级列因 severity 精确匹配天然不含建议类。
7. git 时间线：2026-07-08 客户侧对齐老平台（不排建议）→ 2026-07-10 系统测试侧落地领导确认新口径（独立列+隔离）。两条决策线并存即用户看到的"系统测试做了、客户问题没同步"。

## 方案与步骤

**总体策略**：复用系统测试侧机制（判定类、计数拆分、可见层保留），最小落地到两个客户问题看板；随单元将共享类改名 `SuggestionMetricSupport`（名实相符，红线禁止逃避重构；test 目录零引用，改名安全）。

**P1 缺陷汇总看板（`CustomerIssueDefectSummaryBoardService`）**
1. FACT_SQL 增加 `exclusion_reason` 列读取，`mapIssueFact`/`toIssueSource` 接入（当前硬编码 `""`）。
2. `IssueSource` 增加 `isRegularMetricIssue()`/`isSuggestion()` 方法，调用共享判定类（对齐系统测试 `IssueRow` 做法）。
3. `matchesMetric`：全部常规列（level1_*、level2_*、level3_*、solved、open、extension、retest、new_issue_*、p1/p2/p3、level2/3_legacy）加 `isRegularMetricIssue() &&` 前置；`suggestion_total` 改为 `isSuggestion()`（三口径合一，与系统测试同款）。
4. `AggregateBucket.toRowData`：所有常规计数/比率基于 `issues.stream().filter(isRegularMetricIssue)`；suggestion 列基于 `isSuggestion`；`buildBoardResponse` 的 overall（缺陷占比分母）改用 regular 计数（对齐系统测试 `regularOverall`）。
5. 规则流 `exclude-invalid-issues` 文案更新（"常规指标剔除建议类；建议类仅在建议类缺陷列展示"，对齐系统测试 :476 表述）；`buildMetricDefinitions` 的 summary/建议类定义文案同步。可见层 `valid = !excluded` 不变（325 建议类未被事实层排除，天然保留在可见层）。
6. 下钻明细经 matchesMetric 自动拆分；「全量议题数据」导出走可见层自动保留建议类（对齐系统测试）；统计导出（缺陷汇总统计.xlsx）走计数层自动排除。
7. severity 筛选候选保留 SUGGESTION（`severityFilterOptions(true)`，与系统测试侧一致）。
8. RULE_VERSION：`customer-issue-defect-summary@2026-08-06-v6` → `@2026-09-08-v7`。

**P2 延期问题看板（`CustomerIssueDelayIssuesBoardService`）**
1. 规则流 `exclude-filter` 步骤：`visible = !excluded` → `!excluded && 非建议类`（内存过滤，规则流可见剔除量）；文案改为"剔除关闭后属于申请否决、需求如此或设计如此的数据，以及建议类问题"（领导口径：排除）。链路 gitlabReadable→open→delayed→priority→filtered、`AggregateBucket` 计数、总计行、下钻全链自动排除。
2. `rowSources`（规则流"应用页面筛选"基数）从 `scoped` 起点改为 `visible` 起点保持链路一致。
3. `getRuleExplanation` 说明文字与指标定义（resp_delay/fix_delay）补"排除建议类"表述。
4. RULE_VERSION：`customer-issue-delay-issues@2026-07-28-v4` → `@2026-09-08-v5`。
5. 不加「建议类延期」独立列（领导指示为"排除"；系统测试延期看板的独立列是其当时口径，本次不扩张）。

**P3 共享类改名**
1. `SystemTestSuggestionMetricSupport` → `SuggestionMetricSupport`，`SUGGESTION_HEADER_TOOLTIP` 一并迁移；更新 4 个系统测试看板调用点（纯重命名，零行为变化）。

**P4 测试**
1. 更新 `CustomerIssueDefectSummaryBoardServiceTest`、`CustomerIssueDelayIssuesBoardServiceTest` 现有断言（文案、规则流）。
2. 新增用例：建议类+P3+响应延期 → 不入 resp_delay_p3/sum 且下钻不含；建议类+已修复 → 不入 module_total/solved/fix_rate、计入 suggestion_total；全量议题导出仍含建议类；325 建议类 exclusion_reason 缺失时三口径判定退化为前两口径。
3. 更新两看板 `*GoldenMasterTest` 期望（夹具含建议类，输出数字将变）。
4. 默认套件全绿（秒级护栏自动覆盖端点登记，无新端点、无目录变更）。

**P5 黄金基线门禁（产出门禁时机）**
1. 实现完成后向用户展示预期差异说明（延期计数下降、缺陷汇总常规指标下降、修复率上升、快照 29 条建议类从常规指标移出）。
2. 用户确认有意变更 → `-Dgolden.update=true -Pgolden-baseline -Dtest=GoldenBaselineChainTest` 重建 → git diff 人工审阅（重点：`statistic-boards/get___{boardKey}__customer-issue-*` 与 export 快照）→ 比对模式复跑全绿。
3. 夹具与 `baseline-manifest.json` 不动（快照变更不属于夹具变更）。

**P6 文档收尾**
1. `docs/platform-page-business-rules.md`：5.1 第 4 条修订（"不排除建议类"→ 统计看板排除建议类的新口径，注明领导指示与日期 2026-09-08、记录页不受影响）；5.2 增加建议类条款（参照 4.3 第 5 条写法）；5.3 增加建议类排除条款；5.1 第 8 条记录页隔离确认无需改。
2. BI 口径核对表无客户问题条目（grep 零命中），无需修订；实施时复核一次。
3. `docs/progress.md` 记录；本计划完成后按生命周期归档规则处理。

**P7 本地端到端验证**
1. 起后端 18080，实测两看板：延期计数、缺陷汇总常规指标、建议类列、下钻明细、全量议题导出。
2. 本地库复核排除前后数字（修复率 32.9%→约 42.6%）。

## 决策记录

- **已选**：看板统计层排除（不动事实层 is_excluded）——保留明细可查性与记录页行为，避免议题事实全量重建；对齐系统测试侧实现形态。
- **已选**：复用三口径合一判定——与系统测试侧保证一致；客户侧第三口径（exclusion_reason='建议'）恒假但保留，行为与系统测试完全同源。
- **已选**：缺陷汇总整块常规指标排除（非只改领导点名的两列）——数学自洽必然（已修复/关闭/未关闭/新发议题等均为 module_total 的子集或以其为分母，窄执行会产生"新发议题数 > 模块总缺陷数"倒挂）；对齐系统测试先例。
- **已选**：缺陷汇总「全量议题数据」导出保留建议类——对齐系统测试可见层行为；统计导出跟随计数层排除。
- **已选**：延期看板内存过滤（非 SQL 谓词先行）——规则流 exclude-filter 步骤可见"剔除建议类 N 条"，口径透明。
- **已选**：共享类改名 SuggestionMetricSupport——名实相符红线；纯重命名零行为变化。
- **已否决**：事实层把建议纳入 is_excluded——影响面=议题事实全量重建+全看板（含范围外三看板）+记录页连带，超出领导指示范围。
- **已否决**：延期看板加「建议类延期」独立列——领导指示为"排除"，不加列不扩张 UI。
- **已否决**：五个看板全做——领导点名两个；响应效率/缺陷原因/按功能保持现状，待后续指示（见风险）。

## 接口契约

- 无新增/删除 REST 端点、无表结构变更、无请求/响应字段结构变更（列 key 不变，仅数值口径变化）。
- 快照失效机制：两看板 RULE_VERSION 升级 → `statistic_board_snapshots` 自动失效 → 部署后首轮访问/事实刷新自动重建，无需数据迁移。
- 常量变更：`SuggestionMetricSupport`（由 `SystemTestSuggestionMetricSupport` 改名）；`RULE_VERSION` 两处升级（见 P1.8/P2.4）。

## 风险与假设

1. **新老平台口径分歧**：变更后新平台客户问题统计与老平台（含建议类）不一致——答复领导时说明这是按新指示的有意口径统一（系统测试侧已有"故意不 1:1 对齐老平台"先例与防止回退的注释保护）。
2. **模块内残余不一致**：缺陷原因（5.2.1）、按功能（5.5）、响应效率（5.6）三页仍含建议类；领导若后续要求统一，同款机制扩展即可（P1/P2 已铺路）。
3. **severity='SUGGESTION' 含"需求"token**：标签"需求"也归一为建议类（363 条中纯"建议"标签仅 140）——沿用系统测试侧既有判定，不另设口径；若领导本意仅"建议"标签，数字会不同，需在答复时说明判定口径。
4. **假设**：内网 30001 部署新包后快照自动重建正确（机制既有，快照失效走 RULE_VERSION 比对）；黄金基线重建 diff 仅限两看板相关快照（延迟/缺陷汇总的 board、export、rule-explanation、下钻 cases）——若出现范围外 diff 一律视为回归停下排查。
5. **敏感只读**：`D:/projects/spidergitdata-dev` 老平台源码只读参考，不进构建链路；本地库密码经 docker inspect 环境变量传递，不写入文件。
