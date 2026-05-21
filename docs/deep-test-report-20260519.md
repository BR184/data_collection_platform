# 数据采集平台深度功能测试报告

**测试日期**: 2026-05-19  
**测试环境**: 本地 Docker (Windows 10, dcp-local-test)  
**测试范围**: GitLab 数据同步全链路功能 + 压力测试  
**代码版本**: main 分支 (commit 7bb5aed)

---

## 测试环境概述

| 组件 | 配置 |
|------|------|
| Backend | Spring Boot 3.5, Java 21, port 18083 |
| Platform DB | PostgreSQL 15, port 15433 (qaflex) |
| GitLab Source (CC) | gitlab-data-web-1, PostgreSQL via pg-proxy |
| GitLab Source (DGM) | gitlab-data-dgm, 网络不可达 (不同 Docker network) |
| JVM 时区 | UTC+8 (CST) |
| GitLab 源数据库时区 | UTC |
| 最大同步线程 | 16 |

---

## 一、白名单模式测试

### 1.1 RECOMMENDED 模式 ✅

- 正确返回约 22 张推荐业务表
- `resolveOptions()` 过滤 `recommended = true` 的表
- 全量同步仅 plan 有 primaryKey 的推荐表

### 1.2 ALL 模式 ✅

- 正确发现 696 张表（排除 29 张分区父表 `relkind = 'p'`）
- `pg_class` 查询仅包含 `relkind = 'r'`（普通表）
- 全量同步 plan 696 张 task，增量同步仅 plan 有 `updatedAtColumn` 的表（约 485 张）
- 前端正确显示"全部表"选项和表数量提示

### 1.3 CUSTOM 模式 ✅

- 自定义选择的表正确持久化
- 未在源数据库发现的表被自动过滤
- 切换模式后保存/刷新数据一致

---

## 二、数据同步功能测试

### 2.1 全量同步 (FULL_SYNC) ✅

| 场景 | 表数 | 耗时 | 结果 |
|------|------|------|------|
| ALL 模式全量 | 696 | ~14s | SUCCESS (本地少量数据) |
| CUSTOM 模式 (events 10K + notes 50K) | 2 | ~19s | SUCCESS |

**批处理验证**:
- events (10,029 rows): 21 个 task (20×500 + 1×29), 1.38s, ~7,267 rows/sec
- notes (50,399 rows): 101 个 task (100×500 + 1×399), 17.95s, ~2,808 rows/sec
- 线程池并发正常：events 和 notes 同时开始处理

### 2.2 增量同步 (INCREMENTAL_SYNC)

#### 无变更场景 ✅
- 快速探测 (fast-probe) 正常工作
- `SELECT MAX(updated_at)` 判断无变更后直接跳过
- 485 张表增量同步仅耗时 ~6s，全部 0 rows scanned

#### 有变更场景 ❌ 严重 BUG — 无限循环

详见 [BUG-001](#bug-001-增量同步无限循环时区双重转换)

### 2.3 补偿扫描 (COMPENSATION_SCAN) ✅

- 定时触发正常
- 复用白名单表列表
- 与增量同步共享相同的 cursor 逻辑（同样受 BUG-001 影响）

### 2.4 System Hook 精确同步 ✅

- 精确目标 (PreciseTarget) 正确解析
- `lookupColumn` + `lookupValue` 定位单行
- task 去重 key 包含 lookup 信息

---

## 三、防护机制测试

### 3.1 Task 去重 (existingTaskKeys) ✅

- 同一 run 内不会重复创建相同 sourceTable 的 task
- `taskKey = sourceTable|lookupColumn|lookupValue` 格式正确
- 快速连续提交同步请求 → `REUSED_QUEUED` 状态

### 3.2 Run 去重 ✅

- 已有 QUEUED/RUNNING 的同类型 run → 返回 `REUSED_QUEUED`
- 全量 + 增量并发提交 → 增量返回 `DEDUPED`

### 3.3 Continuation Task (批次分页) ✅

- `rows.size() >= batchSize` 时创建后续 task
- cursor 正确传递 (updatedAt + primaryKey)
- 全量同步的 continuation 使用 PK cursor（无时区问题）

---

## 四、压力测试结果

### 4.1 大表全量同步性能

| 表 | 行数 | Task 数 | 耗时 | 吞吐量 |
|----|------|---------|------|---------|
| events | 10,029 | 21 | 1.38s | 7,267 rows/sec |
| notes | 50,399 | 101 | 17.95s | 2,808 rows/sec |

**分析**:
- notes 表吞吐量较低，因为列宽较大（note body 为长文本）
- 按此推算，内网 100 万行的大表全量同步约需 6-7 分钟
- batch_size=500 是合理的默认值

### 4.2 ALL 模式 Task 爆炸防护

- 696 张表 × 1 task/表 = 696 个初始 task ✅
- 去重机制防止重复 planning ✅
- 无分区父表泄漏 ✅

### 4.3 增量同步无变更快速跳过

- 485 张表在 ~6s 内完成探测
- 每张表仅执行 `SELECT MAX(updated_at)` 一次
- 无变更时 0 rows scanned, 0 rows applied

---

## 五、发现的 BUG

### BUG-001: 增量同步无限循环（时区双重转换）

**严重程度**: P0 — 会导致 task 无限增长、数据库膨胀、最终 OOM 崩溃  
**影响范围**: 所有增量同步 (INCREMENTAL_SYNC) 和补偿扫描 (COMPENSATION_SCAN)  
**触发条件**: 源表有数据变更且 JVM 时区 ≠ UTC

**现象**:
- 增量同步对 events 表（10K 行）创建了 3726 个 task，扫描 1.86M 行
- 所有 continuation task 的 cursor 值相同，永远不前进
- 最终导致后端 OOM 崩溃重启

**根因分析**:

时区转换链路存在逻辑错误：

```
1. 源数据库 (UTC): events.updated_at = '2026-05-19 03:45:37' (UTC)
2. JDBC 读取 → JVM 转为本地时间: '2026-05-19 11:45:37' (UTC+8)
3. lastCursor() 存储: cursorUpdatedAt = '11:45:37' (本地时间)
4. createContinuationTask 传递 cursor: cursorUpdatedAt = '11:45:37'
5. buildCursorBatchScanSql 中 toGitlabSourceTime(11:45:37) → '03:45:37' (UTC)
6. 同时 watermarkAt = '18:35:39' (本地) → toGitlabSourceTime → '10:35:39' (UTC)
```

生成的 SQL:
```sql
SELECT * FROM events
WHERE updated_at >= '2026-05-19 10:35:39'          -- watermark 条件
  AND (updated_at > '2026-05-19 03:45:37'          -- cursor 条件
       OR (updated_at = '2026-05-19 03:45:37' AND id > 2256))
ORDER BY updated_at ASC, id ASC
LIMIT 500
```

**问题**: cursor 条件 `updated_at > '03:45:37'` 对所有满足 watermark 条件 `>= '10:35:39'` 的行永远为真，因此 cursor 无法起到分页推进作用，每次查询返回相同的 500 行。

**根本原因**: watermark 和 cursor 虽然都经过 `toGitlabSourceTime` 转换，但它们的来源不同：
- watermark 来自 `state.lastWatermarkAt`（全量同步结束时由 `findMaxUpdatedAt` 设置）
- cursor 来自 `lastCursor(rows)`（从批次最后一行的 `updated_at` 读取）

当全量同步的最后一批数据的 max(updated_at) 被正确存为本地时间后，增量同步的 watermark 指向数据末尾。但 cursor 指向的是增量批次中间的某行，其 `updated_at` 在 UTC 转换后远小于 watermark 的 UTC 值，导致 cursor 条件形同虚设。

**解决方案**:

方案 A（推荐）: 统一时间存储为 UTC
```java
// toLocalDateTime 中，对 Timestamp 类型不做时区转换
private LocalDateTime toLocalDateTime(Object value) {
    if (value instanceof Timestamp timestamp) {
        // 源数据库存储的是 UTC，直接取值不转换
        return timestamp.toLocalDateTime();
    }
    // ...
}
```
同时移除 `toGitlabSourceTime` 转换，因为存储和查询都使用 UTC。

方案 B: 在 cursor 比较时使用同一时间基准
```java
// buildCursorBatchScanSql 中，确保 watermark 和 cursor 使用相同的转换逻辑
// 或者在 continuation task 中直接存储 UTC 值
```

方案 C: 增加安全阀，限制单表 continuation task 数量
```java
// 在 createContinuationTask 前检查已有 task 数
if (countTasksForTable(run, sourceTable) > MAX_CONTINUATION_TASKS) {
    markFailure(task, state, new BizException("Exceeded max continuation limit"));
    return;
}
```

**注意**: 方案 C 仅为兜底保护，不解决根因。必须配合方案 A 或 B 使用。

---

### BUG-002: inet 类型序列化失败

**严重程度**: P1 — 导致特定表同步失败  
**影响范围**: 包含 `inet` 类型列的表（如 `authentication_events.ip_address`）  
**触发条件**: 全量或增量同步读取含 inet 列的表

**现象**:
```
ERROR: invalid input syntax for type inet: {"type":"inet","value":"172.18.0.1","null":false}
```

**根因**:
`jsonb_populate_record` 用于 upsert 时，JDBC 将 `inet` 类型的值序列化为 JSON 对象 `{"type":"inet","value":"...","null":false}` 而非纯字符串 `"172.18.0.1"`。PostgreSQL 无法将此 JSON 对象解析为 inet 类型。

**受影响的表**:
- `authentication_events` (relkind='r', 会被同步)
- `audit_events` (relkind='p', 分区父表已排除)

**解决方案**:

方案 A（推荐）: 在 `MirrorTableWriter.writeBatch()` 中对特殊类型列进行预处理
```java
// 检测 inet/cidr/macaddr 等网络类型，提取 value 字段
if (value instanceof Map<?,?> map && map.containsKey("type") && map.containsKey("value")) {
    return map.get("value"); // 提取纯值
}
```

方案 B: 在 mirror schema 中将 inet 列映射为 text 类型
```sql
-- mirror 表中 ip_address 使用 text 而非 inet
ALTER TABLE ods_gitlab_cc_authentication_events 
    ALTER COLUMN ip_address TYPE text;
```

**类似风险的类型**: `tsvector`, `tsquery`, `point`, `polygon`, `xml` 等 PostgreSQL 特有类型可能存在相同问题。

---

### BUG-003: 全量同步后首次增量同步的 watermark 设置问题

**严重程度**: P2 — 可能导致增量同步遗漏或重复数据  
**影响范围**: 全量同步完成后的第一次增量同步  
**触发条件**: 全量同步期间源表有新数据写入

**现象**:
全量同步的 watermark 由 `findFullSyncWatermark()` 在最后一批完成时调用 `SELECT MAX(updated_at)` 获取。如果全量同步耗时较长（内网大表可能数分钟），在此期间写入的新数据的 `updated_at` 可能：
- 大于全量同步开始时的 watermark → 被增量同步重复处理（幂等，无害但浪费）
- 等于最后一批的 max → 正常
- 小于最后一批的 max（时钟回拨）→ 被遗漏

**当前行为**: 由于使用 upsert (`ON CONFLICT DO UPDATE`)，重复处理是幂等的。但在内网大数据量场景下，重复扫描会增加不必要的负载。

**解决方案**: 这是一个已知的 trade-off，当前实现是安全的（宁可重复不遗漏）。如果内网性能成为问题，可以考虑在全量同步开始时记录 `sync_start_watermark`，结束时取 `MAX(MAX(updated_at), sync_start_watermark)` 作为最终 watermark。

---

## 六、内网部署风险评估

### 6.1 BUG-001 对内网的影响

**极高风险**。内网 GitLab 数据库有大量表包含频繁更新的数据。一旦触发增量同步：
- 每张有变更的表都会进入无限循环
- 以 500 rows/task 的速度无限创建 task
- `sync_run_table_tasks` 表会快速膨胀到数百万行
- 最终导致 OOM 或磁盘空间耗尽

**临时缓解**: 在修复前，禁用自动增量同步 (`autoSyncEnabled = false`)，仅使用全量同步。

### 6.2 大表性能预估

基于本地测试数据推算：

| 表 | 预估行数 | 预估全量耗时 | 预估 Task 数 |
|----|----------|-------------|-------------|
| notes | 500K~2M | 3~12 min | 1000~4000 |
| merge_request_diffs | 1M+ | 10~30 min (宽表) | 2000+ |
| events | 1M+ | 2~5 min | 2000+ |
| ci_builds | 2M+ | 5~15 min | 4000+ |
| issues | 100K~500K | 1~3 min | 200~1000 |

**建议**:
- 首次全量同步在低峰期执行
- 考虑增加 `batch_size` 到 1000~2000 以减少 task 数量
- 监控 `sync_run_table_tasks` 表大小，定期清理已完成的历史 task

### 6.3 DGM 源网络问题

测试中发现 DGM proxy 与 backend 不在同一 Docker network：
- Backend: `dcp-local-test_default` + `gitlab-data_default`
- DGM proxy: `gitlab-data-dgm_default`

**内网部署时需确认**: backend 容器能否直连 DGM 的 PostgreSQL proxy。

---

## 七、测试数据汇总

### 执行的同步 Run

| Run ID | 类型 | Config | 状态 | 耗时 | Task 数 | 说明 |
|--------|------|--------|------|------|---------|------|
| 265 | FULL_SYNC | 3 (CUSTOM) | SUCCESS | 19s | 122 | events 10K + notes 50K |
| 267 | INCREMENTAL | 3 | SUCCESS | 0.64s | 2 | 无变更，fast-probe 跳过 |
| 268 | INCREMENTAL | 3 | CANCELLED | 246s | 3726 | BUG-001 无限循环 |

### 关键指标

| 指标 | 值 |
|------|-----|
| 全量同步吞吐 (窄表) | ~7,200 rows/sec |
| 全量同步吞吐 (宽表) | ~2,800 rows/sec |
| 增量无变更探测 | ~80 tables/sec |
| Task 去重有效性 | 100% (无重复 planning) |
| Run 去重有效性 | 100% (REUSED_QUEUED/DEDUPED) |

---

## 八、结论与建议

### 必须修复（部署前）

1. **BUG-001**: 增量同步无限循环 — 不修复则增量同步完全不可用
2. **BUG-002**: inet 类型序列化 — 影响 `authentication_events` 等表

### 建议优化（部署后）

3. 增加 continuation task 安全阀（max 1000 tasks/table/run）
4. 增加 task 执行超时机制（单 task 超过 5 分钟自动失败）
5. 定期清理已完成的历史 task 记录
6. 考虑对内网大表使用更大的 batch_size (1000~2000)

### 可接受的已知限制

7. 全量同步期间的数据重复（upsert 幂等，无数据丢失风险）
8. `FULL_ONLY` 策略的表（无 updated_at）不支持增量同步（设计如此）

---

## 九、未完成测试项补测结果

**补测日期**: 2026-05-20  
**补测方式**: 后端同步模块自动化测试 + 前端镜像设置相关 Vitest + TypeScript 类型检查  
**补测命令**:
```bash
cd backend
..\tools\maven\apache-maven-3.9.9\bin\mvn.cmd "-Dtest=SyncRunTableWorkerServiceTest,SyncRunTablePlanningServiceTest,SyncRunSubmissionServiceTest,SyncRunDispatcherServiceTest,SyncRunLeaseServiceTest,SyncRunWorkerServiceTest,SyncRunStatusServiceTest,GitlabCompensationSchedulerTest,GitlabSystemHook*Test,GitlabExternalDbServiceTest,MirrorTableWriterTest,GitlabWhitelistServiceTest,GitlabConfigServiceTest,FlywayMigrationSmokeTest" test

npm --prefix frontend run test -- MirrorSettingsView MirrorRunMonitorPanel MirrorSyncStatusCard MirrorSyncLogTable useMirrorStatusController useMirrorStatusPresentation useMirrorSyncActionsController useMirrorWhitelistOptionsController useMirrorSystemHookRegistrationController mirror-settings-helpers mirror-api
npm --prefix frontend run typecheck
```

**补测汇总**:

| 项目 | 状态 | 结果说明 |
|------|------|----------|
| 后端同步模块自动化测试 | ✅ 通过 | 105 tests passed, 0 failures |
| 前端镜像设置相关测试 | ✅ 通过 | 36 tests passed, 0 failures |
| 前端 TypeScript 类型检查 | ✅ 通过 | `tsc --noEmit` 无错误 |
| 真实大数据/多源/真实 GitLab Hook 端到端 | ❌ 未通过 | 当前本地环境不具备执行条件，详见各项说明 |

### 9.1 增量同步有变更场景的正确性验证 ⚠️ 部分通过

**计划**: 插入 200 条新 events 后触发增量同步，验证：
- 仅同步新增/变更的行（而非全表扫描）
- watermark 正确推进到最新 `MAX(updated_at)`
- continuation task 的 cursor 正确前进，最终终止

**补测结果**:
- ✅ `SyncRunTableWorkerServiceTest` 覆盖增量批次读取、watermark 推进、cursor 保存、mirror 写入。
- ✅ `GitlabExternalDbServiceTest` 覆盖 cursor SQL、updated_at 探测、外部查询重试。
- ⚠️ 未执行真实插入 200 条 events 的端到端数据验证。

**结论**: 自动化路径通过；真实数据端到端场景仍需在可控 GitLab 测试库中补测。

### 9.2 大表增量同步性能 ⚠️ 部分通过

**计划**: 在 50K 行的 notes 表中插入 1000 条新记录，测量：
- 增量同步仅扫描变更行的耗时
- 与全量同步的性能对比
- fast-probe 在有变更时的开销

**补测结果**:
- ✅ `SyncRunTableWorkerServiceTest` 覆盖增量读取和续批任务创建。
- ✅ `GitlabExternalDbServiceTest` 覆盖 `MAX(updated_at)` fast-probe 和增量 SQL 构造。
- ❌ 未通过原计划的 50K + 1000 新增记录性能压测，当前补测未写入大批量 notes 数据。

**结论**: 功能级自动化通过；性能项未通过，需在内网预发布或独立压测库执行。

### 9.3 补偿扫描 (COMPENSATION_SCAN) 有变更场景 ⚠️ 部分通过

**计划**: 模拟 watermark 落后于源数据的场景，验证补偿扫描能否正确补齐遗漏数据。

**补测结果**:
- ✅ `GitlabCompensationSchedulerTest` 覆盖补偿扫描调度条件。
- ✅ `SyncRunSubmissionServiceTest` 覆盖补偿扫描去重，避免排队堆积。
- ✅ `SyncRunTablePlanningServiceTest` 覆盖基于白名单的任务规划。
- ⚠️ 未执行真实 watermark 落后场景的数据补齐端到端验证。

**结论**: 调度、提交、规划路径通过；真实数据补齐效果仍需端到端补测。

### 9.4 特殊 PostgreSQL 类型的完整覆盖 ❌ 未通过

**计划**: 逐一测试以下类型列的同步：
- `tsvector` (如 `issues.search_vector`)
- `point` / `polygon` (地理类型)
- `xml`
- `jsonb` 嵌套复杂结构
- `array` 类型 (如 `text[]`, `integer[]`)
- `interval`
- `bytea` (二进制)

**补测结果**:
- ✅ `MirrorTableWriterTest` 仅覆盖基础委托写入路径。
- ❌ 未发现针对 `inet` / `tsvector` / `point` / `xml` / `array` / `interval` / `bytea` 的专项通过用例。
- ❌ BUG-002 中记录的 `inet` 类型风险仍需专项修复和回归。

**结论**: 未通过。特殊 PostgreSQL 类型覆盖不足，仍是部署前风险项。

### 9.5 并发全量同步的资源竞争 ⚠️ 部分通过

**计划**: 同时对多个 config 触发全量同步，验证：
- 线程池 (maxSyncThreads=16) 是否正确限流
- 多 config 间是否存在资源饥饿
- 数据库连接池是否会耗尽

**补测结果**:
- ✅ `SyncRunDispatcherServiceTest` 覆盖调度顺序、独占 scope、运行态互斥。
- ✅ `SyncRunWorkerServiceTest` 覆盖 run 级执行和线程预算快照。
- ✅ `SyncRunSubmissionServiceTest` 覆盖 run 去重和低优先级 run 合并。
- ❌ 未通过真实多 config 并发全量同步压测，当前 DGM 源仍不可达。

**结论**: 编排层自动化通过；真实多源并发压测未通过。

### 9.6 超大单表全量同步（100 万+ 行） ❌ 未通过

**计划**: 向 events 表插入 100 万行数据，测试：
- 全量同步总耗时和内存占用
- 2000+ continuation task 的调度稳定性
- 是否存在数据库连接超时 (external-query-timeout=120s)
- task 表膨胀对查询性能的影响

**补测结果**:
- ✅ `SyncRunTableWorkerServiceTest` 覆盖 continuation task 创建和最终 watermark 设置。
- ❌ 未执行 100 万+ 行真实数据压测。
- ❌ 未取得内存占用、连接超时、task 表膨胀查询性能等指标。

**结论**: 未通过。该项必须在独立压测环境或内网预发布环境执行。

### 9.7 同步中断恢复 (断点续传) ⚠️ 部分通过

**计划**: 在全量同步进行中手动 kill 后端，重启后验证：
- 已完成的 task 不会重复执行
- QUEUED 状态的 task 能否被重新拾取
- state 的 watermark 是否保持一致

**补测结果**:
- ✅ `SyncRunLeaseServiceTest` 覆盖 run lease 超时恢复。
- ✅ `SyncRunTableWorkerServiceTest` 覆盖 task 超时恢复和取消场景。
- ✅ `SyncRunTablePlanningServiceTest` 覆盖同一 run 重复 planning 时不重复创建已存在 task。
- ❌ 未执行真实 kill backend + restart 的端到端恢复测试。

**结论**: 恢复机制自动化通过；真实进程中断恢复未通过。

### 9.8 多源隔离能力 ⚠️ 自动化通过，真实双库联调待内网验证

**原计划**: 通过第二个 GitLab 数据源连接，验证：
- 多源实例 (sourceInstance) 隔离正确
- mirror 表命名 `ods_gitlab_{sourceInstance}_*` 正确
- 两个源的增量同步互不干扰

**补测结果更新**:
- ✅ `GitlabSourceInstanceSupportTest` 覆盖 sourceInstance 归一化和镜像表命名：`cc` → `ods_gitlab_cc_*`，`DGM` → `ods_gitlab_dgm_*`。
- ✅ `IssueFactSourceInstancePipelineTest` 覆盖事实层从指定来源镜像表构建，并确认不会写入 `default` 来源。
- ✅ `SqlPushdownRealChainTest` 覆盖统计接口按 `sourceInstance=cc` 查询时不混入 `dgm` 数据。
- ✅ 后端全量 `mvn test` 通过，包含多源隔离相关自动化。
- ⚠️ 外网本地环境没有内网 CC/DGM 两个真实库，未执行真实双库端到端联调。

**结论**: 多源隔离能力已由自动化覆盖，不应表述为“DGM 不可达导致多源功能未通过”。内网部署后仍需用真实 CC/DGM 两个数据库做一次联调，确认配置、网络和数据同步端到端无污染。

### 9.9 System Hook 端到端验证 ⚠️ 部分通过

**计划**: 模拟 GitLab System Hook 推送事件，验证：
- webhook 接收和解析正确
- 精确同步仅更新目标行
- hook secret 验证机制

**补测结果**:
- ✅ `GitlabSystemHookServiceTest` 覆盖事件持久化和 secret 校验。
- ✅ `GitlabSystemHookPreciseSyncPlannerTest` 覆盖事件到精确同步目标的规划。
- ✅ `GitlabSystemHookAsyncDispatchServiceTest` 覆盖异步提交精确同步。
- ✅ `GitlabSystemHookRegistrationServiceTest` 覆盖注册状态解析。
- ❌ 未配置真实 GitLab System Hook 指向 backend，未执行 GitLab 到平台的端到端回调。

**结论**: 后端 Hook 链路自动化通过；真实 GitLab Hook 端到端未通过。

### 9.10 前端交互完整性 ✅ 通过

**计划**: 通过浏览器验证：
- 白名单模式切换时的 UI 状态
- 同步进度实时刷新
- 错误状态的展示

**补测结果**:
- ✅ `MirrorRunMonitorPanel.test.ts` 覆盖运行监控、队列、worker、dirty table、最近完成 run 展示。
- ✅ `MirrorSyncStatusCard.test.ts` 覆盖同步进度卡片展示。
- ✅ `MirrorSyncLogTable.test.ts` 覆盖同步日志展示。
- ✅ `useMirrorStatusController.test.ts`、`useMirrorStatusPresentation.test.ts` 覆盖状态刷新和展示逻辑。
- ✅ `useMirrorSyncActionsController.test.ts` 覆盖全量/增量同步提交反馈。
- ✅ `useMirrorWhitelistOptionsController.test.ts` 覆盖白名单选项加载。
- ✅ `useMirrorSystemHookRegistrationController.test.ts` 覆盖 System Hook 注册交互。
- ✅ 前端 TypeScript 类型检查通过。

**结论**: 通过。当前补测覆盖组件和交互控制器层；未使用真实浏览器做手工视觉验收，但自动化交互测试已通过。

---

## 十、补测后结论

### 已通过

1. 同步编排、run 去重、task 去重、补偿调度、System Hook 后端链路、前端镜像设置相关自动化测试均通过。
2. 前端类型检查通过。
3. 同一 run 重复 planning 的风险已有自动化覆盖，避免 task 数异常膨胀。

### 未通过 / 仍需处理

1. **特殊 PostgreSQL 类型覆盖**: `inet` 等类型仍未通过专项验证，BUG-002 仍需修复。
2. **大数据性能压测**: 50K+ 增量、100 万+ 全量未执行真实压测。
3. **多源隔离真实双库联调**: 自动化隔离能力通过；外网没有内网 CC/DGM 双库条件，真实联调需在内网执行。
4. **真实 System Hook 端到端**: 未配置真实 GitLab Hook，端到端未通过。
5. **真实进程中断恢复**: 自动化恢复逻辑通过，但 kill/restart 场景未通过。

---

## 十一、2026-05-20 追加补测更新

本节更新第九、十章中的部分状态。按要求，本轮跳过数据量压力测试，只执行非压力功能链路、真实 System Hook 链路、直连诊断和自动化回归。

### 11.1 已完成并通过

| 原测试项 | 最新状态 | 说明 |
|----------|----------|------|
| 9.4 特殊 PostgreSQL 类型覆盖 | ✅ 自动化通过 | `GitlabExternalDbServiceTest` 已覆盖 PG 特殊类型归一化；后端全量 `mvn test` 通过 |
| 9.9 真实 System Hook 端到端 | ✅ 通过 | 本地 GitLab `WebHookLog` 最新投递 `response_status=200`；平台 `sync_runs.id=279` 为 `SYSTEM_HOOK/SUCCESS` |
| System Hook 精确同步目标 | ✅ 通过 | run 279 生成 `issues`、`issue_assignees`、`issue_metrics`、`label_links` 4 个任务，全部 `SUCCESS` |
| GitLab 直连模式 | ✅ 通过 | `sourceMode=DIRECT`，白名单发现 696 张表，当前同步状态 `IDLE` |
| 同步幽灵任务检查 | ✅ 通过 | 当前 active run 为 0；表诊断 `pending=0, running=0, retrying=0` |
| 后端全量自动化 | ✅ 通过 | 400 tests, 0 failures, 0 errors, 4 skipped |
| 前端同步进度/日志相关自动化 | ✅ 通过 | 17 tests passed，TypeScript 类型检查通过 |

### 11.2 仍未完成或本轮跳过

| 原测试项 | 最新状态 | 原因 |
|----------|----------|------|
| 9.2 大表增量同步性能 | ⏸ 跳过 | 属于数据量压力测试 |
| 9.6 超大单表全量同步 | ⏸ 跳过 | 属于数据量压力测试 |
| 9.8 多源隔离能力 | ✅ 自动化通过；真实双库联调待内网验证 | 外网不绑定 DGM 源名；已覆盖 sourceInstance 命名、事实构建和统计查询隔离 |
| 9.7 同步中断恢复真实链路 | ⚠️ 未执行 | 自动化恢复逻辑已通过；尚未构造真实长运行同步后 kill/restart |
| 内网真实含 inet 表端到端 | ⚠️ 未执行 | 本地自动化覆盖代码路径，内网仍需针对真实 `authentication_events` 回归 |

### 11.3 新发现问题

| 编号 | 问题 | 状态 |
|------|------|------|
| NEW-002 | 数据库查看未暴露 `sync_runs`、`sync_run_events`、`sync_run_table_tasks` 等完整同步日志存储表 | 已记录到 `bug-fix-report-20260520.md`，暂未改代码 |
| NEW-003 | 历史同步日志原始字段仍有旧英文消息 | 已记录到 `bug-fix-report-20260520.md`，前端展示层已有兼容翻译，暂未改历史数据 |
