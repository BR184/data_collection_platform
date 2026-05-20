# BUG 修复报告

**修复日期**: 2026-05-20  
**基于测试报告**: deep-test-report-20260519.md  
**修复范围**: BUG-001 (P0), BUG-002 (P1), 安全阀机制

---

## 一、BUG-001 修复：增量同步无限循环（时区双重转换）

### 问题回顾

- **严重程度**: P0
- **现象**: 增量同步对 events 表（10K 行）创建了 3726 个 task，扫描 1.86M 行，所有 continuation task 的 cursor 值相同，永远不前进，最终导致 OOM 崩溃
- **根因**: `toGitlabSourceTime` 方法假设传入值为 JVM 本地时间并转换为 UTC，但 watermark 和 cursor 的时间基准不一致，导致 cursor 条件在 SQL 中形同虚设

### 修复方案

采用报告推荐的**方案 A：统一时间存储为 UTC**。

### 修改内容

#### 1. GitlabExternalDbService.java

**a) `normalizeJdbcValue` 新增时间戳归一化（读取时统一为 UTC）**

```java
Object normalizeJdbcValue(Object value) {
    if (value == null) return null;
    if (value instanceof OffsetDateTime odt) {
        return odt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
    if (value instanceof Timestamp timestamp) {
        return timestamp.toInstant().atOffset(ZoneOffset.UTC).toLocalDateTime();
    }
    // ... 其他类型处理
}
```

**b) 移除 `toGitlabSourceTime` 方法**

该方法原本将本地时间转换为 UTC，现在所有时间值在读取时已归一化为 UTC，不再需要转换。

**c) `buildCursorBatchScanSql` 直接使用 UTC 值**

```java
// 修复前：
LocalDateTime gitlabWatermark = toGitlabSourceTime(watermark);  // 错误的双重转换
LocalDateTime gitlabCursor = toGitlabSourceTime(cursorUpdatedAt);

// 修复后：直接使用传入的 UTC 值
watermark.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
cursorUpdatedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
```

**d) `buildTimeWindowScanSql` 同样移除转换**

```java
// 修复前：
LocalDateTime gitlabSince = toGitlabSourceTime(since);

// 修复后：直接使用 since
since.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
```

**e) `toLocalDateTime` 和 `extractUpdatedAt` 统一使用 UTC**

```java
private LocalDateTime toLocalDateTime(Object value) {
    if (value instanceof LocalDateTime localDateTime) return localDateTime;
    if (value instanceof OffsetDateTime odt) {
        return odt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
    if (value instanceof Timestamp timestamp) {
        return timestamp.toInstant().atOffset(ZoneOffset.UTC).toLocalDateTime();
    }
    if (value instanceof java.util.Date date) {
        return LocalDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC);
    }
    // ...
}
```

#### 2. SyncRunTableWorkerService.java

**`toLocalDateTime` 统一使用 UTC**

```java
private LocalDateTime toLocalDateTime(Object value) {
    if (value instanceof LocalDateTime localDateTime) return localDateTime;
    if (value instanceof Timestamp timestamp) {
        return timestamp.toInstant().atOffset(ZoneOffset.UTC).toLocalDateTime();
    }
    if (value instanceof OffsetDateTime offsetDateTime) {
        return offsetDateTime.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
    if (value instanceof Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
    return null;
}
```

#### 3. GitlabMirrorTableStorageService.java

**`toLocalDateTime` 统一使用 UTC**（读取 mirror 表中已归一化的 UTC 数据）

```java
private LocalDateTime toLocalDateTime(Object value) {
    if (value instanceof LocalDateTime localDateTime) return localDateTime;
    if (value instanceof Timestamp timestamp) {
        return timestamp.toInstant().atOffset(ZoneOffset.UTC).toLocalDateTime();
    }
    if (value instanceof java.util.Date date) {
        return LocalDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC);
    }
    return null;
}
```

### 修复原理

```
修复前的时间流转（有 BUG）：
源 DB (UTC) → JDBC 读取 → 可能转为本地时间 → 存储 → 读取 → toGitlabSourceTime 转 UTC → SQL
                                                                    ↑ 不一致的转换导致 cursor 失效

修复后的时间流转：
源 DB (UTC) → JDBC 读取 → normalizeJdbcValue 归一化为 UTC → 存储 UTC → 读取 UTC → 直接用于 SQL
                                                                    ↑ 全链路 UTC，无转换
```

---

## 二、BUG-002 修复：inet 类型序列化失败

### 问题回顾

- **严重程度**: P1
- **现象**: `jsonb_populate_record` 用于 upsert 时，inet 类型值被序列化为 `{"type":"inet","value":"172.18.0.1","null":false}` 而非纯字符串
- **影响表**: `authentication_events` 等包含 inet 列的表

### 修复方案

采用报告推荐的**方案 A：在读取时对特殊类型进行预处理**。

### 修改内容

#### GitlabExternalDbService.java — `normalizeJdbcValue`

```java
// 处理 PostgreSQL 特有类型（inet, cidr, macaddr, tsvector 等）
// PG 驱动是 runtime scope，通过类名反射检测
if (value.getClass().getName().startsWith("org.postgresql.util.PG")) {
    try {
        return value.getClass().getMethod("getValue").invoke(value);
    } catch (Exception ignored) {
        return value.toString();
    }
}
```

### 修复原理

```
修复前：
JDBC 读取 inet 列 → PGobject{type="inet", value="172.18.0.1"}
→ Jackson 序列化为 {"type":"inet","value":"172.18.0.1","null":false}
→ jsonb_populate_record 无法解析 → ERROR

修复后：
JDBC 读取 inet 列 → PGobject → normalizeJdbcValue 提取 getValue() → "172.18.0.1"
→ Jackson 序列化为 "172.18.0.1"
→ jsonb_populate_record 正确解析为 inet 类型 ✓
```

### 覆盖的类型

| 类型 | PGobject 子类 | 处理方式 |
|------|--------------|----------|
| inet | PGobject | getValue() → 纯字符串 |
| cidr | PGobject | getValue() → 纯字符串 |
| macaddr | PGobject | getValue() → 纯字符串 |
| tsvector | PGobject | getValue() → 纯字符串 |
| point/polygon | PGobject | getValue() → 纯字符串 |
| xml | SQLXML | 已有处理 |

---

## 三、安全阀：continuation task 数量限制

### 问题回顾

即使 BUG-001 已修复，仍需兜底保护防止异常情况下 task 无限增长导致 OOM。

### 设计要点

- **阈值不能写死过低**：业务实际数据量下限 200w 行；按 `batch_size = 500` 计算需要约 4000 个 continuation task；考虑表可能达到 2000w+ 行，需要预留 4 万 task 容量。
- **改为可配置**：通过 [GitlabMirrorProperties.java](backend/src/main/java/com/data/collection/platform/config/GitlabMirrorProperties.java) 暴露 `platform.gitlab-mirror.max-continuation-tasks-per-table`，默认 **50000**（覆盖至 2500w 行 / batch=500），异常场景仍能兜底。
- **职责单一**：本阀门只防止 OOM；cursor 不前进的真正 BUG 已经在 BUG-001 修复，这里不再承担"逻辑校正"职责。

### 修改内容

#### 1. GitlabMirrorProperties.java

```java
private int maxContinuationTasksPerTable = 50000;

public int getMaxContinuationTasksPerTable() {
  return maxContinuationTasksPerTable;
}

public void setMaxContinuationTasksPerTable(int maxContinuationTasksPerTable) {
  this.maxContinuationTasksPerTable = maxContinuationTasksPerTable;
}
```

#### 2. SyncRunTableWorkerService.java

```java
private static final int DEFAULT_MAX_CONTINUATION_TASKS_PER_TABLE = 50000;

// 构造器注入 GitlabMirrorProperties
public SyncRunTableWorkerService(
    SyncRunTableTaskMapper taskMapper,
    SyncRunTableStateMapper stateMapper,
    JdbcTemplate jdbcTemplate,
    GitlabConfigService configService,
    SourceTableReader sourceTableReader,
    GitlabMirrorSchemaService mirrorSchemaService,
    MirrorTableWriter mirrorTableWriter,
    GitlabMirrorProperties mirrorProperties) { ... }

// 在创建 continuation task 前检查
if (hasMore && !isRunCancellationRequested(task.getRunId())) {
  int maxContinuationTasks = resolveMaxContinuationTasksPerTable();
  long existingTaskCount = taskMapper.selectCount(
      new LambdaQueryWrapper<SyncRunTableTask>()
          .eq(SyncRunTableTask::getRunId, task.getRunId())
          .eq(SyncRunTableTask::getSourceTable, task.getSourceTable()));
  if (existingTaskCount >= maxContinuationTasks) {
    log.error("Exceeded max continuation tasks ({}) for table {}, runId={}, aborting further pagination",
        maxContinuationTasks, task.getSourceTable(), task.getRunId());
    markFailure(task, state, new BizException(
        "Exceeded max continuation task limit (%d) for table %s".formatted(
            maxContinuationTasks, task.getSourceTable())));
    return;
  }
  taskMapper.insert(createContinuationTask(task, lastCursor, batchSize));
}

private int resolveMaxContinuationTasksPerTable() {
  if (mirrorProperties == null) {
    return DEFAULT_MAX_CONTINUATION_TASKS_PER_TABLE;
  }
  int configured = mirrorProperties.getMaxContinuationTasksPerTable();
  return configured > 0 ? configured : DEFAULT_MAX_CONTINUATION_TASKS_PER_TABLE;
}
```

#### 3. 测试构造器更新

`SyncRunTableWorkerServiceTest` 的 `setUp()` 增加 `new GitlabMirrorProperties()` 第八个参数。

### 阈值容量速查表

| batch_size | 阈值 50000 可覆盖 | 阈值 50000 + 数据量 200w | 阈值 50000 + 数据量 2000w |
|------------|------------------|-------------------------|---------------------------|
| 200 | 1000w 行 | 1w task（足够） | 10w task（需调高） |
| 500 (默认) | 2500w 行 | 4000 task（足够） | 4w task（足够） |
| 1000 | 5000w 行 | 2000 task（足够） | 2w task（足够） |

> 若内网某张表预计超过 2500w 行，按 `2500w / batch_size` 估算后写入 `application.yml`：
> ```yaml
> platform:
>   gitlab-mirror:
>     max-continuation-tasks-per-table: 80000
> ```

---

## 四、测试验证

### 自动化测试结果

| 测试类 | 用例数 | 结果 |
|--------|--------|------|
| GitlabExternalDbServiceTest | 23 | ✅ 全部通过 |
| SyncRunTableWorkerServiceTest | 10 | ✅ 全部通过 |
| SyncRunTablePlanningServiceTest | 5 | ✅ 全部通过 |
| SyncRunSubmissionServiceTest | 8 | ✅ 全部通过 |
| SyncRunDispatcherServiceTest | 5 | ✅ 全部通过 |
| SyncRunLeaseServiceTest | 4 | ✅ 全部通过 |
| SyncRunWorkerServiceTest | 7 | ✅ 全部通过 |
| SyncRunStatusServiceTest | 2 | ✅ 全部通过 |
| GitlabCompensationSchedulerTest | 2 | ✅ 全部通过 |
| GitlabSystemHook*Test | 13 | ✅ 全部通过 |
| MirrorTableWriterTest | 1 | ✅ 全部通过 |
| GitlabWhitelistServiceTest | 4 | ✅ 全部通过 |
| GitlabConfigServiceTest | 16 | ✅ 全部通过 |
| FlywayMigrationSmokeTest | 5 | ✅ 全部通过 |
| **合计** | **105** | **✅ 全部通过** |

### 已更新的测试用例

`GitlabExternalDbServiceTest` 中 3 个测试的期望值已更新，反映移除 `toGitlabSourceTime` 后的正确行为：
- `shouldQuoteSourceTableAndTimeColumnForWindowScans`
- `shouldBuildCursorBatchScanSqlWithUpdatedAtAndPrimaryKeyCursor`
- `shouldBuildCursorBatchScanSqlWithoutCursorForFirstBatch`

### 无关的已知失败

`StatisticBoardControllerTest.shouldExposeGitlabLinksInIssueStatisticDetails` 失败与本次修复无关，是 commit `6ec6164`（重构 issue link 处理）引入的问题。

---

## 五、部署注意事项

1. **首次部署后需重新全量同步**: 由于时间存储基准从"可能的本地时间"变为"统一 UTC"，已有的 `lastWatermarkAt` 值可能不一致。建议部署后对所有 config 执行一次全量同步以重置 watermark。

2. **无需数据库迁移**: 本次修复仅涉及 Java 代码层面的时间处理逻辑，不涉及数据库 schema 变更。

3. **向后兼容**: 修复后的代码对全量同步无影响（全量同步不使用时间 cursor），仅改善增量同步和补偿扫描的行为。

4. **监控建议**: 部署后观察 `sync_run_table_tasks` 表的增长速率，确认增量同步不再出现 task 爆炸现象。

---

## 六、BUG-003 评估：全量同步期间新增数据的 watermark 边界

### 问题回顾

- **严重程度**: P2
- **现象**: 全量同步耗时较长（内网大表可能数分钟），在此期间源表新写入的数据 `updated_at` 与 `findFullSyncWatermark()` 在最后一批结束时取得的 `MAX(updated_at)` 之间可能存在边界差异，导致首次增量同步会重复扫描这部分数据。

### 处理决策：**不修复，标记为可接受 trade-off**

原因：

1. **数据正确性已保障**: mirror 表写入使用 `INSERT ... ON CONFLICT DO UPDATE` (upsert)，重复处理是**幂等**操作，**不会产生重复行**，也**不会丢失数据**。

2. **重复扫描的开销可控**: 全量同步耗时通常在分钟级，期间新增的数据量相对全表非常小。首次增量同步重复扫描这部分数据的代价远低于引入 `sync_start_watermark` 机制的复杂度。

3. **遗漏数据的风险已规避**:
   - 当前实现使用 `MAX(updated_at)` 作为 watermark，保证 `watermark >= 全量批次最后一行的 updated_at`。
   - 由于 watermark 取最大值（而非最小值），不会出现"watermark 越过新数据"的遗漏场景。
   - 只有当源数据库存在**时钟回拨**（系统时钟向后跳）时，理论上才可能遗漏，这是基础设施级问题，不在应用层修复范围。

### 监控建议

- 在首次全量同步完成后，观察首次增量同步的 `rows_scanned` 是否远大于 0。
- 若内网部署后发现首次增量同步的重复扫描量过大（如 >1% 全表），再评估引入 `sync_start_watermark` 的必要性。

---

## 七、其余建议优化项的处理状态

测试报告"建议优化（部署后）"章节列出 4 项建议，分别处理如下：

### 7.1 建议 #3：continuation task 安全阀 ✅ 已实施

见本报告第三章。当前实施为可配置上限（默认 50000），覆盖至 2500w 行 / batch=500。

### 7.2 建议 #4：task 执行超时机制 ✅ 现有机制已覆盖

`SyncRunTableWorkerService.recoverTimedOutTasks()` 已实现基于 `lease_until` 的 task 超时回收：
- task 被 claim 时设置 lease（默认 30 秒），由运行中的 worker 通过 heartbeat 续约。
- worker 异常退出或 task 卡死时，lease 到期后被恢复线程重置为 QUEUED 或标记为 TIMEOUT（依据 retry_count）。
- 此机制比"单 task 超过 5 分钟自动失败"更细粒度，且能区分"短暂卡顿"与"worker 死亡"。

无需新增 wall-clock 超时机制；现有 lease + heartbeat 已足够。

### 7.3 建议 #5：定期清理已完成的历史 task ⏸ 暂不实施

原因：
- `sync_run_table_tasks` 表的膨胀主要由 BUG-001（已修复）和异常 continuation 链（已加安全阀）触发，正常场景下增长速率可控。
- 清理策略涉及业务保留期（如保留 N 天历史用于审计），属于运营策略而非代码问题。
- 当前建议通过运维手段定期归档（例如每月将 `finished_at < now() - interval '30 days'` 的 task 迁移至归档表）。

如后续监控显示正常运行下表膨胀仍是问题，再评估在 `GitlabMirrorScheduler` 中加入自动清理任务。

### 7.4 建议 #6：内网大表使用更大的 batch_size ⏸ 不在本次修复范围

属于运行时调参，可通过修改 `GitlabSyncConfig.batchSize` 调整（无需代码改动）。建议在内网压测后根据实测吞吐量决定最终值。

---

## 八、未通过补测项的后续行动计划

测试报告第十章列出 5 项"未通过 / 仍需处理"的补测项，本次修复后状态如下：

| 补测项 | 本次修复后状态 | 后续行动 |
|--------|---------------|----------|
| 9.4 特殊 PostgreSQL 类型覆盖 | ✅ inet/cidr/macaddr/tsvector 等已通过反射统一处理 | 内网部署后对 `authentication_events` 等含 inet 列的表做端到端回归 |
| 9.2/9.6 大数据性能压测 | ➖ 本次未涉及 | 内网预发布环境执行；重点观察 task 表增长、内存占用 |
| 9.8 DGM 多源同步 | ➖ 本次未涉及 | 先修复 DGM proxy 网络连通性（运维侧） |
| 9.9 真实 System Hook 端到端 | ➖ 本次未涉及 | 内网部署后配置真实 GitLab System Hook 回调 backend |
| 9.7 真实进程中断恢复 | ➖ 本次未涉及（自动化已覆盖） | 内网部署后执行 kill backend + restart 验证 |

### 内网部署验证清单

部署后建议依序执行以下端到端验证：

1. **inet 类型回归**: 触发一次包含 `authentication_events` 表的全量同步，确认无 `invalid input syntax for type inet` 报错。
2. **增量同步收敛性**: 在源表插入少量数据，触发增量同步，确认 task 数量 ≤ `ceil(变更行数 / batch_size) + 1`，无 task 爆炸。
3. **watermark 推进**: 增量同步完成后查询 `sync_run_table_states.last_watermark_at`，确认推进到源表最新 `MAX(updated_at)`。
4. **安全阀验证**（可选）: 临时将 `max-continuation-tasks-per-table` 调至 10，故意制造小 batch_size 触发上限，确认 task 被标记为 FAILED 而非无限增长。
