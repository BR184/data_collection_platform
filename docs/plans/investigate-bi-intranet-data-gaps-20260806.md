# 进度与中间物

- 状态：2026-08-10 生产实现和本地验证已完成。来源级事实发布屏障、依赖就绪代际、失败范围恢复、历史 outbox 收敛、CAT 原子保存、BI 合法观测、提交事实稳定关联及前后端修复均已落地；未保留父运行专属事实消费旧路径、兼容别名或双轨逻辑。下一阶段为全新内网隔离打包和现场验收；当前工作树混有多个既有工作单元且发布远端未指定，本工作单元未执行暂存、提交或推送。
- 已确认截图行为：设计页问题类别和总体密度有数据，但模块质量、单次评审散点为 `INCOMPLETE`；编码页提交趋势/频次为提交明细不完整，静态扫描为当前范围无数据，代码质量趋势为输入不完整；首次全量尚未结束时系统测试原因大类和子类共 3583 条全部落入“未归类”，全部数据同步完成后恢复正常，但顶部 P1/P2 卡片仍为 `--` 且“各模块系统测试修复率达成情况”仍不展示；CAT `PUT /api/bi-cat-mirror/config` 返回 `500`，后端同时出现 PostgreSQL `value too long for type character varying(32)`。
- 已确认代码边界：编码页三类事实分别来自 `code_review_match_mode_records`、`merge_request_commit_fact` 和 `bi_code_review_compatibility_records`；主兼容同步成功不能证明后两类事实已发布。提交事实还把 GitLab 仓库名错误当成产品版本名筛选；扫描、注释率、设计模块和散点均使用全范围 `allMatch`，会因少量缺失观测清空整图；前端又把后端 `scanTrend` 硬编码替换成空序列。CAT 配置保存的业务 `500` 已确定来自 `bi_cat_sync_state.next_scheduled_at` 更新 SQL，不是 CAT 地址、网络或审计日志；当前两次更新未处于同一事务，因而会留下“配置已落库、调度状态未更新”的半提交状态。
- 测试状态：当前迁移头为 `V20260810_01`，126 个迁移通过不可变性校验。后端 1138 项零失败、零错误、1 项环境条件跳过并成功构建生产 JAR；前端 115 个测试文件、421 项、ESLint、TypeScript 和生产构建全部通过。真实 PostgreSQL 删除链覆盖 ODS tombstone、来源级事实消费、`issue_fact` 旧标签清除、目标发布和投影更新；最终文本、差异、迁移和跟踪产物门禁通过，运行产物位置门禁仅被仓库根目录既有 8 个 `.tmp-*.log` 阻塞。本地数据规模不作为约 280 万总数据量的容量结论。
- 实施复验发现并修正了 `SyncFactPublicationStateService.isReady()` 的 PostgreSQL `exists` 查询多余右括号；该缺陷会让首个 READY 事实任务以 SQL 语法错误失败。现有直接 PostgreSQL 回归和物理删除全链测试均覆盖该入口，此错误未进入交付包。

## 恢复线索

- 当前阶段：实现和本地验证完成，等待按内网标准生成全新隔离部署包；提交和推送需先明确本次改动边界及目标远端。
- 恢复后首条命令：`git diff --check`
- 上一工作计划：`docs/plans/implement-embedded-bi-dashboard.md`。
- 对应发布源码：`main@4f145def` 的未提交工作树，发布包 `qaflex-full-20260806T111653Z-8269d2b61253.tar.gz`。

## 目标与边界

- 目标：为每个内网现象给出可落到物理表、字段、代码门禁或迁移约束的确定根因，并提出不跨事实族回退、不伪造数据的修复方案。
- 成功标准：CAT 已明确到实际失败列/写入链；设计、编码和系统测试每个异常图明确来源事实、当前数据状态与代码责任；方案包含迁移、实现、回归和内网验收入口。
- 禁止：把小规模本地数据外推为 280 万容量结论；用零值、显示名猜测、跨来源回退或无业务依据的完整性门禁掩盖缺失；写入 `docs/decisions/`；修改或停止既有内网/本地运行实例。

## 约束与背景

- BI 公式以 `docs/bi-dashboard/BI看板数据来源与计算口径核对表.md` 为权威；CAT 原始接口以 `集成测试平台接口使用手册.md` 为事实，映射和错误边界以 `data-contracts.md` 为权威。
- 内网兼容模式常开。截图 4 只能证明老平台 MySQL 配置和导入白名单存在，不能证明 BI 独占人工走查快照、GitLab 提交事实或静态扫描字段完整。
- CAT 真实入口已由用户确认为 `https://172.22.10.56:88`；手册仍未给出认证、证书和完整路径前缀。当前配置保存失败发生在平台本地 PostgreSQL 写入阶段，尚未进入 CAT 出站请求，因此更换基地址不能规避该 `500`。

## 证据与根因

- 已证实：编码提交两图页面明确报告缺少“稳定提交 ID 和提交时间”的提交明细，对应唯一来源 `merge_request_commit_fact`。
- 已证实：提交事实存在第二个独立缺陷。`GitlabFactSourceSqlProvider` 把 `ods_gitlab_projects.name`（GitLab 仓库名）写入 `merge_request_commit_fact.project_name`，`BiPlatformCodingSourceAdapter` 却把该列当产品版本名与 `CC2026R3` 匹配。定点数据中提交来源主要为 `CrownCAD=18890`、`crowncad-plant3d=5831`，而 `merge_request_fact.project_name` 中 `CC2026R3=2596`；按 `source_system + source_instance + project_id + merge_request_id` 连接后，`CC2026R3` 可得到 6828 条关系、6619 个项目内去重提交。仓库显示名不能承担产品版本语义。
- 已证实：本地 `ods_gitlab_merge_request_diff_commits` 已有 31789 行，权威来源 SQL可产出 31789 条关系、28962 个项目内去重提交，但 `merge_request_commit_fact=0`。2026-08-06 的事实子运行 `504` 在来源表尚不存在时失败；来源表后来具备数据后没有执行 MR 全量事实重建。它说明“来源能力首次就绪”必须有权威回填或明确全量重建，不能把已配置能力等同于已发布事实。
- 已证实：编码人工走查问题分布已有 2163 条，说明 BI 独占走查快照不是整体空表；静态扫描和提交事实必须独立调查。
- 已证实：兼容同步已直接把老平台 `spider_crowncad_data.sonar_qube_result/bug_count/annotation_rate` 写入 BI 独占表，不存在字段未接线。当前 Adapter 仍要求所有合法走查记录都有非空扫描状态和注释率；任一缺失便分别清空整张静态扫描图和注释率，且 `qualityTrendComplete = commentsComplete && densityTrendComplete` 会让已有缺陷密度趋势因注释率缺失被整体标记不完整。老平台明确区分“已进行代码扫描 / 未进行代码扫描 / 无需扫描”，扫描失败也表示执行过；`NULL` 不能擅自改成其中任一状态。
- 已证实：静态扫描还有独立的前端必空缺陷。`CodingStageContent.vue` 的 `scanData` 固定返回 `{ categories: [], series: [] }`，完全没有读取响应中的 `data.scanTrend`；即使后端已有合法扫描点，页面也一定画空。现有后端模型和老平台物理字段只有扫描状态与问题总数，但产品文档要求按日期展示“阻断/严重/一般/提示”四级堆叠，当前没有四级数量字段或已确认推导公式；修复前必须补齐真实四级来源，或由用户把目标图明确改为状态/总问题数，不能把总数伪拆成四级。
- 已证实：代码质量趋势当前会保留已计算的缺陷密度点，但 `qualityTrendComplete = commentsComplete && densityTrendComplete` 会因注释率轨缺失而把区块标记 `INCOMPLETE`，所以现场“一条趋势线 + 输入不完整”与代码一致。前端用 `Map<日期, 注释率>` 合并观测，同日多条会以后值覆盖；周粒度也没有注释率归桶公式。CD-38 只确认上游单条注释率直接使用，尚未确认同日/周聚合公式，因此不能擅自用平均值修饰该问题。
- 已证实：设计问题类别有 410 条且总体密度可计算，说明设计范围与基础问题事实存在；缺口只位于模块/单次评审依赖的模块、页数或独立工作量字段。
- 已证实：`BiReviewCalculator` 用全范围 `allMatch` 和 `moduleDenominatorsComplete` 决定整图是否输出。任一评审缺少日期、正页数或正的独立评审工作量，会清空全部单次散点；任一模块汇总分母非正，会清空全部模块。模块名缺失不是现场根因，因为 Adapter 会显式归入“未标注模块”。本地同逻辑定点样本 56 条设计评审中 29 条没有独立评审工作量，128 条独立评审问题项中 45 条工作量非正；该数据只证明缺失/零值是正常会出现的来源状态，不替代内网覆盖率。
- 已证实：系统测试原因图在全部数据同步完成后恢复正常，排除最终态分类字典或字段映射错误。成功 `FULL_SYNC` 的全表任务均结束后才创建全量事实子运行，且 `IssueFactPersistenceService.replaceAllFacts()` 在单事务中整体替换，内部 200 条写入批次不会被外部读到。因此 3583 条“未归类”不是完整全量事实的批次泄漏。
- 已证实：暂态“未归类”的代码根因是定向事实发布缺少父运行跨表完成屏障。`SyncFactRefreshRunExecutor.execute()` 先执行 `drainFactTasks()`，完成事实写入和发布后，才通过 `parentRunActive()` 判断镜像父运行是否仍在运行并把子运行置为 `PAUSED`。本地真实运行 `524/525` 已复现：镜像父运行 `524` 在 `16:19:41–16:20:00` 同步 `issues/label_links/notes/projects`，事实子运行 `525` 却于 `16:19:45` 发布第一批 Issue 事实；`label_links` 到 `16:19:59` 才完成，后续目标又在 `16:20:00–16:20:17` 二次覆盖。首次大范围同步时，先发布只有 Issue 根而缺少标签/备注关联的事实会令原因分类集中落入“未归类”，依赖表完成后再恢复。该控制流由提交 `2fd5c34e`（2026-08-03，增量删除探测与精准派生发布重构）引入，18181 的 2026-07-29 版本不包含它。
- 已证实：`2fd5c34e` 同时删除了监听器原有的 `!event.factRefreshEligible()` 终态资格判断，并把测试从“失败父运行跳过事实刷新”改为 `shouldContinueTargetedPublicationAfterFailedMirrorRun`。因此当前代码不仅在活动父运行期间提前发布，父运行最终 `FAILED` 后仍会继续唤醒同一个事实子运行；这不是日志展示误差，而是提交内被测试固定的错误发布策略。
- 已证实：提交事实首次空表是同一发布时序缺陷的第二个实际后果。镜像父运行 `503` 尚未创建 `ods_gitlab_merge_request_diff_commits` 时，事实子运行 `504` 已执行并因“缺少源表”连续失败；父运行随后成功并写入 `merge_request_diff_commits=31789`、`merge_request_diffs=10000`，但唯一子运行已是 `FAILED`，协调器明确不自动复活失败子运行，最终 `merge_request_commit_fact=0` 且残留 `MERGE_REQUEST PENDING=9998/QUEUED=2`。若发布屏障正确，`504` 只会在两张来源表就绪后首次构建，不会形成该历史残留。
- 已证实：仅把 `parentRunActive()` 移到 `drainFactTasks()` 前仍不充分。镜像运行和 `FACT_REFRESH` 分别使用 `:mirror` 与 `:fact` 两个 `exclusive_scope`；父运行终态后，下一轮高优先级镜像可与上一轮事实子运行并发，事实事务仍可能读取下一轮只完成部分表的实时 ODS。当前根版本 fencing 只能阻止旧版本覆盖新版本，不能把跨表 ODS 读成一个完整代际。
- 已证实：`PARTIAL_SUCCESS` 不能按整个运行一刀切。运行可能只因无事实消费者的表失败，而 Issue/MR 某一事实族的全部来源已经成功；正确资格必须按 `GitlabFactDependencyCatalog` 的事实族依赖、该运行对应表任务和权威范围状态分别判断。另一方面，启用 MR 提交增强时，`merge_request_diffs` 与 `merge_request_diff_commits` 实际在同一 MR 发布事务中被读取，当前通用依赖目录却没有表达这组条件依赖，运行 `503/504` 已证明该缺口会让 MR 发布资格判断失真。
- 已证实：旧目标不能靠下一轮成功运行自然接管。`assignPendingTargetBatches()`、`hasUnpublishedTargets()` 和唯一事实子运行都按 `mirror_run_id` 隔离，失败父运行的目标不会被后续父运行消费。更早版本目标也不能直接按当前 `fact_change_heads.latest_change_version` 发布，因为更高版本可能来自尚未完成依赖的运行，且事实构建读取的是实时 ODS而非历史快照。
- 已证实：失败恢复必须先于事实发布。`RECONCILE_ONLY` 关系表由父行变化生成 `sync_run_authoritative_scopes`；范围达到重试上限后永久留在原运行的 `FAILED`，下一轮增量若父行 `updated_at` 未再变化就不会重新生成该范围。此时只恢复旧事实目标会基于未修复的 ODS 关系发布错误结果。目标版本必须让后续运行接管或重放旧失败范围，成功后才解除相应事实族阻塞。
- 已证实：顶部 P1/P2 卡片和模块 P1/P2 修复率共同读取 `issue_fact.priority_level`。`BiSystemTestCalculator` 以 `issues.stream().allMatch(priorityIndex > 0)` 建立全局 `prioritiesComplete`，当前范围只要一条有效缺陷缺少 P1/P2/P3，便把两张卡片的数量、修复数和修复率全部置空，并把整个模块修复率区块标记为 `INCOMPLETE`；前端将空值显示为 `--`。这与现场现象精确一致。
- 已证实：上述全局门禁不符合 ST-21～ST-31、ST-49B～ST-49C 的已确认公式，也不符合老平台实现。公式只在有效缺陷中分别筛选 `priority=P1/P2` 作为各自分母；老平台 `SpiderIssueDataDAOImpl.getP1Count/getP2Count` 同样直接按 `urgency=P1/P2` 计数，不因其它议题未设置紧急程度而整体拒绝计算。当前测试 `marksPriorityTargetsIncompleteWhenAnyPriorityIsMissing` 反而固化了错误行为。
- 已证实：该缺陷由 2026-08-04 新增、随后打入 2026-08-06 全新包的 BI 系统测试计算器引入，不属于 `2fd5c34e` 的 GitLab 同步修复。发布源码是 `main@4f145def` 的未提交工作树，`8269d2b61253` 是随机发布 ID 后缀而非 Git commit，因此不存在可引用的独立引入提交。
- 已证实：在发布包同版本隔离栈登录管理员后调用 `PUT /api/bi-cat-mirror/config`，请求稳定返回 `500`。完整堆栈落在 `BiCatMirrorRepository.saveConfig:71`：`next_scheduled_at = case when ? then ? else null end` 被 PostgreSQL 推断为 `text`，写入 `bi_cat_sync_state.next_scheduled_at timestamptz` 时失败，数据库明确报告 `expression is of type text`。地址 `.56` 已在第一条更新中成功写入 `bi_cat_mirror_configs`，第二条更新失败，证明 CAT 地址不参与本次失败因果链。
- 已证实：`saveConfig` 的两张表更新没有事务包裹，接口失败后 `bi_cat_mirror_configs` 已保存新配置，而 `bi_cat_sync_state.next_scheduled_at` 仍为旧值；这不是单纯的错误提示问题，而是配置与调度状态原子性缺陷。
- 已证实：`operation_audit_logs.role varchar(32)` 无法容纳部分 LDAP 多角色拼接值，属于独立审计缺陷；`OperationAuditService.record()` 已捕获该异常，它不会造成上述业务 `500`，但会丢失对应操作审计，需单独迁移为可容纳角色集合的类型。
- 已证实：CAT 登录页截图证明真实浏览器入口为 `https://172.22.10.56:88` 且证书不受信任，但不能证明四个后端接口无需认证或使用手册中的无前缀路径。当前开发机对 88 端口 TCP 可达，直连 TLS/HTTP 在 3 秒内未完成；该现象属于保存修复后的联调风险，不参与当前本地 PostgreSQL `500` 的因果判断。Java 客户端使用默认信任库、无认证头且不跟随重定向，必须按 CAT 正式契约和部署 truststore 联调，禁止代码内 trust-all。
- 已证实：CAT 手册自身不能消除路径疑点。接口概述给出 `/integrationSearch/getAllProject`、`/testingPhase/getAllByProjectId`、`/integrationSearch/getStatisticsInfoByTPId`，请求示例和调用流程却写成无控制器前缀的 `/getAllProject`、`/getAllByProjectId`、`/getStatisticsInfoByTPId`；手册也没有登录、Token、Cookie、反向代理前缀或证书链说明。当前客户端采用概述路径。保存 500 修复后，必须由 CAT 浏览器 Network 或 CAT 维护方给出真实四接口请求，才能对第二阶段连接失败作确定配置，不能从登录页猜测。

## 方案与步骤

- 实施状态：步骤 1～3 调查完成，步骤 4～10 生产实现完成，步骤 11 的本地自动化与真实 PostgreSQL 控制流验证完成；剩余仅为新包生成及内网真实 CAT、BI 页面和约 280 万总表规模验收。

1. [已完成调查] 读取设计、编码、系统测试 Adapter/Calculator 与对应测试，列出每个区块的精确完整性条件和页面提示来源。
2. [已完成调查] 对本地已有真实样本做字段分布剖析，只验证映射和分类逻辑，不做容量外推；同时核对老平台基准代码实际字段语义，并检查首次全量期间系统测试事实的发布围栏。
3. [已完成调查] 从发布镜像、迁移和可复现请求定位 CAT 失败 SQL及列，排除无因果关系的旁路日志。
4. 把事实发布从“每个 ODS 页立即创建并运行父专属子任务”收敛为来源级持久 outbox 消费：ODS 页事务只登记版本化目标；镜像运行终态提交在同一来源锁边界内，更新 `source + fact type + dependency` 就绪状态并由其派生事实族 `READY/BLOCKED`，再创建或唤醒来源级事实消费者。活动父运行必须零事实副作用；`SUCCESS` 发布完整事实族；`PARTIAL_SUCCESS/FAILED` 只允许其表任务和权威范围依赖全部成功、且没有更早未修复依赖的事实族，相关依赖失败则保持阻塞。成功全量运行另行登记可合并的 `FULL` 发布意图，不能依赖定向目标补出全量发布。
5. 为实时 ODS 增加唯一来源级发布门：镜像页/权威范围提交与事实发布事务使用同一数据库互斥边界，事实消费还必须确认没有更新的活动镜像和没有更新的阻塞依赖代际。这样事实事务要么读取上一完整代际，要么等待当前镜像完成；根版本 fencing继续负责幂等和乱序，不再承担跨表一致性。禁止仅前移 `parentRunActive()`、仅调整调度优先级或让 BI 页面延迟读取。
6. 将事实族有效依赖统一到一个目录，纳入启用 MR 提交增强时的 `merge_request_diffs + merge_request_diff_commits` 条件依赖；删除重复能力判断。后续同步重新覆盖失败快速表窗口，并接管/重放旧失败权威范围；只有每个曾被阻塞的具体依赖都成功修复后，事实族才能恢复 `READY`。随后把同来源同事实族的全部历史待发布目标合并为有界批次，只有最新 ODS 依赖代际 `READY` 时才允许推进 `fact_change_heads.published_version`。失败事实任务由持久消费者按原 outbox 自动恢复，不要求人工复活某个父专属子运行。
7. 将 CAT 配置和调度状态更新放入同一事务；由 Java 计算可空 `nextScheduledAt`，SQL 直接给 `timestamptz` 列赋值，删除有类型歧义的参数化 `CASE`。增加真实 PostgreSQL 集成测试，覆盖启用自动同步写入当前调度时间、禁用自动同步写入 `NULL`、第二条写入失败时第一条回滚。另建迁移把审计角色集合列改为无错误上限的文本类型并覆盖多角色审计。
8. 删除系统测试 P1/P2 的全局完整性门禁，按各自谓词独立计算；分母为 0 时返回 `NOT_APPLICABLE`，有 P1/P2 时返回真实数量和修复率。保留非法稳定 ID、来源版本冲突和轮次守恒等有业务依据的完整性检查，并增加“同范围含未标注优先级和合法 P1/P2”回归测试。
9. 把评审模块/散点、扫描和注释率改为“合法观测独立计算 + 区块覆盖状态”：只排除缺少该指标必要输入的观测，保留其它可计算点；区块在未覆盖全部范围时继续为 `INCOMPLETE` 并返回总观测数、有效观测数和覆盖率。不得补零、跨表回退或把空扫描状态解释成“未执行”。组合质量趋势分别保留已有序列，不因另一条序列缺失清空。静态扫描前端必须读取正式响应，不再生成硬编码空数组；四级堆叠在真实字段契约确认前不得伪造。
10. 新增后续 Flyway 迁移，删除 `merge_request_commit_fact.project_name` 这一错误冗余语义及其名称索引；Java 实体、来源 SQL和持久化同时删除该字段。BI Repository 通过完整稳定键连接 `merge_request_fact`，只使用 MR 事实的业务 `project_name` 做产品版本筛选。部署后强制一次 MR 全量事实重建，并让“提交来源能力首次完整就绪”触发一次权威回填，避免首次失败后长期保持空事实。
11. 增加真实 PostgreSQL 和领域回归：仓库名 `CrownCAD`、业务版本 `CC2026R3` 的提交必须进入该版本；合法/缺失观测混合时保留合法图点并报告覆盖；活动镜像及阻塞依赖代际下事实零变化，失败权威范围由后续运行自动恢复，恢复后历史目标只发布一次且读取完整 ODS，完整全量事实只能原子切换。随后在内网执行下列只读核验和真实页面验收。

## 决策记录

- 已选：按图表事实族分别调查，避免把编码页局部成功/失败误归为单一“老平台同步问题”。
- 已否决：因 `spider_crowncad_data` 已在白名单就认定 BI 走查快照已成功；主同步和 BI 独占发布是两条状态链。
- 已否决：将历史 `MERGED`/`COMPLETED` 缺陷作为当前根因；当前兼容模式已按合法走查人员判定，不再用合并状态代替走查完成状态。
- 已否决：把缺少优先级的议题强行映射为 P1/P2、从严重级别回退优先级或修改事实表补值；严重程度与优先级是独立语义，正确修复是让 P1/P2 指标按自己的合法成员独立计算。
- 已选：CAT 配置保存以“单事务更新配置与调度状态 + 直接绑定可空时间戳”为唯一权威实现，不给现有错误 SQL叠加兼容分支；审计角色列宽按独立迁移修复，不与业务 `500` 混为一因。
- 已选：提交事实是稳定关系表，产品版本只由 `merge_request_fact` 提供；删除错误冗余字段，不保留双轨或仓库名回退。
- 已选：来源记录不完整时保留合法观测并显式报告覆盖，不再用全范围 `allMatch` 清空整图；唯一例外是稳定身份冲突、来源版本变化或守恒关系破坏等会使结果本身不可信的条件。
- 已否决：把系统测试 3583 条暂态“未归类”归因于全量事实的 200 条批次。代码和 PostgreSQL 事务边界已排除该解释；真实缺陷是定向事实任务在父运行跨表完成前已对外发布。
- 已选：ODS 页事务只负责登记持久目标，不再创建可立即执行的父专属事实子运行；事实消费者由来源级依赖就绪状态驱动。原因是父专属子运行无法自动接管旧失败目标，且与下一轮镜像使用不同互斥范围时仍可读取跨表半成品。目标版本建立一个权威“活动镜像或阻塞依赖代际下零事实副作用”的入口契约。
- 已选：事实族发布资格以该来源当前依赖代际为权威，而不是只看父运行总状态。部分成功中不相关表失败不阻塞合法事实族；任一相关表任务或权威范围失败则阻塞整个事实族，后续运行必须先恢复 ODS 依赖再合并发布历史目标。
- 已否决：只恢复 `!event.factRefreshEligible()`。它能阻止 `FAILED`，但仍允许未经依赖核验的 `PARTIAL_SUCCESS`，也不能解决旧 outbox、失败权威范围或下一轮镜像并发。
- 已否决：让失败父运行的旧目标直接由后续事实任务读取当前版本头。当前事实没有 ODS 历史快照，更高版本若来自阻塞代际，直接读取会把未完成关系再次发布。
- 已否决：通过 BI 页面等待同步完成、在分类器中隐藏“未归类”或前端延迟刷新来掩盖暂态。错误事实已经对平台其它统计、下钻和导出可见，必须在事实发布边界修复。

## 接口契约

- 当前调查不直接新增 API、函数签名或表结构；后续实现会调整 BI 区块覆盖元数据，并通过新 Flyway 迁移删除提交事实的错误冗余列、放宽审计角色集合列。
- 后续事实发布内部契约：依赖就绪身份为 `config_id + source_instance + fact_type + source_table`；来源级消费者只领取当前事实族为 `READY` 的历史未发布目标或待处理 `FULL` 意图，不再把单一 `mirror_run_id` 当作消费边界。任何事实事务推进版本头前必须持有来源发布门并重新确认没有活动镜像或更新的阻塞依赖。
- 既有 CAT 配置入口：`PUT /api/bi-cat-mirror/config`；测试连接入口：`POST /api/bi-cat-mirror/test-connection`。
- 既有 BI 页面入口：`/api/bi/design`、`/api/bi/coding`、`/api/bi/system-test`。

## 内网只读核验

以下 SQL只读取元数据和事实覆盖，不扫描 280 万总量做容量替代测试。先将产品版本和时间范围替换为现场值。

```sql
-- 设计模块/散点的正分母覆盖。
with design_input as (
  select r.id, r.module_name, r.review_scale_pages,
         sum(p.workload_hours) filter (where p.review_category = '独立评审') as workload_hours
    from review_visible_records r
    left join review_visible_problem_items p
      on p.review_record_id = r.id and coalesce(p.deleted, false) = false
   where coalesce(r.deleted, false) = false
     and r.review_type = '设计说明书评审'
     and r.project_name = 'CC2026R3'
   group by r.id, r.module_name, r.review_scale_pages
)
select count(*) as total_records,
       count(*) filter (where review_scale_pages > 0 and workload_hours > 0) as valid_records,
       count(*) filter (where workload_hours is null) as workload_missing,
       count(*) filter (where coalesce(workload_hours, 0) <= 0) as workload_nonpositive
  from design_input;

-- 兼容走查的扫描和注释率覆盖及真实状态分布。
select count(*) as total_records,
       count(*) filter (where nullif(btrim(scan_status), '') is not null) as scan_valid,
       count(*) filter (where comment_rate >= 0) as comment_rate_valid
  from bi_code_review_compatibility_records
 where project_name = 'CC2026R3'
   and upper(coalesce(merge_request_state, '')) = 'MERGED'
   and target_branch = 'dev';
select coalesce(scan_status, '<NULL>') as scan_status, count(*)
  from bi_code_review_compatibility_records
 where project_name = 'CC2026R3'
 group by scan_status order by count(*) desc;

-- 提交事实是否发布，以及错误仓库名与正确 MR 业务版本的对照。
select count(*) from merge_request_commit_fact;
select c.project_name as repository_name, count(*)
  from merge_request_commit_fact c group by c.project_name order by count(*) desc;
select mr.project_name as business_version, count(*)
  from merge_request_commit_fact c
  join merge_request_fact mr
    on mr.source_system = c.source_system
   and mr.source_instance = c.source_instance
   and mr.project_id = c.project_id
   and mr.merge_request_id = c.merge_request_id
 group by mr.project_name order by count(*) desc;

-- 首次“未归类”时段的镜像父运行、事实子运行和 Issue 事实任务时间线。
select id, parent_run_id, run_type, trigger_type, status, request_reason,
       planned_table_count, completed_table_count, started_at, finished_at, error_message
  from sync_runs
 where created_at between :window_start and :window_end
 order by id;
select child.id as fact_run_id, child.parent_run_id, parent.run_type as parent_type,
       parent.status as parent_status, child.status as fact_status,
       task.full_build, task.status as task_status, task.affected_rows,
       task.started_at, task.finished_at, task.error_message
  from sync_runs child
  join sync_runs parent on parent.id = child.parent_run_id
  left join fact_build_tasks task
    on task.run_id = child.id::text and task.fact_type = 'ISSUE'
 where child.run_type = 'FACT_REFRESH'
   and child.created_at between :window_start and :window_end
 order by child.id, task.id;
```

## 风险与假设

- 内网数据库不可由当前环境直接查询；3583 条对应的具体内网运行 ID 仍需上述只读 SQL确认，但“父运行完成前已发布依赖不完整事实”的代码根因已由当前代码顺序、Git 历史和本地真实父子运行时序共同证实。该证据只证明控制流，不替代内网数据量或字段覆盖率。
- 本地运行发布栈使用隔离空库且无法访问内网 MySQL/CAT，适合验证配置保存的真实 PostgreSQL 路径和迁移结构，不适合证明内网数据覆盖率或 CAT 出站连通性。保存修复后仍须在内网分别验证 JVM 证书信任、接口完整前缀和认证要求；浏览器登录页可打开不等于后端四个查询接口可匿名访问。
- 工作树混有 BI、同步、导出和用户改动；调查与后续修复必须保护现有改动，不得整体回滚、暂存或提交。
