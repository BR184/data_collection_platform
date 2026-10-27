# 统一表单 W4：字段管理与受控版本发布 · 工作单元计划（2026-10-23）

> 本文件仅冻结当前已下发的v5.1修复轮次，保留其原验收输入，不是v6存储实施规范。员工第三次封包已收到，本次复审一起提供[方案v6.0](unified-form-system-plan-20261009.md)。新版原版本留存/共同字段统计/Excel的技术准入见[执行单交接清单](unified-form-system-implementation-plan-20261021.md#v60待下发工作单元与交接清单)；当前不新增旧复制方案工作，不自动准入W5。

## 进度与中间物

- 状态：2026-10-27 R05独立26项+3项复验通过，R01—R07全部关闭，W4冻结v5.1目标通过。N0首次审批退回，N1/W5未准入；批准范围见[执行单](unified-form-system-implementation-plan-20261021.md#r05通过与n0首次审批2026-10-27)。
- 当前下一步：本计划仅作为尚在交接的旧实现基线核对，不再安排旧复制建设；员工修改[N0合同](v6-n0-technical-admission-20261027.md#n0首次审核2026-10-27)，获批后进入新工作单元，并在旧基线不再作为活动输入时清理本计划，历史由Git及执行单保留。
- 首次整改交付摘要（历史，当前关闭项以二次整改复审为准）：
  - R01：`FormRecordWriteService.create`、`FormPublicationTxService.switchVersion` 统一为“记录序列咨询锁（如涉及）→范围行→发布任务行→head行（按id）”；新增同范围创建/切换、不同范围并行、初始迁移切换的 `pg_blocking_pids` 交错回归，并核验指针/记录/审计/head/栅栏。
  - R02：删除 `FormInitialMigrationService` 及其请求/结果模型与管理入口委托；INITIAL_MIGRATION 进入通用阶段机，复制使用管理员冻结 field_spec，旧列固定语义映射，按当前绑定全量 S 判定（空S→INITIAL_EMPTY），写LEGACY版本、切换清单、迁移日志，恢复语义不缩减；W1既有迁移测试改走新入口。
  - R03：`FormVersionStructureVerifier` 覆盖类型/空值/默认值、主键/唯一/外键动作、CHECK表达式与字面量、索引、共享序列默认值；启动、复制前与恢复共用，部分缺表或漂移均拒绝truncate/切换。
  - R04：`createPublication` 在任何草稿/指针校验前按 `(scope, requestKey)` 查原任务，按首次冻结的 draftRevision/previewId/expectedCurrentVersionId 核验；同载荷返回原任务（含PUBLISHED），异载荷409；页面重试复用同一 key 与预览。
  - R05：CAS冲突不再自动刷新覆盖；保留本地字段/绑定/标签/基准修订并展示服务器草稿对比，显式选择后才覆盖；范围切换/刷新/离开/删除字段与选项均确认；发布仅使用已保存且核验的草稿。
  - R06：`fail` 在锁内以 `clock_timestamp()` 复核 stage/owner/fencing/attempt 与有效租约，失效持有者零副作用；回收由维护流程独立结算。
  - R07：管理页仅编辑显示名称、填写方式、是否必填、选项与顺序；内部键/列/存储由系统生成且不展示，高级JSON入口移除；条件以中文业务规则呈现。预览输出“名称调整/新增/删除/填写方式/必填/选项”等业务变更及历史记录影响，DDL折叠为工程校验信息；改名保留字段身份与历史值。
- 验证（2026-10-26，Docker恢复后完成）：
  - 后端全量正式回归（独立库15602，Testcontainers真实PG16）：**2002项，Failures 0、Errors 0、Skipped 1**（既有跳过项）；表单相关定向类全绿：`FormTemplateAdminIntegrationTest` 14/14、`FormRecordServiceIntegrationTest` 26/26、`FormInitialMigrationAdminTest` 4/4、`FormSchemaMigrationTest` 26/26。日志`.tmp-logs/w4r-backend-full.log`。
  - 前端：`typecheck`、`lint`、全量`npm test`（173文件/889项）、生产构建全部通过；表单单页与业务字段组件定向16/16。
  - 真实页面（独立PG 15601、后端18090、Vite 18183、真实Chrome）：`acceptance.mjs` **22/22**，覆盖业务字段直接编辑/新增/删除、中文业务预览与历史影响、DDL折叠、CAS冲突保留本地并显式选择、刷新确认取消保留输入、未保存不得发布、发布完成与删除确认取消。证据`.tmp/w4-remediation-20261026/evidence/`（8张截图、`acceptance.log`、`acceptance-summary.json`、`db-evidence.txt`、`acceptance-run.log`）。
  - 突变自证：将冲突识别还原为仅`ApiBizError`后CAS组件测试失败，恢复后9/9通过（`evidence/mutation-cas-fail.log`）。
  - 真实页面发现并修复的R05缺陷：后端409以`HttpRequestError`到达前端，原判断只认`ApiBizError`导致冲突被当普通错误；已统一识别并新增真实传输层组件测试。
- 已完成（2026-10-23）：
  - `FormSpecValidator` 抽取并供种子加载与草稿共用；`FormTemplateSpecCatalog` 行为不变。
  - 管理接口：模板/范围列表与详情、新建 DRAFT、草稿 CAS（409/A0409）、预览证据（目标版本预留、差异/DDL/影响/阻断）、发布创建、恢复、退役、删除、绑定候选、ODS 里程碑。
  - 通用发布执行器：`FormPublicationService`/`FormPublicationTxService`/`FormPublicationMigrator`/`FormHeadSwitchService`/`FormPublicationWorker`；PREPARED→COPYING→VERIFIED→PUBLISHED/FAILED、租约/fencing、范围停写栅栏、复制与切换事务分离、失败恢复、租约过期回收。
  - INITIAL_EMPTY 与 MIGRATION 全量复制/守恒；INITIAL_MIGRATION 于2026-10-26整改并入通用执行器（旧一次性服务已删除）。
  - 历史缺值：新增/收紧必填字段导致旧行缺值时，复制投影写入`legacy_incomplete/legacy_missing_fields`，不补默认值；缺值历史行可直接作废（真实 PG 测试覆盖）。
  - 跨版本旧 key 收敛：`create` 先按 requestKey 查当前权威表，按请求携带版本冻结规格重算指纹；真实 PG 与浏览器均验证返回迁移后同一条记录。
  - 前端 `表单管理` 页面、路由/权限/菜单注册、API 类型与客户端、绑定/里程碑、预览、发布轮询、恢复/退役/删除；字段编辑与预览已于2026-10-26按R07整改为业务字段模型。
- 验证（整改前基线）：`FormTemplateAdminIntegrationTest` 9/9、`FormRecordServiceIntegrationTest` 23/23；后端全量（独立容器15602）1972项，Failures 0、Errors 3、Skipped 1，3项错误为Testcontainers容器池初始化连接失败（宿主机约3GB空闲、驻留38个长期容器），受影响3类隔离复跑29/29通过。历史真实页面18/18及数据库守恒证据见`.tmp/w4-acceptance/evidence/`；整改后的页面证据将写入`.tmp/w4-remediation-20261026/evidence/`。
- 输入指纹（整改后）：见`.tmp/w4-remediation-20261026/`下`code-evidence-manifest.json`、`input-manifest.json`与`evidence/change-summary.json`；指纹值在本行写入前对清单计算，交付报告给出最终文件SHA-256。整改前清单`98b1b116…a4251391`及`.tmp/w4-acceptance/`仅作历史对照。
- 独立复验：审核方正式后端32项、前端45项通过；五项真实PG及一项页面契约反例均失败，分别涉及锁顺序、旧表冻结规格、完整结构漂移、终态幂等、管理员输入及过期诊断权限。证据`.tmp/w4-review-20261026/`。整改逐项对应上述反例。
- 当前下一步：等待复验；若复验仍有R05问题，在同一专属worktree/任务分支内继续修复并追加提交，不重建工作树、不返回混合主树编码、不进入W5。
- 阻塞：无已知实现阻塞；R05整改与N0补充包待审核方复验/审批，W5准入取决于该结果。上一轮R05/R07-b反例已在本轮修复并以正式测试与真实页面覆盖。
- 已知环境问题：默认15433测试库public schema残留缺少`mirror_task_id`的旧ODS表，直接全量会在Flyway建索引处失败；本轮改用独立容器，未修改测试库与迁移。

## 整改执行计划（2026-10-26）

- **统一锁序（R01）**：固定为“共享记录序列咨询锁（如涉及）→ 范围行 → 发布任务行 → head 行（按 id）”。切换在锁范围前取得序列咨询锁；创建保持序列→范围共享锁；领取/失败/回收/恢复只取范围→任务。以审核探针的pg_blocking_pids交错场景转正式回归，覆盖同/不同范围与初始迁移。
- **旧表首迁（R02）**：删除管理入口对一次性`FormInitialMigrationService`的委托，INITIAL_MIGRATION统一进入通用阶段机。新增Legacy选择与复制适配：按草稿绑定的当前来源/项目范围计算S，空S走INITIAL_EMPTY；复制使用管理员冻结field_spec，旧列按固定语义映射（reviewer→reviewer_name、五项score→count、remark→supplement_note），新增/删除字段照常处理；LEGACY版本、切换清单、迁移日志、租约/恢复与MIGRATION一致。W1既有迁移语义测试改走新入口。
- **完整结构契约（R03）**：抽取`FormVersionStructureVerifier`（列类型/空值、约束集合与CHECK字面量、PK/唯一/FK动作、索引、序列默认值），启动核验、恢复与复制共用；漂移时不truncate、不切指针。
- **发布幂等（R04）**：`createPublication`在草稿/指针校验前按`(scope, requestKey)`查原任务，按首次`draftRevision/previewId/expectedCurrentVersionId`核验；同key同载荷返回原任务，异载荷409。页面按已保存草稿与预览缓存请求key，重试复用。
- **管理页输入保护（R05）**：CAS冲突不再自动刷新；保留本地字段/绑定/标签/依据并显示服务器修订，用户确认后才覆盖。范围切换、刷新、离开及删除有内容字段/选项均确认；发布只基于已保存并核验的草稿，未保存变更先保存。
- **租约诊断（R06）**：`fail`在锁内按`clock_timestamp()`复核stage/owner/fencing/有效租约，失效返回false且不写任务、attempt或栅栏；维护回收独立结算。
- **业务字段界面（R07）**：R07-a随机稳定身份已通过；R07-b的业务条件编辑和物理表/DDL移除已落地，仍须统一条件配置、服务校验和受控DDL的类型/互斥语义，并修正真实种子中多选/布尔控件的中文回显。具体反例与验收条件以二次整改复审记录为准，不能改Wiki规则求绿。
- **验证**：将审核探针五项真实PG反例与一项页面反例转为正式测试；新增三模板业务字段组件/页面测试与连续真实页面验收；定向、全量与新指纹在完成后更新。整改代码已全部落地，验证状态以上方“进度与中间物”为准。

## 恢复线索

- 恢复先读本文件、[W3通过与W4准入记录](unified-form-system-implementation-plan-20261021.md#w3通过与w4准入记录2026-10-23)、[主方案§3.4](../plans/unified-form-system-plan-20261009.md#34-管理员字段管理与受控版本发布机制)、[进度](../progress.md)。
- 上一条可恢复命令：`git status --short`，确认工作树仅含既有混合改动与本单元新增文件。
- 本计划完成并验收后，将长期事实归入主方案/架构/进度，再删除本文件。

## 目标与边界

**目标**：交付管理员字段草稿、预览、范围绑定、首次启用、字段增删改/迁移、发布与退役；受控 DDL、持久任务/租约、失败恢复、守恒核验与指针切换；普通用户不可见且直接请求 403；旧链接与历史记录连续；W4/W6 打通“原创建已成功但响应丢失 → 发布迁移 → 旧 key 核验最终收敛”。

**禁止**：不改动W3已通过的填写页保护语义；不以静默换key、丢弃快照或永久unknown解决收敛；不修改既有Flyway迁移与checksum；不把环境绑定/旧表S迁移写入通用Flyway；不运行黄金（W7）。混合主树不提交/推送，不继续追加业务修复；阶段封包后的任务隔离、完整验证单元提交与双远端推送按执行单隔离交接要求及最新AGENTS执行，不代表W4通过或发布准入。

## 约束与背景

- 表结构已由 `V20261022_01__unified_form_storage_foundation.sql` 提供：scopes/versions/drafts/preview/publications/attempts/audit/migration_logs/heads/authorities。
- `FormVersionSchemaService` 生成确定性实例/记录表 DDL、约束、索引、`ddlSummary/ddlHash`；`FormSchemaStartupValidator` 按冻结 spec 做漂移核验。
- 旧表首迁由 `FormLegacyRecordMigrator` 承担取数与映射，由 `FormPublicationTxService` 统一驱动阶段机；原 `FormInitialMigrationService` 已删除，W1/W2 迁移测试已改走管理入口+通用执行器。
- `FormScopeValidationService` 已实现绑定校验、ACTIVE 重叠拒绝、覆盖范围与来源配置校验。
- 权限 `form.template.manage` 已存在（ADMIN/SUPER_ADMIN）；CSRF 由平台统一处理；403 由 `PlatformAuthorizationInterceptor`/`AccessDeniedException` 统一返回 A0303。
- 版本标签证据：ODS 动态表 `ods_gitlab_milestones`（按来源实例），当前无按项目查询服务；W4 新增受控读取与校验证实。
- 旧数据库编辑器已在 W2 移除；数据库查看只读；动态 `form_s*_v*` 表尚未登记查看目录。

## 证据与根因

- 管理端接口、草稿 CAS、预览、发布任务/租约/恢复、通用 MIGRATION、INITIAL_EMPTY、退役/删除、管理页全部缺失（调查见各服务/控制器 grep）。
- 跨版本 old-key 不收敛根因：`FormRecordService.create` 先 `requireExpected` 再重放；`fingerprint` 把 `storageVersionId` 计入指纹；`replayIfPresent` 只查当前表。迁移后客户端仍带旧版本，必然 409，无法证明原请求已写入。
- 现有 INITIAL_MIGRATION 把 DDL/复制放在切换同一事务，不满足长复制与切换分离；W4 通用执行器需拆分复制事务与切换事务。

## 方案与步骤

**S1 管理基础（后端）**
1. 抽取公共 `FormSpecValidator`，供种子加载与草稿校验共用；新增规格差异/映射/预览计算。
2. 管理接口：`GET /templates`、`GET /template-scopes/{id}`、`POST /template-scopes`、`PUT /{id}/draft`（CAS）、`POST /{id}/preview`、`PUT /{id}/binding` 由 draft 统一承载、`POST /{id}/retire`、`DELETE /template-scopes/{id}`、`GET /binding-options`、`GET /version-milestones`。
3. 草稿行 CAS（draftRevision）、预览证据持久化与哈希；绑定变更区分首次发布与后续迁移（后续变更 409）。
4. 权限注解 `form.template.manage`；端点授权契约与黄金端点目录登记。

**S2 受控发布执行器**
1. `FormPublicationService`：POST publications 校验 draftRevision/preview/expectedCurrentVersionId/requestKey；同 key 同指纹返回原任务、异指纹 409；每范围仅一个未结束任务。
2. 类型判定：无当前版本且 S 为空→INITIAL_EMPTY；无当前版本且本范围存在旧 S→INITIAL_MIGRATION（按冻结草稿/预览执行完整协议）；有当前版本→MIGRATION。
3. 阶段机：PREPARED→COPYING→VERIFIED→PUBLISHED/FAILED；租约 owner/fencing/lease_until + `clock_timestamp()` 校验；范围停写栅栏按 publication+token 设置/清除。
4. 复制事务与切换事务分离：DDL/全量复制/守恒在独立事务；切换事务只做指针 CAS、版本状态、迁移日志、head、撤栅栏，提交前复核租约。
5. 恢复：FAILED 显式恢复，重新取栅栏/重捕获 S/清空目标/新 attempt；目标结构/hash 不一致或存在非本任务写入→终止。租约过期由维护扫描回收为 FAILED 并按 token 清栅栏。
6. 测试钩子：`execute(publicationId)` 可同步驱动；`@Scheduled` 轮询默认开启、测试关闭。

**S3 字段迁移与守恒**
1. MIGRATION：从当前 RECORDS 全量复制（含 deleted），按稳定字段名/列映射；改名保值、新增列 NULL、删除列不复制、兼容类型转换（整数/文本/varchar 扩展），不兼容转换进入 blockingErrors。
2. 实例按完整键归并，时间取最早创建/最新更新；记录 id/创建人/request_key/载荷指纹/record_version/时间/产生版本保持；守恒：行数、实例数、逐字段 NULL 安全、无集合外混入。
3. 共享记录序列只向前推进；迁移日志与 attempt 证据写入。

**S4 跨版本旧 key 收敛**
1. `FormRecordService.create` 在 `requireExpected` 前先按 requestKey 查当前权威表；命中时用**请求携带版本**的冻结 field_spec 校验并计算指纹，匹配则返回 REPLAYED。
2. 指纹函数版本参数显式化；旧版本不存在/不属于本范围时拒绝。
3. 真实 PG 测试：v1 创建（响应丢失）→ 发布 v2（改名/新增/删除）→ 旧版本 id + 原 key 重放返回同一条迁移后记录，不新建；错误载荷冲突。

**S5 前端管理页**
1. 类型与 API：模板/范围/草稿/预览/发布/退役 DTO；`collect-forms-api` 管理函数。
2. feature-manifest/PageKey/route-contracts/router 注册“表单管理”，权限 `form.template.manage`，系统设置菜单可见。
3. 页面：模板范围列表、绑定、业务字段增删改与排序、预览对话（业务名称、中文差异、修改前后内容、历史影响与阻断原因）、发布（版本标签+里程碑证据+轮询阶段）、恢复、退役/删除确认；CAS冲突保留输入。按主方案§3.4隐藏内部键和物理映射，删除高级JSON编辑入口；DDL保留为后台及工程审核证据。
4. 组件测试覆盖权限、CAS、预览阻断、发布轮询与退役，以及全程按业务字段操作、技术字段不作为编辑项和中文差异提示。

**S6 真实验收**
1. 隔离 PG + 后端 + Vite：空平台首次启用；页面完成字段改名/新增/删除+迁移；旧链接读取迁移后记录；普通用户 403；重复发布 409；复制中停写 409；失败/重启/租约过期可确定恢复或终止。
2. 浏览器证据：按主方案§3.4业务字段完成编辑、预览、确认及发布的连续流程，覆盖改名、新增、删除、条件/选项及错误定位；核对系统自动标识、改名身份及历史值保持。留存发布阶段、迁移前后字段值与记录数守恒、旧key收敛证据。
3. 文档同步与交付指纹。

## 决策记录

- D1：INITIAL_MIGRATION 与 INITIAL_EMPTY/MIGRATION 共用通用执行器与阶段协议，旧一次性服务已删除；首迁复制必须执行管理员冻结草稿与预览，按当前绑定全量S判定，空S走INITIAL_EMPTY，不得以缩减协议通过验收。
- D2：预览/发布以草稿修订为唯一版本源；发布冻结 field_spec/binding/migration_spec 与证据，不从草稿再读。
- D3：长复制与切换事务分离；任务状态推进与所有持久副作用通过短事务 + 租约行校验。
- D4：跨版本重放指纹使用请求携带结构版本，记录查找使用当前权威表；不解冻/不改写原快照。
- D5：版本标签证据要求非空且能从 ODS 里程碑核验；ODS 缺失时阻止发布并提示补同步，不静默放行。
- D6：字段条件/词典的后台规格继续采用既有JSON契约；管理员通过业务字段和选项配置，系统生成并校验规格，不提供高级JSON编辑作为替代。R07按主方案§3.4验收，不新增业务规则。
- D7：不把动态版本表加入可写数据库查看，仅登记只读目录（如时间允许，随 W4 收口）。

## 接口契约

基址 `/api/collect-forms`，均需 `form.template.manage` + CSRF：

| 方法/路径 | 契约 |
| --- | --- |
| GET `/templates` | 模板、范围、当前版本、草稿修订、活动发布摘要 |
| GET `/template-scopes/{id}` | 范围/绑定/当前版本/草稿/版本列表/最近发布 |
| POST `/template-scopes` | `{templateKey, scopeKey}` → DRAFT 范围 + 初始草稿 |
| PUT `/template-scopes/{id}/draft` | `{expectedDraftRevision, fieldSpec, binding, versionLabel, versionEvidence, migrationSpec}`；CAS → draftRevision+1 或 409/A0409 |
| POST `/template-scopes/{id}/preview` | `{draftRevision}` → `{previewId, diff, ddlSummary, blockers, impact}` 并持久化证据 |
| POST `/template-scopes/{id}/publications` | `{draftRevision, previewId, expectedCurrentVersionId|null, requestKey}` → `{publicationId, stage}` |
| GET `/publications/{id}` | 阶段、attempt、错误、指针、守恒摘要 |
| POST `/publications/{id}/recover` | FAILED 显式恢复 |
| POST `/template-scopes/{id}/retire` | `{expectedCurrentVersionId}` |
| DELETE `/template-scopes/{id}` | 仅未发布且无实例/记录的 DRAFT |
| GET `/binding-options` | 可选来源配置（id/instance/canonical URL） |
| GET `/version-milestones?sourceConfigId&projectId` | 活跃里程碑（iid/title/state） |

## 风险与假设

- ODS 里程碑表在各环境可能未就绪；测试夹具需建表，生产以“阻止发布并提示补同步”处理。
- 既有 INITIAL_MIGRATION 与通用执行器并存存在维护成本；若 W4 审核要求路径 B 也走租约恢复，再统一重构。
- 通用迁移的字段类型转换范围有限：仅支持无损/可校验转换；其余阻断并报告。
- 条件编辑受既有规则与控件支持范围约束；超出支持范围时用业务说明阻止发布，不要求管理员改JSON。结构化配置仍须通过服务端完整校验。
- 黄金端点登记必须与实现同单元完成，但快照重建留 W7/用户授权。
