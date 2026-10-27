# v6 统一表单 N0 技术准入补充包（待审批）

> 状态：2026-10-27 提交，**未批准**。本包只补 v6.0 方向相对已通过 W0/W1 契约的差异，不改业务代码、不执行迁移、不写共享库。审核方回写正式技术合同后，N1 才可开工；R05 关闭不因本包自动获得 W5 或 main 合入授权。
>
> 依据：`docs/plans/unified-form-system-plan-20261009.md`（v6.0，2026-10-27）§2.2、§3.1—§3.8；执行单 `docs/plans/unified-form-system-implementation-plan-20261021.md` 的「W4三次整改复审与v6交接」「N0」两节；实际代码 `D:/projects/dcp-form-w4-20261026`，冻结 HEAD `97950222`（本包提交前）。
>
> 事实来源：`V20261022_01__unified_form_storage_foundation.sql`、`FormVersionSchemaService`、`FormRecordWriteService`/`FormRecordStore`、`FormRecordContextService`、`FormPublicationTxService`、`FormPublicationMigrator`、`FormLegacyRecordMigrator`、`GitlabFactSourceSqlProvider`、`PagePermissionKeyResolver`、`golden-baseline/endpoint-catalog.yml`。文中 DDL 为待审设计稿，不是已执行迁移。

## 0. 差异总览与审批项

v6 与 v5.1 的技术分界：**记录不再复制到当前版本表**。v5.1 的 `FormPublicationMigrator.copy`（复制实例与记录并核验 S）、`FormLegacyRecordMigrator`（旧表首迁）和每版 `request_key` 物理唯一，都建立在“当前表承载全部历史”上；v6 改为“每条记录一个原位权威位置 + 全局定位目录 + 多版本读源”。

| 编号 | 审批项 | 本章节 | 影响面 |
| --- | --- | --- | --- |
| A1 | `form_record_locations` 列/唯一键/FK/索引与同事务登记 | §1 | 记录路由、跨版防重、审计 |
| A2 | 可读版本集合（`source_set_revision/hash`、版本源角色、目录事件） | §1 | 发布切换、消费构建 |
| A3 | 既有数据登记算法与原行不变核验，无实例/无账号只读 | §2 | 旧 Demo、v5 副本 |
| A4 | detail 多规格响应、按所属版编辑/作废、跨版旧 key 与同人提示 | §3 | 记录 API、草稿 |
| A5 | 空结构发布协议、任务 `protocol_version=2`、旧任务收口 | §4 | 发布执行器、恢复 |
| A6 | 多版权威读取器、去副本、head/目录捕获与锁序 | §5 | 消费、fact/head |
| A7 | 共同字段可比属性与九项跨版选择器（MR 公式待批） | §6 | 统计、BI 读源 |
| A8 | 按版本 Excel 导出端点、`form.record.export` 权限与预算 | §7 | 导出、权限、黄金目录 |
| A9 | 一条前向迁移、删除旧运行路径与替代验收清单 | §8 | Flyway、测试、交付 |

## 1. 数据与目录（A1、A2）

### 1.1 新增定位目录

`record_id` 全局唯一（沿用 `form_record_id_seq`），目录是路由与防重的唯一入口；不另存可独立变更的业务内容。

```sql
create table form_record_locations (
    record_id          bigint       primary key,
    scope_id           bigint       not null,
    storage_version_id bigint       not null,
    instance_id        bigint,
    request_key        varchar(64),
    created_by         varchar(128),
    created_at         timestamp    not null default current_timestamp,
    constraint fk_frl_scope foreign key (scope_id)
        references form_template_scopes (id),
    -- 版本必须与 scope 同属：复用既有 unique (scope_id, id)
    constraint fk_frl_version_scope foreign key (scope_id, storage_version_id)
        references form_template_versions (scope_id, id),
    constraint ck_frl_request_key check (request_key is null or btrim(request_key) <> ''),
    constraint ck_frl_instance_scope check (instance_id is null or instance_id > 0)
);

-- 跨该范围全部版本的请求幂等唯一；NULL 仅用于真实无原请求key的历史行
create unique index uq_frl_scope_request
    on form_record_locations (scope_id, request_key)
    where request_key is not null;
create index idx_frl_version on form_record_locations (storage_version_id);
create index idx_frl_instance on form_record_locations (instance_id);
```

- `instance_id` 可空仅服务于无物理实例的只读旧 Demo 登记（§2）；原生记录必须写入该版真实实例，由服务层校验 `instance.scope_id = scope_id` 且 `instance_id` 属于 `storage_version_id` 对应实例表。
- `form_record_locations` 与物理记录一一对应，由服务层按 `(record_id, storage_version_id)` 解析后回表核对；不按“同名数字 ID”猜表。
- 删除/作废不删目录、不换 `record_id`、不搬表。

### 1.2 范围可读版本集合

```sql
alter table form_template_scopes
    add column source_set_revision bigint not null default 0,
    add column source_set_hash     char(64);

alter table form_template_versions
    add column record_source_role varchar(24) not null default 'TARGET',
    add constraint ck_ftv_source_role check (
        record_source_role in ('AUTHORITATIVE', 'LEGACY_SNAPSHOT', 'TARGET'));

create table form_scope_version_sources (
    scope_id    bigint    not null,
    version_id  bigint    not null,
    source_role varchar(24) not null,
    registered_at timestamp not null default current_timestamp,
    primary key (scope_id, version_id),
    constraint fk_fsvs_scope foreign key (scope_id) references form_template_scopes (id),
    constraint fk_fsvs_version_scope foreign key (scope_id, version_id)
        references form_template_versions (scope_id, id),
    constraint ck_fsvs_role check (source_role in ('AUTHORITATIVE', 'LEGACY_SNAPSHOT'))
);
```

- 普通查询/统计/导出只读 `AUTHORITATIVE` 版本；`LEGACY_SNAPSHOT` 仅审计与取证，排除在普通读取之外。v5.1 已复制出的旧表登记为快照，不 UNION、不逆向拆回、不再复制。
- 每次集合变化（新版本发布、旧表登记）在同一事务内递增 `source_set_revision` 并写入规范哈希：`sha256(scope_id | version_id | role)` 按版本 ID 升序拼接。
- `current_version_id` 只决定新建入口，不再定位旧记录。

### 1.3 目录事件与高水位

```sql
create table form_directory_events (
    id                  bigserial primary key,
    scope_id            bigint    not null,
    event_type          varchar(32) not null,
    source_set_revision bigint    not null,
    payload             jsonb     not null default '{}'::jsonb,
    created_at          timestamp not null default current_timestamp,
    constraint fk_fde_scope foreign key (scope_id) references form_template_scopes (id),
    constraint uq_fde_scope_revision unique (scope_id, source_set_revision),
    constraint ck_fde_type check (event_type in
        ('VERSION_SOURCE_ADDED', 'CURRENT_CHANGED', 'LEGACY_REGISTERED'))
);

create table form_record_id_watermarks (
    source_ref varchar(128) primary key,
    high_water bigint not null,
    updated_at timestamp not null default current_timestamp,
    constraint ck_frw_high_water check (high_water >= 0)
);
```

- 高水位只前进：登记或导入后写 `max(record_id)`；`form_record_id_seq` 以 `setval(..., greatest(current, high_water))` 对齐，禁止回退。
- 事件表与切换同事务，供消费方按 `source_set_revision` 检测目录变化；事件只追加。

### 1.4 事务边界

- **创建**：`pg_advisory_xact_lock(hashtext('form_record_id_seq'))` → 范围行 `for share` 且复核 `current_version_id`/停写栅栏 → 实例 upsert → 目录按 `(scope_id, request_key)` 查重 → 物理记录 `insert ... on conflict (request_key) do nothing` → 审计 → `form_change_heads` → 目录插入。任一失败整体回滚；目录与物理记录、审计、head 同事务。
- **跨版重放**：先查目录 `(scope_id, request_key)`；命中后按 `storage_version_id` 回表核对 `create_payload_hash`，一致返回原记录，不一致 409。
- **发布切换**：更新 `current_version_id`、插入版本源、递增集合修订/哈希、写目录事件、退役旧指针语义均在单事务内；DDL 建空表在切换事务之外，先建后验。
- 目录与物理表不使用延迟约束；所有校验在插入前完成。

## 2. 既有数据登记（A3）

### 2.1 范围与前置

- 对象：旧 Demo 表 `collect_form_records`（无 `request_key`/`record_version`，`reviewer` 为业务名，非账号）与已存在的 v5.1 复制副本（各 `form_s*_v*_records`）。
- 真实 E2 现场数据未取得，本包不给现场行数；开发期只在隔离库用夹具验证，不写共享库。
- 登记前必须：旧写入口已停止（平台栅栏不能代替旧后端真实停写）、资源选集稳定、表结构冻结为某一版本规格、原行计数与哈希快照已记录。

### 2.2 登记事务（单事务，失败全回滚）

```text
precheck（只读，独立事务）:
  assert legacyTable 存在且非空（或空表也允许登记空源）
  snapshot = (count, sha256(按 id 升序的 (id, gitlab_base_url, project_id,
              resource_type, resource_id, template_code, deleted, updated_at)))
  assert 每个 (gitlab_base_url, project_id, resource_type, resource_id, template_code)
         能唯一映射到目标 scope 与资源 C；无法映射则阻断
  assert 非空 request_key 候选在 scope 内唯一（旧表无该列→全部 NULL）
  assert 目标版本表未登记过（幂等：已存在同 scope/version 源则返回既有结果）

transaction:
  lock scope row for update
  insert form_template_versions(scope, storage_kind='RECORDS' 或复用旧表名,
      field_spec=冻结旧规格, version_status='PUBLISHED',
      record_source_role='AUTHORITATIVE' 或既有 v5 副本→'LEGACY_SNAPSHOT')
  -- 旧 Demo 无物理实例：按完整资源 C 建立本版实例（只登记一次）
  insert 缺失的 instances（natural key upsert），得到 instance_id
  insert form_record_locations(record_id=旧 id, scope_id, storage_version_id,
      instance_id=null 或按 C 建立的实例, request_key=null,
      created_by=null（未知创建人）, created_at=旧 created_at)
  insert form_scope_version_sources / 更新 source_set_revision+hash
  insert form_directory_events(LEGACY_REGISTERED)
  update form_record_id_watermarks(source_ref=旧表, high_water=max(id))
  assert 目录行数 = 旧表行数，且每个 record_id 可回表命中
commit

postcheck（独立事务）:
  assert 旧表 (count, sha256) 与 precheck 完全相同（原物理行不变）
  assert 目录覆盖数 = 行数、无重复 record_id、无重复 (scope_id, request_key)
  assert source_set_revision 只前进一次
```

- v5 副本的处理：同一逻辑记录若存在多张副本，先按 `origin_version_id + record_id + (C)` 核实唯一真实承载位置，只在目录登记该位置，其余表登记为 `LEGACY_SNAPSHOT` 并排除普通读取；不得复制到新版，也不删除旧表。
- 无创建账号的旧记录 `created_by=null` → 只读（不可编辑/作废），审计保留原 reviewer 业务值。
- 失败恢复：登记事务回滚后旧表零变化；若目录已提交但后置核验失败，按已记录的前置快照人工回滚（删除本次目录/源/事件并回退集合修订），不删除旧表；回滚脚本与审计进入 N1 交付。

## 3. 接口与本地格式（A4）

### 3.1 端点与 DTO

保留既有四个记录端点语义，扩展为“按所属版本”：

```text
GET  /api/collect-forms/detail?gitlabBaseUrl&projectId&resourceType&resourceId&templateKey
POST /api/collect-forms/records                      // 仅当前版本
PUT  /api/collect-forms/records/{recordId}           // 所属版本 + CAS
POST /api/collect-forms/records/{recordId}/void      // 所属版本 + CAS
GET  /api/collect-forms/reviewer-candidates
GET  /api/collect-forms/notification-payload
```

```ts
/** 记录条目：存储版本与规格一并返回，页面不猜表、不共用一套列。 */
interface FormRecordEntryView {
  recordId: number;
  storageVersionId: number;
  originVersionId: number;
  recordVersion: number;
  deleted: boolean;
  createdBy: string | null;
  /** 该记录所属版本的冻结规格（同一响应内按 versionId 去重）。 */
  fieldSpec: FormTemplateSpecView;
  fields: Record<string, unknown>; // 该版 apiKey→值
}

interface FormDetailResponse {
  scope: FormAdminScopeView;
  currentVersion: FormVersionRefView;                // 仅新建使用
  readableVersions: FormVersionRefView[];            // 权威源集合
  sourceSetRevision: number;
  sourceSetHash: string;
  records: FormRecordEntryView[];                    // 全部权威版本的有效记录
}
```

编辑/作废请求体新增 `expectedStorageVersionId` 与既有 `expectedRecordVersion`；服务端先按目录校验版本归属，再按该版物理列 CAS。跨版本记录不得用当前版字段集合提交。

### 3.2 跨版旧 key 与同人提示

- 创建仍携带 `requestKey`；查重范围从“当前记录表”改为 `form_record_locations(scope_id, request_key)`（§1.1），覆盖全部权威版本。
- 命中旧版记录：按原版本校验 `create_payload_hash`；一致返回 `REPLAYED` 原记录（不写新版）；不一致 409。真正未落库的旧版新增请求不静默换 key，保留草稿与未决状态。
- 同人提示：按 `created_by + resource C + templateKey` 在**全部权威版本**内查同人已有有效记录；存在时返回确认要求（沿用现有确认协议），不因跨版而放宽。
- 历史记录权限：`created_by` 非空且等于当前账号 → 可编辑/作废（按所属版校验）；`created_by` 为空（旧 Demo/未知）→ 只读；`form.record.edit_own/delete_own` 语义不变。

### 3.3 本地草稿与缓存格式

```ts
interface FormDraftV2 {
  schemaVersion: 2;
  contextKey: string;          // 账号+来源+项目+资源+模板
  scopeId: number;
  targetRecordId: number | null;
  /** 新建为当前版本；编辑/作废冻结原记录所属版本。 */
  storageVersionId: number | null;
  baseRecordVersion: number | null;
  fields: Record<string, unknown>;   // apiKey→值（按所属版规格）
  requestKey: string;                // 首次创建即固定
  payloadHash: string | null;        // 首次提交快照哈希
  submitState: 'draft' | 'pending' | 'unknown';
  updatedAt: string;
}
```

升级策略：从 v1 草稿按 `contextKey+timer` 迁移，保留原 `requestKey`、载荷与未决状态；无法映射版本时保持 `unknown` 并禁止普通丢弃，不清缓存替代正确性。页面按 `readableVersions` 缓存规格，缓存键含 `storageVersionId`。

## 4. 普通发布（A5）

### 4.1 冻结输入与阶段

预览冻结：`field_spec`、`binding_hash`、`source_set_revision`、`expected_current_version_id`、目标 `version_id`（新版本行先建 `record_source_role='TARGET'`，`version_status='INSTALLED'`）。发布任务携带 `protocol_version=2`。

```text
PREPARED
  -> VALIDATING   （服务端复核冻结预览/绑定/目录修订与当前指针未变）
  -> BUILDING     （生成空 instances/records；结构校验完整契约）
  -> SWITCHING    （单事务：current_version_id、版本源 AUTHORITATIVE、集合修订/哈希、目录事件、旧版本保持可读、任务 PUBLISHED）
  -> PUBLISHED
失败任一步 -> FAILED（记录 last_error，可显式恢复为 PREPARED；恢复前重捕获目录/指针）
```

- 不复制记录、不生成迁移日志、不写 `legacy_incomplete`；已有 v5 legacy 标记仅作为其原结构事实，不重算。
- 空结构校验复用 `FormVersionStructureVerifier`（列/约束表达式/外键动作/索引/序列默认）；目标表非空或结构漂移在任何切换前终止。
- 停写栅栏仍按 `publication+token`；切换事务提交时清除。租约/fencing 继续按 `clock_timestamp()` 复核，过期持有者零副作用。
- 旧工作者保护：任务行 `protocol_version` 不等于 2 时，旧版本执行器拒绝领取；新执行器拒绝 protocol=1 的任务并保持 FAILED 待人工收口，避免旧复制逻辑在新协议上运行。

### 4.2 旧复制任务与日志收口

- 存量未完成的复制/迁移任务（`stage in PREPARED/COPYING/VERIFIED` 且 `publication_kind in (INITIAL_MIGRATION, MIGRATION)`）：发布前一次性管理操作置为 `FAILED`，`last_error='v6_no_copy_protocol'`，不执行复制、不切指针；已成功的历史任务与 `form_template_migration_logs` 只读保留，不删除。
- 已复制的 v5 副本按 §2 登记为 `LEGACY_SNAPSHOT` 或唯一权威位置，不逆向拆回。

## 5. 消费协议（A6）

### 5.1 多版权威读取器

```java
/** 按目录定位记录；只接受 scope 可读版本集合内的 AUTHORITATIVE 源。 */
record LocatedRecord(long recordId, long scopeId, long storageVersionId,
                     Long instanceId, String createdBy, FormTemplateSpec spec) {}

interface FormRecordDirectoryReader {
  List<LocatedRecord> listRecords(ResourceContext ctx, String templateKey);
  Optional<LocatedRecord> findRecord(long recordId);
  SourceSet captureSourceSet(long scopeId);   // revision+hash+version specs
}
```

- 读取顺序：目录 → 所属版本规格（按 `captureSourceSet` 冻结）→ 回表核对 C/本人；禁止依次扫表。
- 去副本：只读 `AUTHORITATIVE`；`LEGACY_SNAPSHOT` 排除普通列表、统计、导出与写回资格。
- 同一业务 C 跨版本可各有一条有效记录；按 §6 选择器与共同字段聚合，不按 C 去重成一条。

### 5.2 head、目录与锁序

- `form_change_heads` 仍按 `(source_system, source_instance, project_id, resource_type, resource_id, template_key)` 唯一；新增“消费快照”列或伴随表记录 `source_set_revision/source_set_hash` 与各版本规格哈希（`head_source_capture jsonb`，含 `capturedRevision/capturedHash`）。
- 统一锁序：共享记录序列咨询锁（创建/切换涉及）→ 范围行 → 发布任务行 → `form_change_heads`；消费构建先捕获目录集合，再锁 head，提交时复核集合未变，变化则废弃候选、零公开。
- 结果公开与结算同事务：写事实/投影版本与 head 更新同事务；读取只暴露已提交的完整版本，拒绝过期执行者覆盖新结果。
- 无物理实例的旧 FORM 资源：目录 `instance_id=null`，消费按目录+记录表读取；新建时 `requireResourceMirrored` 仍要求镜像资源存在。
- 全量到自动增量交接：以目录集合修订为界，全量构建携带修订号；增量按记录审计/变更 head 推进；交接失败保留旧已发布结果，重试从最后已提交修订开始。

## 6. 共同字段与跨版选择器（A7）

### 6.1 稳定身份与可比属性

- 字段身份继续用稳定 `name`（`f_<12hex>` 或种子键）；显示名可改，身份不变。删除后同名新增得到新身份，不继承原值、不合并统计。
- 字段规格新增语义元数据（可选，未声明时该字段不参与跨版统计）：

```json
{"name":"specification_count","type":"integer",
 "comparable":{"key":"spec_count","unit":"count","valueType":"integer","semanticHash":"<sha256 of key|unit|valueType>"}}
```

- 跨版匹配条件：`semanticHash` 相同才可合并；仅中文名相同、单位不同（如“分钟/小时”）、枚举取值不同义 → 不自动匹配。系统维护映射，管理员不维护字段码/SQL。
- 取值口径：数值聚合忽略 NULL，不补 0；缺字段版本不写入该版行；分母按有效记录（deleted=false 且字段非 NULL）说明。比率类指标分母在同一不可比集合内计算。

### 6.2 Issue 九项跨版选择器（语义待审核确认，MR 公式另批）

| 选择器 | 跨版语义（草案，待确认） |
| --- | --- |
| 最新响应 | 全部权威版本按记录 `created_at` 取最新；不因版本发布改变顺序 |
| 首次响应 | 按最早 `created_at` |
| 最新合法计划 | 最新记录中计划解决时间与版本分支均有效的记录；缺失字段不从其它版本拼接 |
| 最近原因/方案 | 最新非空值（按对应版本字段） |
| 有效记录数 | 各版 deleted=false 记录之和（不去重 C） |
| 严重级别分布 | 按资源级严重级别（非表单字段）跨版合并 |
| 响应覆盖 | 资源在任一权威版本存在有效记录即覆盖 |
| 记录版本分布 | 按 `storageVersionId` 分组计数 |
| 版本导出口径 | 按导出所选版本独立列出该版字段列 |

MR 走查公式（时长/缺陷密度等）保持现行口径，待 N4 前单独确认；本包不改公式。

## 7. Excel 与权限（A8）

### 7.1 端点与列顺序

```text
GET /api/collect-forms/exports/records
    ?gitlabBaseUrl&projectId&resourceType
    &versionId（必填：按版本导出）
    &projectIds（C 范围，默认该版本 scope 的项目集合）
    &deleted=false（默认排除作废）
```

- 文件列顺序：系统列（记录ID、资源、创建人、创建时间、记录修订、状态）+ 该版本冻结规格的字段顺序（label 为表头）；字段缺失写空，不补 0。
- DTO 与文件列同源：`FormRecordExportRow`（Java record/TS 类型）由版本规格生成，禁止两处手写列名。
- 内存预算：沿用 `XSSFWorkbook` 仅用于小规模；超过 `exportRowBudget=50000` 行或 64MB 估算时改用 `SXSSFWorkbook` 流式并附保护说明；行数与字节在响应头/审计记录。

### 7.2 权限与登记

- 新增权限键 `form.record.export`（`PlatformPermissionCodes`），迁移模板复用 `V20261022_02__form_permissions.sql`：目录 upsert + 角色授予 + 默认快照；授予范围与 view 一致（SUPER_ADMIN/ADMIN + 质量角色，按审核确认清单）。
- `PagePermissionKeyResolver`：管理页 view/export 映射（若页面提供导出按钮）；`/api/collect-forms/exports/records` 在方法级校验 export 键，未授权返回 A0303。
- 端点登记：`golden-baseline/endpoint-catalog.yml` 增加导出条目与 affected-tables（读表，无写表）；新快照需用户确认后生成（本包不生成）。
- 实时工作区/事实依赖：表单不属于看板工作区，`RealtimeWorkspaceDependencyCatalog`、`GitlabFactDependencyCatalog` 本期不登记；若后续看板消费表单结果，再按登记规范补充（避免无依据登记）。

## 8. 迁移、测试与交付（A9）

### 8.1 Flyway 与分支冲突

- 只追加一条前向迁移（建议 `V20261027_01__form_v6_record_locations.sql`，实际编号在 N1 分支开工时按最新注册表确定），内容为 §1 的目录/源集合/事件/水位表与版本/范围新增列；旧 159 条迁移与 checksum 零改动。
- 开工前置检查：`scripts/check_flyway_migration_immutability.py` 通过；`scripts/flyway-migration-checksums.json` 只新增条目；与其它分支（20001 更新包、delay-writeback、form-version-design）的迁移号冲突时按“已执行迁移不改号”处理，重新取号。
- 部署恢复：迁移前整库备份；失败按备份恢复，不提供自动降级删除真实表。

### 8.2 删除的旧运行路径与替代验收

| 删除对象 | 调用者/测试 | 替代验收 |
| --- | --- | --- |
| `FormPublicationMigrator`（复制/转换/核验 S） | `FormPublicationService` 迁移分支、迁移日志测试 | 空结构发布、非空目标/漂移拒绝、切换原子性 |
| `FormLegacyRecordMigrator` 及首迁入口 | `FormInitialMigrationAdminTest` 等 | 旧表登记、原行不变、目录覆盖、只读权限 |
| 每版 `request_key` 物理唯一依赖 | 创建重放测试 | 目录跨版唯一、旧 key 回原版、未落库拒绝 |
| 预览/发布中的复制计数与校验和前置 | 发布阶段测试 | 目录修订/集合哈希冻结与切换事件 |
| 旧执行器领取 v2 任务 | 调度测试 | `protocol_version` 守卫（双向拒绝） |

- 被替换的错误测试直接删除或改写为目标契约测试，不保留兼容层；既有 W1—W4 通过的记录服务/页面/条件测试保留，删除仅针对复制/迁移运行路径。
- 新黄金有意差异：新增导出端点与目录读源若进入黄金目录，先列差异清单并获用户确认；本包不生成快照、不运行黄金。

### 8.3 测试与运行资源

- 默认后端套件：`mvn test`（真实 PG 由 Testcontainers 或 `TEST_DATASOURCE_URL` 提供）；表单定向：`-Dtest='Form*IntegrationTest,Form*Test'`；前端：`npm run typecheck && npm run lint && npm test && npm run build`。
- N1 起每单元最小充分入口：真实 PG 覆盖目录唯一/回滚/集合修订只进、旧行不变、未配置/漂移拒绝；页面覆盖 V1/V2 双版 CRUD 与迟到响应；发布覆盖空表、竞争、过期租约零副作用。
- 隔离资源：专属 worktree、独立 PostgreSQL（156xx）、后端/前端独立端口；不触碰 18080/15432/18181/18182、共享库、真实 GitLab 或黄金；服务停止后再冻结日志与清单。

## 9. 待审核确认与未决项

| 序号 | 未决项 | 默认建议 | 阻断阶段 |
| --- | --- | --- | --- |
| Q1 | 目录 `instance_id` 可空仅限旧 Demo 登记 | 采纳 | N1 |
| Q2 | `form_scope_version_sources` 与版本 `record_source_role` 双写角色是否只保留一处 | 保留版本列为主、源表为集合视图 | N1 |
| Q3 | 目录事件是否需要跨进程消费（现仅前端/内部构建读取） | 先做同库事件，不做消息队列 | N2 |
| Q4 | `protocol_version` 的旧任务收口方式（自动置 FAILED vs 人工清单） | 自动置 FAILED 并保留错误原因，人工复核 | N1 |
| Q5 | 九项选择器名称与合法计划定义 | 按 §6.2 草案确认 | N4 |
| Q6 | 共同字段语义元数据是否本期强制 | 可选、未声明不参与统计 | N4 |
| Q7 | Excel 行数预算与流式阈值 | 50k 行 / 64MB 估算，超限 SXSSF | N4 |
| Q8 | 导出权限授予角色清单 | 与 view 一致，另行确认 | N4 |
| Q9 | 真实 E2 旧数据规模与登记窗口 | 现场核对后补充 | N1 前 |

**审批请求**：确认 A1—A9 的 DDL/事务与类型契约、§8.2 删除清单与 §9 默认建议；批准后按 N1—N5 顺序实施，回写主方案 §3.1/§3.3—§3.5 与 §3.7—§3.8 的技术合同。未批准前不改业务代码、不建表、不删除旧迁移。
