# GitLab 同步生产化 Phase 0 设计冻结

更新时间：2026-05-08

关联总方案：[GitLab 数据库同步模块生产化方案](./2026-05-08-gitlab-sync-production-plan.md)

## 1. Phase 0 目标

Phase 0 只冻结长期架构边界、状态机和迁移草案，不改变运行逻辑。后续 Phase 1 到 Phase 8 都必须以本文为边界，避免每个阶段各自补字段、各自定义状态，最后把同步模块重新推回隐式耦合状态。

本阶段完成以下内容：

- Source Adapter 接口边界。
- 表级同步状态机。
- Webhook 事件状态机。
- 事实刷新任务状态机。
- 多源隔离规则。
- 现有表复用、新增表、扩展字段的迁移草案。

## 2. 当前可复用基础

### 2.1 已有核心表

| 表 | 当前职责 | Phase 0 判断 |
| --- | --- | --- |
| `gitlab_sync_configs` | GitLab 源配置、source mode、白名单、数据库连接和 Webhook secret | 继续作为源配置主表，后续扩展 Webhook 启用语义 |
| `gitlab_sync_tasks` | 同步任务、去重、锁、冷却、心跳和任务状态 | 继续作为源级/任务级调度表，后续与表级状态配合 |
| `gitlab_sync_logs` | 同步任务摘要日志 | 继续保留，用于任务结果摘要，不承载可恢复状态 |
| `gitlab_webhook_events` | Webhook 原始事件记录 | 继续复用，但需要扩展为持久化事件管道状态表 |
| `gitlab_mirror_records` | JSONB 镜像记录入口 | 继续复用，后续补 tombstone、last-seen 和 reconciliation 所需字段 |
| `sys_table_registry` | 源表到 ODS 镜像表的注册信息、schema fingerprint、主键和更新时间字段 | 继续作为源表元数据主表，不新建重复 metadata 表 |
| `fact_build_tasks` | 事实重建任务 guard 和任务结果 | 继续复用，后续扩展为镜像同步后的事实刷新任务表 |

### 2.2 已有枚举

| 枚举 | 当前值 | 后续策略 |
| --- | --- | --- |
| `SourceMode` | `DIRECT`, `DOCKER` | 保持不变，新增 adapter 层隔离实现 |
| `SyncType` | `FULL`, `INCREMENTAL`, `COMPENSATION`, `WEBHOOK`, `PURGE` | 保持不变，表级水位使用同一类型语义 |
| `SyncTriggerType` | `MANUAL`, `WEBHOOK`, `SCHEDULE` | 保持不变，必要时后续新增 `RECOVERY` 需单独评审 |
| `SyncStatus` | `IDLE`, `PENDING`, `QUEUED`, `RUNNING`, `CANCELLING`, `CANCELLED`, `SUCCESS`, `FAILED`, `TIMEOUT` | 同步任务和表级状态优先复用 |

## 3. Source Adapter 边界

### 3.1 目标

`GitlabExternalDbService` 当前同时承担配置分支、direct SQL、Docker 命令、元数据发现和行扫描。长期应把 direct/docker 的差异封装到 Source Adapter 层，让上层同步调度只关心统一的源读取能力。

### 3.2 建议接口

```java
interface GitlabSourceAdapter {
  SourceMode sourceMode();

  SourceConnectionCheck checkConnection(GitlabSyncConfig config);

  List<SourceTableMetadata> discoverTables(
      GitlabSyncConfig config,
      TableDiscoveryOptions options);

  SourceTableSchema describeTable(
      GitlabSyncConfig config,
      TableWhitelistOption table);

  List<Map<String, Object>> fetchRows(
      GitlabSyncConfig config,
      TableWhitelistOption table,
      SourceScanRequest request);
}
```

### 3.3 Adapter 职责

Direct adapter：

- 使用 JDBC 访问 GitLab PostgreSQL。
- 元数据发现统一使用 `pg_catalog`。
- 负责 PostgreSQL identifier quote。
- 负责 direct query timeout、fetch size、重试和只读权限诊断。

Docker adapter：

- 保留现有 Docker/GitLab Rails 兼容路径。
- 只封装 Docker 命令执行、输出解析和超时。
- 不承载 direct 新能力，例如 direct TLS、连接池、只读诊断。

上层同步调度：

- 不直接拼接 direct/docker 分支。
- 不直接执行 Docker 命令。
- 不直接访问 `information_schema.table_constraints` 等具体发现 SQL。
- 只通过 adapter 获得 metadata、schema 和 rows。

### 3.4 Phase 1 切换原则

Phase 1 实现时先保持外部行为不变，把现有逻辑搬到 adapter 内；完成测试后再逐步增强 direct 诊断。不要在 adapter 抽取同时改变同步策略。

## 4. 表级同步状态机

### 4.1 状态归属

任务级状态继续放在 `gitlab_sync_tasks.status`。表级状态新增 `gitlab_table_sync_states`，用于回答“某个源的某张表同步到了哪里”。

任务级状态描述一次执行：

```text
PENDING -> QUEUED -> RUNNING -> SUCCESS
                         |
                         +-> FAILED
                         +-> TIMEOUT
                         +-> CANCELLING -> CANCELLED
```

表级状态描述某张表的长期水位：

```text
IDLE
  |
  v
RUNNING_FULL / RUNNING_INCREMENTAL / RUNNING_COMPENSATION / RUNNING_WEBHOOK
  |
  +-> SUCCESS
  +-> FAILED
  +-> STALE
  +-> UNSUPPORTED
```

表级状态建议以 `sync_status` 字段保存。为了兼容已有 `SyncStatus`，运行期可以先使用 `RUNNING`、`SUCCESS`、`FAILED`、`IDLE`，策略类状态通过 `row_strategy` 和 `last_failure_reason` 表达。是否新增 `STALE`、`UNSUPPORTED` 到代码枚举，留到 Phase 4 实现时评审。

### 4.2 表级水位推进规则

全量同步：

- 成功：更新 `last_full_synced_at`、`last_success_task_id`、`last_source_row_count`、`last_mirror_row_count`。
- 失败：更新 `last_failure_task_id`、`last_failure_reason`，不覆盖上一次成功水位。

增量同步：

- 只对 `row_strategy=INCREMENTAL` 的表执行。
- 成功：更新 `last_incremental_watermark`。
- 失败：不推进 watermark。

补偿同步：

- 使用表级 `last_incremental_watermark` 或最近成功时间计算回看窗口。
- 成功后可更新 `last_compensation_at`，但不应把补偿窗口起点当成真实增量水位。

Webhook 精确同步：

- 成功后更新 `last_webhook_synced_at` 和 `last_success_task_id`。
- 不直接推进表级增量 watermark，避免乱序 webhook 影响后续增量扫描。

无更新时间字段表：

- `FULL_SMALL_TABLE`：允许周期性全表刷新。
- `FULL_ONLY`：只参与手动/计划全量。
- `UNSUPPORTED`：不参与自动同步，诊断中明确提示。

## 5. Webhook 事件状态机

### 5.1 目标

Webhook HTTP 入口不再依赖内存队列作为可靠性来源。事件必须先落库，然后由 worker 领取、处理、重试、降级或进入死信。

### 5.2 状态机

```text
RECEIVED
  |
  v
PENDING -> PROCESSING -> SUCCESS
              |
              +-> RETRYING -> PENDING
              |
              +-> FALLBACK_INCREMENTAL -> SUCCESS
              |
              +-> DEAD_LETTER
```

状态含义：

- `RECEIVED`：HTTP 入口已接收并落库。
- `PENDING`：等待 worker 处理。
- `PROCESSING`：worker 已领取。
- `SUCCESS`：精确同步完成，或降级增量任务已成功提交。
- `FAILED`：单次处理失败的中间状态，可与 `RETRYING` 合并实现。
- `RETRYING`：等待下一次重试。
- `FALLBACK_INCREMENTAL`：无法精确处理或队列过载，已提交当前源增量兜底。
- `DEAD_LETTER`：超过重试次数或 payload 无法解析，需要人工处理。

### 5.3 领取和幂等规则

- 领取条件：`status in ('PENDING', 'RETRYING') and next_retry_at <= now()`。
- 领取必须带锁，可以使用 `for update skip locked` 或乐观版本字段。
- `dedupe_key` 必须包含 `configId`，推荐格式：`config:{id}:kind:{objectKind}:id:{objectId}`。
- 合并事件时只合并同一 `dedupe_key` 的待处理事件，不跨源、不跨对象。
- 重放事件只创建新的 processing attempt，不修改原始 payload。

## 6. 事实刷新任务状态机

### 6.1 目标

镜像同步成功不等于事实数据已可见。事实刷新需要从同步任务的 finally 中解耦，升级为可诊断、可重跑的下游任务。

### 6.2 状态机

```text
PENDING -> RUNNING -> SUCCESS
              |
              +-> FAILED
              +-> TIMEOUT
              +-> CANCELLED
```

### 6.3 任务 scope

推荐 scope 规范：

- `source:{sourceInstance}:fact:issue`
- `source:{sourceInstance}:fact:merge-request`
- `source:{sourceInstance}:fact:integration-test`
- `source:{sourceInstance}:fact:all`

后续如果 `fact_build_tasks.scope` 长度不足，需要在迁移中扩到 128 或 255。

### 6.4 触发规则

- full mirror sync 成功：创建 full fact refresh task。
- incremental / compensation / webhook mirror sync 成功：创建 incremental fact refresh task。
- mirror 失败：不创建事实刷新任务。
- fact 失败：不回滚 mirror task，但诊断必须显示 fact lag。

## 7. 多源隔离规则

### 7.1 基本原则

所有同步入口必须显式携带 `configId`，或者通过唯一条件解析到一个 `configId`。`sourceInstance` 用于事实和查询口径，不能代替配置主键。

### 7.2 入口规则

| 入口 | configId 获取方式 | 规则 |
| --- | --- | --- |
| 管理端手动全量/增量/补偿 | request `configId` | 必须显式传递，不传只允许兼容 default 源 |
| 调度器 | 遍历 enabled config | 按 config 创建独立 scope |
| Webhook HTTP | secret 唯一匹配 | 多源时 secret 必须唯一，不能 fallback 到 default |
| 表刷新 / on-demand refresh | request 或当前页面 source | Phase 5 前必须审计所有 `getConfig()` 默认路径 |
| 镜像清理 | request `configId` | 不允许全局清理其他源 |
| 事实重建 | request `configId` 或 sourceInstance | 最终落到唯一 config，事实写入 `sourceInstance` |
| 诊断 | request `configId` | 每个源独立诊断 |

### 7.3 禁止规则

- Webhook 入口禁止在多源 secret 不唯一时自动选择 default。
- 用户触发的清理、重建、刷新禁止隐式操作所有源。
- 同步任务 dedupe key 禁止只使用 object id，必须包含 config/source。
- ODS 表名和事实写入必须来自同一个 `GitlabSyncConfig`。

## 8. 迁移草案

本节是草案，不是可直接执行的迁移。正式迁移应在对应 Phase 中按 Flyway 规则新增版本文件，已执行迁移不得改写。

### 8.1 扩展 `gitlab_sync_configs`

```sql
alter table gitlab_sync_configs
    add column if not exists source_enabled boolean not null default true,
    add column if not exists webhook_enabled boolean not null default true,
    add column if not exists db_ssl_enabled boolean not null default false,
    add column if not exists db_ssl_mode varchar(32),
    add column if not exists connection_timeout_seconds integer not null default 10,
    add column if not exists query_timeout_seconds integer not null default 60;
```

说明：

- `enabled` 当前语义暂时保留，Phase 2 再迁移到更清晰的 `source_enabled`。
- `auto_sync_enabled` 只控制定时同步。
- `webhook_enabled` 只控制 Webhook 接收。

### 8.2 复用并扩展 `sys_table_registry`

```sql
alter table sys_table_registry
    add column if not exists row_strategy varchar(32) not null default 'INCREMENTAL',
    add column if not exists metadata_status varchar(32) not null default 'DISCOVERED',
    add column if not exists metadata_message text,
    add column if not exists last_discovered_at timestamp,
    add column if not exists source_row_estimate bigint;
```

说明：

- 不新建 `gitlab_source_table_metadata`，优先复用已有 `sys_table_registry`。
- `column_snapshot`、`primary_key_columns`、`updated_at_column`、`schema_fingerprint` 已覆盖元数据主需求。

### 8.3 新增 `gitlab_table_sync_states`

```sql
create table if not exists gitlab_table_sync_states (
    id bigserial primary key,
    config_id bigint not null references gitlab_sync_configs(id) on delete cascade,
    source_instance varchar(128) not null default 'default',
    source_table varchar(255) not null,
    mirror_table varchar(255) not null,
    sync_status varchar(32) not null default 'IDLE',
    row_strategy varchar(32) not null default 'INCREMENTAL',
    last_full_synced_at timestamp,
    last_incremental_watermark timestamp,
    last_compensation_at timestamp,
    last_webhook_synced_at timestamp,
    last_success_task_id bigint references gitlab_sync_tasks(id) on delete set null,
    last_failure_task_id bigint references gitlab_sync_tasks(id) on delete set null,
    last_failure_reason text,
    last_source_row_count bigint,
    last_mirror_row_count bigint,
    last_seen_schema_fingerprint varchar(128),
    sync_enabled boolean not null default true,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    unique (config_id, source_table)
);

create index if not exists idx_gitlab_table_sync_states_config_status
    on gitlab_table_sync_states(config_id, sync_status, updated_at desc);
```

### 8.4 扩展 `gitlab_webhook_events`

```sql
alter table gitlab_webhook_events
    add column if not exists source_instance varchar(128) not null default 'default',
    add column if not exists event_uuid varchar(128),
    add column if not exists object_id varchar(128),
    add column if not exists dedupe_key varchar(512),
    add column if not exists status varchar(32) not null default 'RECEIVED',
    add column if not exists attempt_count integer not null default 0,
    add column if not exists next_retry_at timestamp,
    add column if not exists last_error text,
    add column if not exists processing_started_at timestamp,
    add column if not exists processed_at timestamp,
    add column if not exists updated_at timestamp not null default current_timestamp;

create index if not exists idx_gitlab_webhook_events_status_retry
    on gitlab_webhook_events(status, next_retry_at, received_at);

create index if not exists idx_gitlab_webhook_events_config_dedupe
    on gitlab_webhook_events(config_id, dedupe_key, received_at desc);
```

说明：

- `processed` 先保留兼容，Phase 3 再决定是否由 `status` 派生。
- 旧数据可以迁移为：`processed=true -> SUCCESS`，否则 `RECEIVED` 或 `PENDING`。

### 8.5 扩展 `gitlab_mirror_records`

```sql
alter table gitlab_mirror_records
    add column if not exists mirror_deleted boolean not null default false,
    add column if not exists deleted_at timestamp,
    add column if not exists last_seen_at timestamp,
    add column if not exists last_seen_task_id bigint references gitlab_sync_tasks(id) on delete set null;

create index if not exists idx_gitlab_mirror_records_deleted
    on gitlab_mirror_records(config_id, table_name, mirror_deleted, synced_at desc);
```

说明：

- 部分 ODS 实体表已有 `mirror_deleted` 概念，统一 JSONB mirror record 入口后，reconciliation 可按同一语义处理。

### 8.6 扩展 `fact_build_tasks`

```sql
alter table fact_build_tasks
    alter column scope type varchar(255),
    add column if not exists config_id bigint references gitlab_sync_configs(id) on delete set null,
    add column if not exists source_instance varchar(128) not null default 'default',
    add column if not exists fact_type varchar(64),
    add column if not exists mirror_task_id bigint references gitlab_sync_tasks(id) on delete set null,
    add column if not exists source_tables text;

create index if not exists idx_fact_build_tasks_config_status
    on fact_build_tasks(config_id, status, created_at desc);
```

## 9. Phase 0 验收

| 验收项 | 结果 |
| --- | --- |
| 能画出 direct/docker 到 mirror/fact/diagnostics 的完整链路 | 已完成，见总方案和本文 Source Adapter 边界 |
| 每个状态机都有成功、失败、重试、取消或降级路径 | 已完成，见表级同步、Webhook、事实刷新状态机 |
| 明确现有表复用、新增表和扩展字段 | 已完成，见迁移草案 |
| 不改变运行逻辑 | 已满足，本阶段只新增/更新 Markdown |

## 10. Phase 1 入口条件

进入 Phase 1 前需要确认：

- 接受 `sys_table_registry` 作为源表元数据主表，不额外新增重复 metadata 表。
- 接受 Source Adapter 先抽取现有行为，再增强 direct 诊断。
- 接受 `gitlab_table_sync_states` 作为 Phase 4 的新增表，不在 Phase 1 提前切主路径。
- 接受 Webhook 持久化以扩展 `gitlab_webhook_events` 为主，不另建平行事件表。
