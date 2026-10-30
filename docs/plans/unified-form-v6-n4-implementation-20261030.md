# 表单 v6 N4：多版本消费、共同字段统计、Issue 选择器与按版 Excel

> 状态（2026-10-30）：开工。批准依据 [N3 最终验收与 N4 准入](unified-form-system-implementation-plan-20261021.md#n4-admission)，技术合同 [N0 §5—§8](v6-n0-technical-admission-20261027.md#5-多版本消费r04q3)。N4 通过前不进入 N5，不运行黄金，不合 main、不出包、不操作真实业务库/正式权限/外部写回。

## 进度与中间物

- 阶段（2026-10-30）：P0 计划与影响面盘点；两个实施前门禁（MR 汇总公式、导出角色名单）待用户确认前，只做不依赖它们的部分。
- 已完成：快进吸收 N4 准入回执 `b18583f4`；本计划；影响面只读盘点（见下节）。
- 测试状态：本轮尚未开始实现，无新增测试；沿用 N1—N3 已关闭专项证据，不重复运行。
- 进行点：门禁材料整理与首批实现（统一权威读源接入）。
- 阻塞：MR 汇总公式不许在未批准时改变；`form.record.export` 正式授予迁移须等角色名单确认；真实 E2 未取得，继续阻断真实登记/切换，不阻断隔离开发。

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

## 步骤与依赖

1. **P0**：计划与影响面盘点；MR 汇总公式与导出角色两份门禁材料报审。
2. **P1 捕获协议**：`form_head_captures` 迁移；捕获/领取/结算服务与真实 PG 双会话探针；目录事件消费与游标；发布/编辑/作废唤醒 head。
3. **P2 统一读源**：`GitlabFactSourceSqlProvider` 的 `form_records` CTE 换成 reader 投影；替换 MR 四个入口与 Issue 入口；删除旧表专用路径（一次性替换，不双读）。
4. **P3 共同字段与 Issue 选择器**：`selectCommonFields`/`aggregateMetric` 生产实现与报告输出；九项选择器接入 Issue 事实。
5. **P4 按版导出**：端点、权限键与鉴权、审计表、流式写出与资源预算。
6. **P5 验证与交付**：代表性两版夹具端到端；受影响类回归；一次隔离默认后端、相关前端检查；护栏、双远端、清单与台账送审。

## 待审批项（不得自行决定）

**门禁盘点已完成，材料见 `.tmp/w4-n4-20261030/gate/README.md`。**

- MR 汇总：表单只影响走查人、走查时长、走查日期兜底；平均值/密度/五类计数的公式与分母不变。跨版取值推荐 A（对全部 AUTHORITATIVE 版本沿用 `max(...)`，存量输出不变），备选 B（按 `(createdAt, recordId)` 取最新整份，会改变现有数值）。**待用户裁定 A/B 前不动 MR 事实 SQL。**
- 导出角色：隔离库（完整迁移）只读结果为 `SUPER_ADMIN/ADMIN/DIRECT_MANAGER/TREE_MANAGER/NORMAL_USER` 五角色，当前授予与默认快照无差异；这是全新安装种子，目标库实际名单需指定环境做同一只读 SQL，**确认前不执行正式授予迁移**。

- **MR 汇总公式**：N0 §7.2 保持现行口径，若实现中发现必须改公式，先报审。
- **导出角色名单**：只从既有导出权复制，确认前可完成权限目录、鉴权与隔离测试，不写正式授予。

## 风险、假设与敏感只读数据

- 真实 E2 未取得：不阻断隔离开发，但真实登记/切换与容量结论须等 E2；不得用小夹具外推容量。
- 其他 AI 并行改动：只在本 worktree 与独占容器/端口操作，不读写共享库与共享服务。
- 敏感只读：`platform_role_permissions`、`platform_default_role_permissions`、`gitlab_sync_configs` 仅只读核对，不写入。
