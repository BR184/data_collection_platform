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
- 系统测试与客户问题的可选范围统一以项目级“议题范围目录”为权威来源。目录以稳定业务键、可修改显示名和精确事实成员三层表达；管理员顺序中的第一条启用范围是统一默认值。系统测试目录维度为 `TESTING_PHASE`，成员是 `issue_fact.testing_phase` 的配置值，但页面如何接纳含多个成员的复合事实由页面业务契约决定：统一 `SystemTestPhaseMembershipPolicy` 显式选择精确成员或包含成员，SQL 与内存过滤必须使用同一模式，并在一次请求开始时编译为不可变成员快照，禁止逐事实重复读取目录。客户问题目录维度为 `MILESTONE`，成员匹配 `issue_fact.milestone_title` 时不区分大小写但保留空白等真实值差异。客户目录首次收到非空事实且尚无任何范围组时建立初始组和成员；目录已有任意范围组后，事实发布只向业务键相同的既有启用范围补充缺失成员，不创建额外范围、不启用停用范围、不改变显示名或顺序。显示名修改不得改变事实匹配，目录变更通过统一源版本使统计与记录快照失效。
- 数据库迁移统一使用 Flyway；已执行迁移不可修改，新增结构或数据变更必须新建迁移。
- Flyway 是建库、测试初始化和升级的唯一结构入口；共享库已执行迁移不可修改，结构修复使用新前向迁移，结构变更与大规模回填分开。测试使用独立 schema 并在每个测试 JVM 首次使用该 schema 时清理后完整迁移，不维护第二份结构快照。
- 平台库为 `qaflex`；GitLab 源库为 `gitlabhq_production`；老平台 MySQL 库为 `gitlab_spider`。三者边界不可混用。

## 模块与数据所有权

### 镜像与同步

- 负责 GitLab 连接、表白名单、增量/补偿同步、任务状态、日志和 Webhook 事件。
- 镜像层拥有 ODS 原始数据和同步水位；同步成功不代表事实层已重建成功，事实刷新状态必须独立记录。
- 已核验0930发布协议：数据镜像设置页“刷新最新数据”与普通自动补偿均提交`INCREMENTAL_SYNC`，共享白名单、增量水位、优先级及事实发布链；`MANUAL/SCHEDULE`表达触发来源，自动入口另受开关与计划到期约束。活动增量可复用并登记补跑，触发不等于完成；日志将该运行类型统一显示为“刷新最新数据”，排查须比较config/source、运行终态、表任务与后续事实发布。“全量补偿对账”是独立`FULL_COMPENSATION_SCAN`，不能与普通自动补偿混称。制品锚点为20260930T044004Z-e275334105bb，原JAR SHA-256为64e281b78ee5465be168a70c8540ff1f1318af69e46bdf28c4b2347284757611；调查材料见.tmp/mirror-sync-0930-investigation/。
- 已确认的发布实现缺口（0930/1010/696c原制品核验）：`FULL_RECONCILE`不建立时间增量`last_watermark_at`，未成功完成过增量的表下次`INCREMENTAL_SYNC`从1970起扫，完整增量末页后才推进水位；新空库因此可能重读历史并为相同行登记关系核对，不能由“升级保留水位的实例稳定”推断“新空库首轮增量已验收”。现有日志“表项”是分页任务行数，完成计入失败等终态；写入量来自父运行快照，独立权威范围不在表项计数内。需要分别核对任务水位、扫描/写入与关系范围状态，不能按小写入量判断工作量或事实刷新就绪。安全基线交接与进度修复尚未实施，不能人工填当前时间水位掩盖。证据：.tmp/fresh-package-incremental-20261019/。
- 1010/696c取消边界缺口已用原class和数据库替身确认：取消的快速终态判定仅检查普通表任务，未覆盖独立权威范围租约；范围提交也未检查父运行取消状态。取消不会回滚已提交ODS/水位，但未完成范围只有正常收尾为FAILED后才满足后续接管条件，不能由重试日志SUCCESS推断旧工作已完整移交。真实并发与现场是否触发尚未验证；取消确认、范围提交与恢复闭包需一并修复，证据沿用上述目录。
- 同一镜像运行的表 worker 数与 DIRECT JDBC 池容量由 `SyncExecutionBudget` 一次解析；`sync_runs.resolved_worker_count` 是该运行不可变并发快照，DIRECT 池容量等于 worker 数加控制面保留连接。连接池按稳定 `configId` 唯一管理，连接或线程配置只能在无活动镜像运行时修改，配置事务提交后旧池退休且不打断已借出连接。
- 调度器每次领取运行必须生成唯一 `lease_owner`；首次心跳、周期续租、暂停和终态提交均以 `(run_id, lease_owner)` fencing，租约转移后的旧执行器不能覆盖新 owner。执行器拒绝入队时仅释放自己拥有的运行并归还本地容量；截止时间和人工取消使用字段级状态 CAS，禁止用陈旧全实体更新覆盖租约。
- 表任务是唯一分页恢复边界：源端读取不持有平台库事务，本地镜像写入、变化目标登记、任务 owner 条件完成、状态水位和下一阶段任务创建在同一事务提交。`SCAN` 续页使用独立任务行；每张表的 `RECONCILE` 只使用一个任务行原子累计计数并推进 keyset cursor，不按页增长任务数。任务执行期间按租约心跳续期，租约转移后的旧 worker 不得提交状态或镜像副作用。
- 动态 ODS 表及其查询索引由运行期 schema 控制面统一管理，因为 Flyway 执行时动态表可能尚不存在。每轮在任何表任务入队前按稳定顺序准备全部当前白名单表，并以 `CREATE INDEX CONCURRENTLY` 补齐物理列完整的索引、重建失效同名索引；worker 数据面不得并行建表或维护索引。
- 表任务的连接、超时、锁/死锁、事务回滚和临时资源异常在原任务内按上限退避重试，父运行复用 `RETRYING/run_after` 等待该任务；租约超时使用同一状态路径。未定义表/列、非法配置和业务契约错误直接失败；任何失败重试都不得推进 cursor、水位或事实发布门禁。
- 源端扫描只使用真实类型复合主键 keyset：全量与删除对账按主键 tuple 分页；增量仅在更新时间列存在有效非部分 B-tree 前导索引时使用 `TIMESTAMP_KEYSET`，否则在固定 `(watermark, scan_upper_bound_at]` 窗口内使用 `PRIMARY_KEY_KEYSET`，保证每次运行最多按主键线性扫描一次。复合游标统一为 JSON 数组并持久化 `page_number`；状态水位只在末页推进。禁止按文本化主键排序、哈希分片重复扫表、`OFFSET` 分页和逐页 `count(*)`。
- GitLab 来源时间必须按物理列类型解释：DIRECT JDBC 读取结果同时保留 `ResultSetMetaData.getColumnTypeName()`，`timestamp without time zone` 保留来源墙上值，`timestamp with time zone`/`timestamptz` 按同一瞬间归一为 UTC `LocalDateTime`；DOCKER JSON 的无偏移文本保留墙上值、带偏移文本归一为 UTC。时间窗口 SQL 必须按来源 schema 生成匹配的 `timestamp`/`timestamptz` 字面量，不能只按 JDBC 通用类型或 JVM 默认时区转换。
- 客户问题活动时间按列固定语义（D-27，已实施）：已核实的 `issue_fact.created_at_source` 为 UTC naive，活动规则通过 `*FromSourceUtc` 映射到上海日历；`fixed_label_time` 的来源事件列是 `timestamptz`，事实投影在 `IssueFactSourceRowMapper` 边界用 `FactSourceRowValueSupport.toShanghaiWallClock` 只取瞬间并按 `CustomerIssueActivityTimeRules.BUSINESS_TIME_ZONE` 落成上海墙上值，写入进程与连接会话默认时区不参与结果，消费者经 `*FromBusinessClock` 直接按业务日解释、不二次转换。事实复制与读取既有修订的分支保持原值，不做第二次换算；空值仍是未知。分列入口对应不同列，不依据主机动态回退，也不归一为单一入口。本机存量事实本就由上海主机投影得到，与新语义同值，无需批量重建；跨环境部署由该边界统一。业务日由 `SystemBusinessDateProvider` 消费可注入 Clock 并显式按上海日历解释；页面、候选、下钻与下载沿用同一固定日期身份。验证为真实 JDBC（`timestamptz` 事件列）→实际 mapper→今日/本周/累计完成，在上海、UTC、洛杉矶写入进程与 UTC/上海/纽约连接会话时区组合下同一瞬间得到同一业务日历值，手工构造上海 naive 值不能代替该投影验证。
- 同一数据源保持单一镜像写入所有权。用户 `TABLE_REFRESH`、`INCREMENTAL_SYNC` 和 System Hook 属于前台运行，独立低优先级 `DELETE_RECONCILIATION` 与全量/补偿运行只在已提交分页边界让行；自动增量仍只为等待中的单表刷新让行。让行以 owner CAS 转为 `PAUSED` 并释放互斥范围，前台运行结束后从持久 cursor 恢复；已经开始的源查询和平台事务不中断。
- 页面同步命令只引用已持久化的 `configId`，不得隐式保存配置；表单存在未保存变更时必须先显式保存。活动镜像运行期间后端继续拒绝连接与容量配置变更，不能为提交前台任务绕过该保护。
- 同步诊断只把当前活动运行的任务计入当前失败/超时，历史累计使用独立字段；当前表任务必须暴露 `taskId/stage/cursor/retry/heartbeat/lease`。表同步诊断按 `dirty_flag desc, source_table asc` 稳定排序，同组顺序不得依赖随运行变化的 `updated_at`。DIRECT 模式同时暴露 Hikari 活动、空闲、等待和容量指标；源总量未知时前端使用不确定进度，不根据动态分页任务数伪造百分比。同步 JSON 日志统一携带 `runId`、`runDbId`、`taskId`、`sourceTable`、`configId`、`sourceInstance`、`runType` 和 `action`。详细决策见 `docs/decisions.md`（D-04）。
- `GitlabSourceLineageCatalog` 是 25 张推荐来源表的主键、删除探测资格、权威父子范围和派生归属唯一目录；评论和标签分别用 `(noteable_type,noteable_id)`、`(target_type,target_id)` 隔离多态对象。`GitlabFactDependencyCatalog` 统一维护平台通用事实读取表与变化信号表，`RealtimeWorkspaceDependencyCatalog` 再把工作区映射到事实类型和扫描依赖；新增推荐表或事实依赖必须显式登记，无派生消费者也必须显式声明。`merge_request_diffs` 与 `merge_request_diff_commits` 是可选的 MR 提交增强来源：两表仍登记为 MR 变化血缘，但不属于通用 `MERGE_REQUEST` 事实硬依赖；自定义白名单未同时选择两表时，原有 MR 事实和代码走查工作区必须继续可用。
- 日常正确性采用三层模型：普通 `INCREMENTAL_SYNC` 仅按目录声明的 `UPDATED_AT` 或 `MONOTONIC_PRIMARY_KEY` 固定上界快速扫描，并处理事件生成的批量权威范围，不创建全表 `RECONCILE`；`resource_label_events` 等变化信号定位父对象后，批量读取当前完整关系集合并原子替换 ODS；无事件的物理删除由独立低优先级 `DELETE_RECONCILIATION` 为到期表创建 `RECONCILE` 任务最终收敛。权威范围按规范 identity 去重，`QUEUED/RUNNING/RETRY_WAITING` 阻断完成，`FAILED` 使运行失败，禁止把未完成范围解释为空集合。
- 普通增量的每张快速表必须持久化来源固定上界，并由 checkpoint 覆盖该上界；必需表缺失、零表规划、上界未固化或权威范围未完成均不得记为成功。只有完整覆盖的 `INCREMENTAL_SYNC + SUCCESS` 推进 `last_incremental_sync_at`；活动增量吸收后续到期触发时持久登记一次尾部补跑，并在原运行结束后立即创建新的固定上界运行。执行结果、快速增量新鲜度和删除对账新鲜度分别记录和展示。
- 删除探测只分页读取 ODS active 主键并用真实类型参数化批查询验证 GitLab 存在性；仅成功来源查询返回的 mirror-only 差集可写 tombstone。单列与复合主键均使用声明式类型和 keyset cursor，JVM 工作集为 `O(batchSize)`；来源异常、非法返回子集或平台事务失败不得推进 cursor、`last_delete_reconciled_at` 或删除行。
- ODS 写入只把插入、业务列真实变化、tombstone 恢复和真实删除输出为 `MirrorRowChange`；变化快照保留列存在性和数据库 `NULL`，并在构造时复制为不可修改 Map，避免后续来源 Map 变化污染事实发布。lookback 相同行、镜像元数据变化和相同权威集合不产生派生目标。ODS DML、`fact_change_heads` 版本推进和 `sync_run_fact_targets` 有界批量 upsert 位于同一平台事务；目标按稳定事实键归一化，冲突的根详情必须失败，镜像活动期间只登记 outbox，不创建可执行事实副作用。
- 事实发布以 `config_id + source_instance + fact_type + source_table` 的持久依赖代际为门禁。镜像终态按本轮实际选择表及表任务、权威范围结果更新依赖为 `READY/BLOCKED`；只有完整 READY 的事实族可创建或唤醒无父运行的来源级 `FACT_REFRESH` 消费者。事实消费者与镜像运行使用同一来源互斥域；待发布权威是 `fact_change_heads` 的版本栅栏（`published_version < latest_change_version`），领取即把该批根写入 `fact_build_task_roots`（随任务终态删除，领取查询排除在途任务已领取的根），失败运行不留下需人工清理的归属，后续来源终态可继续接管全部未发布根。达到重试上限的失败权威范围只由后续选择同一子表的镜像运行重放，成功恢复前对应事实族保持阻塞。全量同步和成功全量补偿登记可合并的全量发布意图，普通增量、手动表刷新和 System Hook 不因目标数量改变为全量模式。
- `FULL_COMPENSATION_SCAN` 只承担首次历史清理和灾难恢复，不是日常实时正确性或物理删除收敛的前置条件；无生产入口的 `COMPENSATION_SCAN` 已删除。增量更新继续保留 PostgreSQL volume、同步状态、镜像表和用户配置，不通过重建容器清库。约 280 万全部同步表总量的删除反熵 batch、时间片和硬性能门禁必须由内网固定负载基准确定。

### 事实与统计

- 事实构建负责字段归一化、标签解析、非法判定和派生字段；统计服务只消费事实层和明确的统计快照。
- `fact_change_heads` 是事实发布的唯一控制面：`latest_change_version` 与 `published_version` 构成版本栅栏并提供乱序 fencing，未发布即 `published_version < latest_change_version`，判定与计数走该谓词的部分索引，成本只与未发布根数相关。`sync_run_fact_targets` 已降级为按轮登记的审计日志（只插入、不参与任何控制面判定，由保留窗口归档 job 收敛）。稳定身份为 `source_instance + fact_type + GitLab root_id`；同一根再次变化必须提高版本，事实 worker 在根版本锁内读取当前 READY 代际的 ODS，使交错运行不能用旧状态覆盖新事实。全量发布先冻结本次覆盖的版本上界再构建、构建后按该上界结算，构建期间新登记的更高版本保持待发布。
- 每个定向事实任务只处理一个有界根 ID 批次，不使用长 OR 条件、内部 cursor 或目标数阈值回退。Issue、MR 和集成测试定向构建均以 GitLab 根 `id` 和 `source_instance` 隔离；发布事务先删除目标旧事实及其从属成员，再写当前来源结果，空来源是合法的只删结果。全量构建以完整快照替换单一来源实例，不影响其他来源实例或事实类型。
- 定向增量的已知容量边界：默认 `factTargetBatchSize=200`，`SyncFactRefreshRunExecutor` 在同一 `FACT_REFRESH` 内连续串行领取并执行根批次；项目、命名空间、用户等粗粒度来源变化可展开为全部 Issue/MR 根。2026-08-03 版本已具备该边界，旧包运行较快不构成可扩展性证明。
- 2026-08-10 `c367258a` 后，默认启用的 MR 提交事实路径在定向 MR 批次中额外执行 `ranked_diffs` 窗口排序查询，并将待处理目标领取扩大为来源范围内的历史待处理目标；这是既有增量瓶颈的性能放大因素。具体版本归属、已排除归因和未修复边界见 `docs/decisions.md` D-13；不得将 CAT 客户端、BI 前端或 2026-09-10 打包动作单独定性为根因。
- 定向事实事务同时解析变化前后的稳定投影范围并推进 `fact_projection_generations`，为每个 `FULL_EPOCH/GLOBAL_VIEW/PROJECT/ISSUE_SCOPE_GROUP` 范围创建可租约、退避和恢复的投影任务。旧 generation 任务按 superseded no-op 成功；请求 `sourceVersion` 规范包含全量 epoch、消费范围 generation、范围组 definition generation 和规则版本，未受影响范围不失效。
- 页面手动刷新只扫描工作区依赖表，但扫描发现的全部跨项目变化均进入统一 outbox。请求先持久化 `sync_run_publication_fences`，镜像运行在释放同源 writer 前捕获要求的 change version；页面完成状态等待该版本内事实目标和自身消费的投影 generation 成功，不能因本次 ODS DML 为零或仅镜像运行成功就宣称最新。
- 延期标签写回在比对差异前必须先增量刷新镜像并等待 ISSUE 事实族发布收敛，判据是该来源实例已无未发布根（`fact_change_heads.published_version < latest_change_version` 的版本栅栏，唯一读法见 `SyncFactPublicationStateService.countUnpublishedTargets`），而不是某一次事实运行的终态。事实消费者是来源级、无父运行的，因此任何依赖父子运行关联判断事实就绪的写法都无效；未收敛时跳过写回，禁止基于落后于镜像的事实写标签。该来源是否具备写回资格由数据源在镜像设置页的「延期标签写回」开关（默认关闭）与 `web_base_url`／`api_token` 是否齐备共同决定，见 `docs/platform-page-business-rules.md`。
- 上述等待由运行终态事件驱动，由 `CustomerIssueDelayClosureOrchestrator` 按「提交前置刷新 → 等发布收敛 → 重算事实并登记写回候选」三段推进，不轮询、不设固定超时：调度触发只把周期提交到应用异步执行器，收敛评估在运行终态事件监听中完成，阶段 3 直接在该执行器上执行。运行终态事件对全部运行类型发布，订阅方各自按 `mirrorRun()` 或运行类型过滤；事实发布登记方必须先于收敛消费方执行（顺序契约见 `SyncRunCompletionListenerOrder`），否则目标尚未登记时会把"未发布目标为 0"误判为已收敛。在飞状态按数据源配置粒度保存在内存，只拥有一个调度周期，跨周期重启（重启复用同互斥域的活跃运行，且候选登记幂等）。
- 客户问题延期重算只改变 `is_response_delayed`、`response_overdue`、`is_resolve_delayed` 三项业务判定，不重新解释其他业务列、成员关系或搜索列（D-15 的窄写不变量）。当前读发布模型下，`FactBuildService` 通过 `FactReadVisibility.liveRevisionPredicate` 读取当前活动事实，在旁路候选中由数据库复制原修订与成员、只替换三项判定；不再原地改写旧事实。`publishWithinCandidate` 返回只表示候选准备到 PROJECTING，必需投影完成并统一发布后才推进公开指针与范围 generation（D-26），不能据其返回认定新延期值已公开。
- **延期写回接替缺口（2026-10-14 已复现、未修复）**：`CustomerIssueDelayLabelWritebackQueueService.candidateSql` 尚未接入修订可见性，登记与 worker 重读都会混入历史/旧代际及未发布候选；`loadCandidate` 无排序取首条，不能保证是当前事实。临时表执行生产查询已复现当前需加标却取历史“不延期”而跳过，以及当前已关闭仍命中历史 open 行。`CustomerIssueDelayClosureOrchestrator.executeDelayClosure` 又在重算候选准备返回后立即入队，未交接该候选的完整发布结果。修复应共同保证候选完整发布后登记差异、worker 只消费当前有效修订；只加去重、排序或过滤后仍立即入队均不足以闭环。完成当前版本集成验证前，不依据旧版真实写回结果认定可接替；运维边界见 `deploy/runbooks/gitlab-sync-orchestrator-runbook.md`“老平台延期标签写回接替边界”。
- 响应延期的豁免判据由 `IssueSlaRules` 单点派生，事实构建与周期重算共用：**存在回复模板（正文含 `### 1、修复状态`），或存在响应模板（表头 `# 问题调研情况说明`）且其"计划解决时间"合法**即豁免。合法格式与非法模板判定**同源**（`IssueResponsePlanFieldRules.parsePlannedResolutionAt`：唯一且完整的日期 + 规则总表 11.4 的分隔符白名单），凡不符合即视为"没有写时间"；取值按最新一份响应模板，不可解析时回退更早的响应模板，与老平台 `IssueServiceImpl.getPlanSolutionTime` 方向一致。回复模板存在时优先豁免，不再检查响应模板的计划解决时间。`issue_fact.has_response` 只表达响应模板存在性，与豁免判据不是同一事实，二者不得互相回退或合并。规则口径见 `docs/platform-page-business-rules.md` 5.3，决策见 `docs/decisions.md` D-21。
- 统计板快照的来源身份由请求声明的实际读取范围推导，不使用隐式默认来源：范围集合显式携带每个 `source_instance`，来源版本由 `FactProjectionVersionService.combinedSourceVersion` 按来源分组生成（单源只锁该源，多源覆盖每个来源）。命中判定、来源版本读取、发布资格校验和事实读取位于同一只读 REPEATABLE READ 事务，快照写入在该事务之外并绑定事务内读到的版本；来源完整性无从证实（依赖未就绪、全量重建未结算、标签事件历史未全量核验）时读取明确报错，命中已有 READY 同样拒绝；完整性可证但尚未收敛到最新变化版本时改取该视图上一个完整发布点并附带数据时刻与待更新数量，该降级产出不写入快照，且该视图从未有过完整发布点时拒绝并说明。不产出新 READY、不以零值或进行中的混合视图冒充完整结果（决策见 `docs/decisions.md` D-18、D-24）。
- 统计下钻契约以单元格声明的能力与集合为准：`StatisticCellData.drilldown` 是唯一的最终下钻能力（新板所有指标即便 0/null 也可打开；旧看板在原生产端用历史有效条件显式写入该值），前端不得再按数值推断。`StatisticDetailResponse` 携带可选集合列表与当前集合：数量提供 `COUNTED`、比率提供 `NUMERATOR`/`DENOMINATOR`、周期提供 `SAMPLE`；空集合仍返回列定义、集合说明与 `total=0`，由明细解释而非报错。主表下钻参数携带行身份、业务日、来源选择与来源版本，明细请求重新解析当前范围与版本并匹配，来源已变或缺少版本时明确拒绝，不混读两个来源代际（决策见 `docs/decisions.md` D-19）。
- 统计板的行维度与精确成员选择是控制参数而非业务筛选条件：在 `StatisticFilterGroupSupport` 中与业务 `filterGroup` 分离，由页面配置声明并按 URL 往返，切换时失效明细键并回到第一页。成员候选由后端 `GET /api/statistic-boards/{boardKey}/control-options` 在同一窄事实的**完整基础范围**（公共范围 + 用户条件，不施加成员选择）上求解后下发，因此选择某个成员后其余成员仍可选；它不是第二套成员权威源，只是同一事实源的一次查询结果。缺失成员与真实成员用"类型 + 值"表达：候选项为 `{kind, value, label}`（`MISSING` 的 `value` 恒为空串，`VALUE` 的 `value` 即成员名），请求参数上"成员名"与"成员类型"分成 `customer/module/function` 与 `customerKind/moduleKind/functionKind` 两组并同时写入主表 `detailParams` 与快照键。成员名因此可以是任意文本，真实名等于 `__missing__` 或等于"未标注…"文案的成员与缺失是两个可分辨的选择，禁止用哨兵值或"歧义计数"表达缺失（决策见 `docs/decisions.md` D-19）。候选与主表必须属于同一来源代际，无论哪一方先返回都要核对（候选响应到达时比对主表最近报告的代际，不一致则丢弃并重取一次）；主表、明细、候选三条加载路径都按请求序号丢弃迟到响应，明细关闭与失效时作废在途请求，快速筛选变更只产生一次请求。模块成员的空白语义在 Java 与 SQL 两侧使用同一 `\s` 定义（Java 正则去空白，SQL `regexp_replace(..., '^\s+|\s+$', '', 'g')`），不得一侧 `trim()`、另一侧只去空格的 `btrim`。范围目录已加载但没有启用项时，页面显示目录状态并阻止按“全部里程碑/阶段”取数，不把默认范围放大为全范围。
- 同一议题的总量去重、模块多归属、空值展示、默认范围和导出口径遵循 `docs/platform-page-business-rules.md`，禁止在页面 SQL 中复制隐藏规则。
- 统计表视图配置以`hiddenColumnKeys`记录主动隐藏意图，当前定义减隐藏集合派生实际可见列，设置草稿与渲染共用该派生结果；不把某次响应的可见全集作为未来列白名单。浏览器偏好和已发布收藏的旧`visibleColumnKeys`只在统一解析边界迁入：以旧列顺序中已知全集减旧可见集合得到隐藏集合，迁入后只写新模型；缺乏证据的新增列默认可见，暂离当前维度的隐藏意图保留。该转换不改统计范围/指标或后端协议，也不清空其它布局设置。
- 事实字段或统计口径变化必须明确是否重建事实层和预热快照；重建不等于重新全量镜像同步。
- 手工全量重建复用 `/api/facts/rebuild?configId=` 提交 `FACT_REFRESH` 后台运行，立即返回运行编号；执行器按 `manualFullRebuild=true` 聚合预检三类事实的全部 ODS 表/字段，再分别派发三类全量任务。分批事务只写新 epoch 的候选事实、父修订关系与搜索列，批间续期租约并写 `sync_run_events`；不删除或改写上一完整版本。全部捕获输入完成后进入 PROJECTING，必需投影针对冻结身份成功后，唯一发布事务切换域指针并结算 heads、全量意图、generation 和栅栏。失败/中止释放候选，旧版本保持；不再使用原地快照外删除。构建任务 SUCCESS 表示批次构建完成，父运行必须等待完整发布；父、子租约分别校验。分批与租约背景见 D-10，当前发布契约见 D-26。提交服务的同来源互斥、后端权限、源表校验与事实构建锁继续有效。该中间阶段不可单独部署，P3–P6 尚需完成业务投影、全查询版本限定与页面接入。
- 首次手工重建意图与镜像证据的边界以 D-26“手工重建意图与镜像证据”为准；未知镜像不伪造、未就绪不认证。提交成功只代表意图登记及后台运行入队，不代表全量构建/完整发布已完成。
- 事实候选失败的逻辑中止与物理清理分离：ABORTED/执行归属释放先提交，遗留未公开修订通过持久标识交给维护调度单批事务清理；旧完整点保持，首个构建/发布异常不得被二次清理失败覆盖。批次与恢复契约统一见 D-26“失败恢复与锁边界”。
- 启动、仅迁移与读生产分离：`PLATFORM_RUN_MODE=migrate` 是不扫描平台业务组件的无 Web 一次性 Flyway 进程；正常启动不内联接管大表。后台按域接管/重试，镜像失败持久退避；稳定键完整对账复用未变修订，不因 FULL_RECONCILE 单独新建代际。完整控制与物理复用边界归 D-26，现场完成判据见升级 runbook。
- D-10 只治理显式全量事实构建的分批发布、租约和互锁；它不等于定向增量目标洪峰已解决。增量长跑的当前诊断与版本边界以 D-13 为准，具体源表/列和 SQL 执行计划仍需内网固定负载探针确认。
- 事实表的搜索影子字段和业务分类字段是持久化查询契约：Java 生成归一化值，SQL 只过滤、排序、聚合，前端字段必须可追溯至请求对象和事实字段，不能在查询层临时重算复杂索引。`issue_fact.reason_category` 只由老平台固定修复模板的勾选原因段生成，非法模板写空并复用非法判定，禁止标签、普通评论或完整 `raw_payload` 回退。
- `issue_fact.bug_status` 与 `issue_state/closed_at_source` 是相互独立的事实维度，后者表达GitLab开闭状态，不得互相回退推断。696c部署实现的前者仍只保存老全角 `状态：X` 合并值，缺失为“未设定议题状态”。2026-10-19用户已确认客户项目325九个明确状态词支持裸/全角/限定半角状态前缀并稳定去重，其它项目不扩；目标规范及实施状态见[活动计划](plans/customer-issue-status-statistics-confirmation-20261019.md)，尚未落地，不能据确认声称部署代码已支持。
- 延期原因与测试状态同名不表示字段等价，问题调研评论不自动生成状态；已确认目标将裸数据异常纳入状态并保留其独立延期原因，但显式“延期原因：数据异常”不生成状态。共享闭环/延期/事件资格消费同一规范词汇，不能统计页补值而来源与其它消费者仍扫描raw标签子串。原696c已复现这种衔接缺口；修复需历史重建和全消费者验证。
- 持续时间比较必须明确两端时间轴：`created_at_source`为UTC naive、`fixed_label_time`为上海墙上值，先解释为相同轴再求差，不得直接相减、篡改D-27存储或对所有列统一偏移。2026-10-19原696c客户解决周期已复现真实24小时显示1.3天的混轴问题，目标修复及回归归同一活动计划；活动日历分桶仍按D-27。
- 多状态字段的成员解析不自动改变各输出的聚合公式。2026-10-19复核本地老平台`SpiderIssueDataService`确认：CC/HTGC指派人详细Excel的未复现、CC需求如此本就按整串equals计数，696c沿袭该实现；改为成员计数属于待确认口径调整，活动计划S06不再作为必修回归。F/P、is_fixed、关闭及非客户项目输入范围分别保留自己的契约。
- `scripts/contracts/fact-field-contract.md` 是事实字段静态契约；新增或修改字段必须同步 Flyway、生成规则测试、查询/前端/导出影响，并明确是否重建历史事实。`scripts/check_fact_field_contract.py` 校验其与 Flyway 最终结构的一致性。
- `CC_PRODUCT` 客户归属以 `ods_gitlab_issues.description` 的“客户名称”为主、标题双破折号后缀为缺失兜底；`issue_fact_customer_members` 是多对多筛选权威，`issue_fact.customer_names` 仅为展示投影。客户别名必须精确规范化，筛选使用成员关系 `exists`，不得拆分或重复议题事实。
- `CC_PRODUCT` 的计划解决时间和计划合并版本分支只来自最新“问题调研情况说明”响应模板，不能复用 SLA 截止时间。事实构建对计划解决时间只保留唯一完整日期；对计划合并版本分支只折叠空白并保留来源文本，不得用非法模板校验过滤事实。计划合并分支的读取成员语义统一接受 `&`、半角逗号、全角逗号和顿号，去空、去重后供候选、SQL/内存精确筛选、表格和详情共用；API 与 Excel 仍输出完整事实原文。客户问题非法模板规则独立要求计划合并分支为一个或多个以 `&` 分隔的 `CCyyyyRn` 成员，校验失败仍保留事实原值。缺陷滞留时长只表达当前未闭环年龄：GitLab 已关闭或命中客户问题最终闭环状态时为 `0`，否则按一次请求固定的 `asOf` 与 `created_at_source` 动态计算；它不能写入事实或页面快照，也不能承载历史解决周期。上述字段、候选和导出只属于 CC_PRODUCT，延期专题不消费它们。详见 `docs/decisions.md`（D-03）。
- `issue_fact.handler_name` 与 `issue_fact.assignee_name` 是独立人员事实；当前 GitLab ODS 只暴露一份规范指派身份时，事实构建可以写入相同值，但查询、筛选、排序和导出不得把两个字段重新合并。`testing_phase` 原始空值保持为空，CC_PRODUCT 仅在响应投影中显示“未设定测试阶段”。
- `code_review_external_metrics` 是 GitLab diff 派生行数和 MR 标题功能名的权威补齐模型。既有或手工导入指标默认视为 `SUCCESS`，只有历史/已完成运行扫描明确发现的 CC MR 才进入补齐队列；后台以持久化 keyset 游标分别扫描历史 MR 和已完成镜像运行，队列按 `source_instance` 隔离并经历 `PENDING/RUNNING/RETRY/ENRICHED/SUCCESS/FAILED`。网络、限流和服务端错误使用封顶指数退避持续重试，认证、资源不存在、非法地址和截断响应进入确定性失败。补齐只通过 API Token 访问 GitLab v4 changes 接口，页面请求不得实时访问 GitLab；成功指标在事实构建互斥内定向发布到 `merge_request_fact`，构建忙时保持 `ENRICHED` 等待下一批次。全局调度关闭时补齐任务必须停止。

- 完整读发布模型采用行修订、半开可见区间与原子域指针。整轮候选固定根集合/水位，真实投影持久产物匹配封印/定义后才统一发布事实、范围、heads、意图 CAS 与缓存；候选预热产物必须在写入事务内冻结同一次写入并读回的内容证据，完成只落盘本任务证据，发布前对全部 `PREPARED` 行双向核验（D-26「候选产物归属证明」）；失败/中止保持旧完整点。页面显式传递 FactReadSelection/DatasetReadSelection，冻结定义与业务日，租约和短事务共享锁保护历史；兼容/原生/镜像数据集恢复真实类型，CAT 引用既有不可变快照。整批页面及下钻/规则/下载只使用已接受身份，准备失败保留旧画面，跨页身份单独核验目标权限与闭包。定义独立变化复用完整重建或数据集持久发布请求。详细不变量见 D-26，BI 专属行为见其文档域；当前实现、完整复验、安装及发布门禁状态唯一见进度文档，不能由局部测试推断可部署。

- 写回版本适用边界：本节多修订/候选交接缺口描述 D-26 主工作树，不原样适用于已恢复 1010 模型的 90bbb/696c 制品。两条线路仍使用内存编排加持久标签作业队列；整轮持久恢复、worker 执行前来源收敛复核及按租约归属结算尚未接入，不能将基础写回已实现等同接替修复全部完成。完成度、制品证据与后续状态见 `docs/progress.md`；目标模型改变时先裁剪专属发布步骤，不恢复双轨。

### 页面与前端底座

- 页面导航和权限入口由 `frontend/src/feature-manifest/` 维护；领域 API 位于 `frontend/src/api-client/`。
- 统计板复用统一运行时、筛选、排序、明细和导出契约；记录页复用共享查询、筛选状态和页面控制器，确有业务差异时保留显式特例。
- `StatisticCellData.numericValue` 是后端下发的排序键，只对计数、比率和时长列有效：比率分母为 `0`（显示 `/`）时必须为 `null`，不得写成 `0`；文本列的 `0` 只是占位。前端统一使用 `frontend/src/utils/missing-value-sorting.ts` 的“无数据恒置底”比较器排序，列是否携带数值排序键由 `frontend/src/components/statistic-board-metric.ts` 判定，不得恢复为 `metricType` 子串匹配或局部哨兵。
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
- 下拉框选项设置是全平台下拉候选的人工干预层：字段在 `DropdownOptionFieldRegistry` 显式注册并经 `dropdown_option_field_bindings` 绑定配置（可共用可拆分，配置名由绑定实时推导）；配置内自动获取值与手动添加值各走一套有序黑白名单（逐值首命中，存在白名单时未命中默认剔除），结果取并集；条件复用 `StatisticFilterEngine`（伪字段 `optionValue`），标签组条件求值期实时展开、失败按不命中降级。配置与绑定任一变化经 `reviewDataSourceVersion()` 指纹使候选快照失效；未绑定或空配置直通现状。详见 `docs/decisions.md`（D-09）。
- 议题模块只识别全角 `模块：`、`工具箱：`；测试阶段始终是独立事实维度。
- 代码走查 MR 模块独立识别 `模块[：|-]`、`工具箱[：|-]`，不得复用议题解析器。
- 快速筛选、高级筛选、导出和下钻只消费同一事实字段；兼容与非兼容模式不跨源提供模块候选。
- 数据库备份管理（系统设置→备份管理）：三表 `backup_settings`/`backup_runs`/`backup_state` 复用 BiCAT 单行条件 UPDATE 抢运行权与租约模式；执行流水线为磁盘预检 → 后端容器内 `pg_dump --format=custom` → `pg_restore --list` 可恢复性校验 → 本地原子落位（bind mount 直落宿主机）或 REMOTE 上传（sshj，TOFU 指纹校验 + `.part-` 临时名远端改名 + 大小/SHA-256 校验，成品仅存远端一份）→ 按份数轮转（只匹配 `qaflex_<实例标识>_*.dump` 本实例命名模式）；远程密码 AES-256-GCM 加密落库（主密钥 `PLATFORM_BACKUP_SECRET_KEY` .env 注入，页面不回显、留空不修改）；每日定时判期与孤儿回收共用 60s 巡检（注入 Clock 判期，失败不自动重试）；测试连接为纯只读三查（SSH 登录与指纹/目录写探针/磁盘余量），绝不触发备份；页面不做一键恢复，恢复仅走 `deploy/runbooks/database-backup-restore.md`。详见 `docs/decisions.md`（D-11）。

### 质量看板契约

- 研发质量看板保留老平台八个 headline 指标和五类辅助图表；其中 DGM 只参与明确的代码走查指标和图表。
- 其他看板提供老平台六类统计，不提供 DGM 选择器或 DGM 接口，其固定 GitLab CC 边界不参与老平台代码走查交接。研发质量/多元看板、代码走查非法数据、系统测试横向对比和外部数据集等交接消费者统一读取 `code_review_formal_records`：CC/DGM 各自优先读取已交接的 `LEGACY_PLATFORM` 事实，未交接时回退到对应 GitLab 正式范围；禁止在消费者内复制来源判断。
- 其他看板的代码规模来自 `merge_request_fact`：功能缺陷密度只统计需代码走查的已合并 CC MR，质量达人榜统计成员全部已合并 CC MR；人员范围由 `quality_board_member_scopes` 管理。代码规模补齐状态不能改变页面 API，未完成补齐的数据按当前已提交事实展示，成功发布后由事实与统计版本统一失效刷新。

### 业务模块

表单管理前端以单页任务工作区呈现现有管理接口，后台壳、端点和公开类型保持独立。业务变化投影与字段身份/存储定义分离，工程值不直接渲染；比较基准在加载时冻结，编辑中不被新预览替换。自动保存复用草稿CAS，失败停止自动重试并保留输入；发布先收口当前保存再取得归属一致的预览，重试复用同次发布意图。保存、预览、轮询、冲突读取与最终刷新继续复核原范围/加载代次/输入快照。管理页体验及确认边界归产品文档。

表单项目候选从已同步的GitLab项目目录由平台后端提供，不由浏览器直接连接GitLab数据库，也不从已有事实/统计的项目子集推导可用项目。候选及写入核对完整来源/项目身份、实际可见范围与删除状态；展示名称/命名空间/路径，保存稳定ID。现有表单管理仅提供来源及里程碑候选，项目候选接口仍待设计与实施；物理名称列、路径字段及来源隔离须从实际ODS结构核实。版本分支候选不能未经核实直接复用里程碑，元数据未就绪不编造选项。此项功能合同纳入N0，尚未执行接口或UI变更。

2026-10-30生产接入边界修订：Issue按模板业务启用点T切换新资源FORM/历史COMMENT；走查表可随包部署，T_mr未设置时生产MR继续COMMENT。外部数据齐全、老平台筛选对齐并验收后再设置T_mr；旧非法页整页保持原读身份与结果直至同批切换。旧模板解析服务历史权威，结构发布不改变业务启用点或既有资源权威。

FORM接入选型：新增IssueBusinessInput与FormIssueInputResolver.resolve，首次事实和周期SLA共用类型化输入及IssueSlaRules.evaluate；IssueFactSourceRowMapper只映射资源基础列，IssueBusinessProjector.apply写业务列，raw_payload业务解析仅用于COMMENT。事实与延期作业持久保存覆盖响应/回复head及权威修订的form_input_hash；既有worker发送前核对当前证据与作业/事实hash、latest=consumed及原轮次授权/fencing。MR选逐记录form_review_fact投影，FormReviewProjector.build与事实同事务发布，替换FORM的form_records CTE；五类数和人分钟求和，均时按记录sum/count，人员按稳定ID集合、走查日期取最近创建时间，密度按MR去重外部新增行。COMMENT继续原查询，新旧统计分区；N4实施目标与断言归《表单接入既有消费链方案》。

表单选择器复用评审数据管理页使用的`components/base/SmartSelect.vue`及其搜索逻辑，单/多选与紧凑模式按对应场景配置；该页筛选区与编辑弹窗的既有配置分别核对。复用组件不复用评审域候选数据：项目/人员等目录保持各自权威身份，不照搬其它业务字段的`allow-create`设置而允许任意造值。

当前一级模块包括：质量看板、评审数据、代码走查、系统测试、客户问题、BI 看板和系统设置。老平台独立“集成测试”模块不恢复。BI 的全部专属事实从 `docs/bi-dashboard/README.md` 统一路由；其余模块的字段、导出、非法数据和下钻规则归 `docs/platform-page-business-rules.md`。

表单采集的目标为MR走查、Issue响应和Issue回复三种独立结构化输入，由各消费方结合GitLab上下文形成事实、合规、统计和导出。2026-10-26用户确认v6.0：历史记录留在所属版本，新建使用当前版本，共同业务字段跨版本统计并按版本导出。v6技术合同经N0五审批准，N1基础实现已在[输入369194aa](plans/unified-form-system-implementation-plan-20261021.md#n1-approval)验收，N2记录服务与填写页已在[输入965e740e](plans/unified-form-system-implementation-plan-20261021.md#n2-approval)验收。N2使用目录origin唯一重放、NONE历史只读与真实时间、有效v2草稿优先和账号隔离；编辑结果未知时先复核范围、所属规格与首次哈希，缺失/失配保持unknown及原快照，证据恢复后重新核验。v5.1普通发布路径已由N3 v2空结构发布替换（输入c0c11489已于2026-10-30通过阶段验收，N4按执行单准入）；消费路径仍按后续单元推进。N3普通发布只建立空新版本并切新建指针，不改绑原生旧记录的目录承载位置。当前阶段及准入归progress与总执行单，旧版批准只证明对应旧版输入。

表单记录增量写入，记录身份、所属结构版本与recordVersion分别处理。全局定位目录将record_id及scope/requestKey唯一映射到实际版本；新建事务原子写物理行、目录、审计和资源变更head。旧记录编辑/作废按所属规格与本人归属执行CAS，不依赖当前新建指针；新字段不回填旧记录。新增响应未知时按原key跨目录核验，命中后用创建时冻结规格比对首次指纹，不依赖当前人员目录，不在新版重复创建。新版普通发布建立空版本表、核验结构并短事务追加版本源/切新建指针，不复制、truncate或改写旧记录。具体合同见[主方案§3.1至§3.4](plans/unified-form-system-plan-20261009.md#31-存储层设计)。

N1已实现的基础边界：`FormRecordDirectoryService`原子登记记录定位与首次身份，`FormRecordDirectoryReader`只从AUTHORITATIVE且PUBLISHED/RETIRED的承载版本读取，NONE按登记旧源表原位取值；业务读取排除作废，显式定位保留作废状态，并核对实际实例ID、范围、模板、完整C与创建人。`FormV6RegistrationService`只由受控内部入口执行，持锁后复核真实绑定、冻结结构、全部参与副本和逐行目录；类型化证据对标量/数组保持NULL及元素边界，重复规范化不丢值，重入比较持久完成证据。源变、缺证据、部分目录、冲突或漂移拒绝并回滚，不改原业务行/冻结规格/首次指纹；真实库登记仍需外部证据与现场授权。N1活动过程已归Git历史，正式判据与批准输入归总执行单。

每个scope登记权威记录源集合及修订/哈希，成功发布的原生历史版本继续参与读取。已有v5复制旧表仅为快照，先核实唯一承载副本并登记，不把全部版本表直接并集；旧Demo也只原位登记，不恢复废弃HTTP写入口，缺创建人的历史记录只读。具体元数据、唯一索引、锁序和前向迁移按N0已批合同实施，既有Flyway和真实审计数据不改写。保留受控DDL完整漂移核验、同物理连接CHECK解析、草稿CAS、租约/fencing、有效租约判断及唯一FORM/COMMENT权威。历史复制日志解释已有事件，不再定义普通结构发布流程。

MR/Issue全量、定向、周期、诊断、统计/导出及写回前核验使用同一多版本读取器；候选冻结可读版本集合、各版规格/词典、资源变化版本和权威修订，结果公开与head结算同事务复核，漂移零公开/零结算。旧pending_storage_version_id/current_version_id不再被当作全部存量数据证据。共同字段按稳定业务身份及可比语义聚合；可比类型绑定每版冻结field.type并与语义声明一致，冲突在发布边界阻断、读取边界明确无效，不用种子默认类型覆盖冻结声明，不把排除不兼容值后的部分结果标为成功。差异字段不补零、不回读评论；Issue最新/首次等选择先跨版选完整候选集。范围目录变更写持久信号，必要重建沿已有任务机制执行，不在建表事务扫描全部历史行。

管理员及填写页异步结果均绑定原账号/C或范围、编辑会话/加载代次及输入修订，包含失败、冲突二次读取和发布最终刷新。未保存新建草稿因当前新建版本变更停止提交并保留内容；既有历史记录编辑继续按原版。状态机、分叉草稿、未知结果禁复制/丢弃、按需刷新和退出保护按已批准体验保留。版本导出使用原版字段并受原数据可见范围与独立导出授权限制，正式端点/权限/预算在准入包登记。决策及替换理由见[D-32](decisions.md#d-32-统一表单字段发布原版本留存跨版本读取与独立导出)。

N3 v2 普通发布器已替换 v5.1 复制路径（输入c0c11489阶段验收通过）：任务 `protocol_version=2` 依次经过 `VALIDATING → BUILDING → SWITCHING → PUBLISHED/FAILED`；构建只创建目标空结构并用同一生成器/Verifier 校验，不复制、truncate 或改写旧记录；切换在短写栅栏内复核冻结源集合、当前指针与空目标，原子完成目标 `AUTHORITATIVE/PUBLISHED`、旧版 `RETIRED`、范围源集合修订/哈希、目录事件与 head pending，随后清栅栏。构建期可写，切换期写入返回可重试冲突。锁序固定为 record-id advisory → scope → publication → head；每次 DDL/切换事务按 `lease_owner/fencing_version/fence_token` 与有效租约校验，过期 `BUILDING/SWITCHING` 可由新拥有者接管；旧 `protocol=1` 任务只读盘点与受控收口，真实库不自动收口。发布类型决定允许的范围状态：`INITIAL_EMPTY` 只接受 `DRAFT` 且无当前指针，`MIGRATION` 只接受 `ACTIVE` 且当前指针等于任务冻结的旧指针；同一规则用于领取后执行、切换入口与 FAILED 恢复，`RETIRED` 一律拒绝，因此恢复失败任务不能重新启用已退役范围（重新启用属另行审批的 S1 功能）。任务创建时持久冻结原范围绑定证据（`origin_binding`/`origin_binding_revision`/`origin_binding_hash`），与拟发布绑定分离；切换与恢复在范围锁内同时复核修订/哈希与实际规范绑定，失配即拒绝且不重冻结、不用旧任务覆盖新绑定。`FormHeadSwitchService` 只直锁head基表并按id序加锁，更新目标head后核验权威集合、实例集合与COMMENT证据三类零计数不变量。结构冻结核验保留生成器 + 真实 PG 的聚焦回归（CHECK 逻辑分组、外键删除动作、临时表跨连接清理、约束/索引/记录ID默认序列），使用“每次借用独立物理连接且不归还”的池入口强制同一连接内完成临时表生命周期。发布验收启动使用 `scripts/run_isolated_form_acceptance_backend.py`：先只读确认资源身份（容器运行与 ID、端口映射、容器内与 host 连接命中同一 `system_identifier` 与库名，并拒绝共享端口 15432/15433），确认后才迁移专用进程升级、初始化安全开关，再只读核验迁移头、非目标作业、match-mode 与外部目标，最后只开放发布 worker 启动；资源身份或库内预检不符即停止，身份阶段不产生任何写入、也不启动后端。原“先当前物理表重放”旁路已在N2整改删除；当前创建只按目录origin证据核验，按实际承载位置返回，新提交仍要求当前版本。管理接口统一要求`form.template.manage`，普通用户页面不可见且直接请求返回403（A0303）。

缺陷原因的目标数据边界已于2026-10-20确认、尚未实施：表单按稳定原因ID集合保留真实多选，字段格式/必填验证与Wiki唯一性合规分开；消费者不能复用旧空格/`&`计数缺陷，非法标志也不能反向删改采集值。原因统计保持独立口径，详见[决策D-30](decisions.md#d-30-缺陷原因采集与合规判定分层)。该决策不改变既有原始记录或授权真实重算。

## 认证与授权

- LDAP 与平台独立。LDAP 提供账号、姓名、状态和角色基础信息；平台创建自己的 HTTP Session，不使用 LDAP JWT 访问业务接口。
- 同一主机的浏览器 Cookie 不按端口隔离，因此平台实例必须拥有稳定的 Cookie 命名空间：标准 Compose 部署使用唯一且跨更新保持不变的 `COMPOSE_PROJECT_NAME` 作为 `PLATFORM_INSTANCE_ID`，Session 与 CSRF Cookie 名由其摘要派生；直接运行未显式设置实例身份时使用应用名和监听端口。
- CSRF Cookie 也必须属于该实例命名空间。它由后端以 `X-XSRF-TOKEN` 响应头交付，前端按浏览器 Origin 保存 Token，并仅在非安全请求中通过同名请求头回传；不得恢复固定 `JSESSIONID`、`XSRF-TOKEN` 或从 Cookie 名猜测 Token 的共享契约。
- 当前产品默认认证 Provider 为 LDAP；本地账号认证只允许通过显式配置启用，缺失配置不得静默把 LDAP 用户切换到本地账号校验。
- 本地认证仅在 `PLATFORM_AUTH_PROVIDER=local` 时允许；正式安全校验要求本地管理员/审批账号使用 Spring Security password hash，并始终要求有效的数据库密码和 GitLab Web 地址。
- 内网发布必须显式设置 `PLATFORM_AUTH_PROVIDER=ldap` 和从后端容器可达的 `PLATFORM_LDAP_BASE_URL`；LDAP 发布模式不注入本地管理员/审批账号，Session CSRF 保持启用。
- LDAP 不可用时已有 Session 和本地权限继续有效；新登录必须失败，不能使用缓存密码绕过认证。角色和账号状态在下次登录时更新，当前阶段不做轮询踢出。
- 平台本地维护细粒度权限目录及 LDAP 角色映射；一个用户的最终权限是所有角色权限的并集，后端为最终裁决者。
- 页面级权限按「资源 + 页面身份」解析：统计板与分析看板由 `@RequirePagePermission` + `PagePermissionKeyResolver` 从 URI 路由变量解析；BI 看板的页面身份在端点路径常量与下载请求体中，由端点注解按页面查看/下载码 + `BiPagePermissionResolver` 解析。程序化鉴权（`PlatformPermissionService.requirePermission` 等）以 Spring `AccessDeniedException` 表达「已登录但无权」，统一映射为 403（`A0303`），不得落成 500；权限默认授权必须与角色授权同批登记，否则「恢复默认权限」会把权限从所有角色清空（见 `docs/decisions.md` D-22）。
- 角色名称可展示和编辑，角色编码是稳定关联键；“一级/二级/三级”不是系统概念，不能进入代码或数据模型。
- 评审记录和问题项的 `created_by` 为空时表示历史数据无归属，不进行推测回填；评审管理的本人删除和任意删除分别由本地权限控制。
- 详细边界见 `docs/decisions.md`（D-01）。
## 兼容模式边界

- CC/DGM、老平台评审数据等临时兼容源必须通过明确的 Match mode 服务、表、任务和 API 访问，并在代码中标注 `兼容模式` / `Match mode`。
- 代码走查老平台单条刷新在 HTTP 请求完成后立即按同源 MySQL 读取该 MR 的全部拆分行。DGM 请求必须同时携带 `Data_Source: DGM` 头与原有 `dataSource=DGM` 查询参数：老平台拦截器只按请求头切换 GitLab 与数据库，查询参数仅用于业务项目选择；兼容读取与老数据转正式刷新共用这一协议。回归测试使用隔离 MySQL/PG 和按头选源的模拟老接口，验证同号跨源隔离及通过记录退出兼容非法列表。
- 正式数据源与兼容数据源不可互相覆盖。`review_data_read_mode=compatibility` 时，`review_visible_*` 暴露正式评审与未被 `PLATFORM_OWNED` 映射遮蔽的 Mongo 快照；`formal` 时仅暴露正式评审。负 ID 快照第一次编辑在单一事务内完整物化主记录、专家、描述、内容和问题项，并建立 `PLATFORM_OWNED` 映射；Mongo 同步只替换快照，不得回写正式评审。代码走查交接成功后由 `code_review_formal_records` 选择正式事实，旧兼容快照不再直接进入正式消费者。所有兼容路径必须标注 `兼容模式` / `Match mode`。
- 老平台评审禁止批量转正式或建立 `LEGACY_MANAGED` 副本；`V20260730_07` 清理错误副本并恢复评审读源设置。代码走查仍是可审计、幂等的正式交接，执行前校验已保存设置版本和源范围，使用 PostgreSQL advisory lock 防并发，并在 `REPEATABLE_READ` 事务内提交；失败写入审计任务，不报告为成功。历史无创建人保持 `created_by = null`。
- 兼容模式 MR 专用测试环境资源固定为 `qaflex-matchmode-mr-*` 容器和 `qaflex_matchmode_mr_pgdata` volume；未经授权不得删除或改名。

## 外部数据集 API

- `/api/external/v1/datasets` 仅面向经过确认的进程外消费者。通用边界见 `docs/decisions.md`（D-02）；新增 Provider 前必须有真实消费者、schema、权限和移除条件。
- 外部 API 默认关闭，使用独立无状态 Bearer token 和数据集白名单；浏览器业务接口继续使用 LDAP Session 与 CSRF，两条认证链不得混用。

## 性能、迁移与发布不变量

- 规模敏感路径先测量再优化；统计查询、事实构建、同步任务和前端渲染不得无限积压或无界等待。
- 默认 `taskScheduler` 只承载短触发任务；`@Async` 与后台编排落在具名应用异步执行器 `platformAsyncExecutor`（显式指定且为 `@Primary`，防止回落调度池）。读版本生产/接管及回收另用单线程 `readPublicationScheduler`，固定数量的 fixedDelay 周期完成后再调度，不提交无界工作队列；不得把大表查询放回默认派发线程。默认调度池只有 1 个线程，长任务会挤占全部调度，等待同池派发的运行还会形成自锁。跨子系统收敛由运行终态事件推进，不得用轮询和任意固定超时表达。
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

## 黄金基线回归测试

- 目的：把冻结的源数据夹具走平台真实链路（同步 → 事实 → 统计），对所有产出数据的接口快照输出，机器 diff 检测任何改动引起的产出漂移。快照严格模式：缺失即失败，杜绝静默遗漏。
- 覆盖清单是机器强制的：`GoldenBaselineCoverageGuardTest`（默认套件）反射枚举全部 Controller 端点，断言每个端点在 `backend/src/test/resources/golden-baseline/endpoint-catalog.yml` 登记（READ/OPTIONS/EXPORT/WRITE 或 EXCLUDED+原因）；新增端点未登记则门禁失败。
- 运行入口：backend 目录 `mvn test -Pgolden-baseline -Dtest=GoldenBaselineChainTest`（需 Docker；双 Testcontainers PostgreSQL 承载 GitLab 源切片与平台库，真实全量同步后遍历目录用例）。默认快速套件排除 `golden-baseline` 标签，不受影响。
- 快照体系：读/导出接口比对 ApiResponse JSON（Excel 规范化为 sheet→行→单元格 JSON）；写接口固定输入执行后快照响应与受影响表状态，逐用例隔离还原。易变字段（墙钟时间戳、端口等）在目录中声明掩码：响应 `ignore-paths` 是比对期忽略；表快照 `ignore-columns` 是生成期剔除，改掩码必须 `-Dgolden.update=true` 重跑重生成并人工审阅 diff。
- 有意的行为变更：显式 `-Dgolden.update=true` 重建受影响快照 → git diff 逐项人工审阅 → 与本次变更同单元提交。无意的快照差异视为回归，必须修复实现，禁止更新快照掩盖。
- 基线夹具（`golden-baseline/fixtures/`）冻结：改动夹具 = 新基线版本，须同步更新 `baseline-manifest.json`（行数与 SHA-256）并在决策记录留痕；夹具与快照由版本库管理。
- 基线版本状态：快照基线跟随生成它的工作树代码。解耦重构通过内网测试前，基线为开发期状态（见 `docs/decisions.md` D-08）；内网测试通过后必须 `-Dgolden.update=true` 重冻结升级为可信基线。
- golden 运行档禁用外部活体（GitLab Web API、MySQL/Mongo 老平台）、自动周期同步、富化与回写；保留受控真实镜像、事实、投影及完整数据集工作器，以完成冻结输入的正常生产链。外部路径在目录中 EXCLUDED 并注明由其它测试层覆盖。
- 完整读发布链须等待镜像/事实以及真实数据集完整产物，再捕获快照。新增 readView 随机身份与运行时刻先核验真实持久租约、范围、固定业务日、完整版本和最小数据时刻，再作规范化；业务状态、数量与范围保持严格比对。修订表随机 UUID 映射到真实域/代际起点/发布序号，保留父关系及可见区间。兼容完整性扩展夹具只补冻结输入的装载元数据，原始业务夹具摘要不变（D-08）。黄金 harness 同时冻结“业务日”和“当前时刻”：生产以 `PlatformConfiguration.platformClock()` 提供系统 UTC 时钟并由需要墙钟口径的服务构造注入，harness 以 `@Primary` 固定到基线清单 `frozen_at`，使滞留时长一类派生字段不再随运行日历抖动快照文件；目录中既有的比对期掩码保持不变，不因该修复放宽或收紧（D-08 第 7 条）。产出行序的稳定排序必须由声明业务身份收尾（兼容数据集用 `legacy_source_id`，正式事实用 `business_order_id + visible_from`），禁止以物理修订 `id` 或行序充当顺序；harness 暂停周期生产者须沿 Spring 调度包装链解出真实目标并断言取消数为正，否则暂停会静默失效并把测试输入登记成额外事实重建（D-08）。
