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
- 状态：已补齐代码，后端编译复验已通过，待数据一致性抽样和真实链路复查
- 老平台证据：`D:\projects\spidergitdata-dev\webapp\src\views\PageHome\ContentComponents\QuestionnaireInfo\ModuleTable.vue` 顶部使用 `el-cascader` 选择 `phaseNameList`，点击“查询”后按当前项目/测试阶段调用 `/dataAnalysis/getModuleTable`。
- 新平台现状：`SystemTestDefectSummaryBoardService` 只在统计板普通条件筛选中声明 `projectName`、`testingPhase`；`StatisticBoardView.vue` 没有为该统计板接入 `SYSTEM_TEST_PHASE_SCOPE_PROVIDER` 或等价页级上下文条。
- 漏掉原因：上一轮审计文档基线已记录“测试阶段级联选择”，但收尾时把已修复的导出、下钻、批次信息等问题合并后，误写成“暂无已确认的老平台功能可达性缺口”。没有把“可见页级数据范围入口”单独作为硬核验项。
- 处理结果：`StatisticBoardView.vue` 已接入统计板页级数据范围配置；`system-test-defect-summary` 使用项目/测试阶段级联选择，选择后写入 `testingPhase` 路由参数，并合并为同一套 `filterGroup`，覆盖主表、下钻、规则说明和导出请求。`SystemTestDefectSummaryBoardService` 已补充阶段候选、阶段归一化匹配和页面筛选规则流。
- 验证记录：前端 `npm.cmd run typecheck` 已通过；后端 `mvn -q -DskipTests compile` 首次失败于新增 `trimToNull` 与父类 protected 方法权限冲突，已改名为 `trimTextToNull`；后续单独复验后端编译已通过。仍需按页面做数据一致性抽样和真实链路复查。

### 2. 系统测试 / 议题查询：测试阶段入口未按老平台多选暴露

- 发现日期：2026-06-18
- 状态：已对齐
- 老平台证据：`D:\projects\spidergitdata-dev\webapp\src\views\PageStandard\IssueSearch.vue` 中“测试阶段”为 `multiple` 选择，提交 `phaseNameList`；`IssueStaticDataController.findFilter(...)` 按测试阶段列表查询，底层语义为 `testing_phase in (...)`。
- 新平台修复前现状：页面顶部挂载 `SYSTEM_TEST_PHASE_SCOPE_PROVIDER`，该 provider 是单选模式；高级筛选虽可用条件组合模拟多阶段，但不是老平台直接可见的多选入口。
- 漏掉原因：上一轮对齐时把“完整 testing_phase 候选”和“条件筛选可表达多阶段”当成已满足，漏掉老平台页面自身提供的直接多选操作窗口。
- 处理结果：`SystemTestIssueSearchView.vue` 已把“测试阶段”放入主筛选区并设为多选；路由用逗号分隔保存多个阶段；`SystemTestIssueSearchQueryRequest.testingPhases()` 解析为列表；`IssueFactRecordRepository` 对多阶段使用 `in` 查询，单阶段旧 URL 仍按同一字段兼容。

## 仍需继续复核的页面

> 下面这些页面目前还不能算“已完全对齐”。它们要么仍有明确的老平台功能差异，要么还有事实层/导出/默认入口未收口项。

### 1. 系统测试 / 系统测试缺陷汇总

- 当前状态：已补齐代码，后端编译复验已通过，待数据一致性抽样和真实链路复查
- 主要缺口：页级测试阶段级联切换入口已接入；需继续核验同一项目/测试阶段下统计、下钻、规则说明和导出是否与老平台一致。
- 关联文档：`docs/audits/system-test-defect-summary-gap-audit.md`

### 2. 系统测试 / 申请延期缺陷分析

- 当前状态：未完全闭环
- 主要缺口：默认阶段、空阶段边界、导出和规则说明仍有老平台口径差异，需要继续复核。
- 关联文档：`docs/audits/system-test-delay-analysis-gap-audit.md`

### 3. 客户问题 / 缺陷响应效率

- 当前状态：未完全闭环
- 主要缺口：默认里程碑入口、总计行可见性、页面可见字段集合仍与老平台存在差异。
- 关联文档：`docs/audits/customer-issue-followup-pages-gap-audit.md`

### 4. 客户问题 / 按功能展示缺陷数量

- 当前状态：未完全闭环
- 主要缺口：老平台是模块-功能透视表，新平台当前仍是普通统计行表，且多展示了老平台没有的指标。
- 关联文档：`docs/audits/customer-issue-followup-pages-gap-audit.md`

### 5. 客户问题 / 缺陷非法数据

- 当前状态：未完全闭环
- 主要缺口：规则说明、部分字段展示和事实层非法规则仍需继续复核。
- 关联文档：`docs/audits/customer-issue-illegal-records-gap-audit.md`

## 已建立审计但本轮暂跳过的页面

> 下面这些页面已经建立二轮差异审计，但由于当前需求边界未明确，本轮不继续实现。它们不算已完成老平台 1:1 对齐，也不作为继续推进其他模块的阻塞项。

### 1. 质量看板 / 研发质量看板

- 页面入口：`quality-board-rd-quality-board`
- 当前判断：已建立 `docs/audits/quality-board-gap-audit.md`，确认与老平台存在较大差异。
- 暂跳过原因：业务方确认暂未拿到新的看板模块需求，本轮先跳过看板模块。
- 后续处理：等看板模块需求明确后，再按老平台源码、规则汇总和新需求重新裁定是否 1:1 还原或产品化重构。

### 2. 质量看板 / 其他看板

- 页面入口：`quality-board-other-board`
- 当前判断：已建立 `docs/audits/quality-board-gap-audit.md`，确认与老平台存在较大差异。
- 暂跳过原因：业务方确认暂未拿到新的看板模块需求，本轮先跳过看板模块。
- 后续处理：等看板模块需求明确后，再确认老平台等价入口、指标集合、跳转和导出是否需要恢复。

## 未找到独立页面级对齐审计闭环的页面

> 下面这些页面在前端仍有入口，但本轮没有找到对应的独立“新老平台页面级差异审计 + 对齐实现 + 复查验证”闭环文档。它们不自动等于功能错误，但不能算已经完成老平台 1:1 对齐。

### 1. 评审数据 / 评审数据管理

- 页面入口：`review-data-home`
- 当前判断：已建立 `docs/audits/review-data-management-gap-audit.md`，本轮已完成差异标记。
- 后续处理：等待业务方确认审计中的待确认差异后，再进入实现或抽样验证。

### 2. 代码走查 / 代码走查多元看板

- 页面入口：`code-review-multi-board`
- 当前判断：代码走查非法数据页已有审计；多元看板只在计划文档中出现，未找到独立老平台对齐审计闭环。
- 后续处理：需要确认老平台是否有同等多元看板；若属于新平台增强，应记录保留依据。

### 3. 系统测试 / 议题多元看板

- 页面入口：`question-metrics-multi-board`
- 当前判断：多个统计页审计中提到多元看板关联能力，但未找到独立页面级审计闭环。
- 后续处理：需要核对老平台多元看板图表、导出和当前 `SystemTestMultiBoardView` 的数据口径。

### 4. 系统设置 / 标签组管理

- 页面入口：`label-group-settings`
- 当前判断：该页属于新平台新增能力，已有设计文档 `docs/plans/2026-06-10-label-group-value-set-design.md`，不属于老平台已有页面 1:1 重构。
- 后续处理：保留为新设计能力，但需要继续按设计文档验收，不纳入老平台页面漏对齐。

### 5. 系统设置 / 数据镜像设置

- 页面入口：`mirror-settings`
- 当前判断：该页属于新平台数据同步治理能力，未找到老平台同名页面对齐审计。
- 后续处理：作为新平台基础设施页面单独验收，不按业务统计页面口径对齐。

### 6. 系统设置 / 数据库查看

- 页面入口：`database-browser`
- 当前判断：该页属于新平台排查工具，未找到老平台同名页面对齐审计。
- 后续处理：作为内部排查工具单独验收，不按老平台业务页面对齐。
