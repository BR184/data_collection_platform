## 进度与中间物

- 调查、方案、实现与本地验证完成，保留本计划用于用户内网验收交接。现场首次发布异常及容量归因仍待内网验收，不能把本次修复描述为已修复现场所有首错。
- 已改：FactTargetPublicationService 首错保留；ReadPublicationService/Repository 逻辑中止与有界物理清理；ReadMaintenanceService/Scheduler 独立维护任务；V20261015_02 及单条迁移锁；新异常回归和既有读模型/发布集成回归；长期契约归 D-26，现场指令归 runbook。
- 反例已证：隔离旧实现 5 项、4 失败（定向/全量/统一发布首错被覆盖 3 项，同步物理删除 1 项），0 错误/跳过。日志 `.tmp/fact-refresh-cleanup-fix-20261015/before-fix-tests.log`。首次修复后试跑仅测试编译失败（AssertJ 与泛型推断重载歧义），已用显式 int 修正，未改业务规避测试。
- 验证完成：`after-fix-tests-2.log` 134/0/0/0（异常保留 5、工作器 7、数据集 27、读生命周期 49、发布编排 19、事实运行执行器 10、运行工作器 17）；`combined-migration-tests.log` 9/0/0/0（原手工提交修复回归，20261013.12→当前 155 条迁移联合升级），合计 143 项。迁移不可变/破坏性检查/smoke 目录、产物路径、空白和 diff 检查均通过。
- 隔离与交付：Maven 仅写 `.tmp/fact-refresh-cleanup-fix-20261015/backend/target`，集成测试使用自行创建并关闭的 Testcontainers；未启停共享服务/栈或改既有库。9 个实现/测试/迁移文件与受测副本 SHA-256 一致，清单 `tested-source-sha256.json`；未全量/黄金/打包/部署，混合工作树未暂存提交推送。
- 已核验归档 SHA-256 为 `8f94ac63b6e644cf78cdcb76c6ab839471fadc8526bfe7ddd3c5e31f4075caf7`；清单后端 JAR 为 `312cb572ec00ec0441f2651d9b27cfaf8ed0a2b7d2926779ff295bab3280984c`，与上个工作单元提取的原包 JAR 一致，可复用该只读字节码证据。
- 从本包 Docker 归档逐层读取 `app/app.jar` 再算摘要，与清单及 100047Z 原包 JAR 三方一致；镜像归档摘要 `af1371caa29c0eaa31f5a3908eca727a35e8f946520c710ed1b1aac5dcdb5074` 一致。证据 `.tmp/fact-refresh-cleanup-audit-20261015/artifact-verification.json` 及 11 份原包类 `javap -c -p`。

### 恢复线索

当前阶段：本地完成、内网验收待接续；首条命令：`Get-Content .tmp/fact-refresh-cleanup-fix-20261015/after-fix-tests-2.log -Tail 20`。使用 runbook“事实候选中止与清理超时”检查新包、首错及待清理标识，不因失败候选逻辑中止就宣称重建成功。上一工作单元见 [手工重建提交修复](manual-fact-rebuild-publication-intent-fix-20261015.md)，两故障入口不同。

### 目标与边界

确认内网 AI 的“批次构建完成、清理超时、自动恢复、IO 原因”分别能否由发布代码支持，并修复已证实的错误覆盖与事实候选无界清理。成功标准为原始异常不丢、逻辑中止可提交、物理清理单批有界且可恢复、旧完整版本稳定、新候选可生产。只修改本单元必要代码；不改共享服务/已有库/超时/镜像策略/黄金快照或他人改动。

### 约束与背景

兼容模式；D-26 规定批次成功不等于完整版本公开。用户仅需基本验证，内网实际容量由用户测试。现有工作树混有其它 AI 改动，不能仅用当前源码代替发布工件。

### 证据与根因

截图无完整异常栈、会话超时设置或阻塞关系。当前发现相同删除 SQL 既用于中止未发布候选，也用于有界历史回收；需要用发布字节码及调用链区分。不能仅凭该 SQL 把异常归类为成功后的 GC。

原包字节码确认（当前源行号只用于导航）：

1. `ReadPublicationRepository:1133` 中止候选：按 `created_by_publication_id` 取全部父修订 ID，无 LIMIT，先删子关系再用数组删父行。`:1188` 历史回收：查询有 LIMIT，由 `ReadPublicationService.collectGarbage` 统一行预算控制；`ReadFallbackProperties:24` 默认 500。不能笼统称“所有过期删除无分批”。历史回收只由 `ReadMaintenanceScheduler → collectOneDomain → collectGarbage` 调度，不是 FACT_REFRESH 成功后的内联阶段。
2. `FactTargetPublicationService:376–396` 先执行完整发布事务；RuntimeException（执行权丢失另行透出）时调用 `ReadPublicationService.abort` 后才重抛原异常。若 abort 的 DELETE 再超时，则原发布异常没有被作为 cause/suppressed 保留，最后只看到清理超时；这是已证实的错误保留缺口，不能据最后 SQL 反推首错。abort 与发布均为独立 Spring 事务，失败的 abort 不会提交 ABORTED，候选可以留在 PROJECTING。
3. `SyncRunWorkerService:79–81` 把逃逸异常写为父运行 FAILED；`FactTargetPublicationService:511` 巡检选择终态父运行的活动候选并中止、释放，而非继续发布。`:456` 独立发布明确限定 `producer_task_id is null`，不能接管 517 这种绑定构建任务的候选。后续来源刷新可能重新消费未结算意图/heads，但不等于原 517 恢复成功。
4. 三族批次“全量构建完成”只证明候选写入，不证明投影成功、候选 PUBLISHED 或域指针移动。应逐事实族查询其当前指针及候选身份；一个父运行中可能有域已发布、其它域失败，不能笼统认定全成或全无效。
5. 原包 application.yml 与本包 compose 默认均为 `PLATFORM_QUERY_TIMEOUT_SECONDS=30`。数据库 statement_timeout=0 不会关闭 JDBC 定时取消；但截图无应用实际 env/调用会话/耗时证据，不能仅凭 `canceling statement due to user request` 或 Spring QueryTimeoutException 唯一确定取消者。
6. `ReadDatasetRepository.captureRelation` 有全源读取/JSONB 比较/NOT EXISTS 插入以及删除清单；`matchesCompleteSource` 另做完整业务行 JSONB 分组和 FULL JOIN 判等。代码确有大表重复处理的性能风险；原生镜像 worker 每次选一个最早符合条件的表，准备失败捕获后下次可重选同表，未见按失败表退避。默认 fixedDelay 为 5000ms（实际调用持续时间另加），不能把“固定 10 秒退避”写成代码事实。prepared 数据集也会在下次巡检重试发布，此恢复只适用 DATASET，不适用失败父事实运行的候选。
7. 现场 autovacuum 时长、5.5GB、IO 等待及 11 次重试未取得原始采样。若 DataFileRead 是 vacuum 自身的等待，只能证明 vacuum 在等读盘，不能证明它阻塞了 DELETE；需 DELETE 自身等待、阻塞 PID 与执行计划等证据。不能给出“十分钟到半小时必恢复”或“只是资源繁忙”的结论。

### 现场最小取证与下一步

- 517 从投影/统一发布开始至首次 DELETE 超时的完整应用异常栈；区分 `publishPreparedCandidate → abort → deleteCandidateRevisions` 与 `collectGarbage → deleteUnreferencedRevisions`。若首错已被覆盖且无日志，不应声称已定位，应先补最小原始错误保留/记录，再复现。
- 只读查询 `sync_runs` 中 517/518/519 的 `run_type,status,error_message`；查询 `read_publications` 中 `producer_run_id='517'` 的 `state,producer_task_id,abort_reason,published_at` 并关联 `read_publication_domains.current_publication_id`。518/519 若是镜像同步 SUCCESS，不能证明事实候选发布完成。
- 对实际故障会话核对 timeout、`pg_stat_activity` 的 DELETE 等待和 `pg_blocking_pids`；区分锁阻塞、扫描/外键检查与磁盘吞吐。现有证据不足以选择索引、批量上限或放大超时方案。
- 既有手工重建意图迁移只修提交 23502，不修这里的发布/中止清理链路；两个包后端相同，不能把更换 090845Z 为 100047Z 当作该故障修复。

### 方案与步骤

1. 核对发布工件和默认查询超时。
2. 核对中止、回收、发布失败、父运行终态和后续接管路径。
3. 核对镜像数据集发布写入、互斥和调度，区分性能风险与现场归因。
4. 汇总事实/被否决结论/内网必要证据，更新进度。

#### 本轮实施方案（用户已授权）

1. 为构建/全量构建/统一发布的异常路径补确定性测试：清理再失败时原异常仍为主异常，清理异常放入 suppressed 并保留发布身份日志；执行权失效规则不变。
2. 新增不可变迁移 `V20261015_02__aborted_fact_cleanup.sql`：只在发布元数据增加 `abort_cleanup_pending` 标识和 ABORTED 约束、待清理部分索引；不重写大事实表、不修改旧迁移。新迁移追加校验和。
3. 事实候选 abort 原子提交 ABORTED/原因/待清理标识并沿用既有元数据和归属释放；不在该事务删除事实父子修订。DATASET 的既有中止行为保持，镜像性能问题另按实际采证处理。
4. 复用 ReadMaintenanceService/Scheduler：每轮只选一个待清理候选；按域→版本锁序，SKIP LOCKED 让位；单事务最多删除 `gcBatchSize`（默认 500）个从未公开的父修订及其关系，仍有剩余则保留标识，下次继续。失败事务回滚本批，不丢待处理标识，不改变候选终态或旧版本。物理清理只接受 ABORTED 且待处理的精确身份。
5. 最小充分验证：先证明新反例在旧实现失败，再隔离 Maven 编译/定向测试；真实 PG 验证正反例、关系约束及迁移，并运行受影响发布/重建既有测试。共享 target、18080/18181 不动；不跑全量/黄金/容量，不打包。

### 决策记录

沿用用户调查优先要求；不以放大超时或反复重试代替定位。不进行小规模容量模拟。选用现有 ABORTED 终态加持久清理标识，物理垃圾不占活动候选名额；不新增发布成功路径或清理线程，不把失败改记 SUCCESS，不自动发布失败父运行的候选。

### 接口契约

无新增外部 API。内部事实中止的完成表示逻辑中止/释放生产归属，物理修订允许等待有界清理；新增发布元数据 `abort_cleanup_pending boolean not null default false`，只能在 ABORTED 时为 true。现有公开事实可见性、发布水位和 heads 结算契约不变。

### 风险与假设

现场 run 517/518/519、表容量、autovacuum 等仅为截图转述，尚无本地直接数据。两个发布包 JAR 摘要相同，不代表现场配置相同；数据库全局 statement_timeout=0 不自动证明应用连接会话值为 0。
