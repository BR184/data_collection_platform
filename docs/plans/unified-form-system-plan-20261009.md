# 统一表单系统建设方案（v6.0：原版本留存）

> 版本：v6.0，2026-10-26。用户已确认业务设计；各阶段实现与验收以以下生效边界及执行单为准。
> 生效边界：N0—N3通过，N4实施Issue先启用及走查能力交付；生产MR继续评论，外部数据与筛选就绪后另设走查启用点。历史解析保留，非法页全旧。[消费接入方案](unified-form-adoption-review-20261030.md)确定改造点与断言，批准UI保持。
> 实施与审核入口：[执行单](unified-form-system-implementation-plan-20261021.md)。产品承诺归[产品文档](../product.md)，技术边界归[架构](../architecture.md)，决策及理由归[D-32](../decisions.md#d-32-统一表单字段发布原版本留存跨版本读取与独立导出)。

## 一、建设目标与已确认选择

统一采集MR代码走查、Issue响应和Issue回复，替代文字评论式表单。表单保存结构化业务输入，消费方结合GitLab资源、标签和事件形成事实、合规结果、统计和导出。CI_ROBOT自动投放入口链接；管理员在采集平台管理业务字段、范围及发布，普通用户填写并维护本人记录。

| 事项 | 当前选择 | 定义位置 |
| --- | --- | --- |
| 存储 | 每个模板范围的每个已发布结构版本拥有独立实例表、记录表 | §2.2、§3.1 |
| 历史记录 | 保留在原版本，不随新结构发布复制或改写；正常使用中的范围允许按原版编辑、作废 | §2.2、§3.3 |
| 字段管理 | 管理业务名称、填写方式、必填、选项、条件和顺序；内部键与数据库结构由系统维护 | §3.4 |
| 新建与旧链接 | 新建用当前版本；旧链接仍能访问各版本原记录，每条按所属版本展示 | §3.3、§3.6 |
| 统计 | 对选定版本中可比较的相同业务字段合并统计；不同字段不强并、不补零 | §3.5 |
| Excel | 按版本导出授权范围内完整有效记录，保留该版本特有字段 | §3.8 |
| 发布 | 建立并校验空目标结构，再原子登记可读版本和切换新建入口；不执行全历史复制 | §3.4 |
| 业务来源 | COMMENT与FORM仍按资源、模板明确赋权；不因跨版本缺字段回读旧评论 | §3.5 |

保留Vue 3、TypeScript、Element Plus及平台现有身份、权限和数据库能力。本期不增加自由布局、任意脚本、通用指标设计器、服务端草稿、GitLab身份绑定、正文回写或老平台整体下线。表单按版本导出已纳入本期，不再列作后续占位。

本次替换的是历史数据处理与消费模型；三模板业务含义、Wiki合规、本人维护、独立记录并发、确认和草稿保护继续有效。v5.1的逐字段复制/转换、迁移后当前表承载全部历史、旧版本记录一律只读以及单一存储版本消费，均不再是新版实施要求。

## 二、核心领域模型与业务规则基线

### 2.1 身份与权限模型

| 概念 | 业务定义 |
| --- | --- |
| 完整资源上下文C | `gitlabBaseUrl/projectId/resourceType/resourceId/templateKey`；resourceId为项目内MR或Issue的IID |
| 业务表单上下文 | 某范围内C对应全部版本的填写记录；同一Issue的响应和回复分开 |
| 物理实例 | 一个结构版本内C对应的记录关联行；不能用实例数量代替跨版本记录/资源数量 |
| 填写记录 | 一次独立填写，拥有稳定记录ID；同一账号、同一走查人可多次填写 |
| 创建人 | 服务端会话username，即LDAP user_id，写入created_by |
| 走查人 | code_review的业务人员字段，与创建账号分别保存 |
| 管理员 | 维护模板、字段、发布、绑定和退役；管理能力不改变记录归属 |

每条记录只有创建人可以编辑或作废，且须拥有对应操作权限。代填时仍由实际提交账号维护，管理员沿用同一归属判定。无创建账号的历史记录以及停用账号的记录保持只读。

填写页复用平台同源登录、session和CSRF。未登录先登录再回到原资源及模板；会话失效按§3.6恢复草稿。GitLab账号识别为后续可选能力，调查见附录A.4。

### 2.2 版本与物理分表模型

| 身份 | 定义与规则 |
| --- | --- |
| template_key | MR走查、Issue响应、Issue回复各自的稳定模板身份，不能因同名字段跨模板混算 |
| scope_id | 来源、项目集合及资源类型等绑定范围；后续字段发布不暗改绑定 |
| 结构版本ID | 一套冻结字段规格和独立物理表；产品发布而字段未变可以沿用，不按年份自动建表 |
| version_label | 管理员选择并提供依据的产品发布标签，仅用于业务识别和追溯 |
| current_version_id | 该范围下一条新增记录使用的结构版本，不能拿它定位所有旧记录 |
| storageVersionId | 记录实际所属的存储版本，由记录定位目录确定；原生新版记录创建后不因后续发布变化 |
| origin_version_id/template_version | 记录首次产生时的版本及标签，保留追溯；不能代替实际存储位置 |
| recordVersion | 该条记录的乐观锁修订号；编辑或作废成功递增，与结构发布分别处理 |

例如V1有100条记录，发布V2后V1仍有100条，V2初始为空。V2新增20条后，资源列表和消费读取两版有效记录，共120条；不会把V1复制为V2的另一批100条。

旧记录按所属版的字段名称、选项、必填与条件规则展示和编辑。V2新增字段不写入V1记录；V2删除字段不删除V1旧值，旧值仍可原版查看及导出。显示改名不改变字段身份；实质含义、单位或不可比较取值改变时创建新字段身份，不借同名合并。范围整体退役仍按既有只读规则，不能把“历史版本”与“退役范围”混为一谈。

每条记录只有一个权威存储位置；同一业务C可在多个版本各有实例和独立记录。已发布结构冻结，历史记录允许写数据行不等于允许改旧表结构。表单审计及历史结构按现有整库备份恢复机制保护。

版本间相同性由系统维护稳定字段身份和冻结语义，管理员不维护字段码、列名或映射SQL。比较和统计规则见§3.5。无法确认原账号的历史记录仍只读，不从走查人姓名猜创建权限。

### 2.3 三模板业务规则与校验边界

| 模板键 | 资源类型 | 业务用途与消费边界 |
| --- | --- | --- |
| code_review | merge_request | 一次走查一条记录，非法数据页按记录展示；MR事实保持MR粒度。走查日期取记录创建时间 |
| issue_response | issue | 问题调研、计划解决日期与计划合入版本；一议题可有多条响应 |
| issue_reply | issue | 缺陷修改、原因与影响信息；覆盖系统测试实际使用的第1至9项，一议题可有多条回复 |

新建和编辑按发布规格校验有效业务字段，补充说明选填。条件不适用时保存明确结构值或NULL，条件说明随控制项启用；历史记录不因新版增加字段而产生待补项；编辑时按记录所属版本的必填和条件规则校验。完整字段、条件和取值见§3.2。

缺陷原因执行[D-30](../decisions.md#d-30-缺陷原因采集与合规判定分层)：采集保存真实原因集合，合规按Wiki的适用范围和状态检查唯一性，统计使用既有原因个数口径。实现分别承担字段校验、唯一性判定和统计投影，使用稳定具体原因ID。合规计数器按具体原因ID去重计数：同一具体原因的历史别名归并为同一ID，一级大类只作分类不参与计数；去重后具体原因数为0或大于1判为非法，尚未判定保持待检查，不把多条采集值截断或猜测主因。产出影响与验收见§4.1。

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
| FR-C3 | 原版本留存、原版维护、项目版本标签 | §2.2、§3.4 |
| FR-C5 | 相同业务字段跨版本统计，差异字段不强并 | §3.5 |
| FR-C6 | 按版本导出获准范围内的完整有效记录 | §3.8 |
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
| D13 | 2026-10-26已替换：历史记录原版本留存，不随结构发布搬迁，§2.2、§3.4 |
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
| B4 | 连续性保留，展示方式于2026-10-26改为读取记录所属原版，不再补入新版字段，§2.2、§3.6 |
| B5 | 已确认作废删除语义，§2.3 |
| B6 | 已确认走查创建日期，§2.3 |
| B7 | 已确认管理员页面字段增删改和退役，§3.4 |
| B8 | RS未知范围暂缓，接入该范围时补证，第五章E3 |
| B9 | 当前无同名不同人业务场景，沿§3.2稳定ID实现 |
| B10 | 已确认管理员管理，§2.1、§3.7 |
| B11 | 已批准条件必填及现行日期/分支值域，§3.2 |

## 三、系统实现设计

### 3.1 存储层设计

#### 元数据、可读版本与记录定位

物理表名继续为`form_s{scope_id}_v{version_id}_instances`、`form_s{scope_id}_v{version_id}_records`。业务值参数化，物理标识符只由服务端已校验目录生成，不从用户输入拼表名。

| 数据对象 | 当前职责与新版约束 |
| --- | --- |
| form_template_definitions | 三模板定义、资源类型和种子规格 |
| form_template_scopes | 冻结绑定、current_version_id、可读版本集合修订与哈希；当前指针只管理新建入口 |
| form_template_versions | 冻结field_spec、词典/语义哈希、表名和发布证据；区分权威记录源、历史副本及未发布目标 |
| form_record_locations（新增） | record_id全局定位、scope内跨版requestKey唯一；分开origin/storage版本、实例模式、完整资源C、创建归属及首次规格/载荷证据，与物理记录/审计/head同事务登记；精确列/约束归N0§1.3 |
| 各版本instances/records | 本版本原始记录及资源关联；记录保存真实业务列，不把所有版本塞入一个JSON正文列 |
| form_record_audit_logs | CREATE/UPDATE/VOID不可变操作快照，包含实际所属版本和记录修订 |
| 管理草稿、预览、发布及attempt | 保留CAS、冻结预览、持久阶段、租约和恢复；不再把复制计数/校验和当普通结构发布前置条件 |
| form_change_heads / form_resource_authorities | 记录变更交接和资源/模板唯一FORM或COMMENT权威；权威不按表单结构版本重复创建 |
| 旧迁移日志/切换清单 | 保留已发生事件的审计证据；新字段发布不再伪造历史迁移日志 |

记录定位目录的record_id全局唯一；非空`(scope_id, request_key)`跨该范围全部版本唯一，NULL仅用于真实无原请求key的历史行。目录中的版本必须属于同一scope，实际记录与目录一一对应。目录用于路由、防重，不另存一份可独立变更的业务内容或创建人权限。原生版本的instance_id必须指向该版真实实例；只读旧Demo没有物理实例时可按完整C登记，不伪造实例ID或记录修订。

新建时同时插入目录、物理记录、审计和变更head；任一失败整体回滚。编辑、作废不搬表、不换record_id，不因新版本发布改目录归属。读取先按目录定位，再到所属表核对C/本人归属，不能依次猜表或仅凭相同数字ID命中另一版本。

#### 可读版本集合与已有副本

范围的可读版本集合包含已登记为权威记录源的已发布版本；每次集合变化递增`source_set_revision`并更新规范哈希。新版本发布后追加空权威记录源；此前原生版本保留，current_version_id切向新版本。结构、字段和词典元数据冻结后不可原地改写，新增变化必须发布新版本。

v5.1曾复制出的旧表是历史副本，不能直接把所有`form_s*`表UNION起来。若现场已有该类数据，核实当前唯一承载副本并登记目录，旧副本保留为审计快照、排除普通查询/统计/导出；不逆向拆回旧表，也不再复制到新版。必须验证一条逻辑记录恰好一个权威位置。

旧Demo表S的首次接入也遵守“不搬旧记录”：登记其真实来源、范围、冻结旧规格和定位信息，纳入统一版本读取；不保留旧HTTP写入口或旧业务Service双轨。登记前须确认旧写入口停止、选集稳定，平台栅栏不能代替旧后端真实停写；登记前后核对原行计数/哈希，变化即回滚登记。缺创建账号的记录只读。无法无损证明ID/归属唯一时阻断登记，先提交E2核对及技术方案；不能擅自复制数据绕开该限制。

#### 初装、升级与结构校验

Flyway只安装/升级基础能力、权限及种子定义，环境绑定和记录源登记走受控管理操作。新增定位目录、集合修订、版本源角色/实例模式、目录事件及高水位的正式列/约束已由[N0§1—§3](v6-n0-technical-admission-20261027.md#n0-fifth-review)五审定型；只追加前向迁移，禁止改动已登记159条旧迁移与checksum，禁止为切换设计删除真实旧表。

表结构按各版冻结规格校验真实列类型/空值、主键、唯一键、外键及动作、CHECK逻辑分组、索引及共享序列默认值。保留已验收的同物理连接临时约束解析方式。新版本表按新记录即时约束创建；旧表按原规格校验，不与最新版种子硬比较。

正常字段发布不复制记录，也不为“新增必填”批量给旧行加缺值标记。已有v5记录上的legacy标记仅作为其原有结构/审计事实处理；原版编辑按该版要求补齐，作废仍不做字段完整性校验。历史记录全局ID高水位登记只前进，不能退序列。

### 3.2 三模板字段规格与条件契约

field_spec是校验和渲染的唯一字段定义，`spec_version=1`冻结编码规则。稳定snake_case键与API camelCase键使用显式绑定，中文label只用于显示。规格分别声明控件、物理列、类型、长度、条件和词典版本。

#### code_review

```json
{
  "spec_version": 1,
  "template_key": "code_review",
  "fields": [
    {"name":"reviewer_user_id","apiKey":"reviewerUserId","label":"走查人","type":"string","required":true,"maxLength":128,"control":"person-select","optionsSource":"platform_ldap_users","storage":{"idColumn":"reviewer_user_id","nameColumn":"reviewer_name","columnType":"varchar(128)"}},
    {"name":"review_duration_minutes","apiKey":"reviewDurationMinutes","label":"走查时间（分钟）","type":"integer","required":true,"min":0,"max":2147483647,"control":"number","storage":{"column":"review_duration_minutes","columnType":"integer"}},
    {"name":"specification_count","apiKey":"specificationCount","label":"规范","type":"integer","required":true,"min":0,"max":2147483647,"control":"number","storage":{"column":"specification_count","columnType":"integer"}},
    {"name":"logic_count","apiKey":"logicCount","label":"逻辑","type":"integer","required":true,"min":0,"max":2147483647,"control":"number","storage":{"column":"logic_count","columnType":"integer"}},
    {"name":"performance_count","apiKey":"performanceCount","label":"性能","type":"integer","required":true,"min":0,"max":2147483647,"control":"number","storage":{"column":"performance_count","columnType":"integer"}},
    {"name":"design_count","apiKey":"designCount","label":"设计","type":"integer","required":true,"min":0,"max":2147483647,"control":"number","storage":{"column":"design_count","columnType":"integer"}},
    {"name":"other_count","apiKey":"otherCount","label":"其他","type":"integer","required":true,"min":0,"max":2147483647,"control":"number","storage":{"column":"other_count","columnType":"integer"}},
    {"name":"supplement_note","apiKey":"supplementNote","label":"补充说明","type":"string","required":false,"maxLength":8192,"control":"textarea","storage":{"column":"supplement_note","columnType":"text"}}
  ],
  "conditions": []
}
```

API输入为`reviewerUserId/reviewDurationMinutes/specificationCount/logicCount/performanceCount/designCount/otherCount/supplementNote`，与上表逐项绑定。五项count表示问题数量，时长单位为分钟。

走查人使用可搜索人员下拉，默认本人，可改选他人。候选来自platform_ldap_users，服务端验证user_id存在且real_name非空，保存该次权威姓名快照。客户端只提交ID，reviewerName由服务端生成。表中仍保留且正名有效的离职人员可选；表外人员按缺候选报告。会话username用于创建账号及本人预填。

#### issue_response

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

`problem_type`与GitLab议题类别及需求身份标签独立。负责人确认保存填写内容及会话创建账号，适用条件使用提交时核验的资源快照，并写入`owner_confirmation_applicable`使条件可审计；一级缺陷必填确认内容，其他级别存NULL表示不适用。资源后变一级而确认缺失时由合规规则报告缺失，不改写历史记录。计划解决日期为业务日，JSON为`YYYY-MM-DD`，现行年份2020—2040，扩大值域须另行确认。计划合并版本分支是计划分支业务值，不是结构发布标签；沿`IssueResponsePlanFieldRules`严格解析，一个或多个`CCyyyyRn`以`&`分隔，历史原值保留。

#### issue_reply

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

`fix_status`对应已解决/部分解决/申请延期/无法复现，GitLab状态沿来源标签读取。`cause_keys`非空、去重、命中冻结词典，保存真实多选，不截断、不猜主因；合规“缺陷原因不唯一”在适用门槛下单独计算，不反向修改采集值（D-30）。原因词典复用中立`DefectCauseMetricCatalog`的稳定key及历史等价映射，结构版本冻结词典内容和版本；`canonicalSha256`按`name/version/items`的紧凑JSON计算，来源字段只作追溯元数据、不参与哈希。一级大类由具体原因派生，模板969“需求理解偏差”等历史显示文字与正名的映射逐项留证，“编译打包问题”等跨大类差异保留原始映射证据。`affected_kinds`可同时选KNOWN/NEW，NONE与二者互斥；控制项关闭时前端清理不再适用的草稿值，服务端拒绝矛盾载荷。`relation_table_updated`为第9项，必填；历史8项回复属于COMMENT来源，不导入历史评论，这与既有平台表单记录的原位版本登记是两件事。历史值不伪造；新表单记录从首次发布后产生，后续结构发布不搬动既有记录，历史记录按§3.2末节保留并维护原版结构。

#### 共用输入边界和JSON示例

多行说明按8192个Unicode码点校验，人员ID/姓名128码点；请求体按UTF-8计128KiB，超限返回413。整数仅接受0至2147483647的JSON整数，拒绝小数、字符串、负数、溢出或空必填值。未知字段及创建账号、姓名快照、deleted、来源版本等只读字段返回400。以上长度为本稿技术预算，随发布规格维护并在目标样本上核验。

文本保留原文，允许普通链接和Markdown文字，读取时安全呈现；本期字段控件覆盖人员、整数、日期、文本、单选、多选及布尔。附件上传、新控件和新业务计算作为能力开发。适用业务字段的空白检查使用去空白判空，保存值及指纹仍按§3.3编码。

响应请求示例：

```json
{"gitlabBaseUrl":"https://gitlab.example.com","projectId":"325","resourceType":"issue","resourceId":"12","templateKey":"issue_response","scopeId":"2","storageVersionId":"4","requestKey":"550e8400-e29b-41d4-a716-446655440000","fields":{"problemType":"DEFECT","problemCause":"已确认原因","modificationPlan":"按方案修改","ownerConfirmation":null,"plannedResolutionDate":"2026-11-01","plannedMergeVersionBranch":"CC2026R4","supplementNote":null}}
```

示例ownerConfirmation为NULL仅在当前资源非一级时有效。三模板分别安装完整field_spec，字段表中的SQL date、text[]、boolean和枚举映射直接落实到真实列。原始记录内容存真实列，JSON用于规格、映射及审计。

#### 真实列与所属版本校验

上述规格是三模板的初始定义，不因新版本发布而追改旧版本。业务列、长度、枚举、条件及人员双列绑定均从该版本冻结规格生成；新版本数据只受该版规则约束。字段名与物理列的映射由系统维护，条件中的字段引用必须解析到真实列及类型。

条件编辑、服务校验和数据库CHECK使用一致语义：互斥只限制配置的具体组合，不能用“选中触发项后总数大于1”代替`withAnyOf`；条件必填的目标类型、操作符、字典/选项和快照列必须在草稿/预览边界验证。对不支持的组合显示业务原因并阻断，不得预览无阻断却在建表或保存时才失败。

历史记录在原表直接维护。新版新增必填、删除字段、改变条件均不重新解释旧记录；编辑旧记录只校验其所属版。Wiki合规与统计业务规则仍独立执行D-30，不因为旧版允许多选就免除唯一性判定。结构版本冻结的是采集规格，不是让历史记录豁免已确认业务检查。

### 3.3 服务端接口、幂等与并发控制

#### 记录端点与上下文

基址`/api/collect-forms`。所有记录增量提交，不携带整页快照；缺失另一条记录不构成删除指令。填写端bigint身份使用十进制字符串，recordVersion为数字；管理端scope/version/publication既有数字契约保持，不做全局Jackson转换。保留既有MR/Issue外链及notification-payload真实外部契约，旧内部save/delete/update-record不恢复。

| 方法/路径 | 版本与写入边界 |
| --- | --- |
| GET `/detail` | 按C返回当前新建版本、获准的各版本规格及全部有效记录；每条携带所属storageVersionId、recordVersion和本人操作能力，GET不建实例 |
| POST `/records` | C、scopeId、当前新建storageVersionId、requestKey、confirmSameReviewer、fields；返回CREATED/REPLAYED/CONFIRM_REQUIRED |
| PUT `/records/{id}` | 按目录实际承载版定位；C、expectedStorageVersionId、recordVersion及fields，校验本人并CAS，不要求该版仍是当前新建版本；originVersionId只保留创建事实 |
| POST `/records/{id}/void` | 原版本人作废，二次确认及输入“作废”；不校验业务字段完整性，审计/head同事务 |
| GET `/reviewer-candidates` | 保持远程人员ID/权威姓名、关键字和有界分页，重放不依赖当前人员目录 |
| GET `/notification-payload` | 保持已发布来源地址、项目、IID和资源类型契约，实际外部变更按E1核对 |

detail正式目标响应按N0§6：`currentVersionId`、`readableVersions`、按versionId字符串索引的`versionSpecs`、带storageVersionId/originVersionId/recordVersion的`records`及`sourceSetRevision/sourceSetHash`；新版DTO及草稿升级已准入，尚未实施。客户端不能以根级“当前field_spec”渲染所有历史块。版本筛选只能缩小获准范围，不能隐藏默认应可访问的历史记录或漏掉消费输入。

C继续为来源Web基址、项目、资源类型、IID、templateKey；服务端匹配已登记来源并核对项目及镜像资源，不从请求猜来源实例。MR/Issue、响应/回复、同数字IID的不同来源严格隔离。资源待同步保留输入并提示；已有合法记录的读取不依赖镜像当前RUNNING/FAILED。

#### 新增幂等与同人提示的六步事务

1. 认证、权限、C与请求边界核验后，按共享序列咨询锁→范围共享锁的统一次序进入新建/核验事务，再先按scope/requestKey查全版本目录；命中则按首次请求版本的冻结规格规范化指纹，核对账号、C、首次载荷，返回同一记录在实际所属版本的权威状态。重放不再查询当前人员目录，使用已保存姓名与首次证据；作废重放不得复活，异账号/异载荷拒绝。
2. 未命中才校验新建版本仍等于current_version_id、范围ACTIVE且可写；未提交的旧版新增请求返回409/A0409并保留输入，不能静默改写版本和key。当前版本变化不拒绝一条已经成功创建的旧key核验。
3. 按冻结规格校验字段、条件、人员权威姓名并形成首次规范指纹；空/集合/日期及长度规则沿现有契约。首次指纹沿真实v5规范JSON顺序，版本分量取origin_version_id，含账号及完整C；origin_spec_hash另作规格证据，不改写历史载荷摘要，后续编辑不重算首次指纹。
4. 同走查人软提示查询同C各权威记录源的有效记录；未确认返回CONFIRM_REQUIRED且不插记录。没有占用、预约或先建空记录。
5. 在已持有的共享记录ID序列咨询锁、范围共享锁内再次复核状态；分配全局ID，插入本版实例/记录、跨版本唯一目录、CREATE审计和资源head。约束竞争后按原key返回唯一结果，不能每版分别成功一次。
6. 提交后返回CREATED或REPLAYED；任何失败目录、记录、审计、head整体回滚。持锁等待后的版本和权限复核使用当前真实状态；响应丢失由同key同快照核验收敛。

#### 编辑、作废与CAS

定位目录和所属版本是记录路由依据；请求带错误所属版不能改写另一条同号记录。正常ACTIVE范围的历史记录仍允许有权限的创建人按原版编辑/作废；无创建账号、停用账号或范围整体退役沿原只读规则。编辑不改变创建时间、origin、所属版和首次幂等证据。

UPDATE按id、created_by、record_version及deleted条件CAS；成功递增recordVersion并写UPDATE审计/head。作废只更新deleted/recordVersion/updated_at，二次调用不重复审计。作废记录排除读取、筛选、业务统计与普通导出，后台审计保留。

#### 并发、失败与长时停留场景

| 场景 | 必须行为 |
| --- | --- |
| 多人同时新增/各自编辑 | 各块、各记录、各key独立，不提交整页或覆盖他人记录 |
| 甲先打开、乙先保存、甲后保存 | 甲只写本条；乙记录不会回到甲打开时的旧状态 |
| 同记录双标签编辑/作废 | recordVersion决定成功者，另一方保留输入并比较，不能静默覆盖 |
| 用户只展开下拉框 | 不创建记录、不占用人员、不触发他人列表更新 |
| 发布V2时V1记录正在编辑 | 原记录继续按V1维护；发布不改其记录版本、字段或数据位置 |
| 发布V2时V1新增草稿未提交 | 保留草稿，确认原key结果后按新版本核对；不伪装成已保存旧记录 |
| V1创建成功响应丢失→发布V2 | 同key核验仍返回V1原记录，不在V2重复新建 |
| 管理员保存/查询/发布刷新迟到 | 每次回写核对原范围、编辑会话/加载代次、输入修订；失败与冲突二次读取也不能跨范围装稿 |
| 定位目录或源集合不完整 | 明确错误并阻断相关读取/提交，不猜当前表、不返回漏数的成功结果 |

### 3.4 管理员字段管理与受控版本发布机制

#### 页面、稳定字段与映射

独立“表单管理”页面按选择表单、编辑字段、查看变化、发布的单页主线组织，字段卡片与常驻摘要同时可见，范围与历史从次级入口进入。交互、信息层级和管理页确认边界归产品文档，实施须保留[用户已批准UI基线](../product.md#form-admin-approved-ui)。管理员维护字段名称、说明、填写方式、顺序、必填、条件及选项。领导提出变更，管理员操作，无额外在线审批流；普通用户菜单不可见且直接请求403。

人员选择、单行/多行文本、数字、日期、单选和多选均按业务名称展示，真实三模板种子中的控件组合也必须正确回显。内部字段键、接口键、物理列、存储类型、JSON、SQL和DDL不作为日常管理编辑项或折叠操作入口；工程证据保留在后台及审核材料。

系统生成不可复用的稳定字段/选项身份。删除后新增即使同名也获得新身份，显示改名沿用原身份。管理员若改变实质含义/单位，应新建字段而非借改名复用；系统不能只看标题猜测语义等价。

预览按业务名称显示新增、名称/填写方式/必填/选项/条件/顺序调整、删除及影响。必须明确“仅影响新版；历史记录按原版保留”，不再宣称给全部历史补空列或删除旧值。条件配置需与实际保存/DDL完全一致，支持边界见§3.2。

草稿未发布不影响填写；CAS冲突保留本地稿及修订基准，展示权威稿供用户明确选择。所有异步响应绑定原编辑上下文，不能在回调中用当前scope猜原请求；最终刷新也要在应用结果时保护更晚输入。管理页确认遵循产品文档，填写页确认仍遵循§3.6。

#### 管理端接口

保留templates、template-scopes、draft、preview、publications、recover、retire、binding-options和version-milestones的职责及form.template.manage鉴权。内部DTO按v6直接改造所有调用者，不保留废弃接口别名或旧复制分支。

预览冻结draftRevision、当前指针、绑定hash、可读版本集合修订、目标空表规格/哈希与真实版本证据，列出旧记录留存及共同字段变化。发布同scope/requestKey同首次载荷返回原任务，不能要求当前指针或草稿仍与创建任务时相同；异载荷409，页面重试复用key。

#### 普通结构发布与失败恢复

1. 准备：按范围→任务顺序核对管理员、草稿CAS、绑定、预览与版本标签；冻结请求并分配目标版本。当前记录源不变。
2. 建立：在独立受控事务中生成空目标实例/记录表、约束和索引；校验完整结构，无历史行复制、转换或truncate。目录/目标非本任务所有时拒绝覆盖。
3. 验证：目标结构和冻结哈希一致、目标为空、绑定仍一致、指针及集合修订未漂移、租约有效。校验不能仅比较约束数量或列名。
4. 激活：短事务核对版本及目录CAS，登记新权威记录源、更新source_set_revision/哈希、切换current_version_id，写发布审计和范围变更信号。旧版本和旧记录不改写，不把全部旧表转只读。
5. 收尾：返回“新版本已发布；历史记录保留原版”，业务派生更新与发布成功分别反馈。失败时旧入口/旧数据继续可用。

保留持久任务、attempt、租约/fencing、归属token及恢复证据；v2阶段已定为VALIDATING→BUILDING→SWITCHING→PUBLISHED/FAILED；BUILDING不设范围写栅栏，SWITCHING按受控enter/apply及过期接管短事务收口。protocol=1旧任务先停旧执行器并受控收口，不由新执行器领取；精确DDL/attempt/fencing归N0§4。所有持久副作用在统一锁序下复核owner/fencing/stage及`clock_timestamp()`租约，提交前复核；过期持有者不能写失败或清别人栅栏，独立维护回收。statement_timeout只限制单条语句。

正常发布无需对全部历史长时间停写；短切换锁协调新建/原版编辑与目录发布。在途旧版新增先提交则登记为旧版记录；切换先提交则未落库的旧版新增收到版本变化。旧记录编辑依据自身所属版，不因新建指针改变而失效。

恢复只处理本任务未发布空目标及元数据。完整结构漂移、非空目标或未知写入时停止，不能truncate有记录的表来求绿。旧v5复制任务须在新版实施前排空或明确终止并留证，历史任务/日志只查不再由新执行器领取；不让两种运行版本共驱同一平台库。

#### 初装、旧记录登记与版本标签

空平台从种子草稿→绑定→预览→建立空结构→激活完成闭环，首条成功新增才创建资源实例。既有表单平台从已核实权威表登记版本源和记录定位目录，保留原表；v5历史副本按§3.1去重，旧Demo S按E2核实后原位登记。登记允许元数据与定位索引写入，不允许复制或重写旧记录业务列。

登记守恒核对为：原物理行/字段值不变、权威有效记录总数不变、每条记录唯一归属、目录覆盖完整、旧副本零重复参与。真实无法定位或归属冲突即阻断，不以自动回填身份或搬数据处理。历史记录没有发布标签/创建账号的真实空值保留。

后续字段发布冻结绑定，来源、项目转移是另一项独立受控业务，不借字段发布完成。版本标签按真实项目里程碑及已有SP/组合标签规则核验，非空标签和可核查证据仍是发布前置；字段未变的产品发版不自动建新表。ODS未就绪须补同步或核实获准依据，不能编造版本。

### 3.5 下游消费适配与权威来源切换

#### 持久变更与多版本完整发布

FORM首次事实与周期SLA统一经过类型化业务输入，raw_payload仅供COMMENT解析；延期写回复用原队列与轮次授权，增加表单证据hash/未消费head校验。MR逐记录投影替换FORM的旧Demo CTE，正式统计按完整有效记录计算，不逐列max或仅保留最新记录。业务启用按模板分批：Issue先用FORM，MR暂用评论，外部数据与筛选对齐后再启用走查；非法页整页保持旧源。具体消费点、字段和断言见[接入方案](unified-form-adoption-review-20261030.md)。

记录、定位目录、审计和form_change_heads同事务；head按资源/模板聚合变化，不按每张版本表各建一个相互独立的消费结果。新增、编辑和作废仍递增变化版本，保留未消费变化，不用易丢的内存通知替代持久交接。

消费候选固定：可读版本集合修订/哈希、各版字段/词典规格、资源head变化版本、FORM/COMMENT类型及authority_revision。构建期间任一证据漂移则放弃候选。结果公开与head结算同事务，在统一顺序下先锁所需范围、再直锁head基表并按ID升序，复核上述证据；租约无效、版本集合变化或权威修订不符，零公开、零结算，重新领取构建。

单一pending_storage_version_id不能代表多个真实存储源。v6采用N0§5已批的form_head_captures伴生表及受锁范围集合修订/哈希，捕获token/owner/fence/租约与head/pending/权威逐项复核；正式列、索引和短事务锁序已定型，不能把当前指针包装成“多版本支持”。FORM无实例资源也必须覆盖目录/权威变化；COMMENT不携带伪造FORM证据。

目录发布写持久范围变更信号；确需下游重建的范围按既有任务机制处理，不把全量历史行处理塞进DDL事务。无记录变化不能伪造CREATE审计。写回只使用完整公开且GitLab/表单输入均收敛的结果，开关、租约和写所有权边界不变。

#### 相同字段跨版本总体统计

统计明确选定的版本集合V、业务范围及时间条件。系统按稳定字段身份、实际业务含义、类型/单位、枚举/词典含义和既有聚合口径确定共同可统计字段；不是按中文显示名或同名物理列直接相加。含义和单位不变的改名仍可合并；删除后新建、同名异义、单位/不可比较取值变化不合并，不自动换算或猜主因。 可比valueType绑定该版真实冻结field.type，并核对版本语义声明；冲突在发布前阻断、读取时明确无效，不以种子默认类型替代或以排除非数值返回部分成功。精确身份/域/类型/单位交集及聚合合同归已批N0§7；不开放现有管理页跨类型编辑。

例如V1有“规范、逻辑”，V2有“规范、设计”；两版总体统计合并“规范”，不把逻辑与设计拼成同一项。V1规范2、V2规范3的既有求和指标得到5。差异字段仍能按各自版本查看/导出，不作为该次共同字段指标。没有共同可统计字段时明确说明，不能返回0假装完成统计。

参与版本、共同字段、未纳入字段及原因、匹配记录数/有效样本数必须可说明。字段不存在、空值、0分别处理，不把缺字段补0；平均值、比例、去重人数等沿已确认业务公式，不用各版本结果的简单平均代替总体分子/分母。文本字段不自动变为数值指标。

普通业务统计只计未作废记录；一个record_id仅出现一次。聚合前先限定原业务来源、模板及scope/项目权限，不跨MR与Issue或仅凭同名字段合并不同业务域。多版本属于一个FORM来源，不形成“每版一份事实再重复叠加”。本期复用现有业务消费和统计页面，不另做通用指标设计器。

#### MR走查与既有事实适配

记录级非法数据保留记录所属版本及稳定ID；MR事实仍保持MR粒度。盘点原form_records CTE的时长、走查日期、owner_name/reviewer_names、状态/速率/效率、检索、标签规则、工作区、统计和导出，把读源统一改为全部权威版本记录。

SQL/Java类型与真实物理列匹配；10+20=30、无表单NULL、真实0、上限及溢出均用真实PG验证。MR级尚未确认的汇总公式继续在W5前交影响说明，不因版本模型变更自行选择新分母或漏掉旧版记录。差异字段无可用业务输入时返回缺项/不可计算，不能隐式回读评论或编造0。

#### Issue九项指标选择器

| 消费项 | 选择依据 | 结构化消费契约 |
| --- | --- | --- |
| planned_resolution_*/planned_merge_version_branch | 跨全部权威版本选最新响应整份，不逐字段补旧 | 按(createdAt,recordId)降序选未作废响应的当前修订；编辑不把旧记录改成新一次响应 |
| research_template_time/响应周期 | 跨全部权威版本的第一条调研响应创建时间 | min有效响应createdAt，保留来源时间；编辑不后移；回复独有时不制造响应效率样本 |
| has_response | 表达响应模板存在 | 与回复存在、延期豁免分开 |
| 响应延期豁免 | D-21：有效回复存在 OR 响应有合法计划时间；响应从新往旧查 | 回复存在即豁免；仅响应存在时检查合法计划，缺签等完整性问题由合规规则单独判断 |
| 原因、非法唯一判定、原因统计/导出 | 跨全部权威版本的最新有效回复按创建时间选，编辑不重排；多选采集与唯一性合规独立 | 按稳定具体原因ID集合投影；合规计数器按具体原因ID去重（别名归并同一ID，一级大类只作分类），0或大于1判为非法，尚未判定保持待检查；字段、合规与统计按§2.3及D-30分别执行。 |
| fix_user | 现有SQL取最新匹配回复作者；B3不另造代填流程 | 取被选有效回复创建账号姓名快照，来源见§3.1；记录归属沿§2.1 |
| known/new affected与回复详情导出 | 从被选回复的独立字段投影 | 新结构直接读类型字段；历史未知为“--”、未选为“否”，两者不同；不再回解析新表Markdown |
| resolve_deadline/解决延期 | B2确定响应选择按创建时间最新；D-21合法计划查找及18天公式分别保留 | 按§2.3的记录顺序选择；旧反向循环取最早的差异列入产出影响及I2回归 |
| fixed_label_time/解决周期 | 完成状态及真实加标事件，沿D-20/D-27及当前已确认状态单元 | 完成取真实事件时间，两端统一为同一瞬间 |

系统时间由可注入UTC时钟写入，新表timestamp约定UTC naive，页面按上海时区显示。计划解决日期为业务日，由SLA规则显式解释截止时刻。历史走查时间登记前核验来源语义，读取时保留原值与真实时间含义，不借结构发布改写。

Issue沿[D-27](../decisions.md#d-27-客户活动时间按列固定语义完成事实投影显式使用上海时区)逐列解释：created_at_source为已核UTC墙上值，fixed_label_time在事件到事实边界投影为上海墙上值，计算两端先还原为同一瞬间。完成状态和真实加标事件沿D-20及当前状态规则，18天公式沿既有定义。计划/首次/原因等字段独立选择，编辑及作废使用各自有效集重算。

消费影响至少包括Issue非法标志/说明、客户计划字段/筛选、响应及解决效率、原因和影响功能导出、周期延期及写回资格。reason_category、fix_user、has_response、research_template_time、planned_resolution_*、planned_merge_version_branch、resolve_sla_days/deadline及两个延期标志逐项核验。

每个指标先确定完整跨版本候选集，再按既定选择器取值；不能每版各选一条后相加。编辑旧记录不改变其创建顺序，作废后重新在全部有效记录中选择。最新整份响应缺字段时保持缺项，不从另一版本拼出一份从未填写过的响应。

#### 按批次划分COMMENT与FORM权威

form_resource_authorities以完整来源/项目/资源类型/IID/templateKey唯一登记，保存scope_id、authority（COMMENT/FORM）、authority_revision、cutover_manifest_id和effective_at。读取器必须取得已登记权威；FORM无记录表示未填写，按其业务规则产生结果。

切换清单冻结来源、项目批次、模板、明确切换时刻、资源判定依据、规则版本和操作者。按以下规则执行：

1. 按模板业务启用点持久登记权威：Issue在T后创建为FORM，历史Issue为COMMENT；MR在T_mr未设置时仍COMMENT，外部数据与筛选验收后设置T_mr，仅其后创建的新MR为FORM。历史资源后补评论、关闭重开不改变创建批次；新FORM缺记录不从评论补值。
2. 既有collect_form_records的S按§3.4原位登记，其MR/模板在初始清单中显式登记FORM。其它MR按批次与资源清单确定权威。旧评论批量导入不进入本期。
3. 资源到达镜像时按已批准批次规则持久登记权威，再允许对应消费与新表单写入。COMMENT资源页面标明沿用评论流程；写入接口核验FORM及authority_revision，避免新记录落在未切换消费路径。
4. 权威用CAS推进，变更与head同事务登记。结构发布只追加版本目录并切换新建入口，来源转权独立留证；范围编辑不暗中改变历史资源权威。
5. 切换前停用对应旧写回所有权；新读取、周期任务、导出及标签写回统一使用已选权威。两边按同一资源/模板分别只读一种输入。

COMMENT保留仅服务已划定历史资源。退出须等历史数据另行获批归档/转换且逐项核对完成，不与外部扫描平台接入时点混同；本期以切换清单约束边界，禁止按缺字段回读旧评论。保留历史解析不代表恢复老平台延期写回；已上线新平台的写回队列、来源开关与所有权继续保持。

#### 真实启用验收（2026-10-30补充）

表单完成不是只验证填写页或记录API。启用前须核对CI_ROBOT真实投放与认证、来源/项目/模板切换批次、新旧资源权威、历史评论样本、事实追平与消费者清单。新Issue响应/回复必须同时进入首次事实、周期SLA和已上线延期队列；计划编辑/回复作废后重算，最新表单变更未消费或授权版本漂移时不得发旧标签。MR正式统计/下钻/导出使用表单；非法页明确保持旧边界。隔离环境先用替身或获准测试实例验收链路，正式批次另经E1/E2/E6及发布门禁，不能随N4单元通过自动开通真实写回。详细盘点与当前源码证据见[接入评审](unified-form-adoption-review-20261030.md)。

### 3.6 前端交互与本地草稿生命周期

#### 页面入口与记录展示

保留`/external/code-review-form?gitlabBaseUrl&projectId&mrIid`，显式转换为MR上下文。统一入口为`/external/forms?gitlabBaseUrl&projectId&resourceType&resourceId[&templateKey]`。Issue省略templateKey时列出已登记响应/回复入口。

CI_ROBOT自动投放稳定链接，链接携带资源上下文和必要模板键；由平台解析环境来源和范围；当前版本用于新建，已有记录按各自所属版本读取。机器人可给Issue统一入口或分别给两模板链接，按E1核实实际调用。notification-payload若有外部调用，其参数/认证变更按外部契约迁移。机器人账号仅是入口发布者，创建人见§2.1。

填写页按已发布field_spec单列渲染。新建块使用当前版本；已保存块及编辑块使用该记录所属版本。顶部显示标题、对应MR/Issue链接和加号，各块标明表单版本；不同版本的字段不能以当前版本字段表覆盖。响应和回复分别新增独立块。保存块默认只读，显式进入本人编辑；显示填写人、模板、产生标签及创建/更新时间，编辑后用灰色小字提示。历史姓名未知如实显示。

修改非最新Issue记录时显示“修改此记录不会替换最新记录”；修改最新记录的计划字段影响当前值。用户需提交新一次计划时可新增响应。作废交互执行§2.3的双重确认。

页面区分未登记模板、资源待同步、不支持类型和COMMENT历史流程。detail覆盖全部获准记录版本并携带所需版本规格；按单资源跨版本真实负载测量响应和渲染；超预算再评审分页或分段协议。

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
| 管理变更 | 仅发布、退役、删除已有字段确认；具体规则归产品文档，不把填写页离开保护扩展成管理页多重弹窗。发布确认以已核验预览为准 |
| 主动退出且存在未提交内容 | 提示退出会清除本平台各标签页草稿，确认后执行退出及清理 |
| 离开尚未提交的填写页 | 显示各块的草稿保存状态和尚未提交事实，用户选择继续填写或保留草稿离开；丢弃草稿是单独的明确操作 |

“已修改”按可编辑字段与初始值/上次正式保存值比较，忽略焦点、展开状态等交互状态。修改后改回原值不再标记业务修改；空白新建块的系统默认值和请求标识不触发离开确认。任一块有实际未提交内容或未确认的提交结果，就保护页面离开。草稿已写入本机不清除“未正式提交”状态。

站内跳转、关闭填写弹窗/编辑区使用平台确认框，可显示“内容尚未提交，草稿已保存至本机”等实际状态；草稿写入失败才说明当前内容未自动保存，正式提交失败与草稿状态分别展示。刷新、关闭标签页或离站时挂载beforeunload，由浏览器展示原生确认框，文案及触发受浏览器控制。页面进程强退等情况依赖持续草稿保存恢复，不将关闭回调作为唯一保存点。已确认离开不连续重复弹相同确认，正式提交成功或显式丢弃后移除对应保护。

#### 草稿数据与恢复

每块分配稳定draftId；新增块首次生成requestKey并立即保存。草稿按账号+C+editingSessionId+draftId存储，editingSessionId区分标签页/编辑会话。草稿包含`draftSpecVersion=1`、账号、完整C、scope/storageVersionId、draftId、editingSessionId、kind、编辑目标recordId、baseRecordVersion、baseFields、用户fields、新增requestKey、首次待提交快照、输入修订、已持久化修订、草稿保存时间及保存状态。

输入变化后及时合并写入本地，失焦、页面转为后台及站内离开前补写；按输入修订核对持久化结果，旧修订写入成功不能标记新输入已保存。草稿允许字段未填完，独立于正式提交的必填/合规校验，不形成他人可见记录或统计输入。本地保存依赖当前浏览器，界面使用本机含义的文字及图标。

恢复先等待权威身份及detail，只恢复同账号同C。重开可恢复原草稿，多标签恢复时为独立会话副本，保留原requestKey及首次提交快照；各活动会话互不覆盖，不将同次新增复制成新操作。已有当前输入时先供比较，覆盖前按本节确认。用户主动再次新建才使用新requestKey。他人、因缺账号/范围退役而只读或已作废目标的本人草稿内容仅可查看/复制，不能再次提交原目标。

当前新建版本变化时，只将未正式保存的新增草稿标为需核对，保留输入；原有记录的编辑草稿继续使用记录所属版本。模板范围退役时停止写入并保留输入。结果未知的新增先按原key核验，不允许绕过核验复制为新提交。冲突保留baseRecordVersion和用户内容，加载权威文本供比较，用户接受后建立新编辑草稿。人员候选失效、Issue级别变化等提交时条件变化定位具体字段，保留其它输入并提示处理；恢复与比较不自动改写正式记录。

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
| 编辑结果不确定 | 重新获取detail | recordVersion=baseRecordVersion+1且可编辑字段等于提交快照则提示已保存；其它情况保留并进入比较 |
| 已保存 | 列表reload失败 | 显示“保存成功，列表刷新失败”，允许单独重载；保持保存成功状态 |
| 保存失败/冲突 | 400、403、409或网络失败 | 保留本块输入，展示原因；冲突按权威版本核对 |

#### 401、跨标签页与存储故障

复用auth-state身份代际。401转游客时保留原账号草稿，同账号重登后核验目标并恢复；换账号隐藏原账号内容。主动退出执行本节确认后清空本平台实例的全部表单草稿，写入登出代际并广播，旧标签页停写并清内存输入。迟到watch和请求回调在写入前复核账号/代际，避免恢复已退出内容。退出或取消等待不撤销已成功的正式保存，结果未知时重新登录后核验。

localStorage不可用、损坏或配额不足时按草稿失败状态展示，保留内存输入和离开保护。退出信号无法可靠传播时，身份复核失败即停本地自动保存并提示限制。草稿持久化、离开确认、提交结果分别维护；服务端草稿和实时协同留在后续范围。

#### 数据库查看旧编辑器退役

删除DatabaseBrowserView.vue的旧表操作列、编辑弹窗、状态和保存函数，删除updateCollectFormRecord API、类型、导出及维护旧入口的测试。当前及历史实例/记录、审计、迁移、发布任务和元数据均以只读表登记，记录修改统一走本人记录端点。

### 3.7 权限模型与系统门禁登记

普通记录权限继续为form.record.create、form.record.edit_own、form.record.delete_own，模板管理为form.template.manage；历史记录维护仍额外核验创建人，模板管理不自动获得代改权。无创建账号或停用账号的记录保持只读。新增form.record.export为独立导出能力，数据可见范围不得超过已有获准范围；候选授权合同已按N0§8分别从当前/默认的真实导出权推导，禁止由查看权推导；正式角色清单及跨模板授权影响仍须目标库只读核实并获用户确认，N1不提前执行导出权限授予。

权限目录、角色当前授予和默认快照同单元登记；旧code_review.form.*及旧数据库内联编辑器不恢复。端点目录覆盖新版detail、记录写入、管理发布、版本导出、所有错误/重放/历史分支及写影响表，新增统计端点遵守view/export映射、权限、工作区元数据和事实依赖四类登记。

数据库查看登记目录/定位表、各版真实表和必要审计，保持只读；管理员日常管理不展示物理标识符。新增Flyway只追加并更新checksum锁，不改既有迁移。实际制品、登记目录与接口必须一致。

黄金仅在发布打包/内网部署验收前或用户明确要求时运行。先展示新版响应、表状态及端点差异并按规则获准，再由更新模式生成快照并严格compare；禁止手改快照、改EXCLUDED/掩码掩盖回归。方案修订和当前冻结修复均不自动授权运行黄金或出包。

### 3.8 按版本Excel导出

首版提供按单个结构版本导出获准业务范围内完整有效记录的XLSX。入口使用业务模板、版本标签及筛选条件，内容保留该版本全部业务字段及记录ID、创建/更新时间、创建人/业务人员和产生版本等必要追溯信息；不要求用户从数据库查看页选物理表。

历史版只导出其权威原记录，不与新版复制合并，也不包含v5历史副本或已作废记录。V2删掉的字段仍出现在V1导出中；没有数据可导出表头和明确零记录结果。文本、日期、选项及人员姓名按原版规格/快照表达，不把旧值强行套入新版词典或丢掉未知原值。

正式目标为`GET /api/collect-forms/exports/records?versionId=<string>&projectIds=<optional>`（N0§8），只接受可读AUTHORITATIVE版本、范围项目子集和获准资源C，不能传任意表名。冻结规格字段顺序、系统列、审计和显式导出权已定型；正式角色、容量实测与黄金仍按各自门禁补齐，不能把50k/64MB待验证预算当容量结论；沿现有导出组件及安全写值机制，不引入独立报表系统。跨版本总体统计仍由数据库消费完成，不依赖人工下载Excel再计算。

## 四、测试验收矩阵与实施路线图

### 4.1 测试验收矩阵

| 范围 | 必测行为与成功判据 |
| --- | --- |
| 结构发布不搬记录 | V1有真实记录→发布V2空表，V1原行/字段/时间/hash及归属不变，V2零历史复制；V2新建只写V2 |
| 唯一记录源 | 多版原生记录各一份；v5快照副本不参与；全局目录覆盖完整且每ID一个权威位置；冲突整体拒绝 |
| 原版读写与旧链接 | 原MR/Issue链接可访问旧记录及原字段；V2新增/删字段不改V1；V1本人编辑/作废留在V1，CAS/审计/head正确 |
| 幂等跨发布 | V1创建成功但响应丢失→发布V2→原key核验返回V1同记录；新key陈旧版拒绝；目录竞争、异载荷/异账号、作废重放不重复创建 |
| 多人及多标签 | 不同记录交叉新增/编辑/作废不互相覆盖，同记录冲突保留输入；结果未知禁复制/丢弃，分叉草稿与退出保护继续有效 |
| 管理异步边界 | 保存、冲突二次读取、发布最终刷新、A→B→A、失败/取消均绑定原会话及修订；不覆盖新输入或跨范围装稿 |
| 字段与条件 | 三模板真实种子中文控件/条件；随机身份不复用；甲仅与乙互斥时甲+丙可用；不支持目标类型在发布前阻断；无JSON/物理表日常入口 |
| 共同字段统计 | 规范2+3=5；改名可合并、同名新身份不合并；差异字段不强并，缺失/NULL/0/作废及比例分母分别验证 |
| Issue时间选择 | 两版响应/回复混合，跨版最新创建/首次创建/合法计划及原因选择正确，编辑不重排、作废后重选、不逐字段拼旧 |
| 消费并发 | 冻结版本集合/规格/head/权威；目录发布、旧记录编辑、全量/自动增量/周期与结果公开交错，漂移零公开/零结算、恢复后完整重建 |
| Excel | 逐版原字段/类型/姓名/时间、仅有效记录及权限过滤，版本特有字段不丢、空结果明确；大样本预算由实测确定 |
| 初始化与恢复 | 空平台首次启用；旧表原位登记覆盖和唯一归属；漂移/非空目标/租约过期/重复发布与故障恢复不改旧记录 |
| 门禁 | 新迁移不可变、端点/权限/工作区登记、默认套件、真实PG、浏览器及规定黄金，未验证外部事项如实列明 |

### 4.2 冻结修复、技术准入与实施顺序

1. 当前员工完成已下发v5.1本轮修复，提交准确代码、证据和工作区状态；审核方按该冻结输入复核R05/R07-b等通用问题，不要求中途改造存储。
2. 完成后一次交接新反馈、v6方案、差异和失效条目；合并员工最新文档进展，不以本分支快照覆盖其交付。此前W0—W3批准只证明对应v5.1版本，已实现基础按影响复用，不推倒重写。
3. v6准入补充包已在N0五审通过（输入4cd8588d）：定位目录/请求唯一性、可读版本源与旧副本处理、原版CRUD/草稿协议、轻量发布/租约、消费集合快照与锁序、权限/导出及新迁移影响；明确可执行DDL/索引/事务、DTO及测试入口。当前仅准入N1基础实施，其余依赖阶段验收。
4. 依次完成多版本存储路由与目录、原版CRUD/幂等与填写页、空结构发布、统一消费/共同字段统计/Excel，再做真实集成；共用字段、权限和审计能力保留，废弃复制执行路径与其调用者/错误测试一并删除。
5. 新版门禁通过再准入W5后续工作、W6集成、W7黄金及W8现场。迁移日志等真实历史数据不因删运行路径而删除；已执行Flyway保留，以前向迁移安装新元数据。

不得以v5复制/守恒测试全绿代替v6验收，不保留“默认复制、可选不复制”双轨，也不以跳过旧测试求绿；测试改为验证新目标，保留仍有效的权限/CAS/归属/审计与消费正确性。精确阶段交付、审批及等待下发点见执行单。

## 五、外部环境联调与待补证事项

| 编号 | 材料或现场验证 | 用途 |
| --- | --- | --- |
| E1 | CI_ROBOT源码/配置、真实Issue链接、notification-payload调用与认证 | 核对稳定外链、模板投放和真实外部契约；自动投放职责不变 |
| E2 | 旧表有效/作废行、现有版本副本、来源/项目、人员及ID清单 | 原位登记与去重，证明物理值不变、目录覆盖和唯一权威；不再用于全历史复制验收 |
| E3 | 实际项目/阶段、版本标签依据，未知RS/DGM范围资料 | 真实绑定，当前已核范围继续按已确认边界 |
| E4 | 最终域名/路径、代理Cookie/会话、实际浏览器环境 | 同源登录、旧链接、多版本渲染和401草稿恢复 |
| E5 | 后台页面AI自动填充实现及流程 | 核对是否依赖单一当前规格，按实际调用适配 |
| E6 | 老平台其余报告、停写回所有权、部署制品与切换清单 | 独立确认整体退出和唯一写所有权；不因本次不搬表就自动批准下线 |

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
| 历史版本入镜像库 | 自有库原版本留存，记录按本人权限维护，表结构冻结 | §2.2 |
| 代码走查先行 | 2026-10-20明确本期三模板完整覆盖 | §2.3 |
| 字段管理面向内部开发 | 用户明确管理员独立页面增删改字段及退役 | §3.4 |
| 发布带版本号及可带版本URL | 项目发布标签；稳定外链汇集原资源记录，每条按所属版本定位 | §2.2、§3.6 |
| 登录态永久有效 | 复用实际会话策略，失效时保留草稿重登 | §3.6、E4 |
| 早期采用全历史复制 | 2026-10-26改为原版本留存、共同字段统计及按版本导出；同人多次软提示保留 | §2.2、§3.3、§3.5、§3.8 |

v5.0将v4.1按六部分重组，调查移入附录；v5.1将用户确认的使用场景归纳为§2.4及§3.6的记录边界、按需刷新、操作确认、草稿状态和恢复规则。v3.4提交为3a105ddf，后续业务裁定以§2.5及D-30为准，过程修订由Git历史保留。

v6.0按2026-10-26用户确认替换全历史复制设计。v5.1及W0准入材料只解释此前实施和审核记录；2026-10-27员工第三次封包已收到并复审，本次提供统一交接材料；实际吸收与准入状态归执行单。
