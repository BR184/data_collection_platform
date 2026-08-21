## architecture.md 文档总则

> 本文档为项目的技术架构权威记录。AGENTS.md 中关于文档职责、更新触发条件和事实优先级的规则同样适用于本文件。
> AI 读取本文件时应将其中内容视为当前技术事实；修改项目代码前必须确认相关架构约定，任何与本文矛盾的设计需先修正本文或取得用户确认。
> 本文档与 `product.md`、`progress.md` 职责分离：产品需求与用户承诺写入 product；当前进度、阻塞项与下一步写入 progress；系统边界、模块职责、数据流、不变量、性能约束、关键决策及技术风险写入 architecture。
> 内容组织以模块为单元，每模块陈述：职责、对外接口、内部核心模型、数据所有权、关键不变量、性能目标、已知限制与演进方向。避免重复 product 的内容，只描述技术“是什么”和“必须满足什么”，不解释“为什么需要”（除非该理由本身就是技术约束）。
> 更新触发：新增/移除/重构模块、变更模块边界或数据所有权、修改不变量或性能契约、技术选型替换、发现并确认长期技术风险。纯实现细节、临时工具或测试辅助代码不进入本文。
> 保持行文紧凑，使用最少的 token 表达完整约束，优先用代码标识、ID、数字和简短断言，避免叙述性解释和展示性列表。所有陈述必须可被验证（代码、测试、运行结果），否则不得写入。

## 系统基线

- 形态：Spring Boot 单体后端 + Vue 3/TypeScript/Vite 前端 + PostgreSQL；前端默认 `18181`，后端默认 `18080`。
- 数据入口：GitLab 镜像表（ODS）→ 事实层 → 统计服务/快照 → 平台页面和导出；真实进程外消费者可按 `docs/decisions.md`（D-02）使用默认关闭的外部只读数据集 API。
- 核心事实表：`issue_fact`、`merge_request_fact`、`integration_test_fact`；评审页面使用 `review_visible_*` 统一读模型：兼容态合并正式评审表与未被平台接管的兼容快照，正式态只读正式表。
- 系统测试事实来自 `issue_fact`，只表达议题/缺陷数量、级别、状态、原因、延期和缺陷修复率；不存在系统测试执行用例数、通过用例数或系统测试通过率字段。
- 产品版本解析以有效 `product_version_id` 为第一权威；缺失时由统一版本规则从项目名称主体匹配已登记版本，组合名称展开为多个版本成员，无法确定版本的事实不进入需要确定版本的统计。平台页面、快照、导出和经授权的外部 Provider 不得保留页面级版本特例，详细规则归 `docs/platform-page-business-rules.md`。
- 普通评审读模型只承载需求、设计、单元测试用例、集成测试用例、系统测试用例五类已确认类型；`code_review_formal_records` 是独立代码走查域。未知 `review_type` 保留原值和追溯样例，但不得被 `其他` 或标题匹配自动归入普通评审。
- 系统测试与客户问题的可选范围统一以项目级“议题范围目录”为权威来源。目录以稳定业务键、可修改显示名和精确事实成员三层表达；管理员顺序中的第一条启用范围是统一默认值。系统测试目录维度为 `TESTING_PHASE`，成员是 `issue_fact.testing_phase` 的配置值，但页面如何接纳含多个成员的复合事实由页面业务契约决定：统一 `SystemTestPhaseMembershipPolicy` 显式选择精确成员或包含成员，SQL 与内存过滤必须使用同一模式，并在一次请求开始时编译为不可变成员快照，禁止逐事实重复读取目录。客户问题目录维度为 `MILESTONE`，成员匹配 `issue_fact.milestone_title` 时不区分大小写但保留空白等真实值差异。客户事实发布时只向业务键相同的既有启用范围补充缺失成员，不创建范围、不启用停用范围、不改变显示名或顺序。显示名修改不得改变事实匹配，目录变更通过统一源版本使统计与记录快照失效。
- 数据库迁移统一使用 Flyway；已执行迁移不可修改，新增结构或数据变更必须新建迁移。
- Flyway 是建库、测试初始化和升级的唯一结构入口；共享库已执行迁移不可修改，结构修复使用新前向迁移，结构变更与大规模回填分开。测试使用独立 schema 并在每个测试 JVM 首次使用该 schema 时清理后完整迁移，不维护第二份结构快照。
- 平台库为 `qaflex`；GitLab 源库为 `gitlabhq_production`；老平台 MySQL 库为 `gitlab_spider`。三者边界不可混用。

## 模块与数据所有权

### 镜像与同步

- 负责 GitLab 连接、表白名单、增量/补偿同步、任务状态、日志和 Webhook 事件。
- 镜像层拥有 ODS 原始数据和同步水位；同步成功不代表事实层已重建成功，事实刷新状态必须独立记录。
- 同一镜像运行的表 worker 数与 DIRECT JDBC 池容量由 `SyncExecutionBudget` 一次解析；`sync_runs.resolved_worker_count` 是该运行不可变并发快照，DIRECT 池容量等于 worker 数加控制面保留连接。连接池按稳定 `configId` 唯一管理，连接或线程配置只能在无活动镜像运行时修改，配置事务提交后旧池退休且不打断已借出连接。
- 调度器每次领取运行必须生成唯一 `lease_owner`；首次心跳、周期续租、暂停和终态提交均以 `(run_id, lease_owner)` fencing，租约转移后的旧执行器不能覆盖新 owner。执行器拒绝入队时仅释放自己拥有的运行并归还本地容量；截止时间和人工取消使用字段级状态 CAS，禁止用陈旧全实体更新覆盖租约。
- 表任务是唯一分页恢复边界：源端读取不持有平台库事务，本地镜像写入、变化目标登记、任务 owner 条件完成、状态水位和下一阶段任务创建在同一事务提交。`SCAN` 续页使用独立任务行；每张表的 `RECONCILE` 只使用一个任务行原子累计计数并推进 keyset cursor，不按页增长任务数。任务执行期间按租约心跳续期，租约转移后的旧 worker 不得提交状态或镜像副作用。
- 动态 ODS 表及其查询索引由运行期 schema 控制面统一管理，因为 Flyway 执行时动态表可能尚不存在。每轮在任何表任务入队前按稳定顺序准备全部当前白名单表，并以 `CREATE INDEX CONCURRENTLY` 补齐物理列完整的索引、重建失效同名索引；worker 数据面不得并行建表或维护索引。
- 表任务的连接、超时、锁/死锁、事务回滚和临时资源异常在原任务内按上限退避重试，父运行复用 `RETRYING/run_after` 等待该任务；租约超时使用同一状态路径。未定义表/列、非法配置和业务契约错误直接失败；任何失败重试都不得推进 cursor、水位或事实发布门禁。
- 源端扫描只使用真实类型复合主键 keyset：全量与删除对账按主键 tuple 分页；增量仅在更新时间列存在有效非部分 B-tree 前导索引时使用 `TIMESTAMP_KEYSET`，否则在固定 `(watermark, scan_upper_bound_at]` 窗口内使用 `PRIMARY_KEY_KEYSET`，保证每次运行最多按主键线性扫描一次。复合游标统一为 JSON 数组并持久化 `page_number`；状态水位只在末页推进。禁止按文本化主键排序、哈希分片重复扫表、`OFFSET` 分页和逐页 `count(*)`。
- GitLab 来源时间必须按物理列类型解释：DIRECT JDBC 读取结果同时保留 `ResultSetMetaData.getColumnTypeName()`，`timestamp without time zone` 保留来源墙上值，`timestamp with time zone`/`timestamptz` 按同一瞬间归一为 UTC `LocalDateTime`；DOCKER JSON 的无偏移文本保留墙上值、带偏移文本归一为 UTC。时间窗口 SQL 必须按来源 schema 生成匹配的 `timestamp`/`timestamptz` 字面量，不能只按 JDBC 通用类型或 JVM 默认时区转换。
- 同一数据源保持单一镜像写入所有权。用户 `TABLE_REFRESH`、`INCREMENTAL_SYNC` 和 System Hook 属于前台运行，独立低优先级 `DELETE_RECONCILIATION` 与全量/补偿运行只在已提交分页边界让行；自动增量仍只为等待中的单表刷新让行。让行以 owner CAS 转为 `PAUSED` 并释放互斥范围，前台运行结束后从持久 cursor 恢复；已经开始的源查询和平台事务不中断。
- 页面同步命令只引用已持久化的 `configId`，不得隐式保存配置；表单存在未保存变更时必须先显式保存。活动镜像运行期间后端继续拒绝连接与容量配置变更，不能为提交前台任务绕过该保护。
- 同步诊断只把当前活动运行的任务计入当前失败/超时，历史累计使用独立字段；当前表任务必须暴露 `taskId/stage/cursor/retry/heartbeat/lease`。DIRECT 模式同时暴露 Hikari 活动、空闲、等待和容量指标；源总量未知时前端使用不确定进度，不根据动态分页任务数伪造百分比。同步 JSON 日志统一携带 `runId`、`runDbId`、`taskId`、`sourceTable`、`configId`、`sourceInstance`、`runType` 和 `action`。详细决策见 `docs/decisions.md`（D-04）。
- `GitlabSourceLineageCatalog` 是 25 张推荐来源表的主键、删除探测资格、权威父子范围和派生归属唯一目录；评论和标签分别用 `(noteable_type,noteable_id)`、`(target_type,target_id)` 隔离多态对象。`GitlabFactDependencyCatalog` 统一维护平台通用事实读取表与变化信号表，`RealtimeWorkspaceDependencyCatalog` 再把工作区映射到事实类型和扫描依赖；新增推荐表或事实依赖必须显式登记，无派生消费者也必须显式声明。`merge_request_diffs` 与 `merge_request_diff_commits` 是可选的 MR 提交增强来源：两表仍登记为 MR 变化血缘，但不属于通用 `MERGE_REQUEST` 事实硬依赖；自定义白名单未同时选择两表时，原有 MR 事实和代码走查工作区必须继续可用。
- 日常正确性采用三层模型：普通 `INCREMENTAL_SYNC` 仅按目录声明的 `UPDATED_AT` 或 `MONOTONIC_PRIMARY_KEY` 固定上界快速扫描，并处理事件生成的批量权威范围，不创建全表 `RECONCILE`；`resource_label_events` 等变化信号定位父对象后，批量读取当前完整关系集合并原子替换 ODS；无事件的物理删除由独立低优先级 `DELETE_RECONCILIATION` 为到期表创建 `RECONCILE` 任务最终收敛。权威范围按规范 identity 去重，`QUEUED/RUNNING/RETRY_WAITING` 阻断完成，`FAILED` 使运行失败，禁止把未完成范围解释为空集合。
- 普通增量的每张快速表必须持久化来源固定上界，并由 checkpoint 覆盖该上界；必需表缺失、零表规划、上界未固化或权威范围未完成均不得记为成功。只有完整覆盖的 `INCREMENTAL_SYNC + SUCCESS` 推进 `last_incremental_sync_at`；活动增量吸收后续到期触发时持久登记一次尾部补跑，并在原运行结束后立即创建新的固定上界运行。执行结果、快速增量新鲜度和删除对账新鲜度分别记录和展示。
- 删除探测只分页读取 ODS active 主键并用真实类型参数化批查询验证 GitLab 存在性；仅成功来源查询返回的 mirror-only 差集可写 tombstone。单列与复合主键均使用声明式类型和 keyset cursor，JVM 工作集为 `O(batchSize)`；来源异常、非法返回子集或平台事务失败不得推进 cursor、`last_delete_reconciled_at` 或删除行。
- ODS 写入只把插入、业务列真实变化、tombstone 恢复和真实删除输出为 `MirrorRowChange`；变化快照保留列存在性和数据库 `NULL`，并在构造时复制为不可修改 Map，避免后续来源 Map 变化污染事实发布。lookback 相同行、镜像元数据变化和相同权威集合不产生派生目标。ODS DML、`fact_change_heads` 版本推进和 `sync_run_fact_targets` 有界批量 upsert 位于同一平台事务；目标按稳定事实键归一化，冲突的根详情必须失败，镜像活动期间只登记 outbox，不创建可执行事实副作用。
- 事实发布以 `config_id + source_instance + fact_type + source_table` 的持久依赖代际为门禁。镜像终态按本轮实际选择表及表任务、权威范围结果更新依赖为 `READY/BLOCKED`；只有完整 READY 的事实族可创建或唤醒无父运行的来源级 `FACT_REFRESH` 消费者。事实消费者与镜像运行使用同一来源互斥域，从全部历史 `mirror_run_id` 合并未发布目标；失败或取消的消费者释放目标归属，后续来源终态可继续接管。达到重试上限的失败权威范围只由后续选择同一子表的镜像运行重放，成功恢复前对应事实族保持阻塞。全量同步和成功全量补偿登记可合并的全量发布意图，普通增量、手动表刷新和 System Hook 不因目标数量改变为全量模式。
- `FULL_COMPENSATION_SCAN` 只承担首次历史清理和灾难恢复，不是日常实时正确性或物理删除收敛的前置条件；无生产入口的 `COMPENSATION_SCAN` 已删除。增量更新继续保留 PostgreSQL volume、同步状态、镜像表和用户配置，不通过重建容器清库。约 280 万全部同步表总量的删除反熵 batch、时间片和硬性能门禁必须由内网固定负载基准确定。

### 事实与统计

- 事实构建负责字段归一化、标签解析、非法判定和派生字段；统计服务只消费事实层和明确的统计快照。
- `sync_run_fact_targets` 是定向事实发布 outbox，稳定身份为 `source_instance + fact_type + GitLab root_id`；`fact_change_heads` 保存最新变化版本和已发布版本并提供乱序 fencing。同一运行同一根再次变化必须提高版本、重置为待发布并清除旧 assignment；来源级消费者跨镜像历史合并目标，事实 worker 在根版本锁内读取当前 READY 代际的 ODS，使交错运行不能用旧状态覆盖新事实。
- 每个定向事实任务只处理一个有界根 ID 批次，不使用长 OR 条件、内部 cursor 或目标数阈值回退。Issue、MR 和集成测试定向构建均以 GitLab 根 `id` 和 `source_instance` 隔离；发布事务先删除目标旧事实及其从属成员，再写当前来源结果，空来源是合法的只删结果。全量构建以完整快照替换单一来源实例，不影响其他来源实例或事实类型。
- 定向事实事务同时解析变化前后的稳定投影范围并推进 `fact_projection_generations`，为每个 `FULL_EPOCH/GLOBAL_VIEW/PROJECT/ISSUE_SCOPE_GROUP` 范围创建可租约、退避和恢复的投影任务。旧 generation 任务按 superseded no-op 成功；请求 `sourceVersion` 规范包含全量 epoch、消费范围 generation、范围组 definition generation 和规则版本，未受影响范围不失效。
- 页面手动刷新只扫描工作区依赖表，但扫描发现的全部跨项目变化均进入统一 outbox。请求先持久化 `sync_run_publication_fences`，镜像运行在释放同源 writer 前捕获要求的 change version；页面完成状态等待该版本内事实目标和自身消费的投影 generation 成功，不能因本次 ODS DML 为零或仅镜像运行成功就宣称最新。
- 同一议题的总量去重、模块多归属、空值展示、默认范围和导出口径遵循 `docs/platform-page-business-rules.md`，禁止在页面 SQL 中复制隐藏规则。
- 事实字段或统计口径变化必须明确是否重建事实层和预热快照；重建不等于重新全量镜像同步。
- 手工全量重建复用 `/api/facts/rebuild?configId=`，但接口只提交 `FACT_REFRESH` 后台运行并立即返回运行编号；运行以 `manualFullRebuild=true` 标识，在调度器中调用唯一的 `rebuildAllFactsForConfig` 入口重建 `issue_fact`、`merge_request_fact`、`integration_test_fact`。任何事实表写入前必须聚合预检三类事实的全部 ODS 表/字段；手工全量重建的三类事实和自动任务的单类事实分别在一个发布事务内完成，PostgreSQL MVCC 使其他连接在提交前继续读取上一已提交版本，任一构建失败则整批回滚。提交后按新的事实源版本刷新统计板与记录页快照；快照未命中时只能实时查询完整的新事实代际。手工运行与构建任务使用同一运行编号，状态面板和最近同步日志以 `sync_runs` 为唯一追踪来源；提交服务对同数据源所有活跃镜像或事实运行互斥。后端权限、源表校验和事实构建锁是权威保护，数据镜像页只提供受确认保护的运维入口。
- 事实表的搜索影子字段和业务分类字段是持久化查询契约：Java 生成归一化值，SQL 只过滤、排序、聚合，前端字段必须可追溯至请求对象和事实字段，不能在查询层临时重算复杂索引。`issue_fact.reason_category` 只由老平台固定修复模板的勾选原因段生成，非法模板写空并复用非法判定，禁止标签、普通评论或完整 `raw_payload` 回退。
- `issue_fact.bug_status` 与 `issue_state/closed_at_source` 是相互独立的事实维度：前者只保存老平台全角 `状态：X` 标签合并值，缺失时保存“未设定议题状态”；后者独立表达 GitLab 议题开闭状态。事实构建、查询、快照、导出和前端不得在两个维度之间回退或互相推断。
- `scripts/contracts/fact-field-contract.md` 是事实字段静态契约；新增或修改字段必须同步 Flyway、生成规则测试、查询/前端/导出影响，并明确是否重建历史事实。`scripts/check_fact_field_contract.py` 校验其与 Flyway 最终结构的一致性。
- `CC_PRODUCT` 客户归属以 `ods_gitlab_issues.description` 的“客户名称”为主、标题双破折号后缀为缺失兜底；`issue_fact_customer_members` 是多对多筛选权威，`issue_fact.customer_names` 仅为展示投影。客户别名必须精确规范化，筛选使用成员关系 `exists`，不得拆分或重复议题事实。
- `CC_PRODUCT` 的计划解决时间和计划合并版本分支只来自最新“问题调研情况说明”响应模板，不能复用 SLA 截止时间。事实构建对计划解决时间只保留唯一完整日期；对计划合并版本分支只折叠空白并保留来源文本，不得用非法模板校验过滤事实。计划合并分支的读取成员语义统一接受 `&`、半角逗号、全角逗号和顿号，去空、去重后供候选、SQL/内存精确筛选、表格和详情共用；API 与 Excel 仍输出完整事实原文。客户问题非法模板规则独立要求计划合并分支为一个或多个以 `&` 分隔的 `CCyyyyRn` 成员，校验失败仍保留事实原值。缺陷滞留时长只表达当前未闭环年龄：GitLab 已关闭或命中客户问题最终闭环状态时为 `0`，否则按一次请求固定的 `asOf` 与 `created_at_source` 动态计算；它不能写入事实或页面快照，也不能承载历史解决周期。上述字段、候选和导出只属于 CC_PRODUCT，延期专题不消费它们。详见 `docs/decisions.md`（D-03）。
- `issue_fact.handler_name` 与 `issue_fact.assignee_name` 是独立人员事实；当前 GitLab ODS 只暴露一份规范指派身份时，事实构建可以写入相同值，但查询、筛选、排序和导出不得把两个字段重新合并。`testing_phase` 原始空值保持为空，CC_PRODUCT 仅在响应投影中显示“未设定测试阶段”。
- `code_review_external_metrics` 是 GitLab diff 派生行数和 MR 标题功能名的权威补齐模型。既有或手工导入指标默认视为 `SUCCESS`，只有历史/已完成运行扫描明确发现的 CC MR 才进入补齐队列；后台以持久化 keyset 游标分别扫描历史 MR 和已完成镜像运行，队列按 `source_instance` 隔离并经历 `PENDING/RUNNING/RETRY/ENRICHED/SUCCESS/FAILED`。网络、限流和服务端错误使用封顶指数退避持续重试，认证、资源不存在、非法地址和截断响应进入确定性失败。补齐只通过 API Token 访问 GitLab v4 changes 接口，页面请求不得实时访问 GitLab；成功指标在事实构建互斥内定向发布到 `merge_request_fact`，构建忙时保持 `ENRICHED` 等待下一批次。全局调度关闭时补齐任务必须停止。

### 页面与前端底座

- 页面导航和权限入口由 `frontend/src/feature-manifest/` 维护；领域 API 位于 `frontend/src/api-client/`。
- 统计板复用统一运行时、筛选、排序、明细和导出契约；记录页复用共享查询、筛选状态和页面控制器，确有业务差异时保留显式特例。
- 统计板筛选操作符语义唯一归属 `service/statistics/engine/StatisticFilterEngine`：看板通过 `StatisticFieldDescriptor` 注册表声明字段绑定，禁止再私有实现 matches*/操作符分支；领域特有判定（里程碑、成员表、阶段成员）经描述符 override 钩子注入。SQL 下推路径与引擎语义由金标矩阵与契约测试锁定；`MirrorTableOverviewBoardService` 为展示层汇总行方言（大小写敏感+数值操作符），豁免迁移。
- 页面组件负责展示和交互，业务口径由事实层、规则层或共享查询提供；页面切换、登录态变化和刷新必须触发正确的数据加载生命周期。记录页首屏以列表为关键资源，筛选候选和辅助同步状态不得阻塞列表渲染；各资源失败必须保留可见错误和明确重试入口，不能以整页骨架或空数据掩盖失败。
- 非看板、非外部来源的记录页默认复用 `api-client`、路由查询、条件筛选、分页、导出、详情与加载错误底座；特例只在接口或产品形态未稳定时存在，稳定后必须收口并以挂载/关键交互测试保护，页面组件不得直接请求 `/api/**` 或调用 `fetch`。
- 客户问题工作区的“最近同步”读取本地最新成功 `ISSUE` 事实构建任务完成时间；它表达当前 `issue_fact` 可查询版本，不依赖老平台 Mongo 调度记录。

### 标签组与用户视图

- 标签组是同类型值集合，成员保存值本身；静态、动态和组合标签组共用值集合语义，适用范围默认按值类型开放，可按同类型/同字段约束。
- 标签组是筛选条件的可复用值集合，不是独立筛选方案；标签组规则不能向事实字段或候选目录注入不存在的业务值。
- `scripts/contracts/label-group-dimension-matrix.yml` 是页面字段、值类型和候选来源的静态校验输入，不是实施计划或业务事实来源。
- 固定表格视图属于用户界面偏好，不是业务事实，不改变统计、筛选、导出或老平台对齐规则；当前浏览器本地保存，按页面和业务 topic 隔离。

### 业务域模块解析

- 评审列表筛选模块读取当前模式下真实可查询记录；新增/编辑候选读取老平台约定的项目标签并允许录入候选外值，保存原始模块值，只做空白清理。
- 议题模块只识别全角 `模块：`、`工具箱：`；测试阶段始终是独立事实维度。
- 代码走查 MR 模块独立识别 `模块[：|-]`、`工具箱[：|-]`，不得复用议题解析器。
- 快速筛选、高级筛选、导出和下钻只消费同一事实字段；兼容与非兼容模式不跨源提供模块候选。

### 质量看板契约

- 研发质量看板保留老平台八个 headline 指标和五类辅助图表；其中 DGM 只参与明确的代码走查指标和图表。
- 其他看板提供老平台六类统计，不提供 DGM 选择器或 DGM 接口，其固定 GitLab CC 边界不参与老平台代码走查交接。研发质量/多元看板、代码走查非法数据、系统测试横向对比和外部数据集等交接消费者统一读取 `code_review_formal_records`：CC/DGM 各自优先读取已交接的 `LEGACY_PLATFORM` 事实，未交接时回退到对应 GitLab 正式范围；禁止在消费者内复制来源判断。
- 其他看板的代码规模来自 `merge_request_fact`：功能缺陷密度只统计需代码走查的已合并 CC MR，质量达人榜统计成员全部已合并 CC MR；人员范围由 `quality_board_member_scopes` 管理。代码规模补齐状态不能改变页面 API，未完成补齐的数据按当前已提交事实展示，成功发布后由事实与统计版本统一失效刷新。

### 业务模块

当前一级模块包括：质量看板、评审数据、代码走查、系统测试、客户问题、BI 看板和系统设置。老平台独立“集成测试”模块不恢复。BI 的全部专属事实从 `docs/bi-dashboard/README.md` 统一路由；其余模块的字段、导出、非法数据和下钻规则归 `docs/platform-page-business-rules.md`。

## 认证与授权

- LDAP 与平台独立。LDAP 提供账号、姓名、状态和角色基础信息；平台创建自己的 HTTP Session，不使用 LDAP JWT 访问业务接口。
- 同一主机的浏览器 Cookie 不按端口隔离，因此平台实例必须拥有稳定的 Cookie 命名空间：标准 Compose 部署使用唯一且跨更新保持不变的 `COMPOSE_PROJECT_NAME` 作为 `PLATFORM_INSTANCE_ID`，Session 与 CSRF Cookie 名由其摘要派生；直接运行未显式设置实例身份时使用应用名和监听端口。
- CSRF Cookie 也必须属于该实例命名空间。它由后端以 `X-XSRF-TOKEN` 响应头交付，前端按浏览器 Origin 保存 Token，并仅在非安全请求中通过同名请求头回传；不得恢复固定 `JSESSIONID`、`XSRF-TOKEN` 或从 Cookie 名猜测 Token 的共享契约。
- 当前产品默认认证 Provider 为 LDAP；本地账号认证只允许通过显式配置启用，缺失配置不得静默把 LDAP 用户切换到本地账号校验。
- 本地认证仅在 `PLATFORM_AUTH_PROVIDER=local` 时允许；正式安全校验要求本地管理员/审批账号使用 Spring Security password hash，并始终要求有效的数据库密码和 GitLab Web 地址。
- 内网发布必须显式设置 `PLATFORM_AUTH_PROVIDER=ldap` 和从后端容器可达的 `PLATFORM_LDAP_BASE_URL`；LDAP 发布模式不注入本地管理员/审批账号，Session CSRF 保持启用。
- LDAP 不可用时已有 Session 和本地权限继续有效；新登录必须失败，不能使用缓存密码绕过认证。角色和账号状态在下次登录时更新，当前阶段不做轮询踢出。
- 平台本地维护细粒度权限目录及 LDAP 角色映射；一个用户的最终权限是所有角色权限的并集，后端为最终裁决者。
- 角色名称可展示和编辑，角色编码是稳定关联键；“一级/二级/三级”不是系统概念，不能进入代码或数据模型。
- 评审记录和问题项的 `created_by` 为空时表示历史数据无归属，不进行推测回填；评审管理的本人删除和任意删除分别由本地权限控制。
- 详细边界见 `docs/decisions.md`（D-01）。
## 兼容模式边界

- CC/DGM、老平台评审数据等临时兼容源必须通过明确的 Match mode 服务、表、任务和 API 访问，并在代码中标注 `兼容模式` / `Match mode`。
- 正式数据源与兼容数据源不可互相覆盖。`review_data_read_mode=compatibility` 时，`review_visible_*` 暴露正式评审与未被 `PLATFORM_OWNED` 映射遮蔽的 Mongo 快照；`formal` 时仅暴露正式评审。负 ID 快照第一次编辑在单一事务内完整物化主记录、专家、描述、内容和问题项，并建立 `PLATFORM_OWNED` 映射；Mongo 同步只替换快照，不得回写正式评审。代码走查交接成功后由 `code_review_formal_records` 选择正式事实，旧兼容快照不再直接进入正式消费者。所有兼容路径必须标注 `兼容模式` / `Match mode`。
- 老平台评审禁止批量转正式或建立 `LEGACY_MANAGED` 副本；`V20260730_07` 清理错误副本并恢复评审读源设置。代码走查仍是可审计、幂等的正式交接，执行前校验已保存设置版本和源范围，使用 PostgreSQL advisory lock 防并发，并在 `REPEATABLE_READ` 事务内提交；失败写入审计任务，不报告为成功。历史无创建人保持 `created_by = null`。
- 兼容模式 MR 专用测试环境资源固定为 `qaflex-matchmode-mr-*` 容器和 `qaflex_matchmode_mr_pgdata` volume；未经授权不得删除或改名。

## 外部数据集 API

- `/api/external/v1/datasets` 仅面向经过确认的进程外消费者。通用边界见 `docs/decisions.md`（D-02）；新增 Provider 前必须有真实消费者、schema、权限和移除条件。
- 外部 API 默认关闭，使用独立无状态 Bearer token 和数据集白名单；浏览器业务接口继续使用 LDAP Session 与 CSRF，两条认证链不得混用。

## 性能、迁移与发布不变量

- 规模敏感路径先测量再优化；统计查询、事实构建、同步任务和前端渲染不得无限积压或无界等待。
- 后台事实构建通过带版本的结果提交，页面读取已完成版本，不让主请求同步等待非关键后台任务。
- 内网 Ubuntu 24.04 无公网发布必须携带已构建业务镜像；增量包只替换后端和前端，不加载 PostgreSQL、不删 volume、不执行 `docker compose down -v`。
- 保数据更新包是默认发布形态，只包含前后端镜像、发布 Compose、升级/回滚入口、结构化发布清单、校验和及离线说明；镜像构建上下文、裸 JAR/`dist`、现场 `.env`、PostgreSQL 镜像和 Docker deb 不得进入更新归档。完整结构以 `deploy/intranet-offline-packaging-standard.md` 为准。
- 保数据更新包必须显式绑定现场合并 Compose 或上一个成功更新包的实际镜像基线，并由打包器为包目录、归档和前后端镜像生成同一个唯一发布 ID；文件名只保留产品、全新/更新类型和发布 ID，环境、源码、工作树与重建信息统一进入发布清单。变更前备份平台库、`.env`、基础 Compose 和已有 override；连续升级替换 override，应用回滚恢复上一份 override。先完成后端 Flyway/健康/行数守恒验收，再切换前端，升级前后 PostgreSQL 容器 ID 和 volume 必须不变。
- 发布构建必须从干净的后端构建目录生成 jar，并校验 jar 内 Flyway 文件集合与源码完全一致；源码已删除的迁移不得因 `target/classes` 残留进入镜像。
- 改动事实字段、统计口径、非法判定、默认范围、老平台映射或快照结构时，增量部署后必须重建事实层并预热统计快照。
- 大表 `GIN`、`trgm`、表达式和覆盖索引先评估锁与耗时；需要 `CREATE INDEX CONCURRENTLY` 时使用独立运维入口，不放入普通 Flyway 事务。破坏性迁移必须经过兼容/观察/删除阶段并记录评审与恢复路径；迁移改动需运行 schema 漂移、迁移不可变性、Flyway profile smoke 和代表性迁移测试。

## 测试与代码事实

- 后端使用 Java 21、Spring Boot、MyBatis-Plus、JUnit 5/Mockito；测试位于 `backend/src/test/`。
- 前端使用 Vue 3、TypeScript、Vite、Element Plus、Vitest；测试位于 `frontend/src/**/*.test.ts`。
- 日常验证按风险选择最小充分入口：后端 `mvn -DskipTests compile`，前端 `npm.cmd run typecheck`，行为变化补定向回归测试；发布前再执行更完整的链路验证。
