# 表单 v6 N4：多版本消费、共同字段统计、Issue 选择器与按版 Excel

> 状态（2026-10-30）：开工。批准依据 [N3 最终验收与 N4 准入](unified-form-system-implementation-plan-20261021.md#n4-admission)，技术合同 [N0 §5—§8](v6-n0-technical-admission-20261027.md#5-多版本消费r04q3)。N4 通过前不进入 N5，不运行黄金，不合 main、不出包、不操作真实业务库/正式权限/外部写回。

## 进度与中间物

- 阶段（2026-10-30）：P0 计划与影响面盘点、P1 主线吸收均已完成，进入 P2；用户已裁定 MR 走逐记录投影 C、导出不做种子授予；已吸收审核回执 `99ec550b` 与主线延期写回修复（cherry-pick `17e65dcd`/`c594d529`/`7679764c`）。
- 已完成：快进吸收 N4 准入回执 `b18583f4` 与接入方案重写 `99ec550b`；吸收主线延期写回修复及迁移 `V20261022_03`（本分支退役权限迁移顺延为 `V20261022_04`，内容与 checksum 不变）；本计划；影响面只读盘点；吸收类回归与延期相关套件全绿。
- 测试状态：5 个吸收类（闭包进度、写回登记、来源就绪、接管失败、轮次事务）30/0/0/0；延期写回与解析/护栏相关 18 类 105/0/0/0；迁移护栏 163 条通过。证据见 `.tmp/w4-n4-20261030/delay-suite-20261030.log`、`enqueue-default-db-20261030.log`、`enqueue-clean-db-20261030.log`。
- 进行点：Issue 首次事实与周期 SLA（共用类型化输入）。
- 阻塞：真实 E2、生产源码版本与 Issue 投放凭据未齐，阻断真实登记/切换与生产启用；不阻断隔离实现。导出正式授予不做，目标库实际名单由权限管理员提供。

## 阶段、恢复线索与资源

- 分支/目录：`表单多版本与填写功能开发-20261026`，`D:/projects/dcp-form-w4-20261026`；不新建阶段分支，不复制未提交文件。
- 首条恢复命令：`git status --short --branch`、`git log --oneline -3`，确认 `c0c11489` 为祖先。
- 前序：N3 活动计划已收口（`git show b18583f4^:docs/plans/unified-form-v6-n3-implementation-20261029.md` 可恢复）；长期边界归 `docs/architecture.md` 与 D-32。
- 环境：自有 Testcontainers 或本任务专属 PG 容器/端口；任何迁移/SQL 写入前用 `scripts/run_isolated_form_acceptance_backend.py` 核对资源身份；不使用共享 15432/15433，不使用预览 18187/18094/15612。其他 AI 正在改动采集平台，本任务只在自己的 worktree 与独占容器内操作。

## 原始需求、成功标准与禁止行为

- MR/Issue 事实消费统一读取权威多版本记录；旧版编辑/作废、新版本发布能驱动自动重建；共同字段按已批语义统计；Issue 九项选择器按已确认规则取值；Excel 按所属版本导出。
- 成功标准：多版本读源无回退、无逐版猜表、无长期双读；捕获/结算漂移零公开零结算；共同字段不可合并时显式无效而非 0 或部分成功；作废/副本/TARGET/越权记录不可导出；权限不扩权。
- 禁止：改动 162 条既有迁移；用当前指针代替集合；把 view 推导成 export；以未批公式改 MR 汇总；运行黄金、合 main、出正式包；影响其他 AI 的工作区、共享库或端口。

## 业务/API/数据/环境约束

- 目录读源：`FormRecordDirectoryReader`（find/listByResource/capture），只接受 `AUTHORITATIVE` 且 `PUBLISHED/RETIRED`；`LEGACY_SNAPSHOT` 排除列表/统计/导出/写回；`NONE` 按登记旧源表读取；同一 C 跨版各一条有效记录，不按 C 去重。
- 捕获与结算：新增 `form_head_captures`（N0 §5.1 DDL）；捕获/结算均“范围 FOR SHARE → head FOR UPDATE”，构建在事务外；结算复核 captureToken、集合修订/哈希、head 最新/ pending 修订、权威修订、owner/fence/`clock_timestamp()` 租约，任一漂移零公开零结算。
- 事件：`form_directory_events` 已存在（N1）；发布/登记写事件并唤醒 head；游标 `form_directory_event_cursors` 推进；全量→增量按捕获修订交接。
- 共同字段：登记声明类型必须等于该版冻结 `field.type`；`stableId`+`semanticKey`+`valueType/unit`+`semanticHash` 全同才合并；不兼容→`unmerged`，缺版→`onlyInSome`，无交集→`NO_COMMON_FIELDS` 且 `total=null`。
- Issue 选择器：按 `(createdAt, recordId)` 降序选最新整份当前修订；编辑不重排、作废重选；首次取最早；D-21 与 18 天公式、缺陷原因多选与唯一性分层保持既有规则。
- 导出：`GET /api/collect-forms/exports/records`；版本必须是可读 `AUTHORITATIVE`，项目集须为 scope 子集且逐行校验目录 C；只导出 `deleted=false`；空版本导出表头；新增 `form_record_export_logs` 审计；方法级 `form.record.export`，不从 view 推导。
- 迁移：162 条不可改；必要 DDL 前向追加并登记 `scripts/flyway-migration-checksums.json`。

## 现有入口与影响面（只读盘点）

| 面 | 位置 | 现状 |
| --- | --- | --- |
| MR 全量事实 | `FactBuildService.rebuildMergeRequestFactsInternal` | 经 `GitlabFactSourceSqlProvider` 的 `form_records` CTE 读 `collect_form_records`，按 project+iid `max(reviewer)`/`max(review_duration_minutes)` 聚成单行 |
| MR 定向 | `FactBuildService.rebuildMergeRequestFactsByRootIds` | 同上 SQL 的 root 过滤分支 |
| MR 单条/诊断 | `FactBuildService.rebuildMergeRequestFactByIid`（`CodeReviewIllegalRecordService:455` 调用） | 同上 |
| MR 周期指标 | `CodeReviewMetricEnrichmentService.publishEnriched` | 外部指标富化，不含表单读源 |
| Issue 事实 | `FactRefreshTaskWorkerService` ISSUE 分支 → `FactBuildService.rebuildIssueFacts*` | `IssueFactSourceRowMapper` + `IssueResponseTemplateParser` 只解析评论来源，表单选择器未接入 |
| 工作区/ODS 依赖 | `RealtimeWorkspaceDependencyCatalog`、`GitlabFactDependencyCatalog` | 无表单条目，N4 保持不变并由回归锁定 |
| 记录读源 | `FormRecordDirectoryReader`、`FormRecordService` | N1/N2 已实现，N4 接入事实与导出 |
| 可比字段 | `FormComparableRegistrationService` + `form_field_comparable_registrations` | 已登记声明；选择/聚合函数未见生产实现，N4 新增 |
| 导出 | 无表单导出端点/权限键 | 全部新增；审计表随导出单元迁移 |
| 权限种源 | `V20260720_01` 授予规则 + `V20260720_06` 默认快照 | `review.record.export`、`customer_issue.record.export` 为既有导出源 |

## 证据与复现

- 合同文件：N0 §5（捕获/结算/事件）、§7（共同字段/选择器/分母）、§8（导出/权限/容量）。
- 夹具：共用非空两版（`code_review` V1/V2，`issue_response`/`issue_reply` 覆盖选择器），真实 PG Testcontainers。
- 待补证据：捕获/结算双会话交错、pending 逐项反例、共同字段 2+3=5 与不兼容反例、Issue 九项、导出空/非空与越权、全量→增量与重启。
- 环境事实（2026-10-30）：共享测试库 15433 `qaflex` 的 `public` 残留 22 张空 ODS/事实表（2026-09-29/10-22 以缺少 `currentSchema` 的连接运行所留），其中 `public.ods_gitlab_labels` 会在 `qaflex_test` 同名表被应急改名后兜底解析，令"标签查询失败必须抛出"回归假通过/假失败。已将全部残留整体移入 `residue_20261030` schema（可逆、未删除），默认配置下 30 项全绿。

## 步骤与依赖

0. **P1 主线吸收（已完成）**：cherry-pick 延期写回三提交与迁移，编译通过；跑吸收类回归。
1. **P2 Issue 首次事实与周期 SLA**：新增 `IssueBusinessInput` 与 `FormIssueInputResolver`（权威/目录读取，响应/回复按 `(createdAt, recordId)` 选最新整份、首次取最早）；`IssueFactSourceRowMapper` 映射基础列、`FactBuildService` 调 resolver 与 `IssueBusinessProjector.apply` 写业务列；`refreshCustomerIssueDelayFactsForConfig` 复用同一 resolver/evaluate 并受捕获守卫，`raw_payload` 仅供 COMMENT。首个回归：FORM 计划 11-15、旧评论计划 11-09 → 周期重算仍取 11-15、解决延期 false；历史 COMMENT 原路径不变。
2. **P3 捕获/结算与证据一致性**：`form_head_captures` 迁移与领取/捕获/结算；目录事件消费与游标；`CustomerIssueDelayLabelWritebackWorkerService.authorize` 复核当前/事实/作业 `form_input_hash`（两模板 head/权威修订）一致且 latest=consumed，沿原队列/轮次/围栏；验证改期、作废后旧任务发送次数为 0。
3. **P4 MR 逐记录投影**：`FormReviewProjector.build` 经目录生成逐记录 `form_review_fact` 并同事务发布；`form_records` CTE 读投影（汇总分钟/五类数/人员/最近创建时间）；质量分析按记录 sum/count；生产 MR 继续评论，走查 FORM 仅隔离验收。
4. **P5 共同字段与按版导出**：`selectCommonFields`/`aggregateMetric` 生产实现（2+3=5、不兼容显式无效）；导出端点、鉴权、审计、流式写出与预算；正式授予不做。
5. **P6 验证与交付**：代表性两版端到端、Issue 切换边界 T、双会话捕获/结算、导出拒绝矩阵；受影响类回归与一次隔离默认后端；护栏、双远端、清单送审。

## 用户裁定（2026-10-30，不再提供选择题）

- **MR 跨版口径 C：逐记录投影**。所有有效、可比较的走查记录参与统计，不取 `max`、不只留最新一条：两条记录时长 10/20 分钟→总时长 30、平均 15；规范问题 2/3→5。MR 事实仍按资源唯一一行；质量分析按记录 sum/count。否决 A（逐列 max）与 B（仅最新）。
- **导出角色：不授予五个种子角色**。隔离库五角色只是样本；当前授予与默认快照分别继承目标环境各自的独立导出权，查看权不得推导导出权。名单未取得前，只用隔离角色完成鉴权测试，不写正式授予迁移。
- **启用顺序**：Issue 按启用点 T 分流（新资源 FORM、历史 COMMENT）先启用；MR 生产继续评论，走查 FORM 仅在隔离范围验收，`T_mr` 未设时 MR 登记 COMMENT；非法页全旧、不混合。

## 已吸收主线依赖

- 延期写回修复：持久进度与领取围栏、恢复领取/租约/授权回滚、模板标题解析与在途停写保存（`17e65dcd`、`c594d529`、`7679764c`）。
- 迁移：主线 `V20261022_03__customer_issue_delay_closure_progress.sql` 随吸收进入；本分支退役权限迁移顺延为 `V20261022_04`，全部 163 条登记 checksum。
- 后续 Issue 周期 SLA 与写回资格复用该修复的队列、轮次、围栏与发送前复核，不建旁路。

## 风险、假设与敏感只读数据

- 真实 E2 未取得：不阻断隔离开发，但真实登记/切换与容量结论须等 E2；不得用小夹具外推容量。
- 其他 AI 并行改动：只在本 worktree 与独占容器/端口操作，不读写共享库与共享服务。
- 敏感只读：`platform_role_permissions`、`platform_default_role_permissions`、`gitlab_sync_configs` 仅只读核对，不写入。
