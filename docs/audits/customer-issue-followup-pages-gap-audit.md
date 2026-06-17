# 客户问题后续页面新旧平台差异记录

> 记录日期：2026-06-17
> 新平台：`D:\projects\data_collection_platform`
> 老平台：`D:\projects\spidergitdata-dev`
>
> 本文只记录会影响数据一致性、字段一致性或功能可达性的差异。分页默认值、UI 形态、筛选控件使用方式等不作为问题记录。

## 对齐范围

本轮继续对比客户问题模块中除“缺陷汇总”外的页面：

1. 延期问题。
2. 缺陷响应效率。
3. 按功能展示缺陷数量。
4. 缺陷非法数据。
5. 缺陷原因分析。
6. CC_PRODUCT 议题明细。

业务规则以 `docs/platform-page-business-rules.md` 第 5 章为准；老平台代码作为旧平台实际行为基线。

## 老平台基线

### 延期问题

- 页面：`webapp/src/views/PageStandard/DelayIssueTable.vue`
- 主接口：`GET /dataAnalysis/getDelayIssue`
- 下钻：`ModuleTableDetail.getDelayIssueInfoByModuleAndUrgency`
- 服务：`SpiderIssueDataService.getDelayIssue(projectId, milestone)`
- 明细 DAO：`SpiderIssueDataDAOImpl.getDelayIssue(projectId, moduleName, milestone, pageSize, pageNum, urgency, delayIssue)`

老平台可见字段：

- 模块。
- 响应延期的缺陷数量：P1、P2、P3、总计。
- 解决延期的缺陷数量：P1、P2、P3、总计。
- 单独渲染“总数”行。

老平台关键口径：

- `projectId = 325`。
- 按里程碑筛选。
- 只统计 `submission_date >= 2026-01-01` 的 CCProduct 议题。
- 排除 `illegal_list` 包含 `GitLab接口报错` 的数据。
- 模块行来自 `dropDownService.getModuleNameFromSpiderIssueData(projectId, null)`，并追加“未设定模块”和“总数”。
- 模块匹配使用 `module_name.contains(moduleName)`。
- P1/P2/P3 只统计紧急程度命中对应值的数据；“总计”也要求紧急程度命中 P1/P2/P3。

### 缺陷响应效率

- 页面：`webapp/src/views/PageStandard/IssueRespEfficiency.vue`
- 主接口：`GET /dataAnalysis/getIssueRespEfficiency`
- 下钻：`ModuleTableDetail.getRespIssue`
- 服务：`SpiderIssueDataService.getIssueRespEfficiency(projectId, milestone)`
- 明细 DAO：`SpiderIssueDataDAOImpl.getHasRespOrFixedIssue(projectId, moduleName, milestone, pageSize, pageNum, hasRespOrFixed)`

老平台可见字段：

- 产品版本。
- 模块。
- 响应周期（小时）。
- 解决周期（天）。

老平台关键口径：

- 按里程碑筛选。
- 模块行来自全量模块白名单。
- 响应周期只统计有 `researchTemplateTime` 的议题，周期 = 调研模板时间 - 议题提交时间，单位小时，平均值四舍五入取整。
- 解决周期只统计 `bug_status` 包含“已修复/完成”且有 `fixLabelTime` 的议题，周期 = 已修复标签时间 - 议题提交时间，单位天，平均值保留 1 位小数。
- 多模块议题按 `&` 拆分后分别计入对应模块；无模块时归入“未设定模块”。

### 按功能展示缺陷数量

- 页面：`webapp/src/views/PageStandard/IssueShowByFunction.vue`
- 主接口：`GET /dataAnalysis/getIssueCountByFunc`
- 下钻：`ModuleTableDetail.getIssueByModuleAndFunc`
- 服务：`SpiderIssueDataService.getIssueCountByFunction(projectId, milestone)`
- 明细 DAO：`SpiderIssueDataDAOImpl.getIssueByFunctionNameAndModule(projectId, moduleName, milestone, pageSize, pageNum, functionName)`

老平台可见字段：

- 动态透视表：每个模块为一个表头组。
- 每个模块组下有两列：功能、问题数量。

老平台关键口径：

- 按里程碑筛选。
- 只保留 `function_name` 非空的数据。
- 模块按 `&` 拆分，并且只保留全量模块白名单内的模块。
- 聚合粒度为 `模块名 + 功能名`，问题数量为议题数。
- 排序为模块名升序、问题数量降序、功能名升序。

## 待标记差异

### 1. “延期问题”页面类型与老平台不一致

**影响范围：高。属于页面功能和统计字段缺失。**

老平台“延期问题”是模块维度统计表，主表直接展示：

- 响应延期 P1/P2/P3/总计。
- 解决延期 P1/P2/P3/总计。
- “总数”行。

新平台当前 `/customer-issues/delay-issues` 复用 `CustomerIssueRecordsView`，页面类型是记录明细列表，只展示延期相关议题记录和筛选条件。它没有提供老平台同等的模块维度双分组统计表，也没有老平台同名的 P1/P2/P3 响应延期、解决延期统计列。

这不是 UI 差异，而是老平台可直接完成的统计功能在新平台当前入口不可达。

### 2. “延期问题”统计口径需要按老平台字段复刻

**影响范围：高。会影响延期问题总数、模块数和下钻集合。**

老平台延期问题统计并非简单展示所有 `delay_issue`、`is_response_delayed` 或 `is_resolve_delayed` 命中的记录。它还包含以下硬编码口径：

- 排除 `illegal_list` 包含 `GitLab接口报错` 的记录。
- 模块行来自全量模块白名单，并追加“未设定模块”。
- 模块匹配是 `module_name.contains(moduleName)`。
- 响应/解决总计要求 `urgency` 包含 P1/P2/P3；未设定紧急程度不会进入 P1/P2/P3 和总计列。
- 下钻接口同样使用 `delay_issue like 响应/解决`、`urgency eq Pn`、`module_name like moduleName`。

新平台当前明细页 `CustomerIssueRecordService.applyTopic(delay)` 的专题过滤是：

- `delay_issue = true` 或 `is_response_delayed = true` 或 `is_resolve_delayed = true`。

该逻辑适合明细检索，但不能代替老平台模块统计表和对应下钻口径。

### 3. “缺陷响应效率”页面统计指标与老平台不一致

**影响范围：高。属于核心字段和业务口径差异。**

老平台缺陷响应效率主表只有 4 个可见字段：

- 产品版本
- 模块
- 响应周期（小时）
- 解决周期（天）

新平台当前 `CustomerIssueResponseEfficiencyBoardService` 展示的是响应/解决 SLA 数量型统计：

- 缺陷总数
- 已响应
- 未响应
- 响应超期
- 响应延期
- 响应率
- 解决延期
- 解决未延期
- 解决延期率

这些指标对分析有用，但不是老平台该页面的功能。当前页面缺少老平台最关键的“平均响应周期”和“平均解决周期”两个字段。

### 4. “缺陷响应效率”下钻集合与老平台不一致

**影响范围：中到高。影响用户核对周期来源。**

老平台点击响应周期，明细查询条件为：

- 当前模块。
- 当前里程碑。
- `research_template_time is not null`。

老平台点击解决周期，明细查询条件为：

- 当前模块。
- 当前里程碑。
- `bug_status like 已修复`。

新平台当前下钻是按响应/延期数量指标过滤，例如 `responded`、`unresponded`、`response_delayed`、`resolve_delayed` 等；没有针对“响应周期”和“解决周期”的老平台同等下钻指标。

### 5. “按功能展示缺陷数量”主表结构与老平台不一致

**影响范围：高。属于字段展示和功能可达性差异。**

老平台按功能展示缺陷数量是模块-功能透视表：

- 每个模块作为一个表头组。
- 每个模块下固定两列：功能、问题数量。
- 数量单元格支持下钻到 `模块 + 功能` 命中的议题明细。

新平台当前 `CustomerIssueByFunctionBoardService` 是普通统计板行表：

- 行是 `模块 / 功能`。
- 列包含问题数量、已修复/关闭、未关闭、申请延期、响应延期、功能占比、一级/二级/三级/建议类。

新平台额外指标虽然可用于分析，但老平台页面要求的透视表结构和字段没有 1:1 呈现；如果用户按老平台方式横向比较模块下功能数量，当前新页面不能等价完成。

### 6. “按功能展示缺陷数量”口径需要复核是否过滤全量模块白名单

**影响范围：中。影响模块/功能集合。**

老平台聚合前会：

- 只保留 `function_name` 非空议题。
- 将 `module_name` 按 `&` 拆分。
- 过滤掉不在全量模块白名单中的模块。

新平台当前使用事实字段 `module_names` 和 `function_name` 聚合，未在页面服务中显式复刻“只保留全量模块白名单内模块”的逻辑。按项目规则，模块解析应沉淀在事实层；但如果事实层 `module_names` 保留了老平台不会统计的模块值，该页面数量会偏移。

该项需要后续结合事实层模块生成规则复核，不能在页面层临时猜。

### 7. “按功能展示缺陷数量”多展示老平台没有的指标

**影响范围：中。属于字段集合差异。**

老平台此页只展示功能和问题数量。新平台当前额外展示：

- 已修复/关闭。
- 未关闭。
- 申请延期。
- 响应延期。
- 功能占比。
- 严重程度拆分。

这些不是老平台此页功能，且规则汇总文件第 5.5 也只要求“按功能筛选缺陷，展示各模块下所有功能对应缺陷数量；数量支持下钻到明细”。因此应标记为待确认：是否保留为增强能力，还是按老平台重构口径收敛为纯数量透视表。

### 8. “客户问题缺陷非法数据”非法规则说明与规则总表不够一致

**影响范围：中。影响用户理解非法原因，不一定代表列表数据已错。**

规则总表第 5.4 明确客户问题非法数据在系统测试非法数据规则上追加：

- 未按要求填写 `缺陷调研模板`。
- 除“计划解决时间”和“一级缺陷的修改方案请模块负责人签字确认”外，调研模板中的问题都需要有回复内容。
- 计划解决时间有且只能填写一个日期时间戳。
- 一级缺陷必须有模块负责人签字确认。

新平台当前 `CustomerIssueIllegalRecordService.getRuleExplanation()` 只说明：

- 限定客户问题范围。
- 保留 `issue_fact.is_illegal = true`。
- 非法原因来自 `issue_fact.illegal_reason`。

页面数据可以继续依赖事实层，但规则说明没有把客户问题专属非法规则解释给用户，低于规则总表要求的可解释程度。后续对齐时需要补规则说明，并核对事实层是否确实生成了这些非法原因。

### 9. “客户问题缺陷非法数据”列表字段少于老平台通用非法明细字段

**影响范围：中。影响用户核对非法来源。**

老平台通用非法/下钻明细基线字段包括：

- 议题编号
- 模块名
- 议题标题
- 议题状态
- 严重程度
- 处理人
- 非法类型
- 测试状态/延期原因/提交时间/更新时间等展开信息

新平台当前客户问题非法数据主表字段为：

- 议题编号
- 标题
- 非法原因
- 所属项目
- 模块
- 严重程度
- 优先级
- 状态
- 里程碑
- 创建人
- 更新时间

可见字段缺少或命名未按老平台对齐：

- 处理人。
- 测试状态。
- 议题提交时间。
- 延期原因。
- “非法类型”命名。

如果这些字段只在详情抽屉中展示，也需要确认详情入口可见且字段值与老平台一致；否则应补齐。

### 10. CC_PRODUCT 议题明细页面缺少老平台功能字段“功能名”

**影响范围：中。影响记录页字段一致性。**

老平台 `CCProductIssueTable.vue` 主表和展开详情都包含“功能名”，并支持过功能名下拉筛选的历史代码入口（当前老平台页面中该筛选被注释，但展示字段仍存在）。

新平台 `CustomerIssueRecordsView` 当前主表字段为：

- 议题编号
- 标题
- 模块
- 缺陷原因
- 延期标记
- 严重程度
- 优先级
- 状态
- 里程碑
- 创建人
- 更新时间

当前未展示 `functionName`。如果事实层已有 `function_name`，该字段应在 CC_PRODUCT 议题明细中按老平台展示，尤其用于和“按功能展示缺陷数量”互相核对。

### 11. CC_PRODUCT 议题明细默认提交日期范围需复核

**影响范围：中。影响默认列表集合。**

老平台 `CCProductIssueTable.vue` 默认 `submissionDate = '2026-01-01'`，即默认查询 2026-01-01 之后提交的 CCProduct 议题。

规则总表第 5.1 也要求客户问题默认统计 `CC_Product` 项目自 2026-01-01 之后创建的议题。

新平台客户问题记录页通过 `CustomerIssueScopeProfile` 收口，是否所有列表路径和 SQL 分页路径都强制应用 `created/submission >= 2026-01-01` 需要复核。若只在统计板使用该 profile，而记录页某些 SQL 分页路径绕过范围，会导致默认总数大于老平台。

## 本轮已对齐：客户问题缺陷原因分析

### 老平台基线

- 页面：`webapp/src/views/PageStandard/ModuleAndCauseCCProduct.vue`
- 主接口：`GET /dataAnalysis/getModuleAndCauseTable`
- 导出：`exportModuleCauseTable`，文件名为 `{milestone}-客户问题缺陷原因统计表`
- 服务：`IssueServiceImpl#getModuleAndCauseTable`
- DAO：`SpiderIssueDataDAOImpl#findAllByCauseExist`

老平台字段为模块维度宽表，按 24 个固定原因字段展示：

- 需求问题：新增理解偏差、需求遗漏、新增需求、需求变更未同步。
- 设计问题：功能设计遗漏、设计方案不合理、场景考虑不全、术语、提示信息不合适。
- 编码规范：编码规范错误、功能编码遗漏、编码逻辑：计算与算法错误、编码逻辑：流程控制错误、编码逻辑：数据与状态处理错误、编码逻辑：业务逻辑错误、编码逻辑：集成与接口错误。
- 打包问题：环境配置问题、编译/打包/部署问题。
- 依赖问题：第三方库问题、算法不支持、机制不支持、前置数据异常、未识别的前后置任务。
- 精度问题：精度导致约束求解异常、精度导致算法执行异常。

关键口径：

- 固定 `projectId = 325`，按里程碑筛选。
- 只统计 `submission_date >= 2026-01-01` 的 CC_Product 议题。
- 只统计缺陷原因非空的议题。
- 原因匹配使用文本包含匹配，并兼容老模板：
  - 新增理解偏差包含“需求理解有误”。
  - 新增需求包含“新增需求问题”。
  - 编码逻辑：业务逻辑错误包含“编码逻辑错误”。
  - 编译/打包/部署问题包含“编译打包问题”。
  - 机制不支持包含“算法/机制不支持”。
- 模块行来自当前范围内模块集合；同一议题多模块时分别计入。
- 表格尾部包含“共计”和“比例”行；比例分母为所有原因命中次数总和，不是议题数。

### 新平台已修正

- `CustomerIssueDefectCauseBoardService` 已从 6 个粗粒度原因列改为老平台 24 个固定原因字段。
- 原因目录抽到 `DefectCauseMetricCatalog`，系统测试缺陷原因分析和客户问题缺陷原因分析共享同一套老平台原因字段和兼容映射。
- 客户问题缺陷原因统计改为读取 `issue_fact.raw_payload` 中保留的评论文本，并按最新命中的缺陷原因评论做文本包含匹配；无评论文本时才回退 `reason_category`。
- 页面范围继续使用 `CustomerIssueScopeProfile`：CC_Product、自 2026-01-01 以来创建、非系统测试/回归测试范围。
- 页面增加里程碑筛选；未选择时按老平台行为使用里程碑候选列表第一项。
- 主表补齐“共计”和“比例”行；比例按所有原因命中次数计算。
- 下钻明细按“模块 + 原因字段”过滤，展示命中的议题明细。
- 导出从默认 CSV 改为 Excel workbook，文件名为 `客户问题缺陷原因统计表.xlsx`，导出字段和页面宽表一致。

## 待继续复核

### 1. 客户问题非法数据事实层规则

本轮先标记页面说明和字段差异。后续需要继续核对事实层 `issue_fact.is_illegal / illegal_reason` 是否已经实现规则总表第 5.4 的客户问题专属非法规则。

## 暂不记录为问题

- 分页默认值不同。
- 查询控件布局不同。
- 是否用统计板通用外壳渲染。
- 新平台额外提供统一条件筛选、规则说明、刷新状态等增强能力，只要不改变老平台数据结果，可作为体验增强保留；若额外字段会干扰老平台字段集合，则需单独审批。
