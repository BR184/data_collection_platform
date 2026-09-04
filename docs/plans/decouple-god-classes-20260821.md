# 进度与中间物

- 状态：方案已获用户确认（含同事模块冻结守卫）；阶段一至阶段六已完成，解耦代码已完成编译、定向/全量回归、前端验证、真实本地链路和仓库门禁验证。
- 已完成：五个目标文件逐行深读；全部协作者、消费者、测试保护面、死代码与冻结边界已用代码证据核实。
- 已完成：`GitlabExternalDbService`、`IssueFactRecordRepository`、`CodeReviewIllegalRecordService`、`BaseRecordTable.vue`、`MirrorSettingsView.vue` 已按计划完成职责外移，公开 API、数据格式和页面模板契约未变。
- 验证：后端 Java 21 编译、16 个定向测试类、statistics 冻结区 24 个测试类、全局 Checkstyle/SpotBugs、Flyway/文本/产物/API 边界门禁通过；前端 122 个测试文件 446 项、TypeScript、ESLint、生产构建通过。
- 验证：在新建隔离数据库 `qaflex_clean` 上全量 `mvn test` 通过 260 个套件、1169 项，0 failures、0 errors、1 项条件跳过；历史 `qaflex` 库的迁移错误已归因为旧 schema 污染，不修改业务代码迁就该库。
- 验证：真实 LDAP + `18080` 后端 + `18181` 前端 + Docker GitLab `gitlab-data-web-1` 链路通过；全量 300/300、事实刷新 6/6 写入 15392 行、增量 20/20；25 个只读 API 烟测及四个主要业务页面加载通过。
- 范围：当前工作树另含 statistics 目录 7 个文件的 11 行未使用 import 删除，属于合并后的既有同事改动；本工作单元未改变其业务逻辑，也不回退该差异，已随全量验证覆盖。
- 范围：工作树同时包含客户问题 325 里程碑目录修复及其 `V20260821_01` 迁移；该修复归属 `fix-customer-issue-milestone-catalog-20260821.md`，本计划仅记录与本轮合并、编译和收口验证的交互，不将其视为解耦阶段新增迁移。

## 阶段二完成记录（2026-08-21）

- 改动：`IssueFactRecordRepository` 1135→约 330 行薄委托；抽取 `IssueFactRecordConditionBuilder`（buildPageQuery + 全部 appendXxx + 三段候选值谓词，纯函数 @Component）、`IssueFactRecordSortSupport`（26 列排序白名单）、`IssueFactRecordRowMapper`（46 列行映射）、`IssueFactFilterValuesQuerySupport`（两段候选值 SQL 字面量 + splitAggregatedValues）。repository 公开方法签名与 SQL 语义逐字符保真；statistics 包 3 个冻结调用点零改动。
- 测试：IssueFactRecordRepositoryTest 5 项断言零改动通过（仅装配行适配新构造器）；CustomerIssueRecordServiceTest 13 项、SystemTestIllegalRecordServiceTest 5 项、SystemTestIssueSearchServiceTest 9 项、CustomerIssueIllegalRecordServiceTest 5 项全部通过（37 项定向）。Checkstyle/SpotBugs 零违规。
- 冻结守卫：statistics 目录全程干净。

## 阶段一完成记录（2026-08-21）

- 改动：`GitlabExternalDbService` 重写为纯注入门面（582→约 300 行），删除内部 SourceAdapter 双类、13 个测试桥接方法、死方法 `compensationScan`/`buildRecordKey`；新增 `GitlabSourceQueryDispatcher` 承接 DIRECT/DOCKER 路由；`parseDockerRows` 及重试包装移入 `GitlabDockerPsqlExecutor`（新增 `queryRows`/`queryScriptRows`/`testConnection`）；10 个协作者升为 Spring Bean（@Component，包私有不变）；`GitlabDirectJdbcExecutor` 由 AutoCloseable 改为 DisposableBean 并承接配置变更事件监听（原在门面）；`GitlabSourceScanSqlBuilder` 新增 `toJsonCursor`；`GitlabSourceSchemaDiscoveryService` 新增 Dispatcher 生产构造器并保留 BiFunction 测试构造器。
- 测试：新建 `GitlabSourceTimestampNormalizerTest`（4 项，承接门面字符串时间语义）；重写 `GitlabExternalDbServiceTest` 为注入构造 + 门面独有行为 5 项；更新 Docker/Direct 集成测试装配。定向 15 个测试类 70 项全部通过；Checkstyle/SpotBugs 零违规。
- 冻结守卫：statistics 目录全程干净。
- 发现的既有问题（不在本工作单元修复）：`GitlabSourceSchemaDiscoveryService.java:75` 存在历史乱码错误文案"閺堫亜褰傞悳鐗堢爱鐞涖劎绮ㄩ弸?"，需用户确认原文后单独修复。

## 恢复线索

- 当前阶段：阶段一至阶段六已完成；源码验证和本地真实链路验证完成，下一步为用户/AI 经理复核工作树差异及内网部署验收。
- 恢复后首条命令：`git status --porcelain -- backend/src/main/java/com/data/collection/platform/service/statistics/`；当前已知例外是 7 个文件共 11 行 import-only 删除，出现业务逻辑差异才停止并报告用户。
- 当前计划：`docs/plans/decouple-god-classes-20260821.md`；上一工作单元：`docs/plans/code-audit-refactor-20260821.md`（已完成并暂停）。

## 目标与边界

- 用户原始需求：参照同事的 statistics 改造问题清单形式，对除同事负责模块外的整个项目出具解耦改造方案；先调研后动手。
- 成功标准：每个目标有文件级证据、明确的新旧结构映射、行为不变量、测试策略和验收命令；方案可直接分阶段执行。
- 明确禁止：
  - 修改 `backend/src/main/java/com/data/collection/platform/service/statistics/` 下任何文件（同事负责：看板复制粘贴工厂 + SQL/内存筛选双轨）。
  - 触碰同事问题二的任何内容：看板文件内的 `matchesFilterGroup/matchesSetOperator/matchesCondition` 一族、看板 Excel 明细导出、快照刷新、实时状态、规则流重复实现，一律不读改、不抽公共层、不加契约测试。
  - 改变业务口径、公开 HTTP API、数据库迁移、事实发布代际、导出列序、快照键格式。
  - 为旧实现保留别名、转发函数或双轨路径；被替代实现必须删除。
  - 触碰兼容模式临时页面/服务（LegacyDatabaseSettingsView 等），它们整体等待删除，不做解耦投资。

## 同事模块冻结守卫（每阶段开始前执行）

- 阶段前置命令：`git status --porcelain -- backend/src/main/java/com/data/collection/platform/service/statistics/`，输出必须为空；非空则立即停止并报告用户。
- statistics 包对 IssueFactRecordRepository 的 3 个调用点（IssueFactBoardRuntimeSupport.findByFilters、CustomerIssueDefectSummaryBoardService.findCustomerIssueRecordFilterValues、SystemTestDefectSummaryBoardService.findForFilterOptions）签名与行为视为只读公共契约：本工作单元只允许重构 repository 内部实现，禁止修改这 3 个方法的参数、返回类型与语义；statistics 包文件本身零改动。
- 前端统计看板组件（StatisticBoardView/StatisticFilterBuilder/StatisticBoardDetailDialog/StatisticBoardToolbar）列入排除区，不修改。
- 当前状态例外：工作树保留合并后既有的 7 个 statistics 文件 11 行 import-only 未提交差异；该差异不属于本轮解耦，不能回退。

## 约束与背景

- 后端 Java 21/Spring Boot 3.5/MyBatis-Plus/Flyway；前端 Vue 3/TS/Vite/Vitest。
- 本机无 mvn/java：后端验证走 Docker `maven:3.9-eclipse-temurin-21`；前端 Node 24 本地。
- 工作树含合并及用户已有未提交改动；本工作单元不回退、不提交推送（除非用户另行指示）。
- 项目维护文本中文，标识符英文；公共方法补 Javadoc/JSDoc。

## 证据与根因（调研结论）

### 范围判定

- 同事冻结区实测：`service/statistics/` 48 文件约 1.49 万行，11 个看板服务各 800~1400 行；statistics 包反向调用本工作单元目标的 3 个方法（见接口契约）。
- 全仓扫描：后端最大非统计文件为 `FactBuildService`(1471)、`CodeReviewIllegalRecordService`(1304)、`IssueFactRecordRepository`(1135)；前端最大为 `BaseRecordTable.vue`(1527)、`MirrorSettingsView.vue`(1374)。`GitlabExternalDbService`(582) 经上轮治理已是薄门面但残留三类问题。

### 目标一：GitlabExternalDbService（582 行）

- 结构事实：SQL 构建（GitlabSourceScanSqlBuilder）、主键存在性（GitlabPrimaryKeyExistenceQueryBuilder）、权威范围（GitlabAuthoritativeScopeQueryBuilder）、重试（GitlabSourceQueryRetryPolicy）、连接设置（GitlabSourceConnectionSettings）、Docker 执行（GitlabDockerPsqlExecutor）、JDBC 值归一（GitlabJdbcValueNormalizer）、DIRECT 执行（GitlabDirectJdbcExecutor）、元数据（GitlabSourceMetadataSupport）、schema 发现（GitlabSourceSchemaDiscoveryService）均已是包私有独立类，且各有专属测试（ScanSqlBuilder 6 项、RetryPolicy 5 项、ConnectionSettings 4 项、JdbcValueNormalizer 8 项、MetadataSupport 8 项等）。
- 问题 1（DIP）：构造函数手动 `new` 全部 10 个协作者（[GitlabExternalDbService.java:53-75]），与上轮 FactBuildService 已修复的反模式相同；`SyncThreadBudgetResolver` 已是 `@Service` 却仍被手动 new。
- 问题 2（SRP）：`parseDockerRows`（477-489 行）内嵌门面，属 Docker 执行器职责；`executeDockerQuery`/`executeDockerScriptQuery` 的重试包装同样留在门面。
- 问题 3（测试穿透）：13 个包私有桥接方法生产调用者为零，仅测试使用，且行为已被协作者测试覆盖——buildXxxSql×8（ScanSqlBuilderTest 覆盖）、resolveUpdatedAtColumn/resolveRowStrategy/buildSchemaFingerprint（MetadataSupportTest 覆盖）、normalizeJdbcValue（JdbcValueNormalizerTest 覆盖）、buildJdbcUrl（ConnectionSettingsTest 覆盖同名断言）、executeExternalQueryWithRetry/computeExternalQueryRetryDelayMs/isRetryableExternalFailure（RetryPolicyTest 同名测试覆盖）。
- 死代码：`compensationScan`、`buildRecordKey` 全仓零引用；`toOptions` 两个变体在 CodeReviewIllegalRecordService 中亦为零调用（见目标三）。
- 测试穿透面：GitlabExternalDbServiceTest 以 `(properties, objectMapper)` 构造并调用桥接方法；DatabaseBrowserServiceTest 等 4 个测试用 Mockito mock 门面（构造器变化不受影响）；DirectIntegrationTest 用真实构造走 Testcontainers。

### 目标二：IssueFactRecordRepository（1135 行）

- 结构事实：单一 `@Service` 承载 46 列 SELECT 常量、7 个公开查询入口、`buildPageQuery` 编排 + 约 25 个 `appendXxx` 条件构建器、排序白名单（26 列映射）、`mapIssueFact` 行映射、两段巨型筛选候选值 SQL（系统测试 158-240 行、客户问题 284-401 行）及聚合值拆分。
- 根因：条件构建、排序白名单、行映射、候选值查询四种职责无边界地堆在一个类；新增筛选维度只能继续改同一文件。
- 消费者：AbstractIssueFactRecordListService 及其子类（系统测试/客户问题记录页）、SystemTestIssueSearchService、statistics 包 3 处（IssueFactBoardRuntimeSupport.findByFilters、CustomerIssueDefectSummaryBoardService.findCustomerIssueRecordFilterValues、SystemTestDefectSummaryBoardService.findForFilterOptions）。
- 测试保护：IssueFactRecordRepositoryTest 5 项（关键词回退、issueIid 检索类型、客户成员存在性、CC 产品筛选语义、计划合并分支分隔符契约），以 mock IssueFactQueryService 捕获 SQL 断言——公开行为不变则测试不动。
- 边界提醒：记录页同样存在 SQL（本类）与内存（AbstractIssueFactRecordListService.applyBaseFilters）双轨，但该双轨属于记录页回退路径，不是同事负责的 statistics 双轨；本方案只拆结构不统一语义，避免越界。

### 目标三：CodeReviewIllegalRecordService（1304 行）

- 结构事实：列表查询（快照缓存 + SQL 快路径 + 内存慢路径）、CSV 导出、Excel 导出（writeLegacySheet 一族约 200 行）、筛选选项装配（toProjectOptions/toLegacyOptions/toCodeReviewProjectNameOptions 等约 150 行）、实时刷新协调、规则预览、视图/响应映射（toView 42 字段、toResponse 42 字段）集中一类。
- 死代码实锤：`exportRecordsCsv`（224-314 行）生产与测试零引用（Controller 只调 exportRecordsWorkbook）；`buildRuleFlowSteps`/`sampleIllegalRecords`/`toIllegalRecordSample`（1057-1092、1146-1155 行）零引用；`toOptions` 两个重载（1216、1291 行）零引用。
- 兼容模式分支：`codeReviewCompatibilityReadEnabled()` 分支散布约 8 处（activeLoader、toView、displayCommitRate、mergeRequestLink、defaultLegacyRepositoryName、requestRealtimeRefresh、refreshSingleRecord、shouldExportAllCodeReviewSheet）。该代码整体标注“老平台交接完成后删除”，不做抽象投资，仅物理聚拢便于未来一次删除。
- 消费者：CodeReviewController（8 个端点）、CodeReviewDgmGitlabProjectOptionService、LegacyPlatformFormalImportService。
- 测试保护：CodeReviewIllegalRecordServiceTest 8 项（含 shouldKeepListRecordsBehaviorAfterRefactor 回归锚点）+ SourceLoaderTest + QuerySupportTest。

### 目标四：BaseRecordTable.vue（1527 行）

- 结构事实：script 约 890 行 = 列宽引擎（约 230 行纯函数：effectiveColumnWidth 一族 + 文本宽度估算 + 列型判定）、快捷筛选摘要/排序文案（约 90 行纯函数）、筛选草稿状态机（keywordDraft/inputFilterDrafts/localFilterValues + commit/reset/guard 约 180 行，与 emit 纠缠）、视口宽度 ResizeObserver（约 20 行）；模板约 295 行、样式约 340 行。
- 已有基础：定时器、浮动滚动条、粘性表头已是独立 composable；列宽引擎是最大的未拆纯逻辑块。
- 消费者：5 个视图（CodeReview/CustomerIssue/SystemTest 记录页、IssueIllegalRecordsPage、ReviewDataManagementView）。
- 测试保护：base-record-table.test.ts 9 项 + 各页面 mount smoke。

### 目标五：MirrorSettingsView.vue（1374 行）

- 结构事实：script 约 623 行中已有 8 个 controller composable（状态、白名单、同步动作、System Hook 注册、事实重建、清理对话框、状态展示、表现层）；剩余内联块为健康状态展示 computed 一族（currentSourceHealth/Tone/Text/Summary/LatestSyncStatusText/HealthMessageText/FactLaggingDomains/MissingTablesPreview 约 110 行纯映射）、formSnapshot 指纹（约 45 行纯函数）、诊断加载编排（loadMirrorSection/loadDeferredMirrorSections/loadConfigs/loadSourceHealth/loadTableSyncDiagnostics/retryFailedRun 约 110 行）、System Hook 状态标签 computed（约 40 行）。
- 测试保护：mirror-settings.mount-smoke.test.ts 4 项 + 6 个 controller 各自测试 + ux-interaction-regressions.test.ts。

### 排除项与后续清单

- 冻结：`service/statistics/**`（同事）；前端统计看板组件（StatisticBoardView/StatisticFilterBuilder/StatisticBoardDetailDialog，与同事看板改造存在契约联动，待其落地后再评估）。
- 兼容模式整体删除区不解耦：LegacyDatabaseSettingsView(1091)、CodeReviewMatchModeSyncService(902)、CodeReviewMatchModeMongoReviewSyncService(665)、CodeReviewMatchModeConfigService(775)。
- Tier B 后续单独立项：FactBuildService(1471，按“读取/映射、派生、写入/索引、编排”深拆，需先补行为测试)、CustomerIssueRecordService(826)、IssueScopeDefinitionService(898)、QualityRdAnalyticsDetailQueryService(809)、ReviewDataRecordReadRepository(657)、BiCodingCalculator(944，BI 域需按 bi-dashboard 文档路由单独处理)。

## 方案与步骤

### 阶段一：GitlabExternalDbService 注入化与 Docker 解析归位（风险低）【已完成】

0. 冻结守卫：执行同事模块冻结守卫命令，确认 statistics 目录干净后才开始。

1. 新增 `GitlabSourceQueryDispatcher`（@Component）：依赖 GitlabDirectJdbcExecutor + GitlabDockerPsqlExecutor，提供 `testConnection`/`query`/`scriptQuery`，内部完成 DIRECT/DOCKER 路由与 Docker 路径重试包装；删除门面内部类 DirectJdbcSourceAdapter/DockerPsqlSourceAdapter 与 sourceAdapters Map。
2. `parseDockerRows` 移入 GitlabDockerPsqlExecutor：新增 `queryRows(config, sql)`/`queryScriptRows(config, script)` 返回解析后行集合；ERROR:/FATAL: 行抛 BizException 语义不变。
3. 协作者升为 Spring Bean（@Component，包私有不变）：ScanSqlBuilder、PrimaryKeyQueryBuilder、AuthoritativeScopeQueryBuilder、RetryPolicy、ConnectionSettings、DockerPsqlExecutor、JdbcValueNormalizer、DirectJdbcExecutor、MetadataSupport、SchemaDiscoveryService；SchemaDiscoveryService 构造参数由 `BiFunction` 改为注入 Dispatcher。GitlabDirectJdbcExecutor 作为单例 Bean 由 Spring 推断 close() 销毁，门面不再实现 DisposableBean；配置变更事件监听器移至 DirectJdbcExecutor 自身。
4. 删除 13 个桥接方法与死方法 compensationScan/buildRecordKey；保留 fullTableScan/incrementalScan（DirectIntegrationTest 的合法端到端入口）。
5. 新增 `GitlabSourceTimestampNormalizerTest`，把门面 extractUpdatedAt 的两条字符串时间语义测试迁到归一器直属测试；更新 GitlabExternalDbServiceTest 为新构造注入并删除已被协作者测试覆盖的重复断言。
6. 验收：`GitlabExternalDbService*Test`、`DatabaseBrowserServiceTest`、`SourceConnectionTesterTest`、`SourceMetadataInspectorTest`、`SyncRunTableDiagnosticsServiceTest`、全部协作者测试定向通过；Checkstyle/SpotBugs 零违规。

### 阶段二：IssueFactRecordRepository 职责拆分（风险中低，statistics 三签名冻结）【已完成】

0. 冻结守卫：执行同事模块冻结守卫命令；确认 statistics 包 3 个调用点文件未发生任何改动。

1. 抽取 `IssueFactRecordConditionBuilder`（包私有 @Component）：`buildPageQuery` + 全部 appendXxx → 纯函数 `IssueFactRecordPageQuery → QueryParts(where, args)`；appendScope/testingPhaseColumn 等作用域逻辑随迁。
2. 抽取 `IssueFactRecordSortSupport`：SORT_COLUMNS 白名单 + sortColumn/sortOrder/nullsClause。
3. 抽取 `IssueFactFilterValuesQuerySupport`：两段候选值 SQL 字面量 + splitAggregatedValues + 两个 FilterValues record 的行映射；repository 公开方法保留为薄委托。
4. `mapIssueFact` 迁至 `IssueFactRecordRowMapper`（或并入 ValueSupport 所在层，实施时按现有依赖方向定）。
5. 行为锁定：IssueFactRecordRepositoryTest 5 项不改一字通过；statistics 包 3 个调用点签名与返回不变。
6. 验收：仓库测试 + AbstractIssueFactRecordListService 相关服务测试 + statistics 无改动编译通过；定向 Checkstyle/SpotBugs。

### 阶段三：CodeReviewIllegalRecordService 减负（风险中）【已完成】

0. 冻结守卫：执行同事模块冻结守卫命令。本类对 `entity/statistics` 的依赖仅限共享响应 DTO（StatisticBoardRuleExplanationResponse 等），该实体包不属于同事模块，保持只读引用不变。

1. 删除死代码：exportRecordsCsv、buildRuleFlowSteps/sampleIllegalRecords/toIllegalRecordSample、toOptions 两个重载（保留 CSV_DATE 因 Excel 导出仍在用）。
2. 抽取 `CodeReviewIllegalRecordExcelExporter`（@Component）：loadAllRows/loadAllRowsWithoutIllegalType/writeLegacySheet 一族 + ExportStyles + LEGACY_EXPORT_HEADERS；服务保留 exportRecordsWorkbook 入口。
3. 抽取 `CodeReviewIllegalRecordFilterOptionAssembler`（@Component）：REQUEST_TYPE_OPTIONS/LEGACY_ILLEGAL_TYPE_OPTIONS/LEGACY_EXTRA_PROJECT_NAME_OPTIONS 常量 + toProjectOptions/projectOptionLabel/toLegacyOptions/toCodeReviewProjectNameOptions/toCodeReviewRepositoryNameOptions/isHiddenCodeReviewProjectName/isMatchModeCcSource。
4. 抽取 `CodeReviewIllegalRecordResponseMapper`：toView/toResponse/toRulePreviewSample + displayCommitRate/legacyCommitRate/mergeRequestLink。
5. 兼容模式分支就地保留不抽象；快照刷新协调（PageRecordSnapshotRefresher 契约）留在服务本体。
6. 验收：CodeReviewIllegalRecordServiceTest 8 项不改一字通过；Controller 8 端点冒烟（现有 mock 测试覆盖）；Checkstyle/SpotBugs。

- 实施/验证：服务约 1304 行降至约 714 行；Excel 导出、筛选选项和响应映射分别外移到 3 个组件，死代码删除；交接报告列出的定向后端回归、Checkstyle 和 SpotBugs 均通过。

### 阶段四：BaseRecordTable.vue 纯逻辑外移（风险低）【已完成】

0. 冻结守卫：确认本阶段不触碰任何统计看板页面/组件（StatisticBoard* 一族不在消费者清单中，天然隔离）。

1. 新增 `frontend/src/components/base/column-width-engine.ts`：列宽引擎全部纯函数（入参 column/rows，无组件状态）；组件内改为调用。
2. 新增 `record-table-display.ts`：quickFilterSummaryChips 构建、formatQuickFilterSummaryValue、readableSortFieldLabel/readableSortDirection。
3. 为两个新模块补直接单元测试（列宽上下界、人名列、叙述列弹性权重、文本宽度估算、摘要值格式化）。
4. 筛选草稿状态机本轮不动（与 emit/guard 纠缠深、收益比低），列入观察。
5. 验收：base-record-table.test.ts 9 项 + 5 个消费页 mount smoke + typecheck + 定向 ESLint + 生产构建。

- 实施/验证：列宽引擎和表格展示文案外移到 2 个纯函数模块并新增单测；BaseRecordTable 原有测试、全量前端测试、TypeScript、ESLint 和生产构建通过。

### 阶段五：MirrorSettingsView.vue 展示层收敛（风险低）【已完成】

0. 冻结守卫：执行同事模块冻结守卫命令（镜像设置页与 statistics 无依赖，例行校验）。

1. 新增 `useMirrorSourceHealthPresentation.ts`：健康状态 Tone/Text/Summary/LatestSyncStatusText/HealthMessageText/FactLaggingDomains/MissingTablesPreview + System Hook 标签/文案 computed，输入为响应式 health/registration 引用。
2. 新增 `mirror-config-fingerprint.ts`：formSnapshot 纯函数 + 单测（字段归一化、白名单排序、默认值）。
3. 新增 `useMirrorDiagnosticsController.ts`：loadMirrorSection/loadDeferredMirrorSections/loadSourceHealth/loadTableSyncDiagnostics/retryFailedRun，沿用现有 controller 命名与错误通知注入模式。
4. 模板与样式不动；页面只组合。
5. 验收：mount smoke 4 项 + 6 个既有 controller 测试 + 新增单测 + typecheck + 构建。

- 实施/验证：健康状态展示、配置指纹和诊断重试编排外移到 3 个模块并新增 9 项单测；页面 4 项挂载冒烟和前端全量测试通过，模板与接口未变。

### 阶段六：收口【已完成】

1. 后端 Java 21 编译、交接报告中的 16 个定向测试类、statistics 冻结区 24 个测试类、全局 Checkstyle/SpotBugs 已通过；在隔离库 `qaflex_clean` 上全量 `mvn test` 通过 1169 项（0 failures、0 errors、1 项条件跳过）。
2. 前端全量 vitest、typecheck、ESLint 和生产构建已通过。
3. 仓库门禁：`check_worktree_artifacts`、`check_runtime_artifact_locations`、`check_text_whitespace`、`check_frontend_api_boundary`、Flyway 不可变性/破坏性检查和 `git diff --check` 已通过。
4. 真实本地链路：LDAP 登录、Docker GitLab 镜像配置、300/300 全量、6/6 事实刷新、20/20 增量、25 个只读 API 及评审/代码走查/系统测试/客户问题页面通过；人工验收清单见下。

### 收口补充：合并后同步游标修复（2026-08-24）【已完成】

- 背景：全量任务使用单调主键扫描策略时，扫描结束页可能只完成任务而不更新表状态游标；空页还可能保留上一次成功运行的旧主键游标，下一轮会从错误位置开始。
- 改动：`SyncRunTableTaskExecutor` 在全量单调主键任务的终态使用本次扫描最终游标；`SyncRunTablePageCommitService` 在非空终态保存最终主键，空终态统一写入 `[]` 清除陈旧游标。普通增量、普通单调主键和分页中间态语义不变。
- 验证：新增 `SyncRunTablePageCommitServiceTest`，覆盖非空终态保存最终游标和空终态清除旧游标 2 项；同步相关交互回归通过。

## 决策记录

- 已选：五目标按“隔离度好→测试强→风险低”排序实施，每阶段独立可验证可回退。
- 已选：桥接测试删除而非迁移——同一行为在协作者测试中已存在同名覆盖，保留两份违反单一事实源；仅字符串时间语义迁往缺失的 TimestampNormalizer 直属测试。
- 已选：statistics 包对 IssueFactRecordRepository 的 3 个方法签名视为冻结公共契约，内部重构不得波及。
- 已选：兼容模式分支不做策略模式抽象——该代码整体待删，抽象反而加大未来删除面。
- 已否决：统一记录页 SQL/内存双轨过滤语义（属口径变更，且易与同事的双轨治理混淆）；本轮只拆结构。
- 已否决：本轮深拆 FactBuildService——1471 行且是事实层核心，须先补齐行为测试再动，列入 Tier B 首位。
- 已保留：筛选草稿状态机仍与 emit/guard 深度纠缠，本轮不再扩大拆分范围，后续单独立项评估。
- 已记录：同步游标修复属于合并后同一工作树的计划外缺陷修复，不改变解耦目标的公开 API、事实口径或迁移契约；因其影响同步终态正确性，纳入本轮收口验证。

## 接口契约

- 本解耦工作单元不新增对外 HTTP API、表结构、数据格式或数据库迁移；工作树中已合并的 `V20260821_01` 属于客户问题里程碑目录修复计划，不属于本计划新增。
- 冻结签名：`IssueFactRecordRepository.findByFilters/findForFilterOptions/findCustomerIssueRecordFilterValues`（statistics 包调用）；`CodeReviewIllegalRecordService` 对 CodeReviewController 的 8 个公开方法；`GitlabExternalDbService` 对 5 个消费者的现有公开方法集。
- 内部接口允许破坏性调整，但必须同轮更新全部调用者与测试并删除旧路径。

## 风险与假设

- GitlabDirectJdbcExecutor 转 Bean 后生命周期由 Spring 管理，需验证 close() 推断销毁与配置变更事件时序等价。
- IssueFactRecordRepository 的 SQL 字符串拼接迁移必须逐字符保真（where 片段顺序影响参数顺序）；以现有捕获 SQL 断言的测试为锚。
- Excel 导出列序（35 列 LEGACY_EXPORT_HEADERS）与 CSV 表头一旦变化即为缺陷；导出器抽取后跑列序断言测试。
- 前端列宽引擎外移后像素级行为必须一致；以现有组件测试 + 页面 mount smoke 保护，必要时补截图对比。
- 工作树混有大量用户变更，全程不执行 git commit/push，除非用户明确指示提交范围。

## 人工验收功能清单

- 数据库浏览器：表预览分页、关键字、排序在 DOCKER 与 DIRECT 两种模式下行为不变。
- 镜像设置页：保存/测试连接/全量·增量·补偿同步/取消/失败重试/事实重建/镜像清理/System Hook 注册检测全部原样；健康状态文案与色调不变。
- 同步运行：DIRECT 连接池指标、表级诊断抽屉、配置保存后连接池退休正常。
- 系统测试/客户问题记录页与非法记录页：筛选、分页、排序、导出（Excel 35 列布局）、详情、实时刷新不变。
- 代码走查非法记录页：SQL 快路径与自定义规则慢路径、筛选选项下拉（含 DGM 项目候选合并、CC 四项兜底）、规则预览、MR 链接生成不变。
- 五个使用 BaseRecordTable 的页面：列宽自适应、快速筛选摘要 chips、当前排序标签、浮动横向滚动条视觉与交互不变。

## 后续内网行为基线约束

- 解耦版本完成内网部署并通过现场测试后，建立该版本的唯一行为基线；按页面和功能逐项记录按钮显示/隐藏、可用/禁用、点击结果、跳转、提示、数据展示、详情、导出及下载结果，并保留版本、数据、操作步骤和截图/响应/文件等证据。
- 测试数据由本地 GitLab 生成，必须覆盖业务支持的全部场景；代码走查非法数据至少覆盖每种非法类型一条、多个非法类型同时命中的数据一条，以及其他已定义组合各至少一条。
- 后续修改、修复和更新必须使用相同测试数据与操作步骤执行基线对比；发现差异时先定位到页面、按钮、接口、数据或显示结果，再判定是预期变更还是回归。
