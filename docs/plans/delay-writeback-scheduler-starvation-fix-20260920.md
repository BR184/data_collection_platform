# 延期标签写回：解除调度自锁并改为运行终态驱动的编排

## 进度与中间物

- 状态：**已实施并验证（2026-09-20）；本方案待用户验收后压缩入权威文档并删除**。
- 已完成变更（生产代码 8 个文件）：新增 `PlatformAsyncConfiguration`（具名 `platformAsyncExecutor`，`@Primary`）、`CustomerIssueDelayClosureOrchestrator`（三段编排 + 终态事件收敛评估）、`SyncRunCompletionListenerOrder`（监听顺序契约）；`CustomerIssueDelayClosureScheduler` 改为只做移交；`CustomerIssueDelayPreWritebackSyncService` 收敛为"提交阶段 1 + 返回 sealed Outcome"；`SyncRunWorkerService` 事实终态补发布并去掉发布守卫；`SyncRunFactRefreshListener` 标注顺序；`RealtimeWorkspaceService` 两处 `@Async` 显式指定执行器；删除超时配置项与死参数（`GitlabMirrorProperties`、`application.yml`）。
- 与方案的两处偏差（按目标版本择优，理由见 `docs/decisions.md` D-16）：①在飞状态按**数据源配置**索引——`GitlabSourceInstanceSupport.sourceInstanceOf` 当前是常量，按来源实例会让多个数据源共用一把锁；②跨周期判定改用**调度周期序号**，不引入 Clock 与时间精度问题。
- 测试状态：默认快速套件 `Tests run: 1389, Failures: 0, Errors: 0, Skipped: 1` 全绿。新增/重写：编排状态机 10 项、调度器轻量触发 2 项、提交结果语义 4 项、应用执行器与 `@Async` 归属 3 项、监听顺序契约 1 项、事实终态发布 2 项（含延缓态不发布）。`@Primary` 移除复现实验确认 `unqualifiedAsyncDoesNotFallBackToTheSchedulingPool` 必红。
- 真实链路（默认参数，未覆盖 `spring.task.scheduling.pool.size`）：两轮编排全部完成；调度线程只移交（1 条日志）且空闲时执行了其它定时任务；收敛评估在 `sync-run-worker-*`；阶段 3 在 `platform-async-2/4`；第二轮镜像终态读到 `unpublishedTargets=45` 后继续等待、直到 `FACT_REFRESH` 终态才推进；`pre-writeback sync timed out` 出现 0 次；运行派发排队时长 2s/0s/0s（缺陷时为 180s）；队列 54 条全部 `SUCCEEDED`、`FAILED` 为 0；线程栈中无任何线程阻塞在延期路径。
- 交付物：本方案（根因取证、目标版本实现、决策记录、测试与验证）。
- 未提交工作树改动（评审代码、文档、大量黄金快照）全部保留，与本工作单元无关。
- 下一步：用户验收后归档（`docs/architecture.md`、`docs/decisions.md` D-16、`docs/platform-page-business-rules.md` 第 14 条已同步完毕）。

## 1. 恢复线索

- 调查基点：`main` 的 `7565afd5` 加当前工作树。
- 恢复后首条命令：`git status --short`，随后读 `AGENTS.md`、`docs/progress.md`（2026-09-20 前两条）、本文件。
- 复现入口（默认参数即可复现）：`backend/run-backend.ps1` 启动后端，数据源
  `delay_label_writeback_enabled=true`，全局开关 `platform.gitlab-mirror.delay-label-writeback-api-enabled=true`。
- 现场证据文件：`.tmp/writeback-test/backend-cycle1-defaultpool.log`（默认池失败轮）、
  `.tmp/writeback-test/backend-writeback.log`（4 线程成功轮）、`.tmp/writeback-test/thread-dump.txt`。

## 2. 目标与边界

### 2.1 目标（两层，缺一不可）

1. **实例层**：延期标签写回在**默认配置**下必须能完成，且不再占用共享调度线程。
2. **类别层**：应用的异步执行器必须与调度线程池分离——`@Async` 不得再落到单线程调度池上。
   当前全仓 3 处 `@Async` 全部受影响，其中 `RealtimeWorkspaceService` 的两处服务于页面手动刷新，
   同属"长任务占用调度线程"这一类风险，必须一并纠正，否则同类缺陷会在别的功能上复发。

### 2.2 明确不做

- 不改写回判据、差异计算（`delayLabelChange`）、队列协议、标签白名单、重试/租约与 worker。
- 不改 `SyncRunDispatcherService` 的派发语义与周期。
- **不靠调大 `spring.task.scheduling.pool.size` 回避**（理由见 §6 否决）。
- 不新增持久化表、不加 Flyway 迁移。
- 不改任何端点产出（本方案只影响后台编排的触发方式与线程归属）。

## 3. 约束与背景

### 3.1 现有链路的责任方

| 环节 | 责任方 |
|---|---|
| 派发 `SUBMITTED/QUEUED` 运行 | `service/sync/SyncRunDispatcherService`（`@Scheduled`，默认 2s，**唯一**入口） |
| 镜像终态 → 提交来源级事实消费者 | `SyncRunWorkerService.publishRunCompletion` → `SyncRunCompletionEvent` → `SyncRunFactRefreshListener` → `SyncRunFactPublicationCoordinator.onMirrorCompleted` |
| 发布收敛判据 | `SyncFactPublicationStateService.countUnpublishedTargets`（按 `published_version`/`change_version` 栅栏，单一权威） |
| 延期事实重算 + 候选入队 | `FactBuildService.refreshCustomerIssueDelayFactsForConfig`、`CustomerIssueDelayLabelWritebackQueueService.enqueueCandidates` |
| 写回执行 | `CustomerIssueDelayLabelWritebackWorkerService`（`@Scheduled`，默认 5s） |

### 3.2 事件机制现状

- `SyncRunCompletionEvent` 携带 `runId/configId/sourceInstance/runType/status/appliedRows`，并已提供
  `mirrorRun()` 判定。
- **但它只对镜像运行发布**，且有两道原因：`SyncRunWorkerService.publishRunCompletion` 开头即
  `if (!isMirrorRun(run)) return;`；更关键的是 `executeSyncRun` 对事实运行是
  `if (runType == FACT_REFRESH) { executeFactRefreshRun(run); return; }` —— **提前返回，根本走不到发布语句**。
  因此"事实运行终态发布"必须落在该分支的终态处，删守卫是不够的。
- 事实运行的终态仍收敛在 `SyncRunWorkerService` 一处：`executeFactRefreshRun` → `finishRun(...)`
  （PAUSED/RETRYING 是延缓态、提前返回，不是终态），不存在第二条终态路径
  （`SyncRunExecutorService` 也只是委托 `workerService.executeRun`）。
- 全仓**唯一**订阅者 `SyncRunFactRefreshListener` 自己做了 `event.mirrorRun()` 过滤，
  因此扩大发布面不会激活其它逻辑。

### 3.3 Executor 现状

- 全仓没有显式配置应用异步执行器；`ThreadPoolTaskScheduler`（`taskScheduler`）本身实现了
  `AsyncTaskExecutor`，是上下文中唯一的通用 `Executor` bean。运行证据表明 `@Async` 就落在它上面。
- 已有两个具名 `ExecutorService`（`CodeReviewMetricEnrichmentConfiguration`），只服务代码评审指标补齐，
  与本链路无关；可作为命名与关闭方式的既有先例。
- `spring.task.*` 在 `application.yml`、`deploy/`、打包脚本与 compose 模板中**均无配置**，
  故调度池为 Spring Boot 默认的 **1 个线程**。

## 4. 证据与根因

### 4.1 运行证据（2026-09-20，本机 dev 后端 + 本地 GitLab + dev 库）

1. **默认池下必然失败**：`17:04:55 [scheduling-1] Queued sync run ... TABLE_REFRESH, sourceTables=[issues, notes, label_links, labels]`
   → `17:07:55 [scheduling-1] pre-writeback sync timed out, runId=3292, status=QUEUED`；
   `sync_runs` 行 `3292`：`created_at=17:04:55.395`、`started_at=17:07:55.645`、`finished_at=17:08:02.635`
   ——运行在超时同一瞬间才被派发，派发被推迟整整 180 秒；同期应有的自动增量同步（周期约 10.5 分钟）
   在 17:04–17:08 内**没有**产生任何运行。
2. **线程栈**：阻塞线程为 `scheduling-1`，
   `CustomerIssueDelayPreWritebackSyncService.sleep(:167) ← waitForMirrorRun(:117) ← refreshBeforeWriteback(:78)
   ← CustomerIssueDelayClosureScheduler.refreshCustomerIssueDelayFactsInternal(:56) ← refreshCustomerIssueDelayFacts(:42)
   ← AsyncExecutionInterceptor.lambda$invoke$0(:114) ← ScheduledThreadPoolExecutor$ScheduledFutureTask.run(:304)`
   ——`@Async` 被执行了，但**执行器就是调度线程池本身**，异步化没有把工作从调度线程上摘下来。
3. **同一份代码仅增加调度线程数即通过**（`-Dspring.task.scheduling.pool.size=4`）：
   `17:10:32 publication converged, mirrorRunId=3294, factType=ISSUE, waitedMillis=9`；
   各轮入队 `51 → 40 → 24 → 9 → 0 → 0`，队列 54 条全部 `SUCCEEDED`，写回成功 66 次、失败 0 次。

### 4.2 根因链

- **直接根因（自锁）**：延期编排的最长 360 秒轮询等待运行在共享调度线程池上，而它等待的镜像运行
  恰恰由同一线程池上的 `SyncRunDispatcherService` 派发 → 默认池为 1 时构成自锁：
  延期事实刷新与标签写回永不完成，且每个调度周期有最多 180 秒全局 `@Scheduled` 停摆
  （同步派发、事实 worker、写回 worker、系统钩子派发、补偿对账、BI CAT 镜像、备份、评审检索回填等）。
- **类别根因**：应用没有显式异步执行器，`@Async` 解析到唯一的 `Executor`（调度器），
  使"异步"方法实际占用调度线程。同类风险已存在于 `RealtimeWorkspaceService` 的两处 `@Async`
  （页面手动刷新）。
- **设计根因**：跨子系统收敛用"轮询 + 任意超时"表达。180 秒阈值无测量依据，且与内网负载无关——
  镜像运行一旦超过 180 秒（内网数据量远大于本机），即便修好自锁也会整轮跳过。这是"适应各种情况"
  必须消掉的东西，而不是调参能解决的。

## 5. 方案

### 5.1 目标设计：运行终态驱动的三段推进（无等待线程、无任意超时）

```
[每小时定时触发，本身不阻塞]
  └─ 若无本来源实例的编排在飞：提交阶段1，置在飞
       ├─ 阶段1：TABLE_REFRESH(issues, notes, label_links, labels)
       ├─ 阶段2：镜像终态 → 现有协调器自动提交来源级 FACT_REFRESH（编排不做事，只等收敛）
       └─ 阶段3：任一运行终态事件到达，且本编排在飞，且 countUnpublishedTargets(sourceInstance, ISSUE) == 0
                 → 在专属执行器上：重算延期事实 + 入队写回 → 释放在飞
```

- **收敛判据不变**：继续用 `SyncFactPublicationStateService.countUnpublishedTargets`（单一权威）。
  镜像增量为零时该判据立即为 0，阶段3 会直接执行，不存在"没有事实运行就当轮跳过"的漏洞。
- **上游事件已在手**：阶段1 完成后的镜像终态事件覆盖"协调器提交事实运行"这一环，
  不需要把事实发布逻辑搬进延期模块，也不改动镜像→事实的既有权威链路。
- **失败与活性**：阶段1 运行终态失败 → 释放在飞并记日志；在飞状态跨过一个完整调度周期后，
  下一周期强制重启编排。阶段3 的入队是 `upsert`（唯一约束 `source_instance+project_id+issue_iid`），
  重复执行幂等；阶段1 的重复提交会被 `SyncRunSubmissionService.findReusableForegroundRun`
  复用为同一活跃运行，不会产生并行镜像。因此"重启编排"不会造成重复写回或运行风暴。
- **在飞状态用内存保存**：进程内单飞即可，重启后 ≤1 个调度周期自愈，无需为此新增表与迁移。

### 5.2 事件发布面校准（单一事件类型，订阅方过滤）

在 `SyncRunWorkerService` 这一处把运行终态事件从"仅镜像运行"扩到"**覆盖事实运行终态**"：

- 事实运行分支（`if (runType == FACT_REFRESH) { executeFactRefreshRun(run); return; }`）在
  `finishRun(...)` 得到终态（SUCCESS/FAILED）后补一次事件发布；PAUSED/RETRYING 是延缓态，不发布。
- `publishRunCompletion` 的 `isMirrorRun` 守卫同时改为按"是否存在终态"判断，使成功与失败两条路径的
  发布规则一致（现有形态下镜像路径的成功/失败都在调用点发布，新形态保持同一语义）。
- 订阅方各自用 `event.mirrorRun()` / `runType` 过滤——唯一既有订阅者 `SyncRunFactRefreshListener`
  已经这样做，行为不变。

好处：只有一种运行终态事件、一条发布路径、一个发布类，避免为事实运行另立事件类型形成双轨。

**对既有链路的影响**：镜像运行的发布内容、时机、事务边界均不变；事实运行只是"多了一次事件发布"，
且当前没有订阅者会因此改变行为。自动增量同步（约 10 分钟一次）不受影响：延期编排的订阅者仅在
"本来源实例有编排在飞"时才做事，无编排在飞时它什么都不做。

### 5.3 异步执行器分离（类别修复）

新增一个具名应用异步执行器（命名体现用途，如 `platformAsyncExecutor`，参考
`CodeReviewMetricEnrichmentConfiguration` 的既有命名与 `destroyMethod` 先例），并把全仓
`@Async` 显式指定到它上面（`@Async("platformAsyncExecutor")`），不再让框架去猜：
调度线程池只跑短触发性任务，长任务一律在专属池上。范围含 3 处：
`CustomerIssueDelayClosureScheduler`、`RealtimeWorkspaceService` 的两处。

### 5.4 删除项（不留双轨、不留死配置）

- `CustomerIssueDelayPreWritebackSyncService`：删除 `waitForMirrorRun`、`awaitIssueFactPublication`
  两个轮询等待、`POLL_INTERVAL_MS`、`sleep()`，以及 `ACTIVE_RUN_STATUSES` 等随之失效的成员。
  该类职责收敛为"提交阶段1 + 声明编排在飞"。
- 删除配置项 `platform.gitlab-mirror.customer-issue-delay-pre-writeback-sync-timeout-seconds`
  及其在 `application.yml` 的环境变量绑定（无害化保留配置项属于双轨，必须删）。
- `CustomerIssueDelayClosureScheduler`：删除 `@Async`，调度方法只做轻量触发。
- 保留并继续使用 `customer-issue-delay-check-delay-ms`（触发周期）与
  `customer-issue-delay-pre-writeback-sync-tables`（阶段1 表清单，业务规则 5.3 第 14 条要求）。

## 6. 决策记录

| 编号 | 事项 | 选择 | 理由 |
|---|---|---|---|
| D1 | 用事件驱动替代轮询等待 | **采用** | 消除任意超时与线程占用；内网镜像耗时超过 180s 时仍能推进，这是"适应各种情况"的必要条件 |
| D2 | 事件发布面改为全部运行类型 | **采用** | 一种事件 + 订阅方过滤，避免为事实运行新增事件类型 |
| D3 | 显式异步执行器，而非调大调度池 | **采用** | 调度池大小不是根因；调参只是把自锁降级为"占用一个线程"，且 3 处 `@Async` 仍会互相影响 |
| D4 | 不新增编排状态表 | **采用** | 内存单飞 + 周期重启已保证活性与幂等；为可恢复性引入表与迁移不划算 |
| D5 | 不改写回业务语义 | **采用** | 本轮真实链路已证明差异计算与队列协议正确，缺陷只在触发与线程归属 |

已否决：

- **调大 `spring.task.scheduling.pool.size`**：治标；数值无测量依据；派发器仍在同一池内，
  其它阻塞任务照样互相挤占。
- **把轮询等待搬进专属线程**：不自锁了，但保留任意超时与线程占用，镜像超时仍整轮跳过；属换姿势的补丁。
- **为事实运行新增独立事件类型**：同一语义两种事件，长期维护要同时理解两套。
- **把延期写回并入镜像完成事件直接执行**：会绕开"写回前必须先增量刷新 issues/notes/label_links/labels"
  的业务规则（`docs/platform-page-business-rules.md` 5.3 第 14 条），且没有事实收敛保证。

## 7. 接口契约

- 无新增 API、无新增表结构、无新增端点，端点目录与黄金快照不受影响。
- 删除配置项：`platform.gitlab-mirror.customer-issue-delay-pre-writeback-sync-timeout-seconds`
  （`application.yml:135` 及 `CUSTOMER_ISSUE_DELAY_PRE_WRITEBACK_SYNC_TIMEOUT_SECONDS` 环境变量绑定）。
- 保留配置项：`customer-issue-delay-check-delay-ms`、`customer-issue-delay-pre-writeback-sync-enabled`、
  `customer-issue-delay-pre-writeback-sync-tables`、worker 相关项。
- 新增内部契约：异步执行器 bean 名、编排在飞状态的来源实例粒度（内部实现，不外露）。
- 不变契约：`countUnpublishedTargets` 语义、`SyncRunCompletionEvent` 字段、
  写回标签白名单与 `add_labels`/`remove_labels` 协议、队列表结构与状态机。

## 8. 测试与验证

### 8.1 单元测试（先加，后改实现）

- 编排状态机：无在飞时提交阶段1；有在飞时不重复提交；镜像终态未收敛时不执行阶段3；
  收敛为 0 时执行阶段3 并释放在飞；阶段1 终态失败时释放在飞并记日志；
  在飞跨过一个调度周期后允许重启；不同来源实例互不影响。
- 收敛为 0 且"本轮没有事实运行"时阶段3 仍执行（覆盖镜像零增量的分支）。
- **线程归属断言**：`@Async` 方法不得在 `scheduling-*` 线程上执行（防回归到调度池）；
  调度线程池在编排等待期间必须仍可派发其它任务。

### 8.2 真实链路复验（默认参数，不加任何 `spring.task.scheduling.pool.size`）

1. 开启写回全局开关与数据源开关，默认参数启动后端；
2. 断言日志出现 `publication converged`，且**不再出现** `pre-writeback sync timed out`；
3. 断言编排窗口内 `SyncRunDispatcherService` 仍能派发运行（在窗口内制造一次自动增量或再提交一次表刷新）；
4. 断言队列入队数最终收敛为 `0`，无 `FAILED`，GitLab 标签与事实一致；
5. 抓 `jstack` 确认阻塞不再出现在 `scheduling-*` 线程上。

### 8.3 门禁

本方案不改变端点产出，不需要重建黄金快照；默认快速套件必须全绿。

## 9. 风险与假设

- 假设：内网未通过环境变量设置调度池大小。已核对 `application.yml`、`deploy/`、打包脚本与 compose
  模板，均无该设置；**若内网另行设置过，本缺陷表现为"偶发跳过 + 全局调度空窗"而非完全失效**，
  实施时需现场核对一次环境变量。
- 事件驱动后，若事实消费者长期失败导致发布永不收敛，编排将不推进（这是正确行为：事实未收敛不该写回），
  下一周期重新发起编排；需在日志中给出可诊断的收敛等待信息。
- `RealtimeWorkspaceService` 的两处 `@Async` 迁到新执行器后，其线程占用与并发需要观察一轮，
  本方案只改线程归属，不改其业务语义与状态机。注意调度池原本只有 1 个线程，等于顺带把这些刷新
  串行化了；换到专属池后理论并发上升。缓解：刷新最终经 `submitTableRefresh/submitRun` 提交，
  `SyncRunSubmissionService.findReusableForegroundRun` 会复用同互斥域的活跃运行，真正的并行镜像不会增加；
  新执行器仍必须是有界池。
- **既有风险（非本方案引入，需一并裁决）**：`FactBuildService.refreshCustomerIssueDelayFactsForConfig`
  是"读到内存 → 改 3 个布尔 → 整行 upsert"；若写入期间有并发的事实发布改同一行，理论上旧值会覆盖新值。
  本方案把阶段3 放在发布收敛之后**缩短**了这个窗口，但没有消除。是否顺手改成只更新三列的定向更新
  （或加版本栅栏）属于新增范围，需用户确认后再做。
- 未验证：`@Async` 解析到调度池的确切 Spring 内部条件（Boot 3.5.16 下的 bean 条件与顺序）。
  方案以"显式指定执行器"消除该不确定性，并用 §8.1 的线程名断言把结论固化下来，而不是依赖推断。
- 已识别但本方案不处理的同类风险：`BackupScheduler`、`GitlabCompensationScheduler` 等若在未来引入
  长时间阻塞，仍会占用调度线程；实施时只做一次审计记录，不扩大改动范围。
