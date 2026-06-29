# 内网测试问题根因同步

> 更新时间：2026-06-29
> 依据范围：本文件只同步本次按代码重新确认的原因和用户已补充的信息，不沿用旧诊断文档中的未验证推测。

## 总览

## 2026-06-26 内网复测后新增差距结论

> 复测反馈说明，上一轮文档里多处“已对齐默认范围/字段映射”的判断还停留在局部代码路径，未覆盖真实页面入口、默认筛选和 SQL 快路径。当前优先级最高的是空表、15 秒超时和慢加载。

### P0：客户问题模块空表和慢加载

1. 客户问题页面必须以 `CC_PRODUCT` / `CC_Product` 项目 `325` 为范围，顶部切换语义应优先对齐老平台的 `mileStone/milestone`。老平台 `IllegalIssueSearchCCProduct.vue` 会调用 `getAllMileStoneName({ projectId: 325 })`，默认取 `mileStoneList[0]`，查询时提交 `projectId=325` 和 `milestone=this.mileStone`。
2. 已修复：客户问题非法数据页固定 `projectId=325`，顶部使用 CC_Product 里程碑/版本语义，默认取第一可用里程碑，不再用 CrownCAD 项目 `9` 的阶段定义作为记录入口默认条件。
3. 已修复：客户问题五个统计看板的顶部范围不再使用 CrownCAD 项目 `9` 的阶段定义，也不再通过 `testingPhase` 承载里程碑体验。前端候选改为从 CC_Product 项目 `325` 的真实里程碑选项读取，URL、接口筛选、后端默认补齐、快照键和下钻条件统一使用 `milestoneTitle -> issue_fact.milestone_title`。旧链接中已有的 `testingPhase` 条件会在后端归一化为 `milestoneTitle` 兼容处理。
4. 2026-06-29 老平台源码复核修正：`CC_PRODUCT议题` 记录页对齐老平台 `CCProductIssueTable.vue -> findCCProductIssueInfo -> ProjectIssueInfoQueryBuilder`，默认 `mileStone/milestoneTitle` 为空，即首屏查询全里程碑；里程碑只作为用户可选筛选。该页默认排除 `bug_status` 包含 `已拒绝` 的记录，不复用客户问题统计页的公共排除。
5. 客户问题慢加载不是分页失效，而是统计看板仍先从 `issue_fact` 拉取一批记录，再在 Java 内存里做客户问题范围、里程碑/阶段、模块、延期和原因聚合。本轮已先给客户问题五个聚合看板接入 `statistic_board_snapshots` 首屏快照，重复打开不再重复聚合；首次缺快照时若仍接近 15 秒，下一步再把 `project_id=325`、`created_at>=2026-01-01`、客户问题公共排除、`milestone_title` 默认值和主要聚合前推到 SQL。

### P0：系统测试 15 秒超时和慢加载

1. `系统测试/缺陷汇总`、`系统测试/议题阶段统计`、`系统测试/缺陷原因分析`、`申请延期缺陷分析` 的主接口已落地统计快照中间表。首次缺快照时仍由页面服务按当前老平台口径生成一次，后续同一事实版本、规则版本和阶段范围直接读取 `statistic_board_snapshots`，避免每次打开页面都重复扫描大范围 `issue_fact`。
2. `SystemTestPhaseSqlPredicateSupport` 会把所选父级阶段展开后匹配 `testing_phase/system_test_label/label_names`，这比纯 `testing_phase` 更宽。老平台多处统计接口是 `testingPhaseService.getByName(phase)` 后按具体测试阶段集合查 `SpiderIssueDataQueryBuilder.setTestingPhases(phases)`；后续要逐页确认是否允许 label_names 模糊命中。
3. 慢加载页面已按优先级进入统计快照：系统测试四个聚合看板和客户问题五个聚合看板均已覆盖。记录类分页页面、下钻详情、规则说明和导出仍走各自实时查询，需要内网复测后再决定是否继续做详情快照、SQL 分页或流式导出。
4. 本轮已先将系统测试统计页的阶段 SQL 收口为只匹配 `issue_fact.testing_phase = 展开后的具体测试轮次`，不再额外用 `system_test_label` 或 `label_names` 放宽命中。该修复针对内网反馈的缺陷汇总“装配/其他 - CC2026R3 多 1 条”以及三个超时页的范围放大问题；后续内网仍需重点用下钻明细确认多出的那条是否来自宽阶段匹配。

### P0：系统测试议题查询默认范围误对齐

1. 本次内网明确反馈：`系统测试/议题查询` 不应该默认锁定 `CC2026R3`，应该默认显示“全部”。上一轮文档把“系统测试统计看板默认当前阶段”的规则泛化到了议题查询页，这是错误的。
2. 已修复：`SystemTestIssueSearchView.vue` 不再把 `testingPhase` 设为 `first-available`，前端下拉默认展示“全部测试阶段”；`SystemTestIssueSearchService` 在无阶段参数时也不再隐式补第一可用阶段。后端仍默认 `projectId=9`，并保留老平台公共排除规则。
3. `系统测试非法数据` 与 `议题查询` 不是同一默认策略：非法数据老平台默认取阶段列表首项，议题查询默认全部。两者不能再共用一个泛化的“系统测试页面默认阶段”结论。

### P0：系统测试非法数据数量偏多

1. 已修复：系统测试非法数据 SQL 快路径已从 `IssueFactRecordPageQuery.Scope.ALL` 收口到 `Scope.SYSTEM_TEST`，并继续默认 `projectId=9`、按阶段定义展开具体测试阶段、复用公共排除规则。
2. 仍需内网样本对比：`Scope.SYSTEM_TEST` 会按系统测试/回归测试语义识别事实范围；若老平台记录入口仅靠 `projectId=9 + testing_phase`，极端样本仍可能出现差异。后续用“只有阶段无系统测试标签/只有标签无阶段/阶段近似但不在定义中”的构造数据继续确认是否还要进一步收口。
3. 文档旧结论中“偏多来自 `Scope.ALL + 阶段集合/过滤条件`”已对应修复；剩余差异不再按该原因归因。

### P0：代码走查非法数据项目切换和少 6000 条

1. 代码走查页面顶部同时区分 `source` 数据源和 `projectName` 项目/版本。老平台项目筛选对应 `projectName`；代码走查非法数据页默认数据源应为“全部数据源”，不能默认选择 CrownCAD，否则会触发 CrownCAD/DGM 的目标分支默认 `dev` 规则并缩小范围。
2. 已修复：`CodeReviewIllegalRecordQuerySupport.legacyTargetBranch()` 只有明确选择 `cc` 或 `dgm` 且未选目标分支时才补 `dev`；全数据源或默认源不再隐式限制到 `dev`。
3. 默认非法 SQL 中 `Clang 分析错误` 对 `cc/default` 默认不纳入总非法，只在非 cc 数据源或显式筛选该非法类型时命中。若老平台默认总非法包含该类，可能形成明显数量缺口，需要直接对照老平台 `StaticDataController` 和 `SpiderCrowncadDataService` 的非法判定。
4. 字段映射中 `owner -> author_name` 方向是对的，但还要逐项复核：项目切换 `projectName -> merge_request_fact.project_name`、数据源 `source -> source_instance`、目标分支 `targetBranch -> target_branch`、被走查人 `owner -> author_name`、合并人 `mergedBy -> merge_user_name`、模块 `moduleName -> module_name`。

### 下一轮修复优先级

1. 先修客户问题缺陷非法数据和 CC_PRODUCT 议题空表：客户问题入口固定 CC_Product 项目 325；非法/延期/统计类页面使用 CC_Product 里程碑默认值，`CC_PRODUCT议题` 按老平台记录页默认全里程碑，不再用 CrownCAD 项目 9 阶段定义作为默认。
2. 再复测系统测试四个聚合看板和客户问题五个聚合看板的首屏快照命中情况；如果首次缺快照仍慢，再把项目、阶段集合、公共排除、必要聚合进一步前推 SQL。
3. 已完成系统测试议题查询默认全部，以及系统测试非法数据 SQL 快路径范围收口。
4. 已完成代码走查默认 targetBranch 补 `dev` 条件修正；项目切换入口和非法类型覆盖仍需内网样本继续验收。

| 问题 | 当前原因判断 | 状态 |
| --- | --- | --- |
| 客户问题模块大部分页面空表格、连模块名也没有 | 入口范围已修复：客户问题范围只能按 `project_id=325` 或 `project_name=CC_Product/CC_PRODUCT` 命中，不再从里程碑或标签里的 `cc_product` 反推；统计页顶部候选、URL、后端补默认、快照键和下钻条件已统一到 `milestoneTitle -> milestone_title`。`CC_PRODUCT议题` 记录页按老平台默认全里程碑，排除已拒绝状态。若内网仍空，应优先核对 `issue_fact` 是否完成事实重建、真实 `project_id=325`、创建时间和 `milestone_title`。 | 已修复主要入口问题，待内网数据复核 |
| 客户问题延期问题只有“未设定模块”且数量为 0 | 延期问题页已接入客户问题默认里程碑匹配和统计快照，不再使用 CrownCAD 父级阶段作为默认条件；若仍只剩兜底行，重点检查 CC_Product 事实数据的 `module_names`、`milestone_title` 和客户问题公共排除。 | 已修复主要入口问题，待内网数据复核 |
| 系统测试/系统测试非法数据比老平台多 | 已修复 SQL 快路径 `Scope.ALL` 风险，当前默认项目 `9`、默认第一父级阶段、按阶段定义展开后匹配。剩余差异需要用边界样本确认 `Scope.SYSTEM_TEST` 语义是否还比老平台记录入口更宽。 | 已修复主要代码原因，待样本复核 |
| 系统测试/议题查询少约 2000 条 | 已按内网反馈改为默认“全部测试阶段”，空阶段不再补第一父级阶段；仍默认项目 `9` 和公共排除规则。后续差异应继续从老平台记录列表公共过滤、字段映射和导出字段对比。 | 已修复默认范围 |
| 系统测试/缺陷原因分析、议题阶段统计 15 秒超时 | 这两个统计看板原来不是 SQL 分页加载，接口先从 `issue_fact` 全量加载符合过滤的事实数据，再在 Java 内存中做规则流、模块/阶段聚合和明细分页；前端请求默认 15 秒超时，因此数据量大时会超时。本轮已把首屏主表接入统计快照表，详情/规则说明/导出仍需单独复测。 | 已确认代码原因，首屏主表已快照化 |
| 系统测试和客户问题部分看板能加载但接近 15 秒 | 同类统计看板普遍是“数据库拉取一批事实 -> Java 规则流过滤/聚合 -> 前端本地分页”。系统测试缺陷汇总、客户问题缺陷汇总、客户问题延期、客户问题缺陷原因、按功能展示、响应效率等页面也存在全量或大范围事实加载，页面能返回只是当前数据量/筛选条件还没突破 15 秒。 | 已确认代码原因 |
| 客户问题/按功能展示缺陷数量字段比老平台少 | 老平台 `IssueShowByFunction.vue` 主表不是“模块 / 功能”扁平行，而是按模块动态生成列组，每个模块下面固定展示“功能”和“问题数量”两列；新平台此前改成一行一个模块/功能，并追加严重程度等统计列，导致用户看到的主表列形态少于老平台。本轮已改回动态模块列组，并按老平台习惯将功能数量多的模块排在前面，页面局部 UI 做了紧凑矩阵优化。 | 已修复展示字段和 UI 主要差异 |
| 部分页面默认范围是全部测试阶段，而不是老平台默认阶段 | 系统测试统计看板、系统测试非法数据和客户问题相关页面已默认第一可用阶段/里程碑；系统测试议题查询按内网反馈保留“全部测试阶段”默认。 | 已修复并区分页面例外 |
| 系统测试缺陷汇总首屏数据抖动，先显示总计 0 再显示完整数据 | 前端统计板 `route.query` watcher 首次立即请求；系统测试阶段数据范围选项异步加载后，`useDataScope` 再按 `first-available` 写入 `testingPhase` 并触发第二次请求。第一次请求没有 `testingPhase` 时，后端 `SystemTestDefectSummaryBoardService` 明确把有效数据置空但仍追加“总计”行，因此短暂显示总计 0；第二次带默认阶段后才显示完整数据。 | 已确认代码原因 |
| 单表刷新、手动增量同步突然很慢、拉取量很大 | 镜像表增量任务已有 `updated_at + cursor` 的增量扫描机制，但页面实时刷新入口当前仍调用全局增量同步，没有把页面声明的 `REALTIME_REFRESH_TABLES` 传入同步运行；因此系统测试缺陷汇总等页面刷新会扩大到全部白名单表，容易扫到 `events` 等大表。 | 已确认代码原因，第一轮修复中 |
| 页面“刷新最新数据”比预期重 | 统计页刷新不是只刷新表格结果，而是先刷新背后的 GitLab 镜像原始表，再重建事实表。例如系统测试/客户问题会刷新 `issues/projects/users/label_links/labels/notes`，代码走查会刷新 MR 相关镜像表，然后重建 `issue_fact` 或 `merge_request_fact`。事实层 SQL 还会聚合 `ods_gitlab_notes` 生成模板、缺陷原因和 SLA 字段，所以镜像层只拉增量不等于事实层计算一定只扫增量。 | 已确认代码原因，第一轮先收窄刷新提交范围 |
| 同步合并仍会产生额外运行记录 | 活动中的全量/增量/补偿/同表刷新已直接返回当前运行单元，不再为吸收请求新建 run。仍存在一个边界：提交新的全量同步时，会把已经排队的低优先级镜像 run 标记为 `MERGED`，这是对历史队列的终止记录，不是新建吸收记录；若产品要求历史中完全不出现 `MERGED`，需另行改日志展示或队列清理策略。 | 主要吸收路径已修复，剩余历史展示边界 |
| 事实重建有时从增量退化成全量 | `FactBuildService` 在发现既有事实缺少搜索索引或阶段派生字段时，会让 `changedSince = null`，下一次事实构建就不带增量谓词，表现为全量重建。升级后旧事实表缺字段或索引为空时尤其容易触发。 | 已确认代码原因 |
| 代码走查非法数据少约 6000 条 | 不能再归因于 MR 29874 模块为空。用户已确认 MR 29874 在镜像库 `merge_request` 和 `merge_request_fact` 中存在，且模块名为“平台”。本轮源码复核确认非法数据页默认应为“全部数据源”，只有明确选择 CrownCAD/DGM 且目标分支为空时才补 `dev`；前端此前默认第一数据源会缩小范围。剩余差异继续按老平台非法条件和新平台事实字段逐项比对。 | 已修正默认数据源和目标分支入口，仍需内网样本复核非法类型覆盖 |

## 客户问题模块范围补充

客户问题模块的数据范围必须记为 `CC_PRODUCT` / `CC_Product`，不是系统测试 CrownCAD 项目范围。依据来自 `C:\Users\admin\Downloads\产品客户问题响应管理机制.mm` 中“数据来源为 CCPRODUCT 数据”的需求说明，以及常驻规则 `docs/platform-page-business-rules.md`：

1. 默认项目为 `CC_Product`，当前老平台项目 ID 为 `325`。
2. 默认统计 2026-01-01 之后创建的客户问题议题。
3. 客户问题页面的顶部范围切换按里程碑/版本体验对齐老平台，除 `CC_PRODUCT议题` 默认全里程碑外，其余客户问题统计/非法/延期类页面默认第一可用里程碑。统计看板链路统一为前端 `milestoneTitle`、接口 `filterGroup.milestoneTitle`、后端 `issue_fact.milestone_title`、快照键 `project=325;milestone=...`；旧 `testingPhase` 参数只作为兼容输入归一化，不再作为新链路主字段。
4. 客户问题缺陷汇总、延期问题、非法数据、缺陷原因、按功能展示和响应/解决效率都不能回退到 CrownCAD 系统测试项目 `9` 的筛选口径，也不能通过里程碑或标签文本中的 `cc_product` 反推出客户问题范围。

后续排查客户问题页面空表时，第一步应先确认 `project_id=325`、创建时间下限、里程碑/阶段匹配和客户问题公共排除规则是否同时成立；不能用系统测试页面的 `testing_phase` 命中结果直接判断客户问题模块是否有数据。

## 2026-06-26 第一轮性能落地方案

本轮先处理内网最容易触发的两个慢点之一：页面刷新按钮声明了少量相关表，但实际提交成全局增量同步，导致 `events`、`notes` 等大表进入链路。该问题与统计主接口 15 秒超时不同，属于刷新链路过重；修掉后不会直接把所有统计页变成毫秒级，但能避免用户点“刷新最新数据”时像全量同步一样拖慢。

中间汇总表/统计快照方案是正确方向，但它不能替代规则对齐和查询范围收口。当前按三步落地：

1. 第一轮：页面实时刷新按页面声明的源表提交 `TABLE_REFRESH`，不再提交全局 `INCREMENTAL_SYNC`。
2. 第二轮：事实层刷新改为按受影响 issue/MR 重建。`notes` 变化时先通过 `noteable_type + noteable_id` 找到受影响议题或合并请求，而不是让事实 SQL 重新聚合大范围 `ods_gitlab_notes`。
3. 第三轮：为系统测试四个聚合看板和客户问题五个聚合看板落统计快照表。快照键包含页面 key、项目/测试阶段、规则版本、事实版本和筛选 hash；事实刷新成功后统一预热默认阶段快照，页面缺失快照时可自愈生成。下钻明细必须继续复用同一范围条件，确保父表数字和详情总数一致。

本轮快照覆盖的是首屏聚合结果，目标是避免重复打开同一事实版本时反复做大范围聚合；它不替代规则对齐。仍有规则差异风险的页面需要继续通过下钻明细和构造样本与老平台逐项对齐。

## 2026-06-29 三轮内性能落地记录

本轮按“先解决会导致 15 秒超时和同步全量偏移的结构问题，再把已收口口径的慢页快照化”的顺序推进。统计快照/中间表不再是可选方案，已作为系统测试慢统计页和客户问题首屏慢统计页的长期结构落地；尚未完成规则对齐的页面仍需通过内网样本继续核对下钻明细和导出。

1. 页面刷新入口收口：页面“刷新最新数据”按页面声明的相关源表提交刷新，不再把全局增量同步的全部白名单表带进来。系统测试/客户问题页面仍会刷新 `issues`、`label_links`、`labels`、`notes` 等必要表；`events` 不再因为普通页面刷新被顺手拉取。
2. 事实刷新对象化：事实刷新任务记录并使用 `mirrorRunId`，通过本次镜像任务写入行的 `mirror_task_id` 反查受影响 issue/MR，只重建这些对象对应的 `issue_fact` / `merge_request_fact`。宽依赖表变化、无法安全判断或影响对象超过 200 条时，仍退回原有较宽事实刷新，避免误漏数据。
3. 大表索引补充：新增默认镜像表 `mirror_task_id` 条件索引，重点覆盖 `notes`、`label_links`、`issue_assignees`、MR reviewer/assignee/metrics 等影响范围反查路径。
4. 大表分片同步：内网总量约 224W，`notes` 和 `events` 占一半以上时，原同步任务虽然每批 500 条，但同一张表只有一条 continuation 续跑链，导致 `notes`、`events` 各自串行，线程池最多只能同时跑两张大表。现已为配置中的大表默认 `notes,events` 建立基于主键签名 md5 前缀的分片任务；默认 `shardKeyLength=1`，即每张大表 16 条独立增量续跑链，每条链仍严格按 `updated_at + pk_signature` 游标每批 500 条拉取。全量同步和系统 hook 精确刷新不走分片，避免破坏首次全量基线和单条精确刷新语义。
5. 分片水位归并：分片任务不再各自推进全表 `last_watermark_at`。每个分片完成时只保存自己的最终游标；当同一运行同一表所有分片都没有 `QUEUED/RUNNING/RETRYING` 后，取每个分片最终游标的最大值，再对所有分片取最小值作为全表下一轮增量水位，并清空全局 `last_cursor_pk`。这样可以并行提速，又不会因为某个分片先结束而跳过其他分片未完成的数据。
6. 系统测试慢页首屏统计快照：`系统测试/议题阶段统计`、`系统测试/缺陷原因分析`、`系统测试/申请延期缺陷分析`、`系统测试/缺陷汇总` 的主表接口写入并读取 `statistic_board_snapshots`。同一 `board_key + scope_key + rule_version + source_version + filter_hash` 后续直接读快照。
7. 客户问题慢页首屏统计快照：`客户问题缺陷汇总`、`客户问题缺陷原因分析`、`延期问题`、`缺陷响应效率`、`按功能展示缺陷数量` 已接入同一快照机制。快照 payload 强制写入 `projectId=325` 和后端实际补入的默认 `milestoneTitle`，避免前端看似一个范围但后端默认到另一个范围导致缓存串用。
8. 动态表头快照：`statistic_board_snapshots` 新增 `definition_payload`，缓存 rows 的同时保存页面 definition。这样 `按功能展示缺陷数量` 这类按模块动态生成列组的页面，读取快照时不会出现“行数据来自快照、表头来自当前空定义”的错位。
9. 快照刷新闭环：`FactRefreshTaskWorkerService` 在 issue 事实刷新成功并完成任务落库后，调用 `StatisticBoardSnapshotRefreshService` 统一预热注册的 `StatisticBoardSnapshotRefresher`。当前注册页面只响应 `ISSUE` 事实类型，MR 事实刷新不会误触发系统测试/客户问题快照重建。

### 中间表冷启动和同步时机

1. 第一次全量镜像同步刚结束时，统计快照中间表可以是空的，因为镜像表只是原始数据层；只有后续 issue 事实构建成功后，`FactRefreshTaskWorkerService` 才会触发 `StatisticBoardSnapshotRefreshService.refreshAfterFactBuild(...)` 预热统计快照。
2. 正确链路应该是：全量/增量镜像同步完成 -> 事实层构建或增量事实刷新成功 -> 统计快照预热。用户首次打开页面时如果对应 `board_key + scope_key + rule_version + source_version + filter_hash` 已命中 READY 快照，就直接读中间表；如果没命中，页面会同步生成一次并写入快照。
3. 如果冷启动下缺少快照，页面首次请求仍会走原聚合逻辑，速度可能接近旧实现；若该次聚合超过前端 15 秒超时，用户请求会失败，且这次请求路径不一定能完成快照写入。因此不能依赖“用户第一次打开页面”作为主要预热手段。
4. 后续内网包应把“事实层重建后预热快照”作为需要事实层重建更新包的验收步骤。这样冷启动慢只发生在后台事实/快照任务阶段，而不转嫁给页面首屏。
5. 增量更新时同样会在 issue 事实刷新成功后刷新相关统计快照。同步时间会增加一段快照预热耗时，但它是后台任务，换来的是用户多次打开聚合页不再反复扫描大范围事实数据。若内网观察到事实刷新后快照预热明显拖慢同步闭环，应再把预热拆成异步低优先级队列或只预热默认阶段、默认里程碑和最近使用范围。
6. 快照不是替代事实层的永久缓存。规则版本、事实版本或筛选 hash 变化后旧快照不会命中；需要事实层重建的包必须同时考虑快照版本和预热范围。

本轮修复边界：

1. 首屏加载速度应明显改善，但下钻详情、规则说明和导出仍可能在内网全量数据下慢，需要复测后决定是否继续做详情页快照或 SQL 化。
2. 客户问题几个页面本轮先做首屏快照，内部聚合口径未改写为 SQL 聚合；如果内网首次缺快照时仍接近 15 秒，下一步继续把 `project_id=325`、`created_at>=2026-01-01`、默认里程碑和主要聚合前推 SQL。
3. 本轮新增两组迁移：`sync_run_table_tasks` 分片字段、`statistic_board_snapshots.definition_payload`。内网升级后需要执行数据库迁移，并在 issue 事实刷新成功后预热快照。若升级后首次打开页面时快照为空，页面会同步生成一次快照；后续同一事实版本会直接读取中间表。

## 关键代码证据

### 1. 加载超时不是默认分页失效，而是统计看板全量聚合

- 前端默认请求超时是 `15_000ms`：`frontend/src/api-client/request.ts`。
- 统计看板主接口没有 `page/size` 参数；表格分页是前端本地 `slice`：`frontend/src/composables/useStatisticBoardTableState.ts`。
- `SystemTestDefectCauseBoardService` 和 `SystemTestPhaseStatisticsBoardService` 都是 `loadSources(...) -> buildRuleFlowSnapshot(...) -> PageSliceSupport.slice(...)`，即先全量加载事实再聚合。
- 记录类页面不一样：`IssueFactRecordRepository.findPage(...)` 会先 `count(*)`，再 SQL `limit/offset`。

结论：缺陷原因分析、议题阶段统计的 15 秒超时主要来自统计看板全量事实加载和 Java 聚合，不是“所有表格默认分页失效”。本轮已对三个系统测试慢统计主表增加快照表读取路径，默认阶段快照由事实刷新链路预热，页面缺失快照时自愈生成。

同类风险页面已经扫到：

| 页面 | 当前加载模式 | 性能风险 |
| --- | --- | --- |
| 系统测试缺陷汇总 | 首屏主表已接入统计快照；缺快照时仍由现有 Java 聚合生成一次并写入中间表。 | 首屏重复打开不再重复聚合；下钻、规则说明和导出仍需复测。 |
| 系统测试缺陷原因分析 | 首屏主表先按快照读取；缺快照时由 SQL 聚合生成并写入 `statistic_board_snapshots`。 | 首屏主表已快照化；下钻、规则说明和导出仍需复测。 |
| 系统测试议题阶段统计 | 首屏主表先按快照读取；缺快照时由 SQL 聚合生成并写入 `statistic_board_snapshots`。 | 首屏主表已快照化；下钻、规则说明和导出仍需复测。 |
| 系统测试申请延期缺陷分析 | 首屏主表先按快照读取；缺快照时由 SQL 聚合生成并写入 `statistic_board_snapshots`。 | 首屏主表已快照化；下钻、规则说明和导出仍需复测。 |
| 客户问题缺陷汇总 | 首屏主表已接入统计快照；顶部范围、默认补齐、快照键和下钻条件统一到 `milestoneTitle`。 | 需继续用内网样本核对公共排除和下钻明细；下钻、规则说明和导出仍按实时查询。 |
| 客户问题延期问题 | 首屏主表已接入统计快照；顶部范围、默认补齐、快照键和下钻条件统一到 `milestoneTitle`。 | 需要重点验收 P1/P2/P3 24/48/72 小时响应延期、模板响应取消延期和解决延期 18 天规则。 |
| 客户问题缺陷原因分析 | 首屏主表已接入统计快照，且快照保存动态 definition；里程碑候选改为 CC_Product 真实里程碑。 | 导出和规则说明仍需内网复测；如果首次生成仍慢，再做 SQL 聚合。 |
| 客户问题按功能展示缺陷数量 | 首屏主表已接入统计快照，且快照保存动态模块列 definition；展示字段已按老平台动态模块列组对齐。 | 首次生成仍可能较慢；后续可把 `project_id=325 + milestone_title + function_name` 聚合前推 SQL。 |
| 客户问题响应/解决效率 | 首屏主表已接入统计快照；顶部范围、默认补齐、快照键和下钻条件统一到 `milestoneTitle`。 | 需要重点验收响应周期、解决周期和多模块分别计入。 |

按功能展示缺陷数量的字段对齐补充：

1. 老平台页面 `D:/projects/spidergitdata-dev/webapp/src/views/PageStandard/IssueShowByFunction.vue` 主表按模块动态生成列组，每个模块列组下只有两个子列：`功能`、`问题数量`。
2. 本轮新平台 `customer-issue-by-function` 主表已改为同款动态列结构：首列为序号，每个模块生成一个列组，列组下展示功能名和问题数量；点击问题数量仍下钻到对应模块 + 功能的议题明细。
3. 本轮额外调整了模块列顺序和局部 UI：模块按功能数量从多到少排序，功能名单元格左对齐，问题数量使用紧凑可点击按钮，减少旧版和新版都存在的横向阅读压力。
4. 该页面性能仍属于“事实查询后 Java 透视聚合”。若内网数据量仍接近 15 秒，下一步应把 `project_id=325`、`created_at>=2026-01-01`、`milestone_title` 和 `function_name is not null` 聚合前推 SQL。

这些页面“默认分页”的说法只适用于最终表格展示或下钻切片，不适用于主接口的数据读取。真正要降到稳定 1-3 秒，需要把页面范围、默认阶段/里程碑、客户问题 scope 和主要聚合前推到 SQL，并将稳定口径接入统计快照/缓存。

### 2. 默认测试阶段未对齐会同时影响数量和性能

老平台不是所有页面默认“全部测试阶段”。已从代码确认到的旧平台默认包括：

- `D:/projects/spidergitdata-dev/webapp/src/views/PageStandard/IllegalIssueSearch.vue`：没有阶段值时使用 `CC2026R3`。
- `D:/projects/spidergitdata-dev/webapp/src/views/PageStatisticsInfo/charts/RollbackModulePieChart.vue`：`testingPhase` 初始值为 `CC2026R3`。
- `D:/projects/spidergitdata-dev/webapp/src/views/PageNinePersonalQuality/NinePersonalQuality.vue`：`testingPhase` 和 `projectName` 初始值为 `CC2026R3`。
- `D:/projects/spidergitdata-dev/webapp/src/views/PageHome/ContentComponents/QuestionnaireInfo/ModuleTable.vue`：阶段列表加载后默认取 `phaseNameList[0]`。

新平台现状：

- 系统测试统计看板、系统测试非法数据和客户问题统计看板已统一使用 `defaultStrategy = 'first-available'`，默认值写入 URL 后再请求主数据，避免前端显示“全部”但后端静默补默认阶段。
- `系统测试/议题查询` 是内网确认的例外：默认阶段为空，表示“全部测试阶段”；显式选择阶段时才按父级阶段展开到具体轮次。
- `CC_PRODUCT议题` 记录页按老平台记录页默认全里程碑，加载 CC_Product 里程碑候选只用于用户主动筛选；客户问题延期记录仍默认第一可用里程碑，避免延期类页面首屏扫全量。

影响判断：

1. 对比老平台时，如果新平台默认全阶段，会把多版本、多阶段数据一起纳入，系统测试非法数据等页面会天然偏多，慢加载页面也会更接近 15 秒。
2. 客户问题页面如果前端默认“全部测试阶段”、后端又静默补默认阶段，用户看到的筛选范围、URL、导出/下钻条件和接口真实条件不一致，排查统计差异时容易误判。
3. 默认阶段应来源于“系统设置 - 议题测试阶段定义”的排序或后续显式默认配置，当前内网样例是 `CC2026R3`，但不能把该版本写死成永久规则。

已同步到常驻业务规则：系统测试统计看板、系统测试非法数据和客户问题相关页面进入时应默认选中老平台当前默认或第一可用启用父级阶段/里程碑，不得隐式默认“全部测试阶段”；默认值必须同时体现在顶部控件、URL、接口筛选、导出和下钻条件中。系统测试议题查询明确作为例外，默认阶段为空。

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

如果内网 CC_Product 的里程碑不是这个父级阶段，默认筛选会把客户问题数据过滤空。当前代码已补 `CustomerIssuePhaseSupport` 的版本键兼容，并让 CC_PRODUCT 记录页默认走真实里程碑候选；客户问题统计页仍需内网复测确认“父级阶段 -> 里程碑/版本键”是否覆盖所有真实里程碑。

### 5. 系统测试非法数据偏多、议题查询偏少的口径差

- 系统测试非法数据走 `IssueFactRecordPageQuery.Scope.SYSTEM_TEST`，默认项目 `9`，默认第一可用父级阶段，并按阶段定义展开后筛选非法数据。
- 议题查询走 `IssueFactRecordPageQuery.Scope.ALL`，默认项目也是 `9`，但不默认选择阶段；这是内网确认的“全部测试阶段”检索入口。

因此这两个页面默认范围本来就不同：非法数据是阶段内质量问题列表，议题查询是 CrownCAD 项目记录检索。后续数量差异不能再简单按“一个多一个少”互相校验，而应分别和老平台对应页面同条件对比。

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

1. 系统测试统计看板、系统测试非法数据和客户问题相关页面默认阶段/里程碑走第一可用启用父级阶段；`系统测试/议题查询` 是记录检索特例，默认显示“全部测试阶段”。
2. 统计看板会等待 `first-available` 默认阶段写入 URL 后再请求数据，避免系统测试缺陷汇总首屏先出现“总计 0”再二次刷新成真实数据。
3. 系统测试非法数据改回记录列表口径：默认项目 `9`，默认第一可用父级阶段，按阶段定义展开后匹配事实层 `testing_phase`，SQL 快路径不再使用 `Scope.ALL`。
4. 系统测试议题查询在无阶段参数时保持空阶段，表示“全部测试阶段”；前端显示、URL、接口查询和导出入口保持同一默认范围。
5. 系统测试缺陷汇总改回老平台主口径：默认项目 `9`，没有测试阶段时不查询全量；有阶段时先按阶段定义展开具体轮次，再用事实层 `testing_phase LIKE 具体阶段` 参与统计，不再用“系统测试/回归测试”标签作为前置范围。
6. 客户问题阶段匹配增加版本键兼容，`2026R3`、`CC2026R3`、`CrownCAD 2026R3` 这类里程碑/阶段值可命中同一个父级阶段；记录页和统计页共用 `CustomerIssuePhaseSupport`，避免 CC_Product 有模块但被默认阶段过滤成空表。
7. 代码走查 `GitLab 接口报错` 判断兼容有空格和无空格文案，并把扫描状态、目标分支、负责人、审查人、指派人展示字段都纳入判断，降低老平台非法样本漏判。
8. 事实增量构建遇到旧事实缺少搜索索引/阶段派生字段时，先按小批量修补索引，再用 `max(ods_updated_at)` 计算增量边界，不再直接退化成全量事实重建。
9. 页面“进入页面自动刷新最新数据”的默认偏好改为关闭；用户显式打开后才会在进页时触发刷新，避免普通打开统计看板就创建同步 run。
10. 代码走查非法数据、系统测试非法数据和客户问题非法数据的行操作已统一收敛为只保留“查看详情/查看详细”。用户侧不再展示“刷新本条”，避免开发测试阶段误触真实同步/GitLab 写链路，也避免和老平台使用习惯不一致。后端单条刷新接口暂不删除，仅作为内部调试/兼容接口保留，不再作为这些页面的可见入口。
11. 系统测试非法数据主表展示字段已按老平台 `IllegalIssueSearch.vue` 对齐为：议题编号、模块名、议题标题、议题状态、严重程度、议题处理人、非法类型。客户问题非法数据沿用同一字段集合；测试阶段、功能名、提交/更新时间、提交人、测试状态、里程碑和标签等字段保留在详情抽屉中。
12. 普通统计看板导出已接入通用 xlsx 工作簿导出，按当前 `StatisticBoardDefinition.columnGroups` 递归生成多级表头，并按页面当前 rows/cells 导出。已有专用导出的系统测试/客户问题缺陷原因分析和系统测试横向对比继续使用专用实现。

## 已复核的新老平台字段映射

本节用于后续内网验收时逐项核对“老平台筛选/展示字段”和“新平台前端、后端、事实层字段”是否一致。结论以当前代码和 `docs/platform-page-business-rules.md` 为准。

| 页面 | 老平台字段/参数 | 新平台前端 key / 展示字段 | 后端请求/服务字段 | 新平台事实/SQL 字段 | 复核结论 |
| --- | --- | --- | --- | --- | --- |
| 代码走查非法数据 | 顶部项目 `projectName` | `projectName`，顶部“项目”切换 | `CodeReviewIllegalRecordQueryRequest.projectName` | `merge_request_fact.project_name like ?` | 已对齐。这里是代码走查所属项目/版本，不是 GitLab `projectId`。 |
| 代码走查非法数据 | 数据源/老平台 `name`，用于 `CrownCAD/DGM` 目标分支默认 | `source`，数据源切换 | `CodeReviewIllegalRecordQueryRequest.source` | `merge_request_fact.source_instance` | 已对齐。只有明确选择 CrownCAD 或 DGM 且目标分支为空时，才按老平台补 `dev`。 |
| 代码走查非法数据 | 被走查人 `author` | 条件筛选仍使用页面 key `owner`，详情展示“被走查人” | `CodeReviewIllegalRecordQueryRequest.owner`，规则层映射为 `author` | SQL 条件使用 `author_name`；详情来源 `author_name as author` | 已对齐。不能映射到 `owner_name`，`owner_name` 是责任人/看板维度，不是非法数据页的被走查人。 |
| 代码走查非法数据 | 走查人 `assignee` | 详情展示“走查人” | `reviewerNames` | `reviewer_names`，并兼容旧走查异常来源 | 已对齐为展示字段；非法类型仍需继续用构造数据对比旧平台异常文案覆盖率。 |
| 代码走查非法数据 | 被指派人 `assigneed` | 详情展示“被指派人” | `assigneeNames` | `assignee_names` | 已对齐展示。 |
| 代码走查非法数据 | 展开行字段：走查编号、所属项目、走查时间、模块名、被走查人、走查人、被指派人、合并时间、合并人、目标分支、工作量、行数、缺陷数、速率/密度/效率 | `CodeReviewIllegalRecordsView.vue` 详情抽屉 | `CodeReviewIllegalRecordRowResponse` | `merge_request_fact` 派生字段 | 已对齐老平台必备字段；新平台追加项目名称、代码库、扫描状态和分类缺陷数不改变老字段含义。 |
| 系统测试非法数据 | 顶部测试阶段 `phaseName/testingPhase` | `testingPhase`，默认第一可用启用父级阶段 | `SystemTestIllegalRecordQueryRequest.testingPhase` | 父级阶段展开后匹配 `issue_fact.testing_phase` | 已对齐。默认不再落到全部测试阶段。 |
| 系统测试非法数据 | 模块名 `moduleName` | 条件 key `moduleName`，主表“模块名” | `IssueFactRecordListRequest.moduleName` | `issue_fact.module_names` 拆分/显示模块匹配 | 已对齐。主表字段名也改为“模块名”。 |
| 系统测试非法数据 | 非法类型 `illegalType / illegalList` | 条件 key `illegalReason`，主表“非法类型” | `SystemTestIllegalRecordQueryRequest.illegalReason` | `issue_fact.illegal_reasons` / `illegal_reason`，通过 `SystemTestIllegalReasonSupport` 做老文案映射 | 已对齐多值非法类型口径。 |
| 系统测试非法数据 | 议题提交人 `author` | 条件 key `authorName`，详情“议题提交人” | `IssueFactRecordListRequest.authorName` | `issue_fact.author_name` | 已对齐。 |
| 系统测试非法数据 | 议题处理人 `handler` | 条件 key `assigneeName`，主表/详情“议题处理人” | `IssueFactRecordListRequest.assigneeName` | `issue_fact.assignee_name` | 已对齐。 |
| 系统测试非法数据 | 展开行字段：更新时间、提交时间、模块名、功能名、议题编号、标题、提交人、处理人、状态、测试状态、严重程度 | 主表保留老平台 7 列；详情抽屉补齐完整展开字段 | `SystemTestIllegalRecordRowResponse` | `issue_fact.updated_at_source/created_at_source/module_names/function_name/iid/title/author_name/assignee_name/issue_state/bug_status/severity_level` | 已对齐。测试阶段、里程碑、项目和标签作为新平台增强信息保留在详情。 |
| 客户问题非法数据 | 顶部里程碑/测试阶段 `mileStone` | `testingPhase`，Element Plus 原生单选，一行一个选项 | `CustomerIssueIllegalRecordQueryRequest.testingPhase` | 优先匹配 `issue_fact.milestone_title`，再兼容父级阶段展开后的 `testing_phase` | 已对齐客户问题使用体验，范围固定 `CC_Product` / 项目 325。 |
| 客户问题非法数据 | 模块名 `moduleName` | 条件 key `moduleName`，主表“模块名” | `IssueFactRecordListRequest.moduleName` | `issue_fact.module_names` | 已对齐。 |
| 客户问题非法数据 | 非法类型 `illegalType / illegalList` | 条件 key `illegalReason`，主表“非法类型” | `CustomerIssueIllegalRecordQueryRequest.illegalReason` | `issue_fact.illegal_reasons` / `illegal_reason`，通过 `CustomerIssueIllegalReasonSupport` 做老文案映射 | 已对齐，并追加客户问题调研模板、计划解决时间、一级缺陷签字规则。 |
| 客户问题非法数据 | 议题提交人 `author` | 条件 key `authorName`，详情“议题提交人” | `IssueFactRecordListRequest.authorName` | `issue_fact.author_name` | 已对齐。 |
| 客户问题非法数据 | 议题处理人 `handler` | 条件 key `assigneeName`，主表/详情“议题处理人” | `IssueFactRecordListRequest.assigneeName` | `issue_fact.assignee_name` | 已对齐。 |
| 客户问题非法数据 | 展开行字段同老平台 `IllegalIssueSearchCCProduct.vue` | 主表同系统测试非法数据；详情抽屉补齐完整展开字段 | `CustomerIssueIllegalRecordRowResponse` | `issue_fact` 客户问题事实字段 | 已对齐。项目、里程碑、优先级和标签为新平台增强字段。 |
| 系统测试议题查询 | 老平台默认项目 CrownCAD `projectId=9`，默认全部测试阶段；显式选择阶段时按父级展开 | 前端 URL/接口默认不写 `testingPhase`，下拉展示“全部测试阶段”；显式选择时写入 `testingPhase` | `SystemTestIssueSearchRequest` | `issue_fact.project_id`，显式阶段筛选时使用 `testing_phase` / `phase_filter_value` | 已对齐内网反馈的默认范围；后续仍需用样本对比列表字段和导出字段。 |
| 系统测试缺陷汇总 | `phase` 默认第一阶段或 `CC2026R3`，按 `testing_phase LIKE` | 数据范围 key `testingPhase` | `SystemTestDefectSummaryBoardService` | `issue_fact.testing_phase` 按阶段定义展开后 `LIKE` 匹配 | 已对齐核心统计范围和默认阶段；模块目录差异需在后续样本对比中单独标记。 |
| 系统测试议题查询 | `issuableReference` | 筛选/主表 `issueIid` | `SystemTestIssueSearchRequest.issueIid` | `issue_fact.iid`，跳转使用 `source_instance + project_id + iid` | 已对齐。 |
| 系统测试议题查询 | `issueTitle` | 筛选/主表 `title` | `IssueFactRecordListRequest.title` | `issue_fact.title` | 已对齐。 |
| 系统测试议题查询 | `projectId/projectName` | 默认 `projectId=9`，筛选 `projectName` | `IssueFactRecordListRequest.projectId/projectName` | `issue_fact.project_id/project_name` | 已对齐默认 CrownCAD 范围；项目名称筛选不替代项目 ID 默认。 |
| 系统测试议题查询 | `moduleName` | 筛选 `moduleName`，主表“模块” | `IssueFactRecordListRequest.moduleName` | `issue_fact.module_names` 多模块拆分匹配 | 已对齐。 |
| 系统测试议题查询 | `functionName` | 筛选/主表 `functionName` | `IssueFactRecordListRequest.functionName` | `issue_fact.function_name` | 已对齐。 |
| 系统测试议题查询 | `author` | 筛选 `authorName`，主表/详情“创建人” | `IssueFactRecordListRequest.authorName` | `issue_fact.author_name` | 已对齐。 |
| 系统测试议题查询 | `handler` | 筛选/主表 `assigneeName`，展示“处理人” | `IssueFactRecordListRequest.assigneeName` | `issue_fact.assignee_name` | 已对齐。 |
| 系统测试议题查询 | `status` | 筛选 `issueState`，主表“议题状态” | `IssueFactRecordListRequest.issueState` | `issue_fact.issue_state` | 已对齐。 |
| 系统测试议题查询 | `bugStatus` | 筛选/主表 `bugStatus` | `IssueFactRecordListRequest.bugStatus` | `issue_fact.bug_status` | 已对齐。 |
| 系统测试议题查询 | `severityLevel` | 筛选/主表 `severityLevel` | `IssueFactRecordListRequest.severityLevel` | `issue_fact.severity_level` | 已对齐。 |
| 系统测试议题查询 | `category` | 筛选 `category` | `IssueFactRecordListRequest.category` | `issue_fact.category` | 已对齐。 |
| 系统测试议题查询 | `mileStone` | 筛选 `milestoneTitle` | `IssueFactRecordListRequest.milestoneTitle` | `issue_fact.milestone_title` | 已对齐。 |
| 系统测试议题查询 | `submissionDate/updatedDate` | `createdAtRange/updatedAtRange` | `createdAtStart/createdAtEnd/updatedAtStart/updatedAtEnd` | `issue_fact.created_at_source/updated_at_source` | 已对齐。 |
| 系统测试缺陷原因分析 | 顶部测试阶段 | 数据范围 `testingPhase`，默认第一可用父级阶段 | `SystemTestDefectCauseBoardService` | 先按阶段定义展开，再匹配 `issue_fact.testing_phase` | 已对齐默认范围；主接口仍是事实查询后 Java 聚合，性能风险另列。 |
| 系统测试议题阶段统计 | 顶部测试阶段 | 数据范围 `testingPhase`，默认第一可用父级阶段 | `SystemTestPhaseStatisticsBoardService` | 阶段定义展开后匹配 `issue_fact.testing_phase` | 已对齐默认范围。 |
| 系统测试申请延期缺陷分析 | 顶部测试阶段 | 数据范围 `testingPhase`，默认第一可用父级阶段 | `SystemTestDelayAnalysisBoardService` | 阶段定义展开后匹配 `issue_fact.testing_phase` | 已对齐默认范围。 |
| 系统测试横向对比 | `projectName/testingPhase/moduleName` | 条件筛选 `projectName/testingPhase/moduleName` | `SystemTestHorizontalComparisonExportService.ExportScope` | 议题按 `issue_fact.testing_phase/module_names`，代码走查按 `merge_request_fact.project_name/target_branch`，评审按 `review_records.project_name` | 已对齐核心范围；导出格式仍需按老平台模板继续逐列复核。 |
| 客户问题缺陷汇总 | 老平台里程碑/版本切换 | 数据范围 `testingPhase`，展示“测试阶段” | `CustomerIssueTestingPhaseFilterSupport` | 优先 `issue_fact.milestone_title`，再兼容 `testing_phase` | 已对齐默认不再“全部测试阶段”，范围固定 CC_Product。 |
| 客户问题缺陷原因分析 | 老平台里程碑/版本切换 | 数据范围 `testingPhase`，条件 `milestoneTitle` 可叠加 | `CustomerIssueDefectCauseBoardService` | `milestone_title` 优先，`testing_phase` 兼容 | 已对齐范围和阶段切换。 |
| 客户问题延期问题 | 老平台里程碑、模块、紧急程度/延期类型 | 数据范围 `testingPhase`，条件 `moduleName/priorityLevel/delayIssue` | `CustomerIssueDelayIssuesBoardService` | `issue_fact.milestone_title/module_names/priority_level/is_response_delayed/is_resolve_delayed` | 已对齐范围；SLA 字段来自事实层本次新规则，内网需重点验收 P1/P2/P3 和 18 天规则。 |
| 客户问题响应/解决效率 | 老平台里程碑/版本切换 | 数据范围 `testingPhase`，条件 `milestoneTitle/moduleName` | `CustomerIssueResponseEfficiencyBoardService` | `issue_fact.milestone_title/testing_phase/module_names/response_cycle_hours/resolve_cycle_days` | 已对齐范围；周期计算依赖本次事实层重建。 |
| 客户问题按功能展示 | 老平台里程碑、模块、功能 | 数据范围 `testingPhase`，条件 `moduleName/functionName/milestoneTitle` | `CustomerIssueByFunctionBoardService` | `issue_fact.milestone_title/testing_phase/module_names/function_name` | 已对齐范围和字段映射。 |
| 评审数据管理-新增评审 | 项目 | 表单 `projectName` | `ReviewDataFilterOptionService.projectNames` / `ReviewDataRecordSaveRequest.projectName` | 候选优先 `ods_gitlab_projects.name`，保存到 `review_records.project_name` | 已对齐：候选来自镜像库全量项目，历史评审数据仅兜底补充。 |
| 评审数据管理-新增评审 | 模块 | 表单 `moduleName` | `ReviewDataFilterOptionService.moduleNames` / `ReviewDataRecordSaveRequest.moduleName` | 候选优先 `ods_gitlab_labels.title` 按模块标签规则归一化，保存到 `review_records.module_name` | 已对齐。 |
| 评审数据管理-新增评审 | 评审负责人 | 表单 `reviewOwner` | `ReviewDataFilterOptionService.reviewOwners` / `ReviewDataRecordSaveRequest.reviewOwner` | 候选优先 `ods_gitlab_users.name`，保存到 `review_records.review_owner` | 已对齐。 |
| 评审数据管理-新增评审 | 评审专家/作者/责任人 | 表单 `reviewExperts`、`authorName`；问题项 `reviewerName/ownerName` | `ReviewDataRecordSaveRequest.reviewExperts/descriptions.authorName/contents.reviewerName`，问题项保存请求 | 候选优先 `ods_gitlab_users.name`，分别保存到 `review_record_experts`、`review_descriptions.author_name`、`review_problem_items.*` | 已对齐，避免只从已导入评审记录取人。 |
| 评审数据管理-新增评审 | 评审版本 | 表单 `reviewVersion` | `ReviewDataFilterOptionService.reviewVersions` / `ReviewDataRecordSaveRequest.reviewVersion` | 候选优先 `ods_gitlab_milestones.title`，保存到 `review_records.review_version` 和描述行版本 | 已对齐。 |
| 镜像设置-延期标签写回 | 老平台 GitLab 写标签任务 | 配置 `delayLabelWritebackEnabled`，默认 false | `GitlabSyncConfig.delayLabelWritebackEnabled` + 后端全局开关 `platform.gitlab-mirror.delay-label-writeback-api-enabled` | `gitlab_sync_configs.delay_label_writeback_enabled` + 环境变量 `CUSTOMER_ISSUE_DELAY_LABEL_WRITEBACK_API_ENABLED` | 已对齐安全边界：发布包默认关闭全局写接口；只有全局开关和数据源开关同时开启且配置 token/web 地址时，才差异增删 `响应已延期/解决已延期` 并去重，不覆盖全量 labels。 |

字段映射审计边界：

1. 上表覆盖本轮已经修改或明确复核过的页面、切换字段、筛选字段、主表/详情/表单字段和关键导出范围字段。
2. 未在本轮改动且未列入上表的页面，不能因为本次打包就视为已经完成逐字段老平台源码审计；后续仍按“老平台源码字段 -> 新平台前端 key -> 后端请求 -> SQL/事实字段”的路径补齐。
3. 本次相对 2026-06-25 空包包含 `FactBuildService`、`IssueFactNormalizationRules`、客户问题 SLA、延期标签写回配置、增量同步边界、事实刷新链路、统计快照和统计口径变更。既有内网实例交付应按“需要事实层重建的增量更新包”处理：保留 PostgreSQL volume、镜像表、同步状态和用户配置，替换业务镜像后基于现有镜像表重建事实层并预热统计快照；只有业务方明确要求清空环境时才制作空数据新包。

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

1. 客户问题模块仍是第一优先级遗留项。客户问题范围必须固定为 `CC_PRODUCT` / `CC_Product`，老平台项目 ID 为 `325`，并按 `C:\Users\admin\Downloads\产品客户问题响应管理机制.mm` 的响应、解决、延期、非法模板规则执行。本轮已补齐平台内事实闭环：`issue_fact.is_response_delayed` 只对 CC_Product、2026-01-01 后创建、open 且未按 `# 问题调研情况说明` 响应的议题生效，并按 P1/P2/P3 或未设定紧急程度的 24/48/72 小时规则计算；模板响应后即使原始标签仍有 `响应已延期`，事实延期也会取消。`issue_fact.is_resolve_delayed` 按调研模板“计划解决时间/预计解决时间”日期和 18 天上限取更早期限，`申请延期`、`数据异常`、`需求如此/设计如此`、`未复现`，以及带 `已修复/完成` 且已填写修复模板 `### 1、修复状态` 的议题会取消解决延期。
2. 老平台还会在每小时 CC_Product 任务中通过 GitLab API 更新 `响应已延期` / `解决已延期` 标签。本轮已补后端受控写回通道，但为了避免开发测试阶段扰动仍在运行的老平台，写回需要同时满足全局开关 `CUSTOMER_ISSUE_DELAY_LABEL_WRITEBACK_API_ENABLED=true` 和数据源开关 `gitlab_sync_configs.delay_label_writeback_enabled = true`，并配置 `api_token`、`web_base_url`。本次内网测试包默认全局关闭，即使页面误开数据源开关也不会调用 GitLab 写接口；验收时先看 `issue_fact.is_response_delayed`、`issue_fact.is_resolve_delayed`、延期统计页和差异去重规则。后续如需真实验证写回，应使用隔离 GitLab/隔离项目，或由业务方明确批准后再临时打开全局开关。开启后，事实构建和小时级客户问题延期闭环任务会按事实字段计算差异，只通过 GitLab Issue API 的 `add_labels` / `remove_labels` 增删这两个延期标签；不覆盖 GitLab 上的完整 labels，不改写其它标签，重复延期标签会先去重，差异为空时不发请求。
3. 客户问题响应/解决效率需要用不同测试数据继续和老平台逐页对比。重点样本应覆盖：无紧急程度按 P3、按模板响应、未按模板响应、预计解决时间早于 18 天、预计解决时间超过 18 天、已修复/完成、申请延期、数据异常、需求如此/设计如此、未复现、多模块议题。对比目标不是模拟完整内网环境，而是用同一批构造数据在新老平台不同页面之间确认规则筛选差异。
4. 系统测试缺陷汇总、缺陷原因分析、议题阶段统计、申请延期缺陷分析，以及客户问题缺陷汇总、缺陷原因分析、延期问题、响应效率、按功能展示的首屏主接口已在 2026-06-29 接入 `statistic_board_snapshots`。仍需内网全量复测确认重复打开是否稳定低于 15 秒；详情、规则说明和导出路径仍可能走原明细查询，若复测仍慢，再继续做 SQL 分页、详情快照或流式导出。
5. 普通统计看板导出已补齐通用 xlsx 工作簿：`AbstractStatisticBoardService` 默认实现 `StatisticBoardWorkbookExportSupport`，按当前页面定义导出多级表头和当前数据。仍需内网验收下载文件的列顺序、合并表头和数值格式是否满足老平台使用习惯；若某个页面有老平台专用模板，再单独覆盖默认导出。
6. 系统测试横向对比导出已经有专门 xlsx 服务，但讨论中要求必须和老平台格式完全一致。当前代码 `SystemTestHorizontalComparisonExportService` 使用扁平 header 文案生成工作簿，并按 `target_branch = dev`、`MERGED` 统计代码走查；仍需拿老平台导出模板逐列、合并表头、顺序、空行过滤、数值格式和 DGM/CrownCAD 分块进行一次精确复核。
7. 代码走查非法数据少约 6000 条仍不能只用 MR 29874 解释。当前目标分支筛选已支持：只在明确选择 CrownCAD 或 DGM 且目标分支为空时默认补 `dev`，用户可按条件筛选其他分支；但缺失数量仍需直接提取老平台非法判断代码，和新平台 `review_exception_reason / scan_status / scan_bug_count / annotation_rate_result / bug_count_result / project_name / module_name / target_branch / owner_name / reviewer_names` 映射逐项比对。下一步应用同一批构造 MR 数据在新老平台导入后比较非法类型命中差异。
8. 议题刷新必须包含评论。当前 `IssueFactRealtimeRefreshService` 的刷新表包含 `notes`，`GitlabFactSourceSqlProvider` 也从 `ods_gitlab_notes` 汇总 issue 评论并写入事实层 `raw_payload`、缺陷原因、响应模板和 SLA 字段；这条链路代码上已经覆盖。但需要在后续真实链路中用“只改评论、不改议题主体字段”的数据验证增量同步是否会触发 `notes -> issue_fact -> 缺陷原因/客户问题延期/响应效率` 更新。
9. 官方默认筛选、默认阶段、展示规则不能被个人保存视图覆盖。当前讨论确认这是老平台已确认的固定规则边界；后续如果实现保存视图，必须只作用于个人账号，不能改写平台官方默认条件、导出条件和领导视角默认展示。
10. 评审数据管理页“新增评审”的项目、模块、评审负责人、评审专家、作者、责任人和评审版本下拉不能只来自已导入评审记录；本轮已确认并补规则：候选值优先来自 GitLab 镜像库全量项目、用户、标签归一化模块和里程碑，历史评审数据只作为兜底补充，避免过去评审未涉及的人、模块或项目无法新增评审。

## 2026-06-29 全页面首屏加载顺序复核

本轮针对“页面先用空范围/全部范围请求一次，再等下拉项或默认值加载后请求第二次”的问题做了全页面扫描和代码复核。结论如下：

1. 通用统计看板 `StatisticBoardView.vue` 已通过 `routeScopeReady` 等待 `useDataScope` 的 `first-available` 默认阶段写入路由后再加载主表，覆盖系统测试缺陷汇总、申请延期缺陷分析、系统测试缺陷原因分析、议题阶段统计，以及客户问题缺陷汇总、缺陷原因分析、延期问题、响应效率、按功能展示缺陷数量。该类页面不应再出现“先全部测试阶段/空阶段请求一次”的首屏请求。
2. 系统测试/客户问题非法数据通用页 `IssueIllegalRecordsPage.vue` 已通过 `filterOptionsLoaded + primaryFilterDefaultsReady` 等待默认测试阶段或里程碑写入路由后再加载表格。系统测试非法数据默认第一可用测试阶段；客户问题非法数据默认 CC_Product 里程碑，项目固定 `325`。
3. `CC_PRODUCT议题` / 客户问题延期记录页 `CustomerIssueRecordsView.vue` 已区分页面画像：`CC_PRODUCT议题` 默认全里程碑并允许“全部里程碑”，延期记录等待第一可用里程碑写入后再加载。
4. 代码走查非法数据页和代码走查多元看板此前存在真实问题：`source` 数据源候选异步加载，`useDataScope` 再写入默认数据源，但页面加载函数已经先按空 `source` 发起请求。当前修正后，非法数据页默认“全部数据源”并不等待首个数据源；多元看板仍按页面语义等待首个数据源落路由后再请求业务数据。
5. 系统测试议题查询、评审数据管理、系统测试多元看板、数据库浏览器等页面默认就是记录检索或全局概览，没有老平台要求的“必须默认某个阶段/里程碑”的异步默认范围；它们加载筛选项和列表/图表不是本轮所说的“错误首屏全量再默认重查”。其中系统测试议题查询按内网反馈必须默认“全部测试阶段”。
6. 本地镜像库直查未完成：`localhost:15432` 当前没有 PostgreSQL 监听，无法用本机 `ods_gitlab_*` 表做实库抽样；本轮以代码证据确认候选来源，后续在内网或本地库恢复后再用只读 SQL 核对项目、用户、标签和里程碑候选数量。

评审数据管理补充结论：

1. 老平台“新增评审”中的“工作产品描述”和“评审分工内容”确实存在，不是新平台新增错放。对应老平台文件包括 `PageHome/ContentComponents/QuestionnaireInfo/AddDescription.vue` 和 `AddContent.vue`，字段分别是评审工作产品、版本号、作者/负责人、规模、单位，以及评审专家、评审分工内容、独立评审工作量、有效独立评审问题数、会议评审工作量、会议评审问题个数。
2. 新平台“新增评审”候选来源已对齐常驻规则：项目来自 `ods_gitlab_projects.name`，模块来自 `ods_gitlab_labels.title` 按平台模块标签规则归一化，评审负责人/评审专家/作者来自 `ods_gitlab_users.name`，评审版本来自 `ods_gitlab_milestones.title`；历史评审导入记录只作为兜底补充。
3. 新平台“新增问题清单”中的评审人和责任人也必须使用镜像用户全集。此前评审人弹窗会优先用当前评审记录的专家列表，导致未被当前记录选为专家的人不方便补录问题；本轮改为“当前评审专家置前 + 镜像用户全集补齐”，责任人继续使用镜像用户候选，避免从已导入评审数据反推人员范围。

### 后续新功能，先不作为本轮对齐目标

1. 测试阶段自动维护：R4 等新版本开始后，按 GitLab 标签或固定前缀自动同步父级阶段和 1-6 轮子阶段。这是重要优化，但老平台当前也是人工维护，先不阻塞本轮老功能对齐。
2. 项目名称自定义和标签同步：评审项目可自定义，但后续要与议题/MR 标签名称对齐，保证横向对比能合并同一项目。这是老平台遗留痛点和新平台治理能力，需单独设计项目别名或映射规则。
3. 权限、LDAP、角色默认视图、个人保存视图和不同账号不同展示方式，属于上线后的平台能力；当前仅记录边界，不抢客户问题、系统测试和代码走查规则对齐优先级。

## 仍需验证的执行方式

- 对于无法直接复现完整内网全量环境的问题，后续不要表述为“缺失内网样本导致无法判断”；应明确为“通过不同测试数据组合，对比新老平台不同页面之间的规则筛选差异”，再根据差异矩阵定位是哪条筛选规则不一致。
- 代码走查非法数据、客户问题延期/响应效率、系统测试缺陷原因分析和导出格式都应采用“提取老平台代码规则 -> 构造覆盖边界的数据 -> 新老平台同数据对比 -> 回填常驻业务规则和测试”的路径。
