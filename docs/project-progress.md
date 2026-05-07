# 数据采集平台项目主文档

更新时间：2026-05-07
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
  - 当前已落地的代码走查外部表单只覆盖“单个外部上下文对应一份当前表单记录”的口径，不等同于通用的多人评判模型。
  - 后续若出现“外部评判类数据源”，需要单独确认并设计多人同权评判能力：同一外部对象允许多个走查人/评判人分别提交判断，记录每个评判人的身份、结论、理由和时间；最终聚合结论按“一票否决”处理，即任一评判人判定非法/有错误，则该数据最终为非法，即使其他评判人判定合法。
  - 这类能力目前未在交接文档中形成明确实现要求，不能直接用现有 `collect_form_records` 的最后一次提交覆盖模型承接。
- CC/DGM 多源镜像隔离
  - 老平台源码 `D:\projects\spidergitdata-dev` 的参考结论：CC 与 DGM 在老平台中不是同库同表混放，而是通过 `spring.datasource` 与 `dgm.datasource` 两个数据库连接隔离；前端访问 DGM 时走 `/dgm` 前缀，Nginx 注入 `Data_Source: DGM` 请求头，后端拦截器再切换到 DGM 数据源。
  - 老平台的实现方式不适合直接移植到新平台，但业务原则需要保留：任何导入、查询、事实构建、导出都必须明确当前处理的是 CC 还是 DGM。
  - 新平台当前 GitLab 镜像表名按源表名生成，例如 `issues` 会进入 `ods_gitlab_issues`，事实构建也仍以 `default` 作为默认来源实例；如果轮流导入 CC 与 DGM 的同名表，存在覆盖、混合和统计口径失真的风险。
  - 推荐的新平台方案是用一个平台库模拟老平台的两个数据源命名空间：优先采用表名前缀隔离，例如 `ods_gitlab_cc_issues`、`ods_gitlab_dgm_issues`；事实层继续使用已有的 `source_instance` 字段写入 `cc` / `dgm`。
  - 明确需要优先关注的功能链路包括：`代码走查多元看板`、`代码走查非法数据`、质量看板中涉及代码走查指标的部分、前后版本/横向对比中涉及代码走查的部分，以及未来若按 CC/DGM 区分的外部代码走查采集表。
  - 系统测试、集成测试和客户问题目前在交接文档中主要是阶段、里程碑或项目范围切换，不是明确的 CC/DGM 数据源切换；除非现场确认这些模块也需要双源并存统计，否则先不扩大 UI 切换范围。
  - 2026-05-07 已开始落地“一个平台库 + 多 GitLab 镜像源 + 来源实例隔离”方案：
    - `gitlab_sync_configs` 新增 `source_instance`，可保存多套 GitLab 源配置，`default` 继续兼容旧配置。
    - ODS 镜像表按来源实例隔离，默认源仍使用 `ods_gitlab_issues`，命名源使用 `ods_gitlab_cc_issues`、`ods_gitlab_dgm_issues` 等表名。
    - 同步任务 scope、补偿调度、Webhook 分发和镜像记录均按配置/来源隔离，避免 CC 与 DGM 的实时自动同步互相复用任务或覆盖记录。
    - `issue_fact`、`merge_request_fact`、`integration_test_fact` 构建时按当前来源实例读取对应 ODS 表，并写入 `source_instance`。
    - 数据镜像设置页新增来源实例字段和已绑定源选择入口，后续还需补“新增源/复制源”的更顺手交互。
  - 2026-05-07 第二轮收口：
    - 使用项目内置 JDK/Maven 跑通后端全量 `mvn test`。
    - Webhook 定时批处理在 registry 表尚未可用时改为 debug 级跳过，避免启动早期或测试早期产生错误日志。
    - 统计看板自动触发事实重建时，如果 GitLab ODS 源表尚未准备好，恢复为空数据降级，不再把源表缺失冒泡成页面错误。
  - 2026-05-07 第三轮收口：
    - 事实构建接口新增可选 `configId`，管理员手动重建 `issue_fact`、`merge_request_fact` 和 `integration_test_fact` 时可以明确指定 GitLab 镜像源；不传时继续兼容默认源。
    - GitLab 镜像同步成功后会按当前 `GitlabSyncConfig` 触发对应来源的增量事实刷新；全量同步后走全量事实刷新，Webhook/增量/补偿同步后走增量事实刷新。
    - 同步后的事实刷新失败只记录 warning，不反向影响镜像同步任务成功状态，避免事实层临时缺表或字段漂移把已经完成的镜像导入打失败。
    - 已通过后端全量 `mvn -q test`、前端 `tsc --noEmit`、Flyway/schema 漂移检查、Flyway 迁移不可变检查、事实字段契约检查和前端 API 边界检查。
  - 2026-05-07 第四轮收口：
    - 记录类 `issue_fact` 查询请求新增可选 `sourceInstance`，客户问题、系统测试议题查询和非法数据列表/导出可按来源实例过滤；不传时保持旧行为。
    - 集成测试分析的项目选项、阶段选项、汇总、明细和导出接口均新增可选 `sourceInstance`，避免 CC/DGM 的 `integration_test_fact` 在查询侧混合统计。
    - 新增集成测试事实查询隔离回归：同一测试阶段下写入 `cc` 与 `dgm` 两组事实，带来源参数查询时只返回对应来源。
    - 已通过后端全量 `mvn -q test`、前端 `tsc --noEmit`、API 契约漂移检查、Flyway/schema 漂移检查、Flyway 迁移不可变检查、事实字段契约检查和前端 API 边界检查。
  - 2026-05-07 第五轮收口：
    - 按最新产品边界收敛为“只有代码走查域面向用户切换 CC/DGM 数据源”，正式记录页和看板结构保持不变，只通过来源参数改变展示数据。
    - `代码走查非法数据` 列表、筛选项、导出和规则预览均已补充 `source` 参数，并下沉到 `merge_request_fact.source_instance` 过滤。
    - `代码走查非法数据` 前端接入与多元看板相同的数据源选择器，路由白名单允许 `source`，规则配置页会继承当前来源。
    - `数据镜像设置` 新增每个 GitLab 源的健康诊断入口，展示当前任务/最近日志、已注册镜像表、实际存在镜像表、代码走查事实数量和缺失关键镜像表。
    - 镜像清理从全局扫描/全局清空改为按当前 `configId` 和来源实例隔离，避免清理 CC 时误删 DGM，或清理默认源时影响命名源。
    - 新增 `V20260507_04__code_review_source_query_indexes.sql`，为代码走查来源切换后的列表查询补 `source_instance + deleted + merged_at_source` 组合索引。
    - 前端对数据源选项和健康诊断响应增加数组兜底，接口异常或内网抖动时页面不会因为响应形状异常直接崩溃。
  - 2026-05-07 code review 风险合并方案第一批落地：
    - Webhook 精确同步队列的合并 key 已加入 `configId`，避免 CC/DGM 中相同 GitLab 对象 ID 在 3 秒批窗口内互相覆盖。
    - Webhook 精确同步队列新增 `webhook-max-queue-size` 上限，达到上限后不再继续堆内存，改触发当前 GitLab 源的增量同步补偿。
    - 外部 GitLab 查询重试从固定间隔改为指数退避 + jitter + 最大延迟上限，降低内网抖动时对 GitLab 源库的同步重试冲击。
    - 事实构建 guard 新增超时恢复，避免异常残留的 single-flight 状态永久卡住后续事实刷新；同一来源内 `all` 与子 scope 互斥，不同来源可并行。
    - `fact_build_tasks` 已保留 `cc:merge-request` 等来源 scope，不再把命名来源的事实任务误归一成 `all`。
    - 数据源健康诊断新增事实层滞后提示：当镜像同步成功但事实表未刷新到同步完成时间之后，会在设置页提示统计可能仍是旧数据。
    - 生产大表索引风险已纳入方案：已执行迁移不直接改写，后续新增大表索引优先采用独立并发建索引迁移或迁移前人工窗口，避免破坏 Flyway checksum。
    - 本轮未直接大拆的架构项：表名前缀隔离改行级来源隔离、搜索影子字段下沉 DB/搜索引擎、服务继承层次重构、Docker CLI 模式替换为长期直连/sidecar。这些属于中长期重构，不应和当前稳定性修复混在同一轮强拆。

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
- 多 GitLab 镜像源混源风险：
  - CC/DGM 采用同一平台库内的来源实例隔离，ODS 镜像表、同步任务、镜像记录和事实层均携带当前来源。
  - 面向用户的数据源切换暂只落在代码走查域，避免把 demo 看板和无明确双源需求的模块扩大改造。
  - 代码走查非法数据的列表、筛选、导出和规则预览已通过 `source` 过滤 `merge_request_fact.source_instance`，不再把不同 GitLab 源的同名项目混在一起。
  - 镜像清理和管理端健康诊断已按 `configId` 隔离，降低多源绑定后误删、误判和启动后状态不可见的风险。
- Webhook 与事实刷新稳定性风险：
  - Webhook 批处理合并 key 已纳入 `configId`，避免不同 GitLab 源的同对象编号被误合并。
  - Webhook 精确同步队列新增内存上限，过载时降级为当前来源的增量同步，优先保证最终一致性。
  - 事实构建 guard 新增超时恢复和来源内 scope 互斥，避免长任务或异常残留造成后续同步永久卡住。
  - 数据源健康诊断已显示事实层滞后状态，避免“镜像成功但页面仍是旧事实”的问题只停留在后台 warning。
- 外部 GitLab 查询重试风险：
  - 外部查询保留失败分类，只对网络/连接类瞬时错误重试。
  - 重试等待改为指数退避 + jitter + 最大延迟上限，避免多个请求在内网抖动时同时重试冲击 GitLab。
- 生产迁移锁表风险：
  - 已明确已执行 Flyway 迁移不可直接改写；大表索引要走新增迁移、并发建索引或迁移前人工执行窗口。
  - `check_schema_flyway_drift.py` 已识别 `concurrently` 索引写法，后续新增非阻塞索引迁移时 schema/Flyway 覆盖检查不会失真。
- 生成物和日志污染认知风险：
  - `.gitignore` 已补充 `.tmp-*`、`tmp-*` 等临时调试文件规则。
  - 新增 `scripts/check_worktree_artifacts.py`，用于阻止日志、构建产物和临时文件被继续纳入版本管理。
  - 新增 `scripts/check_runtime_artifact_locations.py`，用于阻止根目录继续堆积本地日志和临时文件；现有 `backend-start*.log` 已归档到 `.tmp-logs/`。
- 前后端契约漂移风险：
  - 新增 `scripts/check_api_contract_drift.py`，静态比对前端 `api-client` 引用的 `/api/**` 路径是否能在后端 Controller 中找到。
- `schema.sql` 与 Flyway 漂移风险：
  - 新增 `scripts/check_schema_flyway_drift.py`，把表、索引、扩展和最终字段集合纳入可重复静态检查。
- 事实字段契约漂移风险：
  - 新增 `scripts/check_fact_field_contract.py`，检查 `schema.sql`、Flyway 最终字段集合和 `docs/fact-field-contract.md` 是否覆盖关键事实字段。
  - 已修正事实字段文档中的旧字段名，当前以 `category`、`reason_category`、`resolve_sla_days` 和 `phase_search_*` 为准。
- 验证噪音和未来 JDK 兼容风险：
  - Maven 测试阶段已预加载 `mockito-core` javaagent，避免 Mockito 运行时自附加 agent 的未来兼容警告。
  - 测试资源新增 `logback-test.xml`，测试 profile 默认关闭 Spring/MyBatis-Plus banner 并把测试日志收敛到 WARN 级别。
  - Maven 测试和 `backend/run-backend.ps1` 已显式设置 `-Ddebug=false`，避免本机 `DEBUG` 环境变量触发 Spring Boot 条件报告。
  - 新增 `scripts/check_backend_test_hygiene.py`，防止测试 agent 和日志收敛配置被误删。

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
  - `python scripts/check_flyway_migration_immutability.py`
  - `python scripts/check_backend_test_hygiene.py`
  - `python scripts/check_worktree_artifacts.py`
  - `python scripts/check_api_contract_drift.py`
  - `python scripts/check_frontend_api_boundary.py`
  - `python scripts/check_fact_field_contract.py`
  - `python scripts/check_flyway_profile_smoke_coverage.py`
  - `python scripts/check_text_whitespace.py`
  - `python scripts/check_runtime_artifact_locations.py`
  - `mvn -DskipTests compile`
  - `mvn -Dtest=GitlabSourceSchemaGuardTest test`
  - `mvn -Dtest=FlywayMigrationSmokeTest test`
  - `mvn -Dspring.profiles.active=flyway-test -Dtest=FactBuildTaskServiceTest test`
  - `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-local.ps1`
- 2026-05-07 非看板/非外部评判风险收口验证：
  - `mvn -q -Dtest=PlatformAuthorizationInterceptorTest,PlatformStartupSecurityGuardTest,FactBuildOperationGuardTest,CsvExportSupportTest,CollectFormServiceTest,CollectFormControllerTest,FactBuildControllerTest,IntegrationTestControllerTest,ReviewDataRecordServiceTest test`
  - `python scripts/check_schema_flyway_drift.py`
  - `python scripts/check_flyway_migration_immutability.py`
  - `npm.cmd run typecheck`
  - `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-local.ps1`
- 本地工具链状态：
  - 项目 `tools` 目录下已有 JDK 21 与 Maven 3.9.9；新增 PostgreSQL 17.9 客户端到 `tools/postgresql-17.9/pgsql`。
  - `scripts/dev-env.ps1` 已补充 `POSTGRES_HOME`，加载后可直接使用 `java`、`mvn`、`psql`。
  - Flyway 烟测时发现 PostgreSQL 扩展安装在 `public` schema 后，业务 schema 内直接引用 `gin_trgm_ops` 会失败；已改为 `create extension if not exists pg_trgm with schema public` 和 `public.gin_trgm_ops`。
- 本次继续收口的技术风险：
  - 新增 `.gitlab-ci.yml`，把 schema/Flyway 漂移、前后端契约漂移、生成物污染、后端编译、源表守卫测试、前端 typecheck 和 Flyway 烟测接入 CI。
  - 扩展 `scripts/verify-local.ps1`，使本地验证入口与 CI 守卫保持一致。
  - 新增 `scripts/check_text_whitespace.py`，让 CI 能扫描已提交文本文件，本地也能覆盖未跟踪的新文本文件。
  - 新增 `scripts/check_flyway_migration_immutability.py` 和 checksum 清单，防止已锁定 Flyway 迁移被误改。
  - 新增 `scripts/check_backend_test_hygiene.py`，把 Mockito agent、测试日志收敛和 banner 关闭纳入静态守卫。
  - 新增 `scripts/check_frontend_api_boundary.py`，阻止非测试页面绕过 `api-client` 直接调用 `/api`。
  - 新增 `scripts/check_fact_field_contract.py`，把事实字段契约纳入静态守卫，避免 Java 事实生成、SQL 字段和文档说明再次漂移。
  - 非看板页面的 CSV 下载逻辑开始收口到 `frontend/src/utils/csv-download.ts`，减少重复实现。
  - 新增 `application-flyway-test.yml`，提供测试逐步切到 Flyway 初始化的 opt-in profile。
  - `scripts/verify-local.ps1` 和 CI 已加入 `FactBuildTaskServiceTest` 的 `flyway-test` profile 运行，开始把 SpringBootTest 从 `schema.sql` 双轨迁往 Flyway 路径。
  - 新增 `scripts/check_flyway_profile_smoke_coverage.py` 和 `scripts/flyway-profile-smoke-tests.txt`，防止 Flyway profile 代表性测试清单失效或变空。
  - 新增 `docs/flyway-migration-rules.md`，明确已执行迁移不可变、checksum 处理和大表索引策略。
  - 新增 `docs/fact-field-contract.md`，记录 Java 事实字段、SQL 查询字段和前端筛选字段的边界。
  - 新增 `docs/frontend-record-page-rules.md`，约束非看板记录页继续复用共享筛选、分页、导出和明细底座。
  - 新增 `docs/runtime-artifacts.md`，约束日志、临时文件和构建产物的目录与提交前检查。
  - 运行产物治理继续收口：根目录本地日志已移动到 `.tmp-logs/`，CI 和本地验证会拦截新的根目录日志。
  - 后端测试输出继续收口：`mvn -q -Dtest=GitlabSourceSchemaGuardTest test` 已不再输出 Mockito 动态 agent 和 JVM sharing 警告。
  - 本机存在 `DEBUG=release` 环境变量时，`FlywayMigrationSmokeTest` 和 `FactBuildTaskServiceTest` 已不再输出 Spring Boot DEBUG 条件报告。

### 6.7 非看板与非外部评判风险收口

本节排除所有看板模块，也排除“外部评判类数据源的多人同权判定模型”。本次已把可直接落地的风险先收口到代码、迁移和验证脚本中。

已落地：

1. 服务端权限不再只靠前端隐藏入口
   - 新增 `@RequireRole` 与 `PlatformAuthorizationInterceptor`，对高危写操作做后端角色校验。
   - 已覆盖 GitLab 同步配置、连接测试、全量/增量同步、Webhook 注册、任务取消、镜像清理、事实重建、集成测试事实重建、评审数据维护、测试阶段定义维护、代码走查规则预览和实时刷新等入口。
   - 外部采集表不加登录权限，继续保持 GitLab 评论区链接可直接填写。
2. 默认口令和共享环境误启动风险已加硬保护
   - 新增 `PLATFORM_SECURE_CONFIG_REQUIRED` 开关。
   - 开启后，如果仍使用 `admin/admin123`、`approval/approval`、空数据库密码或默认 `GITLAB_WEB_BASE_URL=http://localhost`，后端会启动失败并给出明确原因。
   - 本地开发默认不强制开启，避免影响现有本地调试。
3. 高危操作已有统一审计
   - 新增 `operation_audit_logs` 表和 `PlatformAuditInterceptor`。
   - 所有 `/api/**` 写操作会记录登录用户、角色、HTTP 方法、路径、来源 IP、响应状态和错误摘要。
   - 登录接口本身不写入该通用审计，避免把认证噪音混入业务操作审计。
4. 外部采集表保留免登录，但每次编辑都留痕
   - 新增 `collect_form_record_audit_logs` 表。
   - 每次保存、更新、作废都会记录 `record_id`、动作、`editor_id`、`editor_username`、填写人、来源 IP、User-Agent 和当次表单快照。
   - 当前先保留手动填写模式：前端保存/作废时把“走查人/填写人”同步作为 `editorUsername` 提交；后端同时保存 reviewer 和 editorUsername，便于后续追查“谁编辑过”。
5. 事实重建并发风险已收口
   - 新增 `FactBuildOperationGuard`。
   - 同一 scope 重建会被 single-flight 保护；`all` 与任意子 scope 也互斥，避免事实表中间态和大 SQL 堆积。
6. 评审数据搜索影子字段缺失有了手动回填入口
   - 新增管理员接口 `POST /api/review-data/records/search-index/backfill?batchSize=200`。
   - 可按批回填历史评审数据搜索索引，并返回是否仍存在缺失的搜索字段。
7. 大结果集导出已有上限保护
   - 非看板记录类 CSV 和集成测试明细导出统一加 `10000` 行上限。
   - 超过上限时后端返回业务错误，提示用户缩小筛选条件，避免后端内存和浏览器下载被大结果集拖垮。
8. Flyway/schema 漂移继续由守卫兜底
   - 新增迁移：
     - `V20260507_01__operation_audit_logs.sql`
     - `V20260507_02__collect_form_audit_logs.sql`
   - 已更新 `scripts/flyway-migration-checksums.json`，新增迁移已纳入不可变校验。
   - `schema.sql` 与 Flyway 当前保持一致：19 张表、107 个索引、1 个扩展。

仍需配合真实环境继续做的事项：

1. 旧库大表索引如已存在海量数据，仍建议迁移前单独评估是否需要 `CREATE INDEX CONCURRENTLY` 的人工执行窗口。
2. GitLab 版本、标签口径和 milestone 样本变化仍需要依赖 `issue-source-readiness`、源表守卫和真实样本回归持续兜底。
3. 集成测试备注解析仍需要继续沉淀脱敏现场样本；本次没有扩大隐式解析规则。
4. 评审数据管理、系统测试议题查询和集成测试分析这几个特例页面仍保留边界，后续新增交互时应优先回到共享底座。

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
- CC/DGM 多 GitLab 数据镜像源第一阶段落地
  - `gitlab_sync_configs` 已增加 `source_instance`，用于区分 `default`、`cc`、`dgm` 等来源。
  - 多源导入采用“一个平台库承载多个镜像源、用来源标识和隔离后的 ODS 表名隔离”的方案，而不是把同名表轮流导入同一张 ODS 表。
  - 同步配置、同步任务、同步日志、webhook 事件和调度器均已按配置/来源收口，避免两个 GitLab 源互相抢任务或覆盖日志。
  - GitLab mirror 导入支持按来源生成隔离表名，例如 `ods_gitlab_cc_issues`、`ods_gitlab_dgm_issues`；未指定来源时保留原 `ods_gitlab_issues` 兼容路径。
  - 议题事实和集成测试事实构建已支持按 `GitlabSyncConfig` / `configId` 选择对应来源；同步成功后只触发当前来源的事实刷新，刷新失败只记录告警，不阻断镜像同步成功结果。
  - `issue_fact` 记录查询、导出、集成测试汇总/明细/导出已补充 `sourceInstance` 筛选，避免 CC/DGM 数据在正式记录页混查。
  - 按产品最新边界，前端显式数据源切换只继续落在代码走查域；其他正式模块先保留底层来源隔离能力，不主动暴露 UI 切换。
  - `代码走查非法数据` 已补充 `source` 传递链路：列表、筛选项、导出和规则预览会按 `merge_request_fact.source_instance` 过滤。
  - `数据镜像设置` 已补充来源健康诊断，能看到每个 GitLab 源的任务状态、最近同步日志、镜像表数量、代码走查事实数量和缺失关键表。
  - 镜像清理已改为按当前配置和来源实例隔离，清理某一个源时不会全局删除其他 GitLab 源的镜像表注册、镜像记录或 ODS 表。
  - 已新增代码走查来源查询索引迁移 `V20260507_04__code_review_source_query_indexes.sql`；该迁移已被本地 Flyway 测试库应用，后续不得直接改写，生产大表场景需按 Flyway 规则另走并发建索引策略。
  - 统计看板目前仍按 demo 级别看待，本轮没有继续逐个看板做大结构改造；仅在共享 `IssueFactQueryService` 的通用过滤里保留低侵入 `sourceInstance` 保护，防止已经走共享查询链路的看板混源。
  - 配置保存层已补充来源标识规范化和冲突保护：`sourceInstance` 统一 trim、小写、空值归一到 `default`，长度限制 64；修改已有配置时如果改到另一个已存在来源，会返回明确业务错误，不再让数据库唯一约束或后续同步阶段才暴露问题。
  - 已补充回归测试：
    - `GitlabConfigServiceTest` 覆盖重复来源修改和超长来源标识拦截。
    - `SqlPushdownRealChainTest` 覆盖统计看板 HTTP 链路携带 `sourceInstance` 时只返回对应来源数据。
    - `CodeReviewIllegalRecordSourceLoaderTest` 覆盖同一代码走查事实表中 `cc` 与 `dgm` 隔离查询。
    - `GitlabMirrorPurgeServiceTest` 和 `GitlabSyncControllerTest` 覆盖按配置清理与来源健康诊断。
    - `GitlabWebhookAsyncDispatchServiceTest` 覆盖 Webhook 跨源同对象不误合并、队列过载降级为增量同步。
    - `GitlabExternalDbServiceTest` 覆盖指数退避 + jitter 的重试延迟边界。
    - `FactBuildOperationGuardTest` 覆盖来源内 `all`/子 scope 互斥、不同来源并行和超时恢复。
    - `FactBuildTaskServiceTest` 覆盖来源 scope 不再被误归一成 `all`。
  - 本轮验证通过：
    - `mvn -q -Dtest=GitlabConfigServiceTest,SqlPushdownRealChainTest,StatisticBoardControllerTest,SystemTestDefectSummaryRuleExplanationTest test`
    - `mvn -q -Dtest=CodeReviewControllerTest,CodeReviewRequestAssemblerTest,CodeReviewIllegalRecordServiceTest,CodeReviewIllegalRecordSourceLoaderTest,GitlabMirrorPurgeServiceTest,GitlabSyncControllerTest test`
    - `mvn -q -Dtest=GitlabWebhookAsyncDispatchServiceTest,GitlabExternalDbServiceTest,FactBuildOperationGuardTest,FactBuildTaskServiceTest,GitlabSyncControllerTest test`
    - `mvn -q test`
    - `frontend` 下 `tsc --noEmit --pretty false`
    - `frontend` 下 `vitest run src/views/code-review-multi-board.mount-smoke.test.ts src/views/code-review-illegal-records.mount-smoke.test.ts src/views/code-review-rule-config.mount-smoke.test.ts src/views/mirror-settings.mount-smoke.test.ts`
    - `python scripts/check_api_contract_drift.py`
    - `python scripts/check_schema_flyway_drift.py`
    - `python scripts/check_flyway_migration_immutability.py`
    - `python scripts/check_fact_field_contract.py`
    - `python scripts/check_frontend_api_boundary.py`

## 8. 当前开发约束

后续继续开发时，默认遵循以下规则：

1. 功能参考老平台和交接文档，但实现必须走新平台自己的架构。
2. 统计看板当前仍属于 demo 级别，除非产品决策重新打开，否则不做大规模结构改造；确需补风险时优先放在共享查询底座和事实层边界。
3. 新增条件筛选型记录页优先复用共享条件筛选和共享页面控制器。
4. 新增事实型能力优先补事实层、规则层和查询层，不再先做页面壳子再反补底层。
5. 废弃模块、测试对象模块和正式上线模块要在产品层状态上明确区分，避免用户误解。
6. 外部评判类数据源需求当前暂缓；已明确的刚性边界是免登录填写、手动填写人身份、所有编辑动作留痕，后续正式需求打开时再实现多人同权聚合和“一票否决”。
   - 当前外部采集表就是 GitLab 评论区链接直达填写的业务形态：不加登录权限不是缺陷，手动填写身份不是缺陷，最后一次表单内容覆盖也不是当前阶段要修的问题；系统只要求所有编辑动作持续留痕。后续若要改为自动识别身份、多人明细评判或“一票否决”聚合，必须重新讨论需求后再实现，不能把现有采集表模型当作错误自行修改。

## 9. 下一阶段计划

### 9.1 近期优先级

1. 清理旧文档，只保留本主文档持续维护。
2. 修复非废弃模块的技术层问题，优先恢复验证链路与稳定性。
3. 暂不推进 demo 看板的大结构调整和外部评判类数据源，优先补正式模块的数据口径、导出能力、验证覆盖和多 GitLab 源稳定性。

### 9.2 技术层下一刀

当前优先建议：

1. 继续补强后端与前端验证覆盖，在全量测试已恢复的基础上扩大关键模块保护范围。
2. 继续进入技术层第二阶段，优先收口正式记录页和同步链路的职责过重问题；统计板大组件暂不作为近期主线。
3. 在不动废弃模块的前提下，继续收口非废弃模块的验证缺口。
4. 维持现有特例页面边界，避免再次引入新的隐式耦合。
5. 继续补强 `集成测试数据分析` 的真实备注样例覆盖和全链路验证。

### 9.3 后续功能补齐方向

以下方向已暂缓，等待产品决策重新打开后再推进：

1. `质量看板` 第二轮图表取舍与交互优化
2. `代码走查多元看板` 专题图和筛选细化
3. `系统测试议题多元看板` 专题拆分与视觉收敛
4. 外部评判类数据源能力
5. CC/DGM 多源镜像隔离后续增强：当前第一阶段已用 `source_instance` 与隔离后的 ODS 命名空间承接；后续可继续补充管理端来源切换体验、运行状态展示和真实双 GitLab 环境的联调脚本。
6. 外部评判类数据源的多人同权判定模型：需要支持评判人级别的明细记录、身份留痕、结论聚合和“一票否决”规则，避免最后操作者覆盖前序判断。

## 10. 一句话结论

当前项目已经完成了解耦主线和大部分核心模块落地；在看板和外部数据源暂缓后，近期重点转为“单文档治理 + 技术层稳固 + 正式模块口径与验证补强”。
