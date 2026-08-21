# 进度与中间物

- 状态：全仓静态盘点与核心链路深读已完成第一轮；阶段一已完成，阶段二已完成异常/日期/类型收敛批次，阶段三已完成事实来源组件注入边界，阶段四已完成 SQL 标识符重复收敛、统计明细映射边界核对、非法记录查询参数和日期显示重复收敛；根据用户最新指令，代码修改已暂停。
- 已完成：读取 `AGENTS.md`、`docs/progress.md`、`docs/product.md`、`docs/architecture.md`、`surgical-project-init/SKILL.md`；确认仓库为单一 Git 根，主系统为 `backend` + `frontend`，BI/CAT 为嵌入式一级模块。
- 已完成：确认工作树包含用户已有修改、删除和未跟踪迁移；本工作单元不回退、不清理、不覆盖这些改动。
- 已完成：请求客户端超时/取消执行逻辑合并，JSON 边界由 `any` 收敛为 `unknown` 守卫；BI API 客户端迁移到 `api-client`；日期显示工具收敛；前端异常消息与两个生产 `any` 收敛；事实来源 SQL provider/query executor 改为 Spring 注入。
- 测试：前端 typecheck、异常/日期/标签组定向测试、非法记录页面客户问题冒烟测试、日期/快捷筛选构建器单测、定向 ESLint 通过；后端 Docker Java 21/Maven 已完成事实边界定向 9 项、SQL/镜像/数据库浏览器定向回归、Checkstyle 和 SpotBugs。
- 当前进行点：无。用户要求先暂停修改，当前只保留本进度文档作为交接快照。
- 阻塞：无审计阻塞；全仓逐行结论需要依赖静态扫描结果、核心代码深读和代表性测试验证，不能仅由目录或类名推断。

## 暂停快照

- **暂停原因**：用户说明同事正在负责 `backend/src/main/java/com/data/collection/platform/service/statistics/` 的看板工厂和 SQL/内存筛选双轨解耦，要求本工作单元暂不触碰该问题；随后用户要求暂停修改并先输出当前进度文档。
- **本工作单元已完成的方向**：HTTP 请求边界、前端 API 客户端目录边界、未知异常消息提取、日期显示工具、固定响应类型收敛、事实来源依赖注入、PostgreSQL 标识符引用、代码走查非法记录 CSV 重复逻辑、非法记录 API 查询参数、系统测试/客户问题非法记录快捷筛选重复逻辑。
- **本工作单元未完成的方向**：非统计模块上帝类（如 `GitlabExternalDbService`、`BaseRecordTable.vue`、`MirrorSettingsView.vue`）尚未继续实施拆分；统计看板工厂及其筛选双轨不在本工作单元继续实施。
- **当前工作树边界**：工作树原本已包含用户的删除、迁移、文档和多文件代码修改；本工作单元没有回退、清理或覆盖这些改动。当前暂停前仅新增/调整了本计划文档，后续不再执行代码编辑。

## 恢复线索

- 当前阶段：阶段四统计查询职责拆分前的事务、筛选和导出边界复核。
- 恢复后首条命令：`rg -n "class IssueFactRecordRepository|class CodeReviewIllegalRecordService|toDetailRecord" backend/src/main/java/com/data/collection/platform/service backend/src/main/java/com/data/collection/platform/service/statistics`。
- 上一份计划或对应 commit：无；以当前 `main` 工作树和本计划为准。

## 目标与边界

- 用户原始需求：对项目代码执行基于真实代码的深度 KISS、YAGNI、SOLID、工具类复用、去重、可维护性和可扩展性审计，并给出中文违规清单与分阶段可落地重构计划。
- 成功标准：完成全仓结构/静态扫描；深读运行入口、核心同步/事实链、API/持久化/外部集成、前端主链和代表性测试；每个报告项均有文件和函数/行号证据；计划包含优先级、依赖、风险、验收测试和明确不应重构的稳定边界。
- 本阶段禁止：基于文件名、类名或代码片段猜测行为；为“看起来不够优雅”的稳定业务规则制造无收益抽象；回退用户已有修改；执行破坏性数据库迁移或发布操作。用户已明确授权本工作单元按本计划立即实施代码重构，但涉及业务口径、公开接口、数据迁移或跨事务拆分的方案仍须先完成契约核对。
- 并行边界：`backend/src/main/java/com/data/collection/platform/service/statistics/` 下的看板服务、统计基类、重复快照/导出/规则流和 SQL/内存筛选双轨由同事负责；本工作单元不修改该目录，不新增该目录的共享抽象，不改变其筛选、导出、快照或规则口径。

## 约束与背景

- 后端：Java 21、Spring Boot 3.5、MyBatis-Plus、PostgreSQL/Flyway；前端：Vue 3、TypeScript、Vite、Element Plus、Vitest。
- 项目代码与注释维护文本使用中文，代码标识符使用英文；公共 Java 方法按现有 Javadoc 约定，TypeScript 公共 API 用 JSDoc 或类型表达契约。
- 业务正确性优先于纯风格重构：老平台结果口径、事实发布代际、兼容模式边界、认证/CSRF、镜像任务租约和离线发布契约均视为不可无证据改变的外部/业务边界。
- 测试实际位于 `backend/src/test/` 与 `frontend/src/**/*.test.ts`；默认验证入口受当前环境工具可用性限制。

## 证据与根因

- 已证实：主同步入口为 `DataCollectionPlatformApplication` -> `GitlabSyncController` -> `GitlabSyncCommandFacade` -> `SyncRunSubmissionService` -> `sync_runs` 持久队列。
- 已证实：`SyncRunDispatcherService` 使用 PostgreSQL `FOR UPDATE SKIP LOCKED` 抢占，`SyncRunExecutorService` 使用有界线程池、租约和心跳，`SyncRunWorkerService` 分流镜像运行和事实刷新。
- 已证实：镜像表任务经 `SourceTableReader`/`MirrorTableWriter` 写入 ODS，`FactChangeTargetService` 登记版本化事实目标，`SyncRunFactPublicationCoordinator` 和 `SyncFactRefreshRunExecutor` 推动事实/投影发布。
- 已证实：BI 客户端 `frontend/src/features/bi-dashboard/data/bi-dashboard-api.ts` 统一调用 `frontend/src/api-client/request`，但路径门禁要求领域 API 位于 `api-client`，存在结构契约漂移。
- 待调查：全仓重复逻辑、超长职责类、无类型 Map/Object、魔法值/硬编码文案、公共函数文档缺口、异常边界、前端跨组件状态和真实测试保护范围。

## 方案与步骤

1. [完成] 锁定仓库、读取规则与权威文档，记录用户工作树边界。
2. [完成] 对 Java/TypeScript/脚本完成全仓文件、行数、复杂度代理指标、重复模式和危险构造扫描。
3. [完成] 深读后端运行入口、同步/ODS/事实核心、认证/外部数据边界、BI/CAT 边界及对应测试。
4. [完成] 深读前端入口、路由、API 客户端、统计/BI 视图和状态组合逻辑及对应测试。
5. [完成] 按确定性证据整理违规清单，分为 P0 正确性/安全、P1 可维护性/职责、P2 重复/类型/风格；无法证实项单独标记为待确认。
6. [完成] 设计分阶段重构路线，明确依赖、行为不变量、测试、回滚边界和停止条件。
7. [完成] 执行第一阶段请求客户端、前端 API 边界、日期工具和类型收敛，并完成定向回归。
8. [完成] 执行前端公共异常消息提取、镜像/统计/记录页迁移及两个生产 `any` 清理，并完成定向回归。
9. [完成] 将事实来源 SQL provider/query executor 纳入 Spring 构造注入，移除 `FactBuildService` 内部手动 `new`，完成事实构建定向回归。
10. [完成] 对统计明细映射和 SQL 标识符引用做差异核对；九个看板明细映射保持局部，通用 SQL 标识符引用已收敛并完成定向回归。
11. [暂停/移交] 已核对统计查询、明细映射和导出边界；九个看板的 `toDetailRecord` 因字段集合、顺序、时间精度和私有来源类型不完全一致未强行合并。看板工厂和 SQL/内存筛选双轨移交同事，本工作单元不再修改。
12. [完成] 将系统测试/客户问题非法记录页的公共快捷筛选构建逻辑抽取到领域工具；范围字段、客户问题优先级和测试状态宽度差异显式配置，并以 2 项单测验证字段顺序和差异。
13. [完成] 将评审详情秒级日期显示和代码走查规则配置分钟级日期显示统一到日期工具，保留 19 位/16 位原有精度，并新增分钟显示单测。
14. [暂停] 非统计模块上帝类和前端大型组件尚未进入实施，等待用户后续恢复指令。

## 决策记录

- 已选：审计先于重构，先保护业务契约，再处理结构问题；不把“代码更短”自动等同于 KISS。
- 已选：优先根因和边界收敛，禁止通过 `v2`、别名、回退分支或双轨实现逃避内部重构。
- 已决：BI API 客户端物理目录按现有 API boundary 门禁迁移到 `frontend/src/api-client/`，调用者与测试已同步。
- 待定：是否拆分超大服务类；必须先测量调用关系、事务边界和测试保护，避免把一个清晰事务拆成隐式流程。
- 已决：异常消息工具只负责从 `unknown` 提取文本，不改变请求错误类型、异常传播和既有业务 fallback。
- 已决：同事负责的 `service/statistics` 目录从本工作单元实施范围冻结；原因是两个解耦工作单元同时修改同一套统计筛选、导出和快照契约会造成冲突与口径分叉。

## 已完成修改摘要

- `frontend/src/api-client/request.ts`：合并 `request`/`requestRaw` 的超时、Abort 传播、清理和 fetch 执行逻辑；JSON 边界从 `any` 收敛为 `unknown` 守卫。
- `frontend/src/api-client/bi-dashboard-api.ts`：承接 BI 领域 API 客户端；调用者和契约测试同步迁移，删除领域目录中的传输实现。
- `frontend/src/utils/user-message.ts`、多个页面：统一未知异常消息提取，保留各页面原有 fallback 文案；清理已确认的两个生产 `any`。
- `frontend/src/utils/beijing-time.ts`、`frontend/src/views/review-data/ReviewDataDetailDrawer.vue`、`frontend/src/views/CodeReviewIllegalRuleConfigView.vue`：统一本地 ISO 日期显示，详情保留秒级，规则配置保留分钟级；日期工具补充中文契约注释和测试。
- `frontend/src/api-client/issue-records-api.ts`：统一系统测试/客户问题非法记录查询参数构建，保留各接口字段和分页/排序契约。
- `frontend/src/views/issue-illegal-records/issue-illegal-record-primary-filters.ts`：统一两类非法记录页快捷筛选构建；调用方仍显式提供范围字段和客户问题优先级差异。
- `backend/src/main/java/com/data/collection/platform/service/FactBuildService.java` 及事实来源组件：由 Spring 注入来源 SQL provider/query executor，移除服务内部手动创建。
- `backend/src/main/java/com/data/collection/platform/common/SqlIdentifierSupport.java` 及相关 SQL/镜像/数据库浏览器服务：统一 PostgreSQL 标识符引用；资源链接服务继续保留严格白名单策略。
- `backend/src/main/java/com/data/collection/platform/service/CodeReviewIllegalRecordService.java`：复用既有 CSV 导出支持，删除重复 CSV 转义和日期格式实现。

## 人工验收功能清单

以下是本工作单元实际触及的功能边界，自动测试通过后请按此清单手动验证。统计看板工厂、统计 SQL/内存筛选双轨、统计快照和规则流不属于本清单，由同事负责。

- **请求客户端**：任意登录态接口、镜像设置接口和记录查询接口在正常响应、超时、主动取消、页面离开、非 JSON 错误时，加载状态、错误提示和数据结果保持原行为。
- **BI 看板**：BI 首页、看板切换、明细加载、图表导出以及规则/数据请求正常，Network 请求路径和下载结果不变。
- **事实重建**：镜像设置页触发当前数据源全量/增量事实重建，任务能够入队、执行、刷新状态并显示失败提示；来源 SQL provider/query executor 正常装配。
- **数据库与镜像操作**：数据库浏览器查询/刷新、镜像表结构读取、镜像清理、索引检查、来源健康检查、来源扫描和 GitLab 资源链接的合法/非法标识符行为不变。
- **代码走查非法记录**：列表筛选、规则预览/说明、详情、导出和实时刷新继续按原有数据源及参数工作。
- **系统测试非法记录**：测试阶段默认首选、模块/非法类型/议题编号/标题/处理人/严重程度/议题状态/测试状态筛选、条件筛选、分页、排序、导出、详情和实时刷新不变；不出现客户问题专属的优先级筛选。
- **客户问题非法记录**：里程碑默认首选、模块/非法类型/议题编号/标题/处理人/严重程度/优先级/议题状态/测试状态筛选、条件筛选、分页、排序、导出、详情和实时刷新不变；项目范围仍固定为 `325`。
- **日期显示**：评审详情更新时间显示到秒；代码走查规则配置最近更新时间显示到分钟；镜像、系统测试、客户问题和兼容模式页面的时区转换及空值占位不变。

## 初始确定性违规清单（重构前代码证据）

以下清单记录本工作单元开始时的代码事实；已处理项在上方“已完成”中标注，仍未处理项继续作为阶段四/五实施依据，不将历史定位误报为当前代码状态。

- **[frontend/src/api-client/request.ts]**
  - **定位**：`request` 约 60-147 行；`requestRaw` 约 185-239 行。
  - **违规类型**：SRP、再一再而不再三、KISS。
  - **问题简述**：两条请求路径分别维护超时控制器、Abort 监听、fetch 调用、超时转换和清理逻辑，修复或改变请求行为必须同步修改两处。
  - **优化预判**：抽取一个只负责带超时和取消传播的共享 fetch 执行函数，保留 JSON envelope、文本和 Blob 响应解析在各自入口。

- **[frontend/src/api-client/request.ts]**
  - **定位**：约 124 行 `const payload: any`；约 150 行 `parseJsonPayload(...): any`；约 458 行 JSON 错误读取。
  - **违规类型**：维护为纲、DIP/类型契约。
  - **问题简述**：HTTP 边界把未知 JSON 直接降为 `any`，调用者无法从类型上区分 envelope、错误消息和普通响应。
  - **优化预判**：使用 `unknown` 加对象类型守卫和统一消息读取函数，不改变外部响应格式。

- **[frontend/src/features/bi-dashboard/data/bi-dashboard-api.ts]**
  - **定位**：约 26、32、36、46、50、54、58、67 行的 `/api/...` 请求字面量。
  - **违规类型**：相似相溶、模块边界、KISS。
  - **问题简述**：领域目录直接持有 HTTP API 路径，触发现有 `check_frontend_api_boundary.py` 的 8 项门禁失败，数据阶段组件与传输适配职责混在同一目录。
  - **优化预判**：将 API 客户端及其契约测试归入 `frontend/src/api-client/`，领域视图只依赖客户端公开类型和方法。

- **[frontend/src/views/issue-illegal-records/IssueIllegalRecordsPage.vue]**
  - **定位**：`normalizeIssueState` 249-251 行、`formatDateTime` 253-255 行、`buildCurrentQueryParams` 304-332 行。
  - **违规类型**：再一再而不再三、相似相溶、SRP。
  - **问题简述**：通用记录页在页面组件内重复维护状态显示、时间格式化和路由查询组装，且同类查询组装已在多个记录页复制。
  - **优化预判**：抽取记录查询序列化与显示格式工具；页面只提供领域字段差异和业务默认值。

- **[frontend/src/views/CodeReviewIllegalRecordsView.vue]**
  - **定位**：`buildCurrentQueryParams` 343-367 行。
  - **违规类型**：再一再而不再三、SRP。
  - **问题简述**：与客户问题、系统测试记录页重复构建同一批分页、排序、关键词和筛选参数，领域页面承担了公共协议转换。
  - **优化预判**：复用统一的记录查询参数构建器，以显式字段配置保留代码走查的特有参数。

- **[frontend/src/views/CustomerIssueRecordsView.vue]**
  - **定位**：`buildCurrentQueryParams` 664-692 行；`errorMessage` 660-662 行及多个错误处理调用点。
  - **违规类型**：再一再而不再三、维护为纲。
  - **问题简述**：查询序列化和异常到用户文案转换仍由大页面本地维护，与项目已存在的 `utils/user-message.ts` 及其他页面的同型代码并存。
  - **优化预判**：统一未知异常消息提取和查询序列化入口，保留每个页面不同的过滤字段集合。

- **[frontend/src/views/CustomerIssueIllegalRecordsView.vue]**
  - **定位**：`normalizeIssueState` 47-49 行、`buildConditionFields` 116-118 行、`buildPrimaryFilters` 120-约 143 行。
  - **违规类型**：再一再而不再三、相似相溶。
  - **问题简述**：客户问题与系统测试非法记录页复制筛选字段和状态显示逻辑，公共组件边界没有承接真实重复。
  - **优化预判**：建立显式的非法记录筛选字段工厂和共享状态显示函数，领域差异通过配置传入。

- **[frontend/src/views/SystemTestIllegalRecordsView.vue]**
  - **定位**：`buildConditionFields` 93-95 行、`buildPrimaryFilters` 97-约 120 行。
  - **违规类型**：再一再而不再三、KISS。
  - **问题简述**：与客户问题非法记录页重复生成同一筛选协议，修改字段契约需要在两个页面同步维护。
  - **优化预判**：与客户问题页共同迁移到筛选字段构建工具，并保留系统测试特有字段的显式配置。

- **[frontend/src/views/LegacyDatabaseSettingsView.vue]**、**[frontend/src/views/SystemTestIssueSearchView.vue]**、**[frontend/src/views/review-data-management.ts]**
  - **定位**：分别约 531、388、611 行的 `formatDateTime`；另有 `frontend/src/views/code-review-illegal-records-view-helpers.ts:281-287`。
  - **违规类型**：再一再而不再三、相似相溶。
  - **问题简述**：多个页面重复执行 `replace('T', ' ').slice(...)`，并与已有 `frontend/src/utils/beijing-time.ts` 并存，时间显示规则容易出现页面间漂移。
  - **优化预判**：先区分“无时区本地 ISO”与“带偏移转北京时间”两种已存在语义，再分别收敛到命名明确的工具函数，禁止无证据改变时区口径。

- **[frontend/src/views/*.vue、frontend/src/composables/*.ts]**
  - **定位**：当前扫描发现约 93 处 `error instanceof Error ? ...` 或 `error as Error`，代表性位置为 `MirrorSettingsView.vue:436-482,581`、`DatabaseBrowserView.vue:139-327`、`StatisticBoardView.vue:697-727`。
  - **违规类型**：再一再而不再三、维护为纲、类型安全。
  - **问题简述**：未知异常被各页面重复且不一致地断言为 `Error`，异常为字符串或普通对象时既可能丢失文案，也使页面逻辑噪声显著增加。
  - **优化预判**：扩展已有用户消息工具提供 `getErrorMessage(error, fallback)`，按模块批量替换并为未知异常补测试。

- **[frontend/src/views/LabelGroupSettingsView.vue]**
  - **定位**：约 427 行 `childGroups?.map((child: any) => ...)`。
  - **违规类型**：维护为纲、类型安全。
  - **问题简述**：已有 API 响应在模板中退化为 `any`，子组字段拼写和结构变更无法由 TypeScript 发现。
  - **优化预判**：补齐标签组子组响应类型，在模板中使用已声明的集合元素类型。

- **[frontend/src/views/review-data/ReviewDataLegacyExcelImportDialog.vue]**
  - **定位**：约 224 行 `row.issues.map((item: any) => ...)`。
  - **违规类型**：维护为纲、类型安全。
  - **问题简述**：Excel 预览行的问题集合使用 `any`，导入错误展示依赖隐式字段。
  - **优化预判**：从预览响应契约提取 `issues` 元素类型，并让表格行泛型贯穿组件。

- **[backend/src/main/java/com/data/collection/platform/service/FactBuildService.java]**
  - **定位**：35-1515 行；构造函数 58-83 行；全量/增量编排 85-285 行；事实映射 935-1297 行；批量搜索索引与基础工具 1317-1505 行。
  - **违规类型**：SRP、DIP、再一再而不再三、KISS。
  - **问题简述**：同一服务同时编排三类事实重建、来源 SQL/查询、实体映射、业务派生、持久化、搜索索引修复和通用转换，并在构造函数内手动创建两个依赖。
  - **优化预判**：先把来源读取/映射、事实派生、批量搜索索引和三类重建编排拆成明确协作者；将手动创建的来源组件改为容器注入，事务边界保持在当前事实替换入口。

- **[backend/src/main/java/com/data/collection/platform/service/statistics/SystemTestDefectSummaryBoardService.java]**
  - **定位**：58-1335 行；定义/导出 105-270 行；快照和规则解释 366-521 行；筛选解析 672-1049 行；明细与汇总 1092-1254 行。
  - **违规类型**：SRP、KISS、开放封闭原则。
  - **问题简述**：看板定义、筛选语义、SQL 加载、内存过滤、快照刷新、规则解释、导出和指标聚合全部集中在一个服务，新增规则会继续修改同一大类。
  - **优化预判**：先抽取查询/过滤、汇总指标、明细导出和规则解释协作者；用现有规则版本、快照请求和测试保护业务口径，不改变统计公式。

- **[backend/src/main/java/com/data/collection/platform/service/GitlabExternalDbService.java]**
  - **定位**：36-570 行；来源扫描 100-311 行；Docker 解析 422-498 行；资源生命周期和配置变更 500-570 行；内部 Direct/Docker adapter 546-570 行。
  - **违规类型**：SRP、DIP、KISS。
  - **问题简述**：来源表发现、扫描策略、Docker `psql` 文本解析、JDBC 连接池生命周期、重试和配置变更均由同一服务协调，双模式边界被实现细节包围。
  - **优化预判**：保留 DIRECT/DOCKER 业务边界，拆出扫描编排、Docker 行解析、连接生命周期和配置响应组件，用端口接口注入适配器。

- **[backend/src/main/java/com/data/collection/platform/service/IssueFactRecordRepository.java]**
  - **定位**：14-1064 行；公开查询 76-413 行；SQL 条件拼装 443-980 行；结果映射 984-1030 行。
  - **违规类型**：SRP、KISS、开放封闭原则。
  - **问题简述**：同一 repository 同时承载系统测试、客户问题、非法记录三套查询范围、动态 SQL 过滤、排序白名单和结果映射，增加一个筛选维度会扩大共享条件分支。
  - **优化预判**：先按领域查询拆分条件构建器和结果读取器，保留唯一事实表查询入口与排序白名单，逐项用现有查询测试锁定行为。

- **[backend/src/main/java/com/data/collection/platform/service/CodeReviewIllegalRecordService.java]**
  - **定位**：40-1317 行；列表/导出 144-563 行；快照/刷新/规则 605-793 行；双来源访问器 912-994 行；响应映射 995-1310 行。
  - **违规类型**：SRP、开放封闭原则、YAGNI 风险。
  - **问题简述**：代码走查非法记录服务同时负责查询、Excel/CSV 导出、快照刷新、兼容模式切换、规则预览、双来源选择和响应映射，来源分支随需求继续增长。
  - **优化预判**：保留已确认的正式/兼容来源边界，拆出导出器、来源访问端口、规则预览和页面响应映射，删除不再需要的重复分支而不新增兼容别名。

- **[frontend/src/components/base/BaseRecordTable.vue]**
  - **定位**：约 1-1397 行；筛选/查询状态 190-512 行；列宽测量 514-755 行；排序、滚动条和布局监听 756-约 1100 行。
  - **违规类型**：SRP、KISS、维护为纲。
  - **问题简述**：通用表格组件同时管理筛选草稿、查询提交、列宽估算、响应式布局、展开行、浮动横向滚动条和展示格式，任何一项变化都可能影响整套组件状态。
  - **优化预判**：先将无业务口径的筛选状态、列宽测量和滚动条生命周期提取为 composable，组件保留渲染和事件编排；固定列宽算法通过现有组件测试和截图回归保护。

- **[frontend/src/views/MirrorSettingsView.vue]**
  - **定位**：约 1-1302 行；配置快照 493-528 行；初始化/延迟加载 431-487 行；同步重试/事实重建 546-605 行；模板和多个控制器挂载区。
  - **违规类型**：SRP、KISS、开放封闭原则。
  - **问题简述**：配置编辑、脏状态、源健康、表级诊断、System Hook、同步重试、事实重建和删除确认在一个页面脚本中共同维护生命周期。
  - **优化预判**：沿现有 `useMirror*Controller` 模式拆分页面协调、诊断加载、配置快照和事实操作，页面只组合状态与展示，不改变并发/保护条件。

- **[backend/src/main/java/com/data/collection/platform/service/statistics/*BoardService.java]**
  - **定位**：9 个看板服务各自存在 `toDetailRecord`，例如 `CustomerIssueDefectSummaryBoardService:851`、`CustomerIssueDefectCauseBoardService:614`、`SystemTestDefectSummaryBoardService:1092`。
  - **违规类型**：相似相溶、再一再而不再三、维护为纲。
  - **问题简述**：相同事实字段到明细记录的基础映射在各看板复制，字段增删和链接/标签展示容易产生口径漂移。
  - **优化预判**：提取共享的事实明细基础映射，再由每个看板追加独有字段；先建立字段快照测试，避免把不同领域的业务列强行合并。

- **[backend/src/main/java/com/data/collection/platform/service/*]**
  - **定位**：`quoteIdentifier` 的独立实现至少位于 `DatabaseBrowserQuerySupport:80`、`GitlabResourceLinkService:220`、`GitlabMirrorSchemaService:515`、`GitlabMirrorPurgeService:171`、`GitlabMirrorTableStorageService:448`、`GitlabSourceScanSqlBuilder:223`、`GitlabSourceHealthService:359`、`GitlabMirrorIndexService:120`；已有共享实现为 `GitlabTypedValuesSqlSupport:53`。
  - **违规类型**：相似相溶、再一再而不再三、DIP。
  - **问题简述**：同一 SQL 标识符引用规则在多个服务中重复实现，校验、转义和异常行为存在分叉风险。
  - **优化预判**：先比较实现语义和调用边界，再将可共用部分收敛到已有 SQL support；对数据库浏览器等不同安全策略保留明确专用入口，不盲目合并。
  - **处理结果**：已新增 `backend/src/main/java/com/data/collection/platform/common/SqlIdentifierSupport.java`，统一无策略的 PostgreSQL 标识符引用和空值边界；资源链接服务保留严格字符白名单，统计明细映射未强行共用。

## 待确认审计项（不得直接按风格重构）

- **[backend/src/main/java/com/data/collection/platform/service/statistics/SystemTestDefectSummaryBoardService.java:672-797]** 的宽泛异常捕获可能是看板“数据缺失时可展示空态”的产品契约；在确认错误边界前只收窄异常类型或增加日志上下文，不直接改成失败传播。
- **[backend/src/main/java/com/data/collection/platform/service/GitlabSourceInstanceSupport.java]** 固定 `default` 与历史多来源字段存在表面冲突，但当前代码和近期变更显示单来源收口是有意设计；必须以现行架构契约确认后才可调整。
- 动态表行、外部 GitLab/CAT payload、ECharts option 和统计明细的 `Map<String,Object>` 是边界数据，不因类型形式本身全部改成 DTO；只处理已知固定契约且能降低错误的部分。
- `toDetailRecord` 的九个实现虽然有相似基础字段，但各自使用私有 `IssueSource`、不同字段集合和不同时间精度；当前没有足够证据证明共享 DTO 或通用映射器能降低复杂度，保留局部实现并将重复字段顺序作为看板契约测试对象。
- `BiDashboardRuntimeFactory` 中的手动创建是为了延迟初始化和避免启动期副作用，不能仅因存在 `new` 认定违规。

## 分阶段重构路线图

1. **阶段一：HTTP 边界与类型收敛（当前）**。合并 `request`/`requestRaw` 的超时取消实现，`any` 收敛为 `unknown` 类型守卫；移动 BI API 客户端及测试到 `api-client`，修复边界门禁。验收：request 定向 Vitest、BI API 定向 Vitest、typecheck、API boundary、diff 检查。
2. **阶段二：前端公共协议工具**。建立错误消息提取、无时区 ISO 显示和记录查询参数序列化的最小工具；按页面逐批迁移，保留各领域不同字段和默认筛选。验收：对应页面 Vitest/mount smoke、typecheck、定向 ESLint，禁止快照或 URL 契约变化。
3. **阶段三：事实构建边界**。先给 `FactBuildService` 的现有输入/输出、事务、来源范围和事实数量补行为测试；随后注入来源组件，按“读取/映射、派生、写入/索引、编排”拆分。验收：事实构建、删除反熵、来源实例、字段契约和任务状态定向测试；后端工具链恢复后再跑 Java 21 编译、Checkstyle、SpotBugs。
4. **阶段四：统计查询与导出**。先锁定各看板明细字段顺序和筛选原语，再按事务边界拆 `SystemTestDefectSummaryBoardService`、`IssueFactRecordRepository`、`CodeReviewIllegalRecordService`；不为相似但不等价的明细映射制造共享 DTO，保持规则版本、快照代际、导出列序和 SQL 范围不变。验收：对应看板/导出/规则解释/详情测试及数据库集成测试。
5. **阶段五：大型 Vue 页面**。沿现有 composable 体系拆 `BaseRecordTable` 和 `MirrorSettingsView` 的状态、布局和生命周期协作者；不在本阶段改 UI 契约或并发策略。验收：组件单测、mount smoke、桌面/移动截图、浏览器控制台和 typecheck/build。
6. **阶段六：审计收口**。复跑全仓重复模式、API 边界、文本/产物门禁，审查新增公共方法中文 Javadoc/JSDoc，更新 `docs/progress.md`；后端工具链不可用时明确列为未验证，不提交“已完成”结论。

## 接口契约

- 本阶段不新增 API、函数、表结构或数据格式。
- 后续任何重构必须保持已发布 HTTP API、数据库迁移历史、事实/投影状态机、外部 CAT/GitLab 契约；内部接口可直接调整，但必须同步所有调用者和测试。

## 风险与假设

- 当前工作树状态不是干净基线，任何审计结论必须区分仓库当前代码、用户未提交修改和历史文档结论。
- 未跟踪迁移 `V20260813_01__remove_bi_code_review_compatibility_sync.sql` 与本地旧兼容表数据存在前提风险，本次不执行迁移。
- 本机没有 `mvn`/Maven wrapper；已使用 Docker 中 Java 21/Maven 3.9 完成本轮后端编译、SQL/镜像/数据库浏览器定向测试和 Checkstyle。SpotBugs 尚未在本轮新改动后单独执行，后续阶段需补跑。
- “所有不遵守规则”不能通过主观风格判断穷尽；报告只将有代码证据、可复现影响或明确契约冲突的项列为确定违规，其余列为待确认审查项。
