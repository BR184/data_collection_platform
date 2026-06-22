# 旧平台表格展示口径调查

日期：2026-06-03

## 范围

本轮调查只读代码，不改业务实现。旧平台源码位于 `D:\projects\spidergitdata-dev`，重点覆盖旧平台前端 `webapp/src/views/PageStandard` 与首页统计表 `ModuleTable/ModuleTableCCProduct`，以及对应后端 `DataAnalysisController`、`SpiderIssueDataService`、`IssueServiceImpl`、`SpiderIssueDataDAOImpl`、`DropDownService`、`ModuleTableRow`。

本轮没有覆盖代码走查、集成测试、评审报告等其它数据域的全部表格；这些表的数据源和模块字段口径不同，建议另开专项。

## 总结

旧平台统计表的核心收口政策不是“发现事实表里所有模块并逐行展示”，而是“先生成模块行骨架，再把符合条件的数据计入这些行”。因此，事实数据里出现 `:草图`、格式不标准的模块名、或旧平台模块行骨架中没有的模块，通常不会在统计表中单独形成一行。

模块归并并不是通用别名字典。旧平台最明确的归并规则是：

- 多模块字段按 `&` 拆分，拆分后 `trim`，再与行模块名精确相等才计入。
- `草图 & X` 会计入 `草图` 行。
- `:草图` 不会因为“看起来像草图”自动计入 `草图`；它只是通常不会进入旧平台的统计行骨架，所以不会显示成独立行。
- 普通明细查询仍有若干 `like/contains` 口径，不能简单把所有表都改成同一种精确匹配。

## 全局过滤口径

旧平台多数 `spider_issue_data` 统计查询会调用 `QueryUtil.setQueryFilter` 或等效逻辑：

- 非客户问题项目默认排除 `category like 功能屏蔽`、`bug_status like 已拒绝`、`category like 建议`。
- 排除 `bug_status like 申请否决` 且关闭的数据。
- 排除 `bug_status like 需求如此` 且关闭的数据。
- 统计查询最后一般再限定 `project_id`。

证据：

- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\utils\QueryUtil.java:18`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\service\impl\SpiderIssueDataDAOImpl.java:58`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\service\SpiderIssueDataQueryBuilder.java:396`

注意：非法议题表、延期表、响应效率表会在这个基础上追加自己的条件，不应只看全局过滤。

## 模块行骨架

旧平台模块行通常来自 `DropDownService` 的去重模块列表，而不是直接按当前统计结果 group by。

模块列表生成规则：

- 查询 `DISTINCT module_name`。
- 只限定项目、测试阶段或里程碑等外层范围。
- 对值按字符串 `" & "` 拆分。
- 拆分后去重，保留首次出现顺序。
- 跳过以“未设定...”开头的模块值。
- 没有发现统一的 `:草图 -> 草图` 这种别名字典。

证据：

- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\service\DropDownService.java:254`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\service\DropDownService.java:343`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\service\DropDownService.java:374`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\service\DropDownService.java:438`

## 模块匹配口径

统计主表 `ModuleTableRow` 使用 `ModuleSplitUtil.isContainModule`：

- 空值不匹配。
- 按 `&` 拆分。
- 对拆分段 `trim`。
- 与目标模块名精确相等才匹配。

证据：

- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\utils\ModuleSplitUtil.java:18`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\entity\statistics\ModuleTableRow.java:442`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\entity\statistics\ModuleTableRow.java:462`

例外和风险：

- `SpiderIssueDataQueryBuilder.setModuleNameQuery` 对明细查询仍使用 `module_name like moduleName`。
- 当查询模块是 `曲线` 或 `曲面` 时，额外 `notLike module_name 曲线曲面`，避免宽泛命中 `曲线曲面`。
- 客户问题延期表部分计数使用 `contains(moduleName)`，不是严格拆分匹配。

证据：

- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\service\SpiderIssueDataQueryBuilder.java:663`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\service\SpiderIssueDataQueryBuilder.java:671`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\service\SpiderIssueDataService.java:2130`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\service\SpiderIssueDataService.java:2154`

## 表格口径

### 首页模块统计表

前端页面：

- `webapp/src/views/PageHome/ContentComponents/QuestionnaireInfo/ModuleTable.vue`
- `webapp/src/views/PageHome/ContentComponents/QuestionnaireInfo/ModuleTableCCProduct.vue`

接口：

- `/dataAnalysis/getModuleTable`

展示规则：

- 第一列固定显示模块名。
- 默认按 `detectRate` 降序。
- 行数据来自后端模块行骨架，并额外追加 `总计` 行。
- 非 `总计` 行的数字会以按钮形式支持下钻；`总计` 行只显示文本。
- 表头分组包括一级缺陷分类、一级/二级/三级缺陷数量与修复率、建议类、P1/P2/P3、模块缺陷总数、延期、打开/关闭/修复等比率字段。

后端规则：

- `SpiderIssueDataService.getModuleTable` 先按阶段/项目取模块名列表，再追加 `总计`。
- 后端并行查询每个 `ModuleTable` 指标对应的议题集合。
- `ModuleTableRow` 再按模块名从各指标集合中过滤计数。
- `总计` 行不做模块过滤，直接使用各指标集合总量。

证据：

- `D:\projects\spidergitdata-dev\webapp\src\views\PageHome\ContentComponents\QuestionnaireInfo\ModuleTable.vue:19`
- `D:\projects\spidergitdata-dev\webapp\src\views\PageHome\ContentComponents\QuestionnaireInfo\ModuleTable.vue:26`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\controller\DataAnalysisController.java:77`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\service\SpiderIssueDataService.java:297`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\service\SpiderIssueDataService.java:338`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\entity\statistics\ModuleTableRow.java:454`

### 缺陷原因统计表

前端页面：

- `webapp/src/views/PageStandard/ModuleAndCause.vue`
- `webapp/src/views/PageStandard/ModuleAndCauseCCProduct.vue`

接口：

- `/dataAnalysis/getModuleAndCauseTable`

展示规则：

- 行名字段是 `rowName`。
- 列是固定的细分缺陷原因，不从数据动态发现。
- 列分组包括需求问题、设计问题、编码规范、编码逻辑、环境/打包、第三方库/算法/机制/前置数据/精度等原因。
- 单元格非空时显示可点击按钮并下钻到明细；空值禁用按钮。
- 前端保留了 `共计`、`比例` 行的特殊展示逻辑；但普通 GET 接口直接返回模块行，导出流程才明确追加总计和比例。

后端规则：

- `IssueServiceImpl.getModuleAndCauseTable` 使用 `getModuleNameByProjectNameFromSpiderIssueData` 生成模块行。
- 只统计 `cause` 已存在的议题。
- 每个模块用 `DataUtil.match(issue.moduleName, moduleName)` 找到归属议题。
- 每列来自 `MinorCauseEnum` 固定枚举。
- `OTHER_CAUSE` 有特殊合并：匹配“其它原因”或“由修改其他问题引起的”。

证据：

- `D:\projects\spidergitdata-dev\webapp\src\views\PageStandard\ModuleAndCause.vue:42`
- `D:\projects\spidergitdata-dev\webapp\src\views\PageStandard\ModuleAndCause.vue:450`
- `D:\projects\spidergitdata-dev\webapp\src\views\PageStandard\ModuleAndCause.vue:475`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\controller\DataAnalysisController.java:1040`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\service\impl\IssueServiceImpl.java:1539`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\service\impl\IssueServiceImpl.java:1544`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\service\impl\IssueServiceImpl.java:1304`

### 模块与大类原因统计表

前端页面：

- `webapp/src/views/PageStandard/ModuleAndCateGory.vue`

接口：

- `/dataAnalysis/getModuleAndCategoryTable`

展示规则：

- 第一列是 `rowName`，页面显示为列名/模块。
- 固定列：需求阶段、设计问题、编码问题、单元测试不完备、由修改其他问题引起的。
- 模块行单元格以按钮形式下钻。
- `共计`、`比例` 两行置底；比例行用 `toFixed(2)` 显示两位小数。

后端规则：

- 行来自 `dropDownService.getSpiderCrowncadDataModuleName()`。
- 每列来自 `MajorCauseEnum` 固定枚举。
- 使用 `SpiderIssueDataQueryBuilder` 按测试阶段、项目、模块、大类原因计数。
- 后端追加 `AttributeSumUtil.getAttributeSum(mcList, sum)`，生成共计和比例。

证据：

- `D:\projects\spidergitdata-dev\webapp\src\views\PageStandard\ModuleAndCateGory.vue:30`
- `D:\projects\spidergitdata-dev\webapp\src\views\PageStandard\ModuleAndCateGory.vue:131`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\controller\DataAnalysisController.java:1009`

### 缺陷等级与测试阶段表

前端页面：

- `webapp/src/views/PageStandard/DefectAndPhaseTable.vue`

接口：

- `/dataAnalysis/getDefectAndPhaseTable`

展示规则：

- 行是 `testingPhaseService.getByName(phase)` 展开的具体测试轮次。
- 列来自缺陷严重程度固定枚举。
- 点击单元格下钻时携带测试阶段和分类。

后端规则：

- 每个测试阶段逐个统计 `DefectLevelEnum`。
- 查询使用测试阶段精确相等、严重程度 like、项目限定，并调用全局过滤。

证据：

- `D:\projects\spidergitdata-dev\webapp\src\views\PageStandard\DefectAndPhaseTable.vue:79`
- `D:\projects\spidergitdata-dev\webapp\src\views\PageStandard\DefectAndPhaseTable.vue:104`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\controller\DataAnalysisController.java:1094`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\service\impl\SpiderIssueDataDAOImpl.java:1797`

### 缺陷等级与延期原因表

接口：

- `/dataAnalysis/getDefectAndDelayCauseTable`

展示规则：

- 行来自 `DelayEnum` 固定枚举。
- 列来自 `DefectLevelEnum` 固定枚举。

后端规则：

- 对选中阶段展开出的所有测试阶段做 `in` 查询。
- 同时匹配严重程度和延期原因。
- 调用全局过滤。

证据：

- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\controller\DataAnalysisController.java:1127`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\service\impl\SpiderIssueDataDAOImpl.java:1807`

### 客户问题延期表

前端页面：

- `webapp/src/views/PageStandard/DelayIssueTable.vue`

接口：

- `/dataAnalysis/getDelayIssue`

展示规则：

- 按里程碑筛选，默认项目是 `325`。
- 行是模块名，另有一个 `总数` 行。
- `总数` 行从表格数据中移出，在表格下方单独渲染。
- 列分为“响应延期的缺陷数量”和“解决延期的缺陷数量”，每组都有 P1、P2、P3、总计。
- 点击单元格下钻到明细；点击 `总数` 时模块参数置空。

后端规则：

- 基础模块列表来自 `dropDownService.getModuleNameFromSpiderIssueData(projectId, null)`，并追加“未设定模块”。
- 延期计数字段依赖 `delayIssue` 是否包含“响应”或“解决”，以及 `urgency` 是否包含 P1/P2/P3。
- 模块匹配使用 `moduleName.contains(...)`，不是严格 `&` 拆分匹配。
- 总数行单独计算，包含未设定紧急程度和未设定模块的说明来自前端 tooltip。

证据：

- `D:\projects\spidergitdata-dev\webapp\src\views\PageStandard\DelayIssueTable.vue:3`
- `D:\projects\spidergitdata-dev\webapp\src\views\PageStandard\DelayIssueTable.vue:15`
- `D:\projects\spidergitdata-dev\webapp\src\views\PageStandard\DelayIssueTable.vue:188`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\controller\DataAnalysisController.java:1260`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\service\SpiderIssueDataService.java:1964`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\service\SpiderIssueDataService.java:2130`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\service\SpiderIssueDataService.java:2154`

### 客户问题响应效率表

前端页面：

- `webapp/src/views/PageStandard/IssueRespEfficiency.vue`

接口：

- `/dataAnalysis/getIssueRespEfficiency`

展示规则：

- 按里程碑筛选，默认项目是 `325`。
- 行是模块名。
- 主要展示响应周期、解决周期一类效率字段。
- 点击行/单元格下钻时携带模块、里程碑和项目。

后端规则：

- 先拿项目下完整模块列表，保持行骨架稳定。
- 只统计当前项目和里程碑下的议题。
- 模块字段按 `&` 拆分后 `trim`。
- 响应周期：提交时间到调研模板回复时间，单位小时，四舍五入到整数。
- 解决周期：提交时间到修复标签时间，单位天，保留 1 位小数；只统计 `bug_status` 包含“已修复/完成”的数据。

证据：

- `D:\projects\spidergitdata-dev\webapp\src\views\PageStandard\IssueRespEfficiency.vue:68`
- `D:\projects\spidergitdata-dev\webapp\src\views\PageStandard\IssueRespEfficiency.vue:96`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\controller\DataAnalysisController.java:1269`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\service\SpiderIssueDataService.java:2206`

### 客户问题按功能展示表

前端页面：

- `webapp/src/views/PageStandard/IssueShowByFunction.vue`

接口：

- `/dataAnalysis/getIssueCountByFunc`

展示规则：

- 按里程碑筛选，默认项目是 `325`。
- 行是功能名。
- 点击下钻时携带功能名、里程碑、项目。

后端规则：

- 后端入口在 `DataAnalysisController.getIssueCountByFunc`。
- 统计主体在 `SpiderIssueDataService.getIssueCountByFunction`。
- 该表按功能聚合，不是模块名聚合；模块脏值对行展示影响较小，但下钻明细仍可能受模块筛选影响。

证据：

- `D:\projects\spidergitdata-dev\webapp\src\views\PageStandard\IssueShowByFunction.vue:77`
- `D:\projects\spidergitdata-dev\webapp\src\views\PageStandard\IssueShowByFunction.vue:127`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\controller\DataAnalysisController.java:1278`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\service\SpiderIssueDataService.java:98`

### 普通议题明细表

前端页面：

- `webapp/src/views/PageStandard/IssueSearch.vue`
- `webapp/src/views/PageStandard/IssueSearchCCProduct.vue`
- `webapp/src/views/PageStandard/CCProductIssueTable.vue`

接口：

- `findByModuleNameAndPhaseName` 对应旧平台议题明细查询。

展示规则：

- 左侧筛选包括更新日期、提交日期、模块名、功能名、测试阶段、提交人、处理人、状态、严重程度、测试状态、议题类别、里程碑、议题编号、标题。
- 表格主列通常包括议题编号、模块名、标题、状态、严重程度、测试状态。
- 客户问题表额外展示作者、处理人、优先级、类别、里程碑、提交/更新时间等列。
- 支持分页，默认 pageSize 多为 20。

后端规则：

- 明细查询使用 `SpiderIssueDataQueryBuilder` 拼接条件。
- 模块筛选是 `like module_name`，不是统计主表的 `&` 精确拆分。
- 查询 `曲线` 或 `曲面` 时额外排除 `曲线曲面`。

证据：

- `D:\projects\spidergitdata-dev\webapp\src\views\PageStandard\IssueSearch.vue:192`
- `D:\projects\spidergitdata-dev\webapp\src\views\PageStandard\IssueSearch.vue:359`
- `D:\projects\spidergitdata-dev\webapp\src\views\PageStandard\CCProductIssueTable.vue:190`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\service\SpiderIssueDataQueryBuilder.java:663`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\service\SpiderIssueDataQueryBuilder.java:671`

### 非法议题明细表

前端页面：

- `webapp/src/views/PageStandard/IllegalIssueSearch.vue`
- `webapp/src/views/PageStandard/IllegalIssueSearchCCProduct.vue`

接口：

- `/issueStaticData/getIllegalIssue`

展示规则：

- 主列包括议题编号、模块名、议题标题、议题状态、严重程度、处理人、非法类型。
- 支持模块、阶段、类别、里程碑等筛选和分页。
- 模块下拉会额外追加“未设定模块”。

后端规则：

- 只取 `illegal_list` 非空数据。
- 若指定模块，使用 `module_name = moduleName` 精确匹配，而不是 like。
- 其它过滤经 `IssueStaticDataController` 和 DAO 组合完成。

证据：

- `D:\projects\spidergitdata-dev\webapp\src\views\PageStandard\IllegalIssueSearch.vue:88`
- `D:\projects\spidergitdata-dev\webapp\src\views\PageStandard\IllegalIssueSearch.vue:260`
- `D:\projects\spidergitdata-dev\webapp\src\views\PageStandard\IllegalIssueSearch.vue:339`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\service\impl\SpiderIssueDataDAOImpl.java:2499`
- `D:\projects\spidergitdata-dev\src\main\java\com\huayun\service\impl\SpiderIssueDataDAOImpl.java:2506`

### 下钻明细弹窗

前端页面：

- `webapp/src/views/PageHome/ContentComponents/ModuleTableDetail.vue`

展示规则：

- 表格列包括议题编号、模块名、议题标题、议题状态、严重程度、测试状态。
- 延期场景额外显示延期原因。
- 展开行显示更新时间、提交时间、模块名、编号、标题、提交人、处理人、状态、测试状态、严重程度。
- 下钻入口根据来源分别调用：按模块/严重程度、按模块/原因、按延期/优先级、按响应/解决、按功能。

证据：

- `D:\projects\spidergitdata-dev\webapp\src\views\PageHome\ContentComponents\ModuleTableDetail.vue:3`
- `D:\projects\spidergitdata-dev\webapp\src\views\PageHome\ContentComponents\ModuleTableDetail.vue:41`
- `D:\projects\spidergitdata-dev\webapp\src\views\PageHome\ContentComponents\ModuleTableDetail.vue:320`
- `D:\projects\spidergitdata-dev\webapp\src\views\PageHome\ContentComponents\ModuleTableDetail.vue:340`
- `D:\projects\spidergitdata-dev\webapp\src\views\PageHome\ContentComponents\ModuleTableDetail.vue:405`

## 对新平台的对齐建议

1. 统计表不要从事实数据直接 group by 模块名生成行；应先使用旧平台等价的模块行骨架，再把数据归入行。
2. 主统计表模块归属应按 `&` 拆分后精确匹配，不应把 `草图` 与 `:草图` 直接合并，除非后续明确建立别名字典。
3. `:草图`、空白前缀、异常标点等脏模块应进入“数据质量/未收口模块”审计，而不是直接作为统计表行展示。
4. 明细表和非法表不要盲目套用统计表精确匹配：旧平台明细查询多处仍是 `like` 或 `eq`，需要逐接口对齐。
5. 客户问题延期表目前旧平台使用 `contains` 统计模块，若新平台使用精确拆分，会与旧平台产生差异；需要产品确认是否“复刻旧平台”还是“修正旧口径”。
6. 共计/比例/总计行的展示位置要按表区分：模块主表是 `总计` 行，模块大类表是 `共计/比例` 置底，客户延期表是 `总数` 独立渲染。

## 本次集成测试首页数据差异分析与方案

现象：

- 新平台 `/integration-test/home` 初始模块汇总中，`工具` 等模块的 `执行用例总数` 出现 200 这类无法与旧平台解释的数值。
- 打开同一模块明细后，明细议题列表基本覆盖旧平台对应模块，只是新平台可能多几条；但明细行的执行用例、通过用例、未通过等业务统计字段大量为 0。
- 旧平台 `IntegrationTable.vue` 首页展示的不是议题条数，而是 `spider_integration_data` 中按模块汇总后的 `executeCase/passCase/notPassCase/notPassCaseNow/problemCase/exception/passRate`。

根因判断：

- 新平台首页和明细都读 `integration_test_fact`，不是前端列名映射错误。
- 旧平台集成测试数据来源允许 `## 集成测试数据` 后继续出现 `### 功能`、`### 执行用例总数`、`### 通过用例数` 等 Markdown 标题式字段。
- 新平台 `IntegrationTestNoteParser` 在进入“集成测试数据”段后，遇到任意新的 Markdown 标题就结束解析。因此旧平台常见的 `### 字段名：值` 模板会只保留议题、模块、阶段等外层信息，核心统计字段没有进入 `integration_test_fact`。
- 首页汇总按事实表统计字段求和，字段缺失时只能得到 0 或偏低值；明细因为模块和议题身份仍存在，所以看起来“明细差不多，首页数字不对”。

修复方案：

1. 保持新平台架构不变，继续以 `integration_test_fact` 作为汇总、明细、导出的唯一事实来源。
2. 扩展 `IntegrationTestNoteParser`：在 `## 集成测试数据` 段内，遇到 `### 功能：...`、`### 执行用例总数：...`、`### 通过用例数：...`、`### 未通过用例数：...`、`### 问题用例数：...`、`### 用例外问题数：...` 这类标题式字段时继续解析，不把它当成段落结束。
3. 保留遇到非集成测试字段标题时结束解析的保护，避免误读后续章节。
4. 用回归测试覆盖旧平台标题式模板，先确认当前解析失败，再修复解析器。
5. 修复后不改前端列定义；首页的 `执行用例总数/通过用例数/通过率` 会随事实字段恢复而与模块明细同口径聚合。

## 未确认项

- `DataUtil.match` 的具体匹配语义本轮未深挖；它影响缺陷原因统计表的模块归属。
- 旧平台源码中文在当前控制台输出存在编码噪声，但方法、字段、接口、枚举结构可读；本报告中文业务词基于页面源码 UTF-8 输出、字段名和已知业务标签还原。
- 代码走查、集成测试、评审类表格未纳入本轮“表格展示口径”完整覆盖。
