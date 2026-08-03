# GitLab 增量物理删除探测与精准派生数据发布开发计划

## 进度与中间物

- 状态：2026-07-31 阶段 1 至 6 的本地实现已收口，正在执行阶段 7 的最终门禁；尚未达到可部署状态，剩余硬门禁是约 270 万行固定负载基准、参数定版和内网真实删除/无关功能验收。
- 本次完成：三个唯一依赖目录、批量权威范围、统一 `SCAN -> RECONCILE` 静止屏障、真实类型参数化主键存在查询、每表单一可续跑对账任务、事务内版本化事实 outbox、根版本 fencing、有界根批次、稳定投影 generation、可恢复投影任务、手动 publication fence 和自动增量删除页让行均已落地；旧影响推导、200 目标回退、每对象/每对账页任务、旧目录和 `COMPENSATION_SCAN` 已删除。新增迁移 `V20260731_01` 至 `V20260731_05` 及 ADR-005，权威架构与进度文档已同步。
- 已确认基线：现有 `updated_at` 增量扫描继续负责新增与修改；现有 `RECONCILE` 已具备“分页读取镜像 active 主键 -> 到 GitLab 验证存在性 -> 将镜像独有行写 tombstone”的正确基础能力；现有 `AUTHORITATIVE` 已具备按父对象完整集合替换关系的正确语义。
- 已确认目标：手动页面刷新只处理其事实依赖的镜像表，自动增量处理本次配置选中的推荐业务表；两者均在增量写入后执行主键删除探测，只对实际变化影响的 Issue/MR 构建事实并发布相关快照，不运行全量补偿或全量事实刷新。
- 已确认规模：推荐目录当前为 23 张表；内网约 270 万行，最大 `notes` 约 100 万至 200 万行；本地同步运行当前使用 2 worker，调度器每 60 秒检查一次。
- 已确认性能证据：本地前台增量约 10 秒、单表刷新约 4 至 6 秒；小增量权威关系任务在 24 个目标时约 1.126 至 3.118 worker-seconds，195 个目标时约 5.736 worker-seconds；现有完整补偿与全量派生发布约 5 分 34 秒至 7 分 53 秒，不能进入每轮增量主链路。
- 已确认分页放大：本地运行 `490` 仅验证 268,922 个镜像键就生成 555 个 `RECONCILE` 持久任务，删除数为 0，阶段跨度约 142.150 秒、实际 worker 时间约 61.044 秒；现有“每 500 键创建一个续页任务”不能直接扩展到每轮 270 万键。
- 已确认缺口：现有删除 `RECONCILE` 只由 `FULL_RECONCILE` 全量扫描结束后触发；权威范围按对象创建持久任务；事实影响超过 200 个目标会回退；事实 SQL 以长 OR 条件传递目标；Issue 快照按事实类型全扇出且使用全局事实版本。
- 已确认旧类型：生产提交入口、手工入口和每日调度只提交 `FULL_COMPENSATION_SCAN`，`COMPENSATION_SCAN` 仅余枚举、策略、诊断与测试引用；目标版本直接删除 `COMPENSATION_SCAN` 全链，不保留无生产入口的运行类型。
- 已完成计划审查：补齐跨工作区投影发布、真实业务变化识别、事实 outbox 重试与提交原子性、自动增量页边界让行、权威范围静止屏障和旧运行类型删除契约。
- 验证状态：受影响的 42 个测试类此前 232/232 通过；最终补入自动增量删除页让行和同任务续页后，当前工作树直接相关 3 类 27/27 通过。Java 21 已重新编译 682 个生产源码和 213 个测试源码；Checkstyle 0 违规、SpotBugs 0 问题。Flyway 117 个迁移从空库应用成功，5 个新增迁移已登记 checksum；不可变性、破坏性迁移审查及自测、Profile 覆盖、跟踪产物、文本空白和 `git diff --check` 通过。运行产物位置门禁仅被用户既有 8 个根目录 `.tmp-*.log` 阻断；schema 漂移无缺失，仍有历史基线 13 表/35 索引只存在于 Flyway。内网 270 万行删除探测绝对耗时仍未实测，不得宣称性能验收完成。
- 当前阻塞：无代码或设计阻塞。上线参数、批大小和最终 worker 数必须由固定负载基准确定；内网真实 GitLab、页面和外部消费者验收必须在部署窗口完成。

## 恢复线索

- 当前阶段：本地实现收口与定向回归完成；正在复验静态检查、仓库门禁和完整差异，随后仅剩固定负载基准与部署验收。
- 恢复后首条命令：`git status --short`，随后运行 `python scripts/check_worktree_artifacts.py` 并检查本计划顶部的最新验证证据；固定负载环境可用时直接从“固定负载容量基准”矩阵继续。
- 前置计划：[GitLab 物理删除同步覆盖审计与修复交接](audit-gitlab-physical-delete-reconciliation.md)。该计划记录已落地权威范围与物理删除根因，本计划负责将删除探测纳入日常手动/增量主链路并消除全量派生回退。
- 当前代码基线：`77f9876b fix(fact): 修复自动事实刷新父子运行身份`。
- 实施恢复点：本地代码不再从早期阶段重做；恢复时先完成尚未通过的门禁，再进入阶段 7 固定负载基准。若基准不满足目标，回到统一 batch/worker 和索引边界调优，不恢复已删除的全量回退或双轨实现。

## 目标与边界

### 用户确认的目标模型

统一后的正常同步模型只有一条：

```text
现有增量新增/修改扫描
  -> 本次范围内的镜像主键删除探测
  -> 删除前持久化受影响业务根对象
  -> ODS tombstone/upsert
  -> 批量定向事实替换
  -> 基于变更前后范围的定向统计/记录快照发布
```

- `updated_at`、已有索引感知主键窗口和权威范围信号继续负责新增与修改，不改成全表业务行扫描。
- 删除探测只回答“镜像 active 主键是否仍存在于 GitLab”，不读取全量业务字段，也不为 source-only 主键重复承担新增同步。
- 手动“刷新最新数据”从页面工作区解析事实依赖，再解析事实依赖的源表；不由页面服务各自维护字符串表清单。
- 自动增量对该运行实际选择的源表执行删除探测。当前 `RECOMMENDED` 配置即 23 张推荐表；`CUSTOM`/`ALL` 严格跟随已保存配置和本次运行表范围，不越权扫描未配置表。
- 发现删除后先从镜像旧行及声明式血缘反查受影响 Issue/MR，再写 tombstone；不能先删除归属信息再猜影响范围。
- 没有业务变化时不创建事实构建和快照发布工作；有变化时计算量随去重后的受影响根对象和投影范围增长。

### 可验证成功标准

1. 删除一个 Issue 标签关系后，下一次自动增量或依赖 Issue 事实的手动刷新能够发现 `label_links` 缺失，ODS 写 tombstone，并只重建对应 Issue。
2. 标签移除后，旧严重程度、标签组、表头数量、下钻、导出和记录页均在同一派生发布链路收敛；不需要人工全量补偿或手工全量事实刷新。
3. `notes`、指派人、审核人、metrics、根 Issue/MR 以及被事实消费的维表物理删除均通过同一血缘与目标模型处理，不出现标签专用分支。
4. 自动增量在 `RECOMMENDED` 模式对 23 张推荐表执行删除探测；页面手动刷新只探测该工作区依赖表；数据库浏览器的显式单表刷新只探测所选源表。
5. 无差异运行完成删除探测后不提交任何事实或投影任务；无关表变化不刷新 ISSUE/MERGE_REQUEST/INTEGRATION_TEST 事实。
6. 任意数量的影响目标都走批量定向事实替换；1、200、201、500 和数千目标行为一致，不存在“超过阈值回退时间增量或全量事实”的分支。
7. 定向事实以 GitLab 根实体 `id` 为内部目标键并按 `source_instance` 隔离；根实体已删除时仍能删除旧事实，其他来源实例不受影响。
8. 快照发布使用变更前与变更后的稳定项目/scope-group ID，只失效并预热实际受影响的消费者；显示名和事实文本不充当身份，多范围 sourceVersion 确定且未受影响表格继续命中原快照。
9. 删除探测全程使用真实类型主键、复合主键、参数化批查询和 keyset 游标；JVM 内存复杂度为 `O(batchSize)`，不能随全表行数增长。
10. 任一 GitLab 查询失败、超时或返回非法主键时整页失败并可恢复，绝不能把查询失败解释成“来源为空”并批量误删。
11. 手动刷新只有在依赖表增量、删除探测、持久 publication fence 内事实版本和触发工作区必需 scope generation 均成功后才报告完成；部分成功必须明确展示未完成，不得宣称数据最新。
12. 内网固定负载基准证明自动运行不会持续重叠或积压，GitLab 关键在线查询和现有非相关页面没有可测回归后才允许上线自动删除探测。
13. 手动刷新扫描依赖表时发现的任何变化都发布其全部新旧影响投影；触发工作区只限制扫描表范围和自身完成等待，不能截断其他工作区、项目或页面的派生更新。
14. lookback 重读相同行、相同权威集合和仅镜像运行元数据变化不登记事实目标；只有业务源列实际变化、tombstone 状态变化或真实增删才触发派生发布。
15. 自动增量在删除页提交后即使暂停让行，已提交目标仍持续发布；随后手动同表刷新即使没有新的 ODS DML，也必须等待既有版本发布后才显示最新。

### 明确不做

- 不在每次自动增量后执行 `FULL_COMPENSATION_SCAN`、全量业务行同步、全量事实刷新或全量快照预热。
- 不把 GitLab 与镜像的全部主键一次性装入 Java `Set`，不为每张表单独创建线程，不绕过现有 `SyncExecutionBudget` 和单一镜像 writer。
- 不新增第二套同步调度器、旁路定时器、页面专用清理服务、标签专用补丁或新旧双轨入口。
- 不依赖 Issue System Hook 解决静默删除；当前 GitLab System Hook 没有 Issue 事件触发，Hook 仅作为已有精确信号快路径。
- 不物理删除 ODS 行。ODS 继续保留 `mirror_deleted=true` 的 tombstone 与旧身份，物理清理属于独立保留策略，不进入本工作单元。
- 不修改统计业务口径、页面字段、外部数据集契约、LDAP/权限、兼容模式或老平台读源。
- 不为旧内部接口保留别名、转发入口、回退分支或 `v2` 类；新权威模型完成后同步修改所有调用者并删除被替代代码。
- 不凭本地样本写死“23 线程”“固定 500 条”或绝对性能承诺；批大小与 worker 必须由计划中的容量基准确定。

## 约束与背景

### 运行与数据不变量

- GitLab 源库只读，当前现场事实为 GitLab CE 16.11.10 / PostgreSQL 14.11；平台库为 PostgreSQL。
- 同一 `configId + source_instance` 任一时刻只有一个镜像 writer；任务租约、心跳、分页原子提交、暂停让行和 DIRECT 连接预算继续遵守 ADR-004。
- 来源物理删除没有 `updated_at` 行可读，因此任何仅依赖时间游标的方案都不能独立发现静默删除。
- `label_links`、`notes` 等多态表必须携带类型限定；相同数字 ID 的 Issue 与 MergeRequest 不能互相影响。
- ODS upsert/tombstone、变化目标登记和任务分页终态必须在平台库同一事务提交；外部 GitLab 查询不能持有平台数据库事务。
- 全量补偿仍负责初始化、历史字段漂移和低频灾难恢复，但不再是正常数据实时性的前置条件。
- 首次部署前已有历史脏数据，仍按前置审计执行一次成功全量补偿及全量事实发布；此后日常正确性由新链路承担。

### 当前 23 张推荐表

`users`、`user_details`、`projects`、`namespaces`、`members`、`milestones`、`issues`、`issue_assignees`、`issue_metrics`、`notes`、`labels`、`label_links`、`resource_label_events`、`merge_requests`、`merge_request_assignees`、`merge_request_reviewers`、`merge_request_metrics`、`ci_pipelines`、`ci_builds`、`deployments`、`environments`、`events`、`todos`。

当前代码事实中 21 张推荐表使用单列主键，`ci_builds(id,partition_id)` 与 `issue_assignees(issue_id,user_id)` 使用复合主键；实施不得把单列 `id` 当成默认正确模型。

每张表都必须在目标血缘目录中显式声明以下三种结果之一：

1. 直接或间接影响某类事实，并提供从旧镜像行反查根对象的规则；
2. 只是权威关系/变更信号，由其派生的目标关系承担事实影响；
3. 当前没有派生事实消费者，删除只收敛 ODS。

禁止用 `default -> ignore` 静默处理新表。推荐表或事实 SQL 增加依赖时，契约测试必须迫使开发者补齐血缘声明。

### 名词约定

- **增量阶段**：现有时间/主键窗口扫描、精确 upsert 和权威范围替换，负责 source 仍存在的新增与修改。
- **删除探测阶段**：分页读取镜像 active 主键，批量查询这些键在 GitLab 是否存在，只处理 mirror-only 差集。
- **变化目标**：由某一镜像变更影响、需要重建的 Issue/MR/IntegrationTest 根实体，以 `source_instance + fact_type + root_id` 唯一标识。
- **投影范围**：统计或记录快照依赖的稳定范围，如项目、项目阶段、项目里程碑或真正的全局范围，不使用显示名称作为身份。
- **全量补偿**：读取全量业务行、覆盖镜像并执行删除对账的恢复操作，不等同于删除探测。

## 证据与根因

### 根因链

1. GitLab 删除 Issue 标签时物理删除 `label_links`，Issue 自身 `updated_at` 不保证变化。
2. 时间增量只能读取仍存在的行，无法返回已删除主键；ODS 因此保留 active 旧关系。
3. 事实构建继续读取旧关系，`issue_fact.severity_level` 等字段保持旧值。
4. 统计、下钻和导出基于旧事实继续计数；仅重建事实仍会再次读到错误 ODS。
5. 当前 `AUTHORITATIVE` 只有父对象或标签事件命中时才能完整替换关系；完全静默且父对象未命中的删除仍等待全量补偿。

### 可直接复用的现有实现

- `SyncRunTableTaskExecutor.executeReconciliationPage` 已按镜像主键分页，并通过 `findExistingPrimaryKeySignatures` 查询 GitLab 存在集合。
- `SyncRunTablePageCommitService.commitReconciliationPage` 已在本地事务内写 tombstone、提交游标、续接任务并更新 `last_full_verified_at`。
- `GitlabMirrorTableStorageService` 已支持复合主键 tombstone 和权威范围原子替换。
- `AuthoritativeRelationCatalog` 已表达 Issue/MR 与指派、审核、metrics、notes、label_links 的复合完整范围。
- `FactBuildService` 已支持按目标先删除旧 Issue/MR 事实再写当前结果，空来源可作为有效删除。
- 同步运行、表任务、事实子运行、租约、让行、重试和诊断已经形成统一编排，不需要另建系统。

### 必须先消除的放大点

1. 删除 `RECONCILE` 当前只在 `FULL_RECONCILE` 完整业务行扫描末尾创建，不能独立跟随增量。
2. `planAuthoritativeRelatedTasks` 为每个父对象/关系范围创建持久表任务，小增量可接受但规模放大后形成任务 N+1。
3. `FactRefreshImpactScopeService.MAX_PRECISE_TARGETS=200`，超过后回退到时间增量或全量；关系删除不保证推进根对象时间，回退既慢又可能不正确。
4. 事实目标 SQL 使用长 OR 谓词，目标数增加后 SQL 长度、解析时间和计划稳定性不可控。
5. 事实影响在运行完成后通过可变 ODS 的 `mirror_task_id` 和表任务反查；后续写入可能覆盖归属，变化目标不是独立持久事实。
6. `StatisticBoardSnapshotRefreshService` 和 `PageRecordSnapshotRefreshService` 只接收 `factType + full`，所有同事实类型刷新器都会执行。
7. Issue 快照 `source_version` 取最新成功事实任务的全局版本；即使只改一个 Issue，所有 Issue 快照也会版本失效。
8. ODS 热路径使用 `coalesce(mirror_deleted, false)=false`，与现有 `mirror_deleted=false` 部分索引谓词不一致，已观察到 PostgreSQL 选择顺序扫描。
9. GitLab 主键存在查询和平台 tombstone 更新当前拼接长 OR；必须改为参数化批量关系，不能把镜像值继续拼入 SQL 文本。
10. `resource_label_events.created_at` 在现场没有索引，不能通过提高该表无命中时间扫描频率替代删除探测。
11. 当前删除探测每页创建新的持久任务；268,922 个键已产生 555 个任务和 142 秒阶段跨度，任务插入、领取、租约、汇总和续接本身会成为 270 万键主路径瓶颈。
12. 当前镜像 upsert 的冲突更新条件允许 `updated_at >=`，权威替换还会强制更新；若不先区分业务列真实变化，lookback 重读和相同完整集合会被误当变化，破坏“零变化不建事实”的目标。

## 方案与步骤

### 总体策略：演进现有系统，不保留两套实现

目标运行仍为：

```text
Controller / Scheduler
  -> SyncRunSubmissionService
  -> SyncRunWorkerService
  -> SyncRunTablePlanningService
  -> SyncRunTableTaskExecutor
  -> SyncRunCompletionEvent
  -> FACT_REFRESH
  -> scoped projection publication
```

`RECONCILE` 从“全量扫描的附属末段”提升为统一的删除探测阶段。现有 `FULL_SYNC/FULL_COMPENSATION_SCAN` 也改为使用相同阶段编排，删除全量路径中按表末页临时追加对账的专用控制流。生产代码最终只保留一个主键存在性比较器、一个 tombstone 写入器、一个变化目标登记入口和一个派生发布入口。

### 运行范围矩阵

| 运行类型 | 增量/业务行阶段 | 删除探测范围 | 事实模式 | 投影模式 |
| --- | --- | --- | --- | --- |
| 自动 `INCREMENTAL_SYNC` | 现有增量 + 批量权威范围 | 本次配置实际选中的源表；推荐模式为 23 表 | 仅持久变化目标 | 仅新旧影响范围 |
| 页面手动刷新 | 工作区依赖表增量 + 批量权威范围 | 同一工作区依赖表 | 仅持久变化目标 | 发布扫描发现的全部影响范围；触发工作区只等待自身所需投影 |
| 数据库浏览器单表刷新 | 所选表现有刷新语义 | 所选表 | 由血缘决定；无消费者则不建事实 | 由变化目标决定 |
| `SYSTEM_HOOK` | 精确/权威范围快路径 | 不执行 23 表全局探测 | 仅精确变化目标 | 仅新旧影响范围 |
| `FULL_SYNC` | 全量业务行 | 全部实际同步表 | 全量 | 全量 |
| `FULL_COMPENSATION_SCAN` | 全量业务行 | 全部实际补偿表 | 全量 | 全量 |

### 1. 建立唯一的数据依赖与血缘目录

- 将 `AuthoritativeRelationCatalog` 重构为 `GitlabSourceLineageCatalog`，直接更新全部调用点并删除旧类，不保留转发包装。
- 每个来源表定义：稳定主键来源、删除探测资格、权威父子范围、事实消费者、根对象解析策略以及显式 `NO_DERIVED_CONSUMER`。
- 将 `GitlabFactRefreshRequirements` 重构为 `GitlabFactDependencyCatalog`，统一维护 `factType -> 必需业务表 + 变更信号表`；自定义白名单能力检查和手动工作区刷新共用该目录。
- 将 `RealtimeWorkspaceFactRequirementResolver` 扩展为唯一 `workspaceKey -> factType + 投影范围` 目录；删除各 Board/Record Service 中重复的 `REALTIME_REFRESH_TABLES`。
- 添加静态契约测试：扫描所有 `RealtimeWorkspaceService` 刷新入口、所有事实源 SQL和 23 张推荐表，确保没有未注册工作区、未声明事实依赖或默认为忽略的表。
- 多态关系继续要求类型限定；父范围、目标范围和固定值必须能生成规范化签名并接受复合主键测试。

### 2. 把同步运行拆成有屏障的两个阶段

- `SyncRunTablePlanningService` 为正常增量/手动范围只规划增量与权威范围工作；`SyncRunWorkerService` 先排空全部 `SCAN` 任务和动态权威范围。
- 只有第一阶段所有必需任务成功，才幂等规划每张目标表一个初始 `RECONCILE` 任务并进入删除探测；第一阶段失败时不得更新删除验证时间。
- 权威范围状态统一为 `QUEUED/RUNNING/RETRY_WAITING/SUCCESS/FAILED`。唯一 stage coordinator 必须在锁定运行行的同一事务内判定：全部能够产生范围的 `SCAN` producer 均为 `SUCCESS`，且该运行全部 scope 均为 `SUCCESS`，才允许幂等创建 `RECONCILE`；`QUEUED/RUNNING/RETRY_WAITING` 均阻断屏障，任一 `FAILED` 直接使本阶段失败，不能按空队列继续。页提交在登记范围和推进 producer 终态前获取同一运行锁，消除“判空后又入队”的竞态；重启恢复也只调用该 coordinator，不另写汇总分支。
- `RECONCILE` 继续使用现有 `SyncRunTableTaskStage`、租约和 keyset cursor，但每张表只保留一个可续跑任务。每页在同一任务上原子提交 cursor、tombstone 和变化目标；达到执行时间片或需要让行时把同一任务重新排队，禁止每页插入一条新的持久任务。
- 删除探测任务设置有界执行时间片。扩展 `SyncRunYieldService` 的运行级策略，使自动 `INCREMENTAL_SYNC` 仅在 `RECONCILE` 页事务提交后可暂停并释放同源 `exclusive_scope`/writer；手动刷新完成后原运行再竞争恢复，不能与手动 writer 并行。
- 页边界让行使用任务 ID、lease owner 与版本的 CAS：原子保存 cursor、累计扫描/删除计数，清空 lease，设置 `run_after` 并把同一任务改回可领取状态；未到最后一页不得设置 `finished_at`。CAS 失败的旧 worker 禁止释放运行所有权、重复提交或覆盖新租约。自动运行期间到达的手动刷新最坏只等待一个主键页或一个时间片，不能等待完整 `notes` 扫描结束。
- 全量运行也改为“完整 `SCAN` 阶段结束 -> 统一规划 `RECONCILE`”，删除 `commitScanPage` 在单表末页隐式追加对账任务的分支，避免同一能力存在两种编排。
- 重启恢复通过现有任务阶段和幂等任务键判断：已完成扫描不重复写业务行，已存在的删除探测页从持久 cursor 恢复。
- `sync_run_table_states` 新增语义明确的 `last_delete_reconciled_at`；`last_full_verified_at` 只表示完整业务数据验证，不能再混用为日常删除探测新鲜度。
- 页面工作区终态要求本次依赖表都有成功 `RECONCILE`；失败表、未配置表和未完成基线继续通过现有状态结构返回，不静默降级。

### 3. 优化主键删除探测而不扩大内存

- 保留单向算法：只分页读取镜像 active 主键并询问 GitLab 哪些键仍存在；新增由增量/权威范围负责，不需要再把 GitLab 全部主键拉回平台。
- 平台镜像读取固定使用 `mirror_deleted = false` 并以真实复合主键 keyset 排序，确保命中 active 部分索引；为缺失索引的镜像表通过独立 Flyway 迁移补齐。
- 迁移先把既有 `mirror_deleted is null` 统一回填为 `false`，再由 `GitlabMirrorSchemaService` 对现有和未来动态镜像表保证 `not null default false` 与 `(真实主键...) where mirror_deleted=false` 索引契约；不能只修改查询而让历史 NULL 行消失。
- GitLab 存在性查询改为参数化 `VALUES`/typed join（单主键可使用等价数组连接），不拼接主键字面量和长 OR；结果必须是请求键的子集，否则整页失败。
- DIRECT 模式通过外部 DataSource 的 PreparedStatement 传入数组/VALUES 参数；Docker 模式通过 `docker exec psql` 的标准输入把有界键批次导入会话级临时输入关系后连接查询，禁止把键拼进 shell 命令。若 Docker 传输实现或容量基准未通过，启动/提交时显式报告该模式暂不具备自动删除探测能力，不能静默退回长 OR。
- 平台 tombstone 更新改为参数化 `UPDATE ... FROM (VALUES ...)`，同时支持单主键和复合主键，避免长 OR 与 SQL 解析放大。
- 每页仅保留主键、存在签名和本页影响目标；严禁跨页累计全表 ID。
- 删除探测复用本次运行的 `resolved_worker_count` 和 DIRECT 连接预算。小表不创建额外线程，大表也不获得专用线程；表大小只影响页数，不改变执行模型。
- 分离 `delete-reconcile-batch-size` 与业务行批大小，隔离环境比较 2000、5000、10000、20000；配置必须有合法范围和启动校验，不能继续沿用未经容量验证的 500 键默认值。
- 对空镜像表直接成功；对缺少稳定主键、来源 schema 漂移或主键类型不一致的表显式失败并阻止新鲜度声明。
- DIRECT 与 Docker source mode 必须分别验证。Docker 模式当前每批通过 `docker exec` 且结果会物化为 List，未通过同等容量门禁前不能默认宣称支持每轮 23 表删除探测。

### 4. 批量化权威关系范围

- 新增规范化 `sync_run_authoritative_scopes` 队列表，按 `run_id + child_table + scope_signature` 去重父对象派生范围；不再为每个范围立即创建一个持久表任务。
- `sync_run_authoritative_scopes` 是按去重根范围增长的持久工作集，不伪装成常数规模；worker 按同一子表和同一关系定义领取 100 至 500 个范围组成一个执行批次，避免再创建 O(根对象数) 的 `sync_run_table_tasks`。实际批大小由基准确定。
- 范围批量领取、完成和重试必须携带 owner/lease/version fencing；失去租约的 worker 不得写镜像、登记目标或完成范围。领取事务使用有界批次和跳过锁定行，不允许两个 worker 重复拥有同一范围。
- 来源使用 `target_id = any(?)` 或 typed `VALUES` 查询完整集合，按范围键分组；没有来源行的组必须作为权威空集合参与替换。
- 平台在一个事务内批量读取这些范围的 active 镜像键、计算缺失、写 tombstone/upsert、登记事实目标并完成范围状态。
- `resource_label_events`、父 Issue/MR 增量、notes 自身增量和 System Hook 全部写入同一范围队列；递归派生规则由血缘目录显式禁止。
- 删除原来每个父行调用 `planAuthoritativeRelatedTasks` 创建任务的 N+1 路径，并搜索确认旧任务创建入口和单范围兼容分支无引用。

### 5. 在 ODS 事务内持久化变化目标

- 新增 `sync_run_fact_targets`，主键为 `(mirror_run_id, source_instance, fact_type, root_id)`；保存可诊断的 `project_id/iid`，但事实构建以 `root_id` 为权威目标。
- 只有实际业务变化的 ODS DML 才返回变化身份并登记目标：新行插入、源业务列 `IS DISTINCT FROM`、tombstone 恢复和真实删除。lookback 重读完全相同行、仅同步任务/时间等镜像元数据变化以及相同权威集合替换均为派生 no-op；元数据可单独推进，但不能制造事实目标。
- 发生实际变化的普通 upsert、权威范围替换和删除探测必须先根据血缘解析目标，再在同一平台事务内写目标与镜像变化；不能等镜像运行结束后从当前 active 行猜测。权威集合先按规范化真实主键计算 added/changed/removed 差集，只有差集项参与 DML 和目标登记，空差集只完成范围状态。
- 关系删除直接从旧镜像行读取 `target_id`、`noteable_id`、`issue_id`、`merge_request_id` 等归属；根实体删除从 tombstone 保留身份得到目标。
- 标签、用户、项目、命名空间、里程碑等维表删除在 tombstone 前通过声明式反向关系批量找出所有受影响根对象；目标表唯一键负责跨表和跨页去重。
- signal-only 表不直接登记事实目标，除非其自身字段进入事实；没有派生消费者的表只写 ODS，不提交事实任务。
- 删除 `FactRefreshImpactScopeService` 从表任务和可变 `mirror_task_id` 推导目标的职责。若该类没有其他职责则整体删除；不得保留旧推导作为失败回退。
- 镜像页提交后的持久目标扫描负责尽早创建或唤醒 `FACT_REFRESH`，镜像完成事件只负责最终 drain 通知；零目标增量不创建事实任务。不能再把父运行成功事件作为 outbox 的唯一提交入口。
- 删除零目标时调用 `reconcileMissingIssueFacts` 的现有行为；全局缺失事实修复只能属于显式恢复/反熵任务，不能让每个无变化增量运行产生事实写入或快照失效。
- 已提交 ODS 变化的目标不能因父运行随后失败、取消或超时而丢失。事实发布协调器必须从持久目标/outbox 判断待发布工作，父运行任何终态只要存在未发布目标都必须继续收敛；不能只依赖当前 `factRefreshEligible(SUCCESS/PARTIAL_SUCCESS)` 事件条件。
- 新增全局单调 `fact_change_version_seq` 和每个稳定根唯一的 `fact_change_heads` 版本头。ODS 业务 DML 按规范化目标顺序锁定 `source_instance + fact_type + root_id` 行，为真实变化分配新的 sequence version，写入头的 `latest_change_version` 和本运行目标；事实 worker 锁定同一行后只处理当前 ODS。若 `published_version` 已覆盖目标版本则直接幂等完成；否则发布当前 ODS 并推进到锁内观察到的最新版本。全局版本使持久 publication fence 可以表达确定水位，根行锁保证连续镜像运行 A/B 交错时旧任务不能写回旧事实。
- 同一 `mirror_run_id + source_instance + fact_type + root_id` 在跨表或跨页再次发生真实变化时，目标 upsert 必须写入更高 `change_version`、原子重置为 `PENDING`，并清空旧 assignment、published version/task/time 元数据。版本头行锁与事实 worker 共用，所以重置不会和旧任务提交交叉丢失。
- 已提交的 `PENDING` 目标不等待镜像父运行终态。持久协调器在父运行 `RUNNING/RETRYING/PAUSED` 或任意终态时都可幂等创建或唤醒该父运行唯一的 `FACT_REFRESH` 子运行并分批派发；父运行继续产生的新版本复用同一子运行，已发布版本不会阻止后续目标重新入队。

### 6. 将事实构建改为无上限的目标批处理

- 协调器按稳定键分页选择尚未分配且未被版本头覆盖的目标，将一个有界目标批次原子归属一个 `fact_build_task`；一项事实任务就是一个批次，worker 无二级 cursor，成功时整批提交。目标出现更新版本时可在版本头锁内撤销旧 assignment，旧任务执行前必须重验归属并把空批次作为幂等成功。批量大小可配置且有上限，不把全部目标装入单个 SQL 或 JVM 列表。
- `FactBuildService`、`IntegrationTestFactBuildService` 改用根 `id` 数组/目标关系连接 ODS，删除当前 `(project_id,iid)` 长 OR 谓词。
- 每批在同一带租约 fencing 的事实发布事务中：锁定稳定根目标并读取当前 change version -> 读取并保存旧投影范围 -> 删除这些根的旧事实及从属成员 -> 从当前 ODS 构建仍存在的事实 -> 读取新投影范围 -> 推进 generation 并创建投影任务 -> 标记覆盖版本内的目标 `PUBLISHED` -> 将事实任务置为成功。任一步失败整笔回滚，不能出现事实已替换但目标未发布或重复推进 generation 的崩溃窗口。
- ODS 变化事务与事实批次事务都按规范化 `(source_instance, fact_type, root_id)` 顺序获取版本头行锁；禁止按查询返回顺序加锁，避免多根批次互相死锁。
- 根来源为空是有效结果：只删除旧事实，不插入新事实；重复执行同一批结果一致。
- 删除 `MAX_PRECISE_TARGETS` 和所有“超过阈值回退时间增量/全量”的分支。全量只由明确的首次构建、人工全量或全量补偿触发。
- `FactBuildResponse.affectedRows` 区分处理目标数和实际事实写入数，运行日志记录目标批次数、删除数、插入/更新数与耗时，避免零插入的根删除被误报为无变化。

### 7. 用稳定投影范围替代全局快照扇出

- 定义 `FactProjectionScope` 的稳定类型：`FULL_EPOCH`、`GLOBAL_VIEW`、`PROJECT`、`ISSUE_SCOPE_GROUP`。`PROJECT` 的 `scopeKey` 是 GitLab `project_id`；`ISSUE_SCOPE_GROUP` 的权威键是 `project_id + IssueScopeDimension + issue_scope_groups.id`，统一编码器生成规范字符串。不可修改的 `issue_scope_groups.business_key` 继续作为 URL/查询外部身份，`display_name` 和事实显示值都不能充当缓存身份。
- Issue 旧、新事实范围通过现有权威目录解析：按 `issue_scope_catalogs(project_id, dimension)`，分别把 `testing_phase` 映射到 `TESTING_PHASE`、`milestone_title` 映射到 `MILESTONE`，再用 `issue_scope_members.source_value` 找到所有启用消费组的 `issue_scope_groups.id`。旧值、新值、多个维度和多个消费组取并集；根删除仍从旧事实得到完整旧范围。
- 新增 `fact_projection_generations`。全量事实发布只推进 `FULL_EPOCH`；定向发布根据旧事实和新事实的并集推进受影响 `PROJECT/ISSUE_SCOPE_GROUP`；真正跨全部数据的消费者才推进 `GLOBAL_VIEW`。`issue_scope_groups` 增加单调 `definition_generation`：group 显示名/启用状态修改推进自身，member 新增/修改/删除或迁移推进旧、新 group，catalog 启用状态或维度语义变化推进其全部 group；这些目录变化不重建事实，但必须使相关请求换 source version。
- `StatisticBoardSnapshotRequestFactory`、`PageRecordSnapshotService` 不再把“最新成功 Issue 事实任务 ID”作为所有快照的全局版本。每次请求先把 business key 解析为稳定 scope 集合，再按 `scopeType + scopeKey` 排序；新 `sourceVersion` 是 `FULL_EPOCH + 请求消费的全部 fact generation + 每个 group definition_generation + 既有规则版本` 的确定性编码，不能只取一个“当前范围”或依赖集合迭代顺序。
- 每个统计和记录刷新器显式声明消费的事实类型与投影范围，并把 `RefreshContext(factType, full)` 替换为包含范围集合的强类型上下文；更新全部实现后删除旧布尔上下文。
- 新增可重试的 `fact_projection_refresh_tasks`，按 `fact_build_task_id + scope_type + scope_key` 去重；一项 scope 任务聚合执行目录中所有适用统计/记录刷新器，因此持久结构不再存放或按 `projection_type` 分叉。事实事务提交 generation 与投影任务后，投影 worker 只预热匹配范围，所有适用刷新器成功才完成该任务。
- 每项投影任务保存事实事务分配的 `target_generation`。worker 领取后若当前 scope generation 已更高，则该旧任务以 superseded no-op 成功，不得用旧 generation 覆盖或宣称当前版本；否则只写入包含其 target generation 的规范 sourceVersion。
- generation 在事实事务内先推进，所以即使预热失败，页面也不会命中旧版本；请求会读取当前事实重新计算。工作区只有在必需投影任务成功后才显示本次刷新完成。
- 刷新器必须同时处理旧范围和新范围：标签/阶段/里程碑被移除时刷新旧表头，被新增时刷新新表头；根删除只有旧范围，也必须使旧统计减一。
- 全量发布继续刷新所有相关投影，但与定向路径共用相同接口和任务模型，不保留两套 snapshot 方法。

### 8. 统一手动页面刷新依赖

- `RealtimeIncrementalRefreshService.requestIncrementalRefresh` 改为接收强类型 `WorkspaceRefreshRequest(workspaceKey, stableScopeSelection)`，由工作区目录解析事实类型、源表、目标选择器和必需投影范围；页面服务不再传 `List<String>` 或显示名称。没有局部稳定范围的工作区显式使用 `GLOBAL` 选择器。
- 删除 `IssueFactRealtimeRefreshService`、`MergeRequestFactRealtimeRefreshService`、所有统计 Board 和记录 Service 中的 `REALTIME_REFRESH_TABLES` 常量。
- 工作区依赖表由 `workspace -> factType -> source tables/change signals` 两级目录得到；相同 Issue 页面自然得到相同依赖，不再因复制清单产生 `resource_label_events`、`issue_assignees` 等遗漏。
- 工作区依赖只限制本次扫描哪些来源表以及触发页面等待哪些投影任务。若整表删除探测发现其他项目或工作区的变化，仍必须为其全部新旧范围创建并执行投影任务；不得以触发工作区为过滤条件丢弃已提交变化。
- 手动镜像运行在依赖表 `RECONCILE` 完成、仍持有同源 writer 时，按工作区的 `source_instance + fact_type + target selector` 持久化 publication fence：记录此刻已提交目标的最大 `change_version`。同源 writer 排他性保证 fence 前不会再迟到提交一个更早目标；自动运行在页边界让行前已提交但尚未发布的目标也会被该 fence 捕获。
- `RealtimeWorkspaceRefreshProgressService` 不再只等待“本次子运行”。它先等待 fence 内目标全部被版本头覆盖并标记 `PUBLISHED`，再从这些目标的 `published_by_fact_build_task_id` 收集触发工作区消费的旧、新 scope 及其 required generation，等待相应投影任务成功并验证请求 sourceVersion；已由其他自动/手动 FACT_REFRESH 发布的版本同样有效。
- FACT_REFRESH 为父镜像运行持续 drain 全部已提交目标和跨工作区投影；触发工作区可以在自身 fence 满足后完成，其他受影响投影继续由 outbox 收敛。状态消息使用现有通用阶段表达，不向页面暴露内部表名或实现说明。
- `AuthoritativeRelationReconciliationService` 的“是否曾完成 FULL_RECONCILE label_links”判断由通用依赖新鲜度服务替代；所有工作区都按自身依赖表检查最近成功删除探测，不保留标签页面特例。
- 兼容模式服务继续在正式 GitLab 事实发布之后按既有边界组合读取，不把 Mongo/MySQL 兼容表纳入 GitLab 删除探测。

### 9. 保留全量补偿的唯一职责

- `FULL_COMPENSATION_SCAN` 保留用于首次部署、历史漏同步字段、灾难恢复和低频反熵；不由普通增量完成事件自动提交。
- 全量补偿复用统一 `SCAN -> RECONCILE -> 全量事实 -> 全量投影` 模型，删除其专用删除比较器或专用事实分支。
- 每日补偿频率是否调整属于容量基准后的运维决策；新链路验收前不取消安全兜底，也不把它包装成实时机制。
- 首次上线仍执行一次全量补偿清理历史漂移。验收成功后，后续物理删除场景必须只用普通自动或手动刷新证明，不再用补偿掩盖缺陷。
- 已确认 `COMPENSATION_SCAN` 没有生产提交入口；本工作单元直接删除该枚举值、执行策略、诊断/前端标签、数据库约束分支和测试，不保留别名或兼容路径。`FULL_COMPENSATION_SCAN` 的手工恢复入口继续保留。
- `GitlabDailyVerificationScheduler` 在上线观察期只作为可关闭的低频反熵；是否继续每日执行必须由新链路稳定期数据决定。无论保留何种周期，工作区“最新”状态都不得依赖最近一次全量补偿。

## 接口契约

### 内部类型

计划中的名称是目标名称，实施时应直接更新所有调用点，不创建旧名称适配器。

```java
record FactChangeIdentity(
    String sourceInstance,
    FactType factType,
    long rootId,
    Long projectId,
    Long iid) {}

record VersionedFactChangeTarget(
    long mirrorRunId,
    FactChangeIdentity identity,
    long changeVersion) {}

record FactProjectionScope(
    String sourceInstance,
    FactType factType,
    ProjectionScopeType scopeType,
    String scopeKey) {}

enum FactPublicationMode {
    TARGETED,
    FULL
}

record FactPublicationContext(
    String sourceInstance,
    FactType factType,
    FactPublicationMode mode,
    Set<FactProjectionScope> affectedScopes) {}

record WorkspaceRefreshRequest(
    String workspaceKey,
    WorkspaceScopeSelection scopeSelection) {}

enum WorkspaceScopeSelectionType {
    GLOBAL,
    PROJECT,
    ISSUE_SCOPE_GROUP
}

record WorkspaceScopeSelection(
    WorkspaceScopeSelectionType type,
    Set<Long> projectIds,
    Set<Long> issueScopeGroupIds) {}
```

- `rootId` 是 GitLab Issue/MR 的数据库 `id`，不是项目内 `iid`。
- `scopeKey` 必须由统一编码器从稳定 ID 生成并进行边界校验。
- `mode=FULL` 只允许明确全量运行；目标数量不能将 `TARGETED` 自动改成 `FULL`。
- `FactChangeIdentity` 是血缘解析得到的稳定业务身份；只有在版本头事务内分配 `changeVersion` 后，才形成可进入 outbox 的 `VersionedFactChangeTarget`。
- `WorkspaceScopeSelection` 只包含目录认可的 `GLOBAL/PROJECT/ISSUE_SCOPE_GROUP` 稳定 ID 集合；外部 business key 在控制器边界解析，显示名称不得进入同步依赖或完成判定。
- 变化目标与投影范围在进入持久层前必须规范化、去重并验证来源实例。

### 持久化结构

#### `sync_run_authoritative_scopes`

- `id bigint primary key`
- `run_id bigint not null references sync_runs(id)`
- `source_instance varchar not null`
- `child_table varchar not null`
- `relation_key varchar not null`
- `scope_signature varchar not null`
- `lookup_scope_json text not null`，仅保存经目录校验、规范化的复合范围
- `status/lease_owner/lease_expires_at/retry_count/run_after/error_message`
- `task_id bigint null references sync_run_table_tasks(id)`
- 唯一键：`(run_id, child_table, relation_key, scope_signature)`
- 索引：可领取状态、`run_id + status`、`task_id`

#### `sync_run_fact_targets`

- `mirror_run_id bigint not null references sync_runs(id)`
- `source_instance varchar not null`
- `fact_type varchar not null`
- `root_id bigint not null`
- `change_version bigint not null`，来自对应稳定版本头；同一运行内再次发生真实变化时更新为较新值
- `project_id bigint null`
- `iid bigint null`
- `first_task_id/last_task_id bigint null`，只用于诊断
- `publication_status varchar not null`：只允许 `PENDING/QUEUED/PUBLISHED`
- `assigned_fact_run_id bigint null references sync_runs(id)`
- `assigned_fact_build_task_id bigint null references fact_build_tasks(id)`
- `published_version bigint null`
- `published_by_fact_build_task_id bigint null references fact_build_tasks(id)`
- `created_at/updated_at/published_at timestamp`
- 主键：`(mirror_run_id, source_instance, fact_type, root_id)`
- 索引：`mirror_run_id + fact_type`、`source_instance + fact_type + root_id + change_version`，以及事实 worker 的分页领取键

#### `fact_change_heads`

- `source_instance varchar not null`
- `fact_type varchar not null`
- `root_id bigint not null`
- `latest_change_version bigint not null`
- `published_version bigint not null default 0`
- `updated_at timestamp not null`
- 主键：`(source_instance, fact_type, root_id)`
- 配套 `fact_change_version_seq` 为每次真实变化分配全局单调版本；版本可有空洞，不以连续性或 wall-clock 推断完成。
- 该表只承担每个根对象的行锁与版本 fencing，不是第二个 outbox，也不保存待处理事件。ODS 变化事务先按稳定键排序后锁定/创建版本头，再分配版本并登记 `sync_run_fact_targets`；事实发布事务锁定同一版本头，避免跨运行乱序覆盖和批量锁死锁。

#### `fact_projection_generations`

- `source_instance varchar not null`
- `fact_type varchar not null`
- `scope_type varchar not null`
- `scope_key varchar not null`
- `generation bigint not null`
- `updated_at timestamp not null`
- 主键：`(source_instance, fact_type, scope_type, scope_key)`
- generation 只能在事实发布事务中单调递增。

#### `issue_scope_groups.definition_generation`

- 新增 `definition_generation bigint not null default 1`，作为 group 定义和成员集合的稳定版本，不使用 wall-clock timestamp 参与缓存身份。
- `IssueScopeDefinitionService` 修改 group 或 member 时锁定受影响 group 并单调递增；member 迁移同时推进旧、新 group，catalog 级语义/启用变化推进其全部 group。服务事务、迁移脚本和目录自动对账必须共用该入口或执行等价的同事务 generation 更新。

#### `fact_projection_refresh_tasks`

- 关联 `FACT_REFRESH` 运行及具体 `fact_build_task_id`
- 保存 `source_instance/fact_type/scope_type/scope_key/target_generation`
- 使用统一后台任务状态 `QUEUED/RUNNING/RETRY_WAITING/SUCCESS/FAILED`，并保存 lease、owner/version fencing、`retry_count/recovery_count/run_after` 和时间字段
- 唯一键：`(fact_build_task_id, scope_type, scope_key)`
- 任务成功必须代表该范围所有适用统计/记录刷新器均成功；单个刷新器异常不得只写日志后把任务标为成功。

#### `sync_run_publication_fences`

- 保存手动镜像 `run_id/workspace_key/source_instance/fact_type`、规范化 `target_selector_type/target_selector_key`、`required_change_version`、`status` 与时间字段。
- 唯一键：`(run_id, workspace_key, fact_type, target_selector_type, target_selector_key)`。
- fence 在手动运行完成依赖表删除探测且仍持有同源 writer 时创建；`required_change_version` 是该 selector 内当时已提交目标的最大版本，不按 FACT_REFRESH 子运行 ID 推断新鲜度。

#### `sync_run_publication_fence_scopes`

- 保存 `fence_id`、稳定 `scope_type/scope_key`、`required_generation`、`projection_task_id` 与 `status`。
- 主键：`(fence_id, scope_type, scope_key)`；同一 scope 再次推进时以 `greatest(existing, incoming)` 更新 required generation 和负责该最新 generation 的任务，不累计重复等待行。
- fence resolver 从其版本范围内目标的实际发布任务解析触发工作区消费的旧、新 scope；已有发布和稍后发布使用同一解析路径。只有所有目标版本已覆盖、所有 scope generation 已发布且请求使用的规范 sourceVersion 可读，fence 才成功。

#### `sync_runs` 与事实任务状态扩展

- 为 `sync_runs` 增加 `run_after timestamp not null default current_timestamp` 并纳入 dispatcher 索引；现有实体、领取 SQL、超时恢复和状态机同步更新。
- 一个定向 `fact_build_task` 对应一个有界目标 assignment 批次，不再在任务内部继续分页；worker 只处理领取时仍归属自己的当前版本，因较新版本被撤销 assignment 的旧任务幂等完成空批。事实任务和投影任务使用 `QUEUED/RUNNING/RETRY_WAITING/SUCCESS/FAILED`、owner/version fencing、`run_after/retry_count/recovery_count`。

#### 镜像变化发布 outbox

- `sync_run_fact_targets` 本身就是唯一 outbox，不另建第二张变化事件表。
- 同一运行目标发生更高 `change_version` 的 upsert 时，无条件重置 `publication_status=PENDING`，清空 `assigned_fact_run_id/assigned_fact_build_task_id/published_version/published_by_fact_build_task_id/published_at`；该写入与版本头递增、ODS 业务 DML 同事务。只有完全相同业务数据的 no-op 不执行重置。
- ODS 事务递增版本头并插入/合并目标时状态为 `PENDING`。页提交事务提交后，持久协调器可在父运行 `RUNNING/RETRYING/PAUSED` 或任意终态时，在一个事务中创建或复用该父运行唯一的 `FACT_REFRESH` 子运行；子运行按稳定键选择未被既有 `published_version` 覆盖的 PENDING 目标组成有界 assignment 批次并置为 `QUEUED`，已被其他交错运行发布覆盖的目标直接置为 `PUBLISHED` 并记录实际发布版本。
- 事实批次事务锁定版本头后从当前 ODS 构建，将头推进到观察到的 `latest_change_version`，并把所有被该版本覆盖的目标置为 `PUBLISHED`。其他运行已创建的重复事实任务领取后发现版本已覆盖时只完成自身目标和任务，不重复推进 generation。
- 普通执行异常把同一事实任务通过 owner/version CAS 转为 `RETRY_WAITING`，递增 `retry_count` 并设置指数退避的 `run_after`；目标保持 `QUEUED`。同一事务把 `FACT_REFRESH` 运行置为 `RETRYING`、`run_after=min(未完成子任务.run_after)`，清空 run lease/heartbeat，使 dispatcher 到期后重新领取原运行。
- 若暂时没有可领取任务但镜像父运行仍为活动/暂停状态，或仍存在可能被后续页重置的目标，`FACT_REFRESH` 置为 `PAUSED`、释放租约并等待目标协调器唤醒，不能由 `SyncFactRefreshRunExecutor` 计算成 `SUCCESS/PARTIAL_SUCCESS`。只有父镜像运行已经终态、该父运行全部目标已被版本覆盖、事实任务和投影任务全部成功，子运行才允许 `SUCCESS`。
- 子任务达到自动重试上限后明确置 `FAILED`，`FACT_REFRESH` 同步进入终态 `FAILED` 并保留未发布目标；不得形成“活动 run 但无 claimable task”的悬挂态。运维恢复在一个事务内递增 recovery audit、把原 task 置回 `RETRY_WAITING`、把同一 run 恢复为 `RETRYING` 并设置 `run_after`，不创建第二个事实子运行或丢弃目标。投影任务使用完全相同的 run 级传播与恢复规则。
- 已 FAILED 的唯一 FACT_REFRESH 子运行不会因后来新增目标被静默改成成功或清零重试；协调器保留新目标并持续告警，只有上述显式恢复事务可唤醒同一 run/task，页面 fence 因此保持失败而不会误报最新。
- 唯一约束保证一个镜像运行只创建一个事实子运行；父运行失败、取消或超时但已有已提交目标时仍可领取发布。定期恢复扫描必须发现“非 PUBLISHED 目标但无可运行事实任务”的非法状态并修复或告警，不能永久搁置。
- `FACT_REFRESH` 子运行创建、目标归属和事实任务创建必须在同一事务；不能依赖仅存在于 Spring 事件内存中的一次通知。
- 事实 DML、旧/新投影范围、generation、投影任务、目标 `PUBLISHED` 和事实任务 `SUCCESS` 使用同一平台事务与租约 fencing；任何异常全部回滚。投影任务独立重试，达到上限后保持工作区未完成并通过同一任务恢复入口重新排队，刷新器异常不得吞掉。
- dispatcher 只领取 `run_after <= now()` 的 `QUEUED/PAUSED` 运行以及 `FACT_REFRESH + RETRYING` 运行；领取时重新校验 exclusive scope。已清空 lease、等待 `run_after` 的 RETRYING 不是 active blocker，只有 `RUNNING/CANCELLING` 或仍持有效 lease 的 RETRYING 阻塞同 scope，避免多个等待重试彼此锁死。FACT_REFRESH 从 `RUNNING` 进入 `PAUSED/RETRYING/FAILED` 的状态迁移、子任务更新和 run lease 释放必须由统一状态服务原子提交，删除执行器当前“无可领取任务即汇总终态”的分支。

### 状态契约

- `sync_run_table_states.last_delete_reconciled_at` 只在该表一次完整主键范围探测成功后推进。
- 删除探测页成功不单独宣称整表成功；只有最后一页完成且所有前页成功才推进时间。
- `PARTIAL_SUCCESS` 可以发布已提交镜像变化对应的事实，但工作区不得显示“最新”；失败依赖表在下次运行继续重试。
- `FACT_REFRESH` 完成条件包括父镜像运行已终态、该父运行不再可能产生新目标、全部目标版本已覆盖，以及所有事实/投影任务成功；父运行活动期间的暂时 drain 只能进入 `PAUSED`。
- `RETRY_WAITING`、达到上限的 `FAILED` 事实/投影任务和未覆盖的目标都不属于成功；恢复动作必须复用原任务身份并保留 retry/recovery 审计。
- 零变化镜像运行不创建空事实任务；全量运行除外。
- 已提交变化目标的父运行无论最终为成功、部分成功、失败或取消，都不能把目标遗留为永久未发布；页面新鲜度必须反映其派生发布终态。
- 手动镜像 run 的表任务成功不等于页面刷新完成；对应 publication fence 未满足或 scope generation 任务失败时，工作区持续显示处理中/失败，绝不能因为本次 DML 为零而跳过已有未发布版本。

### 外部接口

- 不新增或修改对外 REST 路径、页面 DTO、外部数据集字段或导出格式。
- 手动刷新按钮语义增强为同时覆盖物理删除，已有请求与响应兼容；内部移除表清单参数不属于对外兼容对象。
- 同步诊断接口可复用现有 `task_stage=RECONCILE` 展示删除探测进度；如必须增加字段，只增加明确的结构化阶段/计数，不暴露内部 SQL。

## 分阶段实施

### 阶段 0：锁定依赖矩阵与基准

1. 从 23 张推荐表、`GitlabFactSourceSqlProvider`、事实构建服务、实时工作区和所有快照刷新器生成代码事实矩阵。
2. 为每张表确认主键、主键类型、源索引、镜像 active 索引、事实消费者、根目标解析和投影范围。
3. 对内网执行只读 `EXPLAIN (ANALYZE, BUFFERS)` 样本，记录 `notes`、`label_links`、`resource_label_events` 和复合关系表的主键存在查询基线。
4. 新增 ADR，记录单向镜像键验证、持久变化目标、分阶段运行和范围 generation 决策。
5. 验收：矩阵无“未知/默认忽略”项；所有源查询只读；批大小仍标记为待基准参数。

### 阶段 1：收口依赖和血缘目录

1. 实现三个职责清晰且互不重复的目录：来源血缘、事实依赖、工作区依赖。
2. 更新白名单能力判断、权威任务规划和手动刷新调用者。
3. 删除旧目录类与全部页面 `REALTIME_REFRESH_TABLES`。
4. 先保持现有运行行为不变，使用契约测试证明依赖范围与改造前一致或按调查补齐。
5. 验收：搜索旧常量/旧类无引用；所有工作区都能解析非空、确定且去重的依赖。

### 阶段 2：持久变化目标并替换旧影响推导

1. 增加 Flyway 结构和实体/仓储，包括稳定版本头、带 change version 的运行目标和领取索引。
2. 让普通 upsert、权威替换和现有全量删除对账只返回真实业务差异，并在同一事务内锁定版本头、推进版本和登记目标；同运行目标再次变化必须重置 PENDING/assignment/publication，镜像元数据更新与相同集合重读不得登记目标。
3. 为维表反向影响增加批量解析器和源实例隔离测试。
4. 事实提交改为读取目标表，删除 `FactRefreshImpactScopeService` 的 200 上限和任务/ODS 事后推导。
5. 验收：现有增量和权威删除场景事实结果不变；任务数超过 200 仍定向；完全相同行和相同权威集合零目标、不入队。

### 阶段 3：批量事实与范围化投影发布

1. 将事实目标改为根 ID 关系/数组连接；协调器按页创建有界 batch task，每项任务一次事务处理完整批次且没有内部 cursor。
2. 允许已提交目标在镜像父运行活动/暂停时持续派发；在带版本头行锁和租约 fencing 的事实事务内完成事实替换、旧/新投影范围、generation、投影任务、目标发布与事实任务成功。
3. 为 `sync_runs` 增加 `run_after`，重构 FACT_REFRESH executor/dispatcher 为 PAUSED 等父运行、RETRYING 等子任务、FAILED 可恢复的闭合状态机。
4. 按 `issue_scope_catalogs -> issue_scope_members -> issue_scope_groups.id` 实现强类型稳定范围，更新所有统计/记录刷新器，统一全量与定向入口并生成多范围规范 sourceVersion。
5. 增加手动 publication fence 及 scope generation 解析，将工作区进度纳入实际发布版本，移除全局事实任务 source version。
6. 验收：单 Issue 更新不执行全部 Issue 刷新器；自动暂停后手动 no-op 仍等待既有目标；旧、新范围均更新；显示名/成员变化正确失效；未受影响快照版本和内容不变。

### 阶段 4：批量权威范围

1. 增加权威范围队列、全状态批量领取和唯一 stage coordinator。
2. 将父行、event、note 与 Hook 派生改为只登记去重范围。
3. 实现批量完整集合查询、按范围分组和空组替换。
4. 删除每范围一个持久任务的旧规划路径。
5. 验收：1、24、195、500、数千范围的 scope 工作集按去重根数增长，但 `sync_run_table_tasks` 不再按根增长；批量领取次数符合批大小，结果与逐范围语义一致。

### 阶段 5：启用增量/手动删除探测

1. 将运行 worker 改为通过运行行锁执行“所有生产者终态 + 权威范围队列静止”的原子屏障，再统一规划删除探测阶段。
2. 重构全量运行使用同一阶段屏障并删除末页隐式对账分支。
3. 将源/镜像主键比较和 tombstone SQL 改为参数化批量实现；`RECONCILE` 在同一表任务上提交页检查点并按时间片重新排队，删除逐页创建 continuation task 的路径。
4. 扩展自动 `INCREMENTAL_SYNC` 在删除页边界暂停、释放同源 writer 和恢复的 CAS 状态机；手动完成删除探测后在释放 writer 前捕获 publication fence，增加 `last_delete_reconciled_at`、工作区依赖新鲜度和可恢复游标测试。
5. 验收：无删除的自动运行主删除任务数接近本次选中表数，不能随键页数线性增长；自动 23 表、页面依赖表、数据库浏览器单表三种范围准确；System Hook 不触发全局扫描。

### 阶段 6：故障、并发和可观测性加固

1. 增加阶段耗时、每表扫描键数、缺失键数、影响目标数、事实批次和投影范围数指标。
2. 验证取消、暂停、租约丢失、源超时、平台事务回滚、进程重启与重复投递。
3. 确保源查询失败不会产生 tombstone；平台提交失败不会推进 cursor 或新鲜度。
4. 验证自动运行与手动刷新复用/排队语义，避免同源重复全表键扫描。
5. 在“候选确认后、tombstone 事务后、事实替换后、部分投影后”四个故障点注入进程中断，验证恢复后无漏目标、无重复统计且最终唯一。
6. 验收：所有失败可由 `runId/runDbId/taskId/factTaskId/projectionTaskId` 串联，重试后无重复事实或 generation 异常增长；父运行失败/取消但已有目标时派生发布仍最终完成。

### 阶段 7：容量基准、部署和旧路径清理

1. 在固定 270 万行副本或内网维护窗口按本文基准矩阵运行 2/4/6 worker 和候选批大小。
2. 选择满足同步周期、连接预算与 GitLab 负载约束的参数，写入架构/ADR 和部署配置。
3. 搜索并删除旧全局快照版本、全扇出上下文、每对象权威任务、200 目标回退、全量末页专用对账和标签专用新鲜度服务。
4. 删除已确认无生产提交入口的 `COMPENSATION_SCAN` 全链；确认 `GitlabDailyVerificationScheduler` 只保留经运维决定的低频反熵职责，配置/UI 文案不再暗示它承担实时性。
5. 按部署步骤完成一次历史清理和真实删除验收。
6. 将长期事实压缩写入 `docs/architecture.md`、当前状态写入 `docs/progress.md`；工作单元完成后删除本计划和已完成的前置审计计划。

## 测试与验证矩阵

### 单元与契约测试

| 范围 | 必测行为 |
| --- | --- |
| 依赖目录 | 23 表全部显式分类；所有工作区有事实依赖；多态范围类型隔离；无重复字符串清单 |
| 删除探测 SQL | 单/复合主键、真实类型、参数化 VALUES、空批、非法返回子集、镜像 active 索引谓词 |
| 分页 | 0、1、batch-1、batch、batch+1、多页；cursor 恢复；页提交失败不推进；最后一页才推进新鲜度 |
| 镜像变化 | 插入、业务列变化、tombstone 恢复和真实删除登记目标；完全相同行、较旧 lookback 行和仅镜像元数据更新不登记目标 |
| tombstone | 删除前登记目标；重复删除幂等；其他 source instance 不变；源异常不删除 |
| 权威批次 | 新增、替换、空组清空、完全相同集合 no-op、多个父范围、同数字不同类型、范围去重、owner fencing、RETRY_WAITING 阻断屏障、FAILED 失败、禁止递归派生 |
| 事实目标 | 1、200、201、500、数千目标；根删除；关系最后一条删除；维表一对多影响；同一运行同根跨表/跨页再次变化重置 PENDING；无全量回退 |
| 事实替换 | 目标旧事实先删后写；空来源只删；Issue 从属成员守恒；其他事实类型和来源隔离 |
| 投影 | 旧范围、新范围、仅旧范围、仅新范围、同范围；project/scope-group/global；显示名改名、成员迁移、多范围 sourceVersion 稳定排序；旧 generation 任务乱序完成时 superseded；跨工作区影响不丢失；失败不服务旧版本 |
| 手动刷新 | 工作区映射、单表范围、publication fence、未配置依赖、部分失败、并发点击、与自动运行复用；扫描发现其他项目变化时全部投影仍发布 |
| 运行状态 | SCAN producer 与 scope 全状态原子屏障、延迟重试/late insert/重启判空、RECONCILE 规划幂等、自动增量页边界让行 CAS、FACT_REFRESH PAUSED/RETRYING/run_after/恢复、取消/租约丢失、零变化不建事实 |
| 任务规模 | 270 万键主删除任务数接近表数；同一任务页检查点与时间片让行；不得每页增长任务行 |
| 发布 outbox | 父运行活动/暂停/任意终态均可发布；同一运行目标再变化清空旧发布信息；普通失败退避、run 级重领、重试上限和原任务恢复；A/B 乱序 fencing；事实/范围/generation/任务/目标原子提交；重复通知只生成一个事实子运行 |

### PostgreSQL 集成测试

- 使用独立 PostgreSQL 测试边界同时建立 GitLab source fixture 与平台镜像，不能依赖开发机共享 `localhost:15433`。
- 覆盖真实 typed `VALUES`、复合主键、部分索引命中、事务回滚、并发可见性和 keyset cursor。
- 场景至少包含：删除 Issue `label_links`、删除最后一条 note、取消唯一 assignee、删除 MR reviewer/metric、删除根 Issue/MR、删除 label/user/project/milestone 维表。
- 每个场景同时断言 ODS tombstone、`sync_run_fact_targets`、事实行、投影 generation、统计/记录快照和无关快照。
- 加入两个 source instance 使用相同 root id 的隔离场景。
- 加入镜像运行 A/B 与事实任务交错完成场景，断言最终事实来自当前 ODS，旧任务不能覆盖更新结果。
- 加入事实事务五个提交点逐一回滚及正常异常达到重试上限场景，断言目标不丢失、generation 不重复推进、恢复原任务后最终收敛。
- 加入页面 A 手动刷新扫描整张依赖表却发现页面 B/其他项目删除的场景，断言 A 的扫描范围不扩大，但 B 的受影响投影仍被发布。
- 加入“自动 RECONCILE 已提交删除目标 -> 页边界暂停让行 -> 手动同表刷新得到业务 no-op”场景，断言手动 publication fence 等待旧目标的事实与必需 generation 后才显示最新。
- 加入同一 mirror run 的同一根先发布、后被另一表/另一页再次改变场景，断言 target 版本提高、状态重置并第二次发布。
- 加入 scope `RETRY_WAITING` 延迟到期、`FAILED`、producer 终态前 late insert 与 coordinator 重启场景，断言不会提前创建 RECONCILE。
- 加入 scope 显示名修改、`source_value` 从旧 group 迁到新 group、根删除只有旧 group、多 group 请求顺序不同场景，断言身份和 sourceVersion 稳定且旧新缓存均正确失效。

### 端到端业务回归

- 隔离 Issue 从“一级缺陷”移除标签：一级缺陷数量及所有相关表头减一，议题仍落入正确的新分类；下钻和导出一致。
- 删除/恢复评论，验证响应模板、计划时间、延期状态和客户问题记录页。
- 删除/恢复 MR 指派、审核或指标，验证代码走查事实和页面。
- 手动刷新同一页面与等待自动增量两条路径分别完成，不执行全量补偿。
- 从一个工作区触发手动刷新，同时预置同依赖表中另一项目的物理删除，验证两个项目的受影响统计都收敛且无关投影不变。
- 对未涉及的系统测试阶段、客户问题、代码走查、质量看板、数据库浏览器、兼容模式和外部数据集做代表性不变回归。

### 固定负载容量基准

数据场景固定为：

1. 23 表无删除；
2. 删除 1 条关系；
3. 删除 200/201/500 条、分别集中于一个根和分散于多个根；
4. 删除 5000/10000 条关系或一个影响数千根的维表；
5. `notes` 100 万、200 万 active 键；
6. 自动 `RECONCILE` 持续提交目标并并行进行定向事实/投影，同时插入一个高优先级手动工作区刷新；
7. 合成环境每组预热 1 次并至少采样 10 次；内网真实数据每组至少 3 次。分别记录中位数和 P95，不挑最好结果。

组合参数：2/4/6 worker；删除页 2000/5000/10000/20000；权威范围批 100/200/500；DIRECT 与 Docker source mode 分开记录。

记录指标：

- 自动运行总耗时、增量阶段、删除探测阶段、事实阶段、投影阶段；
- 每表 keys/s、每批 GitLab 查询 P50/P95、镜像读取与 tombstone P50/P95；
- GitLab CPU/IO/缓冲命中、活动连接、平台 DIRECT 池 pending、平台数据库 CPU/IO；
- JVM 峰值堆、GC、任务表增长、失败/重试数；
- 页面从触发到可读正确事实与快照的端到端延迟；
- 同时提交手动刷新时的等待、复用和完成时间。

容量通过条件：

- 连续 30 个自动周期无运行积压或重叠，P95 总耗时小于实际同步周期；
- JVM 峰值与全表行数不呈线性增长，连接数不超过运行解析预算；
- 大表查询计划使用主键/active 索引，无无界排序、OFFSET 或 active 全表顺序扫描；
- 270 万键无差异运行的主删除任务数接近本次选中表数，不随页数创建数百或数千持久任务；
- 与无删除探测基线相比，GitLab 在线关键查询延迟没有超出现场共同确认的回归预算；
- 事实与投影耗时随目标/范围增长，不随 270 万镜像总量增长；
- 最终 worker、批大小和硬回归上限由重复分布写入 ADR，未达标不得通过增加线程掩盖。

发布目标在真实基准中不得静默放宽：现有 `updated_at` 增量阶段 P95 回归不超过 `max(10%, 1 秒)`；2 worker、23 表、270 万键无删除的删除探测目标 P95 不超过 30 秒、硬上限 60 秒；页面依赖/显式单表手动刷新目标 P95 不超过 30 秒、硬上限 60 秒；无删除自动链路和 1 至 200 个删除到投影 READY 的目标 P95 不超过 60 秒；201 至 500 个删除目标 P95 不超过 90 秒、硬上限 120 秒；5000 个删除硬上限 5 分钟且不得全量回退。若现场证明目标不现实，必须回到架构和用户决策重新评估，不能只修改文档数字。

附加内存硬门禁候选为相对空闲基线不超过 256 MiB，且不得出现全量键集合常驻或长时间 Full GC；最终值随固定硬件基准一并写入 ADR。

## 部署与验收

### 部署前

1. 完成全部定向测试、Java 21 编译、Checkstyle、SpotBugs、Flyway 门禁、文本/产物/差异检查。
2. 停止调度并等待所有镜像、事实和投影任务终态；禁止新旧代码同时消费同一任务队列。
3. 按离线部署标准备份平台库、Compose 和 `.env`，记录当前 ODS/事实/快照代表行数。
4. 迁移保持新增结构为主，不复用或改写已发布 Flyway；旧应用镜像可忽略新增表，以保留应用回滚能力，但新代码不包含旧运行时回退。

### 首次上线

1. 部署新后端并确认 Flyway、健康、运行预算和任务领取正常。
2. 按前置审计只执行一次 `FULL_COMPENSATION_SCAN` 及全量事实/投影发布，清理部署前历史漂移并建立基线。
3. 确认 23 张表 `last_delete_reconciled_at`、事实全量 epoch 和工作区依赖状态有效。
4. 恢复自动调度，连续观察至少 30 个增量周期；无删除周期不得产生事实/投影任务积压。

### 真实删除验收

1. 创建隔离 Issue/MR 和标签/评论/关系，不使用现有业务记录。
2. 在 GitLab 正常界面删除关系或根实体，等待一次普通自动增量；验证 ODS、目标、事实、generation、快照和页面。
3. 再创建隔离对象并点击对应页面“刷新最新数据”，验证只规划页面依赖表并得到相同结果。
4. 全程确认没有 `FULL_COMPENSATION_SCAN`、全量事实任务或无关快照刷新。
5. 验证无关页面、其他来源实例、兼容模式和外部 API 数据未变化。

### 回滚

- 应用回滚使用部署标准恢复上一后端/前端镜像，不回滚已正确写入的 ODS tombstone 或事实数据。
- 新增表和列保持向后可忽略，旧应用不会读取；不得为回滚在新代码中保留双写或旧算法。
- 若发生误删风险，立即停止调度和事实 worker，保留运行/任务/目标证据，先恢复应用并从备份对比；禁止直接清空 ODS 或运行无界修复 SQL。

## 决策记录

### 已选方案

- 选择“现有增量 + 镜像主键存在性验证”，因为删除集合只需要计算 mirror-only，没必要传输 GitLab 全部主键。
- 选择复用现有 `RECONCILE` 任务阶段，而不是新增运行类型或旁路服务，因为租约、游标、让行和诊断已经成熟。
- 选择运行级阶段屏障，确保所有新增/修改与动态权威范围完成后再声明删除探测新鲜度。
- 选择事务内持久变化目标，避免事实 worker 依赖随后可能变化的 ODS `mirror_task_id`。
- 选择稳定根版本头与运行目标分离：版本头只做锁和乱序 fencing，运行目标作为唯一 outbox，避免 A/B 事实任务互相覆盖而不制造第二条事件链。
- 选择根 ID 批量事实替换和稳定投影 generation，删除任意目标阈值回退和全局快照任务版本。
- 选择“工作区限制扫描与等待、变化决定发布范围”，保证手动刷新不会越权扫描无关表，也不会把顺带发现的其他项目变化留在陈旧投影中。
- 选择低频保留全量补偿，只承担恢复和反熵职责，不承担实时性。

### 明确否决

- 否决每轮全量补偿：已有实测为分钟级并强制全量派生发布，会阻塞增量实时性。
- 否决两边全量 ID 装入集合：网络和堆内存随总行数增长，且 source-only 集合对删除判断没有价值。
- 否决 23 表各开一个线程：会绕过统一连接预算并形成 GitLab 查询尖峰。
- 否决只保留 `AUTHORITATIVE`：没有父对象/event 信号的静默删除仍不可见。
- 否决只使用 Hook/事件：当前 GitLab 版本缺少 Issue System Hook，事件表本身也可能无索引或丢失。
- 否决超过目标数回退时间增量或全量：删除不保证根更新时间变化，既不严格正确也不可预测。
- 否决页面过滤旧数据：只掩盖统计，ODS、事实、导出和其他消费者仍错误。
- 否决定向事实后全局快照扇出：会把单对象删除重新放大到全部页面，违背实时性目标。
- 否决长期保留新旧目录、旧表清单参数或旧事实影响服务：内部接口处于开发期，应直接迁移调用者并删除旧路径。

## 风险与假设

| 风险/假设 | 处理方式 |
| --- | --- |
| 270 万行键扫描可能超过同步周期 | 上线前固定负载测试；只允许调整统一预算和批大小，不增加旁路线程 |
| GitLab 在一轮探测过程中继续变化 | 不做跨库长事务；每个键只在成功来源查询后删除，探测后的新删除由下一周期收敛，最大陈旧时间为同步周期 |
| 来源查询超时被误当空集合 | 查询异常整页失败；只有成功结果集合才能计算差集 |
| 维表删除影响根对象很多 | 反向解析后写去重目标表并分页事实构建，无数量阈值回退 |
| 多态 ID 相同导致误删 | 血缘目录强制类型限定，复合范围和集成测试覆盖 |
| 快照只刷新新范围导致旧计数残留 | 事实事务同时捕获 before/after，发布二者并集 |
| 投影预热失败时页面读到旧缓存 | 事实事务先推进 generation；旧 sourceVersion 不再命中，预热失败只影响首次查询延迟 |
| 新表加入推荐目录但没有影响规则 | 契约测试要求显式 `NO_DERIVED_CONSUMER` 或完整血缘，禁止默认忽略 |
| 自定义白名单缺少事实依赖 | 手动刷新返回明确不支持状态，不扫描或构建不完整事实 |
| 自动删除扫描阻塞紧急手动刷新 | 只在已提交页边界通过 CAS 让行并释放同源 writer；固定负载并发场景验证最大等待 |
| 自动已提交目标尚未发布时手动刷新得到 no-op | 已提交 PENDING 立即可派发；手动在 writer 内捕获 change-version fence，并等待实际发布任务与必需 generation |
| 事实或投影持续失败导致目标搁置 | `RETRY_WAITING` 退避、上限告警、原任务恢复和非法孤儿扫描共同保证可见且可恢复，不把失败标成最新 |
| scope 显示名或成员关系变化导致缓存身份漂移 | group ID/business key 是稳定身份；definition generation 与 fact generation 分离并同时进入规范 sourceVersion |
| 目标、版本头和任务元数据快速增长 | 权威范围批量化、删除探测合理页大小、已发布目标/任务保留策略与版本头生命周期单独制定；不通过删除审计记录换性能 |
| 首次部署历史漂移不会自动全部消失 | 上线只执行一次全量补偿建立干净基线，之后用普通增量/手动场景证明实时链路 |
| 定向改造影响非相关表格 | 以工作区/事实/投影依赖契约和无关快照不变测试作为合并门禁 |

## 完成定义

只有同时满足以下条件，本工作单元才可标记完成：

- 生产代码只有一条增量/删除/事实/投影链路，旧符号和旧回退搜索结果为零。
- 自动、页面手动和单表刷新范围符合矩阵，System Hook 和全量运行边界清晰。
- 物理删除的 ODS、事实、统计、记录、下钻和导出端到端正确，无需日常全量补偿。
- 1 至数千目标均批量定向处理，未发生隐式全量事实或全局快照扇出。
- 23 表无差异、lookback 相同行和相同权威集合不创建派生任务；失败/恢复/A-B 乱序/多来源隔离测试通过。
- 手动刷新顺带发现的跨项目变化全部发布；自动让行后手动 no-op 由 publication fence 等到正确版本；事实/投影失败可由同一 run/task 恢复且不存在未发布孤儿目标。
- scope group 身份、目录 definition generation 和多范围 sourceVersion 契约通过改名、成员迁移、根删除与请求顺序回归。
- 270 万行固定负载基准、2/4/6 worker 选择和查询计划证据完成并写入 ADR/架构。
- 内网一次历史基线补偿与自动/手动真实删除验收完成，无关功能回归通过。
- `docs/architecture.md`、`docs/progress.md`、部署 runbook 与代码一致；完成后删除本活动计划及已完成前置计划。
