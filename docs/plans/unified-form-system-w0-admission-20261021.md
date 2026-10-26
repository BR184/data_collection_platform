# 统一表单系统 W0 实施准入材料

> 依据：[统一表单方案 v5.1](unified-form-system-plan-20261009.md) 与[AI员工实施与审核清单](unified-form-system-implementation-plan-20261021.md)。
> 状态：2026-10-22 第五次技术审核通过，R01至R09在设计层全部关闭，W0通过，批准进入W1。批准版本与边界见第8.14节；员工先将获准技术契约归并主方案，再实施W1。
> 范围：仅实施调查与契约补全，不改业务代码、数据库、运行服务或生产数据。

## 进度与中间物

- 当前阶段：W0第五次技术审核通过；W1已准入、尚未开始实施，当前结论见§8.14。
- 已完成：开发基线、工作区与验证环境核实；缺失存储/任务/权威/切换清单正式定义；管理员草稿与发布事务边界；Issue 两模板完整字段规格与真实列；消费、权限、端点、工作区、统计/导出与写回影响面；逐阶段测试入口；E1—E6 与准入证据核对；R01—R09 逐项整改（失败重做、统一 fencing/锁顺序、切换事务 head 登记、首版标签、三初装路径、跨版本历史连续性、绑定锁定、原因合规与完整消费投影、隔离测试入口）。
- 未完成：获准技术契约归并主方案及W1至W8实施。员工从W1存储与迁移基础开始，后续阶段继续按实施清单逐阶段提交审核。1
- 本次改动文件：本文件；[实施与审核清单](unified-form-system-implementation-plan-20261021.md) W0 状态行与交付记录；[项目进度](../progress.md) 表单条目。
- 验证状态：W0 仅做只读调查、文档自检与既有表单定向测试；未运行黄金、事实重建、迁移或浏览器验收。定向测试 `CollectFormServiceTest` 5 项 + `CollectFormControllerTest` 6 项 = 11 项通过/0 失败（exit 0），仅证明 Maven/JDK 与既有入口可用，不代表目标功能已实现。
- 恢复线索：先运行 `git status --short`，读 [AGENTS.md](../../AGENTS.md)、[项目进度](../progress.md)、主方案 v5.1、实施清单与本文件；按“审核结论”决定是否进入 W1。
- 共享工作树提醒：除本单元文件外，工作树还有 BI 口径、系统测试缺陷原因模板等其它任务改动；本单元不暂存、不提交、不推送这些文件。

## 目标与边界

W0 只解决“能否按已确认设计开始编码”的准入问题：

1. 把主方案已表达、但缺少物理定义的状态表、草稿表、发布任务、变更交接、资源权威和切换清单补成可实现的正式契约。
2. 把 Issue 两模板的字段、条件、枚举、词典版本、API 键和物理列补成单一规格。
3. 盘点目标代码的真实消费点、登记项、旧入口退役面和验证入口。
4. 记录开发基线、隔离环境、外部依赖与启动批准依据。

不在 W0 内：写业务代码、建表、改权限、改事实/统计、生成快照、启停服务、连接内网或处理生产数据。

## 开发基线与工作区

### 仓库与版本

| 项 | 当前事实 |
| --- | --- |
| 仓库 | `D:\projects\data_collection_platform` |
| 分支 | `main` |
| HEAD | `84e7c023449de190e722d79d027accb062358172` |
| 最近提交 | `84e7c023 docs: 回填发布读取架构、决策与工作记录` |
| 远端 | `gitlab25`、`origin` 为 AGENTS 固定双远端；`gitlab`、`gitlab-http`、`blueprint` 仅用户明确指定时使用 |
| 本轮推送 | 不推送。工作树混合多个任务，且用户未指定远端；按 AGENTS 与执行单保持本地交付 |

### 工作区差异（2026-10-21 核实）

暂存区为空。未暂存改动：

| 文件 | 归属判断 | 本单元处理 |
| --- | --- | --- |
| `docs/plans/unified-form-system-plan-20261009.md` | 表单交接编辑（v5.1 链接与 S0 描述） | 保留，不覆盖 |
| `docs/plans/unified-form-system-implementation-plan-20261021.md` | 本单元执行单 | 更新 W0 状态与记录 |
| `docs/plans/unified-form-system-w0-admission-20261021.md` | 本单元新增 | 本文件 |
| `docs/progress.md` | 混合：表单交接条目与其它任务条目 | 只改表单条目 |
| `docs/product.md` | 其它任务（系统测试缺陷原因模板） | 不动 |
| `docs/bi-dashboard/progress.md` | 其它任务（客户活动口径） | 不动 |
| `docs/plans/system-test-defect-cause-template-2027r1.md` | 其它任务 | 不动 |

与交接文档“暂存区另有既有计划及 analytics-read-navigation.ts 末尾空行”的描述不同：本次核实暂存区为空，且工作树未见该前端文件改动。W0 以当前 `git status` 为准，不据此推断他人工作已完成或丢失。

### 允许修改范围

W0：仅本文件、实施清单状态行/交付记录、进度文档表单条目。

W1 起（审核通过后）：主方案第三章补全差异、`backend/src/main/resources/db/migration/` 新增迁移与 checksum 追加、表单后端服务/控制器/Mapper、前端表单页与管理页、对应测试、端点目录/数据库查看/权限/工作区登记、必要的事实契约与迁移。不得改动其它任务的 BI/客户统计/系统测试模板文件。

### 当前运行服务与隔离环境

| 入口 | 实际身份 | W0 处置 |
| --- | --- | --- |
| `18080` | 主树后端，PID 22956，Java 21，Spring Boot classes，`spring.devtools.restart.enabled=false`，UTF-8 | 未启停；W1 起定向验证优先复用，发布门禁前按 AGENTS 记录并恢复 |
| `18181` | Docker `qaflex-local-latest-18181-frontend-1`（->80），配套 backend 18581、PostgreSQL 15581；2026-10-20 正式候选本地栈 | 未启停、不复用为开发库 |
| `15432` | Docker `qaflex-dev-postgres-15432` 本地开发库 | 只读核对；不得作为测试清库目标 |
| `15433` | Docker `qaflex-test-postgres-15433` 测试库，`qaflex_test` schema 122 表，Flyway 最新 `20261015.03` | 共享测试库，只读参考；**不作为新表单测试目标**，表单测试使用 15446 独立容器或 Testcontainers（R09） |
| Java | `tools/jdk/jdk-21.0.10+7/bin/java.exe` | Maven 使用该 JDK |
| Maven | `tools/maven/apache-maven-3.9.9/bin/mvn.cmd` | 串行执行，不使用系统 `mvn` |
| Node/npm | `v24.18.0` / `11.16.0` | 前端验证 |
| Docker | 可用；存在多项历史演练容器 | 不清理、不改名、不复用业务卷 |

本地开发库存量样本（只读，不代表内网）：

- `collect_form_records`：1 行，`code_review/merge_request`，`deleted=true`，`reviewer` 空白。
- `gitlab_sync_configs`：1 条，`Local GitLab`，`enabled=true`，`web_base_url=http://127.0.0.1`，`source_instance=default`，`whitelist_mode=RECOMMENDED`。
- `platform_ldap_users`：270 条，空 `real_name` 为 0。
- `ods_gitlab_milestones`：项目 9=14、172=3、325=9、374=1（未删除）。

结论：本地 S 只有 1 条作废走查行，不能代表内网存量；W1 的“代表性非空迁移”必须在隔离库构造混合样本，真实全量留 E2。

## 技术契约补全（拟写回主方案第三章）

以下为唯一拟用方案。审核通过后按编号写回主方案 §3；未通过前不得据此编码。

### 1. 范围写入栅栏与管理员草稿

#### 1.1 范围表补列

```sql
alter table form_template_scopes
    add column updated_at timestamp not null default current_timestamp,
    add column write_fenced boolean not null default false,
    add column write_fence_reason varchar(64),
    add column write_fence_publication_id bigint,
    add column write_fence_token varchar(64),
    add column write_fence_at timestamp,
    add column binding_revision bigint not null default 0 check (binding_revision >= 0),
    add column binding_hash char(64);
```

`write_fenced=true` 表示结构发布/退役期间暂停该范围的记录写入；读取仍使用 `current_version_id` 指向的完整版本。写入事务必须在同一事务内先执行：

```sql
select status, write_fenced, current_version_id
  from form_template_scopes
 where id = :scopeId
   for update;
```

仅当 `status='ACTIVE'`、`write_fenced=false` 且请求 `storageVersionId=current_version_id` 时继续。否则返回 409/A0409，不写记录、审计或 head。该行锁同时承担“排空在途写入”：发布任务取得行锁设置栅栏时，必须等待所有已持有该行锁的写事务提交或回滚。

栅栏必须带归属：`write_fence_publication_id` 记录持有任务，`write_fence_token` 由任务在设置栅栏时生成（`{publicationId}:{fencingVersion}:{attemptNo}`），`write_fence_at` 记录时间。清除栅栏只能按 `(scopeId, publicationId, token)` 条件更新；旧任务、过期租约或新任务已接管时，旧 token 的影响 0 行，不得撤销新任务的保护。维护清理只能清除“所属任务已 FAILED/不存在且无更新任务持有”的栅栏。

#### 1.2 管理员草稿

```sql
create table form_template_scope_drafts (
    scope_id bigint primary key references form_template_scopes(id) on delete cascade,
    draft_revision bigint not null default 1 check (draft_revision > 0),
    field_spec jsonb not null,
    source_config_id bigint,
    source_instance varchar(128),
    canonical_gitlab_base_url varchar(255),
    project_ids jsonb not null default '[]'::jsonb
        check (jsonb_typeof(project_ids) = 'array'),
    version_project_id bigint,
    version_label varchar(64),
    version_evidence jsonb,
    binding_hash char(64) not null,
    migration_spec jsonb,
    updated_by varchar(128) not null,
    updated_at timestamp not null
);
```

- 绑定信息以草稿为准，发布事务才写回 `form_template_scopes`；DRAFT 范围不影响填写页。
- 保存使用 CAS：`update ... set ..., draft_revision = draft_revision + 1 where scope_id=? and draft_revision=?`。影响 0 行返回 409/A0409。
- 草稿保存与发布互斥：保存先锁范围行；若存在 `PREPARED/COPYING/VERIFIED` 发布任务，返回 409“发布任务进行中，草稿只读”。
- 草稿可删除；删除只允许 `DRAFT` 范围且无实例、记录、发布任务。

#### 1.3 预览证据

```sql
create table form_template_preview_evidence (
    id bigserial primary key,
    scope_id bigint not null references form_template_scopes(id) on delete cascade,
    draft_revision bigint not null,
    field_spec_hash char(64) not null,
    binding_hash char(64) not null,
    preview_hash char(64) not null,
    preview_json jsonb not null,
    blocking_errors jsonb not null default '[]'::jsonb,
    created_by varchar(128) not null,
    created_at timestamp not null,
    unique (scope_id, draft_revision)
);
```

`preview_hash` 是规范化预览 JSON 的 SHA-256；`blocking_errors` 非空时预览不得进入发布。发布任务冻结 `preview_id` 与两个哈希，发布时重新计算并比对，防止预览后被替换。

### 2. 持久发布任务与受控 DDL 事务

#### 2.1 正式表

```sql
create table form_template_publications (
    id bigserial primary key,
    scope_id bigint not null references form_template_scopes(id),
    request_key varchar(64) not null,
    request_hash char(64) not null,
    draft_revision bigint not null,
    preview_id bigint not null references form_template_preview_evidence(id),
    publication_kind varchar(16) not null
        check (publication_kind in ('INITIAL_EMPTY', 'INITIAL_MIGRATION', 'MIGRATION')),
    expected_current_version_id bigint,
    from_version_id bigint,
    to_version_id bigint not null,
    frozen_field_spec jsonb not null,
    frozen_migration_spec jsonb,
    frozen_binding jsonb not null,
    binding_hash char(64) not null,
    version_evidence jsonb not null,
    ddl_summary jsonb not null,
    ddl_hash char(64) not null,
    selection_counts jsonb not null,
    selection_captured_at timestamp,
    copy_checksum jsonb,
    verification_summary jsonb,
    stage varchar(16) not null
        check (stage in ('PREPARED', 'COPYING', 'VERIFIED', 'PUBLISHED', 'FAILED')),
    attempt_no integer not null default 0 check (attempt_no >= 0),
    recovery_cycle integer not null default 0 check (recovery_cycle >= 0),
    task_version bigint not null default 0 check (task_version >= 0),
    lease_owner varchar(128),
    lease_until timestamp,
    fencing_version bigint not null default 0 check (fencing_version >= 0),
    fence_token varchar(64),
    next_attempt_at timestamp,
    recovery_verified_at timestamp,
    recovery_evidence jsonb,
    last_error text,
    operator_id varchar(128) not null,
    operator_name varchar(128),
    created_at timestamp not null,
    updated_at timestamp not null,
    published_at timestamp,
    unique (scope_id, request_key),
    unique (to_version_id),
    foreign key (scope_id, expected_current_version_id)
        references form_template_versions(scope_id, id),
    foreign key (scope_id, from_version_id)
        references form_template_versions(scope_id, id),
    foreign key (scope_id, to_version_id)
        references form_template_versions(scope_id, id),
    check (
        (publication_kind = 'INITIAL_EMPTY'
         and from_version_id is null and expected_current_version_id is null)
        or (publication_kind = 'INITIAL_MIGRATION'
            and from_version_id is not null and expected_current_version_id is null)
        or (publication_kind = 'MIGRATION'
            and from_version_id is not null
            and expected_current_version_id = from_version_id)
    ),
    check (stage <> 'PUBLISHED' or published_at is not null),
    check (stage <> 'COPYING' or fence_token is not null)
);

create unique index uq_form_publications_active
    on form_template_publications(scope_id)
    where stage in ('PREPARED', 'COPYING', 'VERIFIED');

create index idx_form_publications_claim
    on form_template_publications(stage, next_attempt_at, lease_until)
    where stage in ('PREPARED', 'COPYING', 'VERIFIED');

create table form_template_publication_attempts (
    id bigserial primary key,
    publication_id bigint not null references form_template_publications(id) on delete cascade,
    attempt_no integer not null check (attempt_no > 0),
    phase varchar(16) not null
        check (phase in ('COPY', 'VERIFY', 'PUBLISH', 'FAILED')),
    selection_captured_at timestamp not null,
    selection_counts jsonb not null,
    copy_checksum jsonb,
    verification_summary jsonb,
    error text,
    started_at timestamp not null,
    finished_at timestamp,
    unique (publication_id, attempt_no)
);
```

- `request_hash` 为同一 `(scope_id, request_key)` 的首次载荷指纹；同 key 异哈希返回 409/A0409，同 key 同哈希返回原任务。
- `to_version_id` 在 PREPARED 前由服务端分配，目标版本行以 `INSTALLED` 登记；同一版本只能被一个任务冻结。
- `publication_kind` 三路径：`INITIAL_EMPTY` 空平台首启，无来源迁移；`INITIAL_MIGRATION` 已有 `collect_form_records` S 的首版迁移，`from_version_id` 指向 LEGACY、`expected_current_version_id` 为空；`MIGRATION` 已有 RECORDS 版本之间的结构迁移，`expected_current_version_id=from_version_id`。
- `version_evidence` 必须包含本次 `version_label` 的来源配置、项目、里程碑 ID/原文、映射与核验时间；缺证据或空白标签不得准备发布（R04）。历史迁移行的产生标签为 NULL 是记录级例外，不是新发布标签例外。
- `binding_hash` 冻结本次绑定；首次发布写回范围并递增 `binding_revision`，后续普通字段发布必须与范围当前绑定哈希相同（R07）。
- `selection_counts/selection_captured_at/copy_checksum` 只描述**当前 attempt**；每次重试必须新开 attempt、重新停写、重新捕获完整 S、清空目标后重新复制，禁止沿用旧副本（R01）。历史 attempt 在 `form_template_publication_attempts` 保留，供重审与恢复核对。
- `ddl_summary` 保存每张目标表的物理名、列、约束、索引与列指纹；`ddl_hash` 是其规范化哈希。目标结构在重试间不变；若结构漂移，任务只能终止并由新发布分配新目标版本。

#### 2.2 阶段与状态迁移

| 当前 | 事件 | 下一状态 | 事务/锁 |
| --- | --- | --- | --- |
| — | POST publications，校验通过 | PREPARED | 锁范围行与草稿行，CAS 预期指针，插入任务 |
| PREPARED | 取得租约、设置带归属栅栏、清空目标 | COPYING | 锁范围行与任务行；新 attempt；任务 CAS `(task_version, fencing_version)` |
| COPYING | DDL 与从新冻结 S 复制完成，行数/键守恒通过 | VERIFIED | 复制完成后写 attempt `verification_summary` |
| VERIFIED | 完整核验通过，切换指针 | PUBLISHED | 锁范围行与任务行，CAS `current_version_id`，写迁移日志与 head，按 token 撤栅栏 |
| PREPARED/COPYING/VERIFIED | 不可恢复失败 | FAILED | 保留旧指针；当前租约持有者按 token 撤栅栏并记录错误 |
| FAILED | 显式恢复，结构/哈希核验通过 | COPYING | 递增 `recovery_cycle`；**重新停写、重新捕获完整 S、清空目标、新 attempt 复制**；写 `recovery_verified_at/evidence` |
| FAILED | 终止 | FAILED | 保持终态，目标结构只作诊断，不复用为可读版本 |

恢复规则（R01）：

- FAILED 后旧版本继续可写；旧目标副本只作诊断，**不得**在恢复后直接沿用。恢复必须先由当前任务重新取得栅栏，重新捕获捕获时刻的完整 S（含失败期间新增、修改、作废的全部行），再清空目标表（`truncate` 或重建同结构表），从新 S 全量复制并重新完成守恒核验。
- 旧副本与任何失败前冻结的 `selection_counts` 不能作为切换依据；切换事务只接受当前 attempt 的 `selection_captured_at/selection_counts/copy_checksum/verification_summary`。
- 若目标结构、`ddl_hash` 或列指纹与冻结值不一致，或目标被非本任务写入，不得恢复；该任务终止，另开新发布并分配新目标版本。
- 恢复前必须确认该范围没有其它 `PREPARED/COPYING/VERIFIED` 任务；若已有新任务，旧 FAILED 任务只能终止，不能恢复、切指针或撤销新任务栅栏。
- PUBLISHED 为终态；同一 `to_version_id` 不得再次切换。

#### 2.3 租约、fencing 与统一锁顺序（R02）

**统一锁顺序**：`form_template_scopes` 行 → `form_template_publications` 行 → 目标表/版本元数据 → `form_change_heads`（按 id 升序）。记录写入为 scope → instance → record → audit → head；权威登记为 scope → authority → head；消费者只锁 head。任何路径不得反向取锁，禁止“先 head 后 scope”。

**fencing 前置校验（取得锁之后按数据库实际时刻）**：任何持久副作用（阶段推进、DDL、复制、设置/清除栅栏、写迁移日志、切指针、登记 head、清理目标）都必须在同一事务内先按统一锁顺序取得全部必要锁，再锁定任务行并用 `clock_timestamp()` 验证。`current_timestamp` 固定为事务开始时间，等待锁后可能已经过期，不得用于租约判断：

```sql
select id from form_template_publications
 where id = :publicationId
   and scope_id = :scopeId
   and stage = :expectedStage
   and lease_owner = :owner
   and fencing_version = :fencingVersion
   and lease_until > clock_timestamp()
   for update;
```

影响 0 行即失效，任务不得执行该副作用。校验通过后在同一事务内立即续租到覆盖本次临界区的预算：

```sql
update form_template_publications
   set lease_until = clock_timestamp() + :criticalBudget
 where id = :publicationId
   and lease_owner = :owner
   and fencing_version = :fencingVersion
   and stage = :expectedStage
   and lease_until > clock_timestamp();
```

影响 0 行同样终止。`statement_timeout` 只限制单条语句的执行时间，不能当作事务累计超时；临界区开始前把它设为单条语句的安全上限，并在全部写操作完成后、提交前用 `clock_timestamp()` 复核租约。多条语句各自未超时但累计超过租约时，最终校验返回失效并回滚事务。DDL/复制事务必须持有该任务行锁直到提交；租约过期后的新工作者会等待旧事务结束，再以新 `fencing_version` 接管。旧工作者迟到提交时，其已提交的结构/复制只作为诊断，新一次 attempt 会在新栅栏下清空并重复制，不能切指针。

**持锁长事务到期边界**：

- 仅在事务入口、等待锁之前完成的校验无效。设置栅栏、复制、核验、切换等每个独立事务都要在取得 scope 行锁与任务行锁之后执行上式；锁等待使租约在等待期间到期时，校验返回 0 行，整个事务回滚，不写阶段、结构、栅栏、指针或 head。
- 续租后的 `criticalBudget` 必须按该事务的最坏累计工作量设定；`statement_timeout` 只防止单条语句无限执行，不提供累计预算。复制等长操作不得放进切换事务。切换事务只包含指针/版本/绑定/迁移日志/head 登记/撤栅栏等短操作，累计是否仍在租约内由提交前的实际时刻校验决定。
- **允许提交的诊断写入**：任务自身仍持有有效租约时，可在失效事务回滚后另开短事务写 `form_template_publication_attempts.phase='FAILED'`、`error/finished_at`、任务 `last_error/next_attempt_at`，并把 `stage` 置 FAILED；写入条件仍是 `lease_owner/fencing_version` 与最新 attempt 匹配，且不得触碰指针、版本状态、绑定、迁移日志、head、栅栏。已被新任务接管时，旧工作者连诊断也不得覆盖新任务记录。
- **过期租约回收**：租约已过期时，旧工作者无权写 `stage=FAILED`。维护调度以独立事务锁任务行，仅当 `lease_until <= clock_timestamp()` 且不存在同范围更高 `fencing_version` 的活动任务时，置 `stage='FAILED'`、写 `last_error='LEASE_EXPIRED'`、按 token 清除本任务栅栏并释放未结束任务占用；该回收不得切指针、发布版本或登记 head。租约仍有效时回收不生效，由持有者自行收尾。
- **必须回滚或终止的发布/结算**：指针 CAS、版本 PUBLISHED/RETIRED、绑定写回、迁移日志、head 登记与 head 结算、记录/派生结果公开，只要任一 fencing 或续租校验影响 0 行，就回滚事务并终止本次发布；旧指针与旧完整读取保持不变。切换事务提交前在最后一条写语句之前再次执行 `lease_until > clock_timestamp()` 校验，失败即回滚。租约在提交瞬间到期不改变已提交结果的有效性，但下一次操作必须重新取得有效租约。
- 受控交错验收：①事务在租约到期前开启并等待 scope/head 锁，到期后才取得锁 → fencing 影响 0 行，无指针/head/栅栏变化；②续租后单条语句超时 → `statement_timeout` 中止，事务回滚，旧指针不变，attempt 记录失败；③多条语句各自未超时但累计超过租约 → 提交前 `clock_timestamp()` 校验失败，事务回滚，旧指针不变；④旧工作者恢复后诊断写入成功但发布写入影响 0 行。

**栅栏副作用**：

- 设置：锁范围行 → 锁任务行并按上式校验 → `update form_template_scopes set write_fenced=true, write_fence_publication_id=:id, write_fence_token=:token, write_fence_at=clock_timestamp() where id=:scopeId`。
- 清除：`... where id=:scopeId and write_fenced=true and write_fence_publication_id=:id and write_fence_token=:token`；影响 0 行表示已被新任务接管或栅栏不属于本任务，必须停止并报告。
- 维护清理：只允许所属任务 `FAILED`/不存在且没有更新任务持有栅栏时，按同一 token 条件清除；不得按范围无条件清除。

**切换事务**：锁范围行 → 锁任务行并按上式校验并续租 → 校验当前指针仍为预期 → 执行指针/版本/绑定/迁移日志/head 登记 → 最后一条写语句前再次按 `clock_timestamp()` 校验租约 → 按 token 撤栅栏 → 提交。任一步失败全部回滚，指针、版本、绑定、head 与栅栏保持切换前状态；失效任务只按“允许提交的诊断写入”记录失败。

**head 结算**：领取时写 owner/lease/fencing。结算必须在结果公开的同一事务内、取得 head 行锁之后执行，并满足 `status='LEASED' and lease_owner=:owner and fencing_version=:fencingVersion and lease_until > clock_timestamp()`；影响 0 行时连同已写入的派生结果整批回滚，工作者不得重试同一捕获结果，必须重新领取并重建。版本复核与结算的原子条件见 §3.1。

**复制事务与租约预算**：复制开始前按上式在取得锁后用 `clock_timestamp()` 续租，租约为“当前实际时间 + 实测复制事务上限 + 余量”；DDL/复制事务持有任务行锁期间无法被他人续租，租约必须覆盖整个事务。`statement_timeout` 只限制单条语句，累计超预算由提交前实际时刻校验拦截；任一校验失败即回滚。事务结束若租约已过期，不得进入切换，按失效/重取处理；超过预算时先评审分批/缩短事务方案，不得以过期租约继续写或让两个工作者同时复制。进程重启不依赖内存任务；维护调度重新领取过期租约，先核对任务、目标结构与 attempt 证据，再按新 attempt 复制；不存在“直接沿用旧复制结果”的恢复路径。

每范围最多一个未结束任务，由部分唯一索引保证；重复发布并发只有一个能进入 PREPARED。

#### 2.4 发布事务边界

1. **准备事务**：锁范围行与草稿行；校验 `expected_current_version_id` 与当前指针、无未结束任务、`draftRevision`/预览哈希；校验 `version_label` 非空白且有 `version_evidence`（R04）；校验绑定规则（R07）；分配目标版本及物理名；插入 PREPARED。提交后范围仍可写。
2. **停写事务**：锁范围行 → 锁任务行并校验租约/fencing → 生成 `fence_token` → 设置 `write_fenced=true` 与归属列 → 提交。写入事务先锁同一范围行，提交成功即代表在途写入已排空。读取不经该锁，继续读旧版本。
3. **复制 attempt**：新开 `form_template_publication_attempts`；在已栅栏范围上以 `REPEATABLE READ` 捕获完整 S（含 deleted 行）并记录 `selection_captured_at/counts`；清空目标表（新表或 `truncate`），验证为空；执行受控 DDL 与全量复制。目标结构/`ddl_hash` 不符时终止；不得沿用任何旧 attempt 的复制数据。所有标识符由服务端生成并校验 ASCII/63 字节。目标容量不足时停止并进入 FAILED，不改为非事务分批。
4. **核验**：逐项完成 S 计数、完整实例键去重、NULL 安全字段比对、ID/时间/归属/产生版本守恒、目标集合外零混入；写当前 attempt 的 `selection_counts/copy_checksum/verification_summary`，进入 VERIFIED。
5. **切换事务**：锁范围行 → 锁任务行并按 §2.3 在取得锁后用 `clock_timestamp()` 校验并续租 → 再次校验 `current_version_id` 仍为预期；CAS 更新指针；新版本 `PUBLISHED`，旧版本 `RETIRED`；仅首次发布写回草稿绑定并递增 `binding_revision`；`status` 置 ACTIVE；按发布类型写迁移日志；**登记受影响资源的 head**；任务置 PUBLISHED；按 token 撤栅栏。上述写操作全部完成后、提交前再次校验租约仍有效，失败即回滚。任一步失败全部回滚，指针不变；事务累计超出续租预算由提交前实际时刻校验拒绝，`statement_timeout` 只限制单条语句。
6. **失败**：任何步骤失败进入 FAILED，旧指针与旧完整读取保持；当前租约持有者按 token 撤栅栏并记录错误与诊断；不得撤销新任务的栅栏。失败任务不自动无限重试。

**切换事务中的 head 登记（R03，覆盖无实例资源）**：结构变化后，即使没有后续用户保存，也必须让下游感知受影响资源。受影响资源不能只取目标版本实例，定义为：

- A = 本范围本模板 `form_resource_authorities.authority='FORM'` 的全部资源，无论是否已有实例或记录；
- B = 目标版本 `<instances>` 的全部资源，覆盖初始迁移与防御性集合；
- 受影响集合 = A ∪ B。COMMENT 权威不进入该集合，其 head 的 `pending_storage_version_id` 必须为 NULL。

**初始化与不变量 I-R03**：

- FORM 权威登记（镜像资源到达时）在同一事务创建/更新 head：范围已有 `current_version_id` 时 `pending_storage_version_id=current_version_id` 并递增 `latest_change_version`；尚未激活/无版本时保持 NULL，由首次 `INITIAL_EMPTY` 切换写入。无实例不创建空实例或记录。
- 结构切换事务把 A∪B 全部 head 的 `pending_storage_version_id` 设为 `toVersionId` 并递增 `latest_change_version`。
- **不变量 I-R03**：ACTIVE 范围内，每个 FORM 权威资源都有 head，且 `head.pending_storage_version_id = scope.current_version_id`；每个 COMMENT 权威资源对应 head 的 `pending_storage_version_id is null`。切换事务提交前核验该不变量，违反即回滚。

**切换事务内执行顺序**（锁顺序仍为 scope → publication → 版本元数据 → head id 升序）：

```sql
-- 1. A/B 中尚缺 head 的资源先补建（只建 head，不建实例/记录）；
--    authority 与 instance 的写入都要先锁同一 scope，切换持有 scope 锁期间集合稳定。
insert into form_change_heads(
    source_config_id, source_instance, canonical_gitlab_base_url,
    project_id, resource_type, resource_id, template_key, scope_id,
    latest_change_version, consumed_change_version,
    latest_audit_id, pending_storage_version_id,
    status, created_at, updated_at)
select a.source_config_id, a.source_instance, a.canonical_gitlab_base_url,
       a.project_id, a.resource_type, a.resource_id, a.template_key, a.scope_id,
       1, 0, null, :toVersionId, 'PENDING', :now, :now
  from form_resource_authorities a
 where a.scope_id = :scopeId
   and a.template_key = :templateKey
   and a.authority = 'FORM'
on conflict (source_config_id, project_id, resource_type, resource_id, template_key) do nothing;

insert into form_change_heads(
    source_config_id, source_instance, canonical_gitlab_base_url,
    project_id, resource_type, resource_id, template_key, scope_id,
    latest_change_version, consumed_change_version,
    latest_audit_id, pending_storage_version_id,
    status, created_at, updated_at)
select i.source_config_id, i.source_instance, i.gitlab_base_url,
       i.project_id, i.resource_type, i.resource_id, i.template_key, i.scope_id,
       1, 0, null, :toVersionId, 'PENDING', :now, :now
  from <instances> i
 where i.scope_id = :scopeId
on conflict (source_config_id, project_id, resource_type, resource_id, template_key) do nothing;

-- 2a. A ∪ B 只用于求 ID 集合；此处不取得任何行锁
with affected as (
    select h.id
      from form_change_heads h
      join form_resource_authorities a
        on a.source_config_id = h.source_config_id
       and a.project_id = h.project_id
       and a.resource_type = h.resource_type
       and a.resource_id = h.resource_id
       and a.template_key = h.template_key
       and h.scope_id = a.scope_id
     where a.scope_id = :scopeId
       and a.template_key = :templateKey
       and a.authority = 'FORM'
    union
    select h.id
      from form_change_heads h
      join <instances> i
        on i.source_config_id = h.source_config_id
       and i.project_id = h.project_id
       and i.resource_type = h.resource_type
       and i.resource_id = h.resource_id
       and i.template_key = h.template_key
       and h.scope_id = i.scope_id
     where i.scope_id = :scopeId
)
select id from affected;  -- 应用层保存为 affectedIds

-- 2b. 最终锁定查询直接读取并锁定 form_change_heads 基表行，按 id 升序；
--     返回行数必须等于 cardinality(affectedIds)，否则回滚。
select id
  from form_change_heads
 where id = any(:affectedIds)
 order by id
 for update;

-- 3. 更新同一 affectedIds 集合，保留既有审计证据
update form_change_heads h
   set latest_change_version = h.latest_change_version + 1,
       pending_storage_version_id = :toVersionId,
       status = case when h.status = 'LEASED'
                      and h.lease_until > clock_timestamp() then 'LEASED'
                     else 'PENDING' end,
       updated_at = :now
 where h.id = any(:affectedIds);
```

**提交前最终核验**（三项计数均须为 0，否则整个切换事务回滚）：

```sql
-- 3.1 每个 FORM 权威资源都有 head 且存储证据等于目标版本
select count(*) from form_resource_authorities a
  left join form_change_heads h
    on h.source_config_id = a.source_config_id
   and h.project_id = a.project_id
   and h.resource_type = a.resource_type
   and h.resource_id = a.resource_id
   and h.template_key = a.template_key
   and h.scope_id = a.scope_id
 where a.scope_id = :scopeId and a.template_key = :templateKey and a.authority = 'FORM'
   and (h.id is null or h.pending_storage_version_id is distinct from :toVersionId);

-- 3.2 每个目标实例都有 head、pending 等于目标版本，且权威为 FORM
select count(*) from <instances> i
  left join form_change_heads h
    on h.source_config_id = i.source_config_id
   and h.project_id = i.project_id
   and h.resource_type = i.resource_type
   and h.resource_id = i.resource_id
   and h.template_key = i.template_key
   and h.scope_id = i.scope_id
  left join form_resource_authorities a
    on a.source_config_id = i.source_config_id
   and a.project_id = i.project_id
   and a.resource_type = i.resource_type
   and a.resource_id = i.resource_id
   and a.template_key = i.template_key
   and a.scope_id = i.scope_id
 where i.scope_id = :scopeId
   and (h.id is null or h.pending_storage_version_id is distinct from :toVersionId
        or a.id is null or a.authority <> 'FORM');

-- 3.3 本范围本模板不存在未被 A ∪ B 覆盖的 FORM 存储证据 head
select count(*) from form_change_heads h
 where h.scope_id = :scopeId and h.template_key = :templateKey
   and (h.pending_storage_version_id is not null or h.pending_record_id is not null)
   and h.id <> all(:affectedIds);
```

**锁实现边界（R03-a）**：`WITH affected` 只作为 ID 筛选，不承担行锁；2b 的 `SELECT ... FOR UPDATE` 直接读取 `form_change_heads` 基表，按 `id` 升序取得实际行锁，并保持到第 3 步更新、最终核验和提交。2a 到 2b 之间不得提交或重新开始事务；head 只能由持有同一 scope 锁的权威登记/切换事务创建，切换持有 scope 锁期间 affectedIds 稳定；2b 返回行数少于 cardinality(affectedIds) 时立即回滚。W4/W5 必须以真实 PostgreSQL 双会话验证：锁定查询执行后另一会话修改同一 head 会等待；两行物理顺序与 ID 顺序不同时仍按 ID 升序；已有实例与无实例资源、消费与切换交错均按统一顺序。

- 保留既有 `latest_audit_id`、pending 记录证据与 `consumed_change_version`；纯结构变化不伪造审计。
- 无实例资源通过 A 进入 affectedIds，head 版本与存储证据在切换事务内更新；消费者之后取得该 head 锁时，捕获的 `pending_storage_version_id`（NULL 或旧版本）与当前 `toVersionId` 不等，结果提交必被拒绝。该保护不要求创建空实例或空记录。
- 首次空范围启用时 A/B 均为空，affectedIds 为空，三项核验计数为 0，切换正常完成；之后登记 FORM 权威时按 I-R03 初始化 `pending_storage_version_id=current_version_id` 并递增 head，触发对未填写资源的必要重建。
- `INITIAL_MIGRATION` 前必须按切换清单为每个迁移资源登记 FORM 权威与 head；B 中存在实例但缺少 FORM 权威时 3.2 计数非 0，切换失败，不生成空记录。
- COMMENT 权威不得拥有目标版本实例；FORM→COMMENT 转换必须先按独立迁移处置既有实例，否则 3.2 检测到 `authority <> 'FORM'` 或 3.3 检测到未覆盖的存储证据并使切换失败。
- 切换事务对受影响 head 行按 id 升序加锁并递增版本，与消费者的结果提交事务在同一批 head 行上串行化。旧候选若在切换前已完整提交，其公开结果发生在切换前，切换随后递增版本、以新版本待消费；若切换先提交，旧候选在结果提交时发现存储证据或权威修订不匹配，整批结果不写、零结算，不得公开旧结构结果。
- head 补建或核验失败（如实例缺少来源身份、唯一键冲突、I-R03 违反）使整个切换事务回滚，指针不切换；该事务的规模上限由 W4 在目标范围规模上实测，超出预算必须先评审，不得静默分批后切指针。

**发布类型与迁移日志**：

- `INITIAL_EMPTY`：无 `from_version`、无 LEGACY、无迁移日志；`selection_counts` 记录全零。
- `INITIAL_MIGRATION`：`from_version` 为 LEGACY，`expected_current_version_id` 为空；必须写 `form_template_migration_logs(scope_id, LEGACY, 新RECORDS)`，其中 `verification_summary` 包含逐字段守恒证据。LEGACY 行必须在目标记录复制前插入。
- `MIGRATION`：`from_version` 为当前 PUBLISHED RECORDS；写迁移日志。所有类型都必须在切换事务内登记 head。

#### 2.5 首次启用与退役

- 空平台：Flyway 只安装模板定义、能力表与权限；范围保持 DRAFT。管理员创建范围、绑定来源/项目、选择真实版本标签及证据、预览并以 `INITIAL_EMPTY` 发布，范围才 ACTIVE。缺标签证据或标签为空白的范围保持 DRAFT/INSTALLED，不得发布（R04）。
- 已有 `collect_form_records` S：按 §2.9 的 `INITIAL_MIGRATION` 路径；不得与空平台首启合并。
- 退役：锁范围行，CAS `current_version_id`，`status=RETIRED`、`write_fenced=true`；历史读取和消费继续使用当前完整版本；新增/编辑/作废拒绝。
- 删除范围：只允许 DRAFT、无版本表、无实例/记录、无发布任务；删除草稿、预览与范围行。

#### 2.6 LEGACY 来源版本表示

迁移记录的 `origin_version_id` 必须指向真实版本行，但旧 `collect_form_records` 没有实例/版本表。统一表示：

- 仅在 `INITIAL_MIGRATION` 路径创建一行 `storage_kind='LEGACY'`、`version_status='RETIRED'` 的来源版本：`instance_table='collect_form_records'`（共享旧表名，仅作来源标识）、`record_table=null`、`field_spec` 为旧列到稳定键的只读映射、`migration_spec=null`。
- 创建顺序：准备/停写事务先插入 LEGACY 版本行，再分配目标 RECORDS 版本；复制记录时 LEGACY 已存在，满足 `origin_version_id` 外键。范围 `current_version_id` 在 switch 前保持 NULL。
- 迁移完成后写 `form_template_migration_logs(scope_id, LEGACY, 目标RECORDS)`；`verification_summary` 保存逐字段守恒证据。`INITIAL_EMPTY` 无 LEGACY、无迁移日志。
- LEGACY 行只用于 `origin_version_id` 和历史证据，不参与范围路由、当前指针、写入、读取或物理结构漂移检查；启动校验对 LEGACY 只核对元数据与旧表存在，不把它当作可由服务端分配的新版本表。服务端分配的物理表仍必须唯一且不超过 63 字节。
- 无旧来源的模板（`issue_response`/`issue_reply`）不创建 LEGACY 行；新记录 `origin_version_id` 指向首次 PUBLISHED 的 RECORDS 版本，`template_version` 为真实发布标签。
- 迁移后的历史记录保留原 ID、值、时间与 `deleted`，`created_by/reviewer_user_id/request_key/create_payload_hash` 为 NULL，`record_version=1`；`origin_version_id` 指向 LEGACY。历史记录 `template_version` 无证据时为 NULL 仅是记录级例外；新记录与 PUBLISHED RECORDS 版本不得使用 NULL 标签（R04）。

#### 2.7 范围绑定锁定与历史覆盖（R07）

- 范围绑定由 `(template_key, source_config_id, source_instance, canonical_gitlab_base_url, project_ids, version_project_id, resource_type)` 组成；`binding_hash` 为规范化 JSON 的 SHA-256，`binding_revision` 每次首次绑定/获批变更递增。
- **首次发布**（`INITIAL_EMPTY`/`INITIAL_MIGRATION`）可以把草稿绑定写入范围：范围此前无版本表、无实例；`INITIAL_MIGRATION` 还要求旧 S 每行都命中该绑定，否则停止迁移。
- **后续结构发布**（`MIGRATION`）不得改变绑定：要求草稿 `binding_hash` 与范围当前 `binding_hash` 完全相同，任何来源、规范化 URL、项目集合或版本项目差异返回 409/A0409 “绑定变更需独立迁移”。普通字段发布不得借机换来源、移除历史项目或改变旧链接定位。
- 发布前范围验证器必须检查：
  1. ACTIVE 范围在 `(template_key, source_config_id, resource_type, project_id)` 上不重叠；项目非空、去重、正整数；`version_project_id` 属于项目集合。
  2. 当前指针指向本范围 PUBLISHED 的 RECORDS 版本；来源配置存在，规范 URL/实例一致；同一事实实例对应多个配置且无法唯一选择时拒绝。
  3. 目标版本的全部实例/记录，其来源、项目、资源类型均被本范围绑定覆盖；若某项目被移除且仍存在实例/记录，拒绝发布。
  4. `INITIAL_MIGRATION` 的 S 全覆盖：S 中每一行的来源/项目/类型都必须命中已登记范围；发现未覆盖行停止上线并报告。
- 跨来源或项目转移不在本期普通发布内；确需转移时按主方案独立迁移单元重新验收守恒、唯一归属、历史链接和消费，不得用字段发布实现。
- 反例验收：对已有实例的范围，尝试通过草稿改来源配置、移除有历史记录的项目、或把项目加入导致范围重叠，均返回 409 且指针不变；旧外链仍按原 C 找到原记录。

#### 2.8 建表顺序

同一迁移内按以下顺序创建/加列，避免前向引用：

1. 范围加列 → 草稿 → 预览；
2. 切换清单头/明细；
3. 资源权威（引用切换清单）；
4. 发布任务与 attempt（引用预览与版本）；
5. 变更 head（引用审计与版本）。

发布任务与权威表所需的 `form_template_versions`、`form_record_audit_logs` 已由主方案基础表提供；若基础表与上述表在同一迁移，先建基础表。任何新增迁移只追加，不修改既有迁移与 checksum。

#### 2.9 初装路径与 Flyway 职责差异（R05）

必须区分三条路径，不得混写：

| 路径 | 触发 | LEGACY | 迁移日志 | 指针/状态 |
| --- | --- | --- | --- | --- |
| A 空平台 | 无旧表或 S 为空；管理员绑定后首次发布 | 不创建 | 不写 | 目标表为空；scope DRAFT→ACTIVE，current=目标 RECORDS |
| B 已有旧表 S | 旧后端停写窗口内执行一次性 `INITIAL_MIGRATION` | 先插入 LEGACY，再建目标 version | 写 `LEGACY→目标RECORDS`，含逐字段守恒 | scope current 迁移前 NULL；切换后 ACTIVE/目标 |
| C 已有 RECORDS | 后续字段结构升级 | 不创建 | 写 `当前RECORDS→新RECORDS` | 指针 CAS 切换 |

**路径 B 的执行顺序**：

1. 按 runbook 停止旧后端写入并排空请求；确认无旧实例续写；旧前端进入维护态。
2. 管理员创建 DRAFT 范围、绑定来源/项目、选择真实版本标签及证据、预览；范围验证器核对 S 全覆盖。
3. 发布任务 `INITIAL_MIGRATION`：准备事务插入 LEGACY 版本；停写事务设置带 token 栅栏；复制 attempt 创建目标实例/记录表。
4. 从 `REPEATABLE READ` 快照读取 S（含 `deleted=true`），逐行映射：`reviewer→reviewer_name`、五项 `*_score→*_count`、`remark→supplement_note`；保留原 ID、值、时间、`deleted`；`created_by/reviewer_user_id/request_key/create_payload_hash` 为 NULL；`origin_version_id=LEGACY`；`template_version` 无证据为 NULL；目标字段规范中必填而历史值缺失时写 `legacy_incomplete=true` 与 `legacy_missing_fields`，不补写默认值。
5. 实例按完整键归并；时间取同键最早创建/最新更新；验证 `|S|=记录数`、`distinct(完整实例键)=实例数`、逐字段 NULL 安全守恒、集合外零混入；写 attempt 证据。
6. 切换事务：设置 scope current、RECORDS PUBLISHED、ACTIVE；写迁移日志；为全部迁移实例登记 head；按 token 撤栅栏。旧表/旧审计登记只读，旧写入口删除。
7. S 为空时改走路径 A，不创建 LEGACY、不写迁移日志；旧表存在但 S 外有活跃旧模板/未覆盖行时停止并报告，不切换。

**与主方案的差异（须审核批准）**：主方案 §3.4 第 1 条写“初始历史接入使用 Flyway 事务”。本提案让通用 Flyway 只安装基础表、能力表、权限和种子模板；环境相关的范围绑定与 S 迁移由管理员绑定后的 `INITIAL_MIGRATION` 一次性受控发布执行，仍在停写窗口内、同一事务边界和同一守恒核验下完成。差异原因：Flyway 无法获知环境来源配置 ID、项目集合与发布标签，且主方案同时要求管理员绑定/预览后范围才 ACTIVE；若强行在通用 Flyway 内迁移，会需要第二套环境规格注入。若审核要求保留 Flyway 入口，备选是用 Java Flyway 迁移读取预先登记的初始化规格表，但该表仍需环境预置与停写，复杂度更高；本提案建议采用受控发布路径，并请审核方在该差异上明确批准或退回。

**保留的主方案职责**：基础结构、权限、审计、发布/权威/head 能力表、种子模板仍由 Flyway 安装；迁移不可变性、checksum 追加、空库 smoke 不变；S 定义、字段映射、守恒和切换顺序不变。仅执行入口由通用 Flyway 改为一次性受控发布，属需批准的差异，不默认为已生效设计。

### 3. 记录变更交接与资源权威

#### 3.1 变更 head

```sql
create table form_change_heads (
    id bigserial primary key,
    source_config_id bigint not null,
    source_instance varchar(128) not null,
    canonical_gitlab_base_url varchar(255) not null,
    project_id bigint not null check (project_id > 0),
    resource_type varchar(64) not null,
    resource_id bigint not null check (resource_id > 0),
    template_key varchar(64) not null references form_template_definitions(template_key),
    scope_id bigint not null references form_template_scopes(id),
    latest_change_version bigint not null default 0 check (latest_change_version >= 0),
    consumed_change_version bigint not null default 0 check (consumed_change_version >= 0),
    latest_audit_id bigint references form_record_audit_logs(id),
    pending_record_id bigint,
    pending_record_version integer,
    pending_storage_version_id bigint references form_template_versions(id),
    pending_authority_revision bigint,
    status varchar(16) not null
        check (status in ('PENDING', 'LEASED', 'RETRY_WAIT', 'CONSUMED', 'FAILED')),
    lease_owner varchar(128),
    lease_until timestamp,
    fencing_version bigint not null default 0 check (fencing_version >= 0),
    next_attempt_at timestamp,
    last_error text,
    created_at timestamp not null,
    updated_at timestamp not null,
    unique (source_config_id, project_id, resource_type, resource_id, template_key),
    check (consumed_change_version <= latest_change_version),
    check (resource_type in ('merge_request', 'issue'))
);

create index idx_form_change_heads_claim
    on form_change_heads(status, next_attempt_at, lease_until)
    where status in ('PENDING', 'RETRY_WAIT', 'LEASED');
create index idx_form_change_heads_scope
    on form_change_heads(scope_id, status);
```

写入规则（与记录/审计同一事务）：

- 新建/编辑/作废成功：`latest_change_version = latest_change_version + 1`，更新 pending 证据、`latest_audit_id`；`consumed_change_version` 不变。
- 若当前 `status='LEASED'` 且 `lease_until > clock_timestamp()`，不改状态，只推进 latest 与证据；否则置 PENDING、清 `next_attempt_at`。
- `latest_audit_id` 纯结构/权威变化可为空，但版本仍递增。

消费规则：

- 有界领取：按 `(status, next_attempt_at, lease_until)` 取批，行锁 `skip locked`；领取即用 `clock_timestamp()` 写 `lease_owner/lease_until/fencing_version+1`，记录每行捕获的 `latest_change_version`、权威类型与 `authority_revision`、`pending_storage_version_id`、`pending_authority_revision` 与捕获时的 `scope.current_version_id`。
- 冻结输入：按捕获版本读取该来源/范围的完整记录、权威修订和结构定义；构建前后根并集。输入漂移时终止该候选，不结算。
- **结果提交与版本复核（R03）**：派生结果公开、版本核验与 head 结算必须在同一事务完成，不得“先复核、后提交”：
  1. 直接锁定 `form_change_heads` 基表行，按 id 升序 `for update`：`select id from form_change_heads where id = any(:headIds) order by id for update;`。`headIds` 只作筛选；不得把锁加在 WITH 结果或 join 结果上。该锁顺序与切换事务一致，两者在相同 head 行上串行化，锁保持到结果写入、结算和提交。
  2. 取得锁后按 §2.3 校验租约（`status='LEASED'`、`lease_owner`、`fencing_version`、`lease_until > clock_timestamp()`），再读取当前 `form_resource_authorities` 行，按以下条件用 `is not distinct from` 复核（权威行缺失即不匹配）：
     - 捕获与当前的权威类型相同：FORM/COMMENT 不得互换，FORM→COMMENT→FORM 也不能只凭最终类型相同而通过。
     - 捕获的 `authority_revision` 等于当前权威行的 `authority_revision`，且捕获与当前的 `pending_authority_revision` 用 `is not distinct from` 相等；两个修订比较对 FORM 与 COMMENT 都执行，不能只放在 COMMENT 分支。
     - FORM：在类型与修订均匹配后，捕获与当前的 `pending_storage_version_id` 还必须非空且相等；任一为 NULL 表示初始化/切换未完成，候选不得公开结果。
     - COMMENT：在类型与修订均匹配后，捕获与当前的 `pending_storage_version_id` 均为 NULL。
     - 读取 `scope.current_version_id` 只作诊断断言。正确性由 §2.4 的不变量 I-R03 保证：切换事务在同一事务内更新指针与全部 FORM head，并在提交前核验；因此 head 存储证据与 scope 当前版本一致，不依赖无锁重读当前指针。
  3. 全部匹配才写入派生结果并结算：`consumed_change_version = greatest(consumed, captured)`；若 `consumed=latest` 置 CONSUMED，否则置 PENDING。结果写入与 head 结算同事务提交，提交后公开的结果一定属于提交时刻仍有效的结构版本与权威修订。
  4. 任一版本、权威类型、权威修订或租约不匹配：不写任何派生结果、不推进 `consumed_change_version`；回滚结果事务后，另开短事务按相同 owner/fencing 释放租约为 PENDING，由新 head 驱动重建。不得仅结算捕获的较小版本后继续公开旧结构结果。
- 切换事务会更新同一批 head 行，因此允许的交错只有两种：旧候选在切换前完整提交并公开；或切换先提交，旧候选在取得 head 锁后发现存储版本/权威修订不匹配而原子拒绝。失败不改变公开指针，也不留下部分派生结果。
- 验收覆盖：FORM 已登记但无实例、已有实例、首次空范围启用；两种提交顺序均受控；切换先成功时旧候选零公开、零结算；切换失败保持旧公开指针；无后续用户保存也能触发必要重建；不生成空业务记录。
- 权威修订验收：存储版本不变而 `authority_revision` 递增（含 FORM→COMMENT→FORM 后最终类型与存储版本恢复相同）时，捕获与当前修订不同，旧候选必须零公开、零结算；无实例资源的同类交错同样覆盖。
- 失败：持久退避 `next_attempt_at`、`last_error`；达到上限置 FAILED，等待人工/维护处置，不静默清零。

#### 3.2 资源权威

```sql
create table form_resource_authorities (
    id bigserial primary key,
    source_config_id bigint not null,
    source_instance varchar(128) not null,
    canonical_gitlab_base_url varchar(255) not null,
    project_id bigint not null check (project_id > 0),
    resource_type varchar(64) not null check (resource_type in ('merge_request', 'issue')),
    resource_id bigint not null check (resource_id > 0),
    template_key varchar(64) not null references form_template_definitions(template_key),
    scope_id bigint not null references form_template_scopes(id),
    authority varchar(16) not null check (authority in ('COMMENT', 'FORM')),
    authority_revision bigint not null default 1 check (authority_revision > 0),
    cutover_manifest_id bigint references form_authority_cutover_manifests(id),
    effective_at timestamp not null,
    created_at timestamp not null,
    updated_at timestamp not null,
    unique (source_config_id, project_id, resource_type, resource_id, template_key)
);
```

- 读取器必须按完整身份取得一行；无行返回明确的“未登记权威”，不得回退到另一来源。
- 权威 CAS：`update ... set authority=?, authority_revision=authority_revision+1, cutover_manifest_id=?, effective_at=? where id=? and authority_revision=?`；影响 0 行为冲突。
- 权威写入遵循 §2.3 锁顺序：先锁范围行并核验 `status/current_version_id`，再锁/写权威，最后按 id 升序更新 head；不得反向取锁。
- 权威首次登记与后续变更都与 head 同事务：递增 `latest_change_version`，`pending_authority_revision` 指向本次 `authority_revision`。
- **FORM 权威初始化（I-R03）**：登记为 FORM 时，若范围已有 `current_version_id`，同事务把 head 的 `pending_storage_version_id` 设为该版本并递增 `latest_change_version`；范围尚未激活/无版本时保持 NULL，由首次 `INITIAL_EMPTY` 切换写入。无实例不创建空实例或记录。登记为 COMMENT 时 head 的 `pending_storage_version_id` 保持 NULL，并递增版本通知消费者改读评论权威。
- 权威登记、head 初始化与切换使用同一锁顺序（scope → authority → head），不得先锁 head 再取 scope；切换持有 scope 锁期间权威集合稳定。
- FORM 无记录表示未填写，按业务规则产生未填写结果；不得读旧评论补齐。
- COMMENT 只服务切换清单划定的历史资源；旧评论流程保留，禁止形成“FORM 缺字段即回读评论”的永久回退。

#### 3.3 切换清单

```sql
create table form_authority_cutover_manifests (
    id bigserial primary key,
    manifest_key varchar(64) not null unique,
    source_config_id bigint not null,
    source_instance varchar(128) not null,
    canonical_gitlab_base_url varchar(255) not null,
    template_key varchar(64) not null references form_template_definitions(template_key),
    scope_id bigint not null references form_template_scopes(id),
    effective_at timestamp not null,
    project_ids jsonb not null
        check (jsonb_typeof(project_ids) = 'array' and jsonb_array_length(project_ids) > 0),
    resource_rule jsonb not null,
    rule_version varchar(64) not null,
    status varchar(16) not null check (status in ('DRAFT', 'APPLIED', 'RETIRED')),
    operator_id varchar(128) not null,
    operator_name varchar(128),
    created_at timestamp not null,
    applied_at timestamp
);

create table form_authority_cutover_items (
    id bigserial primary key,
    manifest_id bigint not null references form_authority_cutover_manifests(id) on delete cascade,
    project_id bigint not null check (project_id > 0),
    resource_type varchar(64) not null check (resource_type in ('merge_request', 'issue')),
    resource_id bigint check (resource_id > 0),
    authority varchar(16) not null check (authority in ('COMMENT', 'FORM')),
    created_at timestamp not null,
    unique nulls not distinct (manifest_id, project_id, resource_type, resource_id)
);
```

- `resource_rule` 固定来源时钟语义、创建时间边界、模板键与适用项目；显式资源用 items 登记，批次规则用 `resource_id is null` 表示。
- 应用清单：锁范围/来源，登记 APPLIED，按规则/清单 upsert 权威并递增 head；对已存在资源逐项留证。
- 新资源到达镜像时，按已 APPLIED 清单规则登记权威，再允许消费与新表单写入。
- 清单只划定历史 COMMENT 边界；整体退出另按 E6 验收，不在本期扩大。

### 4. Issue 两模板字段规格

#### 4.1 规格结构与条件 DSL

- `spec_version=1`；每个字段显式声明 `name`（稳定 snake_case）、`apiKey`（camelCase）、`label`、`type`、`control`、`required`、边界、`storage.column/columnType`。
- 条件使用冻结 DSL：`requiredWhen`、`clearWhen`、`mutuallyExclusive`。条件字段只能引用已声明字段或只读资源上下文；不允许脚本表达式。
- 空白判空使用 `btrim`；文本长度按 Unicode 码点；整数仅接受 JSON 整数 0—2147483647；日期为 `YYYY-MM-DD`；请求体上限 128 KiB，超限 413。
- 未知字段、只读字段（创建账号、姓名快照、deleted、版本等）返回 400。
- 枚举/词典在规格内冻结；结构版本发布时一并冻结内容与哈希。

#### 4.2 `issue_response` 完整规格

```json
{
  "spec_version": 1,
  "template_key": "issue_response",
  "fields": [
    {"name":"problem_type","apiKey":"problemType","label":"问题类型","type":"enum","control":"radio","required":true,
     "options":[{"value":"DEFECT","label":"缺陷"},{"value":"REQUIREMENT","label":"需求"}],
     "storage":{"column":"problem_type","columnType":"varchar(32)"}},
    {"name":"problem_cause","apiKey":"problemCause","label":"问题原因","type":"string","control":"textarea","required":true,"maxLength":8192,
     "storage":{"column":"problem_cause","columnType":"text"}},
    {"name":"modification_plan","apiKey":"modificationPlan","label":"修改方案","type":"string","control":"textarea","required":true,"maxLength":8192,
     "storage":{"column":"modification_plan","columnType":"text"}},
    {"name":"owner_confirmation","apiKey":"ownerConfirmation","label":"模块负责人确认","type":"string","control":"textarea","required":false,"maxLength":8192,
     "requiredWhen":{"resourceSeverityLevel":"一级缺陷"},
     "storage":{"column":"owner_confirmation","columnType":"text"}},
    {"name":"planned_resolution_date","apiKey":"plannedResolutionDate","label":"计划解决时间","type":"date","control":"date","required":true,
     "minYear":2020,"maxYear":2040,
     "storage":{"column":"planned_resolution_date","columnType":"date"}},
    {"name":"planned_merge_version_branch","apiKey":"plannedMergeVersionBranch","label":"计划合并的版本分支","type":"string","control":"version-members","required":true,"maxLength":255,
     "format":"CCyyyyRn，多个以 & 分隔；严格解析，历史原值保留",
     "storage":{"column":"planned_merge_version_branch","columnType":"varchar(255)"}},
    {"name":"supplement_note","apiKey":"supplementNote","label":"补充说明","type":"string","control":"textarea","required":false,"maxLength":8192,
     "storage":{"column":"supplement_note","columnType":"text"}}
  ],
  "conditions": [
    {"type":"conditionalRequired","target":"owner_confirmation",
     "requiredWhen":{"resourceSeverityLevel":"一级缺陷"},
     "clearWhen":{"resourceSeverityLevel":["二级缺陷","三级缺陷","建议类","未设定"]},
     "snapshotColumn":"owner_confirmation_applicable"}
  ],
  "dictionaries": []
}
```

条件说明：

- `resourceSeverityLevel` 取提交时资源标签快照；一级缺陷必填确认内容，其他级别存 NULL 表示不适用。记录保存 `owner_confirmation_applicable boolean not null`，使条件可审计；资源后来变为一级而确认缺失时由合规规则报告缺失，不改写历史记录。
- 计划解决日期为业务日；现行年份 2020—2040，扩大值域须另行确认。
- 计划合并版本分支为业务值，不是结构发布标签；严格规则沿 `IssueResponsePlanFieldRules`，多个成员以 `&` 分隔。

#### 4.3 `issue_reply` 完整规格

```json
{
  "spec_version": 1,
  "template_key": "issue_reply",
  "fields": [
    {"name":"fix_status","apiKey":"fixStatus","label":"修复状态","type":"enum","control":"radio","required":true,
     "options":[{"value":"RESOLVED","label":"已解决"},{"value":"PARTIAL","label":"部分解决"},{"value":"DEFERRED","label":"申请延期"},{"value":"UNREPRODUCIBLE","label":"无法复现"}],
     "storage":{"column":"fix_status","columnType":"varchar(32)"}},
    {"name":"cause_keys","apiKey":"causeKeys","label":"缺陷原因","type":"enum-array","control":"multi-select","required":true,
     "dictionary":"DEFECT_CAUSE","maxItems":24,
     "storage":{"column":"cause_keys","columnType":"text[]"}},
    {"name":"specific_reason","apiKey":"specificReason","label":"具体原因","type":"string","control":"textarea","required":true,"maxLength":8192,
     "storage":{"column":"specific_reason","columnType":"text"}},
    {"name":"modification_plan","apiKey":"modificationPlan","label":"修改方案","type":"string","control":"textarea","required":true,"maxLength":8192,
     "storage":{"column":"modification_plan","columnType":"text"}},
    {"name":"caused_by_other_defect","apiKey":"causedByOtherDefect","label":"由修改其他缺陷造成","type":"boolean","control":"radio","required":true,
     "storage":{"column":"caused_by_other_defect","columnType":"boolean"}},
    {"name":"affected_kinds","apiKey":"affectedKinds","label":"修改该缺陷可能影响的功能","type":"enum-array","control":"checkbox","required":true,
     "options":[{"value":"KNOWN","label":"已知的受影响功能"},{"value":"NEW","label":"新识别的受影响功能"},{"value":"NONE","label":"无"}],
     "storage":{"column":"affected_kinds","columnType":"text[]"}},
    {"name":"known_affected_functions","apiKey":"knownAffectedFunctions","label":"已知的受影响功能说明","type":"string","control":"textarea","required":false,"maxLength":8192,
     "requiredWhen":{"field":"affected_kinds","contains":"KNOWN"},
     "clearWhen":{"field":"affected_kinds","notContains":"KNOWN"},
     "storage":{"column":"known_affected_functions","columnType":"text"}},
    {"name":"new_affected_functions","apiKey":"newAffectedFunctions","label":"新识别的受影响功能说明","type":"string","control":"textarea","required":false,"maxLength":8192,
     "requiredWhen":{"field":"affected_kinds","contains":"NEW"},
     "clearWhen":{"field":"affected_kinds","notContains":"NEW"},
     "storage":{"column":"new_affected_functions","columnType":"text"}},
    {"name":"impact_test_status","apiKey":"impactTestStatus","label":"是否对可能影响的功能进行了测试","type":"enum","control":"radio","required":true,
     "options":[{"value":"YES","label":"是"},{"value":"NO","label":"否"},{"value":"NOT_APPLICABLE","label":"不涉及"}],
     "storage":{"column":"impact_test_status","columnType":"varchar(16)"}},
    {"name":"has_residual_impact","apiKey":"hasResidualImpact","label":"有无遗留问题或潜在影响","type":"boolean","control":"radio","required":true,
     "storage":{"column":"has_residual_impact","columnType":"boolean"}},
    {"name":"residual_impact_detail","apiKey":"residualImpactDetail","label":"遗留问题或潜在影响说明","type":"string","control":"textarea","required":false,"maxLength":8192,
     "requiredWhen":{"field":"has_residual_impact","equals":true},
     "clearWhen":{"field":"has_residual_impact","equals":false},
     "storage":{"column":"residual_impact_detail","columnType":"text"}},
    {"name":"relation_table_updated","apiKey":"relationTableUpdated","label":"是否更新了关联关系表","type":"enum","control":"radio","required":true,
     "options":[{"value":"YES","label":"是"},{"value":"NO","label":"否"},{"value":"NOT_APPLICABLE","label":"不涉及"}],
     "storage":{"column":"relation_table_updated","columnType":"varchar(16)"}},
    {"name":"supplement_note","apiKey":"supplementNote","label":"补充说明","type":"string","control":"textarea","required":false,"maxLength":8192,
     "storage":{"column":"supplement_note","columnType":"text"}}
  ],
  "conditions": [
    {"type":"mutuallyExclusive","field":"affected_kinds","whenContains":"NONE","withAnyOf":["KNOWN","NEW"]},
    {"type":"conditionalRequired","target":"known_affected_functions","requiredWhen":{"field":"affected_kinds","contains":"KNOWN"},"clearWhen":{"field":"affected_kinds","notContains":"KNOWN"}},
    {"type":"conditionalRequired","target":"new_affected_functions","requiredWhen":{"field":"affected_kinds","contains":"NEW"},"clearWhen":{"field":"affected_kinds","notContains":"NEW"}},
    {"type":"conditionalRequired","target":"residual_impact_detail","requiredWhen":{"field":"has_residual_impact","equals":true},"clearWhen":{"field":"has_residual_impact","equals":false}}
  ],
  "dictionaries": [
    {"name":"DEFECT_CAUSE","version":"defect-cause-v20261020",
     "source":"DefectCauseMetricCatalog.METRICS",
     "canonicalSha256":"efbc6c2ef07c5e7e229c1a2e0fffd7411b6c01097e16a70d2abeb8103535ce6c",
     "items":[
       {"key":"demand_misunderstand","label":"新增理解偏差","group":"需求问题"},
       {"key":"missing_requirement","label":"需求遗漏","group":"需求问题"},
       {"key":"add_demand_2","label":"新增需求","group":"需求问题"},
       {"key":"demand_change_not_sync","label":"需求变更未同步","group":"需求问题"},
       {"key":"design_forget","label":"功能设计遗漏","group":"设计问题"},
       {"key":"design_scheme","label":"设计方案不合理","group":"设计问题"},
       {"key":"incomplete","label":"场景考虑不全","group":"设计问题"},
       {"key":"prompt_message","label":"术语、提示信息不合适","group":"设计问题"},
       {"key":"standard_error","label":"编码规范错误","group":"编码规范"},
       {"key":"function_forget","label":"功能编码遗漏","group":"编码规范"},
       {"key":"logic_calculation_algorithm_error","label":"编码逻辑：计算与算法错误","group":"编码规范"},
       {"key":"logic_flow_control_error","label":"编码逻辑：流程控制错误","group":"编码规范"},
       {"key":"logic_data_state_process_error","label":"编码逻辑：数据与状态处理错误","group":"编码规范"},
       {"key":"logic_business_logic_error","label":"编码逻辑：业务逻辑错误","group":"编码规范"},
       {"key":"logic_integration_interface_error","label":"编码逻辑：集成与接口错误","group":"编码规范"},
       {"key":"environment_config_issue","label":"环境配置问题","group":"打包问题"},
       {"key":"compilation_package_deployment_issue","label":"编译/打包/部署问题","group":"打包问题"},
       {"key":"other_thirdParty","label":"第三方库问题","group":"依赖问题"},
       {"key":"algorithm_not_support","label":"算法不支持","group":"依赖问题"},
       {"key":"mechanism_not_support","label":"机制不支持","group":"依赖问题"},
       {"key":"precondition_data_exception","label":"前置数据异常","group":"依赖问题"},
       {"key":"other_unIdentifyTask","label":"未识别的前后置任务","group":"依赖问题"},
       {"key":"precision_constraint_exception","label":"精度导致约束求解异常","group":"精度问题"},
       {"key":"precision_algorithm_exception","label":"精度导致算法执行异常","group":"精度问题"}
     ]}
  ]
}
```

规则说明：

- `cause_keys` 非空、去重、命中冻结词典；保存真实多选，不截断、不猜主因。合规“缺陷原因不唯一”在适用门槛下单独计算，不反向修改采集值（D-30）。
- `affected_kinds` 可同时选 KNOWN/NEW；NONE 与二者互斥。控制项关闭时前端清理不再适用的草稿值，服务端拒绝矛盾载荷。
- `relation_table_updated` 为第 9 项，必填；历史 8 项回复属于 COMMENT 来源，**不导入历史评论**；这与“已有平台 Issue 表单记录必须随结构版本迁移”是两件事，见 §4.5。
- 历史值不伪造；新表单记录从首次发布后产生，后续结构升级时按 §4.5 迁移既有平台表单记录，不把历史 GitLab 评论当表单记录导入。

#### 4.4 物理列与约束

两表共用系统列（与主方案一致）：`id`、`instance_id`、`origin_version_id`、`template_version`、`created_by`、`record_version`、`deleted`、`request_key`、`create_payload_hash`、`created_at`、`updated_at`，`unique(request_key)`。为历史迁移增加两个内部登记列（不是业务字段，不进入 field_spec/API 写入白名单）：`legacy_incomplete boolean not null default false`、`legacy_missing_fields text[] not null default '{}'`；仅迁移复制写入 `true` 与该行缺失的必填字段名，业务编辑补齐后清回 `false/'{}'`。

`issue_response` 追加：

```sql
problem_type varchar(32) not null check (problem_type in ('DEFECT', 'REQUIREMENT')),
problem_cause text not null check (btrim(problem_cause) <> ''),
modification_plan text not null check (btrim(modification_plan) <> ''),
owner_confirmation text,
owner_confirmation_applicable boolean not null,
planned_resolution_date date not null,
planned_merge_version_branch varchar(255) not null
    check (btrim(planned_merge_version_branch) <> ''),
supplement_note text,
check (
    (owner_confirmation_applicable and owner_confirmation is not null
     and btrim(owner_confirmation) <> '')
    or (not owner_confirmation_applicable and owner_confirmation is null)
)
```

`issue_reply` 追加：

```sql
fix_status varchar(32) not null
    check (fix_status in ('RESOLVED', 'PARTIAL', 'DEFERRED', 'UNREPRODUCIBLE')),
cause_keys text[] not null
    check (cardinality(cause_keys) between 1 and 24),
specific_reason text not null check (btrim(specific_reason) <> ''),
modification_plan text not null check (btrim(modification_plan) <> ''),
caused_by_other_defect boolean not null,
affected_kinds text[] not null
    check (cardinality(affected_kinds) > 0
           and affected_kinds <@ array['KNOWN', 'NEW', 'NONE']::text[]
           and not ('NONE' = any(affected_kinds) and cardinality(affected_kinds) > 1)),
known_affected_functions text,
new_affected_functions text,
impact_test_status varchar(16) not null
    check (impact_test_status in ('YES', 'NO', 'NOT_APPLICABLE')),
has_residual_impact boolean not null,
residual_impact_detail text,
relation_table_updated varchar(16) not null
    check (relation_table_updated in ('YES', 'NO', 'NOT_APPLICABLE')),
supplement_note text,
check (not ('KNOWN' = any(affected_kinds))
       or (known_affected_functions is not null and btrim(known_affected_functions) <> '')),
check (('KNOWN' = any(affected_kinds)) or known_affected_functions is null),
check (not ('NEW' = any(affected_kinds))
       or (new_affected_functions is not null and btrim(new_affected_functions) <> '')),
check (('NEW' = any(affected_kinds)) or new_affected_functions is null),
check ((has_residual_impact and residual_impact_detail is not null
        and btrim(residual_impact_detail) <> '')
       or (not has_residual_impact and residual_impact_detail is null))
```

`cause_keys` 去重与词典命中由服务端校验；数据库只保证非空、上限和类型。`affected_kinds` 的存储顺序由服务端规范化，数据库只校验集合语义。

以上 `NOT NULL/CHECK` 是字段规则的基准表达式。任何目标版本表都按 §4.5 的约束生成器输出：无历史迁移时生成即时约束；迁移历史记录时生成带 `deleted or legacy_incomplete` 豁免的约束。两种情形都不使用 `NOT VALID`，不得把即时 `NOT NULL` 直接套到历史行。

#### 4.5 已有表单记录的跨版本迁移（R06）

“不导入历史 GitLab 评论”不等于“不迁移已有平台表单”。同一 `template_key` 的结构升级必须迁移当前范围全部既有记录（含作废行），Issue 两模板同样适用。迁移契约：

1. **保留身份与时间**：记录 ID、`created_by`、`created_at`、`origin_version_id`、`template_version`、`request_key`、`create_payload_hash`、`record_version`、`deleted` 原值迁移；`storageVersionId` 由新范围当前指针解析。实例按完整键归并，不重建记录身份。
2. **历史缺值保留与直接作废**：新增字段在历史行中为 NULL，不伪造默认值；新增必填字段只约束新提交/编辑，不回填历史。作废只更新 `deleted/record_version/updated_at`，不执行字段完整性校验，历史缺值行可直接作废。用户业务编辑历史记录时，服务端按当前 field_spec 校验并提示补齐必填/条件字段；补齐成功后才允许保存。编辑与作废都以记录级 CAS 和审计/head 同事务完成。
3. **物理约束生成（不使用 `NOT VALID`）**：约束生成器按目标版本 field_spec 统一输出，`legacy_incomplete` 是记录级豁免标记：
   - 无历史行的首次建表：按 §4.4 生成即时 `NOT NULL/CHECK`，`legacy_incomplete` 恒为 false。
   - 迁移目标：业务列按可空创建；每个必填约束改写为 `CHECK (deleted OR legacy_incomplete OR <col> IS NOT NULL)`，条件与跨字段约束改写为 `CHECK (deleted OR legacy_incomplete OR (<原条件>))`。约束在建表时添加，复制写入历史缺值行时同时写 `legacy_incomplete=true` 与 `legacy_missing_fields`，因此复制插入和后续作废都不会被拒绝。
   - 业务编辑按当前 field_spec 校验；保存事务在同一 UPDATE 内把 `legacy_incomplete` 置 false、`legacy_missing_fields` 清空，条件/跨字段规则由服务端与约束同时保证。
   - 重试复用目标结构：先确认约束集合/指纹等于冻结 `ddl_hash`；结构一致则 `truncate` 后按同一标记重复制，既有约束对 legacy 行仍放行；结构不一致或存在非生成器添加的约束时终止，另开新发布重建目标表。不得为通过重试删除/禁用约束或补写伪造值。
   - 不按字段名猜测豁免；`legacy_incomplete` 只能由迁移复制或“补齐后清除”两条路径变更，普通新建默认 false。
4. **origin/标签**：迁移不改变 `origin_version_id` 与原始 `template_version`；新版本标签只写入新产生记录。历史标签无证据保持 NULL（记录级例外，不用于新发布）。
5. **弃用字段**：删除字段从目标结构移除，值不复制；改名按显式映射保值；类型变化必须声明可验证转换，失败值进入报告并阻塞切换（或按已批准策略保留 NULL，但不得静默截断）。
6. **验证**：构造含新必填字段缺值、作废行、改名/类型转换、空值与条件不满足的历史样本；升级后断言 ID/归属/创建时间/origin/template_version/deleted/record_version 守恒，新增字段为 NULL，`legacy_incomplete/legacy_missing_fields` 准确，集合外零混入；**缺值历史行直接作废成功**；新建缺必填字段被拒绝；编辑历史行补齐前被拒绝、补齐后成功且标记清除；复制并加约束后失败→新 attempt 重复制，历史缺值行仍能插入且原值不变；旧链接仍按原记录 ID 访问。

### 5. 消费适配与影响面

#### 5.0 上下文解析、来源与人员候选

- **来源配置**：`gitlab_sync_configs`（实体 `GitlabSyncConfig`）提供 `id/web_base_url/source_instance`。`GitlabSourceInstanceSupport.sourceInstanceOf(config)` 当前固定返回 `default`；`normalizeSourceInstance` 只做小写与下划线归一。W2 新增单一规范 URL 解析器：允许 http/https，规范 scheme/host 大小写、默认端口和末尾斜线，保留部署子路径，拒绝 userinfo/query/fragment；请求 URL 只用于匹配已保存配置，不据此访问 GitLab。匹配到配置后取 `configId/sourceInstance`，与范围绑定比对。
- **项目与资源校验**：范围 `project_ids` 必须包含请求项目；按 `ods_gitlab_merge_requests(target_project_id, iid)` 或 `ods_gitlab_issues(project_id, iid)` 且 `mirror_deleted=false` 读取 GitLab 全局根 `id`。MR/Issue 未镜像时返回“资源待同步”，保留输入并允许重试；不得创建空记录或事实。建议使用 HTTP 409 + 专用码 `A0410` 区分版本冲突 `A0409`，该新码需审核确认；未确认前不得复用 A0409 表达资源未同步。
- **人员候选**：`platform_ldap_users(user_id, real_name, dept_name, account_status, status, employment_status, ...)` 当前有 270 条、空 `real_name` 为 0。候选查询只要求 `btrim(real_name) <> ''`，不按 `status/employment_status` 过滤（正名有效的离职人员仍可选，沿主方案）。`GET /reviewer-candidates` 按 `user_id` 稳定游标分页，`keyword` 至多 128 码点，limit 默认 20/最大 100；返回 `userId/realName/deptName`、`nextCursor` 和独立 `currentUserCandidate`。服务端保存时再次校验 user_id 存在且 real_name 非空，并以该次权威姓名写 `reviewer_name`；客户端只提交 ID。
- **notification-payload**：保留 `sourceAddress/projectId/requestIid/resourceType` 四字段及类型；按已保存来源、项目集合与 ODS 资源校验。若存在外部调用，其参数/认证迁移按 E1 处理，未核实前不退役外部契约。

#### 5.1 统一读取器

新增单一权威读取器 `FormAuthoritativeInputReader`（名称暂定），职责：

1. 按完整 C（来源配置、项目、资源类型、资源 ID、模板键）解析已登记权威与 scope/current_version。
2. 读取当前版本记录，按 `(created_at, id)` 选择；不读旧版本表，不读旧评论补齐。
3. 返回冻结的响应/回复快照与来源证据，供事实构建、统计、导出、写回资格共用。
4. 权威缺失返回显式“未登记”，不得静默选择 COMMENT 或 FORM。

#### 5.2 MR 走查消费

| 当前消费点 | 当前实现 | 目标 |
| --- | --- | --- |
| `GitlabFactSourceSqlProvider` `form_records` CTE（约 242—252 行）与 MR join（约 425—427 行） | 从 `collect_form_records` 取 `max(reviewer)`、`max(review_duration_minutes)`、`max(updated_at)` | 改为按权威读取当前版本 `code_review` 记录 |
| MR 源 select（约 337—372 行） | `reviewer_names/owner_name` 先评论、再旧表单、再 reviewer/assignee；`code_walkthrough_date` 先评论、再表单 `updated_at`、再 MR `updated_at` | FORM 权威 MR 只读记录：走查人取记录权威姓名快照；走查日期取记录 `created_at`；不再回退评论/旧表 |
| `MergeRequestFactSourceRowMapper` | 计算 `reviewStatus`、速率/密度/效率，依赖 `reviewDurationMinutes`、`startedAt`、`ownerName` 等 | 保持字段契约；多记录聚合语义需确认（见“待确认事项”） |
| `CodeReviewIllegalRecordSourceLoader` / `CodeReviewIllegalRecordSqlQueryBuilder` | 逐 MR 读 `code_review_formal_records`，记录级非法信息来自 MR 事实 | FORM 权威 MR 需要记录级来源（新读取/投影），并在既有页面按记录展示；COMMENT 历史继续旧读模型 |
| 质量/BI `BiCodingCalculator`、`BiPlatformCodingSourceAdapter`、`QualityBoardCodeReviewReadSupport`、`SystemTestHorizontalComparisonExportService`、`VersionedFactSql` | 读 `code_review_formal_records` / `merge_request_fact` | 通过事实与统一读取器间接消费；金额/效率公式不变，变化仅来源与空值语义 |
| 工作区 | `code-review-*` 依赖 `FactType.MERGE_REQUEST` | 新增表单变更作为 MR 事实变化信号；共享 head 消费后驱动工作区刷新 |

#### 5.3 Issue 消费

| 当前消费点 | 当前实现 | 目标 |
| --- | --- | --- |
| `GitlabFactSourceSqlProvider` ISSUE_SOURCE_SQL `issue_notes` | 聚合全部评论为 `notes_text`，同时取 `research_template_time`、`fix_user` | COMMENT 权威保留；FORM 权威从响应/回复记录取结构化字段 |
| `IssueFactSourceRowMapper` | 用 `notes_text` 解析响应模板、原因、SLA、非法/延期 | 改为接收读取器快照；不得先渲染 Markdown 再解析 |
| `IssueSlaRules` | `hasParseablePlanSolutionTime(notesText)` | FORM：有效回复存在即豁免，否则合法计划日期；COMMENT 仍按评论模板与合法计划日期判定。该行只表达 SLA 豁免输入，原因合规另由统一计数器处理 |
| `IssueResponseTemplateParser` / `IssueResponsePlanFieldRules` | 解析最新评论模板、严格日期/分支 | FORM 不再解析模板；仅 COMMENT 使用；严格日期/分支规则仍用于字段校验 |
| `IssueClassificationRules` / `IssueFactNormalizationRules` | 从 `notes_text` 判断“未按模板回复”“缺陷原因不唯一”、原因文本、延期原因 | COMMENT 保留评论输入来源与模板提取；FORM 的模板完整性由记录存在/字段校验替代；**两种来源的“缺陷原因不唯一”都由同一合规计数器按稳定具体原因 ID 去重计数，一级大类仅用于分类统计**，不复刻空格/`&` 漏报；延期原因仍取标签 |
| `FactBuildService` 客户延期三列、`CustomerIssueDelayClosureOrchestrator` | 事实重建后按 `responseDelayed/resolveDelayed` 重算并写回 | 读取器作为输入；写回资格仍由配置开关、`web_base_url`/`api_token`、来源收敛与唯一写所有权共同决定 |
| `CustomerIssueDelayLabelWritebackPlanner/QueueService/WorkerService` | 以 `issue_fact` 的延期标志与标签比对 | 只消费完整发布结果；FORM 记录的修改/作废进入 head，旧任务不能结算新版本 |
| 统计/导出：`CustomerIssueDefectCauseBoardService`、`SystemTestDefectCauseBoardService`、`SystemTestIssueMetricDimensionSupport`、`BiCustomerIssueCalculator`、客户/系统测试记录导出 | 读 `issue_fact.reason_category`、`fix_user`、`has_response`、`research_template_time`、`planned_resolution_*`、`planned_merge_version_branch`、SLA/延期字段 | 保持现有公式；新增结构化投影供“回复详情”导出与合规使用；原因统计仍按原因个数 |
| 工作区 | `customer-issue-*`、`system-test-*` 依赖 `FactType.ISSUE` | 表单变更纳入 ISSUE 事实变化信号；完成事件仍取 GitLab 标签事件 |

#### 5.4 原因合规与完整消费投影（R08）

**原因合规统一计数**：

- COMMENT 与 FORM 只是输入来源不同；适用门槛（固定/完成状态及 Wiki 范围）内的“缺陷原因不唯一”必须使用同一个 `DefectCauseComplianceCounter`，按稳定**具体原因 ID 集合**去重计数：0 或 >1 个不同具体 ID 判非法，恰好 1 个合法。一级大类只用于分类统计与展示，不参与唯一性判定；同属“编码规范”的 `standard_error` 与 `function_forget` 是两个具体原因，必须判非法，跨大类同理。不得调用 `CauseUtil.getMajorByCause` 的空格/`&` 分割，也不得因 COMMENT 来源而保留漏报。
- COMMENT 映射：先按现有模板规则提取原因段原文，再用冻结词典的完整标签/别名做最长优先匹配并移除匹配片段；每个标签/别名归并到同一稳定具体 ID，同一 ID 的多次命中只计 1 个原因，不按文本命中次数计数。未匹配的非空片段生成稳定的未知原因标识（规范化文本的 SHA-256）并作为独立具体原因参与计数，同时写入核对报告；不得静默丢弃、归并到已知大类或按大类压缩。具体词表版本与 `DEFECT_CAUSE` 一致；历史等价映射逐项留证。
- FORM 映射：`cause_keys` 本身就是稳定具体 ID 集合，不解析文本；服务端校验非空、去重、词典命中后按集合计数。一级大类由 ID 派生，仅供分类统计。
- **合规判定独立于采集**：保存多选仍成功；到达检查门槛时按上述具体 ID 数量标记“缺陷原因不唯一”，不反向修改采集值（D-30）。
- 验收覆盖：同大类双具体原因→非法；跨大类双原因→非法；同一原因多个等价别名→合法（计 1）；零原因→非法；未到检查状态→不标记；保存多选成功。
- 统计口径不变：原因分析页仍按既有“原因个数/原因大类”公式消费 `reason_category`/原因标签；合规计数与统计分母是两层，不得互相替代。COMMENT 的 `reason_category` 原文保留，FORM 由 `cause_keys` 映射为规范展示文本。

**完整消费投影（不得缩减）**：现有已确认的 Issue 消费/导出全部保留；员工提出以下单一物理投影方案供技术审核，不以“只做采集”为由删除任何已确认输出。`issue_fact` 增加可空列（COMMENT 来源相应列为 NULL，FORM 来源写入；既有公式不变）：

| 现有输出/消费 | 投影字段 |
| --- | --- |
| 修复状态 | `fix_status` |
| 一级/二级缺陷原因、缺陷原因分析、原因统计/导出 | `cause_keys`（稳定 ID）+ `reason_category`（规范展示文本）；统计公式不变 |
| 具体原因 | `specific_reason` |
| 修改方案 | `reply_modification_plan`（回复）与 `response_modification_plan`（调研）分列 |
| 由修改其他缺陷造成 | `caused_by_other_defect` |
| 修改该缺陷可能影响的功能、已知/新识别受影响功能 | `affected_kinds`、`known_affected_functions`、`new_affected_functions` |
| 是否对可能影响的功能进行了测试 | `impact_test_status` |
| 有无遗留问题/潜在影响 | `has_residual_impact`、`residual_impact_detail` |
| 是否更新了关联关系表 | `relation_table_updated` |
| 缺陷修复人 | 既有 `fix_user`，取被选有效回复创建账号姓名快照 |
| 调研问题类型/原因/修改方案/负责人确认 | `problem_type`、`problem_cause`、`response_modification_plan`、`owner_confirmation` |
| 计划解决时间/计划分支/首次响应/是否响应/延期 | 既有 `planned_resolution_at/text`、`planned_merge_version_branch`、`research_template_time`、`has_response`、`response_delayed/overdue`、`resolve_sla_days/deadline`、`resolve_delayed`（按 §3.5 选择器重算） |

- `reason_category` 与 `cause_keys` 是同一来源的两层投影，不是 COMMENT/FORM 回退；FORM 不读评论，COMMENT 不读表单。
- `scripts/contracts/fact-field-contract.md`、`check_fact_field_contract.py`、Flyway、事实写入/复制、BI 适配器、导出和黄金登记必须同单元更新。新增列全部可空，不对既有行回填；无表单来源时输出保持现有空值/未知语义。
- 若审核方要求分期，必须先证明被延后的输出仍与现状等价或已另行批准，不得以本期范围缩减代替；采集、选择器、合规和现有详情导出属于同一交付单元。

#### 5.5 登记与退役清单

| 类别 | 必须登记/删除 |
| --- | --- |
| 端点目录 | 删除 `/save`、`/delete`、`/update-record` 及快照；`detail` 改参数/响应；新增 `POST /records`、`PUT /records/{id}`、`POST /records/{id}/void`、`GET /reviewer-candidates`、管理/发布端点。写用例声明实例、记录、审计、head、发布/权限表影响 |
| 权限 | 新增 `form.record.create`、`form.record.edit_own`、`form.record.delete_own`、`form.template.manage`；删除旧 `code_review.form.create/edit/delete`；角色授予与 defaults 同批迁移 |
| 数据库查看 | 登记元数据、各版本实例/记录、审计、迁移日志、发布任务、head、权威、切换清单与旧表（只读）；按实际结构维护中文列标签 |
| 工作区/事实依赖 | `code-review-*` 接入 FORM 变更；`customer-issue-*`/`system-test-*` 接入 ISSUE 表单变更；不新增统计板页面时不触发四类页面登记，若新增则同单元完成 |
| 前端 | 新增 `/external/forms`；保留 `/external/code-review-form` 作为兼容壳；删除 DatabaseBrowser 旧编辑器、`updateCollectFormRecord` API/类型/测试 |
| 旧入口 | 删除旧 Controller/Service/Mapper 的 `save/delete/update-record` 及调用者；旧表与旧审计只读；旧评论读取仅按 COMMENT 权威保留 |

### 6. 测试入口与隔离矩阵（R09）

#### 6.1 测试类状态

| 测试类 | 状态 | 用途 |
| --- | --- | --- |
| `CollectFormServiceTest`（5 项） | 已有 | W0 基线；Mockito，不连数据库 |
| `CollectFormControllerTest`（6 项） | 已有 | W0 基线；MockMvc standalone，不连数据库 |
| `FormSchemaMigrationTest` | 计划，待实现 | W1 迁移、守恒、checksum |
| `FormRecordServiceTest` / `FormRecordConcurrencyIT` | 计划，待实现 | W2 三模板字段、幂等、CAS、审计/head 原子性 |
| `FormPublicationIT` / `FormPublicationLeaseIT` | 计划，待实现 | W4 发布、租约、失败/恢复、栅栏、切换 |
| `FormIssueConsumptionIT` / `FormMrConsumptionIT` | 计划，待实现 | W5 真实非空事实链、原因合规、选择器 |
| 前端表单/草稿测试 | 计划，待实现 | W3 草稿、身份、迟到响应、离开保护 |
| `GoldenBaselineChainTest` | 已有 | 仅 W7 授权运行 |

新增类未实现前不得把命令写成“已通过”。W0 实际只跑已有两个类。

#### 6.2 可执行命令（PowerShell 7，工作目录明确）

**W0 基线（已实跑）**：

```powershell
$env:JAVA_HOME = 'D:\projects\data_collection_platform\tools\jdk\jdk-21.0.10+7'
Set-Location 'D:\projects\data_collection_platform\backend'
& 'D:\projects\data_collection_platform\tools\maven\apache-maven-3.9.9\bin\mvn.cmd' -q test '-Dtest=CollectFormServiceTest,CollectFormControllerTest'
```

**W1 迁移（计划；隔离 PG 容器）**：

该容器密码固定为 `form_test_password`，测试配置的默认密码是 `change_this_password`，因此必须同时设置 `TEST_DATASOURCE_USERNAME` 与 `TEST_DATASOURCE_PASSWORD`，否则干净环境会认证失败。容器使用本轮唯一名称，保存创建成功返回的容器 ID；就绪检查和清理只针对该 ID，**不按名称删除**，创建失败、同名冲突或端口占用都不触碰占用者：

```powershell
Set-Location 'D:\projects\data_collection_platform'
python scripts/check_flyway_migration_immutability.py
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$keys = @(
  'JAVA_HOME', 'TEST_DATASOURCE_URL', 'TEST_DATASOURCE_USERNAME',
  'TEST_DATASOURCE_PASSWORD', 'SPRING_FLYWAY_SCHEMAS', 'SPRING_FLYWAY_DEFAULT_SCHEMA'
)
$previous = @{}
foreach ($key in $keys) { $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process') }

$containerId = $null
$mvnExit = 1
$cleanupError = $null
try {
  $containerName = if ($env:FORM_TEST_CONTAINER_NAME) {
    $env:FORM_TEST_CONTAINER_NAME
  } else {
    'qaflex-form-test-pg-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
  }
  $runOutput = docker run -d --rm -q --name $containerName `
    -e POSTGRES_USER=qaflex -e POSTGRES_PASSWORD=form_test_password -e POSTGRES_DB=qaflex `
    -p 15446:5432 postgres:16-alpine
  $containerId = if ($runOutput) { ($runOutput | Select-Object -First 1).ToString().Trim() } else { $null }
  if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($containerId)) {
    $containerId = $null
    throw 'docker run failed; no container owned by this run'
  }

  $ready = $false
  for ($i = 0; $i -lt 30; $i++) {
    docker exec $containerId pg_isready -U qaflex -d qaflex | Out-Null
    if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    Start-Sleep -Seconds 1
  }
  if (-not $ready) { throw 'form test container not ready' }

  $env:JAVA_HOME = 'D:\projects\data_collection_platform\tools\jdk\jdk-21.0.10+7'
  $env:TEST_DATASOURCE_URL = 'jdbc:postgresql://127.0.0.1:15446/qaflex?currentSchema=form_test,public'
  $env:TEST_DATASOURCE_USERNAME = 'qaflex'
  $env:TEST_DATASOURCE_PASSWORD = 'form_test_password'
  $env:SPRING_FLYWAY_SCHEMAS = 'form_test'
  $env:SPRING_FLYWAY_DEFAULT_SCHEMA = 'form_test'
  Set-Location 'D:\projects\data_collection_platform\backend'
  & 'D:\projects\data_collection_platform\tools\maven\apache-maven-3.9.9\bin\mvn.cmd' -q test '-Dtest=FormSchemaMigrationTest'
  $mvnExit = $LASTEXITCODE
}
catch {
  Write-Error $_
  $mvnExit = 1
}
finally {
  if ($containerId) {
    docker rm -f $containerId | Out-Null
    if ($LASTEXITCODE -ne 0) { $cleanupError = "failed to remove test container $containerId" }
  }
  foreach ($key in $keys) {
    [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process')
  }
}
if ($cleanupError) { Write-Error $cleanupError }
if ($mvnExit -ne 0) { exit $mvnExit }
if ($cleanupError) { exit 1 }
```

生命周期失败语义：同名冲突或 15446 端口占用时 `docker run` 失败，`containerId` 为空，不执行任何删除；创建成功后仅在 `finally` 按保存的 ID 清理；测试失败保留原退出码，清理失败只额外报告；测试通过但清理失败返回非零。若环境变量未被 Spring Boot 覆盖，追加 Maven 系统属性 `'-Dspring.flyway.schemas=form_test' '-Dspring.flyway.default-schema=form_test'`，不得退回共享 schema。

**W2/W4/W5（计划）**：每个阶段重复上述同一生命周期块，把 `-Dtest` 替换为对应命令；不得改为按固定名称启动、预删除容器或单独清理：

```powershell
& 'D:\projects\data_collection_platform\tools\maven\apache-maven-3.9.9\bin\mvn.cmd' -q test '-Dtest=FormRecordServiceTest,FormRecordConcurrencyIT'
& 'D:\projects\data_collection_platform\tools\maven\apache-maven-3.9.9\bin\mvn.cmd' -q test '-Dtest=FormPublicationIT,FormPublicationLeaseIT'
& 'D:\projects\data_collection_platform\tools\maven\apache-maven-3.9.9\bin\mvn.cmd' -q test '-Dtest=FormIssueConsumptionIT,FormMrConsumptionIT'
```

**W3/W6 前端**（工作目录 `D:\projects\data_collection_platform\frontend`）：

```powershell
Set-Location 'D:\projects\data_collection_platform\frontend'
npm.cmd run test
npm.cmd run typecheck
npm.cmd run lint
npm.cmd run build
```

按文件过滤用 `npm.cmd run test -- src/views/forms`（Vitest 过滤，不是另一脚本）。

**W6 默认后端套件（计划；隔离库）**：复用 W1 的同一生命周期块（唯一容器名、保存容器 ID、就绪检查、`finally` 仅按 ID 清理），在设置六个环境变量后把测试命令替换为完整默认套件：

```powershell
Set-Location 'D:\projects\data_collection_platform\backend'
& 'D:\projects\data_collection_platform\tools\maven\apache-maven-3.9.9\bin\mvn.cmd' -q test
```

默认套件会清理并重建其 Flyway schema；因此必须使用 15446 独立容器与 `form_test` schema，**不得**使用 15433 的共享 `qaflex_test`，也不得清理其他任务的 schema。

**W7 黄金（仅授权）**：

```powershell
Set-Location 'D:\projects\data_collection_platform\backend'
& 'D:\projects\data_collection_platform\tools\maven\apache-maven-3.9.9\bin\mvn.cmd' test -Pgolden-baseline '-Dtest=GoldenBaselineChainTest'
```

需 Docker；运行前后按 AGENTS 记录并恢复用户既有服务入口，未授权不得运行或用 `-Dgolden.update=true`。

**仓库门禁**：

```powershell
Set-Location 'D:\projects\data_collection_platform'
python scripts/check_worktree_artifacts.py
python scripts/check_runtime_artifact_locations.py
python scripts/check_text_whitespace.py
git diff --check
```

#### 6.3 隔离与清理边界

- `15432` 开发库与 `18080`/`18181` 共享服务只读，不写测试数据、不作为写验收后端。
- `15433` 的 `qaflex_test` 被既有测试与其它任务共用；新表单测试不得清空该 schema，也不得依赖其当前状态。
- 新表单后端测试统一使用 Testcontainers 或专用 `15446` 容器与 `form_test` schema；容器用户/密码必须通过 `TEST_DATASOURCE_USERNAME/PASSWORD` 同步给测试连接（与容器 `POSTGRES_USER/PASSWORD` 一致），每个测试 JVM 从零 Flyway，结束后删除容器/schema，不影响共享库。
- 容器所有权以“本轮成功创建并保存的容器 ID”为准：使用本轮唯一名称，禁止启动前按固定名称删除；同名冲突、端口占用或创建失败时立即停止且不触碰占用者；就绪和清理只针对保存的 ID。清理失败必须报告，测试失败的原退出码优先保留。
- 验收覆盖：已有同名容器（用 `FORM_TEST_CONTAINER_NAME` 指向该容器，预期 `docker run` 失败且容器保持运行）、15446 端口被占、容器创建失败、测试失败、清理失败五种路径，证明其他任务的数据库容器未被停止或删除。
- 浏览器验收使用专用后端（唯一端口，如 18680）与专用隔离库，前端 dev server 使用唯一端口（如 18681）；不得占用 18080/18181。验收后停止并删除专用栈。
- 测试夹具不得指向内网或生产；E2/E3 现场数据只用于现场核验，不写入开发/测试库。

### 7. 外部依赖 E1—E6 实际状态

| 编号 | 仓库内证据 | 当前状态 | 阻塞范围 |
| --- | --- | --- | --- |
| E1 机器人与外部协议 | 仅产品/方案描述与平台自身 `/notification-payload`；未找到 CI_ROBOT 源码/配置、真实 Issue 链接、外部调用认证样例 | 未取得 | W3 可先做稳定路由与内部三模板；外部调用契约迁移、旧协议退役需 E1 |
| E2 内网存量与范围 | 本地 `collect_form_records` 1 条作废行；无内网只读连接；无真实 S 清单 | 未取得 | W1 可做隔离非空迁移；真实 S 覆盖与生产切换前核对需 E2 |
| E3 项目与发布依据 | 本地里程碑：项目 9=14、172=3、325=9、374=1；项目 79 无本地里程碑（与附录 A.2 一致）；实际来源配置仅 1 条本地 GitLab；无目标项目/阶段清单 | 部分本地证据，目标未取得 | 缺真实发布标签证据的范围保持 DRAFT/INSTALLED，**不得以 NULL/空标签发布启用**；可开发并用隔离测试证据验证。未知 RS/DGM 按 E3 暂缓，不阻塞已核范围 |
| E4 域名、代理与会话 | 本地 18080/18181；无目标域名/代理/Cookie 策略 | 未取得 | 本地页面/会话可验收；现场登录与 401 恢复需 E4 |
| E5 外部 AI 自动填充 | 仓库内无自动填充实现或调用；仅方案提及 | 未取得 | 独立采集可推进；外部自动化验收不得以幂等通过代替 |
| E6 制品与旧平台交接 | 本地正式候选 18181 栈存在；发布标准/runbook 存在；未固定本轮目标制品，未核实旧写所有权 | 未取得 | W7/W8 发布与切换；未核对制品/写所有权不得切现场 |

### 8. 技术准入结论与待确认事项

#### 8.1 技术准入与 FR-E1

审核方已按用户委派承担技术审批；当前技术准入结论以§8.14为准，该节是本次W0技术批准的实际依据。FR-E1若另有群内人工流程，单独记录真实依据，不将本次AI技术审核冒记为群内人工评审。

结论：W0技术审核通过，R03剩余两处关闭，R01至R09在设计层全部关闭，批准员工进入W1；审批不代表功能验收或上线授权。

#### 8.2 事项分类（按 §8.5 裁定拆分）

**已有裁定，不重开**：

- 三模板范围、记录归属、并发、草稿、危险操作、原因分层（D-30）。
- 权威策略：存量 Issue 为 COMMENT，切换点后指定范围新资源为 FORM，旧表 S 对应 MR 明确登记 FORM；缺的是 E2/E3 现场项目与切换时间，不是重新表决策略。
- 历史 GitLab 评论不批量导入；已有平台表单记录必须随结构版本迁移（§4.5）。

**工程设计（员工提出单一方案，技术审核）**：

- Issue 详情消费的完整物理投影（§5.4）；不得缩减已确认输出。
- 范围绑定锁定、发布 fencing/租约、失败重做与 head 登记（§2.3/§2.4/§2.7）。
- 初装路径与 Flyway 职责划分（§2.9）已单项批准，批准边界见 §8.8；发布/迁移协议仍待剩余整改通过。
- 历史缺值的物理约束生成（§4.5）。

**外部补证（阻塞对应阶段）**：

- E1—E6 及目标范围真实发布标签证据；缺证据范围保持 DRAFT/INSTALLED，不得 NULL 发布。
- E2/E3 现场项目与切换时间；只影响对应启用/现场切换，不阻塞不依赖它们的 W1 基础存储。

**W5 前需确认（仅 MR 级汇总公式）**：

- 同一 MR 多条有效走查记录时，MR 事实 `review_duration_minutes`（现为 max）与 `code_walkthrough_date`（现取来源 updated_at）的目标公式、实际消费者、旧值/目标值及产出影响，需在 W5 前补充影响说明并确认；不阻塞 W1 基础存储。不得以旧 Demo 的 max 直接当业务依据。

#### 8.3 工程细节（员工可自行完成，审核方抽查）

- 管理员页面权限采用单一 `form.template.manage`（页面进入与操作共用，后端强制），不新增 view 码；普通用户无菜单、路由和端点权限。
- 草稿/预览/发布表的索引、租约参数、任务批大小、错误码映射。
- 记录端点 DTO、字段白名单、指纹规范化、CAS SQL 和审计快照结构。
- 数据库查看动态版本表登记方式（读取元数据生成只读定义），不把动态表名写入代码常量。
- 测试夹具、并发屏障、隔离 schema 和浏览器脚本。

#### 8.4 首次技术审核结论（2026-10-21）

**结论：修改后重审，W1未准入。** 审核方按用户委派负责技术审批；员工继续整改W0，功能实现仍由员工承担。

本次审查输入为`main@84e7c023449de190e722d79d027accb062358172`工作树中的本文件，审查前SHA-256为`f5d9adc4a04d52cd4b358d588f5cac49a9babd5f6edfb8316a67990d2984de0b`。以下是原提案的契约问题，原文保留便于整改对照，不构成被批准的实施方案。

| 编号 | 级别与位置 | 问题及影响 | 重审验收条件 |
| --- | --- | --- | --- |
| R01 | P1，§2.2/§2.4失败恢复 | FAILED后撤栅栏恢复旧版本写入，原目标却仍按失败前的冻结输入核验。此时旧版本新增/修改记录，再恢复原任务，旧副本可能漏掉这些写入；COPYING要求目标为空，也未解释已有复制数据如何恢复 | 明确重试重新停写并捕获新的完整输入，重新复制及守恒核验；以“复制后失败→旧版新增/修改/作废→重试”证明所有成功写入都保留，禁止沿用旧副本直接切指针 |
| R02 | P1，§2.3/§2.4/§3.1租约与副作用 | fencing仅描述阶段CAS，切指针、写DDL/复制及独立撤栅栏未明确与当前任务/租约原子校验；head结算也缺有效租约条件。旧工作者可能在失效后修改结构或撤掉新任务栅栏 | 给出统一锁顺序及当前任务/租约/代次校验，覆盖全部持久副作用和清理；栅栏能识别归属任务；用旧任务超时、新任务接管、旧任务迟到成功/失败交错证明其不能切换、结算或清理新任务 |
| R03 | P1，§2.4切换事务 | 第5步列出指针/绑定/日志/任务变更，未把结构发布对应head登记纳入同一事务。结构变化后既有记录可能未进入下游重建 | 明确受影响资源的持久变更如何随指针切换原子登记，以及旧消费者失效条件；证明没有后续用户保存时，发布本身仍触发必要重建，登记失败则指针不切换 |
| R04 | P1，§2.1与§8.2首版标签 | 允许首版缺证据时使用空标签，与主方案RECORDS/PUBLISHED要求真实version_label及发布时间冲突；LEGACY的历史NULL例外被扩大到新发布 | 缺证据范围保持DRAFT/INSTALLED；发布校验拒绝无证据或空白标签。仅历史记录的产生标签按原规则可空，测试明确两者区别 |
| R05 | P1，§2.4至§2.7初装迁移 | 空平台首启、已有旧表S迁移和首次范围发布混写。旧S有LEGACY来源却按无from_version跳过迁移日志，LEGACY何时先于记录FK插入也未明确；初装Flyway搬迁与运行期发布的职责与主方案不同 | 分开列明真正空平台与已有collect_form_records两条初装路径，确定LEGACY创建、复制、日志及指针顺序；有S即有逐字段迁移证据。保持主方案Flyway初装职责，如需调整先列明技术差异供审核 |
| R06 | P1，§4.3/§4.4历史连续性 | “新表只接收发布后的新记录”排除了后续同模板结构升级应搬迁的既有Issue表单；固定NOT NULL也未处理新增必填字段在历史行中为空的规则 | 区分“不导历史评论”与“迁移已有平台表单”。完整定义版本升级的历史缺值和物理约束生成，验证新增必填字段仍能迁移旧记录且保留ID/归属/时间，新建/编辑继续按当前规格校验 |
| R07 | P1，§2.4范围绑定写回 | 发布事务把草稿来源/项目绑定直接写回范围，未限制已有实例的来源/资源归属变更。普通字段发布可能改变旧链接定位或遗漏原范围历史 | 对已使用范围校验绑定不破坏现有归属、范围不重叠及历史覆盖；跨来源/项目转移按主方案独立迁移审核。以试图通过字段发布换来源/移除历史项目的反例证明会被拒绝 |
| R08 | P1，§5.3原因合规与消费范围 | “COMMENT保持原判据”未区分保留评论解析和延续原因计数bug；§5.4又把已确认的消费/既有详情导出改为可缩减。两处可能改变D-30和本期完整消费要求 | COMMENT保留输入来源，适用门槛内的唯一性判定仍正确计数并遵守D-30；FORM与COMMENT均不兼容漏报。逐项映射现有消费/导出，物理投影方案由技术审核确定，不以减少列为由删除已确认输出 |
| R09 | P2，§6验证入口与隔离 | `-Dtest=...`和`npm run lint/typecheck/build`不能直接执行；表内默认清理15433测试schema，与末段要求独立隔离未落实到具体入口；共享18080也不能用作写入验收后端 | 区分已有与拟新增测试类，列工作目录、PowerShell 7可执行命令、测试后端/连接/schema和清理边界；新增类标为计划待实现。证明验证不会写入业务库或清理其他任务共享schema |

上述问题来自契约逐项核对及失败交错推演，不声称已运行新DDL或复现新实现。已读取现有Surefire报告：CollectFormServiceTest为5项，CollectFormControllerTest为6项，失败/错误/跳过均为0；这些报告仅支持既有入口可运行，不证明新契约正确。本次审核不重跑业务测试、黄金、迁移或服务。

#### 8.5 原“待业务确认”事项归类

本节纠正§8.2的统一阻塞表述，重审时由员工据此修订原提案。

| 事项 | 审核归类与处理 |
| --- | --- |
| MR多记录汇总 | 记录级展示、各记录创建日期已确认；MR事实单值如何汇总仍需说明实际消费者、旧值/目标值及影响，再在W5前确认。不能用旧Demo的max直接当业务依据，也不阻塞不涉及汇总公式的W1基础存储 |
| 权威初始化范围 | 策略已在主方案§3.5确定：存量Issue为COMMENT，切换点后指定范围新资源为FORM，旧表S对应MR明确登记FORM。缺的是E2/E3现场项目和切换时间，不重新表决策略；影响对应启用及现场切换 |
| Issue事实列范围 | 物理列或独立投影属于技术设计，由员工按消费/导出映射提出单一方案供审核。当前已有消费不能缩成只采集，独立新表单导出仍沿主方案后续范围，无需用户逐列决定表结构 |
| 首版发布标签 | 属E3外部证据，按R04处理；可开发并用隔离测试证据验证，目标范围没有真实依据就不发布启用，不能以NULL发布绕过 |

产品文档另有CC2027R1及后续版本切换原因分析模板的已确认边界，但新字段尚未提供。相关词典/导出适配跟踪其材料，不擅自将本次冻结的旧词典当作2027模板，也不阻塞通用存储整改。

用户已委派本对话承担技术审批，本节就是首次审核记录。FR-E1如另需群内人工通过，单独记录真实依据；不能伪造，也不能以仓库缺少文件概括本次全部未准入原因。本次不通过的技术原因以R01至R09为准。

#### 8.6 员工重审交付

1. 逐项修订R01至R09，附原编号、修改章节及失败路径的预期结果，保留同一主方案业务基线。
2. 将§8.2按§8.5拆成已有裁定、工程设计、外部补证及W5前需确认的汇总公式，避免全局阻塞。
3. 提交完整修订稿、相对本次审查输入的差异及文件SHA-256，更新执行单和进度。技术批准前不把本稿提升为主方案生效契约。
4. 本轮继续W0文档整改，不写W1功能，不清库、不启停现有服务、不运行黄金。审核方核对后另给明确准入结论。

#### 8.7 员工重审整改记录（2026-10-21）

**结论：R01—R09 已逐项修订，提交重审；W1 仍未准入。** 本文件是完整修订稿。审查输入 SHA-256 为 `f5d9adc4a04d52cd4b358d588f5cac49a9babd5f6edfb8316a67990d2984de0b`；修订稿 SHA-256 见本节末。文件未纳入 Git，无法提供字节级 `git diff`；以下按审查编号列出修改章节、修订内容与失败路径预期，审核方可用其审查副本按章节对照。

| 编号 | 修改章节 | 修订内容 | 失败路径预期 |
| --- | --- | --- | --- |
| R01 | §2.1/§2.2/§2.4 | 新增 `form_template_publication_attempts`；FAILED 重试必须新 attempt、重新停写、重新捕获完整 S、清空目标后重复制；旧副本只作诊断 | “复制后失败→旧版新增/修改/作废→重试”后，切换目标包含第二次捕获的全部行；旧副本不能切指针；结构漂移/外部写入拒绝恢复 |
| R02 | §1.1/§2.3/§2.4/§3.1/§3.2 | 范围增加栅栏归属列与 token；统一锁顺序；取得全部锁后用 `clock_timestamp()` 复核并续租，`statement_timeout` 只限制单条语句、累计超预算由提交前校验拦截；明确诊断写入、过期回收与必须回滚的发布/结算；旧工作者不能撤新栅栏 | 到期前开事务、到期后得锁→影响 0 行且无指针/head/栅栏变化；单条或累计超预算→事务回滚；旧工作者只能写自身诊断，不能切指针/结算 |
| R03 | §2.4/§3.1 | 切换事务内 set-based upsert 受影响 head；消费者在结果提交事务内锁同一批 head，复核存储版本/权威修订后同事务公开结果并结算 | 旧候选先提交则公开发生在切换前；切换先提交则旧候选复核失配、整批结果回滚并释放租约；无部分公开结果 |
| R04 | §2.1/§2.5/§2.6/§7/§8.2 | 删除“首版空标签”允许；发布要求非空 `version_label` 与版本证据；PUBLISHED RECORDS 必须有标签和时间；仅历史迁移记录 `created_by is null` 时可保留 NULL 产生标签 | 缺证据/空白标签范围保持 DRAFT/INSTALLED；发布请求 409；新建记录缺标签拒绝；历史迁移行 NULL 标签保留且不用于新发布 |
| R05 | §2.6/§2.9 | 分离空平台（INITIAL_EMPTY）、旧表 S（INITIAL_MIGRATION）、既有 RECORDS（MIGRATION）；LEGACY 先插入再复制；INITIAL_MIGRATION 写 LEGACY→RECORDS 迁移日志；Flyway 职责差异单列待批 | S 为空走空平台路径；S 未覆盖/越界停止上线；有 S 必有逐字段迁移证据；无 from_version 的空平台不写迁移日志；Flyway 基础职责保留 |
| R06 | §4.3/§4.4/§4.5 | 区分“不导历史评论”与“迁移已有平台表单”；跨版本迁移保留 ID/归属/时间/origin/template_version/deleted/record_version；历史缺值以 `legacy_incomplete/legacy_missing_fields` 标记，约束统一生成 `deleted OR legacy_incomplete OR ...`，不使用 `NOT VALID`；编辑补齐后清标记 | 历史行新增必填字段为 NULL 仍可迁移且可直接作废；新建缺必填被拒；编辑历史行补齐前被拒、补齐后成功；重试重复制仍接受历史 NULL；历史评论不进入新表 |
| R07 | §1.1/§2.7 | 范围增加 `binding_revision/binding_hash`；首次发布写绑定，后续字段发布绑定必须不变；校验范围重叠、指针、版本项目、实例/记录覆盖与 S 全覆盖；跨来源/项目转移另立迁移 | 已有实例时通过字段发布改来源/移除项目/造成范围重叠均 409、指针不变；旧外链仍定位原记录；S 未覆盖停止 |
| R08 | §5.3/§5.4 | COMMENT 保留评论输入但合规统一用 `DefectCauseComplianceCounter` 按稳定**具体原因 ID** 去重计数，一级大类仅分类统计；别名归并同一 ID；未匹配片段作为独立未知具体原因参与计数并留报告；§5.4 改为完整消费投影映射，删除缩减选项 | 同大类双具体原因、跨大类双原因均判非法；同一原因多别名计 1；0 项非法；FORM 同规则；保存多选成功；现有修复状态/原因/具体原因/修改方案/影响功能/测试/遗留/关联关系表/修复人/计划/SLA/导出输出均保留 |
| R09 | §6 | 区分已有/计划测试类；给出 PowerShell 7 工作目录与命令；新表单测试用 15446 独立容器 + `form_test` schema 或 Testcontainers，显式设置 `TEST_DATASOURCE_USERNAME/PASSWORD` 与容器一致；检查就绪/退出码，`finally` 清理并恢复环境变量；不得用 15432/15433 共享 schema 或 18080/18181 写验收；浏览器用专用 18680/18681 | 干净环境命令可执行且认证成功；失败不被清理掩盖；新增类未实现前标计划；测试清理仅限专用容器；共享 schema/服务不受影响 |

**本次未做**：未写 W1 功能、未执行新 DDL、未清库、未启停服务、未运行黄金或事实链。§2.9 的 Flyway 初装职责差异仍需审核方明确批准或退回。修订稿 SHA-256 记录在[实施与审核清单](unified-form-system-implementation-plan-20261021.md)的 W0 交付记录（避免本文件写入自身哈希造成自引用）；审查输入 SHA 见本节开头。

#### 8.8 第二次技术审核结论（2026-10-21）

**结论：修改后重审，W1 未准入。** 审核输入为本文件 1096 行修订稿，SHA-256 `21e6cbf29f4d83bc3c01fce86fda171bc129c271f610ba6850c31ae947f91628`，仓库 HEAD 为 `84e7c023449de190e722d79d027accb062358172`。输入副本与清单位于 `.tmp/form-w0-rereview-20261021/`。本节追加后文件哈希会改变，上述哈希仅标识员工提交的审查输入。

本次按主方案、产品/架构与 D-30 核对完整修订稿。四项在设计层关闭，五项仍有阻塞。关闭不代表实现或运行验收通过，后续仍须按 W1 至 W7 提交相应证据。

| 编号 | 结论 | 核对结果 |
| --- | --- | --- |
| R01 | 单项通过 | §2.2/§2.4 明确失败后重新停写、捕获包含失败期间全部成功写入的完整 S、清空目标并全量复制；旧副本不作切换依据。历史缺值约束与重复制的配合仍按 R06 补齐 |
| R02 | 未关闭，P1 | 归属 token、锁顺序与 fencing 已补充，但租约有效性的时间校验仍会接受已过期任务，见下文 |
| R03 | 部分通过，仍为 P1 | 指针切换与 head 登记同事务已补齐；旧候选的版本复核与公开结果提交尚未形成原子协议，见下文 |
| R04 | 单项通过 | 新发布必须具备非空标签与证据，历史行的 NULL 产生标签例外未再扩大为新发布例外 |
| R05 | 单项通过 | 三路径、LEGACY 先于复制和初装迁移日志已明确；批准下述 Flyway 职责划分 |
| R06 | 部分通过，仍为 P1 | 已区分历史评论与平台记录，并明确先复制后加约束；新增必填缺值仍会阻止作废和部分失败重做，见下文 |
| R07 | 单项通过 | 后续字段发布不得修改既有绑定，范围重叠与历史未覆盖会拒绝切换；跨来源/项目转移另行验收 |
| R08 | 未关闭，P1 | 完整消费投影不缩减的整改通过；原因唯一性改成按一级大类计数，直接违反 D-30，见下文 |
| R09 | 部分通过，仍为 P2 | 专用容器、schema、端口与实际 npm 脚本已明确；新容器密码没有传给测试连接，命令仍不能按所列步骤成功运行 |

**剩余整改与验收条件**：

1. **R08：按稳定具体原因 ID 去重计数。** 审查输入第 821 至 823 行要求统计不同一级大类，§8.7 又明确“同大类多具体原因合法”。例如同时保存 `standard_error` 和 `function_forget`，两者都属于“编码规范”，当前修订稿会判为一个原因；D-30 要求它们按两个具体原因判非法。删除全部“大类数量决定唯一性”和“同大类多原因合法”条款；COMMENT 的等价别名归并到同一具体 ID，FORM 直接使用具体 ID 集合，大类仅供分类统计。覆盖同大类双原因、跨大类双原因、同一原因多个别名、零原因和未到检查状态；保存多选继续成功，适用门槛内多原因仍标非法。
2. **R06：历史缺值不得阻止本人作废。** 审查输入第 768 行的 `CHECK (col IS NOT NULL) NOT VALID` 在添加时跳过旧行，但以后对该行的 UPDATE 仍生效。旧记录新增必填字段为空时，按主方案只更新 `deleted/record_version/updated_at` 的作废 SQL 也会失败，用户被迫补填才能删除。员工需给出一个完整的约束生成方案，同时允许迁移历史 NULL、本人确认后直接作废，并对新建和业务编辑执行当前字段校验。重试复用目标结构时，还须处理上一次已添加的约束，否则清空目标后重插历史 NULL 同样会失败。验收覆盖“升级新增必填→直接作废”“复制并加约束后失败→新 attempt 重复制”“新建/编辑缺必填被拒”，保持审计/head 原子性和原值追溯。
3. **R02：租约校验使用实际校验时刻，并明确持锁事务的到期处理。** 审查输入第 298、312、481 行使用 `current_timestamp`；PostgreSQL 中它固定为事务开始时间。任务在到期前开启事务，等待 scope/head 锁到到期之后，仍可能按旧时间通过校验并切指针或结算。取得必要锁后应按数据库实际时刻复核 owner/fencing/stage/lease，并明确在持锁长事务中到期时哪些诊断写入允许提交、哪些发布/结算必须回滚或终止。不能只在事务入口查询一次；按统一锁顺序给出最终发布与结算的有效性边界。验收用受控锁交错覆盖“到期前开事务、到期后得锁”和“校验后执行超预算”，证明不会把过期租约作为有效发布依据。
4. **R03：版本复核和公开结果提交必须相互约束。** 审查输入第 482 行只有“提交前再次读取版本”。允许的交错仍可能是：旧消费者读到 v1→结构发布切到 v2 并递增 head→旧消费者提交 v1 结果；第 481 行允许仅结算捕获的较小版本，不能据此阻止这次旧结果公开。员工需明确结果提交、结构版本/权威修订核验与 head 结算的同事务条件及加锁顺序，和切换事务形成共同的串行化边界；较新 head 留待消费不等于允许旧结构候选继续公开。覆盖“复核后暂停→完成结构切换→恢复旧候选”及反向交错，证明旧候选只能在切换前完整提交，或在切换后被原子拒绝；失败不改变公开指针。
5. **R09：补齐独立库连接凭据与失败处理。** 审查输入第 892 行将容器密码设为 `form_test_password`，第 897 至 899 行只设置 URL/Flyway schema；现有测试配置默认密码为 `change_this_password`。在干净环境执行所列命令会认证失败。明确设置该专用容器对应的 `TEST_DATASOURCE_USERNAME/PASSWORD`，适用于 W1、W2/W4/W5 与 W6；检查数据库就绪和各原生命令退出码，确保失败不会被后续清理的成功掩盖，结束后恢复本次修改的环境变量。W0 只修订命令；新增测试类实现后再以真实连接验证，不要求现在运行不存在的类。

数据库语义已核对 PostgreSQL 16 官方文档：[NOT VALID 仍约束后续插入和更新](https://www.postgresql.org/docs/16/sql-altertable.html)、[current_timestamp 为事务开始时间](https://www.postgresql.org/docs/16/functions-datetime.html#FUNCTIONS-DATETIME-CURRENT)。以上是契约反例与静态核对，本次未执行新 DDL 或运行并发探针。

**Flyway 初装职责裁定**：批准 §2.9 将环境相关的范围绑定和旧表 S 搬迁交给 `INITIAL_MIGRATION` 受控发布；通用 Flyway 继续安装基础结构、能力表、权限与种子模板。理由是绑定与真实标签由目标环境提供，管理员预览/发布应复用同一执行入口。停旧写、排空、S 全覆盖、逐字段守恒、迁移日志、原子切换及开放新写前的验收要求均保留。此批准仅改变执行入口，不批准省略事务或守恒，也不代表 R02/R03/R06 的发布与迁移协议已通过。完整 W0 获准后，员工须同步修改主方案 §3.4 的两处 Flyway 初装搬迁表述，保持单一设计来源。

**验证与交接**：本轮读取提交文件与实际测试配置、package scripts，核对输入哈希及行数；未重跑既有 11 项业务测试，其历史结果不证明新方案正确。未改业务代码、数据库、服务、权限、迁移或黄金。MR 级汇总公式仍在 W5 前提交影响说明后确认；E1 至 E6 按原阶段补证。员工继续 W0，仅修订剩余五项、清除对应矛盾表述，提交相对本次 1096 行输入的完整差异和新 SHA-256 后重审。未跟踪文件同样可用审查副本作文本差异，不应以未纳入 Git 为由省略完整差异。

#### 8.9 员工第三次整改记录（2026-10-21）

**结论：R02、R03、R06、R08、R09 已整改，提交重审；W1 仍未准入。** 审查输入为 1096 行修订稿，SHA-256 `21e6cbf29f4d83bc3c01fce86fda171bc129c271f610ba6850c31ae947f91628`；本次修订稿 SHA-256 记录在[实施与审核清单](unified-form-system-implementation-plan-20261021.md)的 W0 交付记录（避免本文件写入自身哈希造成自引用）。文件未纳入 Git，但可按 `.tmp/form-w0-rereview-20261021/` 审查副本逐节对照；已删除 `NOT VALID` 约束方案和“同大类多原因合法/按大类数量判定唯一性”表述，不再保留矛盾条款。

| 编号 | 修改章节 | 修订内容 | 失败路径预期 |
| --- | --- | --- | --- |
| R02 | §2.3/§2.4/§3.1 | 租约判断全部改为取得锁后按 `clock_timestamp()` 复核并续租；`statement_timeout` 仅限制单条语句，累计超预算由提交前实际时刻校验拦截；诊断写入、过期租约回收与必须回滚的发布/结算分别定义 | 到期前开事务、到期后得锁→影响 0 行、无指针/head/栅栏变化；单条语句超时或累计超预算→事务回滚、attempt 记录失败；过期任务只能由维护回收置 FAILED，不能切指针 |
| R03 | §3.1/§2.4 | 消费者在结果提交事务内按 id 升序锁 head，复核存储版本/权威修订/租约后同事务公开结果并结算；移除“仅结算捕获的较小版本后继续公开” | 旧候选先提交→公开发生在切换前，切换随后递增 head；切换先提交→旧候选复核失配、整批结果不写、lease 释放为 PENDING；失败不改变公开指针、无部分结果 |
| R06 | §4.4/§4.5/§2.9 | 增加内部列 `legacy_incomplete/legacy_missing_fields`；必填/条件约束统一生成 `deleted OR legacy_incomplete OR ...`，不使用 `NOT VALID`；直接作废不校验字段；编辑补齐后同事务清标记；重试按冻结结构 truncate 重复制 | 升级新增必填→缺值历史行可直接作废；复制加约束后失败→新 attempt 重复制仍接受历史 NULL；新建/编辑缺必填被拒；重试不得删约束或补伪造值 |
| R08 | §5.3/§5.4/§8.7 | 合规计数改为稳定具体原因 ID 集合去重；一级大类仅分类统计；别名归并同一 ID；未知片段作为独立未知具体 ID 参与计数；验收覆盖同大类/跨大类/多别名/零原因/未到门槛 | `standard_error`+`function_forget` 判非法；跨大类双原因判非法；同一原因多别名计 1 合法；0 项非法；保存多选成功；现有消费/导出不缩减 |
| R09 | §6.2/§6.3 | 命令显式设置 `TEST_DATASOURCE_USERNAME/PASSWORD` 与容器一致；就绪轮询、原生命令退出码检查、`finally` 清理并恢复环境变量；W2/W4/W5/W6 统一沿用 | 干净环境命令认证成功；数据库未就绪或 Maven 失败均以非零退出，清理成功不掩盖失败；共享 schema/服务不受影响 |

**Flyway 裁定**：接受 §8.8 批准边界：通用 Flyway 安装基础结构、能力表、权限与种子模板，环境相关绑定与旧表 S 搬迁走 `INITIAL_MIGRATION` 受控发布；停旧写、排空、S 全覆盖、逐字段守恒、迁移日志、原子切换和开放新写前验收全部保留。主方案 §3.4 两处表述待完整 W0 获准后同步，本轮不动主方案。

**本次未做**：未写 W1 功能、未执行新 DDL、未清库、未启停服务、未运行黄金或事实链；未新增测试类。既有表单定向测试 11 项通过仅作为旧入口证据，未重跑也不作为新契约验收。R01、R04、R05、R07 与 Flyway 职责划分沿用 §8.8 单项通过结论，本轮未改动其语义。

#### 8.10 第三次技术审核结论（2026-10-21）

**结论：修改后重审，W1 未准入。** 本次输入为员工提交的 1204 行修订稿，SHA-256 `3d4eef7b2cb44d5763d9d1ec5f4e4bc36d126035e44c6766c6b5bbf24334fb22`，HEAD `84e7c023449de190e722d79d027accb062358172`。393 行差异的 17 个 hunk 已逐段核对上下文，并从第二次审查的 1096 行副本重建出完全一致的新稿。输入副本、差异及核对材料位于 `.tmp/form-w0-review3-20261021/`。本节追加后的文件不再使用该输入哈希标识当前字节。

| 编号 | 当前结论 | 审核范围 |
| --- | --- | --- |
| R01、R04、R05、R07 | 沿用单项通过 | 失败后完整重复制、发布标签、三初装路径及范围绑定的既定裁定保持；Flyway 职责划分沿用 §8.8 |
| R02 | 本轮单项通过 | 取得锁后按实际时刻复核并续租；区分过期回收、诊断写入、发布/结算回滚；切换最终校验及 head 结算与公开结果同事务。执行解释见下文 |
| R06 | 本轮单项通过 | 内部历史缺值标记不对客户端开放；迁移约束允许历史 NULL 与直接作废；业务编辑按当前规格补齐并清标记；重复制使用同一冻结结构与标记，不删除约束 |
| R08 | 本轮单项通过 | 按稳定具体原因 ID 集合去重，等价别名归并，同大类多个具体原因仍判非法；采集成功与合规独立，统计公式保持 |
| R03 | 仍未关闭，P1 | 已有实例的 head 与结果提交可串行化，但新协议漏掉无实例资源，见下文 |
| R09 | 仍未关闭，P1 | 凭据、就绪、环境恢复已补齐；新增无条件删除固定名称容器，破坏测试隔离，见下文 |

**R03：结构变更保护必须覆盖尚未填写的资源。** 审查输入第 348 至 360 行只从目标版本 `<instances>` 集合登记 head；第 504 行只比较捕获与当前的 `pending_storage_version_id/pending_authority_revision`，把 `scope.current_version_id` 改成“只作证据”。主方案规定实例在首次正式提交时建立，而资源镜像到达时先登记权威及 head。因此以下状态完全合法：资源已是 FORM、有 head，但用户尚未保存表单，实例不存在。

反例：该 head 的存储证据为 NULL 或 v1，消费者捕获 scope v1；管理员发布 v2，因该资源无实例，切换 SQL 不更新也不锁它的 head；消费者随后取得该 head 锁，两项 pending 证据仍与捕获值相同，按第 505 行提交并结算 v1 候选。新协议既没有共同锁，也不再以实际 scope 版本不匹配拒绝该结果，不能保证“结构切换先完成时旧候选必被拒绝”。这是本轮用 pending 证据替换实际版本判定后遗漏的场景，不要求为未填写资源创建空实例或记录。

员工需给出覆盖完整受影响资源的单一协议，使无实例资源、已有实例资源及对应权威/head 都能参与结构变更的原子保护。明确 head 存储证据初始化、更新集合与最终核验条件，证明 pending 证据和实际 scope 版本一致；保留统一锁顺序，不能靠无锁再读一次当前指针解决。验收至少包括：FORM 已登记但无实例、已有实例、首次空范围启用；两种提交顺序均受控；切换先成功时旧候选零公开、零结算，失败保持旧公开指针；无后续用户保存也能触发必要重建；不生成空业务记录。

**R09：仅清理本轮拥有的容器。** 审查输入第 923 至 924 行先固定命名 `qaflex-form-test-pg`，再无条件 `docker rm -f $container`；第 936、958 行也按名称删除。第二个测试任务运行同一入口时，会先删掉第一个任务正在使用的数据库，随后可能在任一任务的 finally 中再次删除对方刚创建的同名容器。固定测试用途名称不能证明本轮拥有该容器。

凭据问题已解决，本项剩余整改是去掉启动前强制删除，采用本轮唯一名称或遇到已有名称立即停止，并保存成功创建返回的容器 ID；就绪检查和清理只针对该 ID，只有本轮创建成功才有清理资格。端口被占用时明确失败，不删除占用者。容器创建、就绪和测试应纳入同一生命周期处理；清理失败要报告，保留原测试失败结果。验收覆盖已有同名容器、固定端口占用、创建失败、测试失败和清理失败，证明其他任务容器未被停止或删除。W0 只改命令与契约，不为本次复审启动或清理任何容器。

**R02 执行解释**：`statement_timeout` 限制的是单条语句，不能当作整个事务的累计超时。这里批准的是实际时刻复核、持锁互斥与最终发布/结算校验协议；员工下一稿应同步纠正 §2.3 中将语句超时称为整个事务超预算硬中止的文字，并补“多条语句各自未超时但累计超过租约”的验收。维护回收清除 scope 栅栏时仍必须先锁 scope 再锁任务，不得反向取锁。[PostgreSQL 16 官方说明](https://www.postgresql.org/docs/16/runtime-config-client.html#GUC-STATEMENT-TIMEOUT)明确按每条语句分别计时。本项不要求新增另一套租约或发布模型。

**证据与后续范围**：本轮核对文档、完整差异及数据库超时语义，未运行新 DDL、迁移、事实链、黄金或浏览器，未重跑既有 11 项测试。以上是设计层反例，未声称已复现新实现。W1 继续等待；员工修订 R03/R09 并同步上述 R02 文字解释后，提交相对本次 1204 行输入的完整差异、新 SHA-256 和逐项答复。已关闭条目不重复开发，MR 汇总公式和 E1 至 E6 仍按原阶段处理。主方案保持不动，完整准入后再合并获准技术契约。

#### 8.11 员工第四次整改记录（2026-10-21）

**结论：R03、R09 已整改，R02 文字已按单条语句语义纠正；W1 仍未准入。** 审查输入为 1204 行修订稿，SHA-256 `3d4eef7b2cb44d5763d9d1ec5f4e4bc36d126035e44c6766c6b5bbf24334fb22`；本次修订稿 SHA-256 记录在[实施与审核清单](unified-form-system-implementation-plan-20261021.md)的 W0 交付记录（避免本文件写入自身哈希造成自引用）。文件未纳入 Git，可对照 `.tmp/form-w0-review3-20261021/` 审查副本逐节核验；已关闭项未重复开发。

| 编号 | 修改章节 | 修订内容 | 失败路径预期 |
| --- | --- | --- | --- |
| R03 | §2.4/§3.1/§3.2 | 受影响集合改为 A（本范围本模板 FORM 权威，含无实例资源）∪ B（目标实例）；FORM 权威登记初始化 head 存储证据并建立不变量 I-R03；切换先补建缺失 head、按 id 升序锁 A∪B、全部写 `pending_storage_version_id=toVersionId`，提交前做三项覆盖核验；消费者按权威类型复核，FORM 的 pending 必须非空且等于捕获值 | FORM 已登记无实例：切换后旧候选捕获 NULL/旧版 vs 当前新版→复核失配，零公开零结算；已有实例同；两种提交顺序均受控（旧候选先提交则公开在切换前，切换先提交则原子拒绝）；首次空范围启用 A/B 为空仍成功；切换失败指针/head 不变；无后续保存也触发重建；不生成空实例/空记录 |
| R09 | §6.2/§6.3 | 删除启动前按固定名称 `docker rm -f`；本轮唯一名称，保存 `docker run` 返回的容器 ID，就绪与清理只针对该 ID；同名冲突、端口占用、创建失败立即停止且不触碰占用者；清理失败报告，测试失败退出码优先保留 | 已有同名容器、15446 被占、创建失败均不删除他人容器；测试失败保留原退出码；清理失败单独报告；其他任务数据库容器不受影响 |
| R02 | §2.3/§2.4/§8.9 | 修正 `statement_timeout` 为单条语句计时，不再表述为事务累计超时；累计超预算由提交前 `clock_timestamp()` 校验拦截；新增“多条语句各自未超时但累计超过租约”的验收 | 多语句累计超租约→提交前校验失败、事务回滚、旧指针不变；单条超时→`statement_timeout` 中止；两者都不产生发布/结算副作用 |

**关闭项保持**：R01、R02、R04、R05、R06、R07、R08 及 §2.9 Flyway 职责划分沿用 §8.10 结论，本轮未改其语义；R03/R09 是本轮唯一新整改项。I-R03 只新增 head 证据初始化与切换内覆盖核验，不新增实例、记录或另一套发布模型。

**本次未做**：未写 W1 功能、未执行新 DDL、未清库、未启停服务、未运行黄金或事实链；未新增测试类，未重跑既有 11 项测试。W0 仍只改文档与命令契约。

#### 8.12 第四次技术审核结论（2026-10-21）

**结论：修改后重审，W1 未准入。** 本次输入为1377行修订稿，SHA-256 `812ae85edfe9fee34bc7f0d7ffbdf9b8fed9ba7dd4f63c44323490b59de22d41`，HEAD `84e7c023449de190e722d79d027accb062358172`。429行差异的16个hunk已从第三次审查的1204行副本完整重建出相同提交稿。副本、差异与核对材料位于`.tmp/form-w0-review4-20261021/`；追加本节后的文件哈希不再等于审查输入哈希。

| 条目 | 裁定 | 审核范围 |
| --- | --- | --- |
| R09 | 本轮单项通过 | 去掉名称预删除，保存本轮创建的容器ID；就绪与清理按ID执行；创建失败不删除占用者；保留测试失败码并报告清理失败，恢复环境变量 |
| R02文字 | 通过 | 明确语句超时与累计租约校验的区别，补充多条语句累计超过租约的验收；此前租约协议批准保持 |
| R03集合覆盖 | 通过 | A∪B包括FORM权威的无实例资源；登记、切换与覆盖计数维护I-R03，不生成空业务实例/记录 |
| R03执行契约 | 未关闭，P1 | 新增的实际SQL未锁定CTE内的head基表行，FORM分支又遗漏此前要求的权威修订比较，见下文 |
| 其余条目 | 沿用批准 | R01/R04/R05/R06/R07/R08与Flyway职责划分保持；已关闭项不重新表决 |

**R03-a：按ID排序的CTE结果不等于按ID锁住head行。** 审查输入第393至419行把A∪B放在`affected`这个WITH查询中，最终执行`select id from affected order by id for update`。PostgreSQL明确说明，主查询的锁定子句不作用于其引用的WITH查询；当前最终查询没有直接锁定`form_change_heads`基表。因此不能声称第419行已按升序取得全部head行锁。[PostgreSQL 16 SELECT官方文档](https://www.postgresql.org/docs/16/sql-select.html#SQL-FOR-UPDATE-SHARE)说明了该边界。

后续第422至429行的UPDATE仍会对实际更新行加锁，但它没有按ID取得锁的保证，不能替代约定的预锁定步骤。例如发布UPDATE先锁head 2、消费者按升序先锁head 1，再各自等待另一行，会形成死锁，导致发布或结果事务被数据库中止。此处不声称UPDATE完全没有锁，也不以此断言发生了无保护的指针提交；问题是正式SQL没有实现双方共用的锁顺序。

员工需让最终锁定查询显式读取并锁定`form_change_heads`的实际行，A∪B只作为ID筛选集合，并按`head.id`升序取得基表行锁；保持这些锁直到同事务的更新、最终核验和提交完成。不要把锁直接加进UNION结果来代替基表行锁。后续W4/W5以真实PG两个会话验证：锁定查询执行后另一会话修改同一head会等待；两行物理顺序与ID顺序不同、消费与切换交错时仍按统一顺序；已有实例与无实例两类均覆盖。W0先修正可实现的SQL契约，不在本次审核运行DDL或业务探针。

**R03-b：FORM也必须复核权威修订。** 审查输入第610至612行按权威类型分支：FORM只要求存储版本非空且相等，只有COMMENT明确比较`pending_authority_revision`。I-R03证明的是存储版本与scope一致，不能证明来源权威修订未变。此前§8.8及§8.10要求存储版本与权威修订都参与同事务复核，本轮不应因新增分支而省略FORM的修订条件。

员工需把`pending_authority_revision`的NULL安全比较作为两种权威共用的最终条件，同时明确捕获与当前的权威类型也相符，再分别校验FORM非空存储版本、COMMENT空存储版本。覆盖“存储版本不变而权威修订变化”，以及无实例资源FORM→COMMENT→FORM的交错，证明旧捕获修订不会在存储版本恢复相同后通过最终公开/结算。权威更新仍与head修订登记同事务，不增加新来源或新的兼容路径。

**交接边界**：只剩R03上述两处修订，A∪B方案和R09容器生命周期无需重做。员工提交相对本次1377行输入的完整差异、新SHA-256及逐项答复后重审；完整准入前保持主方案和W1业务代码不动。MR汇总公式在W5前确认，E1至E6按原阶段补证。本轮仅文档、差异和官方数据库语义核对，未运行新DDL、迁移、事实链、黄金、浏览器或既有11项业务测试；测试命令设计通过不代表这些新场景已实测。

#### 8.13 员工第五次整改记录（2026-10-21）

**结论：R03-a 基表行锁与 R03-b 权威修订复核已修订；W1 仍未准入。** 审查输入为 1377 行修订稿，SHA-256 `812ae85edfe9fee34bc7f0d7ffbdf9b8fed9ba7dd4f63c44323490b59de22d41`；本次修订稿 SHA-256 记录在[实施与审核清单](unified-form-system-implementation-plan-20261021.md)的 W0 交付记录（避免本文件写入自身哈希造成自引用）。可对照 `.tmp/form-w0-review4-20261021/` 审查副本逐节核验；A∪B 集合覆盖与 R09 容器生命周期沿用 §8.12 通过结论，本轮未重做。

| 编号 | 修改章节 | 修订内容 | 失败路径预期 |
| --- | --- | --- | --- |
| R03-a | §2.4 | A∪B 只用于求 `affectedIds`，不再对 WITH 结果加锁；新增 2b 直接查询 `form_change_heads` 基表并按 `id` 升序 `FOR UPDATE`，锁保持到更新、最终核验和提交；返回行数少于 affectedIds 基数即回滚；2a/2b 之间不提交、不重启事务 | 执行锁定查询后另一会话修改同一 head 会等待；两行物理顺序与 ID 顺序不同时仍按 ID 升序；已有实例与无实例资源、消费与切换交错均不死锁且按统一顺序 |
| R03-b | §3.1 | 捕获时记录权威类型、`authority_revision` 与 `pending_authority_revision`；结果提交事务内对两种权威都做 NULL 安全比较：捕获/当前权威类型相同、实际 `authority_revision` 相等、`pending_authority_revision` 相等；FORM 再要求存储版本非空且相等，COMMENT 再要求存储版本均为 NULL | 存储版本不变但权威修订递增→零公开零结算；无实例资源 FORM→COMMENT→FORM 后类型与存储版本恢复相同，但修订不同→仍拒绝，不能凭最终类型/版本通过 |
| 验收 | §3.1/§2.4 | 增加双会话基表锁、物理顺序与 ID 顺序不一致、权威修订变化、无实例 FORM→COMMENT→FORM 交错用例；W4/W5 以真实 PG 执行 | 锁定查询后并发更新等待；旧捕获修订不会被最终公开/结算接受；切换失败保持旧公开指针 |

**本次未做**：未写 W1 功能、未执行新 DDL、未清库、未启停服务、未运行黄金或事实链；未新增测试类，未重跑既有 11 项测试。W0 仍只改文档契约，SQL 为待 W4/W5 用真实 PostgreSQL 验证的契约。

#### 8.14 第五次技术审核与W1准入（2026-10-22）

**结论：W0通过，批准进入W1。** 审核输入为1426行修订稿，SHA-256 `0c20354a46f6e1761c29bdf10f0e6f038c035aa4ee339191fad2d63585b29f1a`，HEAD `84e7c023449de190e722d79d027accb062358172`。158行差异的8个hunk已从第四次审查的1377行副本完整重建出相同提交稿。输入副本、差异及核对材料位于`.tmp/form-w0-review5-20261022/`；追加本节后的文件哈希不再等于批准输入哈希。

| 条目 | 裁定 | 核对结果 |
| --- | --- | --- |
| R03-a | 关闭 | A∪B只筛选ID；切换与消费者直接查询head基表，按不可变id升序取得行锁并持有到同事务更新/核验/提交，缺行即回滚；不再把WITH结果锁当作基表锁 |
| R03-b | 关闭 | 两种权威共用类型、实际authority_revision及pending_authority_revision复核；缺失权威拒绝，FORM/COMMENT分别再检查非空/空存储证据；首次登记与后续修订都原子推进head证据 |
| R01/R02/R04/R05/R06/R07/R08/R09 | 沿用通过 | 历次单项批准保持；Flyway基础安装与INITIAL_MIGRATION职责划分按§8.8生效，无新增设计阻塞 |
| 完整W0技术准入 | 通过 | 原九项问题在设计层全部关闭，业务需求与既有阶段边界保持；W1尚未开始实现 |

**批准范围与执行顺序**：

1. 员工先将本次获准的物理存储、字段规格、发布/迁移、锁与租约、head/权威及消费契约归并主方案第三章；替换已被否决的旧表述，包括两处Flyway初始搬迁、旧副本恢复、历史缺值约束及原因计数。同步产品/架构的审核状态时保留“尚未实现”事实。以本节固定输入作为归并依据，不把旧审核意见或失效提案误当当前契约，也不长期保留第二套设计来源。归并属于已批准W1的起始工作，无需重新表决既定需求。
2. 按实施清单W1完成存储与迁移基础：新增迁移、checksum追加、三模板规格、范围/结构校验、权限与数据库查看登记，以及隔离PG空库与代表性非空迁移验证。保护混合工作树，测试仅使用本轮隔离资源；不得改既有迁移或写入业务库。
3. W1交付后提交实际差异、测试通过/失败/跳过、迁移守恒与失败回滚证据，供审核方验收；W1准入不代表W1验收通过，不提前越过W2至W8的阶段审核。审核方继续只审批与审核，实施、测试和修复由员工完成。

**验证边界与后续门禁**：本次是设计与实施准入审核，未执行新DDL、迁移、双会话并发、事实链、黄金或浏览器，未重跑既有11项业务测试；这些历史结果不构成新方案验收。基表锁等待/顺序、权威修订变化及FORM→COMMENT→FORM、无实例资源、租约累计超时、失败重复制和完整结果原子提交，仍须在对应W1/W4/W5/W6阶段提交真实PG及实际链路证据，必测跳过不算通过。

MR级汇总公式仍在W5前补充消费影响并确认；E1至E6与真实发布标签证据继续按对应阶段补齐，不扩为不依赖它们的W1阻塞。目标范围缺发布证据仍保持DRAFT/INSTALLED。本次批准不授权黄金快照/夹具变更、内网部署或写所有权切换，相应授权与W7/W8验收规则保持。

## 决策记录

- 已选：以主方案 v5.1 为唯一设计源；W0 只提交契约与影响面，不编码；新增表/列均采用加法；发布采用“范围行锁 + 带归属写入栅栏 + 持久任务 + 指针 CAS + 切换事务登记 head”，不引入第二套发布模型；失败重做必须重新停写、重新捕获完整 S、清空目标后重复制；租约在取得锁后按 `clock_timestamp()` 复核并续租，过期由维护回收，`statement_timeout` 仅限制单条语句；结构发布按 FORM 权威 ∪ 目标实例建立受影响 head 集合并维护 I-R03，A∪B 只作 ID 筛选、切换与消费者都直接锁定 form_change_heads 基表行并按 id 升序，结果公开对两种权威都比较类型与修订、并同事务结算 head；普通字段发布锁定范围绑定；空标签不发布；历史缺值用 `legacy_incomplete` 豁免约束，不使用 `NOT VALID`；COMMENT 与 FORM 共用按具体原因 ID 计数的合规计数器；已确认消费/导出不缩减；初装按空平台/旧表 S/既有 RECORDS 三路径分离，Flyway 职责差异按 §8.8 批准边界执行；测试容器以本轮创建 ID 为所有权，禁止按名称预删除。
- 已否决：沿用旧副本直接切指针；旧任务/过期租约撤新栅栏；仅阶段 CAS 而副作用不校验 fencing；只在事务入口校验租约或用 `current_timestamp` 判断有效期；把 `statement_timeout` 当作事务累计超时；结构发布只按目标实例登记 head，遗漏已登记 FORM 但无实例的资源；对 WITH 结果加 FOR UPDATE 代替基表行锁；FORM 分支省略权威类型/修订比较；先复核版本、后在另一事务公开旧结果或仅结算较小版本；`CHECK ... NOT VALID` 约束历史缺值行；按一级大类数量判定原因唯一性；字段发布换来源/移除历史项目；以 NULL/空标签发布；COMMENT 保留空格/`&` 原因漏报；以“只做采集”缩减既有导出；表单测试清理 15433 共享 schema 或占用 18080 做写验收；启动前按固定名称删除测试容器；以旧 Demo 行为缩减本期能力；在 W0 直接建表/改权限；用无记录时回退评论的方式掩盖权威缺失；用 `new_*`/双写兼容旧端点；把 MR 记录级展示继续塞进 MR 事实。
- 当前审核裁定：§8.14为当前结论，R01至R09在设计层全部关闭，W0通过、W1已准入。员工先归并获准主方案契约再实施W1，后续逐阶段审核；此前被否决的提案不构成有效契约。
- 待定：MR级汇总公式（W5前补充影响说明后确认）；E1至E6及真实发布标签证据；W1及后续阶段的实际实现与验收。

## 接口契约

- 记录端点、管理端点、错误码、字段 JSON 与物理列以本文件第 2—4 节和主方案 §3.2—§3.3 为准；W0 不新增业务 API。
- 新表名与列名在本文件中固定；审核通过后写回主方案第三章，后续实施不得另立字段名。
- 既有旧端点 `/save`、`/delete`、`/update-record` 在 W2/W4 删除，不保留兼容别名；旧前端编辑器在 W3/W4 删除。
- 发布/权威/head 的 bigint ID 在 JSON 中使用十进制字符串；前端保持字符串，不转 Number。

## 风险与假设

- 本地 S 只有 1 条作废行，不能代表内网；W1 的迁移守恒必须在隔离库构造混合样本，E2 前不声称生产存量已验证。
- 发布 DDL 是运行期结构变更，必须真实 PG 受控验证；单元替身不足以证明锁、租约、重启和失败恢复。
- 动态版本表的数据库查看登记与黄金端点登记是两套机制，均需在对应工作单元完成；缺失会使默认套件或 W7 门禁失败。
- `issue_fact` 新增列会改变事实字段契约和导出映射；必须同步契约文件、Flyway、复制/写入、BI 适配器、导出与黄金登记，不能只改一处。
- 当前 18080 主树后端与 18181 正式候选栈均在运行；本轮未启停。W1 起若需释放资源，先记录入口与版本，结束后恢复同一入口并验证健康。
- 本文件不构成发布授权；快照/夹具变更、内网部署和写所有权切换仍按 AGENTS 与用户授权执行。
