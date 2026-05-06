# 数据采集平台项目主文档

更新时间：2026-05-06
适用项目：`D:\projects\data_collection_platform`

## 1. 文档规则

- `docs/project-progress.md` 是当前项目唯一保留并持续维护的主 Markdown 文档。
- 其他阶段性 `current-state` / `plans` / `progress` 文档不再保留，避免多份口径并存。
- 后续所有项目进展、功能缺口、技术债、验证状态和下一步计划都统一收口到本文档。
- 当本文档明显过长、已不利于快速判断现状时，再做一次结构化整理，但仍保持单主文档策略。

## 2. 项目定位

当前项目是一个以 GitLab 数据镜像为数据入口、以本地事实层和统计分析为核心、同时承载评审数据、代码走查、集成测试、系统测试、客户问题和系统设置能力的单体平台。

当前总体方向已经明确：

- 功能参考老平台和交接文档
- 实现遵循新平台自己的架构、分层和复用底座
- 优先走真实链路，不为了补页面而反向牺牲底层设计

## 3. 当前真实状态

### 3.1 架构主线

整体解耦主线已完成，项目已进入“基于统一底座继续补功能和做稳定化”的阶段。

当前已经建立的核心底座包括：

- `issue_fact` 共享事实读取与消费链路
- 统计板通用运行时与注册机制
- 前端 `feature-manifest.ts` 单一页面契约与导航清单
- 前端 `api-client/*` 领域化 API 分层
- 前端条件筛选状态与记录页控制器复用链路
- 代码走查、客户问题、系统测试、评审数据等模块的共享页面底座

### 3.2 模块现状

#### 质量看板

- 顶部模块已从“统计分析”改名为“质量看板”。
- `镜像表基础统计` 导航入口已移除。
- 当前保留：
  - `研发质量看板`
  - `其他看板`
- 当前问题：
  - 这两个入口已切换为真实 ECharts 看板：首页负责强信号概览，其他看板负责跨域辅助分析。

#### 评审数据

- `评审数据管理` 已是正式页面。
- 已完成服务拆分、条件筛选状态收口、页面控制层收口，并将前端列表、路由、详情、导出、问题项与弹窗动作进一步拆成 `review-data/*` composable 组；详情抽屉、帮助抽屉、行级动作与问题清单展开区也已从主页面中拆出。后端 `ReviewDataController` 已引入专门的 list request DTO 与 assembler，开始收口 web 请求组装边界。
- 当前仍保留特例装配层，不强行并入通用记录页控制器。

#### 代码走查

- `代码走查非法数据`、规则配置页、外部表单页均已落地。
- `代码走查多元看板` 已切换为真实 ECharts 看板，支持 `CC / DGM` 数据源切换、摘要卡片、模块分布与责任人分布。

#### 集成测试

- `集成测试数据分析` 已从占位页替换为真实页面。
- 已建立独立 `integration_test_fact` 事实链路。
- 已具备：
  - 项目筛选
  - 测试阶段筛选
  - 模块汇总
  - 模块详情分页
  - 手动重建事实
- 当前仍需继续补强数据口径、导出能力和更完整的验证链路。
- 本次已补强：
  - 支持模块明细 CSV 导出。
  - 集成测试备注解析从事实构建服务中抽出独立解析器，并补充 markdown 表格行容错。
  - 集成测试备注解析支持冒号、半角等号、全角等号和数字单位混用。
  - 集成测试备注解析支持横向 markdown 汇总表，即一行表头、一行数据的模板写法。
  - 集成测试校验口径已扩展到完整统计字段：核心字段必填、所有统计字段不能为负数、执行用例总数必须等于通过用例数加本次未通过用例数。
  - 明细抽屉已展示校验口径说明，降低“待补充 / 待确认”标签的理解成本。
  - 补充 controller、解析器、规则层、事实构建到查询层的后端链路测试，以及前端挂载导出冒烟测试。
  - 标签归类已补强：`R1集成测试` 等阶段标签、`新功能 / 老功能 / 增强功能` 等功能分类标签不再被误识别为模块。
- 后续仍需继续补强：
  - 更多真实备注样例覆盖
  - 更大样本下的数据口径回归覆盖

#### 系统测试

- 已落地：
  - `系统测试缺陷汇总`
  - `申请延期缺陷分析`
  - `缺陷原因分析`
  - `议题阶段统计`
  - `议题查询`
  - `系统测试非法数据`
- 当前保留：
  - `议题多元看板` 已落地为真实 ECharts 看板，聚合系统测试正式统计板结果并保留回正式页入口

#### 客户问题

- 已落地：
  - `缺陷汇总`
  - `缺陷非法数据`
  - `缺陷原因分析`
  - `CC_PRODUCT议题`
  - `延期问题`
  - `缺陷响应效率`
  - `按功能展示缺陷数量`
- 已统一接入共享条件筛选、规则说明、刷新和记录页控制器底座。

#### 系统设置

- 已落地：
  - `数据镜像设置`
  - `数据库查看`
  - `议题测试阶段定义`
- `模块管理` 占位入口已删除，不再作为正式功能存在。

## 4. 已完成的关键建设

### 4.1 镜像与数据基础

- 已完成 GitLab 镜像全量同步、增量同步、补偿同步和白名单模式。
- 已支持 Docker 模式读取外部 GitLab 数据库。
- 已支持镜像进度、状态、日志与可视化设置页。
- 已修复 `ALL` 白名单模式的真实表发现逻辑。
- 已优化镜像写入性能和时区处理。

### 4.2 事实层与统计层

- 已完成 `issue_fact` 规范化和共享消费。
- 已把系统测试、客户问题的核心统计板收口到共享统计板运行时。
- 已明确搜索 SQL 下沉的一期架构：Java 生成 `normalized / compact / spell / initials` 搜索影子字段，SQL 基于持久化索引字段完成模糊匹配、拼音搜索和首字母搜索。
- 已新增 Flyway 迁移 `V20260506_01__search_and_fact_query_indexes.sql`，开始纳管评审数据、议题事实和合并请求事实的搜索索引列、分类查询字段及关键查询索引。
- 已新增 Flyway 迁移 `V20260506_02__gitlab_sync_core_schema.sql`，开始纳管 GitLab 同步配置、任务、日志、Webhook、镜像记录和采集表单记录等基础表。
- 已新增 Flyway 迁移 `V20260506_03__operational_support_schema.sql`，继续纳管代码走查外部指标、集成测试事实、测试阶段日历、模块字典和镜像表注册表。
- 已提供通用统计板接口：
  - 统计数据
  - 明细下钻
  - 规则说明
  - 导出
  - 状态查询
  - 手动刷新

### 4.3 前端底座

- 已完成 `api.ts` 拆分，形成 `api-client/*` 领域化结构。
- 已完成共享类型下沉与契约收口。
- 已完成 `feature-manifest.ts` 作为页面契约和导航单一来源。
- review-data、customer-issues、question-metrics 三条记录链路的 controller 已开始统一成“web request DTO + assembler + service request”模式，减少 controller 手工组装噪音。
- 已完成：
  - `useConditionFilterGroupState`
  - `useRecordPageController`
  - `useRuleExplanationPanel`

### 4.4 页面与交互

- 记录类页面已统一走可复用底座。
- 统计类页面已统一走统计板抽象链路。
- `BaseRecordTable` 默认字段已统一居中，短字段展示更稳定。

## 5. 当前功能缺口

当前真正仍未完成、且不是已明确废弃范围的功能缺口，主要有：

1. `质量看板` 已承接真实内容，但图表取舍与口径仍需要继续根据用户反馈收敛。
2. `代码走查多元看板` 已落地真实图表页，后续重点转为补充更细的专题图和交互细节。
3. `系统测试议题多元看板` 已落地真实图表页，后续重点转为继续优化图表筛选与专题拆分。

以下项不纳入当前主动补齐范围：

- 看板类功能
  - `质量看板`、`代码走查多元看板`、`系统测试议题多元看板` 第一版已落地。
  - 当前不再是“是否承接内容”的问题，而是“继续按用户反馈优化图表价值和布局”。
- 外部数据源类功能
  - 包括新的外部数据接入、外部数据源配置和外部数据源看板化扩展。
  - 已按产品决策暂缓，不作为近期实现目标。

## 6. 当前技术债与需补项

### 6.1 明确保留的特例

- `ReviewDataManagementView`
  - 为什么暂时保留：评审数据管理同时包含记录列表、问题项展开、详情抽屉、帮助抽屉、行级动作和导出等复合交互，已经拆出 composable 组和局部组件，但页面仍承担特例编排职责。
  - 什么时候收口：当记录页共享控制器能够稳定承载“记录列表 + 行级动作 + 多抽屉 + 问题项子列表”组合后，再把剩余页面编排迁入通用记录页壳层。
- `IntegrationTestAnalysisView`
  - 为什么暂时保留：集成测试分析页同时承载项目/阶段切换、summary table、detail drawer、rebuild、export 和校验提示，且 `integration_test_fact` 真实备注样本仍在补厚。
  - 什么时候收口：当事实构建链路的真实样本回归覆盖稳定，并且 summary/detail/export 三类接口形成统一数据页契约后，再抽成通用事实分析页模式。
- `SystemTestIssueSearchView`
  - 为什么暂时保留：该页仍使用旧式筛选模型，当前已有新的系统测试非法数据页承接正式条件筛选能力，强行迁移会同时牵动旧查询、路由 query 和结果展示。
  - 什么时候收口：当系统测试搜索的字段模型可以复用 `system-test-condition-fields`，并完成旧筛选 query 到条件筛选 DSL 的兼容映射后，再迁入共享记录页控制器。
- `StatisticBoardView` / `StatisticBoardPage`
  - 为什么暂时保留：统计板底座已经承载多个正式看板，但单个核心组件仍同时处理数据加载、详情抽屉、规则说明、列设置、排序、路由 query 同步、分页和 realtime 状态。
  - 什么时候收口：优先拆出 detail drawer controller、rule explanation panel、view settings / column prefs、realtime toolbar/status；这些块具备独立测试后，再评估是否下沉成更通用的 board runtime。

### 6.2 验证层仍需补强

- 前端构建已可通过。
- 路由契约测试已具备基础覆盖。
- 集成测试控制器已有单测。
- 后端全量测试链路已恢复，但共享底座与关键正式页面的保护范围仍需继续扩大。
- issue fact 记录查询链路的第一轮后端结构重构已完成，但前端记录页与统计板层面的进一步去重仍未开始。

### 6.3 体验层仍可优化

- 不同页面之间的切换仍存在“闪一下 / 空一下”的观感问题。
- 当前不应通过增加耦合或重缓存的方式强行抹平，需要后续在壳层或渐进加载策略上单独设计。

### 6.4 数据口径仍需继续完善

- 集成测试模块当前已有真实链路，且已完成第一轮解析容错、导出能力和核心校验口径补强；仍需继续补：
  - 更多真实备注样例覆盖
  - 更完整的验证覆盖

### 6.5 SQL 下沉与 Flyway 纳管

- 当前搜索能力采用“Java 生成索引字段，SQL 消费索引字段”的一期方案。
  - Java 仍负责拼音、首字母、紧凑文本等搜索影子字段生成。
  - SQL 已负责记录页关键词查询、高级筛选、分页、排序和部分非法判定。
- 本次已开始把搜索和分类查询所需的数据库结构迁入 Flyway：
  - `review_records` 的搜索字段与标题专用搜索字段。
  - `issue_fact` 的分类、非法原因、阶段筛选和多字段搜索索引字段。
  - `merge_request_fact` 的关键词搜索字段和责任人搜索字段。
  - 相关 `pg_trgm`/GIN 索引和记录页分页排序索引。
- 本次继续把 GitLab 同步基础结构迁入 Flyway：
  - `gitlab_sync_configs`、`gitlab_sync_logs`、`gitlab_sync_tasks`、`gitlab_webhook_events`。
  - `gitlab_mirror_records` 和 `collect_form_records`。
  - 同步任务去重、范围状态、日志按配置查询、镜像表查询和采集表单上下文索引。
- 本次继续把剩余核心支撑表迁入 Flyway：
  - `code_review_external_metrics` 和 `integration_test_fact`。
  - `testing_phase_calendar`、`module_dictionary` 和 `sys_table_registry`。
  - 集成测试事实、测试阶段、模块字典和镜像表注册相关索引。
- 已完成 `schema.sql` 与 Flyway 迁移的静态覆盖比对：
  - 17 张表、103 个索引、1 个扩展均已在 Flyway 迁移中纳管，无缺口。
  - 17 张表的最终字段集合已完成比对，无缺表、无漏列。
  - `alter table add column` 补列项覆盖 `schema.sql` 的 108 项，并额外保留 7 项旧库兼容补列。
  - 主配置已切换为 Flyway 默认入口，`schema.sql` 初始化改为通过 `SPRING_SQL_INIT_MODE` 按需启用。
- 当前仍未下沉的部分：
  - 拼音/首字母索引生成仍在 Java，暂不改为数据库函数。
  - 议题分类、非法原因、SLA、历史遗留等事实计算仍在 Java 事实构建阶段完成。
  - 统计看板中仍有部分聚合在 Java 内存层完成，后续按性能和口径稳定性逐步评估。
  - 测试 profile 仍保留 `schema.sql` 初始化，待真实 PostgreSQL 烟测可运行后再评估是否切到 Flyway。
- 后续建议：
  - 已在本地补齐 Maven/JDK/PostgreSQL 客户端工具链，并通过 `FlywayMigrationSmokeTest` 确认空库可由 Flyway 独立建出完整基础结构。
  - 为大表环境评估 `CREATE INDEX CONCURRENTLY` 的独立迁移策略。
  - 如果这些版本号迁移已经在共享库执行过，后续不要直接修改已执行 SQL 文件；说明性内容改走新迁移或统一执行 Flyway repair 后再校验。
  - 补统一索引重建入口，避免历史数据缺失搜索影子字段时长期走 Java fallback。

### 6.6 风险修复进展

- GitLab 源表依赖风险：
  - 新增 `GitlabSourceSchemaGuard`，事实构建执行大 SQL 前先检查 ODS 源表和关键字段。
  - 议题事实、合并请求事实、集成测试事实分别走独立源表检查，缺表或缺字段时返回明确业务错误。
- 事实构建大 SQL 复杂度风险：
  - 议题、合并请求和集成测试事实源查询已接入 `SqlQueryMonitor`，超过阈值时输出压缩后的 SQL 与参数数量。
  - 源表检查前置后，GitLab 版本或白名单变化导致的字段缺失不会再直接落到难读的大 SQL 报错里。
- 生成物和日志污染认知风险：
  - `.gitignore` 已补充 `.tmp-*`、`tmp-*` 等临时调试文件规则。
  - 新增 `scripts/check_worktree_artifacts.py`，用于阻止日志、构建产物和临时文件被继续纳入版本管理。
- 前后端契约漂移风险：
  - 新增 `scripts/check_api_contract_drift.py`，静态比对前端 `api-client` 引用的 `/api/**` 路径是否能在后端 Controller 中找到。
- `schema.sql` 与 Flyway 漂移风险：
  - 新增 `scripts/check_schema_flyway_drift.py`，把表、索引、扩展和最终字段集合纳入可重复静态检查。

## 7. 当前验证状态

已确认通过的验证包括：

- 前端 `npm run build`
- 前端 `npx vitest run src/router.test.ts`
- 集成测试模块相关控制器测试
- 后端若干统计板、筛选支持、镜像与控制器切片测试
- 后端局部 `compile`
- 后端 `mvn test`

本次新增但尚未在当前机器完成验证：

- 新增 Flyway 迁移：
  - `backend/src/main/resources/db/migration/V20260506_01__search_and_fact_query_indexes.sql`
  - `backend/src/main/resources/db/migration/V20260506_02__gitlab_sync_core_schema.sql`
  - `backend/src/main/resources/db/migration/V20260506_03__operational_support_schema.sql`
- 扩展 Flyway 烟测：
  - `FlywayMigrationSmokeTest` 现在会检查搜索索引列、分类查询字段和关键索引是否由 Flyway 创建。
  - `FlywayMigrationSmokeTest` 现在也会检查 GitLab 同步基础表、采集表单记录表和关键同步索引是否由 Flyway 创建。
  - `FlywayMigrationSmokeTest` 现在也会检查代码走查指标、集成测试事实、阶段日历、模块字典和镜像表注册结构是否由 Flyway 创建。
- 完成 Flyway 覆盖静态比对：
  - `schema.sql` 中的 17 张表、103 个索引、1 个扩展在 Flyway 迁移中无缺口。
  - 17 张表的最终字段集合也已完成比对，无缺表、无漏列。
  - 主配置默认关闭 `schema.sql` 初始化，改由 Flyway 作为建库入口；测试 profile 暂保留原初始化链路。
- 本次新增风险守卫的当前机器验证：
  - `python scripts/check_schema_flyway_drift.py`
  - `python scripts/check_worktree_artifacts.py`
  - `python scripts/check_api_contract_drift.py`
  - `mvn -DskipTests compile`
  - `mvn -Dtest=GitlabSourceSchemaGuardTest test`
  - `mvn -Dtest=FlywayMigrationSmokeTest test`
- 本地工具链状态：
  - 项目 `tools` 目录下已有 JDK 21 与 Maven 3.9.9；新增 PostgreSQL 17.9 客户端到 `tools/postgresql-17.9/pgsql`。
  - `scripts/dev-env.ps1` 已补充 `POSTGRES_HOME`，加载后可直接使用 `java`、`mvn`、`psql`。
  - Flyway 烟测时发现 PostgreSQL 扩展安装在 `public` schema 后，业务 schema 内直接引用 `gin_trgm_ops` 会失败；已改为 `create extension if not exists pg_trgm with schema public` 和 `public.gin_trgm_ops`。

已修复并恢复：

- 后端全量测试链路
  - 本次修复了 2 处测试代码未跟随服务重构更新的问题：
    - `CodeReviewIllegalRecordServiceTest`
    - `SystemTestDefectSummaryRuleExplanationTest`
- 非废弃模块的第二批验证补强
  - 新增客户问题两个正式统计板的控制器测试覆盖：
    - `customer-issue-response-efficiency`
    - `customer-issue-by-function`
  - 新增 `IntegrationTestAnalysisView` 前端挂载与明细抽屉冒烟测试
- 非废弃模块的第三批验证补强
  - 新增客户问题正式记录页与非法数据页前端冒烟测试：
    - `CustomerIssueRecordsView`
    - `CustomerIssueIllegalRecordsView`
  - 新增 `StatisticBoardPage` 路由到统计板键的挂载冒烟测试
  - 新增客户问题正式记录接口控制器测试：
    - `CustomerIssueControllerTest`
  - 新增共享前端底座单测：
    - `useRecordPageController`
    - `useRuleExplanationPanel`
- 技术层第一阶段重构
  - 三类 `issue_fact` 记录查询服务已统一切到请求对象模式：
    - `CustomerIssueRecordService`
    - `CustomerIssueIllegalRecordService`
    - `SystemTestIssueSearchService`
  - 新增共享记录查询基类：
    - `AbstractIssueFactRecordListService`
  - 新增共享请求对象：
    - `IssueFactRecordListRequest`
    - `CustomerIssueRecordQueryRequest`
    - `CustomerIssueIllegalRecordQueryRequest`
    - `SystemTestIssueSearchQueryRequest`
  - 同步补充 service 单测：
    - `CustomerIssueRecordServiceTest`
    - `CustomerIssueIllegalRecordServiceTest`
    - `SystemTestIssueSearchServiceTest`
  - 相关 controller 切片测试已随签名收口同步更新：
    - `CustomerIssueControllerTest`
    - `QuestionMetricsControllerTest`
- 系统测试非法数据
  - 已按交接功能口径完成新平台实现，未移植老平台代码。
  - 后端复用 `issue_fact`、系统测试范围规则、共享记录查询基类和条件筛选规则。
  - 前端复用客户问题非法数据页抽象出的共享记录页、条件筛选、规则说明和记录页控制器。
  - 已补充 service/controller 单测与前端挂载冒烟测试。
- 集成测试数据分析第一轮补强
  - 新增模块明细 CSV 导出接口与前端导出按钮。
  - 导出接口显式使用 UTF-8，避免中文 CSV 响应乱码。
  - 新增 `IntegrationTestNoteParser`，支持 `key: value`、`key = value`、`key＝value`、两列 markdown 表格和横向 markdown 汇总表等备注格式。
  - 校验口径扩展到完整统计字段：
    - `执行用例总数`、`通过用例数`、`本次未通过用例数` 为必填核心字段。
    - `执行用例总数 = 通过用例数 + 本次未通过用例数`。
    - `执行用例总数`、`通过用例数`、`本次未通过用例数`、`初始未通过用例数`、`本次问题用例数`、`用例外问题数` 均不能为负数。
  - 补充 `IntegrationTestControllerTest`、`IntegrationTestNoteParserTest`、`IntegrationTestFactRulesTest` 和前端导出/口径说明冒烟覆盖。
- 集成测试数据分析第二轮补强
  - 新增 `IntegrationTestFactPipelineTest`，覆盖 GitLab ODS 最小数据、备注解析、`integration_test_fact` 构建、模块汇总和明细查询的端到端后端链路。
  - 修复标签归类规则，避免阶段标签和功能分类标签混入模块口径。
  - 补充 `IssueFactNormalizationRulesTest` 对阶段/功能标签不入模块的单元保护。
  - 补充更贴近现场备注的解析样例：支持 `###` 等任意级 markdown 标题作为段落边界，避免后续章节覆盖集成测试数据。
  - 数字解析改为取首个整数，避免 `10（含自动化2条）` 被误读为 `102`。
  - 在当前 `integration_test_fact` 一条 issue 一条事实的模型下，横向表多功能行按同一 issue 聚合：计数字段求和，功能和执行人去重拼接。

## 8. 当前开发约束

后续继续开发时，默认遵循以下规则：

1. 功能参考老平台和交接文档，但实现必须走新平台自己的架构。
2. 新增统计板优先接入现有统计板运行时和 `feature-manifest.ts`，不再绕开底座单独写一套。
3. 新增条件筛选型记录页优先复用共享条件筛选和共享页面控制器。
4. 新增事实型能力优先补事实层、规则层和查询层，不再先做页面壳子再反补底层。
5. 废弃模块、测试对象模块和正式上线模块要在产品层状态上明确区分，避免用户误解。
6. 看板类与外部数据源类需求当前暂缓，除非产品决策重新打开，否则不主动推进。

## 9. 下一阶段计划

### 9.1 近期优先级

1. 清理旧文档，只保留本主文档持续维护。
2. 修复非废弃模块的技术层问题，优先恢复验证链路与稳定性。
3. 暂不推进看板和外部数据源，优先补正式模块的数据口径、导出能力和验证覆盖。

### 9.2 技术层下一刀

当前优先建议：

1. 继续补强后端与前端验证覆盖，在全量测试已恢复的基础上扩大关键模块保护范围。
2. 继续进入技术层第二阶段，优先收口统计板与记录页前端大组件的职责过重问题。
3. 在不动废弃模块的前提下，继续收口非废弃模块的验证缺口。
4. 维持现有特例页面边界，避免再次引入新的隐式耦合。
5. 继续补强 `集成测试数据分析` 的真实备注样例覆盖和全链路验证。

### 9.3 后续功能补齐方向

以下方向已暂缓，等待产品决策重新打开后再推进：

1. `质量看板` 第二轮图表取舍与交互优化
2. `代码走查多元看板` 专题图和筛选细化
3. `系统测试议题多元看板` 专题拆分与视觉收敛
4. 外部数据源类能力

## 10. 一句话结论

当前项目已经完成了解耦主线和大部分核心模块落地；在看板和外部数据源暂缓后，近期重点转为“单文档治理 + 技术层稳固 + 正式模块口径与验证补强”。
