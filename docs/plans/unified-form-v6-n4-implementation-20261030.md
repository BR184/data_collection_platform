# 表单 v6 N4：多版本消费、共同字段统计、Issue 选择器与按版 Excel

> 状态（2026-11-02 第四次复审复核整改）：R01/R02-a/R02-b/R03-a/R04-b 已关闭，R05 处置归环境负责人；第三次复审接受完整输入冻结、持久签名、自动 FACT_REFRESH 落库与 M01 升级代码路径；第四次复验关闭 R04-b 锁序、P3 无变化重复触发与 M01 命令，仅指出 P3 轮次真实终态停 REQUESTED。本轮只修 `startMirrorRound` 拒绝分支、`refreshFactsOnly`、`authorizeEnqueue` 三个重算入口的外层事务隔离为 REPEATABLE READ，**待复审**。P4、P5 暂缓。批准依据 [N3 最终验收与 N4 准入](unified-form-system-implementation-plan-20261021.md#n4-admission)，技术合同 [N0 §5—§8](v6-n0-technical-admission-20261027.md#5-多版本消费r04q3)。N4 通过前不进入 N5，不运行黄金，不合 main、不出包、不操作真实业务库/正式权限/外部写回。

## 进度与中间物

- 阶段（2026-11-02）：P0 计划与影响面盘点、P1 主线吸收完成；P2 首次送审（`6a14d605`）与两轮整改后，四审 R02/R03 已关闭；第三次复审指出的 R04-a、P3 持久签名、Issue 自动消费与 M01 实际入口已整改，待复审；P4、P5 暂缓。用户已裁定 MR 走逐记录投影 C、导出不做种子授予。
- 已完成：快进吸收 N4 准入回执 `b18583f4`、接入方案重写 `99ec550b`、三审回执 `992fe7dc` 与四审回执 `2cd57488`；吸收主线延期写回修复及迁移 `V20261022_03`（本分支退役权限迁移顺延为 `V20261022_04`，内容与 checksum 不变）与规范状态成员合同（`6aa9bd69`、`9e5e4bac`）；R01 首次事实与周期路径统一以 `IssueStatusMembers` 判定解决延期并补修前红证据；R02 首次登记冻结 COMMENT/FORM、写入口只核验不补登记，已有 FORM 权威缺 head 时按真实修订与当前集合证据补建；R03 响应/回复各自权威判定延期豁免；R04 周期只绑定候选捕获、最终公开事务复核并原子推进指针与 consumed；P3 目录事件游标与发送前表单 head 守卫；M01 以批准主线 140 条迁移集建旧库并执行 27 条低版本新增迁移。2026-11-02 补：读取业务值前冻结全部 FORM 证据（他人持有 head 即整份候选让位）、发布候选清单计数/哈希与逐根完整性校验、事实与写回作业 `form_input_hash`（当前/事实/作业三方一致，head 守卫仅附加）、表单写入桥接既有 `fact_change_heads`/FACT_REFRESH 并在公开后触发原来源级延长写回轮次、M01 改走真实 `MigrationApplication` 一次性 out-of-order 入口且默认模式 validate。2026-11-02 复核整改：`verifyConsumedEvidence` 统一为先范围 `FOR SHARE` 再 head `FOR UPDATE`，与捕获/结算同一锁序；续轮事件只在本次公开实际消费新 FORM 修订（`CAPTURED` 证据）时触发；迁移 runbook 补 `PLATFORM_RUN_MODE=migrate`，升级后显式 `service` 启动。2026-11-02 第四次复验：上述三项关闭；P3 轮次真实终态停 `REQUESTED`（请求1/处理1/完成0/失败1），根因是轮次服务先开启默认 `READ COMMITTED` 事务，内部 `REQUIRED` 加入的重算无法提升隔离级别，被稳定输入检查拒绝。本轮把 `startMirrorRound` 拒绝分支、`refreshFactsOnly`、`authorizeEnqueue` 三个重算入口的外层事务统一设为 `REPEATABLE_READ`，重算与进度/授权仍在同一事务原子提交，不拆 `REQUIRES_NEW`、不移除稳定输入检查、不以重试绕过。
- 测试状态（受复审影响范围）：10-31 受影响类 16 类 167 项全绿（选择器/SLA 15、首次事实与周期真实 PG 16、捕获/结算 4、延期写回 worker 17、表单记录服务 37、登记 21、可比登记 9、目录迁移 3、FactBuild 单测、主线历史升级 1、旧编号历史失败关闭 1）；2026-11-01 补“缺 head 按真实权威修订初始化且已存在 head 不重置”反例后，`FormIssueAdoptionFactPipelineIntegrationTest` 18/18 通过（含新增真实 PG 守卫：发布后无残留证据、head 推进未消费时拒发、COMMENT 权威不误判；`.tmp-logs/n4-p3-guard-db-3.log`；首次复跑因宿主机内存不足在 surefire fork 启动失败，约束堆后通过）；FactBuild 相关单元 29/29 通过（`.tmp-logs/n4-factbuild-unit.log`）。R01 两处真实实现突变分别只打红对应反例（`.tmp/w4-n4-r01-20261030/red/`）；迁移护栏 167 条（既有 166 条零改动 + 新前向 `V20261102_01`）、空白/产物/运行时路径护栏通过。2026-11-02 第三次复审整改验证：`FormIssueAdoptionFactPipelineIntegrationTest` 20/20（新增“他人持有 head 不无证公开”“A 读取后 B 写入零结算”真实 PG 反例）、`FormHeadCaptureIntegrationTest` 4/4、写回 worker 19/19（新增事实/作业/当前三方签名反例）、`FormRecordServiceIntegrationTest` 38/38（新增 ODS 未变时表单写入推进 ISSUE 事实头）、M01 主线升级 1/1（140→167，默认 false validate），FactBuild/选择器/编排/写回队列相关类全绿；日志 `.tmp-logs/batch-a.log`、`.tmp-logs/batch-b.log`、`.tmp-logs/batch-c.log`、`.tmp-logs/m01-2.log`。2026-11-02 复核整改：`FormIssueAdoptionFactPipelineIntegrationTest` 22/22、`FormHeadCaptureIntegrationTest` 4/4、`FactTargetPublicationServiceIntegrationTest` 19/19、`FactPublicationFailurePreservationTest` 5/5、写回 worker 19/19、编排 7/7，合计 76 项全绿。两个运行反例均完成修前红/修后绿：旧 head→范围锁序在真实 PG 复现 `40P01` 死锁（`.tmp-logs/review3-lock-order-red.log`），修复后通过（`.tmp-logs/review3-lock-order-green.log`）；旧“任何证据即触发”在无变化周期公开下 `requestSourceRound` 被调 3 次（`.tmp-logs/review3-round-trigger-red.log`），仅新消费触发后通过（`.tmp-logs/review3-round-trigger-green.log`）。M01 沿用已通过的 140→167 主线升级证据，仅修 runbook 命令模式，未重跑。2026-11-02 第四次复验整改：新增真实 PG 终态回归（异步编排真实执行、写回关闭且定时触发隔离；断言 `IDLE`、`completed_revision=request_revision`、`failure_count=0`、无隔离错误，同输入周期公开不再新增请求），修前红停 `REQUESTED`/失败1（`.tmp-logs/review4-round-converge-red.log`），修后绿（`review4-round-converge-green.log`）；三个重算入口用替身断言真实连接隔离，修前红均为 `read committed`（`review4-round-tx-red.log`），修后绿（`review4-round-tx-green.log`）。受影响 4 类 54 项全绿：adoption pipeline 23、轮次事务 5、编排 7、写回 worker 19。
- 进行点：回执 `fdb85e31` 已吸收为 `6b4360d7`；修复 `30da46ed`、文档 `74f78a8c` 已推送 `gitlab25` 与 `origin` 并回读一致，下一步送复审复核。P4/P5 在复审通过前不启动；不推进 MR 统计、导出、main 或出包。
- 事件游标边界：`FormDirectoryEventCursorService` 已提供服务与真实 PG 反例；Issue 路径当前由发布事务直接唤醒 head，目录事件的生产消费调度随 P4/MR 接入，不在本轮冒充已启用。
- 阻塞：真实 E2、生产源码版本与 Issue 投放凭据未齐，阻断真实登记/切换与生产启用；不阻断隔离实现。导出正式授予不做，目标库实际名单由权限管理员提供。
- 状态纠偏：此前“P1/P2 完成、九项选择器完成”的表述撤回；九项选择器只完成取值、原因集合与 SLA 接入，统计/导出消费（P4/P5）未实施。

## 阶段、恢复线索与资源

- 分支/目录：`表单多版本与填写功能开发-20261026`，`D:/projects/dcp-form-w4-20261026`；不新建阶段分支，不复制未提交文件。
- 首条恢复命令：`git status --short --branch`、`git log --oneline -3`，确认 `c0c11489` 为祖先。
- 前序：N3 活动计划已收口（`git show b18583f4^:docs/plans/unified-form-v6-n3-implementation-20261029.md` 可恢复）；长期边界归 `docs/architecture.md` 与 D-32。
- 环境：自有 Testcontainers 或本任务专属 PG 容器/端口；任何迁移/SQL 写入前用 `scripts/run_isolated_form_acceptance_backend.py` 核对资源身份；不使用共享 15432/15433，不使用预览 18187/18094/15612。其他 AI 正在改动采集平台，本任务只在自己的 worktree 与独占容器内操作。

## 原始需求、成功标准与禁止行为

- MR/Issue 事实消费统一读取权威多版本记录；旧版编辑/作废、新版本发布能驱动自动重建；共同字段按已批语义统计；Issue 九项选择器按已确认规则取值；Excel 按所属版本导出。
- 成功标准：多版本读源无回退、无逐版猜表、无长期双读；捕获/结算漂移零公开零结算；共同字段不可合并时显式无效而非 0 或部分成功；作废/副本/TARGET/越权记录不可导出；权限不扩权。
- 禁止：改动已登记既有迁移（当前 167 条，含新前向输入证据迁移）；用当前指针代替集合；把 view 推导成 export；以未批公式改 MR 汇总；运行黄金、合 main、出正式包；影响其他 AI 的工作区、共享库或端口；再次移动或恢复共享库对象。

## 业务/API/数据/环境约束

- 目录读源：`FormRecordDirectoryReader`（find/listByResource/capture），只接受 `AUTHORITATIVE` 且 `PUBLISHED/RETIRED`；`LEGACY_SNAPSHOT` 排除列表/统计/导出/写回；`NONE` 按登记旧源表读取；同一 C 跨版各一条有效记录，不按 C 去重。
- 捕获与结算：新增 `form_head_captures`（N0 §5.1 DDL）；捕获/结算均“范围 FOR SHARE → head FOR UPDATE”，构建在事务外；结算复核 captureToken、集合修订/哈希、head 最新/ pending 修订、权威修订、owner/fence/`clock_timestamp()` 租约，任一漂移零公开零结算。
- 事件：`form_directory_events` 已存在（N1）；发布/登记写事件并唤醒 head；游标 `form_directory_event_cursors` 推进；全量→增量按捕获修订交接。
- 共同字段：登记声明类型必须等于该版冻结 `field.type`；`stableId`+`semanticKey`+`valueType/unit`+`semanticHash` 全同才合并；不兼容→`unmerged`，缺版→`onlyInSome`，无交集→`NO_COMMON_FIELDS` 且 `total=null`。
- Issue 选择器：按 `(createdAt, recordId)` 降序选最新整份当前修订；编辑不重排、作废重选；首次取最早；D-21 与 18 天公式、缺陷原因多选与唯一性分层保持既有规则。
- Issue 启用点：`platform.form-adoption.issue-enabled-at` 只参与首次权威登记，不参与后续读源判断。消费按 `form_resource_authorities` 的持久权威与修订逐模板（`issue_response`/`issue_reply`）读取：`FORM` 读目录，`COMMENT` 保持评论；缺失权威时若项目已登记范围则为“待登记”并跳过表单，未登记范围保持评论；权威冲突或来源身份不一致显式拒绝。`FORM` 空目录表示“尚未填写”，不回退评论。`raw_payload` 仅供 COMMENT。
- 导出：`GET /api/collect-forms/exports/records`；版本必须是可读 `AUTHORITATIVE`，项目集须为 scope 子集且逐行校验目录 C；只导出 `deleted=false`；空版本导出表头；新增 `form_record_export_logs` 审计；方法级 `form.record.export`，不从 view 推导。
- 迁移：既有 166 条不可改；必要 DDL 前向追加并登记 `scripts/flyway-migration-checksums.json`。

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
### R05 共享环境改动证据与处置（2026-10-30）

**违规事实**：本任务曾把共享测试库 15433 `qaflex.public` 的 22 张表整体移入 `residue_20261030` schema，超出“只在自己 worktree 与独占容器内操作”的隔离边界；可逆不等于隔离。**本任务不再移动、恢复或删除任何共享对象，最终保留或恢复由共享环境负责人确认。**

实际执行 SQL（由 `pg_class` 当前 `public` 普通表生成，逐表执行）：

```sql
create schema if not exists residue_20261030;
alter table public.fact_build_tasks set schema residue_20261030;
alter table public.fact_projection_generations set schema residue_20261030;
alter table public.fact_projection_refresh_tasks set schema residue_20261030;
alter table public.ods_gitlab_issue_assignees set schema residue_20261030;
alter table public.ods_gitlab_issues set schema residue_20261030;
alter table public.ods_gitlab_label_links set schema residue_20261030;
alter table public.ods_gitlab_labels set schema residue_20261030;
alter table public.ods_gitlab_merge_request_assignees set schema residue_20261030;
alter table public.ods_gitlab_merge_request_diff_commits set schema residue_20261030;
alter table public.ods_gitlab_merge_request_diffs set schema residue_20261030;
alter table public.ods_gitlab_merge_request_metrics set schema residue_20261030;
alter table public.ods_gitlab_merge_request_reviewers set schema residue_20261030;
alter table public.ods_gitlab_merge_requests set schema residue_20261030;
alter table public.ods_gitlab_milestones set schema residue_20261030;
alter table public.ods_gitlab_namespaces set schema residue_20261030;
alter table public.ods_gitlab_notes set schema residue_20261030;
alter table public.ods_gitlab_projects set schema residue_20261030;
alter table public.ods_gitlab_resource_label_events set schema residue_20261030;
alter table public.ods_gitlab_users set schema residue_20261030;
alter table public.page_record_snapshots set schema residue_20261030;
alter table public.statistic_board_snapshots set schema residue_20261030;
alter table public.sync_runs set schema residue_20261030;
```

当前对象与行数（只读 `count(*)`，2026-10-30 核对；`reltuples` 均为 -1 表示未分析，故不用估算值）：

| 对象 | 行数 | 大小（含索引/TOAST） |
| --- | --- | --- |
| `fact_build_tasks` | 1 | 24576 B |
| `fact_projection_generations` | 1 | 32768 B |
| `fact_projection_refresh_tasks` | 1 | 32768 B |
| `sync_runs` | 1 | 24576 B |
| `ods_gitlab_issue_assignees` | 0 | 8192 B |
| `ods_gitlab_issues` | 0 | 32768 B |
| `ods_gitlab_label_links` | 0 | 24576 B |
| `ods_gitlab_labels` | 0 | 16384 B |
| `ods_gitlab_merge_request_assignees` | 0 | 8192 B |
| `ods_gitlab_merge_request_diff_commits` | 0 | 0 B |
| `ods_gitlab_merge_request_diffs` | 0 | 8192 B |
| `ods_gitlab_merge_request_metrics` | 0 | 8192 B |
| `ods_gitlab_merge_request_reviewers` | 0 | 8192 B |
| `ods_gitlab_merge_requests` | 0 | 32768 B |
| `ods_gitlab_milestones` | 0 | 8192 B |
| `ods_gitlab_namespaces` | 0 | 8192 B |
| `ods_gitlab_notes` | 0 | 32768 B |
| `ods_gitlab_projects` | 0 | 16384 B |
| `ods_gitlab_resource_label_events` | 0 | 8192 B |
| `ods_gitlab_users` | 0 | 8192 B |
| `page_record_snapshots` | 0 | 24576 B |
| `statistic_board_snapshots` | 0 | 24576 B |

依赖与前后证据：迁移时 `public` 有 22 张普通表、0 视图；`residue_20261030` 内无外键、无视图引用，只有 `page_record_snapshots_id_seq`/`statistic_board_snapshots_id_seq` 两个自有序列随表移动，默认值随表移动。当前 `public` 的 `pg_class` 普通表数为 0（视图/序列等关系对象也为 0）。4 个非空表的 1 行均为 2026-09-29 隔离测试写入的合成数据，非共享业务数据。

未留证边界：迁移前未做 `pg_dump`/权限/owner 快照，无法证明迁移前 owner、ACL、依赖对象与 `public` 搜索路径行为完全一致；迁移语句本身可逆，但恢复会重新影响共享库，本任务不执行。恢复方法（仅供负责人评估，需在共享库维护窗口执行）：

```sql
alter table residue_20261030.<上述22张表逐一> set schema public;
drop schema residue_20261030;  -- 仅在确认其中无其他对象时
```

后续隔离规则：所有测试与验证显式绑定本任务独占容器/数据库/schema 与 `currentSchema`/`search_path`；缺表反例在自有 schema 内制造；不得依赖此次共享库整理作为任何验证前提。

### M01 升级验证与旧库处置（2026-10-31 修订）

**主线历史 → 目标版本真实升级**（`FormV6LegacyUpgradeIntegrationTest`，真实 PG16）：旧库只由批准主线迁移集构建——从 `db/migration` 复制除 26 条分支新增脚本外的全部文件，`migrate()` 实际执行 140 条，水位 `20261022.03`；断言 `V20261019_01` 成功存在、`customer_issue_delay_closure_requests` 存在、`form_record_locations` 不存在。写入真实非空旧 Demo 行与来源配置后，切换到目标迁移集并以应用同款 `outOfOrder(true)` 执行其余 26 条低版本新增迁移（`20261012.01`—`20261030.02`）；断言执行条数 26、水位 `20261030.02`、旧库历史前缀 140 条逐条不变、`.03` 描述仍为 `customer issue delay closure progress`、`.04` 为 `retire legacy form permissions`、`validate()` 直接通过（未调用 repair）、旧行守恒，并在升级库上完成旧 Demo 登记与统一读源取回原值。不预装新脚本、不 repair、不改写历史、不放宽校验。

**旧编号库失败关闭**（`FormV6RenumberedHistoryUpgradeTest`）：模拟旧表单库历史——迁到 `.02` 后登记 `20261022.03 = retire legacy form permissions`（校验和取当前 `.04`，即旧文件原内容），再执行合并迁移必须抛 checksum 校验错误；历史行保持原描述、后续版本零应用、`form_record_locations` 不存在。合并代码不会 repair、改写历史或静默跳过。

**旧表单试验库清单（2026-10-30 只读核对 `flyway_schema_history`）**：

| 库/容器 | 端口 | `.03` 及之后 | 归属/用途 | 处置 |
| --- | --- | --- | --- | --- |
| `dcp-fill-ui-pg-20261028` | 15612 | `.03 = retire legacy form permissions`；`.27.01 = form v6 directory` | 填写页 UI 预览（另一任务） | 可丢弃预览库，按合并版本重建；如需保留由归属方书面控制升级，本任务不 upgrade/repair |
| `dcp-form-ui-60d52946` | 15605 | `.03 = retire legacy form permissions`，无后续 | 表单管理 UI 预览（另一任务） | 同上 |
| `qaflex-postgres` | 15438 | `.03 = customer issue delay closure progress` | 本地开发栈 | 主线编号，不受改号影响；本任务不操作 |
| `dcp-quality-ui-20261030-pg` | 15442 | `.03 = customer issue delay closure progress` | 质量看板验收（另一任务） | 同上 |
| 本任务隔离库 | 15601/15602/15607/15608/15499 | — | N1—N3 一次性容器 | `docker ps -a` 与端口监听核对均不存在，无需处置 |
| 共享库 | 15432/15433 | — | 共享环境 | 不在本任务处置范围，见 R05，交负责人 |

**规则**：不自动 repair、不改写 `flyway_schema_history`、不移动共享对象；旧编号库只允许“丢弃重建”或归属方书面控制升级。

## 步骤与依赖

0. **P1 主线吸收（已完成）**：cherry-pick 延期写回三提交与迁移，编译通过；跑吸收类回归。
1. **P2 Issue 首次事实与周期 SLA（整改完成待复审）**：`IssueBusinessInput`/`FormIssueInputResolver`/`IssueBusinessProjector` 按持久权威逐模板分流；R01 规范状态成员、R02 首次登记冻结与权威分流、R03 原因集合/模板合规/回复详情快照与分模板延期豁免均落在同一份类型化输入。
2. **P3 捕获基础与证据一致性（基础完成）**：`V20261030_01` 新增 `form_head_captures`，`V20261030_02` 新增 `form_publication_captures` 绑定；领取/捕获/结算按“范围 FOR SHARE → head FOR UPDATE”并复核 token、集合修订/哈希、pending、权威修订与租约；捕获绑定实际候选，最终公开事务内原子推进指针与 consumed；目录事件游标按发布版本唤醒 head；FORM 证据变化由消费端完整发布，周期只对同一已消费快照重算。R04 以事实修订作为“同一输入”证据：旧写回作业 `required_fact_version` 落后或表单 head 未消费即发送前跳过。
3. **P4 MR 逐记录投影**：`FormReviewProjector.build` 经目录生成逐记录 `form_review_fact` 并同事务发布；`form_records` CTE 读投影（汇总分钟/五类数/人员/最近创建时间）；质量分析按记录 sum/count；生产 MR 继续评论，走查 FORM 仅隔离验收。
4. **P5 共同字段与按版导出**：`selectCommonFields`/`aggregateMetric` 生产实现（2+3=5、不兼容显式无效）；导出端点、鉴权、审计、流式写出与预算；正式授予不做。
5. **P6 验证与交付**：代表性两版端到端、Issue 切换边界 T、双会话捕获/结算、导出拒绝矩阵；受影响类回归与一次隔离默认后端；护栏、双远端、清单送审。

## 三审整改项（回执 `992fe7dc`）

| 项 | 要求 | 状态 |
| --- | --- | --- |
| R01 主线依赖未吸收完整 | 吸收 `6aa9bd69`/`9e5e4bac` 规范状态成员合同；首次事实与周期同一判定；“待合并＋已有回复”为 false，“原因:设计如此”不是闭环 | 已完成：`91b21cbc` 修复；两处真实实现突变分别只打红对应反例，恢复后 14/14 绿（`.tmp/w4-n4-r01-20261030/red/`） |
| R02 未按持久权威分流 | T 只参与首次登记；消费按 `form_resource_authorities` 完整来源身份＋模板＋修订；响应/回复分别核验；缺失待登记、冲突拒绝；COMMENT 有表单仍评论，FORM 空仍空 | 已完成：`FormIssueInputResolver` 按权威读取并逐项核验，`FormIssueAuthorityRegistrar` 首次登记；PG 反例覆盖 T 前既有 FORM、COMMENT 有表单、身份冲突拒绝、缺失待登记 |
| R03 FORM 残留评论结果 | 分离基础来源与模板投影；原因取最新有效回复稳定 ID 集合；0/多选唯一性分层；空原因、作废清空、标签/模块规则保留；回复详情用类型化快照 | 已完成：响应/回复仅按采纳模板改写业务列；原因按版本 `field_spec` 映射稳定 ID，0/>1 判非法；回复详情进 `ReplySnapshot`；作废清空由 PG 断言 |
| R04 周期混用新输入与旧事实 | COMMENT 保留三列窄写；FORM 由捕获消费端完整发布；周期只重算同一已消费快照；head 未消费不混用；PG 断言改期、同标记业务变化、作废清空 | 已完成：未消费 head 先完整重建再发布，已消费仅按同一输入重算时间列；结算在发布成功后执行；旧事实修订作业发送前跳过（HTTP 0 次） |
| R05 共享环境越界 | 见上文“R05 共享环境改动证据与处置”；后续只绑独占环境，共享对象交负责人处置 | 证据已补，待负责人确认 |
| M01 迁移发布前置 | 主线已执行历史（含 `V20261022_03`）→合并版本（`.04`）真实升级验证；旧表单试验库清单与处置；不 repair、不改写历史 | 已完成：见下文“M01 升级验证与旧库处置”；`.03` 主线内容保持，旧编号库显式失败关闭 |

## 四审整改项（回执 `2cd57488`）

| 项 | 反例与要求 | 整改与验证 |
| --- | --- | --- |
| R02-a 持久权威仍可翻转 | 历史议题已按 COMMENT 消费，T 前移后被改成 FORM 空记录；写入口仍自行登记 FORM；要求首次登记冻结 COMMENT/FORM，写入口只核验已登记 FORM、缺登记拒绝，MR 未启用拒绝 FORM 新建 | `FormIssueAuthorityRegistrar` 只对“启用点后创建、ACTIVE、有当前版本且项目唯一覆盖”的议题首次登记 FORM，其余登记 COMMENT；已有权威/head 一律不改写。`FormRecordAuthorityService.ensureForWrite` 删除插入，缺权威抛 409。反例 `movingEnablePointEarlierDoesNotFlipHistoricalCommentAuthority`、`shouldRejectCreateWithoutPersistentFormAuthorityAndNeverSelfRegister` |
| R02-b 重复登记覆盖在途租约 | 重复登记把他人 `LEASED` 改回 `PENDING` 且保留 owner/fence；要求只核验、不重置状态/归属/租约，仅在缺失时初始化 | 登记只做 `on conflict do nothing`；已有权威/head 不更新状态与租约；已有 FORM 权威但缺 head 时按真实 `authority_revision` 与当前集合证据补建。反例 `repeatedRegistrationKeepsLeasedHeadAndConsumedWatermark` 断言 LEASED/fence/水位与 CONSUMED 均保持，`missingHeadIsInitializedFromPersistentFormAuthority` 断言缺失 head 补建值及再次登记不重置 |
| R03-a 豁免跨模板串用 | 响应 COMMENT 无合法计划、回复 FORM 为空时，旧评论回复仍把响应判为未延期；要求响应合法计划与回复存在性各取自身权威后再合并 | `IssueSlaRules.isResponsePlanExempt` 只认响应模板合法计划，回复存在性只按回复权威；`IssueBusinessProjector` 最后合并 `hasReply || responsePlanExempt`。反例 `commentResponseDoesNotBorrowFormEmptyReplyOrOldCommentReplyForExemption` 断言 `responseDelayed=true` |
| R04 周期提前结算并绕过捕获 | 候选仍 `PROJECTING`、指针未动，head 已 `latest=consumed=2`；要求捕获绑定实际候选、最终发布事务内复核并原子推进指针与 consumed，删除简化结算旁路 | 周期只 `claimHead`→`capture`→`bindToPublication(target.publicationId())`；`FactTargetPublicationService.publishPreparedInternal` 在指针公开后同事务调用 `settleForPublication`，逐项复核 head/集合/权威/pending/token/租约，漂移抛冲突回滚公开与结算；删除 `captureLatest/settleConsumed` 旁路。反例 `periodicRefreshSettlesOnlyInsideFinalPublication`（PROJECTING 不结算、最终公开同时推进指针与 consumed）、`driftAfterBuildKeepsPublicationAndConsumptionAtZero`（构建后漂移零公开零结算） |
| P3 事件游标与发送前证据守卫 | 补目录事件游标；作业与事实版本相同、仅表单 head 已推进时仍须 HTTP 0 | `FormDirectoryEventCursorService` 按事件单调推进游标并为该版本已落位资源唤醒 FORM head；`CustomerIssueDelayLabelWritebackWorkerService` 发送前检查 FORM 权威 head 未消费证据，命中则等待。反例 `directoryEventCursorWakesOnlyResourcesInPublishedVersion`、`shouldNotSendWhenOnlyFormEvidenceAdvancedAndFactVersionIsUnchanged` |
| M01 升级证据无效 | 旧库由合并集预装新表单结构且缺主线 `V20261019_01`；要求用真实主线迁移集建旧库、保留全部已发布迁移、验证低版本新增迁移执行 | 见“M01 升级验证与旧库处置（2026-10-31 修订）”：140 条主线真实执行 → 26 条 out-of-order 新增 → 前缀/描述/validate/守恒/可用性断言 |

## 用户裁定（2026-10-30，不再提供选择题）

- **MR 跨版口径 C：逐记录投影**。所有有效、可比较的走查记录参与统计，不取 `max`、不只留最新一条：两条记录时长 10/20 分钟→总时长 30、平均 15；规范问题 2/3→5。MR 事实仍按资源唯一一行；质量分析按记录 sum/count。否决 A（逐列 max）与 B（仅最新）。
- **导出角色：不授予五个种子角色**。隔离库五角色只是样本；当前授予与默认快照分别继承目标环境各自的独立导出权，查看权不得推导导出权。名单未取得前，只用隔离角色完成鉴权测试，不写正式授予迁移。
- **启用顺序**：Issue 按启用点 T 分流（新资源 FORM、历史 COMMENT）先启用；MR 生产继续评论，走查 FORM 仅在隔离范围验收，`T_mr` 未设时 MR 登记 COMMENT；非法页全旧、不混合。

## 已吸收主线依赖

- 延期写回修复：持久进度与领取围栏、恢复领取/租约/授权回滚、模板标题解析与在途停写保存（`17e65dcd`、`c594d529`、`7679764c`）。
- 迁移：主线 `V20261022_03__customer_issue_delay_closure_progress.sql` 随吸收进入；本分支退役权限迁移顺延为 `V20261022_04`；恢复的主线补丁 `V20261019_01` 与新增 `V20261030_01/.02` 一并登记，当前全部 166 条登记 checksum。
- 后续 Issue 周期 SLA 与写回资格复用该修复的队列、轮次、围栏与发送前复核，不建旁路。

## 风险、假设与敏感只读数据

- 真实 E2 未取得：不阻断隔离开发，但真实登记/切换与容量结论须等 E2；不得用小夹具外推容量。
- 其他 AI 并行改动：只在本 worktree 与独占容器/端口操作，不读写共享库与共享服务。
- 敏感只读：`platform_role_permissions`、`platform_default_role_permissions`、`gitlab_sync_configs` 仅只读核对，不写入。
- P2 边界：`issue_fact` 尚无 known/new affected、影响测试、遗留影响与关联表等回复详情列，本轮不新建列，也不把多选原因写入单值 `reason_category`；这些九项选择器的下游落地随统计/分析消费（P5）另行决定，避免先造列后改口径。
