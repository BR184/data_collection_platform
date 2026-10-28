# 统一表单 v6 — N1 基础实施活动计划（2026-10-27）

## 进度与中间物

- [数组编码整改，2026-10-28] 输入`5a6d982f`：唯一剩余R01缺口（JDBC数组二次归一化丢失元素边界）先补真实数组失败回归，再修规范化幂等；定向39/0/0/0与默认后端套件2048/0/0/1全绿，详见[N1数组编码整改与送审](#n1-array-encoding-remediation)。R02—R05保持关闭；等待复验，N2/main/正式包未准入。
- [二次整改复验结论，2026-10-28] 输入`f69856d676b0b1476c87519113c18f5bfa19fb39`（实现`e24c6238`）：**R02/R03关闭，R04/R05保留关闭，仅R01数组编码缺口修改后重审，N2未准入**。当前关闭条件与员工指令见[二次整改复验](#n1-second-remediation-review)。双远端已核对；审核专属分支快进吸收，员工树只读；证据归审核树`.tmp/n1-rereview2-20261028/`，使用独占Testcontainers随机端口，不连接共享库或业务服务，frontend不改。
- [二次整改交付，2026-10-28] 输入`e3c7e42a`：本轮五项复验观察先转为正式失败回归（最终测试在基线实现上11/11转红），再修R01—R03后端；定向35项与默认后端套件2044/0/0/1全绿，详见[N1二次整改交付与送审](#n1-second-remediation)。R04/R05保持关闭；等待再次复验，N2、main与正式包未准入。
- [首次整改复验历史结论，2026-10-28] 审核输入`f698aad7af511ab07c5fa014f18bb36dff75a97a`：修改后重审；当时R04/R05关闭，R01/R02/R03仍有真实PG反例，见[首次整改复验记录](#n1-remediation-review)。本记录仅绑定该输入，当前结论以上方二次整改复验为准。审核证据`.tmp/n1-rereview-20261028/`，无共享库/业务服务操作。
- [审核结论] 输入`2ac212f16193a49a3e87d36745c609eaa54b24f0`首次验收：**修改后重审，N2未准入**。N1-R01—R04为P1实现缺陷，N1-R05为P2环境与验证整改；详见[首次验收与整改执行单](#n1-first-review)。N0既有批准保持。
- [审核恢复] 审核树`C:/Users/admin/.codex/worktrees/form-admin-ui/data_collection_platform` / `codex/form-admin-ui-20261027`已快进吸收输入；员工树`D:/projects/dcp-form-w4-20261026`只读。审核证据归本树`.tmp/n1-review-20261027/`；独立正式测试12/0/0/0，并以真实PG复现八项边界观察。未使用15432/15433、共享服务、业务页面或外部调用。
- [当前阶段] N1-R01—R05整改已完成并送复验；下面“首次交付”和“整改交付”都只描述提交范围，不代表验收通过。审核若给出新反例，仍以本计划的整改执行单为唯一关闭条件入口。
- [已完成] P1 `V20261027_01__form_v6_directory.sql`（范围集合/版本角色/实例模式/来源表、定位目录、可比登记表、目录事件/游标/水位、`form_spec_hash`/`form_source_set_hash`/`form_create_fingerprint`/`form_advance_record_sequence`、可读集合视图），校验和已登记（159→160，仅新增）；P2 `FormV6RegistrationService`（路径A/B、precheck 一致性强制、守恒断言、幂等重入、漂移/重复归属/证据缺失整体拒绝）与 `FormRecordDirectoryService`（目录写入接入六步创建、跨版重放按 origin 证据复算 v5 指纹）；P3 `FormRecordDirectoryReader`（find/listByResource/captureSourceSet，排除 TARGET/LEGACY_SNAPSHOT，延迟回表）；稳定语义登记 `FormComparableRegistrationService`（声明=冻结类型校验、身份冲突拒绝）。
- [整改交付] 已快进吸收审核回执`0617d4d5`（仅文档，frontend相对`94749f3a`零差异）。R01：承载证据改为确定性全值快照（业务值、修订号、作废、更新时间、实例与完整资源C、origin规格哈希），持锁后重算并与预检比对；幂等重入逐条比对目录行并按统一读源实际回表，部分目录/同数量错身份/证据不符一律409输出差异，失败零副作用。R02：归属判定只信任`FormScopeValidationService`的真实绑定范围，唯一属于他范围的旧行不入本次选集，真重叠与未覆盖仍整体拒绝；承载实例的来源配置/来源实例/地址/项目/资源类型/模板必须落在范围绑定内，目录写实例自身证据，不由范围伪造来源。R03：`LEGACY_DEMO_FIELD_SPEC`物理列改回旧表真实列（`reviewer`、五个`*_score`、`remark`等），NONE记录按版本登记的`source_table`原位回读，业务读取排除作废并核验承载身份/完整C/创建人，缺行或失配明确拒绝，`find`与`listByResource`共用同一载荷契约（键为冻结规格物理列名），另保留`findIncludingVoided`显式定位用途并返回作废状态。R04：路径B登记前复用`FormVersionSchemaService`+`FormVersionStructureVerifier`完整结构核验，路径A新增旧物理表列与类型契约核验；删除“先迁满160条再插数据”的伪升级用例，新增`FormV6LegacyUpgradeIntegrationTest`：同一独占容器先`target=20261022.03`（159条）写入真实非空旧Demo与v5多副本，冻结原行/规格/首次指纹/迁移历史/序列快照，再升至head并逐行比对守恒、角色与形状默认值，最后在升级库上真实完成路径B与路径A登记及统一读源；版本夹具改用正式生成器+核验器建表。
- [整改验证快照] 定向正式测试20项全绿（迁移3、登记10、语义与读源6、真实升级1），在最终字节状态复跑一次；默认后端套件2029项0失败0错误1跳过（既有`GitlabWhitelistServiceIntegrationTest`），连接串显式绑定本任务专属容器`dcp-n1r-test-pg-15499`（`127.0.0.1:15499`），收尾已删除并回读为0残留。本轮未连接15432/15433，未回退或改写共享对象；共享环境的历史误操作只做只读取证，见`N1-R05只读取证与隔离收口`。首次交付的12/0/0/0与三轮默认套件结果不再当作本轮证据引用。
- [阻塞] 数组编码整改已交付，等待审核复验R01关闭。R02—R05保持关闭，不重做已关闭项。复验通过前N2、W5、main与正式包仍不准入。以下员工交付快照不代替当前审核结论。

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

<a id="n1-first-review-remediation"></a>

## N1-R01—R05 整改交付回执（员工，2026-10-28）

**输入**：审核回执`0617d4d5`（快进吸收，仅3份文档）；整改基线`2ac212f1`。
**范围**：只改已准入的N1后端与正式测试；frontend相对批准UI`94749f3a`零差异；未新增迁移（第160条不可变，本轮无需DDL纠正）；未进入N2/W5，未合main，未出包，未跑黄金。

### 逐条关闭证据

| 项 | 复现的缺陷 | 关闭实现 | 正式回归 |
| --- | --- | --- | --- |
| N1-R01 | 承载记录`score 3→99`、`record_version 1→2`后`rowHash`不变；源2条/目录1条仍报“已完成登记” | `carrierFingerprint`覆盖身份、承载/origin版本、规格哈希、实例、完整C、request_key、账号、首次指纹、修订、作废、两个时间与全部业务列值；`requirePrecheckMatch`持锁后重算比对；`requireLegacyEvidence`/`requireCopiedEvidence`逐条比对目录列并`reader.findIncludingVoided`实际回表，差异→`FormConflictException`（“登记不完整”“登记证据不一致”），不覆盖也不补认 | `shouldRecheckFullValueEvidenceAfterCarrierValueRevisionOrVoidChange`、`shouldRejectIncompleteOrMismatchedDirectoryInsteadOfReportingAlreadyRegistered` |
| N1-R02 | A绑11/B绑12时，A预检把仅属B的`900002`报重复归属；实例改项目72与`another-source/http://other.invalid`仍登记成功且来源被范围改写 | `selectLegacyRows`按“本范围覆盖+他范围覆盖”四分类，唯一属他范围的行不入S；`requireInstanceBinding`用新鲜范围绑定核验来源配置/来源实例/规范地址/项目集合/资源类型/模板，目录写入实例自身C | `shouldRegisterDisjointProjectScopesIndependentlyAndRejectTrueOverlap`、`shouldRejectCarrierWhenInstanceEvidenceCrossesScopeBinding` |
| N1-R03 | 旧行`remark='legacy'`存在但`listByResource`返回空；旧规格映射七个不存在列；作废记录仍返回且无作废标志；`find`空载荷 | `withFields`→`readBack`：MATERIALIZED联实例表、NONE按登记`source_table`回旧表；`LEGACY_DEMO_FIELD_SPEC`改回真实列；业务读取排除作废并核验承载身份/完整C/创建人，缺行与失配明确拒绝；`find`与列表同一载荷契约；`findIncludingVoided`保留定位用途并暴露`deleted` | `shouldReadLegacyDemoValuesInPlaceThroughUnifiedReader`（含逐列存在性断言）、`shouldExcludeVoidedRecordsFromBusinessReadsButKeepLocationEvidence`、`shouldRejectBusinessReadWhenBackTableEvidenceMismatch` |
| N1-R04 | 正式核验器已报结构漂移但预检与登记放行；“非空升级”先迁满160条再插数据；夹具手写简化DDL | `verifyVersionStructures`复用生成器+核验器（路径B前置，漂移→“版本结构漂移”）；`verifyLegacyPhysicalContract`核对旧表列存在与`format_type`类型（→“旧Demo物理列契约不一致”）；`FormV6TestSupport.createRecordVersion`改用正式生成器建表并先核验；新增真实`20261022.03`→head升级测试 | `shouldRejectRegistrationWhenFormalStructureVerifierReportsDrift`、`shouldUpgradeRealNonEmptyLegacyDatabaseAndConserveEveryExistingRow` |

### N1-R05 只读取证与隔离收口

- 上一轮违约事实、16张`ods_gitlab_*`的`mirror_task_id`清单、共享`qaflex_test`迁移历史与时间、可证实/不可证实边界，见[只读取证](../../.tmp/w4-n1r-20261028/logs/r05-shared-environment.md)（`.tmp`证据，不入库）。本轮对共享库仅执行只读`select`，未删列、未回退、未重启共享容器。
- 本轮全部验证绑定自有隔离库：定向与升级测试用Testcontainers独占容器（随机端口）；默认套件用专属容器`127.0.0.1:15499`并由`TEST_DATASOURCE_URL`显式给出连接串，连接不通即失败，不回退默认15433。套件跑完后容器已删除并按名回读为0残留。
- 命令、突变与还原记录：`.tmp/w4-n1r-20261028/logs/`（`red-r01..r04.log`、`targeted-final-bytes.log`、`default-suite-isolated.log`、`r05-*.txt/md`）与`red-evidence.md`；文件SHA-256见`file-hashes.txt`。

### 结论与下一步

定向20/0/0/0、默认套件2029/0/0/1（既有跳过项）在最终代码状态下通过；四项P1与一项P2均有固定断言并在实现突变时转红。等待审核复验；通过前不进入N2，不合main、不出包、不改批准UI，已执行160条迁移保持不可变。

<a id="n1-remediation-review"></a>

## N1首次整改复验与当前执行单（2026-10-28）

**结论：修改后重审，R04/R05关闭，R01/R02/R03保持P1待整改。** 输入固定为`f698aad7af511ab07c5fa014f18bb36dff75a97a`，不是否定N0批准或要求重做本轮全部交付；N2及后续阶段仍未准入。批准UI`94749f3a`、既有160条迁移保持不变。

### 已核验证据

- 员工树`D:/projects/dcp-form-w4-20261026`干净；origin/gitlab25均回读为输入提交。审核树在原专属分支快进吸收；10个差异文件的提交字节与员工树、`file-hashes.txt`逐个一致。frontend相对`94749f3a`零差异，正式迁移及锁文件相对上轮零差异。
- 审核独立执行四类正式N1 PG测试（迁移、登记、语义与读源、真实非空升级）：20项、0失败、0错误、0跳过，`BUILD SUCCESS`，4:31。员工最终默认日志为2029项、0失败、0错误、1既有跳过，`BUILD SUCCESS`，18:24；本审核核对日志及隔离收口，不再重复整套默认测试。
- 独立探针`ReviewN1RProbe.java`调用本输入编译产物及真实服务，在审核独占PG16随机端口60454中复现下列五项观察，完整运行退出0；夹具使用正式结构生成/核验器。证据本树`.tmp/n1-rereview-20261028/`：`input-verification.json`、`directed-tests.log`、`probe-results.json`、`probe.log`、探针源码及`verification.json`。
- 审核探针容器`819d8b43fa4ea1951b5531111f9e89ba52a8a282abb91c9a97c678dbaf578e09`已按ID关闭删除，inspect无对象且任务标签下无残留。审核未连接共享库、未启动业务服务、未运行黄金、未改实现或测试源码。

### 关闭项

| 项 | 复验判断 |
| --- | --- |
| R04 | 关闭：登记已实际调用正式结构生成/核验器；新的升级测试在同一独占库先执行159条、写非空Demo/v5多副本、保留原行/规格/首次指纹/历史/序列，再执行第160条，随后登记并回读原值。原先类型漂移放行和伪升级测试缺口已修。后续R01—R03若影响这些路径，只做相称回归，不重新设计R04。 |
| R05 | 关闭：核对员工共享只读取证清单与边界说明、专属15499默认套件日志及删除回读。`docker exec ... psql SELECT`属于已要求的只读数据库访问，“未连接15432/15433”只能表示未使用宿主机共享端口跑业务测试，不能表述为从未访问共享对象。无法证明的其他会话影响仍明确未知，不据此推断损坏或擅自回退；审核本轮未访问共享库。 |

### R01仍未关闭：全值与幂等证据有三个缺口

1. **NULL与空字符串碰撞。** 把承载行`note_text=NULL`改为`''`，其他列不变，预检哈希前后均为`cc8cb95d991afc92c86fe5a1ed2c1d361844b43fac62e125978be4c69723d409`；用旧预检仍`REGISTERED`。输入`carrierFingerprint` L952和`legacyRowHash` L589使用`nullToEmpty`及未转义分隔拼接，不是可区分原值的全值编码。
2. **未冻结全部参与判定的副本。** v1/v2同一ID，v2为较高修订承载；只改v1的得分和说明，前后哈希均为`5a6b4182608db5862ba39d8c1a2405439520ab645c705e2325a2d6f881a7d476`，旧预检仍登记成功。分析读取了旧副本，但哈希只覆盖选出的carrier，无法证明预检时那套原始副本未变。
3. **不比较已完成登记的持久指纹。** 正常登记后，将承载行改成`score=99`并推进修订/时间；持久事件的完成哈希为`19b8301e679197c20b0a03d51438af07ac252f708d38b3ae5d41735ccae9879e`，当前哈希已变成`6406c4373810a2068294204a6cd6dcf864fa05e6c4593e33e07f37b342c8f884`。新预检仍宣称`alreadyRegistered=true`，携新预检重入返回`ALREADY_REGISTERED`且仍只有一个事件。`requireCopiedEvidence`只把目录与当前源行比较，未读取首次完成证据；A路径同样未比较持久完成指纹。N0 §3.2已明确“读取既有登记指纹，相同才幂等；源变化409”，不能把新预检哈希当作历史登记哈希。

**关闭条件**：按已批N0全值协议采用确定性、带类型/NULL边界的编码（可复用`to_jsonb`全行证据），覆盖所有参与承载/丢失判定的源副本、实例与冻结规格，不用空串或未转义分隔符代替原值。A/B重入均核对既有持久完成证据、当前源证据及逐行目录；证据变化/缺失明确409并给差异，不静默覆盖、补算或重新认定已登记。正常业务编辑可以继续，但再次调用登记工具不能把编辑后的状态冒充原登记证据；纠正沿已有新修订/补偿事件协议处理。

**正式回归**：NULL/空串、包含分隔字符的文本、非承载副本值/修订/身份/规格变化、完成后源变化及证据缺失；A/B都验证同源重入成功、源变重入409、事件/修订/目录/原始行零副作用。保留本轮已通过的部分目录和目录字段失配回归。

### R02仍未关闭：B路径比较实例与范围，却未验证范围绑定本身

**实证**：ACTIVE范围`binding_hash`改成64个0后，既有`FormScopeValidationService.listCoverageScopes`准确报“ACTIVE范围绑定哈希不一致: 4”；B路径预检和登记却成功，写入1条目录。输入`requireInstanceBinding` L876只比较实例字段与`requireScope`读取的字段，未验证来源配置、版本项目、绑定哈希及覆盖/指针约束；错误范围与实例恰好相同仍会被认可。A路径取锁前读取的`scope`也未在取锁后重新加载，不能把旧对象称为新鲜绑定。

**关闭条件**：A/B复用已有统一范围校验实现，锁后重新读取并核对当前完整绑定/来源/版本项目/指针及归属，不能由逐字段相等替代范围合法性。预检也拒绝无效绑定；跨预检/锁等待变化的绑定必须按当前证据重新拒绝，不套用旧对象。保留已修的非重叠多范围与跨来源实例拒绝。

**正式回归**：损坏绑定哈希、来源配置/实例不一致、版本项目不在集合、指针归属错误、锁等待中绑定改变，以及合法A/B登记。使用真实PG交错验证锁后判断和失败零写入；不要新增一套平行绑定规则。

### R03仍未关闭：回读没有核验实例ID及所属范围

**实证**：已登记记录的目录`instance_id=1/scope_id=5`。把物理记录改指向同一版本实例表中`id=2/scope_id=6`的实例（来源、项目、资源C相同），`find`仍成功返回业务值，结果还声称`instanceId=1/scopeId=5`。输入`readMaterializedRow` L191虽然取出`r.instance_id`，却未与目录比较，也未查询/校验`i.scope_id/template_key`；`requireSameResource` L329只比来源与资源C。完整C相同不等于实例身份与所属范围相同。

**关闭条件**：业务与显式定位两条路径共用完整承载身份核验：物理记录实例ID=目录实例ID，实际实例属于目录/版本范围且模板一致，并保留现有完整C与创建人校验；缺行、错实例、错范围或错模板均明确拒绝，不返回沿用旧身份的载荷。

**正式回归**：同C不同实例ID、实例范围/模板失配、真实C/创建人失配、缺行、作废定位与业务排除；分别覆盖`find`、`listByResource`及`findIncludingVoided`。保留已修的NONE真实值与find/列表一致性测试，不改前端。

### 员工下一步指令

1. 原专属树核对干净状态、AGENTS、输入及归属，快进吸收本审核回执；只在原N1计划下整改R01/R02/R03。R04/R05保留关闭，不重跑历史突变或重复取证共享库。
2. 先把本轮五项观察转为正式失败回归，再修N1后端。测试使用本任务自有容器/库；不使用共享15432/15433，不改UI，不进入N2/N3/N4/W5，不合main、不出包、不运行黄金。
3. 最终状态执行受影响的四类N1 PG测试、一次相称默认套件及仓库护栏；提交完整差异、逐项证据、输入指纹和资源清理。既有160条迁移不改，确需DDL纠正只新增前向迁移。按职责提交并推送双远端，回读后送N1再次复验。

<a id="n1-second-remediation"></a>

## N1二次整改交付与送审（2026-10-28）

**结论：R01—R03已按五项反例整改并送复验；R04/R05保持关闭。** 输入`e3c7e42a`（仅文档，实现同`f698aad7`），只改N1后端、正式测试与文档；N2/W5/main/正式包仍未准入，批准UI`94749f3a`与160条既有迁移不变。

### 五项观察 → 正式回归（先红后绿）

| 观察 | 正式回归 |
| --- | --- |
| R01 NULL 与空串指纹相同 | `shouldDistinguishNullFromEmptyStringInCarrierEvidence`；`FormEvidenceCodecTest`（NULL/空串/分隔符位移/数值文本） |
| R01 非承载旧副本变化未进入指纹 | `shouldDetectChangesInNonCarrierCopies` |
| R01 完成后源变化仍返回已登记 | `shouldRejectReentryWhenSourceChangedAfterRegistration`（A/B 重入） |
| R02 绑定哈希损坏仍登记成功 | `shouldRejectCopiedRegistrationWhenActiveScopeBindingHashIsCorrupted` |
| R02 来源配置/实例失配 | `shouldRejectRegistrationWhenScopeSourceConfigOrInstanceMismatches` |
| R02 版本项目不在集合 | `shouldRejectLegacyRegistrationWhenVersionProjectIsOutsideScopeProjects` |
| R02 指针归属错误 | `shouldRejectRegistrationWhenScopePointerBelongsToAnotherScope` |
| R02 锁等待期间绑定改变 | `shouldRejectWhenBindingChangesWhileRegistrationWaitsForScopeLock`（真实PG行锁交错） |
| R03 物理记录实例ID与目录不同 | `shouldRejectReadWhenPhysicalRecordPointsToAnotherInstanceId` |
| R03 实例所属范围不同 | `shouldRejectReadWhenInstanceBelongsToAnotherScope` |
| R03 实例模板不同 | `shouldRejectReadWhenInstanceTemplateDiffersFromScope` |

R03 三项均分别覆盖`find`、`listByResource`、`findIncludingVoided`；R02 损坏用例的 ACTIVE 范围在用例结束前归还为 RETIRED，避免共享测试库内的损坏绑定污染同类用例的全局复核。

### 实现

- R01：新增`FormEvidenceCodec`，按标量类型与NULL边界生成确定性证据（`null/str/int/dec/bool/date/time/arr`），替代`nullToEmpty`与未转义分隔符。A路径逐行覆盖旧表全值、作废与时间；B路径覆盖每个参与承载/丢失判定的副本（含非承载旧副本）的身份、实例、origin/storage冻结规格哈希、修订、作废、账号、请求键与逐列值。A/B重入先核对持久完成证据（`form_directory_events.payload->>'rowHash'`+carrierKind+`evidenceVersion=2`），源变化或证据缺失一律409并输出差异，不用当前源哈希冒充历史登记。
- R02：A/B在同一事务持锁后重新读取范围，并复用`FormScopeValidationService`（`validateActiveScopes`+`listCoverageScopes`）判定完整绑定/来源/版本项目/指针与归属；预检同样拒绝无效绑定；锁等待期间绑定改变按当前证据拒绝。路径A目录来源配置改用范围绑定的`sourceConfigId`，不再按URL反推（同URL多配置存在歧义）。
- R03：`FormRecordDirectoryReader`在完整C/创建人校验前新增承载身份核验：物理记录实例ID=目录实例ID、实例属于目录范围、实例模板=范围模板；三条入口共用同一实现。
- 无新增迁移；R04/R05未重做。

### 验证

- RED：最终11项正式回归在`e3c7e42a`基线上11/11失败（非编译或夹具错误），日志`red-round2-final-v2.log`；`FormEvidenceCodec`为新增类型，编解码4项单测不适用于基线运行。
- GREEN：`FormEvidenceCodecTest`+四类N1 PG测试共35项、0失败0错误0跳过，`BUILD SUCCESS`（登记18、语义与读源9、迁移3、真实升级1、编解码4），日志`green-round2-final.log`。
- 默认后端套件：专属容器`dcp-n1r2-test-pg-15499`（`127.0.0.1:15499`，`postgres:16-alpine`）**2044项、0失败、0错误、1跳过**（既有`GitlabWhitelistServiceIntegrationTest`），27:15，`BUILD SUCCESS`。首次运行在16个既存容器并行负载下MySQL Testcontainers启动等待超时（1项错误），该用例单跑42.8s通过；同代码状态重跑整套得到上述全绿结果，两份日志均保留。容器已删除并回读0残留。
- 护栏：160条迁移锁零改动；artifacts/runtime/whitespace/`git diff --check`通过；frontend相对`94749f3a`零差异。
- 证据：本树`.tmp/w4-n1r2-20261028/`（RED/GREEN/默认套件日志、`red-evidence.md`、重跑日志）。

### 边界与下一步

- 未使用15432/15433承载业务测试、未启动共享业务服务、未运行黄金、未访问内网/真实GitLab、未改UI。
- 复验通过前不进入N2，不合main、不出包；若审核给出新反例，仍以本文件整改执行单为唯一关闭入口。

<a id="n1-second-remediation-review"></a>

## N1二次整改复验与当前执行单（2026-10-28）

**结论：修改后重审，只剩R01数组编码缺口；R02/R03关闭，R04/R05保留关闭。** 输入`f69856d676b0b1476c87519113c18f5bfa19fb39`，实现`e24c6238`。本轮已修的标量NULL/空串、旧副本变化、持久完成证据比较、统一绑定与锁后重读、三个读取入口的实例ID/范围/模板校验均认可；新增编码器仍在真实JDBC数组链路丢失元素边界，因此N1整体未通过，N2未准入。

### 输入与复验边界

- 两个远端均回读为输入，员工树干净；9个差异文件的提交字节、员工树与`file-hashes.txt`逐项一致。审核树在原专属分支快进吸收，未改实现或正式测试；frontend相对`94749f3a`零差异，160条迁移及锁文件相对上轮零差异。
- 独立运行`FormEvidenceCodecTest`及四类N1 PG测试：35项、0失败、0错误、0跳过，`BUILD SUCCESS`，7:20。员工最终默认日志为2044/0/0/1、`BUILD SUCCESS`、27:15，已核对；首次MySQL Testcontainers启动超时及定向容器连接超时记录均保留，最终成功证据未替代失败记录。本审核未重复默认全量或历史RED。
- 定点反例由`.tmp/n1-rereview2-20261028/ReviewN1R2Probe.java`调用当前编译产物与真实登记服务，使用正式规格校验、结构生成及核验器、独占PG16随机端口60935。规格与两组值都通过真实约束，探针完整运行退出0。
- 审核证据本树`.tmp/n1-rereview2-20261028/`：`input-verification.json`、`directed-tests.log`、`probe-results.json`、`probe.log`、探针源码及`verification.json`。探针容器`ec76a2534ea03523c2ba5e511878034a3da3e56869dfe0942982db9f1e5492a4`已按ID关闭删除，任务标签下无残留；本审核无共享库、业务服务、外部调用或黄金操作。

### 唯一剩余项：R01 / P1，数组被二次归一化成文本

**真实反例**：同一冻结多选规格允许`a`、`b`、`a, b`三个选项。原列`text[]`只有一个元素`["a, b"]`，改为两个元素`["a","b"]`后，前后证据都变成：

```json
{"tags":{"t":"str","v":"[a, b]"}}
```

完整预检指纹前后均为`2a0c9796b75159a1dbcacc9e45dcdca94c8a5075e33a8f2a9d2bae14f385ff21`。使用修改前预检仍返回`REGISTERED`；登记完成后再把数组改回单个元素，预检继续称已登记，重入返回`ALREADY_REGISTERED`且事件仍只有1条。持久完成证据比较已经接入，但相同的错误编码使它无法识别这种源变化。

**根因（输入行号）**：`FormV6RegistrationService:801`先把JDBC `Array`经`FormEvidenceCodec.canonical`转成`List`；`:1023`调用`putValue`时，`FormEvidenceCodec:57`再次调用`canonical`。后者没有保留`List`的分支，在`:108`退化成`String.valueOf(list)`，所以`:147`的数组编码分支在这条真实链路上接不到数组。现有4项编解码单测仅覆盖标量与文本分隔符，没有覆盖JDBC数组→规范值→二次编码。

**关闭条件**：让规范化对受支持值保持类型与幂等性，数组始终保留元素类型、数量、顺序和边界，JDBC原值与已规范值走同一确定性数组编码；真实登记路径的指纹必须区分上述两组合法值。保留现有标量、NULL、持久完成证据和所有已关闭行为；保持既有合法选项、冻结规格、历史原值与批准UI。编码改变导致旧完成证据不匹配时沿既有409/补偿协议处理，不覆盖历史指纹。

**正式回归与交付**：先把上述JDBC反例转为当前输入上失败的正式测试；编解码测试覆盖直接JDBC数组、已规范List、含逗号/括号/分隔符的元素、NULL边界及重复规范化。真实PG验证相同数组重入成功、数组变化使预检失效、持久登记后数组变化409，失败时目录/事件/集合修订无副作用。只修这个R01缺口及必要测试，在最终状态跑相关编解码与登记/读源PG回归及一次相称默认套件、仓库护栏后提交双远端并回读，送N1复验；R02—R05不重做，不改前端或160条既有迁移，不进入N2/W5、main或正式包，不运行黄金。

### 员工恢复指令

在原专属树`D:/projects/dcp-form-w4-20261026`核对AGENTS、干净状态、分支与输入，快进吸收本审核回执；以本节为当前整改范围。所有验证使用本任务自有数据库/容器，不访问15432/15433；保留失败与成功日志并按实际状态报告。本轮关闭项只做受影响回归，不再重新取证共享环境。

<a id="n1-array-encoding-remediation"></a>

## N1数组编码整改与送审（2026-10-28）

**结论：唯一剩余R01数组编码缺口已修复并送复验；R02—R05保持关闭。** 输入`5a6d982f`（仅文档）；只改证据编码及必要测试。N2/W5/main/正式包仍未准入，批准UI`94749f3a`与160条既有迁移不变。

### 反例 → 正式回归

- `FormEvidenceCodecTest`：JDBC `Array` 与已规范 `List` 必须走同一编码；重复规范化保持类型与元素边界；含逗号/方括号/引号/分隔字符的元素、空数组、NULL 元素、空串、字面量`null`互不碰撞。
- `FormV6RegistrationIntegrationTest.shouldDistinguishJdbcArrayBoundariesInRegistrationEvidence`：真实 `text[]` 承载链路下，单元素`["a, b"]`与两元素`["a","b"]`为不同合法值；登记后修改非承载旧副本，预检与重入均409且目录/事件/修订/承载行零副作用；预检后、登记前数组边界变化使旧预检409且零写入。

### 实现

- `FormEvidenceCodec.canonical` 增加 `List`/`Object[]` 分支：保留元素类型、数量、顺序与边界，受支持值的规范化幂等；JDBC 原值与已规范值走同一确定性数组编码。标量、NULL 与既有编码不变。

### 验证

- RED：最终测试在输入实现（未修 `canonical`）上 3 项失败（编解码 2、真实登记 1），`red-final.log`；非编译或夹具错误。
- GREEN：五类N1测试39项、0失败0错误0跳过（登记19、语义与读源9、迁移3、真实升级1、编解码7），`green-full.log`。
- 默认后端套件：专属容器`dcp-n1r3-test-pg-15499`（`127.0.0.1:15499`）**2048项、0失败、0错误、1跳过**（既有`GitlabWhitelistServiceIntegrationTest`），21:20，`BUILD SUCCESS`。首次运行MySQL用例因容器就绪竞态连接失败（1项错误），同代码状态重跑转绿；两份日志均保留。容器已删除并回读0残留。
- 护栏：160条迁移锁零改动；artifacts/runtime/whitespace/`git diff --check`通过；frontend相对`94749f3a`零差异。
- 证据：本树`.tmp/w4-n1r3-20261028/`。

### 边界与下一步

- 未新增迁移、未改UI、未使用15432/15433承载业务测试、未运行黄金、未访问内网/真实GitLab；R02—R05未重做。
- 复验通过前不进入N2，不合main、不出包。
