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

## 解耦总方案

本次解耦不以“文件变小”为目标，而以“职责单一、链路可测、用户语义稳定”为目标。拆分后的代码必须让后续修同步日志、System Hook、统计下钻、搜索体验时，只触碰对应职责模块，不再牵连无关功能。

### 总体分层

```text
前端页面层
  只负责用户任务编排、路由状态、面板组合

前端组件/组合函数层
  负责通用表格、搜索筛选、通知文案、统计看板交互

API 客户端与类型层
  负责接口契约、领域类型、响应归一化

后端 Controller 层
  只负责 HTTP 入参、权限、响应包装

后端 Application Service 层
  负责编排一个完整业务用例，例如启动同步、刷新事实、注册 System Hook

后端 Domain Service 层
  负责稳定领域能力，例如同步任务租约、源表读取、事实构建、统计聚合

Repository / Gateway 层
  负责数据库、GitLab 源库、Docker/直连适配等外部资源访问
```

### 依赖方向

- 页面可以依赖组件、组合函数、API 类型；组件不得反向依赖具体页面。
- Controller 可以依赖 Application Service；不得直接拼装复杂诊断、状态文案或业务流程。
- Application Service 可以依赖多个 Domain Service；Domain Service 之间不得形成循环依赖。
- Gateway 只提供外部访问能力，不承载业务判断。
- 用户提示文案只允许在前端 presentation 层或后端明确的 response DTO 中出现，不允许散落在 worker、mapper、SQL 层。

## 后端解耦方案

### 1. GitLab 源读取解耦

当前问题集中在 `GitlabExternalDbService`：它同时知道连接方式、SQL、schema、分页、重试和诊断。解耦后按以下边界拆分：

| 新模块 | 职责 | 不允许承担的职责 |
| --- | --- | --- |
| `GitlabSourceConnectionFactory` | 根据配置创建直连或 Docker 查询通道 | 不拼 SQL、不判断业务同步策略 |
| `GitlabSourceQueryExecutor` | 执行只读 SQL、处理超时、慢查询日志 | 不决定查哪些表、不改镜像表 |
| `GitlabSourceSchemaDiscoveryService` | 读取源表字段、主键、更新时间列 | 不执行数据同步 |
| `GitlabSourceScanSqlBuilder` | 构造全量、增量、游标续批 SQL | 不连接数据库、不写日志状态 |
| `GitlabSourceDiagnosticsService` | 汇总源库连接和表结构诊断 | 不触发同步任务 |
| `SqlIdentifierSupport` | 统一表名/字段名引用和校验 | 不包含业务表白名单 |

验收标准：

- `GitlabExternalDbService` 不再直接构造所有 SQL，只作为兼容门面或被完全替换。
- 直连模式和 Docker 模式共用同一套 schema/SQL 构造逻辑。
- 表名、字段名引用只允许从 `SqlIdentifierSupport` 进入。

### 2. 同步运行模型解耦

当前问题集中在 `SyncRunTableWorkerService` 和 `GitlabSyncController`：任务状态、执行、恢复、用户提示互相混在一起。解耦后按“提交、调度、执行、展示”拆分：

| 新模块 | 职责 |
| --- | --- |
| `SyncRunCommandController` | 接收启动/取消/重试请求，返回受理结果 |
| `SyncRunStatusController` | 查询当前运行、日志、进度、表级诊断 |
| `SyncRunSubmissionService` | 判断当前是否已有运行、合并/拒绝/受理请求 |
| `SyncRunLifecycleService` | 创建 run、完成 run、取消 run，统一状态流转 |
| `SyncTaskLeaseService` | claim/heartbeat/recover 表级任务租约 |
| `SyncTaskExecutor` | 执行单个表任务 |
| `SyncTableContinuationPlanner` | 决定是否创建续批任务 |
| `MirrorReconciliationService` | 处理全量补偿中多余镜像数据删除 |
| `SyncRunUserStatusMapper` | 内部状态到用户可见状态的唯一映射 |

状态边界：

- 数据库可以保留 `QUEUED`、`PARTIAL_SUCCESS` 等内部状态。
- 前端普通用户视角不得直接显示这些词；统一映射为“等待当前任务完成”“已完成，部分表需要查看明细”等中文短语。
- 同步日志、当前运行、表级任务抽屉必须使用同一个状态映射工具。

验收标准：

- 全量同步中点击单表刷新，不显示“失败”，而显示“已受理，等待当前同步完成”或“当前数据已按已完成部分展示”。
- System Hook 唤醒日志、自动补偿、手动全量、单表刷新使用一致的状态语义。
- `GitlabSyncController` 不再注入十多个服务，复杂拼装迁移到门面服务。

### 3. 事实构建解耦

当前问题集中在 `FactBuildService`：Issue、MR、SQL、mapper、任务状态更新耦合。拆分为两条清晰链路：

```text
FactBuildOrchestrator
  ├─ IssueFactBuildService
  │   ├─ IssueFactSourceQuery
  │   ├─ IssueFactRowMapper
  │   └─ IssueFactWriter
  └─ MergeRequestFactBuildService
      ├─ MergeRequestFactSourceQuery
      ├─ MergeRequestFactRowMapper
      └─ MergeRequestFactWriter
```

设计规则：

- SQL Provider 只负责 SQL 和参数。
- Row Mapper 只负责字段到 fact 对象的转换。
- Writer 只负责 upsert/delete。
- Orchestrator 只负责任务编排、耗时、错误边界。

验收标准：

- Issue 和 MR 任一链路改字段，不影响另一条链路。
- fallback SQL 不再复制整段主 SQL，只通过字段能力检测增减 join/字段。
- 全量同步后事实表与镜像表一致性测试必须覆盖 Issue 和 MR。

### 4. 统计看板解耦

当前统计服务重复实现定义、聚合、规则说明和下钻。解耦为“定义、范围、指标、聚合、明细”五类能力：

| 新模块 | 职责 |
| --- | --- |
| `StatisticBoardDefinitionFactory` | 构造列组、过滤器、明细列 |
| `IssueScopePolicy` | 判断系统测试、客户问题、代码走查等业务范围 |
| `MetricCatalog` | 定义指标 key、名称、是否可下钻、公式说明 |
| `StatisticAggregator` | 对 fact 数据聚合为行/列 |
| `StatisticDetailRecordMapper` | 生成下钻明细，统一议题链接字段 |
| `StatisticRuleExplanationBuilder` | 构造规则说明与样例 |

关键约束：

- “议题编号超链接”必须在 `StatisticDetailRecordMapper` 或统一 link cell 工具中实现，不允许每个看板自己拼。
- 系统测试与客户问题缺陷汇总共享聚合核心，只通过 `IssueScopePolicy` 和文案对象区分。
- 新增指标时只新增 metric 定义和必要聚合逻辑，不复制整套看板服务。

验收标准：

- 所有统计类下钻表的议题编号都能跳转 GitLab。
- 新增一个指标时，改动点可预测，不需要同时搜多个看板服务复制粘贴。
- 规则说明、导出、下钻明细使用同一指标定义。

## 前端解耦方案

### 1. 镜像设置页解耦

`MirrorSettingsView.vue` 拆为容器和面板：

```text
MirrorSettingsView.vue
  ├─ MirrorSourceSelector.vue
  ├─ MirrorSourceConfigPanel.vue
  ├─ MirrorSyncStrategyPanel.vue
  ├─ MirrorSystemHookPanel.vue
  ├─ MirrorSourceHealthPanel.vue
  ├─ MirrorRunMonitorPanel.vue
  ├─ MirrorSyncLogTable.vue
  └─ MirrorDangerZonePanel.vue
```

组合函数边界：

- `useMirrorConfigState`：选源、新增、表单快照、保存状态。
- `useMirrorSyncActions`：全量、增量、全量补偿、取消、连接测试。
- `useMirrorSystemHookState`：System Hook URL、secret、注册状态。
- `useMirrorHealthPresentation`：健康状态文案、颜色、摘要。
- `useMirrorRunPolling`：空闲轮询、运行中轮询、停止条件。

验收标准：

- 切换线程策略不会被状态轮询覆盖回旧值。
- System Hook URL 的显示、复制、可编辑边界明确；直连模式下不误导用户认为平台可自动注册。
- 删除镜像数据、保存配置、启动同步互不影响 loading 状态。

### 2. 统计看板前端解耦

`StatisticBoardView.vue` 拆为四层：

```text
StatisticBoardRouteShell
  负责路由、页面进入刷新、query 同步

StatisticBoardToolbarShell
  负责筛选、查询、导出、规则说明、视图设置

StatisticBoardTableShell
  负责表头、排序、列宽、列拖拽、分页

StatisticBoardDetailShell
  负责下钻弹窗、明细分页、明细排序、链接单元格
```

验收标准：

- 自动刷新只刷新数据和实时状态，不重置用户正在编辑的筛选草稿。
- 下钻弹窗打开时刷新页面能恢复明细状态。
- 规则说明加载失败不影响主表查询。

### 3. 通用表格搜索解耦

`BaseRecordTable.vue` 拆为搜索状态、筛选草稿和表格展示：

| 新模块 | 职责 |
| --- | --- |
| `BaseRecordTableToolbar.vue` | 搜索框、筛选项、查询/重置按钮 |
| `useRecordTableKeywordSearch` | 关键词草稿、防抖、手动提交 |
| `useRecordTableFilterDrafts` | 输入型筛选草稿、提交、清空 |
| `BaseRecordTableBody.vue` | 表格、分页、排序、展开、插槽 |

搜索体验规则：

- 自动搜索只用于明确配置的关键词搜索。
- 高级筛选输入框默认不自动提交，除非页面显式开启。
- 防抖时间统一从配置读取，默认 600ms；用户点击“查询”必须立即执行。

验收标准：

- 用户输入停顿不会过早应用高级筛选。
- 清空单个筛选只清空对应条件，不重置其他条件。
- 记录表、数据库查看、问题查询共享一致的查询节奏。

### 4. 数据库查看器解耦

`DatabaseBrowserView.vue` 中的 `collect_form_records` 编辑能力迁移为注册式扩展：

```text
DatabaseBrowserView.vue
  ├─ useDatabaseBrowserTableState
  ├─ DatabaseTableToolbar.vue
  ├─ DatabaseTableGrid.vue
  └─ table-editors/
      └─ CollectFormRecordEditorDialog.vue
```

设计规则：

- 数据库查看器只知道“当前表是否存在编辑器”。
- 具体业务表编辑器由 `databaseTableEditorRegistry` 注册。
- 未注册编辑器的表只允许查看，不出现无效编辑入口。

验收标准：

- 切换任意表不会加载无关业务编辑状态。
- `collect_form_records` 编辑逻辑可单独测试。
- 数据库查看页面仍保持查看、排序、分页、刷新能力。

### 5. 类型与 manifest 解耦

类型拆分：

```text
frontend/src/types/api/
  ├─ index.ts
  ├─ sync.ts
  ├─ statistics.ts
  ├─ review-data.ts
  ├─ database.ts
  ├─ integration-test.ts
  └─ common.ts
```

manifest 拆分：

```text
frontend/src/feature-manifest/
  ├─ index.ts
  ├─ route-contracts.ts
  ├─ quality-dashboard.ts
  ├─ review-data.ts
  ├─ code-review.ts
  ├─ integration-test.ts
  ├─ system-test.ts
  ├─ customer-issues.ts
  └─ system-settings.ts
```

验收标准：

- 旧导入路径可通过聚合导出兼容，避免一次性大爆炸改动。
- 菜单、权限、历史 URL、页面 query 契约测试通过。

## 实施任务拆分

### Phase A：解耦前安全网

**Task A1：补同步状态语义测试**

- 验收：全量同步运行中触发单表刷新、System Hook、自动补偿时，用户状态文案不显示失败或原始枚举。
- 验证：后端同步服务测试 + 前端日志文案单测。

**Task A2：补统计下钻链接契约测试**

- 验收：所有统计明细返回 `iid` 时必须同时具备可跳转链接字段或统一 link cell。
- 验证：统计 controller/service 测试 + 前端 detail dialog 测试。

**Task A3：补通用表格搜索节奏测试**

- 验收：自动搜索、手动查询、清空筛选互不覆盖。
- 验证：`BaseRecordTable` / 搜索 composable 单测。

### Phase B：低风险公共能力抽取

**Task B1：拆分同步文案与状态映射**

- 依赖：A1
- 触碰文件：`mirror-settings-helpers.ts` 及新增 label/translator 文件。
- 验收：前端无原始内部状态 fallback。

**Task B2：统一 GitLab 资源链接服务**

- 依赖：A2
- 触碰文件：`GitlabIssueLinkService.java` 及引用点。
- 验收：Issue/MR 链接生成测试通过，类名语义正确。

**Task B3：统一前端议题链接单元格**

- 依赖：A2
- 触碰文件：统计下钻、集成测试明细、记录表映射。
- 验收：所有下钻表链接行为一致。

### Phase C：同步模块解耦

**Task C1：拆 Controller 门面**

- 依赖：B1
- 验收：旧 URL 不变，Controller 不再直接拼复杂诊断和提交响应。

**Task C2：拆任务租约与恢复**

- 依赖：A1
- 验收：claim、heartbeat、timeout recover 可单测。

**Task C3：拆表任务执行与续批规划**

- 依赖：C2
- 验收：小表、101 条超批、无更新时间列、取消场景均通过测试。

**Task C4：拆全量补偿删除逻辑**

- 依赖：C3
- 验收：缺少数据补齐、多余镜像删除、错误数据纠正均有测试。

### Phase D：源读取与事实层解耦

**Task D1：拆源连接与执行器**

- 依赖：C1
- 验收：直连模式与 Docker 模式共用执行接口。

**Task D2：拆 schema 探测与 SQL Builder**

- 依赖：D1
- 验收：表名/字段名统一引用，schema 缺失诊断不触发同步。

**Task D3：拆 Issue/MR 事实构建链路**

- 依赖：D2
- 验收：Issue/MR 任一链路失败不污染另一链路状态。

### Phase E：统计与前端页面解耦

**Task E1：抽统计明细链接与列定义工厂**

- 依赖：B3
- 验收：所有统计下钻表链接统一。

**Task E2：抽缺陷汇总聚合核心**

- 依赖：E1
- 验收：系统测试与客户问题汇总共用核心聚合，口径不变。

**Task E3：拆镜像设置页面板**

- 依赖：B1、C1
- 验收：线程策略、System Hook、同步动作、清理弹窗互不覆盖状态。

**Task E4：拆统计看板前端 shell**

- 依赖：E1
- 验收：筛选、排序、下钻、规则说明、自动刷新互不干扰。

**Task E5：拆通用表格搜索与筛选**

- 依赖：A3
- 验收：高级搜索体验稳定，手动查询立即生效。

### Phase F：收尾模块化

**Task F1：拆 API 类型**

- 验收：旧导入兼容，`npm run typecheck` 通过。

**Task F2：拆 feature manifest**

- 验收：菜单、路由、权限、历史 URL 冒烟测试通过。

## 每阶段回归清单

- 后端：同步启动、取消、全量补偿、单表刷新、System Hook 唤醒、事实构建。
- 前端：登录、导航、镜像设置、同步日志、数据镜像监控、数据库查看、统计看板、统计下钻、规则说明、导出。
- 体验：通知可手动关闭、中文提示、状态不误导、加载不抖动、用户编辑中的草稿不被轮询覆盖。
- 构建：后端测试、前端测试、类型检查、生产构建全部通过。
