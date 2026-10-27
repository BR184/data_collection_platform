## 进度与中间物

- 审核结论（2026-10-27）：**N0修改后重审，N1/W5未准入**。R05已关闭，W4冻结v5.1目标通过；该结论不代替新版技术准入。以下八项必须先改成单一、可执行的合同，再提交N0，不开始N1业务代码。
- 输入：员工`4423920e5d4bf916388156dc380192d9b0070eb0`，原N0 SHA-256 `1e24d65ea633ade176ffbe5484442e8a50115de41b5b53b7717a7b64d434f15e`；原提案附于本文后段，未审建议不得作为批准规则。
- 工作区与恢复：审核文档`D:/projects/dcp-form-version-design-20261026` / `codex/form-version-design-20261026`；员工实现目录`D:/projects/dcp-form-w4-20261026`保持该送审HEAD及干净状态。恢复先读AGENTS/进度/本文并运行`git -C D:/projects/dcp-form-w4-20261026 status --short --branch`。
- 已验证：独立正式组件26项、原三项审核探针均通过；142项代码/27项证据及双远端一致。隔离PG16执行本模块基础DDL和提案全部SQL块，SQL块可解析，但protocol列、新阶段及旧Demo元数据仍有下述缺项。证据主树`.tmp/w4-r4-review-20261027/`。
- 目标与边界：用户已确认旧记录原版留存/维护、共同字段统计及版本Excel；补充DDL、事务、消费和恢复合同，不重谈既定业务，不复制旧行、不回退目录证据、不扩大权限，不以本文批准真实登记/切换。
- 当前步骤：员工按下表及Q项裁定修订提案、补可执行隔离验证入口并回填主方案差异；审核方再复核。无业务实现或正式迁移已执行。敏感边界为真实E2旧数据及外部转权，当前不访问；MR级待批公式仍不代拟。
- 员工提交（2026-10-27）：修订稿见下方「N0修订稿」，按R01—R08/Q1—Q9逐项整改；隔离探针public 12项、非空升级1项、共同字段95项全部通过，容器按ID删除；只改本文档与`.tmp`探针，未改业务代码、正式迁移、前端（仍等于`94749f3a`）。待二审。

## N0修订稿（2026-10-27，待二审）

> 状态：R05已关闭、W4冻结v5.1通过；本稿仅回应N0-R01—R08与Q1—Q9，**N0未批准、N1/W5未准入**。不含业务实现、不改正式迁移、不运行黄金、不合main、不出包。原N0与评审原文保留在本节之后，作为冻结输入。
>
> 输入：员工分支`codex/unified-form-w4-isolation-20261026`，吸收交接`c9a7aa44`（UI基线`94749f3a`，`git diff --exit-code 94749f3a -- frontend`无差异）；原N0输入SHA-256见§10。事实来源为本分支实际迁移与代码，已按文件逐项核对（见各节引用）。

### 0. 逐项对照与Q项落实

| 审核项 | 本稿落实位置 | 关键结论 |
| --- | --- | --- |
| R01 发布/版本DDL | §1.2、§1.3、§4 | protocol列、v2阶段、活跃唯一/领取索引、attempt协议、空实例LEGACY、角色单一来源、旧任务受控收口、恢复重冻结 |
| R02 登记不变量 | §3 | 原行全值哈希、单事务内完成核验、提交后只追加补偿事件、Demo/v5两条路径、幂等重入 |
| R03 创建/重放/序列 | §2 | 单一六步事务、目录优先判重、首次冻结规格/账号/C/指纹、只进不退高水位 |
| R04 消费提交 | §5 | `form_head_captures`定型、提交事务锁序与CAS谓词、目录事件持久领取/确认/重启、全量→增量 |
| R05 九项选择器 | §7.2 | 逐项对齐主方案§3.5，删除“最新非空回退”和新增分支门槛；同走查人沿reviewerUserId+C |
| R06 DTO/草稿 | §6 | 真实字符串bigint、真实`draft-store.ts` v1字段逐项映射、保留分叉/未知证据 |
| R07 共同字段 | §7.1、§7.3 | 三模板默认可比元数据、语义哈希扩展、逐指标分母与2+3=5 |
| R08 消费/导出/验证 | §5、§8、§9 | 实际消费类清单、导出角色与范围、流式边界、可执行隔离入口 |
| Q1 | §1.1、§3.1 | 仅旧Demo目录可空实例；原生记录必须真实实例，以复合外键+CHECK强制 |
| Q2 | §1.2 | 删除`form_scope_version_sources`；版本列唯一权威，可读集合由视图派生 |
| Q3 | §5.3 | 同库持久事件+游标，多进程领取/确认/重启恢复 |
| Q4 | §4.3 | 不自动FAILED；停旧执行者后按锁/租约/fencing受控收口 |
| Q5 | §7.2 | 不重开业务选择器；MR公式另批 |
| Q6 | §7.1 | 首版覆盖三模板全部既有共同字段，未声明字段不静默计入 |
| Q7 | §8.3 | 50k/64MB仅为待验证预算；提交估算、硬边界、清理与固定负载证据 |
| Q8 | §8.2 | 从真实目录推导导出授予，给出具体角色与不扩权证据 |
| Q9 | §3.4、§9 | E2/真实数据不阻断隔离设计验证，但阻断真实登记/切换 |

### 1. 迁移与DDL（R01、Q1、Q2）

#### 1.1 迁移拆分与顺序

只追加前向迁移；旧159条（最后`V20261022_03`）零改动。编号在N1开工时按最新注册表复核，建议：

| 迁移 | 内容 | 依赖 |
| --- | --- | --- |
| `V20261027_01__form_v6_directory.sql` | 目录、范围集合、版本角色/实例模式、来源表、目录事件、高水位、函数 | 仅`V20261022_01` |
| `V20261027_02__form_v6_publication_protocol.sql` | protocol列、v2阶段、活跃/领取索引、attempt协议、旧任务收口视图 | 01 |
| `V20261027_03__form_v6_consumption.sql` | head捕获列/表、捕获与结算函数、无实例权威规则 | 01、02 |
| `V20261027_04__form_v6_export_permission.sql` | `form.record.export`目录/授予/defaults、导出审计表 | 无（权限表） |

所有DDL按“旧基础结构非空升级”和“空平台”两种路径验证（§9）；不允许改已执行迁移或整体重写Flyway锁文件。

#### 1.2 范围集合与版本角色（单一权威）

```sql
-- 范围：可读集合修订与哈希；只由发布切换/登记事务递增
alter table form_template_scopes
    add column source_set_revision bigint not null default 0
        check (source_set_revision >= 0),
    add column source_set_hash char(64);

-- 版本：角色只有这一处权威；实例模式决定是否允许无实例
alter table form_template_versions
    add column record_source_role varchar(24) not null default 'AUTHORITATIVE',
    add column instance_mode varchar(16) not null default 'MATERIALIZED',
    add column source_table varchar(63);

alter table form_template_versions
    alter column instance_table drop not null,
    add constraint ck_ftv_role check (
        record_source_role in ('AUTHORITATIVE','LEGACY_SNAPSHOT','TARGET')),
    add constraint ck_ftv_instance_mode check (
        instance_mode in ('MATERIALIZED','NONE')),
    add constraint ck_ftv_instance_shape check (
        (record_source_role <> 'TARGET'
         and ((storage_kind = 'RECORDS' and record_table is not null
               and instance_mode = 'MATERIALIZED' and instance_table is not null
               and source_table is null)
           or (storage_kind = 'LEGACY' and record_table is null
               and instance_mode = 'MATERIALIZED' and instance_table is not null
               and source_table is null)
           or (storage_kind = 'LEGACY' and record_table is null
               and instance_mode = 'NONE' and instance_table is null
               and source_table is not distinct from 'collect_form_records')))
        or (record_source_role = 'TARGET' and storage_kind = 'RECORDS'
            and record_table is not null and instance_mode = 'MATERIALIZED'
            and instance_table is not null and source_table is null)),
    add constraint ck_ftv_none_role check (
        instance_mode <> 'NONE' or record_source_role = 'AUTHORITATIVE'),
    add constraint uq_ftv_id_instance_mode unique (id, instance_mode);

-- 集合哈希：仅AUTHORITATIVE版本，按版本ID升序；不依赖扩展
create function form_source_set_hash(p_scope_id bigint) returns char(64)
language sql stable as $$
    select encode(sha256(convert_to(coalesce(string_agg(
        v.id::text || ':' || v.record_source_role, ',' order by v.id), ''), 'UTF8')), 'hex')
    from form_template_versions v
    where v.scope_id = p_scope_id and v.record_source_role = 'AUTHORITATIVE'
$$;

create function form_spec_hash(p_spec jsonb) returns char(64)
language sql immutable as $$
    select encode(sha256(convert_to(p_spec::text, 'UTF8')), 'hex')
$$;

-- 可读集合只由版本列派生，不再建/双写来源角色表（Q2）
create view form_scope_readable_versions as
select v.scope_id, v.id as version_id, v.version_label, v.version_status,
       v.storage_kind, v.instance_mode, v.instance_table, v.record_table,
       v.source_table, v.field_spec, v.record_source_role
from form_template_versions v
where v.record_source_role = 'AUTHORITATIVE'
  and v.version_status in ('PUBLISHED','RETIRED');
```

- 既有行升级：默认`AUTHORITATIVE/MATERIALIZED`，满足形状检查；v5旧副本在登记时由§3改为`LEGACY_SNAPSHOT`，不新增角色表。
- `TARGET`只用于发布中的空结构版本；切换时同事务改为`AUTHORITATIVE`。`TARGET`不得被`current_version_id`引用，由§9完整性函数校验。
- `instance_mode='NONE'`只允许旧Demo登记（`source_table='collect_form_records'`），不伪造实例表。

#### 1.3 定位目录（Q1）

```sql
create table form_record_locations (
    record_id          bigint primary key,
    scope_id           bigint not null references form_template_scopes(id),
    storage_version_id bigint not null,
    instance_mode      varchar(16) not null,
    instance_id        bigint,
    request_key        varchar(64),
    created_by         varchar(128),
    created_at         timestamp not null default current_timestamp,
    constraint fk_frl_version_scope foreign key (scope_id, storage_version_id)
        references form_template_versions(scope_id, id),
    constraint fk_frl_version_mode foreign key (storage_version_id, instance_mode)
        references form_template_versions(id, instance_mode),
    constraint ck_frl_request_key check (
        request_key is null or btrim(request_key) <> ''),
    constraint ck_frl_instance check (
        (instance_mode = 'MATERIALIZED' and instance_id is not null and instance_id > 0)
        or (instance_mode = 'NONE' and instance_id is null))
);

create unique index uq_frl_scope_request
    on form_record_locations(scope_id, request_key)
    where request_key is not null;
create index idx_frl_version on form_record_locations(storage_version_id);
create index idx_frl_instance on form_record_locations(storage_version_id, instance_id)
    where instance_id is not null;
```

- `instance_mode`随目录行保存并由复合外键锁定到版本，避免用触发器猜版本模式；原生记录`instance_id`非空且属于该版实例表（应用层插入前校验实例表`scope_id/version`）。
- `record_id`沿用`form_record_id_seq`；删除/作废不删目录、不换ID、不搬表。
- `request_key`只对真实请求存在；旧Demo全部为NULL，允许重复NULL。

#### 1.4 目录事件与高水位（Q3）

```sql
create table form_directory_events (
    id                  bigserial primary key,
    scope_id            bigint not null references form_template_scopes(id),
    event_type          varchar(32) not null,
    source_set_revision bigint not null,
    payload             jsonb not null default '{}'::jsonb,
    operator_id         varchar(128),
    created_at          timestamp not null default current_timestamp,
    status              varchar(16) not null default 'PENDING',
    attempt_no          integer not null default 0 check (attempt_no >= 0),
    lease_owner         varchar(128),
    lease_until         timestamp,
    fencing_version     bigint not null default 0 check (fencing_version >= 0),
    next_attempt_at     timestamp,
    last_error          text,
    consumed_at         timestamp,
    constraint ck_fde_type check (event_type in
        ('VERSION_ADDED','CURRENT_CHANGED','LEGACY_REGISTERED','REGISTRATION_CORRECTED')),
    constraint ck_fde_status check (status in
        ('PENDING','LEASED','RETRY_WAIT','CONSUMED','FAILED')),
    constraint uq_fde_scope_revision unique (scope_id, source_set_revision, event_type)
);
create index idx_fde_claim on form_directory_events(status, next_attempt_at, lease_until)
    where status in ('PENDING','RETRY_WAIT','LEASED');

create table form_directory_event_cursors (
    scope_id            bigint primary key references form_template_scopes(id),
    last_consumed_event_id bigint not null default 0,
    updated_at          timestamp not null default current_timestamp
);

create table form_record_id_watermarks (
    source_ref varchar(128) primary key,
    high_water bigint not null check (high_water >= 0),
    updated_at timestamp not null default current_timestamp
);

-- 只进不退；调用者须已持有hashtext('form_record_id_seq')事务锁（同事务可重入）
create function form_advance_record_sequence(p_high bigint) returns bigint
language plpgsql as $$
declare cur bigint;
begin
    perform pg_advisory_xact_lock(hashtext('form_record_id_seq'));
    select last_value into cur from form_record_id_seq;
    if p_high > cur then
        perform setval('form_record_id_seq', p_high, true);
        return p_high;
    end if;
    return cur;
end $$;
```

- 事件只追加；事件`FAILED`只能经新修订/补偿事件纠正，不删除、不回退`source_set_revision`。
- 游标在“该修订全部事件CONSUMED”后推进；重启从游标重放，重复扇出幂等。

### 2. 创建、跨版重放与序列事务（R03）

统一锁序（与§4/§5一致）：**记录序列咨询锁 → 范围行 → 发布任务行（如涉及）→ head行（按id）**。创建路径为“序列锁 → 范围FOR SHARE → 目录判重 → 实例/记录/审计/head”；发布切换为“序列锁 → 范围FOR UPDATE → 任务行 → head”；消费提交为“范围FOR SHARE → head FOR UPDATE”（不新增反向锁）。

#### 2.1 单一六步事务

```text
createRecord(context, requestKey, storageVersionId, actor, fields):
  1. pg_advisory_xact_lock(hashtext('form_record_id_seq'))
  2. select ... from form_template_scopes where id=? for share
     并读取 current_version_id/status/write_fenced/source_set_revision
  3. select * from form_record_locations
       where scope_id=? and request_key=?              -- 跨全部权威版本，先判已有key
     if found:
        v = 版本(location.storage_version_id)
        assert v.record_source_role='AUTHORITATIVE'
        assert location.created_by = actor
        assert 请求资源C与记录行C一致
        assert 记录未作废
        hash = fingerprint(origin/first冻结规格(v.field_spec), actor, fields)
        assert hash = 首次create_payload_hash
        return REPLAYED(原记录)                        -- 不写实例、不改记录、不切版本
  4. assert scope.status='ACTIVE' and not write_fenced
     assert storageVersionId = scope.current_version_id
  5. instance = ensureInstance(context)                 -- 自然键upsert + 行锁
     record = insert into <v.record_table>(...)
        values(instance, origin_version_id=storageVersionId, created_by=actor,
               request_key, create_payload_hash=fingerprint(frozen spec, actor, fields), ...)
        on conflict (request_key) do nothing
     if record is null: 回到步骤3重查；仍无则409“并发写入冲突”
     insert form_record_locations(record_id, scope, storageVersionId,
        instance_mode='MATERIALIZED', instance_id=instance.id,
        request_key, created_by=actor, created_at=record.created_at)
     insert form_record_audit_logs(...CREATE...)
     upsert form_change_heads(... pending_record_id/version,
        pending_storage_version_id=storageVersionId,
        pending_source_set_revision=scope.source_set_revision,
        pending_source_set_hash=scope.source_set_hash,
        status='PENDING')
  6. commit；任一步失败整体回滚
```

- **重放不依赖当前人员目录**：只按首次冻结规格、原账号、原C与原载荷哈希校验；姓名变化不阻断。
- 旧版key：目录命中即按原`storage_version_id`回表，不要求`storageVersionId=current_version_id`；请求携带的版本仅用于校验其属于该范围的可读集合（不得用当前版规格重算）。
- 账号/C/指纹/作废任一不符→409；未命中但请求版本≠当前→409（结构已变化），由客户端刷新后重试，不静默换key。
- 目录、物理记录、审计、head必须同事务；不允许延迟约束或事务外补写。

#### 2.2 CAS、作废与目录不变

```text
updateRecord(recordId, expectedStorageVersionId, recordVersion, fields):
  目录→版本；assert expectedStorageVersionId=location.storage_version_id
  update <record_table> set fields..., record_version=record_version+1, updated_at=?
   where id=? and created_by=? and record_version=? and deleted=false
  if 0 rows -> 409；审计UPDATE/head PENDING（source_set_revision不变）
voidRecord(recordId, expectedStorageVersionId, recordVersion, confirmVoid):
  同锁序；update deleted=true, record_version+1 where ...；审计VOID/head PENDING
```

- 作废不删目录、不换版本、不搬表；选择器与统计一律排除`deleted=true`。
- 目录不存业务值，只存定位与首次身份；业务值始终回表读取。

#### 2.3 高水位只进不退

- 创建只使用`nextval('form_record_id_seq')`，不`setval`。
- 登记（§3）持有同一咨询锁，调用`form_advance_record_sequence(max(old_id))`；若高水位≤当前`last_value`则不动，保留`is_called`语义。
- 登记与创建、登记与切换因同一咨询锁串行；不同范围也串行（代价可接受，换取全局ID单调与目录唯一）。

### 3. 既有数据登记（R02、Q1、Q9）

#### 3.1 两条登记路径

| 路径 | 物理承载 | 版本元数据 | 目录 | 创建人/key |
| --- | --- | --- | --- | --- |
| A 旧Demo `collect_form_records` | 原表原位，不搬 | 新建LEGACY版本：`storage_kind='LEGACY'`、`instance_mode='NONE'`、`instance_table=null`、`record_table=null`、`source_table='collect_form_records'`、`record_source_role='AUTHORITATIVE'`、`version_status='PUBLISHED'`、`field_spec=旧列固定语义规格` | `record_id=旧id`、`storage_version_id=该版`、`instance_mode='NONE'`、`instance_id=null`、`request_key=null`、`created_by=null`、`created_at=旧值` | 不伪造实例、账号、记录修订 |
| B v5已复制记录 | 选择唯一承载副本原位 | 承载版本→`AUTHORITATIVE`；其余含同一逻辑记录的版本→`LEGACY_SNAPSHOT`（不删表、不改行） | 指向承载行；保留真实`request_key/created_by`、`origin_version_id`、`created_at`、`create_payload_hash` | 只排除副本，不重建 |

**v5承载副本判定**（登记前报告，不猜）：按`(origin_version_id, record_id, 资源C)`分组，比较各版本表中的`record_version/updated_at/业务值`；选`record_version`最大、再`updated_at`最新、再版本ID最大的副本为承载（通常为cutover时的当前版本，保留最新值；`origin_version_id`仍记录创建版本）。若业务值不同的副本`record_version`相同→阻断；若旧副本含承载规格已删除字段的非空值→输出`field_loss_report`并阻断，待审核裁决，不合并、不逆向拆回、不补写。该scope在cutover前除承载版本外的全部版本统一置`LEGACY_SNAPSHOT`，一律不参与普通读取；不得保留为可读AUTHORITATIVE空集。

#### 3.2 全值与单调登记

```text
precheck（只读）:
  rowHash = sha256(string_agg(to_jsonb(t)::text, E'\n' order by t.id))
  count   = count(*)
  生成 carrierReport（A无副本；B逐记录承载与field_loss_report）
  若目标版本/目录已存在：读取既有登记指纹，相同→ALREADY_REGISTERED；不同→409

register（单事务，失败全回滚）:
  pg_advisory_xact_lock(hashtext('form_record_id_seq'))
  lock table <source tables> in share mode          -- 旧写已停，仍防登记期间变化
  scope row for update
  重新计算 count/rowHash 并断言与precheck一致        -- 发布前后业务值变化即中止
  A: insert LEGACY/NONE版本；B: 更新承载/副本 record_source_role
  insert form_record_locations ... on conflict (record_id) do nothing
     -- 已存在则逐字段比对，不同即中止（不覆盖）
  assert 目录行数=源行数、无重复record_id、无重复(scope_id,request_key)
  assert 每条目录可按(storage_version,record_id)回表命中且业务值哈希一致
  update scope set source_set_revision=source_set_revision+1,
                   source_set_hash=form_source_set_hash(scope_id)
  insert form_directory_events(LEGACY_REGISTERED, 新修订, payload含count/rowHash/
         carrierReport/fieldLossReport/operator)
  upsert form_record_id_watermarks(source_ref, max(id))
  assert 集合哈希=函数重算值；commit
```

- **提交后不删除、不回退**：任何纠正都是新修订 + `REGISTRATION_CORRECTED`事件 + 审计；`source_set_revision`只增。登记失败只回滚事务，旧表零变化。
- 幂等重入：同源同哈希返回既有结果，不再增修订；源变化则409并给差异报告。
- 未知账号只读；审计保留原`reviewer`业务值，不写`created_by`。
- 反例矩阵：业务值变化、已有跨版key、账号已知/未知、副本值不同、缺失字段、登记失败后重入、两个范围同时登记/创建、登记中断回滚。

### 4. 发布协议（R01、Q4、Q9）

#### 4.1 v2阶段与防旧执行器

```text
v1（历史）：PREPARED → COPYING → VERIFIED → PUBLISHED / FAILED
v2（新）： VALIDATING → BUILDING → SWITCHING → PUBLISHED / FAILED
           FAILED →（复核冻结输入）→ VALIDATING
```

- v2任务创建即为`VALIDATING`，**不使用PREPARED**：旧二进制只领取`stage='PREPARED'`，无法误领v2任务；新执行器只领取`protocol_version=2 and stage='VALIDATING'`，拒绝protocol=1任务并在诊断中列出。部署仍要求先停旧二进制并收口旧任务（§4.3），不依赖“旧二进制识别新列”。
- 创建时冻结：`frozen_field_spec`、`frozen_binding`、`binding_hash`、`expected_current_version_id`、`frozen_source_set_revision/hash`、目标版本（先插入`TARGET`+`INSTALLED`+空结构元数据）。不复制任何记录。
- 失败任一步→`FAILED`并记录`last_error`；仅任务所有者按fencing撤栅栏，attempt保留。

#### 4.2 空结构构建与切换

```text
claim（新执行器）:
  序列咨询锁 → 范围FOR UPDATE → 任务FOR UPDATE
  assert protocol=2, stage='VALIDATING', lease无效或已过期
  置范围停写栅栏(publication+token)；stage='BUILDING'；新attempt(VALIDATE)
commit（构建在事务外，受租约/栅栏保护）
build:
  生成空 instances/records（已存在且非空→FAILED；结构漂移→FAILED）
  FormVersionStructureVerifier全量核验；stage='BUILDING'→'SWITCHING'
switch（单事务）:
  序列咨询锁 → 范围FOR UPDATE → 任务FOR UPDATE
  assert owner/fencing/lease有效；assert frozen_source_set_revision/hash=当前集合
  assert expected_current_version_id=当前指针；assert 目标INSTALLED且结构完整
  目标版本→PUBLISHED + AUTHORITATIVE
  旧当前版本→RETIRED（角色保持AUTHORITATIVE，仍可读）
  source_set_revision+1；source_set_hash=form_source_set_hash(scope)
  当前指针CAS更新（含binding哈希复核）
  目录事件VERSION_ADDED/CURRENT_CHANGED
  范围内head置PENDING并写pending_source_set_revision/hash（§5）
  任务→PUBLISHED；attempt(SWITCH,PUBLISH)；清栅栏
```

- 冻结集合/指针/绑定任一漂移→零切换、任务FAILED（可恢复），不得重捕获后沿用旧预览。
- 恢复：`FAILED→VALIDATING`前复核冻结输入仍等于当前集合/指针/绑定；不等→409要求新预览；禁止静默重捕获。
- 空结构版本可导出表头（§8）；不生成迁移日志、不写`legacy_incomplete`、不truncate/复制旧行。

#### 4.3 旧任务受控收口（Q4）

由运维在新二进制启动前执行，单任务单事务，顺序仍为“序列锁 → 范围FOR UPDATE → 任务FOR UPDATE”：

```sql
-- 只读盘点：列出待收口任务及是否仍有有效租约
select p.id,p.scope_id,p.stage,p.lease_owner,p.lease_until,p.fencing_version,
       clock_timestamp() < coalesce(p.lease_until,'-infinity') as live_lease
from form_template_publications p
where p.protocol_version=1 and p.stage in ('PREPARED','COPYING','VERIFIED')
order by p.id;

-- 受控终止（参数逐任务填写；live_lease=true时不得执行）
begin;
select pg_advisory_xact_lock(hashtext('form_record_id_seq'));
select id from form_template_scopes where id=:scope for update;
select stage,lease_owner,lease_until,fencing_version,fence_token
  from form_template_publications
 where id=:id and protocol_version=1 for update;
update form_template_publications
   set stage='FAILED', last_error='v6_no_copy_protocol',
       recovery_evidence=coalesce(recovery_evidence,'{}'::jsonb)||jsonb_build_object(
         'closedBy',:operator,'closedAt',clock_timestamp(),'previousStage',stage,
         'previousLeaseOwner',lease_owner),
       updated_at=clock_timestamp()
 where id=:id and protocol_version=1 and stage in ('PREPARED','COPYING','VERIFIED')
   and (lease_owner is null or lease_until is null or lease_until <= clock_timestamp())
   and fencing_version=:fencing;
-- 影响行数必须为1，否则回滚
insert into form_template_publication_attempts(
  publication_id,attempt_no,phase,selection_captured_at,selection_counts,error,
  started_at,finished_at,protocol_version)
values(:id,(select attempt_no+1 from form_template_publications where id=:id),
  'FAILED',clock_timestamp(),'{}'::jsonb,'v6_no_copy_protocol',
  clock_timestamp(),clock_timestamp(),1);
update form_template_scopes
   set write_fenced=false,write_fence_reason=null,write_fence_publication_id=null,
       write_fence_token=null,write_fence_at=null
 where id=:scope and write_fence_publication_id=:id and write_fence_token=:token;
commit;
```

- 有效租约（`live_lease=true`）不得终止：等租约到期，或在确认旧进程已停止后由运维以显式证据接管（记录`closedBy`与进程核对），不得无条件`UPDATE FAILED`。
- 已`PUBLISHED`任务不动；已复制的目标表保留但保持`TARGET`/不可读，登记与清理在N1交付清单中逐表处理；不删除真实表。
- 收口后新执行器不处理protocol=1任务；诊断接口/命令列出其状态供复核。

### 5. 多版本消费（R04、Q3）

#### 5.1 目录读取与捕获

```java
/** 目录按recordId定位；只接受AUTHORITATIVE且PUBLISHED/RETIRED的版本。 */
interface FormRecordDirectoryReader {
  Optional<LocatedRecord> find(long recordId);
  List<LocatedRecord> listByResource(ResourceC ctx, String templateKey);
  SourceSet capture(long scopeId);   // revision/hash/versions/fieldSpecHash
}
record LocatedRecord(long recordId, long scopeId, long storageVersionId,
                     String instanceMode, Long instanceId, String createdBy,
                     FormTemplateSpec spec) {}
```

- 读取顺序：目录→版本规格（调用方先`capture`冻结）→回表核对C/创建人；禁止按数字ID猜表、禁止逐版扫表。
- 只读`AUTHORITATIVE`；`LEGACY_SNAPSHOT`排除列表、统计、导出与写回资格；同一C跨版可各有一条有效记录，按§7选择器取用，不按C去重成一条。
- 捕获持久化到伴生表（单一选型，不再“列或表”二选一）：

```sql
alter table form_change_heads
    add column pending_source_set_revision bigint,
    add column pending_source_set_hash char(64);

create table form_head_captures (
    head_id             bigint primary key references form_change_heads(id) on delete cascade,
    capture_token       uuid not null default gen_random_uuid(),
    source_set_revision bigint not null,
    source_set_hash     char(64) not null,
    versions            jsonb not null,   -- [{versionId,role,fieldSpecHash,instanceMode,sourceTable}]
    latest_change_version bigint not null,
    authority_revision  bigint,
    lease_owner         varchar(128) not null,
    fencing_version     bigint not null,
    captured_at         timestamp not null default current_timestamp
);
```

#### 5.2 领取→捕获→构建→提交

```text
claim（单语句，多进程安全）:
update form_change_heads h
   set status='LEASED', lease_owner=:worker,
       lease_until=clock_timestamp()+interval '5 minutes',
       fencing_version=fencing_version+1, next_attempt_at=null,
       updated_at=clock_timestamp()
 where h.id = (
   select id from form_change_heads
    where status in ('PENDING','RETRY_WAIT')
       or (status='LEASED' and lease_until < clock_timestamp())
    order by next_attempt_at nulls first, id
    for update skip locked limit 1)
returning h.*;

capture（短事务，提交后构建）:
  select source_set_revision, source_set_hash from form_template_scopes
   where id=:scope for share;                       -- 与切换的FOR UPDATE互斥
  select v.id,v.record_source_role,form_spec_hash(v.field_spec),
         v.instance_mode,v.source_table,v.record_table
    from form_template_versions v
   where v.scope_id=:scope and v.record_source_role='AUTHORITATIVE'
     and v.version_status in ('PUBLISHED','RETIRED');
  insert/overwrite form_head_captures(...);
  commit;

build（事务外，只读捕获中的版本表与规格副本）:
  按§7选择器生成事实候选；不读“当前指针”代替集合；
  规格哈希与捕获不一致→放弃候选（零公开）。

publishAndSettle（单事务）:
  select ... from form_template_scopes where id=:scope for share;  -- 保持到提交
  select ... from form_change_heads where id=:head for update;
  assert lease_owner=:worker and fencing_version=:fence
         and status='LEASED' and lease_until > clock_timestamp();
  assert form_head_captures.source_set_revision/hash = 当前scope值;   -- 漂移零公开
  assert head.latest_change_version = capture.latest_change_version; -- 记录漂移零结算
  assert 捕获authority_revision = form_resource_authorities.authority_revision;
  写事实/投影公开版本（同事务）;
  update form_change_heads
     set consumed_change_version=capture.latest_change_version,
         pending_record_id=null,pending_record_version=null,
         pending_storage_version_id=null,pending_source_set_revision=null,
         pending_source_set_hash=null,pending_authority_revision=null,
         status='CONSUMED',lease_owner=null,lease_until=null,last_error=null,
         updated_at=clock_timestamp()
   where id=:head and status='LEASED' and fencing_version=:fence
     and latest_change_version=capture.latest_change_version;
  -- 影响行数≠1则回滚，事实零公开、head零结算
commit;
```

- **锁的位置**：构建可以在事务外进行，但**提交事务持有范围`FOR SHARE`直到提交**，并以单调`source_set_revision/hash`做CAS；集合在捕获后变化必然被复核发现。这样既满足“提交时集合未变”，又不让长构建阻塞表单写入/发布（架构的“长计算不阻塞短任务”约束）。若审核要求范围锁从捕获一直持有到提交，需要接受构建期间停写，本稿不采用。
- 漂移处理：集合或head变化→回滚，`status='RETRY_WAIT'`，退避后重试；事实与结算均为零，不发布半成品。
- 过期worker：租约过期后可被新worker领取（fencing+1），旧worker的CAS必然失败、零副作用。
- 重启接管：`form_head_captures`持久化，重启后从head状态与捕获重放；已CONSUMED不重复构建。

#### 5.3 目录事件、权威类型与全量→增量（Q3）

- 事件只追加（§1.4），发布切换/登记同事务写事件并唤醒范围内head：发布→该scope全部head置PENDING并写`pending_source_set_revision/hash`；登记→按目录中的资源C幂等upsert head（`latest_change_version=1`）。
- 事件消费：`select ... where status in ('PENDING','RETRY_WAIT') or (status='LEASED' and lease_until<now) order by id for update skip locked limit 1`→置LEASED→扇出head（幂等）→置CONSUMED；失败置RETRY_WAIT并保留`last_error`；`form_directory_event_cursors.last_consumed_event_id`在整修订事件CONSUMED后推进，重启从游标重放。
- 全量→增量：全量构建记录捕获修订R；R之后的事件为增量起点；增量捕获新修订并按选择器重建受影响head；交接失败保留上一已发布事实版本，重试从最后已提交修订开始。
- 权威类型：`form_resource_authorities.authority`为`FORM`/`COMMENT`；`authority_revision`只在权威类型切换（COMMENT↔FORM）时递增，写入同事务。`COMMENT`权威的head不写表单存储证据（沿用切换事务不变量）；`FORM`权威按捕获集合构建。
- 无实例且无记录的FORM资源：发布仍建authority/head；构建捕获到零记录，发布**显式空表单投影**（表单派生字段置空/缺省，不删除资源事实），head正常CONSUMED。`FORM→COMMENT→FORM`因authority_revision变化使旧候选CAS失败并重建。
- MR事实消费入口（N1替换读源，全部走同一reader，不允许保留旧表专用路径）：`FactBuildService.rebuildMergeRequestFactsInternal`（全量）、`rebuildMergeRequestFactsByRootIds`（定向）、`rebuildMergeRequestFactByIid`（单条/诊断）、`CodeReviewMetricEnrichmentService.publishEnriched`（周期）。现有`GitlabFactSourceSqlProvider`的`form_records` CTE只读`collect_form_records`，切换后改为reader提供的投影；切换前保持只读现状，不双读。
- Issue事实消费入口：`FactRefreshTaskWorkerService`的ISSUE分支、`FactBuildService`的Issue构建；现有`IssueFactSourceRowMapper`/`IssueResponseTemplateParser`保留为评论来源，表单选择器在N2按§7接入；切换按(模板,字段)一次性替换并删除旧分支，不长期双轨。
- 工作区/事实依赖：表单不登记为工作区/ODS依赖（`RealtimeWorkspaceDependencyCatalog`、`GitlabFactDependencyCatalog`不变），表单→事实由head/capture驱动；回归锁定两目录无表单条目。

### 6. DTO、接口与草稿升级（R06）

#### 6.1 真实DTO（字符串bigint）

```ts
interface FormRecordView {                 // 现有字段保持不变
  id: string; recordVersion: number; deleted: boolean;
  legacyIncomplete: boolean; legacyMissingFields: string[];
  fields: Record<string, unknown>; fieldNames: Record<string, string>;
  snapshotValues: Record<string, boolean>;
  createdBy: string | null; createdByName: string | null;
  templateVersion: string | null; originVersionId: string;
  createdAt: string | null; updatedAt: string | null;
  canEdit: boolean; canVoid: boolean;
}
interface FormRecordEntryView extends FormRecordView {
  storageVersionId: string;                // 物理承载版本
  originVersionId: string;                 // 创建时版本（历史事实）
}
interface FormDetailResponseV2 {
  templateKey: string; scopeId: string; currentVersionId: string;
  readableVersions: Array<{ versionId: string; versionLabel: string | null;
    versionStatus: string; instanceMode: 'MATERIALIZED' | 'NONE'; fieldSpecHash: string }>;
  sourceSetRevision: string; sourceSetHash: string;
  versionSpecs: Record<string, FormTemplateSpecView>;  // key=versionId字符串
  records: FormRecordEntryView[];                     // 全部权威版本有效记录
  resource: FormResourceView; account: FormAccountView; capabilities: FormCapabilitiesView;
}
```

- **身份区分**：`recordId`（目录主键/全局稳定）≠`originVersionId`（创建版本）≠`storageVersionId`（当前物理承载版本）≠`recordVersion`（记录CAS修订）。全部bigint ID在JS侧为十进制字符串；`recordVersion`为number；管理端`scope/version/publication id`维持现有number。
- 编辑/作废请求新增`expectedStorageVersionId: string`（与既有`recordVersion: number`并存）；服务端以目录解析版本，拒绝与请求不符；跨版本记录不得用当前版字段集合提交。创建请求`scopeId/storageVersionId/requestKey`仍为string，`storageVersionId`必须等于当前版本。
- 精确契约与调用者：`frontend/src/types/api/collect-form.ts`、`frontend/src/api-client/collect-forms-api.ts`、`frontend/src/features/collect-forms/form-filling-session.ts`、`CollectFormView.vue`、`CollectFormController`、`FormRecordService`、`FormRecordViewFactory`。服务端继续用显式`Long.toString`输出ID，不引入Jackson全局数字字符串化。

#### 6.2 草稿 v1→v2（按真实存储）

v1事实（`frontend/src/features/collect-forms/draft-store.ts`）：键为`qaflex.form.draft.v1|<account>|<contextKey>|<editingSessionId>|<draftId>`，其中`contextKey=canonicalGitlabBaseUrl|projectId|resourceType|resourceId|templateKey`；对象字段为`draftSpecVersion=1, accountUserId, contextKey, context{gitlabBaseUrl,projectId,resourceType,resourceId,templateKey,scopeId,storageVersionId}, draftId, editingSessionId, kind, targetRecordId, baseRecordVersion, baseFields, fields, requestKey, firstPendingSnapshot, inputRevision, persistedRevision, savedAt, status, submitState, submitSnapshot, savedElsewhere, updatedAt`。**不存在的“contextKey+timer迁移”不采用。**

| v1字段 | v2处理 |
| --- | --- |
| `draftSpecVersion` | 写2；读取同时接受1 |
| `accountUserId/contextKey/context.*` | 原样保留；`context.scopeId/storageVersionId`保持字符串 |
| `draftId/editingSessionId/kind/targetRecordId/baseRecordVersion/baseFields/fields` | 原样保留 |
| `requestKey/firstPendingSnapshot/submitSnapshot/submitState` | 原样保留；未知/冲突状态不降级 |
| `inputRevision/persistedRevision/savedAt/status/savedElsewhere/updatedAt` | 原样保留 |
| 新增`originVersionId` | 编辑块=原记录所属版本；新建=null |
| 新增`baseStorageVersionId` | 编辑/作废冻结的物理版本；新建=当前版本 |
| 新增`versionSpecHash` | 捕获时规格哈希；变化→只读并提示 |

- 键前缀改`v2`但**同account/context/session/draftId**；读取v2失败才回退读v1并重新写入；写v2成功后才删该v1键；不合并不同`draftId`、不合并其他会话。
- 多标签：仍只克隆`updatedAt`最大的会话（保留原`draftId/requestKey/firstPendingSnapshot`）；v2不得把分叉副本合并。
- 缺失/不可读版本：按`targetRecordId`查目录；服务端能解析→按所属版恢复；不能解析→保持只读冻结并显示“记录版本不可用”，不得永久`unknown`、不得清缓存代替正确性。
- 新建未落库的旧版草稿与已有历史记录编辑分开：前者只能复制为新key新建，后者按`originVersionId`编辑；`unknown`在核验成功或明确失败前禁止丢弃/复制。
- 账号：401不删草稿（现有行为），`markAttemptAbandoned`→unknown/frozen；仅显式退出删除本账号草稿。
- 必测：大ID（>2^53字符串）、v1多标签分叉、旧key未知、401/换账号、原版CAS、规格变化只读、v1→v2幂等升级。

### 7. 共同字段、分母与九项选择器（R05、R07）

#### 7.1 可比元数据与三模板默认值

```json
"comparable": {
  "semanticKey": "issue.supplement_note",
  "meaning": "补充说明",
  "valueType": "string",
  "unit": null,
  "enumDomain": null,
  "semanticVersion": 1,
  "semanticHash": "sha256(canonical-json{enumDomain,meaning,semanticKey,semanticVersion,unit,valueType})"
}
```

- 哈希输入是**完整语义对象**（键排序、显式null），不是`key|unit|valueType`三串；系统维护，管理员不可编辑；新增字段未声明→不参与统计并在校验报告中列出，已确认核心字段必须声明。
- 三模板既有字段全部给出默认值（下表为字段→语义键/单位/枚举域；未列者如纯文本仍给语义键并标`text`）：

| 模板 | 字段 | semanticKey | valueType/unit | enumDomain |
| --- | --- | --- | --- | --- |
| code_review | reviewer_user_id | code_review.reviewer_user_id | string | platform_ldap_users |
| code_review | review_duration_minutes | code_review.review_duration_minutes | integer/minute | — |
| code_review | specification_count | code_review.specification_count | integer/count | — |
| code_review | logic_count | code_review.logic_count | integer/count | — |
| code_review | performance_count | code_review.performance_count | integer/count | — |
| code_review | design_count | code_review.design_count | integer/count | — |
| code_review | other_count | code_review.other_count | integer/count | — |
| code_review/issue_response/issue_reply | supplement_note | common.supplement_note | string/text | — |
| issue_response/issue_reply | modification_plan | issue.modification_plan | string/text | — |
| issue_response | problem_type | issue_response.problem_type | enum | DEFECT/REQUIREMENT |
| issue_response | problem_cause | issue_response.problem_cause | string/text | — |
| issue_response | owner_confirmation | issue_response.owner_confirmation | string/text | — |
| issue_response | planned_resolution_date | issue_response.planned_resolution_date | date/business-day | — |
| issue_response | planned_merge_version_branch | issue_response.planned_merge_version_branch | string/branch | — |
| issue_reply | fix_status | issue_reply.fix_status | enum | RESOLVED/PARTIAL/DEFERRED/UNREPRODUCIBLE |
| issue_reply | cause_keys | issue_reply.cause_keys | enum-set | DEFECT_CAUSE(24) |
| issue_reply | specific_reason | issue_reply.specific_reason | string/text | — |
| issue_reply | caused_by_other_defect | issue_reply.caused_by_other_defect | boolean | — |
| issue_reply | affected_kinds | issue_reply.affected_kinds | enum-set | KNOWN/NEW/NONE |
| issue_reply | known_affected_functions | issue_reply.known_affected_functions | string/text | — |
| issue_reply | new_affected_functions | issue_reply.new_affected_functions | string/text | — |
| issue_reply | impact_test_status | issue_reply.impact_test_status | enum | YES/NO/NOT_APPLICABLE |
| issue_reply | has_residual_impact | issue_reply.has_residual_impact | boolean | — |
| issue_reply | residual_impact_detail | issue_reply.residual_impact_detail | string/text | — |
| issue_reply | relation_table_updated | issue_reply.relation_table_updated | enum | YES/NO/NOT_APPLICABLE |

- 明确**不合并**：`problem_cause`≠`specific_reason`；`problem_type`≠`fix_status`；`impact_test_status`与`relation_table_updated`虽同枚举值但语义不同；code_review五类计数≠reply原因词典；严重级别是资源级快照，不是表单可比字段。
- 改名不改`semanticKey/hash`；删除后同名新增得到新稳定身份，不继承、不合并；同名不同义即使哈希碰撞也不合并（哈希仅作校验，匹配还须稳定身份与语义键）。

#### 7.2 九项Issue选择器（对齐主方案§3.5，不重开）

| # | 选择器 | 正式语义 |
| --- | --- | --- |
| 1 | 最新整份响应（planned_resolution_*、planned_merge_version_branch） | 跨全部权威版本按`(createdAt, recordId)`降序选未作废响应的当前修订；整份取用，不逐字段补旧；编辑不重排 |
| 2 | 首次调研时间/响应周期 | `min(createdAt)`有效响应；编辑不后移；仅回复无响应不制造样本 |
| 3 | has_response | 响应记录存在；与回复存在、延期豁免分开 |
| 4 | 响应延期豁免（D-21） | 有效回复存在→豁免；仅响应存在→按最新响应模板的合法计划检查；合规完整性另行判断；方向为最新→更早，仅限D-21 |
| 5 | 原因/非法唯一/原因统计与导出 | 最新有效回复按`(createdAt, recordId)`降序；按稳定具体原因ID集合投影；合规按ID去重，0或>1非法，未判定为待检查；多选采集与唯一性独立 |
| 6 | fix_user | 被选有效回复的创建账号姓名快照；不另造代填、不前向回退 |
| 7 | known/new affected与回复详情 | 从被选回复的独立字段投影；历史未知为“--”，未选为“否”；不重解析Markdown |
| 8 | resolve_deadline/解决延期 | 响应按创建时间最新；D-21合法计划查找与18天公式分别保留；旧“反向取最早”差异列入产出影响与I2回归 |
| 9 | fixed_label_time/解决周期 | 完成状态+真实加标事件，沿D-20/D-27；完成取真实事件时间，两端同一瞬间 |

- “最新”统一为`(createdAt, recordId)`降序；作废记录从筛选、聚合、导出、首次/最新及效率样本中排除；禁止“每版各选一条再相加”，最新整份响应缺字段保持缺项。
- 同走查人软提示：MR按**业务`reviewer_user_id`值+C**跨权威版本查询，记录归属仍是`created_by`；Issue不凭`created_by`泛化走查人规则。
- MR级汇总公式（时长/缺陷密度等）保持现行口径，待W5前单独提交影响说明；本稿不拟新公式、不改分母。

**非空样本与预期（夹具）**：V1响应t1（原因A，计划2026-11-01，分支CC2027R1）、V2响应t2（原因B，分支非法）、V1回复t3（原因X,Y）、V3回复t4（原因Z）、V2回复t5已作废。预期：最新响应=V2整份（计划字段取自V2，非法分支不拼接V1）；首次调研=t1；has_response=true；原因=最新有效回复V3→{Z}；fix_user=V3作者；D-21因有效回复存在而豁免；解决周期仅由真实完成事件产生。

#### 7.3 分母与2+3=5

| 指标/选择器 | 分子 | 分母 | 缺字段/NULL/0/作废 |
| --- | --- | --- | --- |
| 平均走查时长 | Σ非空duration | 非空duration的有效code_review记录数（跨权威版） | NULL不补0；0保留；作废排除；副本不计 |
| 五类问题计数/规范2+3=5 | 同一semanticKey跨版求和 | — | 缺字段版不写行；不改名合并新身份 |
| 缺陷密度 | 有效问题数×1000 | 去重被走查行数>0（既有口径） | 分母≤0→null，不造0 |
| 响应周期 | Σ(首次响应-创建) | 有创建且首次响应的议题数 | 仅回复无样本；样本0显示0并带样本数 |
| has_response覆盖率 | has_response=true议题 | 基数集议题（既有D） | 无响应计false，不排除 |
| 原因分布 | 分类命中数（多原因重复计） | Σ分类命中数（D-30） | 无原因不计；作废排除 |
| 非法唯一原因 | 最新有效回复中0或>1个原因ID的议题 | 有有效回复的议题 | 未判定单列待检查，不入分母 |
| 解决周期 | Σ(真实完成事件-创建) | 有真实完成事件的议题 | 无事件排除；两端同一瞬间 |
| 共同字段存在率 | 非空/非缺字段记录数 | 选定版本有效记录数 | 缺字段与NULL分列；0不算缺 |

- **2+3=5**：V1规范=2、V2规范=3，semanticKey/单位相同→5；仅改名→仍合并；删除后同名新增→新身份不合并；同单位异义枚举→不合并；无共同字段→明确输出“无可统计共同字段”并附选定/贡献版本，不返回0。
- 每个指标输出`selectedVersions/contributingVersions`（实际贡献记录所在版本），供审计与导出。

### 8. 导出与权限（R08、Q7、Q8）

#### 8.1 端点、范围与列

```text
GET /api/collect-forms/exports/records
    ?versionId=<十进制字符串，必填>
    &projectIds=<可选，逗号分隔；默认该版本scope.project_ids>
```

- `versionId`必须是该scope的可读`AUTHORITATIVE`版本（`PUBLISHED/RETIRED`）；`TARGET`/`LEGACY_SNAPSHOT`一律404/409；`projectIds`必须是scope项目集合子集，空交集拒绝。
- 只导出`deleted=false`的有效记录（不提供`includeVoided`，作废结果禁止导出）；新建空版本导出**仅表头**。
- 列：系统列（记录ID文本、GitLab地址、项目、资源类型、资源ID、创建人姓名快照、创建时间、记录修订、状态）+该版本冻结规格字段（按规格顺序、label为表头）。字段缺失写空，不补0；bigint ID写文本；日期`yyyy-MM-dd`（业务日），时间`Asia/Shanghai` `yyyy-MM-dd HH:mm:ss`。
- `FormRecordExportRow`由版本规格生成，表头与行同源，禁止两处手写列名。
- 审计（新增表，N1迁移内）：

```sql
create table form_record_export_logs (
    id bigserial primary key,
    scope_id bigint not null references form_template_scopes(id),
    version_id bigint not null,
    project_ids jsonb not null,
    row_count bigint not null check (row_count >= 0),
    byte_size bigint not null check (byte_size >= 0),
    file_sha256 char(64) not null,
    operator_id varchar(128) not null,
    created_at timestamp not null default current_timestamp
);
```

#### 8.2 权限授予（具体角色与不扩权证据）

- 新增`form.record.export`（module“表单采集”，sort 3345）。授予集合由**真实目录数据**推导，不猜“质量角色”：

```sql
insert into platform_permissions(permission_code,permission_name,module_name,description,sort_order)
values ('form.record.export','导出表单记录','表单采集',
        '按已发布版本导出获准范围内的有效表单记录',3345)
on conflict (permission_code) do update
set permission_name=excluded.permission_name,module_name=excluded.module_name,
    description=excluded.description,sort_order=excluded.sort_order,
    enabled=true,updated_at=current_timestamp;

-- 仅授予同时持有两个模块view键的既有角色；不新增未知角色
insert into platform_role_permissions(role_code,permission_code)
select r.role_code,'form.record.export'
from (values ('SUPER_ADMIN'),('ADMIN'),('DIRECT_MANAGER'),('TREE_MANAGER'),('NORMAL_USER'))
     r(role_code)
where exists (select 1 from platform_role_permissions p
              where p.role_code=r.role_code and p.permission_code='code_review.illegal.view')
  and exists (select 1 from platform_role_permissions p
              where p.role_code=r.role_code and p.permission_code='customer_issue.record.view')
on conflict do nothing;

insert into platform_default_role_permissions(role_code,permission_code)
select role_code,'form.record.export'
from (values ('SUPER_ADMIN'),('ADMIN'),('DIRECT_MANAGER'),('TREE_MANAGER'),('NORMAL_USER'))
     roles(role_code)
on conflict do nothing;
```

- **基线证据**：`V20260720_01`对五个角色授予全部非system权限（含两个view），`V20261022_02`已把`form.record.delete_own`授予同一五角色。导出与既有模块导出（如`customer_issue.record.export`）同属“view+export”惯例，不扩大到其他角色；若目标库存在自定义角色，迁移只按上述join实际授予，N1交付时用只读查询列出最终角色名单与差异，不修改其它权限。
- 端点方法级校验`form.record.export`，未授权A0303；导出范围仍受scope/项目/版本约束，不能跨范围读取。管理页当前无导出按钮，**不新增页面view/export映射**；若后续加按钮，须先按AGENTS报批并同单元登记`PagePermissionKeyResolver`。
- `endpoint-catalog.yml`新增EXPORT条目（读目录/版本/记录表）；夹具固定scope/version ID使动态表名确定；本稿不生成黄金快照，新增/差异待W7审批。

#### 8.3 资源边界与容量（Q7）

- 50k行/64MB仅为**待验证预算**，不是容量结论。估算：`rows × (系统列+字段列平均UTF-8字节+XML开销) + 工作簿固定开销`；固定负载（1万/5万行、宽表）测量耗时、堆峰值、临时文件大小，阈值取重复采样分布，不靠重跑。
- 行为：超过`rowLimit=50000`或估算`byteLimit=64MB`→返回413并说明实测值/上限，不静默截断；小结果（≤5000行）可用`XSSFWorkbook`，以上用`SXSSFWorkbook(window=200)`流式写出。
- 临时资源：SXSSF临时目录为`${java.io.tmpdir}/qaflex-form-export-<uuid>/`，`finally`递归删除；不写入仓库、不残留；响应流与审计记录行数、字节数、SHA-256。
- 现场容量由内网固定负载实测，不用小夹具外推；失败清理与超限路径纳入N1测试。

### 9. 验证入口与隔离探针（R08、Q9）

#### 9.1 可执行入口（pwsh，显式隔离，不用通配mvn）

```powershell
$name = 'dcp-n0-' + [guid]::NewGuid().ToString('N').Substring(0,8)
$port = 15607   # 先确认未被占用；不得使用15432/15433/15601/15602
$pw   = 'n0-isolated-only'
docker run -d --name $name -e POSTGRES_PASSWORD=$pw -p 127.0.0.1:${port}:5432 postgres:16-alpine | Out-Null
try {
  for ($i=0; $i -lt 60; $i++) { docker exec $name pg_isready -U postgres; if ($LASTEXITCODE -eq 0) { break }; Start-Sleep -Seconds 1 }
  docker cp backend/src/main/resources/db/migration/V20261022_01__unified_form_storage_foundation.sql ${name}:/tmp/base.sql
  docker cp D:/projects/data_collection_platform/.tmp/w4-r5-n0-20261027/probe-public.sql ${name}:/tmp/probe.sql
  docker exec $name psql -U postgres -v ON_ERROR_STOP=1 -f /tmp/base.sql
  docker exec $name psql -U postgres -v ON_ERROR_STOP=1 -f /tmp/probe.sql
  if ($LASTEXITCODE -ne 0) { throw 'N0 probe failed' }
} finally {
  $id = docker inspect -f '{{.Id}}' $name
  docker rm -f $id
  "removed=$id" | Tee-Object -FilePath D:/projects/data_collection_platform/.tmp/w4-r5-n0-20261027/cleanup.txt
}
```

- 业务测试（N1起）用显式`-Dtest=类名`与显式`TEST_DATASOURCE_URL/TEST_POSTGRES_JDBC_URL`指向本容器；禁止`-Dtest='Form*'`或裸`mvn test`作为“隔离”证据。默认15433库不参与。
- 真实E2与现场登记/切换仍是独立门禁（Q9），不阻断本设计探针，但阻断真实登记与N1上线；MR公式仍待批。

#### 9.2 探针清单（本稿已执行，见交付证据）

| 探针 | 预期 |
| --- | --- |
| P1 空平台应用V20261022_01+本稿01—03 | 全部DDL成功，旧159条未改 |
| P2 非空基础升级（先插v5范围/版本/任务再ALTER） | 默认角色/模式回填，形状检查通过 |
| P3 protocol/stage | v1只接受旧阶段；v2只接受VALIDATING/BUILDING/SWITCHING/PUBLISHED/FAILED；非法组合23514 |
| P4 活跃唯一/领取索引 | 同scope第二个活跃任务23505；FAILED后可建；两协议不并存 |
| P5 实例模式 | MATERIALIZED空instance_table拒绝23514；NONE空表+source_table成功；无实例目录行仅NONE允许 |
| P6 目录约束 | 跨版requestKey重复23505；NULL可重复；版本/scope复合FK；实例模式复合FK |
| P7 可读视图/哈希 | TARGET/快照不出现；AUTHORITATIVE集合哈希=函数重算 |
| P8 登记 | Demo零实例/零账号登记、v5承载+副本角色、全值哈希一致、重入幂等、提交后补偿事件不回退 |
| P9 旧任务收口 | 有效租约不终止；过期租约终止并写attempt/清本任务栅栏；非本任务栅栏不动 |
| P10 head捕获/结算 | 集合或head漂移时CAS为0；一致时CONSUMED且pending清空 |
| P11 目录事件 | 领取/确认/租约过期重领/游标推进；重启从游标重放 |
| P12 无实例FORM | 发布建authority/head，捕获零记录可结算为空投影，不删资源 |
| P13 共同字段/2+3=5 | 三模板默认元数据齐全；哈希一致则2+3=5；新身份/异义枚举不合并；无共同字段显式报告 |
| P14 导出权限 | 只授予同时持有两个view键的五角色；默认快照一致；无其他角色新增 |

证据写入`D:/projects/data_collection_platform/.tmp/w4-r5-n0-20261027/`：probe SQL、逐项日志、容器ID与清理记录、共同字段报告；不留业务服务或共享库访问。

**本次执行结果（2026-10-27）**：`postgres:16-alpine` 隔离容器`99ac1b2068ec1176af77a8c8a1a343075956476ed409646e07d9bfbc51e9d47d`（本机15607，唯一命名`dcp-n0-r5-20261027`）执行P1、P3—P12、P14共12项与独立`p2db`的P2共1项全部PASS；共同字段探针95项PASS；先应用`V20261022_01`再应用本稿DDL，旧159条未改。容器已按ID删除、无残留端口；未启动业务服务、未连接共享库或内网。

### 10. 替换范围、风险与审批请求

#### 10.1 N1删除/替换清单

| 对象 | 现状调用者 | 替代与验收 |
| --- | --- | --- |
| `FormPublicationMigrator.copy`（复制/核验S） | `FormPublicationTxService.copy` | 空结构发布+集合切换；非空目标/漂移拒绝；不复制 |
| `FormLegacyRecordMigrator.copy`（首迁复制） | 同文件INITIAL_MIGRATION分支 | §3登记；原行不变/目录覆盖/只读 |
| 每版`request_key`物理唯一依赖 | 创建重放 | 目录跨版唯一；旧key回原版；未落库409 |
| 旧执行器领取v2/新执行器领取v1 | worker | 双向拒绝；v1仅受控收口 |
| 复制计数/校验和前置与`legacy_incomplete`新写 | 发布测试 | 目录修订/集合哈希冻结；历史标记只读保留 |
| `collect_form_records`事实读源 | MR事实四个入口 | §5 reader统一替换，一次性切换无长期双轨 |

被替换的测试直接改写为目标契约测试，不保留兼容层；既有W1—W4记录/页面/条件测试保留。

#### 10.2 风险与未决（需审核裁定）

1. v2初始阶段采用`VALIDATING`而非`PREPARED`，以防止旧二进制误领；若审核要求保留PREPARED，须同时接受“部署前必须停旧二进制”的强约束。
2. 消费提交采用“构建在事务外、提交事务持范围FOR SHARE到提交”的方案，避免长构建阻塞写；若要求捕获后全程持锁，需要接受发布期间停写。
3. v5承载副本若存在已删除字段的值丢失，按§3.1阻断并交审核/业务裁决，不自动合并。
4. 导出角色名单按真实目录join推导；最终名单与差异需N1只读核对后确认。
5. MR级汇总公式与真实E2规模仍按原阶段，不在本稿代拟。

#### 10.3 审批请求

请确认：§1—§8的DDL/事务/消费/导出合同、§10.1删除清单与§10.2暂定项；批准后N1按阶段实施，先提交最小DDL+目录/登记测试，再发布/消费/导出。未批准前不改业务代码、不建正式表、不改旧迁移、不合main、不出包。

## N0首次审核（2026-10-27）

### 必须整改的技术合同

| 编号 | 原提案位置与问题 | 修改及复验条件 |
| --- | --- | --- |
| N0-R01 发布与版本DDL不完整 | §1/§4/§8.1只列目录等新增结构，却使用protocol_version及VALIDATING/BUILDING/SWITCHING。隔离真实PG中protocol查询报42703，三个阶段各报23514；活跃任务唯一/领取索引仍只覆盖PREPARED/COPYING/VERIFIED。版本instance_table仍NOT NULL，无实例LEGACY元数据探针报23502。Q2又给表与视图两套角色来源 | 交付全部前向ALTER、约束/索引及类型：协议列/历史默认值、完整阶段与活跃唯一/领取条件、原任务/attempt兼容历史、不再需要复制字段的非空要求、旧Demo源类型及空实例元数据、角色单一事实源。按旧基础结构非空升级及空平台验证；旧159条不改。停止旧二进制后处理旧任务，不能声称旧二进制会识别新增协议列；终止需锁/租约/fencing及归属token结算、释放本任务栅栏并保留审计，不能只UPDATE FAILED。恢复不得静默重捕获新目录/指针后沿用旧预览，须重新冻结/确认或明确证明原输入仍有效 |
| N0-R02 登记不变量与恢复相冲突 | §2.2提交后删除目录/源/事件并回退集合修订，破坏单调证据且可能让旧消费者再次匹配；指纹只列身份/更新时间，未覆盖业务值；旧Demo与v5原生记录共用NULL key/未知创建人示例，无法证明已有幂等与权限不丢 | 将必要原行全值/计数/唯一归属/目录覆盖核验纳入可回滚登记边界；已提交后的纠正用新增修订、补偿事件和审计，禁止回退修订/删除已发布证据。明确旧Demo与v5承载版两条登记路径：Demo不伪造实例/账号/记录修订；v5保留真实requestKey、账号、origin、创建时间及首次指纹，只排除副本。锁住稳定选集并核验全部业务值；写全幂等重入、冲突、未知来源、失败恢复伪码。发布前后有业务值变化、已有跨版key、账号已知/未知、副本及登记失败均需反例 |
| N0-R03 创建/重放与序列事务不足 | §1.4先校验current_version和upsert实例，再查跨版目录，与主方案“先判已有key”冲突；§2登记只锁scope，高水位setval却与所有创建共享序列竞争。§3只说hash一致，缺账号/C、作废和首次版本证据 | 给单一六步事务：同一序列咨询锁→范围锁→跨版key核验，已有key按首次冻结规格、原快照及账号/C返回同一记录，再判未命中的新建版本；不依赖现人员目录。登记与创建/切换按同锁序保护高水位，只进不退并保留is_called语义。CAS/作废按原版，目录/记录/审计/head原子。覆盖响应丢失→发布→旧key重放、跨账号/异载荷/已作废、跨范围同时登记/创建及失败回滚 |
| N0-R04 消费提交只有原则，没有正式合同 | §5.2用“新增列或伴随表”未选型；没有head_source_capture正式DDL，未明确替换pending_storage_version_id、FORM/COMMENT权威修订、无实例且无记录FORM资源、公开/结算和租约条件。捕获后再查集合未说明范围锁保持到提交，目录事件无领取/确认/重启游标 | 选定单一列/表、唯一键/索引及版本含义；写捕获→构建→在范围与实际head基表锁内复核→公开并结算的完整事务与CAS谓词，含目录/规格、head版本、权威类型/authority_revision/pending修订、有效租约；漂移零公开/零结算。给目录事件持久消费/确认/失败恢复及全量→增量交接。覆盖无记录FORM、FORM→COMMENT→FORM、目录/旧版编辑交错、过期worker、重启接管，不用当前指针替代源集合 |
| N0-R05 已确认业务选择器被改写 | §6.2的“九项”被换成数量/版本分布/导出口径等，遗漏主方案真实九项；“最近原因/方案”改成最新非空，可能从更早回复补值；合法计划又增加分支必须有效。§3.2把同走查人提示改按created_by查 | 逐项对齐主方案§3.5既有九项：最新整份响应、首次调研、has_response、D-21豁免、最新回复原因/合规、fix_user、known/new affected、解决延期、真实完成事件。按(createdAt,recordId)稳定排序，编辑不重排、作废重选，禁止逐字段非空回退；合法计划的用途按D-21分别解释，不新增分支门槛。MR同走查人软提示沿业务reviewerUserId+C跨版查询，归属仍是created_by；Issue不凭created_by泛化走查人规则。给非空样本及预期字段值，不重开用户已确认规则 |
| N0-R06 DTO与草稿迁移不符合现有契约 | §3记录ID/版本ID改number；草稿按不存在的contextKey+timer升级，省略draftId、editingSessionId、firstPendingSnapshot、savedElsewhere及输入/持久化修订，可能合并分叉副本或丢未知提交依据 | 按真实draft-store.ts v1键/字段逐项映射，保留账号/C/会话/块及首次提交证据；全部bigint身份按既有十进制字符串，区分原版记录定位、创建时版本和recordVersion。给字段规格去重的精确schemas/记录DTO与所有调用者。缺失版本有可核验恢复路径，不能永久unknown；新建未落库的旧版草稿与已有历史记录编辑分开处理。覆盖大ID、v1多标签分叉、旧key未知、401/换账号及原版CAS |
| N0-R07 共同字段可能全部不统计或误合并 | §6.1元数据可选且未声明一律不统计，现有三个种子没有该元数据；本期核心能力可能为空。semanticHash只包含key/unit/valueType，未纳入稳定字段身份、枚举/词典语义，不能证明同义；还一概按非NULL记录作分母 | 提交三模板既有字段的系统默认可比描述/稳定身份及修改规则；普通管理员不录技术码。比较必须验证稳定身份与真实含义/类型/单位、选项/词典语义，不能只hash三个文本键；同名新身份不并。声明选定版本/实际贡献版本、缺字段/NULL/0/作废及每个现有指标分母，沿已批公式，不创造通用非NULL分母。覆盖规范2+3=5、仅改名、删除后同名新增、同单位异义枚举和无共同字段 |
| N0-R08 消费影响、导出授权和验证入口未闭合 | §7.2以“表单不是看板”免除既有工作区/事实影响盘点，然而code-review-*、customer-issue-*已依赖MR/ISSUE事实；角色仅写“质量角色/与view一致”未列现行授权依据。§8.3的mvn test/通配命令没有固定隔离数据源与清理，可能触达默认15433 | 列全量/定向/周期/诊断/工作区/导出/写回资格的实际类/入口及读源替换；允许证明现有元数据无需变，但要有回归，不把本地表硬塞ODS白名单。导出给精确权限角色/defaults、版本/项目/C可见范围与禁止作废结果、固定输入、列类型/日期/姓名、流式资源上限/失败清理；新建空版本也可导出表头。提供pwsh可执行的最小分阶段命令，显式库/账号/密码或仅用已核实Testcontainers类、唯一容器ID及finally环境恢复；不再以通配mvn test作为可安全隔离的证据。只追加前向迁移，可按阶段多条，不为“一条”限制改已执行迁移 |

以上为设计合同缺项或与已确认设计冲突，不是宣称N1实现已经出错；本轮没有实施或写入产品迁移。

### Q1—Q9裁定

| 项 | 审核裁定 |
| --- | --- |
| Q1 | 同意目录instance_id仅真实旧Demo可空；原生记录须真实实例。删除“无物理实例却补建实例”的二选一，补齐版本目录/只读投影合同；不伪造ID或修订 |
| Q2 | 同意角色只有一处权威：版本列为主，集合需要时由视图派生；不得再独立双写source_role。最终SQL须单一化 |
| Q3 | 同意同库持久事件，无需消息队列；必须支持多进程领取/确认和重启恢复，不能仅供前端读取。协议见R04 |
| Q4 | 不批准无条件自动FAILED。须先停旧执行者，核对所有权/租约/栅栏再受控终止或排空，历史证据保留，见R01 |
| Q5 | 不批准改业务选择器；按已确认主方案直接修正，无需用户再次确认。MR级未批公式另行提交影响说明 |
| Q6 | 不批准未声明即全部不统计；首版必须覆盖已有共同字段。可选扩展不能削减已确认核心能力，见R07 |
| Q7 | 流式方向可接受；50k/64MB仅为待验证预算，不批准为可靠容量结论。提交估算方式、硬边界/超限行为、临时资源清理和固定负载证据；现场容量不由小夹具外推 |
| Q8 | 独立export权限已确认，角色名单未批；先从真实目录/迁移核实，给具体授予和不扩权证据。不是“view一致”即可猜授予 |
| Q9 | 真实E2是现场登记/切换前门禁，不一概阻断隔离N1研发；N1前必须完成代表性非空夹具与可执行登记合同。当前N1被R01—R08技术缺项阻断，不以E2未取得替代根因 |

### 验证与下一次提交

独立R05正式26项与既有审核3项全绿；员工74项/20张截图、908项全量及typecheck/lint/build冻结日志与指纹已核对，未独立再跑浏览器或全量。后端git树与97950222逐字相同，本轮不重跑旧后端，只引用此前冻结证据。隔离PG仅执行V20261022_01基础结构和本稿SQL块及元数据夹具：protocol列42703、新阶段23514、空实例元数据23502均为合同反例；没有运行N1业务功能。第一轮DDL探针因夹具重复插入基础种子失败，修正夹具后完成，两个本轮唯一容器均按ID删除，不计产品缺陷。

下一版先交N0修订稿及逐项对照、完整差异、原文/修订指纹和可执行DDL/事务/隔离测试入口；获N0批准后才实施N1。已关闭R05不再重复整改，不新增旧复制功能；主方案业务设计保持。执行进度及最终N1准入只归执行单和progress。

## 原提案（冻结输入，以下建议未获整体批准）

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
