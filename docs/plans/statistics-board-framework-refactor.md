# 统计看板框架统一改造方案（问题一 + 问题二）

## 进度与中间物

- **创建时间**：2026-08-18。状态：**实施中**。
- 已完成（2026-08-21）：
  - 阶段 1 筛选内核：新增 `service/statistics/engine/` 三件套（`StatisticFieldType` / `StatisticFieldDescriptor` / `StatisticFilterEngine`），操作符语义单点化；引擎操作符矩阵测试 `StatisticFilterEngineTest`（7 项）全绿。
  - 阶段 3 首板迁移（绞杀者第一步）：`CustomerIssueDefectSummaryBoardService` 接入引擎，删除私有 matchesFilterGroup/matchesFilterCondition/valuesForFilterField/matchesSetOperator/normalizeSetOperator/equalsIgnoreCase/containsIgnoreCase 共约 150 行，替换为 `filterFields()` 字段注册表；里程碑与 bugStatus 域逻辑以 override 钩子保留原语义。
  - 阶段 0 金标安全网（首板）：`CustomerIssueDefectSummaryBoardGoldenMasterTest` —— 14 个筛选矩阵用例 + 2 个工作簿语义指纹，快照落盘 `src/test/resources/golden/customer-issue-defect-summary/`；屏蔽易变字段 generatedAt/queryDurationMs，工作簿用单元格内容指纹而非字节哈希（XLSX 内嵌时间戳不可复现）。生成→比对两连跑稳定全绿。
- 测试状态：statistics 包 43 项测试全部通过（含既有板测试无回归）；全项目编译通过。
- 当前进度（2026-08-21 三次更新）：**通用筛选族迁移已全部完成 5/5**——含最大板 SystemTestDefectSummary ✅7e0c3565（14 方法家族删除，testingPhase 经 override+phaseValueCache 闭包保留，EffectiveFilterGroup 双段分别编译 and 组合，matchesModuleDirectoryFilters 同步转引擎子组）。此前：DefectSummary ✅35fae152、DefectCause ✅308fd5e2、ResponseEfficiency ✅db4b1f46、DelayIssues ✅63f46044。ByFunction 无筛选族跳过。——DefectSummary ✅35fae152、DefectCause ✅308fd5e2、ResponseEfficiency ✅db4b1f46、DelayIssues ✅63f46044（引入 multiValueWithCondition 条件感知取值器）。范围再修正：CustomerIssueByFunctionBoardService **无通用筛选族**（仅 matchesTestingPhase 领域过滤），无需迁移；MirrorTableOverviewBoardService 有家族但行类型为 Map<String,Object> 且含数值操作符（matchesNumber eq/gt/lt 等），**迁移前置条件为引擎新增 NUMBER 字段类型**，独立小任务延后；SystemTestDefectSummaryBoardService（14 方法，最大板）**待迁移**，无完整既有单测需从零建金标夹具，严格金标先行。金标基类 AbstractStatisticBoardGoldenMasterTest 已抽取。每板闭环：金标两连跑→迁移删家族→statistics 全量回归→独立提交推送。已知坑：操作符白名单抛 IllegalArgumentException；部分板无 exportIssueRecordsWorkbook；迁移后补 java.util.Objects import；校验层丢弃空值条件并拒绝未知字段/操作符。待办：SystemTestDefectSummary 迁移、MirrorTable（前置：引擎 NUMBER 类型）、SQL↔Engine 平价契约测试、基类实时状态三兄弟模板化、清理验证、architecture.md/decisions.md 同步。
- 问题三（上帝类）已移交其他同事，本计划不覆盖。

---

## 1. 恢复线索

- **当前阶段**：方案定稿 → 阶段 0 金标测试。
- **恢复后首条命令**：`mvn test -pl backend -Dtest='Statistic*Test,SystemTest*Test,CustomerIssue*Test'`（先确认现有基线；本机工具链见 docs/plans/replace-compatibility-mode.md 第 1 节）。
- 关联计划：docs/plans/replace-compatibility-mode.md（兼容模式过渡，与本计划并行不冲突，但阶段 4 删兼容分支时若涉及 statistics 文件需协调顺序）。

## 2. 目标与边界

### 用户原始需求
对问题一（statistics 包 1.5 万行看板复制粘贴工厂）和问题二（筛选语义 SQL+内存双轨实现）做最通用的改造：不影响任何现有功能；恢复 AGENTS.md 编码规范（单一职责/单一事实源/数据驱动/可测试边界）；具备成熟扩展性与容错性；性能与解耦达标。

### 可验证的成功标准
1. **行为零变化**：改造前后所有看板的 board 响应 JSON、明细分页、Excel/CSV 导出字节级一致（金标测试强制）。
2. **操作符语义单点化**：eq/ne/contains/isEmpty/isNotEmpty/year/month/day/at/before/after/between/intersects/notIntersects/containsAll/partialContainsAny 等全部操作符在且仅在一处实现。
3. **新看板成本**：新增一个看板只需 定义+字段绑定+聚合逻辑，目标 ≤200 行，不再复制任何 matches*/export*/snapshot*/realtime* 方法。
4. **双轨对齐可证明**：SQL 下推路径与内存引擎路径在同一夹具矩阵上结果一致的契约测试存在且通过。
5. 全量 `mvn test` 与前端 Vitest 通过；statistics 包总行数显著下降（预期 -40% 以上）。

### 明确禁止
- 禁止长期双轨：每个看板迁移完成后必须同工作单元删除旧私有实现（AGENTS.md 红线），不允许新旧并存超过一个提交。
- 禁止修改任何 Controller 响应结构、URL、权限码；禁止 DB schema 变更。
- 禁止为迁移引入运行时开关（feature flag）——用金标测试而非开关保证安全。

## 3. 约束与背景

- 架构契约：统计板复用统一运行时、筛选、排序、明细和导出契约（architecture.md）；快照是性能边界，页面请求优先读快照；SQL 与内存过滤必须同一模式。
- 现有可复用资产（保留并增强，不重写）：AbstractStatisticBoardService（定义解析/明细切片/CSV）、StatisticBoardRegistry、StatisticBoardSnapshotService/RefreshService/RequestFactory、IssueFactBoardRuntimeSupport、StatisticFilterGroupSupport、StatisticMetricCalculator、StatisticRuleFlowSupport、DefectSummaryBoardSupport、SystemTestPhaseMembershipPolicy。
- 看板清单（11 个大板）：SystemTest{DefectSummary,DefectCause,HorizontalComparisonExport,IssueMultiBoard,PhaseStatistics,DelayAnalysis}、CustomerIssue{DefectSummary,DefectCause,ResponseEfficiency,DelayIssues,ByFunction} 等。

## 4. 证据与根因

### 4.1 复制粘贴实锤
- SystemTestDefectSummaryBoardService(1335行) 与 CustomerIssueDefectSummaryBoardService(1159行) 方法清单逐个对应：doLoadBoard/buildBoardResponse/refreshSnapshots/snapshotRequest/getRealtimeStatus×3/exportIssueRecordsWorkbook/exportIssueRecordsFilename/getRuleExplanation/buildRuleFlowSnapshot/toRuleFlowSample/matchesFilterGroup/matchesSetOperator/containsIgnoreCase/loadSources/toIssueSource/toDetailRecord/matchesMetric/buildDetailComparator/count/rate/percent 全部每板一份。
- 内存匹配家族重复分布：matchesFilterGroup(IssueSource) 4 个文件；matchesCondition/matchesEffectiveFilterGroup 5 个文件；matchesSetOperator/containsIgnoreCase/rate(long) 6 个文件。
- 单板私有方法多达 ~60 个（SystemTestDefectSummaryBoardService）。

### 4.2 双轨实锤
- SQL 轨：IssueFactRecordRepository(约1000行) 自有 appendEq/appendEqIgnoreCase/appendContainsIgnoreCase/appendInIgnoreCase/appendDateTo/appendTestingPhaseEquals/appendLegacyBugStatusFilter 家族。
- 内存轨：各板 matchesText/matchesDateTime/matchesAny/matchesSetOperator/parseDateTimeBoundary 家族。
- 两轨无共享代码、无对齐契约测试，仅靠架构文档一句'必须使用同一模式'人肉约束。

### 4.3 根因（为什么无法共享）
**每个看板都定义了私有的 `record IssueSource`**（字段各不相同），匹配代码以私有类型为参数，Java 类型系统上就无法抽取共用——这是复制粘贴的结构性根因。次要根因：字段键→取值器的映射用 switch 硬编码在每个板的 matchesCondition 里，而操作符语义（真正通用的部分）被粘在同一个 switch 里一起被复制。

### 4.4 已有的正确部分（保持不动）
- AbstractStatisticBoardService 已统一定义解析、高级筛选 JSON 解析、明细切片、CSV/board workbook 导出——方向正确，只是下沉不够。
- IssueFactBoardRuntimeSupport 已集中事实加载与实时状态委托——各板的 getRealtimeStatus 三兄弟只是薄包装，可直接模板化。
- StatisticBoardSnapshotService/RequestFactory 已提供快照基础设施。

## 5. 方案与步骤（核心设计）

### 5.0 设计总纲：三个抽象解决两个问题

问题一根因 = 私有数据模型 + 每板手写横切逻辑；问题二根因 = 操作符语义两处实现。对应三个新抽象：

1. **StatisticRowView（统一行视图接口）** —— 消灭私有 IssueSource record。
2. **StatisticFieldRegistry + FieldDescriptor（字段注册表）** —— 字段键→取值器+值类型的唯一映射，替代每板 switch。
3. **StatisticFilterEngine（筛选引擎）** —— 全部操作符语义的唯一实现，输入 FilterGroup+FieldRegistry，输出已编译 Predicate。

### 5.1 阶段 0：金标安全网（先行，1 个工作单元）

- 为全部 11 个板建立金标测试：固定 fixture（issue_fact 小规模确定性数据集）→ 固定筛选组合矩阵（每板 ≥10 组代表性 filters，含边界：空组/OR 逻辑/labelGroup/日期 between/集合运算符）→ 断言 board 响应 JSON、明细第一页、导出 workbook 的 MD5。
- 金标测试放 tests 同包 `golden/` 子目录，命名 test_<board>_golden_master()。
- **验收**：金标测试在未改动代码上全绿。此后每一阶段提交都必须保持全绿。

### 5.2 阶段 1：筛选内核（修问题二，2 个工作单元）

新增三个类型（放 service/statistics/engine/ 子包）：

- `public interface StatisticRowView`：统一行取值约定 —— `Object value(String fieldKey)`、`List<String> multiValue(String fieldKey)`、`LocalDateTime dateTime(String fieldKey)`。由现有 StatisticIssueFactSource / 各领域记录适配实现（适配器每域一份，替代每板一份私有 record）。
- `record StatisticFieldDescriptor(String fieldKey, FieldType type, Function<StatisticRowView,Object> accessor, boolean labelGroupCapable, Set<String> aliases)`；FieldType ∈ {TEXT, MULTI_VALUE, DATETIME, STATE, MEMBERSHIP}。MEMBERSHIP 型字段挂一个 `MembershipResolver` 插件（如 testingPhase 目录展开、bugStatus 成员表），把 SystemTestPhaseMembershipPolicy 这类领域策略作为插件注入而不是 if-else。
- `public final class StatisticFilterEngine`：
  - `Predicate<StatisticRowView> compile(StatisticFilterGroup group, Map<String,StatisticFieldDescriptor> fields)` —— 一次请求编译一次，返回组合谓词（AND/OR 逻辑、条件短路）。
  - 内部唯一实现 TextOps/SetOps/DateTimeOps/MembershipOps 四个操作符族；逐字迁移现有 matchesText/matchesDateTime/matchesAny/matchesSetOperator/parseDateTimeBoundary 语义（含 eq 忽略大小写、between 含边界、isEmpty 对 null 的判定等细节，以金标为准）。
  - 性能内建：expected 集合在 compile 期归一化一次（消灭现在每行 normalizedSet 的 O(n·m)）；日期边界 compile 期解析一次；谓词树短路求值。
- **双轨对齐契约**：新增抽象测试基类 `FilterSemanticsContractTest`，对每个注册的 FieldDescriptor 自动跑全操作符×边界值矩阵；另建 SQL↔Engine 平价测试：同一 fixture 分别走 IssueFactRecordRepository SQL 路径与 Engine 内存路径，断言同一结果集。SQL 侧后续渐进改为从 FieldDescriptor 生成列映射（本期只要求平价测试，不强改 SQL 侧，控制爆炸半径）。

### 5.3 阶段 2：看板框架模板（修问题一，2 个工作单元）

扩展 AbstractStatisticBoardService（保持类名与公共 API 不变，新增受保护模板）：

- `protected BoardRuntime runtime()`：板声明自己的 SourceLoader（函数式：filters→List<StatisticRowView>）、FieldRegistry、列规格。
- 模板化五件套（全部下沉到基类/支撑类，板不再手写）：
  1. 范围筛选：loadSources → engine.compile → filtered list（替代每板 loadBoardScopedSources+matchesFilterGroup）；
  2. 规则流快照：buildRuleFlowSnapshot/toRuleFlowSample 泛化为 StatisticRuleFlowSupport.build(scoped, final, sampler)；
  3. 实时状态三兄弟：getRealtimeStatus×2/requestRealtimeRefresh 直接基于 boardKey 委托 IssueFactBoardRuntimeSupport（基类默认实现，板零代码）；
  4. 快照刷新：refreshSnapshots 基于 StatisticBoardSnapshotRequestFactory 泛化绑定（基类默认实现）；
  5. 议题记录 Excel 导出：新增 `IssueRecordWorkbookSpec`（列定义+行映射），统一 exportIssueRecordsWorkbook/exportIssueRecordsFilename（替代每板 POI 手拼，含样式复用 ExcelExportStyles）。
- 板的最终形态：`boardKey + buildDefinition(列/叶子/指标文案) + FieldRegistry 绑定 + SourceLoader + aggregate()(领域聚合) `，目标 ≤200 行/板。DefectSummaryBoardSupport 保留为缺陷汇总族的共享聚合器。

### 5.4 阶段 3：逐板迁移（绞杀者模式，每板 1 个工作单元 ×11）

- 迁移顺序（风险从低到高）：CustomerIssueDefectSummary → CustomerIssueDefectCause → SystemTestDefectSummary → SystemTestDefectCause → ResponseEfficiency → DelayIssues → ByFunction → PhaseStatistics → DelayAnalysis → IssueMultiBoard → 其余小板。
- 每板迁移 = 同一提交内：改继承模板 + 删除该板全部私有 matches*/export*/snapshot*/realtime* 实现 + 金标测试保持全绿。**禁止新旧并存到下一个提交**。
- 每板迁移后跑：该板金标 + 该板既有单元测试 + 全量 mvn test 一次。

### 5.5 阶段 4：性能加固（1 个工作单元）

- 量化基线：迁移前记录各板冷路径（无快照）耗时于固定 fixture；迁移后对比，回归上限 ≤10%（金标之外的性能门禁）。
- 引擎级优化已在 5.2 内建（compile 一次/预归一化/短路）；如仍有热点，允许把 SourceLoader 结果按 (workspaceVersion+filterGroup hash) 做请求内 memo，但禁止引入跨请求缓存（快照已是跨请求边界）。
- 明确不做：并行流聚合、SQL 全量下推（等双轨平价测试稳定后再评估）——避免过早优化。

### 5.6 阶段 5：清理与文档（1 个工作单元）

- 删除全部死代码（各板遗留私有工具方法）；grep 验证 matchesDateTime/matchesSetOperator 等符号仅存在于 engine 包。
- 更新 architecture.md 统计板章节：写明 RowView/FieldRegistry/FilterEngine 三层契约与新看板接入步骤；decisions.md 记录本次框架决策。
- AGENTS.md 无需改（规范未变，是代码追上了规范）。

## 6. 决策记录

| 决策 | 选择 | 理由 | 否决方案 |
|---|---|---|---|
| 共享模型形态 | 接口 StatisticRowView + 各域适配器 | 零破坏接入现有 fact 记录；板间字段差异由注册表表达 | 强制统一物理 record（会改持久层，爆炸半径过大） |
| 操作符语义归属 | StatisticFilterEngine 单点实现 | 修问题二的根；金标+契约测试锁定语义 | 保留双轨加对齐测试（治标不治本，维护成本翻倍） |
| SQL 侧改造深度 | 本期只做平价测试，不改 SQL 生成 | 控制爆炸半径；SQL 轨服务于记录页分页，语义独立可用 | 本期统一 SQL 生成（收益低风险高，留待二期） |
| 安全策略 | 金标测试，不用 feature flag | 符合 AGENTS.md 禁止双轨红线；字节级行为锁定最强 | 新旧路径运行时切换（违反红线） |
| 迁移策略 | 绞杀者模式逐板迁移 | 每步可独立回退；11 个提交各自可审 | 一次性大爆炸重写（不可回退，禁止） |

## 7. 接口契约（新增公共 API 草案）

- `package ...statistics.engine:`
  - `public interface StatisticRowView { Object value(String fieldKey); List<String> multiValue(String fieldKey); LocalDateTime dateTime(String fieldKey); }`
  - `public enum StatisticFieldType { TEXT, MULTI_VALUE, DATETIME, STATE, MEMBERSHIP }`
  - `public record StatisticFieldDescriptor(String fieldKey, StatisticFieldType type, Function<StatisticRowView,Object> accessor, MembershipResolver membership, boolean labelGroupCapable) {}`
  - `public final class StatisticFilterEngine { public static Predicate<StatisticRowView> compile(StatisticFilterGroup group, Map<String,StatisticFieldDescriptor> fields); }`
  - `public interface MembershipResolver { List<String> resolveMembers(String rawValue); boolean matches(String actual, List<String> members); }`
- 基类新增受保护模板：runtime()/sourceLoader()/fieldRegistry()/issueWorkbookSpec()；getRealtimeStatus 三兄弟与 refreshSnapshots 变为基类默认实现。
- 无 Controller/URL/权限/DB 变更。

## 8. 风险与假设

- **最大风险**：操作符语义细节遗漏（如 ne 对 null 的处理、between 边界含闭、partialContainsAny 大小写）。对策：迁移前先把现网实现逐字读入引擎 + 金标矩阵必须覆盖每操作符至少一例。
- **假设**：金标 fixture 能代表生产行为差异空间；若某板有隐藏分支（如 phaseValueCache 展开目录），迁移前需在该板金标中补专项用例。
- **易错点**：EffectiveFilterGroup（默认条件+用户组分离）与纯 userGroup 两种形态；引擎 API 必须同时支持（compile 接受 defaultCondition+userGroup 双参或合并后的组）。
- **协调点**：与兼容模式过渡计划（replace-compatibility-mode.md）阶段 4 存在文件交集（statistics 包多个板含 //兼容模式-MatchMode 分支）；约定：本计划先迁板、兼容分支随板迁移一并按原样保留标注，待兼容删除阶段统一摘除，避免两个重构互相踩踏。
- **回滚**：每板独立提交，git revert 单板即可；金标测试在任何回滚后应依然成立。

## 9. SystemTestDefectSummaryBoardService 迁移配方（下一工作单元直接执行）

目标文件 `service/statistics/SystemTestDefectSummaryBoardService.java`（1335 行）。筛选家族位于 **L805-L1030**：matchesFilterGroup×2 重载、matchesEffectiveFilterGroup、matchesCondition×2 重载、matchesIssueState、matchesDateTime、parseDateTimeBoundary、matchesText、matchesPhase、legacyPhaseValues、matchesAny、matchesSetOperator、matchesPartialContainsAny。调用点：`matchesEffectiveFilterGroup(issue, effectiveFilterGroup, phaseValueCache)`（loadBoardScopedSources 内，默认条件+用户组双段）。

要点：
1. **phaseValueCache**：跨行共享缓存。做法：filterFields() 内 new LinkedHashMap 并被 testingPhase 描述符 override 闭包捕获（每请求编译一次=一次缓存），override 内调 legacyPhaseValues + SystemTestPhaseMembershipPolicy.matches(CONTAINS_MEMBER)，语义逐字保留。
2. **EffectiveFilterGroup 双段**：照抄 DefectCause 板模式——defaultCondition 与 userGroup 分别 compile 再 and()；userGroup 为 null 或空条件恒真。
3. **字段→描述符**：MODULE_FIELD/moduleNames=multiValue；projectName/title/severityLevel/priorityLevel/authorName/assigneeName=multiValue 单值包装；labels=multiValue；state=multiValue(isClosed?closed:open)；createdAt/updatedAt=dateTime(accessor)；testingPhase=multiValueWithOverride(阶段成员语义)；bugStatus/delayCause=multiValueWithOverride(IssueStatusMembers/IssueDelayCauseMembers labelGroup/plain 分支)。
4. **金标**：无完整既有单测，需从零建夹具：mock phaseCatalogService.listParentNames、phaseScopeResolver.resolvePhases、runtimeSupport.loadFacts（StatisticIssueFactSource 构造行）、issueFactRecordRepository.findForFilterOptions；矩阵 ≥12 用例覆盖 text/set/datetime/phase 全族；继承 AbstractStatisticBoardGoldenMasterTest。
5. 完成后：全量 statistics 回归 → 独立提交 `refactor(statistics): 迁移系统测试缺陷汇总看板到统一筛选引擎` → 推送。
