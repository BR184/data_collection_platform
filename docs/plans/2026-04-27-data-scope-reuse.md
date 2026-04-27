# Data Scope Reuse Plan

更新时间：2026-04-27

## 目标

为新平台建立一套统一的“数据上下文切换”复用机制，用来承载老平台中这类能力：

- 系统测试阶段切换
- 集成测试阶段切换
- 客户问题里程碑切换
- 代码走查数据源切换
- 前后版本对比切换

本方案不采用页面基类或抽象类，而是沿用新平台当前更一致的实现方式：

- provider 协议
- composable
- 共享组件

## 设计原则

1. 不把“数据上下文切换”混进普通筛选项
2. 保持 query 持久化，并与 `useRouteTableState` 兼容
3. 优先让已有明确上下文需求的页面先接入
4. 旧平台功能只参考行为，不迁移旧实现

## 新增复用骨架

### 1. 类型协议

文件：

- `frontend/src/types/data-scope.ts`

职责：

- 定义 `DataScopeProvider`
- 定义 `DataScopeOption`
- 定义 `DataScopeMode`

### 2. Provider 工具

文件：

- `frontend/src/composables/data-scope-providers.ts`

首批内置 provider：

- `SYSTEM_TEST_PHASE_SCOPE_PROVIDER`
- `INTEGRATION_PHASE_SCOPE_PROVIDER`
- `CUSTOMER_MILESTONE_SCOPE_PROVIDER`
- `CODE_REVIEW_SOURCE_SCOPE_PROVIDER`

辅助方法：

- `buildScopeOptions()`

用于把 `OptionItemResponse[]` 或 `string[]` 统一映射成可供组件消费的选项结构。

### 3. 状态同步 composable

文件：

- `frontend/src/composables/useDataScope.ts`
- `frontend/src/composables/shell-data-scope.ts`

职责：

- 从 route query 读取当前上下文值
- 监听选项变化，纠正非法 query
- 在切换上下文时写回 query
- 自动把分页重置到第一页
- 向主模块壳注册当前页面的数据上下文

### 4. UI 组件

文件：

- `frontend/src/components/data-scope/DataScopeBar.vue`
- `frontend/src/components/data-scope/DataScopeCompareDialog.vue`

职责：

- `DataScopeBar` 承载页级数据上下文切换
- `DataScopeCompareDialog` 承载前后版本/前后阶段对比选择

位置约束：

- 数据上下文不再放在各页面自己的工具栏里
- 统一挂载到主模块壳 `shell-content` 的右上角
- 只有当前页面注册了上下文时才显示
- 未注册上下文的页面保持现状，不出现占位元素

### 5. 基础组件适配

文件：

- `frontend/src/components/base/BaseRecordTable.vue`

变更：

- 新增 `context-prefix` 插槽，作为过渡层保留

说明：

- 当前最终采用壳层统一挂载
- 保留该插槽，便于后续局部场景扩展

## 首批接入页面

### 集成测试

文件：

- `frontend/src/views/IntegrationTestAnalysisView.vue`

接入：

- `INTEGRATION_PHASE_SCOPE_PROVIDER`

说明：

- 项目选择保留页面原有控件
- 测试阶段切换改用统一 `DataScopeBar`
- 展示位置在主模块壳右上角

### 系统测试列表页

文件：

- `frontend/src/views/SystemTestIssueSearchView.vue`

接入：

- `SYSTEM_TEST_PHASE_SCOPE_PROVIDER`

说明：

- `testingPhase` 从普通筛选提升为页级上下文
- 原列表查询、排序、详情行为不变
- 展示位置在主模块壳右上角

### 非法数据通用页

文件：

- `frontend/src/views/issue-illegal-records/IssueIllegalRecordsPage.vue`
- `frontend/src/views/issue-illegal-records/issue-illegal-records-types.ts`

接入能力：

- 页面配置支持 `scopeProvider`
- 页面配置支持 `buildScopeOptions`

已落地页面：

- `frontend/src/views/SystemTestIllegalRecordsView.vue`
- `frontend/src/views/CustomerIssueIllegalRecordsView.vue`

说明：

- 页内不再自行渲染上下文条
- 由通用页注册到壳层右上角

### 客户问题列表页

文件：

- `frontend/src/views/CustomerIssueRecordsView.vue`

接入：

- `CUSTOMER_MILESTONE_SCOPE_PROVIDER`

说明：

- `milestoneTitle` 从普通条件构建器中提升为页级上下文
- 展示位置在主模块壳右上角

### 代码走查多元看板

文件：

- `frontend/src/views/CodeReviewMultiBoardView.vue`

接入：

- `CODE_REVIEW_SOURCE_SCOPE_PROVIDER`

说明：

- 用于承载老平台 `CC / DGM` 类型的数据源切换能力
- 展示位置在主模块壳右上角
- 当前数据基于 `merge_request_fact.source_instance`
- 页面展示代码走查的合并请求数、缺陷数、注释率、走查时长，以及模块 / 责任人分布

## 本地演示数据

文件：

- `scripts/seed-local-code-review-source-demo.sql`

说明：

- 为本地 `merge_request_fact` 补入 `cc` / `dgm` 两个 `source_instance`
- 用于让代码走查多元看板与右上角数据源切换在本地可直接体验
- 不改动镜像同步链路，只用于本地开发与验收

## 命名说明

页面上出现“测试阶段”还是“里程碑”，不是命名不统一，而是业务上下文本身不同：

- 系统测试、集成测试：上下文是 `testingPhase`
- 客户问题：上下文是 `milestoneTitle`
- 代码走查：上下文是 `source`

统一的是切换模型和壳层展示位置，不是强行把所有业务都叫成同一个字段名。

## 测试与验证

已验证：

- `npm run build`
- `npm run test -- src/views/system-test-issue-search.mount-smoke.test.ts src/views/system-test-illegal-records.mount-smoke.test.ts src/views/customer-issue-records.mount-smoke.test.ts src/views/customer-issue-illegal-records.mount-smoke.test.ts src/views/integration-test-analysis.mount-smoke.test.ts`
- `npm run test -- src/views/code-review-multi-board.mount-smoke.test.ts`
- `mvn -q "-Dtest=CodeReviewControllerTest,CodeReviewMultiBoardServiceTest" test`

## 当前边界

本次实现优先覆盖已有明确上下文切换需求的列表页、分析页和代码走查看板。

尚未接入但已适配方案的页面：

- `question-metrics-home`
- `question-metrics-delay-analysis`
- `question-metrics-defect-cause`
- `question-metrics-phase-statistics`
- `customer-issues-home`
- `customer-issues-response-efficiency`
- `customer-issues-issue-by-function`

这些统计看板页下一步建议接入 `DataScopeProvider`，但需要先把 `StatisticBoardView` 的 route/filterGroup 注入路径统一成“页级上下文 + 条件构建器”双层结构。

## 后续建议

1. 为 `StatisticBoardView` 增加与 `BaseRecordTable` 对齐的壳层数据上下文接入点
2. 将系统测试“轮次预设”从纯 `testingPhase` 平铺切换升级为更贴近老平台的树形分组 provider
3. 让代码走查非法数据页也可选是否按 `source` 缩小统计范围
4. 将“前后版本对比”导出场景接入 `DataScopeCompareDialog`
