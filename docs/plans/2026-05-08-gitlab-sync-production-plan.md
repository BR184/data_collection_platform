# GitLab 数据库同步模块生产化方案

## 2026-05-09 修订：准确性优先的四层同步架构

本次修订把 System Hook 降级为最薄弱但有用的加速信号，不再让它承担同步正确性。同步模块的准确性优先级固定为：

```text
每日 N 点全量校验：保证核心镜像表最终一致
补偿间隔 X 分钟扫描：保证不依赖 Hook 也会追数据
用户手动刷新当前页面：保证用户需要时能主动拉新
System Hook：只负责提前唤醒，不负责准确性
```

最终设计一句话：把 System Hook 当闹钟，把补偿扫描当日常同步主力，把每日全量校验当准确性兜底，把所有同步、校验和事实刷新都做成可恢复的表级幂等任务。

### 架构取舍

- 不再把 Webhook 精确同步作为正确性链路。Hook 触发后只执行 `记录事件 -> 标记 source dirty -> 合并/限流 -> 提前触发一次补偿扫描`。
- 补偿扫描从 Webhook 的补充机制升级为日常最终一致主机制。每 X 分钟扫描核心白名单表，按表比较源端 `max(updated_at)` 与本地表级 watermark，不一致则创建表级增量任务。
- 每日全量校验不直接全表 `select *`，而是先比较行数、max 更新时间、主键范围和分片摘要；发现差异后再按主键分片修复。
- 用户手动刷新只刷新当前页面依赖的数据表，不触发全源重跑。
- 断电、断网、慢盘、掉线、Hook 丢失、Hook 延迟都不能破坏最终一致性，只会影响数据追新的提前程度。

### 新同步分层

```text
GitLab PostgreSQL / Docker GitLab
        |
        v
Source Adapter
  - DirectJdbcSourceAdapter
  - DockerPsqlSourceAdapter
        |
        v
Table Probe
  - count
  - max(updated_at)
  - primary key range
  - schema fingerprint
        |
        v
Durable Sync Job
  - compensation scan
  - manual page refresh
  - daily verify
        |
        v
Table Sync Task
  - per table lease
  - heartbeat
  - retry/backoff
  - updated_at + pk cursor
        |
        v
Mirror Tables / ODS
        |
        v
Durable Fact Refresh Task
        |
        v
Record Pages / Boards / Export

System Hook
        |
        v
Dirty Signal / Wakeup Only
```

### 关键数据模型

新增或重建以下权威模型，后续实现不再依赖源级 `last_full_sync_at` / `last_incremental_sync_at` 作为同步准确性的判断依据：

- `gitlab_sync_jobs`：源级调度意图，包含 `config_id`、`job_type`、`trigger_type`、`status`、`priority`、`run_after`、`heartbeat_at`、`lease_owner`、`retry_count`、`started_at`、`finished_at`、`error_code`、`error_message`。
- `gitlab_table_sync_tasks`：真正执行的表级任务，包含 `job_id`、`config_id`、`source_instance`、`source_table`、`mirror_table`、`task_type`、`status`、`watermark_at`、`cursor_updated_at`、`cursor_pk`、`batch_size`、`lease_until`、`heartbeat_at`、`retry_count`、`last_error`、`rows_scanned`、`rows_applied`。
- `gitlab_table_sync_states`：表级权威状态，包含 `config_id`、`source_instance`、`source_table`、`mirror_table`、`primary_key_columns`、`updated_at_column`、`row_strategy`、`last_success_at`、`last_watermark_at`、`last_cursor_pk`、`source_max_updated_at`、`source_row_count`、`mirror_row_count`、`schema_fingerprint`、`last_error`、`retry_count`、`dirty_flag`。
- `gitlab_hook_events`：Hook 信号表，状态只表达接收和合并结果，例如 `RECEIVED`、`COALESCED`、`IGNORED`、`ERROR`，不表达同步成功。
- `fact_refresh_tasks`：事实刷新任务，按 `config_id + source_instance + fact_type` 可恢复执行，失败进入诊断，不反向打失败镜像任务。

### 补偿扫描主流程

```text
每 X 分钟，对每个 source_enabled=true 的 GitLab 源：
  1. 加载白名单核心表
  2. 探测每张表的 row_count、max(updated_at)、schema_fingerprint
  3. 与 gitlab_table_sync_states 比较
  4. 对脏表创建 gitlab_table_sync_tasks
  5. worker 按 updated_at + 主键游标分批拉取
  6. 幂等 upsert 到 ODS
  7. 成功后推进该表 watermark
  8. 提交事实刷新任务
```

增量批量查询统一采用：

```sql
where updated_at >= :watermark - interval '5 minutes'
  and (
    updated_at > :cursor_updated_at
    or (updated_at = :cursor_updated_at and id > :cursor_id)
  )
order by updated_at asc, id asc
limit :batch_size
```

没有更新时间字段的表不得静默跳过并视为成功，必须显式标记 `row_strategy`：

- `INCREMENTAL`：按 `updated_at + primary key` 游标增量。
- `FULL_SMALL_TABLE`：小表允许周期性全表摘要与必要时全量刷新。
- `VERIFY_ONLY`：只参与每日校验和诊断，不进入高频补偿扫描。
- `UNSUPPORTED`：不承诺自动追新，必须在诊断中暴露。

### 恶劣环境保护

- 所有源级任务、表级任务、校验任务、事实刷新任务必须落库。
- 任务运行时必须 heartbeat，超时后自动标记 `TIMEOUT`，下一轮可恢复。
- 单表失败只影响该表，源级任务汇总为 `PARTIAL_SUCCESS`，不能拖死整个源。
- 网络错误、连接断开、超时执行指数退避；SQL、权限、schema 错误标记 `BLOCKED`，不无限重试。
- 默认同一 GitLab 源只跑 1 个同步任务，表级并发默认 1，最多 2。
- 每张表必须记录 `last_success_at`、`last_watermark_at`、`last_error`、`retry_count`。
- 重复拉取必须安全，靠主键和 `updated_at` 防止旧数据覆盖新数据。
- 物理删除主要由每日校验修复；Hook delete 只能标 dirty 或提前触发扫描。

### 准确性口径

```text
System Hook 不保证准确；
X 分钟补偿扫描保证变化不会长期漏掉；
手动刷新保证当前页面可主动追新；
每日 N 点全量校验保证核心镜像表最终一致；
所有任务都可恢复、可重试、可诊断。
```

系统不承诺 GitLab 任意修改后立刻一致。平台只承诺在补偿扫描和每日校验窗口内达到可解释的最终一致。

### 落地顺序

1. 新建持久化源级 job、表级 task、表级 state 模型。
2. 将补偿扫描改为 `表探测 -> 表级任务 -> 批量游标 upsert`。
3. 将 System Hook 改为 dirty signal 和 wakeup，不再直接执行精确同步。
4. 将事实刷新改为持久化任务和独立 worker。
5. 增加每日校验：先 row_count/max_updated_at/pk range，再分片修复和 tombstone。
6. 改造镜像设置页和诊断接口，按四层准确性模型展示健康状态。
7. 补齐测试：多源隔离、direct/docker、单表失败、表级水位、批量游标、防旧覆盖、Hook 降级、每日校验删除兜底。

更新时间：2026-05-08

适用范围：GitLab 镜像同步、直连数据库读取、Webhook 实时同步、多源隔离、事实层刷新与运维诊断。

## 1. 背景

当前平台已经支持 GitLab 镜像全量同步、增量同步、补偿同步、Webhook 触发同步、白名单模式和多源 `source_instance` 隔离。历史上主要通过 Docker 容器内 `gitlab-psql` 读取 GitLab 数据库；内网上线后，需要以直连 PostgreSQL 的方式作为主路径。

Phase 6 本地 Docker GitLab 验证结论：

- 直连数据库模式可以与 Webhook 同时工作。
- 直连模式不支持自动注册 GitLab Webhook，需要在 GitLab 中手工配置 receiver URL 和 secret。
- 只读数据库用户可以用于同步，但表和主键发现必须走 `pg_catalog`，不能依赖受权限影响更大的 `information_schema.table_constraints`。
- 现有同步链路仍存在内存 Webhook 队列、全局水位、事实刷新弱可见、多源默认配置残留等生产化风险。

本方案不追求短期最低成本，而是定义同步模块的完整长期解法：直连为主路径，Docker 为兼容路径，同步链路具备可恢复、可观测、可重放、可隔离、可诊断能力。

## 2. 目标

1. 直连数据库成为长期主路径，支持只读账号、超时、重试、诊断和 schema 变化识别。
2. Webhook 从内存 best-effort 升级为持久化事件管道，支持失败重试、重启恢复、手动重放和降级补偿。
3. 同步状态从源级时间点升级为表级水位，单表失败可恢复，不污染整个源的状态。
4. 删除一致性从单纯依赖 Webhook 升级为 Webhook tombstone + 周期性 reconciliation 双保险。
5. 镜像层和事实层解耦，事实刷新状态可见、可重跑、可诊断。
6. 多 GitLab 源全链路显式按 `configId` 和 `sourceInstance` 隔离。
7. 运维控制台能回答：连接是否正常、同步是否滞后、Webhook 是否积压、事实是否过期、哪个表失败、如何重跑。

## 3. 非目标

- 不在本方案中移除 Docker 模式。Docker 模式保留为兼容路径，但不作为新增能力的主承载方式。
- 不把 GitLab 源库写入能力纳入平台。直连账号必须是只读。
- 不追求严格 exactly-once。同步系统采用 at-least-once + 幂等写入 + 补偿校正。
- 不把统计看板大结构重构混入本方案；事实层只处理同步后的数据新鲜度和来源隔离。

## 4. 目标架构

```text
GitLab PostgreSQL / Docker GitLab
        |
        v
Source Adapter
  - DirectJdbcSourceAdapter
  - DockerPsqlSourceAdapter
        |
        v
Source Metadata Registry
  - tables
  - primary keys
  - updated-at columns
  - schema fingerprint
        |
        v
Sync Orchestrator
  - full sync
  - incremental sync
  - compensation sync
  - precise webhook sync
        |
        +--------------------+
        |                    |
        v                    v
Mirror Tables / ODS      Webhook Event Pipeline
        |                    |
        v                    v
Fact Refresh Tasks       Diagnostics / Operations
        |
        v
Record Pages / Boards / Export
```

核心分层：

- 数据源连接层：封装 direct 和 docker 两种读取方式。
- 元数据层：发现并缓存源表结构、主键、更新时间字段和 schema 指纹。
- 同步调度层：负责全量、增量、补偿、精确同步和表级水位推进。
- Webhook 管道：负责事件落库、异步分发、重试、死信、重放和降级。
- 镜像存储层：负责 ODS 表 upsert、tombstone、last-seen 标记和 reconciliation。
- 事实刷新层：负责镜像同步后的事实构建任务化和状态诊断。
- 运维诊断层：提供上线前 dry-run、运行态健康检查和人工恢复入口。

## 5. 关键设计原则

### 5.1 直连为主，Docker 兼容

新增能力优先保证 direct 路径完整。Docker 模式继续保留，但只承担旧环境兼容、本地调试和无法开放 DB 直连时的过渡能力。

### 5.2 Webhook 至少落库一次

Webhook HTTP 入口收到请求后，先写入事件表，再交给 worker 处理。内存队列只作为加速，不作为可靠性来源。

### 5.3 同步写入必须幂等

同一 webhook 重复投递、worker 重试、补偿同步回看，都不能产生重复数据或错误覆盖。镜像写入以 source table + source primary key + source instance 作为幂等边界。

### 5.4 表级水位替代全局水位

每个 `configId + sourceTable` 独立记录同步水位、最近任务、失败原因、schema 指纹和行数。单表失败不应让整个源的状态变得不可解释。

### 5.5 删除事件需要双保险

删除 Webhook 能及时 tombstone；周期性 full/reconciliation 负责修正漏掉的删除事件。

### 5.6 事实层失败必须可见

镜像同步成功不代表页面数据已更新。事实刷新失败可以不反向打失败镜像任务，但必须进入诊断状态并支持重跑。

### 5.7 所有用户操作显式指定来源

管理端手动同步、刷新、清理、重建、诊断和 Webhook 处理都必须明确 `configId` 或可唯一解析到某个 `configId`，不能隐式落到 default 源。

## 6. 数据模型方向

本节定义长期数据模型方向，具体字段以实现阶段的 Flyway 迁移设计为准。

### 6.1 源表元数据

建议表：`gitlab_source_table_metadata`

核心字段：

- `config_id`
- `source_instance`
- `table_name`
- `primary_key_columns`
- `updated_at_column`
- `column_schema_json`
- `schema_fingerprint`
- `row_strategy`
- `last_discovered_at`
- `metadata_status`
- `metadata_message`

`row_strategy` 建议枚举：

- `INCREMENTAL`
- `FULL_SMALL_TABLE`
- `FULL_ONLY`
- `UNSUPPORTED`

### 6.2 表级同步状态

建议表：`gitlab_table_sync_states`

核心字段：

- `config_id`
- `source_instance`
- `source_table`
- `mirror_table`
- `last_full_synced_at`
- `last_incremental_watermark`
- `last_compensation_at`
- `last_success_task_id`
- `last_failure_task_id`
- `last_failure_reason`
- `last_source_row_count`
- `last_mirror_row_count`
- `last_seen_schema_fingerprint`
- `sync_enabled`

### 6.3 Webhook 事件状态

建议增强现有 Webhook 事件表，或新增专用状态字段。

事件状态：

- `RECEIVED`
- `PENDING`
- `PROCESSING`
- `SUCCESS`
- `FAILED`
- `RETRYING`
- `FALLBACK_INCREMENTAL`
- `DEAD_LETTER`

核心字段：

- `config_id`
- `source_instance`
- `event_uuid`
- `event_type`
- `object_kind`
- `object_id`
- `dedupe_key`
- `payload_json`
- `attempt_count`
- `next_retry_at`
- `last_error`
- `received_at`
- `processed_at`

### 6.4 事实刷新任务

建议表：`fact_refresh_tasks`

核心字段：

- `config_id`
- `source_instance`
- `fact_type`
- `trigger_type`
- `mirror_task_id`
- `source_tables`
- `status`
- `started_at`
- `finished_at`
- `last_error`

## 7. 实施阶段

### Phase 0：架构和状态机冻结





状态：已完成。详细设计见 [GitLab 同步生产化 Phase 0 设计冻结](./2026-05-08-gitlab-sync-phase0-design-freeze.md)。

描述：先冻结长期模型，避免后续 phase 各自补丁化。该阶段只产出设计文档和迁移草案，不改运行逻辑。

任务：

- [x] 定义 Source Adapter 接口边界。
- [x] 定义表级同步状态机。
- [x] 定义 Webhook 事件状态机。
- [x] 定义事实刷新任务状态机。
- [x] 定义多源隔离规则：所有入口如何获得 `configId`。

验收标准：

- [x] 能画出 direct/docker 到 mirror/fact/diagnostics 的完整链路。
- [x] 每个状态机都有成功、失败、重试、取消或降级路径。
- [x] 能明确哪些现有表复用，哪些表需要新增或扩展。

验证：

- [x] 方案评审通过。
- [x] Flyway 迁移草案通过人工 review。

依赖：无。

预计范围：中。

### Phase 1：直连主路径生产化

状态：已完成。代码侧已完成 Source Adapter 边界收敛、`pg_catalog` 元数据发现、只读元数据诊断、schema fingerprint 和无更新时间字段 row strategy 输出。

描述：把 direct JDBC 从“可用”提升为“可上线主路径”。

任务：

- [x] 将 direct 和 docker 查询能力收敛到统一 Source Adapter。
- [x] direct 元数据发现统一使用 `pg_catalog`。
- [x] 补充只读权限诊断：连接、schema 读取、主键发现、白名单发现。
- [x] 增加源表 schema fingerprint。
- [x] 标记无更新时间字段表的同步策略。
- [x] direct 模式明确输出 Webhook 需要手动注册。

验收标准：

- [x] 只读账号可以发现表、主键和更新时间字段。
- [x] direct 诊断可以区分连接失败、权限不足、主键缺失、白名单为空、schema 变化。
- [x] direct 模式不会尝试执行 Docker/GitLab Rails Webhook 注册命令。
- [x] Docker 模式旧行为不回退。

验证：

- [x] `GitlabExternalDbServiceTest`
- [x] direct 只读账号元数据发现测试
- [x] 本地 Docker GitLab 手动联调脚本

验证备注：当前 Maven 环境下 `GitlabExternalDbServiceDirectIntegrationTest` 受 `@Testcontainers(disabledWithoutDocker=true)` 影响被跳过；只读直连链路已在 Phase 6 本地 Docker GitLab 手动联调中验证，后续 Phase 8 继续固化可显式开启的 direct integration profile。

依赖：Phase 0。

预计范围：中。

### Phase 2：Webhook 配置语义重整

状态：已完成。代码侧已拆分数据源启用、自动定时同步和 Webhook 接收语义，Webhook 路由改为只接受唯一匹配且已启用 Webhook 的配置；direct 模式诊断和设置页已提示需要手动注册 Webhook。

描述：拆清楚源启用、自动同步启用和 Webhook 启用，降低多源和直连模式误配风险。

任务：

- [x] 拆分 `sourceEnabled`、`autoSyncEnabled`、`webhookEnabled` 的语义。
- [x] 保存配置时校验启用 Webhook 的源必须有唯一非空 secret。
- [x] Webhook 入口只路由到唯一匹配且 `webhookEnabled=true` 的配置。
- [x] direct 配置页展示 receiver URL、secret 状态和手动注册提示。
- [x] 健康诊断展示 secret 缺失、重复、禁用和模式不支持自动注册等状态。

验收标准：

- [x] 关闭自动定时同步不影响 Webhook 接收。
- [x] 多个启用 Webhook 的源不能使用相同 secret。
- [x] 无 secret 或重复 secret 的配置在上线前诊断中可见。
- [x] direct 模式用户不会误以为平台会自动注册 Webhook。

验证：

- [x] `GitlabConfigServiceTest`
- [x] `GitlabWebhookServiceTest`
- [x] `GitlabSyncControllerTest`
- [x] 镜像设置页前端挂载测试
- [x] `GitlabExternalDbServiceTest` 回归
- [x] 前端 `npm run typecheck`
- [x] Flyway/schema 漂移检查
- [x] Flyway 迁移不可变检查
- [x] API 契约漂移检查
- [x] 前端 API 边界检查

补充记录：

- 新增 Flyway 迁移 `V20260508_01__gitlab_webhook_config_semantics.sql`，为 `gitlab_sync_configs` 增加 `source_enabled` 和 `webhook_enabled`，并增加启用 Webhook 源的 secret 唯一索引。
- `enabled` 暂作为 legacy 数据源启用字段保留，保存时与 `sourceEnabled` 对齐；`autoSyncEnabled` 只控制定时同步，不再阻断 Webhook 接收。
- direct 模式支持接收 Webhook，但不支持平台自动注册，页面和诊断均明确提示需要在 GitLab 项目中手动配置 receiver URL 和 secret。
- `mvn -q -Dtest=FlywayMigrationSmokeTest test` 在本机固定测试 schema 上因历史 `20260430.01` checksum 不一致失败；迁移静态漂移和不可变性检查已通过，后续重跑烟测前需要对本机 `qaflex_test_migration` 执行 repair 或清理测试 schema。

依赖：Phase 0。

预计范围：中。

### Phase 3：Webhook 持久化事件管道

描述：把 Webhook 从内存队列升级为数据库持久化事件管道。

任务：

- [ ] Webhook HTTP 入口接收后立即落库。
- [ ] worker 从数据库领取 `PENDING` / `RETRYING` 事件。
- [ ] 支持处理失败后的指数退避重试。
- [ ] 支持超过次数进入 `DEAD_LETTER`。
- [ ] 支持队列满时标记 `FALLBACK_INCREMENTAL` 并提交当前源增量同步。
- [ ] 服务启动时恢复未完成事件。
- [ ] 支持管理端手动重放某条事件。

验收标准：

- [ ] 服务在 Webhook 接收后立即重启，事件不会丢失。
- [ ] 同一对象事件可合并，但合并 key 必须包含 `configId`。
- [ ] 队列满、重试、死信、降级都能在日志和诊断中看到。
- [ ] 重放事件不会造成重复镜像记录。

验证：

- [ ] `GitlabWebhookAsyncDispatchServiceTest`
- [ ] Webhook 持久化恢复测试
- [ ] 队列过载降级测试
- [ ] 多源同对象不误合并测试

依赖：Phase 2。

预计范围：大，需要拆成多轮实现。

### Phase 4：表级水位和可恢复同步

描述：把同步进度从源级状态升级到表级状态，提升失败恢复和补偿精度。

任务：

- [ ] 新增或完善表级同步状态表。
- [ ] 全量同步成功后更新表级 full 状态。
- [ ] 增量同步按表级 watermark 推进。
- [ ] 补偿同步按表级 watermark 回看。
- [ ] 单表失败记录到表级状态，不污染其他表成功状态。
- [ ] 支持管理端重跑某个源的某张表。
- [ ] 对无更新时间字段表执行显式策略：小表全表刷新、大表 full only 或 unsupported。

验收标准：

- [ ] 某张表失败后，其他表仍能推进水位。
- [ ] 补偿同步能按表独立判断回看窗口。
- [ ] 无更新时间字段表不会被误认为增量已覆盖。
- [ ] 管理端能看出每张表最后成功和失败状态。

验证：

- [ ] `GitlabMirrorSyncServiceTest`
- [ ] 表级水位推进测试
- [ ] 单表失败恢复测试
- [ ] 无更新时间字段策略测试

依赖：Phase 1、Phase 3。

预计范围：大，需要拆成多轮实现。

### Phase 5：删除一致性与 Reconciliation

描述：解决 Webhook 漏删、删除事件识别失败、本地残留旧数据的问题。

任务：

- [ ] 统一 mirror tombstone 字段和写入语义。
- [ ] 扩展 GitLab 常见删除 payload 的识别测试。
- [ ] 全量同步或 reconciliation 时写入 `lastSeenAt`。
- [ ] 对源库不存在但本地存在的行标记 deleted 或 stale。
- [ ] 清理任务只清理超过保留期且已确认 stale/deleted 的记录。
- [ ] 事实层统一排除 tombstone 记录。

验收标准：

- [ ] 删除 Webhook 能及时 tombstone。
- [ ] Webhook 丢失后，周期性 reconciliation 能修正残留记录。
- [ ] 误删风险可控：不会因为一次失败扫描大面积标记删除。
- [ ] 事实查询不会展示已 tombstone 的数据。

验证：

- [ ] `GitlabMirrorTableStorageServiceTest`
- [ ] 删除 Webhook tombstone 测试
- [ ] full sync missing-row reconciliation 测试
- [ ] 事实层过滤 deleted 测试

依赖：Phase 4。

预计范围：中。

### Phase 6：事实刷新任务化

描述：让镜像同步和事实刷新形成可解释、可恢复的下游任务关系。

任务：

- [ ] 镜像同步完成后创建事实刷新任务。
- [ ] 事实刷新 worker 独立执行。
- [ ] 支持按 `configId + factType` 重跑。
- [ ] 事实刷新失败进入任务状态和诊断结果。
- [ ] 记录事实刷新对应的镜像任务和来源表。
- [ ] 页面或诊断能展示事实层刷新到哪个镜像时间点。

验收标准：

- [ ] 镜像成功但事实失败时，用户能在诊断中看到明确原因。
- [ ] 事实失败可以单独重跑，不必重跑源库同步。
- [ ] 多源事实刷新互不阻塞。
- [ ] 页面数据源和事实刷新来源一致。

验证：

- [ ] `FactBuildTaskServiceTest`
- [ ] `FactBuildOperationGuardTest`
- [ ] 同步后事实刷新任务测试
- [ ] 事实刷新失败诊断测试

依赖：Phase 4。

预计范围：中。

### Phase 7：运维控制台和 Runbook 完整化

描述：把同步链路变成可上线、可排障、可恢复的运维功能。

任务：

- [ ] 每个 GitLab 源提供健康诊断总览。
- [ ] 展示 direct 连接状态、Webhook 状态、白名单状态、表级水位、Webhook backlog、事实刷新状态。
- [ ] 提供 dry-run：只诊断不写入。
- [ ] 提供手动操作：重跑表、重放 Webhook、重建事实、触发 reconciliation。
- [ ] Runbook 固化上线前 checklist 和故障定位路径。

验收标准：

- [ ] 上线前可以通过一个入口完成 direct + Webhook + 白名单 + 事实层检查。
- [ ] 线上异常可以定位到连接、源表、同步任务、Webhook、镜像表或事实层中的具体环节。
- [ ] 管理员可以按最小范围恢复，而不是只能全量重跑。

验证：

- [ ] `GitlabSyncControllerTest`
- [ ] 镜像设置页挂载测试
- [ ] direct runbook 手动演练

依赖：Phase 1、Phase 3、Phase 4、Phase 6。

预计范围：中。

### Phase 8：测试矩阵和发布保护

描述：把直连和同步核心风险纳入持续验证，避免后续回归。

任务：

- [ ] 单元测试覆盖 source adapter、元数据发现、Webhook 状态机、表级水位、tombstone、事实任务。
- [ ] 切片测试覆盖 controller 配置、诊断和手动恢复入口。
- [ ] 本地真实 GitLab 联调脚本保留为手动 profile。
- [ ] CI 默认不依赖真实 GitLab，但保留可开启的 direct integration profile。
- [ ] 发布 checklist 纳入 direct 账号权限、网络边界、secret 唯一性、白名单、dry-run 和回滚策略。

验收标准：

- [ ] 核心逻辑在普通 CI 中可验证。
- [ ] 真实 GitLab 联调步骤可重复执行。
- [ ] 发布前 checklist 能覆盖主要生产风险。

验证：

- [ ] 后端同步模块测试集合
- [ ] 前端镜像设置页测试集合
- [ ] Flyway/schema 漂移检查
- [ ] Runbook 手动演练

依赖：Phase 1 到 Phase 7。

预计范围：中。

## 8. 推荐落地顺序

实际推进时，不建议严格按编号一次做完所有内容。推荐顺序：

1. Phase 0：先冻结架构和状态机。
2. Phase 1：让 direct 主路径足够可靠。
3. Phase 2：把 Webhook 配置语义收清楚。
4. Phase 3：把 Webhook 管道持久化。
5. Phase 4：升级表级水位。
6. Phase 6：事实刷新任务化。
7. Phase 5：删除一致性和 reconciliation。
8. Phase 7：补齐运维控制台。
9. Phase 8：把测试矩阵和发布保护固化。

Phase 5 和 Phase 6 可以并行设计，但实现上建议先完成表级水位。Phase 7 可以在 Phase 3 之后先做最小可用版，再随着后续阶段逐步补字段。

## 9. 风险与控制

| 风险 | 影响 | 控制策略 |
| --- | --- | --- |
| Webhook 事件在服务重启时丢失 | 实时同步缺口 | 事件先落库，worker 可恢复处理 |
| 多源 Webhook secret 重复 | 事件路由到错误源或拒绝 | 保存配置和诊断时强校验唯一性 |
| 无更新时间字段表无法增量 | 数据长期不更新 | 显式 row strategy，小表全刷，大表 full only |
| 表级水位迁移复杂 | 影响现有同步任务 | 新状态表先旁路记录，再切主路径 |
| Reconciliation 误判删除 | 大量数据被 tombstone | 引入 lastSeenAt、任务成功标记和保护阈值 |
| 事实刷新失败不可见 | 页面展示旧数据 | 事实刷新任务化，诊断展示 fact lag |
| Docker 兼容路径拖累架构 | 新旧逻辑纠缠 | Source Adapter 隔离，新增能力优先 direct |
| 真实 GitLab 版本差异 | 表结构发现或事实构建失败 | 元数据 fingerprint、真实样本回归、runbook 联调 |

## 10. 开放问题

1. 内网生产 GitLab 是否允许平台直连 PostgreSQL，是否要求 TLS。
2. 生产 direct 账号由谁创建，权限边界是否只允许指定 schema/table。
3. Webhook receiver URL 在内网中是由 GitLab 直接访问平台，还是经网关转发。
4. 无更新时间字段的大表是否存在业务必须近实时同步的场景。
5. 删除一致性更偏向 soft delete 长期保留，还是允许过期物理清理。
6. 运维控制台的手动重放、重跑和清理操作需要哪些角色权限。

## 11. 完成定义

当以下条件满足时，可以认为 GitLab 数据库同步模块达到生产化目标：

- direct 是默认推荐路径，Docker 仅为兼容路径。
- Webhook 接收后先持久化，服务重启不会直接丢事件。
- 全量、增量、补偿和 Webhook 精确同步都按 `configId + sourceInstance` 隔离。
- 每张白名单表都有独立同步状态和可解释水位。
- 删除数据既能通过 Webhook 及时 tombstone，也能通过 reconciliation 修正。
- 镜像同步和事实刷新都有独立状态，页面旧数据可以被诊断解释。
- 管理端可以 dry-run、重跑单表、重放 Webhook、重建事实。
- CI 和手动联调能覆盖 direct 元数据发现、只读权限、Webhook 持久化、多源隔离和事实刷新。
