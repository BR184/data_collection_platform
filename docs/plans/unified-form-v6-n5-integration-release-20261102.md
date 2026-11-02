# 表单 v6 N5：集成与发布准备

> 状态（2026-11-02，N5 进行中）：N4 整体验收通过（回执 `d09dbf04`），P6-R01/R02 关闭；本计划只做集成基线核对、证据矩阵、未实施功能清单与发布资料，不合并 main、不运行黄金、不出包、不启用生产。分支 `表单多版本与填写功能开发-20261026`，输入 `76dc4dd3`。N4 活动计划已收口，原文可按 `git show 76dc4dd3:docs/plans/unified-form-v6-n4-implementation-20261030.md` 恢复。

## 进度与中间物

- 阶段：吸收 N4 通过回执并建立 N5 计划；主线依赖与冲突已只读核对，尚无集成候选或新增验证。
- 已完成：回执吸收提交 `76dc4dd3`；N4 长期事实归入 `docs/product.md`、`docs/architecture.md` 与 `docs/decisions.md` D-35；N4 活动计划删除，过程保留 Git 历史。
- 输入 SHA：功能分支 `76dc4dd3a9b394fe4bb18668ee70a6ea0fd8da84`；main `f74fdef75ea2e1f093c131ceb8c6eee490d397ef`（origin/gitlab25 一致）；共同祖先 `84e7c023449de190e722d79d027accb062358172`。
- 已有验证复用：后端默认套件 2191 项、0 失败、0 错误、1 条件跳过（日志 SHA-256 `24a45a17…`，XML 383 份，`.tmp-logs/p6-r02-final-20261102/`）；171 条迁移锁通过。N5 不重复该运行，只有集成代码改变链路时再跑。
- 进行点：等待对“集成范围/顺序”与“S1 功能改动范围”的审批；未解决前不创建候选升级、不合并 main。
- 恢复命令：`git status --short --branch`、`git log -1`、`git worktree list`；本计划文件即 N5 恢复入口。

## 原始需求、成功标准与禁止行为

- 用户已确认：记录留原版、新建用当前版、旧记录按原版维护、相同业务字段跨版统计、按版本 Excel 导出；Issue 按启用点 T 分流 FORM/COMMENT，MR 在 T_mr 前继续评论。
- N5 成功标准：集成范围与冲突有据可查；主方案 §4.1 验收矩阵逐项映射到证据或明示缺口；未实施承诺与发布资料齐全；后续集成候选可在不破坏 main 已批准修复的前提下验证。
- 禁止：本阶段合 main、强制推送、正式出包、源码留档分支、生产启用、真实权限授予、真实 GitLab 写回；运行黄金、改快照/夹具/掩码；改已部署迁移；重构批准 UI；未经报审实现 S1 或自行设计新模板模型、版本绑定解耦、退役恢复。

## 基线核对（只读，2026-11-02）

### 分叉历史

`merge-tree` 模拟显示直接合并会产生 45 处冲突：`read` 服务 39 个文件只保留 1 个、18 条 `V20261012_01`—`V20261015_03` 发布读取迁移被 main 侧删除；延期写回编排/进度/轮次/写回队列出现 add/add 或内容冲突；`FactBuildService`、`FactTargetPublicationService`、`IssueSlaRules`、`IssueFactSourceRowMapper` 等同时承载双方语义。结论：**禁止直接 merge main**，必须按行为单元显式集成并恢复发布读取基线。

原因：main 的 `59cd5587` 以延期稳定候选为第一父提交收敛主线，未采用 `84e7c023` 发布读取分支的树；双方共同祖先仍是 `84e7c023`，因此 main 对未见文件的“删除”会被 Git 当作已决定，直接合并会丢失发布读取基础设施。证据：`git merge-tree --write-tree gitlab25/main HEAD`（结果树 `1d357a0a…`，exit 1，45 处 CONFLICT）原始输出 `.tmp/merge-tree-main-head.txt`。

### 迁移与升级入口

| 项 | main | 功能分支 | 判断 |
| --- | --- | --- | --- |
| 迁移总数 | 140 | 171 | 分支 = main 140 + 31，无 main 独有版本 |
| 共享 140 条 checksum | — | 与 main 完全一致 | 无既有迁移改动 |
| 分支新增 31 条 | — | 18 条发布读取 + 13 条表单 v6 | 全部前向；含 `V20261022_01/02/04`、`20261027_01`、`20261029_01/02`、`20261030_01/02`、`20261102_01`—`05` |
| 旧主线升级测试 | 140 条基线 | `FormV6LegacyUpgradeIntegrationTest` 按 140+31、head `20261102.05` | 已与当前 main 迁移集对齐，1/1 通过 |

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
| 管理异步边界 | `FormTemplateAdminIntegrationTest` 22、管理页前端测试；批准 UI 基线 | 已有证据 |
| 字段与条件 | `FormTemplateAdminIntegrationTest` 22、`FormVersionStructureVerifierIntegrationTest` 6 | 已有证据 |
| 共同字段统计 | `FormCommonFieldServiceTest` 12、`FormReviewProjectionIntegrationTest` 3、`CodeReviewMultiBoardFormRecordsIntegrationTest` 3 | 已有证据 |
| Issue 时间选择 | `FormIssueInputResolverAndSlaTest` 15、`FormIssueAdoptionFactPipelineIntegrationTest` 25 | 已有证据 |
| 消费并发 | `ReadPublicationModelIntegrationTest` 49、`FormHeadCaptureIntegrationTest` 9、`CustomerIssueRequirementLabelAutoChainIntegrationTest` 9、`FormIssueAdoptionFactPipelineIntegrationTest` 25 | 已有证据 |
| Excel | `FormRecordExportIntegrationTest` 5、`FormRecordExportSpringContextIntegrationTest` 1、导出布局测试 | 端点待黄金审批 |
| 初始化与恢复 | `FormV6LegacyUpgradeIntegrationTest` 1、`FormV6DirectoryMigrationTest` 3、`FormV6RenumberedHistoryUpgradeTest` 1 | 已有证据 |
| 门禁 | 171 条迁移锁、端点/权限/工作区登记、默认套件 2191、批准前端构建/桌面证据 | 黄金未运行，按发布门禁另行审批 |

矩阵说明：以上计数来自冻结 XML（`.tmp-logs/p6-r02-final-20261102/xml/`），按输入哈希复用；若集成改变运行链路，只在最终集成代码跑一次默认套件及必要代表性链路，先冻结原始日志/XML 再单跑。前端未变时沿用已批准版本的 typecheck/lint/build/桌面证据，不重复模板或浏览器全矩阵。

## 未实施功能与待确认项

| 项 | 当前事实 | 最小实施范围（须先报审） |
| --- | --- | --- |
| S1 项目目录候选 | 无 `form_project_candidates`、无 `GET /api/collect-forms/admin/projects`；管理页仍以“项目编号”文本和数字输入版本所属项目 | 后端新增只读投影与分页搜索端点（ODS/DGM 适配、完整路径、拼音/首字母、来源隔离、字符串 projectId、`form.template.manage`）；前端在批准布局内用 SmartSelect 替换编号输入，适用项目多选、版本所属项目从集合显式选择，保留失效/未同步/失败状态 |
| 业务命名与自动带出 | 代码平台/适用项目/版本所属项目等业务名已有产品规则，但来源地址、项目、里程碑→版本名称、范围名称建议未接入管理页 | 仅在绑定抽屉接入候选与自动带出；不改字段卡片、常驻摘要、抽屉层级和视觉基线 |
| 按版导出使用入口 | 后端 `GET /api/collect-forms/exports/records` 与 `form.record.export` 已存在（只登记未授予）；前端无导出入口 | 增加受权限控制的版本/项目导出入口与错误/空状态；不授予角色，不改导出公式与端点契约 |
| 独立新模板模型、版本绑定解耦、退役恢复 | 未获用户确认 | 不实现；先列影响与待确认项，等待裁定 |

以上功能均需先提交接口、调用/状态变化、受影响测试与桌面验收清单，经审核后再实施；当前 UI 保持。

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

1. 送审本计划的集成范围/顺序与 S1 改动范围；未获确认前不创建集成候选、不合并 main。
2. 文档契约先行：合并 AGENTS 双方规则，保留 main D-29/31—34 与新增 D-35，更新旧计划中的 D-32 引用。
3. 在独立 worktree/分支从当前 main 建立集成候选，按“发布读取基线 → main 独立修复 → 表单 v6 → 页面/导出”顺序逐单元合入，保留 main 已批准行为；禁止整分支 ours/theirs 覆盖。
4. 冲突解决后跑一次最终集成默认套件与必要代表性链路，冻结日志/XML；失败先修根因，不连续复跑。
5. S1 功能与导出入口按获批范围实施并做桌面验收；不重构批准 UI。
6. 发布资料、黄金差异清单和外部前置齐备后另行申请；黄金、main、出包、生产启用仍各自门禁。

停止条件：迁移/契约冲突未解决、直接 merge 会删除发布读取文件、集成默认套件失败、S1 范围未批准、黄金未授权。资源：本 worktree 与自有构建输出、隔离 PG/端口/容器；不使用 15432/15433、共享服务或真实写回。

## 风险、假设与敏感只读数据

- 最大风险是 main/分支双历史：直接合并丢失发布读取 39 个服务文件中的 38 个与 18 条迁移；必须按上述显式集成方案执行。
- main 的延期写回/统计修复与分支表单语义同文件冲突，需逐行为合并；不以文件覆盖代替语义验证。
- 证据复用依赖输入哈希：`24a45a17…` 日志与 `215c10f3…` 后端差异只对 `63ac96ae` 有效；任何集成代码变化都需重新验证受影响范围。
- 敏感只读：`gitlab_sync_configs`、角色权限表、真实 ODM/ODS 数据；不写入、不授权、不访问未获准环境。
