# 替代兼容模式专项适配计划

## 进度与中间物

- **本计划创建时间**：2026-08-18。状态：**调查完成，待实施**。
- 已完成文件/变更：暂无代码变更。已完成全量盘点（详见"证据与根因"）。
- 测试状态：未运行（尚无代码改动）。
- 当前阻塞/进行点：无阻塞；下一步从"待办清单 A1（代码走查人工指标批量导入入口）"开始。

---

## 1. 恢复线索

- **当前阶段**：影响面盘点完成 → 待办清单评审与分优先级。
- **恢复后建议执行的首条命令**：本机后端启动用 `D:\Environments\jdk-21.0.12+8` + `D:\Environments\apache-maven-3.9.9`，不能用项目内 `tools/` 目录（本机不存在）；`run-backend.ps1` 本机不可用，需手动 `mvn -Dmaven.test.skip=true -Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8 spring-boot:run`，且 PowerShell 直接调 `mvn.cmd` 会吞 `-D` 参数，须用 `cmd /c`。
- 上一份计划链接：无（`docs/plans/` 目录此前为空）。
- 对应 commit：无（尚未提交）。

## 2. 目标与边界

### 用户原始需求
逐步过渡，最终**彻底摒弃老平台**（Spider）：让新平台（data_collection_platform）对代码走查和评审数据不再依赖老平台 MySQL/MongoDB 兼容表，达到可整体删除兼容模式（Match mode）系统的状态。

### 可验证的成功标准
1. `code_review_read_mode` 切到 `formal` 后，代码走查非法数据、质量看板、多元看板、系统测试横向对比、外部数据集、BI 编码页全部只读 `code_review_formal_records`（正式事实），数据行为与兼容态一致。
2. `review_data_read_mode` 切到 `formal` 后，评审数据管理、质量看板、系统测试横向对比只读正式评审表（`review_records` 等），不再合并 Mongo 快照。
3. 全代码库 `grep 兼容模式-MatchMode|match_mode` 无残留（除历史 commit），全部临时表/服务/Controller/前端页面/权限可删除后系统仍可编译、测试通过。
4. 旧兼容表数据可完整、可审计地搬运到正式结构（代码走查 → `merge_request_fact` 或 `code_review_external_metrics`；评审 → `review_records` 族）。

### 明确禁止的行为
- **不得为已废弃接口保留别名、转发、回退分支或双轨逻辑**（AGENTS.md 开发期红线）。
- 不得用 `new_xxx`/`xxx_v2` 包裹旧实现逃避重构。
- 不得让新旧数据结构长期并存再不断追加转换层。
- 不得在正式查询里重新引入兼容判断（兼容读源判断必须随临时系统一并删除）。
- 老平台评审不参与批量转正式（`docs/platform-page-business-rules.md` 已确认），评审过渡走"负 ID 快照首次编辑物化为 `PLATFORM_OWNED`"路线或 Excel 导入，不得破坏该契约。
- 不得修改已执行的历史 Flyway 迁移；结构变更必须新建前向迁移。

## 3. 约束与背景

### 业务与数据契约
- 代码走查兼容 / 正式读源选择由 `code_review_match_mode_db_settings.code_review_read_mode` 服务端决定，客户端参数不得参与。
- 评审读源由 `review_data_read_mode` 决定；`review_visible_*` 兼容态=正式表 ∪ 未接管的 Mongo 快照，正式态=只读正式表。
- 两兼容表及配套表：`code_review_match_mode_records`（来自老平台 MySQL `spider_crowncad_data`，CC/DGM 双源）、`review_data_match_mode_{reports,problem_details,descriptions,contents}`（来自老平台 MongoDB `spider` 库）。
- 正式读源：代码走查 = `code_review_formal_records` 视图（优先 `LEGACY_PLATFORM` 事实，未交接回退 GitLab 正式事实）；评审 = `review_records` / `review_record_experts` / `review_problem_items` / `review_record_descriptions`。
- `code_review_external_metrics` 是 GitLab diff 派生指标（行数、提交数）与人工走查指标（缺陷数、扫描、注释率、规范数、速率等）的统一补齐模型；人工指标目前仅能从老平台 MySQL 导入，平台自身无写入入口。
- `collect_form_records` 是采集表单（走查人、时长、规范分），事实构建已并入代码走查口径。
- 涉及 LDAP、兼容模式、事实层、统计快照或部署时，同时遵守 `docs/decisions.md`、架构文档、进度文档。
- 环境：后端 18080、前端 18181、PostgreSQL `127.0.0.1:15432/qaflex`；GitLab docker `gitlab-v16` :8929 已启动。老平台 MySQL/MongoDB 连接由兼容设置页维护（默认 `172.22.10.72`）。

### 技术栈
- 后端 Java 21 + Spring Boot + MyBatis-Plus + Flyway；前端 Vue 3 + TS + Vite + Element Plus；测试 JUnit5/Mockito + Vitest。
- 本机工具链：JDK `D:\Environments\jdk-21.0.12+8`，Maven `D:\Environments\apache-maven-3.9.9`；项目内 `tools/` 目录本机不存在。

## 4. 证据与根因

### 盘点结论（2026-08-18，代码层面）
- 后端 `src/main/java` 涉及 MatchMode/兼容模式的 **58 个文件**；前端 **21 个文件**；后端测试 **21 个文件**；相关 Flyway 迁移 **17 个**（`V20260701_01` ~ `V20260730_07`）。
- **核心已适配（正式链路已具备）**：
  - `merge_request_fact` 已含代码走查字段；`code_review_formal_records` 视图已实现"LEGACY_PLATFORM 优先、未交接回退 GitLab"。
  - `LegacyPlatformFormalImportService` 已实现代码走查转正式（advisory lock + REPEATABLE_READ + 设置版本校验），`hasPromotedCodeReviewData` 驱动视图切换。
  - `CodeReviewMetricEnrichmentService/Repository` 已从 GitLab diff 自动补齐行数类指标（added_lines、deleted_lines、commit_count、commit_rate）到 `code_review_external_metrics`。
  - 评审正式表族与 `review_visible_records`/`review_visible_problem_items` 统一读模型已存在；评审负 ID 快照首次编辑物化 `PLATFORM_OWNED` 已实现（`ReviewDataMatchModeMaterializeService`）。
  - 评审 Excel 导入（`ReviewDataLegacyExcelImportService` + `/api/review-data/legacy-excel-import/*`）已存在。
  - `QualityBoardCodeReviewReadSupport.resolveScope` 已实现 MATCH_MODE→`code_review_match_mode_records` / FORMAL→`code_review_formal_records` 的集中读源解析。
- **关键未适配点**：
  1. **`code_review_external_metrics` 无平台侧批量导入入口**（无对应 Controller）。老平台历史的"人工走查指标"（defect_count、scan_status、annotation_rate_result、bug_count_result、五类规范数、review_speed_*、review_efficiency、function_name、clang_added_line_count 等）只能经兼容表→转正式间接进入 `merge_request_fact`，无法独立落 `code_review_external_metrics` 供事实构建复用。
  2. **外部工具写入 API 未完成**：`docs/platform-page-business-rules.md` 明确"当前外部工具写入 API 尚未完成时，新平台只能消费事实层已有字段，不能臆造工具结果"。即注释率工具、静态扫描工具正式接入前，这些人工字段在新平台无生产入口。
  3. **代码走查页「数据源=DGM → 项目名称（projectName）下拉框」是独立于兼容表的新平台自有项目名录**（用户 2026-08-18 复核确认的细节）：
     - 前端 DGM 分支（`CodeReviewIllegalRecordsView.vue` 的 `activeSourceIsDgm`）把项目粒度字段由 CC 的「所属项目（repositoryName）」切换为「项目名称（projectName）」；`projectScopeOptions = filterOptions.projectNames`，DGM 页签固定 name=DGM、不展示 repositoryName 列/筛选。
     - 后端候选在 `CodeReviewIllegalRecordService.toCodeReviewProjectNameOptions` 组装：当前读源的 `project_name` 值 ∪ `dgmProjectOptionService.listProjectNames()`（来自 `code_review_dgm_project_options` 表）。
     - `code_review_dgm_project_options` + `code_review_dgm_gitlab_project_source_settings` 由 `CodeReviewDgmGitlabProjectOptionService`（GitLab API 定时同步 Group API）维护，**正式/兼容两种读源共用**、不参与兼容表数据写入——属于应保留并迁出兼容设置页的正式项目名录，不是兼容临时表。
     - 风险：若直接删除兼容设置页及 `CodeReviewMatchModeDbSettingsController` 的 `dgm-gitlab-project-source*` / `dgm-gitlab-project-options*` 端点，且未先把该配置迁至正式项目目录，DGM「项目名称」下拉框将只剩当前读源数据里出现的 `project_name`，丢失 GitLab 侧全量候选。
  4. **BI**（`docs/bi-dashboard/`）只跟随平台唯一兼容模式读取 `code_review_match_mode_records`；正式态读 `code_review_formal_records`。兼容模式删除需同步验证 BI 编码页不需要改动（数据来源字段已等价映射）。

## 5. 方案与步骤

总体策略：**先补齐"正式链路自助生产能力"（代码走查人工指标导入 + 评审物化/导入验证），再分批切换读模式，最后整体删除兼容系统**。每个阶段独立可回退、可验证。

### 阶段 0：基线验证（先做，占用最小）
- 确认当前环境前后端已启动（18080/18181）、GitLab :8929、Postgres :15432。
- 跑通现有测试基线：后端 `mvn test`（关键服务），前端 `npm run typecheck` + 相关 `.test.ts`，记录基线通过。

### 阶段 1：代码走查人工指标正式链路
- **A1【未适配·待开发】** `code_review_external_metrics` 批量导入入口：新增 Controller + Service，支持从老平台 MySQL 已同步的 `legacy_mysql_imported_rows` 或 Excel 导入人工走查指标（CC/DGM 双源），写入 `code_review_external_metrics`（用 `enrichment_status` 队列复用既有发布链路或直接入库）。接口遵循权限 `system.match_mode.*` 还是新权限待定（见决策记录）。
- **A2【未适配·待开发】** 事实构建复用：确认 `GitlabFactSourceSqlProvider` 的 `imported_metrics` CTE 在导入后能把人工指标发布进 `merge_request_fact`，并刷新 `fact_refreshed_at` 触发快照失效。
- **A3【未适配·待开发】** 校验：CC/DGM 各取样本 MR，对比 `merge_request_fact` 与老平台原表口径（defect_count、scan_status、annotation_rate_result、review_speed_* 等逐字段一致）。

### 阶段 2：代码走查读模式切换与清理准备
- **B1【已适配·待验证】** `code_review_read_mode=formal` 时全部消费者（非法数据页、研发质量看板、多元看板、系统测试横向对比、外部数据集、BI 编码页）验证只读 `code_review_formal_records`，页面行为与兼容态一致。
- **B2【待验证】** 正式切换后 `QualityBoardCodeReviewReadSupport` 的 MR 去重键（`(project_id, merge_request_id)` vs 兼容 `merge_request_iid`）在非法数据/看板聚合下结果一致。
- **B3【待开发？】** 若转正式导入范围不足，需补数据搬运脚本：兼容表→`merge_request_fact(LEGACY_PLATFORM)` 幂等重放（参考 `LegacyPlatformFormalImportService.importCodeReviewData`），保证 CC/DGM 全量交接。
- **B4【待开发·前置】** DGM 项目名称候选落户正式目录：把 `code_review_dgm_gitlab_project_source_settings`（GitLab 连接/Token/Group）与 `code_review_dgm_project_options`（项目名缓存表）从兼容设置页迁到正式「项目目录 / GitLab 数据源」管理，`CodeReviewDgmGitlabProjectOptionService` 端点脱离 `CodeReviewMatchModeDbSettingsController` 独立成正式接口；前端 DGM「项目名称」下拉改读正式目录。**此项必须在阶段 4 删除兼容页之前完成**，否则删除后 DGM 下拉失去 GitLab 侧候选。

### 阶段 3：评审数据正式链路
- **C1【已适配·待验证】** 评审 Excel 导入链路验证：导出老平台 Mongo 数据→导入 `review_records` 族→`review_data_read_mode=formal` 后页面行为一致。
- **C2【待验证】** 负 ID 快照物化路径（`ReviewDataMatchModeMaterializeService`）在正式态下不再使用；确认 `review_visible_*` 正式态只返回 `review_records`。
- **C3【待开发？】** 若需要 Mongo→正式表的自动化批量迁移（非 Excel 手工），需新增一次性迁移工具（评估必要性与成本，见决策记录）。

### 阶段 4：整体删除兼容模式系统
- **D1【待开发】** 删除后端：`CodeReviewMatchMode*Service/Config/RecordLoader/SwitchService/LegacyPlatformFormalImportService`、`CodeReviewMatchModeDbSettingsController`、相关 entity、`GitlabSyncConfig.match_mode_enabled` 字段与使用点、`PlatformPermissionCodes.SYSTEM_MATCH_MODE_*`、所有 `//兼容模式-MatchMode` 分支。
- **D2【待开发】** 删除前端：`LegacyDatabaseSettingsView.vue`、`legacy-database-api.ts`、路由入口、feature-manifest 对应 pageKey、`CodeReviewIllegalRecordsView` 中的兼容分支与 `codeReviewCompatibilityRead` 判断。**注意**：`CodeReviewIllegalRecordsView` 中 DGM→projectName 下拉、`activeSourceIsDgm`/`projectScopeUsesRepository` 分支在 DGM 正式态仍需要，不能随兼容分支一并删除（仅删除 `matchMode` 相关判断，保留 DGM 读源切换与项目名称下拉）。
- **D3【待开发】** 删除测试：上述 21 个测试文件中的兼容相关用例，替换为正式链路契约测试。
- **D4【待开发】** 删除迁移建表/数据：新增清库迁移（不再恢复历史兼容表结构），同步更新 `DatabaseBrowserCollectionTableDefinitions`、database-browser 目录。
- **D5【待开发】** 文档同步：`docs/architecture.md` 兼容模式边界章节、`docs/platform-page-business-rules.md` 兼容规则、`docs/decisions.md`（如需记录删除决策）、BI 文档中兼容态来源描述。

### 阶段 5：验收与提交
- **E1** 全量回归：后端 `mvn test`、前端 Vitest + typecheck、Flyway 新库全迁移。
- **E2** 无 `兼容模式-MatchMode`/`match_mode` 残留检查；`git diff --check`、`scripts/check_*` 系列脚本。
- **E3** 按 Conventional Commits 分单元提交推送到 `main`。

## 6. 决策记录

| 决策 | 选择 | 理由 | 否决方案 |
|---|---|---|---|
| 代码走查人工指标如何进入正式链路 | 优先补 `code_review_external_metrics` 批量导入入口（方案 A1），复用既有 enrichment 发布链路 | 字段模型已存在，事实构建已 join；改动最小、不引入新表/双轨 | 新建第二张人工指标表（会与 `code_review_external_metrics` 双轨并存，违反红线） |
| 过渡实现方式 | 在已有 `code_review_formal_records` 视图与 `merge_request_fact` 上做数据搬运与导入，不清空既有结构 | 视图已实现优先 LEGACY_PLATFORM 逻辑，改造成本最低 | 新造一套"转正式新链路" |
| 评审数据过渡 | 优先走既有 Excel 导入 / 负 ID 物化契约；自动化 Mongo→正式表迁移作为可选 C3，先评估数据量与频度再定 | 契约已存在且是用户已确认事实；避免重复开发 | 强制新建批量迁移工具（可能过度设计） |
| 权限 | 代码走查人工指标导入接口沿用 `system.match_mode.*` 还是新权限，**待定**（实施 A1 时与用户确认） | — | — |
| `code_review_external_metrics` 导入是否叠加 `enrichment_status=ENRICHED` 进既有发布队列 | 建议叠加（复用 `publishEnriched`） | 避免事实构建重复逻辑 | 导入直接写 `merge_request_fact`（绕过指标表，破坏单一事实源） |

## 7. 接口契约

> 暂无新增对外 API/表结构；实施 A1 时按如下方向确定后回填：

- **A1 新增接口（草案）**：`POST /api/code-review/external-metrics/import`（批量导入人工走查指标）。
  - 入参草案：`{ sourceInstance: "cc"|"dgm", rows: [{ projectId, mergeRequestIid, defectCount?, scanStatus?, annotationRateResult?, bugCountResult?, codeSpecificationCount?, codeLogicSpecificationCount?, performanceSpecificationCount?, designSpecificationCount?, otherSpecificationCount?, reviewSpeedLocPerHour?, reviewSpeedKlocPerHour?, reviewDefectDensityPerKloc?, reviewEfficiencyPerHour?, commitCount?, commitRate?, functionName?, clangAddedLineCount? }] }`
  - 返回草案：`{ accepted, imported: n, message }`。
- 无新增表结构；如确需落库临时文件 → `legacy_mysql_imported_rows`（已存在）或 `code_review_external_metrics`。

## 8. 风险与假设

- **假设**：老平台 MySQL/MongoDB 在过渡期持续可访问；若断连，方案 A1/C1 的数据搬运需改从兼容表本地快照（`code_review_match_mode_records` / `legacy_*_imported_*`）导，不依赖老平台在线。
- **待验证**：外部注释率/静态扫描工具正式接入新平台的写入 API 到底是平台补还是外部工具侧补——决定 A1 范围和 C 阶段工作量；当前按"平台补导入入口"推进，若外部工具先接入则 A1 收敛为"兜底导入"。
- **易错点**：转正式（advisory lock + REPEATABLE_READ）并发重放时设置版本必须含 `updated_at` 乐观锁；删除兼容系统前必须确认 `hasPromotedCodeReviewData` 判定可靠，否则正式读源会错误回退 GitLab 导致数据口径漂移。
- **敏感只读数据**：老平台 MySQL/MongoDB 为生产数据源，任何搬运必须只读连接、批量限流（复用现有 `mysqlFetchSize`）、失败不推进水位。

---

<!-- 附：全量涉及文件清单（供删除/改造定位，2026-08-18 盘点） -->
<details>
<summary>涉及文件清单（点击展开）</summary>

**后端 main（58 文件，含命名示例）**：
`controller/CodeReviewMatchModeDbSettingsController.java`、`controller/CodeReviewController.java`、`controller/GitlabSyncConfigFacade.java`、`controller/GitlabSyncControllerResponseMapper.java`、`controller/GitlabSyncSaveConfigRequest.java`、
`service/CodeReviewMatchMode{Config,ConfigService,LegacyRefreshService,MongoReviewSyncService,RecordLoader,SwitchService,SyncService}.java`、`service/LegacyPlatformFormalImportService.java`、`service/CodeReviewMatchModeRecordLoader.java`、`service/CodeReviewMatchModeConfig.java`、
`service/QualityBoardCodeReviewReadSupport.java`、`service/CodeReviewIllegalRecordService.java`、`service/CodeReviewIllegalRuleRegistry.java`、`service/CodeReviewMultiBoardService.java`、`service/CodeReviewMultiBoardAnalyticsQueryService.java`、`service/CodeReviewDgmGitlabProjectOptionService.java`、
`service/CodeReviewRuleConfigSupport.java`、`entity/CodeReviewRuleConfigGroup.java`、`service/ReviewDataMatchModeMaterializeService.java`、`service/ReviewDataMatchModeRecordRepository.java`、`service/ReviewDataRecordCommandService.java`、`service/ReviewDataRecordQueryService.java`、`service/ReviewDataFilterOptionService.java`、`service/ReviewDataAuthorizationService.java`、`service/PageRecordSnapshotService.java`、`service/GitlabConfigService.java`、`service/IntegrationTestFactBuildService.java`、`service/RealtimeWorkspaceSyncMetadataService.java`、`service/DatabaseBrowserCollectionTableDefinitions.java`、
`service/analytics/{CodeReviewAnalyticsReadMode,AnalyticsDashboardQueryContextResolver,QualityRdAnalyticsDashboardProvider,QualityRdAnalyticsDetailQueryService,QualityBoardOtherQueryService}.java`、
`service/statistics/{SystemTestDefectCauseBoardService,SystemTestDefectSummaryBoardService,SystemTestDelayAnalysisBoardService,SystemTestHorizontalComparisonExportService,SystemTestIssueMultiBoardService,SystemTestPhaseMembershipPolicy,SystemTestPhaseStatisticsBoardService}.java`、
`entity/CodeReviewMatchMode{CollectionOptionResponse,ConnectionTestResponse,DbSettingsResponse,DbSettingsSaveRequest,StatusResponse,SyncResponse,TableOptionResponse}.java`、`entity/GitlabSyncConfig.java`、
`bi/domain/BiCodingCalculator.java`、`bi/infrastructure/{BiDashboardRuntimeFactory,BiPlatformCodingSourceAdapter}.java`、
`security/PlatformPermissionCodes.java`、`config/PlatformSecurityConfiguration.java`

**前端（21 文件）**：
`views/LegacyDatabaseSettingsView.vue`、`views/CodeReviewIllegalRecordsView.vue`、`views/MirrorSettingsView.vue`、`views/review-data-management.ts`、`views/code-review-illegal-records-view-helpers.ts`、`views/code-review-rule-config-utils.ts`、
`api-client/legacy-database-api.ts`、`api-client/code-review-api.ts`、`feature-manifest/modules.ts`、`router.ts`、`App.vue`、`composables/useFloatingHorizontalScrollbar.ts`、
`types/api/{system-settings,sync,code-review}.ts`、`types/code-review-rule-config.ts`、
`components/rule-config/CodeReviewRuleConfigEditor.test.ts`、`views/code-review-illegal-records-view-helpers.test.ts`、`views/code-review-rule-config-utils.test.ts`、`views/ux-interaction-regressions.test.ts`、`App.test.ts`

**后端测试（21 文件）**：见"证据与根因"与 `backend/src/test/**`（含 `MatchModeOwnershipBoundaryTest`、`LegacyPlatformFormalImportServiceTest`、`QualityBoardCodeReviewReadSupportTest`、`ReviewDataMatchMode*Test`、`FlywayMigrationSmokeTest` 等）。

**Flyway 迁移（17 文件）**：`V20260701_01__code_review_match_mode.sql` 至 `V20260813_01__remove_bi_code_review_compatibility_sync.sql`（含 db_settings、sync_state、records、review_data_match_mode_* 系列、读模式/权限/视图）。
</details>
