# 系统测试缺陷汇总页新旧平台差异记录

> 记录日期：2026-06-15
> 新平台：`D:\projects\data_collection_platform`
> 老平台：`D:\projects\spidergitdata-dev`
>
> 本文只记录会影响数据一致性、字段一致性或功能可达性的差异。分页默认值、UI 形态、筛选控件使用方式等不作为问题记录。

## 对齐目标

同一项目/测试阶段/筛选条件下，新平台应与老平台保持：

1. 主表模块行集合和总计行一致。
2. 每个统计列的数量、百分比和空值展示一致。
3. 可下钻的统计单元格命中的议题集合一致。
4. 下钻表格展示字段和字段值一致。
5. 当前汇总导出、全量议题导出和横向对比导出的数据范围、字段含义和值一致。
6. 页面可见的数据批次信息一致或能表达同等含义。

## 老平台基线

### 页面入口和主接口

- 页面：`webapp/src/views/PageHome/ContentComponents/QuestionnaireInfo/ModuleTable.vue`
- 主表接口：`GET /dataAnalysis/getModuleTable`
- 当前汇总导出：`POST /dataAnalysis/exportModuleTable`
- 全量议题数据导出：`GET /issueStaticData/all`
- 系统测试横向对比导出：`POST /staticData/exportTestDataExcel`
- 下钻弹窗：`webapp/src/views/PageHome/ContentComponents/ModuleTableDetail.vue`

### 主表统计来源

老平台主表由 `SpiderIssueDataService.getModuleTable()` 调用 `ModuleTable.values()` 逐项查询，再由 `ModuleTableRow` 组装。关键代码：

- `src/main/java/com/huayun/service/SpiderIssueDataService.java`
- `src/main/java/com/huayun/service/impl/SpiderIssueDataDAOImpl.java`
- `src/main/java/com/huayun/entity/statisticsEnum/ModuleTable.java`
- `src/main/java/com/huayun/entity/statistics/ModuleTableRow.java`

### 老平台可见能力

老平台页面提供：

- 测试阶段级联选择。
- 查询当前阶段系统测试缺陷汇总。
- 下载当前汇总。
- 下载当前阶段全量议题数据。
- 下载系统测试横向对比 Excel。
- 展示定时任务执行时间和执行时长。
- 主表数量类单元格下钻到议题详情。
- 下钻详情字段：议题编号、模块名、议题标题、议题状态、严重程度、测试状态、延期原因，以及展开区的议题更新时间、议题提交时间、提交人、处理人等。

## 已确认一致或基本具备

### 1. 主表列集合基本覆盖

新平台 `SystemTestDefectSummaryBoardService` 已覆盖老平台主表大部分可见统计列：

- 一级缺陷：回退、挂机、其他、已修复数量、数量、修复率。
- 二级缺陷：已修复数量、数量、修复率。
- 三缺陷：已修复数量、数量、修复率。
- 建议类缺陷数量。
- P1/P2/P3 数量和修复率，P1/P2 关闭率。
- 模块总缺陷数、缺陷占比、延期缺陷占比、已修复/未更新、修复率、关闭率、未关闭缺陷数、申请延期、复测未通过。
- 新发议题数量、修复数量、修复率、关闭率。
- 一级遗留率、二级遗留数量、三级遗留数量、二三级遗留率。

### 2. 下钻能力基本具备

新平台统计单元格支持 `/api/statistic-boards/system-test-defect-summary/details` 下钻，能返回 issue 明细并带 GitLab 链接字段。

### 3. 当前汇总导出基本具备

新平台统计板通用导出 `/api/statistic-boards/system-test-defect-summary/export` 可导出当前汇总。

## 待修正差异

### 1. 修复类统计口径和老平台不一致

**影响范围：高。会直接影响多个主表数量和百分比。**

老平台不是统一用 `closed` 判定“已修复”。不同列有不同硬编码口径：

- `FIXED`、`CRITICAL_FIXED`、`MAJOR_FIXED`、`MINOR_FIXED`、`NEW_ISSUE_FIX` 走 `setFixQuery()`。
- `setFixQuery()` 语义是测试状态匹配老平台修复类标签，例如 `已修复/完成`、`待合并`、`未更新` 等；不是单纯 `status = CLOSED`。
- `P1_FIXED`、`P2_FIXED`、`P3_FIXED` 又是另一套口径：`bug_status` 包含 `已修复/完成` 或 `未复现`，或 `status = CLOSED`。
- `P2_CLOSED`、`P3_CLOSED` 要求 `status = CLOSED` 且 `bug_status` 包含 `已修复/完成` 或 `未复现`。

新平台当前 `StatisticIssueFactSource.isSolvedLike()` / `SystemTestDefectSummaryBoardService.IssueSource.isSolvedLike()` 为：

- `fixed || isClosed()`

这会把所有关闭议题都算进“已修复/未更新”和各类修复率，可能大于老平台结果。需要按老平台每个 `ModuleTable` 统计项拆分映射，不能用一个统一 `isSolvedLike()` 覆盖全部列。

### 2. 关闭率口径和老平台不一致

**影响范围：高。影响 `关闭率(%)`、`P2缺陷关闭率(%)`、`P3缺陷关闭率(%)`、新发关闭率。**

老平台：

- 总体 `closedRate` 使用 `CLOSED`，即 `status = CLOSED`。
- `P1_CLOSED` 只要求 `urgency = P1` 且 `status = CLOSED`。
- `P2_CLOSED` / `P3_CLOSED` 要求关闭并且测试状态包含 `已修复/完成` 或 `未复现`。
- `NEW_ISSUE_CLOSED_COUNT` 要求非历史遗留、关闭、且测试状态包含 `已修复/完成` 或 `未复现`。

新平台当前：

- 多数关闭率直接按 `isClosed()` 或 `fixed && isClosed()` 计算。

这会导致部分列与老平台不一致，尤其是 P2/P3 和新发关闭率。

### 3. 一级缺陷“其他”分类口径可能不一致

**影响范围：中到高。影响一级缺陷分类三列和一级缺陷分布。**

老平台 `OTHERS` 规则：

- `severity_level = 一级缺陷`
- 标题不包含 `退`、`回退`、`倒退`、`挂机`

新平台依赖事实字段 `level1Other`。需要确认事实层 `level1Other` 是否严格按上述标题排除规则生成，而不是依赖标签或其他分类字段。如果事实层不是完全复刻，`其他(个)`、`回退(个)`、`挂机(个)` 会不一致。

### 4. 复测未通过口径不一致

**影响范围：中。影响 `复测未通过缺陷数(个)` 和下钻。**

老平台 `RETEST_FAILED`：

- `bug_status like '未修复'`

新平台当前：

- `labels.contains("复测未通过")`

如果本地事实层没有把老平台 `bug_status = 未修复` 统一映射为 `复测未通过` 标签或字段，则该列数量不一致。按老平台应优先遵从测试状态 `未修复`。

### 5. 遗留率区域口径和老平台不一致

**影响范围：高。影响遗留率区域 4 列。**

老平台当前显示列和代码口径：

- `一级缺陷遗留率(%)` = `(一级缺陷总数 - 一级未修复数) / 一级缺陷总数`，代码使用 `critical - CRITICAL_OPEN`。
- `二级缺陷遗留数量` = 二级未修复数，代码使用 `MAJOR_OPEN`。
- `三级缺陷遗留数量` = 三级未修复数，代码使用 `MINOR_OPEN`。
- `二三级缺陷遗留率(%)` = 二三级未修复数 / 模块总缺陷数，代码使用 `MajorAndMinorOpen / moduleAll2`。

新平台当前按 `issue_fact.is_legacy` 统计：

- 一级遗留率 = 一级历史遗留数量 / 一级总数。
- 二三级遗留率 = 二三级历史遗留数量 / 二三级总数。

虽然新平台命名更合理，但与老平台写死口径不一致。当前阶段应按老平台口径对齐，除非业务方明确重定义。

### 6. 模块行集合来源可能不一致

**影响范围：中到高。影响主表是否显示 0 行模块。**

老平台模块行来自：

- `dropDownService.getModuleNameFromSpiderIssueData(projectId, testingPhases)`
- 再追加 `总计`

新平台当前按当前范围内 issue 的 `moduleNames()` 动态建桶，再追加 `总计`。

业务规则要求“模块列来自全量议题模块信息，不限当前项目或当前测试阶段；当前项目或测试阶段下没有对应模块数据时，数量显示为 0，不能隐藏模块行”。如果新平台只展示当前范围有数据的模块，会缺少老平台/规则要求的 0 值模块行。

### 7. 下钻详情字段少于老平台

**影响范围：中。影响用户核对统计来源。**

老平台下钻表格和展开区展示：

- 议题编号
- 模块名
- 议题标题
- 议题状态
- 严重程度
- 测试状态
- 延期原因
- 议题更新时间
- 议题提交时间
- 议题提交人
- 议题处理人

新平台当前 `SystemTestDefectSummaryBoardService` 下钻字段：

- 议题编号
- 标题
- 模块
- 所属项目
- 创建人
- 状态
- 标签
- 更新时间

缺少或未按老平台字段名展示：

- 测试状态
- 严重程度
- 延期原因
- 议题提交时间
- 议题处理人

### 8. 下钻过滤条件无法完全表达老平台部分统计项

**影响范围：高。影响点击数字后的明细集合。**

老平台不同单元格分别调用不同查询：

- 回退/挂机/其他：`findByModuleAndTitle`
- 严重程度/建议/总缺陷/open/申请延期：`issueStaticData/filter`
- 已修复/未更新：`getByModuleAndBugStatus`
- P1/P2/P3：`getByModuleAndUrgency`

新平台 `matchesMetric()` 对部分比例列默认返回 `true`，且没有为所有老平台可点单元格提供对应过滤：

- `p1_fix_rate`、`p1_close_rate`、`p2_fix_rate`、`p2_close_rate`、`p3_fix_rate` 目前没有精确下钻谓词。
- `level1_legacy_rate`、`level23_legacy_rate` 等比例列不可下钻还可以接受，但若页面允许点击，必须按老平台可达集合处理。

需要确认前端是否只允许数量列下钻；如果允许比例列或修复率列下钻，新平台明细会过宽。

### 9. 缺少“全量议题数据”导出入口或未在本页等价暴露

**影响范围：中。功能可达性差异。**

老平台系统测试缺陷汇总页有“下载议题数据”，调用：

- `GET /issueStaticData/all`

新平台统计板通用导出只导出汇总板本身。若本页没有等价入口导出当前阶段全量议题数据，则老平台功能缺失。已有系统测试议题查询页的导出不能自动视为本页等价，除非入口和筛选范围能一键复现当前缺陷汇总页范围。

### 10. 缺少“系统测试横向对比 Excel 下载”入口或未在本页等价暴露

**影响范围：中。功能可达性差异。**

老平台系统测试缺陷汇总页有“系统测试横向对比excel下载”，调用：

- `POST /staticData/exportTestDataExcel`

新平台系统测试横向对比是独立规则章节，但当前缺陷汇总页如果没有同等入口，属于老平台可达功能缺失。

### 11. 数据批次信息没有 1:1 展示

**影响范围：低到中。影响用户判断数据时效，不直接影响统计值。**

老平台显示：

- `任务执行时间`
- `执行时长`

新平台显示实时工作区刷新状态、最近同步、镜像/事实刷新状态。语义更完整，但不是老平台字段 1:1。若用户需要按老平台字段判断批次，需补齐等价字段或在状态中明确展示任务执行时间和执行时长。

### 12. 过滤规则需要复核“建议”处理是否与主表列兼容

**影响范围：中。影响建议类缺陷数量。**

业务规则 4.2 写系统测试统计默认排除“建议”标签，但系统测试缺陷汇总主表又包含“建议类缺陷(个)”列。老平台 `ModuleTable.SUGGESTION` 按 `category like '建议'` 统计。

新平台规则说明目前写“剔除功能屏蔽、已拒绝、建议，以及关闭后属于申请否决/数据异常/需求如此的议题”，但主表又有 `suggestion_total`。需要核对 `IssueFactBoardRuntimeSupport` / `issue_fact.is_excluded` 是否把建议类整体排除。如果排除，则新平台建议类列永远偏小或为 0；如果未排除，则规则说明与业务规则文字需要澄清。

## 暂不记录为问题

- 老平台默认表格高度、固定列、排序交互与新平台不同。
- 老平台分页默认值与新平台不同。
- 新平台额外提供规则说明、统一条件筛选、实时刷新状态等增强能力。

## 2026-06-15 对齐进展

### 已修正

1. 修复类统计口径已改为按老平台 `ModuleTable` 分项谓词计算，不再用 `fixed || closed` 统一代替。
2. `issue_fact.bug_status` 已从老平台 `状态：` 标签和裸状态标签归一化生成；无状态标签时才回退为“已关闭/未关闭”。
3. P1/P2/P3 修复率、P1/P2/P3 关闭率、新发关闭率已按老平台关闭和测试状态组合口径计算。
4. 复测未通过已改为按 `bug_status` 包含“未修复”统计。
5. 遗留率区域已改为老平台 `ModuleTableRow` 当前写死口径，不再使用 `issue_fact.is_legacy`。
6. 模块行已按系统测试作用域内出现过的模块预建空桶，避免有效议题被过滤后模块行消失。
7. 下钻详情已补齐严重程度、测试状态、延期原因、议题提交时间、议题处理人。
8. 系统测试缺陷汇总页已补“下载议题数据”动作，复用 `/api/question-metrics/issues/export` 并携带当前条件筛选。
9. 系统测试缺陷汇总页已补“横向对比导出”动作，新增 `/api/statistic-boards/system-test-defect-summary/horizontal-comparison/export`，导出范围包含评审数据、CrownCAD/DGM 代码走查数据、缺陷原因和系统测试缺陷汇总；代码走查按 `dev + MERGED` 过滤，`CC2025R1` 评审项目映射为 `CC2025R1&R2`。
10. 页面刷新状态已补老平台同义字段“任务执行时间/执行时长”，直接使用实时工作区返回的 `lastRefreshStartedAt`、`lastRefreshFinishedAt` 计算展示。
11. “建议类缺陷”列与公共过滤规则的关系已确认保持老平台现状：系统测试公共规则会排除建议类数据，列仍保留用于兼容老平台表头和历史导出结构；若后续业务确认建议类需要纳入统计，应先更新 `docs/platform-page-business-rules.md`。
12. 系统测试横向对比导出已由 CSV 改为 Excel 工作簿，接口返回 `.xlsx` 文件，前端按文件下载处理，补齐老平台“系统测试横向对比excel下载”的格式差异。

### 仍未完成

暂无已确认的老平台功能可达性缺口仍未补齐。

### 已知格式差异

暂无。
