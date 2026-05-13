# GitLab 数据同步稳定性与准确性修复方案

日期：2026-05-13

状态：Backend core fixes implemented，待联调和发布前验证

适用范围：GitLab 数据库镜像同步、表级任务调度、补偿扫描、每日校验、页面级手动刷新、事实层刷新和运维诊断。

## 1. 方案定位

主方案是 `docs/plans/2026-05-08-gitlab-sync-production-plan.md`。本方案不是新架构、不是替代方案，而是在主方案已经确定的“四层同步架构”和“表级幂等任务”方向上，补齐当前实现中影响稳定性、准确性和页面级手动刷新的缺口。

本方案必须继承主方案的以下主旨：

- System Hook 降级为 dirty/wakeup 信号，不承担同步正确性。
- 补偿扫描是日常最终一致主机制。
- 每日全量校验是准确性兜底。
- 用户手动刷新只刷新当前页面依赖的数据表，不触发全源重跑。
- 所有同步、校验和事实刷新都应成为可恢复、可重试、可诊断的表级幂等任务。

`docs/plans/2026-05-08-gitlab-sync-phase0-design-freeze.md` 不作为实现基线。Phase 0 设计冻结属于已废弃旧方案，只保留历史参考价值。

本方案与主方案的对应关系：

| 主方案要求 | 当前缺口 | 本方案补强 |
| --- | --- | --- |
| 多源操作必须显式隔离 | 显式 `configId` 不存在时可能 fallback 默认源 | Phase 1 收紧 strict configId |
| 补偿扫描是日常同步主力 | 调度仍参考旧 `gitlab_sync_tasks` 活跃度 | Phase 2 切到表级 state/task/job |
| 表级任务必须可恢复 | 长任务缺少批次 heartbeat 和 lease 续租 | Phase 3 增加 worker heartbeat |
| 表级同步状态可解释 | 活跃同步路径未充分校验 schema drift | Phase 4 加入 schema fingerprint 和安全演进策略 |
| 诊断必须可信 | 白名单 fallback 可能被诊断误判为成功 | Phase 5 拆分 UI fallback 与 strict diagnostics/planning |
| 手动刷新当前页面依赖表 | 已有入口但状态、依赖表、事实刷新闭环不完整 | Phase 6 补齐页面级手动刷新闭环 |

新同步模块的优先级固定为：

1. 稳定性：任务可恢复、可重试、可诊断，单表失败不拖垮整个源。
2. 准确性：最终一致优先，补偿扫描和每日校验要能发现并修复漏同步。
3. 可解释性：用户和运维能看清楚当前页面、表、事实层分别刷新到哪里。
4. 弱实时性：默认不承诺严格实时；只通过用户主动刷新当前页面依赖表来加速新鲜度。

准确性链路仍采用四层模型：

```text
每日全量校验：最终一致兜底
补偿扫描：日常追数主力
用户手动刷新当前页面：交互式 freshness 加速入口
System Hook：只作为 dirty/wakeup 信号，不承担正确性
```

## 2. 非目标

- 不恢复 Webhook 精确同步作为准确性主链路。
- 不承诺 GitLab 源库发生变化后页面立即一致。
- 不提供“一键刷新全部源表”的交互式实时能力，避免用户操作放大为重负载同步。
- 不自动处理高风险 schema 变更，例如删除列或不兼容类型转换。
- 不把无 `updated_at` 的大表纳入高频补偿扫描，除非后续为具体表定义了小表全刷策略。

## 2.1 2026-05-13 实施进展

已落地后端核心修复：

- Phase 1：显式 `configId` 严格解析，不存在的配置不再 fallback 默认源。
- Phase 2：补偿扫描优先使用表级 job/task/state 活跃度；无表级和 legacy 活动时会 bootstrap 一次表级补偿规划。
- Phase 3：表级 worker 在批次边界刷新 heartbeat/lease，并在超时恢复前二次读取任务，避免误回收仍在运行的任务。
- Phase 4：活跃同步路径的 `getPreparedMirrorTableForSync` 增加 schema check 到期判断，到期后复用 `prepareMirrorTable` 的安全 schema 演进逻辑。
- Phase 5：白名单拆分 UI fallback 与 strict discovery；诊断和真实任务规划使用 strict 结果。
- Phase 6：页面级手动刷新使用 strict 白名单；请求表不在白名单或属于 verify-only 时不再静默成功，会返回明确失败。
- 状态语义补充：已有表级全量/校验/补偿/刷新 job 处于活动状态时，手动全量和手动增量请求复用活动 job 状态，不再创建一个 0 秒完成的新计划误导用户。
- 产品语义补充：页面级刷新入口指业务页面/看板/列表页当前展示依赖的数据刷新；数据库浏览器中的单表刷新仅定位为管理员运维排查入口。

已通过定向回归：

```powershell
. ..\scripts\dev-env.ps1
mvn -q "-Dtest=GitlabConfigServiceTest,GitlabWhitelistServiceTest,GitlabSyncControllerTest,GitlabCompensationSchedulerTest,GitlabTableSyncPlanningServiceTest,GitlabTableSyncWorkerServiceTest,GitlabMirrorSyncServiceTest,DatabaseBrowserServiceTest,DatabaseBrowserControllerTest,CodeReviewControllerTest,FactRefreshTaskWorkerServiceTest" test
```

未完成事项：

- 页面级手动刷新 API 仍沿用既有响应结构，当前以失败状态避免“误报已刷新”；后续可以扩展 `unsupportedTables`、`factRefreshPlanned`、`plannedTasks` 等结构化字段。
- `StatisticBoardControllerTest` 是 `@SpringBootTest`，本地会连接 `localhost:15432`，当前环境未启动数据库，未纳入本轮自动回归。

## 3. 当前关键问题

### 3.1 显式 `configId` 缺失会回退默认源

现状风险：

- `GitlabConfigService.getConfigById(Long id)` 在 id 不存在时会返回默认配置。
- `/by-config?configId=999` 这类显式指定来源的请求可能误操作默认源。

影响：

- 多源隔离被破坏。
- 诊断、手动刷新、事实刷新可能落到错误源。

修复方向：

- 显式传入 `configId` 的入口必须严格解析。
- 找不到配置时返回 `404` 或业务错误，不允许 fallback。
- 只有完全未传 `configId` 的旧兼容入口可以走默认源，并在后续逐步收敛。

### 3.2 补偿扫描仍依赖旧任务活跃度

现状风险：

- `GitlabCompensationScheduler` 通过旧的 `gitlab_sync_tasks` 活跃时间判断是否需要补偿。
- 新表级任务路径主要写入 `gitlab_sync_jobs`、`gitlab_table_sync_tasks`、`gitlab_table_sync_states`。
- 新配置如果没有旧任务记录，可能长期跳过补偿。

影响：

- 补偿扫描作为日常同步主力的定位被削弱。
- Hook 丢失或关闭时，数据可能长期不追新。

修复方向：

- 补偿扫描的权威依据切到表级状态和表级任务。
- 按 `config_id + source_table` 判断最近成功、最近运行、dirty 标记和源端探测结果。
- 旧源级任务活跃度只能作为兼容参考，不参与是否补偿的主判定。

### 3.3 表级长任务缺少运行中心跳

现状风险：

- 表级 task 被领取时只设置一次 lease。
- 长时间的 `incrementalCursorScan`、分片修复或批量 upsert 可能超过 lease。
- 恢复逻辑可能把仍在运行的任务判为超时并重复调度。

影响：

- 产生重复任务、错误超时、状态误报。
- 在慢源库、慢盘或大表场景下稳定性下降。

修复方向：

- 表级 worker 在批次边界续租并更新 `heartbeat_at`。
- `heartbeat-timeout-seconds` 必须大于外部查询超时与批量写入最坏耗时，并保留安全余量。
- 超时恢复前二次确认任务是否仍有新 heartbeat，避免误回收。

### 3.4 schema 漂移检查不在活跃同步路径

现状风险：

- 控制面 `prepareMirrorTable` 能处理 schema 演进。
- 但 worker 活跃路径使用 `getPreparedMirrorTableForSync`，主要信任缓存注册表。
- 源端新增列、字段变化后，活跃任务可能继续使用过期镜像定义。

影响：

- 同步失败或字段缺失。
- 诊断无法及时解释具体表的 schema 问题。

修复方向：

- 在表级任务规划或执行前增加轻量 schema fingerprint 校验。
- 发现源端新增列时自动追加镜像列。
- 不自动删除镜像列。
- 不自动执行不兼容类型变更；该表进入 `BLOCKED` 或 `SCHEMA_ERROR`，其他表继续推进。

### 3.5 白名单诊断可能给出误成功

现状风险：

- `GitlabWhitelistService.listOptions` 捕获异常后返回推荐表 fallback。
- 诊断层可能把 fallback 结果当成白名单发现成功。

影响：

- 源库元数据权限、连接、schema 错误被掩盖。
- 后续同步任务可能基于并不存在或不可读的表创建。

修复方向：

- 区分 UI 展示 fallback 和同步/诊断严格模式。
- 诊断严格模式下，发现失败必须返回 `whitelistOk=false` 和错误原因。
- 任务规划严格模式下，不允许 fallback 推荐表创建真实同步任务。

## 4. 总体设计决策

### 4.1 默认弱实时，准确性靠补偿与校验

默认后台链路不追求秒级实时。补偿扫描保证变化不会长期漏掉，每日校验负责发现删除、漏扫、schema 漂移和摘要差异。

页面上的“刷新最新数据”不是强实时承诺，而是用户主动触发的局部新鲜度加速：只刷新当前页面依赖的 GitLab 源表，并在镜像表完成后触发对应事实层刷新。

### 4.2 所有显式来源操作必须 strict config

显式来源入口包括：

- 诊断 `/by-config`
- 手动全量、增量、补偿、校验
- 页面级刷新
- 表级重跑
- 事实层重建
- Webhook secret 路由后的配置解析

这些入口解析不到配置时必须失败，不允许静默落到默认源。

### 4.3 表级状态是补偿扫描权威来源

补偿扫描判断依据调整为：

- `gitlab_table_sync_states.last_success_at`
- `gitlab_table_sync_states.last_watermark_at`
- `gitlab_table_sync_states.dirty_flag`
- 最近未结束的 `gitlab_table_sync_tasks`
- 源端 probe 的 `row_count`、`max(updated_at)`、`schema_fingerprint`

旧 `gitlab_sync_tasks` 只保留为历史状态展示或兼容入口，不再决定新补偿扫描是否运行。

### 4.4 worker 以批次为恢复边界

表级任务的恢复边界是批次，而不是整张表：

- 每个批次提交前后更新 heartbeat。
- 成功批次推进 cursor 和 rows_applied。
- 失败批次记录错误、保留 cursor，下次从安全 overlap 窗口重试。
- 任务重复执行必须依赖主键 upsert、更新时间比较和 tombstone 规则保持幂等。

### 4.5 schema 漂移只自动处理低风险变化

自动处理：

- 源表新增列。
- 镜像表缺少元数据列。
- schema fingerprint 缓存过期后的重新准备。

不自动处理：

- 删除列。
- 字段类型不兼容变化。
- 主键变化。
- `updated_at` 策略变化导致无法稳定增量。

高风险变化处理方式：

- 该表任务标记失败或阻塞。
- 诊断给出具体原因。
- 其他表继续同步。
- 管理员通过受控窗口执行表重建或策略调整。

### 4.6 白名单 fallback 只允许服务 UI

推荐白名单 fallback 的唯一用途是让设置页在源库暂不可达时仍能展示建议项，并且必须带 warning。

禁止用途：

- 不允许作为诊断成功依据。
- 不允许作为真实同步任务规划依据。
- 不允许作为页面级刷新依赖表的有效性证明。

### 4.7 无 `updated_at` 表默认稳定优先

默认策略：

- 有 `updated_at + primary key`：`INCREMENTAL`。
- 无 `updated_at` 且已明确小表策略：`FULL_SMALL_TABLE`。
- 无 `updated_at` 且未定义小表策略：`VERIFY_ONLY`。
- 缺主键或权限不足：`UNSUPPORTED`。

本轮修复不主动扩大 `FULL_SMALL_TABLE` 范围，避免为了新鲜度牺牲稳定性。

## 5. 页面级手动刷新设计

### 5.1 功能定位

用户在某个业务页面、看板或列表页点击“刷新最新数据”时，系统只刷新该页面依赖的 GitLab 源表，而不是刷新整个 GitLab 源。

这里的“当前页面依赖表”是产品功能语义，不是数据库浏览器中的任意 ODS/镜像物理表。数据库浏览器可以保留运维排查用的单表刷新入口，但它不应成为普通用户理解或使用的“页面级刷新”主入口。

该能力用于解决：

- 用户刚在 GitLab 修改了 issue/MR/comment，希望当前页面尽快看到。
- 后台补偿扫描间隔较长，用户需要临时加速某个页面。
- Webhook 丢失或延迟时，用户可以主动触发局部追数。

该能力不解决：

- 全平台严格实时。
- 所有页面同时刷新。
- 无法增量的未知大表实时刷新。
- 普通用户从数据库浏览器任意选择物理表进行刷新。

### 5.2 目标链路

```text
页面刷新按钮
  -> 页面/看板声明 source table dependencies
  -> 后端 strict 解析 configId
  -> 严格白名单和 row_strategy 校验
  -> 创建 gitlab_sync_jobs(job_type=MANUAL_REFRESH)
  -> 创建 gitlab_table_sync_tasks(task_type=MANUAL_REFRESH)
  -> 表级 worker 批量增量拉取并 heartbeat
  -> 镜像表 upsert/tombstone
  -> 创建 fact_build_tasks
  -> 事实 worker 刷新页面依赖事实
  -> 页面轮询或查询刷新状态
```

### 5.3 页面依赖表声明

每个支持手动刷新的页面必须显式声明依赖源表，例如：

- 缺陷汇总类看板：`issues`、`projects`、`users`、`label_links`、`labels`、`notes`。
- 代码评审违规记录：按当前服务现有 `REALTIME_REFRESH_TABLES` 声明。
- 数据库浏览器：从当前浏览的 ODS 表反解到唯一 source table。

要求：

- 依赖表声明是后端权威，前端不能传任意表名扩大范围。
- 依赖表必须与当前页面事实构建逻辑保持一致。
- 依赖表变更时必须补测试，防止页面刷新遗漏事实来源。
- 数据库浏览器的刷新入口仅面向管理员/运维诊断；普通业务页面不得依赖用户输入的数据库表名决定刷新范围。

### 5.4 API 与返回状态

手动刷新 API 应返回可解释状态，而不是只返回“已开始”。

建议状态字段：

- `accepted`：请求是否被接受。
- `configId`：实际刷新源。
- `sourceTables`：本次计划刷新的源表。
- `plannedTasks`：创建的表级任务数。
- `unsupportedTables`：无法手动刷新的表及原因。
- `factRefreshPlanned`：是否已计划事实刷新，或将在表任务成功后创建。
- `status`：`ACCEPTED`、`RUNNING`、`SUCCESS`、`PARTIAL_SUCCESS`、`FAILED`、`UNSUPPORTED`。
- `message`：面向用户和运维的简短说明。

页面展示口径：

- `ACCEPTED/RUNNING`：正在刷新最新数据。
- `SUCCESS`：当前页面依赖表和事实层刷新完成。
- `PARTIAL_SUCCESS`：部分表刷新失败，页面可能不是最新。
- `UNSUPPORTED`：当前页面依赖表不支持主动追新，只能等待每日校验或管理员处理。
- `FAILED`：刷新失败，展示诊断入口。

### 5.5 与事实层刷新关系

镜像表刷新成功不等于页面可见数据已刷新。页面级手动刷新必须把事实层纳入状态模型：

- 表级任务成功后，按 `config_id + source_instance + fact_type` 创建事实刷新任务。
- 同一页面短时间多次刷新应合并事实任务，避免重复构建。
- 如果镜像成功但事实刷新失败，页面状态应为 `PARTIAL_SUCCESS` 或 `FACT_FAILED`，不能宣称已完成。
- 诊断中要能看到“镜像刷新到哪个 watermark，事实刷新到哪个时间点”。

### 5.6 无法增量表的行为

页面依赖表如果缺少 `updated_at`：

- `FULL_SMALL_TABLE`：允许创建受限的全表小表刷新任务。
- `VERIFY_ONLY`：不创建手动刷新任务，返回 `unsupportedTables`，提示只能等待校验或管理员重建策略。
- `UNSUPPORTED`：直接返回 unsupported 原因。

禁止行为：

- 静默跳过后返回成功。
- 用 fallback 推荐表伪造任务。
- 为未知大表临时全量刷新。

## 6. 分阶段实施任务

### Phase 1：strict configId 与入口隔离

描述：修复显式配置解析 fallback，防止多源误操作。

任务：

- [ ] 为 `getConfigById` 增加 strict 语义，找不到配置时抛出明确异常。
- [ ] 保留旧默认源入口，但方法名或调用点表达清楚“未传 configId 才可默认”。
- [ ] 更新诊断、表级任务、事实刷新、页面级刷新等显式入口。
- [ ] 增加 `configId` 不存在时的 controller 测试。

验收标准：

- [ ] `/by-config?configId=999` 不会操作默认源。
- [ ] 表级 worker 遇到不存在配置时任务失败且错误可诊断。
- [ ] 旧兼容入口未传 `configId` 时行为不回退。

验证：

- [ ] `GitlabConfigServiceTest`
- [ ] `GitlabSyncControllerTest`
- [ ] `FactRefreshTaskWorkerServiceTest`
- [ ] `GitlabTableSyncWorkerServiceTest`

### Phase 2：补偿扫描切到表级状态

描述：让补偿扫描真正成为日常同步主力。

任务：

- [ ] 新增表级 activity 查询，读取 `gitlab_sync_jobs`、`gitlab_table_sync_tasks`、`gitlab_table_sync_states`。
- [ ] `GitlabCompensationScheduler` 改为按表状态和源端 probe 规划任务。
- [ ] 有正在运行的同源同表任务时不重复创建补偿任务。
- [ ] 旧 `gitlab_sync_tasks` 活跃度退出主判定。

验收标准：

- [ ] 新配置即使没有旧任务记录，也会进入补偿扫描。
- [ ] 已有表级任务运行时不会重复规划同表补偿。
- [ ] 单表失败不影响其他表补偿规划。

验证：

- [ ] `GitlabCompensationSchedulerTest`
- [ ] `GitlabTableSyncPlanningServiceTest`
- [ ] 表级状态诊断接口测试

### Phase 3：表级 worker 心跳与 lease 续租

描述：提高长任务、慢查询、大批量写入场景的稳定性。

任务：

- [ ] 在每个批次扫描前后更新 `heartbeat_at` 和 `lease_until`。
- [ ] 将外部查询超时、批量写入耗时、heartbeat timeout 做配置一致性诊断。
- [ ] 超时恢复前二次读取任务 heartbeat，避免误回收。
- [ ] 长任务日志记录 taskId、table、cursor、rows_applied。

验收标准：

- [ ] 模拟长批次任务不会被恢复线程误判超时。
- [ ] worker 崩溃后任务可按 cursor 恢复。
- [ ] heartbeat 配置不合理时诊断给出 warning 或 failure。

验证：

- [ ] `GitlabTableSyncWorkerServiceTest`
- [ ] 超时恢复单元测试
- [ ] `GitlabTableSyncDiagnosticsServiceTest`

### Phase 4：活跃同步路径 schema 漂移保护

描述：让表级任务在真实执行前确认镜像 schema 仍可用。

任务：

- [ ] 在任务规划或执行前计算源表 schema fingerprint。
- [ ] fingerprint 变化时调用安全的镜像准备流程。
- [ ] 新增列自动追加。
- [ ] 不兼容类型、主键变化、`updated_at` 策略变化标记表级错误。
- [ ] 诊断展示 schema drift 类型和处理建议。

验收标准：

- [ ] 源端新增列后，后续同步不因镜像缺列失败。
- [ ] 不兼容 schema 只阻塞该表，不阻塞整个源。
- [ ] schema 错误能在表级诊断中看到。

验证：

- [ ] `GitlabMirrorSchemaServiceTest`
- [ ] `GitlabTableSyncWorkerServiceTest`
- [ ] schema drift 诊断测试

### Phase 5：严格白名单诊断与规划

描述：消除 fallback 推荐表导致的误成功。

任务：

- [ ] 拆分白名单解析模式：`UI_FALLBACK`、`STRICT_DIAGNOSTICS`、`STRICT_PLANNING`。
- [ ] 诊断接口使用 strict diagnostics，失败时返回 `whitelistOk=false`。
- [ ] 表级任务规划使用 strict planning，失败时不创建任务。
- [ ] UI fallback 返回 warning 字段，提示源库不可达或元数据不可读。

验收标准：

- [ ] 源库元数据读取失败时诊断不会显示白名单 OK。
- [ ] 规划任务不会基于 fallback 推荐表创建。
- [ ] 设置页仍可展示推荐项，但用户能看到 warning。

验证：

- [ ] `GitlabWhitelistServiceTest`
- [ ] `GitlabSyncControllerTest`
- [ ] 前端类型检查，如响应结构变化

### Phase 6：页面级手动刷新闭环

描述：把用户“刷新当前页面”的 freshness 能力补成完整闭环。

任务：

- [ ] 为每个支持刷新页面固化后端依赖表声明。
- [ ] 页面刷新入口 strict 解析 `configId`，无法解析时失败。
- [ ] 按依赖表创建 `MANUAL_REFRESH` job/task，不刷新无关表。
- [ ] 表刷新成功后创建或合并对应事实刷新任务。
- [ ] 返回并查询刷新状态，区分镜像成功、事实成功、部分失败和 unsupported。
- [ ] 对无 `updated_at` 表按 row strategy 返回明确结果。

验收标准：

- [ ] 点击某个看板刷新时，只创建该看板依赖表任务。
- [ ] 页面不会在事实刷新失败时宣称数据已最新。
- [ ] 无法主动刷新的表在响应中可见。
- [ ] 多源场景不会刷新错误 config。

验证：

- [ ] `GitlabMirrorSyncServiceTest`
- [ ] `StatisticBoardControllerTest`
- [ ] `CodeReviewControllerTest`
- [ ] `DatabaseBrowserServiceTest`
- [ ] 相关 board service 测试

### Phase 7：Runbook 与发布保护

描述：把稳定性修复后的操作口径固化到运维文档和发布验证。

任务：

- [ ] 更新 `docs/gitlab-direct-sync-webhook-runbook.md` 的准确性口径。
- [ ] 增加页面级手动刷新排障步骤。
- [ ] 增加 heartbeat、白名单 strict、schema drift 的诊断解释。
- [ ] 增加上线前 verification commands。

验收标准：

- [ ] 运维能按文档判断连接、白名单、补偿、表级任务、事实层和页面刷新问题。
- [ ] 发布 checklist 覆盖本方案所有高风险点。

验证：

- [ ] 文档 review
- [ ] 本地 dry-run 脚本
- [ ] 后端定向回归

## 7. 推荐实施顺序

建议顺序：

1. Phase 1：先收紧 `configId`，避免后续所有任务继续扩大多源误操作风险。
2. Phase 5：再拆白名单 strict/fallback，防止规划层继续创建伪任务。
3. Phase 2：补偿扫描切表级权威状态，恢复后台最终一致主链路。
4. Phase 3：补 worker heartbeat，减少长任务误恢复。
5. Phase 4：补 schema drift 保护，提升长期运行准确性。
6. Phase 6：补齐页面级刷新闭环，让弱实时能力对用户可见、可解释。
7. Phase 7：最后更新 runbook 和发布保护。

Phase 2 和 Phase 3 可以并行设计，但建议先合并 Phase 3 的 heartbeat 后再扩大补偿扫描触发频率。

## 8. 验证矩阵

后端定向回归建议：

```powershell
. ..\scripts\dev-env.ps1
mvn -q "-Dtest=GitlabConfigServiceTest,GitlabSyncControllerTest,GitlabWhitelistServiceTest,GitlabTableSyncPlanningServiceTest,GitlabTableSyncWorkerServiceTest,GitlabMirrorSyncServiceTest,FactRefreshTaskWorkerServiceTest" test
```

页面级刷新相关回归建议：

```powershell
. ..\scripts\dev-env.ps1
mvn -q "-Dtest=StatisticBoardControllerTest,CodeReviewControllerTest,DatabaseBrowserServiceTest,GitlabMirrorSyncServiceTest" test
```

前端类型检查建议：

```powershell
& "C:\Program Files\nodejs\npm.cmd" run typecheck
```

发布前手动验证：

- [ ] 新增一个 GitLab 源，不创建旧 `gitlab_sync_tasks`，确认补偿扫描仍会规划表级任务。
- [ ] 对不存在的 `configId` 调用诊断和刷新接口，确认不会落到默认源。
- [ ] 模拟白名单元数据读取失败，确认诊断为失败，UI 有 fallback warning。
- [ ] 模拟长表任务，确认 heartbeat 持续推进且不会误超时。
- [ ] 修改源表新增列，确认镜像自动追加列或诊断明确失败。
- [ ] 在看板点击刷新，确认只创建当前看板依赖表的 `MANUAL_REFRESH` 任务。
- [ ] 镜像刷新成功但事实刷新失败时，确认页面状态不显示“已最新”。

## 9. 风险与缓解

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| strict config 改动暴露旧调用方未传 id | 部分旧入口行为变化 | 仅对显式 `configId` 入口强制 strict，未传 id 的兼容入口保留默认源 |
| 补偿扫描切表级后任务数量增加 | 源库压力上升 | 默认同源并发 1，按表去重，增加 run_after 和退避 |
| heartbeat 续租过于频繁 | 数据库写压力上升 | 只在批次边界和长批次定时续租，控制最小续租间隔 |
| schema fingerprint 每次计算开销大 | 同步延迟增加 | 使用缓存 TTL，只在任务开始、fingerprint 变化或诊断时计算 |
| 页面刷新被用户频繁点击 | 重复任务和源库压力 | 按 `configId + pageKey + sourceTables` 合并短窗口内请求 |
| 无 `updated_at` 表无法主动追新 | 用户认为刷新无效 | 返回 unsupported 明细，文案说明只能等待校验或管理员配置小表策略 |
| 事实刷新失败导致页面旧数据 | 用户误判数据已最新 | 页面状态区分镜像刷新和事实刷新，失败可诊断、可重跑 |

## 10. 完成定义

本修复完成后应满足：

- 显式 `configId` 不存在时不会 fallback 到默认源。
- 补偿扫描不再依赖旧源级任务活跃度。
- 表级长任务有 heartbeat 和 lease 续租，不会被轻易误判超时。
- 活跃同步路径能发现 schema drift，并安全处理新增列。
- 白名单诊断不会用 fallback 推荐表冒充成功。
- 用户可以刷新当前页面依赖表，并看到镜像刷新和事实刷新状态。
- 无法主动刷新的表会明确返回 unsupported，不静默成功。
- Runbook 能解释默认弱实时、手动页面刷新、补偿扫描和每日校验之间的关系。
