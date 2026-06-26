# 内网测试问题根因同步

> 更新时间：2026-06-26
> 依据范围：本文件只同步本次按代码重新确认的原因和用户已补充的信息，不沿用旧诊断文档中的未验证推测。

## 总览

| 问题 | 当前原因判断 | 状态 |
| --- | --- | --- |
| 客户问题模块大部分页面空表格、连模块名也没有 | 客户问题页面默认注入顶部“测试阶段”筛选，筛选按 `milestone_title` / `testing_phase` 匹配；如果内网 CC_Product 议题的里程碑与系统设置父级阶段不一致，`CustomerIssueTestingPhaseFilterSupport` 会把数据全部过滤掉。部分页面又从过滤后的结果生成模块行，导致模块目录也为空。 | 已确认代码原因 |
| 客户问题延期问题只有“未设定模块”且数量为 0 | 延期问题页先用客户问题范围取 `rowSources`，再按默认测试阶段筛选；若默认测试阶段不匹配，只有兜底的“未设定模块”行会被保留，延期计数来自 `finalSources`，因此全为 0。 | 已确认代码原因 |
| 系统测试/系统测试非法数据比老平台多 | 新平台系统测试范围同时命中 `testing_phase`、`system_test_label` 和 `label_names` 中包含“系统测试/回归测试”的记录；老平台记录列表主要按项目和测试阶段定义展开后的阶段过滤。范围更宽会导致非法记录偏多。 | 已确认代码原因 |
| 系统测试/议题查询少约 2000 条 | 议题查询使用 `Scope.ALL`，但测试阶段条件走 `phase_filter_value`，和系统测试非法数据使用 `Scope.SYSTEM_TEST + testing_phase` 的口径不同；同时默认项目和阶段展开路径不完全一致，导致同一测试阶段下数量不一致。 | 已确认代码原因 |
| 系统测试/缺陷原因分析、议题阶段统计 15 秒超时 | 这两个统计看板不是 SQL 分页加载。接口先从 `issue_fact` 全量加载符合过滤的事实数据，再在 Java 内存中做规则流、模块/阶段聚合和明细分页；前端请求默认 15 秒超时，因此数据量大时会超时。 | 已确认代码原因 |
| 系统测试和客户问题部分看板能加载但接近 15 秒 | 同类统计看板普遍是“数据库拉取一批事实 -> Java 规则流过滤/聚合 -> 前端本地分页”。系统测试缺陷汇总、客户问题缺陷汇总、客户问题延期、客户问题缺陷原因、按功能展示、响应效率等页面也存在全量或大范围事实加载，页面能返回只是当前数据量/筛选条件还没突破 15 秒。 | 已确认代码原因 |
| 部分页面默认范围是全部测试阶段，而不是老平台默认阶段 | 老平台相关页面会默认使用当前测试阶段，例如当前样例 `CC2026R3`，或使用阶段列表第一项；新平台客户问题顶部范围前端配置为 `defaultStrategy = empty` 且显示“全部测试阶段”。这会扩大默认查询范围、放大统计看板全量聚合耗时，并导致页面数量与老平台默认口径不一致。 | 已确认代码原因 |
| 系统测试缺陷汇总首屏数据抖动，先显示总计 0 再显示完整数据 | 前端统计板 `route.query` watcher 首次立即请求；系统测试阶段数据范围选项异步加载后，`useDataScope` 再按 `first-available` 写入 `testingPhase` 并触发第二次请求。第一次请求没有 `testingPhase` 时，后端 `SystemTestDefectSummaryBoardService` 明确把有效数据置空但仍追加“总计”行，因此短暂显示总计 0；第二次带默认阶段后才显示完整数据。 | 已确认代码原因 |
| 单表刷新、手动增量同步突然很慢、拉取量很大 | 增量任务只带 `last_watermark_at`，没有带 `last_cursor_pk`，SQL 边界为 `updated_at >= watermark`，会反复拉取同一水位时间戳上的历史行。手动增量同步还会规划全部白名单表，不是只刷新当前页面表。 | 已确认并已修正同步边界 |
| 页面“刷新最新数据”比预期重 | 统计页刷新不是只刷新表格结果，而是先刷新背后的 GitLab 镜像原始表，再重建事实表。例如系统测试/客户问题会刷新 `issues/projects/users/label_links/labels/notes`，代码走查会刷新 MR 相关镜像表，然后重建 `issue_fact` 或 `merge_request_fact`。 | 已确认代码原因 |
| 同步合并仍会产生额外运行记录 | 当前 `SyncRunSubmissionService` 在复用/合并已有运行时会插入状态为 `MERGED` 的 `sync_runs` 记录。它不会被调度执行，但会让运行历史、状态展示和后续排查看起来像创建过额外同步。用户期望是被吸收的同步不能进队列，甚至不创建运行单元。 | 已确认代码差距 |
| 事实重建有时从增量退化成全量 | `FactBuildService` 在发现既有事实缺少搜索索引或阶段派生字段时，会让 `changedSince = null`，下一次事实构建就不带增量谓词，表现为全量重建。升级后旧事实表缺字段或索引为空时尤其容易触发。 | 已确认代码原因 |
| 代码走查非法数据少约 6000 条 | 不能再归因于 MR 29874 模块为空。用户已确认 MR 29874 在镜像库 `merge_request` 和 `merge_request_fact` 中存在，且模块名为“平台”。当前只能确认新平台非法判断依赖 `review_exception_reason`、`scan_status`、`scan_bug_count`、`annotation_rate_result`、`bug_count_result`、`project_name/module_name` 占位值和 GitLab 报错字段；后续应通过构造不同测试数据，对比新老平台页面规则筛选差异，确认哪些老平台非法条件没有命中新平台谓词。 | 已纠正旧结论，需规则差异对比 |

## 客户问题模块范围补充

客户问题模块的数据范围必须记为 `CC_PRODUCT` / `CC_Product`，不是系统测试 CrownCAD 项目范围。依据来自 `C:\Users\admin\Downloads\产品客户问题响应管理机制.mm` 中“数据来源为 CCPRODUCT 数据”的需求说明，以及常驻规则 `docs/platform-page-business-rules.md`：

1. 默认项目为 `CC_Product`，当前老平台项目 ID 为 `325`。
2. 默认统计 2026-01-01 之后创建的客户问题议题。
3. 客户问题页面的顶部范围切换按里程碑/版本体验对齐老平台，优先匹配 `milestone_title`，再兼容按父级阶段展开后的 `testing_phase`。
4. 客户问题缺陷汇总、延期问题、非法数据、缺陷原因、按功能展示和响应/解决效率都不能回退到 CrownCAD 系统测试项目 `9` 的筛选口径。

后续排查客户问题页面空表时，第一步应先确认 `project_id=325`、创建时间下限、里程碑/阶段匹配和客户问题公共排除规则是否同时成立；不能用系统测试页面的 `testing_phase` 命中结果直接判断客户问题模块是否有数据。

## 关键代码证据

### 1. 加载超时不是默认分页失效，而是统计看板全量聚合

- 前端默认请求超时是 `15_000ms`：`frontend/src/api-client/request.ts`。
- 统计看板主接口没有 `page/size` 参数；表格分页是前端本地 `slice`：`frontend/src/composables/useStatisticBoardTableState.ts`。
- `SystemTestDefectCauseBoardService` 和 `SystemTestPhaseStatisticsBoardService` 都是 `loadSources(...) -> buildRuleFlowSnapshot(...) -> PageSliceSupport.slice(...)`，即先全量加载事实再聚合。
- 记录类页面不一样：`IssueFactRecordRepository.findPage(...)` 会先 `count(*)`，再 SQL `limit/offset`。

结论：缺陷原因分析、议题阶段统计的 15 秒超时主要来自统计看板全量事实加载和 Java 聚合，不是“所有表格默认分页失效”。

同类风险页面已经扫到：

| 页面 | 当前加载模式 | 性能风险 |
| --- | --- | --- |
| 系统测试缺陷汇总 | `IssueFactBoardRuntimeSupport.loadFacts(...)` 读取事实后，在 Java 中做系统测试范围、默认模块范围、阶段和模块聚合。 | 没选阶段时仍会先读系统测试范围事实；选阶段后仍不是 SQL 聚合。 |
| 系统测试缺陷原因分析 | `IssueFactQueryService.query(FACT_SQL, filters, phasePredicate...)` 拉取阶段范围事实，再 Java 聚合原因和模块。 | 阶段谓词进 SQL，但明细分页和聚合都在内存侧完成。 |
| 系统测试议题阶段统计 | 同样先查询阶段范围事实，再 Java 按轮次聚合。 | 数据量大时主表和下钻都会重复构造规则流。 |
| 系统测试申请延期缺陷分析 | 先查询阶段范围事实，再 Java 保留延期原因并聚合固定延期类型。 | 页面当前能返回，但仍有接近 15 秒的同类结构性风险。 |
| 客户问题缺陷汇总 | `runtimeSupport.loadFacts(...)` 后用 `CustomerIssueScopeProfile` 在 Java 中收口 CC_Product、日期、里程碑和模块。 | 客户问题范围没有完全前推到 SQL，模块行也依赖过滤后的内存结果。 |
| 客户问题延期问题 | `issueFactQueryService.query(FACT_SQL, queryFilters...)` 读取事实后，在 Java 中执行客户问题范围、排除、GitLab 报错、open、延期和模块聚合。 | 默认范围如果没带 `projectId/milestone`，会先拉大集合再过滤。 |
| 客户问题缺陷原因分析 | 查询事实后 Java 执行客户问题范围、里程碑/测试阶段和原因聚合；里程碑候选也会查询 `issue_fact` 后再过滤客户问题范围。 | 候选项和主表都存在大范围读取。 |
| 客户问题按功能展示缺陷数量 | 查询事实后 Java 按客户问题范围、功能和模块展开。 | 功能/模块聚合没有 SQL 预聚合。 |
| 客户问题响应/解决效率 | 查询事实后 Java 计算响应周期、解决周期并按模块展开。 | 周期计算和模块展开都在内存侧。 |

这些页面“默认分页”的说法只适用于最终表格展示或下钻切片，不适用于主接口的数据读取。真正要降到稳定 1-3 秒，需要把页面范围、默认阶段/里程碑、客户问题 scope 和主要聚合前推到 SQL，或建立统计快照/缓存。

### 2. 默认测试阶段未对齐会同时影响数量和性能

老平台不是所有页面默认“全部测试阶段”。已从代码确认到的旧平台默认包括：

- `D:/projects/spidergitdata-dev/webapp/src/views/PageStandard/IllegalIssueSearch.vue`：没有阶段值时使用 `CC2026R3`。
- `D:/projects/spidergitdata-dev/webapp/src/views/PageStatisticsInfo/charts/RollbackModulePieChart.vue`：`testingPhase` 初始值为 `CC2026R3`。
- `D:/projects/spidergitdata-dev/webapp/src/views/PageNinePersonalQuality/NinePersonalQuality.vue`：`testingPhase` 和 `projectName` 初始值为 `CC2026R3`。
- `D:/projects/spidergitdata-dev/webapp/src/views/PageHome/ContentComponents/QuestionnaireInfo/ModuleTable.vue`：阶段列表加载后默认取 `phaseNameList[0]`。

新平台现状：

- `frontend/src/composables/statistic-board-data-scopes.ts` 中系统测试相关数据范围使用 `defaultStrategy = 'first-available'`，但客户问题数据范围使用 `defaultStrategy = 'empty'`，占位和空值文案都是“全部测试阶段”。
- 客户问题后端 `CustomerIssueTestingPhaseFilterSupport.applyDefaultTestingPhase(...)` 又会在没有显式阶段时补第一个启用父级阶段，导致前端显示/URL 的“全部测试阶段”和后端实际筛选范围不一致。
- 部分系统测试服务端规则说明和代码也不完全一致，例如 `SystemTestDefectCauseBoardService` 的规则流文案写“未选择时保留全部系统测试阶段”，但实际代码会补默认阶段。

影响判断：

1. 对比老平台时，如果新平台默认全阶段，会把多版本、多阶段数据一起纳入，系统测试非法数据等页面会天然偏多，慢加载页面也会更接近 15 秒。
2. 客户问题页面如果前端默认“全部测试阶段”、后端又静默补默认阶段，用户看到的筛选范围、URL、导出/下钻条件和接口真实条件不一致，排查统计差异时容易误判。
3. 默认阶段应来源于“系统设置 - 议题测试阶段定义”的排序或后续显式默认配置，当前内网样例是 `CC2026R3`，但不能把该版本写死成永久规则。

已同步到常驻业务规则：系统测试和客户问题相关页面进入时应默认选中老平台当前默认或第一可用启用父级阶段，不得隐式默认“全部测试阶段”；默认值必须同时体现在顶部控件、URL、接口筛选、导出和下钻条件中。

### 3. 刷新慢来自两层放大

第一层是镜像刷新放大：

- `IssueFactRealtimeRefreshService`、系统测试统计看板和客户问题统计看板会刷新 `issues/projects/users/label_links/labels/notes`。
- `CodeReviewIllegalRecordService` 会刷新 `merge_requests/merge_request_metrics/merge_request_reviewers/merge_request_assignees/label_links/labels/projects/namespaces/users`。
- 手动增量同步 `INCREMENTAL_SYNC` 会在 `SyncRunTablePlanningService.shouldPlanFromWhitelist(...)` 中规划全部白名单表。

第二层是水位边界放大：

- 原逻辑新建增量任务时只设置 `watermark_at = state.lastWatermarkAt`，没有设置 `cursor_updated_at/cursor_pk`。
- 源库扫描 SQL 使用 `updated_at >= watermark`，导致同一 `updated_at` 水位上的历史行被重复拉取。
- 本次已改为：任务规划时带上 `lastWatermarkAt + lastCursorPk`；执行时无更新先用 `max(updated_at)` 直接 0 行返回；无游标的首批增量改成 `updated_at > watermark`；SQL 时间字面量保留到微秒，避免把同一秒内旧数据重新扫入。

### 3.1 同步合并目标与当前差距

用户期望的同步编排口径：

1. 全量同步优先级最高，尤其第一次全量同步；第一次全量同步期间，任何同步都合并到全量同步。
2. 非全量同步时，例如增量更新运行中，任何单表刷新都合并到该增量更新，不进入队列，也不创建新的同步运行单元。
3. 任意时刻同一数据源只能有当前同步进程；其他全量/增量/补偿/页面刷新请求只能被吸收或拒绝，不能排队。
4. 多个不同表的单表刷新如果没有全量/增量运行，可按提交时间顺序处理；同一表短时间多次刷新只执行一次。
5. 自动同步应走增量更新；凌晨 2 点全量补偿对账是独立的全量补偿更新，需要和普通自动增量区分。

当前代码现状：

- `SyncRunPolicyService.exclusiveScopeOf(...)` 已经把 `FULL_SYNC`、`INCREMENTAL_SYNC`、`TABLE_REFRESH`、`SYSTEM_HOOK`、`COMPENSATION_SCAN`、`FULL_COMPENSATION_SCAN` 放在同一个 mirror exclusive scope，因此同一数据源不会并发跑多个镜像同步。
- `SyncRunDispatcherService.claimNextQueuedRun(...)` 只会调度 `QUEUED`，并且同一 `exclusive_scope` 有 `RUNNING/RETRYING/CANCELLING` 时不会再启动其他 run。
- `SyncRunSubmissionService.shouldReuseMirrorRun(...)` 会让运行中的全量、增量、System Hook、全量补偿吸收大部分后续镜像请求；同表 `TABLE_REFRESH` 也会去重。
- 但被吸收的请求仍通过 `recordAbsorbedSubmission(...)` 插入 `status = MERGED` 的 `sync_runs` 记录。它不执行，但违反“不要创建”的目标，也会污染运行历史和状态理解。
- 不同表的 `TABLE_REFRESH` 当前不是动态追加到正在运行的表刷新 run；如果没有可复用的 active run，仍可能创建新的 `QUEUED` run，依赖 dispatcher 串行处理。这和“多个不同表按时间顺序”接近，但和“任何同步过程中只有当前同步进程可存在，其他同步无法进入队列甚至创建”存在模型冲突，需要决定是保留表级顺序队列，还是改为动态并入当前 run 的任务集合。
- `SyncRunTableWorkerService.drainRunTasks(...)` 会持续 claim 当前 run 下 `QUEUED` 表任务，机制上支持“运行中追加表任务”；但追加必须和 run 完成状态更新使用同一把提交锁，否则可能出现 worker 已经 drain 完、run 正在标记成功时又追加新表任务的竞态。
- 当前普通定时任务是 `GitlabCompensationScheduler` 提交 `COMPENSATION_SCAN`，不是 `INCREMENTAL_SYNC`；凌晨每日全量补偿是 `GitlabDailyVerificationScheduler` 提交 `FULL_COMPENSATION_SCAN`，默认时间 `02:00`。因此“自动同步就是增量更新”与当前命名和编排不完全一致，需要统一：要么把普通自动任务改为 `INCREMENTAL_SYNC`，要么在业务文案中明确 `COMPENSATION_SCAN` 就是自动增量补偿扫描。

建议后续同步编排修正方向：

1. 被 active run 吸收的请求直接返回当前 run 的 `SyncRunSubmissionResult`，不再插入 `MERGED` run。
2. 对全量/增量/全量补偿运行中的任何页面刷新请求，直接复用当前 run；返回消息说明“已由当前同步覆盖”。
3. 对同表短时间重复单表刷新，保持同表去重，但去重不落 `MERGED` 记录。
4. 对不同表单表刷新需要先定模型：若允许“表级顺序”，可以保留一个 active/queued `TABLE_REFRESH` run 并追加表任务；若严格“不创建其他同步”，则需要把新表动态写入当前 run 的任务表，而不是新建 run。
5. 调整自动同步入口：普通自动同步提交 `INCREMENTAL_SYNC`；凌晨 2 点保留 `FULL_COMPENSATION_SCAN`，并在状态展示、文案和日志中区分“自动增量”和“全量补偿对账”。

### 4. 客户问题空表格的直接触发点

客户问题页面按规则需要顶部“测试阶段”切换，但当前实现默认选择系统设置中第一个启用父级阶段：

- `CustomerIssueTestingPhaseFilterSupport.applyDefaultTestingPhase(...)`
- 匹配逻辑：`milestone_title` 或 `testing_phase` 等于父级，或能通过父级展开匹配子级。

如果内网 CC_Product 的里程碑不是这个父级阶段，默认筛选会把客户问题数据过滤空。客户问题缺陷汇总、按功能展示等页面又从 `finalSources` 生成模块行，所以不是“只有数量为 0”，而是连模块行都没有。延期问题页因为有“未设定模块”兜底，所以表现为只剩一行且全 0。

### 5. 系统测试非法数据偏多、议题查询偏少的口径差

- 系统测试非法数据走 `IssueFactRecordPageQuery.Scope.SYSTEM_TEST`，范围条件会匹配 `testing_phase/system_test_label/label_names` 里的系统测试或回归测试。
- 议题查询走 `IssueFactRecordPageQuery.Scope.ALL`，默认项目是 CrownCAD，但不使用同一套系统测试 scope；阶段筛选默认走 `phase_filter_value`。

因此这两个页面在新平台内部也不是完全同一口径，更不用说和老平台记录列表口径比较。非法数据偏多和议题查询少 2000 条都可以由这组范围差异解释。

### 6. 系统测试缺陷汇总首屏抖动

触发链路如下：

1. `StatisticBoardView.vue` 监听 `route.query`，并设置 `immediate: true`，页面进入时会立刻请求看板接口。
2. `useStatisticBoardDataScope(...)` 异步加载“系统设置 - 议题测试阶段定义”的启用阶段。
3. `useDataScope(...)` 对系统测试缺陷汇总使用 `defaultStrategy = first-available`；阶段选项返回后，如果 URL 还没有 `testingPhase`，会把第一个可用阶段写入路由。
4. 路由变化触发第二次看板请求。
5. 第一次请求没有 `testingPhase`，而 `SystemTestDefectSummaryBoardService.buildRuleFlowSnapshot(...)` 中 `hasTestingPhaseCondition(...)` 为 false 时直接把 `valid` 置为 `List.of()`；同时 `doLoadBoard(...)` 总会追加 `toSummaryRowData(TOTAL_ROW_KEY, TOTAL_ROW_LABEL, sources)`，所以前端会短暂展示“总计”一行且所有指标为 0。
6. 第二次请求带上默认 `testingPhase` 后，后端返回真实模块行和总计行，页面看起来就发生了数据抖动。

结论：这不是数据源瞬间变化，也不是表格组件计算错误，而是初始化阶段的两次请求加上后端“无测试阶段时返回总计 0”的组合效果。修正方向是前端在 required data scope 默认值落路由前不要发首个看板请求，或者后端对缺少必选测试阶段的系统测试缺陷汇总不返回总计 0 行。

### 6.1 老平台系统测试缺陷汇总筛选规则提取

本节直接从老平台代码提取，后续对齐时以这些入口为准：

- 前端入口：`D:/projects/spidergitdata-dev/webapp/src/views/PageHome/ContentComponents/QuestionnaireInfo/ModuleTable.vue`
- 主接口：`D:/projects/spidergitdata-dev/src/main/java/com/huayun/controller/DataAnalysisController.java#getModuleTable`
- 主服务：`D:/projects/spidergitdata-dev/src/main/java/com/huayun/service/SpiderIssueDataService.java#getModuleTable`
- 指标查询：`D:/projects/spidergitdata-dev/src/main/java/com/huayun/service/impl/SpiderIssueDataDAOImpl.java#findByModuleTableAndTestingPhase`
- 公共过滤：`D:/projects/spidergitdata-dev/src/main/java/com/huayun/utils/QueryUtil.java#setQueryFilter`
- 模块成员匹配：`D:/projects/spidergitdata-dev/src/main/java/com/huayun/utils/ModuleSplitUtil.java#isContainModule`

提取到的规则：

1. 默认项目是 CrownCAD 系统测试项目 `projectId=9`；接口 `projectId` 默认值也是 `9`。
2. 前端阶段下拉加载后默认选择 `phaseNameList[0].value`，然后请求 `/dataAnalysis/getModuleTable?phase=...`；不是默认“全部测试阶段”。
3. `phase` 如果是父级阶段名，老平台用 `testingPhaseService.getByName(phase)` 展开为具体测试阶段；如果查不到，就把传入值当作具体 `testing_phase`。
4. 系统测试缺陷汇总没有显式阶段时，老平台 `projectId=9` 直接返回 `null`，不会查询全量。
5. CrownCAD 项目主查询按 `testing_phase LIKE %具体阶段%` 匹配，而不是只精确等于阶段值，也不是只靠“系统测试/回归测试”标签命中。
6. 模块行目录来自 `dropDownService.getModuleNameFromSpiderIssueData(projectId, testingPhases)`，也就是指定项目和阶段范围内出现过的模块名；随后追加固定的“总计”行。
7. 指标查询先套公共过滤：系统测试项目默认排除 `category` 含 `功能屏蔽`、`category` 含 `建议`、`bug_status` 含 `已拒绝`，并排除关闭状态下的 `申请否决`、`需求如此`。
8. 模块匹配不是子串包含，而是把 `module_name` 按 `&` 拆分并 `trim` 后与目标模块精确相等；总计行不做模块过滤。
9. 一级缺陷总数：`severity_level = 一级缺陷`。
10. 一级回退：`severity_level = 一级缺陷` 且标题包含 `（退`、`回退` 或 `倒退`。
11. 一级挂机：`severity_level = 一级缺陷` 且标题包含 `挂机`。
12. 一级其他：`severity_level = 一级缺陷` 且标题不包含 `退`、`回退`、`倒退`、`挂机`；因此“退出”等包含 `退` 的标题不会进入其他一级。
13. 二级/三级缺陷总数分别按 `severity_level = 二级缺陷 / 三级缺陷`。
14. 建议类列来自 `category` 含 `建议`，但公共过滤也排除了 `category` 含 `建议`，所以默认公共口径下建议类通常为 0。
15. `已修复/未更新`、各严重程度已修复、P1/P2/P3 已修复等修复类统计，老平台多处使用 `bug_status` 含 `已修复/完成` 或 `未复现` 或 `status = CLOSED`；部分遗留列还沿用 `已修复/待合并/未更新` 的旧标签口径。
16. P1/P2/P3 数量按 `urgency = P1/P2/P3`，与严重程度一级/二级/三级是两套独立标签体系。
17. 申请延期按 `bug_status` 含 `申请延期`；延期占比 = 模块延期缺陷总数 / 模块总缺陷数。
18. 复测未通过按 `bug_status` 含 `未修复`。
19. 新发议题按 `bug_status` 不含 `历史遗留`；新发修复/关闭在此基础上叠加修复或关闭条件。
20. 缺陷占比 = 模块总缺陷数 / 当前测试阶段总缺陷数；总计行直接使用该阶段全部有效议题。

与新平台当前实现需要继续对照的重点：

1. 新平台 `SystemTestDefectSummaryBoardService` 当前说明和执行中仍强调 `inSystemTestScope`，即系统测试/回归测试范围；老平台缺陷汇总主查询的第一限定是 `project_id=9 + testing_phase LIKE 展开后的具体阶段`。如果事实数据存在阶段字段但缺少系统测试标签，新平台可能少；如果标签宽泛但阶段不在当前父级展开范围，新平台可能多。
2. 老平台模块目录来自“指定阶段内出现过的模块名”，新平台为了避免空目录和支持默认模块范围，目录改成启用阶段范围加默认标签组/模块筛选。这是产品化修正，但做 1:1 差异定位时要单独标记为目录规则差异，不能混同为单元格计数差异。
3. 老平台在 DAO 查询层大量使用 `LIKE`，例如 `testing_phase LIKE %阶段%`、`bug_status LIKE ...`、`category LIKE ...`；新平台事实层若使用归一化枚举等值匹配，需要确认归一化值能覆盖旧文案。
4. 后续系统测试缺陷汇总差异排查应按“同一批构造数据在老平台导入/新平台导入后逐列比较”的方式推进，优先覆盖：只有阶段无系统测试标签、只有系统测试标签但阶段不匹配、标题含“退出”、多模块 `A & B`、关闭的申请否决/需求如此、建议类、历史遗留、新发、P1/P2/P3 和未修复样本。

### 7. 代码走查 MR 29874 的纠正

旧结论“MR 29874 模块为空导致缺失”已经作废。

已确认事实：

- MR 29874 在镜像库 `merge_request` 和 `merge_request_fact` 表中存在。
- MR 29874 的模块名是“平台”。

因此它不会因为 `module_name = '未标注模块名'` 或模块为空而被解释为缺失。下一步只应核对该 MR 在 `merge_request_fact` 中以下字段：

```sql
select source_instance,
       project_id,
       merge_request_iid,
       module_name,
       project_name,
       target_branch,
       merge_request_state,
       merged_at_source,
       review_exception_reason,
       scan_status,
       scan_bug_count,
       annotation_rate_result,
       bug_count_result,
       owner_name,
       reviewer_names
  from merge_request_fact
 where merge_request_iid = 29874;
```

若老平台统计到了它，而新平台没有，优先比较：

- 老平台 `assignee` 中的走查异常值是否正确映射到新平台 `review_exception_reason`。
- 老平台 `sonar_qube_result = 未进行代码扫描/GitLab 接口报错` 是否正确映射到新平台 `scan_status`。
- 老平台 `bug_count_result`、`annotation_rate_result` 是否在新平台事实表中为空、文案不同或来源缺失。
- GitLab 接口报错在老平台检查 `sonar_qube_result/target_branch/assignee`，新平台检查 `scan_status/target_branch/owner_name/reviewer_names`，字段替换可能漏掉部分样本。

## 本次已落地的同步修正

已修改：

- `backend/src/main/java/com/data/collection/platform/service/sync/SyncRunTablePlanningService.java`
- `backend/src/main/java/com/data/collection/platform/service/sync/SyncRunTableTaskExecutor.java`
- `backend/src/main/java/com/data/collection/platform/service/GitlabSourceScanSqlBuilder.java`
- `backend/src/test/java/com/data/collection/platform/service/GitlabExternalDbServiceTest.java`
- `backend/src/main/java/com/data/collection/platform/service/RealtimeIncrementalRefreshService.java`
- `backend/src/main/java/com/data/collection/platform/service/IssueFactRealtimeRefreshService.java`
- `backend/src/main/java/com/data/collection/platform/service/MergeRequestFactRealtimeRefreshService.java`
- `backend/src/main/java/com/data/collection/platform/service/statistics/IssueFactBoardRuntimeSupport.java`
- `backend/src/main/java/com/data/collection/platform/service/statistics/SystemTestDefectCauseBoardService.java`
- `backend/src/main/java/com/data/collection/platform/service/statistics/SystemTestPhaseStatisticsBoardService.java`
- `backend/src/main/java/com/data/collection/platform/service/statistics/SystemTestDelayAnalysisBoardService.java`
- `backend/src/main/java/com/data/collection/platform/service/statistics/CustomerIssueDefectCauseBoardService.java`
- `backend/src/main/java/com/data/collection/platform/service/CodeReviewIllegalRecordService.java`
- `backend/src/main/java/com/data/collection/platform/service/sync/SyncRunSubmissionService.java`
- `backend/src/main/java/com/data/collection/platform/service/GitlabCompensationScheduler.java`

行为变化：

1. 手动单表刷新、手动增量同步的新任务会从 `sync_run_table_states.last_watermark_at + last_cursor_pk` 之后继续。
2. 非分页中的增量任务会先查询源表 `max(updated_at)`；如果源表最大更新时间没有超过平台保存水位，直接 0 行成功。
3. 无历史游标的增量首批扫描使用 `updated_at > watermark`，避免历史边界行反复被拉取。
4. 增量扫描 SQL 保留 `updated_at` 微秒精度，避免把水位截断到秒后扩大扫描窗口。
5. 已排队但没有 cursor 的旧任务，执行时会从当前表状态补 cursor，降低增量更新包部署后的旧任务风险。
6. 业务页面“刷新最新数据”不再同步刷新原始镜像表并立刻重建事实表，而是提交/复用一次手动增量同步；事实刷新由同步完成监听器统一触发，页面状态显示为镜像同步已提交、事实层排队等待。
7. 被已有同步吸收的请求不再插入 `MERGED` 运行记录，避免“没有执行但历史里多出一个同步单元”的状态污染。
8. 普通定时自动同步已改为提交 `INCREMENTAL_SYNC`；凌晨 2 点全量补偿对账仍保持 `FULL_COMPENSATION_SCAN`，两类任务在运行类型上区分。

## 本次已落地的页面口径修正

已修改：

- `frontend/src/composables/statistic-board-data-scopes.ts`
- `frontend/src/components/StatisticBoardView.vue`
- `frontend/src/composables/usePageAutoRefreshPreference.ts`
- `frontend/src/composables/usePageAutoRefreshPreference.test.ts`
- `frontend/src/views/CustomerIssueIllegalRecordsView.vue`
- `frontend/src/views/issue-illegal-records/IssueIllegalRecordsPage.vue`
- `frontend/src/views/SystemTestIssueSearchView.vue`
- `backend/src/main/java/com/data/collection/platform/service/CustomerIssueIllegalRecordService.java`
- `backend/src/main/java/com/data/collection/platform/service/CustomerIssuePhaseSupport.java`
- `backend/src/main/java/com/data/collection/platform/service/SystemTestIllegalRecordService.java`
- `backend/src/main/java/com/data/collection/platform/service/SystemTestIssueSearchService.java`
- `backend/src/main/java/com/data/collection/platform/service/statistics/CustomerIssueTestingPhaseFilterSupport.java`
- `backend/src/main/java/com/data/collection/platform/service/statistics/SystemTestDefectSummaryBoardService.java`
- `backend/src/main/java/com/data/collection/platform/service/CodeReviewIllegalRuleRegistry.java`
- `backend/src/main/java/com/data/collection/platform/service/CodeReviewIllegalRecordSqlSupport.java`

行为变化：

1. 系统测试和客户问题相关页面默认阶段统一走第一可用启用父级阶段，前端控件不再显示“全部测试阶段”作为默认范围。
2. 统计看板会等待 `first-available` 默认阶段写入 URL 后再请求数据，避免系统测试缺陷汇总首屏先出现“总计 0”再二次刷新成真实数据。
3. 系统测试非法数据改回记录列表口径：默认项目 `9`，默认第一可用父级阶段，按阶段定义展开后匹配事实层 `testing_phase`，不再用 `Scope.SYSTEM_TEST` 的宽泛标签命中范围扩大数据。
4. 系统测试议题查询在无阶段参数时也补第一可用父级阶段，前端显示、URL、接口查询和导出入口保持同一默认范围。
5. 系统测试缺陷汇总改回老平台主口径：默认项目 `9`，没有测试阶段时不查询全量；有阶段时先按阶段定义展开具体轮次，再用事实层 `testing_phase LIKE 具体阶段` 参与统计，不再用“系统测试/回归测试”标签作为前置范围。
6. 客户问题阶段匹配增加版本键兼容，`2026R3`、`CC2026R3`、`CrownCAD 2026R3` 这类里程碑/阶段值可命中同一个父级阶段；记录页和统计页共用 `CustomerIssuePhaseSupport`，避免 CC_Product 有模块但被默认阶段过滤成空表。
7. 代码走查 `GitLab 接口报错` 判断兼容有空格和无空格文案，并把扫描状态、目标分支、负责人、审查人、指派人展示字段都纳入判断，降低老平台非法样本漏判。
8. 事实增量构建遇到旧事实缺少搜索索引/阶段派生字段时，先按小批量修补索引，再用 `max(ods_updated_at)` 计算增量边界，不再直接退化成全量事实重建。
9. 页面“进入页面自动刷新最新数据”的默认偏好改为关闭；用户显式打开后才会在进页时触发刷新，避免普通打开统计看板就创建同步 run。

## 本地烟测和真实链路验证

本地默认 `qaflex` 库存在 Flyway 历史漂移，因此本次用隔离库 `qaflex_smoke_20260626` 验证，不修改原库。烟测数据包含系统测试 `1001/1002/1003`、客户问题 `31095` 和代码走查 MR `29874`。

已验证：

1. 后端 `mvn -q -DskipTests compile` 通过。
2. 前端 `npm.cmd run typecheck` 通过。
3. 系统测试非法数据进入页面后 URL 和列表请求均带 `testingPhase=CC2026R3`，能看到 `1001`，不会包含 `CC2026R4` 的 `1002`。
4. 客户问题非法数据进入页面后 URL 和列表请求均带 `testingPhase=CC2026R3`，能看到 `31095`，模块显示为 `平台`。
5. 系统测试议题阶段统计进入页面后 URL 带 `testingPhase=CC2026R3`，接口返回第一轮系统测试非 0 总计，且浏览器请求中没有自动 `/refresh`。
6. 代码走查非法数据能命中 MR `29874` 的 `GitLab 接口报错`。

## 今日老平台开发对齐后的遗留项

> 依据：`C:\Users\admin\Downloads\新平台功能优化讨论.txt`、`docs/platform-page-business-rules.md`、当前代码和本轮已通过的 `SystemTestDefectSummaryRuleExplanationTest`。
> 当前目标仍是先覆盖老平台已有功能；权限、自动维护阶段、个人视图等新平台增强能力先不抢优先级。

### 老平台已有功能，必须继续对齐

1. 客户问题模块仍是第一优先级遗留项。客户问题范围必须固定为 `CC_PRODUCT` / `CC_Product`，老平台项目 ID 为 `325`，并按 `C:\Users\admin\Downloads\产品客户问题响应管理机制.mm` 的响应、解决、延期、非法模板规则执行。当前已经修正默认阶段和 CC_Product 范围，但客户问题延期链路还没有完全等价老平台：`CustomerIssueDelayIssuesBoardService` 只消费 `issue_fact.is_response_delayed / is_resolve_delayed`，事实构建中的响应延期主要来自 `响应已延期` 标签或模板状态；老平台文档要求按 P1/P2/P3 的 24/48/72 小时窗口定时判定，模板响应后取消延期标签。这一套每小时轮询、自动标记和取消的闭环仍需专项补齐。
2. 客户问题响应/解决效率需要用不同测试数据继续和老平台逐页对比。重点样本应覆盖：无紧急程度按 P3、按模板响应、未按模板响应、预计解决时间早于 18 天、预计解决时间超过 18 天、已修复/完成、申请延期、数据异常、需求如此/设计如此、未复现、多模块议题。对比目标不是模拟完整内网环境，而是用同一批构造数据在新老平台不同页面之间确认规则筛选差异。
3. 系统测试缺陷原因分析和议题阶段统计仍有 15 秒超时结构性风险。当前缺陷原因占比分母已经按“缺陷原因个数”聚合，能覆盖一个议题多个原因时占比总和应为 100% 的老平台规则；但主接口仍是 `issue_fact` 查询后在 Java 聚合，再做明细分页切片。默认阶段对齐只能缩小范围，不能彻底保证大数据量下稳定 1-3 秒，需要后续把核心聚合前推 SQL，或增加统计快照/缓存。
4. 普通统计看板导出还没有完全达到“一字不差”。`StatisticBoardController` 只有遇到 `StatisticBoardWorkbookExportSupport` 才导出 xlsx，否则走 `StatisticBoardCsvSupport`；CSV 当前只导出一行叶子列表头，不能保留页面多级表头。已实现工作簿导出的主要是系统测试/客户问题缺陷原因分析和系统测试横向对比，系统测试缺陷汇总、议题阶段统计、申请延期缺陷分析、客户问题缺陷汇总、延期问题、按功能展示、响应效率等普通看板仍需按“当前看到的表格”导出多级表头和当前数据。
5. 系统测试横向对比导出已经有专门 xlsx 服务，但讨论中要求必须和老平台格式完全一致。当前代码 `SystemTestHorizontalComparisonExportService` 使用扁平 header 文案生成工作簿，并按 `target_branch = dev`、`MERGED` 统计代码走查；仍需拿老平台导出模板逐列、合并表头、顺序、空行过滤、数值格式和 DGM/CrownCAD 分块进行一次精确复核。
6. 代码走查非法数据少约 6000 条仍不能只用 MR 29874 解释。当前目标分支筛选已支持：只在明确选择 CrownCAD 或 DGM 且目标分支为空时默认补 `dev`，用户可按条件筛选其他分支；但缺失数量仍需直接提取老平台非法判断代码，和新平台 `review_exception_reason / scan_status / scan_bug_count / annotation_rate_result / bug_count_result / project_name / module_name / target_branch / owner_name / reviewer_names` 映射逐项比对。下一步应用同一批构造 MR 数据在新老平台导入后比较非法类型命中差异。
7. 议题刷新必须包含评论。当前 `IssueFactRealtimeRefreshService` 的刷新表包含 `notes`，`GitlabFactSourceSqlProvider` 也从 `ods_gitlab_notes` 汇总 issue 评论并写入事实层 `raw_payload`、缺陷原因、响应模板和 SLA 字段；这条链路代码上已经覆盖。但需要在后续真实链路中用“只改评论、不改议题主体字段”的数据验证增量同步是否会触发 `notes -> issue_fact -> 缺陷原因/客户问题延期/响应效率` 更新。
8. 官方默认筛选、默认阶段、展示规则不能被个人保存视图覆盖。当前讨论确认这是老平台已确认的固定规则边界；后续如果实现保存视图，必须只作用于个人账号，不能改写平台官方默认条件、导出条件和领导视角默认展示。

### 后续新功能，先不作为本轮对齐目标

1. 测试阶段自动维护：R4 等新版本开始后，按 GitLab 标签或固定前缀自动同步父级阶段和 1-6 轮子阶段。这是重要优化，但老平台当前也是人工维护，先不阻塞本轮老功能对齐。
2. 项目名称自定义和标签同步：评审项目可自定义，但后续要与议题/MR 标签名称对齐，保证横向对比能合并同一项目。这是老平台遗留痛点和新平台治理能力，需单独设计项目别名或映射规则。
3. 权限、LDAP、角色默认视图、个人保存视图和不同账号不同展示方式，属于上线后的平台能力；当前仅记录边界，不抢客户问题、系统测试和代码走查规则对齐优先级。

## 仍需验证的执行方式

- 对于无法直接复现完整内网全量环境的问题，后续不要表述为“缺失内网样本导致无法判断”；应明确为“通过不同测试数据组合，对比新老平台不同页面之间的规则筛选差异”，再根据差异矩阵定位是哪条筛选规则不一致。
- 代码走查非法数据、客户问题延期/响应效率、系统测试缺陷原因分析和导出格式都应采用“提取老平台代码规则 -> 构造覆盖边界的数据 -> 新老平台同数据对比 -> 回填常驻业务规则和测试”的路径。
