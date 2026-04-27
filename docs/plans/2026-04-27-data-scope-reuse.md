# Data Scope Reuse Plan

更新时间：2026-04-27

## 目标

为新平台建立一套统一的“数据上下文切换”复用机制，用来承载老平台里这类能力：

- 系统测试阶段切换
- 集成测试阶段切换
- 客户问题里程碑切换
- 代码走查数据源切换
- 前后版本对比切换

这套能力不作为页面基类实现，而是采用我们当前平台更一致的方式：

- provider 协议
- composable
- 共享组件

## 设计原则

1. 不把“数据上下文切换”混进普通筛选项
2. 保持 query 持久化，和现有 `useRouteTableState` 兼容
3. 先做前端可复用骨架，优先接入已有明确上下文切换需求的页面
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

同时提供：

- `buildScopeOptions()`

用于把 `OptionItemResponse[]` 或 `string[]` 统一映射成可供组件消费的选项结构。

### 3. 状态同步 composable

文件：

- `frontend/src/composables/useDataScope.ts`

职责：

- 从 route query 读取当前上下文值
- 监听选项变化，纠正非法 query
- 在切换上下文时写回 query
- 自动把分页重置到第一页
- 给页面提供当前上下文摘要

### 4. UI 组件

文件：

- `frontend/src/components/data-scope/DataScopeBar.vue`
- `frontend/src/components/data-scope/DataScopeCompareDialog.vue`

职责：

- `DataScopeBar` 承载页级数据上下文切换
- `DataScopeCompareDialog` 承载前后版本/前后阶段对比选择

## 基础组件适配

文件：

- `frontend/src/components/base/BaseRecordTable.vue`

变更：

- 新增 `context-prefix` 插槽

用途：

- 把一级数据上下文放在筛选区之前，避免和普通筛选项混在一起

## 首批接入页面

### 集成测试

文件：

- `frontend/src/views/IntegrationTestAnalysisView.vue`

接入：

- `INTEGRATION_PHASE_SCOPE_PROVIDER`

说明：

- 项目选择保留页面原有控件
- 测试阶段切换改用统一 `DataScopeBar`

### 系统测试列表页

文件：

- `frontend/src/views/SystemTestIssueSearchView.vue`

接入：

- `SYSTEM_TEST_PHASE_SCOPE_PROVIDER`

说明：

- `testingPhase` 从普通筛选提升为页级上下文
- 原列表查询、排序、详情行为不变

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

### 客户问题列表页

文件：

- `frontend/src/views/CustomerIssueRecordsView.vue`

接入：

- `CUSTOMER_MILESTONE_SCOPE_PROVIDER`

说明：

- `milestoneTitle` 从普通条件构建器中提升为页级上下文

## 测试与验证

已验证：

- `npm run build`
- `npm run test -- src/views/system-test-issue-search.mount-smoke.test.ts src/views/system-test-illegal-records.mount-smoke.test.ts src/views/customer-issue-records.mount-smoke.test.ts src/views/customer-issue-illegal-records.mount-smoke.test.ts src/views/integration-test-analysis.mount-smoke.test.ts`

## 当前边界

这次实现优先覆盖“列表页/分析页已经存在明确上下文切换需求”的场景。

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

1. 为 `StatisticBoardView` 增加和 `BaseRecordTable` 对齐的 `context-prefix` 层
2. 将系统测试“轮次预设”从纯 `testingPhase` 平铺切换升级为更贴近老平台的树形分组 provider
3. 为代码走查多看板页接入 `CODE_REVIEW_SOURCE_SCOPE_PROVIDER`
4. 将“前后版本对比”导出场景接入 `DataScopeCompareDialog`
