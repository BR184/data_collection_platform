# 漏对齐专项登记

> 用于登记“页面已完成对齐后，复查又发现老平台已有但新平台缺失或未等价暴露”的问题。  
> 每条记录必须能追溯到老平台源码、规则汇总文件或真实页面截图，避免同类漏项在第一轮全页复查后继续出现。

## 登记规则

1. 只登记已进入“已对齐/暂无缺口”状态后又发现的遗漏。
2. 普通待办继续写在对应页面的 gap audit；这里记录的是复查漏网项。
3. 每条必须写清：页面、老平台证据、新平台现状、漏掉原因、后续处理状态。
4. 处理完成后保留记录，不删除，用于最终全量复查清单。

## 漏项清单

### 1. 系统测试 / 系统测试缺陷汇总：缺少测试阶段级联切换入口

- 发现日期：2026-06-18
- 状态：待对齐
- 老平台证据：`D:\projects\spidergitdata-dev\webapp\src\views\PageHome\ContentComponents\QuestionnaireInfo\ModuleTable.vue` 顶部使用 `el-cascader` 选择 `phaseNameList`，点击“查询”后按当前项目/测试阶段调用 `/dataAnalysis/getModuleTable`。
- 新平台现状：`SystemTestDefectSummaryBoardService` 只在统计板普通条件筛选中声明 `projectName`、`testingPhase`；`StatisticBoardView.vue` 没有为该统计板接入 `SYSTEM_TEST_PHASE_SCOPE_PROVIDER` 或等价页级上下文条。
- 漏掉原因：上一轮审计文档基线已记录“测试阶段级联选择”，但收尾时把已修复的导出、下钻、批次信息等问题合并后，误写成“暂无已确认的老平台功能可达性缺口”。没有把“可见页级数据范围入口”单独作为硬核验项。
- 后续处理：应按 `docs/plans/2026-04-27-data-scope-reuse.md` 的统计板上下文接入方向补齐，不能用普通高级筛选临时代替。

### 2. 系统测试 / 议题查询：测试阶段入口未按老平台多选暴露

- 发现日期：2026-06-18
- 状态：已对齐
- 老平台证据：`D:\projects\spidergitdata-dev\webapp\src\views\PageStandard\IssueSearch.vue` 中“测试阶段”为 `multiple` 选择，提交 `phaseNameList`；`IssueStaticDataController.findFilter(...)` 按测试阶段列表查询，底层语义为 `testing_phase in (...)`。
- 新平台修复前现状：页面顶部挂载 `SYSTEM_TEST_PHASE_SCOPE_PROVIDER`，该 provider 是单选模式；高级筛选虽可用条件组合模拟多阶段，但不是老平台直接可见的多选入口。
- 漏掉原因：上一轮对齐时把“完整 testing_phase 候选”和“条件筛选可表达多阶段”当成已满足，漏掉老平台页面自身提供的直接多选操作窗口。
- 处理结果：`SystemTestIssueSearchView.vue` 已把“测试阶段”放入主筛选区并设为多选；路由用逗号分隔保存多个阶段；`SystemTestIssueSearchQueryRequest.testingPhases()` 解析为列表；`IssueFactRecordRepository` 对多阶段使用 `in` 查询，单阶段旧 URL 仍按同一字段兼容。
