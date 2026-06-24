<!-- DOC_STATUS_START -->
> 文档状态：当前修复方案 / 待实施
> 说明：2026-06-23 内网测试发现的 8 个问题，逐条给出根因、改动落点（file:line）、可落地的分步方案与验收点。供后续 AI 员工照此稳步推进，不做模糊描述。
<!-- DOC_STATUS_END -->

# 内网测试问题修复方案（2026-06-23）

来源：2026-06-23 内网真机测试记录（系统测试缺陷汇总等页面）。共 8 个问题，混合了数据口径 bug、性能、功能增强与 UI 优化。

约定：
- 每条按「现象 → 根因（带 file:line 证据）→ 分步方案 → 验收」组织。
- 涉及事实字段口径的改动，必须遵守 `AGENTS.md` §0.0 重构红线与 `docs/platform-page-business-rules.md`。
- 标签组相关改动以 `docs/plans/2026-06-10-label-group-value-set-design.md`（含 2026-06-23 修订）为准。
- 凡改 `IssueLabelRules` / `FactBuildService` 等事实层逻辑，改完必须**重建 fact**，否则 `issue_fact` 旧数据不会自动更新。

## 问题清单与优先级

| 编号 | 问题 | 类型 | 优先级 |
|---|---|---|---|
| 1 | 回退判定 token 过松（标题含单字"退"即判回退） | 数据口径 bug | P0 |
| 2 | 排序/刷新后整卡片白屏卡顿 | 性能 bug | P0 |
| 5 | 统计表格条件筛选字段不全 | 功能增强 | P1 |
| 7 | 测试阶段选择框缺二级选项 | 功能增强 | P1 |
| 3 | 动态标签组成员来源缺镜像表 | 功能增强 | P2 |
| 4 | 组合标签组 SAME_FIELD 未约束子组 | 标签组 bug | P2 |
| 6 | 下钻明细表 UI（表头/排序/滚动条/标签风格） | UI 优化 | P2 |
| 8 | 议题测试阶段定义页 UI 优化 | UI 优化 | P3 |

---

## 问题 1：回退判定 token 与老平台不一致（P0）

### 现象
"系统测试缺陷汇总"回退列数字偏大。需求口径："一级缺陷且标题含（倒退）则为回退"。

### 根因（已确认）
两边都是「一级缺陷 + 标题含关键词」判回退，差别在第三个关键词：

- 老平台：`severity_level='一级缺陷'` 且标题 LIKE `（退` / `回退` / `倒退` 之一。
  证据：[SpiderIssueDataDAOImpl.java:79-85](D:/projects/spidergitdata-dev/src/main/java/com/huayun/service/impl/SpiderIssueDataDAOImpl.java#L79)（`:691-696` 重复一次）。
- 新平台：第三个 token 是光秃秃的 `退`，导致 `退出`/`撤退`/`退化`/`衰退` 等被误判为回退。
  证据：[IssueClassificationRules.java:30](backend/src/main/java/com/data/collection/platform/service/IssueClassificationRules.java#L30) `REGRESSION_TITLE_TOKENS = List.of("回退", "倒退", "退")`。

判定结果在 fact build 时固化进 `issue_fact.is_regression`（[FactBuildService.java:498](backend/src/main/java/com/data/collection/platform/service/FactBuildService.java#L498)）和 `category`（[:484](backend/src/main/java/com/data/collection/platform/service/FactBuildService.java#L484)）。缺陷汇总的"回退(个)"列 `level1_back` 读的是 `is_regression` 布尔（[SystemTestDefectSummaryBoardService.java:113-117](backend/src/main/java/com/data/collection/platform/service/statistics/SystemTestDefectSummaryBoardService.java#L113)）。

### 不能用标签组（问题5）解决的原因
回退是**事实层固化的分类字段**（决定议题归到哪一列，是聚合维度），标签组是**查询期行筛选器**，层级不同。且回退口径必须在看板/下钻/导出三处一致且与老平台对齐，不能做成用户可改。

### 分步方案
1. 改 [IssueClassificationRules.java:30](backend/src/main/java/com/data/collection/platform/service/IssueClassificationRules.java#L30)：把 token 列表由 `"回退", "倒退", "退"` 改为 `"回退", "倒退", "（退"`（与老平台对齐，去掉单字 `退`）。
2. 检查 `containsToken`（IssueRuleSupport）是否对全角左括号 `（` 正常匹配（老平台用的就是全角 `（退`）；确认 trim/lowercase 不会破坏中文括号。
3. `mvn -DskipTests compile` 验证编译。
4. 触发 fact 全量重建（见文末「重建 fact 操作」），让 `is_regression`/`category` 用新口径重算。

### 验收
- 标题为 `用户退出崩溃`、`性能衰退` 的一级议题不再计入回退。
- 标题为 `（倒退）启动失败`、`xx回退` 的一级议题仍计入回退。
- 同一阶段下"回退(个)"列合计与老平台一致（或差异可由其他已知因素解释）。

---

## 问题 2：排序/刷新后整卡片白屏卡顿（P0）

### 现象
即使关闭自动刷新，点表头排序或翻页后，整块卡片白屏凝滞一段时间（白屏期间能看到排序其实已完成）。

### 根因（已确认，主因+次因）
**主因**：排序/翻页是纯前端操作，却被错误地触发了"整卡片 `v-loading` 遮罩 + 一次完整后端看板重拉"。

- 排序本地完成：[useStatisticBoardTableState.ts:29-35](frontend/src/composables/useStatisticBoardTableState.ts#L29) `sortedRows` computed 立即排好（所以"数据已变"）。
- 但 [useStatisticBoardSortControls.ts:49-60](frontend/src/composables/useStatisticBoardSortControls.ts#L49) 排序后把 `sortBy/sortOrder` 写进路由 query；翻页同理 [useStatisticRoutePagination.ts:75-87](frontend/src/composables/useStatisticRoutePagination.ts#L75)。
- [StatisticBoardView.vue:524-540](frontend/src/components/StatisticBoardView.vue#L524) 有 `watch(() => route.query, ..., {deep:true})`，任何 query 变化都触发 `refreshStatisticBoardRouteState`。
- [useStatisticBoardRouteRefresh.ts:9-19](frontend/src/composables/useStatisticBoardRouteRefresh.ts#L9)：`setLoading(true)` → `await loadBoard(false)`（完整后端往返）→ `loadRealtimeStatus` → `syncDetailFromRoute`，三个 await 串行，全程 loading=true。
- [StatisticBoardView.vue:587](frontend/src/components/StatisticBoardView.vue#L587) `<el-card v-loading="loading">` 把整卡片套进半透明白色遮罩 = "白屏"来源。

**次因（叠加延长卡顿）**：数据返回后 [BaseStatisticTable.vue:85](frontend/src/components/base/BaseStatisticTable.vue#L85) 的普通 `el-table`（固定列 + 多级表头 + border，无虚拟滚动）做同步重排，构成主线程长任务。

**已排除**：ECharts（复用实例 `setOption`，且缺陷汇总表页无图表）、路由全局 loading（仅 path 变化触发，排序只变 query）。

### 分步方案
分两步，先治主因（收益最大、风险最低），次因按需再做。

**第一步（主因，必做）**：让纯前端的排序/翻页不再触发后端重拉。
1. 在 [StatisticBoardView.vue:524-540](frontend/src/components/StatisticBoardView.vue#L524) 的 query watcher 里，区分"需要重拉后端的 query key"（如 filterGroup、testingPhase、项目范围）与"纯前端展示 key"（`sortBy`、`sortOrder`、`page`、`pageSize`）。当本次 query 变化只涉及纯前端 key 时，直接 `return`，不调 `refreshStatisticBoardRouteState`，不 setLoading。
   - 实现建议：比较新旧 query，剔除前端 key 后若无差异则跳过。前端 key 清单可复用/对齐 `statistic-board-route-query.ts` 里已有的 key 定义。
2. 验证排序/翻页时 Network 面板不再出现多余的 `getStatisticBoard` 请求，`v-loading` 不再触发。

**第二步（次因，可选，视第一步后体感决定）**：降低 el-table 重排开销。
1. 若第一步后仍有可感卡顿：给排序/翻页操作避免整卡片遮罩——把 `v-loading` 从 `el-card` 收窄到只包数据区，或排序/翻页时不触发 loading。
2. 行数大时（接近 200）可评估 `el-table-v2` 虚拟滚动，但改造成本高，非必要不做。

### 验收
- 关闭自动刷新后，点表头排序：无网络请求、无白色遮罩、排序结果即时呈现。
- 翻页、改每页条数：同样不触发后端重拉与遮罩。
- 改筛选条件/测试阶段：仍正常触发后端重拉（不能误伤真正需要重拉的场景）。

---

## 问题 5：统计表格条件筛选字段不全（P1）

### 现象
"系统测试缺陷汇总"筛选字段选择框只有少数几个字段，无法按下钻表字段（标题、处理人等）或其他有业务含义的字段筛选。

### 口径（已与业务方确认）
筛选字段框 = 三类并集：(1) 下钻明细表的列；(2) 汇总表头中的**计数/布尔/枚举**列（映射到背后行级字段，如 回退→is_regression）；(3) `issue_fact` 中有业务含义但两表都没显示的隐藏字段。**排除**汇总表头中的**比率/聚合**列（修复率、占比、遗留率等组级聚合，无法行级筛选）。当前阶段目标：把最大可取的行级字段都纳入，比率类排除；不够用再议。

### 根因 / 现状（已确认）
- 当前筛选字段仅 5 个，定义在 [SystemTestDefectSummaryBoardService.java:95-111](backend/src/main/java/com/data/collection/platform/service/statistics/SystemTestDefectSummaryBoardService.java#L95)：`projectName`、`testingPhase`、`moduleName`、`severityLevel`、`priorityLevel`。
- 匹配逻辑 switch 在 [:539-547](backend/src/main/java/com/data/collection/platform/service/statistics/SystemTestDefectSummaryBoardService.java#L539)（labelGroup 分支 :531-537），其余 key `default -> true`。
- 下钻明细列 13 个，定义在 [:164-177](backend/src/main/java/com/data/collection/platform/service/statistics/SystemTestDefectSummaryBoardService.java#L164)。
- 筛选字段由后端 `buildDefinition` 下发（与议题查询页"前端 TS 构造字段"机制不同，补齐要走后端路线）。

### 分步方案（分两档，按成本递增）

**档 A（低成本，优先做）——补齐"明细已有且 IssueSource 已有"的字段：**
这些字段已存在于内部 `IssueSource` record（[:898](backend/src/main/java/com/data/collection/platform/service/statistics/SystemTestDefectSummaryBoardService.java#L898) 及 `toIssueSource` :650-682），无需改数据源：
`title`、`bugStatus`(测试状态)、`delayCause`(延期原因)、`authorName`(创建人)、`assigneeName`(处理人)、`state`(开/关闭)、`createdAt`、`updatedAt`、`labels`。
1. 在 buildDefinition 筛选字段列表（:95-111）为每个字段加一行工厂调用：
   - 文本类（title）用 `StatisticFilterFieldFactory.text(...)` 或 `textLabelGroup(...)`。
   - 枚举/有限值（bugStatus、state、severity 类）用 `select(...)`。
   - 时间类（createdAt、updatedAt）用 `datetime(...)`。
   - 人员/模块/标签等字符串字段，按 2026-06-23 标签组方案可用 `textLabelGroup` 让其支持标签组值。
2. 在 `matchesCondition` switch（:539-547）为每个新 key 加 case，从 `IssueSource` 取对应值比对。
3. select 类字段的候选值：参照议题查询页 [SystemTestIssueSearchService.getFilterOptions](backend/src/main/java/com/data/collection/platform/service/SystemTestIssueSearchService.java#L281) 的 `toOptions/toLegacyOptions/toSeverityOptions` 去重生成；缺陷汇总当前无此装配，需在 buildDefinition 基于已加载 sources 计算 distinct 注入 select options（或新增 options 接口）。

**档 B（高成本，按需做）——补齐"隐藏但有业务含义"的字段：**
`milestoneTitle`(里程碑)、`functionName`(功能名)、`category`(分类)、`issueType`、`reasonCategory`、`closedAtSource` 等，存在于 [IssueFact.java](backend/src/main/java/com/data/collection/platform/entity/IssueFact.java) 但不在 `IssueSource`。
1. 扩展 `IssueSource` record（:898）加字段。
2. 扩展 `toIssueSource`（:650-682）映射。
3. 扩展上游 `StatisticIssueFactSource` 的 SQL/取数。
4. 同档 A 加 buildDefinition 字段 + switch case + 候选值。

**汇总表头计数列映射（计入档 A/B）：**
| 汇总列 | 映射字段 | 是否可筛 |
|---|---|---|
| 回退 level1_back | is_regression | ✅ 布尔 |
| 挂机 level1_hang | is_crash | ✅ 布尔 |
| 其他一级 level1_other | is_level1_other | ✅ 布尔 |
| 一/二/三级 total | severity_level | ✅ 已可筛 |
| 申请延期 / 复测未通过 / 已修复 / 新发 | bug_status 派生 | ✅ 枚举 |
| 延期来源 | delay_issue | ✅ 布尔 |
| **所有 *_rate / 占比 / 遗留率** | 组级聚合 | ❌ 排除 |

程序化排除依据：buildDefinition 中 metricType=="ratio" 的列一律不纳入筛选字段。

**明确不可直接做的**：需求曾提"合并人""目标分支"——这两个字段在 `issue_fact` 实体中**不存在**，需先从 `raw_payload` 解析或扩展 ODS/事实表，属于先补数据模型的前置项，本次不纳入。

### 验收
- 缺陷汇总筛选框新增档 A 的 9 个字段，可按标题/处理人/创建人/测试状态/时间等筛选，结果与下钻一致。
- 比率类列（修复率等）不出现在筛选字段框。
- 筛选→看板→下钻→导出口径一致。

---

## 问题 3：动态标签组成员来源只有有限几个表（P2）

### 现象
动态标签组的"成员来源"下拉只有评审记录、评审问题项、议题事实、代码走查 MR 4 个，无法选 GitLab 镜像表（`ods_gitlab_*`）作为来源。

### 根因（已确认）
数据源是**硬编码**在后端目录类，只登记了 4 个事实表，没暴露任何镜像表。前端下拉纯透传后端目录。
- 目录构建 [LabelGroupDynamicRuleCatalogService.buildSources()](backend/src/main/java/com/data/collection/platform/service/labelgroup/LabelGroupDynamicRuleCatalogService.java#L145)：只 `put` 了 `review_records` / `review_problem_items` / `issue_fact` / `merge_request_fact` 四个源（:147-228）。
- 候选值查询 [LabelGroupDynamicRuleCandidateService.distinctCandidates](backend/src/main/java/com/data/collection/platform/service/labelgroup/LabelGroupDynamicRuleCandidateService.java#L63) 与规则求值都是**数据驱动**的，直接用 `source.tableName()` 拼 SQL，所以新增源不需要改查询逻辑。
- 前端下拉来自 `GET /api/label-groups/dynamic-rule-sources`（[label-groups-api.ts:50](frontend/src/api-client/label-groups-api.ts#L50)），[LabelGroupSettingsView.vue:174](frontend/src/views/LabelGroupSettingsView.vue#L174) 注入，**纯透传，无需改前端**。

### 分步方案
1. 在 [buildSources()](backend/src/main/java/com/data/collection/platform/service/labelgroup/LabelGroupDynamicRuleCatalogService.java#L145) 新增需要暴露的镜像表，例如：
   ```java
   put(sources, source("ods_gitlab_issues", "GitLab议题镜像", "...", "ods_gitlab_issues",
       List.of(field(...), candidateField(...), ...)));
   ```
   字段用镜像表真实列名（GitLab 原始 snake_case，如 `iid`、`author_id`、`state`），`valueType` 必须匹配真实列类型。
2. 用 `field`（无候选）、`candidateField`（运行时 distinct）、`staticField`（固定枚举）三种工厂登记字段，按需标记 `outputSupported/filterSupported/groupSupported/aggregateSupported`。
3. 若要跨源 JOIN，在 [buildRelations()](backend/src/main/java/com/data/collection/platform/service/labelgroup/LabelGroupDynamicRuleCatalogService.java#L232) 加逻辑关联（非外键）。
4. 前端无需改动，新源会自动出现在下拉。

**注意**：先确认哪些镜像表/列确实需要暴露给业务用户（不要把全部 `ods_gitlab_*` 一股脑放出，按需登记）；镜像表列语义与 fact 表不同，登记前核对列名与类型。

### 验收
- 动态标签组"成员来源"下拉出现新登记的镜像表。
- 选镜像表 + 输出字段后，预览能正确 distinct 出候选成员值。

---

## 问题 4：组合标签组 SAME_FIELD 约束未传导到子组（P2）

### 现象
组合标签组适用范围选"仅同来源字段可用"（SAME_FIELD）时，来源字段不能约束子标签组——子组下拉依旧显示全部。

### 根因（已确认，前后端三处缺口）
父组的 SAME_FIELD/sourceFieldKey 约束在**后端展开递归、后端保存校验、前端子组下拉**三处都没有下探/传导到子组。
- **后端展开**：[LabelGroupExpansionService.expand](backend/src/main/java/com/data/collection/platform/service/labelgroup/LabelGroupExpansionService.java#L18) 只对顶层组调一次 `validateApplicableScope`（:28），不下探子组；[LabelGroupService.expandValues](backend/src/main/java/com/data/collection/platform/service/labelgroup/LabelGroupService.java#L188) 递归子组时只校验 `valueType`（:201-205），从不校验 `applicableScope`/`sourceFieldKey`。
- **后端保存**：[validateGroupShape](backend/src/main/java/com/data/collection/platform/service/labelgroup/LabelGroupService.java#L261) 对子组只校验 valueType、嵌套规则、循环、总数，无"子组 sourceFieldKey 与父组一致"的检查。
- **前端子组下拉**：[LabelGroupSettingsView.vue childGroupOptions:101-109](frontend/src/views/LabelGroupSettingsView.vue#L101) 只按 id/enabled/groupType/valueType 过滤，**没有按 sourceFieldKey 过滤**；且 `form.sourceFieldKey` 变化无 watch 联动清理已选子组。

### 设计前置决策（需先确认语义）
SAME_FIELD 父组对子组到底应是哪种约束？两种可选，必须先定：
- **方案甲（推荐，约束传导）**：父组 SAME_FIELD + sourceFieldKey=X 时，只允许引用 sourceFieldKey 也=X（或 SAME_TYPE 且能用于 X）的子组。子组下拉据此过滤，保存时校验，展开时一致。语义最直观。
- **方案乙（仅父组生效）**：维持子组各自 scope 独立，父组 SAME_FIELD 只在父组直接挂到字段时拦自己。但这与"来源字段约束子标签组"的需求不符，等于不修。

按需求文字应取**方案甲**。下述步骤按方案甲。

### 分步方案
**前端**：
1. [childGroupOptions:101-109](frontend/src/views/LabelGroupSettingsView.vue#L101) 增加过滤：当 `form.applicableScope==='SAME_FIELD'` 时，只保留 `group.sourceFieldKey===form.sourceFieldKey`（或 group 为 SAME_TYPE 且值类型兼容）的子组。
2. 补一个 `watch(() => form.sourceFieldKey, ...)`，参照现有 valueType 清理逻辑（[:142-150](frontend/src/views/LabelGroupSettingsView.vue#L142)），sourceFieldKey 变化时清掉不再匹配的已选子组。

**后端**：
3. [validateGroupShape](backend/src/main/java/com/data/collection/platform/service/labelgroup/LabelGroupService.java#L261)：保存 COMPOSITE 且 SAME_FIELD 时，校验每个子组的 sourceFieldKey 与父组一致（或子组为 SAME_TYPE），不一致则拒绝保存并提示具体子组。
4. [expandValues 递归](backend/src/main/java/com/data/collection/platform/service/labelgroup/LabelGroupService.java#L188) / [LabelGroupExpansionService.validateApplicableScope](backend/src/main/java/com/data/collection/platform/service/labelgroup/LabelGroupExpansionService.java#L54)：展开时把父组 SAME_FIELD 约束一并校验（与保存校验同口径，防止历史数据绕过）。

### 验收
- 父组设 SAME_FIELD + 模块字段时，子组下拉只列出来源字段为模块（或 SAME_TYPE 兼容）的组。
- 改父组来源字段后，已选的不匹配子组被自动清除。
- 保存不一致的组合组时被拒绝并提示。
- 展开结果不再包含越界子组成员。

---

## 问题 6：下钻明细表 UI 结构（P2）

### 现象
下钻明细表（点统计单元格弹出）：表头文字没上下居中、排序点击热区太小、横向滚动条拖拽热区太小难选中；标签/测试状态字段是纯文字，应做成 GitLab V16 标签风格。

### 根因（已确认）
组件是 [StatisticBoardDetailDialog.vue](frontend/src/components/StatisticBoardDetailDialog.vue)（裸 `el-table` + `v-for el-table-column`，:64-96）。
- **表头不居中**：全局样式 [styles.css:2177-2180](frontend/src/styles.css#L2177) 表头 `th` 用非对称 padding `0 0 4px`，文字偏上；body 单元格是对称 `10px 0`。
- **排序热区小**：[:80](frontend/src/components/StatisticBoardDetailDialog.vue#L80) 用 `:sortable="'custom'"`，Element Plus 只把右侧那对小三角（~14px）作为热区，表头文字不可点；项目无 `.caret-wrapper` 放大样式。
- **横向滚动条热区小**：弹窗用 el-table 默认横向条（~6px 热区）。项目已有成熟"浮动横向滚动条"方案在 [MirrorSyncLogTable.vue](frontend/src/views/MirrorSyncLogTable.vue)（逻辑 :93-189，样式 :368-410），但耦合在该组件内、非独立 composable。
- **标签纯文本**：弹窗所有列统一渲染为链接或纯文本（[:83-94](frontend/src/components/StatisticBoardDetailDialog.vue#L83)），`detailCellValue` 只产 `string` 或 `{label,href}`，无 tag 类型。范式参照 [BaseRecordTableCell.vue:14-37](frontend/src/components/base/BaseRecordTableCell.vue#L14)（按 `column.type==='tags'` 渲染 `el-tag`）。

### 分步方案
1. **表头居中**（最简，先做）：在 `StatisticBoardDetailDialog.vue` 用 `:deep()` 给表头 th 加 `vertical-align: middle` + 对称 padding，或给该弹窗的 el-table 传 `header-cell-style`。避免直接改全局 styles.css:2177（影响面大）。
2. **排序热区**：加 `:deep(.el-table .caret-wrapper)` 扩大点击区，或自定义表头让整个 header cell 可点触发排序。
3. **横向滚动条**：把 MirrorSyncLogTable 的浮动横向滚动条逻辑抽成可复用 composable（如 `useFloatingHorizontalScrollbar`），再接入本弹窗。**这一项工作量最大，建议单独排期**，先做 1、2。
4. **标签 V16 风格**：扩展 `StatisticDetailColumn` 类型增加 `type`（如 `'tags'`/`'status'`），后端 `detailColumns` 配合标记标签/测试状态列；前端弹窗按 `column.type` 加 `el-tag` 分支，参照 BaseRecordTableCell 与 MirrorSyncLogTable 的颜色映射。

### 验收
- 表头文字垂直居中。
- 点击表头文字区域即可排序（热区明显变大）。
- 横向滚动条可见、易拖拽（与镜像同步日志表一致体验）。
- 标签/测试状态列渲染为彩色 tag。

### 风险
- 第 1 步若改全局 styles.css 会影响所有 el-table，务必走弹窗局部 `:deep()`。
- 第 3 步抽 composable 要回归 MirrorSyncLogTable 自身，避免改坏既有页面。

---

## 问题 7：测试阶段选择框缺二级选项（P1，后端零改动）

### 现象
缺陷汇总顶部测试阶段选择框只有父级（如 CC2026R3），无法展开选第一轮/第二轮…/回归测试等子级。

### 根因（已确认）
后端已返回带 children 的数据，前端**把已到手的 children 丢弃了，只用父级**。
- 主入口是顶部 DataScopeBar，provider [SYSTEM_TEST_DEFECT_SUMMARY_SCOPE_PROVIDER](frontend/src/composables/statistic-board-data-scopes.ts#L30) 是 `mode:'single-select'`，选项来自 `buildParentOptions`（[:125-133](frontend/src/composables/statistic-board-data-scopes.ts#L125)），只 map `group.name`，丢弃 `children`。
- 后端 `getTestingPhaseGroups` 返回的 `TestingPhaseGroupResponse` 已带 `children`（[types/api/system-settings.ts:46](frontend/src/types/api/system-settings.ts#L46)），数据已在前端手里。
- 组件 [DataScopeBar.vue:105-115](frontend/src/components/data-scope/DataScopeBar.vue#L105) 已内置 `tree-single` 模式（`el-cascader` + `checkStrictly` + `emitPath:false`），父级可单选、子级可单选。
- 后端 resolver [SystemTestPhaseScopeResolver.resolvePhases](backend/src/main/java/com/data/collection/platform/service/SystemTestPhaseScopeResolver.java#L29) 已能正确处理：选父级→展开全部启用子级；选子级→精确返回该子级。缺陷汇总 `matchesPhase` 与 `moduleRowSources` 空值保护对子级值都安全。

### 分步方案（纯前端，改 [statistic-board-data-scopes.ts](frontend/src/composables/statistic-board-data-scopes.ts) 两处）
1. 把缺陷汇总 provider 的 `mode` 从 `'single-select'` 改为 `'tree-single'`（[:31-40](frontend/src/composables/statistic-board-data-scopes.ts#L31)）。
2. 新增 `buildTreeOptions(groups)`：父节点 = `group.name`，子节点 = `group.children[].testingPhase`；在缺陷汇总配置分支（[:91-95](frontend/src/composables/statistic-board-data-scopes.ts#L91)）改用它替代 `buildParentOptions`。

**后端无需任何改动**（resolver 与匹配逻辑已支持子级）。筛选面板里那个 select 字段若也要二级是更高成本项，主诉求在顶部范围条，先改 DataScopeBar 即可。

### 验收
- 缺陷汇总顶部测试阶段下拉，CC2026R3 可展开出第一轮~第六轮 + 回归测试。
- 选父级：统计为该父级全部启用子级合计。
- 选某子级（如第一轮）：统计只含该子级（呼应 [testing-phase-display-statistics-whitelist-20260623.md](testing-phase-display-statistics-whitelist-20260623.md) 的口径）。

---

## 问题 8：议题测试阶段定义页 UI 优化（P3）

### 现象
[TestingPhaseDefinitionView.vue](frontend/src/views/TestingPhaseDefinitionView.vue)（813 行）UI 粗糙，需优化。

### 根因（已确认的具体粗糙点）
- 左栏父级是自定义 `div.phase-list-row`（grid 五列硬排，[:692-694](frontend/src/views/TestingPhaseDefinitionView.vue#L692)），右栏子级是 `el-table`，两栏风格割裂。
- 排序用上/下箭头逐格交换（[moveGroup/moveChild:333-350](frontend/src/views/TestingPhaseDefinitionView.vue#L333)），每次触发两条 PUT + 全量 `loadGroups()` 刷新，长列表体验差。
- 窄屏（<760px）父级操作组换行（[:800-807](frontend/src/views/TestingPhaseDefinitionView.vue#L800)），交互断裂。
- 启停 switch 文字（启/停）与右栏 header 的 `el-tag`（启用/停用）状态表达不一致。
- 右栏子表同样吃全局表头非对称 padding（与问题 6 同根）。
- 工具栏硬编码宽度（220/130/280px），中等屏换行不齐。

### 分步方案（UI 优化，建议最后做）
1. 表头居中：与问题 6 一并处理（同根因 styles.css:2177，走局部 `:deep()`）。
2. 状态表达统一：父子级启停统一用一种范式（建议都用 switch，去掉与 tag 的不一致）。
3. 工具栏与栏内响应式：用弹性布局替代硬编码宽度，统一断点。
4. （可选，较大）排序体验：箭头逐格交换可改为拖拽排序，或至少把"两条 PUT + 全量刷新"改为单次批量更新 + 局部刷新。
5. （可选）左右栏风格统一：评估是否把左栏父级列表也改为与右栏一致的表格范式。

### 验收
- 表头居中、状态表达统一、窄屏不断裂。
- 排序操作不再每格触发全量刷新（若做第 4 步）。

### 风险
- 该页 813 行、逻辑集中，改动面大，建议拆成多个小 PR，优先 1-3（低风险样式/响应式），4-5 单独评估。

---

## 重建 fact 操作（#1 改完必做）

`is_regression` / `category` 是 fact 构建时固化进 `issue_fact` 的列，改 `IssueClassificationRules` 后旧行不会自动更新，必须触发重建。

现有接口（无需新增）：[FactBuildController.java](backend/src/main/java/com/data/collection/platform/controller/FactBuildController.java) `POST /api/facts/rebuild`，`@RequireRole(ADMIN)`：

- 全量重建议题事实：`POST /api/facts/rebuild?scope=issue&full=true`
- 全量重建全部事实：`POST /api/facts/rebuild?scope=all&full=true`
- 查询重建任务状态：`GET /api/facts/build-tasks/latest?scope=issue`

内网执行步骤：
1. 用 admin 账号登录，按 AGENTS.md §1.1 处理 CSRF（CSRF 开启时带 `XSRF-TOKEN` Cookie + `X-XSRF-TOKEN` 头；关闭时复用登录 `JSESSIONID`）。
2. `POST /api/facts/rebuild?scope=issue&full=true`。
3. 轮询 `GET /api/facts/build-tasks/latest?scope=issue` 直到完成。
4. 用问题 1「验证」里的 SQL 抽查 `is_regression` 数量是否回落、误判（标题仅含"退出/撤退"）是否消除。

注意：这是**受控的数据刷新**，只重算 `issue_fact`，不动 `ods_gitlab_*` 镜像表、不重建数据卷、不清同步状态，符合 intranet-offline-packaging-standard.md 的增量更新约束。

## 实施优先级建议

| 优先级 | 问题 | 理由 | 预估改动面 |
|---|---|---|---|
| P0 | #1 回退 token | 产出错误统计数字，违背老平台口径 | 1 行 + 重建 fact |
| P1 | #2 白屏卡顿 | 高频操作体验阻塞 | 前端 watcher/loading 逻辑 |
| P1 | #7 二级阶段 | 核心筛选能力缺失，后端零改动 | 纯前端 2 处 |
| P2 | #5 筛选字段 | 分档：低成本字段先补 | 后端 buildDefinition + switch |
| P2 | #3 动态源 | 后端一处 buildSources | 后端 1 方法 |
| P2 | #4 SAME_FIELD 传导 | 前后端三处，需先定语义 | 前端 2 + 后端 2 |
| P2 | #6 下钻 UI | 表头/排序先做，浮动滚动条单独排 | 前端样式 + composable 抽取 |
| P3 | #8 阶段定义页 UI | 纯体验优化，改动面大 | 前端，拆多 PR |

## 通用注意事项
1. **凡涉及 fact 字段判定的改动（#1）必须重建 fact**，旧数据不会自动更新。
2. **比率/聚合列（修复率、占比、遗留率，metricType=="ratio"）永不进行级筛选**（#5），这是硬约束。
3. **"合并人""目标分支"在 `issue_fact` 实体中不存在**，#5 不能直接加这两个字段，需先补数据模型，单独立项。
4. 改动后按 AGENTS.md §0.1 测试策略，仅做最小必要验证（后端 `mvn -DskipTests compile`，前端 `npm.cmd run typecheck`），不擅自跑全套。
5. 全部为内网增量更新，遵循 [intranet-offline-packaging-standard.md](../intranet-offline-packaging-standard.md)，不重建数据卷、不清同步状态（除非 #1 重建 fact 是受控的数据刷新而非环境重建）。
