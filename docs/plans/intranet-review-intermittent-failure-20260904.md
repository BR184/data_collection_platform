# 内网评审页间歇性故障：复审结论与修复设计方案（2026-09-04）

## 进度与中间物

- 状态：**P0+P1 实施完成并全量验证通过（2026-09-04）**。
- 已实施：
  - P0-1：Mongo 同步 5 处 replace 方法改为"raw_payload 守卫 upsert + 键反连接点删"，删除 4 处 TRUNCATE；MySQL 同步 raw 行同模式，代码走查表改为"装载表原子换名接管 + id 序列所有权移交 + 提交后 ANALYZE"，删除 1 处 TRUNCATE。**无需新增迁移**。
  - P0-2：PageRecordSnapshotService 指纹分段计算器（3s 短超时模板、首败短路、5 分钟桶降级标记）+ readOrRefresh 降级读最近快照（含 STALE）。
  - P0-3：两个 @RestControllerAdvice 合并为 GlobalExceptionHandler（DataAccessException →"数据库操作失败，请稍后重试"）；兼容模式记录缺失改抛 BizException("该评审记录已被重新同步，请刷新列表后重试")。
  - P1-1：match 仓库 getRecordOrThrow/getRecordSourceOrThrow/listProblemItems/listRecordExperts/loadProblemStatusesByRecordIds 全部改点查（列清单常量 + IN 点查/单行查询），快照重建路径保留全量。
  - P1-2：readOrRefresh 进程内 single-flight（CompletableFuture 合并同键并发重建，失败共享）。
  - P1-3：评审写失效后单线程 daemon executor 异步预热 FILTER_OPTIONS 快照。
- 已验证：受影响单元测试 16/16 绿；testcontainers 真实 PG 集成测试 6/6 绿（Mongo 合并语义 4 项 + MySQL 换名/raw 合并 2 项）。
- 全量套件首跑（未注入测试库 env）1239 用例 4 failures + 68 errors，诊断结论：
  - **68 errors = 环境问题，非代码回归**：@SpringBootTest 类回退 `localhost:15433`（无监听）级联上下文加载失败（memory 环境陷阱第 13 条）；重跑必须注入 `TEST_DATASOURCE_URL/USERNAME/PASSWORD`。
  - **2 failures = 测试编码了未被产线服务的旧语义**：旧双 advice 中真正在产线生效的是 GlobalExceptionHandler（BizException→400，同事所见"服务处理异常"文案只存在于它的兜底），LabelGroupControllerTest/ReviewDataControllerTest 按 GlobalRestExceptionHandler 的 200 语义编写；已改期望为 400（响应体断言不变）。
  - **1 failure = 测试自身竞态**：single-flight 测试中第二个请求若调度延迟会错过合并窗口、合法地自行重建；已改为裸线程 + 轮询 WAITING（park 在 winner.join()）后再放行。
- 全量套件复跑（注入测试库 env）**1239 用例 / 1 failure / 0 errors / 1 skipped**：唯一失败为 `ReviewDataRecordReadSupportTest`（同事 2026-08-25 untracked WIP 文件，期望值不一致——git status 与 mtime 已复核），**零回归**。修复验证完成。
- 待办：提交与推送待用户确认——工作树含进行中的解耦第二阶段改动（Gitlab 执行器/统计/AGENTS.md 等约 130 项）与本单元无关，仅可摘取本单元文件清单（见「恢复线索」）；黄金基线门禁按 AGENTS.md 留待打包验收时运行。

## 决策记录（实施期修订）

- **P0-1 修订一（放弃 sync_generation 列）**：原方案的 generation 点删存在未变行缺陷——内容未变的行不落 update 则 generation 保持旧值，会被"删除 generation≠G"误删。改用**键反连接点删**（`not exists (select 1 from unnest(键数组))`）：未变行零写入、零误删，且**无需任何迁移**（不影响其他表格）。数组经 JDBC setArray/createArrayOf 传输，集成测试已实证。
- **P0-1 修订二（MySQL 代码走查表改用原子换名而非 upsert）**：该表唯一约束已被 V20260702_12 有意删除（老平台同一 MR 允许多条走查记录），ON CONFLICT 无冲突目标可用。装载表暂存结构本就存在，改为装载表原子换名接管（ACCESS EXCLUSIVE 仅毫秒级元数据操作），即方案讨论中否决过的"影子表交换"模式——对无唯一键的全量刷新表它是唯一正确选项。换名后需将 id 序列所有权 `ALTER SEQUENCE ... OWNED BY 新表.id` 移交新表，否则退役表持有序列所有权导致 DROP 失败（集成测试发现并已修复）；换名接管的新表由 `LIKE ... including indexes` 自带全部索引，提交后立即 ANALYZE 补统计。
- **P0-1 修订三（放弃内容摘要/全业务列元组守卫的 MySQL 变体）**：raw 行表有 raw_payload 可作单列守卫；代码走查表走换名路径无需守卫，避免了 49 列元组或摘要列的维护负担。
- **P1-3 修订（预热范围收窄为 FILTER_OPTIONS）**：列表快照键含筛选参数组合，写入侧无法预知下一个请求参数，预热"某个"列表变体无意义；并发重建惊群由 single-flight 合并兜底。

## 恢复线索

- 当前阶段：修复完成并全量验证通过（2026-09-04）；剩余动作仅为用户确认后的提交与推送。
- 恢复后首步：如需复验，backend 目录注入 `TEST_DATASOURCE_URL='jdbc:postgresql://localhost:15432/qaflex?currentSchema=qaflex_test,public' TEST_DATASOURCE_USERNAME=qaflex TEST_DATASOURCE_PASSWORD=<docker inspect qaflex-dev-postgres-15432 取 POSTGRES_PASSWORD>` 后运行 `mvn.cmd test`；判读标准 = 唯一失败 `ReviewDataRecordReadSupportTest`（同事 WIP），其余必须全绿。
- 实施涉及文件：`CodeReviewMatchModeMongoReviewSyncService.java`、`CodeReviewMatchModeSyncService.java`、`PageRecordSnapshotService.java`、`ReviewDataRecordService.java`、`ReviewDataMatchModeRecordRepository.java`、`common/exception/GlobalExceptionHandler.java`（合并，GlobalRestExceptionHandler 已删除）、对应测试 5 个（含 2 个 testcontainers 集成测试）。
- 上一份关联计划：`docs/plans/dropdown-option-config-20260903.md`（已完成）。

## 目标与边界

- 用户原始问题：内网离线环境评审页面点击编辑/新建偶发"加载失败"；同事编辑评审问题时报"服务处理异常，请联系开发人员排查"（C0001）；重试两次又恢复。
- 成功标准：评审页读写在任何后台同步运行期间不再出现超时/C0001；同步语义不变（老平台数据变化能反映到平台）；黄金基线全绿。
- 明确禁止：为掩盖问题加前端重试兜底而不治根因；保留 TRUNCATE 与 upsert 双轨；改动无关模块。

## 约束与背景

- 内网离线部署：单实例后端 + 本地 PostgreSQL；兼容模式（MatchMode）已启用，评审数据含老平台 Mongo 历史快照（负 ID 记录）。
- 前端默认请求超时 15s（`frontend/src/api-client/request.ts:14`）；后端 JdbcTemplate 查询超时 30s（`application.yml:33` `PLATFORM_QUERY_TIMEOUT_SECONDS`）；Hikari 20 连接（`application.yml:21`）。
- PG 未设置 `lock_timeout`/`statement_timeout`（JDBC URL 无参数）；死锁检测默认 1s。
- 开发期红线：直接改成目标版本并删旧路径，不留兼容层。

## 证据与根因

### 复审对旧结论的修正

1. ❌ 旧结论"getRecordDetail 走快照缓存重建"——错误。`ReviewDataRecordService.getRecordDetail`（:52-54）直查，不经快照；快照只覆盖 `listRecords`/`getFilterOptions`。
2. ❌ 旧结论"后台任务约每 10 分钟"——错误。补偿调度 60s、全量校验 cron 每分钟、SyncRunDispatcher 2s、FactRefreshTaskWorker 5s；仅 Mongo/MySQL match 同步为 10 分钟。
3. ⚠️ 旧三假设（快照重建超时/连接池耗尽/锁竞争）方向不误但过于宽泛，且遗漏了唯一具有决定性的具体根因（下述主根因）。

### 内网部署包实证（2026-09-04，二进制级确认）

验证对象：`qaflex-full-20260803T122027Z-ad35f6c0e8c3.tar.gz`（用户确认即内网正在运行的版本）。

1. **构建来源**：`RELEASE-MANIFEST.json` 记录 source.commit=`07431616428d`（分支 codex/fix-label-links-reconcile-20001，2026-08-03，workspaceState=clean）。该提交存在于本仓库。
2. **jar 完整性**：从镜像层提取 `app/app.jar`，SHA-256 `4fa9377b...` 与 manifest 的 backendJarSha256 完全一致——验证的即内网运行的二进制。
3. **二进制常量池验证**：`CodeReviewMatchModeMongoReviewSyncService.class` 含 4 处 `truncate table`、12 处 `review_data_match_mode` 表引用；`PageRecordSnapshotService.class` 含 `match-review:` 指纹段与 2 处 `review_data_match_mode_reports` 计数引用。
4. **配置验证**（包内 `.env` + jar 内 application.yml）：
   - 未覆盖 `platform.code-review.match-mode.mongo-sync-delay-ms` → 默认 **600000ms = 10 分钟**生效；`sync_enabled` 默认 true（DB 设置开关，用户确认已开启自动同步）。
   - `PLATFORM_QUERY_TIMEOUT_SECONDS=30` → 30s 查询超时**在内网已生效**（被锁阻塞的查询 30s 后抛 QueryTimeoutException，而非无限等待）。
   - Hikari 池 20 连接、`GITLAB_MAX_SYNC_THREADS=16`。
5. **版本对照**：内网提交 `07431616` 与当前 HEAD 之间共 525 个文件变更，但**本文全部 10 个故障路径文件（Mongo 同步/MySQL 同步/快照服务/评审记录服务/查询服务/命令服务/物化服务/仓库层/两个异常 advice）在两版本间零差异**——main 上的修复完成后可直接以增量更新包部署到内网，故障路径与本文分析一一对应。（注意：当前工作树中 PageRecordSnapshotService 有未提交的 dropdown 指纹段 +26 行，属下拉框选项设置功能的未提交改动，与本故障路径无关，实施时以工作树现状为基线。）
6. **异常误报面实证**：同事看到的文案"服务处理异常，请联系开发人员排查"是 `GlobalExceptionHandler.handleException`（:58）的兜底文案，证明该 advice 实际优先于 `GlobalRestExceptionHandler` 的 `DataAccessException` 专属 handler 被执行——数据库异常被泛化误报不是理论推测，是已发生的事实。

### 主根因：Mongo 评审同步的"事务性 TRUNCATE 锁风暴"

`CodeReviewMatchModeMongoReviewSyncService.replaceSnapshots`（:196-213）在**单个事务**里执行：

1. 对每个集合 delete + insert 全量 raw JSONB 文档（`legacy_mongo_imported_documents`，含 `document.toJson()` 完整负载）；
2. `truncate table` 4 张表（:252/:307/:364/:405）后批量重插全部评审报告/内容/问题/描述。

PG 中 TRUNCATE 立即获取 **ACCESS EXCLUSIVE 锁**并持有至事务提交。锁窗口 = 截断点 → 事务结束，随老平台数据量线性增长（秒级~分钟级），每 10 分钟一轮（启动后 45s 还有初跑，恰逢早晨集中登录）。

### 直接受害路径（全部有代码证据）

| # | 症状 | 链路 |
|---|------|------|
| 1 | 列表/筛选"加载失败" | `reviewDataSourceVersion()` **无条件** count `review_data_match_mode_reports`/`problem_details`（PageRecordSnapshotService:240-261）→ 每个列表/筛选请求（**哪怕命中缓存**）在指纹阶段排队 → 前端 15s 超时 →"评审数据加载失败：请求超时" |
| 2 | 编辑负 ID 记录"加载失败" | `getRecordDetail(-id)` → `buildRows()` 对 4 张 match 表**全表扫描**（ReviewDataMatchModeRecordRepository:488-544）→ 同步窗口内排队超时 |
| 3 | 编辑评审问题 C0001（快速报错） | 保存 → `materializeForMutation` 事务内多次读写 match 表（findMaterializedRecordId + getRecordSourceOrThrow 4 表全扫）↔ 同步 TRUNCATE → **PG 死锁 1s 快速抛错**或 30s QueryTimeout → DataAccessException → C0001"服务处理异常"（前端 15s 内收到，与同事描述吻合） |
| 4 | 编辑评审问题 C0001（ID 漂移） | 同步 insert **不带 id 列**（:254-259）→ 每轮 truncate+重插后自增 id 全变 → 编辑弹窗跨同步周期打开后保存 → `EmptyResultDataAccessException("兼容模式评审记录不存在")`（Repository:109/:222）→ C0001 |

"重试两次又好"：第一次重试仍落在同步窗口内，第二次窗口已过；或用户重开弹窗拿到新 id。

### 放大器（真实但次要）

- raw 文档表每 10 分钟全量 delete+insert JSONB → 表膨胀 + autovacuum 压力 → 全库性能随时间劣化。
- `readOrRefresh` 无 single-flight：写后失效 + 多用户并发未命中 → 并行全量重建惊群。
- 两个 `@RestControllerAdvice`（GlobalExceptionHandler / GlobalRestExceptionHandler）均有 `Exception` 兜底且无 `@Order` → DataAccessException 被前者抢先映射为 C0001"服务处理异常"，掩盖真实类别（应为"数据库操作失败"）。
- Hikari 20 连接 vs GitLab 同步 16 线程（`application.yml:111`）+ 密集调度共享同一池。
- MySQL match 同步同样 TRUNCATE `code_review_match_mode_records`（CodeReviewMatchModeSyncService:542），代码走查页同模式受害。

## 方案与步骤

设计参考的业内成熟模式：CDC/ETL 增量 upsert + 稳定键、原子表交换（blue-green swap）、single-flight 请求合并、stale-while-revalidate、超时分级快速失败、bulkhead 连接隔离。

### P0-1 同步改增量 upsert，废除 TRUNCATE（根治主根因，含路径 1/2/3/4 与表膨胀）

适用表均有唯一键：reports/problems/descriptions `unique(legacy_id)`、contents `unique(match_mode_report_legacy_id, content_order)`、raw 文档 `unique(collection_name, document_key)`、MySQL match 表按其稳定键。

1. 新迁移：各表加 `sync_generation bigint not null default 0` 列（raw 文档表同）。
2. `replaceSnapshots` 改写：
   - 每轮取全局递增 generation G（`code_review_match_mode_sync_state` 已有状态表可承载——V20260701_01:62 已建，加列或新字段均可，或用 `select coalesce(max(sync_generation),0)+1`）。
   - 全部 insert 改 `insert ... on conflict (<唯一键>) do update set <业务列...>, synced_at = excluded.synced_at, sync_generation = G where (<业务列元组>) is distinct from (<excluded 业务列元组>)` —— **行内容未变时不落 update**，`synced_at` 不动 → 指纹不漂移 → 不触发无效快照重建（否则把锁风暴换成重建风暴）。
   - 事务末尾点删消失行：`delete from <表> where sync_generation <> G`（走索引/条件扫描，行级锁，与读兼容）。
   - id 由 on conflict 天然保持不变 → 负 ID 跨同步稳定（路径 4 根除）。
   - ROW EXCLUSIVE 行锁与 ACCESS SHARE 读锁兼容 → 锁风暴与死锁面根除（路径 1/2/3 根除）。
3. MySQL match 同步（CodeReviewMatchModeSyncService:541-548）同模式改造：该链已用"临时表暂存 + `truncate + insert-select` 回填"（`replaceSnapshots(refreshCodeReviewRecords, summary)`），锁窗口较短但模式相同；改为 `insert ... select ... on conflict (source_instance, project_id, merge_request_id) do update`（V20260701_01:53 已有该自然唯一键）+ 按 generation 点删，同事务删除 truncate 调用。该表被 `codeReviewSourceVersion()` 指纹与代码走查页依赖，同属受害面。
4. 删除全部 5 处 TRUNCATE 调用，不留重建/双轨路径（首装空表时 upsert 即全量插入）。

### P0-2 版本指纹查询容错降级（防御纵深：指纹永不挂死页面）

`PageRecordSnapshotService`：

1. 指纹各 count 查询单独 try/catch + 短超时（新建 JdbcTemplate 副本设置 3s query timeout，不全局改 30s）。
2. 任一段失败 → 不抛异常，`readOrRefresh` 降级：按 page_key+snapshot_type+scope_key+rule_version+request_hash 取**最近一条（含 STALE）**直接返回，并 warn 日志标记降级。指纹恢复后自然走正常失效。
3. 该语义即 stale-while-revalidate 最小落地，改动集中在本服务单文件。

### P0-3 修正异常误报面（C0001 → 正确分类）

1. 合并两个 `@RestControllerAdvice` 为一个（职责重叠：BizException/NotFound/Exception 兜底各两份）：DataAccessException → "数据库操作失败，请稍后重试"；消除无 @Order 的映射不确定性。
2. 兼容模式 `getRecordOrThrow`/`getRecordSourceOrThrow` 的 `EmptyResultDataAccessException` 改抛 `BizException("该评审记录已被重新同步，请刷新列表后重试")`——业务语义归业务异常（400）。

### P1-1 match 表点查（消除全表扫描读放大）

`ReviewDataMatchModeRecordRepository`：`getRecordOrThrow`/`getRecordSourceOrThrow`/`listProblemItems`/`listRecordExperts`/`loadProblemStatusesByRecordIds` 改按 id/legacy_id 点查（单行 + `legacy_id in (...)`），不再 `buildRows()` 全量组装；`loadRecords`/`loadAllRecordSources`（快照重建专用）保留全量。物化事务持锁时间随之缩短。

### P1-2 readOrRefresh single-flight（防重建惊群）

进程内 `ConcurrentHashMap<缓存键, CompletableFuture<T>>` 合并同键并发重建；内网单实例部署，无需分布式锁。

### P1-3 写后异步预热（写后首个读不再必然冷重建）

`invalidateSnapshots()` 后异步触发 list + filter-options 重建（单线程 executor，经 P1-2 single-flight 去重），写入完成即返回，不拖慢写响应。

### P2 调优（单独决策，不在本单元必须范围）

- 在线请求与后台任务的 statement_timeout 分级；Hikari 池容量评估（20 → 30）或同步任务独立池（bulkhead）。
- 前端幂等 GET 自动重试 1 次（500ms 退避），覆盖残余瞬时故障。

### 实施顺序与验证

1. P0-1 → P0-3 → P0-2（一个后端工作单元）→ 全量默认套件 + 黄金基线（同步语义等价，基线应全绿）。
2. P1-1 → P1-2 → P1-3（第二个工作单元）→ 同上验证。
3. 新增测试：
   - 同步服务：两轮同步后 id 不变；内容不变时 `synced_at` 不推进（指纹不漂移）；老平台删除行被点删；新增/更新行正确 upsert。
   - 并发回归：同步事务进行中并发调用 listRecords/getFilterOptions/getRecordDetail（断言不阻塞、结果正确）；同步进行中并发编辑物化负 ID 记录（断言无死锁、无 C0001）。
   - 指纹降级：count 查询失败时返回旧快照。
4. 内网部署后验收：按时间点 grep 后端日志确认 `deadlock`/`QueryTimeout`/`Unhandled exception` 在评审页操作时段归零。

## 决策记录

- **已选**：增量 upsert + generation 点删（而非影子表 RENAME 原子交换）。理由：唯一键已具备，改动集中在同步服务与一条迁移；RENAME 方案需要双倍表与 DDL 权限，运维复杂度高，且无法解决 id 漂移以外的表膨胀治理之外的收益不成比例。
- **已选**：upsert 带 `is distinct from` 守卫使指纹不漂移。理由：指纹含 `max(synced_at)`，无守卫则每 10 分钟全表 update → 快照周期性无效重建。
- **已否决**：前端自动重试作为主修复。理由：治标不治根，窗口内仍失败；仅列入 P2 兜底。
- **已否决**：为兼容旧 TRUNCATE 行为保留重建开关。理由：开发期红线禁止双轨；首装空表 upsert 即全量。
- **待定**：P2 各项是否实施、何时实施。

## 接口契约

- 无 REST API 变更；无对外契约变更。
- 新增迁移：match 表族 + `legacy_mongo_imported_documents` + MySQL match 表增加 `sync_generation bigint not null default 0`。
- `PageRecordSnapshotService` 新增降级读旧内部方法（包内私有语义），公共签名不变。
- 异常映射变更：DataAccessException → C0001"数据库操作失败，请稍后重试"；兼容模式记录缺失 → 400"该评审记录已被重新同步，请刷新列表后重试"。

## 风险与假设

- ~~假设：内网实例启用了兼容模式同步定时器~~ → **已实证**（见「内网部署包实证」）：用户确认自动同步已开启且周期 10 分钟；包内配置无覆盖 → 代码默认 600000ms 生效；二进制含全部 TRUNCATE 路径。
- **可选交叉验证**（不阻塞实施，供部署后回归确认）：故障时间点是否与 10 分钟同步边界对齐：内网 `grep -n "deadlock\|QueryTimeout\|Unhandled exception\|服务处理异常" backend/logs/*.log`。
- 风险：upsert 批量按唯一键冲突顺序可能与物化写事务产生行锁等待（远轻于 EXCLUSIVE 锁，PG 行级等待 + 1s 死锁检测可自愈）；测试用例覆盖该并发场景。
- 风险：同步期间读请求可能看到"新旧混合"中间态（READ COMMITTED 下逐语句可见性）——现状 TRUNCATE 方案因事务快照反而全有或全无。评审列表/详情按行独立读取，混合态表现为部分行更新前后版本，与最终一致语义一致，可接受；黄金基线在同步静默期运行不受影响。
- 敏感约束：老平台 Mongo/MySQL 仅通过同步只读访问，不回写（维持现状）。
- 实施提醒：内网当前版本（07431616）与 main 的故障路径文件零差异，但增量更新包需按 `deploy/intranet-offline-packaging-standard.md` 流程制作并跑发布前黄金基线门禁。
