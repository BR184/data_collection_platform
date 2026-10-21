# 统一表单系统建设方案（v5.1 评审定稿）

> 版本：v5.1，2026-10-21。业务基线包含用户确认的并发、危险操作及草稿体验；技术方案提交群内评审，评审通过后进入开发。读者：研发主管、测试负责人及实施人员。
> 依据：2026-09-29会议纪要、用户后续裁定、新老平台源码与Wiki规则。当前阶段见[项目进度](../progress.md)，调查依据见附录A。

## 一、执行摘要与架构评审拍板清单

统一采集MR代码走查、Issue响应和Issue回复，通过按版本建立真实物理表、管理员字段发布及统一消费入口，为走查和Issue事实提供结构化输入。

表单服务负责记录与版本。消费方结合GitLab资源、标签和事件生成事实、规则结果、统计与导出。CI_ROBOT在新建MR/Issue时自动投放入口链接，用户在采集平台填写，管理员在独立页面管理字段和适用范围。

本稿提交以下唯一推荐方案，评审确认后按第四章实施。

| 评审事项 | 本稿采用的方案 | 定义位置 |
| --- | --- | --- |
| 表单实现 | 复用Vue 3、TypeScript和Element Plus，自研白名单控件渲染与字段管理。真实分表、LDAP人员及事实列映射由平台统一维护 | §3.2、§3.4 |
| 存储身份 | 表名固定为`form_s{scope_id}_v{version_id}_instances`和`form_s{scope_id}_v{version_id}_records`，发布标签用于展示 | §3.1 |
| 字段发布 | Flyway安装基础能力；后续由管理员发起持久发布任务，服务端生成DDL、迁移并校验后切换指针 | §3.4 |
| 变更交接 | 记录、审计和`form_change_heads`同事务提交，消费者固定输入后完整发布 | §3.5 |
| 新老来源切换 | 按时间点及指定项目批次划分，存量Issue保持COMMENT，新资源显式赋权FORM；本期不批量导入历史GitLab评论 | §3.5 |
| 原因采集与合规 | 执行已确认的[D-30](../decisions.md#d-30-缺陷原因采集与合规判定分层) | §2.3 |
| 上线验收 | 三模板采集、管理员字段发布、实际消费和恢复验收齐备后切换业务入口 | 第四章 |

自研选择基于现有平台的真实列、身份、权限、事实与数据库查看契约。复用现有组件可直接保持这些边界；通用表单产品须逐项适配相同契约，本期不引入新的表单平台依赖。

本期交付三模板、字段管理、首次启用、记录维护、版本迁移、旧链接连续性和必要消费适配。后续独立范围包括自由拖拽布局、逐版本比较页面、新表单导出、服务端草稿、GitLab账号映射、正文回写、代码自查及老平台整体下线。

## 二、核心领域模型与业务规则基线

### 2.1 身份与权限模型

| 概念 | 业务定义 |
| --- | --- |
| 完整资源上下文C | `gitlabBaseUrl/projectId/resourceType/resourceId/templateKey`；resourceId为项目内MR或Issue的IID |
| 表单实例 | 某范围内C对应的一组填写记录；同一Issue的响应和回复是两个实例 |
| 填写记录 | 一次独立填写，拥有稳定记录ID；同一账号、同一走查人可多次填写 |
| 创建人 | 服务端会话username，即LDAP user_id，写入created_by |
| 走查人 | code_review的业务人员字段，与创建账号分别保存 |
| 管理员 | 维护模板、字段、发布、绑定和退役；管理能力不改变记录归属 |

每条记录只有创建人可以编辑或作废，且须拥有对应操作权限。代填时仍由实际提交账号维护，管理员沿用同一归属判定。无创建账号的历史记录以及停用账号的记录保持只读。

填写页复用平台同源登录、session和CSRF。未登录先登录再回到原资源及模板；会话失效按§3.6恢复草稿。GitLab账号识别为后续可选能力，调查见附录A.4。

### 2.2 版本与物理分表模型

| 身份 | 用途 | 演进规则 |
| --- | --- | --- |
| template_key | 跨版本模板身份 | 使用稳定英文键 |
| scope_id | 来源、项目集合、资源类型及产品版本体系的适用范围 | 各范围有独立当前版本 |
| 结构版本ID | 物理结构和路由身份 | 字段变化时创建新实例表及记录表 |
| version_label | 本次字段变更随项目发布的标签 | 人工选定项目里程碑，使用平台惯用缩写 |
| origin_version_id/template_version | 记录首次产生时的结构版本及发布标签 | 迁移后保留原值；历史无发布证据时标签为NULL |
| storageVersionId | 记录当前所在的结构版本 | 由范围current_version_id解析 |
| recordVersion | 记录编辑和作废的乐观锁序号 | 初始1，成功变更加1 |

同模板跨版本迁移该范围全部历史记录，保留旧物理表只读。业务只读取当前版本，当前表包含迁移后的历史记录。不同模板分别迁移；一个范围始终只有一个写入版本。

旧链接继续按C访问原记录：等价改名保留原值，新增字段在历史记录中为空，删除字段从当前结构移除。记录ID、创建账号、创建时间和产生版本保持不变。历史有效记录继续按业务时间范围参与计算。

版本次序由当前指针、稳定迁移链及发布时间表达。字段未变更的产品发布沿用原表单版本。过渡期标实际承载发布的版本；SP和组合标签保留发布原文，例如`CC2026R4SP1`、`CC2027R1 & 2027R2`。标签校验见§3.4。

“镜像库”指采集平台自有库。历史表永久留存并登记数据库查看；灾难恢复沿用[整库备份恢复runbook](../../deploy/runbooks/database-backup-restore.md)。

### 2.3 三模板业务规则与校验边界

| 模板键 | 资源类型 | 业务用途与消费边界 |
| --- | --- | --- |
| code_review | merge_request | 一次走查一条记录，非法数据页按记录展示；MR事实保持MR粒度。走查日期取记录创建时间 |
| issue_response | issue | 问题调研、计划解决日期与计划合入版本；一议题可有多条响应 |
| issue_reply | issue | 缺陷修改、原因与影响信息；覆盖系统测试实际使用的第1至9项，一议题可有多条回复 |

新建和编辑按发布规格校验有效业务字段，补充说明选填。条件不适用时保存明确结构值或NULL，条件说明随控制项启用；历史缺值在迁移时保留，用户编辑时提示补齐当前必填字段。完整字段、条件和取值见§3.2。

缺陷原因执行[D-30](../decisions.md#d-30-缺陷原因采集与合规判定分层)：采集保存真实原因集合，合规按Wiki的适用范围和状态检查唯一性，统计使用既有原因个数口径。实现分别承担字段校验、唯一性判定和统计投影，使用稳定具体原因ID。产出影响与验收见§4.1。

多条响应或回复的“最新”按`(createdAt, recordId)`降序确定，使用被选记录的当前内容；修改旧记录不改变排序。首次响应、延期豁免、完成时间等分别使用§3.5的指标选择器。

记录作废按删除语义处理：先确认后果，再输入“作废”；服务端核验确认字段、归属及记录版本。作废后从全部业务筛选、聚合、导出、首次/最新选择及效率计算中排除，并登记失效和重算。后台保留审计和只读历史，作废无自行恢复入口。

MR业务仍校验是否需要走查、是否缺记录和其它必需输入；缺记录的MR须能进入相应异常结果。Issue的模块、级别、状态和完成事件继续取GitLab。结构化表单的字段验证与下游业务合规各自执行。

### 2.4 填写流程与操作保护

用户从MR/Issue链接进入，查看已保存记录，再新建自己的填写块。代码走查默认选择本人，Issue按会话记录填写账号。新建只建立本地草稿，正式保存后才对其他使用者可见并进入业务消费。MR走查、Issue响应和回复共用以下设计。

| 设计规则 | 用户能观察到的行为 | 详细契约 |
| --- | --- | --- |
| 每次只处理目标记录 | 多人同时新建、填写、保存、编辑或作废各自记录均互不覆盖；旧页面中别人为空或不存在，不会被随本人保存写回 | §3.3 |
| 页面按需加载 | 进入页面、主动刷新列表及本页保存后获取已保存记录；他人操作不实时刷新当前页面 | §3.6 |
| 填写过程保持稳定 | 列表更新保留所有草稿、正在使用的输入框、选择项及位置，失败仍保留现有内容 | §3.6 |
| 冲突限定到目标 | 同一条记录在另一处修改或作废时核对后再保存；其他独立记录变化不触发冲突 | §3.3 |
| 草稿保存状态可见 | 各填写块显示自动保存中、已保存至本机或失败，正式提交后显示记录已保存 | §3.6 |
| 危险操作先确认 | 数据丢弃、覆盖、作废和管理变更先说明影响，用户确认后执行；日常输入修正直接完成 | §3.6 |
| 离开保护未提交内容 | 关闭、刷新或跳转前提醒未正式提交；本机草稿已保存也仍提醒，允许保留草稿后离开 | §3.6 |

### 2.5 原始需求与裁定索引

以下编号保留原需求的可追溯性，验收依据为所指章节。会议与用户后续裁定的差异见附录A.5。

| 原需求 | 对应行为 | 定义位置 |
| --- | --- | --- |
| FR-A1 | 竖版单列 | §3.6 |
| FR-A2 | 有效字段必填及条件要求 | §2.3、§3.2 |
| FR-A3 | 备注统一为补充说明 | §3.2、§3.4 |
| FR-A4 | 保存后只读、显式编辑 | §3.6 |
| FR-A5 | 记录创建人编辑 | §2.1 |
| FR-A6 | 创建/更新时间及后台审计 | §3.1、§3.6 |
| FR-A7 | 规范覆盖范围与本期模板 | §2.3、第五章E3 |
| FR-A8 | 正名人员下拉和服务端校验 | §3.2 |
| FR-B1 | 加号独立记录、接力填写 | §2.1、§3.6 |
| FR-B2 | 并发和长时停留 | §3.3场景表 |
| FR-B3 | 跳转对应MR | §3.6 |
| FR-B4 | webhook同步评论 | 第一章后续范围 |
| FR-B5 | 按记录增量提交 | §3.3 |
| FR-B6 | 乐观锁冲突提示 | §3.3、§3.6 |
| FR-B7 | 新增幂等 | §3.3六步事务 |
| FR-B8 | 页面无占用/预约 | §3.3场景S4 |
| FR-B9 | 保存失败保留输入 | §3.6 |
| FR-B10 | 本地草稿恢复和登出清空 | §3.6 |
| FR-B11 | 未保存离开确认 | §3.6 |
| FR-B12 | 同走查人二次确认 | §3.3六步事务 |
| FR-B13 | 交叉新增和保存后重载 | §3.3场景S1、§3.6 |
| FR-B14 | 本人作废与全业务排除 | §2.3、§3.3 |
| FR-C1 | 管理员字段管理和退役 | §3.4 |
| FR-C2 | 字段变化新建真实表 | §2.2、§3.4 |
| FR-C3 | 历史留存、项目版本标签 | §2.2、§3.4 |
| FR-C4 | 变更风险、消费影响和门禁 | §3.4、§3.7 |
| FR-D1 | 同源共享登录 | §2.1、第五章E4 |
| FR-D2 | 可选GitLab身份及允许代填 | §2.1、附录A.4 |
| FR-E1 | 群内评审后开发 | 第一章、§4.2 |
| FR-E2 | 老平台逐步退出 | 附录A.3、第五章E6 |
| FR-E3 | 身份及表单实现选型 | 第一章、附录A.4 |

| 原裁定 | 当前效力与定义位置 |
| --- | --- |
| D1 | 审计保留、用户无编辑历史，§3.1、§3.6 |
| D2 | 原分两份文档已撤销，本文件合并需求、设计及实施路线 |
| D3 | 旧列保留、新列规范命名，§3.2、§3.4 |
| D4 | 平台身份与权威人员姓名，§2.1、§3.2 |
| D5 | 最新范围覆盖三模板，§2.3 |
| D6 | 评论正文回写留待后续，第一章 |
| D7 | 自有库留存及整库恢复，§2.2 |
| D8 | 记录按创建人维护，§2.1 |
| D9 | 逐记录并发控制，§3.3 |
| D10 | 创建人按每条记录判定，§2.1 |
| D11 | 本地草稿生命周期，§3.6 |
| D12 | 代码自查保持现状，第一章 |
| D13 | 同模板全历史迁移，§2.2、§3.4 |
| D14 | 记录粒度与走查创建日期，§2.3、§3.5 |
| D15 | 稳定人员ID及姓名快照，§3.2 |
| D16 | 作废与停用账号，§2.1、§2.3 |
| D17 | Issue结构化业务源，§2.3、§3.5 |
| D18 | 原因分层，§2.3及D-30 |

| 用户补充项 | 处理位置与状态 |
| --- | --- |
| B1 | 已确认，§2.3及D-30；调查见附录A.1 |
| B2 | 已确认，§2.3、§3.5；调查见附录A.1 |
| B3 | 已确认常规本人填写/编辑，确认内容契约见§3.2 |
| B4 | 已确认旧链接和原记录连续性，§2.2、§3.4 |
| B5 | 已确认作废删除语义，§2.3 |
| B6 | 已确认走查创建日期，§2.3 |
| B7 | 已确认管理员页面字段增删改和退役，§3.4 |
| B8 | RS未知范围暂缓，接入该范围时补证，第五章E3 |
| B9 | 当前无同名不同人业务场景，沿§3.2稳定ID实现 |
| B10 | 已确认管理员管理，§2.1、§3.7 |
| B11 | 已批准条件必填及现行日期/分支值域，§3.2 |

## 三、系统实现设计

### 3.1 存储层设计

#### 元数据、范围与当前指针

```sql
create table form_template_definitions (
    template_key varchar(64) primary key,
    display_name varchar(128) not null,
    status varchar(16) not null check (status in ('ACTIVE', 'DISABLED')),
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create table form_template_scopes (
    id bigserial primary key,
    template_key varchar(64) not null references form_template_definitions(template_key),
    scope_key varchar(64) not null,
    source_config_id bigint,
    source_instance varchar(128),
    canonical_gitlab_base_url varchar(255),
    project_ids jsonb not null default '[]'::jsonb check (jsonb_typeof(project_ids) = 'array'),
    version_project_id bigint check (version_project_id > 0),
    resource_type varchar(64) not null,
    current_version_id bigint,
    status varchar(16) not null check (status in ('DRAFT', 'ACTIVE', 'RETIRED')),
    unique (template_key, scope_key),
    check (status <> 'ACTIVE' or
        (source_config_id is not null and source_instance is not null
         and canonical_gitlab_base_url is not null and version_project_id is not null
         and jsonb_array_length(project_ids) > 0 and current_version_id is not null))
);

create table form_template_versions (
    id bigserial primary key,
    scope_id bigint not null references form_template_scopes(id),
    version_label varchar(64),
    storage_kind varchar(16) not null check (storage_kind in ('LEGACY', 'RECORDS')),
    instance_table varchar(63) not null,
    record_table varchar(63),
    field_spec jsonb not null,
    migration_spec jsonb,
    version_status varchar(16) not null check (version_status in ('INSTALLED', 'PUBLISHED', 'RETIRED')),
    published_at timestamp,
    unique (scope_id, id),
    check ((storage_kind = 'LEGACY' and record_table is null)
        or (storage_kind = 'RECORDS' and record_table is not null)),
    check (storage_kind = 'LEGACY' or version_status <> 'PUBLISHED'
        or (version_label is not null and published_at is not null))
);
alter table form_template_scopes add constraint form_scope_current_version_fk
    foreign key (id, current_version_id) references form_template_versions(scope_id, id);

-- 所有新版记录表共用；版本迁移保留记录 ID，不各自创建 bigserial。
create sequence form_record_id_seq as bigint;
```

范围验证器同时用于启用事务、启动和来源配置变更：

1. ACTIVE的项目集合非空、无重复、均为正整数；同一`(template_key, source_config_id, resource_type, project_id)`只命中一个ACTIVE范围。
2. 当前指针指向本范围PUBLISHED的RECORDS版本；复合外键保证版本归属，验证器保证发布状态。
3. version_project_id属于project_ids，范围内项目共享已确认的产品版本体系；不同体系分别登记。
4. DRAFT允许来源和项目未绑定，空集合不匹配填写请求；空平台可启动。非法ACTIVE、范围重叠或错误指针返回具体配置错误并拒绝启用或启动。
5. source_config_id为逻辑稳定关联。ACTIVE校验配置存在、规范URL及实例一致；来源改址或删除须先处理引用范围。RETIRED保留来源快照，历史查询和消费可独立于已删除配置读取。
6. source_instance取`GitlabSourceInstanceSupport.sourceInstanceOf(已保存配置)`，当前为`default`。若同一事实实例对应多个配置且无法唯一选择，拒绝登记并报告。

初始登记三模板及核实的范围：Issue回复覆盖已登记系统测试/客户问题项目，响应先覆盖已核实客户问题项目。来源能力沿现有镜像模型，未知RS/DGM版本依据按E3处理。

物理表名固定为`form_s{scope_id}_v{version_id}_instances`和`form_s{scope_id}_v{version_id}_records`。所有物理标识符由服务端分配，限ASCII、唯一且不超过63字节；业务值参数化。下列占位符在执行前替换为经过验证的元数据名称。

#### 实例、记录和稳定ID

```sql
create table <instances> (
    id bigserial primary key,
    scope_id bigint not null references form_template_scopes(id),
    source_config_id bigint not null,
    source_instance varchar(128) not null,
    gitlab_base_url varchar(255) not null,
    project_id bigint not null check (project_id > 0),
    resource_type varchar(64) not null,
    resource_id bigint not null check (resource_id > 0),  -- MR/Issue各自项目内IID，不是GitLab全局id
    template_key varchar(64) not null references form_template_definitions(template_key),
    created_at timestamp not null,
    updated_at timestamp not null,
    unique (scope_id, source_config_id, project_id, resource_type, resource_id)
);

-- 以下记录业务列仅为code_review示例；Issue两表同系统列，业务列按§3.2分别建真实列。
create table <records> (
    id bigint primary key default nextval('form_record_id_seq'),
    instance_id bigint not null references <instances>(id),
    origin_version_id bigint not null references form_template_versions(id),
    template_version varchar(64),               -- 原始发布标签；未登记历史可为空
    created_by varchar(128),                    -- 会话 username，即 LDAP user_id
    reviewer_user_id varchar(128),
    reviewer_name varchar(128),                 -- 历史原值允许 NULL，不伪造正名
    review_duration_minutes integer not null check (review_duration_minutes >= 0),
    specification_count integer not null check (specification_count >= 0),
    logic_count integer not null check (logic_count >= 0),
    performance_count integer not null check (performance_count >= 0),
    design_count integer not null check (design_count >= 0),
    other_count integer not null check (other_count >= 0),
    supplement_note text,
    request_key varchar(64),
    create_payload_hash char(64),               -- 首次规范化提交的 SHA-256，不随编辑变化
    record_version integer not null default 1 check (record_version > 0),
    deleted boolean not null default false,
    created_at timestamp not null,
    updated_at timestamp not null,
    check (created_by is null or
        (reviewer_user_id is not null and reviewer_name is not null
         and btrim(reviewer_name) <> '' and request_key is not null
         and create_payload_hash is not null and template_version is not null)),
    unique (request_key)
);
-- PostgreSQL 普通唯一约束允许多个 NULL，覆盖无请求标识的历史行。
create index <已校验的实例索引名> on <records>(instance_id) where deleted = false;
```

三模板共用系统列，业务列分别按§3.2建真实列。上例的reviewer约束仅适用于code_review；Issue使用各自字段条件，不补造走查字段。以后新增字段的历史可空约束由发布规格生成。

全局记录ID由`form_record_id_seq`分配，迁移显式保留旧ID；序列推进至旧表全量及新版最大ID之上，空库从1开始。实例ID可重建。origin、产生标签、requestKey、首次指纹和recordVersion迁移保持原值。

记录寻址使用`(scopeId, storageVersionId, C, id)`，先核验范围当前版本再解析表。陈旧存储版本返回409/A0409，由用户核对后重新建立草稿。记录版本上限为2147483647，达到上限时拒绝继续变更。

#### 审计与迁移日志

```sql
create table form_record_audit_logs (
    id bigserial primary key,
    record_id bigint not null,
    template_key varchar(64) not null,
    scope_id bigint not null references form_template_scopes(id),
    storage_version_id bigint not null references form_template_versions(id),
    record_version integer not null,
    operation varchar(16) not null check (operation in ('CREATE', 'UPDATE', 'VOID')),
    snapshot_json jsonb not null,
    operator_id varchar(128) not null,
    operator_name varchar(128),
    client_ip varchar(64),
    user_agent varchar(512),
    created_at timestamp not null,
    unique (record_id, record_version, operation)
);

create table form_template_migration_logs (
    id bigserial primary key,
    scope_id bigint not null references form_template_scopes(id),
    from_version_id bigint not null references form_template_versions(id),
    to_version_id bigint not null references form_template_versions(id),
    migrated_rows bigint not null,
    verification_summary jsonb not null,
    executed_at timestamp not null,
    unique (scope_id, from_version_id, to_version_id)
);
```

每次成功操作写入操作后的记录全量快照、完整C及实例来源快照、操作账号、IP和UA。快照按recordId/recordVersion定位，字段前后值可由相邻快照比对；用户页面只展示创建/更新时间。创建人姓名使用CREATE审计的operator_name快照，历史无归属保留未知。旧审计保持只读，迁移沿用原事件证据。

迁移日志记录前后版本及守恒核验。空模板首次发布没有from_version，使用§3.4发布任务记录；已有结构之间迁移才写form_template_migration_logs。审计失败向外传播并回滚业务事务。

#### 启动与结构校验

启动只检查本地元数据：spec版本、范围及指针、迁移链、public schema表存在、真实列类型/长度、系统列、标识符、人员双列及数值边界。错误定位到模板、范围、结构版本及字段；拒绝带漂移结构启动。里程碑外部证据在发布期核验并存档，空ODS不阻断已验证元数据启动。

### 3.2 三模板字段规格与条件契约

field_spec是校验和渲染的唯一字段定义，`spec_version=1`冻结编码规则。稳定snake_case键与API camelCase键使用显式绑定，中文label只用于显示。规格分别声明控件、物理列、类型、长度、条件和词典版本。

#### code_review

```json
{
  "spec_version": 1,
  "fields": [
    {"name":"reviewer_user_id","label":"走查人","type":"string","required":true,"maxLength":128,"control":"person-select","optionsSource":"platform_ldap_users","storage":{"idColumn":"reviewer_user_id","nameColumn":"reviewer_name","columnType":"varchar"}},
    {"name":"review_duration_minutes","label":"走查时间（分钟）","type":"integer","required":true,"min":0,"max":2147483647,"control":"number"},
    {"name":"specification_count","label":"规范","type":"integer","required":true,"min":0,"max":2147483647,"control":"number"},
    {"name":"logic_count","label":"逻辑","type":"integer","required":true,"min":0,"max":2147483647,"control":"number"},
    {"name":"performance_count","label":"性能","type":"integer","required":true,"min":0,"max":2147483647,"control":"number"},
    {"name":"design_count","label":"设计","type":"integer","required":true,"min":0,"max":2147483647,"control":"number"},
    {"name":"other_count","label":"其他","type":"integer","required":true,"min":0,"max":2147483647,"control":"number"},
    {"name":"supplement_note","label":"补充说明","type":"string","required":false,"maxLength":8192,"control":"textarea"}
  ]
}
```

API输入为`reviewerUserId/reviewDurationMinutes/specificationCount/logicCount/performanceCount/designCount/otherCount/supplementNote`，与上表逐项绑定。五项count表示问题数量，时长单位为分钟。

走查人使用可搜索人员下拉，默认本人，可改选他人。候选来自platform_ldap_users，服务端验证user_id存在且real_name非空，保存该次权威姓名快照。客户端只提交ID，reviewerName由服务端生成。表中仍保留且正名有效的离职人员可选；表外人员按缺候选报告。会话username用于创建账号及本人预填。

#### issue_response

| API键（物理列） | 类型/控件 | 必填/边界 | 消费 |
| --- | --- | --- | --- |
| problemType（problem_type） | enum: DEFECT/REQUIREMENT，单选 | 必填且仅一项；与GitLab议题类别及需求身份标签独立 | 模板完整性 |
| problemCause（problem_cause） | 多行文本 | 必填、去空白后非空 | 展示/追溯，修复原因分类独立保存 |
| modificationPlan（modification_plan） | 多行文本 | 必填 | 调研修改方案 |
| ownerConfirmation（owner_confirmation） | 确认内容文本 | 当前资源一级缺陷时必填；其它级别可保存NULL表示不适用 | 原Wiki/现行一级确认规则；按普通可编辑确认内容保存 |
| plannedResolutionDate（planned_resolution_date） | SQL date/日期控件 | 新提交必填、唯一完整合法日期；JSON YYYY-MM-DD，现行解析年份2020—2040，扩大值域须另确认；历史文本走原格式解析 | 最新计划/延期，完成时间取GitLab标签事件 |
| plannedMergeVersionBranch（planned_merge_version_branch） | 版本成员输入/受控选择 | 必填；沿现行IssueResponsePlanFieldRules严格规则，一个或多个CCyyyyRn以&表示；历史原值保留，合法性使用严格解析。此为计划分支业务值，不是表单结构的发布标签 | 最新计划分支/筛选/导出 |
| supplementNote（supplement_note） | 多行文本 | 选填 | 补充说明 |

负责人确认保存填写内容及会话创建账号，适用条件使用提交时核验的资源快照。实际流程由本人填写和编辑；本期保持普通确认内容，不增加另一账号电子签字流程。资源后变一级而确认缺失时报告缺失。

#### issue_reply

| API键（物理列） | 类型/控件 | 必填/条件 |
| --- | --- | --- |
| fixStatus（fix_status） | 单选 RESOLVED/PARTIAL/DEFERRED/UNREPRODUCIBLE | 对应已解决/部分解决/申请延期/无法复现，必选；GitLab状态沿来源标签读取 |
| causeKeys（cause_keys） | text[]，稳定原因词典ID，多选控件 | 新提交非空、去重且命中冻结词典；历史空值保留。采集、合规、统计分层见§2.3。 |
| specificReason（specific_reason） | 多行文本 | 回复具体原因，必填 |
| modificationPlan（modification_plan） | 多行文本 | 必填 |
| causedByOtherDefect（caused_by_other_defect） | boolean单选 | true/false必须显式填写；关联缺陷号/链接在说明中选填 |
| affectedKinds（affected_kinds） | text[]：KNOWN/NEW/NONE | 必选；NONE与其它互斥，KNOWN/NEW可同时选 |
| knownAffectedFunctions（known_affected_functions） | 多行文本 | KNOWN选中时必填；未选时清空该字段 |
| newAffectedFunctions（new_affected_functions） | 多行文本 | NEW选中时必填 |
| impactTestStatus（impact_test_status） | YES/NO/NOT_APPLICABLE | 必选，以选择值保存答案 |
| hasResidualImpact（has_residual_impact） | boolean单选 | 必选 |
| residualImpactDetail（residual_impact_detail） | 多行文本 | hasResidualImpact=true必填 |
| relationTableUpdated（relation_table_updated） | YES/NO/NOT_APPLICABLE | 第9项必选；历史8项模板缺此项为未知 |
| supplementNote（supplement_note） | 多行文本 | 选填 |

原因词典复用中立DefectCauseMetricCatalog的稳定key及历史等价映射，结构版本冻结词典内容和版本。一级大类由具体原因派生。模板969“需求理解偏差”等历史显示文字与正名的映射逐项留证，“编译打包问题”等跨大类差异保留原始映射证据。

原因集合上限取安装词典项数；枚举集合去重并验证边界。KNOWN和NEW可同时选择，NONE与二者互斥。控制项关闭时前端清理不再适用的草稿值，服务端拒绝矛盾载荷。历史8项回复缺第9项为未知。

#### 共用输入边界和JSON示例

多行说明按8192个Unicode码点校验，人员ID/姓名128码点；请求体按UTF-8计128KiB，超限返回413。整数仅接受0至2147483647的JSON整数，拒绝小数、字符串、负数、溢出或空必填值。未知字段及创建账号、姓名快照、deleted、来源版本等只读字段返回400。以上长度为本稿技术预算，随发布规格维护并在目标样本上核验。

文本保留原文，允许普通链接和Markdown文字，读取时安全呈现；本期字段控件覆盖人员、整数、日期、文本、单选、多选及布尔。附件上传、新控件和新业务计算作为能力开发。适用业务字段的空白检查使用去空白判空，保存值及指纹仍按§3.3编码。

响应请求示例：

```json
{"gitlabBaseUrl":"https://gitlab.example.com","projectId":"325","resourceType":"issue","resourceId":"12","templateKey":"issue_response","scopeId":"2","storageVersionId":"4","requestKey":"550e8400-e29b-41d4-a716-446655440000","fields":{"problemType":"DEFECT","problemCause":"已确认原因","modificationPlan":"按方案修改","ownerConfirmation":null,"plannedResolutionDate":"2026-11-01","plannedMergeVersionBranch":"CC2026R4","supplementNote":null}}
```

示例ownerConfirmation为NULL仅在当前资源非一级时有效。三模板分别安装完整field_spec，字段表中的SQL date、text[]、boolean和枚举映射直接落实到真实列。原始记录内容存真实列，JSON用于规格、映射及审计。

### 3.3 服务端接口、幂等与并发控制

#### 记录端点与上下文

接口基址为`/api/collect-forms`。按记录增量提交，每次仅处理当前记录。写请求不携带整页记录快照，其他记录的缺失、空值及旧值不构成删除或覆盖指令；作废只作用于明确指定的记录ID。不同记录的变化不改变本条recordVersion，也不以整页更新时间拒绝保存；结构升级仍按storageVersionId核验。删除内部`/save`、`/delete`、`/update-record`及全部调用者；已发布MR外链按§3.6保留。新记录及管理协议的bigint ID在JSON中使用十进制字符串，前端保持字符串；notification-payload沿既有外部响应契约。

| 方法/路径 | 权限 | 请求与响应 |
| --- | --- | --- |
| GET `/detail` | form.record.create | 参数C；返回模板/field_spec、scopeId/storageVersionId/versionLabel、可空实例ID、按created_at/id排序的全部未作废记录、资源链接、会话账号及本人操作能力。GET仅读取 |
| POST `/records` | form.record.create | C、scopeId/storageVersionId/requestKey/confirmSameReviewer/fields；返回`{outcome:'CREATED'或'REPLAYED',record}`。需确认时返回HTTP200及`{outcome:'CONFIRM_REQUIRED',reviewerUserId}`，本次无记录写入 |
| PUT `/records/{id}` | form.record.edit_own | C、scopeId/storageVersionId/recordVersion/fields；返回成功后的权威记录 |
| POST `/records/{id}/void` | form.record.delete_own | C、scopeId/storageVersionId/recordVersion、confirmVoid=true、confirmationText='作废'；返回作废后的权威状态 |
| GET `/reviewer-candidates` | form.record.create | keyword至多128码点，limit默认20、最大100，按稳定userId游标；返回items(userId/realName/deptName)、nextCursor及独立currentUserCandidate，本人候选独立于分页 |
| GET `/notification-payload` | form.record.create | 保留sourceAddress、projectId、requestIid、resourceType原四字段及类型，校验来源、项目和资源；真实外部调用迁移见第五章E1 |

fields按发布规格映射，协议字段位于fields之外。返回统一ApiResponse：参数/字段/目标不存在或已删除为400并说明原因，无权或归属不符为403/A0303，存储版本或记录版本冲突为409/A0409，会话失效为401，请求体超限为413。CONFIRM_REQUIRED是二次确认状态，只有CREATED/REPLAYED构成新增保存成功。

URL仅接受已保存来源配置的Web基址：允许http/https，规范scheme/host大小写、默认端口和末尾斜线，保留部署子路径，拒绝userinfo/query/fragment。服务端从配置取得configId/sourceInstance，与范围比对，验证项目集合及ODS中对应project+iid的MR/Issue，并解析GitLab全局根ID。请求URL只用于匹配配置，镜像核验使用本地数据。

资源未镜像时返回“资源待同步”，保留输入并允许重试；已有合法本地记录的detail读取独立于同步RUNNING/FAILED状态。来源改址通过配置变更及范围验证器处理。同数字项目/IID的不同来源、MR与Issue、响应与回复保持隔离。

走查新增请求示例：

```json
{
  "gitlabBaseUrl":"https://gitlab.example.com",
  "projectId":"9","resourceType":"merge_request","resourceId":"12",
  "templateKey":"code_review","scopeId":"1","storageVersionId":"2",
  "requestKey":"550e8400-e29b-41d4-a716-446655440000",
  "confirmSameReviewer":false,
  "fields":{
    "reviewerUserId":"employee-001","reviewDurationMinutes":30,
    "specificationCount":1,"logicCount":0,"performanceCount":0,
    "designCount":0,"otherCount":0,"supplementNote":null
  }
}
```

#### 新增幂等与同人提示的六步事务

1. 校验认证、权限、C、当前结构版本、字段、请求大小和规范UUID requestKey。客户端从首次草稿起保存该key。服务端key作用域为当前结构版本对应的记录表；同表跨实例或账号复用拒绝，不跨范围查询key。迁移复制原key，陈旧版本请求先返回版本冲突。
2. 以规范JSON编码后计算SHA-256首次指纹。内容包括范围、完整C、storageVersionId、会话账号和该模板全部可编辑字段，键序采用冻结规格顺序。ID及整数使用服务端解析后的十进制值，集合按语义排序去重，日期为规范YYYY-MM-DD，省略和NULL统一为NULL，空串保留；补充说明仅CRLF转LF，其余文本保持约定原文。编码UTF-8，哈希为小写十六进制。confirmSameReviewer、派生姓名和系统时间戳不入指纹；规范编码随spec_version=1固定。
3. 查找本版本相同key。账号、完整C及首次指纹全相符才返回REPLAYED及该记录当前状态；任一不同返回409/A0409“请求标识与本次操作不一致”，保护已有记录内容。已作废记录拒绝重放，已编辑记录返回当前值。重放沿用已存姓名；首次新增才查权威人员值。
4. 事务内按完整实例键执行`INSERT ... ON CONFLICT DO NOTHING`，随后按完整键读回并短暂锁定实例行；并发首次创建收敛为同一实例。
5. 锁内再次检查key。code_review按reviewerUserId检查既有有效记录；命中且未确认时返回CONFIRM_REQUIRED，回滚本事务及本次创建的空实例。用户确认后沿用key和载荷，仅改变confirmSameReviewer。Issue允许同作者多条，记录身份保持独立。
6. 按request_key唯一约束执行`ON CONFLICT DO NOTHING ... RETURNING`。实际插入者写一条CREATE审计；未插入者重读并执行第3步校验。实例、记录、审计和持久变更标记同事务提交，失败全部回滚。

事务仅覆盖本地提交步骤，锁不跨LDAP/网络调用或用户确认等待。序列允许空洞。审计服务向事务传播异常；同人确认期间无实例锁或页面占用。

#### 编辑、作废与CAS

下例为走查列更新；Issue按各自字段白名单生成业务赋值，三模板共用归属、deleted及record_version条件。

```sql
update <records>
   set reviewer_user_id = :reviewerUserId, reviewer_name = :resolvedReviewerName,
       review_duration_minutes = :minutes, specification_count = :specificationCount,
       logic_count = :logicCount, performance_count = :performanceCount,
       design_count = :designCount, other_count = :otherCount,
       supplement_note = :supplementNote, record_version = record_version + 1,
       updated_at = :now
 where id = :id and instance_id = :instanceId
   and created_by = :actor and deleted = false and record_version = :expected;

update <records>
   set deleted = true, record_version = record_version + 1, updated_at = :now
 where id = :id and instance_id = :instanceId
   and created_by = :actor and deleted = false and record_version = :expected;
```

编辑和作废先校验上下文/存储版本，再核验实例归属和本人身份，最后执行条件更新。影响一行时写UPDATE/VOID审计及变更标记后提交。影响零行时在同一上下文读取权威状态，分类返回无权、已作废或版本冲突。编辑保持origin/requestKey/首次指纹/created_at不变。作废重复请求在账号及上下文核验后可返回已作废状态，审计仍只有一次；未作废的陈旧版本返回冲突。未知字段拒绝仅应用于本表单schema。

#### 并发、失败与长时停留场景

| 场景 | 协议与验收 |
| --- | --- |
| S1 独立记录并行操作 | 空页面同时新建、几乎同时保存、旧页面后保存及新增/编辑/作废交错，只影响目标记录；实例完整键原子归并，记录独立，短事务锁内执行同人提示；列表加载保留其他草稿。 |
| S2 同账号两处编辑 | 相同 recordVersion 的 CAS 最多一次成功；失败 409/A0409，保留输入，不写成功审计。 |
| S3 双击/超时/重开重试 | 稳定 key/首次指纹、原子唯一冲突处理，只一条记录和 CREATE；异人/异上下文/改载荷复用拒绝。 |
| S4 页面久置/401/误关 | GET仅读取；本地草稿按账号/资源/编辑会话隔离且状态可见，未提交离开确认；401保留，同账号恢复；主动登出经确认后跨标签停写/清空；不可持久化如实提示。 |
| S5 作废与编辑交错 | 两者均条件写且递增版本；先提交者成功，后者冲突或已作废；已作废不能复活。 |
| S6 审计/网络/列表刷新失败 | 审计失败回滚；新增不确定响应同key核验，编辑显式核对；保存与reload结果分开；迟到响应不覆盖新状态、其他资源或草稿，页面取消不等于服务器未提交。 |
| S7 版本升级与旧草稿/页面 | 原记录身份保留，旧 storageVersionId 被拒绝；用户核对后建立当前版本草稿，不靠相同数字 ID 误写。 |

使用可控屏障和延迟响应验证交错。检查GET无占位/未结束事务、确认等待不持有事务，CAS失败无成功审计，避免依赖固定sleep或碰运气重跑。

### 3.4 管理员字段管理与受控版本发布机制

#### 页面、稳定字段与映射

独立“表单管理”页面按模板列表、字段草稿、范围绑定、预览、发布结果及退役组织。管理员编辑字段名称、说明、白名单类型/控件、顺序、必填/条件、枚举选项；从真实来源及项目范围选择产品发布标签。领导提出字段需求，由管理员操作，本期无需额外在线审批流。

稳定字段键在创建后不变，名称和说明可修改。发布预览展示字段差异、历史映射、转换错误及消费影响。受支持字段的增删改和排序由规格发布完成；消费者按稳定语义键映射真实列，删除后按缺失语义返回未知或业务缺项。类型变化必须声明可验证转换，失败值列入报告；同名不同义例如0至100改为0至5必须显式换算，消费方保留产生版本的语义边界。

未发布的模板草稿不影响填写页；删除配置、发布、变更范围及退役遵循§3.6确认规则。用户保存与发布/退役竞争时，以事务核验顺序给出明确结果：已提交记录保留，失效提交保留输入供核对或复制，不能部分写入；管理员草稿和发布版本冲突各自提示，不覆盖他人已提交配置。

migration_spec固定spec版本，包含前后结构版本ID、完整选集S上下文、字段映射、等价改名/类型转换/新增空值/删除列、身份缺失策略和校验清单。普通字段新增在历史行中为NULL；原始版本标签缺证据时保持NULL。记录内容按§2.2迁移。

#### 管理端接口

以下路径位于`/api/collect-forms`，均使用form.template.manage，校验管理员身份及CSRF。

| 方法/路径 | 契约 |
| --- | --- |
| GET `/templates` | 返回模板、范围、当前版本和发布状态 |
| GET `/template-scopes/{scopeId}` | 返回字段规格、来源快照、项目、版本和发布结果 |
| POST `/template-scopes` | 创建DRAFT范围，可未绑定来源；返回稳定scopeId |
| PUT `/template-scopes/{scopeId}/draft` | 携带expectedDraftRevision；保存字段规格、绑定及迁移映射草稿，CAS冲突返回409/A0409 |
| POST `/template-scopes/{scopeId}/preview` | 校验指定draftRevision，返回字段、DDL摘要、选集及消费影响和blockingErrors，不切换当前结构 |
| POST `/template-scopes/{scopeId}/publications` | 携带draftRevision、expectedCurrentVersionId（首次为NULL）、requestKey；冻结预览后的规格及证据，返回publicationId和状态。相同key同指纹返回同任务 |
| GET `/publications/{publicationId}` | 返回任务阶段、核验结果、错误及当前指针 |
| POST `/template-scopes/{scopeId}/retire` | 按expectedCurrentVersionId核验后退役，并返回历史访问说明 |
| DELETE `/template-scopes/{scopeId}` | 只删除未发布且无实例/记录的DRAFT范围 |

模板新建复用已登记语义类型及白名单控件，新增业务模板的专用条件与消费映射按开发单元登记。两个管理员编辑草稿或重复发布均有显式版本冲突。

#### 首次启用与发布状态机

Flyway安装元数据、审计、发布任务、变更交接、来源权威及初始三模板能力。空平台保留DRAFT/INSTALLED状态。管理员从种子模板建草稿、绑定来源及项目、预览并发布后，范围才进入ACTIVE。环境配置ID和产品标签由该环境登记，实例在首次成功提交时建立。

后续字段发布使用平台受控DDL执行器。基础表结构及执行器能力由Flyway升级；管理员生成的版本表记录在发布目录，不写flyway_schema_history，不修改已有迁移及checksum。应用以已验证元数据和实际列进行漂移核验。

持久任务表`form_template_publications`承担恢复和防重：保存publicationId、scopeId、requestKey、首次指纹、draftRevision、expected/from/toVersionId、冻结field_spec/migration_spec、版本证据、DDL摘要及哈希、S计数、逐字段核验、操作者、租约/fencing版本、阶段、错误及时间。唯一键为(scope_id, request_key)，相同key载荷冲突返回A0409；每范围最多一个未结束任务。阶段固定为PREPARED、COPYING、VERIFIED、PUBLISHED、FAILED，阶段推进以任务版本及租约CAS控制。

1. 锁定范围并核验预期当前版本，冻结草稿、来源证据、迁移映射及影响清单，分配目标版本ID和服务端物理名称，登记PREPARED任务。请求仅含字段定义和稳定ID。
2. 为受影响范围关闭记录写入，排空在途事务；所有写事务在锁内复核范围/发布状态。读取继续使用此前完整版本。发布任务持有短期可接管租约，执行DDL的数据库身份仅获所需schema权限。
3. 校验标识符、白名单类型及约束后创建新版本实例表和记录表，按冻结选集复制数据。先验证目标为空；恢复时核对任务ID、已生成结构/哈希及复制结果，确认一致后推进，漂移进入FAILED。
4. 逐项完成下述S守恒及消费等价核验，写verification_summary，任务进入VERIFIED。初始历史接入使用Flyway事务；本期后续版本采用受影响范围停写后的事务迁移，目标容量不足时先评审分批方案。
5. 在同一事务中CAS切换current_version_id，设置PUBLISHED/ACTIVE、写迁移日志及消费变更，任务进入PUBLISHED，再恢复写入。存在待处理旧head时保持同一资源的变更证据，禁止旧任务结算新版本。
6. 失败保持旧指针和旧完整读取，保留目标结构及诊断供核对后恢复；已发布任务重复调用只返回结果。未切指针的失败任务须经结构核验后重试或终止，不能覆盖未知目标数据。

启用和退役使用同一范围验证器。已产生实例的来源/资源归属保持稳定，跨来源或项目转移按独立迁移守恒验收。草稿可删除，使用过的模板/范围退役后拒绝新增/编辑，保留历史查询和有效记录消费。

#### 产品发布标签校验

发布时从version_project_id的GitLab里程碑人工选定，规范为项目惯用缩写，保存来源配置、项目、里程碑ID/原文、映射及核验时间。单版本须命中该项目；SP须有基础版本及实际SP发布依据；组合标签省略前缀时继承第一成分，例如`CC2027R1 & 2027R2`分解为`CC2027R1`、`CC2027R2`分别核验。里程碑均active时仍由维护者选择实际承载本次发布的版本。

ODS未就绪或无里程碑时补同步或提供人工可核查的发布依据，预览核验通过后发布。首次启动只按§3.1检查本地结构，已验证范围的启动与ODS到达解耦。

#### 初始旧表选集S与守恒迁移

发布前只读盘点collect_form_records全表，含deleted=true。检查模板/来源/项目/类型、NULL及空姓名、数值、完整键、两个时间、request_iid/resource_id。resource_id必须为正整数MR IID，非空request_iid与它相同；不一致、溢出或负数先报告并取得处置裁定。

S定义为`template_code='code_review'`，且命中已核验来源、已登记项目集合、`resource_type='merge_request'`的全部行，包含作废行。规范URL对应表随发布规格冻结，旧表保留原地址。所有走查历史必须被已登记范围覆盖，发现未覆盖行则停止上线核对。其他模板原样保留；若仍有活跃旧模板，先解决其写入口退役影响。GitLab历史评论的来源切换见§3.5。

1. 按发布runbook备份，停止旧后端写入并排空请求，确认无旧实例续写。新写尚未开放，旧前端进入维护态。
2. Flyway建立初始结构并在同一事务搬迁S。实例及记录使用完全相同的选集；实例join包含范围、来源、项目、类型、资源ID及模板。
3. 每个S行保留f.id并建立一条记录，实例按完整键归并。映射`reviewer→reviewer_name`、五项`*_score→*_count`、`remark→supplement_note`；保留原姓名（含NULL/空串）、数值、说明、deleted和两个时间。created_by/reviewer_user_id/request_key/create_payload_hash为NULL，origin指向初始LEGACY；form_title留在旧表。
4. NULL安全逐字段核验ID、归属、值及时间，验证`|S|=新记录数`、`distinct(S完整实例键)=新实例数`，S无遗漏，集合外无混入。实例时间取同键最早创建和最新更新。后续结构迁移另保留origin、key、指纹和记录版本，删除及转换按映射执行。
5. 守恒成功才写迁移日志并切当前指针；失败回滚。目标表非空或迁移链已成功时拒绝重复搬迁。共享序列按§3.1推进，允许空洞。
6. 验证后端健康、报告和新端点后切前端、开放写入，进行事实重建和预热。旧表及旧审计登记只读，旧写入口随之删除。

历史必填不回填假值，旧列保留原名和标签。数据库查看按版本区分“备注/补充说明”。既有重复行逐条迁移，去重属于单独数据治理。

新写开放前可停止新版应用并保留旧表恢复原入口；已成功迁移只做前向修复。新写开放后，恢复采用维护停写及前向修复；若需整库恢复，按runbook明确恢复点和新数据损失。

### 3.5 下游消费适配与权威来源切换

#### 持久变更与完整发布

form_change_heads按完整来源/项目/资源类型/IID/模板唯一登记，跨结构迁移沿用同一业务变更身份。保存下列状态：

| 字段组 | 契约 |
| --- | --- |
| 完整身份 | source_config_id、source_instance、规范基址、project_id、resource_type、resource_id、template_key、scope_id |
| 变更版本 | latest_change_version由数据库原子递增；consumed_change_version只推进至已发布输入；latest_audit_id指向最新记录证据，纯结构/权威变化可为空 |
| 待处理证据 | 记录ID/版本、变化前后受影响根、结构版本及authority_revision；记录快照在不可变审计，发布/转权证据在相应任务 |
| 任务状态 | status、lease_owner/lease_until、fencing版本、next_attempt_at、last_error及时间；记录可观测积压和失败原因 |

记录、审计和head原子提交。消费者每批有界领取，按根合并过期待处理输入，IID通过ODS解析为全局根ID。资源未就绪保留待处理。进程重启按持久状态恢复，失败持久退避；证据保留覆盖全部未消费变更。

候选构建捕获GitLab输入、表单head版本、结构/范围定义及权威修订，所有批次使用同一冻结输入，或在检测到输入漂移时终止该候选。变化前后根的并集全部重建。租约/fencing校验成功后，仅结算本次捕获的change_version，更新期间到达的较新变更保持待处理。

detail返回保存结果，派生端点展示完整结果及更新状态。失败保留此前完整产出并标明待更新；作废涉及的旧结果标明失效并重算，当前业务有效集遵循§2.3。发布须有完整输入证明，旧任务不能结算新版本。

Issue全量、定向、周期SLA重算、诊断及写回前复核调用同一权威读取器。标签写回只消费完整公开结果，执行前核验GitLab及表单输入已收敛、当前开关、租约和写所有权。保存事务结束后由消费链调度，网络写入保持在独立工作任务。

#### MR走查与既有事实适配

走查结果按记录建立，MR事实按既有粒度保持。逐项替换原form_records CTE的时长、走查日期、owner_name/reviewer_names来源及状态/速率/效率派生，盘点检索、标签规则、工作区、统计和导出依赖。记录级展示和MR级汇总分别定义，已有求和、最小值和空值语义的变化列入有意差异清单。

SQL聚合与Mapper按真实类型对齐，integer/Integer契约使用checked cast及typed RowMapper。真实PostgreSQL验证10+20=30、无表单NULL、零、整数上限和越界拒绝发布；定向及全量使用同一来源隔离与选择规则。无匹配范围返回明确未配置状态。

#### Issue九项指标选择器

| 消费项 | 选择依据 | 结构化消费契约 |
| --- | --- | --- |
| planned_resolution_*/planned_merge_version_branch | 最新响应模板整份，不逐字段补旧 | 按(createdAt,recordId)降序选未作废响应的当前修订；编辑不把旧记录改成新一次响应 |
| research_template_time/响应周期 | 第一条调研响应创建时间 | min有效响应createdAt，保留来源时间；编辑不后移；回复独有时不制造响应效率样本 |
| has_response | 表达响应模板存在 | 与回复存在、延期豁免分开 |
| 响应延期豁免 | D-21：有效回复存在 OR 响应有合法计划时间；响应从新往旧查 | 回复存在即豁免；仅响应存在时检查合法计划，缺签等完整性问题由合规规则单独判断 |
| 原因、非法唯一判定、原因统计/导出 | 最新有效回复按创建时间选，编辑不重排；多选采集与唯一性合规独立 | 按稳定具体原因ID集合投影；字段、合规与统计按§2.3及D-30分别执行。 |
| fix_user | 现有SQL取最新匹配回复作者；B3不另造代填流程 | 取被选有效回复创建账号姓名快照，来源见§3.1；记录归属沿§2.1 |
| known/new affected与回复详情导出 | 从被选回复的独立字段投影 | 新结构直接读类型字段；历史未知为“--”、未选为“否”，两者不同；不再回解析新表Markdown |
| resolve_deadline/解决延期 | B2确定响应选择按创建时间最新；D-21合法计划查找及18天公式分别保留 | 按§2.3的记录顺序选择；旧反向循环取最早的差异列入产出影响及I2回归 |
| fixed_label_time/解决周期 | 完成状态及真实加标事件，沿D-20/D-27及当前已确认状态单元 | 完成取真实事件时间，两端统一为同一瞬间 |

系统时间由可注入UTC时钟写入，新表timestamp约定UTC naive，页面按上海时区显示。计划解决日期为业务日，由SLA规则显式解释截止时刻。历史走查时间先核验来源语义再迁移。

Issue沿[D-27](../decisions.md#d-27-客户活动时间按列固定语义完成事实投影显式使用上海时区)逐列解释：created_at_source为已核UTC墙上值，fixed_label_time在事件到事实边界投影为上海墙上值，计算两端先还原为同一瞬间。完成状态和真实加标事件沿D-20及当前状态规则，18天公式沿既有定义。计划/首次/原因等字段独立选择，编辑及作废使用各自有效集重算。

消费影响至少包括Issue非法标志/说明、客户计划字段/筛选、响应及解决效率、原因和影响功能导出、周期延期及写回资格。reason_category、fix_user、has_response、research_template_time、planned_resolution_*、planned_merge_version_branch、resolve_sla_days/deadline及两个延期标志逐项核验。

#### 按批次划分COMMENT与FORM权威

form_resource_authorities以完整来源/项目/资源类型/IID/templateKey唯一登记，保存scope_id、authority（COMMENT/FORM）、authority_revision、cutover_manifest_id和effective_at。读取器必须取得已登记权威；FORM无记录表示未填写，按其业务规则产生结果。

切换清单冻结来源、项目批次、模板、明确切换时刻、资源判定依据、规则版本和操作者。按以下规则执行：

1. 切换前存量Issue维持COMMENT，继续原评论流程；切换批次中创建时间达到切换点的新资源显式赋权FORM。源创建时间采用核实后的来源时钟语义，跨界样本列入验收。
2. 既有collect_form_records的S按§3.4迁移，其MR/模板在初始清单中显式登记FORM。其它MR按批次与资源清单确定权威。旧评论批量导入不进入本期。
3. 资源到达镜像时按已批准批次规则持久登记权威，再允许对应消费与新表单写入。COMMENT资源页面标明沿用评论流程；写入接口核验FORM及authority_revision，避免新记录落在未切换消费路径。
4. 权威用CAS推进，变更与head同事务登记。结构升级只改变存储版本，来源转权独立留证；范围编辑不暗中改变历史资源权威。
5. 切换前停用对应旧写回所有权；新读取、周期任务、导出及标签写回统一使用已选权威。两边按同一资源/模板分别只读一种输入。

COMMENT保留仅服务已划定历史资源。后续整体退出另按E6登记截止范围、验收和移除条件；本期以切换清单约束边界，禁止形成按缺字段回读旧评论的永久内部回退。

### 3.6 前端交互与本地草稿生命周期

#### 页面入口与记录展示

保留`/external/code-review-form?gitlabBaseUrl&projectId&mrIid`，显式转换为MR上下文。统一入口为`/external/forms?gitlabBaseUrl&projectId&resourceType&resourceId[&templateKey]`。Issue省略templateKey时列出已登记响应/回复入口。

CI_ROBOT自动投放稳定链接，链接携带资源上下文和必要模板键；由平台解析环境来源、范围及当前版本。机器人可给Issue统一入口或分别给两模板链接，按E1核实实际调用。notification-payload若有外部调用，其参数/认证变更按外部契约迁移。机器人账号仅是入口发布者，创建人见§2.1。

填写页按已发布field_spec单列渲染，顶部显示标题、版本、对应MR/Issue链接和加号。响应和回复分别新增独立块。保存块默认只读，显式进入本人编辑；显示填写人、模板、产生标签及创建/更新时间，编辑后用灰色小字提示。历史姓名未知如实显示。

修改非最新Issue记录时显示“修改此记录不会替换最新记录”；修改最新记录的计划字段影响当前值。用户需提交新一次计划时可新增响应。作废交互执行§2.3的双重确认。

页面区分未登记模板、资源待同步、不支持类型和COMMENT历史流程。detail默认返回全部记录，按单资源真实负载测量响应和渲染；超预算再评审分页或分段协议。

#### 按需刷新与独立编辑

本期不做实时推送或定时轮询。已保存列表仅在进入/切换资源、用户主动刷新列表或本页记录保存成功后按需加载；他人新增、修改、作废不会主动刷新当前浏览器。主动刷新列表与刷新整个浏览器页面分开，后者执行离开保护。

已保存记录列表和本地填写块分别维护。记录使用稳定recordId，草稿使用draftId，列表位置不承担身份。重新加载只更新对应已保存状态，保留所有新增/编辑草稿、输入焦点、下拉展开和选择、光标及滚动位置。加载失败保留原列表与输入。旧页面中某条记录为空或缺失，仅说明页面尚未加载该记录，写入边界遵循§3.3。

每次请求绑定发起时的账号/身份代际、C、编辑会话、目标记录或草稿及请求次序。迟到结果只更新原归属，失效或较旧查询结果不得替换新状态；已保存或作废的记录不因旧响应倒退。切换资源/模板后，原请求不能改写当前页面或清除新草稿。

提交期间仅暂停当前块的字段编辑和重复提交，固定该次提交内容，其他填写块仍可操作。返回记录先合并到已保存状态，再刷新列表；重复点击或超时按§3.3核验同一次操作。仅在明确成功后清理对应草稿。请求结果未知时保留提交快照和状态，核验后才允许丢弃或替换该次提交内容；用户关闭弹窗、切页或取消等待不被当作服务器撤销成功，离开可保留快照后继续核验。

#### 危险操作与离开确认

危险操作按会丢弃输入、覆盖内容、删除有效记录或改变模板使用范围来判定。提示先说明目标和后果，按钮明确表达动作，取消保持原状态；确认后才执行。普通打字纠错、未丢弃内容的选项调整、获得焦点及正常保存无需额外确认。

| 操作类别 | 执行前提示与确认 |
| --- | --- |
| 作废正式记录 | 按§2.3先确认影响，再输入“作废”，服务端继续核验确认字段与版本 |
| 丢弃或覆盖填写内容 | 取消有修改的编辑、清空/删除有内容的草稿、恢复旧草稿覆盖当前输入前，说明将丢弃哪一块内容并确认；只影响目标块 |
| 条件切换导致删除已填明细 | 先说明哪些已有说明将清空，确认后变更控制项和对应明细；取消同时保留原选项及明细。空明细直接切换 |
| 管理变更 | 删除字段/选项或模板草稿、版本发布、范围启用/变更及退役前展示影响并确认；发布确认以已核验预览为准 |
| 主动退出且存在未提交内容 | 提示退出会清除本平台各标签页草稿，确认后执行退出及清理 |
| 离开尚未提交的填写页 | 显示各块的草稿保存状态和尚未提交事实，用户选择继续填写或保留草稿离开；丢弃草稿是单独的明确操作 |

“已修改”按可编辑字段与初始值/上次正式保存值比较，忽略焦点、展开状态等交互状态。修改后改回原值不再标记业务修改；空白新建块的系统默认值和请求标识不触发离开确认。任一块有实际未提交内容或未确认的提交结果，就保护页面离开。草稿已写入本机不清除“未正式提交”状态。

站内跳转、关闭填写弹窗/编辑区使用平台确认框，可显示“内容尚未提交，草稿已保存至本机”等实际状态；草稿写入失败才说明当前内容未自动保存，正式提交失败与草稿状态分别展示。刷新、关闭标签页或离站时挂载beforeunload，由浏览器展示原生确认框，文案及触发受浏览器控制。页面进程强退等情况依赖持续草稿保存恢复，不将关闭回调作为唯一保存点。已确认离开不连续重复弹相同确认，正式提交成功或显式丢弃后移除对应保护。

#### 草稿数据与恢复

每块分配稳定draftId；新增块首次生成requestKey并立即保存。草稿按账号+C+editingSessionId+draftId存储，editingSessionId区分标签页/编辑会话。草稿包含`draftSpecVersion=1`、账号、完整C、scope/storageVersionId、draftId、editingSessionId、kind、编辑目标recordId、baseRecordVersion、baseFields、用户fields、新增requestKey、首次待提交快照、输入修订、已持久化修订、草稿保存时间及保存状态。

输入变化后及时合并写入本地，失焦、页面转为后台及站内离开前补写；按输入修订核对持久化结果，旧修订写入成功不能标记新输入已保存。草稿允许字段未填完，独立于正式提交的必填/合规校验，不形成他人可见记录或统计输入。本地保存依赖当前浏览器，界面使用本机含义的文字及图标。

恢复先等待权威身份及detail，只恢复同账号同C。重开可恢复原草稿，多标签恢复时为独立会话副本，保留原requestKey及首次提交快照；各活动会话互不覆盖，不将同次新增复制成新操作。已有当前输入时先供比较，覆盖前按本节确认。用户主动再次新建才使用新requestKey。他人、历史只读或已作废目标的本人草稿内容仅可查看/复制，不能再次提交原目标。

存储版本变化或模板退役时保留输入，显示需核对或已停止提交，允许查看及复制。冲突保留baseRecordVersion和用户内容，加载权威文本供比较，用户接受后建立新编辑草稿。人员候选失效、Issue级别变化等提交时条件变化定位具体字段，保留其它输入并提示处理；恢复与比较不自动改写正式记录。

#### 草稿与正式保存状态反馈

每个新增或编辑块在标题右侧、靠近保存操作的位置持续显示状态及必要时间，采用文字和图标，不依赖颜色或反复弹Toast。首次填写处说明“草稿仅保存在当前浏览器，点击保存后才正式提交”。同页多块分别显示，避免一个成功状态掩盖其它草稿失败。

| 状态 | 展示文案与含义 |
| --- | --- |
| 空白新建/编辑未改值 | “尚未填写”/“未修改”，无未提交变更，不假报自动保存成功 |
| 输入更新待写入 | “正在保存草稿…”；即使上一修订已写入，也不提前显示当前修订已保存 |
| 当前输入已持久化 | “草稿已自动保存至本机（HH:mm）”，同时保留“尚未提交”标识 |
| 草稿恢复 | “已恢复本机草稿，尚未提交”，正常核验后继续填写 |
| 本地写入失败 | “草稿自动保存失败，当前内容仅保留在本页”；常驻提示、允许重试，离开时再次说明风险 |
| 正式提交处理中/结果未知 | “正在提交…”/“提交结果待确认”，保留草稿与快照并核验结果 |
| 正式保存完成 | “记录已保存”，清理本次已提交草稿，其他块保持各自状态 |

自动保存提示采用成熟表单的顶部状态反馈模式，参照[Google Forms草稿提示](https://support.google.com/docs/answer/10952360?hl=en)；本系统采用本地草稿，留存与恢复范围按本节约定。浏览器关闭交互遵循[MDN beforeunload说明](https://developer.mozilla.org/en-US/docs/Web/API/Window/beforeunload_event)。

| 当前状态 | 事件 | 处理 |
| --- | --- | --- |
| 新增或编辑草稿 | 开始保存 | 冻结当前块提交内容；新增同时保留首次载荷和requestKey，其它块仍可填写 |
| 新增结果不确定 | 超时、断网、重开 | 使用同key及同载荷重放核验；需要改内容时先确认原写结果，已保存转显式编辑，确定未写才解除冻结 |
| CONFIRM_REQUIRED | 用户确认同走查人 | 同key、同载荷，仅改变确认标记 |
| CREATED/REPLAYED | 保存成功 | 先接收权威记录并合并列表，再清本块草稿；保留其它新增及编辑草稿 |
| 编辑结果不确定 | 重新获取detail | 当前版本=base+1且可编辑字段等于提交快照则提示已保存；其它情况保留并进入比较 |
| 已保存 | 列表reload失败 | 显示“保存成功，列表刷新失败”，允许单独重载；保持保存成功状态 |
| 保存失败/冲突 | 400、403、409或网络失败 | 保留本块输入，展示原因；冲突按权威版本核对 |

#### 401、跨标签页与存储故障

复用auth-state身份代际。401转游客时保留原账号草稿，同账号重登后核验目标并恢复；换账号隐藏原账号内容。主动退出执行本节确认后清空本平台实例的全部表单草稿，写入登出代际并广播，旧标签页停写并清内存输入。迟到watch和请求回调在写入前复核账号/代际，避免恢复已退出内容。退出或取消等待不撤销已成功的正式保存，结果未知时重新登录后核验。

localStorage不可用、损坏或配额不足时按草稿失败状态展示，保留内存输入和离开保护。退出信号无法可靠传播时，身份复核失败即停本地自动保存并提示限制。草稿持久化、离开确认、提交结果分别维护；服务端草稿和实时协同留在后续范围。

#### 数据库查看旧编辑器退役

删除DatabaseBrowserView.vue的旧表操作列、编辑弹窗、状态和保存函数，删除updateCollectFormRecord API、类型、导出及维护旧入口的测试。当前及历史实例/记录、审计、迁移、发布任务和元数据均以只读表登记，记录修改统一走本人记录端点。

### 3.7 权限模型与系统门禁登记

#### RBAC目录、授予及默认值

普通记录权限为`form.record.create`、`form.record.edit_own`、`form.record.delete_own`，默认授予全部平台托管角色，含NORMAL_USER。创建人归属按§2.1额外校验。

迁移同批更新platform_permissions、platform_role_permissions、platform_default_role_permissions，删除旧`code_review.form.create/edit/delete`：全新库按默认全量播种；存量库create/edit继承旧create/edit当前角色集合，保留定制收紧；delete_own按本人作废默认口径授予托管角色。defaults三新码始终登记托管角色全量，与当前定制授予分开维护。

`form.template.manage`仅授予管理员，权限目录/角色/defaults同批登记。普通用户无管理菜单和路由，后端直接请求返回403。新增角色及恢复默认沿平台现有权限管理。

#### 端点、数据查看和消费登记

1. 端点目录删除旧save/delete/update-record及对应快照，更新detail参数/响应，登记记录、候选、管理/发布端点及所有结果分支。写用例声明实例、记录、审计、head及相关任务/权限表影响。
2. notification-payload来源验证可能使原任意Issue样例转为参数拒绝，登记用例目标语义及真实调用迁移。
3. 数据库查看登记三张元数据表、各版本实例/记录、记录审计、迁移日志、发布任务、head、权威及历史旧表；按实际结构维护中文列标签。
4. 登记实际MR/Issue消费、事实/投影、工作区及缓存依赖。若新增统计板页面/端点，同单元完成页面view/export映射、权限目录及角色迁移、实时工作区元数据、事实依赖四类登记。
5. 新Flyway迁移只追加，既有文件零改动/零删除，checksum锁文件只新增条目；验证漂移、不可变性、Flyway smoke和代表性非空迁移。

#### 黄金门禁

日常开发先跑最小充分测试及端点覆盖护栏。仅在发布打包/内网部署验收前或用户明确要求时运行黄金全链路。拟改响应、表状态、旧用例退役先展示差异并确认；更新模式生成受影响快照，经审阅后用比对模式全绿。夹具变更须确认新基线并登记manifest及D-08，快照只由工具生成。

运行前记录开发服务的实际入口及版本，停止需释放资源的服务；完成后恢复同一入口，验证后端UP及前端可访问。原入口为正式Compose/制品时恢复对应栈；原入口为主树开发服务时使用pwsh启动项目脚本。具体命令与纪律遵循[AGENTS.md](../../AGENTS.md)及[黄金基线架构说明](../architecture.md)。

## 四、测试验收矩阵与实施路线图

### 4.1 测试验收矩阵

| 范围 | 必须覆盖的确定性行为 | 最小有效层 |
| --- | --- | --- |
| R1 SQL 类型 | 10+20=30 且生产 Mapper/实体类型一致；无表单 NULL、零、整数上限、上限+1 不发布；根/全量同源 | 真实 PostgreSQL 集成测试，覆盖生产Mapper及真实返回类型 |
| R2 迁移 | 混合模板/范围/来源、NULL姓名、作废行、时间戳、错配/非法资源键；S字段守恒、归属唯一、集合外不混入；失败不切指针 | 隔离库完整 Flyway/代表性非空迁移与只读核验 |
| R3/R4 身份/路由 | 同数字局部ID不能选错记录；同模板两范围同标签/不同当前版本；来源相同数字项目、范围重叠拒绝；跨版本ID与审计保留、旧请求拒绝 | 元数据单元测试 + 跨版本集成测试 |
| R5 来源隔离 | A/B URL同project+iid时不混算，未登记URL拒绝，其他模板不参与、根/全量一致 | CTE与上下文解析集成测试 |
| R6 幂等 | 同key并发1记录/1CREATE；重放已编辑返回当前、不复活作废；异人/异上下文/改载荷不泄露；401/重开同key | 后端集成 + 前端可控响应/草稿测试 |
| R7 原子性 | 空页面并行新建、旧页面后保存及不同记录增改废交错均保留独立记录；相同版本编辑最多1成功、编辑与作废交错、实例首次创建竞争、审计失败回滚；需确认无实例/记录/审计落库 | JUnit及真实PG受控事务并发 |
| R8 旧入口 | 搜索旧端点/方法无调用；历史数据库查看无操作入口；新表无代改 | 前端组件/类型检查 + 后端端点目录护栏 |
| R9 权限 | 全新/存量定制/defaults缺口、恢复默认后新码可用旧码消失；本人/他人/历史403 | 迁移/权限集成与服务授权测试 |
| C1/C3 规格 | person双列/真实类型、伪造姓名、空正名、未知字段、请求大小/Unicode长度、整数边界、标识符长度/冲突；空ODS启动仍可验证本地元数据 | 单元/参数测试 + 本地元数据启动集成 |
| C2 草稿 | 三模板/资源/编辑会话隔离，多块及多标签恢复互不覆盖；输入修订/落盘状态一致；冲突/401保留；确认退出后跨标签停写；换账号隐藏；存储失败提示；成功但reload失败不重复 | Vitest可控存储/身份/请求生命周期 |
| I1 Issue字段 | 响应六域/条件签字/日期与分支；回复1—9项/条件说明/稳定原因ID；未知/错模板字段拒绝，三真实物理表 | 字段与服务单测 + PG写入/读取 |
| I2 Issue消费 | 最新计划、首次响应、任一有效回复豁免、原因/修复人/导出分别选择；编辑/作废/多模板不串值；完成事件不被表单替代 | 同一非空输入走真实全量/定向/周期与导出 |
| I3 初始化/外链 | 空库待绑定可启动；不同环境configId激活；重叠/无物理表拒绝；旧MR链接保留、Issue多模板选择、镜像晚到不假成功 | 完整Flyway + 服务/路由测试与浏览器 |
| I4 切换/恢复 | 已有评论无重复计数、无隐藏fallback；原归属不猜测；输入漂移、重启/租约/取消及全量到自动增量交接 | 真实PG受控并发/真实链路 |
| C4 发布边界 | 记录/审计/持久变更原子提交；MR/Issue消费者完整输入、作废排除、旧任务不能结算新版本；失败保留完整产出；周期SLA不回读旧源 | 真实PG事实/交接集成 + 读源回归 |
| 操作与提示 | 无实时推送/轮询；按需加载保留列表、草稿和控件状态；旧响应不倒退或串资源；危险操作取消零变更；草稿已保存但未提交仍离开确认，未改值无提示；原生关闭边界及本机状态文案准确 | Vitest受控时序及真实浏览器交互验收 |

管理员字段发布另须验证：字段改名保值、新增历史空、删除不再引用旧列、纯规格变更无需改页面代码；重复发布、任务恢复、租约过期、当前指针CAS冲突、迁移守恒与漂移拒绝；旧链接继续访问、退役仍消费历史有效数据。覆盖受控DDL的初装/后续发布分工和首次无from_version路径。

B1回归覆盖零/一/多原因、同大类/跨大类、别名等价、未到检查状态、历史值留存，以及多选保存后在适用门槛被标异常。展示非法标志、相关筛选/导出和异常计数变化；统计原因个数分母保持既有定义。

C5文档与顺序检查覆盖本稿引用、登记清单、目标代码/制品和实际验收证据一致。技术预算与容量验收使用目标内网负载，记录条数/文本分布、保存到派生可见延迟、积压及恢复；测量后约定参数和时效预算。

后端测试位于backend/src/test/，前端位于frontend/src/**/*.test.ts。按实际package scripts执行typecheck、生产构建和lint。浏览器验收三模板新增/编辑/只读、代填、同人确认、作废、资源跳转、多草稿、401/换账号、保存后reload失败，以及管理页、预览和发布；覆盖保存触发加载时另一块的焦点/下拉状态、危险操作确认及取消、草稿保存中/成功/失败/恢复/正式提交和未提交离开提示。真实非空迁移、SQL、并发和消费链分别验证。

### 4.2 S0至S6实施路线

| 阶段 | 工作 | 准入及完成条件 |
| --- | --- | --- |
| S0 发布准备 | 群内评审；核实来源、范围、存量、人员、标签、机器人及目标制品；冻结切换清单 | 明确旧写停机、恢复点、重建入口，审阅权限升级语义、输入预算及消费影响 |
| S1 元数据/迁移 | 先补非空迁移、身份、路由测试，再实现结构、初始迁移、共享序列及checksum登记 | S守恒、只读历史、唯一业务归属和空平台启动通过 |
| S2 服务端 | 三模板校验、上下文、六步幂等、CAS、原子审计/head及管理员发布任务 | 原子失败、任务恢复、本人授权和旧入口退役通过 |
| S3 消费适配 | 三模板读取器、指标选择器、冻结输入、根/全量/周期同源、转权及写回资格 | 非空真实SQL、输入漂移、重启/取消/接管及全量到自动增量交接通过 |
| S4 前端/管理 | 单列填写、Issue入口、草稿/身份、旧MR链接、管理员字段/绑定/发布/退役 | 浏览器验收及普通用户管理403；旧数据库编辑器退役、权限与目录齐备 |
| S5 最小充分验证 | 默认套件、相关真实链路、构建/lint、UI及文档检查 | 必测无跳过，产出变化和未验证外部事项列明 |
| S6 发布门禁 | 规定黄金；停旧写、迁移验收、前端切换、事实重建/预热和现场三模板验收 | 黄金全绿、存量覆盖完整、重建入口可用、切换及写所有权已核验 |

实现依赖为共用基础、三模板采集、消费切换、授权发布。老平台整体退出另满足E6。恢复执行§3.4，发布前固定代码/制品，按风险一次运行最小充分验证；修改或新失败再补相关复验。

## 五、外部环境联调与待补证事项

| 编号 | 所需材料或现场验证 | 完成后解决的问题 |
| --- | --- | --- |
| E1 | CI_ROBOT源码/配置、真实Issue链接、notification-payload调用及认证样例 | 确认Issue模板投放、旧外部调用迁移对象和期限；自动投放职责已由用户确定 |
| E2 | 内网旧表全量及作废行、来源配置、资源键、人员分布和范围清单 | 验证S覆盖、字段预算及守恒；历史评论保持既定来源，无需为本期批量导入盘点 |
| E3 | 实际接入项目/阶段清单及发布标签证据；接入未知范围时提供RS资料、DGM真实版本依据 | 绑定真实产品体系，当前已核范围继续推进 |
| E4 | 最终访问域名/路径、代理Cookie及会话策略 | 验证同源登录、资源返回、401草稿恢复和现场访问入口 |
| E5 | 会议提及后台页面AI自动填充的实现和流程，按纪要线索与相关开发人员核对 | 确认自动化是否操作新表单及需覆盖的行为 |
| E6 | 老平台其余报告输入、停写回手段和窗口、目标部署制品及新老切换清单 | 确认整体退出前置条件、当前包的重建/发布能力和唯一写所有权 |

## 附录A：历史数据实测、GitLab OAuth调研与修订备忘

### A.1 B1与B2实测

以下为2026-10-20对本地qaflex-dev-postgres-15432/qaflex.public历史镜像的只读调查；响应创建覆盖5月15日至7月1日，最新镜像为8月24日16:10 UTC。ODS保留最终正文，无逐次正文前值。统计按未删除记录及完整资源身份计算。

| 调查 | 数据结论 | 可定位证据 |
| --- | --- | --- |
| B1原因项数 | 有效回复156条/155个Issue：146单选、4双选、6未选；每Issue取最新后为145/4/6，4条均跨大类 | `.tmp/reports/form-cause-selection-20261020.json`；模板969、旧Wiki及新老原因检查器 |
| B1四条多选 | CrownCAD #30933/#30697：功能设计遗漏及编码逻辑的数据/状态处理错误；#31315：需求遗漏及功能设计遗漏；CCProduct #2523：需求变更未同步及场景考虑不全 | #30697/#30933已修复漏报；#2523另有调研模板异常；#31315未修复，未进入该项检查门槛 |
| B1根因 | 老CauseUtil以空格拼接分类，检查却按&拆分；新IssueClassificationRules继承该逻辑，非空多分类也可能计成1项，空分类亦不能可靠表示0项 | `CauseUtil.getMajorByCause`、`IssueServiceImpl.checkCauseLegal`、`IssueClassificationRules.oldPlatformMajorByCauseSplitCount/appendOldPlatformMajorIfContains` |
| B2多响应 | 有效响应154条/149个Issue，项目325为150/145，项目9为4/4；144个单条，5个各两条；另3条已删除排除 | 项目325 #2226/#2257/#2301/#2327/#2349，SQL窗口与时间交叉连接复核 |
| B2编辑 | 78条updated_at晚于created_at，51条有last_edited_at；5组按创建/更新/显式编辑选最新均一致，旧响应在新响应后修改为0组 | ODS记录时间、最终正文及同值事实修订 |
| B2计划实例 | #2301旧响应6月17日08:39创建，现计划6月27日；新响应6月22日18:57创建、19:02编辑，现计划6月23日；两种排序均选新响应 | note828226/note831092，以上为上海时间；新事实计划为6月23日，首次响应为6月17日 |

该样本证明多因、多份计划及编辑实际存在，具体编辑了哪个字段须有正文前值才能判定。相关业务已在§2.3、字段在§3.2、选择器在§3.5确定。

### A.2 模板、多记录与项目版本证据

| 调查对象 | 结论与实施价值 |
| --- | --- |
| 早期本地模板盘点 | code_review评论463条，代码自查182条/167个MR，Issue响应154条，早期回复158条；与A.1有效集的时间/删除条件不同，分别留作采样证据 |
| 多记录走查 | 兼容表CC 30308行/28069个MR，按所属项目+IID去重；多记录MR共1702个，其中不同走查人1690个/3449条，同走查人多条12个/24条 |
| 非法走查页 | 历史样本8545条记录对应7303个MR，印证记录级展示；同一MR多行是既有粒度 |
| 创建人与走查人 | 463条评论中449条姓名直接一致，14条经显示名/别名核对为同人，样本无代填；模型按会议要求保留代填 |
| 同人重复 | 12组间隔小于1小时6组、1至24小时5组、1至7天1组；旧平台按MR/走查人/所属项目定期去重，新表单允许同人多次并使用软提示 |
| 新平台本地表 | collect_form_records本地及冻结夹具为空，内网迁移须用E2非空盘点 |
| CrownCAD项目9 | 里程碑CrownCAD 2024 R2至2026 R4，存在旧命名；发布标签按项目缩写规范 |
| CCProduct项目325 | CC2024 R3至CC2026 R3，与项目9的R节奏分别独立 |
| DGM项目79 | 本地无里程碑，接入依据见E3 |
| 过渡/SP/组合 | 既有筛选及测试阶段存在CC2026R4SP1、SP3和CC 2025 R4&2026 R1；里程碑多版本均active，发布标签须人工登记 |

项目标签证据来自ods_gitlab_milestones、IssueLabelRules、CodeReviewIllegalRecordFilterOptionAssembler；评审文档版本和客户计划分支属于其它语义，不用于推断表单结构版本。

### A.3 新老平台与Wiki调查入口

| 证据入口 | 核实结论及改造影响 |
| --- | --- |
| `CollectFormService/Controller/RecordMapper`及`V20260506_02` | 旧结构固定列，同完整资源键upsert覆盖；缺created_by；新模型需独立记录及版本 |
| `CollectFormView.vue`、router及route-contracts | 旧页面硬编码MR/code_review、横向布局及自由姓名；稳定外链继续保留 |
| `V20260507_02`及审计服务 | 原审计保存操作后快照和IP/UA；新事务需传播审计失败 |
| `GitlabFactSourceSqlProvider.form_records` | 原表按project_id/request_iid聚合；时长唯一来自表单，走查日期用评论/表单updated_at/MR时间后备，owner/reviewer为后备 |
| 原MR owner/reviewer分支 | 项目9/79的dev主范围内，评论异常文本可优先占用owner；兼容表owner_name对应老author（被走查人），reviewer_names对应老assignee（走查人），按所属项目及IID区分MR |
| `CodeReviewIllegalRecordSqlQueryBuilder`、看板及读模式 | 代码走查非法页、质量与相关图表实际读formal/match_mode，由老平台同步；当前表单尚未进入该统计链 |
| 事实检索、标签动态规则及数据库查看 | 已有表单读取面，替代CTE须一并盘点 |
| 旧Wiki导出 | `C:/Users/admin/Desktop/数据采集平台规则汇总/数据采集平台规则汇总.txt`；与旧平台`IssueServiceImpl/ParseDocumentServiceImpl/CauseUtil`定点比对 |
| 新Issue解析及SLA | `IssueResponseTemplateParser/IssueResponsePlanFieldRules/IssueTemplateParsingSupport/IssueClassificationRules/IssueSlaRules`及事实SQL/mapper；显示、合法计划查找和首次响应各有选择逻辑 |
| 模板969及历史回复 | 模板说明1至8项，实际回复及检查器有第9项“是否更新了关联关系表：是/否/不涉及”，字段版本存在差异 |
| 原因及导出词典 | `DefectCauseMetricCatalog`、`SystemTestLegacyCauseExportFields`；分类正名、别名及跨大类历史映射须分别保留 |
| LEGACY转正式来源身份 | `LegacyPlatformFormalImportService`的merge_request_id按legacy_source_id存走查记录主键，GITLAB来源为GitLab MR ID；消费join/去重需区分来源语义 |

Wiki“表头即响应”的历史规则已由D-21替代；旧解决期限部分反向循环取最早，与本稿创建时间最新契约的差异列入I2回归。原因计数根因见A.1，统计公式仍以页面规则及其人工确认口径为准。

老平台还提供走查以外的报告输入及写回。整体退出需补齐其余输入、正式结果交接、报告/下钻/导出对账和写回交接；CC/DGM可按数据域分别验收。手工重建缺列修复V20261015_01是否在实际包内，按E6核实；主树、1010恢复包及其它制品分别验收。

### A.4 GitLab OAuth调研

本期填写身份使用§2.1的平台会话。后续需要GitLab身份时，采用授权码流程及read_user、后端调用/api/v4/user；账号映射保存平台账号与GitLab实例、用户ID及username，授权token用完弃置。

此前官方文档调查的交互与安全结论：

1. 管理员创建适用实例的OAuth应用；受信应用在实例支持条件下可省授权确认。已登录状态仍以目标实例实测为准，未登录或放弃识别可返回填写页。
2. 浏览器发起/oauth/authorize，携带client_id、redirect_uri、response_type=code、一次性state及scope=read_user；后端在/oauth/token以授权码换token，再访问/api/v4/user。
3. state由服务端签发、绑定会话并一次核验，生产回调使用HTTPS；confidential client密钥由后端持有，可叠加PKCE；public client按实例能力使用PKCE。
4. OIDC为等价备选：openid及所需profile/email，发现端点为/.well-known/openid-configuration；验证ID token签名、issuer/audience、nonce和有效期，声明不足时取userinfo。正式实现选择一种协议路径。
5. 可按已核email/工号规则匹配平台人员，匹配失败只保留已验证账号映射。Referer和静态共享链接无法提供点击者身份，平台页面也无法读取GitLab域的会话Cookie。

参考：[OAuth应用配置](https://docs.gitlab.com/integration/oauth_provider/)、[OAuth API](https://docs.gitlab.com/api/oauth2/)、[OIDC](https://docs.gitlab.com/integration/openid_connect_provider/)。上述为原稿调研归纳，正式接入时核验目标实例版本、应用配置及认证行为。

### A.5 会议差异与修订备忘

| 原会议表述 | 当前有效裁定 | 定义位置 |
| --- | --- | --- |
| 并发冲突不处理 | 用户改为逐记录提交和乐观锁 | §3.3 |
| 仅表单创建人编辑 | 用户明确落实每条记录的创建账号 | §2.1 |
| 不保留完整编辑历史 | 保留后台操作后快照，页面显示两时间 | §3.1、§3.6 |
| 历史版本入镜像库 | 自有库只读历史表及数据库查看 | §2.2 |
| 代码走查先行 | 2026-10-20明确本期三模板完整覆盖 | §2.3 |
| 字段管理面向内部开发 | 用户明确管理员独立页面增删改字段及退役 | §3.4 |
| 发布带版本号及可带版本URL | 项目发布标签；稳定外链定位当前结构及原记录 | §2.2、§3.6 |
| 登录态永久有效 | 复用实际会话策略，失效时保留草稿重登 | §3.6、E4 |
| 未细化迁移及同人多次 | 用户确认同模板全历史迁移及软提示 | §3.4、§3.3 |

v5.0将v4.1按六部分重组，调查移入附录；v5.1将用户确认的使用场景归纳为§2.4及§3.6的记录边界、按需刷新、操作确认、草稿状态和恢复规则。v3.4提交为3a105ddf，后续业务裁定以§2.5及D-30为准，过程修订由Git历史保留。
