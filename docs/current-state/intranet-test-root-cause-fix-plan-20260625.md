<!-- DOC_STATUS_START -->
> 文档状态：当前修复方案 / 待实施与真实环境验证
> 说明：合并 2026-06-25 两份内网测试根因分析方案，作为后续修复系统测试、客户问题、代码走查和评审下拉问题的统一入口。若本文件与 `docs/platform-page-business-rules.md` 冲突，以业务规则总表为准，并先更新业务规则总表再改代码。
<!-- DOC_STATUS_END -->

# 内网测试根因分析与统一修复方案（2026-06-25）

## 范围

本文件合并以下两份方案：

1. `内网测试问题根因分析与解决方案`：13 个问题，覆盖统计差异、性能超时、排序、评审下拉和代码走查项目范围。
2. `客户问题模块新增问题分析`：5 个问题，覆盖客户问题缺陷汇总、非法数据、缺陷原因、延期问题和筛选后 0 行展示。

合并后共 18 个问题：

| 优先级 | 数量 | 问题编号 |
|---|---:|---|
| P0 Critical | 9 | 2、7、9、10、12、14、15、16、17 |
| P1 High | 7 | 1、4、6、8、11、13、18 |
| P2 Medium | 2 | 3、5 |

## 总原则

1. 新平台是老平台业务口径重构，不重新设计统计口径。数据集合、字段含义、统计数量、导出和下钻优先对齐老平台源码和 `docs/platform-page-business-rules.md`。
2. 不做页面局部补丁式兼容。共性问题必须沉淀到事实层、规则层、共享查询层、共享统计组件或平台级样式。
3. 客户问题模块使用 `milestone_title` 作为主分类和顶部“测试阶段”切换匹配来源，同时兼容父级阶段展开后的 `testing_phase`。不能把客户问题页面硬套系统测试 `testing_phase`。
4. 系统测试模块继续使用测试阶段定义表展开后的 `testing_phase`，并套用系统测试公共排除规则。
5. 性能修复优先做 SQL 预过滤、索引和共享查询能力；只有真实慢 SQL 仍无法满足时，再进入 SQL 聚合、汇总表或物化视图设计。
6. 涉及事实字段派生、排除规则、历史遗留、已修复、模块、严重程度等口径变更时，必须重建 fact 后才能验收。

## 共性根因

### A. 客户问题把里程碑当测试阶段使用不完整

客户问题真实分类维度是 `milestone_title`。当前若页面或服务继续用 `primaryPhaseLabel()` / `testing_phase` 作为唯一筛选条件，会导致：

- 客户问题缺陷汇总为空。
- 非法数据默认不选有数据，选择任意“测试阶段”后为空。
- 缺陷原因分析没有模块或加载慢。
- 延期问题只有“未设定模块”。
- 缺陷响应效率、按功能展示缺陷数量为空。

统一修复方向：

- 新增或收敛 `CustomerIssueSqlPredicateSupport`，统一提供客户问题基础 WHERE、里程碑筛选和参数构建。
- 客户问题看板统一用 `milestone_title` 作为主范围字段，必要时兼容父级阶段展开后的 `testing_phase`。
- 客户问题默认基础范围：`project_id = 325`、`deleted = false`、`created_at >= 2026-01-01`、`milestone_title` 非空，并套用客户问题公共排除规则。
- 页面文案仍可显示“测试阶段”，但后端语义必须映射到客户问题里程碑口径。

### B. 系统测试统计范围和老平台排除规则仍需逐项验证

系统测试议题查询、缺陷汇总、非法数据、延期分析、缺陷原因、议题阶段统计需要共享同一套事实层范围和排除规则。当前差异集中在：

- 系统测试范围过宽，议题查询比老平台多约 1W。
- 非法数据比老平台多很多。
- 单条或少量议题因模块、严重程度、回退/挂机/其他规则差异造成统计偏差。

统一修复方向：

- 先用真实 SQL 找出差异议题集合，再定位是范围、排除、事实字段还是模块归属问题。
- 不能为了单条议题从标题、裸标签或括号说明中反推模块；如需改变规则，先更新业务规则总表。

### C. 慢页需要把阶段/里程碑筛选下推到 SQL

申请延期缺陷分析、议题阶段统计、缺陷原因分析等页面超时，根因倾向于全量读取 `issue_fact` 后在 Java 内存中过滤。

统一修复方向：

- 统计查询解析出测试阶段或里程碑后，在 SQL 层增加 `testing_phase = ANY(?)` 或 `milestone_title = ANY(?)` 预过滤。
- 增加 `issue_fact` 上的受控复合索引。
- 保留 Java 规则流做最终口径校验，不让 SQL 优化改变统计语义。

## 18 项问题台账

## 修复记录

### 2026-06-25 第一轮：客户问题范围与里程碑筛选共享修复

已完成：

1. 客户问题范围识别不再因为议题带有“系统测试/回归测试”标签而被排除。`CC_Product` 项目、客户问题里程碑或客户问题标签命中后，按客户问题范围处理。
2. 客户问题列表/非法数据等 SQL 分页路径中，顶部“测试阶段”切换在客户问题 scope 下改为匹配 `milestone_title`，不再误用 `phase_filter_value`。
3. 客户问题事实构建中的非法数据派生也同步使用客户问题范围规则，不再被系统测试标签抢走。
4. 客户问题统计页的顶部“测试阶段”共享匹配逻辑已支持父级阶段通过 resolver 匹配 `milestone_title`，不再只做字符串相等比较。

影响项：

- 问题 14：客户问题/缺陷汇总页面为空。
- 问题 15：客户问题/缺陷非法数据选择任意测试阶段后为空。
- 问题 16：客户问题/缺陷原因分析没有模块且加载慢。
- 问题 17：客户问题/延期问题只有“未设定模块”。
- 问题 2：客户问题/按功能展示缺陷数量为空。

验收前置：

- 涉及 `FactBuildService` 的客户问题事实派生规则变更，内网部署后必须重建 issue fact。
- 重建后优先复核截图中的三个页面：客户问题缺陷汇总、延期问题、按功能展示缺陷数量。

---

### 2026-06-25 第二轮：排序、显式模块筛选与候选来源收口

已完成：

1. 统计表格所有百分比/率类单元格的 `numericValue` 改为真实比例排序值。前端仍按后端数值排序，不解析展示文本；`9%`、`10%`、`90%` 会按真实大小排序。
2. 覆盖页面包括系统测试缺陷汇总、共享缺陷汇总支持、客户问题缺陷汇总、客户问题按功能展示缺陷数量、系统测试缺陷原因比例行、客户问题缺陷原因比例行。
3. 新增 `StatisticExplicitModuleFilterSupport`，当条件筛选中存在显式 `moduleName` 正向条件时，客户问题统计类页面只生成匹配模块行和总计行，不再保留无关模块的 0 行。
4. 显式模块行过滤已接入客户问题缺陷汇总、客户问题缺陷原因分析、延期问题、缺陷响应效率、按功能展示缺陷数量。
5. 代码走查非法数据项目下拉已确认从不带 `projectId` 的 `merge_request_fact` 全量候选生成；如果内网仍只有 CrownCAD，需要先复核 MR fact 构建/同步范围，而不是前端写死选项。
6. 评审数据管理下拉已确认优先读取 `ods_gitlab_projects`、`ods_gitlab_users`、`ods_gitlab_labels`、`ods_gitlab_milestones`，再合并已有评审数据；空评审数据不再导致新增评审候选为空。
7. 系统测试申请延期缺陷分析、议题阶段统计、缺陷原因分析已确认接入 `SystemTestPhaseSqlPredicateSupport` 做阶段 SQL 下推；`V20260625_01__performance_indexes.sql` 已覆盖 `issue_fact` 阶段、原因、模块文本、项目阶段等查询索引。

影响项：

- 问题 1：客户问题响应效率与延期问题源码路径已区分；两个页面分别统计周期指标和延期议题。
- 问题 6：系统测试缺陷汇总所有“率”字段升降序错误。
- 问题 7：系统测试/申请延期缺陷分析请求超时。
- 问题 10：议题阶段统计、缺陷原因分析请求超时。
- 问题 12：代码走查非法数据页面项目下拉只有 CrownCAD。
- 问题 13：评审数据管理页面下拉框为空。
- 问题 18：筛选特定模块后其他模块仍显示为 0。

仍需真实环境差异集合验证：

- 问题 3、4、5、8、9、11 涉及具体议题集合、老平台源码口径或内网事实数据差异。当前不为单条标题、单个模块或单个时间范围写特殊规则；需要用文中 SQL 或老平台导出明细先锁定差异集合，再决定是否修改 `IssueClassificationRules`、`IssueLabelRules`、`SystemTestScopeProfile`、MR fact 构建或代码走查非法规则。

---

### 问题 1：客户问题/缺陷响应效率 与 客户问题/延期问题数据完全相同（P1）

现象：两个页面问题数据完全相同。

根因假设：

- 两个服务可能使用了相同数据源或筛选条件。
- 响应效率应基于响应模板、响应周期和解决周期；延期问题应基于响应/解决是否超期。

修复入口：

- `CustomerIssueResponseEfficiencyBoardService.java`
- `CustomerIssueDelayIssuesBoardService.java`
- 客户问题延期和响应规则共享工具类。

验收：

- 响应效率页展示周期类指标，不等同延期问题列表。
- 延期问题页只展示响应或解决已延期的议题。

### 问题 2：客户问题/按功能展示缺陷数量为空（P0）

现象：页面加载成功但没有数据。

根因假设：

- `function_name` 未正确填充。
- 客户问题范围筛选过严。
- 里程碑/测试阶段字段混用。

修复入口：

- `FactBuildService.java`：功能名解析。
- `CustomerIssueByFunctionBoardService.java`：客户问题范围、里程碑筛选、按功能聚合。

验证 SQL：

```sql
SELECT function_name, COUNT(*)
FROM issue_fact
WHERE deleted = false
  AND project_id = 325
GROUP BY function_name
ORDER BY COUNT(*) DESC;
```

### 问题 3：系统测试缺陷汇总/平台/其他 比老平台多 1 条（P2）

现象：多出“【退出草图】项目中文档数量比较多时，退出任意的草图等待的时间都较长”。

根因假设：

- 一级缺陷“其他”排除规则与老平台不同。
- 老平台排除 `退`、`回退`、`倒退`、`挂机`；新平台当前 token 需要以真实规则为准复核。

修复入口：

- `IssueClassificationRules.java`
- `IssueLabelRules.java`

约束：

- 是否把“退出”归入回退/非其他必须以老平台源码和真实数据验证为准，不直接为单例标题加特殊规则。

### 问题 4：系统测试缺陷汇总/装配/其他 比老平台多 3 条【配合】（P1）

现象：新平台多三条以【配合】开头的偶发问题。

根因假设：

- 同一 issue 重复进入事实层。
- 模块归属或排除规则与老平台不同。

验证 SQL：

```sql
SELECT issue_iid, title, COUNT(*)
FROM issue_fact
WHERE title LIKE '%【配合】装配好的模型，退出再打开后配合爆红%'
  AND testing_phase LIKE '%2026R3%第一轮%'
  AND deleted = false
GROUP BY issue_iid, title
HAVING COUNT(*) > 1;
```

修复入口：

- `FactBuildService.java`
- GitLab 镜像到事实层去重逻辑。

### 问题 5：系统测试缺陷汇总/装配/三级缺陷 比老平台少 1 条（P2）

现象：新平台少 1 条三级缺陷。

根因假设：

- 严重程度识别差异。
- 排除规则误排除。
- 模块识别差异。

验证 SQL：

```sql
SELECT issue_iid, title, severity_level, module_names, is_excluded, exclusion_reason
FROM issue_fact
WHERE issue_iid = :iid;
```

修复入口：

- `IssueLabelRules.java`
- `IssueFactNormalizationRules.java`
- `FactBuildService.java`

### 问题 6：系统测试缺陷汇总所有“率”字段升降序错误（P1）

现象：百分比字段排序按字符串排序。

修复方向：

- 后端 `StatisticCellData` 对 rate 类指标输出数值型 `sortValue` 或元数据 `rawValue`。
- 前端排序统一使用后端数值排序依据，不从展示文本解析百分号。

修复入口：

- `SystemTestDefectSummaryBoardService.java`
- `StatisticBoardTable.vue` 或统计表格共享排序逻辑。

验收：

- `9%`、`10%`、`90%` 按 9、10、90 排序。

### 问题 7：系统测试/申请延期缺陷分析请求超时（P0）

现象：页面超过 15 秒超时。

修复方向：

- 测试阶段筛选下推 SQL。
- 增加 `issue_fact(deleted, testing_phase, delay_issue, delay_cause)` 等复合索引。

修复入口：

- `SystemTestDelayAnalysisBoardService.java`
- `IssueFactQueryService.java`
- Flyway 迁移文件。

### 问题 8：系统测试非法数据量比老平台大很多（P1）

现象：非法数据数量远超老平台。

根因假设：

- 非法判定规则差异。
- 默认排除规则或系统测试范围差异。

修复入口：

- `IssueFactNormalizationRules.java`
- `SystemTestIllegalRecordService.java`
- 系统测试范围和排除规则共享组件。

验证：

- 对比新老平台非法议题明细，而不是只看总数。

### 问题 9：系统测试/议题查询数据量比老平台多约 1W（P0）

现象：议题查询返回数据量显著偏大。

根因假设：

- 系统测试范围定义过宽。
- 默认排除规则不一致。
- 测试阶段标签识别过宽。

修复入口：

- `SystemTestScopeProfile.java`
- `IssueFactQueryService.java`
- 测试阶段定义表 resolver。

验收：

- 同项目、同阶段、同筛选条件下，新老平台议题集合差异可解释。

### 问题 10：议题阶段统计、缺陷原因分析请求超时（P0）

现象：两个页面超时。

修复方向：

- 与问题 7 同类：阶段筛选下推 SQL，增加缺陷原因/阶段统计索引。

修复入口：

- `SystemTestPhaseStatisticsBoardService.java`
- `SystemTestDefectCauseBoardService.java`
- `IssueFactQueryService.java`

### 问题 11：代码走查非法数据页面比老平台少 6000 条（P1）

现象：新平台非法数据少很多。

根因假设：

- 非法判定规则差异。
- `merge_request_fact` 数据源不完整。
- 时间范围 `merged_at_source > 2024-04-01` 与老平台不一致。

修复入口：

- `CodeReviewIllegalRecordSourceLoader.java`
- `CodeReviewIllegalRuleRegistry.java`

验证：

```sql
SELECT MIN(merged_at_source), MAX(merged_at_source), COUNT(*)
FROM merge_request_fact
WHERE deleted = false
  AND merge_request_state = 'merged';
```

### 问题 12：代码走查非法数据页面项目下拉只有 CrownCAD（P0）

现象：项目下拉应有 20+ 项，实际只有 CrownCAD。

根因假设：

- 前端缺少项目切换器或后端选项筛选过严。
- `merge_request_fact` 只构建了 CrownCAD。

修复入口：

- `CodeReviewIllegalRecordService.java`
- 代码走查非法数据前端页面。
- `FactBuildService.java` 的 MR 事实构建。

验收：

- 项目下拉来自 `merge_request_fact` 中所有有效项目，不写死 CrownCAD。

### 问题 13：评审数据管理页面下拉框为空（P1）

现象：评审专家、负责人、作者等下拉为空。

修复方向：

- 新增评审功能所有下拉字段优先从 GitLab 镜像库取值。
- 镜像表为空或不存在时可降级到已有评审记录，但不能只依赖导入后的评审数据。

修复入口：

- 评审数据管理 options service。
- `ods_gitlab_users`、项目、里程碑、标签等镜像表查询。

### 问题 14：客户问题/缺陷汇总页面为空（P0）

现象：整个页面没有数据。

根因假设：

- 客户问题用 `milestone_title`，不是 `testing_phase`。
- `CustomerIssueScopeProfile` 过滤过严。
- `project_id = 325` 数据被错误排除。

修复入口：

- `CustomerIssueScopeProfile.java`
- `CustomerIssueDefectSummaryBoardService.java`
- `CustomerIssueSqlPredicateSupport`（建议新增或收敛）。

验证 SQL：

```sql
SELECT project_id, project_name, milestone_title, COUNT(*) AS issue_count
FROM issue_fact
WHERE project_id = 325
  AND deleted = false
GROUP BY project_id, project_name, milestone_title
ORDER BY issue_count DESC;
```

### 问题 15：客户问题/缺陷非法数据选择任意测试阶段后为空（P0）

现象：默认不选有数据，选任意阶段为空。

根因：

- 前端或后端把客户问题顶部切换映射到了 `testing_phase`，而客户问题事实记录主要有 `milestone_title`。

修复入口：

- `CustomerIssueIllegalRecordService.java`
- 客户问题非法数据前端筛选配置。
- `CustomerIssueTestingPhaseFilterSupport.java`

验收：

- 选择父级阶段时，客户问题按 `milestone_title` 命中数据。

### 问题 16：客户问题/缺陷原因分析没有模块且加载慢（P0）

现象：页面没有模块行或只显示总计；老平台有模块但数据为 0；加载慢。

根因假设：

- 模块数据为空或模块识别规则不一致。
- 使用了错误的 `testing_phase` 筛选。
- 没有按里程碑做 SQL 预过滤。

修复入口：

- `CustomerIssueDefectCauseBoardService.java`
- 客户问题模块识别事实构建。
- 客户问题索引迁移。

索引建议：

```sql
CREATE INDEX IF NOT EXISTS idx_issue_fact_customer_issue_query
ON issue_fact(project_id, deleted, milestone_title, module_names, reason_category)
WHERE project_id = 325 AND deleted = false;
```

### 问题 17：客户问题/延期问题只有“未设定模块”（P0）

现象：只显示“未设定模块”，老平台有其他模块且无“未设定模块”。

根因假设：

- 延期议题的 `module_names` 没有按客户问题项目规则填充。
- 统计仍在用系统测试阶段字段。
- 老平台可能从其他字段或标签规则提取模块。

修复入口：

- `CustomerIssueDelayIssuesBoardService.java`
- `FactBuildService.java` 客户问题模块识别。
- 老平台客户问题模块来源对照。

约束：

- 不能从任意标题文本或裸标签临时猜模块；必须先确认老平台规则或更新业务规则总表。

### 问题 18：筛选特定模块后其他模块仍显示为 0（P1）

现象：筛选模块后，其他模块仍在表格中，只是数据为 0。

根因：

- 表格先生成全量模块骨架，再应用单元格过滤，导致不匹配行仍保留。

修复方向：

- 后端统计响应增加可配置行过滤策略：
  - 默认是否隐藏全 0 行由页面定义。
  - 当存在显式模块筛选时，只保留匹配模块行和总计行。
- 不在前端做单页临时过滤，避免导出和下钻口径不一致。

修复入口：

- 统计看板共享行过滤支持。
- 客户问题相关 BoardService。

## 建议实施顺序

### 第一轮：打通 P0 可用性

1. 客户问题里程碑口径统一：问题 14、15、16、17。
2. 慢页 SQL 预过滤和索引：问题 7、10。
3. 系统测试议题查询范围差异诊断：问题 9。
4. 代码走查项目下拉和事实范围：问题 12。

### 第二轮：修正 P1 数据准确性

1. 客户问题响应效率与延期问题分离：问题 1。
2. 系统测试非法数据对齐：问题 8。
3. 代码走查非法数据数量差异：问题 11。
4. 系统测试汇总装配差异：问题 4。
5. 评审数据下拉冷启动：问题 13。
6. 百分比排序：问题 6。
7. 筛选后 0 行展示策略：问题 18。

### 第三轮：收口 P2 差异

1. 平台/其他多 1 条：问题 3。
2. 装配/三级缺陷少 1 条：问题 5。

## 统一验收清单

1. 所有统计类父表数字与点击下钻后的 `total` 一致。
2. 页面导出、下钻、筛选、排序复用同一后端口径。
3. 客户问题页面选择顶部“测试阶段”后，实际按 `milestone_title` 命中客户问题数据。
4. 系统测试页面继续按测试阶段定义表匹配 `testing_phase`。
5. 代码走查非法数据项目下拉来自事实数据或镜像数据，不写死 CrownCAD。
6. 评审新增下拉在空评审数据时仍能从镜像库获取候选。
7. 三个慢页在内网真实数据下不再触发 15 秒超时。
8. 任何事实层口径变更后，已执行 fact 全量重建并用真实 SQL 复核。
