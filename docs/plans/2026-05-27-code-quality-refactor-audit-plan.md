# 代码质量深度审计与重构计划

日期：2026-05-27  
范围：`backend/src/main/java`、`backend/src/test/java`、`frontend/src`  
目标：按 KISS、YAGNI、SOLID 及项目个人底线原则，清理职责耦合、重复逻辑、误导性状态展示和维护成本高的代码。

## 审计说明

- 本轮先做静态全量索引和高风险文件逐段阅读：当前源码与测试文件共 690 个，不包含 `node_modules`、`dist`、`target` 等生成目录。
- 已重点读取最大文件、同步链路、统计下钻、通用表格、镜像设置页、事实构建、GitLab 链接生成等近期高频变更模块。
- 外部真实 CC/DGM 数据源和内网 GitLab 全量生产数据无法在本地直接读取，因此涉及真实库规模、真实字段缺失率的判断需要在内网测试补充验证。
- PowerShell 输出中中文存在终端编码显示问题，本计划不把终端乱码当成源码编码问题；后续若要确认源码编码，需用 IDE 或 UTF-8 原始字节方式单独验证。

## 铁律

1. 重构期间不得改变用户可见业务语义；必须先用测试或快照锁定当前行为。
2. 每个阶段完成后都必须保持项目可启动、可测试、可回滚。
3. 前端任何反馈类提示必须为中文，不允许把 `QUEUED`、`PARTIAL_SUCCESS`、内部策略枚举等原始实现概念直接暴露给普通用户。
4. 改一个模块时必须检查相邻功能，不允许出现“同步日志实时性修复导致同步策略或 System Hook 体验异常”的连带问题。
5. 简单逻辑三次重复、复杂逻辑两次重复，必须进入统一工具类、组合式函数或领域支持类。

## 违规清单

- **[backend/src/main/java/com/data/collection/platform/service/GitlabExternalDbService.java]**
  - **定位**：`GitlabExternalDbService` 全类；`discoverTableSchema`、同步读取、SQL 构造、连接适配相关方法
  - **违规类型**：SRP / KISS / 相似相溶原则 / 再一再而不再三原则
  - **问题简述**：一个 1186 行服务同时负责直连与 Docker 连接、SQL 构造、schema 探测、数据读取、诊断、超时控制和缓存，任何同步规则调整都可能牵动连接层和查询层。
  - **优化预判**：拆分为 `GitlabSourceConnectionFactory`、`GitlabSourceQueryExecutor`、`GitlabSourceSchemaDiscoveryService`、`GitlabSourceScanSqlBuilder`、`GitlabSourceDiagnosticsService`，并统一表名/字段名引用工具。

- **[backend/src/main/java/com/data/collection/platform/service/FactBuildService.java]**
  - **定位**：`FactBuildService` 全类；`ISSUE_SOURCE_SQL`、`ISSUE_SOURCE_SQL_FALLBACK`、`MERGE_REQUEST_SOURCE_SQL`、事实构建与映射方法
  - **违规类型**：SRP / KISS / YAGNI / 再一再而不再三原则
  - **问题简述**：Issue、MR、SQL 模板、字段映射、任务调度状态更新集中在一个 863 行服务中，且 fallback SQL 与主 SQL 高度重复。
  - **优化预判**：拆分 Issue/MR 两条事实构建链路，抽出 SQL Provider、Row Mapper 和 Build Orchestrator；fallback 改为可组合字段能力检测，避免整段 SQL 复制。

- **[backend/src/main/java/com/data/collection/platform/service/sync/SyncRunTableWorkerService.java]**
  - **定位**：`SyncRunTableWorkerService` 全类；`drainRunTasks*`、`recoverTimedOutTasks`、`executeTask`、`markSuccess`、`reconcileMirrorExtras`
  - **违规类型**：SRP / KISS / 维护为纲原则
  - **问题简述**：表级 worker 同时承担任务租约、并发执行、取消恢复、源表读取、镜像写入、全量补偿删除、游标续批和进度汇总，导致状态语义很容易互相污染。
  - **优化预判**：拆分 `SyncTaskLeaseService`、`SyncTaskExecutor`、`SyncTaskRecoveryService`、`SyncTableContinuationPlanner`、`MirrorReconciliationService`；状态更新只保留在一个事务边界类中。

- **[backend/src/main/java/com/data/collection/platform/controller/GitlabSyncController.java]**
  - **定位**：`GitlabSyncController` 全类；配置、状态、诊断、同步触发、取消、清理、System Hook 等接口
  - **违规类型**：SRP / 接口边界不清 / KISS
  - **问题简述**：一个 Controller 注入 14 个服务并暴露所有同步相关入口，HTTP 层承担了配置脱敏、诊断拼装、运行时文案和提交响应构造。
  - **优化预判**：按职责拆为配置、运行状态、同步动作、诊断、System Hook 五个控制器或门面服务；保留现有 URL 兼容，内部委托新门面。

- **[backend/src/main/java/com/data/collection/platform/service/GitlabIssueLinkService.java]**
  - **定位**：`GitlabIssueLinkService`；`issueUrl`、`mergeRequestUrl`、`loadProjectPath`
  - **违规类型**：KISS / 命名一致性 / 开放封闭原则
  - **问题简述**：类名只表达 Issue，但实际已经负责 Issue 与 MR 链接；新增其他 GitLab 资源时会继续扩大语义偏差。
  - **优化预判**：重命名为 `GitlabResourceLinkService`，保留兼容适配或一次性更新注入点；资源类型用枚举或小型 value object 表达。

- **[backend/src/main/java/com/data/collection/platform/service/statistics/SystemTestDefectSummaryBoardService.java]**
  - **定位**：`buildDefinition`、`doLoadBoard`、`doLoadDetail`、`buildRuleFlowSnapshot`、`AggregateBucket`
  - **违规类型**：SRP / KISS / 拓展预留原则
  - **问题简述**：统计定义、规则说明、明细下钻、聚合桶、指标判定全部在同一服务内，新增一个指标需要同时改定义、聚合、明细和规则说明。
  - **优化预判**：抽出 `DefectSummaryBoardDefinitionFactory`、`DefectMetricCatalog`、`DefectSummaryAggregator`、`DefectRuleExplanationBuilder`，服务只负责编排。

- **[backend/src/main/java/com/data/collection/platform/service/statistics/CustomerIssueDefectSummaryBoardService.java]**
  - **定位**：`buildDefinition`、`loadBoardScopedSources`、`buildRuleFlowSnapshot`、`matchesMetric`
  - **违规类型**：再一再而不再三原则 / SRP / KISS
  - **问题简述**：与系统测试缺陷汇总服务存在大量同构代码，差异主要是范围判定和少量文案，后续修一个下钻/链接问题容易漏掉另一套。
  - **优化预判**：与系统测试汇总共享缺陷统计核心，差异以 `IssueScopePolicy`、`BoardCopy`、`MetricVisibility` 注入。

- **[backend/src/main/java/com/data/collection/platform/service/statistics/*BoardService.java]**
  - **定位**：多个统计看板的 `buildDefinition`、`requestRealtimeRefresh`、`doLoadDetail`
  - **违规类型**：重复造轮子 / 拓展预留原则
  - **问题简述**：统计看板普遍重复定义过滤器、明细列、实时刷新表集合、下钻分页和链接字段，导致“议题编号超链接”这类能力曾多次不一致。
  - **优化预判**：建立统计看板基类之外的组合能力：`StatisticIssueDetailColumnFactory`、`StatisticRealtimeRefreshTables`、`StatisticDetailRecordMapper`、`StatisticBoardDefinitionBuilder`。

- **[frontend/src/views/MirrorSettingsView.vue]**
  - **定位**：组件全体；配置表单、数据源健康、线程策略、System Hook、同步动作、清理弹窗、监控面板编排
  - **违规类型**：SRP / KISS / 维护为纲原则
  - **问题简述**：1050 行页面虽然已抽出部分 controller，但仍把多数据源配置、同步策略、状态展示、健康诊断、System Hook 和清理流程揉在一起。
  - **优化预判**：拆为 `MirrorSourceConfigPanel`、`MirrorSyncStrategyPanel`、`MirrorSystemHookPanel`、`MirrorSourceHealthPanel`、`MirrorDangerZonePanel`，页面只保留选源和保存编排。

- **[frontend/src/views/mirror-settings-helpers.ts]**
  - **定位**：状态标签、类型标签、触发方式、表任务状态、表策略说明、同步消息翻译、清理摘要
  - **违规类型**：相似相溶原则过度集中 / KISS
  - **问题简述**：一个 helper 混合状态字典、日志文案翻译、诊断文案和 HTML 摘要，局部修文案时容易影响无关场景；部分 fallback 仍可能返回内部原始值。
  - **优化预判**：拆成 `sync-status-labels.ts`、`sync-run-message-translator.ts`、`sync-table-diagnostics-labels.ts`、`mirror-purge-summary.ts`，并禁止 UI fallback 返回原始枚举。

- **[frontend/src/components/StatisticBoardView.vue]**
  - **定位**：组件全体；路由、筛选、排序、列拖拽、视图偏好、实时刷新、规则说明、下钻弹窗
  - **违规类型**：SRP / KISS / 拓展预留原则
  - **问题简述**：统计看板容器已使用多个 composable，但仍作为所有交互的集中枢纽，新增“进入页面自动刷新”等体验能力时容易影响排序、下钻、规则说明。
  - **优化预判**：按用户任务拆成 route shell、toolbar shell、table shell、detail shell；自动刷新和实时状态只通过单一 `useStatisticBoardRefreshOrchestrator` 管控。

- **[frontend/src/components/base/BaseRecordTable.vue]**
  - **定位**：搜索、筛选、分页、排序、展开、插槽、自动搜索防抖相关函数
  - **违规类型**：SRP / KISS / 维护为纲原则
  - **问题简述**：通用表格同时处理独立关键词搜索、字段级输入筛选、自动搜索、防抖、分页和插槽能力，后续每个页面的搜索体验都会被同一个复杂组件牵连。
  - **优化预判**：抽出 `useRecordTableSearchState`、`useRecordTableFilterDrafts`、`BaseRecordTableToolbar`，表格主体只负责渲染数据和发出事件。

- **[frontend/src/components/DatabaseBrowserView.vue]**
  - **定位**：`openCollectFormEditor`、编辑弹窗状态、表格浏览状态
  - **违规类型**：SRP / YAGNI / KISS
  - **问题简述**：数据库查看器内嵌 `collect_form_records` 专用编辑能力，使通用数据库浏览和业务表单编辑耦合。
  - **优化预判**：抽出 `CollectFormRecordEditorDialog.vue` 与 `useDatabaseBrowserTableState`；数据库查看器通过表定义注册可编辑能力。

- **[frontend/src/views/IntegrationTestAnalysisView.vue]**
  - **定位**：`syncPageData`、明细路由、导出、事实重建、链接单元格构造
  - **违规类型**：SRP / KISS / 再一再而不再三原则
  - **问题简述**：页面级代码重复实现了项目/阶段范围、明细路由、排序分页、CSV 导出和 GitLab 链接单元格，与统计看板和记录表已有能力重复。
  - **优化预判**：抽出 `useIntegrationTestAnalysisData` 与通用 `buildIssueLinkCell`，明细表链接契约复用统计下钻的同一工具。

- **[frontend/src/types/api.ts]**
  - **定位**：文件全体
  - **违规类型**：KISS / 拓展预留原则
  - **问题简述**：903 行集中 API 类型文件承载同步、统计、评审、系统测试、数据库浏览等多个领域，类型变更容易产生大面积冲突。
  - **优化预判**：拆为 `types/api/sync.ts`、`types/api/statistics.ts`、`types/api/review-data.ts`、`types/api/database.ts`，通过 `types/api/index.ts` 兼容导出。

- **[frontend/src/feature-manifest.ts]**
  - **定位**：页面清单、路由契约、特殊路由契约、查询键定义
  - **违规类型**：SRP / 拓展预留原则
  - **问题简述**：663 行 manifest 同时承载导航数据、路由契约、查询参数白名单和特殊页面定义，新增页面时容易误改全局契约。
  - **优化预判**：拆为领域 manifest 文件和 `route-contracts.ts`，最终由 `feature-manifest/index.ts` 聚合。

## 分阶段重构路线图

### 第 0 阶段：行为冻结与安全网

- 补齐关键链路 characterization tests：同步提交/取消/排队语义、单表刷新、System Hook 唤醒、统计下钻链接、记录表自动搜索。
- 对用户可见提示建立快照或断言：状态、日志类型、错误提示不得出现未翻译内部枚举。
- 建立重构检查清单：每次提交后至少运行前后端单测、类型检查、构建。

### 第 1 阶段：低风险工具与文案层清理

- 拆分 `mirror-settings-helpers.ts`，集中禁止原始枚举外泄。
- 抽出 `GitlabResourceLinkService`，统一 Issue/MR 链接生成命名。
- 抽出通用 `IssueLinkCell`/`buildIssueLinkCell`，让统计下钻、集成测试明细、记录表使用同一链接契约。
- 这一阶段不改变数据库结构、不改变 API URL。

### 第 2 阶段：同步运行状态与 worker 拆分

- 从 `SyncRunTableWorkerService` 中拆出租约、恢复、执行、续批、补偿删除。
- 统一内部状态到用户状态的映射，明确“等待当前同步完成”“已受理”“处理中”“需要查看明细”等非误导文案。
- 对全量同步中触发单表刷新、自动补偿、System Hook 唤醒等组合场景补测试，确保不会显示“失败”或误导性“部分成功”。

### 第 3 阶段：GitLab 源读取与事实构建拆分

- 拆分 `GitlabExternalDbService` 的连接、schema、SQL、读取、诊断职责。
- 拆分 `FactBuildService` 的 Issue/MR 构建链路，减少 fallback SQL 复制。
- 对直连模式优先补真实链路测试，Docker 模式仅保留外网/本地兼容测试。

### 第 4 阶段：统计看板后端复用

- 提炼缺陷汇总通用聚合核心，系统测试与客户问题只保留范围策略和文案差异。
- 统一统计明细列、GitLab 链接、分页排序、规则说明构造。
- 检查所有统计类下钻表，确保议题编号链接、排序、导出、筛选一致。

### 第 5 阶段：前端页面拆分

- 拆分 `MirrorSettingsView.vue` 为多个面板组件。
- 拆分 `StatisticBoardView.vue` 的刷新编排、表格展示、明细弹窗。
- 拆分 `BaseRecordTable.vue` 的搜索/筛选状态，确保高级搜索延迟、手动查询、清空行为符合用户直觉。
- 抽离 `DatabaseBrowserView.vue` 的业务专用编辑弹窗。

### 第 6 阶段：类型与 manifest 模块化

- 拆分 `types/api.ts`，保留聚合导出，降低导入改动面。
- 拆分 `feature-manifest.ts`，按质量看板、评审数据、代码走查、集成测试、系统测试、客户问题、系统设置分组。
- 每次拆分后运行路由访问与导航冒烟测试，避免菜单和历史 URL 失效。

## 验收命令

```powershell
cd D:\projects\data_collection_platform
& 'D:\projects\data_collection_platform\tools\maven\apache-maven-3.9.9\bin\mvn.cmd' -q test
cd frontend
& 'C:\Program Files\nodejs\npm.cmd' test -- --run
& 'C:\Program Files\nodejs\npm.cmd' run typecheck
& 'C:\Program Files\nodejs\npm.cmd' run build
```

## 暂不立即重构的边界

- 不在本轮直接调整业务统计口径。
- 不在未补测试前重写同步执行模型。
- 不为了“拆小文件”引入过度抽象；只有当前已经重复或职责确实混杂的部分才拆。
- 不删除历史兼容路径，除非先确认内网迁移包和既有数据不会依赖。
