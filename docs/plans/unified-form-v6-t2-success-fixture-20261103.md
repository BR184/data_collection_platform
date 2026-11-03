# 表单 v6 T2 成功样本与导出提案（2026-11-03）

> 状态：提案，未执行。**未改夹具/快照/目录/掩码，未运行 golden/update，未合并 main，未出包。**
> 目的：在不扩大生产语义的前提下，为黄金基线补齐最小可判定的表单成功路径与按版 Excel 导出样本。
> 本文件先交审核方，再交用户确认；批准后才进入快照生成与严格 compare。

## 一、现状与问题

1. 功能分支 HEAD 的 `golden-baseline/snapshots/` 下没有任何 `collect-forms/` 文件；main 的 8 份旧表单快照已在 N5 删除。
2. 端点目录已登记 20 个 `/api/collect-forms/**` 端点，其中 1 个为 `EXCLUDED`（导出），19 个会产生响应快照；当前 19 份响应快照全部缺失。
3. 10 个写用例共声明 16 份表状态快照，当前全部缺失。
4. `platform-seed.sql.gz` 不含任何表单表数据；`compatibility-completeness.sql.gz` 仅 380 字节，是兼容性清单元数据，不是业务行。表单成功样本必须在引导阶段单独构造。
5. 导出端点当前因“需用户确认”被排除；用户已要求把导出纳入 `EXPORT` 类别并复用既有 Excel 单元格规范化，因此需要可读版本与有效记录。

## 二、最小成功夹具

### 2.1 资源与身份（全部固定）

| 项 | 取值 | 依据 |
| --- | --- | --- |
| 模板 | `issue_response` | 无人员目录依赖；字段校验可完全固定 |
| GitLab 资源 | `projectId=9`、`resourceType=issue`、`resourceId=969`（全局 id `1228`） | 源切片 `issues` 行：id 1228、iid 969、project 9、state_id 2、无 labels |
| 项目 | `cloudcad/crowncad`（namespace 4） | 源切片 `projects`/`namespaces` |
| Web 基址 | `http://gitlab.test.local:18080` | 黄金测试 `platform.gitlab-mirror.web-base-url` 固定值 |
| 范围 | `form_template_scopes.id=1`，`scope_key='golden-issue-response'`，`status='ACTIVE'` | 空库首行，显式 ID |
| 版本 | `form_template_versions.id=1`，`version_label='CC2026R3'`，`storage_kind='RECORDS'`，`record_source_role='AUTHORITATIVE'`，`instance_mode='MATERIALIZED'`，`version_status='PUBLISHED'` | `ck_ftv_instance_shape` |
| 物理表 | `form_s1_v1_instances`、`form_s1_v1_records` | `FormVersionSchemaService` 命名 |
| 权威 | `form_resource_authorities`：`(configId, 'default', project 9, 'issue', 969, 'issue_response') = FORM`，revision 1 | 写入口 `FormRecordAuthorityService` 要求 |
| 目录 | `form_record_locations.record_id=1`，`instance_id=1`，MATERIALIZED | 详情/导出/编辑/作废共用 |
| 基线记录 | 1 条有效记录，`record_version=1`，`created_by='admin'` | 用于详情、编辑、作废、导出 |
| 基线时间 | `2026-09-01 09:00:00`（创建）；`2026-09-01 08:00:00`（发布时间） | 黄金 `frozenClock`/`frozenBusinessDate` |

`projectId=9` 的 `resourceId=969` 在 `ods_gitlab_issues` 中存在，`order by source_order` 首行即可解析 `root_id=1228`；`issue_fact` 是否有该模板议题不影响 ODS 回退。该议题无 labels，`severityLevel` 为 `未设定`，因此 `owner_confirmation` 非必填且必须为空。

### 2.2 夹具构造步骤（建议放入 `GoldenBaselineSupport`）

在 `GoldenBaselineChainTest.bootstrapChain()` 的全量同步、镜像发布、数据集发布完成之后，`captureWriteReplayBaselines()` 之前执行：

1. 先暂停表单后台消费者，避免基线/写用例的 `form_change_heads` 被领取：
   - `pauseScheduledProducer(FormPublicationWorker.class)`；
   - `pauseScheduledProducer(FormDirectoryEventWorker.class)`；
   - 两处都沿用现有断言，取消数为 0 时硬失败。
2. 记录并临时关闭主来源的写回/同步副作用：
   - 读取 `gitlab_sync_configs.id=1` 的 `enabled`、`source_enabled`；
   - 置 `enabled=false`、`source_enabled=false`；
   - 置 `web_base_url='http://gitlab.test.local:18080'`；
   - 请求结束、基线头行清理后按原值恢复；`web_base_url` 保留固定值（见 2.4）。
3. 用 `FormTemplateSpecCatalog.rawJson("issue_response")` 作为冻结 `field_spec`，按外键与 ACTIVE 约束顺序插入范围与版本；用 `FormScopeBinding.create/hash` 计算并写入 `binding_hash`：
   - 先插 `status='DRAFT'`、`current_version_id=null` 的范围行（其余字段：`source_config_id=configId`、`source_instance='default'`、`canonical_gitlab_base_url='http://gitlab.test.local:18080'`、`project_ids='[9]'`、`version_project_id=9`、`source_set_revision=1`）；
   - 再插版本行（`id=1`、`published_at='2026-09-01 08:00:00'`、`version_label='CC2026R3'`、`field_spec` 为上述原文）；
   - 最后把范围更新为 `status='ACTIVE'`、`current_version_id=1`，并执行 `update form_template_scopes set source_set_hash = form_source_set_hash(1) where id = 1`；
   - 不能在版本行存在前把范围置为 ACTIVE 或写入 `current_version_id=1`，否则外键/ACTIVE 检查失败。
4. 调用 `FormVersionSchemaService.generate(1, 1, spec, false)`，按返回语句顺序执行实例表、记录表、索引 DDL；不手写 DDL。
5. 插入 FORM 权威行；`cutover_manifest_id` 为空，`effective_at='2026-09-01 08:00:00'`，`authority_revision=1`。
6. 通过真实新增端点生成基线记录（不手写记录/目录/审计/哈希）：
   - `POST /api/collect-forms/records`
   - `scopeId="1"`、`storageVersionId="1"`、`templateKey="issue_response"`、`gitlabBaseUrl` 同上、`projectId=9`、`resourceType="issue"`、`resourceId="969"`；
   - `requestKey="00000000-0000-4000-8000-000000000001"`；
   - `fields`：见 2.3。
   - 断言 HTTP 200、`data.status=CREATED`、`data.record.id="1"`；随后核对实例 1 行、记录 1 行、目录 1 行、CREATE 审计 1 行。
7. 清理基线副作用，使其不进入任何读快照：
   - `delete from form_change_heads where ... template_key='issue_response' and resource_id='969'`；
   - `delete from fact_change_heads where source_instance='default' and fact_type='ISSUE' and root_id=1228`；
   - 恢复第 2 步记录的 `enabled`/`source_enabled`。
8. 校准序列，保证后续成功用例 ID 固定：
   - `select setval('form_record_id_seq', 1, true)`（下一次 nextval=2）；
   - `select setval('form_template_scopes_id_seq', 1, true)`；
   - `select setval('form_template_versions_id_seq', 1, true)`；
   - 审计/实例表序列由写用例还原器按表最大值校准（见 5.4）。
9. 自检：`FormScopeValidationService.validateActiveScopes()` 必须通过；`select form_source_set_hash(1)` 必须等于 1 号范围的 `source_set_hash`；基线记录必须能通过 `FormRecordDirectoryReader.find(1)` 回读。

### 2.3 基线字段值

| 字段 | 值 |
| --- | --- |
| `problemType` | `DEFECT` |
| `problemCause` | `黄金基线固定问题原因` |
| `modificationPlan` | `黄金基线固定修改方案` |
| `ownerConfirmation` | 省略或 `null`（严重程度未设定，必须为空） |
| `plannedResolutionDate` | `2026-09-30` |
| `plannedMergeVersionBranch` | `CC2026R3` |
| `supplementNote` | `黄金基线样本` |

对应快照列：`owner_confirmation_applicable=false`；`created_at/updated_at='2026-09-01 09:00:00'`；`record_version=1`；`deleted=false`；`legacy_incomplete=false`。

### 2.4 为什么需要 `web_base_url` 与临时关闭来源

- `FormRecordContextService.validateSource` 要求 `gitlab_sync_configs.web_base_url` 与范围 `canonical_gitlab_base_url` 完全一致；当前 `seedSyncConfig` 不写该列，所以必须先补固定值。
- 成功写会通过 `FormIssueChangeBridge` 在事务提交后提交一次 `FACT_REFRESH`。若不抑制，黄金套件会在写用例进行中启动新的同步运行，污染 `sync_runs`、事实与读发布状态。临时把 `enabled`/`source_enabled` 置 false 只影响该次测试请求后的桥接判定，不改变表单写事务本身；请求 `finally` 恢复原值。
- 该抑制**只用于黄金成功写用例**，不代表生产关闭事实刷新；真实桥接由 `FormIssueChangeBridge` 的独立集成测试覆盖。实现上给对应 `WriteCase` 增加 `suppress-fact-refresh: true`，执行器在请求前读取并保存该来源的 `enabled/source_enabled`，置 false，`finally` 恢复原值；未声明该标志的写用例不改变来源状态。

## 三、用例提案

### 3.1 读/导出用例

| 端点 | 用例 id | 请求 | 预期 |
| --- | --- | --- | --- |
| `GET /api/collect-forms/detail` | `missing-scope`（已有） | 保留现有查询 | 400 业务拒绝，快照锁定 |
| `GET /api/collect-forms/detail` | `golden-record`（新增） | `gitlabBaseUrl=http://gitlab.test.local:18080&projectId=9&resourceType=issue&resourceId=969&templateKey=issue_response` | 200；当前版本 1；1 条记录；`writable=true`；能力位由 admin 权限决定 |
| `GET /api/collect-forms/exports/records` | `golden-version`（新增，`EXPORT`） | `versionId=1&projectIds=9` | 200；xlsx；规范化单元格见 3.3 |
| `GET /api/collect-forms/notification-payload` | `unregistered-source`（已有） | 保留现有查询 | 400 业务拒绝 |
| `GET /api/collect-forms/notification-payload` | `registered-issue`（新增） | `gitlabBaseUrl=http://gitlab.test.local:18080&projectId=9&requestIid=969&resourceType=issue` | 200；仅四字段 `gitlabBaseUrl/projectId/requestIid/resourceType` |
| `GET /api/collect-forms/reviewer-candidates` | `default`（已有） | `limit=20` | 200；空目录或固定候选；不新增人员夹具 |
| `GET /api/collect-forms/templates` | `default`（已有） | — | 200；三模板定义 |
| `GET /api/collect-forms/template-scopes/{scopeId}` | `missing-scope`（已有） | `scopeId=999999999` | 400 |
| `GET /api/collect-forms/publications/{publicationId}` | `missing-publication`（已有） | `publicationId=999999999` | 404/400 业务拒绝（按现有实现） |
| `GET /api/collect-forms/binding-options` | `default`（已有） | — | 200；来源 web 基址为固定值 |
| `GET /api/collect-forms/version-milestones` | `unknown-source`（已有） | `sourceConfigId=999999999&projectId=1` | 400 |
| `GET /api/collect-forms/admin/projects` | `unknown-source`（已有） | `sourceConfigId=999999999` | 400 |

### 3.2 写用例

所有成功写用例均使用同一资源与基线记录；执行前还原受影响表到冻结基线，执行后再次还原。

| 端点 | 用例 id | 请求要点 | 预期响应 |
| --- | --- | --- | --- |
| `POST /api/collect-forms/records` | `golden-create`（新增） | `requestKey="00000000-0000-4000-8000-000000000002"`；`fields` 使用 3.2.1 新增值 | 200；`status=CREATED`；记录 id 2、版本 1 |
| `PUT /api/collect-forms/records/{id}` | `golden-update`（新增） | `id=1`；`recordVersion=1`；`storageVersionId="1"`；`expectedStorageVersionId="1"`；字段改 3.2.2 | 200；“表单记录已更新”；版本 2 |
| `POST /api/collect-forms/records/{id}/void` | `golden-void`（新增） | `id=1`；`recordVersion=1`；`expectedStorageVersionId="1"`；`confirmVoid=true`；`confirmationText="作废"` | 200；“表单记录已作废”；`deleted=true`、版本 2 |
| 其余 7 个写用例 | 保留现有 `missing-scope`/`unknown-template`/`missing-publication` | 不变 | 现有确定性拒绝 |

3.2.1 新增字段值：

| 字段 | 值 |
| --- | --- |
| `problemType` | `REQUIREMENT` |
| `problemCause` | `新增样本问题原因` |
| `modificationPlan` | `新增样本修改方案` |
| `ownerConfirmation` | 省略 |
| `plannedResolutionDate` | `2026-10-15` |
| `plannedMergeVersionBranch` | `CC2026R3` |
| `supplementNote` | `新增样本` |

3.2.2 编辑字段值：仅将 `problemCause` 改为 `更新后的问题原因`、`supplementNote` 改为 `更新后补充`，其余与基线一致。

### 3.3 导出工作簿预期

- 文件名：`issue_response-records-1.xlsx`；工作表名：`CC2026R3`。
- 系统列 A—J：记录ID、GitLab地址、项目、资源类型、资源ID、创建人、创建时间、记录修订、结构版本、状态。
- 业务列 K—Q：问题类型、问题原因、修改方案、模块负责人确认、计划解决时间、计划合并的版本分支、补充说明。
- 基线行：`1`、`http://gitlab.test.local:18080`、`9`、`issue`、`969`、`管理员`、`2026-09-01 09:00:00`、`1`、`CC2026R3`、`有效`、`DEFECT`、`黄金基线固定问题原因`、`黄金基线固定修改方案`、空、`2026-09-30`、`CC2026R3`、`黄金基线样本`。
- 导出的是冻结列原值而非中文枚举标签，`problemType` 单元格必须是 `DEFECT`。
- 规范化复用 `GoldenSnapshotSupport.assertExportSnapshot → normalizeExcel`，不新增 Excel 规范化分支。

### 3.4 复用现有测试、不重复造样本

| 行为 | 已覆盖测试 |
| --- | --- |
| 版本/项目/目录 C 三重谓词、作废排除、空版本表头、导出审计 | `FormRecordExportIntegrationTest#exportHonorsVersionProjectAndVoidPredicatesAndWritesAudit`、`#exportUsesFrozenVersionProjectsAfterScopeRebind`、`#exportRejectsForeignProjectAndUnreadableVersion`、`#exportLimitsAreExplicitAndEmptyVersionStillHasHeader` |
| 新建/编辑/作废、并发、重放、回滚、权限 | `FormRecordServiceIntegrationTest` |
| 三模板选择、发布/切换、退役、候选 | `FormTemplateAdminIntegrationTest` |
| 旧 COMMENT 权威保持不变 | 现有客户问题/评审事实测试；本夹具只新增 1228 的 FORM 行，不更新任何已有 COMMENT 行或 `form_authority_cutover_*` |

## 四、账号与测试权限

- 黄金登录账号为 `admin`，本地认证映射 `SUPER_ADMIN`。迁移已使 SUPER_ADMIN 获得 `form.record.create/edit_own/delete_own`、`form.template.manage`。
- `form.record.export` 按 `V20261102_03` 只登记、不授予任何角色。为覆盖真实权限门禁，导出用例执行前后临时授予/回收，不写迁移、不改生产授权：
  1. 执行前查询 `select count(*) from platform_role_permissions where role_code='SUPER_ADMIN' and permission_code='form.record.export'`；
  2. 计数为 0 时插入该行，并在 `finally` 删除；计数为 1 时不插入也不删除；
  3. 临时授予只包围导出请求，权限设置页快照不受影响。
- 若审核方不接受测试期临时授权，备选方案是把导出用例声明为预期 403；但这样无法产出“有值的按版 Excel”，不满足本轮 T2 目标。
- 正式环境 `form.record.export` 的授予名单仍由权限管理员提供，属于外部启用前置，不在本提案内。

## 五、副作用、快照与恢复

### 5.1 成功写用例的表快照

每个成功写用例声明以下受影响表（默认 `REPLAY`，基线在夹具完成后捕获）：

| 表 | 新建 | 编辑 | 作废 | 说明/掩码 |
| --- | --- | --- | --- | --- |
| `form_s1_v1_instances` | 1→1 | 1→1 | 1→1 | 只锁身份，不新增实例 |
| `form_s1_v1_records` | 1→2 | 1 行改版本 2 | 1 行置作废、版本 2 | 全列严格 |
| `form_record_locations` | 1→2 | 1→1 | 1→1 | 全列严格 |
| `form_record_audit_logs` | 1→2 | 1→2 | 1→2 | `ignore-columns: [client_ip, user_agent]` |
| `form_change_heads` | 0→1 | 0→1 | 0→1 | 全列严格；消费者已暂停 |
| `fact_change_heads` | 0→1 | 0→1 | 0→1 | `ignore-columns: [updated_at]`；`latest_change_version` 由序列校准保持严格 |

新增 18 份表快照（3 个用例 × 6 表）。导出用例另产生 1 行 `form_record_export_logs`；该表不进入黄金表快照（xlsx 压缩包摘要/字节数受工作簿元数据影响），由现有导出集成测试断言审计字段。

### 5.2 序列与非确定性处理

- 写用例还原器新增两类校准（只影响测试引擎，不改生产代码）：
  - 还原任一 `form_s*_records` 表后，`form_record_id_seq` 设为 `max(id)+1, is_called=false`；
  - 还原 `fact_change_heads` 后，`fact_change_version_seq` 设为 `max(latest_change_version)+1, is_called=false`。
- `fact_change_heads.updated_at` 由 SQL `current_timestamp` 产生，掩码该列；其余时间列使用黄金固定时钟，保持严格。
- 审计的 `client_ip`/`user_agent` 属请求环境元数据，掩码；`snapshot_json`、`operator_name`、`created_at` 保持严格。
- 黄金 HTTP 会话可选统一 `User-Agent: golden-baseline`；若采用，则只需掩码 `client_ip`。两种方案二选一，生成前固定。

### 5.3 完整副作用清单

| 副作用 | 产生方式 | 恢复 |
| --- | --- | --- |
| 新增/更新记录行 | 真实写事务 | 写用例前后 `REPLAY` 还原 |
| 新增目录行 | `writeForCreatedRecord` | 同上 |
| CREATE/UPDATE/VOID 审计 | `writeAudit` | 同上 |
| 表单 head 变更 | `bumpHead` | 同上 |
| 事实 head 变更 | `FormIssueChangeBridge.record` | 同上 |
| 事实刷新运行 | AFTER_COMMIT 提交 | 成功写用例临时关闭来源后不产生；基线 created 后清理 |
| 导出审计行 | `FormRecordExportService.export` | 新库每次运行自增；生产不适用 |
| 权限临时授予 | 导出用例 | `finally` 精确回收 |
| 表/函数/视图 | 迁移 | 不回退；回滚走数据库备份 |

### 5.4 统计增量预期

成功写只产生 `form_change_heads`、`fact_change_heads` 和审计，不发布 `form_review_fact`，也不触发投影/发布消费者；因此本夹具对 BI、质量看板、客户问题统计的增量预期为 **0**。若生成后出现统计快照变化，视为实现或夹具隔离问题，先停止排查，不进入 update。

### 5.5 恢复与可重复性

- 黄金套件每次新建 Testcontainers 数据库；夹具失败时整库丢弃，无需人工清理。
- 同一库重复执行夹具必须显式失败（范围/版本/记录主键已存在），不静默覆盖。
- 写用例失败时 `finally` 仍需还原表、恢复来源开关、回收临时权限；任何一步失败都作为测试失败传播。

## 六、缺失快照清单

### 6.1 现有 19 份响应快照（全部缺失）

| # | 快照文件 |
| ---: | --- |
| 1 | `collect-forms/get___detail__missing-scope.json` |
| 2 | `collect-forms/get___notification-payload__unregistered-source.json` |
| 3 | `collect-forms/get___reviewer-candidates__default.json` |
| 4 | `collect-forms/post___records__missing-scope.json` |
| 5 | `collect-forms/put___records_{id}__missing-scope.json` |
| 6 | `collect-forms/post___records_{id}_void__missing-scope.json` |
| 7 | `collect-forms/get___templates__default.json` |
| 8 | `collect-forms/get___template-scopes_{scopeId}__missing-scope.json` |
| 9 | `collect-forms/post___template-scopes__unknown-template.json` |
| 10 | `collect-forms/put___template-scopes_{scopeId}_draft__missing-scope.json` |
| 11 | `collect-forms/post___template-scopes_{scopeId}_preview__missing-scope.json` |
| 12 | `collect-forms/post___template-scopes_{scopeId}_publications__missing-scope.json` |
| 13 | `collect-forms/post___template-scopes_{scopeId}_retire__missing-scope.json` |
| 14 | `collect-forms/delete___template-scopes_{scopeId}__missing-scope.json` |
| 15 | `collect-forms/get___publications_{publicationId}__missing-publication.json` |
| 16 | `collect-forms/post___publications_{publicationId}_recover__missing-publication.json` |
| 17 | `collect-forms/get___binding-options__default.json` |
| 18 | `collect-forms/get___version-milestones__unknown-source.json` |
| 19 | `collect-forms/get___admin_projects__unknown-source.json` |

### 6.2 现有 16 份表快照（全部缺失）

| # | 快照文件 |
| ---: | --- |
| 1 | `collect-forms/post___records__missing-scope__form_record_audit_logs.json` |
| 2 | `collect-forms/post___records__missing-scope__form_change_heads.json` |
| 3 | `collect-forms/put___records_{id}__missing-scope__form_record_audit_logs.json` |
| 4 | `collect-forms/put___records_{id}__missing-scope__form_change_heads.json` |
| 5 | `collect-forms/post___records_{id}_void__missing-scope__form_record_audit_logs.json` |
| 6 | `collect-forms/post___records_{id}_void__missing-scope__form_change_heads.json` |
| 7 | `collect-forms/post___template-scopes__unknown-template__form_template_scopes.json` |
| 8 | `collect-forms/post___template-scopes__unknown-template__form_template_scope_drafts.json` |
| 9 | `collect-forms/put___template-scopes_{scopeId}_draft__missing-scope__form_template_scope_drafts.json` |
| 10 | `collect-forms/post___template-scopes_{scopeId}_preview__missing-scope__form_template_preview_evidence.json` |
| 11 | `collect-forms/post___template-scopes_{scopeId}_publications__missing-scope__form_template_publications.json` |
| 12 | `collect-forms/post___template-scopes_{scopeId}_publications__missing-scope__form_template_versions.json` |
| 13 | `collect-forms/post___template-scopes_{scopeId}_retire__missing-scope__form_template_scopes.json` |
| 14 | `collect-forms/delete___template-scopes_{scopeId}__missing-scope__form_template_scopes.json` |
| 15 | `collect-forms/delete___template-scopes_{scopeId}__missing-scope__form_template_scope_drafts.json` |
| 16 | `collect-forms/post___publications_{publicationId}_recover__missing-publication__form_template_publications.json` |

### 6.3 新增用例带来的快照

- 响应快照 +6：`detail/golden-record`、`exports/records/golden-version`、`notification-payload/registered-issue`、`records/golden-create`、`records/{id}/golden-update`、`records/{id}/void/golden-void`。
- 表快照 +18：三个成功写用例各 6 张（见 5.1）。
- 合计：响应 25、表 34。生成前必须由用户确认这 59 份新快照。

## 七、边界与确认项

1. 本提案不修改 `compatibility-completeness.sql.gz`；该文件只承载兼容性清单元数据，业务行由夹具另行构造。
2. 本提案不触碰旧非法页源码、旧 COMMENT 值或 `form_authority_cutover_*`；夹具插入前断言 1228/issue_response 无既有权威行。
3. 临时关闭来源只用于黄金成功写用例；不写生产配置、不改迁移默认值。
4. 导出权限只做测试期临时授予；正式授权名单仍由权限管理员提供。
5. 59 份新快照、夹具辅助代码、写用例字段、序列校准和临时权限钩子都属夹具变更，须先经审核方、再经用户确认；确认后先 `-Dgolden.update=true` 生成并逐份审阅 diff，再严格 compare。
6. 首次严格 compare 可能先暴露响应断言差异；用例内响应断言失败时不会继续执行同一用例的表快照断言，因此不能保证一次 compare 收齐后续表差异。必须按“生成 → 审阅 → 严格 compare → 修复 → 再 compare”收敛。
7. 未执行事项：golden/update、夹具/快照/掩码修改、main 合入、正式出包、升级、真实授权、生产启用。