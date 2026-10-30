# 表单 v6 N3：空结构发布、短切换与旧任务收口

> 状态（2026-10-30）：输入dafb88a0复验，R01—R03关闭，仅R04-a待修，N4未准入。N3通过后收口本计划，长期事实归 `docs/architecture.md`、`docs/decisions.md`、`docs/product.md` 与 `docs/progress.md`。

## 进度与中间物

- 阶段（2026-10-30）：P0—P5已交付，整改输入`dafb88a0`复验关闭R01—R03，R04仍有写入前资源身份校验缺口。只执行[N3二次审核末项指令](unified-form-system-implementation-plan-20261021.md#n3-review-2)，原交付证据继续保留。
- 已完成文件：本计划；迁移 `V20261029_01__form_v6_publication_protocol.sql`（第 161 条，checksum 已登记）；`FormPublicationTxService`/`FormPublicationService`/`FormHeadSwitchService`/`FormTemplateAdminService` v2 改造；删除 `FormPublicationMigrator`、`FormLegacyRecordMigrator` 与 v1 复制目标测试；前端阶段类型与管理页提示。
- 测试状态：
  - `FormTemplateAdminIntegrationTest` 19/19 通过（真实 PG），其中新增参数化用例覆盖 `code_review`/`issue_response`/`issue_reply` 三模板的 INITIAL_EMPTY 首次启用与 MIGRATION V1→V2 空发布：旧记录及目录位置不变、目标空表、角色 `AUTHORITATIVE/RETIRED`、`source_set_revision` 1→2、目录事件 2 条、head pending 与源集合版本一致；V1 当时可新建，发布 V2 后新建只进当前 V2，旧 V1 继续维护已有记录。
  - `FormRecordServiceIntegrationTest` v2 锁序适配后全套通过；前端 174 文件/944 项、typecheck/lint/build 通过（页面验收前基线）。
  - 默认套件首轮 2027 项 3 失败：2 项迁移头期望已修正（`FormV6LegacyUpgradeIntegrationTest` 头部改 `20261029.01`/2 条新迁移；`MigrationOnlyIntegrationTest` 头部同步），1 项与本单元无关的 `ProcessBackupProcessRunnerTest` 租约竞态 flake 单独运行通过。修正后最终全量 **2030 项，0 失败，0 错误，1 跳过，`BUILD SUCCESS`**（31 分 04 秒，日志 `.tmp-logs/n3-default-suite-final.log`），备份失败未复发。
- 本轮裁决（2026-10-29）：页面验收只做三个代表性范围（`code_review` 升级、`issue_response` 首启、`issue_reply` 升级）；两升级范围核旧记录可见、原版编辑、新版首条新增，首启范围核启用与首条新增；顺序为补夹具→页面验收及必要修复→仅一次最终默认全量→封包送审；备份用例单跑通过不替代全量，全量若复发同一失败则保留材料单独登记送审，不反复跑到绿、不放宽断言、不自行宣告通过。
- 页面验收（2026-10-29）：专属 PG 15607、后端 18093（仅发布 worker，300ms）、Vite 18186、Chrome CDP 9273；三范围经真实管理页发布并通过 `ALL CHECKS PASSED (42)`。发布确认均显示“新版本从空结构开始；已有记录保留在原版本，不迁移、不改绑。”；两升级范围旧记录可见、原版编辑成功、新版首条新增进入新表；首启范围首条新增成功。DB 对账：旧记录与目录位置逐字节不变、新表初始为空、旧版 `RETIRED/AUTHORITATIVE`、新版 `PUBLISHED/AUTHORITATIVE`、源集合 1→2（首启 0→1）、目录事件各 2 条、head pending 指向新版本、`write_fenced=false`、任务 `protocol=2`（`MIGRATION`/`INITIAL_EMPTY`）。证据：`.tmp/w4-n3-20261029/evidence/`（acceptance.log、summary、db-final.txt、7 张截图），环境与边界见同目录 `environment.md`。
- 外部连接边界：首次为运行发布 worker 打开调度总开关时，库内 match-mode 默认启用，17:16 对 `172.22.10.72:27017` 发起过一次 Mongo 握手并被对端关闭，未观察成功读写；随后在专属库关闭 match-mode 并重启，最终日志仅 `127.0.0.1:15607`、Mongo 0 次。
- 整改（2026-10-29）：已快进吸收审核回执`3d6449d7`，在`19d40549`基础上关闭 R01—R04，逐项证据见下节“N3-R01—R04 关闭证据”。
- 进行点：审核方独立后端66项与脚本7项通过，43项输入哈希和双远端dafb88a0一致。主入口替身反例发现资源不符时先迁移/UPDATE后拒绝；下一步只拆开写入前资源预检与迁移后业务预检，并补入口两条流程回归。
- 阻塞：仅N3-R04-a未关闭，不能进入N4。R01—R03与已验证66项不重跑；前端/42项页面沿用，默认全量/黄金不运行。复验证据位于审核树`.tmp/n3-rereview-20261030/`；首轮证据`.tmp/n3-review-20261029/`保留。

### N3-R01—R04 关闭证据

以下为员工整改送审自述；复验关闭状态以首部及审核执行单为准。

- **R01 退役被绕过**：`FormPublicationTxService` 在范围锁内按发布类型复核状态与指针（`INITIAL_EMPTY` 只允许 `DRAFT`+无指针，`MIGRATION` 只允许 `ACTIVE`+冻结旧指针），领取后执行、`enterSwitch`、`applySwitch`、`recover` 共用同一守卫；`RETIRED` 拒绝恢复与切换，旧指针/源集合/事件/head/角色零变化，未新增重新启用入口。正式回归 `shouldRejectRecoveryForRetiredScope`（真实 PG）：失败→退役→恢复被 409 拒绝，任务保持 FAILED、范围保持 RETIRED、无栅栏。RED 证据：修复前同一测试断言失败（`.tmp/w4-n3r-20261029/red/`）。
- **R02 绑定被覆盖**：新增第 162 条前向迁移 `V20261029_02__form_publication_origin_binding.sql`（`origin_binding/origin_binding_revision/origin_binding_hash`，旧行与 v1 任务保持 NULL），checksum 已登记；任务创建时冻结原绑定证据，`enter/apply/recover` 在范围锁内复核修订、哈希与规范绑定，失配拒绝且不重冻结。正式回归 `shouldRejectBindingDriftBeforeSwitchAndRecovery`、`shouldRejectBindingDriftAfterSwitchEntered` 覆盖 enter 前、enter 后 apply 前与恢复三处；拒绝时零指针/集合/事件/head/角色切换，SWITCHING 失败按原 owner/token 清自有栅栏。三模板首次启用与 V1→V2 既有参数化用例保持通过，未扩模板矩阵。
- **R03 结构回归恢复**：新增 `FormVersionStructureVerifierIntegrationTest`（正式生成器 + 真实 PG16），恢复 CHECK 逻辑分组漂移、外键删除动作漂移、临时表跨连接三项历史缺陷回归，并保留合法结构通过、探测临时表零残留、Hikari 多连接入口（`PinnedDataSource` 每次借独立物理连接且不归还）。同文件保留约束集合缺失、记录索引缺失、记录ID默认序列漂移。已删除的复制/S 迁移/truncate 测试保持删除，未恢复旧协议或双轨。
- **R04 启动隔离与日志**：新增 `scripts/run_isolated_form_acceptance_backend.py`（迁移专用进程 → 初始化并核验 match-mode/BI CAT/DGM/备份开关与外部目标 → 只开放发布 worker 启动 → 启动后扫描非回环地址/Mongo）与 `scripts/form_publication_smoke.py`（真实管理接口建范围→绑定→预览→发布，核对空版本与新建指针）；纯函数单测 `run_isolated_form_acceptance_backend_test.py` 7/7。证据 `.tmp/w4-n3r-20261029/r04/`：预检不符（含默认 match-mode 开启、BI CAT 全量补偿开启、外部 Mongo 已配置）时退出码 2 且 18094 无监听；一次安全启动 11 项预检 + 冒烟 + 三项日志扫描全 PASS，任务 `protocol=2/PUBLISHED`、新版本记录 0。原始 17:16—17:17 Mongo 事件从 `backend/logs/data-collection-platform-backend.json.log` 第 1023—1026 行冻结，`environment.md` 路径已纠正（原引用 `.tmp-logs/n3-backend.log` 从 17:32:54 起，不含该事件），边界仍为“尝试连接、对端关闭、未观察成功读写”。
- **本轮验证范围**：受影响的 `FormTemplateAdminIntegrationTest`（22/22）、`FormRecordServiceIntegrationTest`（36/36）、`FormVersionStructureVerifierIntegrationTest`（6/6）、`FormV6LegacyUpgradeIntegrationTest`（1/1，非空 159→162 升级、既有迁移零改动）、`MigrationOnlyIntegrationTest`（1/1，head `20261029.02`）共 66 项全绿；R01/R02 三项 RED 断言先失败后转绿。沿用未变前端 24 项/typecheck、员工 944 项与 42 项页面证据；未重跑默认全量、未运行黄金。
- **新增/修改/删除清单**：新增 3 个脚本（含 1 个纯函数测试）、1 条迁移、1 个聚焦结构测试；修改 `FormPublicationTxService`、`FormTemplateAdminIntegrationTest`、两处迁移头断言、checksum 手册、本计划、`architecture.md`、`decisions.md`、`progress.md`、验收 `environment.md`。删除项沿用首次送审的 4 项代码/测试（`FormPublicationMigrator`、`FormLegacyRecordMigrator`、`FormInitialMigrationAdminTest`、`FormSchemaMigrationTest`）与已按生命周期删除的 N2 计划，本轮无新增删除。

### 实施记录

- 迁移编号 161：`protocol_version`、v2 阶段/attempt 阶段、活跃唯一与领取索引、`frozen_source_set_revision/hash`、head `pending_source_set_revision/hash`；checksum 已登记。
- 协议差异：`createPublication` 建目标 `TARGET/INSTALLED` 空版本并冻结源集合；claim 不设栅栏；build 只建空表并核验；enter/apply 短栅栏原子切换；BUILDING/SWITCHING 过期由 claim 接管；失败/恢复只由当前 fence 所有者收口；旧 protocol=1 只盘点与受控收口。
- 发现并修复：租约过期原用 Java `Clock`（UTC）与数据库本地时间比较，导致接管恒判未过期；统一改为数据库 `clock_timestamp()`。
- head 受影响集合从“目标实例∪权威”扩展为“目标实例∪仍可读旧版实例∪权威”，避免旧记录 head 在空发布后成为未覆盖证据。
- 前端只改阶段词表与发布确认提示，批准布局/样式、管理页结构未变。
- 三模板参数化用例暴露并已处理夹具前提：空范围首版发布后尚无 head（首条记录写入才产生）；`code_review` 新建需 LDAP 人员与 ODS MR 镜像；`issue_response` 在资源为一级缺陷时要求模块负责人确认。

## 阶段、恢复线索与资源

- 分支/目录：`表单多版本与填写功能开发-20261026`，`D:/projects/dcp-form-w4-20261026`；不新建阶段分支。
- 首条恢复命令：`git status --short --branch`、`git log --oneline -3`，确认 `d68019e6` 为祖先。
- 前序计划：`docs/plans/unified-form-system-implementation-plan-20261021.md#n3-admission`；技术合同 `docs/plans/v6-n0-technical-admission-20261027.md#4-发布协议r01q4q9`。
- 环境：仅自有 Testcontainers 或本任务专属 PG 容器/端口；应用启动关闭全部后台作业并排除 Mongo 自动配置；不使用 15432/15433、共享服务；不动预览 18187/18094/15612。

## 原始需求、成功标准与禁止行为

- 管理员发布新版后：新版为空结构，后续新建进入新版；旧记录、原版规格、目录承载位置与首次身份不变；旧链接、原版编辑/作废、原 key 核验继续可用。
- 构建期间可写；切换只有短栅栏；任意漂移、失效执行者或恢复失败不得产生部分切换。
- 禁止：复制/清空旧记录、改绑旧目录、生成空实例、截断旧表、真实库自动登记或收口旧任务、N4 消费/统计/Excel、真实权限/外部写回、main/正式包/黄金。

## 业务、API、数据与环境约束

- 发布任务 `protocol_version=2`：`VALIDATING → BUILDING → SWITCHING → PUBLISHED/FAILED`；旧二进制只认 `PREPARED`，新执行器拒绝 `protocol=1`。
- 统一锁序：`pg_advisory_xact_lock(hashtext('form_record_id_seq')) → form_template_scopes FOR UPDATE → form_template_publications FOR UPDATE → head`；所有领取/DDL/enter/apply/接管/失败收口同前缀。
- 每次阶段/DDL 为短事务；事务开始锁任务行并校验 `lease_owner/fencing_version/fence_token/lease_until > clock_timestamp()`，DDL 副作用后、COMMIT 前复校；失败整体回滚。
- 切换期间写入返回可重试栅栏；旧版未落库 key 的创建按 409，不改投新版；新版本新请求成功。
- 160 条既有迁移零改动；新增前向迁移并登记 `scripts/flyway-migration-checksums.json`。

## 证据与复现

- 旧协议缺陷：`FormPublicationTxService.claim` 领取即写范围栅栏；`copy` 在单事务内 `ensureTables/truncate/复制`；`switchVersion` 不更新源集合/head/目录事件；`reapExpired` 只置 FAILED。
- 需保留的反例/证据：哈希与 key 收敛已有 N2 证据；N3 新增真实 PG 并发与守恒证据归 `.tmp/w4-n3-20261029/`。

## 步骤与依赖

1. P1 迁移：协议列/阶段/attempt/活跃唯一与领取索引、head 源集合列。
2. P2 执行器：创建 VALIDATING；claim/BUILDING 接管；空表构建与核验；enter/apply 短切换；SWITCHING 接管；失败/恢复；源集合、目录事件与 head 更新。
3. P3 旧任务：只读盘点与受控收口服务；新执行器拒绝 v1；删除复制/转换运行路径与调用者，重写目标测试。
4. P4 前端：阶段词表与发布确认中的“新版本为空结构、旧记录保留原版”说明；保留批准布局/样式。
5. P5 验证：真实 PG 三模板首次启用与 V1→V2 空发布、守恒、旧 key 收敛、未落库 409、构建/切换并发、漂移拒绝、崩溃与接管幂等、旧任务拒绝/收口；默认套件、受影响前端、页面基础检查与数据库对账；护栏、提交、双远端回读、清理后送审。

## 选定与否决方案

- 选定：复用现有执行器/生成器/Verifier 与 `FormHeadSwitchService`；v2 任务不改写 v1 行；空结构构建只建表不复制；目标版本先 `TARGET/INSTALLED`，apply 时 `AUTHORITATIVE/PUBLISHED`。
- 否决：新执行器读取 v1 复制协议；构建期停写；truncate 目标表；切换后改绑旧目录；为旧测试保留复制双轨；真实库自动收口。

## API、函数、表结构与协议（本单元）

- 新迁移（编号以实际 main 为准，预计 `V20261029_01__form_v6_publication_protocol.sql`）：publications `protocol_version`、阶段约束与索引；attempts `protocol_version`、phase 扩展；change_heads `pending_source_set_revision/hash`。
- 复用 DB 函数：`form_source_set_hash(scope)`、`form_advance_record_sequence(high)`；目录事件 `VERSION_ADDED/CURRENT_CHANGED`。
- 执行器方法：`createPublication`、`claim`、`build`、`enterSwitch`、`applySwitch`、`takeover`、`fail`、`recover`、`closeLegacyPublication`、`listLegacyPublications`。
- 前端：`FormPublicationStage` 扩展 v2 阶段；发布确认展示空结构/旧记录保留原版；后台壳与样式不变。

## 风险、假设与敏感数据

- 迁移需兼容既有 v1 任务行（默认 protocol=1）与 v1 阶段；旧任务不自动失败。
- 切换窗口预算：enter→apply 目标 < 2s，过期接管租约 5m；诊断记录阶段、owner、fence、租约与耗时。
- 假设：现有预览冻结与 `FormVersionSchemaService`/Verifier 足以生成空结构；真实 PG 版本 16 与 Testcontainers 一致。
- 仅只读使用现有真实库不做写入；所有写入只发生在自有容器/专属库。

## 替换/删除路径

- 删除 `FormPublicationTxService.copy` 及 `FormPublicationMigrator`/`FormLegacyRecordMigrator` 的发布调用；同步删除/改写依赖复制、truncate、`INITIAL_MIGRATION` 的发布测试。
- 保留 N1/N2 的目录登记、读取、记录服务与规格守卫；删除仅服务于 v1 复制的后台路径，不留双轨。

## 短栅栏预算与诊断

- `enterSwitch` 与 `applySwitch` 各自为短事务；仅 apply 前置的栅栏窗口阻塞写入，超时返回可重试业务错误。
- 诊断：任务视图暴露 protocol/stage/owner/lease/fencing；旧任务盘点只读列出，不自动修改。

## 测试入口与资源

- 后端：已实施入口为`FormTemplateAdminIntegrationTest`与`FormRecordServiceIntegrationTest`（真实PG），不是未创建的`FormPublicationV2IntegrationTest`；默认套件交付证据2030/0/0/1。整改按审核执行单复验受影响类。
- 前端：管理页发布组件/类型测试，填写页回归沿用 N2。
- 页面：仅三模板发布确认与新版首条新增基础检查，不做视觉整改。
