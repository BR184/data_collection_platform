# 进度与中间物

- 状态：生产实现和本地验证已完成。打包继续暂停，未运行新的打包进程；下一步是提交当前工作单元并在新内网包中按真实约 280 万总表规模验收一次性索引建设耗时和后续 10 分钟自动增量。
- 已完成：截图确认自动同步运行完成 25/25 个表任务但终态为部分成功，用户手工重试 `projects` 后成功；用户随后确认 `users` 也出现相同现象。
- 已完成：确认 `projects`、`users` 均在 `2fd5c34e` 新增的维表反向事实目标链路内；确认普通执行异常直接终态为 `FAILED`，现有自动恢复只处理租约超时的 `RUNNING`，人工按钮会另建单表刷新运行。
- 已完成：核验现场包基线 `4f145def` 同时包含上述维表反查、失败状态机和“部分成功仍派发事实”的实现；当前目标版本已同时具备事实来源代际门禁、动态 ODS 索引、批量事实目标登记和普通瞬时失败原任务重试。
- 已完成：用本地真实 Flyway schema 只读核验索引。`projects` 反查使用的 `issues.project_id`、`merge_requests.target_project_id` 有可用 active 条件索引；`users` 反查使用的 `issues.author_id`、`issue_assignees.user_id`、`notes.author_id`、`merge_requests.author_id/merge_user_id`、MR assignee/reviewer `user_id` 没有对应反查索引。不得再把两个表笼统归因为同一组缺失索引。
- 已完成：进一步确认全新部署的动态 ODS 表不会获得历史 Flyway 中的条件索引。Flyway 执行时 ODS 表尚未创建，`DO $$ ... to_regclass(...)` 会跳过；后续动态建表只创建主键唯一索引。因此内网新部署与本地历史数据库的索引状态不同，`projects` 在新部署同样缺少反查索引。
- 已完成：确认当前表任务虽然已有 `run_after/retry_count/max_retry_count` 字段，但普通异常仍直接写 `FAILED`，领取 SQL只读取 `QUEUED`，父运行也不会等待表任务重试，现有字段未形成状态闭环。
- 已完成：`GitlabMirrorIndexCatalog/Service` 在动态表存在后按物理列维护查询索引，失效并发索引可自动重建；运行规划先准备全部白名单 ODS 结构再入队。事实根详情按 1000 个 ID 分批读取，事实版本头和 outbox 按稳定目标键有界批量写入。
- 已完成：表任务异常按保留的来源错误、Spring/SQL 类型和 SQLSTATE 分类；瞬时异常在原任务进入 `RETRYING` 并复用父运行，租约超时走同一退避状态，确定性 schema/配置错误仍直接失败。失败页不推进 cursor、水位或事实发布门禁。
- 测试状态：后端全量 1161 项零失败、零错误、1 项环境条件跳过；`projects/users` 反向解析、全部依赖索引实际创建、无效并发索引修复、批量事实版本、任务/父运行重试、DIRECT/Docker 来源异常共 78 项定向与集成测试通过。Checkstyle 0 违规、SpotBugs 0 问题，可执行 JAR 构建成功；126 个 Flyway 迁移不可变性、破坏性审查、Profile 覆盖和隔离 schema 完整迁移链通过。
- 当前进行点：验证完成，审查并提交本工作单元；不进行打包和本地小数据容量外推。

## 恢复线索

- 当前阶段：实现、测试与构建已完成，执行工作树门禁和提交审查。
- 恢复后首条命令：`git diff --check; git status --short`。
- 上一相关计划：`docs/plans/investigate-gitlab-issues-sync-regression-20260805.md`。

## 目标与边界

- 目标：根治 `projects/users` 在自动增量中的首次建表竞态、反查索引缺失、高扇出逐根写入和瞬时失败不自愈问题，同时保持其他来源表、事实发布、删除反熵和同步水位语义不变。
- 成功标准：全新动态 ODS 表和已有缺索引表都获得当前查询契约所需索引；任一任务领取前全部选中 ODS 结构已准备；瞬时数据库异常在原任务有界重试且父运行等待；确定性契约错误直接失败；批量事实目标保持版本单调、幂等和事务原子性。
- 禁止：不增加旧接口转发、表不存在跳过、无条件重试或新旧状态双轨；不修改已执行 Flyway；不以本地小数据外推内网 280 万总量；本轮不继续打包。

## 约束与背景

- 现场版本基线为 `qaflex-full-20260806T111653Z-8269d2b61253.tar.gz`，源码对应 `main@4f145def` 的未提交发布工作树。
- 当前仓库 `main@b8a4b08b` 已包含后续事实发布修复；调查必须同时区分现场版本行为和当前目标版本行为。
- `projects`、`users` 都是 Issue、MR、集成测试三个事实族的业务来源依赖，不能把单表失败简单视为无影响。

## 证据与根因

### 已确认的失败状态机根因

- `SyncRunTableTaskExecutor.markFailure()` 对任意普通执行异常直接将当前任务写为 `FAILED`，并把表状态设为 `dirty_flag=true`、保存 `last_error`、增加 `retry_count`。
- `SyncRunTableTaskLeaseService.recoverTimedOutTasks()` 只把租约已过期的 `RUNNING` 重新排入 `QUEUED`；不会处理普通 `FAILED`。
- `SyncRunTableWorkerService` 只领取 `QUEUED`。因此同一自动运行中的普通失败没有自动重试机会，即使任务的 `max_retry_count=3`。
- 人工“重试失败任务”由 `GitlabSyncCommandFacade.retryFailedSync()` 查询 dirty/FAILED 表，再调用 `submitTableRefresh()` 创建新运行；这就是人工点击后能够成功的代码原因。

### 已确认的共同触发路径及引入版本

- `2fd5c34e`（2026-08-03，增量删除定向事实发布重构）新增 `GitlabFactChangeResolver` 和 `FactChangeTargetService`，把真实 ODS 变化后的事实目标反查放入 `SyncRunTablePageCommitService.commitScanPage()` 的同一平台事务。
- `projects`、`users` 都被声明为 `DIMENSION_REVERSE_LOOKUP`。一行维表变化不只更新自身 ODS 行，还会反查全部关联 Issue/MR 根，并逐根推进版本头和写事实 outbox；该工作失败会回滚整页 ODS、水位和目标，再由外层把表任务记为 `FAILED`。
- `projects` 反查 `ods_gitlab_issues.project_id` 与 `ods_gitlab_merge_requests.target_project_id`。这两列已有 active 条件索引，但一个项目变化仍可能返回大量根并逐根写 outbox，成本取决于关联基数。
- `users` 执行 7 组反查：Issue 作者、Issue 指派人、Issue 评论作者、MR 作者/合并人、MR 指派人、MR Reviewer、MR 评论作者。当前 Flyway 与动态 schema 管理没有为这些用户关联列建立匹配索引，因此除了结果高扇出，还会在多个大表上执行低选择性扫描。
- 现场包基线 `4f145def` 在父运行 `PARTIAL_SUCCESS` 时仍派发该运行已登记的事实目标，缺少按事实族完整依赖的代际门禁。故成功业务表的新 ODS 与失败维表的旧 ODS 可能被同一轮事实刷新混合读取。

### 仍需现场 `last_error` 区分的首次异常

- 若为 `statement timeout`、连接获取超时、锁等待或事务/租约异常：与维表高扇出、`users` 反查索引缺口及自动多表并发吻合。
- 若为 `relation ... does not exist`：说明新部署首轮并行创建动态 ODS 表时，维表反查先于依赖表创建完成；人工重试时依赖表已存在所以成功。
- 若为字段不存在或其他确定性 SQL 错误：按错误中明确的 `table.column` 回到血缘目录或物理 schema 契约修复。
- 在取得该字段前，只能确定“哪条新代码路径触发失败”和“为何失败后不自愈”，不能确定上述哪一种数据库异常是现场首次触发器。

内网只读取证 SQL（不触发同步、不修改数据）：

```sql
select
    task.id as task_id,
    run.run_id,
    run.run_type,
    run.trigger_type,
    run.status as run_status,
    run.resolved_worker_count,
    task.source_table,
    task.status as task_status,
    task.task_stage,
    task.row_strategy,
    task.page_number,
    task.rows_scanned,
    task.rows_applied,
    task.retry_count,
    task.max_retry_count,
    task.watermark_at,
    task.cursor_updated_at,
    task.scan_upper_bound_at,
    task.started_at,
    task.finished_at,
    task.last_error
from sync_run_table_tasks task
join sync_runs run on run.id = task.run_id
where task.source_table in ('projects', 'users')
order by task.id desc
limit 40;
```

### 影响边界

- `projects` 失败：该失败页事务整体回滚，`ods_gitlab_projects` 保持旧版本；新项目、项目改名、path/namespace 等变化不会提交。Issue/MR/集成测试事实中的项目名称、链接和项目范围可能陈旧或缺失。
- `users` 失败：该失败页事务整体回滚，`ods_gitlab_users` 保持旧版本；新用户、改名、状态变化不会提交。Issue/MR 中作者、指派人、Reviewer、合并人和评论作者等展示与统计可能陈旧或缺失。
- 两表都不会直接删除已有 Issue/MR 主事实，但现场包允许其他成功表先发布事实，因此可能得到“议题/MR 是新状态、项目或人员维度仍是旧状态”的跨代混合结果。
- 失败页不推进表水位；只要后续真正重试成功，仍会从旧 checkpoint 重读，不会永久跳过来源变化。问题是普通 `FAILED` 当前不会在同一运行自动重排，且现场包缺少完整依赖发布门禁。

## 方案与步骤

1. 建立动态 ODS 索引唯一目录，覆盖历史事实构建索引和维表反向事实解析索引；由 schema 服务在动态表存在后检查并以 `CREATE INDEX CONCURRENTLY` 只创建缺失索引。
2. 在白名单运行规划阶段按稳定顺序准备全部可用 ODS 表和索引，全部成功后才创建数据任务，消除并行 worker 首次建表竞态；worker 数据面只读取已准备结构。
3. 为表任务增加按异常类型分类的原任务重试：连接、超时、锁/死锁、事务回滚和临时资源不足进入 `RETRYING`；未定义表/列、非法配置和业务契约错误直接 `FAILED`。
4. 让领取 SQL只读取到期的 `QUEUED/RETRYING`，父运行在仅剩等待重试任务时复用运行级 `RETRYING`，到期后继续同一任务；达到上限才终态失败，失败页不推进水位。
5. 把事实版本头和运行目标登记改为有界 `VALUES/CTE` 批量写入，按稳定事实键排序并保持现有 fencing、单调版本和同运行幂等覆盖语义。
6. 以 PostgreSQL 集成测试和同步状态机单元测试验证全新/已有 ODS 索引、瞬时与确定性失败、父运行等待、批量版本单调、事务回滚、事实门禁及普通 Issue/MR/关系表回归。

## 决策记录

- 已选：直接修改现有权威实现和全部调用点，不增加兼容入口；用户已明确授权实施目标版本。
- 已选：动态 ODS 索引由运行期 schema 管理，而不是继续依赖“表可能尚不存在”的 Flyway 条件块；历史迁移保持不可变。
- 已选：复用现有 `sync_run_table_tasks` 的 `RETRYING/run_after/retry_count/max_retry_count` 字段和运行级调度机制，补齐缺失闭环，不引入第二套任务模型。
- 已选：结构准备覆盖当前白名单解析出的全部表，而不仅是本次手工指定表，确保维表反查和权威关系读取的依赖表在 worker 启动前存在。
- 已否决：仅凭“手工重试成功”归因于网络抖动；必须证明代码如何处理该瞬时失败以及为何不会自动恢复。
- 已否决：把 `projects/users` 一并归为“反查列都无索引”；`projects` 两个反查列已有索引，`users` 才存在系统性用户关联索引缺口。
- 已否决：只给普通失败增加无条件重试。确定性 schema/SQL 错误会重复失败，高扇出事务也会重复制造负载；目标实现必须同时约束维表影响解析成本、补齐必要索引、分类瞬时/确定性异常，并用事实依赖代际阻止跨代发布。
- 已否决：在事实解析器中捕获 `relation does not exist` 后跳过维表影响。该方案会提交 ODS 水位但静默丢失事实目标，破坏数据正确性。
- 已否决：只新增一条 Flyway 条件索引迁移。全新部署时动态 ODS 表仍晚于 Flyway 创建，问题会再次出现。

## 接口契约

- 不新增或改变对外 API，不新增业务表和迁移列。
- `GitlabMirrorSchemaService.prepareMirrorTablesForRun(config, options)`：控制面按稳定顺序准备本轮来源全集，任一表失败时不创建任何数据任务。
- 动态索引目录项包含稳定索引名、来源表、列顺序和条件谓词；仅当物理列完整时创建，已有有效同名索引保持不动。
- `SyncRunTableTaskLeaseService.deferOwnedTask(...)`：仅当前有效 owner 可把 `RUNNING` 原子改为 `RETRYING`，递增次数、设置 `run_after`、清租约且不修改游标/水位。
- `RunTableTaskSummary.nextRunAfter`：返回等待重试任务的最早到期时间，供父运行复用现有运行级调度。
- `FactChangeTargetService.registerChanges(...)` 对外签名和返回值不变，内部按 `platform.gitlab-mirror.fact-target-batch-size` 分批执行。

## 风险与假设

- 截图不能可靠读取 `projects/users` 任务的完整 `last_error`、attempt 和 cursor；最终首次异常若依赖具体 PostgreSQL 错误码，必须读取现场只读 SQL，而不能伪造确定答案。
- 本地当前数据库和 GitLab 数据量不代表内网 280 万总量，本轮只验证控制流与依赖，不做容量外推。
