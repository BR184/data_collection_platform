# 客户问题延期事实重算：收敛为只写三列的定向更新（方案 A）

## 进度与中间物

- 状态：**已实施并验证（2026-09-20）；本方案待用户验收后压缩入权威文档并删除**。
- 已完成变更：`FactBuildService`（新增 `updateCustomerIssueDelayFlags`、替换 `refreshCustomerIssueDelayFactsForConfig` 的写入调用）、`IssueFactSourceRowMapper.mapOpenCustomerIssue`（补映射 `id`）；测试 `FactBuildServiceTest`（新增回归锚点，旧实现必红）、新增 `FactBuildServiceCustomerIssueDelayFlagsTest`（真库写入面边界 3 项）。生产代码仅此 2 个文件。
- 测试状态：默认快速套件 `Tests run: 1377, Failures: 0, Errors: 0, Skipped: 1` 全绿；真实链路全列 diff：1506 行未变、5 行仅四列变化、越界写入 0。
- 门禁状态：黄金基线门禁 1 项失败 `data.reviewTypes`（`review-data/records/filter-options`），已取证归属为工作区未提交的 `ReviewDataFilterOptionService` 第 6 项评审类型，与本方案无关；未更新快照、未加掩码。
- 交付物：本方案（根因取证、目标版本实现、决策记录、测试与验证）。
- 关联：`docs/plans/delay-writeback-scheduler-starvation-fix-20260920.md`（调度自锁，独立缺陷，正交改动，仍待审批）。
- 未提交工作树改动（评审代码、文档、大量黄金快照）全部保留，与本工作单元无关。
- 下一步：用户验收后归档（`docs/architecture.md` 与 D-15 已同步完毕）。

## 1. 恢复线索

- 调查基点：`main` 的 `7565afd5` 加当前工作树。
- 恢复后首条命令：`git status --short`，随后读 `AGENTS.md`、`docs/progress.md`（2026-09-20）与本文件。
- 关键代码位置（改动前）：`service/FactBuildService.java:308`
  （`refreshCustomerIssueDelayFactsForConfig`）、`:338`（`batchUpsertIssueFacts` 调用）、
  `:712`（`loadOpenCustomerIssueFacts`）、`:844`（`batchUpsertIssueFacts` 实现）、
  `service/IssueFactPersistenceService.java:33`（`upsertIssueFacts`）。

## 2. 目标与边界

### 2.1 目标

让"客户问题延期事实重算"只写它真正拥有的三列
（`is_response_delayed`、`response_overdue`、`is_resolve_delayed`），
不再以内存快照整行覆盖已发布的事实行，也不再连带重写客户成员关系与搜索列。

### 2.2 明确不做

- 不改三列的计算规则、议题范围（project 325、open、`created_at_source >= 2026-01-01`）、调用方与触发时机。
- 不改 `IssueFactMapper.batchUpsert`（事实构建与发布主链路共用语句，零改动）。
- 不改 `IssueFactPersistenceService.upsertIssueFacts` 的既有语义（仍服务于事实构建）。
- 不为共用语句引入任何条件/版本守卫（理由见 §6 D2）。
- 不新增表、不加 Flyway 迁移、不改配置项、不改端点产出。

## 3. 约束与背景

- `issue_fact` 结构：`id bigserial primary key`，另有
  `unique (source_system, source_instance, project_id, issue_id)`；
  `fact_refreshed_at`、`created_at`、`updated_at` 均为 `timestamp not null default current_timestamp`。
- 延期三列的职责：事实发布时由 `service/IssueFactSourceRowMapper.java:139-161` 一并计算并写入；
  延期重算的唯一增量价值是**时间维度**——SLA 自然到期而数据未变时把布尔翻转。
- 现有 `batchUpsertIssueFacts`（`FactBuildService:844-849`）一次做三件事：
  1. `issueFactPersistenceService.upsertIssueFacts(batch)` → `issueFactMapper.batchUpsert`
     （覆盖 `issue_fact` 全部业务列，`mapper/IssueFactMapper.java:359-419`，无版本守卫）
     ＋ `customerMembershipRepository.replaceForFacts`（重写 `issue_fact_customer_members`）；
  2. `searchIndexRepairService.refreshIssueFactSearchIndexes(batch)`（重算并写回搜索列）。
- 仓库已有"只拥有部分列的任务用定向 update 写回"的既有范式：
  `service/FactSearchIndexRepairService.java:79`（只写搜索列的批量 `update issue_fact`），
  本方案沿用同一模式。
- 读取与写入的字段面必须对称：`loadOpenCustomerIssueFacts`（`:712`）是 `select f.*`，
  而写入过去是整行 upsert——这就是缺陷的结构性来源。

## 4. 证据与根因

### 4.1 根因

延期重算的写入路径把"刷新三列"实现成了"整行 upsert + 客户成员替换 + 搜索列刷新"，
而它的入参是**读入内存的快照**（`loadOpenCustomerIssueFacts` 读全部 open 的 325 议题事实，
逐行算布尔，最后统一写回）。任何在"读"与"写"之间提交的事实发布都会被旧快照覆盖，
且由于目标行的变化在 `sync_run_fact_targets` / `fact_change_heads` 中已被标记为已发布，
**没有任何机制会重发**——静默且不自愈，直到该议题下次变化或跑全量重建。

### 4.2 受影响面（三处，全部基于旧快照）

| 写入面 | 现状 | 后果 |
|---|---|---|
| `issue_fact` 全部业务列 | `batchUpsert` 无守卫整行覆盖 | `raw_payload`、`label_names`、`has_response`、`handler_name`、`planned_*`、`illegal_*` 等被回退 |
| `issue_fact_customer_members` | `replaceForFacts` 按快照重写 | 客户筛选范围被回退 |
| `issue_fact` 搜索列 | `refreshIssueFactSearchIndexes` 按快照重算 | 搜索/归一化索引被回退 |

### 4.3 具体情形（构造序列）

议题 X（325，open）：开发者补了一条 `### 1、修复状态` 的回复评论并把测试状态改成「已修复/完成」。

1. 自动增量同步抓到变化 → 登记该议题目标；
2. 事实发布落新事实：`raw_payload` 含新评论、`has_response=true`、`research_template_time` 有值、
   `label_names` 含新测试状态、`handler_name` 变化；
3. 延期重算的 `SELECT` 在步骤 2 之前完成，内存里是旧行；
4. 延期重算写回：步骤 2 的全部字段被回退，只有三个延期布尔是新的。

后果是页面/记录/统计显示旧状态，且可能据此写错 GitLab 标签；因已标记为已发布，不会自愈。

## 5. 方案与步骤

### 5.1 新增一条只写三列的定向批量更新

在 `FactBuildService` 中新增私有方法（放在 `refreshCustomerIssueDelayFactsForConfig` 与
`loadOpenCustomerIssueFacts` 附近，读取与写入同一处，沿用 `FactSearchIndexRepairService` 的范式）：

```
private void updateCustomerIssueDelayFlags(List<IssueFact> changedFacts)
```

实现要点：

- 用类内已有的 `jdbcTemplate` 与 `partition(...)` 分批（批大小沿用 `FACT_BATCH_SIZE`），执行
  `jdbcTemplate.batchUpdate(sql, ...)`：

  ```sql
  update issue_fact
     set is_response_delayed = ?,
         response_overdue = ?,
         is_resolve_delayed = ?,
         updated_at = current_timestamp
   where id = ?
  ```

- **按 `id` 匹配**，不按 `unique (source_system, source_instance, project_id, issue_id)` 匹配。
  理由：事实目标替换（`IssueFactPersistenceService.replaceRootFacts`）是"删除旧行 + 写新行"，
  会换新的 `id`；按 `id` 匹配时，陈旧快照自然落空（影响 0 行），下一轮重算从新行重算收敛；
  若按自然键匹配，则会把陈旧标志写到**刚刚发布的新行**上，属反向污染。
- **不写 `fact_refreshed_at`**：它的语义是"事实构建时间"，延期重算不是构建。
- **不再调用** `issueFactPersistenceService.upsertIssueFacts`、`customerMembershipRepository.replaceForFacts`、
  `searchIndexRepairService.refreshIssueFactSearchIndexes`。

### 5.2 替换调用点

`FactBuildService.refreshCustomerIssueDelayFactsForConfig`（`:338`）的
`batchUpsertIssueFacts(changedFacts)` 改为 `updateCustomerIssueDelayFlags(changedFacts)`。
方法的入参筛选逻辑（三值都相同则跳过）、返回结构与日志文案保持不变。

### 5.3 实施顺序

1. 先加测试（§8），使其在旧实现下必红；
2. 加 §5.1 的定向更新方法与 §5.2 的调用点替换；
3. 删除延期路径对 `batchUpsertIssueFacts` 的使用（该方法仍被事实构建路径使用，保留）；
4. 跑默认快速套件；
5. 用 §8.4 的真实链路复验固化结论；
6. 同步文档（§5.4）。

### 5.4 文档同步

- `docs/architecture.md`「事实与统计」段落补一条：延期重算只写三列、按主键匹配、不触碰成员与搜索列；
- `docs/decisions.md` 新增一条决策（编号顺延，D-15 或当前末位+1）：该任务的写入面边界与理由；
- `docs/progress.md` 记录实现与验证证据。

## 6. 决策记录

| 编号 | 事项 | 选择 | 理由 |
|---|---|---|---|
| D1 | 用定向更新替代整行 upsert | **采用** | 安全性来自"少写"：语句在结构上不具备覆盖其它列的能力，不依赖任何运行期条件成立 |
| D2 | 在共用 `batchUpsert` 上加版本守卫 | **否决** | 该语句被事实构建/增量构建/事实发布共用；守卫不成立时发布被**静默跳过**，比原缺陷更严重；且 `issue_fact` 无单调来源版本列，只能用墙上时钟近似 |
| D3 | 定向更新按 `id` 还是自然键匹配 | **按 `id`** | 目标替换会换新 `id`，陈旧快照应落空而非污染新行 |
| D4 | 追加时间戳 CAS 守卫 | **否决（改动了我此前的倾向）** | 新核实到两点：① `mapOpenCustomerIssue` 根本没有映射 fact 自身的 `updated_at`（`IssueFactSourceRowMapper` 只映射 `updated_at_source`），要加 CAS 必须给共享读取方法补映射；② `timestamp` 等值比较对精度/往返敏感，守卫不成立时是**静默不更新**——等于把"低概率瞬时偏差"换成"低概率静默漏更新"，且漏更新更不可观测。残留偏差（三列可能偏一轮）下一轮自愈，量级可接受。若将来需要更强保证，应在 `issue_fact` 上引入真正的单调来源版本列，而不是时间戳 CAS |
| D5 | 是否写 `updated_at` | **写** | 与改动前行为保持对齐（原路径也写它），语义上表达"该行被写过"；已核对 Java 侧无读取 `issue_fact.updated_at` 的消费方（`GitlabFactSourceSqlProvider:222` 读的是 `collect_form_records`） |
| D6 | 是否新增表/迁移 | **不新增** | 三列刷新不需要状态持久化 |

已否决的其它做法：让延期重算只更新"仍在 ODS 中存在"的行（不解决快照问题）；
在延期路径里先重读整行再比较后写（仍需整行写能力，且把窗口进一步拉长）。

## 7. 接口契约

- 无新增/删除 API、端点、表、配置项。
- 新增内部方法：`FactBuildService.updateCustomerIssueDelayFlags(List<IssueFact>)`（私有）。
- 写入契约（唯一变化点）：延期重算的写入面固定为
  `issue_fact(is_response_delayed, response_overdue, is_resolve_delayed, updated_at)`，
  按 `id` 定位；不再写其它列、不再写 `issue_fact_customer_members`、不再写搜索列。
- 不变契约：三列的计算规则与议题范围、`batchUpsert` 语义、事实构建/发布链路、队列表与写回协议。

## 8. 测试与验证

### 8.1 回归锚点（旧实现必红）

- `FactBuildServiceTest` 新增：延期重算调用定向更新，**不再**调用
  `issueFactPersistenceService.upsertIssueFacts` 与 `searchIndexRepairService.refreshIssueFactSearchIndexes`
  （用替身验证调用面；这条在旧实现下必红）。

### 8.2 真库集成测试（写入面边界）

沿用 `PostgresIntegrationTestDatabase` 与手写 schema 的既有范式
（见 `SyncFactPublicationStateServiceIntegrationTest`），断言：

1. 目标行的三列按新值更新；
2. 目标行的**其余列逐列相等**（重点 `raw_payload`、`label_names`、`has_response`、
   `handler_name`、`planned_resolution_at`、`illegal_reasons`）；
3. `issue_fact_customer_members` 的行数与内容不变；
4. 搜索列不变；
5. 非目标行（非 325、已关闭、`created_at_source` 早于 2026-01-01）不受影响。

### 8.3 边界

- `changedFacts` 为空时不发出 SQL；
- 条数超过 `FACT_BATCH_SIZE` 时分批执行且每批只含三列写入；
- 行已被替换（`id` 不存在）时影响 0 行、不抛错。

### 8.4 真实链路复验（本机 18181 + 本地 GitLab）

1. 对 project 325 全部 `issue_fact` 行做**全列快照**（写入临时文件）；
2. 开启写回开关、按既有方式跑一轮延期编排（含事实发布与写回）；
3. 再次全列快照并逐列 diff，断言：变化只出现在
   `is_response_delayed`、`response_overdue`、`is_resolve_delayed`、`updated_at` 四列；
   其它列零变化，`issue_fact_customer_members` 零变化；
4. 断言写回标签仍与事实收敛（沿用上一轮已建立的真实链路基线）。

### 8.5 门禁

本改动不改变端点产出，不需重建黄金快照；默认快速套件必须全绿。

## 9. 风险与假设

- 残留（已知且接受）：三列本身仍可能在"读→写"窗口内被并发发布落在其中，
  此时三列会偏一轮，下一轮重算自愈；最坏情况是单条议题的标签短暂抖动。
  本方案把影响面从"整行 + 成员 + 搜索列、静默不自愈"收窄到"三列、一轮内自愈"。
- 假设：`issue_fact.id` 在事实构建与发布链路中稳定（除非目标替换），已在 D3 说明其后果是落空而非污染。
- 未验证：本机没有真实并发发布的时间窗复现手段，§8.2/§8.4 验证的是"写入面边界"而不是"竞态是否发生"；
  竞态的消除依赖结构性收窄，不依赖时序测试。
- 与调度自锁方案的关系：两者正交。若先实施本方案，写回在默认调度配置下仍不会执行（自锁未解），
  因此 §8.4 的真实链路复验需按 `delay-writeback-scheduler-starvation-fix-20260920.md` 的方式临时绕过，
  或在自锁方案一并实施后再做。
