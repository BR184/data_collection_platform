# GitLab issues 镜像滞后回归最终调查与修复计划

## 进度与中间物

- 状态：2026-08-06 代码修复、完整后端/前端门禁及本地 GitLab 10 分钟自动链路复验已完成。用户授权的全新空数据隔离包 `qaflex-full-20260806T111653Z-8269d2b61253.tar.gz` 已生成并完成包外审计与隔离启动验证；目标内网入口为 `172.22.10.115:30001`，后端 `30002`、PostgreSQL `15434`、LDAP `http://172.22.10.116:80`。约 280 万全部同步表总量的内网删除反熵基准与静默删除现场验收仍未完成，因此新栈继续保持删除反熵关闭，不把本地部署结果外推为容量结论。
- 已确认：正常基线 `qaflex-update-20260729T093338Z-72635b164fee.tar.gz` 对应 `b9249f36`；异常包 `qaflex-full-20260803T122027Z-ad35f6c0e8c3.tar.gz` 对应 `07431616428d4861723867707237d6c085360a9b`。
- 已确认：异常版本相对正常基线的同步行为回归由提交 `2fd5c34e4e7a4c1324332338163cfe1ca7ff69f6` 中的“日常物理删除对账”修复引入。该提交把全表 `RECONCILE` 接入每次普通 `INCREMENTAL_SYNC`/`TABLE_REFRESH`，同时引入缺少时间列时静默成功的 `DELETE_ONLY` 分支；`553f7a49` 只修复 nullable 镜像变化，不是本次引入点。
- 已确认：用户已在 20001 数据镜像设置中把 `compensation_interval_minutes` 保存为 10；不能把默认 360 分钟作为现场原因，也不能把间隔改成 30 或 60 分钟视为修复。
- 已确认：现场约 280 万行是全部同步表总量，不是 `issues` 单表行数。当前本地数据规模不具备容量代表性；不再使用本地小规模对账耗时做 280 万总量外推，删除反熵的 15/30/60 分钟 SLA 只以内网固定负载实测为准。
- 已确认：现场手工单表刷新最多约 30 秒，最慢全量约 20 分钟，异常版本已经连续运行约三天。即使每轮普通增量也耗时 20 分钟，三天内仍应开始多轮新的固定上界扫描，因此“单轮全表对账超过 10 分钟形成无限积压”不能单独解释连续断档，只能解释延迟扩大和触发碰撞。
- 已确认：`issues.updated_at` 在异常版本中仍会被识别为 `INCREMENTAL`；`DELETE_ONLY` 直接影响无可靠时间列的表及其绿色 0 行语义，不会直接让 `issues` 跳过时间扫描。
- 已确认：GitLab CE 16.11 的 `resource_label_events` 使用 `issue_id/merge_request_id`，`action=1/2` 分别表示增加/移除；当前目录已经具备事件到 `label_links(target_type,target_id)` 权威范围的正确映射，但普通增量被全表对账拖住，无法发挥快速信号作用。
- 已确认：现场“刷新最新数据”日志全部绿色不能证明数据已追平。当前运行成功只检查任务是否无失败/待处理；0 行任务、`DELETE_ONLY` 和仅验证镜像已有键的 `RECONCILE` 都可记为 `SUCCESS`。同类活动运行的后续触发复用原 `runId`，不会产生新的日志行。
- 已确认：最近同步日志按 `runType` 把 `INCREMENTAL_SYNC` 显示为“刷新最新数据”、把 `SYSTEM_HOOK` 单独显示为“System Hook 唤醒”，因此现场所述绿色“刷新最新数据”应按普通增量运行核查。绿色仍只来自运行 `SUCCESS`，页面没有来源上界、必需表覆盖和 checkpoint 追平判定。
- 已确认：`SyncRunWorkerService.updateSyncTimestamps` 会让除 `FULL_SYNC` 外任意成功或部分成功镜像运行调用 `updateSyncTime(..., false)`；因此 `TABLE_REFRESH`、`SYSTEM_HOOK`、`FULL_COMPENSATION_SCAN`、`PARTIAL_SUCCESS` 或 0 任务成功都可推进 `last_incremental_sync_at`，调度器又只读取该字段判断 10 分钟是否到期。频繁的非完整运行可以无限延后真正的自动增量。
- 已确认：上述全局时钟宽泛更新并非 `2fd5c34e` 首次引入：基础行为来自 `22468eb1`，允许 `PARTIAL_SUCCESS` 推进来自 `a718d845`；同类前台运行复用来自正常基线 `b9249f36`。`2fd5c34e` 新增的全表阶段扩大运行时长和成功类型，使这些潜伏缺陷从可容忍窗口变成版本级可见回归。
- 已确认：真实烟测还暴露了独立的时间值回归：`5e2eb2e6` 将 PostgreSQL `timestamp without time zone` 的 JDBC `Timestamp` 从 `toLocalDateTime()` 改为 `toInstant()` 后按 UTC 转换；在 `Asia/Shanghai` JVM 中，来源 `2026-08-06 03:20:22.840104` 被固定上界记录为 `2026-08-05 19:20:22.840104`，恰好少 8 小时。
- 最终根因：不是队列中存在大量未合并请求，而是“扩大的日常运行 + 只返回旧 `runId` 的无尾部补跑复用 + 可被非完整运行推进的全局调度时钟 + 不校验数据覆盖的绿色成功”共同破坏了 10 分钟增量契约。即使现场每张表只有 30 秒、全量只有 20 分钟，这条控制链仍能让新固定上界扫描长期不启动或让未覆盖 `issues` 的运行持续显示成功。
- 方案结论：采用“快速增量 + 标签事件定向权威刷新 + 独立低优先级删除反熵”三层模型。保留现有 tombstone、事实 outbox、定向事实替换和投影发布，不重写已验证正确的数据一致性链路。
- 实施状态：同步控制面、DIRECT/DOCKER 统一时间语义和可空 `MirrorRowChange` 快照均已完成本地实现。DIRECT 每个结果集缓存列标签、JDBC type 和 typeName；无时区值保留来源墙上时间，有时区值归一 UTC；变化快照允许数据库 `NULL`、拒绝空列名并与原 Map 解耦。10 分钟真实自动新增/删除链路已通过；独立删除反熵默认保持关闭，待 280 万总量基准后启用。
- 最终审查：发现并修复了移除 `DELETE_ONLY` 后的关系刷新选择缺口。旧范围规划只从 `SCAN` 任务反推已选择表，导致 `issue_assignees` 等 `RECONCILE_ONLY` 子表不再进入权威范围；现由 `resolvedSourceTables` 运行快照与实际扫描任务合并判定，继续严格遵守自定义白名单。
- 测试状态：完整后端 1118 项零失败、零错误、1 项环境条件跳过，目标删除发布 PostgreSQL 集成测试 2/2 通过；Checkstyle 0 违规、SpotBugs 0 问题，可执行 JAR 构建成功。完整前端 114 个文件、409 项全部通过，ESLint、TypeScript 和生产构建通过。124 条 Flyway 迁移不可变性、破坏性审查、事实字段、API 漂移和测试卫生门禁通过。时间/快照修复另有 46 项定向测试和 PostgreSQL 16 + JDBC 42.7.13 时间类型 4 项集成测试证据。
- 迁移状态：`V20260805_02` 保持已执行内容及 checksum 不变，调度索引以前向迁移 `V20260805_04` 新增；本地真实 PostgreSQL 成功校验 123 条迁移并由 `20260805.03` 升级到 `20260805.04`，后端健康状态为 `UP`。
- 页面状态：18181 管理员真实页面已验证“执行结果 / 数据追平 / 删除对账”分列；历史普通增量可同时显示“已完成”和“数据未追平”，桌面布局、移动端折叠侧栏后的宽表横向滚动及控制台零错误通过。页面中的本地历史配置 360 分钟不作为 20001 现场 10 分钟配置证据。
- 文档门禁：`git diff --check`、文本空白/换行、跟踪产物、Flyway checksum 和破坏性迁移检查通过。全局前端 API 边界检查仍只报并行 BI 文件 `frontend/src/features/bi-dashboard/data/bi-dashboard-api.ts` 的 8 处 `/api` 直连；运行产物位置检查仍只报仓库根目录既有 8 个 `.tmp-*.log`，本工作单元不修改这些并行/用户文件。
- 打包状态：发布包大小 `306,658,041` bytes，SHA-256 `71d339998867cea32d3f47c9ad4a53613bda420e232f761381980dd166f34d7b`，目标 Flyway `20260806.02`，三张镜像均为 `linux/amd64`。包内 7 项摘要、包外摘要、归档清单、30 项打包器测试、前端 17 项发布测试/类型检查/生产构建、后端 clean package 和镜像内产物摘要全部通过。
- 隔离部署状态：Compose project `qaflex-20260806t111653z-8269d2b61253` 自动生成独立三容器、默认网络、PostgreSQL 卷和后端日志卷，未写固定 `container_name`。本机因 `15434` 已由 GitLab 代理占用，仅对本地启动进程覆盖数据库为 `35432`、LDAP 为 `host.docker.internal:28081`，包内内网参数未变；三服务 healthy，后端/前端 HTTP 200，Flyway 为 `20260806.02`，浏览器登录首屏无控制台 warning/error，原 `qaflex-*` 三容器 ID 未变。
- 当前进行点：全新包已完成并保持 `GITLAB_DELETE_RECONCILIATION_ENABLED=false`。下一步在内网 `172.22.10.115` 以包内原始参数部署到 `30001/30002/15434`，随后按 2/4/6 worker 执行约 280 万总量删除反熵基准，并验证无事件物理删除和 sweep 期间前台增量让行。

## 恢复线索

- 当前阶段：实现、完整自动化门禁、真实 10 分钟事件链和全新隔离发布包完成，等待内网 30001 部署、280 万删除反熵基准和静默删除现场验收。
- 恢复后首条命令：读取 `D:/projects/data_collection_platform_deploy/qaflex-full-20260806T111653Z-8269d2b61253/README-INTRANET-DEPLOY.md`，在内网部署前再次确认 `30001/30002/15434` 未占用。不得修改现有 `18080/18181` 和 `20001/20002` 实例，不得在容量验收前开启删除反熵。
- 前置计划：`docs/plans/audit-gitlab-physical-delete-reconciliation.md`、`docs/plans/implement-incremental-delete-detection-targeted-refresh.md`。
- 回归提交：`2fd5c34e4e7a4c1324332338163cfe1ca7ff69f6`。

## 目标与边界

### 用户需求

同时满足以下两个结果：

1. 10 分钟自动同步配置下，GitLab 新增和更新的 Issue 不再被 280 万总量的全表删除探测阻塞。
2. Issue 标签关系被物理删除后，平台不依赖人工全量补偿即可及时清除 ODS 旧关系，并刷新对应 Issue 事实、统计、下钻、导出和记录页。

### 可验证成功标准

1. 普通 `INCREMENTAL_SYNC` 完成条件不包含任何全表 active 主键存在性扫描；`issues.updated_at` 水位可独立推进。
2. `resource_label_events` 新增移除事件后，下一轮普通增量按事件定位 Issue/MR，权威读取其当前完整 `label_links`；来源空集合能 tombstone 最后一条旧关系。
3. 未产生事件的物理删除由独立 `DELETE_RECONCILIATION` 自动收敛；不要求运维人员执行 `FULL_COMPENSATION_SCAN`。
4. 前台增量和手工刷新提交时不会复用或等待整个后台删除运行；最多等待一个已经开始的删除页查询和事务提交。
5. 业务根表缺少声明的增量列、事件表缺少单调主键或来源 schema 与目录不一致时，运行明确失败并告警；禁止 `SUCCESS + rows_scanned=0` 掩盖错误。
6. 新增/更新时效和删除反熵时效使用独立状态、指标和告警，任一链路失败不会伪造另一链路成功。
7. 页面绿色只在执行成功且声明的快速增量新鲜度检查通过时表示“数据已追平”；单纯任务无报错只能显示“执行成功”，不能替代新鲜度结论。

### 明确禁止

- 不通过把自动间隔改成 30 或 60 分钟规避；这只减少触发次数并进一步降低新数据时效，不能消除单轮全表扫描。
- 不删除 tombstone、事实 outbox、根版本 fencing、定向事实替换、投影 generation 或 publication fence。
- 不为“一级缺陷”标签、某个项目、某个页面或单个 Issue 增加专用 SQL/定时器。
- 不允许普通增量在无时间列时降级为“什么都不读但记成功”。
- 不并发启动第二个同源镜像 writer，不把 GitLab 和 ODS 全部主键一次性加载到 JVM。
- 不把一次上线清理所需的全量补偿误写成未来日常正确性的前置条件。

## 约束与背景

- 自动调度器每 60 秒检查一次到期配置；用户设置的 10 分钟表示成功增量之间的目标间隔，不是实时事件推送承诺。
- GitLab 物理删除不会留下可由 `updated_at` 读取的源行；时间增量本身无法证明一个已镜像主键已不存在。
- `resource_label_events` 是追加型变化信号。事件只用于定位需要核对的父对象，删除结论必须来自 `label_links` 当前完整集合，不能仅依据 `action=2` 直接删除某条 ODS 行。
- 同一来源保持单一 writer；运行使用唯一 owner、租约心跳、条件终态和持久分页 cursor，低优先级任务只在已提交页边界释放互斥范围并让行。
- 当前工作树含用户未提交的 BI 改动；实施必须保护这些改动，并单独验证同步领域差异。

## 证据与根因

### 2026-08-06 真实自动增量烟测

- [失败] 使用隔离可执行实例连接本地 GitLab 导入库，创建 `cloudcad/cc-product`（项目 ID `325`）Issue `42418`（IID `2548`），来源 `updated_at=2026-08-06 03:20:22.840104`；平台配置已持久化为 `auto_sync_enabled=true`、`compensation_interval_minutes=10`、`compensation_schedule_mode=INTERVAL`，未执行手工刷新。
- [失败] 自动运行 `499` 在 `11:36:24` 开始、`11:36:57` 结束，`INCREMENTAL_SYNC/SCHEDULE/PARTIAL_SUCCESS`，规划/完成 `20/18`、扫描/应用 `13/0`；Issue 任务显示水位 `2026-07-06 07:05:00.145174`、固定上界 `2026-08-05 19:20:22.840104`，而来源最大值为 `2026-08-06 03:20:22.840104`，Issue `42418` 不在 ODS。
- [失败] 下一次自动运行 `501` 于 `11:46:52` 开始并于 `11:46:59` 结束，仍为 `PARTIAL_SUCCESS`，规划/完成 `20/18`、扫描/应用 `0/0`；其 Issue 上界仍为 `2026-08-05 19:20:22.840104`，未补入 `42418`。这证明 10 分钟调度本身有触发，失败不是“后续调度没有运行”。
- [失败] `events`、`merge_request_diffs` 等任务因 `MirrorRowChange` 构造函数中的 `Map.copyOf` 拒绝来源行 `NULL` 值而抛 `NullPointerException`；运行终态为部分成功，不能推进完整新鲜度。当前 `main` 的 `MirrorRowChange` 尚未包含仅存在于 `codex/fix-label-links-reconcile-20001` 的 `553f7a49` 可空字段修复。
- [失败] `gitlab_sync_configs.last_incremental_sync_at` 仍为 `2026-07-29 16:52:39.299707`，`incremental_rerun_requested_at` 为空、触发计数为 `0`；测试实例停止后不再继续产生现场任务。ODS `issues` 中无 `42418`，非删除行最大更新时间仍为 `2026-07-06 07:10:00.145174`。
- [当时结论] 首次实现当时不可交付，不能以“运行日志为绿色”或“调度确实每 10 分钟触发”作为通过依据；时间戳墙上时间语义和可空行快照必须修复并重新执行真实自动增量验收。修复后的通过证据见下一节。
- [设计证据] PostgreSQL JDBC 42.7.13 在 `Asia/Shanghai` JVM 下把无时区 `2026-08-06 03:20:22.840104` 返回为 `Timestamp(local=03:20, instant=前日19:20Z)`，把有时区 `2026-08-06 03:20:22.840104Z` 返回为 `Timestamp(local=11:20, instant=03:20Z)`；两者 `getColumnType()` 均为 `93`，只有 `getColumnTypeName()` 能稳定区分 `timestamp` 与 `timestamptz`。因此禁止对所有 `Timestamp` 统一调用 `toInstant()` 或 `toLocalDateTime()`。

### 2026-08-06 修复后 10 分钟自动链路复验

- [通过] 在同一 DIRECT 配置（`auto_sync_enabled=true`、间隔 10 分钟）创建 Issue `cloudcad/cc-product#2549`（全局 ID `42419`），未执行手工刷新。自动增量 `524` 于 `16:19:41-16:20:00` 完成 `21/21` 张表，ODS 和 `issue_fact` 均写入该 Issue；事实子运行 `525` 完成 `67/67`，`events`、`merge_request_diffs` 未再出现可空快照异常。
- [通过] 通过 GitLab 16.11 `Labels::CreateService` 和 `Issues::UpdateService` 给该 Issue 增加唯一临时标签 `1274`，生成关系 `338176` 和事件 `390837`。无手工刷新时，自动增量 `528` 于 `16:51:11-16:51:28` 成功，事件 checkpoint、ODS active 关系和 `issue_fact.label_names` 同轮更新；事实子运行 `529` 成功。
- [通过] 通过同一 Rails 业务服务移除该 Issue 的最后一条标签关系，源端 `label_links` 行消失并生成移除事件 `390838`。自动增量 `530` 于 `17:01:43-17:01:56` 成功，精确权威范围为 `label_links(target_type='Issue',target_id=42419)`；ODS 关系 `338176` 写 `mirror_deleted=true`，`issue_fact.label_names`、严重程度和模块字段清空，Issue 与集成测试事实目标均发布，事实子运行 `531` 成功。
- [隔离] ODS 中同样 `target_id=42419` 的 3 条 `target_type='MergeRequest'` 标签关系保持 active，证明复合范围未跨类型误删。
- [边界] 本次证明事件可达的标签新增/最后关系删除可在下一轮 10 分钟自动增量内收敛。无事件静默删除已由真实 PostgreSQL 集成测试覆盖；生产 `DELETE_RECONCILIATION` 默认关闭，仍须完成约 280 万总量基准和现场静默删除验收后启用。

### 版本级回归与潜伏缺陷边界

1. `2fd5c34e` 为修复 GitLab 物理删除未及时收敛，把 `INCREMENTAL_SYNC`、`TABLE_REFRESH` 纳入 `SyncRunReconciliationCoordinator.planIfReady`，为每张已扫描表追加全表 `RECONCILE`。
2. 同一提交把过去普通增量会跳过的无时间列表改为 `DELETE_ONLY`，执行器不读取来源便调用 `completeUnchangedTask`，随后再用该表全部 ODS active 主键执行删除对账。
3. `issues` 有 `updated_at`，仍先执行固定上界时间增量；新增全表阶段不会直接改变 Issue 查询条件，但会延长同源 writer 持有时间，使 10 分钟触发更容易与活动运行碰撞。
4. 活动 `INCREMENTAL_SYNC` 存在时，`SyncRunSubmissionService.findReusableForegroundRun` 只返回旧 `runId` 和 `REUSED_ACTIVE/REUSED_QUEUED`，不插入新运行、不写待补跑标记，也不在旧运行结束后自动开启新固定上界。
5. 运行结束时，`SyncRunWorkerService.updateSyncTimestamps` 对所有非 `FULL_SYNC` 的 `SUCCESS/PARTIAL_SUCCESS` 镜像运行调用 `updateSyncTime(..., false)`；`GitlabCompensationScheduler` 又只按该字段判断 10 分钟是否到期。
6. 因而一次 `TABLE_REFRESH`、`SYSTEM_HOOK`、`FULL_COMPENSATION_SCAN`、部分成功或 0 任务成功都能把调度基准推到当前时间。只要这类绿色运行持续出现，真正的完整 `INCREMENTAL_SYNC` 可以长期不被提交。
7. 即使日志中的绿色运行确实都是 `INCREMENTAL_SYNC`，当前终态也只检查任务失败/待处理数，不要求计划中包含 `issues`，不要求必需表任务覆盖其来源固定上界，也不拒绝 0 表运行；因此仍可形成“每轮都成功，但 Issue checkpoint 没有追平”的结果。
8. 这四个缺陷共同构成确定的控制面根因：日常运行范围被放大、同类触发只复用不补跑、错误运行可推进全局时钟、绿色状态不证明数据覆盖。修复必须同时处理，单独缩短任务、增加 worker 或调整间隔都不能恢复契约。

### 现场耗时反证

- 如果单轮固定为 20 分钟：`T0` 扫描固定上界，`T+10` 触发被合并，`T+20` 旧运行结束，最迟在新的 10 分钟间隔后仍应提交下一轮。该模型只能造成约几十分钟延迟，不能造成三天没有有效新增。
- 现有队列合并本身没有失效；缺陷是合并后没有持久“尾部补跑”语义。它减少了队列行数，却也丢失了旧扫描上界之后的新到期请求。
- 约 280 万行全表删除对账仍必须从普通增量移除，因为它扩大延迟、触发碰撞和 GitLab 负载；但最终修复不能再把它写成三天断档的唯一根因。

### 绿色日志为什么不代表实时

- 后端日志行已包含 `runType`、`triggerType`、表任务数和写入数，但 `SUCCESS` 只表示没有失败或待处理任务。
- 最近同步日志能按 `runType` 区分普通增量、单表刷新和 Hook，但绿色标签只映射运行状态，不计算 `source upper bound <= checkpoint`。
- `DELETE_ONLY + 0 行`、普通增量规划 0 张表、缺少 `issues`、只完成删除存在性验证或成功但无变化，都可以显示绿色。
- 被复用的 10 分钟触发没有独立 `sync_runs` 行，所以最近日志看不到它，也无法证明触发后开始了新窗口。

### 现场只读判定清单

实施前从 20001 保存一份只读证据，用于确定当时走的是“调度被假时钟推迟”还是“增量运行缺少覆盖”分支；两者共享同一修复，不以该查询结果决定是否修复：

1. 最近 72 小时 `sync_runs` 按 `run_type/trigger_type/status` 分组，并列出每个运行的创建、开始、结束时间；重点区分 `INCREMENTAL_SYNC`、`SYSTEM_HOOK`、`TABLE_REFRESH` 和 `FULL_COMPENSATION_SCAN`。
2. 对每个绿色 `INCREMENTAL_SYNC` 核对是否存在 `issues` 的 `SCAN` 任务、`watermark_at`、`scan_upper_bound_at`、终态、扫描行数和应用行数；0 任务或缺少 `issues` 直接判为伪成功。
3. 同时比较 `gitlab_sync_configs.last_incremental_sync_at`、`sync_run_table_states.issues.last_watermark_at`、GitLab `max(issues.updated_at)`、ODS active `max(issues.updated_at)`；全局时间较新但表水位/ODS 落后即证明假时钟。
4. 核对活动运行与日志 ID 是否连续；当前版本无法还原已丢失的复用触发次数，这一缺口本身是已确认事实，修复后由 pending 字段和 `sync_run_events` 提供证据。

### 排除项

- 不是用户把同步间隔设错：现场已显式保存为 10 分钟。
- 不是 `issues` 有 270 万行：规模约 280 万是所有同步表合计。
- 不是 `553f7a49` 的 nullable 快照修复：该提交没有改变任务规划和来源读取。
- 不是 GitLab Issue 本身不更新：18181 上一版本能及时同步，同一现场差异与 `2fd5c34e` 的版本边界一致。
- 不是简单把间隔改到 30 或 60 分钟就能规避：这既不能修复错误时钟和丢失尾部触发，也会扩大数据陈旧时间。

## 目标运行模型

```text
每 10 分钟快速增量
  -> 若已有同类 active 运行，持久合并为一个 rerun_requested_at
  -> UPDATED_AT 表读取 (watermark, fixed upper bound]
  -> MONOTONIC_PRIMARY_KEY 信号表读取 (last id, fixed max id]
  -> 事件/父行登记批量权威范围
  -> 当前完整关系集合替换 ODS，缺失关系写 tombstone
  -> 事务内登记事实目标
  -> 校验必需表、来源上界和 checkpoint 完整覆盖
  -> 仅完整 SUCCESS 推进 last_incremental_sync_at
  -> 若存在 rerun_requested_at，原子清除并立即排入一个新固定上界运行

独立后台删除反熵
  -> DELETE_RECONCILIATION 低优先级运行
  -> 每表一个持久 RECONCILE 任务
  -> ODS active 主键分页到 GitLab 验证
  -> mirror-only 行写 tombstone并登记事实目标
  -> 每页提交后检查前台等待者和时间片
  -> PAUSED 释放 writer，之后从原 cursor 恢复
  -> 表完成时单独推进 last_delete_reconciled_at
```

快速增量和后台反熵共用相同的 `MirrorRowChange -> sync_run_fact_targets -> FACT_REFRESH -> projection generation` 发布链。两者只在“如何发现变化”上不同，发现变化后的正确性逻辑只有一套。

## 标签删除完整数据流

1. GitLab 从 Issue 移除标签，物理删除对应 `label_links`，并向 `resource_label_events` 追加 `action=2` 事件。
2. 下一次快速增量按 `resource_label_events.id` keyset 读取新事件；事件中的 `issue_id` 生成范围 `label_links(target_type='Issue', target_id=:issueId)`。MR 同理使用 `merge_request_id` 和 `target_type='MergeRequest'`。
3. `SyncRunAuthoritativeScopePlanner` 按规范化范围去重；同一轮同一 Issue 多个标签事件只读取一次当前完整集合。
4. 权威执行器读取 GitLab 当前 `label_links` 集合，并与同范围 ODS active 集合比较。来源少掉的关系在同一事务写 `mirror_deleted=true`；来源为空表示删除全部标签，不是查询失败。
5. tombstone 前后的稳定父对象身份登记到现有事实 outbox；Issue 定向事实替换重新读取当前 active 标签，删除旧 `severity_level`、状态、模块等派生值。
6. 相关项目、范围组和全局投影 generation 推进；未受影响项目和页面快照不失效。
7. 如果 GitLab 未产生事件、事件读取失败或标签对象自身被物理删除，后台 `DELETE_RECONCILIATION` 对 `label_links`/`labels` 的主键存在性验证负责最终收敛，不需要人工全量补偿。

## 来源读取契约

在 `GitlabSourceLineageCatalog.SourceDefinition` 中新增显式 `IncrementalReadMode`，元数据发现只能验证目录契约，不能再自行决定业务策略：

| 模式 | 适用范围 | 普通增量行为 | 契约失败行为 |
| --- | --- | --- | --- |
| `UPDATED_AT` | `issues` 等有可靠变更时间列的业务表 | 固定上界时间窗口 + 主键 keyset；末页推进时间水位 | 声明列不存在或类型错误时任务失败并告警 |
| `MONOTONIC_PRIMARY_KEY` | `resource_label_events` 等追加型信号表 | 固定最大主键上界 + 主键 keyset；末页推进 `last_cursor_pk` | 主键缺失、不可比较或回退时任务失败，不推进 cursor |
| `RECONCILE_ONLY` | 无可靠增量列、仅由权威父范围或后台反熵维护的表 | 普通增量不创建伪 `SCAN`；只接受已声明权威范围 | 若既无权威来源又被事实消费，启动/schema guard 失败 |

要求：

- 删除 `DELETE_ONLY` 任务策略及其 `completeUnchangedTask` 快捷成功分支。
- 23 张推荐表必须全部显式分类；新增推荐表没有默认模式。
- `resource_label_events` 使用 `MONOTONIC_PRIMARY_KEY`，处理所有新事件来触发当前集合核对，不以 `action` 值决定是否登记范围。
- 首次切换事件游标时以已镜像最大 `id` 初始化 checkpoint；部署后的一次全量补偿负责消除历史缺口，再开始严格 `id > checkpoint` 增量。
- `sync_run_table_tasks` 增加持久 `scan_upper_bound_pk`，与已有 `cursor_pk` 一起保证进程重启后仍扫描同一固定主键窗口。
- 普通 `INCREMENTAL_SYNC` 只在所有快速扫描与本轮权威范围完成后推进增量成功时间，不等待后台反熵。

## 后台删除反熵状态机

### 运行与任务

- 新增内部运行类型 `DELETE_RECONCILIATION`，不新增第二套删除算法；任务直接进入现有 `RECONCILE` 执行器。
- 每个来源同一时刻最多一个 active/paused 删除反熵运行；每张选中表一个持久任务，cursor、累计扫描/应用数和重试状态保存在同一任务行。
- 删除运行不执行全量业务行 `SCAN`，只验证 ODS active 主键是否仍存在于 GitLab，因此不会重复承担新增/修改同步。
- 只有来源查询成功且返回集合通过子集校验时才能 tombstone mirror-only 行。超时、连接失败、非法主键或部分返回使整页失败，cursor 和删除时间不推进。

### 优先级与互斥

- 优先级顺序：手工 `TABLE_REFRESH` > 自动 `INCREMENTAL_SYNC` > `DELETE_RECONCILIATION` > `FULL_COMPENSATION_SCAN`。
- 后台删除页开始后不强制中断远程 SQL；页事务提交后若存在前台等待者，立即把运行持久化为 `PAUSED`、释放同源 writer 和本地 worker 容量。
- 即使没有前台等待者，达到固定时间片也暂停并重新排队，避免一个 280 万总量运行长期占有 writer。
- `SyncRunSubmissionService` 不得把新的增量或手工刷新去重/复用到 active `DELETE_RECONCILIATION`；它们必须形成独立高优先级运行。
- 后台运行恢复时复用原 run/task/cursor，不新建重复任务；等待时间老化仍可防止后台永久饥饿。

### 调度与新鲜度

- 自动增量到期判断读取 `last_incremental_sync_at`、用户保存的 10 分钟间隔和持久 `incremental_rerun_requested_at`；后者存在时不等待新间隔。
- `last_incremental_sync_at` 只能由完整 `INCREMENTAL_SYNC` 在全部必需快速表和权威范围成功后推进；`PARTIAL_SUCCESS`、`TABLE_REFRESH`、`SYSTEM_HOOK`、`DELETE_RECONCILIATION` 和 `FULL_COMPENSATION_SCAN` 均不得推进该字段。
- 同类 `INCREMENTAL_SYNC` 已 active/queued 时仍只合并为一个运行，但必须在配置行原子写入最早 `incremental_rerun_requested_at` 并累计 `incremental_rerun_trigger_count`，同时向目标运行写 `RERUN_REQUESTED` 事件。不得只返回旧 `runId`。
- 旧运行进入终态时，完成事务在同一配置行锁内消费 pending 标记并创建恰好一个新 `INCREMENTAL_SYNC`；无论旧运行成功还是失败，已发生的新到期请求都不能丢失。事务失败时标记和旧运行终态一并回滚，由恢复 worker 重试。
- 新运行在任务首次执行时独立获取来源固定上界，不能沿用被复用运行的 `scan_upper_bound_at`。多个 pending 触发只补跑一次最新窗口，避免恢复为无界队列积压。
- 普通增量规划 0 张快速表、缺少 `issues` 等目录声明的必需表、任一来源上界未固化、checkpoint 未覆盖该上界或权威范围未全部成功时，运行必须失败且不得推进全局时钟。
- 后台调度按各表 `last_delete_reconciled_at` 选择到期表；运行进行中或暂停时不提交重复 sweep。
- 默认目标为所有推荐表 60 分钟内完成一轮；该值是发布硬门禁，不是凭样本直接确定的参数。若 280 万固定负载无法在不影响 GitLab 的前提下满足，必须先优化批查询/批大小或调整容量方案，不能让普通增量重新等待全表扫描。
- 配置项使用独立部署属性：删除反熵开关、目标周期、单次时间片和页大小；不得复用用户的 10 分钟增量间隔表达两种不同 SLA。

## 实施改动清单

### 领域契约与元数据

1. `GitlabSourceLineageCatalog`：为 `SourceDefinition` 增加 `IncrementalReadMode` 和必需列契约；显式分类全部推荐表。
2. `TableWhitelistOption`、`GitlabSourceMetadataSupport`、`GitlabWhitelistService`：元数据只填充物理列、类型和索引能力；最终读取模式由目录决定并由 schema guard 校验。
3. `GitlabSourceSchemaGuard`：增加模式、主键、声明时间列和权威来源闭包校验；关键表契约不满足时阻止运行，不再回退为伪成功。

### 运行规划与执行

1. `SyncRunType`、`SyncRunPolicyService`、`SyncRunSubmissionService`：加入 `DELETE_RECONCILIATION`、低优先级、独立去重和前台抢占规则。
2. `SyncRunSubmissionService`、`SyncRunCompletionCommitService`、运行事件记录：复用/合并增量时持久写 pending 状态；旧运行终态原子创建一个尾部补跑；事件记录触发时间、action 和原 `runId`，使“按时触发但未开始新扫描”可诊断，不伪造成新的成功运行。
3. `SyncRunTablePlanningService`：普通增量仅规划 `UPDATED_AT`/`MONOTONIC_PRIMARY_KEY`；删除运行直接规划 eligible 表的 `RECONCILE`；全量同步/补偿继续使用 `FULL_RECONCILE`。
4. `SyncRunReconciliationCoordinator`：从普通 `INCREMENTAL_SYNC` 和默认 `TABLE_REFRESH` 的全表阶段移除；仅为明确全量运行服务，或由删除运行规划器复用任务创建能力。
5. `SyncRunTableTaskExecutor`：新增固定主键上界扫描；删除 `DELETE_ONLY`；保留现有参数化主键存在查询和 tombstone 执行器。
6. `SyncRunTablePageCommitService`：时间水位、单调主键 cursor、删除对账时间分别推进；任一维度不能代替另一维度。
7. `SyncRunWorkerService`、运行完成事务、让行协调器和恢复服务：快速运行不等待全表对账；先验证必需表和来源上界覆盖，再由完整快速增量 `SUCCESS` 更新 `last_incremental_sync_at`；后台删除在页边界/时间片暂停，前台运行完成后按原 cursor 恢复。
8. `GitlabCompensationScheduler`：拆分“增量到期”和“删除反熵到期”判断；两类提交和日志使用不同名称与指标。

### 数据迁移与诊断

1. 新建前向 Flyway 迁移，为 `sync_run_table_tasks` 增加 `scan_upper_bound_pk`，为 `gitlab_sync_configs` 增加 nullable `incremental_rerun_requested_at` 和非负 `incremental_rerun_trigger_count`，迁移旧 `row_strategy` 诊断值，并增加必要约束/索引；不得修改 `V20260731_*`。
2. 复用 `sync_run_table_states.last_cursor_pk` 作为单调主键 checkpoint，复用 `last_delete_reconciled_at` 作为每表反熵新鲜度；不新增含义重复字段。
3. 诊断接口/页面分别显示“执行结果”“最近增量追平”“最近删除对账完成”“增量滞后”“删除对账滞后”；绿色“数据已追平”必须由来源上界、表 checkpoint 和运行覆盖共同证明，总体执行成功不能覆盖数据陈旧告警。
4. 结构化日志补齐 `readMode`、`watermark/cursor`、`sourceUpperBound`、`coverageStatus`、`rerunRequestedAt/count`、`deleteSweepAge`、`yieldReason` 和 `foregroundQueueAge`。

## 失败恢复与一致性

- 快速增量失败：不推进该表水位和配置的增量成功时间；下一轮从原 checkpoint 重试。
- 标签事件页成功、权威范围失败：事件任务可成功提交，但镜像父运行不能记增量成功；权威范围按原身份重试，不能把空集合当作失败回退。
- 后台删除页失败：不推进 cursor、`last_delete_reconciled_at` 或 tombstone；按现有退避/上限进入 `RETRYING/FAILED` 并告警。
- 后台让行或进程重启：恢复原 run/task/cursor；已提交 tombstone 和事实 outbox 幂等，不回滚到旧 active 状态。
- 事实或投影失败：镜像变化保持提交，现有 outbox/版本头继续重试；页面手工 publication fence 只有在必需事实和投影均发布后才成功。
- GitLab 查询返回空只在“权威范围查询成功”时表示完整清空；网络错误、SQL 错误和 schema 错误绝不能转成空集合。

## SLA、容量与监控门禁

### 发布目标

| 指标 | 10 分钟配置下的硬门禁 |
| --- | --- |
| Issue 新增/更新从 GitLab 提交到 ODS 可见 | 不超过 15 分钟 |
| Issue/MR 标签移除事件到 ODS tombstone 和对应事实发布 | 不超过 15 分钟 |
| 后台删除页阻塞前台运行 | 不超过一个在途页，且不超过 30 秒 |
| 约 280 万全部 active 键完成一轮静默删除反熵 | 不超过 60 分钟 |
| 连续两个自动增量周期 | 即使首轮仍 active，后续触发也必须形成一个可审计尾部补跑；不得只复用旧扫描上界 |

### 必须监控

- 每表 `source max(updated_at) - last_watermark_at`；`issues` 超过两个增量周期立即告警。
- `resource_label_events max(id) - last_cursor_pk` 和最老未处理事件时间。
- 每表 `now - last_delete_reconciled_at`、当前 sweep 完成比例、页耗时和 GitLab 查询失败率。
- 前台运行排队时长、后台让行次数、writer 持有时长、DIRECT 连接池等待数和源库慢查询。
- tombstone 数、事实 outbox 未发布数、投影失败数；镜像成功但事实长期未发布必须单独告警。

## 验收与测试矩阵

### 自动回归

| 场景 | 必须断言 |
| --- | --- |
| 普通 Issue 增量 | 只创建快速扫描/权威任务，不创建任何全表 `RECONCILE`；水位和 `last_incremental_sync_at` 推进 |
| 增量运行 0 行但水位已追平 | 可记执行成功；只有来源上界不超过 checkpoint 时才记数据新鲜 |
| `PARTIAL_SUCCESS`/单表/Hook/删除运行成功 | 不推进全局 `last_incremental_sync_at`；下一次自动增量仍按原计划到期 |
| 同类运行复用 | 不创建无界重复运行；持久 pending 和触发事件；旧运行终态后恰好创建一个使用新固定上界的尾部补跑 |
| 连续多次到期触发 | active 期间 N 次触发合并为一个尾部补跑，`trigger_count=N`；补跑创建后 pending 原子清零且崩溃恢复不丢失 |
| 高频 Hook/单表刷新 | 连续成功也不改变自动增量到期基准；10 分钟完整增量仍按时产生 |
| 无时间列业务根表 | schema guard 或任务明确失败；不存在 `DELETE_ONLY + SUCCESS + 0 行` |
| 普通增量缺少 `issues` 或规划 0 表 | 运行明确失败并告警，不推进 `last_incremental_sync_at` |
| 标签移除事件 | 事件按 id 增量读取；生成类型隔离权威范围；旧 `label_links` tombstone；对应 Issue 事实清除旧标签派生值 |
| 最后一个标签移除 | 来源权威集合为空仍成功清空 ODS；不误删同 ID 的 MR 标签 |
| 同一 Issue 多事件 | 权威范围去重，只发布一个稳定根的最新版本；重复执行幂等 |
| 事件页查询失败/重启 | 上界和 cursor 不漂移；恢复后无漏行、无重复副作用 |
| 后台发现静默删除 | 复用现有 tombstone/outbox/事实/投影链，无需 `FULL_COMPENSATION_SCAN` |
| 后台无删除 | 只推进 `last_delete_reconciled_at`，不创建事实或投影任务 |
| 后台来源查询失败 | 不删除任何 ODS 行，不推进 cursor 和删除时间 |
| 后台运行中到期增量 | 创建独立高优先级运行；后台一页后 `PAUSED`；Issue 增量先完成后原 cursor 恢复 |
| 手工刷新与后台交错 | 不复用后台运行；publication fence 等待所有已提交目标发布 |
| 事实/投影暂时失败 | 镜像 tombstone 保留，outbox 可恢复，页面不伪报最新 |

### 固定负载基准

1. 仅在内网使用约 280 万 active 键的真实数据集，分别测量 2/4/6 worker；记录每表、每页、整轮耗时和 P50/P95/P99。禁止以本地 31 万级数据替代或线性外推。
2. 基准同时每 10 分钟注入 Issue 新增/更新和标签移除事件，证明快速运行不积压且不等待整轮删除扫描。
3. 记录 GitLab CPU、I/O、buffer hit、连接数、慢查询和平台 DIRECT 池等待；不能只看平台总耗时。
4. 分别验证 0 删除、小量删除和来源查询失败；内存复杂度保持 `O(batchSize)`。
5. 只有 15/30/60 分钟三项 SLA 门禁全部通过，才允许启用自动删除反熵；未通过时不得恢复普通增量全表对账。

### 内网真实链路

1. 创建 Issue 并更新标题/描述，等待一次 10 分钟自动增量，核对 GitLab 与 ODS 最大更新时间和目标行。
2. 给 Issue 增加再移除标签，核对 `resource_label_events`、ODS `label_links.mirror_deleted`、`issue_fact`、统计、下钻和导出。
3. 构造一个不依赖事件的物理删除，核对后台反熵自动 tombstone 和事实发布。
4. 在后台 sweep 进行时更新另一条 Issue，证明后台页边界让行和快速增量先完成。
5. 全链记录 run/task/fact/projection ID，任何一步失败都能从日志和诊断接口定位。

## 部署与现场恢复

1. 部署前停止自动调度并确认无 active 镜像/事实运行；备份 PostgreSQL，禁止删除 volume。
2. 部署代码和前向迁移，先保持后台删除反熵关闭；执行 schema guard 和事件字段只读检查。
3. 执行一次 `FULL_COMPENSATION_SCAN`，清理异常版本期间已经形成的 Issue 更新断档、标签旧关系和其他历史漂移；等待三类事实与投影全部成功。
4. 仅在全量补偿成功且来源/ODS 行数与最大 ID 核验一致后，以已成功镜像的 `resource_label_events.id` 初始化单调事件 checkpoint；开启 10 分钟快速增量，连续验证至少三轮无普通 `RECONCILE`、无丢失尾部补跑。
5. 开启低优先级 `DELETE_RECONCILIATION`，观察完整一轮 280 万总量基准及其间至少一次前台增量让行。
6. 通过 SLA 和数据一致性验收后再恢复常态；上线清理完成后，日常标签删除和静默删除均不再依赖人工全量补偿。
7. 回滚只回滚应用镜像，不回滚已执行 Flyway；恢复旧应用前必须确认其能忽略新增列。若不能，使用升级前数据库备份恢复到隔离环境，不对生产 volume 执行破坏性操作。

## 方案与步骤

1. [完成] 锁定正常/异常发布、回归提交和现场 10 分钟配置事实。
2. [完成] 用现场耗时反证“无限积压”单因解释，区分 `2fd5c34e` 的版本级回归与更早的假时钟/无尾部补跑潜伏缺陷。
3. [完成] 确定快速增量覆盖门禁、持久尾部补跑、标签事件权威刷新、独立删除反熵、失败恢复和 SLA。
4. [完成] 已增加同步控制面、时间戳类型元数据与可空快照失败回归测试，并修正烟测发现的运行时缺陷；定向、同步域、真实 JDBC 和事实发布组件回归通过。
5. [完成] 实现 pending 合并与原子尾部补跑，从普通增量移除全表 `RECONCILE`，实现单调事件扫描和标签权威范围快速刷新；10 分钟自动 Issue 新增、标签新增和最后标签删除链已通过。
6. [完成但待现场容量验收] 增加独立 `DELETE_RECONCILIATION`、低优先级调度、时间片和前台页边界让行，并完成 Hook 隔离、真实 PostgreSQL 集成及确定性回归。
7. [完成] 已生成并启动全新空数据隔离包，验证 Compose project、容器、网络、卷和主机端口隔离；打包器前端测试/构建并发写 `components.d.ts` 的竞态已改为顺序执行并增加回归测试。
8. [进行中] 在内网 30001 新栈执行真实 GitLab 自动链路及 280 万容量、无事件静默删除和 sweep 期间前台增量让行验收。

## 决策记录

- 选定：快速增量、事件定向权威刷新、后台反熵三层共用单一 tombstone/派生发布链。
- 选定：保留同类触发合并，但把它改为“一个 active + 一个持久尾部补跑”语义；不能只返回旧运行 ID。
- 选定：执行成功与数据新鲜度分离，只有完整快速增量覆盖必需表和来源上界才推进全局增量时钟。
- 选定：`resource_label_events` 只定位父对象，删除结论来自当前完整 `label_links` 集合；这同时覆盖多标签、重复事件和最后一条关系删除。
- 选定：后台反熵复用现有 `RECONCILE` 执行器，以独立 run type、低优先级和持久 cursor 解决容量与抢占问题。
- 否决：继续让普通增量执行全部表主键扫描；其成本与总量相关，天然破坏 10 分钟新增/更新时效。
- 否决：把三天断档只归因于单轮积压；现场 30 秒单表/20 分钟全量数据不支持该结论，也不能解释假时钟和绿色伪成功。
- 否决：仅把间隔调到 30/60 分钟；这不能改变单轮任务结构，也不能保证下一轮新增 Issue 被读取。
- 否决：只依赖 System Hook；当前 GitLab System Hook 不提供完整 Issue 删除/标签关系可靠事件覆盖。
- 否决：每天人工跑全量补偿；这是运维补救，不是日常一致性设计。
- 否决：为标签写页面层过滤或统计层兜底；ODS 仍错误时其他消费者继续不一致。

## 接口契约

- 对外 REST 路径、页面业务 DTO、统计口径和导出格式不变。
- 内部新增 `SyncRunType.DELETE_RECONCILIATION` 和 `IncrementalReadMode`；删除内部 `DELETE_ONLY` 策略。
- 内部任务新增 `scan_upper_bound_pk`；`last_cursor_pk`、`last_watermark_at`、`last_delete_reconciled_at` 各自保持单一语义。
- `last_incremental_sync_at` 重新收口为“完整快速增量追平时间”，不再由其他镜像 run type 或部分成功更新。
- `gitlab_sync_configs.incremental_rerun_requested_at` 和 `incremental_rerun_trigger_count` 表达活动增量之后至少发生一次新到期触发；它们只负责合并后的尾部补跑，不充当数据水位。
- `sync_run_events` 新增 `RERUN_REQUESTED`/`RERUN_QUEUED` 事件类型，payload 至少包含目标运行、首次/最近触发时间、累计次数和触发来源。
- `sync_runs.payload_json.resolvedSourceTables` 保存本轮元数据解析后的实际白名单快照；权威范围选择使用该快照与真实 `SCAN` 任务并集，不能因 `RECONCILE_ONLY` 不建任务而丢失关系刷新。
- 诊断接口允许增加独立执行结果、增量/删除新鲜度和复用触发字段；如果前端展示这些字段，必须同步更新类型、测试和文案。

## 风险与假设

- `resource_label_events` 是快速标签删除信号，但不能假设所有物理删除都产生事件；独立反熵仍是最终一致性保障。
- 严格单调主键扫描需要首次 checkpoint 和固定上界；部署前历史缺口必须用一次全量补偿清理。
- 280 万总量的真实吞吐受表分布、GitLab 缓存、网络、worker 和 batch 大小影响；样本线性外推不能替代发布基准。
- 后台扫描即使低优先级也会读取 GitLab；必须以 CPU、I/O、连接和慢查询共同决定参数，不能只提高 worker。
- 当前 20001 现场运行表仍未取得只读结果，所以不能从现有截图区分是假时钟饿死调度，还是绿色 `INCREMENTAL_SYNC` 缺少 `issues`/上界覆盖；这不改变已由代码和版本差异证明的控制面缺陷、`2fd5c34e` 回归边界和目标修复结构。
