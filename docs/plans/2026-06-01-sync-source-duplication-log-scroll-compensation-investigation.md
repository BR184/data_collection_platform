<!-- DOC_STATUS_START -->
> 文档状态：已实施并验证
> 说明：同源保护、同步日志滚动条、补偿 planning/缓存优化已落地并通过相关测试。
<!-- DOC_STATUS_END -->

# 同源配置、同步日志滚动条与补偿扫描耗时调研方案

日期：2026-06-01
范围：GitLab 镜像数据源配置、最近同步日志交互、自动/全量补偿扫描性能与可观测性
状态：已实施并验证

## 1. 结论摘要

这三个问题都不是“功能不可用”的大故障，而是生产前很值得处理的工程细节：

1. **同一个 GitLab 源库被多个启用的数据源配置指向**，当前平台只在前端提示，不会阻止保存。由于事实表唯一键包含 `source_instance`，同一批 Issue、MR、评论会以不同来源身份进入事实层。单来源页面不一定出错，但未显式按来源过滤或跨来源汇总的页面会看到重复业务对象。这是可预期的数据治理风险，不是随机小概率 bug。
2. **最近同步日志横向滚动条不够灵敏**，根因更像是 Element Plus 表格内部滚动容器与浏览器自动隐藏滚动条机制叠加。现在没有专门增强“可横向拖动”的视觉/命中区域。
3. **0 写入但 20 表补偿扫描耗时变长**不一定反常。当前补偿扫描仍会逐表规划任务、探测 `max(updated_at)`、判断水位、更新状态、汇总日志。`recordCount=0` 只说明没有写入，不说明没有源库查询、任务调度、连接等待或事实刷新。现在日志只展示“计划表项/完成表项/写入记录/总耗时”，不足以解释慢在哪里。

建议不是大重构，而是补齐三层保护：**配置层防误启、UI 层更可操作、运行层分阶段观测**。

## 2. 本地实现确认

### 2.1 同源重复配置

已确认代码中有前端重复物理源判断：

- `frontend/src/views/MirrorSettingsView.vue`
  - `physicalSourceFingerprint(config)` 对 DIRECT 使用 `host:port:dbName:dbUsername`，对 DOCKER 使用 `dockerContainerName`。
  - `duplicatePhysicalSourceMatches` 只检查已启用数据源。
  - `duplicatePhysicalSourceWarning` 只展示 warning，文案明确“平台不会阻止保存”。

服务端保存链路：

- `backend/src/main/java/com/data/collection/platform/controller/GitlabSyncConfigFacade.java`
- `backend/src/main/java/com/data/collection/platform/service/GitlabConfigService.java`

服务端目前会校验：

- `source_instance` 唯一。
- System Hook Secret 在启用源之间唯一。
- 自动同步开启时连接配置完整。
- 补偿间隔范围 1-720 分钟。

服务端目前不会校验：

- 两个启用配置是否指向同一个物理 GitLab 数据库。
- 两个启用配置是否同时开启自动同步但共享相同 DB 指纹。
- 一个源是生产源，另一个是 smoke/minimal 但实际同库时是否允许事实层双写。

事实层唯一键：

- `issue_fact`：`(source_system, source_instance, project_id, issue_id)`。
- `merge_request_fact`：`(source_system, source_instance, project_id, merge_request_id)`。
- `integration_test_fact`：`(source_system, source_instance, project_id, issue_id)`。

因此，同一个物理 GitLab 对象如果进入 `cc` 和 `smoke_cc` 两个 source instance，数据库不会认为它重复。这是设计上的多源隔离能力，但对“同物理源重复启用”会放大成业务重复。

### 2.2 最近同步日志横向滚动条

相关文件：

- `frontend/src/views/MirrorSyncLogTable.vue`
- `frontend/src/styles.css`

当前日志表：

- `el-table` 使用 `max-height=280`。
- 列宽合计约 984px 加上消息列 `min-width=220`，在右侧面板宽度不足时需要横向滚动。
- `sync-log-table-shell` 只有 `max-height: 240px`，没有专门定义横向滚动提示、滚动条 gutter 或 hover/focus 状态。
- Element Plus 的横向滚动条在内部 `.el-scrollbar__bar.is-horizontal` 上，由组件控制显示隐藏。自动隐藏设计符合预期，但用户反馈说明 hover 命中或视觉强度不足。

### 2.3 补偿扫描耗时

相关文件：

- `backend/src/main/java/com/data/collection/platform/service/GitlabCompensationScheduler.java`
- `backend/src/main/java/com/data/collection/platform/service/GitlabDailyVerificationScheduler.java`
- `backend/src/main/java/com/data/collection/platform/service/sync/SyncRunSubmissionService.java`
- `backend/src/main/java/com/data/collection/platform/service/sync/SyncRunDispatcherService.java`
- `backend/src/main/java/com/data/collection/platform/service/sync/SyncRunWorkerService.java`
- `backend/src/main/java/com/data/collection/platform/service/sync/SyncRunTablePlanningService.java`
- `backend/src/main/java/com/data/collection/platform/service/sync/SyncRunTableTaskExecutor.java`
- `backend/src/main/java/com/data/collection/platform/service/sync/SyncRunLogService.java`

关键观察：

- 自动补偿扫描由 `GitlabCompensationScheduler` 每 `scheduler-delay-ms` 扫描一次是否到期，默认 60 秒。
- `SyncRunDispatcherService` 默认每 2 秒派发 queued run。即便任务本身很快，也可能包含最多接近 2 秒的排队派发等待。
- `COMPENSATION_SCAN` 在 `SyncRunTablePlanningService` 中 sourceTables 为空时，会从白名单规划所有可运行表。
- 每张表执行时可能先调用 `findMaxUpdatedAt` 判断源表是否有更新；即使无写入，也要访问源库。
- `recordCount` 来自 `run.appliedRows`，不包含扫描行数、跳过表、源库探测、排队等待、连接耗时、任务规划耗时。
- 当前日志 `formatDuration` 只用 `finishedAt - startedAt`，而 `startedAt` 由 dispatcher claim 时写入，所以不包含提交到派发之前的排队时间，但包含规划、读取、写入、补偿对账和事实刷新触发前的镜像 run 时间。

## 3. 行业/企业级规则调研

### 3.1 阿里云 DataWorks / Data Integration

阿里云 DataWorks 文档对数据同步给出的原则比较明确：

- DataWorks 的 Data Integration 覆盖离线、实时、全增量等多种同步策略；全增量任务包含全量初始化、增量同步和周期合并等阶段。
- 全增量同步要求主键或业务主键；无主键表不适合直接按普通 CDC 语义保证最终唯一状态。
- DataWorks 文档明确说明 Exactly-once 不支持，可能出现重复记录，推荐通过主键和目标端能力保证唯一性。

来源：

- Alibaba Cloud DataWorks Data Integration overview：说明 Exactly-once 不支持，重复记录可能出现，需要依赖主键和目标端能力去重。
- Alibaba Cloud DataWorks full/incremental sync：说明全增量同步依赖主键/业务主键，任务会拆分批处理、增量和合并阶段。

### 3.2 Confluent / Kafka Connect / Debezium

企业 CDC 生态的共识也类似：

- Debezium 默认提供 at-least-once delivery，异常恢复时可能重复投递；exactly-once 是更严格能力，需要依赖 Kafka Connect exactly-once 支持及配置，不是默认前提。
- Debezium FAQ 明确要求消费者预期重复事件，因为 offset checkpoint 和恢复之间可能重放。
- Confluent JDBC Sink 提供 `insert.mode=upsert`，并要求配置 `pk.mode` 和 `pk.fields`，这是典型目标端幂等写入方案。

来源：

- Debezium exactly-once delivery documentation。
- Debezium FAQ。
- Confluent JDBC Sink Connector configuration reference。

### 3.3 对本平台的映射

企业级处理原则不是“永不允许重复源”，而是分层：

1. **源身份必须清晰**：同一个物理源只能被一个生产逻辑源代表，除非明确标记为测试/影子源。
2. **写入必须幂等**：镜像表以源表主键 upsert，事实表以业务键 upsert。当前平台已具备这一层，但 key 包含 `source_instance`，所以只能保证同一逻辑源内幂等。
3. **跨源重复要显式治理**：如果允许同物理源多逻辑源，必须有数据域、环境、用途、查询隔离或跨源 dedupe 规则。
4. **高风险配置不能只靠弱提示**：生产管理台通常至少会有 warning + 二次确认；更严格的是启用自动同步/System Hook 时服务端阻断。
5. **运行指标要能解释慢**：写入数不是同步耗时的唯一指标。企业同步产品通常区分读取量、写入量、跳过量、排队时间、任务拆分、合并/对账耗时、目标端耗时。

## 4. 风险判断

### 4.1 同源配置是否会出现问题？

会，但风险类型是“数据语义重复”和“资源重复消耗”，不是数据损坏。

可能出现：

- 同一个 Issue/MR 在 `cc` 和 `smoke_cc` 下各有一份事实记录。
- 未指定 `sourceInstance` 的统计、记录页、导出或健康汇总可能重复计算。
- 自动补偿扫描、日常全量补偿、System Hook 同时跑两套配置，源库和本地库压力翻倍。
- 观察日志时看到两条来源不同但内容相同的同步任务，排障难度上升。
- 如果未来新增页面忘记加 `sourceInstance` 过滤，重复会悄悄进入新页面。

不太可能出现：

- 同一逻辑源内重复行无限累积。因为镜像表和事实表使用 upsert/唯一键。
- 直接破坏正式源数据。平台只是读取 GitLab 源库、写本地镜像/事实层。

### 4.2 生产直连是否降低风险？

直连只降低连接方式复杂度，不自动解决同源双配置问题。DIRECT 下反而更容易用相同 `host/port/db/user` 判断物理重复，因此应该利用这一点做服务端保护。

### 4.3 smoke_cc 是否应该允许？

建议允许，但必须有“测试/影子源”语义：

- 默认不允许与生产源同时 `sourceEnabled=true && autoSyncEnabled=true`。
- 允许保存为 disabled，或只允许手动同步。
- 如果必须启用，需要二次确认并留下审计记录。
- 业务页面默认不混入 smoke 源，除非用户显式选择。

## 5. 修改方案

### 5.1 同源重复配置：建议做 P0/P1 两级保护

#### P0：后端保存前保护

在 `GitlabConfigService.saveConfig` 归一化后增加物理源指纹检测。

建议规则：

- DIRECT 指纹：`direct:{normalizedHost}:{port}:{dbName}:{dbUsername}`。
- DOCKER 指纹：`docker:{dockerContainerName}:{dbName}:{dbUsername}`，比前端当前只用 container 更稳。
- 只比较 `sourceEnabled=true` 的配置。
- 当前保存对象如果 `sourceEnabled=false`，允许保存。
- 如果发现另一个启用配置同指纹：
  - 若当前配置 `autoSyncEnabled=true` 或对方 `autoSyncEnabled=true`，默认阻断。
  - 返回明确错误：同一个 GitLab 源库已由 X(sourceInstance) 启用；请停用其中一个，或将当前源保存为测试源并关闭自动同步。

为什么后端要做：前端 warning 可能被绕过，API 保存也要保护。

#### P1：允许受控例外

如果团队确实需要 smoke/minimal 源指向同库：

- 增加字段或先用约定：`sourceInstance` 以 `smoke_` 开头视为测试源。
- 测试源允许同物理源，但默认强制：`autoSyncEnabled=false`、`fullCompensationEnabled=false`、`systemHookEnabled=false`。
- 前端展示“测试源”标签，并在源选择、健康状态、日志中标注。
- 需要开启自动同步时，必须额外确认，不建议第一阶段做。

#### P1：页面查询防混源审计

做一次静态/自动化审计：所有读取 `issue_fact`、`merge_request_fact`、`integration_test_fact` 的查询，如果页面有数据源上下文，应明确传递并过滤 `source_instance`。

重点文件方向：

- `IssueFactRecordRepository`
- `MergeRequestFactQueryService`
- `IssueFactQueryService`
- `IntegrationTestQueryService`
- 所有 `*BoardService`
- 前端 `data-scope` providers 和 route query

目标不是盲目给所有页面强加 source，而是避免“默认全源汇总”被误认为单源数据。

### 5.2 最近同步日志横向滚动条增强

保留自动隐藏，但增强触发和可见性：

1. 给 `MirrorSyncLogTable` 外层增加 hover/focus 状态类，例如 `.sync-log-table-shell:hover`、`.sync-log-table-shell:focus-within`。
2. 针对 Element Plus 内部横向滚动条增强样式：
   - hover/focus 时 `.el-scrollbar__bar.is-horizontal` opacity 提高。
   - 增大横向 bar 高度和拖块最小宽度。
   - 底部预留 8-10px padding，避免滚动条贴边难以命中。
3. 增加一个非常轻的横向可滚动提示：不是常驻说明文字，而是右侧/底部渐隐阴影，只有内容溢出时出现。
4. 可选：支持 Shift + 鼠标滚轮横向滚动，适合表格宽列场景。
5. 增加一次 Playwright 视觉/交互回归：窄右侧面板下，鼠标移入日志表底部，滚动条应可见；拖动后消息列可进入视野。

不建议：

- 把滚动条改成永久显示。你对自动隐藏满意，只需要更灵敏。
- 全局修改所有 Element Plus 表格滚动条，容易影响其它页面。

### 5.3 补偿扫描耗时：先补可观测性，再决定优化

#### 第一阶段：指标拆分

在同步运行和日志中补充这些字段：

- `queuedAt`：已有但 UI 未展示。
- `startedAt`：已有。
- `finishedAt`：已有。
- `queueDurationMs = startedAt - queuedAt`。
- `runDurationMs = finishedAt - startedAt`。
- `plannedDurationMs`：表规划耗时。
- `scanDurationMs`：表任务总执行耗时，或至少按任务聚合。
- `sourceProbeCount`：源表探测次数。
- `scannedRows`：已有 run/table 层字段，但日志 UI 未展示。
- `appliedRows`：已有，当前叫写入记录。
- `skippedTables`：水位未变化跳过的表数。
- `reconciledDeletedRows`：全量补偿对账删除数。

UI 上把“写入记录”旁边补充“扫描记录/跳过表/排队耗时”，这样 0 写入但 20 表耗时 12 秒不会显得不可解释。

#### 第二阶段：慢因判断阈值

定义初步阈值：

- 20 张表、0 写入、DIRECT 同机连接：目标 1-3 秒。
- 20 张表、0 写入、远程内网连接：目标 3-8 秒，需要看每表 RTT。
- 超过 10 秒：标记 warning，展示“源库探测耗时偏高/任务排队偏高/表规划偏高”。
- 全量补偿 `FULL_COMPENSATION_SCAN` 不应与普通增量补偿用同一个 1 秒预期，因为它可能做镜像侧 active primary key 对账。

#### 第三阶段：优化候选

如果指标显示源库探测占主要耗时：

- 对补偿扫描增加“先批量探测表水位”的优化，减少每表独立连接/查询开销。
- 对没有 `updated_at` 或长期无变化的表做跳过缓存。
- 增加白名单表分层：核心实时表高频补偿，低频表只进全量日对账。

如果指标显示排队占主要耗时：

- 调整 dispatcher delay 或在手动触发后主动唤醒 dispatcher。
- 注意不要把普通 idle 页面轮询也提频，否则会重现你提到的“为了同步日志更快，整个镜像设置页 3 秒刷新导致配置表单被远端状态覆盖”的体验问题。

如果指标显示事实刷新占主要耗时：

- 区分镜像 run 耗时和后续 fact refresh run 耗时，不要混在一条日志口径里。
- fact refresh 可按受影响表决定是否触发，而不是所有 0 写入也触发完整刷新。

## 6. 回归与深度排查清单

为了避免“功能返回正常但体验变坏”的问题，建议增加一组非 happy-path 回归。

### 6.1 配置页状态保护

验证场景：

- 用户正在编辑同步策略时，idle 3 秒轮询不会覆盖未保存表单。
- 用户正在编辑同步策略时，running 1.5 秒轮询不会覆盖未保存表单。
- 保存成功后才允许强制应用服务端返回配置。
- 切换数据源时，如果当前表单有未保存变更，应提示或明确丢弃。

已有基础：`useMirrorStatusController.test.ts` 已覆盖非阻塞轮询不覆盖本地编辑，但建议加上更多字段和源切换场景。

### 6.2 同源重复保护

验证场景：

- 已有生产源 enabled + autoSync，新增 `smoke_cc` 指向同 DB 且 enabled + autoSync：后端阻断。
- 新增 `smoke_cc` 指向同 DB 但 disabled：允许保存。
- 新增 `smoke_cc` enabled 但 autoSync=false：按最终策略决定允许或二次确认。
- 修改已有配置到另一个已启用配置的物理指纹：后端阻断。
- DIRECT host 大小写、空格、端口默认值归一化后仍能识别重复。

### 6.3 日志滚动交互

验证场景：

- 右侧面板宽度低于表格总宽时，鼠标进入日志表底部 200ms 内横向滚动条可见。
- 横向拖动后最右侧消息列可见。
- 垂直滚动不受影响。
- 自动隐藏仍保留，鼠标离开后不常驻干扰。

### 6.4 补偿扫描性能解释性

验证场景：

- 20 表、0 写入：日志显示扫描/跳过/排队/运行耗时，而不是只显示写入 0 和总耗时。
- 7600+ 写入：吞吐率、扫描行数、写入行数能解释为什么 1 秒完成。
- 全量补偿与普通补偿使用不同文案和预期，不把对账任务误认为普通增量。
- 同一时刻多个配置到期时，日志能显示排队耗时，避免误判执行慢。

## 7. 建议实施顺序

### 阶段 A：低风险立刻做

1. 后端增加同物理源启用冲突校验。
2. 前端保留现有 warning，但把文案从“平台不会阻止保存”调整为与后端策略一致。
3. 最近同步日志滚动条增强，仅作用于 `MirrorSyncLogTable`。
4. 日志表增加 `queuedAt` 展示或 tooltip，先解释排队与执行的差别。

### 阶段 B：可观测性补强

1. `sync_run_events` 增加阶段事件：PLAN_START/PLAN_DONE、TASK_SCAN_SKIP、TASK_SCAN_DONE、RUN_DONE。
2. `SyncRunLogService` 汇总 queue/run/scan/applied/scanned/skipped 指标。
3. 前端日志展开区展示阶段耗时。

### 阶段 C：性能优化

基于阶段 B 的数据决定：

- 源库探测慢：优化批量水位探测或跳过缓存。
- 派发慢：优化 dispatcher 唤醒。
- 表任务慢：按表展示最慢任务。
- fact refresh 慢：拆分镜像与事实刷新展示，并减少无变化刷新。

## 8. 本次调研未做的事

- 未修改代码。
- 未连接内网真实库验证 5.27/5.28 后的耗时变化。
- 未跑 Playwright 截图，因为当前任务要求先调研并出方案。
- 未对所有事实查询做完整逐文件审计；方案中已列为 P1 审计项。

## 9. 参考资料

- Alibaba Cloud DataWorks Data Integration Overview：Exactly-once 不支持，重复记录可能出现，需要依赖主键和目标端能力保证唯一性。
- Alibaba Cloud DataWorks Configure full and incremental synchronization：全增量同步涉及全量初始化、增量同步、周期合并，并要求主键或业务主键。
- Debezium Exactly once delivery documentation：Debezium 默认 at-least-once，异常情况下可能重复投递。
- Debezium FAQ：消费者需要预期重复事件，因为恢复会从最后记录的 offset 继续。
- Confluent JDBC Sink Connector configuration：`insert.mode=upsert` 需要 `pk.mode` 和 `pk.fields`，目标端用主键语义抵御重复写入。

## 10. 2026-05-21 内网 1 秒版本对比补充

对比包：

- `D:\projects\data_collection_platform_deploy\qa-flex-platform-intranet-20260521-runnable-envfix-r2-ubuntu2404-offline.tar.gz`
- 解包后后端为 `backend/app.jar`
- `VERSION.txt` 标记 commit：`48384d9`

### 10.1 不能简单理解为“空表所以更慢”

更准确的理解是：

- `写入记录 = 0` 只代表本次没有行被写入或更新到镜像表。
- 它不代表没有执行源库连接、白名单解析、表任务规划、逐表 `max(updated_at)` 探测、任务状态更新、日志汇总、事实刷新触发或镜像侧对账。
- 对 20 张表而言，即使每张表只有一次很轻的源库探测，也会累积固定成本；如果是远程内网数据库，还会叠加网络 RTT、连接池等待和源库执行计划成本。
- 有数据的同步可能只命中少量活跃表，且批量 upsert 很快；空写入的 20 表扫描反而可能花在“确认没有变化”上。

所以“空表比有数据慢”不是一个可靠结论。可靠结论是：**当前日志的写入数口径不足以解释同步真实工作量**。

### 10.2 旧包与当前版本的关键差异

旧包 `app.jar` 中的迁移只到：

- `V20260519_01__gitlab_sync_compensation_default.sql`
- `V20260519_02__disable_all_table_whitelist_mode.sql`

旧包中不存在当前版本新增的：

- `V20260522_01__full_compensation_schedule_config.sql`

当前版本新增了：

- `gitlab_sync_configs.full_compensation_enabled`
- `gitlab_sync_configs.full_compensation_time`
- 每日全量补偿对账调度入口
- `FULL_COMPENSATION_SCAN` 的用户侧入口和日志语义

通过反编译旧包 `GitlabDailyVerificationScheduler` 可确认：

- 旧包每日校验调度提交的是 `SyncRunType.COMPENSATION_SCAN`
- reason 为 `Daily verification scan`

当前源码中：

- `GitlabDailyVerificationScheduler` 调用 `submissionService.submitFullCompensationSync(...)`
- 实际提交 `SyncRunType.FULL_COMPENSATION_SCAN`
- `SyncRunTablePlanningService.rowStrategyForTask` 对 `FULL_COMPENSATION_SCAN` 使用 `FULL_RECONCILE`

这说明 2026-05-21 的“1 秒版本”和当前版本的每日校验/补偿链路很可能已经不是同一类任务。

### 10.3 为什么过去能 1 秒，现在不一定能

目前最强假设：

1. **过去的 1 秒是普通补偿扫描**
   - 旧包每日校验提交 `COMPENSATION_SCAN`。
   - 普通补偿更偏向按 `updated_at` 水位确认增量变化。
   - 无变化时可能很快结束。

2. **当前慢的可能是全量补偿对账**
   - 5/22 后新增 `FULL_COMPENSATION_SCAN`。
   - 它不是普通“看看有没有新数据”，而是为了发现镜像缺失、多余、字段漂移等问题。
   - 即使写入 0，也可能要全表分批扫描源库，甚至扫描镜像侧 active primary key 做删除/差异对账。

3. **表数和白名单范围也可能变了**
   - 旧包迁移 `V20260519_02` 会把 `ALL` 改回 `RECOMMENDED`。
   - 当前如果白名单实际覆盖 20 张表，则固定探测成本比只覆盖少量核心表更高。

4. **调度与日志口径可能制造错觉**
   - 日志展示的“耗时”只看 `startedAt -> finishedAt`。
   - “写入记录”只看 `appliedRows`。
   - 没有展示 `scannedRows`、跳过表、排队时间、源库探测时间、对账删除数。

### 10.4 下一步不改代码的验证办法

在内网环境先查最近慢日志到底是哪类 run：

```sql
select id,
       run_id,
       source_instance,
       run_type,
       trigger_type,
       status,
       planned_table_count,
       completed_table_count,
       scanned_rows,
       applied_rows,
       created_at,
       started_at,
       finished_at,
       extract(epoch from (finished_at - started_at)) as run_seconds,
       request_reason,
       error_message
  from sync_runs
 where source_instance in ('cc', 'smoke_cc', 'default')
 order by id desc
 limit 30;
```

如果慢的是 `FULL_COMPENSATION_SCAN`，那它不能拿 2026-05-21 旧包的 `COMPENSATION_SCAN` 1 秒表现直接对标。

继续看表级任务：

```sql
select source_table,
       row_strategy,
       status,
       rows_scanned,
       rows_applied,
       started_at,
       finished_at,
       extract(epoch from (finished_at - started_at)) as task_seconds,
       last_error
  from sync_run_table_tasks
 where run_id = :run_id
 order by task_seconds desc nulls last, id;
```

判断规则：

- `row_strategy = INCREMENTAL`：普通补偿/增量路径，理论上无变化应较快。
- `row_strategy = FULL_RECONCILE`：全量补偿对账路径，0 写入但耗时增加是合理可能。
- 如果 `rows_scanned > 0, rows_applied = 0`：说明扫描确认了很多数据但没有写入。
- 如果某几张表 `task_seconds` 明显更高：瓶颈在源库查询或镜像侧对账。
- 如果所有表都很平均但总耗时高：瓶颈可能是每表固定开销、网络 RTT、连接池或线程数。

### 10.5 当前判断

暂不建议基于“空表比有数据慢”做修复。

应先确认慢日志的 `run_type` 和 `row_strategy`：

- 如果是 `FULL_COMPENSATION_SCAN / FULL_RECONCILE`，优先调整用户预期、执行窗口、日志指标和限速/分批策略。
- 如果是普通 `COMPENSATION_SCAN / INCREMENTAL` 仍明显变慢，再继续排查源库探测、白名单表数、dispatcher、线程预算、连接池和最近改动。

## 11. 是否退回过去的轻量任务

### 11.1 产品目标重新定位

当前产品目标不是“每天做一次数据校验”这么简单，而是：

- System Hook 表现不够稳定时，定时任务要作为 Issue/MR 类数据的准实时兜底。
- 用户首次访问数据展示类页面时，系统应尽快补偿刷新，避免页面展示过旧数据。
- 除首次全量同步外，其他同步策略都应优先追求准确性和效率。

在这个目标下，补偿能力应拆成两类：

1. **准实时补偿**
   - 面向用户正在看的页面和核心 Issue/MR 数据。
   - 目标是低延迟、低源库压力、可频繁运行。
   - 应使用 `COMPENSATION_SCAN / INCREMENTAL` 或按页面表集的 `TABLE_REFRESH`。

2. **完整巡检/对账**
   - 面向发现镜像缺失、误删、长期漂移和历史修复。
   - 目标是完整性，不应抢占交互链路。
   - 应使用 `FULL_COMPENSATION_SCAN / FULL_RECONCILE`，但降低频率、错峰执行，并在日志中明确标识。

因此不建议把所有机制简单退回旧版本，也不建议让高频定时任务跑全量对账。更稳妥的方向是：**恢复轻量补偿作为实时链路，把全量补偿降级为低频巡检链路**。

### 11.2 当前代码链路判断

已确认当前代码存在三条不同链路：

1. `GitlabCompensationScheduler`
   - 周期性检查 `compensationIntervalMinutes`。
   - 提交 `SyncRunType.COMPENSATION_SCAN`。
   - 表任务策略为 `INCREMENTAL`。
   - 更适合作为准实时兜底。

2. 数据展示页“刷新最新数据”
   - `StatisticBoardView` 触发 `refreshStatisticBoardRealtime`。
   - Issue 类看板通过 `IssueFactBoardRuntimeSupport` 调用 `refreshTablesOnDemandDetailed(realtimeRefreshTables, boardKey)`。
   - 实际提交 `TABLE_REFRESH`，只刷新页面声明的核心表，例如 `issues/projects/users/label_links/labels/notes`。
   - 这是更精确的页面级刷新路径，原则上不应退回全表补偿。

3. `GitlabDailyVerificationScheduler`
   - 5/22 后提交 `submitFullCompensationSync(...)`。
   - 实际为 `FULL_COMPENSATION_SCAN`。
   - 表任务策略为 `FULL_RECONCILE`。
   - 更像低频全量对账，不适合作为用户感知的实时更新路径。

旧内网包的每日校验提交的是 `COMPENSATION_SCAN`，所以旧版本“1 秒”表现更接近轻量补偿链路，而不是当前的全量对账链路。

### 11.3 是否退回的建议

建议采用“部分退回 + 分层保留”，而不是整体退回：

1. **保留当前页面级 `TABLE_REFRESH`**
   - 它比普通全表补偿更符合“用户打开某个看板时刷新相关数据”的目标。
   - 重点应验证它是否真的只刷新必要表，并且事实构建只做必要范围。

2. **保留周期性 `COMPENSATION_SCAN` 作为高频准实时兜底**
   - 如果需要更接近实时，可以调低 `compensationIntervalMinutes`，但要配合去重、冷却时间和日志可观测性。
   - 不建议把它替换成 `FULL_COMPENSATION_SCAN`。

3. **把 `FULL_COMPENSATION_SCAN` 从实时路径中剥离**
   - 只用于每日/每周低峰全量对账。
   - 默认时间应放在业务低峰。
   - 如果内网环境更重视实时体验，可以考虑默认关闭 `full_compensation_enabled`，由管理员显式开启。

4. **不要完全删除全量补偿**
   - System Hook 和轻量增量都依赖 `updated_at`、主键、水位和白名单正确。
   - 如果源表存在历史修复、删除、字段漂移、漏同步，全量补偿仍是兜底能力。
   - 删除它会降低长期数据可信度。

### 11.4 推荐的同步策略矩阵

| 场景 | 推荐 run type | 表范围 | 频率 | 用户预期 |
| --- | --- | --- | --- | --- |
| 用户点击“刷新最新数据” | `TABLE_REFRESH` | 页面声明的核心表 | 按需，带 15 秒冷却 | 秒级到数秒 |
| 用户首次进入数据展示页自动兜底 | `TABLE_REFRESH` 或轻量 `COMPENSATION_SCAN` | 页面核心表优先 | 按页面/工作区冷却 | 不阻塞首屏，完成后提示 |
| 后台准实时兜底 | `COMPENSATION_SCAN` | 推荐白名单核心表 | 分钟级 | 快速确认是否有新增/更新 |
| 手动增量同步 | `INCREMENTAL_SYNC` | 白名单表 | 管理员触发 | 快速补齐近期变化 |
| 手动全量同步 | `FULL_SYNC` | 全部白名单表 | 管理员触发 | 慢但完整 |
| 每日/每周巡检 | `FULL_COMPENSATION_SCAN` | 全部白名单表 | 低峰低频 | 发现漂移，不承诺 1 秒 |

### 11.5 退回轻量任务的边界条件

可以退回或偏向旧轻量任务的条件：

- 目标是 Issue/MR 页面准实时更新。
- 只关心新增/修改，不要求本次发现历史删除或镜像漂移。
- 数据源表都有稳定主键和 `updated_at`。
- 业务允许通过每日/每周低峰任务补充完整对账。

不应该退回轻量任务的条件：

- 要发现源库删除后本地镜像未删除。
- 要验证镜像侧是否丢历史行。
- 要处理 `updated_at` 不可靠或历史回填的表。
- 要给管理员提供“我现在就要确认镜像完整性”的按钮。

### 11.6 建议后续修改方向

后续如果进入实现阶段，建议按以下顺序做：

1. **配置语义拆分**
   - 将“自动补偿扫描间隔”明确为轻量准实时补偿。
   - 将“全量补偿对账时间”明确为低频巡检。
   - UI 文案避免把两者都叫“补偿扫描”。

2. **默认策略调整**
   - 高频自动任务默认使用 `COMPENSATION_SCAN`。
   - `full_compensation_enabled` 的默认值需要重新评估；生产默认开启可能合理，但内网交互测试环境默认关闭更符合体验目标。
   - 如果继续默认开启，应保证它只在低峰运行，且日志明确显示“全量对账”。

3. **页面首次访问策略**
   - 优先使用页面级 `TABLE_REFRESH`，并保持工作区 15 秒冷却。
   - 不应在首次访问时触发全量补偿。
   - 首屏加载与后台刷新解耦：先展示当前可用数据，再异步刷新和提示状态。

4. **优先级和互斥策略**
   - 当前 `FULL_COMPENSATION_SCAN` 优先级 25，`COMPENSATION_SCAN` 为 20，`TABLE_REFRESH` 为 40。
   - 当全量对账正在运行时，页面级刷新会复用/等待同一 mirror scope，可能影响用户感知。
   - 后续应考虑全量对账可让路，或至少在 UI 上显示“正在全量对账，页面刷新已合并/排队”。

5. **可观测性先行**
   - 在真正调参前，先让日志区分 `TABLE_REFRESH`、`COMPENSATION_SCAN`、`FULL_COMPENSATION_SCAN`。
   - 展示表范围、扫描行数、写入行数、跳过表、排队耗时、执行耗时。
   - 否则很容易再次出现“功能正常，但用户体验变了”的问题。

### 11.7 当前决策建议

建议结论：

- **要恢复过去轻量任务在准实时链路中的地位。**
- **不要整体回滚到旧实现。**
- **保留全量补偿，但把它从用户感知的实时链路中移走，作为低频完整性巡检。**

这既符合你对准确性和效率的要求，也保留了生产系统长期运行所需的兜底能力。

## 12. 同步策略收敛与增量替代 System Hook 的重新评估

### 12.1 新输入

新增判断前提：

- 当前同步策略对使用者和维护者都偏多：全量、增量、页面级刷新、自动补偿、全量补偿、System Hook、重试、事实刷新等概念同时存在。
- System Hook 的实际表现不理想，并且 GitLab System Hook 的 Issue 触发器能力存在缺口。
- 内网测试显示增量更新对服务器压力和耗时表现都很好。
- 短期内没有足够时间彻底重构策略，但希望方案朝“更少策略、更快耗时、更可靠兜底”的方向演进。
- 目标耗时希望恢复到旧版 1 秒，甚至低于 1 秒。

### 12.2 重新评估后的策略方向

建议将同步策略从“多入口并列”收敛成三层：

1. **基础层：首次全量同步**
   - 只用于初始化基线和管理员明确要求的重建。
   - 保留 `FULL_SYNC`。

2. **实时层：轻量增量同步**
   - 用于页面刷新、首次访问兜底、后台定时兜底。
   - 主路径使用 `INCREMENTAL_SYNC`、`TABLE_REFRESH`、`COMPENSATION_SCAN / INCREMENTAL`。
   - System Hook 不再作为核心可靠性依赖。

3. **巡检层：低频完整性对账**
   - 用于发现历史漏行、删除漂移、全量一致性问题。
   - 保留 `FULL_COMPENSATION_SCAN / FULL_RECONCILE`，但从用户实时路径中移走。

短期不建议立即删除 System Hook 代码，因为它牵涉接口、配置、注册状态、事件表、诊断、前端设置页和测试。但可以先把它产品地位降级：

- 默认不推荐启用。
- 不作为 Issue 类数据实时性的主要承诺。
- UI 文案从“System Hook 唤醒”调整为“可选事件唤醒/实验能力”。
- 后续有时间时再做软删除和迁移清理。

### 12.3 增量是否可以完全替代 System Hook

从当前目标看，**可以把增量作为主路径替代 System Hook 的业务地位**，但不建议马上物理删除 System Hook。

可以替代的理由：

- Issue 事件触发器缺失时，System Hook 对最核心业务对象的实时性无法闭环。
- 直连 GitLab PostgreSQL 后，增量扫描可以直接基于源库事实状态判断，不依赖 GitLab 事件是否完整送达。
- 增量同步天然能覆盖 System Hook 漏发、网络抖动、事件合并失败等问题。
- 当前实现里 System Hook 也最终只是提交统一 `SyncRunType.SYSTEM_HOOK`，再走同一套镜像表任务执行器；它不是一条完全不同的数据通道。

仍需保留一点谨慎：

- 增量依赖每张表的 `updated_at` 或等价水位字段。
- 如果某些表没有可靠更新时间，System Hook 精确回读或全量对账仍有补充价值。
- System Hook 对极少量对象精确刷新理论上可以更省，但前提是事件类型完整且 payload 可定位；当前 Issue 缺口削弱了这个优势。

因此推荐结论：

- **短期：功能上弱化 System Hook，默认走增量/页面级刷新。**
- **中期：把 System Hook 从主设置流程中隐藏或标记为高级/实验。**
- **长期：确认 1-2 个版本无依赖后，再删除 System Hook 入口、注册、事件表和相关 UI。**

### 12.4 当前策略与旧版策略的主要不同

旧版内网包更接近以下模型：

- 每日校验提交普通 `COMPENSATION_SCAN`。
- 普通补偿使用 `INCREMENTAL` 表任务策略。
- 未引入 `full_compensation_enabled/full_compensation_time`。
- 没有把每日校验升级为 `FULL_COMPENSATION_SCAN / FULL_RECONCILE`。
- 因此 0 写入、无变化场景主要成本是水位探测和少量表任务状态更新，容易出现 1 秒级结果。

当前版本的变化：

- 新增 `FULL_COMPENSATION_SCAN`。
- 新增 `full_compensation_enabled`，迁移默认值为 `true`。
- `GitlabDailyVerificationScheduler` 提交 `submitFullCompensationSync(...)`。
- `SyncRunTablePlanningService.rowStrategyForTask` 对 `FULL_COMPENSATION_SCAN` 使用 `FULL_RECONCILE`。
- System Hook 进入统一 run orchestrator，和增量/补偿共享同一个 mirror exclusive scope。
- 页面级“刷新最新数据”走 `TABLE_REFRESH`，但如果同一 source scope 已有全量对账或增量任务运行，会复用、合并或等待现有任务。
- `SyncRunDispatcherService` 默认 2 秒派发一次 queued run；如果按用户从点击到完成的墙钟时间计算，它天然不稳定地接近 0-2 秒起步。

### 12.5 当前耗时变长的最可能原因

按可能性排序：

1. **任务类型变重**
   - 旧版每日校验是 `COMPENSATION_SCAN / INCREMENTAL`。
   - 当前每日全量补偿是 `FULL_COMPENSATION_SCAN / FULL_RECONCILE`。
   - 这会把“确认最近是否有变化”变成“做完整对账”，即使写入 0 也可能扫描更多内容。

2. **全量补偿默认开启**
   - `V20260522_01__full_compensation_schedule_config.sql` 将 `full_compensation_enabled` 默认设为 `true`。
   - 如果内网环境没有手动关闭，它会自动进入每日全量对账路径。

3. **策略入口变多导致互斥等待**
   - System Hook、页面级刷新、自动补偿、全量补偿都使用同一个 mirror exclusive scope。
   - 一旦全量对账在运行，页面级刷新或增量会被复用/等待/合并，用户会感觉“刷新变慢”。

4. **白名单表范围扩大**
   - 当前如果实际规划 20 张表，0 写入也要逐表规划和探测。
   - 旧版如果表范围更小，固定成本自然更低。

5. **调度口径影响 1 秒目标**
   - run 执行耗时 `startedAt -> finishedAt` 可以是 1 秒。
   - 用户感知耗时 `提交 -> dispatcher claim -> 执行 -> UI 轮询看到完成` 可能超过 1 秒。
   - 当前 dispatcher 默认 2 秒轮询，前端等待状态也按 1 秒轮询最多 8 次；如果追求亚秒级，需要专门优化唤醒和状态回传。

### 12.6 面向 1 秒甚至亚秒的建议

如果 1 秒是明确目标，建议不要用全量补偿作为实时链路，而是改成以下目标模型：

1. **实时链路只跑轻量增量**
   - 页面刷新：`TABLE_REFRESH`，只刷页面依赖表。
   - 后台兜底：`COMPENSATION_SCAN`，只刷核心推荐表。
   - 手动刷新最新数据：优先 `TABLE_REFRESH` 或 `INCREMENTAL_SYNC`，不触发全量补偿。

2. **核心表分层**
   - Issue 页面核心：`issues`、`notes`、`label_links`、`labels`、`projects`、`users`。
   - MR 页面核心：`merge_requests`、`merge_request_metrics`、`merge_request_assignees`、`merge_request_reviewers`、`notes`、`label_links`、`projects`、`users`。
   - 低频辅助表不进入高频补偿。

3. **关闭实时路径中的全量对账**
   - `FULL_COMPENSATION_SCAN` 只保留低峰运行。
   - 内网测试环境可默认关闭 `full_compensation_enabled`，需要时管理员手动触发。

4. **减少异步调度等待**
   - 当前 `run-dispatcher-delay-ms` 默认 2000ms，不适合亚秒级用户感知目标。
   - 短期可评估降低到 500ms，但要观察 DB 轮询压力。
   - 更稳的长期方案是提交 run 后主动唤醒 dispatcher，而不是靠固定轮询。

5. **页面刷新不等待完整对账**
   - 首屏先展示当前数据。
   - 后台轻量刷新完成后再更新“同步状态/最后同步时间”。
   - 不因为低频全量对账阻塞页面级刷新。

### 12.7 短期建议

在不大改代码的前提下，建议短期策略是：

- 关闭或低频化 `full_compensation_enabled`，至少不要让它影响白天交互。
- 保留 `COMPENSATION_SCAN`，把它作为 System Hook 的替代主力。
- 页面级刷新继续使用 `TABLE_REFRESH`，但严格控制表范围。
- System Hook 暂时不删除，只降级为可选能力。
- 日志 UI 明确区分“轻量增量”和“全量对账”，避免把全量对账的耗时误认为增量退化。

### 12.8 需要验证的关键 SQL

如果要证明“现在慢是策略变重导致的”，优先看最近慢任务的 `run_type` 和表任务策略：

```sql
select id,
       run_id,
       run_type,
       trigger_type,
       planned_table_count,
       completed_table_count,
       scanned_rows,
       applied_rows,
       request_reason,
       started_at,
       finished_at,
       extract(epoch from (finished_at - started_at)) as run_seconds
  from sync_runs
 order by id desc
 limit 50;
```

```sql
select run_id,
       source_table,
       row_strategy,
       rows_scanned,
       rows_applied,
       extract(epoch from (finished_at - started_at)) as task_seconds
  from sync_run_table_tasks
 where run_id = :run_id
 order by task_seconds desc nulls last, source_table;
```

判断方式：

- 大量慢记录是 `FULL_COMPENSATION_SCAN` + `FULL_RECONCILE`：策略变重是主因。
- 慢记录是 `COMPENSATION_SCAN` + `INCREMENTAL`：继续查表范围、源库探测、连接池和 dispatcher。
- 慢记录是 `TABLE_REFRESH`，但被全量对账占用同一 scope：需要处理互斥/让路。
- run 本身小于 1 秒，但用户看到超过 1 秒：重点查 dispatcher delay 和前端轮询。

## 13. 本地运行环境实测补充

### 13.1 运行环境确认

本地当前实际运行：

- 后端监听 `18080`，命令为 `java -jar backend/target/qa-flex-platform-backend-0.0.1-SNAPSHOT.jar`。
- 平台库 `localhost:15432` 通过 `dcp-platform-db-15432-proxy` 代理到 `localhost:15433`。
- 平台库容器为 `dcp-local-test-postgres-1`，数据库 `qaflex`。
- GitLab cc 源库通过 `localhost:15434` 访问，代理容器为 `qaflex-gitlab-cc-pg-proxy`。
- 当前 `cc` 配置为 DIRECT，`auto_sync_enabled=true`，`compensation_interval_minutes=360`，`full_compensation_enabled=true`，`full_compensation_time=02:00`。

注意：当前本地配置并不是 1-3 分钟自动补偿，而是 360 分钟；所有源的全量补偿开关均为 true。

### 13.2 10 秒记录不是全量补偿

本地最近慢记录：

| run id | source | run type | trigger | planned tables | scanned | applied | run seconds |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 317 | cc | `COMPENSATION_SCAN` | `SCHEDULE` | 20 | 0 | 0 | 10.173 |
| 313 | cc | `COMPENSATION_SCAN` | `SCHEDULE` | 20 | 0 | 0 | 11.047 |
| 316 | cc | `COMPENSATION_SCAN` | `SCHEDULE` | 20 | 0 | 0 | 3.208 |
| 312 | cc | `COMPENSATION_SCAN` | `SCHEDULE` | 20 | 0 | 0 | 3.392 |
| 307 | cc | `COMPENSATION_SCAN` | `SCHEDULE` | 20 | 0 | 0 | 3.121 |

结论：

- 这批 10 秒不是 `FULL_COMPENSATION_SCAN`。
- 表任务的 `row_strategy` 都是 `INCREMENTAL`。
- 因此“10 秒就是全量补偿导致的”这个假设在本地实测中被排除。
- 真正需要优化的是轻量 `COMPENSATION_SCAN` 的 20 表无变化探测路径。

### 13.3 与 5/19 的 1 秒记录对比

5/19 的 `cc` 自动补偿记录：

| run id | run type | planned tables | run seconds |
| --- | --- | --- | --- |
| 252 | `COMPENSATION_SCAN` | 20 | 1.453 |
| 250 | `COMPENSATION_SCAN` | 20 | 1.450 |
| 248 | `COMPENSATION_SCAN` | 20 | 1.297 |
| 246 | `COMPENSATION_SCAN` | 20 | 1.491 |
| 244 | `COMPENSATION_SCAN` | 20 | 1.430 |

关键点：

- 5/19 和 5/27 之后都是 20 张表。
- 都是 `COMPENSATION_SCAN / INCREMENTAL`。
- 因此耗时变长不是因为表数量从少变多，也不是因为全量对账替换了这条自动补偿链路。
- 差异集中在每张表的源库探测耗时：5/19 单表多为 `0.06-0.25s`，5/27 之后常见 `0.4-1.6s`，个别达到 `4.29s`。

### 13.4 当前轻量补偿为什么会 0 写入但慢

代码路径确认：

- `SyncRunTableTaskExecutor` 对 `INCREMENTAL` 任务，在没有 cursor 时先调用 `sourceTableReader.findMaxUpdatedAt(config, option)`。
- `GitlabExternalDbService.findMaxUpdatedAt` 生成 SQL：

```sql
select max(updated_at) as max_updated_at
  from public.<source_table>;
```

- 如果源库最大 `updated_at` 没有超过本地水位，就直接把表任务标记成功，`rows_scanned=0`、`rows_applied=0`。

所以 `0 写入 / 0 扫描` 的 10 秒，主要花在 20 张表的 `max(updated_at)` 探测、连接池等待、源库执行和任务状态更新上。

### 13.5 源库直接探测结果

在 GitLab cc 源库上直接执行 `explain analyze`，可见：

- `notes` 的 `select max(updated_at)` 走顺序扫描，约 `90ms`，表约 5 万行。
- `events` 的 `select max(updated_at)` 走顺序扫描，约 `12ms`，表约 1 万行。
- `issues` 有 `index_issues_on_updated_at`，但冷缓存下规划和索引读取也可出现几十毫秒级。
- `notes/events/labels` 等表没有单列 `updated_at` 索引。

这说明源库侧 `max(updated_at)` 不是稳定的零成本操作。缓存冷、容器负载、并发探测、缺少索引时，20 张表的固定成本会放大。

### 13.6 另一个强嫌疑：同步线程数与外部连接池不匹配

当前 `cc` 配置：

- `sync_thread_mode=CPU_RATIO`
- `sync_thread_value=0.8`
- `max_sync_threads=16`

当前 DIRECT 外部源库连接池：

- `GitlabDirectJdbcExecutor.createDirectDataSource`
- `hikariConfig.setMaximumPoolSize(2)`

这意味着表任务可能以多线程并发启动，但真正访问 GitLab 源库的 JDBC 连接最多只有 2 条。20 张表同时排队做 `max(updated_at)` 时，任务耗时里会混入连接池等待时间。

这与实测现象吻合：

- 源库单条 SQL 直接执行通常是几十毫秒到百毫秒。
- 表任务记录却可能显示 `0.4-1.6s`，慢 run 里 `notes` 达到 `4.29s`。
- 说明任务耗时不只是 SQL 执行，还包含等待外部连接、线程调度、本地状态更新和源库冷缓存。

### 13.7 当前更准确的慢因排序

基于本地实测，慢因排序应调整为：

1. **轻量补偿的 20 表 `max(updated_at)` 探测固定成本变高**
   - 不是全量补偿。
   - 不是写入慢。

2. **外部 DIRECT 连接池最大 2，与表任务并发不匹配**
   - 多线程启动后可能集中等待 2 条外部连接。

3. **部分 GitLab 源表缺少适合 `max(updated_at)` 的索引**
   - `notes/events/labels` 等表可能顺序扫描。
   - 数据量增长或缓存冷后耗时抖动明显。

4. **dispatcher 2 秒轮询影响用户感知耗时**
   - run 执行 1 秒，不代表用户从触发到看到完成一定 1 秒。

5. **全量补偿仍需从实时链路隔离**
   - 但本地这批 10 秒记录不是它导致的。

### 13.8 更新后的优化建议

如果目标是恢复 1 秒甚至亚秒级，应优先考虑：

1. **轻量补偿只扫描核心表**
   - 高频自动补偿不要默认覆盖全部 20 张推荐表。
   - Issue 类实时链路优先：`issues`、`notes`、`label_links`、`labels`、`projects`、`users`。
   - MR 类实时链路再加入 `merge_requests`、`merge_request_metrics`、`merge_request_assignees`、`merge_request_reviewers`。

2. **批量水位探测**
   - 不要每张表单独开一次查询。
   - 可以将核心表的 `max(updated_at)` 探测合并为一次或少量查询返回。
   - 这样可以减少连接池等待和 SQL 往返。

3. **外部连接池与同步线程预算对齐**
   - 如果表任务线程是 8-16，外部连接池只有 2，会造成排队。
   - 可选择降低轻量补偿 worker 数到 2，或提高外部连接池到一个受控值。
   - 对本地 GitLab 源库，过高并发不一定更快，建议先测 2、4、8 三档。

4. **为核心源表水位探测补索引或改查法**
   - 对源库可控时，为高频表补 `updated_at` 索引。
   - 对 GitLab 内置表不建议随意改 schema，除非明确接受维护成本。
   - 更保守方案是在平台侧缓存源水位，避免每次所有表都探测。

5. **日志拆分等待与执行**
   - 表任务耗时需要区分：等待外部连接、源库 SQL、写入、状态更新。
   - 否则无法判断是数据库慢还是连接池排队。

## 14. 2026-06-01 手动验证记录

### 14.1 验证目标

验证目标：

- 不改业务代码，只通过本地测试库配置和手动提交 run 验证。
- 判断轻量 `COMPENSATION_SCAN` 是否能回到过去 1 秒左右。
- 验证“只启用一个数据源”是否明显影响耗时。
- 验证线程数与外部连接池匹配是否影响稳定性。
- 验证页面核心表刷新是否能达到亚秒级。

测试方式：

- 直接向 `sync_runs` 插入 `QUEUED` run，让当前运行中的后端 dispatcher 执行。
- 这样会绕开等待 scheduler 到期，但仍走真实的 dispatcher、planning、table worker、source reader、mirror writer、run completion 链路。
- 每次记录 `queue_seconds = started_at - created_at` 和 `run_seconds = finished_at - started_at`。
- 测试结束后恢复 `gitlab_sync_configs` 到测试前状态。

### 14.2 测试前配置

测试前本地配置：

| id | source | source enabled | auto sync | full compensation | interval | thread mode | thread value | max threads |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | default | true | false | true | 720 | FIXED | 2 | 16 |
| 2 | cc | true | true | true | 360 | CPU_RATIO | 0.8 | 16 |
| 3 | dgm | true | false | true | 360 | FIXED | 4 | 16 |
| 4 | smoke_cc | true | false | true | 60 | FIXED | 1 | 1 |
| 5 | smoke_dgm | true | false | true | 60 | FIXED | 1 | 1 |

说明：

- 本地目前只有 `cc` 开启自动同步。
- 其它数据源虽然 `source_enabled=true`，但 `auto_sync_enabled=false`。
- 因此“多数据源启用”不会直接导致多个自动补偿同时跑，但会影响配置页/健康检查/全量补偿等运维面。

### 14.3 基线：当前配置不变

手动提交 3 次 `COMPENSATION_SCAN`：

| run id | run type | tables | scanned | applied | queue seconds | run seconds |
| --- | --- | --- | --- | --- | --- | --- |
| 318 | `COMPENSATION_SCAN` | 20 | 0 | 0 | 0.752 | 1.719 |
| 319 | `COMPENSATION_SCAN` | 20 | 0 | 0 | 1.974 | 0.430 |
| 320 | `COMPENSATION_SCAN` | 20 | 0 | 0 | 1.893 | 0.586 |

结论：

- 当前配置下，轻量补偿可以回到 1 秒级，甚至低于 1 秒。
- 10 秒不是稳定复现的必然路径。
- `queue_seconds` 仍接近 dispatcher 的 2 秒轮询上限，这会影响用户感知耗时。

### 14.4 只启用一个数据源

临时操作：

- 将除 `cc` 之外的数据源 `source_enabled=false`、`auto_sync_enabled=false`、`full_compensation_enabled=false`。
- `cc` 保持启用。

手动提交 3 次 `COMPENSATION_SCAN`：

| run id | run type | tables | scanned | applied | queue seconds | run seconds |
| --- | --- | --- | --- | --- | --- | --- |
| 321 | `COMPENSATION_SCAN` | 20 | 0 | 0 | 1.844 | 1.318 |
| 322 | `COMPENSATION_SCAN` | 20 | 0 | 0 | 1.746 | 0.355 |
| 323 | `COMPENSATION_SCAN` | 20 | 0 | 0 | 1.646 | 0.449 |

结论：

- 只启用一个数据源后，轻量补偿同样稳定在 1 秒左右。
- 但与基线差异不大，不能证明“多数据源启用”是本地 10 秒的直接主因。
- 你内网 7600+ 写入 1 秒且只连接一个数据源的信息仍有价值，但更可能影响的是减少并发调度、健康检查、全量补偿和误配置风险，而不是单次 `cc` 补偿 run 的核心执行路径。

### 14.5 固定 2 线程

临时操作：

- 将 `cc.sync_thread_mode` 改为 `FIXED`。
- 将 `cc.sync_thread_value` 改为 `2`。
- 这是为了和当前 `GitlabDirectJdbcExecutor` 的外部 Hikari 连接池上限 `2` 对齐。

手动提交 3 次 `COMPENSATION_SCAN`：

| run id | run type | tables | scanned | applied | queue seconds | run seconds |
| --- | --- | --- | --- | --- | --- | --- |
| 324 | `COMPENSATION_SCAN` | 20 | 0 | 0 | 1.944 | 0.666 |
| 325 | `COMPENSATION_SCAN` | 20 | 0 | 0 | 1.853 | 1.000 |
| 326 | `COMPENSATION_SCAN` | 20 | 0 | 0 | 1.748 | 0.822 |

结论：

- 固定 2 线程后表现稳定，但没有明显比当前配置更快。
- 它可能降低连接池等待抖动，但不是恢复 1 秒的必要条件。
- 因为本轮测试已处于热缓存状态，连接池问题不一定会复现 10 秒。

### 14.6 页面核心表刷新

手动提交 3 次 `TABLE_REFRESH`，只刷新 Issue 页面核心表：

- `issues`
- `notes`
- `label_links`
- `labels`
- `projects`
- `users`

结果：

| run id | run type | tables | scanned | applied | queue seconds | run seconds |
| --- | --- | --- | --- | --- | --- | --- |
| 327 | `TABLE_REFRESH` | 6 | 0 | 0 | 0.988 | 0.191 |
| 328 | `TABLE_REFRESH` | 6 | 0 | 0 | 0.907 | 0.280 |
| 329 | `TABLE_REFRESH` | 6 | 0 | 0 | 0.828 | 0.211 |

结论：

- 页面级核心表刷新执行耗时稳定低于 0.3 秒。
- 这条链路最适合用户首次访问或点击“刷新最新数据”。
- 如果目标是用户感知的 1 秒甚至亚秒，应优先保证页面刷新走 `TABLE_REFRESH` 核心表集，而不是 20 表补偿。

### 14.7 手动全表增量

手动提交 1 次 `INCREMENTAL_SYNC`：

| run id | run type | tables | scanned | applied | queue seconds | run seconds |
| --- | --- | --- | --- | --- | --- | --- |
| 330 | `INCREMENTAL_SYNC` | 20 | 0 | 0 | 0.448 | 1.151 |

结论：

- 当前全表增量在无变化场景下也可以达到 1 秒级。
- 这支持“增量替代 System Hook 主路径”的方向。

### 14.8 测试后恢复

已恢复配置：

| id | source | source enabled | auto sync | full compensation | thread mode | thread value | max threads |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | default | true | false | true | FIXED | 2 | 16 |
| 2 | cc | true | true | true | CPU_RATIO | 0.8 | 16 |
| 3 | dgm | true | false | true | FIXED | 4 | 16 |
| 4 | smoke_cc | true | false | true | FIXED | 1 | 1 |
| 5 | smoke_dgm | true | false | true | FIXED | 1 | 1 |

测试 run 记录保留在数据库中，request_reason 分别为：

- `Manual probe baseline current config`
- `Manual probe single source enabled`
- `Manual probe fixed 2 threads`
- `Manual probe core issue tables`
- `Manual probe incremental all tables`

### 14.9 本轮实测结论

1. **可以回到过去的 1 秒级**
   - 当前代码和本地环境下，`COMPENSATION_SCAN` 已经实测回到 `0.43-1.72s`。
   - `INCREMENTAL_SYNC` 为 `1.151s`。
   - 页面核心表 `TABLE_REFRESH` 为 `0.19-0.28s`。

2. **10 秒不是策略必然导致**
   - 因为同一代码、同一表数、同一数据源，在热缓存/负载较低时已经恢复。
   - 10 秒更像是环境抖动：源库冷缓存、GitLab 容器负载、外部连接等待、源表 `max(updated_at)` 顺序扫描、或当时有其它任务/进程占用资源。

3. **“只连接一个数据源”有帮助，但本地不能证明它是主因**
   - 单数据源启用后表现很好，但基线同样很好。
   - 它更像是降低系统复杂度和误触发风险的治理手段，而不是单次 `cc` run 的唯一性能开关。

4. **页面实时性应走核心表 `TABLE_REFRESH`**
   - 这是本轮最快路径。
   - 用户首次访问页面不应触发 20 表补偿，更不应触发全量补偿。

5. **下一步应定位抖动，而不是只回滚策略**
   - 给 `max(updated_at)` 探测增加分阶段耗时日志。
   - 记录外部连接池等待时间或至少将 source query 耗时打点。
   - 区分 dispatcher queue、planning、source probe、write、fact refresh。
   - 观察 GitLab 容器冷缓存和 CPU/IO 峰值。

## 15. 临时分段耗时探针测试

按“只在本地临时验证、不进入正式版本”的要求，临时在以下边界增加 `TEMP_PERF` 日志，并在测试后删除：

- `SyncRunWorkerService`：记录 run 级 `planning`、table task drain、summary 三段耗时。
- `SyncRunTableTaskExecutor`：记录单表 setup、source probe/read、write、mark success、idle 总耗时。
- `GitlabDirectJdbcExecutor`：记录源库查询获取连接、执行与映射、总耗时。

### 15.1 测试操作记录

1. 停止本地正在运行的后端，避免 Windows 锁定 jar。
2. 临时加入 `TEMP_PERF` 分段日志。
3. 使用本地 JDK 21 和 Maven 重新构建后端 jar。
4. 以本地平台库 `127.0.0.1:15432/qaflex` 启动后端。
5. 手动提交 3 类 run：
   - 20 表 `COMPENSATION_SCAN`
   - 再次 20 表 `COMPENSATION_SCAN`
   - 6 表页面核心 `TABLE_REFRESH`
6. 收集日志和 `sync_runs` 数据。
7. 删除所有 `TEMP_PERF` 代码，确认主代码无残留。

### 15.2 分段耗时结果

| run id | run type | tables | queue seconds | run seconds | planning | table drain | summary | total |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 331 | `COMPENSATION_SCAN` | 20 | 0.775 | 1.998 | 1267 ms | 692 ms | 4 ms | 1978 ms |
| 332 | `COMPENSATION_SCAN` | 20 | 0.523 | 0.693 | 246 ms | 419 ms | 3 ms | 681 ms |
| 333 | `TABLE_REFRESH` | 6 | 0.453 | 0.265 | 39 ms | 200 ms | 3 ms | 252 ms |

### 15.3 关键发现

1. **20 表补偿的冷路径主要慢在 planning**
   - run 331 的 planning 为 `1267 ms`，超过 table task drain 的 `692 ms`。
   - 日志显示 planning 阶段会做源库元数据发现，例如主键发现、列元数据发现。
   - 一次主键发现查询出现过约 `771 ms`，列元数据发现约 `167 ms`。

2. **热路径可以回到亚秒**
   - run 332 同样是 20 表 `COMPENSATION_SCAN`，总执行约 `0.693s`。
   - 这说明当前代码路径本身没有必然退化到 10 秒。

3. **页面核心刷新仍然非常快**
   - run 333 的 6 表 `TABLE_REFRESH` 总执行约 `0.265s`。
   - 用户首次访问数据展示页或点击“刷新最新数据”，应优先走页面相关表刷新。

4. **10 秒抖动的更具体嫌疑**
   - 源库元数据发现冷启动或缓存失效。
   - 源库查询获取连接等待。
   - 20 表并发探测时，外部 JDBC 连接池与表任务线程数不匹配。
   - GitLab PostgreSQL 容器冷缓存、CPU/IO 峰值或同时有其他任务。

### 15.4 对方案的修正

之前“给 `max(updated_at)` 探测增加分阶段耗时日志”的判断仍然成立，但这次临时探针说明还要把 planning 阶段纳入一等观测对象。否则只看表级 probe，会漏掉 20 表补偿启动前的源库元数据发现成本。

后续正式改造建议：

1. 保持 `COMPENSATION_SCAN` 作为 1-3 分钟自动同步主路径。
2. 高频补偿不要每次重复做昂贵的源库元数据发现；优先复用白名单表结构、主键、列信息，或给 metadata discovery 加 TTL 缓存。
3. 在正式日志中增加 run 级分段耗时，但不要保留临时 `TEMP_PERF` 文案：
   - queue wait
   - planning
   - source metadata discovery
   - table probe/read
   - write
   - fact refresh
   - summary
4. 将页面首次访问/刷新最新数据固定为页面核心 `TABLE_REFRESH`，不要走 20 表补偿。
5. 将外部 JDBC 连接池大小与同步 worker 并发做显式匹配，避免 16 个表任务挤 2 条源库连接。

### 15.5 清理确认

临时探针代码已删除，并用 `rg -n "TEMP_PERF" backend/src/main/java` 确认主代码中无残留。正式版本不包含本节测试用日志。

## 16. 冷启动/慢热启动修复方案

### 16.1 当前根因定位

当前 20 表自动补偿的 planning 链路是：

`SyncRunWorkerService`
-> `SyncRunTablePlanningService.planRunTables`
-> `planWhitelistTables`
-> `GitlabWhitelistService.resolveOptions`
-> `GitlabWhitelistService.listOptionsStrict`
-> `loadAvailableTables`
-> `SourceMetadataInspector.discoverTables`
-> GitLab PostgreSQL 元数据查询

其中 `GitlabWhitelistService` 已有缓存，但存在两个问题：

1. **TTL 写死为 1 分钟**
   - 代码中是 `private static final Duration CACHE_TTL = Duration.ofMinutes(1)`。
   - 如果自动补偿间隔设置为 1-3 分钟，那么 3 分钟场景下几乎每次 planning 都会缓存过期。
   - 这会让“平台长期运行”仍然周期性遇到冷/慢热 metadata discovery。

2. **缓存是单 entry**
   - 当前只有一个 `volatile CacheEntry cacheEntry`。
   - 如果多个数据源配置轮流访问，即使各自源库稳定，也会互相覆盖缓存。
   - 这解释了“只连接一个数据源”为什么可能让表现更稳定：它减少了缓存抖动和策略分支，不一定是同步本身变快。

### 16.2 是否可以通过延长缓存留存时间优化

可以，而且这是适合当前使用场景的优化。

你的生产假设是：

- 平台进程长期运行。
- GitLab 进程长期运行。
- GitLab 表结构不会高频变化。
- 高频任务追求 Issue/MR/comment 数据增量实时性，而不是每分钟重新发现 GitLab schema。

在这个假设下，GitLab 源库的主键、表名、`updated_at` 列这类结构元数据属于低频变化信息。将缓存 TTL 从 1 分钟延长到 30 分钟、2 小时甚至 12 小时，通常不会带来明显服务器压力，反而能减少源库压力。

资源影响判断：

| 影响项 | 判断 |
| --- | --- |
| 应用内存 | 很低。缓存对象主要是表名、主键、`updated_at` 列和推荐标记，几十到几百 KB 级别。 |
| GitLab PostgreSQL 压力 | 降低。缓存越长，越少执行 `pg_class/pg_attribute/pg_index` 元数据查询。 |
| 平台 DB 压力 | 基本不变。缓存命中后仍会创建/更新 run、state、task。 |
| 数据准确性 | 对普通 Issue/MR/comment 增量数据没有直接负面影响，因为数据读取仍按表任务执行。 |
| schema 变化感知 | 会变慢。这是唯一主要风险。GitLab 升级、迁移或源表结构变化后，可能要等 TTL 过期或手动刷新。 |

### 16.3 推荐修复策略

不建议只把 `CACHE_TTL` 从 1 分钟硬改成更长。更稳的方案是分三层处理。

#### P0：自动补偿 planning 快路径

目标：让 1-3 分钟自动补偿不依赖每次源库 schema discovery。

规则：

- 对 `COMPENSATION_SCAN`，如果已有可用 `sync_run_table_states`，优先从状态表规划任务。
- 状态表需要满足：
  - `config_id` 匹配。
  - `source_instance` 匹配。
  - `sync_enabled = true`。
  - 有 `primary_key_columns`。
  - 有 `updated_at_column`。
- 只有以下情况才回退到 `GitlabWhitelistService.resolveOptions(config)`：
  - 当前配置第一次运行，状态表还没有基线。
  - 白名单配置刚保存并被标记为需要刷新。
  - 手动全量同步、全量补偿对账、诊断页面要求重新发现。
  - 状态表记录不完整。

预期效果：

- 高频 `COMPENSATION_SCAN` 的 planning 不再反复查 GitLab 元数据。
- 20 表自动补偿的冷启动抖动明显下降。
- 对数据读取准确性影响小，因为读取数据仍按每张表的水位和源表 probe 执行。

#### P1：元数据缓存固定延长、多 source 隔离

目标：让缓存行为符合生产环境，同时避免继续增加用户可配置项。

最终取值：

- `CACHE_TTL = Duration.ofHours(12)`
- `MAX_CACHE_ENTRIES = 16`

选择 12 小时的原因：

- 现有 `schema-check-interval-minutes` 默认就是 `720` 分钟，12 小时和当前 schema 检查节奏一致。
- 能覆盖 1-3 分钟自动补偿的长期运行场景，避免每轮或隔几轮重新做源库元数据发现。
- GitLab 源库表结构不是分钟级变化对象，12 小时不会影响 Issue/MR/comment 数据增量准确性。
- 不新增配置项，避免设置页面和运维参数继续膨胀。

缓存结构：

- 从单个 `volatile CacheEntry` 改为按 `signature` 分组的 bounded map。
- `signature` 已包含：
  - `source_instance`
  - `source_mode`
  - docker container
  - host/port/db/user
- 多源同时启用时，不互相覆盖缓存。

失效规则：

- 当前第一阶段不新增刷新按钮和配置项。
- 保存数据源连接配置会因为 signature 变化自然命中新 key；旧 key 最多保留到容量淘汰，不再被使用。
- GitLab 升级或迁移后，如果需要立即刷新结构元数据，可以重启平台进程。
- 后续如果实际遇到 schema 变化不重启的场景，再单独增加内部刷新动作，不提前暴露成常规配置项。

#### P2：正式分段耗时观测

目标：以后不再靠临时 `TEMP_PERF` 才知道慢在哪里。

正式日志不要使用临时文案，但应记录这些字段：

- `queue_wait_ms`
- `planning_ms`
- `metadata_discovery_ms`
- `task_drain_ms`
- `source_probe_ms`
- `source_read_ms`
- `write_ms`
- `fact_refresh_ms`
- `summary_ms`
- `cache_hit`
- `cache_signature`

展示规则：

- 最近同步日志可以先只展示总耗时。
- 详情抽屉/诊断接口展示分段耗时。
- 当 `planning_ms > 1000` 或 `metadata_discovery_ms > 500` 时标记为“源元数据发现偏慢”。

### 16.4 为什么不建议只依赖长 TTL

延长 TTL 是有效优化，但不应是唯一优化。

原因：

1. 服务重启后的第一次 run 仍然会冷。
2. TTL 到期后的第一条 run 仍然可能慢。
3. 多数据源轮换时，当前单 entry 缓存会被覆盖。
4. 诊断页面、配置页面、同步 planning 共享同一个缓存语义，容易互相影响。

因此更稳的是：

- 高频补偿优先从 `sync_run_table_states` 规划。
- 元数据发现缓存用于状态缺失、配置变更、诊断、全量任务。
- TTL 延长作为降低冷路径频率的补充。

### 16.5 推荐落地顺序

1. **先做 P0**
   - `COMPENSATION_SCAN` 已有状态时从 `sync_run_table_states` 规划。
   - 这是最直接服务 1 秒级自动补偿目标的改动。

2. **再做 P1**
   - 将 TTL 固定改为 12 小时。
   - 把单 entry 缓存改为按 source signature 隔离，最多保留 16 个 source signature。

3. **最后做 P2**
   - 加正式分段耗时字段。
   - 最近同步日志详情页用这些字段解释“为什么这次慢”。

### 16.6 风险与回滚

主要风险不是服务器压力，而是 schema 变化后的缓存陈旧。

可接受原因：

- GitLab 表结构不是分钟级变化对象。
- 当前业务主要同步固定推荐表。
- 平台已经有 mirror registry 和 state 表，结构信息本来就有持久化缓存性质。

保护措施：

- TTL 固定为 12 小时，和现有 schema 检查节奏一致。
- source signature 包含连接关键信息，连接配置变化后会自然使用新缓存 key。
- 必要时通过重启平台进程清空缓存。
- 全量同步或全量补偿后续可以选择强校验。
- 如果某张表因为缓存陈旧同步失败，任务失败后应提示“刷新源元数据缓存/重新全量同步”。

### 16.7 当前建议结论

建议修改，但不是简单回滚旧版本。

推荐决策：

- 自动补偿继续作为准实时主路径。
- `COMPENSATION_SCAN` 高频场景不再每次做源库元数据发现。
- 元数据缓存 TTL 从 1 分钟固定改为 12 小时，不新增配置项。
- 多数据源场景必须按 source signature 隔离缓存；如果生产只启用一个源，收益更稳定。
- 页面首次访问继续走核心表 `TABLE_REFRESH`，这是目前实测最快路径。

### 16.8 已实施的缓存收敛改动

按“不新增配置项”的决策，已采用最小代码改动：

- `GitlabWhitelistService.CACHE_TTL` 从 `1 分钟` 改为 `12 小时`。
- 缓存从单个 `volatile CacheEntry` 改为按 source signature 隔离的内存 map。
- 最多保留 `16` 个 source signature，避免异常多源场景无限增长。
- 缓存内容改为 `List.copyOf(discovered)`，避免外部修改缓存列表。
- 新增单元测试覆盖：
  - 同一 source signature 在缓存期内复用元数据发现结果。
  - 不同 source signature 不互相覆盖缓存。

验证命令：

```powershell
mvn -f backend\pom.xml -Dtest=GitlabWhitelistServiceTest test
```

结果：`Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`。

### 16.9 已实施的自动补偿 planning 快路径

为避免高频 `COMPENSATION_SCAN` 每次进入白名单/源元数据发现链路，已增加状态表快路径：

- 当 run type 为 `COMPENSATION_SCAN` 且 payload 没有指定表范围时，先读取已有 `sync_run_table_states`。
- 如果状态表中存在可运行的增量表：
  - `config_id` 匹配。
  - `source_instance` 匹配。
  - `sync_enabled = true`。
  - `primary_key_columns` 非空。
  - `updated_at_column` 非空。
- 直接从这些 state 创建 `INCREMENTAL` 表任务。
- 不调用 `GitlabWhitelistService.resolveOptions(config)`，因此不会触发源库 metadata discovery。
- 如果没有可运行 state，则回退原有白名单规划，保证首次运行仍然可用。

验证覆盖：

- `shouldPlanCompensationFromExistingStatesWithoutWhitelistDiscovery`
  - 已有 state 时从状态表规划。
  - 验证不会调用 whitelist discovery。
- `shouldFallBackToWhitelistDiscoveryWhenCompensationHasNoExistingStates`
  - 首次或状态缺失时回退原路径。

验证命令：

```powershell
mvn -f backend\pom.xml "-Dtest=SyncRunTablePlanningServiceTest,GitlabWhitelistServiceTest" test
```

结果：`Tests run: 14, Failures: 0, Errors: 0, Skipped: 0`。

## 17. 同源保护与同步日志滚动条修复进度

### 17.1 同源重复配置后端保护已实施

已在 `GitlabConfigService.saveConfig` 的归一化流程中增加物理源唯一性校验。

实施规则：

- 当前保存对象未启用时允许保存，用于 smoke/test 草稿源。
- 当前保存对象启用时，只和其它已启用数据源比较。
- DIRECT 指纹归一化为：
  - `direct`
  - host trim/lowercase
  - port
  - db name trim/lowercase
  - db username trim/lowercase
- DOCKER 指纹归一化为：
  - `docker`
  - container trim/lowercase
  - db name trim/lowercase
  - db username trim/lowercase
- 如果同物理源中当前配置或已有配置任意一方开启 `autoSyncEnabled`，阻断保存。
- 错误信息包含冲突的 `sourceInstance`，引导用户停用其中一个，或关闭自动同步后作为测试源保存。

这保留了 smoke/minimal 源作为停用测试源的空间，同时阻止两个启用自动同步的数据源把同一批 Issue/MR/comment 写入事实层。

验证覆盖：

- `shouldRejectEnabledAutoSyncWhenAnotherEnabledSourceUsesSameDirectDatabase`
  - DIRECT host/db/user 大小写和空格归一化后仍能识别重复。
  - 冲突源已启用但未自动同步，当前源开启自动同步时仍阻断。
- `shouldAllowSamePhysicalSourceWhenSavedAsDisabledTestSource`
  - 当前源停用且自动同步关闭时允许保存为 smoke/test 源。

### 17.2 最近同步日志横向滚动条已按真实触发路径修复

补充问题现象：

> 展开某一条同步日志后，再收缩，横向滑动条会消失，鼠标移入也不够容易重新浮现。

判断：

- 这不是单纯 hover 样式弱，而是 expand row 改变表格高度/布局后，Element Plus 表格横向滚动条没有及时重算。
- 收起展开行后，内部 scrollbar 的可见状态和表格布局状态可能不同步。

已实施修复：

- `MirrorSyncLogTable` 给 `el-table` 增加 `ref="tableRef"`。
- 监听 `@expand-change="handleExpandChange"`。
- 展开/收起后 `await nextTick()`，主动调用 `tableRef.value?.doLayout?.()`。
- 增加 `is-scrollbar-awake` 临时状态，展开/收起后让横向滚动条保持可见约 `1200ms`。
- hover/focus/awake 状态下，仅增强当前日志表的 Element Plus 横向滚动条：
  - 提高 opacity。
  - 增大横向 bar 高度。
  - 增加 thumb 最小宽度。
  - 底部预留 `10px`，减少贴边难拖。
- 增加 Shift + 鼠标滚轮横向滚动支持。

这个修复保持“自动隐藏”设计，不把滚动条改成永久显示，也不影响其它 Element Plus 表格。

验证覆盖：

- `MirrorSyncLogTable.test.ts`
  - 展开事件后会调用 `doLayout`。
  - 展开事件后会加上 `is-scrollbar-awake`。
  - `1200ms` 后自动恢复隐藏状态。
  - 源码断言保证滚动条增强样式只挂在 `.sync-log-table-shell` 下。

### 17.3 当前验证结果

后端：

```powershell
mvn -f backend\pom.xml "-Dtest=GitlabConfigServiceTest,GitlabWhitelistServiceTest,SyncRunTablePlanningServiceTest" test
```

结果：`Tests run: 33, Failures: 0, Errors: 0, Skipped: 0`。

前端：

```powershell
& 'C:\Program Files\nodejs\npm.cmd' run test -- MirrorSyncLogTable.test.ts
```

结果：`4 passed`。
