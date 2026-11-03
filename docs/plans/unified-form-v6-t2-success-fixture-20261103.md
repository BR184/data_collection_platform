# 表单 v6 T2 成功样本与导出提案（2026-11-03，修订一）

> 状态：吸收审核回执 `4812dbec` 后重写，**替换上一版“删除 head、关闭来源”的夹具设计**。本文件只交审核方与用户确认，未执行：未改夹具/快照/目录/掩码，未运行 golden/update，未合并 main，未出包，未授予真实权限。
> 路径：真实创建 → 原 FACT_REFRESH/事实/投影真实消费 → 暂停调度 → 命令用例保留真实入队 → 按完整副作用还原。

## 一、现行事实与数量口径

1. 功能分支 HEAD 的 `golden-baseline/snapshots/` 下没有 `collect-forms/` 文件；main 的 8 份旧表单快照已在 N5 删除。
2. 端点目录登记 20 个 `/api/collect-forms/**` 端点：1 个 `EXCLUDED`（旧导出定义已改由新导出端点承担）、19 个响应快照，当前全部缺失。
3. 10 个既有写用例声明 16 份表状态快照，当前全部缺失。
4. `platform-seed.sql.gz` 不含表单业务行；表单成功基线必须在引导阶段用真实 API 构造，不能写种子。
5. 上一版“59 份”按 25 响应 + 34 表计算，且依赖删除 head/关闭来源；本版按真实副作用重算为 **62 份（25 响应 + 37 表）**，详见第六节。

## 二、单一路径（审核指定）

### 2.1 来源种子固定 Web 地址

在 `GoldenBaselineSupport.seedSyncConfig()` 的 INSERT 中增加 `web_base_url`，值为 `http://gitlab.test.local:18080`（与 `FormSourceUrlSupport.canonicalize` 及范围 `canonical_gitlab_base_url` 完全一致）。**保持来源 `enabled=true`、`source_enabled=true`，不关闭任何刷新。**

这是种子值而非测试期临时修改，下列既有快照会因此有意变化，生成前需一并批准：

| 既有快照 | 预期变化 |
| --- | --- |
| `gitlab-sync/get___configs__default.json` | `data[0].webBaseUrl`：`null` → `http://gitlab.test.local:18080` |
| `gitlab-sync/get___status__by-config.json` | `config.webBaseUrl` 同上；`logs` 增加基线 FACT_REFRESH 运行 |
| `gitlab-sync/put___config__golden-update.json` | 响应 `webBaseUrl` 同上；受影响表基线含该列 |
| `gitlab-sync/get___source-health__by-config.json` | `latestLogStatus/latestLogMessage` 指向基线 FACT_REFRESH；`latestLogFinishedAt` 已在目录掩码 |
| `facts/get___build-tasks_latest__default.json` | 最新任务变为基线增量 ISSUE 任务（`full=false`、`affectedRows=1`），时间列已掩码 |
| `database-browser/get___tables__default.json` | 首次用本分支代码生成时新增 25 张 `form_*` 只读登记；属 N5 代码既有差异，不是夹具新增数据 |

### 2.2 基线夹具构造（真实 API 之前）

在 `bootstrapChain()` 的全量同步、镜像/数据集发布完成后、`captureWriteReplayBaselines()` 之前执行：

1. 插入范围（1 行，显式 `id=1`）：
   ```sql
   insert into form_template_scopes(
     id, template_key, scope_key, source_config_id, source_instance, canonical_gitlab_base_url,
     project_ids, version_project_id, resource_type, status, updated_at)
   values (1,'issue_response','golden-issue-response',1,'default',
     'http://gitlab.test.local:18080','[9]',9,'issue','DRAFT','2026-09-01 08:00:00');
   ```
2. 插入版本（1 行，显式 `id=1`）：`scope_id=1`、`version_label='CC2026R3'`、`storage_kind='RECORDS'`、`instance_table='form_s1_v1_instances'`、`record_table='form_s1_v1_records'`、`field_spec=FormTemplateSpecCatalog.rawJson("issue_response")`、`version_status='PUBLISHED'`、`published_at='2026-09-01 08:00:00'`、`instance_mode='MATERIALIZED'`、`record_source_role='AUTHORITATIVE'`。
3. 将范围置为 `ACTIVE` 并写指针/绑定（同一行）：
   ```sql
   update form_template_scopes
      set status='ACTIVE', current_version_id=1,
          source_set_revision=1,
          binding_revision=1, binding_hash=<FormScopeBinding.create/hash>,
          updated_at='2026-09-01 08:00:00'
    where id=1;
   update form_template_scopes set source_set_hash=form_source_set_hash(1) where id=1;
   ```
   顺序必须是 DRAFT → 版本 → ACTIVE；版本行存在前写 `current_version_id` 会触发外键/ACTIVE 检查。
4. 调用 `FormVersionSchemaService.generate(1, 1, spec, false)` 执行返回的 DDL，生成 2 张物理表（`form_s1_v1_instances`、`form_s1_v1_records`）及索引；不手写 DDL。
5. 插入 FORM 权威（1 行）：`(source_config_id=1, source_instance='default', canonical_gitlab_base_url='http://gitlab.test.local:18080', project_id=9, resource_type='issue', resource_id=969, template_key='issue_response', scope_id=1, authority='FORM', authority_revision=1, cutover_manifest_id=null, effective_at='2026-09-01 08:00:00')`。
6. 运行自检：`FormScopeValidationService.validateActiveScopes()` 通过；`form_source_set_hash(1)` 等于范围值；`selected 1` 行范围、1 行版本、1 行权威。

**准确行数（基线发布前）**：范围 1、版本 1、权威 1、物理表 2（0 行）；无记录/目录/审计/head。

### 2.3 真实创建与消费（不禁用来源、不删 head）

1. 用黄金会话调用真实新增端点：
   `POST /api/collect-forms/records`，body：
   ```json
   {
     "scopeId": "1", "storageVersionId": "1", "templateKey": "issue_response",
     "gitlabBaseUrl": "http://gitlab.test.local:18080", "projectId": 9,
     "resourceType": "issue", "resourceId": "969",
     "requestKey": "00000000-0000-4000-8000-000000000001",
     "fields": {
       "problemType": "DEFECT",
       "problemCause": "黄金基线固定问题原因",
       "modificationPlan": "黄金基线固定修改方案",
       "plannedResolutionDate": "2026-09-30",
       "plannedMergeVersionBranch": "CC2026R3",
       "supplementNote": "黄金基线样本"
     }
   }
   ```
   预期 HTTP 200、`data.status=CREATED`、`data.record.id=1`；写事务新增实例 1、记录 1、目录 1、CREATE 审计 1、`form_change_heads` 1（latest=1、PENDING）。`ownerConfirmation` 省略（该议题无 labels，严重程度未设定，条件必填必须为空）。
2. 提交后 `FormIssueChangeBridge.onFormWrite` 真实执行：`fact_change_heads` 新增 1228/ISSUE 1 行；`SyncRunSubmissionService.submitFactRefresh(config, false, "表单记录变化触发事实刷新")` 真实插入 `sync_runs` 1 行（QUEUED、FACT_REFRESH、fullBuild=false）。调度器/事实 worker/投影 worker 此时仍保持运行。
3. 等待真实消费收敛（每一步失败即停止，不进入快照）：
   ```sql
   -- 无活动运行
   select count(*) from sync_runs where status in ('QUEUED','RUNNING','PAUSED','RETRYING');
   -- form head 已消费
   select status, latest_change_version, consumed_change_version
     from form_change_heads
    where source_config_id=1 and project_id=9 and resource_type='issue' and resource_id=969
      and template_key='issue_response';
   -- 捕获已结算
   select c.status, c.latest_change_version, c.consumed_change_version
     from form_head_captures c
     join form_change_heads h on h.id=c.head_id
    where h.source_instance='default' and h.project_id=9 and h.resource_id=969
      and h.template_key='issue_response';
   -- 事实带同一 FORM 签名与真实计划字段
   select planned_resolution_at, planned_resolution_text, form_input_hash, deleted
     from issue_fact where source_instance='default' and issue_id=1228;
   -- ISSUE 域已完整公开且无在途候选
   select p.state from read_publication_domains d
     join read_publications p on p.id=d.current_publication_id
    where d.producer_kind='GITLAB_FACT' and d.source_instance='default' and d.fact_type='ISSUE';
   select count(*) from read_publications p join read_publication_domains d on d.id=p.domain_id
    where d.producer_kind='GITLAB_FACT' and d.fact_type='ISSUE'
      and p.state in ('BUILDING','PROJECTING','READY_TO_PUBLISH');
   -- 延期闭包轮次已结算（写回开关关闭时只重算事实）
   select stage from customer_issue_delay_closure_requests;
   select count(*) from customer_issue_delay_label_writeback_jobs where status in ('PENDING','RUNNING');
   ```
   期望：活动运行 0；head `CONSUMED` 且 `consumed_change_version=latest_change_version`；capture `CONSUMED`；`issue_fact.planned_resolution_at='2026-09-30 00:00:00'`、`planned_resolution_text='CC2026R3'`、`form_input_hash` 为 64 位、`deleted=false`；ISSUE 域 `PUBLISHED` 且在途候选 0；闭包 `IDLE`、写回任务 0。
4. 签名一致性：在测试包 `com.data.collection.platform.service` 增加探针，调用同包 `FormIssueBuildInputService.current("default",9,969)`，断言 `status=OK` 且 `hash()` 等于上一步 `issue_fact.form_input_hash`。这样用生产同一份冻结输入证明“事实绑定同一 FORM 签名”，不手写哈希。
5. 记录实际行数：基线记录/实例/目录/审计各 1，`form_change_heads` 1，`form_head_captures` 1，`form_publication_captures` 1，`fact_change_heads` 1，基线 FACT_REFRESH 运行 ≥1（真实桥接触发），`fact_build_tasks` 至少 1 条 ISSUE 增量任务，`issue_fact` 1228 行更新为带表单签名版本。允许真实链路产生额外的闭环/旁路公开行；数值以生成前实际查询为准，不写死。

### 2.4 收敛后暂停调度（不改变来源开关）

依次暂停并断言取消数为正（沿用 `GoldenBaselineSupport.pauseScheduledProducer`）：

- `SyncRunDispatcherService`（不再领取命令写产生的 QUEUED 运行）
- `FactRefreshTaskWorkerService`（不再执行事实任务）
- `FactProjectionTaskRecoveryScheduler`（不再领取/发布独立投影候选）
- `FormPublicationWorker`、`FormDirectoryEventWorker`（不再处理命令写产生的表单事件）
- `CustomerIssueDelayClosureScheduler`（避免周期闭包轮次在命令用例期间重算事实）

暂停发生在基线收敛之后，因此基线是“表单已保存且事实已消费”的真实状态，不再出现“表单已保存、事实仍旧”。

### 2.5 命令用例：保留真实 head 与 AFTER_COMMIT 入队

写入口与 `FormIssueChangeBridge` 均不旁路、不新增 `suppress-fact-refresh`：

| 端点 | 用例 | 请求要点 | 真实副作用 | 预期响应 |
| --- | --- | --- | --- | --- |
| `POST /records` | `golden-create` | `requestKey=…0002`，字段取 3.2.1 | 记录 id=2、实例复用、目录 1、CREATE 审计 1；`form_change_heads.latest=2`；`fact_change_heads.latest=2`；`sync_runs` 新增 1 行 QUEUED FACT_REFRESH | 200、`CREATED`、记录 id=2、记录版本 1 |
| `PUT /records/{id}` | `golden-update` | `id=1`、`recordVersion=1`、`storageVersionId='1'`、`expectedStorageVersionId='1'`，字段取 3.2.2 | 记录版本 2、UPDATE 审计 1；两 head 各 +1；`sync_runs` 新增 1 行 QUEUED | 200、“表单记录已更新”、版本 2 |
| `POST /records/{id}/void` | `golden-void` | `id=1`、`recordVersion=1`、`expectedStorageVersionId='1'`、`confirmVoid=true`、`confirmationText='作废'` | 记录 `deleted=true`、版本 2、VOID 审计 1；两 head 各 +1；`sync_runs` 新增 1 行 QUEUED | 200、“表单记录已作废” |

- 每次用例前 `REPLAY` 还原到基线，因此上述“新增 1 行”在三个用例中独立成立；若来源仍有活动运行，`submitRun` 会复用而不是新增——暂停后基线无活动运行，复用路径不应出现；若出现即停止排查夹具顺序。
- `SyncRunSubmissionService.submitRun` 在本场景只插入 `sync_runs` 一行；`pg_advisory_xact_lock` 随事务释放，不产生持久行，不写 `sync_run_events`/`sync_run_table_states`/任务表。命令用例不产生事实任务、投影或公开指针变化；`sync_runs` 保持 `QUEUED` 是预期，不是“队列清空失败”。
- 字段值：新增 `problemType=REQUIREMENT`、`problemCause=新增样本问题原因`、`modificationPlan=新增样本修改方案`、`plannedResolutionDate=2026-10-15`、`plannedMergeVersionBranch=CC2026R3`、`supplementNote=新增样本`；编辑仅改 `problemCause=更新后的问题原因`、`supplementNote=更新后补充`。

## 三、表快照、序列与掩码

### 3.1 每个成功写用例声明 7 张表（共 21 份）

| 表 | 新建 | 编辑 | 作废 | 掩码 |
| --- | --- | --- | --- | --- |
| `form_s1_v1_instances` | 1→1 | 1→1 | 1→1 | 无 |
| `form_s1_v1_records` | 1→2 | 1 行版本 2 | 1 行作废、版本 2 | 无 |
| `form_record_locations` | 1→2 | 1→1 | 1→1 | 无 |
| `form_record_audit_logs` | 1→2 | 1→2 | 1→2 | `ignore-columns: [client_ip]`（会话固定 `User-Agent: golden-baseline`） |
| `form_change_heads` | latest 1→2 | 1→2 | 1→2 | 无（时间来自冻结 Clock） |
| `fact_change_heads` | latest 1→2 | 1→2 | 1→2 | `ignore-columns: [updated_at]`（SQL `current_timestamp`） |
| `sync_runs` | 0→1 QUEUED | 0→1 | 0→1 | `ignore-columns: [run_id, created_at, updated_at]`；快照 scope：`run_type='FACT_REFRESH' and status='QUEUED'` |

- 响应/业务字段/`payload_json`/`form_input_hash`/计划字段严格比较，不使用业务值或签名掩码。
- `sync_runs.run_id` 由 `SyncRunIdGenerator` 生成随机 UUID，`created_at/updated_at` 为系统时钟，属运行身份而非业务值，故掩码；`id`、`status`、`priority`、`exclusive_scope`、`payload_json` 严格。
- 固定请求 `User-Agent` 在 `GoldenHttpSession` 统一设置；`client_ip` 仍受容器网络影响，故只掩码该列。

### 3.2 序列与完整恢复清单

`GoldenWriteCaseSupport.replayTable` 已按主键 `id` 自动校准 serial；另需显式校准：

- `form_record_id_seq`：还原任一 `form_s*_records` 后设为 `max(id)+1, is_called=false`（共享序列，不是表自有 serial）。
- `fact_change_version_seq`：还原 `fact_change_heads` 后设为 `max(latest_change_version)+1, is_called=false`。
- `sync_runs.id` 的 serial 由 `realignIdSequence` 处理；同时显式列入清单，避免表结构变化后静默跳过。

完整副作用与恢复：

| 副作用 | 产生方式 | 恢复 |
| --- | --- | --- |
| 记录/实例/目录/审计 | 真实写事务 | 用例前后 `REPLAY` |
| `form_change_heads` | `bumpHead` | `REPLAY` |
| `fact_change_heads` | `FormIssueChangeBridge.record` | `REPLAY` |
| `sync_runs` QUEUED | AFTER_COMMIT `submitFactRefresh` | `REPLAY` |
| 事实任务/投影/公开指针 | 调度已暂停 | 不产生；若产生即停止 |
| 导出审计 `form_record_export_logs` | `FormRecordExportService` | 导出前后删除/还原到基线，不累积 |
| 导出权限临时授予 | 测试期 | 按原行精确恢复 |
| 表/函数/视图 | 迁移 | 不回退；回滚走备份 |

## 四、读/导出用例与成功响应

| 端点 | 用例 | 请求/预期 |
| --- | --- | --- |
| `GET /detail` | `golden-record`（新增） | `gitlabBaseUrl`、`projectId=9`、`resourceType=issue`、`resourceId=969`、`templateKey=issue_response`；200、版本 1、1 条记录、`writable=true` |
| `GET /notification-payload` | `registered-issue`（新增） | `gitlabBaseUrl`、`projectId=9`、`requestIid=969`、`resourceType=issue`；200，`data` 严格且仅四字段 `sourceAddress/projectId/requestIid/resourceType` |
| `GET /exports/records` | `golden-version`（新增，EXPORT） | `versionId=1&projectIds=9`；200 xlsx，规范化后含基线 1 行 |
| 其余 19 个既有用例 | 保持 `missing-scope/unregistered-source/missing-publication` 等 | 确定性拒绝，不新增人员夹具 |

导出工作簿：文件名 `issue_response-records-1.xlsx`、工作表 `CC2026R3`；系统列 A—J、业务列 K—Q；基线行 `1 / http://gitlab.test.local:18080 / 9 / issue / 969 / 管理员 / 2026-09-01 09:00:00 / 1 / CC2026R3 / 有效 / DEFECT / 黄金基线固定问题原因 / 黄金基线固定修改方案 / 空 / 2026-09-30 / CC2026R3 / 黄金基线样本`。导出原值不转中文枚举；复用 `GoldenSnapshotSupport.assertExportSnapshot → normalizeExcel`。

导出审计与权限：

1. 导出前记录 `form_record_export_logs` 基线，导出后在 `finally` 删除本次新增行（或按基线还原），保证不改变后续数据库浏览器/读快照。
2. 执行前查询 `select count(*) from platform_role_permissions where role_code='SUPER_ADMIN' and permission_code='form.record.export'`；为 0 时插入 1 行并在 `finally` 删除；为 1 时不动。正式授权名单仍由权限管理员提供，不在本夹具内。

## 五、统计增量与页面范围

基线消费只改变 `issue_fact` 中 1228 一行：`planned_resolution_at/planned_resolution_text/form_input_hash`，其余业务列不变。按页面范围计算：

- 客户问题各页以 project 325 为业务范围（`appliedFilters.projectId=325`），1228 属于 project 9，**被项目范围排除**，增量为 0。
- 系统测试/质量看板要求 testing_phase、milestone、system-test 标签或客户标签；1228 无 milestone、无 labels、无 testing_phase（切片：`milestone_id=null`、label_links=0、assignee=0），**被范围规则排除**，增量为 0。
- BI 编码类读取 MR，评审数据读取评审记录，均不读取该表单字段，增量为 0。
- 结论中的 0 来自页面范围规则，**不是消费者被暂停**；暂停发生在基线消费完成之后。若生成后任一统计快照变化，先停止排查，不进入 update。

## 六、快照数量（按真实副作用重算）

### 6.1 响应快照：25 份

- 既有 19 份缺失（清单见 6.3）。
- 新增 6 份：`detail/golden-record`、`notification-payload/registered-issue`、`exports/records/golden-version`、`records/golden-create`、`records/{id}/golden-update`、`records/{id}/void/golden-void`。

### 6.2 表快照：37 份

- 既有 16 份缺失（清单见 6.4）。
- 新增 21 份：3 个成功写用例 × 7 张表（3.1）。
- 导出审计不生成表快照，由既有导出集成测试断言并由夹具还原。

**合计 62 份（25 响应 + 37 表）**。与旧提案 59 的差异：`sync_runs` 使每个成功用例多 1 份表快照（+3），数量口径由“抑制链路”改为“真实入队后还原”。

### 6.3 既有 19 份响应快照（全部缺失）

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

### 6.4 既有 16 份表快照（全部缺失）

这些快照在基线存在后会显示基线行（例如 `form_template_scopes` 的 scope 1、`form_record_audit_logs`/`form_change_heads` 的基线记录），不再是空表；失败路径仍必须零新增，快照按基线行严格比较。

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

### 6.5 既有快照的预期更新（不是新增）

除 2.1 的 6 份外，基线消费还会改变下列表快照，需在 update 前逐份审阅：

| 快照 | 变化 |
| --- | --- |
| `customer-issues/post___illegal-records_refresh-one__first-issue__issue_fact.json` | 1228 行新增 `planned_resolution_at/text` 与 `form_input_hash` |
| `question-metrics/post___illegal-records_refresh-one__first-issue__issue_fact.json` | 同上 |

## 七、执行顺序、批准与停止条件

1. 先交审核方确认本文件；再交用户批准夹具辅助代码、种子 `web_base_url`、7 张表清单、3 个掩码、62 份生成范围及 2.1/6.5 的既有快照变化。
2. 生成前先跑严格 compare 收集已覆盖差异（新用例响应缺失会阻断该用例后续表断言，用本文件静态清单补齐）；不要先无授权 update。
3. 经批准后以 `-Dgolden.update=true` 生成，逐份审阅 diff（重点是 `form_input_hash`、计划字段、`sync_runs.payload_json`、两条有序人员数组）；再移除 update 严格复比。
4. 停止条件：head 未消费、事实签名不一致、公开域未 PUBLISHED、出现活动运行/新事实任务/投影、统计快照变化、导出审计或权限未还原、任一掩码外业务值变化。
5. 未执行事项：golden/update、夹具/快照/掩码修改、main 合入、正式出包、升级、真实授权、生产启用。

### 附：源码基线

- 黄金源码输入固定为当前分支 HEAD `56004c2e`（在其下包含资料提交 `30341633`）；**不得用资料提交作为源码输入**。
- 生成前单独直接验证 `56004c2e` 的候选服务修复（项目候选按真实 ODS 整型列读取命名空间）：至少覆盖绑定选项/项目候选相关定向测试，并把结果记入生成前置证据；验证失败先停止。
- 本轮已在 `56004c2e` 直接执行 `FormProjectCandidateIntegrationTest`：10 项、0 失败、0 错误、0 跳过，耗时 56.152 秒（surefire `tests="10" errors="0" failures="0" skipped="0"`；日志 `.tmp/n5-t1-20261103/candidate-test.log`，不提交）。该测试使用真实 PostgreSQL 容器并锁定真实 `integer` 列型；黄金、夹具与页面矩阵均未运行。
