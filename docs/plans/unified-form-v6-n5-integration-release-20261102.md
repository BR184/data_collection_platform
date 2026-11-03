# 表单 v6 N5：集成与发布准备

> 状态（2026-11-03，N5 输入 `4ae4f5ac` 复验：R03 关闭，R04 事务竞态已修复且最终默认套件通过，待复验）：N4 整体验收通过（回执 `d09dbf04`）；范围回执 `eb906e43`、复审回执 `aa85f101`、复验回执 `deba851a` 与事务回执 `8db94c97` 已吸收。R01/R02/R05 保持关闭；R03 关闭；R04 仅给 `requestRound` 加 `REQUIRES_NEW` 独立短事务，正式回归修前红/修后绿。N4 批准、受控集成与已批准 UI 保留。未批准功能分支→main、黄金、正式出包、源码留档或生产启用。分支 `表单多版本与填写功能开发-20261026`。N4 活动计划已收口，原文可按 `git show 76dc4dd3:docs/plans/unified-form-v6-n4-implementation-20261030.md` 恢复。

## 进度与中间物

- 阶段：N4 通过并归档；N5 受控合并、S1 与历史版本导出入口已实施并完成整链验证，待文档护栏与双远端送审。
- 已完成：N4 长期事实归入 `docs/product.md`、`docs/architecture.md` 与 `docs/decisions.md` D-35；N4 活动计划删除；N5 基线差异、证据矩阵、发布资料已入档；范围审批已吸收。
- 已完成：`main→功能分支` 手工合并提交 `8c58938d`，主线专属修复依次回放至 `f22cd63d`；`ab88ab38` 完整业务身份详情读取已在工作树核实存在。
- 已完成 S1：迁移 `V20261102_06__form_project_candidates.sql`；`FormProjectCandidateService` 从 ODS 项目/命名空间幂等重建投影，`FormProjectCandidateRefreshListener` 挂在镜像成功终态；`GET /api/collect-forms/admin/projects` 支持分页前名称/路径/拼音/首字母搜索、`projectIds` 精确回显与缺失 ID；隔离真实 PG 5 项通过（含同步事件→投影→候选、跨页拼音/路径、同名不同命名空间、来源投影独立、幂等）。
- 已完成前端：范围抽屉以 SmartSelect 替换项目编号输入（适用项目多选、版本所属项目从已选集合单选、唯一来源/单项目自动带出、迟到响应丢弃、失败/未同步/失效保留选择与重试），`version-members` 显示改为“版本分支”；历史版本行新增受 `form.record.export` 控制的“导出Excel”，按该版本冻结项目默认全选并复用现有下载契约。
- 已完成集成修复：延期语义按“主线 D-34 原始标签/修复模板豁免 + 表单规范状态成员闭环”合并，单独回复不再冒充修复模板；`V20261102_07` 重建 `code_review_formal_records` 视图以暴露发布读取新增列；首次响应模板 SQL 的首行判定与 `IssueResponseTemplateTitleRules` 同源（含制表符等前导空白）。
- 输入 SHA：功能分支批准输入 `39291fa29eb340234a680aca86a34cb1e89306b1`；main `f74fdef75ea2e1f093c131ceb8c6eee490d397ef`（origin/gitlab25 一致）；共同祖先 `84e7c023449de190e722d79d027accb062358172`。
- 已完成整链验证（工作树基于 `e476e6e7`，含本次集成改动）：后端默认套件 2307 项、0 失败、0 错误、1 条件跳过，日志 `.tmp-logs/n5-final-20261103/final-suite.log`（SHA-256 `4a045fa373544c5eb2c3936b7ba95f8ead96c61a254b8d6f71672d43d6b85bbf`，XML 392 份）；173 条迁移锁通过；前端 typecheck/lint 通过，Vitest 174 文件 949 项通过，`vite build` 成功；仓库护栏与 `git diff --check` 通过。
- 首次默认套件（`.tmp-logs/n5-final-suite.log`，11:04 结束）出现 `IssueFactSourceInstancePipelineTest` 6 个 `setUp` 错误，报 `customer_issue_delay_closure_requests` 不存在；同一时段共享 15433 的 Flyway 历史被外部重建（installed_on 11:00:36–11:00:42），隔离单类复跑 6/6 通过，随后全量复跑 r3 全绿。两次日志均保留，失败不归因于本分支代码。
- 桌面隔离验收（PG 15521、后端 18097、前端 18197，不改写 15432）：S1 项目名称跨页/拼音/路径搜索与单项目自动带出可用；发布 `PUBLISHED` 后导出按钮按版本项目导出并落审计；存量记录 990001 从填写页打开、带出原值、编辑问题原因保存成功并显示“更新 11/03 04:00”。
- 桌面核对发现并修复 S1 缺陷：版本所属项目下拉传入 `FormAdminProjectOption`（无 `value`/`label`），显示裸项目号且选项为空；`FormTemplateAdminView.vue` 统一经 `toProjectSelectOption` 投影，并新增选项契约断言，管理页测试 29/29 通过。
- R01 已整改：`FormProjectCandidateService` 按 `source_instance` 分流；`default` 只取本 config 的 ODS 镜像归属（`ods_gitlab_projects.mirror_task_id → sync_run_table_tasks.config_id/mirror_table`），`dgm` 只取 `code_review_dgm_project_options`，其它来源显式不可用；命名空间沿 `parent_id` 递归到根，缺父/循环/深度超限/跨来源均不伪造路径。`FormProjectCandidateIntegrationTest` 10/10 覆盖两来源同时刷新、DGM 同 ID 不同平台、`team/sub/project` 根路径、空目录、失败保留。
- R02 已整改：新增 `V20261102_08__form_project_candidate_state.sql` 与 `FormProjectCandidateStartupInitializer`，服务模式启动对已确认来源做一次幂等初始化，迁移模式不扫描业务包；状态区分 `UNSYNCED/READY/EMPTY/FAILED/UNAVAILABLE`，失败保留最后成功快照并在接口返回 message；DGM 同步事件成功/失败分别刷新与标记失败。专属库实测启动初始化 63 条候选 `READY`，`2001` 路径为 `demo/sub/payment-gateway`。
- R03 已整改：管理 detail 的 `VersionView` 新增服务端 `exportable`、`recordSourceRole`、来源与版本项目元数据；导出服务优先读取 `form_template_publications.frozen_binding.projectIds`，范围重绑后仍按版本冻结项目导出。前端移除新增导出弹窗与实心主按钮，改为历史版本行内次要操作与内联项目/下载面板，复用范围/版本/来源/代次守卫且不写全局候选缓存。
- R03 溢出已修（2026-11-03 复验）：`.version-export-panel` 由 `flex: 1 0 100%` 改为 `flex: 1 1 calc(100% - 22px)` 并设 `min-width: 0`，长项目名 `overflow-wrap: anywhere`；未改抽屉、按钮层级或整体布局。无头 Chrome 同场景测量：抽屉内容 420/420、版本行 380/380、面板 358/358，横向溢出 0；把旧声明临时回填后版本行 402/380、抽屉 422/420，确认 22px 外宽来源。截图与测量 JSON 在 `.tmp-logs/n5-rereview-445f0a53-20261103/`。
- R05 已核明（见下表）：`f22cd63d` 的 7 份快照均可追溯到已批准 main `eb9b2f8b` 或分支既有发布读取基线；未发现本轮新改或无法证明授权的快照变化，因此不回滚快照，只在文档更正该提交说明。
- R04 首轮失败证据：`FormIssueAdoptionFactPipelineIntegrationTest` 在 31.16 秒后 stage 仍 `REQUESTED`；类运行区间约 13:32:53–13:34:08，失败窗口约 13:33:37–13:34:08。窗口内唯一日志是 `HikariPool-6` 于 13:33:48 的后台拒绝；`60326/59425/57234` 分别对应 `HikariPool-10/7/6`，三池首次活动为 13:27:17/13:30:59/13:31:58，均早于本类上下文启动，属已结束上下文的残留连接池。本类自身连接池在失败窗口内无拒绝记录；全日志唯一一条 `Customer issue delay closure advance failed` 在 13:23:14，来自单元测试替身异常，与本类无关。
- R04 确定性定位与修复：审核方在真实 `AFTER_COMMIT` 回调中用同步屏障复现——`requestRound` 在尚未释放的事务连接上写入，异步 `claimBySource` 于资源清理前领取为空，随后留下 `REQUESTED/1/0`；仅给 `requestRound` 加 `REQUIRES_NEW` 后同一时序领取成功。修复按执行单只改该方法事务边界，`requestSourceRound` 仍在其返回后调用 `submitAdvance`；未改重算事务、写回链、`onConfigSaved` 或 UI，未加迁移。**原 13:33 现场不追认同因。**
- R04 正式回归：`afterCommitRequestIsVisibleToWorkerBeforeWakeUp` 用真实 Spring 事务 + PG + 屏障验证提交回调登记后另一连接可领取；临时撤销 `REQUIRES_NEW` 时领取为空（红），恢复后通过（绿）。`formDriven...` 保持 IDLE、completed=request、failure=0；新增 `rolledBackPublicationDoesNotRegisterDelayRound` 覆盖发布回滚不登记不唤醒。
- R04 超时诊断保留：`awaitDelayClosureSettled` 与 `@AfterEach awaitInFlightDelayRound` 超时抛出 progress 全字段（含 `next_check_at`/租约/`updated_at`）、执行器 active/queue/poolSize 与 `platform-async-*` 线程状态及栈顶；未延长 30 秒、未加重试/跳过/掩码。
- R04 最终默认套件（事务修复后，专属 15523 / schema `qaflex_n5_tx_final`）：**2316 项、0 失败、0 错误、1 条件跳过，`BUILD SUCCESS`，25:13**，392 份 XML；日志 `.tmp-logs/n5-r04-tx-final-20261103/final-suite.log`（SHA-256 `8e08852064534ed29606d06c0f4ed96078d12e2cfd26ec99090885aa9ea9a888`）。输入 `HEAD=4ae4f5ac0d5bfdf736439d8c107ff59b3bc85633`、backend/frontend diff SHA-256 `7cac8ba140180c671dc41e874eb58eb10aeb0a5c769294c6bfecd05c080334cc`；无新增未跟踪代码文件。旧 2314 结果不再作为新版本门禁；前端未变，沿用已批准证据。
- 进行点：R03 关闭；R04 修复、正式回归与最终默认套件完成，等待复验；黄金、main 合入、出包与生产启用仍未准入。
- 恢复命令：`git status --short --branch`、`git log -1`、`git worktree list`；本计划文件即 N5 恢复入口。

## 原始需求、成功标准与禁止行为

- 用户已确认：记录留原版、新建用当前版、旧记录按原版维护、相同业务字段跨版统计、按版本 Excel 导出；Issue 按启用点 T 分流 FORM/COMMENT，MR 在 T_mr 前继续评论。
- N5 成功标准：集成范围与冲突有据可查；主方案 §4.1 验收矩阵逐项映射到证据或明示缺口；未实施承诺与发布资料齐全；后续集成候选可在不破坏 main 已批准修复的前提下验证。
- 禁止：本阶段把功能分支合入 main、强制推送、正式出包、源码留档分支、生产启用、真实权限授予、真实 GitLab 写回；运行黄金、改快照/夹具/掩码；改已部署迁移；重构批准 UI；自行设计新模板模型、版本绑定解耦、退役恢复。允许且仅允许 `main→功能分支` 的普通合并与手工冲突裁决。

## 基线核对（只读，2026-11-02）

### 分叉历史

`merge-tree` 模拟显示自动合并会产生 45 处冲突：`read` 服务 39 个文件只保留 1 个、18 条 `V20261012_01`—`V20261015_03` 发布读取迁移被 main 侧删除；延期写回编排/进度/轮次/写回队列出现 add/add 或内容冲突；`FactBuildService`、`FactTargetPublicationService`、`IssueSlaRules`、`IssueFactSourceRowMapper` 等同时承载双方语义。结论：**不接受自动合并结果**；按回执在原功能分支普通合并 `f74fdef7`，逐项手工裁决并恢复发布读取基线，合并提交可回退，main 保持不动。

原因：main 的 `59cd5587` 以延期稳定候选为第一父提交收敛主线，未采用 `84e7c023` 发布读取分支的树；双方共同祖先仍是 `84e7c023`，因此 main 对未见文件的“删除”会被 Git 当作已决定，直接合并会丢失发布读取基础设施。证据：`git merge-tree --write-tree gitlab25/main HEAD`（结果树 `1d357a0a…`，exit 1，45 处 CONFLICT）原始输出 `.tmp/merge-tree-main-head.txt`。

### 迁移与升级入口

| 项 | main | 功能分支 | 判断 |
| --- | --- | --- | --- |
| 迁移总数 | 140 | 174 | 分支 = main 140 + 34（S1 新增 06/07、复审状态表 08），无 main 独有版本 |
| 共享 140 条 checksum | — | 与 main 完全一致 | 无既有迁移改动 |
| 分支新增 34 条 | — | 18 条发布读取 + 13 条表单 v6 + S1 投影 `20261102_06` + 集成视图重建 `20261102_07` + 候选状态表 `20261102_08` | 全部前向；含 `V20261022_01/02/04`、`20261027_01`、`20261029_01/02`、`20261030_01/02`、`20261102_01`—`08` |
| 旧主线升级测试 | 140 条基线 | `FormV6LegacyUpgradeIntegrationTest` 按 140+34、head `20261102.08` | 已与当前 main 迁移集对齐，最终默认套件通过 |

集成时必须把 18 条发布读取迁移及对应读服务视为**待恢复的基线**，不能因 Git 自动合并结果为零而接受。

### 需吸收的 main 独立修复（按行为单元）

| 单元 | 代表提交 | 冲突/影响面 |
| --- | --- | --- |
| 延期写回与轮次 | `9a8dee32`、`89902fa0`、`9daf6c77`、`3222bf57`、`a08109c9`、`89ab940f`、`de5755d4` | 编排器、进度仓储、轮次服务、写回队列/worker、`FactBuildService`、`IssueSlaRules` |
| 客户问题状态/时间轴 | `6aa9bd69`、`a1756363` | 事实构建、来源行映射、效率规则、业务日/完成加标时间 |
| 统计/质量口径 | `b564ba03`、`f0817018`、`cab4bd8d`、`79642b64`、`e8294d36`、`1e08ec6a`、`463f914a`、`78c2dc57` | 客户问题统计板、系统测试下钻、质量看板/导出、BI 客户问题、分析明细 |
| 事实/同步基础 | `a3291069`、`eb9b2f8b`、`3d2a921e`、`ab88ab38` | 迁移进程隔离与待定意图、timestamptz、可选目录缺失、记录身份重读 |
| 打包/部署/文档 | `5e923483`、`7488b21c`、`dd8f4d80`、`b76abe28`、`cff5d7ed` 等 | 更新包、回滚健康检查、发布 runbook、AGENTS 与 decisions |

吸收原则：main 已批准的修复不因表单分支旧代码回退；表单行为以已批准的 FORM/COMMENT 分区、逐记录投影和按版导出为准；同一文件同时含两类改动时必须合并语义，不能整文件择一。

### 文档与契约冲突

- `AGENTS.md` 与 main 有 187 行差异且合并冲突；需在集成工作单元中合并双方有效规则，不直接取任一侧。
- main 新增 D-29、D-31—D-34；分支计划曾引用 D-32 表示表单 v6，但 main 的 D-32 已用于发布包事实重建范围。本轮已新增 **D-35** 承载表单 v6，并修正旧计划链接；集成时保留 main 的 D-29/31—34。
- main 仍保留旧 `CollectFormService/Controller` 及旧 `collect-forms` 黄金快照，分支已由 v6 记录服务替代并删除旧写入口；集成必须保留 v6 删除结果，旧调用/测试/快照随替换同步清理，不恢复双轨。
- 发布读取相关 API/页面（read-views 会话端点、详情读帧）在 main 不存在，属于本次集成的有意新增产出。

## 验收证据矩阵（主方案 §4.1）

| 主方案验收项 | 复用证据（最终套件计数） | 状态 |
| --- | --- | --- |
| 结构发布不搬记录 | `FormV6DirectoryMigrationTest` 3、`FormStorageFoundationUpgradeTest` 1、`FormV6LegacyUpgradeIntegrationTest` 1；N3 已批准 | 已有证据 |
| 唯一记录源 | `FormV6RegistrationIntegrationTest` 21、`FormV6DirectoryMigrationTest` 3、`FormV6LegacyUpgradeIntegrationTest` 1 | 已有证据 |
| 原版读写与旧链接 | `FormRecordServiceIntegrationTest` 38；N2 桌面交互证据（批准输入 `94749f3a`） | 已有证据 |
| 幂等跨发布 | `FormRecordServiceIntegrationTest` 38、`FormV6RegistrationIntegrationTest` 21 | 已有证据 |
| 多人及多标签 | N2 双用户/多标签桌面交互证据；前端交互测试未变 | 沿用批准证据 |
| 管理异步边界 | `FormTemplateAdminIntegrationTest` 23、管理页前端测试；批准 UI 基线 | 已有证据 |
| 字段与条件 | `FormTemplateAdminIntegrationTest` 23、`FormVersionStructureVerifierIntegrationTest` 6 | 已有证据 |
| 共同字段统计 | `FormCommonFieldServiceTest` 12、`FormReviewProjectionIntegrationTest` 3、`CodeReviewMultiBoardFormRecordsIntegrationTest` 3 | 已有证据 |
| Issue 时间选择 | `FormIssueInputResolverAndSlaTest` 15、`FormIssueAdoptionFactPipelineIntegrationTest` 25 | 已有证据 |
| 消费并发 | `ReadPublicationModelIntegrationTest` 49、`FormHeadCaptureIntegrationTest` 9、`CustomerIssueRequirementLabelAutoChainIntegrationTest` 9、`FormIssueAdoptionFactPipelineIntegrationTest` 25 | 已有证据 |
| Excel | `FormRecordExportIntegrationTest` 6、`FormRecordExportSpringContextIntegrationTest` 1、导出布局测试 | 端点待黄金审批 |
| 初始化与恢复 | `FormV6LegacyUpgradeIntegrationTest` 1、`FormV6DirectoryMigrationTest` 3、`FormV6RenumberedHistoryUpgradeTest` 1 | 已有证据 |
| 门禁 | 174 条迁移锁、端点/权限/工作区登记、最终隔离默认套件 2314、前端 953 项与构建、隔离桌面证据 | 黄金未运行，按发布门禁另行审批 |

矩阵说明：以上历史计数来自冻结 XML（`.tmp-logs/p6-r02-final-20261102/xml/`）；S1 与集成变更后在最终代码重跑一次默认套件，原始日志/XML 冻结于 `.tmp-logs/n5-final-20261103/`。前端变更后已重跑 typecheck/lint/全量 Vitest/build，并完成隔离桌面链路，不重复模板或浏览器全矩阵。

## S1 与导出实施范围（已批准）

回执 `eb906e43` 已批准以下范围，不再等待另一次同范围审批；仍须保留管理/填写页现有布局、字段卡片、常驻摘要、抽屉层级、主次操作与样式体系。

| 项 | 当前事实 | 已批准实施范围 |
| --- | --- | --- |
| S1 项目目录候选 | 无 `form_project_candidates`、无 `GET /api/collect-forms/admin/projects`；管理页仍以“项目编号”文本和数字输入版本所属项目 | 后端新增只读投影与分页搜索端点（ODS/DGM 适配、完整路径、拼音/首字母、来源隔离、字符串 projectId、`form.template.manage`）；前端在批准布局内用 SmartSelect 替换编号输入，适用项目多选、版本所属项目从集合显式选择，保留失效/未同步/失败状态 |
| 业务命名与自动带出 | 代码平台/适用项目/版本所属项目等业务名已有产品规则，但来源地址、项目、里程碑→版本名称、范围名称建议未接入管理页 | 仅在绑定抽屉接入候选与自动带出；不改字段卡片、常驻摘要、抽屉层级和视觉基线 |
| 按版导出使用入口 | 后端 `GET /api/collect-forms/exports/records` 与 `form.record.export` 已存在（只登记未授予）；前端无导出入口 | 在已有“历史版本”抽屉的版本行增加次要“导出Excel”，仅 `form.record.export` 有效时显示；按该版本项目多选（默认全部获准项目），直接复用现有导出契约与下载组件；TARGET/未发布版本不可导出，403/413/失败沿用错误样式 |
| 独立新模板模型、版本绑定解耦、退役恢复 | 未获用户确认 | 不实现；先列影响与待确认项，等待裁定 |

S1 实施要点：
- 新增 `form_project_candidates` 只读投影（ODS `ods_gitlab_projects/namespaces` 或 DGM 目录适配）；从现有本地目录幂等构建，并挂在既有目录同步成功边界刷新；GET 不连接 GitLab、不触发同步、不新增轮询器。
- `GET /api/collect-forms/admin/projects` 用 `form.template.manage` 鉴权，服务端分页前支持名称/路径/拼音/首字母，`size≤100`；可增加只读 `projectIds` 精确回显模式，仍限定同一 `sourceConfigId`，候选 `projectId` 为十进制字符串。
- 管理页范围抽屉用现有 `SmartSelect` 替换项目编号输入：适用项目多选、版本所属项目只从已选集合单选；唯一代码平台/单项目自动带出，多项目不默认首项；里程碑→版本名称只填空值，迟到响应不覆盖手工输入；“版本成员”改称“版本分支”。
- 候选缺失/失败/未同步保留选择与重试状态，不退回手填编号；候选身份含来源+项目 ID，切来源/项目使旧请求失效；SmartSelect 远程模式由服务端结果决定，不再本地二次过滤。
- 按版导出使用历史版本抽屉内的版本行操作，项目范围来自该版本而非当前编辑草稿；导出资格由后端返回，不由前端猜物理表/记录数；不新增独立管理页或填写页全项目导出。

最小验证：跨页搜索（目标不在首 20 项）、同名跨来源、回显/失效、单多选自动带出、迟到响应；真实 PG 目录同步→投影→候选接口；导出资格/权限、项目谓词、空版本表头及失败反馈。完成后只做管理范围选择/回显/发布、历史版本按项目导出与原编辑主线的桌面核对。

实施结果（2026-11-03）：迁移、投影、同步事件刷新、候选端点与 `FormTemplateAdminView` 接入选代已完成；真实 PG 集成测试 5/5（跨页拼音/路径、同名不同命名空间身份不合并、回显缺失、来源投影独立、成功事件刷新、幂等）；前端管理页 29/29、SmartSelect 1/1、类型检查与 ESLint 通过。本地 ODS 项目目录当前没有平台归属列，候选行按 `sourceConfigId` 独立存储和查询、切换来源使旧请求失效，同名身份由来源配置+项目 ID 确定；跨平台同名不合并依赖各来源目录内容与这一身份边界。桌面核对待整链验证阶段执行。

## N5 复审整改（R01—R05）

### R01/R02 候选来源、路径与目录状态

- 来源隔离：`gitlab_sync_configs.source_instance='default'` 只读取本 config 经 `sync_run_table_tasks` 拥有的 ODS 项目/命名空间；`dgm` 只读取 `code_review_dgm_project_options` 的已同步目录；其它来源返回 `UNAVAILABLE`，不回退全量 ODS。
- 路径：命名空间沿 `parent_id` 递归到根并校验深度≤32、缺失父项、循环与跨来源；无法证明的路径不输出候选。
- 状态：`form_project_candidate_states` 记录 `UNSYNCED/READY/EMPTY/FAILED/UNAVAILABLE`；`EMPTY` 可用但无选项，`FAILED` 保留最后成功快照并返回 message，GET 不连远端、不触发同步。
- 初始化：`FormProjectCandidateStartupInitializer` 仅在服务模式启动执行一次幂等初始化；`MigrationApplication` 不扫描平台业务包。专属库实测 63 条候选 `READY`，`2001` 返回 `demo/sub/payment-gateway`。
- 回归：`FormProjectCandidateIntegrationTest` 10/10；含两来源同时刷新、同 ID 不同平台、根路径、缺父/循环/跨来源排除、空目录、失败保留、启动初始化。

### R03 导出资格与历史抽屉交互

- 后端 detail 返回 `exportable`、`recordSourceRole`、`sourceConfigId/sourceInstance/canonicalGitlabBaseUrl` 与版本 `projectIds`；`exportable` 仅对 AUTHORITATIVE 且 PUBLISHED/RETIRED 且来源可读、项目非空为真。
- 导出项目范围优先取 `form_template_publications.frozen_binding.projectIds`，范围重绑后仍按版本冻结项目校验；请求外项目继续返回“必须属于该版本所在范围”。
- 前端入口仅保留历史版本行内次要“导出Excel”，内联单项目回显/多项目 SmartSelect、下载与取消；无新增 dialog、无实心主按钮，切换范围/版本或关闭抽屉后迟到结果不覆盖当前状态。
- 回归：管理详情 23/23、导出 29/29、前端 32/32；真实浏览器（后端 18098 / 前端 18198 / 专属 PG 15522）完成范围发布、历史抽屉单项目回显、`versionId=1&projectIds=2001` 下载 200/xlsx/4008 字节，审计行 `[2001]/row_count=0/byte_size=4008`。

### R04 最终隔离验证（专属资源）

- 专属资源：`dcp-n5-r04-pg`，容器 ID `8b5961d5b4a5e1560fc9a096de856db88a1d87482e3e1dcedb6fbb1468a6f530`，端口 `15522`，数据库 `qaflex`，schema `qaflex_n5_final`；桌面服务 18098/18198 使用同一容器 `public` schema，取证后已停止。
- 固定命令：
  `cd D:/projects/dcp-form-w4-20261026/backend`
  `TEST_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:15522/qaflex?currentSchema=qaflex_n5_final,public'`
  `TEST_DATASOURCE_USERNAME=qaflex TEST_DATASOURCE_PASSWORD=form_test_password`
  `SPRING_FLYWAY_SCHEMAS=qaflex_n5_final SPRING_FLYWAY_DEFAULT_SCHEMA=qaflex_n5_final`
  `mvn -o -Dfile.encoding=UTF-8 clean test`
- 禁止：使用或修复共享 15432/15433，repair/重建/回退共享库；Testcontainers 类继续使用各自独占容器。
- 输入与产物：`HEAD=af6d0330e00c44088938690272dc022d13ad26b5`，backend diff SHA-256 `713698c35965c5ca734912834a4cd1e7764224e84f4ea991a36f8a0f59a1e849`，全工作树 diff SHA-256 `aba9357de5f906834a9ace215b111be4c5c1ea7c1a528321843fdb80caeed709`，untracked manifest SHA-256 `18c39c16ba8e25f95cb93d10d20d9515ae95efa50183fea9026ed0db512843a5`。
- 结果：首次全量 2314/1 失败（失败类单独复测 25/25，失败时轮次停在 `REQUESTED`；当时的宿主内存/连接抖动归因未被原始日志证实）。后续审核方在独立探针中确定性复现“提交回调登记后异步领取为空”的事务竞态，并只对 `requestRound` 加独立短事务修复（见上文 R04 定位）；**不追认 13:33 原现场必然同因**。重建 schema 后二次全量 2314/0/0/1，`BUILD SUCCESS`，392 份 XML；最终日志 SHA-256 `c316975c106d3e8c07874b6c49580e4b57bb0b0f9cbc3aa4e5da66d8368a5aa2`。首次日志、复测日志与两轮 XML 分目录保留，未用跳过/掩码/放宽断言让失败变绿。

### R05 黄金快照变更来源

`f22cd63d` 的提交说明称“未在本轮更新任何快照”，与实际修改 7 份快照不符；以下为逐文件核验，均为已批准输入的继承，没有本轮新改或无法证明授权的期望值，故不回滚快照，并在本文更正原说明（不改写 Git 历史）。

| 快照 | f22cd63d 变更 | 来源/授权 | 与当前 main 差异 | 结论 |
| --- | --- | --- | --- | --- |
| `customer-issues/get___illegal-records_export__default.json` | 延期状态“未设定议题状态”→“数据异常”等业务值 | main `eb9b2f8b` 回放 | 与 main 完全相同 | 保留 |
| `customer-issues/get___illegal-records_filter-options__default.json` | 新增选项“数据异常” | main `eb9b2f8b` 回放 | 仅差分支既有 `readView` 块 | 保留 |
| `customer-issues/get___records_export__delay.json` | 延期导出业务值与状态 | main `eb9b2f8b` 回放（post 与其一致） | main 后续 `cbca4eeb` 已再更新 | 保留 |
| `customer-issues/get___records_filter-options__cc-product.json` | 新增选项“数据异常” | main `eb9b2f8b` 回放 | 仅差分支既有 `readView` 块 | 保留 |
| `customer-issues/get___records_filter-options__delay.json` | 新增选项“数据异常” | main `eb9b2f8b` 回放 | 仅差分支既有 `readView` 块 | 保留 |
| `statistic-boards/get___{boardKey}_issues_export__customer-issue-defect-summary.json` | 客户问题缺陷汇总导出值 | main `eb9b2f8b` 回放 | 与 main 完全相同 | 保留 |
| `statistic-boards/get___{boardKey}_rule-explanation__customer-issue-customer-statistics.json` | 校准集合 `outputCount` 169→164 | main `eb9b2f8b` 回放 | 余 `readView` 与延期范围 142（main 后续 `cbca4eeb` 为 110），均为分支既有基线 | 保留 |

核验命令：`git show f22cd63d^..f22cd63d -- <file>`、`git rev-parse <ref>:<file>`、`git diff eb9b2f8b f22cd63d -- <file>`；`eb9b2f8b` 已是 main `f74fdef7` 的祖先。未运行黄金、未执行 update、未手改期望值或掩码。

说明：`cbca4eeb` 对延期导出与规则说明快照的后续更新属于已批准 main 的测试基线变化，但本轮整改不运行 golden，故未手工搬运该期望值；它与本次 `f22cd63d` 的来源授权无关，留待后续发布门禁按黄金授权流程处理。

## 发布资料与差异清单（待审批，不执行）

- 新增端点：22 个（表单管理/记录/导出、read-views 会话、代码走查非法页 context 等）；删除 3 个旧写入口（`/api/collect-forms/save`、`update-record`、`delete`）。以 `endpoint-catalog.yml` 为准。
- EXCLUDED：`/api/collect-forms/exports/records`（pending-approval，需用户确认快照）、`/api/read-views*`（session 随机身份，真库/HTTP 覆盖）、外部数据集 API（external-dependency）。
- 权限：`form.record.create/edit_own/delete_own` 按迁移授予；`form.template.manage` 授予 SUPER_ADMIN/ADMIN；`form.record.export` 只登记不授予；旧 `code_review.form.*` 退役。`EndpointAuthorizationContractTest` 覆盖导出键。
- 工作区/页面：本单元未新增统计板页面；沿用客户问题既有 view/export 映射与工作区依赖。表单管理/记录端点由 `form.template.manage` 与 `form.record.*` 保护。
- 有意产出：按版导出端点、FORM/COMMENT 事实分区、MR 逐记录投影、read-view 详情读帧、表单响应/审计表结构。
- 受影响表：`form_template_*`、`form_record_*`、`form_change_heads`、`form_resource_authorities`、`form_publication_captures`、`form_review_fact`、`form_record_export_logs`、`read_publications`/`read_publication_domains` 及其门禁表。
- 黄金：不运行、不更新；上述新端点/EXCLUDED 理由与快照差异需审批后按仓库门禁生成，夹具/掩码不得先改。

## 外部前置

- E1 CI_ROBOT 真实投放/认证与 notification-payload 调用。
- E2 目标来源项目、Issue 启用时点 T、真实存量登记数据与权限清单。
- E3/E5 真实项目/里程碑/代码分支候选来源，AI 自动填充流程。
- E4 最终域名/代理/Cookie/浏览器环境。
- E6 旧写回所有者、停写切换窗口与真实写回验收；导出权当前/默认授予名单。
- 缺材料只阻断对应启用步骤，不搬迁历史评论、不访问未获准目标；生产 MR 继续 COMMENT，代码走查非法页整页旧源。

## 步骤、依赖与停止条件

1. 回执已批准范围；冻结双方 SHA（main `f74fdef7`、功能分支批准输入 `39291fa2`）与干净状态，修订本计划的禁止项和合并步骤；main 若前进先只读核对新增差异再固定目标。
2. 文档契约先行：合并 AGENTS 双方规则，保留 main D-29/31—34 与新增 D-35，更新旧计划中的 D-32 引用。
3. 在原功能分支执行 `git merge --no-commit --no-ff f74fdef7`，按“契约/发布读取 → 事实/同步 → Issue/写回 → MR/统计 → 页面/部署”顺序手工裁决冲突；保留双方父提交，不 rebase/压缩历史，不整树 ours/theirs，不盲目 cherry-pick 已在祖先中的主线提交。处理完冲突先编译和直接影响回归，再形成可回退的合并提交。
4. 冲突解决后跑一次最终集成默认套件与必要代表性链路，冻结日志/XML；失败先修根因，不连续复跑。
5. S1 功能与导出入口按获批范围实施并做桌面验收；不重构批准 UI。
6. 发布资料、黄金差异清单和外部前置齐备后另行申请；黄金、main 合入、出包、生产启用仍各自门禁。

停止条件：迁移/契约冲突未解决、直接 merge 会删除发布读取文件、集成默认套件失败、S1 范围未批准、黄金未授权。资源：本 worktree 与自有构建输出、隔离 PG/端口/容器；复审最终默认套件使用专属 `dcp-n5-r04-pg`（15522，schema `qaflex_n5_final`），桌面使用同一容器与 18098/18198；拒绝落回 15432/15433，不写共享库、不触碰共享服务或真实写回。

## 风险、假设与敏感只读数据

- 最大风险是 main/分支双历史：直接合并丢失发布读取 39 个服务文件中的 38 个与 18 条迁移；必须按上述显式集成方案执行。
- main 的延期写回/统计修复与分支表单语义同文件冲突，需逐行为合并；不以文件覆盖代替语义验证。
- 证据复用依赖输入哈希：`24a45a17…` 日志与 `215c10f3…` 后端差异只对 `63ac96ae` 有效；任何集成代码变化都需重新验证受影响范围。
- 敏感只读：`gitlab_sync_configs`、角色权限表、真实 ODM/ODS 数据；不写入、不授权、不访问未获准环境。
