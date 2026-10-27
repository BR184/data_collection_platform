# 统一表单 v6 — N1 基础实施活动计划（2026-10-27）

## 进度与中间物

- [审核结论] 输入`2ac212f16193a49a3e87d36745c609eaa54b24f0`首次验收：**修改后重审，N2未准入**。N1-R01—R04为P1实现缺陷，N1-R05为P2环境与验证整改；详见[首次验收与整改执行单](#n1-first-review)。N0既有批准保持。
- [审核恢复] 审核树`C:/Users/admin/.codex/worktrees/form-admin-ui/data_collection_platform` / `codex/form-admin-ui-20261027`已快进吸收输入；员工树`D:/projects/dcp-form-w4-20261026`只读。审核证据归本树`.tmp/n1-review-20261027/`；独立正式测试12/0/0/0，并以真实PG复现八项边界观察。未使用15432/15433、共享服务、业务页面或外部调用。
- [当前阶段] N1已送审并被退回整改；下面员工交付中的“已完成”只描述提交范围，不代表验收通过。员工环境操作与默认套件的真实边界见N1-R05。
- [已完成] P1 `V20261027_01__form_v6_directory.sql`（范围集合/版本角色/实例模式/来源表、定位目录、可比登记表、目录事件/游标/水位、`form_spec_hash`/`form_source_set_hash`/`form_create_fingerprint`/`form_advance_record_sequence`、可读集合视图），校验和已登记（159→160，仅新增）；P2 `FormV6RegistrationService`（路径A/B、precheck 一致性强制、守恒断言、幂等重入、漂移/重复归属/证据缺失整体拒绝）与 `FormRecordDirectoryService`（目录写入接入六步创建、跨版重放按 origin 证据复算 v5 指纹）；P3 `FormRecordDirectoryReader`（find/listByResource/captureSourceSet，排除 TARGET/LEGACY_SNAPSHOT，延迟回表）；稳定语义登记 `FormComparableRegistrationService`（声明=冻结类型校验、身份冲突拒绝）。
- [员工验证快照] 三类定向测试12项通过，审核独立复跑一致；其中“非空升级”实际先执行全部160条迁移，再插入旧形状数据，不能作为159→160非空升级证据。默认套件第三轮2021项仍有1项迁移头断言失败，改断言后只单类复验通过，没有最终代码状态下整套全绿的日志。不得把历史用例名称或多轮结果拼接成完整验收通过。
- [阻塞] N1-R01—R05未关闭，N2未准入。

### 恢复信息

1. **阶段与首条恢复命令**：本计划为 N1 唯一活动文档；恢复先读 AGENTS、`docs/progress.md`、N0 合同（`docs/plans/v6-n0-technical-admission-20261027.md`，五审批准节）与本文件，执行 `git status --short --branch` 与 `git worktree list`。基线 commit `13985b7a01ecc46a4193506106605e2c870968bb`，分支 `codex/unified-form-w4-isolation-20261026`，worktree `D:/projects/dcp-form-w4-20261026`。前序计划：`v6-n0-technical-admission-20261027.md`（N0 已结，历史保留）。
2. **原始需求、成功标准与禁止行为**：按执行单“五审通过后N1实施指令”（`unified-form-system-implementation-plan-20261021.md`）实施多版本定位目录/源集合、稳定元数据与真实结构校验、旧Demo/v5唯一承载原位登记及幂等/回滚、统一内部多版权威读源和必要前向迁移/正式测试。成功标准：定向真实 PG 测试 + 一次最小充分默认后端套件全绿；原行/规格/指纹守恒；迁移 159 条旧锁零差异；frontend 相对 `94749f3a` 零差异。禁止：改 frontend/UI；N3 发布执行器、N4 事实公开/统计/导出、正式权限授予；自动在启动时登记真实旧库或启用旧复制器改已登记源；暴露不完整读写组合；共享/真实业务库（禁 15432/15433 及共享服务）；合 main、出包；改 159 条既有迁移或锁条目。
3. **业务/数据/环境约束**：登记事务前确认旧写停止、选集稳定；原业务行、原 `field_spec`、origin/首次 hash/创建归属/时间不改写；重复/未覆盖/冲突/漂移整体拒绝；稳定 ID/语义声明的一次性登记位置为独立登记表（不原地改写旧规格）。隔离数据库：Testcontainers `postgres:16-alpine`（既有模式）或显式专属库；不预建前端/业务服务。
4. **提交、日志、复现与根因证据**：证据目录 `.tmp/w4-n1-20261027/`；迁移校验和登记 `scripts/flyway-migration-checksums.json`。无缺陷根因记录。
5. **步骤与依赖**：P1 DDL → P2 登记与定位服务 → P3 内部 reader → P4 正式测试 → P5 验证/门禁/送审。P2 依赖 P1；P3 依赖 P1；P4 依赖 P2/P3；P5 收口。
6. **选定/否决方案与待定项**：稳定语义登记选独立表 `form_field_comparable_registrations`（绑定 scope/version/字段 stableId/semanticKey/declaredType/unit，实际类型取该版冻结 `field_spec` 的 `field.type`，冲突拒绝；不改写 `field_spec`）。否决：把可比元数据写回 `field_spec`（违反“不借元数据升级改旧规格”）。待定：无。
7. **API/表结构/关键常量**：新增 DDL 按 N0 合同 §1.2—§1.4（`V20261027_01__form_v6_directory.sql`，仅依赖 `V20261022_01`；实际基线中 `form_template_versions.instance_table` 为 `not null`，迁移先 `drop not null`）。Java 新增：`FormRecordDirectoryReader`（find/listByResource/captureSourceSet）、`FormRecordDirectoryWriter`（§2.1 六步事务中的目录写入与重放判重）、`FormV6RegistrationService`（§3.1 路径 A/B + §3.2 precheck/register）、`FormComparableRegistrationService`（稳定 ID/语义声明登记与实际类型校验）。复用：`FormFieldValueService.fingerprint`（v5 首次指纹）、`FormHashSupport`、`FormVersionStructureVerifier`、`FormRecordStore.bumpHead`。
8. **风险、假设与敏感只读数据**：假设旧 Demo 停写窗口由调用方保证（N1 只在隔离夹具执行登记）。风险：v5 多副本承载判定歧义 → 按 §3.1 规则阻断并输出报告，不自动裁决。无敏感数据。

### 阶段

- **P1 DDL**（任务 #48）：`V20261027_01__form_v6_directory.sql` = 范围集合列 + 版本角色/实例模式/来源表 + 形状约束 + `form_spec_hash`/`form_source_set_hash`/`form_create_fingerprint`/`form_advance_record_sequence` + `form_scope_readable_versions` 视图 + `form_record_locations` + `form_directory_events`/`cursors` + `form_record_id_watermarks`。空平台与非空旧库升级两路径验证（并入 P4 迁移测试）。
- **P2 登记与定位**（任务 #49）：`FormV6RegistrationService`（precheck 只读 rowHash/count/承载报告/field_loss_report；register 单事务：咨询锁 → `lock table ... in share mode` → scope FOR UPDATE → 重算断言 → 路径A/B → 目录插入 `on conflict do nothing` + 逐字段比对 → 守恒断言 → 修订+1 → `LEGACY_REGISTERED` 事件 → 水位）；`FormRecordDirectoryWriter` 接入 `FormRecordWriteService` 创建路径（六步：序列咨询锁 → 范围 FOR SHARE → 目录判重/重放 → 插入 → 目录 → 审计 → head）；重放按目录 origin 版本 + v5 指纹核验。
- **P3 内部 reader**（任务 #50）：`FormRecordDirectoryReader` 只读 AUTHORITATIVE 且 PUBLISHED/RETIRED；排除 LEGACY_SNAPSHOT/TARGET；`listByResource` 走 `idx_frl_resource`；`captureSourceSet` 返回修订/哈希/版本集合。不改 `GitlabFactSourceSqlProvider`（N4 才切换）。
- **P4 正式测试**（任务 #51）：迁移两路径；登记路径 A/B、唯一/重复/缺失归属、原行/规格/hash/目录守恒、首次 hash 与 origin/storage 不同、NULL 实例/未知账号、重入/回滚/单调补偿、`form_advance_record_sequence` is_called 全边界与跨范围竞争、合法与漂移结构、reader 隔离、可比登记类型冲突/稳定身份校验。
- **P5 验证与送审**（任务 #52）：定向 PG 测试 + 默认后端套件一次；迁移锁 159 零差异；frontend 零差异；仓库护栏；阶段提交推双远端回读；验收包。

<a id="n1-first-review"></a>

## N1首次验收与整改执行单（2026-10-27）

**结论：修改后重审。** 绑定输入`2ac212f16193a49a3e87d36745c609eaa54b24f0`，不是重新评审N0设计。N0/W4既有批准保持；N2、W5、真实登记/切换、main及正式包未准入。批准前端保持`94749f3a`，本轮整改只涉及已准入的N1后端、正式测试和必要文档。

### 审核证据与限制

- 独立核对17个提交差异文件、员工清单中的14个文件哈希，均一致；源码证据`input-verification.json`。审核工作树仅增加文档和`.tmp`审核探针，未修实现。
- 本树后端独立执行`mvn -Dtest=FormV6DirectoryMigrationTest,FormV6RegistrationIntegrationTest,FormComparableRegistrationIntegrationTest test`：12项、0失败、0错误、0跳过，`BUILD SUCCESS`，耗时3:58。三个类均使用隔离Testcontainers随机端口。
- `.tmp/n1-review-20261027/ReviewN1Probe.java`调用当前编译产物与真实服务，在本轮专有PG16随机端口62057中复现下列八项边界观察；版本表由正式`FormVersionSchemaService`生成，并先通过`FormVersionStructureVerifier`，不采用缺少正式约束的简化表替代。首次探针夹具漏填实例时间导致工具失败，补齐后完整运行退出0；不计为产品缺陷。
- 证据目录为审核树`.tmp/n1-review-20261027/`：`directed-tests.log`、`probe-results.json`、`probe.log`、`ReviewN1Probe.java`、`input-verification.json`及`verification.json`。临时探针不进入产品源码；整改须转为正式回归。
- 审核容器`e75adcdeff021a112f84fe4872e78ca950cadb5ee850ed28e7eff94460157394`已关闭删除，按ID inspect无对象、任务标签下无残留。未启动业务服务，未接触共享15432/15433、内网、GitLab/机器人或黄金。未重复默认全量套件；现有明确反例已足以阻断验收。

### N1-R01 / P1：登记指纹与幂等不能证明全量守恒

**实证**：同一条承载记录从`score=3/record_version=1`改成`99/2`并更新时间，前后`rowHash`均为`04e0fce904988392123b5f2d925471a6b52377fd8392b40ff798f6b231c4516f`；用修改前预检结果登记仍返回`REGISTERED`。随后源表有2条、目录只有1条，重复登记返回`ALREADY_REGISTERED/directoryRows=2`，第二条仍无法定位。

**根因**：`FormV6RegistrationService.carrierFingerprint`（输入L572）只拼身份与创建证据，不含业务值、作废/修订/更新时间、完整C及实例证据；L534以“存在任意MATERIALIZED目录行”代替完成登记，且在字段丢失/承载合法性检查前直接返回；`assertBackTableHits`（L587）只数目录行，未实际回表。旧Demo的登记哈希也须按N0 §3.2核实全值范围，不能只保留挑选后的业务子集。

**关闭条件**：按N0 §3.2生成确定性全值/副本证据，持锁后重算并和预检一致；幂等依据完整既有登记证据与逐条目录比对，核对原始行、完整C、origin/规格/首次指纹、账号/时间、实例身份和行数，实际回表核验，不将“已有部分目录”视为成功。已提交源变化或不完整登记明确409并输出差异，不覆盖或自动补认历史。覆盖业务值/修订/作废/实例C/规格变化、部分目录、同数量错ID及已存在但证据不符；断言失败时目录、角色、事件、集合修订及原业务行均无副作用。

### N1-R02 / P1：合法多范围被拒，跨范围/跨来源却能登记

**实证一**：A只绑定项目11、B只绑定项目12，两条旧Demo记录分别唯一属于A/B；预检A却把仅属于B的900002报“重复归属”。原因是`selectLegacyRows`（L279）把所有其他范围拥有的行均列为重复，没有区分“只属于他范围”与“同时属于本范围和他范围”。

**实证二**：范围绑定项目71和来源`default/http://gitlab.n1.local`；实例被改为项目72和`another-source/http://other.invalid`。登记仍成功，目录项目写72，却把来源改写成范围的`default/http://gitlab.n1.local`。`analyzeCopies`（L451）仅取实例项目/资源类型/资源ID，L237用范围来源填目录，未核验实际完整C及绑定。

**关闭条件**：整体覆盖检查与本范围选集分开；全局未覆盖/真正多重覆盖拒绝，唯一属于其他范围的行不进入本次S。A/B均在锁后用新鲜范围/绑定与真实实例或旧行证据校验完整C、模板、来源与项目归属，不伪造来源；复用既有范围校验实现，不能依赖夹具恰好相同。正式回归至少覆盖两个非重叠范围依次登记、真正重叠、未覆盖、同资源ID不同项目/来源、实例C及绑定不一致；失败零写入。

### N1-R03 / P1：统一读源丢失旧值，且返回作废数据

**实证**：旧Demo成功登记后，源行`remark='legacy'`仍在，`listByResource`却返回空字段；冻结旧列规格有7个不存在的物理列：`reviewer_name/specification_count/logic_count/performance_count/design_count/other_count/supplement_note`，实际是`reviewer/*_score/remark`。普通承载记录`find`字段为空而列表字段非空；作废后列表仍返回这条业务记录，结果也没有作废标志供下游排除。

**根因**：`FormRecordDirectoryReader.withFields`（输入L156）把NONE直接当无业务行，未按`source_table`回旧表；`readRecordFields`（L192）仅按ID回表，未过滤作废或核对实例完整C/创建人。NONE表示没有实例，不表示没有原始记录。错误映射在`LEGACY_DEMO_FIELD_SPEC`（L42）。

**关闭条件**：业务读取统一完成目录→冻结规格→实际承载/旧源表回读，原值不搬迁；旧列规格准确对应真实列。普通数据读取排除作废并核对身份/完整C/创建人，缺行或证据不一致明确拒绝。若幂等核验仍需要定位作废记录，保留显式定位用途与作废状态，不把它当有效业务读源；`find`与列表的载荷契约明确且一致。覆盖NONE真实值、普通值、NULL、作废、身份/C/创建人失配、缺行、TARGET/快照排除、退役历史仍可读。不得改写已存在的冻结规格追认错误登记；真实库从未获准登记，纠错按前向/补偿规则处理。

### N1-R04 / P1：缺少真实结构准入，非空升级测试未执行升级

**实证**：正式生成的版本表先通过完整结构核验，再把`score integer`改为`text`。既有`FormVersionStructureVerifier`准确报结构漂移，N1预检与登记却继续返回`REGISTERED`。启动时曾校验不等于登记事务时仍合法。

**测试缺口**：`FormV6DirectoryMigrationTest.shouldUpgradeNonEmptyLegacyDatabaseWithoutChangingExistingRows`（输入L53）先迁移到160条，再插入数据；`FormV6TestSupport.createRecordVersion`手工建表，使用局部bigserial且缺真实约束，不能证明正式旧结构的升级/漂移边界。

**关闭条件**：登记前复用完整冻结结构核验，A旧物理表也核对真实字段契约；漂移/半表/错误约束/ID默认值均拒绝，不写目录或切角色。正式升级测试在独立库先迁到`20261022.03`（159条），写入真实非空Demo/v5及多副本数据、保存原行/规格/首次指纹/权限/序列证据，再执行新迁移并核验守恒、角色与形状。版本夹具使用正式生成器与核验器；错误契约作为明确负例。新增迁移前后均检查既有160条不可变，不整体改锁文件。

### N1-R05 / P2：隔离违约与默认验证证据需收口

员工报告及`verification-n1-20261027.json`明确记载：为默认测试重启既有15432/15433容器，并对共享15433的16张ODS表补`mirror_task_id bigint`。这违反N1明确禁止15432/15433及共享服务的边界；“仅增列”不构成授权。审核未对共享环境做恢复或清理，也未据此推断业务数据已损坏。

**关闭条件**：补齐操作时间、实际命令、16张表清单、Flyway新增历史与当时失败日志的只读核对，说明受影响对象与可证实边界；不得擅自删列、回退或再次重启共享容器。后续定向、默认套件、升级验证全部显式绑定自有隔离库/容器，错误连接即中止。基础迁移链问题只在隔离环境复现，若需范围外共享修复单独交接，不手修共享库维持测试。

修复R01—R04后在最终提交状态运行定向真实PG及一次相称默认后端套件，记录通过/失败/错误/跳过、版本、连接目标和环境清理；环境失败如实阻断，不拼接不同状态声明整套全绿。已有160条迁移已执行，视为不可变：需要DDL纠正时新增前向迁移并只追加校验和。无需黄金、前端或浏览器复跑。

### 员工下一步执行顺序

1. 在原专属树核对AGENTS、HEAD、干净状态及归属；从`origin/codex/form-admin-ui-20261027`快进吸收本审核回执，再更新本计划的整改恢复线索。吸收前后核对frontend相对`94749f3a`零差异。若有新提交造成非快进，先查明，不重置、不强推。
2. 先将上述反例转为能在`2ac212f1`上失败的正式测试，再修N1后端实现。不改变批准的产品模型、不迁移旧业务值、不实施N2/N3/N4，不改前端；所有DB/端口/卷与测试目标显式隔离。
3. 完成R01—R05关闭证据、最终状态回归与仓库护栏后，按职责分批提交并推送origin/gitlab25任务分支，回读两端提交；提交完整差异、输入哈希、逐项证据和环境收口，送N1复验。通过前仍不得进入N2、W5、main或出包。
