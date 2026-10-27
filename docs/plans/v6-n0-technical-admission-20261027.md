## 进度与中间物

- 当前结论（2026-10-27）：**N0三审III-01—04及S1整改已交付，待四审；N0仍未批准，N1/W5未准入**。II-01、II-06、II-07保持关闭；W4冻结v5.1、R05及批准UI保持。整改对照见下方[N0三审整改回执](#n0-third-review-remediation)。
- 审核输入：三审原输入`f7fa9384`及审核交接`565332e8`已核对，双远端一致后快进吸收；本轮只改N0合同、progress、执行单、产品相关S1条款及`.tmp`探针。
- 工作区与恢复：员工树`D:/projects/dcp-form-w4-20261026`，分支`codex/unified-form-w4-isolation-20261026`，吸收后HEAD `565332e8`；修改前工作树干净。恢复先读AGENTS/进度/本文，执行`git status --short --branch`及`git worktree list`，按本轮交付提交续做，不回退员工成果。
- 已验证：隔离PG16执行public 115项、非空升级6项、真实双会话33项、共同字段93项全部PASS；共同字段三个突变均非零退出；旧版在途请求等待约3.2秒后409，接管/发布与消费交错等待约2.3/3.2秒且按新集合结算，无40P01。
- 证据与环境：本树`.tmp/w4-r5-n0-20261027-r2/verification.json`、逐项日志、`cleanup.txt`；唯一容器`b3f38037…fa81a`无共享端口且已按ID删除。前端零改动，未改业务代码、正式迁移、权限或黄金，未访问共享库/内网/真实外部。
- 下一步：送四审。员工已按三审只修订III-01—04及S1执行细节；导出正式角色清单、真实E2、MR级未批公式及未确认产品模型按既定边界处理，不新增授权或改UI。

<a id="n0-third-review"></a>
## N0三审（2026-10-27）

**结论：修改后重审。** 员工完成了部分技术整改，但“II-01—07全部关闭”与本次独立复验结果不一致。以下针对待实施合同和探针，不表示已部署系统发生这些故障。

### 本轮关闭与保留

| 原项 | 裁定 | 依据与边界 |
| --- | --- | --- |
| II-01 | 关闭 | 序列按下一可分配值处理`is_called`，独立public探针对应边界通过；同一咨询锁、只前进要求保持 |
| II-02 | 未关闭，见III-01 | origin/storage身份分离及目录证据方向接受；真实既有指纹的核验仍失败 |
| II-03 | 未关闭，见III-02 | BUILDING不长期设置范围栅栏、两中间阶段接管方向接受；具体接管SQL反向取锁且切换测试不代表约定请求协议 |
| II-04 | 未关闭，见III-03 | captureToken/owner/fence/租约守卫方向接受；执行探针的锁序和pending核验与正文不一致 |
| II-05 | 未关闭，见III-04 | 真实枚举/词典域参与哈希的修订接受；实际共同字段筛选与聚合仍未验证 |
| II-06 | 关闭候选授权合同 | 当前授予与默认快照分别从真实导出权推导，已删除view推导及无条件默认授予。正式角色名单与跨模板授权影响仍须目标库只读核对并获用户确认；确认前不执行新权限授予 |
| II-07 | 关闭本轮入口整改 | 基础DDL→修订DDL→探针顺序已补，资源按成功create返回的自有ID清理；独立SQL入口顺序通过，五模式材料与脚本已审读，未独立复跑全部模式。N1正式测试工具仍遵守退出码/清理失败报告要求 |

W4/R05、批准UI和二审两项方向保持通过。已关闭部分不要求重做。

### 剩余四项与验收

以下§号指下方员工`f7fa9384`修订合同；脚本路径均相对于其`.tmp/w4-r5-n0-20261027-r2/`。

| 编号 | 等级与位置 | 实证及影响 | 关闭条件 |
| --- | --- | --- | --- |
| N0-III-01（续II-02） | P1，§1.3/§2.1/§3.1，`probe.sql` P16 | v5真实`FormFieldValueService.fingerprint`将scope、完整context、原storageVersionId、actor、fields按既有JSON序列化后计算SHA-256；新函数改为originSpecHash等另一格式。§3.1又要求原样保留旧`create_payload_hash`。独立按实际Java构造顺序生成旧格式摘要，登记保留后调用本稿`probe_replay`，同一请求返回`PAYLOAD_MISMATCH`。P16夹具却先把两个旧副本的hash改为新算法，因此没有证明真实升级 | 给出唯一可执行的指纹合同：可以统一沿用现有算法并按原创建版本核验，或明确历史算法的证据和有限验证边界；不能默默重算已提交的首次指纹。旧行已编辑/迁移时也不得拿当前字段重建首次请求。用真实旧格式hash、不同origin/storage、第一次请求载荷、后续编辑值构造非空升级，旧key须返回同一ID且原行/审计/head均不变；错误账号、完整C、字段和作废仍拒绝。探针不得先用新算法改造历史证据 |
| N0-III-02（续II-03） | P1，§4.2，`switch-takeover.sql`、`switch-a.sql`、`switch-b.sql` | 正文统一锁序为序列→范围→任务，但SWITCHING takeover段和脚本直接先锁任务。独立双会话按约定顺序锁范围并请求任务，与原接管脚本形成真实`40P01`。现有切换会话A也先锁任务；会话B不带expectedStorageVersionId，直接按最新指针插入固定目标表，所以“等待后写新版本”不能证明旧版在途创建的正确性。现有夹具还全是ACTIVE范围，未证明首次启用 | 所有领取/接管/DDL/enter/apply/失败收口脚本与正文统一为序列→范围→任务→head，先锁后按`clock_timestamp()`复核，不依靠重试消化死锁。按同一guard执行真实并发。明确并测试enter已提交但apply未执行的窗口、过期接管和崩溃回滚；携带旧版ID和原key的在途新增在切换后必须按约定409，不静默改投新版；新请求使用新版可成功。首次启用明确DRAFT→ACTIVE、NULL旧指针及初次集合/head登记，覆盖零记录无空实例和首条新增闭环。N1最大停写时间/监控不能替代当前事务合同 |
| N0-III-03（续II-04） | P1，§5.2/§5.3，`probe_capture/probe_settle` | 正文要求范围→head，实际`probe_settle`先head再范围；独立与范围→head事务交错，复现`40P01`。探针也没执行正文规定的pending修订核验：把head的pending集合修订/哈希和pending权威修订改成不匹配值后，`probe_settle`仍返回1并置CONSUMED | 将领取后捕获与结算的执行函数统一到正文锁序，不能用另一套简化函数证明协议。列全scope/head/capture/权威/pending的精确比较及NULL语义；分别验证每项单独失配均零公开零结算，特别是pending集合、pending权威与当前权威类型/修订。所有失败、回收和重试状态更新也须有效owner/fence/租约guard，不能改新owner。用真实双会话覆盖消费与发布/接管交错，消除上述死锁和失配仍结算反例 |
| N0-III-04（续II-05） | P1，§7.1/§7.3，`common-fields-probe.mjs` | 域哈希已有实质修正，但“2+3=5”仍直接读取两个对象的`specification_count`后reduce，没有使用共同字段选择结果。把V2规格里的该字段改为新稳定身份，81项仍全部通过且合并为5；把“无共同字段”样例替换成两个完全相同的语义键，81项仍全过。后者只断言键存在，既没求交集也没输出无共同字段判定 | 定型可直接实施的共同字段选择/聚合函数输入输出，身份+语义键+类型/单位/域合同参与实际筛选，聚合只消费筛选结果。探针用真实两版规格及按其身份存储的记录执行该函数：相同字段2+3=5、显示改名仍5、新身份不合并、异义/异域不合并、确无交集返回明确结果，并验证缺字段/NULL/0/作废/贡献版本。去掉只证明常量或元数据存在的统计结论，突变破坏身份或域比较必须转红 |

III-01证据中旧格式摘要为`7168507c…590db88`，提案新函数返回`3c626d48…7dde49c`，采用同一账号、资源、字段。算法事实来自本次输入的`FormFieldValueService.java:152`；审核按其明确的JSON构造顺序与ASCII样本独立重建SHA-256，没有运行Java业务服务。该区分足以证明两种格式不一致，不把它表述成真实库已故障。

### S1及既有规则补充

项目名称多选、必要代码平台单选、自动带出、复用SmartSelect和业务命名的方向接受，保留当前UI。实现前补齐以下具体合同，仍不改frontend：

1. SmartSelect目前过滤传入的候选并发出search事件；分页接口只写了名称/path/fullPath模糊匹配。明确名称/拼音/首字母搜索如何覆盖尚未加载的页，不能只对当前20项做本地过滤后宣称与评审页一致。
2. 完整项目路径须考虑父命名空间，默认ODS与DGM按真实来源映射隔离，不能拼一个命名空间段或混入另一个平台的同ID项目。项目候选ID、管理端数字ID的边界和版本所属项目从多选中如何明确确定，均给固定样例，不任意取第一项。
3. 上轮要求的D-21样例仍需补齐：无有效回复、最新响应计划非法、较早响应计划合法。分别写明“页面最新响应仍为最新整份”和“延期豁免按D-21继续检查较早响应”的预期；本稿的有回复样例绕过了该分支，不算覆盖。规则沿用主方案§3.5与D-21，不重开业务裁定。

新增独立表单、版本绑定解耦、退役后恢复的具体模型仍待用户确认；它们不作为当前四项技术整改的阻塞来源。

### 独立验证与下一步

审核输入四文档及探针清单全部指纹一致，送审分支双远端回读均为`f7fa9384`。独立PG16执行public 82项及非空升级6项通过，原Node 81项通过；另完成真实旧格式hash核验、两处双会话死锁、pending失配及两种共同字段验证突变。两个突变均保留81项全通过，证据说明原测试缺乏对应判别能力。没有宣称独立重跑员工全部185项或五种入口模式。

证据在当前审核树`.tmp/n0-review-r3/`：`verification.json`、`actual-v5-fingerprint.json`、`real-v5-fingerprint-replay.log`、`publication-lock-inversion.log`、`consumer-lock-inversion.log`、`pending-evidence-mismatch.log`、`semantic-test-adequacy.json`及两个突变目录。唯一PG16容器`8bc80a38655b8658c919911cf299238233817209d882068a428a31d1e0eb2d9c`无映射端口，已按ID删除并inspect确认不存在。附加的目录事件/旧版写入观测只作为探针边界证据，不据此单独宣称生产缺陷。

下一步只修订III-01—04及S1执行细节，修改N0正文和相称的`.tmp`设计探针；每项给真实固定输入、唯一执行合同、结果与反例转红证据，提交完整差异及指纹。保留原审核记录，正式状态以本节为准；不要重做II-01/06/07，不用新增测试数量代替缺失行为。**N0明确批准前不开始N1/W5，不改业务代码、正式Flyway、权限授予或frontend，不运行黄金、不合main、不出包。**

<a id="n0-third-review-remediation"></a>
## N0三审整改回执（员工，2026-10-27）

> 本节只回应三审III-01—04及S1执行细节；II-01/06/07保持关闭，不重做。下列状态均以本稿探针实际执行结果为准，N0仍未获批准。

| 三审项 | 整改位置 | 实际改动与反例转红证据 |
| --- | --- | --- |
| III-01 真实v5指纹 | §1.3、§2.1、§3.1；`revision.sql` `form_create_fingerprint`；`probe.sql` P16 | 函数改为v5 `FormFieldValueService.fingerprint` 的精确JSON顺序，`storageVersionId`固定取目录`origin_version_id`，`fields`用保序`json`文本；不再把`originSpecHash`放进哈希。P16夹具使用独立计算的旧格式哈希`e31d3a8d…86974d8`（不是新函数反向生成），SQL函数逐字节复现；上稿`jsonb_build_object`算法必然不同。旧key返回同一ID且零新增/审计/head；承载版本/当前编辑值/异账号/六列完整C/作废均拒绝；编辑后原版行与目录origin/storage不变 |
| III-02 发布锁序与首次启用 | §4.2；`switch-enter/apply`、`write-old-request`、`write-new-request`、`fence-write`、`crash-a`、`recover`、`switch-takeover`、`publish-interleave-hold`、`first-enable-*`、`first-create`、`concurrency-check` | 所有领取/enter/apply/接管/DDL/失败收口/首次启用脚本统一为序列→范围FOR UPDATE→任务FOR UPDATE，claim/DDL在副作用前后复核owner/fence/租约。真实双会话：enter提交后写入收到可重试栅栏；apply持锁约4秒，旧版在途请求等待约3.2秒后返回409且无旧版目标行，新版请求成功；崩溃事务终止后阶段/指针/栅栏/目标全回滚；SWITCHING过期接管幂等；首次启用DRAFT→ACTIVE、NULL旧指针、零记录无空实例、首条新增生成实例/记录/目录/审计/head |
| III-03 消费锁序与pending | §5.1、§5.2；`capture-settle.sql`（public与p3db共用）；`probe.sql` P18；`concurrency-*`、`consume-wait-*` | 捕获/结算统一为范围FOR SHARE→head FOR UPDATE；捕获快照pending记录身份/修订/承载版本、pending集合、pending权威；结算逐项用`IS NOT DISTINCT FROM`比较scope/head/capture/当前权威/pending。分别单独修改pending集合修订、集合哈希、pending权威、当前权威、pending记录ID/版本、head最新版本，均零事实零结算，恢复后才结算；失败/重试更新受owner/fence/租约guard，旧worker不能改新owner状态。消费与发布、消费与接管两方向真实交错均无40P01，按提交后新集合结算 |
| III-04 共同字段实际选择 | §7.1、§7.3；`common-fields-probe.mjs` | 新增`resolveField`/`selectCommonFields`/`aggregateMetric`：稳定身份+语义键+类型/单位+冻结域哈希参与交集筛选，聚合只消费common。真实两版2+3=5、显示改名仍5、新`stableId`→onlyInSome且不聚合、语义键变化→unmerged、真实`affected_kinds`选项变化→unmerged、无交集→NO_COMMON_FIELDS且total=null；缺字段/NULL/0/作废/贡献版本分别验证。正常93项通过；`identity`/`no-common`/`domain`三个突变均非零退出 |
| S1 执行细节 | §11.1—§11.4 | 补齐服务端跨页名称/路径/拼音/首字母搜索、父命名空间完整路径、ODS/DGM来源隔离、项目候选ID与管理端数字ID边界、版本所属项目显式选择；补无有效回复、最新响应计划非法、较早响应合法三个D-21样例。仍不改frontend |

**验证与清理**：唯一执行入口`run-probes.ps1 -Mode normal -Port 15609`，容器`b3f3803759343475188f591080e7203f8c527bc01a63d12367772671a07fa81a`，public 115、非空升级6、真实双会话33、共同字段93全部PASS；`oldRequestBlockedSeconds=3.2`、`takeoverConsumeWaitSeconds=2.3`、`publishConsumeWaitSeconds=3.2`；容器按ID删除并`docker inspect`确认不存在。前端零改动，未执行正式迁移、权限、黄金、main或打包。

以下为二审历史记录及本次冻结提案，不能按其中“全部关闭”或“唯一生效”字样越过三审结论。

<a id="n0-second-review"></a>
## N0二审（2026-10-27）

**结论：修改后重审。** 本次审核的是尚未实施的v6合同及验证方法。以下反例不表示共享业务库或已部署消费者发生了这些错误；它们说明当前提案仍不能直接交给实施AI。

### 已接受的修订与两项裁定

接受protocol列/阶段约束、单一版本角色来源、空实例LEGACY形状、目录优先判重和字符串ID的设计方向；登记提交后只追加修订/补偿、不删已提交证据的改法关闭原R02的回退争议。三模板字段元数据已列全，九项选择器删除了原来新增的“最新非空回退”和分支门槛，这些已改部分不要求重做。

§10.2两项裁定均批准其方向：

1. v2初始阶段用`VALIDATING`，同时保留部署前停止旧执行器、受控收口旧任务的前提。新阶段不能代替部署隔离。
2. 耗时只读构建在事务外，提交事务短时间锁范围和head并复核后原子公开。无需从捕获一直持锁到构建结束；捕获归属、提交租约及受控DDL仍须补齐下列执行合同。

这些裁定不构成N1准入。W4/R05和当前UI批准保持，不重新打开已关闭的旧版整改。

### 剩余关闭条件

以下§号均指下方员工冻结修订稿，避免新增反馈改变行号后定位失效。

| 编号 | 等级与位置 | 具体问题与影响 | 关闭条件与验证 |
| --- | --- | --- | --- |
| N0-II-01 | P1，§1.4序列函数、§2.3 | 函数只看`last_value`。独立PG中序列为`last_value=1,is_called=false`，登记最大ID为1后，下一次`nextval`仍返回1；新建会撞已登记身份。既有实现已读取`is_called`，不能在新合同中退化 | 用下一可分配值与登记高水位比较，必要时`setval(high,true)`，保持同一咨询锁及只前进。覆盖相等且未调用、相等已调用、高于/低于、跨范围及登记后立即创建；断言新ID大于全部已登记ID，失败允许空洞 |
| N0-II-02 | P1，§2.1、§6 | key命中后以物理承载版本`v.field_spec`描述首次指纹，又要求请求版本属于可读集合；§6还要求按`originVersionId`编辑。对“v1创建→旧v5迁到v2→v6登记v2为权威、v1为历史副本”，创建版本与承载版本不同，旧key可能被拒或按错误规格校验，编辑也可能走错表 | 逐步写明原创建版本/首次冻结规格的持久来源、目录承载定位和两个不同校验路径。已有key核验允许验证同范围的原始版本证据，不能因它不再是正常读取集合就拒绝；正常读取只取权威副本。编辑按目录`storageVersionId`及其规格/CAS，`originVersionId`只保留创建事实。用上述真实非空形状验证旧key返回同一ID且零新增/审计/head变化、现有记录可正确编辑，并覆盖异账号/C/载荷拒绝 |
| N0-II-03 | P1，§4.2 | 只写了领取`VALIDATING`和恢复`FAILED`，没有崩溃后过期`BUILDING/SWITCHING`的领取/结算路径；领取时即整范围停写并贯穿事务外构建，仍沿用旧复制期长停写；“受租约保护”的事务外DDL未给出副作用发生前如何阻止旧执行者 | 给出两中间阶段的过期接管、attempt与fencing及失败/重试转移；明确空结构构建与当前记录写入的并发边界，把范围写栅栏限定在实际需要的短切换区，符合主方案§3.4。每次DDL/阶段更新用遵守统一锁序的短事务，在副作用前及提交前复核有效owner/token/租约，失配整体回滚；不能只在DDL之后校验。真实双会话覆盖构建中崩溃、切换前崩溃、旧执行者迟到DDL/结算、当前记录可写及切换原子性 |
| N0-II-04 | P1，§5.1—§5.3 | 捕获只写`insert/overwrite`，没有锁head验证捕获者身份；独立PG可让过期worker以fence19覆盖当前fence20的捕获。P10实际CAS在租约过期时仍能结算；正文虽有前置租约assert，但未明确最终公开时的owner/租约/captureToken复核，不能用P10宣称证明过期执行者零副作用 | 捕获短事务按范围→head锁序校验owner/fencing/租约，持久捕获身份；公开/结算同事务绑定本次captureToken及其owner/fence，复核权威类型、修订和pending修订，在最终写入前按`clock_timestamp()`复核有效租约，任一不符回滚事实与head。旧worker不得覆盖或消费新捕获。补两worker接管、捕获覆写、累计耗时跨租约、权威FORM→COMMENT→FORM及集合切换交错。另补§5.1按完整资源C定位的实际查询/索引：目录没有C、LEGACY无实例，不能把`listByResource`留作全表扫描占位；事件upsert明确仅新head初始化1、既有head单调推进，不回退已消费版本 |
| N0-II-05 | P1，§7.1/§7.3及共同字段探针 | 语义哈希使用固定枚举域名称，没有绑定真实选项稳定ID或原因词典版本/内容。独立反例修改真实`affected_kinds`选项ID，当前探针的语义哈希仍相同；95项计数主要证明元数据存在，不能证明异义枚举隔离及实际跨版统计 | 将可比域解析绑定冻结选项/词典的稳定语义契约及版本/内容哈希，明确显示改名与实际语义变化的区别。用真实规格的两个版本运行可比判定与聚合：2+3=5、零共同字段、同名异义、新身份、真实枚举/词典变化均验证，不能只对自造常量求和。逐指标分母引用已确认来源；MR级未批汇总公式仍只列影响，不在此批准新公式 |
| N0-II-06 | P1，§8.2授权SQL | 同时有两个查看权限就被授予导出，不能证明用户原有导出资格。独立PG样例为“原导出=false，新导出=true”；默认快照又对五角色无条件授予，出现当前导出=false、默认=true，恢复默认后可能扩大授权 | 列出明确批准的新权限授予依据；若沿用原导出能力，分别按当前授予与默认快照的真实导出权推导，禁止由查看权推导。补端点、项目/资源C可见范围的精确服务端谓词，不能只检查请求projectIds属于表单绑定范围。覆盖仅查看无导出、撤销导出、恢复默认、部分项目可见、越权资源与空版本表头。当前样例未获授权，不执行正式权限迁移 |
| N0-II-07 | P2，§9.1可执行入口 | 入口只应用基础DDL和`probe-public.sql`，漏了`revision.sql`；独立按文档顺序运行报`record_source_role`不存在。`docker run`丢弃返回ID且未检查退出码，finally按名称重新查找，会在同名创建失败时清理别人的容器 | 文档入口与实际验证用同一脚本：基础DDL→修订DDL→探针，逐条检查退出码及就绪超时。仅保存本轮成功创建返回的容器ID；创建失败无ID则零清理，只对所持ID执行finally，清理失败另报且保留原失败码。覆盖正常、DDL失败、就绪超时、同名冲突及端口占用，确认占用者存活；提供固定调用、日志和清理证明 |

九项选择器的补充澄清沿用主方案§3.5：给出“无回复、最新响应计划非法、较早响应命中D21”样例，分别写明页面最新字段与D21豁免结果，不能把“仅响应按最新合法计划”解释成最新非空回退或取消既有豁免。它是既定规则的执行说明，不新增业务选择。

### S1：本轮新增用户要求的吸收

员工本次冻结输入为`c9a7aa44`；以下补充在其后提交，因此不作为违反原交接的缺陷。但下一稿必须吸收`c2fc3cb1`及`a6d7bdd2`中已确认要求，按[产品文档](../product.md#form-admin-approved-ui)补合同：

- 项目候选来自已同步的真实GitLab目录，名称/路径搜索、多选；必要的代码平台单选，能推导的自动带出。复用评审数据管理页的`SmartSelect`及搜索、回显、清空行为，不能让管理员输入项目编号。
- 逐项给出候选物理来源、API、权限、分页和未同步/失效/失败行为；不让浏览器直连数据库，也不把已有统计记录当完整项目目录。
- “版本成员”当前实为版本分支字符串，须用用户能懂的业务名称并核实候选来源；人员、里程碑、分支和表单结构版本各自明确。提供可见名称表与常用任务最短流程，能自动填写的信息不再要求重复输入。
- 新增独立表单、版本绑定解耦、退役后恢复的具体模型仍待用户确认，只列影响与待确认项，不自行写成批准设计。保留当前管理页布局及视觉，本轮不改frontend。

### 本轮验证与复审交付

审核输入、员工三文档SHA-256及SQL/脚本指纹均已核对；独立PG16重现public 12项通过，独立执行共同字段95项通过。追加反例日志依次为`sequence-equal-unused.log`、`employee-cas-expired-lease.log`、`unguarded-capture-overwrite.log`、`export-view-only-and-defaults.log`、`documented-entry-without-revision.log`；枚举反例为`semantic-domain.json`。证据归当前审核工作树`.tmp/n0-review-r2/`，总索引`verification.json`。P10反例针对员工探针的实际谓词，不能据此声称已部署Java消费者缺陷。

唯一容器`b08a78c1415fc189756406393a8a75ce6b19195f8df3e6db7ea57dc03328f763`无宿主机端口，已按ID删除。SQL仅运行一轮；审核工具末尾Node输出曾因主机GBK解码失败，修正UTF-8后只补跑Node，原日志保留。未重新运行非空P2、后端/前端全套、浏览器、黄金或真实链路；旧结果不替代新版实现验收。

员工下一步只修订本文生效合同及相称的`.tmp`设计探针；按N0-II-01—07和S1提交改动位置、固定输入、预期/实际、完整日志及新指纹。原始输入和审核记录保留，正文明确修订后唯一合同，不能让已否决段落继续充当执行规则。提交前证明frontend相对`94749f3a`零差异、159条旧迁移未变，执行仓库护栏并同步双远端。**N0明确获批前，不开始N1/W5、正式DDL或权限授予。**

## N0生效合同（2026-10-27，二审整改后）

> 状态：**本节是唯一生效合同**，已按[N0二审](#n0-second-review)关闭II-01—II-07并吸收S1；被否决段落已删除或改写，不再作为执行规则。R05已关闭、W4冻结v5.1通过；**N0仍未批准、N1/W5未准入**。不含业务实现、不改正式迁移、不运行黄金、不合main、不出包。
>
> 输入：员工分支`codex/unified-form-w4-isolation-20261026`，已快进吸收审核交接`d338f843`（含`c9a7aa44`；UI基线`94749f3a`，`git diff --exit-code 94749f3a -- frontend`无差异）；事实来源为本分支实际迁移与代码，逐节引用。
>
> 验证：单入口[`run-probes.ps1`](#n0-run-entry)在唯一命名PostgreSQL 16容器执行：public 115项、非空升级6项、真实双会话33项、共同字段93项全部PASS；共同字段三个突变均非零退出；正向、DDL失败、就绪超时、同名冲突、端口占用五个模式均只清理本轮捕获的容器ID。证据与指纹见§9.2。

### 0.1 二审关闭对照

| 二审项 | 关闭位置 | 本稿结论 |
| --- | --- | --- |
| II-01 序列`is_called` | §1.4、§2.3 | 按“下一可分配值”比较；相等且未调用时`setval(high,true)`；其他情况零改动；同一咨询锁、只前进 |
| II-02 origin/storage混用 | §1.3、§2.1、§2.2、§6.1 | 目录持久化`origin_version_id/origin_spec_hash/create_payload_hash`；旧key按原版证据核验；编辑按`storageVersionId`及规格/CAS，origin只保留创建事实 |
| II-03 发布接管/长停写 | §4.2 | BUILDING不设范围栅栏；DDL/阶段更新用短事务并在副作用前后复核owner/token/fence/租约；给出VALIDATING/BUILDING/SWITCHING接管与崩溃回滚 |
| II-04 捕获/结算归属 | §5.1—§5.3 | 捕获与结算绑定captureToken/owner/fence/`lease_until > clock_timestamp()`；旧worker不得覆盖/消费新捕获；补资源C索引与事件单调推进 |
| II-05 语义哈希 | §7.1、§7.3 | 绑定真实选项稳定ID或词典版本+内容哈希；显示改名不变、选项/词典变化必变；以真实双版本验证2+3=5及零共同字段 |
| II-06 导出授权 | §8.1、§8.2 | 只按既有导出权复制当前授予与默认快照，禁止由查看权推导；端点显式键+版本/项目/资源C谓词；不执行正式授权迁移 |
| II-07 可执行入口 | §9.1、§9.2 | 基础DDL→修订DDL→探针逐条检查；`docker create`捕获ID后再`start`；只清理所持ID；覆盖正常/DDL失败/就绪超时/同名/端口占用 |
| S1 管理页功能 | §11 | 项目名称下拉/SmartSelect、权威候选接口、业务命名、最短流程；本轮不改frontend |

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
| Q8 | §8.2 | 只按既有导出权复制当前授予与默认快照，禁止由查看权推导；正式名单另行确认 |
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

-- 集合哈希：仅AUTHORITATIVE版本，按版本ID升序；绑定角色与规格哈希，不依赖扩展
create function form_spec_hash(p_spec jsonb) returns char(64)
language sql immutable as $$
    select encode(sha256(convert_to(p_spec::text, 'UTF8')), 'hex')
$$;

create function form_source_set_hash(p_scope_id bigint) returns char(64)
language sql stable as $$
    select encode(sha256(convert_to(coalesce(string_agg(
        v.id::text || ':' || v.record_source_role || ':' || form_spec_hash(v.field_spec),
        ',' order by v.id), ''), 'UTF8')), 'hex')
    from form_template_versions v
    where v.scope_id = p_scope_id and v.record_source_role = 'AUTHORITATIVE'
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
    storage_version_id bigint not null,          -- 当前承载位置
    origin_version_id  bigint not null,          -- 创建时版本，只作创建事实
    instance_mode      varchar(16) not null,
    instance_id        bigint,
    source_config_id   bigint not null,          -- 完整资源C，定位不扫版本表
    source_instance    varchar(128) not null,
    gitlab_base_url    varchar(255) not null,
    project_id         bigint not null check (project_id > 0),
    resource_type      varchar(64) not null check (resource_type in ('merge_request','issue')),
    resource_id        varchar(255) not null,
    request_key        varchar(64),
    created_by         varchar(128),
    origin_spec_hash   char(64) not null,        -- 首次冻结规格（原版）哈希
    create_payload_hash char(64),                -- 首次请求指纹；与key同存同无
    created_at         timestamp not null default current_timestamp,
    constraint fk_frl_version_scope foreign key (scope_id, storage_version_id)
        references form_template_versions(scope_id, id),
    constraint fk_frl_origin_scope foreign key (scope_id, origin_version_id)
        references form_template_versions(scope_id, id),
    constraint fk_frl_version_mode foreign key (storage_version_id, instance_mode)
        references form_template_versions(id, instance_mode),
    constraint ck_frl_request_key check (
        request_key is null or btrim(request_key) <> ''),
    constraint ck_frl_create_evidence check (
        (request_key is null) = (create_payload_hash is null)),
    constraint ck_frl_resource check (btrim(resource_id) <> ''),
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
create index idx_frl_resource on form_record_locations(
    scope_id, resource_type, resource_id, project_id);
create index idx_frl_origin on form_record_locations(origin_version_id);
```

- `storage_version_id`决定正常读取/编辑回表与规格校验；`origin_version_id`只决定旧key重放时的首次规格证据。两者不同是合法常态（v5承载副本、原生版本登记）。
- 首次指纹持久在目录，不依赖任一版本表是否仍可读：`create_payload_hash`仍采用v5 `FormFieldValueService.fingerprint`的既有算法与JSON顺序（`scopeId → context{gitlabBaseUrl,projectId,resourceType,resourceId,templateKey} → storageVersionId → actor → fields`，紧凑JSON后SHA-256）。`storageVersionId`分量固定取目录`origin_version_id`，因为这是唯一能重现历史首次哈希的版本；`origin_spec_hash`只作原版规格证据，不进入哈希。`fields`必须是按原版规格字段顺序生成的规范字段JSON文本（保留键顺序），不得用`jsonb`重排。原版规格不可篡改；若哈希不匹配即`ORIGIN_EVIDENCE_MISSING`并拒绝重放。
- `instance_mode`随目录行保存并由复合外键锁定到版本，避免用触发器猜版本模式；原生记录`instance_id`非空且属于该版实例表（应用层插入前校验实例表`scope_id/version`）。
- 完整资源C落在目录：`listByResource(scope, C)`是`where scope_id=? and resource_type=? and resource_id=? and project_id=?`的单索引查询，不扫版本实例表，也不因LEGACY/NONE无实例而退化。
- `record_id`沿用`form_record_id_seq`；删除/作废不删目录、不换ID、不搬表。
- `request_key`只对真实请求存在；旧Demo全部为NULL，允许重复NULL。
- 旧Demo登记的`origin_version_id`即其LEGACY版本，`origin_spec_hash`取旧列固定语义规格；不伪造实例、账号或记录修订。

首次请求指纹函数与v5 Java逐字节一致（`p_fields`为`json`而非`jsonb`，保留规范化字段顺序；字符串ID必须按`Long.toString`传入）：

```sql
create function form_create_fingerprint(
    p_scope_id text, p_gitlab_base_url text, p_project_id text,
    p_resource_type text, p_resource_id text, p_template_key text,
    p_storage_version_id text, p_actor text, p_fields json) returns char(64)
language sql immutable as $$
    select encode(sha256(convert_to(
        '{"scopeId":' || to_json(p_scope_id)::text ||
        ',"context":{"gitlabBaseUrl":' || to_json(p_gitlab_base_url)::text ||
        ',"projectId":' || to_json(p_project_id)::text ||
        ',"resourceType":' || to_json(p_resource_type)::text ||
        ',"resourceId":' || to_json(p_resource_id)::text ||
        ',"templateKey":' || to_json(p_template_key)::text ||
        '},"storageVersionId":' || to_json(p_storage_version_id)::text ||
        ',"actor":' || to_json(p_actor)::text ||
        ',"fields":' || p_fields::text || '}', 'UTF8')), 'hex')
$$;
```

- **唯一核验合同**：重放只按目录`origin_version_id`作为`storageVersionId`、目录完整C、冻结账号和原版规格下的规范字段复算；请求版本只需属于该scope（含`LEGACY_SNAPSHOT`），不参与哈希分量。禁止用承载版本、当前行值或上稿`jsonb_build_object`格式重算历史首次指纹。
- **已编辑/已迁移行**：目录的`create_payload_hash`保持首次请求证据；载体行后续编辑不改变它，重放必须携带首次请求的规范字段，携带当前编辑值应`PAYLOAD_MISMATCH`。登记迁移只复制历史值，不生成新格式。

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

-- 只进不退；调用者须已持有hashtext('form_record_id_seq')事务锁（同事务可重入）。
-- 下一可分配值 = last_value（未调用）或 last_value+1（已调用）。
-- 若 p_high >= 下一可分配值，setval(p_high,true)，保证下一次nextval > p_high；
-- 否则序列零改动。相等且未调用时不会再把同一ID分配给新建。
create function form_advance_record_sequence(p_high bigint) returns bigint
language plpgsql as $$
declare
    v_last bigint;
    v_called boolean;
    v_next bigint;
begin
    if p_high is null or p_high < 0 then
        raise exception 'form_advance_record_sequence: high water must be >= 0'
            using errcode = '22023';
    end if;
    perform pg_advisory_xact_lock(hashtext('form_record_id_seq'));
    select last_value, is_called into v_last, v_called from form_record_id_seq;
    v_next := v_last + case when v_called then 1 else 0 end;
    if p_high >= v_next then
        perform setval('form_record_id_seq', p_high, true);
        return p_high;
    end if;
    return v_next - 1;
end $$;
```

- 反例关闭：新序列`last_value=1,is_called=false`登记最大ID=1，下一次`nextval`返回2而不是1；已调用且相等时序列不动，下一次返回2；高水位更高时`setval`；更低时不动；跨范围仍共用同一咨询锁。
- 失败允许空洞；不要求连续，只要求新ID大于全部已登记ID。返回值为调用后的可分配上界（登记高水位或当前已调用值），仅供审计，不参与ID生成。

### 2. 创建、跨版重放与序列事务（R03）

统一锁序（与§4/§5一致）：**记录序列咨询锁 → 范围行 → 发布任务行（如涉及）→ head行（按id）**。创建路径为“序列锁 → 范围FOR SHARE → 目录判重 → 实例/记录/审计/head”；发布切换为“序列锁 → 范围FOR UPDATE → 任务行 → head”；消费提交为“范围FOR SHARE → head FOR UPDATE”（不新增反向锁）。

#### 2.1 单一六步事务

```text
createRecord(context, requestKey, storageVersionId, actor, fields):
  1. pg_advisory_xact_lock(hashtext('form_record_id_seq'))
  2. select ... from form_template_scopes where id=? for share
     并读取 current_version_id/status/write_fenced/source_set_revision
  3. select * from form_record_locations
       where scope_id=? and request_key=?              -- 跨全部版本，先判已有key
     if found:
        assert 请求版本属于该scope的任一已知版本（含LEGACY_SNAPSHOT）-- 只校验范围归属
        assert location.created_by = actor
        assert 请求完整C（sourceConfigId/sourceInstance/gitlabBaseUrl/projectId/
               resourceType/resourceId）与目录六列全部一致
        origin = 版本(location.origin_version_id)
        assert origin.scope_id=scope 且 form_spec_hash(origin.field_spec)=location.origin_spec_hash
        canonical = 按origin.field_spec规范化首次请求字段（字段顺序与null显式保留）
        hash = form_create_fingerprint(scopeId, C, location.origin_version_id,
                                       actor, canonical)     -- v5 JSON顺序；不用当前行/承载版
        assert hash = location.create_payload_hash
        assert 记录未作废（按location.storage_version_id回表）
        return REPLAYED(原记录)                        -- 不写实例、不改记录、不切版本
  4. assert scope.status='ACTIVE' and not write_fenced
     assert storageVersionId = scope.current_version_id
  5. instance = ensureInstance(context)                 -- 自然键upsert + 行锁
     record = insert into <v.record_table>(...)
        values(instance, origin_version_id=storageVersionId, created_by=actor,
               request_key, create_payload_hash=form_create_fingerprint(
                   scopeId, C, storageVersionId, actor,
                   按v.field_spec规范化的字段), ...)
        on conflict (request_key) do nothing
     if record is null: 回到步骤3重查；仍无则409“并发写入冲突”
     insert form_record_locations(record_id, scope, storageVersionId, originVersionId=storageVersionId,
        instance_mode='MATERIALIZED', instance_id=instance.id, C五列,
        request_key, created_by=actor, origin_spec_hash=form_spec_hash(v.field_spec),
        create_payload_hash=record.create_payload_hash, created_at=record.created_at)
     insert form_record_audit_logs(...CREATE...)
     upsert form_change_heads(... pending_record_id/version,
        pending_storage_version_id=storageVersionId,
        pending_source_set_revision=scope.source_set_revision,
        pending_source_set_hash=scope.source_set_hash,
        status='PENDING')
  6. commit；任一步失败整体回滚
```

- **两条校验路径**：旧key重放只读目录的`origin_version_id/origin_spec_hash/create_payload_hash`，并按v5算法以`origin_version_id`作为`storageVersionId`复算；正常读取与编辑只读`storage_version_id`。承载副本的规格可以因迁移而与创建版本不同，重放不得用承载规格或当前字段重算，也不得因原版已是`LEGACY_SNAPSHOT`而拒绝。
- **重放不依赖当前人员目录**：只按首次冻结规格、原账号、原C与原载荷哈希校验；姓名变化不阻断。
- **旧版key**：目录命中即按原`storage_version_id`回表，不要求请求版本=当前版本；请求版本只须属于该scope已知版本。账号/完整C/指纹/作废任一不符→409；未命中但请求版本≠当前→409（结构已变化），由客户端刷新后重试，不静默换key。切换提交后，携带旧版ID与未落库key的在途新增必须409，新版本请求才可成功。
- 目录、物理记录、审计、head必须同事务；不允许延迟约束或事务外补写。
- **历史指纹不重建**：真实非空升级中，v5迁移行保留其首次`create_payload_hash`；即使载体行已被编辑或迁移，重放仍使用首次规范字段，携带当前编辑值必须`PAYLOAD_MISMATCH`。探针使用独立按v5 JSON顺序计算的旧格式哈希，不得先用新算法改造夹具。
- **真实非空反例**：v1创建→v5迁到v2→v6登记v2为权威、v1为历史副本；旧key用v1规格和v1版本身份核验并返回同一recordId，零新增记录/审计/head；编辑按目录v2及规格/CAS，v1行不变。

#### 2.2 CAS、作废与目录不变

```text
updateRecord(recordId, expectedStorageVersionId, recordVersion, fields):
  目录→版本(location.storage_version_id)；拒绝与expectedStorageVersionId不符
  assert 承载版本record_source_role='AUTHORITATIVE'（不接受历史副本）
  用该版field_spec校验字段名/类型/条件；originVersionId不参与选表
  update <record_table> set fields..., record_version=record_version+1, updated_at=?
   where id=? and created_by=? and record_version=? and deleted=false
  if 0 rows -> 409；审计UPDATE/head PENDING（source_set_revision不变）
voidRecord(recordId, expectedStorageVersionId, recordVersion, confirmVoid):
  同锁序；update deleted=true, record_version+1 where ...；审计VOID/head PENDING
```

- 作废不删目录、不换版本、不搬表；选择器与统计一律排除`deleted=true`。
- 目录不存业务值，只存定位、完整资源C与首次身份；业务值始终回表读取。
- 跨版本记录不得用当前版字段集合提交；`originVersionId`只用于展示/重放证据，编辑拒绝按它选表。

#### 2.3 高水位只进不退

- 创建只使用`nextval('form_record_id_seq')`，不`setval`。
- 登记（§3）持有同一咨询锁，调用`form_advance_record_sequence(max(old_id))`：以`last_value+is_called`推导下一可分配值；仅当高水位达到下一可分配值时`setval(high,true)`，其余零改动。
- 登记与创建、登记与切换因同一咨询锁串行；不同范围也串行（代价可接受，换取全局ID单调与目录唯一）。
- 已复现并关闭：`last_value=1,is_called=false`且登记最大ID=1时，下一次`nextval=2`；相等已调用、更高、更低、跨范围、登记后立即创建均得到大于全部已登记ID的新ID。

### 3. 既有数据登记（R02、Q1、Q9）

#### 3.1 两条登记路径

| 路径 | 物理承载 | 版本元数据 | 目录 | 创建人/key |
| --- | --- | --- | --- | --- |
| A 旧Demo `collect_form_records` | 原表原位，不搬 | 新建LEGACY版本：`storage_kind='LEGACY'`、`instance_mode='NONE'`、`instance_table=null`、`record_table=null`、`source_table='collect_form_records'`、`record_source_role='AUTHORITATIVE'`、`version_status='PUBLISHED'`、`field_spec=旧列固定语义规格` | `record_id=旧id`、`storage_version_id=origin_version_id=该版`、`instance_mode='NONE'`、`instance_id=null`、C取源行、`request_key=null、create_payload_hash=null`、`created_by=null`、`origin_spec_hash=旧规格哈希`、`created_at=旧值` | 不伪造实例、账号、记录修订 |
| B v5已复制记录 | 选择唯一承载副本原位 | 承载版本→`AUTHORITATIVE`；其余含同一逻辑记录的版本→`LEGACY_SNAPSHOT`（不删表、不改行） | 指向承载行；`origin_version_id=记录行的origin_version_id`、`origin_spec_hash=原版规格哈希`、保留真实`request_key/created_by/create_payload_hash`、C五列、`created_at` | 只排除副本，不重建；首次指纹按历史值原样复制，不按当前字段或新算法重算 |

**v5承载副本判定**（登记前报告，不猜）：按`(origin_version_id, record_id, 资源C)`分组，比较各版本表中的`record_version/updated_at/业务值`；选`record_version`最大、再`updated_at`最新、再版本ID最大的副本为承载（通常为cutover时的当前版本，保留最新值；`origin_version_id`仍记录创建版本）。若业务值不同的副本`record_version`相同→阻断；若旧副本含承载规格已删除字段的非空值→输出`field_loss_report`并阻断，待审核裁决，不合并、不逆向拆回、不补写。该scope在cutover前除承载版本外的全部版本统一置`LEGACY_SNAPSHOT`，一律不参与普通读取；不得保留为可读AUTHORITATIVE空集。**原版版本行与其`field_spec`必须保留**，供旧key重放核验；不得因不再是正常读取集合而删除或改写。登记发现`request_key`存在但首次指纹、账号或完整C证据缺失，或`origin_spec_hash`与原版规格不符时，按409阻断并要求补充证据；不得用当前字段、承载版本或新算法补写首次哈希。

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
  insert form_record_locations(record_id, storage_version_id, origin_version_id,
         origin_spec_hash, create_payload_hash, C五列, created_by, created_at, ...)
     on conflict (record_id) do nothing
     -- 已存在则逐字段比对（含origin证据/C），不同即中止（不覆盖）
  assert 目录行数=源行数、无重复record_id、无重复(scope_id,request_key)
  assert 每条目录可按(storage_version,record_id)回表命中且业务值哈希一致
  assert origin_version_id 属于该scope且其field_spec哈希=origin_spec_hash
  update scope set source_set_revision=source_set_revision+1,
                   source_set_hash=form_source_set_hash(scope_id)
  insert form_directory_events(LEGACY_REGISTERED, 新修订, payload含count/rowHash/
         carrierReport/fieldLossReport/operator)
  upsert form_record_id_watermarks(source_ref, max(id))
  assert 集合哈希=函数重算值；commit
```

- **提交后不删除、不回退**：任何纠正都是新修订 + `REGISTRATION_CORRECTED`事件 + 审计；`source_set_revision`只增。登记失败只回滚事务，旧表零变化。
- 幂等重入：同源同哈希返回既有结果，不再增修订；源变化则409并给差异报告。
- 未知账号只读；审计保留原`reviewer`业务值，不写`created_by`；有`request_key`但无原账号/首次指纹的旧行，key重放明确409，不用当前账号补写。
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
- 失败任一步→`FAILED`并记录`last_error`；构建失败不触碰范围栅栏；切换阶段失败只由持有当前`fence_token`的所有者在短事务中清除，接管者按token核对后清除；attempt保留。v2起始`VALIDATING`与“构建在事务外、提交时短暂持锁复核”两项为二审已批准方向，不再作为待选项。

#### 4.2 空结构构建、短栅栏与过期接管（II-03）

**统一约束**：每次建表/阶段更新/切换都是独立短事务，锁序仍为“序列咨询锁 → 范围FOR UPDATE → 任务FOR UPDATE（→head）”。事务开始时对任务行`FOR UPDATE`并校验`lease_owner/fencing_version/fence_token/lease_until > clock_timestamp()`，在DDL副作用之后、`COMMIT`之前再次校验；校验失败整体回滚，不能只在DDL之后检查。接管者同样先锁任务行，故旧执行者的DDL事务与接管串行；旧事务提交前会看到新fencing并回滚。仅当无其他事务持锁时，旧执行者迟到的DDL才可能先把校验与DDL执行完——因此DDL前校验与提交前校验都在同一行锁内，接管无法插入其间。

```text
claim / BUILDING takeover（短事务）:
  序列锁 → 范围FOR UPDATE → 任务FOR UPDATE
  VALIDATING: lease空或已过期 → owner=worker, lease=+5m, fencing+1,
              stage='BUILDING', attempt(VALIDATE)；不设范围栅栏，不停写
  BUILDING  : lease已过期 → owner=worker, lease=+5m, fencing+1,
              attempt(BUILD_TAKEOVER)，记录oldOwner/oldFence；仍不设栅栏
  目标表检查：不存在→新建；已存在且为空且结构完整→复用；非空或结构漂移→FAILED
  （不drop、不truncate、不复制；旧attempt的已提交空表可复用）

build（事务外，只读/建空结构）:
  每个DDL事务按上述锁序并做前后owner/token/租约复核；
  FormVersionStructureVerifier全量核验；不读当前指针代替冻结集合

enterSwitch（短事务，开始停写）:
  序列锁 → 范围FOR UPDATE → 任务FOR UPDATE
  assert owner/fencing/lease有效、stage='BUILDING'
  assert frozen_source_set_revision/hash=当前集合
  assert expected_current_version_id=当前指针、目标INSTALLED且结构完整
  stage='SWITCHING', fence_token='<new>'；范围write_fenced=true并写
    write_fence_reason='publication', write_fence_publication_id, write_fence_token,
    write_fence_at；attempt(SWITCH_ENTER)；commit
  -- 栅栏只在此刻到applySwitch提交之间存在；构建期间不停写

applySwitch（短事务，幂等）:
  序列锁 → 范围FOR UPDATE → 任务FOR UPDATE
  assert owner/fencing/lease有效、stage='SWITCHING'
  assert 范围write_fenced且write_fence_publication_id/ token与本任务一致
  重读并断言冻结集合/指针/绑定未变
  目标版本→PUBLISHED+AUTHORITATIVE；旧当前版本→RETIRED（角色保持AUTHORITATIVE，仍可读）
  scope.source_set_revision+1；source_set_hash=form_source_set_hash(scope)
  当前指针CAS更新；目录事件VERSION_ADDED/CURRENT_CHANGED
  范围内head置PENDING并写pending_source_set_revision/hash（§5）
  任务→PUBLISHED；attempt(SWITCH_APPLY,PUBLISH)；清范围栅栏与fence_token；commit
  若当前指针已是目标、版本状态已正确：只补事件/head/任务/栅栏，幂等收口

SWITCHING takeover（租约过期，短事务）:
  序列锁 → 范围FOR UPDATE → 任务FOR UPDATE
  assert stage='SWITCHING'且lease已过期
  assert 范围write_fenced且write_fence_publication_id=任务id
  若fence_token与任务不一致：foreign fence，任务FAILED，不改范围栅栏
  owner=worker, fencing+1, fence_token='<new>', lease=+5m
  按applySwitch同一契约补做/收口；目标非空或结构漂移→FAILED并只清本任务token
  attempt(SWITCH_TAKEOVER)；commit

首次启用（INITIAL_EMPTY，短事务）:
  序列锁 → 范围FOR UPDATE → 任务FOR UPDATE
  enter: assert scope.status='DRAFT'、current_version_id is null、
         expected_current_version_id/from_version_id is null、目标INSTALLED、
         frozen_source_set_revision/hash=当前空集合；stage='SWITCHING'+栅栏
  apply: 目标→PUBLISHED+AUTHORITATIVE；scope→ACTIVE、
         current_version_id=目标、source_set_revision+1、集合哈希重算；
         同事务登记范围内初始head（latest=0、pending集合=新修订）；
         attempt(SWITCH_ENTER/SWITCH_APPLY)；清栅栏
  零记录阶段不创建任何空实例；首条新增才建实例/记录/目录/审计/head
```

- **构建期崩溃**：任务留在`BUILDING`、无范围栅栏、当前记录照常写入；租约过期后按上述接管，已存在空表复用，非空/漂移拒绝。
- **切换前崩溃**：`enterSwitch`未提交→无栅栏无状态；已提交→任务`SWITCHING`+栅栏，租约过期后接管幂等完成，期间写入被短暂阻塞并收到可重试响应。
- **旧执行者迟到**：DDL/阶段更新/结算都带owner/fence/租约CAS；接管后CAS为0，零副作用。真实双会话已验证：BUILDING写入成功、切换期等待约3.2s后写入新当前版本、旧owner DDL被拒且无表残留、切换事务终止后指针/阶段/目标状态全部回滚。
- 冻结集合/指针/绑定任一漂移→零切换、任务FAILED（可恢复），不得重捕获后沿用旧预览；`applySwitch`校验/提交失配→事务回滚；当前owner仍有效时用受guard短事务置FAILED、记attempt FAILED并清本任务栅栏，owner失联则走SWITCHING接管。FAILED恢复复核冻结输入，不等→409要求新预览。
- **所有发布脚本同一锁前缀**：领取、BUILDING/enter/apply、SWITCHING接管、DDL、失败/收口、首次启用均先取序列咨询锁，再范围FOR UPDATE，再任务FOR UPDATE；不得先锁任务或依靠死锁重试。真实双会话证明接管/切换与按约定顺序的创建/消费无`40P01`。
- **enter已提交、apply未提交窗口**：enter提交后栅栏立即生效，普通写入收到可重试响应；apply提交后范围恢复可写。携带旧版ID和未落库key的在途新增必须按409返回，不静默改投新版本；携带新版本的新请求成功。
- **首次启用闭环**：DRAFT范围以NULL旧指针进入SWITCHING，apply同事务把目标置AUTHORITATIVE、范围置ACTIVE并登记初始集合/head；零记录阶段不创建空实例，首条新增才生成实例、记录、目录、审计和head，ID高于已登记高水位。
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
/** 目录按recordId/资源C定位；只接受AUTHORITATIVE且PUBLISHED/RETIRED的版本。 */
interface FormRecordDirectoryReader {
  Optional<LocatedRecord> find(long recordId);
  List<LocatedRecord> listByResource(ResourceContext c, String templateKey);
  SourceSet capture(long scopeId);   // revision/hash/versions/fieldSpecHash
}
record LocatedRecord(long recordId, long scopeId, long storageVersionId,
                     long originVersionId, String instanceMode, Long instanceId,
                     String createdBy, ResourceContext c, FormTemplateSpec spec) {}
```

- `listByResource`为单条索引查询，不扫版本表、不依赖实例表：
  `select ... from form_record_locations where scope_id=? and resource_type=? and resource_id=? and project_id=? order by created_at, record_id`，走`idx_frl_resource(scope_id,resource_type,resource_id,project_id)`。LEGACY/NONE目录行同样命中。
- 读取顺序：目录→版本规格（调用方先`capture`冻结）→回表核对C/创建人；禁止按数字ID猜表、禁止逐版扫表。
- 只读`AUTHORITATIVE`；`LEGACY_SNAPSHOT`排除列表、统计、导出与写回资格；同一C跨版可各有一条有效记录，按§7选择器取用，不按C去重成一条。
- 捕获持久化到伴生表（单一选型，不再“列或表”二选一）；捕获身份与head的owner/fence/租约绑定：

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
    pending_record_id   bigint,
    pending_record_version integer,
    pending_storage_version_id bigint,
    pending_source_set_revision bigint,
    pending_source_set_hash char(64),
    pending_authority_revision bigint,
    lease_owner         varchar(128) not null,
    fencing_version     bigint not null,
    status              varchar(16) not null default 'CAPTURED'
        check (status in ('CAPTURED','CONSUMED','DISCARDED')),
    captured_at         timestamp not null default current_timestamp,
    consumed_at         timestamp
);
```

- 捕获短事务锁序为“范围FOR SHARE → head FOR UPDATE”：先校验head为LEASED、owner/fence匹配、`lease_until > clock_timestamp()`，再写capture；任一失配返回零行、不覆盖既有capture。过期worker无法用旧fence覆盖新捕获。捕获同时快照`pending_record_id/version/storage_version_id`、`pending_source_set_revision/hash`、`pending_authority_revision`，供结算逐项复核。
- 捕获/结算函数是唯一执行合同：public探针和双会话交错探针运行同一文件，不另写简化函数；锁序和CAS谓词不得只存在于文档。

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
  begin;
  select source_set_revision, source_set_hash from form_template_scopes
   where id=:scope for share;                       -- 与切换的FOR UPDATE互斥
  select * from form_change_heads where id=:head for update;
  assert status='LEASED' and lease_owner=:worker and fencing_version=:fence
         and lease_until > clock_timestamp();       -- 过期/接管即零行
  captureToken := gen_random_uuid();
  select v.id,form_spec_hash(v.field_spec),v.instance_mode,v.source_table,v.record_table
    from form_template_versions v
   where v.scope_id=:scope and v.record_source_role='AUTHORITATIVE'
     and v.version_status in ('PUBLISHED','RETIRED');
  insert form_head_captures(head_id,capture_token,...,lease_owner,fencing_version,status)
    values(...,:worker,:fence,'CAPTURED')
    on conflict (head_id) do update
      set capture_token=excluded.capture_token, source_set_revision=excluded...,
          lease_owner=excluded.lease_owner, fencing_version=excluded.fencing_version,
          status='CAPTURED', consumed_at=null;
  commit;

build（事务外，只读捕获中的版本表与规格副本）:
  按§7选择器生成事实候选；不读“当前指针”代替集合；
  规格哈希与捕获不一致→放弃候选（零公开），不发布半成品。

publishAndSettle（单事务）:
  begin;
  select ... from form_template_scopes where id=:scope for share;  -- 保持到提交
  select ... from form_change_heads where id=:head for update;
  select ... from form_head_captures where head_id=:head;
  assert head.status='LEASED' and head.lease_owner=:worker
         and head.fencing_version=:fence and head.lease_until > clock_timestamp();
  assert capture.capture_token=:captureToken and capture.status='CAPTURED'
         and capture.lease_owner=:worker and capture.fencing_version=:fence;
  assert capture.source_set_revision/hash = 当前scope值;      -- 集合漂移零公开
  assert head.latest_change_version = capture.latest_change_version; -- 记录漂移零结算
  assert head.pending_source_set_revision/hash IS NOT DISTINCT FROM 当前scope值;
  assert capture.pending_source_set_revision/hash IS NOT DISTINCT FROM head.pending_source_set_revision/hash;
  assert capture.pending_record_id/version/storage_version_id
         IS NOT DISTINCT FROM head.pending_record_id/version/storage_version_id;
  assert head.pending_authority_revision IS NOT DISTINCT FROM 当前authority_revision;
  assert capture.pending_authority_revision IS NOT DISTINCT FROM head.pending_authority_revision;
  assert capture.authority_revision IS NOT DISTINCT FROM 当前authority_revision;
  assert 当前authority='FORM' and capture/head/scope的NULL语义逐项按IS NOT DISTINCT FROM判定;
  写事实/投影公开版本（同事务，版本号单调，拒绝覆盖已提交新版本）;
  update form_change_heads
     set consumed_change_version=capture.latest_change_version,
         pending_record_id=null,pending_record_version=null,
         pending_storage_version_id=null,pending_source_set_revision=null,
         pending_source_set_hash=null,pending_authority_revision=null,
         status='CONSUMED',lease_owner=null,lease_until=null,last_error=null,
         updated_at=clock_timestamp()
   where id=:head and status='LEASED' and lease_owner=:worker
     and fencing_version=:fence and lease_until > clock_timestamp()
     and latest_change_version=capture.latest_change_version
     and pending_source_set_revision IS NOT DISTINCT FROM 当前scope值
     and pending_source_set_hash IS NOT DISTINCT FROM 当前scope值;
  update form_head_captures set status='CONSUMED',consumed_at=clock_timestamp()
   where head_id=:head and capture_token=:captureToken;
  -- 任一影响行数≠1则整事务回滚：事实零公开、head零结算
  commit;
```

- **锁的位置**：构建在事务外；捕获短事务与结算短事务均按“范围FOR SHARE → head FOR UPDATE”锁序，最终公开/结算同事务并复核captureToken、owner、fencing、`clock_timestamp()`租约、集合修订/哈希、head最新修订、pending修订和authority_revision。范围锁只覆盖两次短事务，不覆盖构建，满足“长计算不阻塞短任务”；不采用“捕获后全程持锁”的旧方案。
- 漂移处理：集合/head/capture/权威/pending任一项变化→回滚，事实与结算均为零；仅在仍持有有效owner/fence时把head置`RETRY_WAIT`并退避，否则保持新owner状态不动。失败、回收和重试状态更新与结算使用同一“范围FOR SHARE → head FOR UPDATE”锁序及owner/fence/租约guard，旧worker不能修改新owner状态。
- **pending逐项反例**：捕获后分别单独修改`pending_source_set_revision`、`pending_source_set_hash`、`pending_authority_revision`、当前`authority_revision`、`pending_record_id`、`pending_record_version`、`head.latest_change_version`，每项都必须零事实、零结算；恢复原值后才允许结算。探针必须实际执行这些谓词，不能只断言字段存在。
- **真实交错**：双会话覆盖“消费持范围FOR SHARE→head”与“发布/接管持序列→范围FOR UPDATE→任务（→head）”两个方向；消费在范围锁上等待后按新集合捕获/结算，不出现`40P01`，也不因等待期间的集合变化错误结算旧捕获。
- 过期worker：租约过期后可被新worker领取（fencing+1）；旧worker的capture与settle CAS必然为0，零副作用。真实PG已验证：旧token覆盖捕获为0、过期租约结算为0、双worker接管后仅新owner结算、FORM→COMMENT→FORM旧捕获失效。
- 重启接管：`form_head_captures`持久化，重启后从head状态与捕获重放；已CONSUMED不重复构建；captureToken每次捕获必换。

#### 5.3 目录事件、权威类型与全量→增量（Q3）

- 事件只追加（§1.4），发布切换/登记同事务写事件并唤醒范围内head：发布→该scope全部head置PENDING并写`pending_source_set_revision/hash`；登记→按目录中的资源C幂等upsert head。
- head版本单调：新head初始化`latest_change_version=1`；既有head只能前进：
  `insert ... values(...,1,...) on conflict (source_config_id,project_id,resource_type,resource_id,template_key) do update set latest_change_version=greatest(form_change_heads.latest_change_version, excluded.latest_change_version), status='PENDING', updated_at=clock_timestamp()`。
  低修订事件不得回退已消费版本；`consumed_change_version <= latest_change_version`由约束保证（真实PG已验证1→5→3仍为5）。
- 事件消费：`select ... where status in ('PENDING','RETRY_WAIT') or (status='LEASED' and lease_until<clock_timestamp()) order by id for update skip locked limit 1`→置LEASED→扇出head（幂等）→置CONSUMED；失败置RETRY_WAIT并保留`last_error`；`form_directory_event_cursors.last_consumed_event_id`在整修订事件CONSUMED后推进，重启从游标重放。
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
- 编辑/作废请求新增`expectedStorageVersionId: string`（与既有`recordVersion: number`并存）；服务端以目录`storage_version_id`解析版本，拒绝与请求不符；跨版本记录不得用当前版字段集合提交。`originVersionId`只用于展示/重放证据，不参与选表。
- 创建请求`scopeId/storageVersionId/requestKey`仍为string，新建的`storageVersionId`必须等于当前版本；但同一`requestKey`重放时允许请求的`storageVersionId`是该scope任一已知版本（含已降为`LEGACY_SNAPSHOT`的原版），返回同一记录，不新建。
- 旧版key重放按目录`origin_version_id/origin_spec_hash/create_payload_hash`核验；响应仍返回目录`storageVersionId`与实际记录。客户端不得因重放请求版本较旧而改写本地已冻结草稿。
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
| 新增`originVersionId` | 编辑块=原记录创建版本（只展示/重放）；新建=null |
| 新增`baseStorageVersionId` | 编辑/作废冻结的物理版本；新建=当前版本 |
| 新增`versionSpecHash` | 捕获时规格哈希；变化→只读并提示 |

- 键前缀改`v2`但**同account/context/session/draftId**；读取v2失败才回退读v1并重新写入；写v2成功后才删该v1键；不合并不同`draftId`、不合并其他会话。
- 多标签：仍只克隆`updatedAt`最大的会话（保留原`draftId/requestKey/firstPendingSnapshot`）；v2不得把分叉副本合并。
- 缺失/不可读版本：按`targetRecordId`查目录；服务端能解析→按所属版恢复；不能解析→保持只读冻结并显示“记录版本不可用”，不得永久`unknown`、不得清缓存代替正确性。
- 新建未落库的旧版草稿与已有历史记录编辑分开：前者只能复制为新key新建，后者按`baseStorageVersionId`编辑；`unknown`在核验成功或明确失败前禁止丢弃/复制。
- 账号：401不删草稿（现有行为），`markAttemptAbandoned`→unknown/frozen；仅显式退出删除本账号草稿。
- 必测：大ID（>2^53字符串）、v1多标签分叉、旧key未知、401/换账号、原版CAS、规格变化只读、v1→v2幂等升级。

### 7. 共同字段、分母与九项选择器（R05、R07）

#### 7.1 可比元数据与三模板默认值

```json
"comparable": {
  "semanticKey": "issue_reply.affected_kinds",
  "meaning": "修改该缺陷可能影响的功能",
  "valueType": "enum-set",
  "unit": null,
  "semanticVersion": 1,
  "domain": {
    "kind": "enum",
    "values": ["KNOWN", "NEW", "NONE"],
    "domainHash": "sha256(canonical-json{kind,values})"
  },
  "semanticHash": "sha256(canonical-json{semanticKey,meaning,valueType,unit,semanticVersion,domain.domainHash})"
}
```

- **绑定真实冻结域**：枚举字段的`values`来自该版本`field_spec`冻结选项的稳定`value`（排序去重，标签不进入哈希）；原因词典字段的域为`{kind:'dictionary',key,version,contentHash,values[]}`，`contentHash`取冻结词典`canonicalSha256`（缺失时才按`{key,version,values}`重算），`values`为该版本冻结成员`key`排序。字典版本或成员变化都必须改变`domainHash`。
- 哈希输入是**完整语义对象**（键排序、显式null），不是`key|unit|valueType`三串；系统维护，管理员不可编辑；新增字段未声明或无法解析冻结域→不参与统计并在校验报告中列出，已确认核心字段必须声明。不得退化成正中文名匹配。
- **显示改名与实际语义变化的区别**：仅改`label`/说明文案→`semanticHash`不变，跨版继续合并；改选项稳定ID、增删选项、改词典版本或成员→`semanticHash`变化，不得合并。两个字段同名但冻结域不同→不合并；删除后同名新增得到新稳定ID/新语义身份→不合并。
- **稳定字段身份**：每个字段的`stableId`是跨版本身份的唯一键。N1为既有字段一次性登记确定值；新建字段生成新`stableId`；显示名/`label`变化不改它；删除后同名新增不得复用旧`stableId`。匹配某共同字段必须同时满足`stableId`相同、`semanticKey`相同、`valueType/unit`相同、`semanticHash`（含冻结域哈希）相同；任一不同即不共同，不能用中文名、位置或仅`semanticHash`部分字段替代。
- 三模板既有字段全部给出默认值（下表`枚举域`是业务速记，实际哈希以冻结选项/词典为准；未列者如纯文本仍给语义键并标`text`）：

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
- 字段显示名改变不改稳定`name/semanticKey`；删除后同名新增得到新稳定身份，不继承、不合并；同名不同义即使名称相同也不合并（匹配须同时满足稳定身份、语义键与`semanticHash`，哈希仅作校验）。
#### 7.1.1 共同字段选择与聚合执行合同

```text
FieldContract = {
  stableId, name, apiKey, semanticKey, valueType, unit, semanticVersion,
  domainKind, domainHash, semanticHash
}
resolveField(templateKey, field, frozenSpec) -> FieldContract | ineligible:
  stableId = field.stableId（N1登记；既有字段一次性确定，新增字段新值）
  semanticKey/meaning 来自冻结声明；未声明或域无法解析→不参与并在报告列出
  domainHash = enum(冻结选项稳定value排序去重) |
               dictionary(key,version,contentHash,members排序) |
               plain
  semanticHash = sha256(canonical{semanticKey,meaning,valueType,unit,
                                  semanticVersion,domainHash})

selectCommonFields(versions[versionId,templateKey,fieldSpec,records]) -> {
  status: OK | NO_COMMON_FIELDS,
  common: [FieldContract...],
  unmerged: [{stableId,reason,variants}],
  onlyInSome: [{stableId,versions,missingVersions}],
  selectedVersions, perVersion
}:
  每版 stableId→contract；同版重复 stableId 直接失败
  candidate = 所有版本 stableId 的交集
  candidate 每项要求全部版本的 {stableId,semanticKey,valueType,unit,semanticHash}
  完全一致才进入 common；同身份但语义/域变化进入 unmerged；缺版进入 onlyInSome
  common 为空必须返回 NO_COMMON_FIELDS 及选定版本，不得返回 0 或空成功

aggregateMetric(selection, versions, semanticKey) -> {
  status: OK | NO_COMMON_FIELD | NO_COMMON_FIELDS,
  metric, total, contributions, contributingVersions,
  missing, nulls, zeros, deleted, nonNumeric, selectedVersions
}:
  只在 selection.common 中按 semanticKey 找合同；找不到→total=null，不读原始规格计数
  逐记录：deleted排除；字段缺失排除并计missing；NULL排除并计nulls；
          数值0计入并计zeros；非数值排除并计nonNumeric；只对有效数值求和
  contributingVersions只含实际贡献记录所在版本
```

- **哈希验证**：`affected_kinds`仅改显示标签→哈希不变；把`KNOWN`改为`KNOWN_AFFECTED`或增加`PARTIAL`→哈希变化；`DEFECT_CAUSE`词典版本`defect-cause-v20261020`、24个成员与`canonicalSha256`参与哈希，删除成员或改版本→哈希变化；`impact_test_status`与`relation_table_updated`不合并。
- **验证（93项真实规格检查，非自造常量）**：真实两版`code_review`的`specification_count`为2/3，经共同字段选择聚合为5并列出V1/V2贡献；仅`label`改名仍为5；V2改成新`stableId`→`onlyInSome`且聚合`NO_COMMON_FIELD`而非5；同身份改`semanticKey`→`unmerged`且不聚合；真实`affected_kinds`选项变化→`unmerged`且不聚合；`code_review`与`issue_reply`确无交集→`NO_COMMON_FIELDS`且`total=null`、列出选定版本；缺字段/NULL/0/作废分别计数，0参与求和。三个突变（新身份、无交集改成同键、真实域变化）必须使探针以非零退出。
- 历史81项中的元数据/字典检查保留为辅助，不再以“字段存在/计数”代替共同字段选择和聚合结论。

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

- **2+3=5**：V1规范=2、V2规范=3，`stableId`/semanticKey/单位/冻结域相同→进入common后聚合为5；仅显示改名→仍合并；删除后同名新增→新`stableId`，`onlyInSome`且不聚合；同单位异义枚举→`unmerged`且不聚合；真实枚举选项ID变化→`unmerged`且不聚合；无共同字段→明确输出`NO_COMMON_FIELDS`并附选定/贡献版本，`total=null`，不返回0。聚合函数只消费`selectCommonFields`的common，不直接读取规格计数或原始记录求和。
- 真实探针使用`backend/src/main/resources/form-templates/*.json`的实际字段、选项与`DEFECT_CAUSE`词典（24项、版本`defect-cause-v20261020`、`canonicalSha256`），运行实际选择与双版本聚合：`specification_count` V1=2、V2=3→5；两版本无共同字段时输出显式报告；另验证缺字段/NULL/0/作废/贡献版本。突变新身份、无交集改成同键、真实域变化都必须转红。
- 每个指标输出`selectedVersions/contributingVersions`（实际贡献记录所在版本），供审计与导出。

### 8. 导出与权限（R08、Q7、Q8）

#### 8.1 端点、范围与列

```text
GET /api/collect-forms/exports/records
    ?versionId=<十进制字符串，必填>
    &projectIds=<可选，逗号分隔；默认该版本scope.project_ids>
```

- `versionId`必须是该scope的可读`AUTHORITATIVE`版本（`PUBLISHED/RETIRED`）；`TARGET`/`LEGACY_SNAPSHOT`一律404/409；`projectIds`必须是scope项目集合的非空子集，且每条导出记录的目录C必须落在`(scope, version, requestedProjects)`内，不能只校验请求参数后按版本全量导出。
- **可见谓词（服务端）**：`hasPermission(user,'form.record.export') ∧ version∈readable(scope) ∧ requestedProjects≠∅ ∧ requestedProjects⊆scope.project_ids ∧ row.project_id∈requestedProjects ∧ row.scope_id=scope ∧ row.storage_version_id=version`。平台当前没有按账号授予的项目ACL数据表，项目可见性由显式导出权+范围绑定决定；若后续引入账号-项目ACL，导出必须复用同一可见性provider并再取交集，不得只按范围放行。`resourceType/resourceId`不开放任意跨范围查询；如需按资源过滤，只能在上式结果内追加等值条件。
- 只导出`deleted=false`的有效记录（不提供`includeVoided`，作废结果禁止导出）；新建空版本导出**仅表头**（可读版本、零行，仍记录审计）。
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

#### 8.2 权限授予（显式导出权，不扩权；II-06）

- 新增权限键`form.record.export`（module“表单采集”，sort 3345）。**授予依据只能是既有导出能力，不能由查看权推导**；当前授予与恢复默认快照分别按各自表内的真实导出权复制：

```sql
insert into platform_permissions(permission_code,permission_name,module_name,description,sort_order)
values ('form.record.export','导出表单记录','表单采集',
        '按已发布版本导出获准范围内的有效表单记录',3345)
on conflict (permission_code) do update
set permission_name=excluded.permission_name,module_name=excluded.module_name,
    description=excluded.description,sort_order=excluded.sort_order,
    enabled=true,updated_at=current_timestamp;

-- 当前授予：只复制已持有既有导出键的角色
insert into platform_role_permissions(role_code,permission_code)
select distinct rp.role_code,'form.record.export'
from platform_role_permissions rp
where rp.permission_code in ('review.record.export','customer_issue.record.export')
on conflict do nothing;

-- 默认快照：同样只看默认表内的既有导出权，不新增无导出默认的角色
insert into platform_default_role_permissions(role_code,permission_code)
select distinct dp.role_code,'form.record.export'
from platform_default_role_permissions dp
where dp.permission_code in ('review.record.export','customer_issue.record.export')
on conflict do nothing;
```

- **不扩权证据**：代码走查/客户问题两个`view`键同时存在不等于导出资格；真实PG反例`CUSTOM_VIEW`仅有两个view键→未获导出；`VIEW_ONLY`仅有view→未获导出；`EXPORT_ONLY`持有既有导出键→获导出。撤销`EXPORT_ONLY`的导出后立即拒绝；恢复默认只恢复默认表内有导出来源的角色，`VIEW_ONLY`仍拒绝。初始种子中五个角色都持有既有导出键，所以候选集合是这五角色，但这是**导出权**结果，不是`view`推导；若目标库已撤销某角色导出，迁移不会恢复它。
- **正式授权仍需批准**：本轮不执行权限迁移。N1写入前用只读查询列出目标库实际候选角色、当前授予与默认快照差异，交用户确认最终清单；确认前不新增`form.record.export`行。若产品只允许部分角色，按确认清单显式收窄，不用“默认全量”兜底。
- 端点方法级校验`form.record.export`，未授权A0303；导出范围再按§8.1的版本/项目/资源C谓词过滤，不能跨范围读取。管理页当前无导出按钮，**不新增页面view/export映射**；若后续加按钮，须先按AGENTS报批并同单元登记`PagePermissionKeyResolver`。
- 真实PG探针已覆盖：仅查看无导出、双查看键仍无导出、撤销导出、恢复默认不扩权、部分项目、越权项目、跨范围版本、TARGET版本、空版本表头；均未执行正式权限DDL。
- `endpoint-catalog.yml`新增EXPORT条目（读目录/版本/记录表）；夹具固定scope/version ID使动态表名确定；本稿不生成黄金快照，新增/差异待W7审批。

#### 8.3 资源边界与容量（Q7）

- 50k行/64MB仅为**待验证预算**，不是容量结论。估算：`rows × (系统列+字段列平均UTF-8字节+XML开销) + 工作簿固定开销`；固定负载（1万/5万行、宽表）测量耗时、堆峰值、临时文件大小，阈值取重复采样分布，不靠重跑。
- 行为：超过`rowLimit=50000`或估算`byteLimit=64MB`→返回413并说明实测值/上限，不静默截断；小结果（≤5000行）可用`XSSFWorkbook`，以上用`SXSSFWorkbook(window=200)`流式写出。
- 临时资源：SXSSF临时目录为`${java.io.tmpdir}/qaflex-form-export-<uuid>/`，`finally`递归删除；不写入仓库、不残留；响应流与审计记录行数、字节数、SHA-256。
- 现场容量由内网固定负载实测，不用小夹具外推；失败清理与超限路径纳入N1测试。

### 9. 验证入口与隔离探针（R08、Q9）

#### 9.1 可执行入口（pwsh，显式隔离，不用通配mvn）

<a id="n0-run-entry"></a>

唯一入口为`.tmp/w4-r5-n0-20261027-r2/run-probes.ps1`（PowerShell 7，UTF-8 BOM）。固定调用：

```powershell
# 正向：base -> revision -> probe；非空升级；真实双会话；共同字段
pwsh -File D:/projects/dcp-form-w4-20261026/.tmp/w4-r5-n0-20261027-r2/run-probes.ps1 `
     -Mode normal -Port 15609

# 负向清理：DDL失败 / 就绪超时 / 同名冲突 / 端口占用
pwsh -File .../run-probes.ps1 -Mode ddl-failure -Port 15609
pwsh -File .../run-probes.ps1 -Mode timeout -Port 15609 -ReadyTimeoutSeconds 0
pwsh -File .../run-probes.ps1 -Mode name-conflict -Port 15609
pwsh -File .../run-probes.ps1 -Mode port-conflict -Port 15609
```

- 容器使用`docker create`先取得ID，再`docker start`；只有本轮成功创建返回的ID进入`finally`。`create`失败（如同名冲突）无ID→零清理，不按名称查找；`start`失败（如端口占用）持有自己的ID→只删除该ID。就绪循环有超时并逐次检查退出码。
- 执行顺序固定为：`V20261022_01`→`revision.sql`→`capture-settle.sql`→`probe.sql`；独立`p2db`执行基础DDL→`seed-pre.sql`→`revision.sql`→`probe-p2.sql`；`p3db`执行基础DDL→`revision.sql`→`capture-settle.sql`→并发夹具→`switch-enter`→`switch-apply`/`write-old-request`/`write-new-request`→崩溃/恢复→`fence-write`→`switch-takeover`/`consume-wait-takeover`→`publish-interleave-hold`/`consume-wait-publish`→首次启用→`concurrency-check`。逐条`ON_ERROR_STOP`并检查退出码，日志按步骤落盘。
- 清理只对所持ID执行`docker rm -f`，回读`docker inspect`确认不存在；清理失败写`cleanup.txt`并保留原失败码。同名/端口冲突模式先创建占用者，失败尝试不得删除占用者，占用者由自己的ID清理。
- 端口只使用156xx空闲端口；不得使用15432/15433/15601/15602或共享服务。业务测试（N1起）用显式`-Dtest=类名`与显式`TEST_DATASOURCE_URL/TEST_POSTGRES_JDBC_URL`指向本容器；禁止`-Dtest='Form*'`或裸`mvn test`作为“隔离”证据。
- 真实E2与现场登记/切换仍是独立门禁（Q9），不阻断本设计探针，但阻断真实登记与N1上线；MR公式仍待批。

#### 9.2 探针清单（本稿已执行，见交付证据）

| 探针 | 预期 |
| --- | --- |
| P1 空平台应用V20261022_01+本稿01—03 | 全部DDL成功，旧159条未改 |
| P2 非空基础升级（先插v5范围/版本/任务再ALTER） | 默认角色/模式回填，形状检查通过，v1任务保持PREPARED |
| P3 protocol/stage | v1只接受旧阶段；v2只接受VALIDATING/BUILDING/SWITCHING/PUBLISHED/FAILED；非法组合23514 |
| P4 活跃唯一/领取索引 | 同scope第二个活跃任务23505；FAILED后可建；两协议不并存 |
| P5 实例模式 | MATERIALIZED空instance_table拒绝23514；NONE空表+source_table成功；无实例目录行仅NONE允许 |
| P6 目录约束 | 跨版requestKey重复23505；NULL可重复；版本/scope复合FK；实例模式复合FK；origin/scope复合FK |
| P7 可读视图/哈希 | TARGET/快照不出现；AUTHORITATIVE集合哈希（含规格哈希）=函数重算 |
| P8 登记 | Demo零实例/零账号登记、v5承载+副本角色、origin/C/全值哈希一致、重入幂等、提交后补偿事件不回退 |
| P9 旧任务收口 | 有效租约不终止；过期租约终止并写attempt/清本任务栅栏；非本任务栅栏不动 |
| P10 head捕获/结算 | 集合/head/authority漂移时CAS为0；一致时CONSUMED且pending清空 |
| P11 目录事件 | 领取/确认/租约过期重领/游标推进；重启从游标重放；head版本1→5→3保持5 |
| P12 无实例FORM | 发布建authority/head，捕获零记录可结算为空投影，不删资源 |
| P13 共同字段/2+3=5 | 真实三模板字段域齐全；同一`stableId`/语义键/类型单位/冻结域经实际交集选择后2+3=5；显示改名不变；新身份/异义/异域进入unmerged或onlyInSome且不聚合；无共同字段返回NO_COMMON_FIELDS、total=null；缺字段/NULL/0/作废分别计数；三个突变非零退出 |
| P14 导出权限 | 只按既有导出权复制；仅查看/双查看不获导出；撤销/默认恢复不扩权 |
| P15 序列`is_called` | 相等未调用→下一次2；相等已调用、更高、更低、跨范围、登记后立即创建均大于已登记ID |
| P16 真实v5指纹/origin-storage | 独立旧格式哈希`e31d3a8d…86974d8`与SQL函数逐字节一致；上稿新算法不同；旧key按origin版本重放同一ID且零新增/审计/head；承载版本重算、当前编辑值、异账号/完整C/字段/作废均拒绝；原版行不变 |
| P17 发布接管/短栅栏/首次启用 | 所有脚本序列→范围→任务；BUILDING不停写；enter提交后写入可重试；apply后旧版在途新增409、新版请求成功；非空目标拒绝；过期BUILDING接管；旧owner迟到DDL/结算零副作用；崩溃全回滚；SWITCHING过期接管幂等；首次启用DRAFT→ACTIVE、NULL旧指针、零空实例、首条新增闭环 |
| P18 捕获/结算归属与交错 | 同一capture-settle函数在public/p3db执行；范围→head锁序；旧worker不覆盖/结算；pending集合、pending权威、当前权威、记录身份/修订/最新版本逐项失配均零事实零结算；失败/重试受owner/fence/租约guard；消费与发布/接管双会话无40P01并按新集合结算 |
| P19 资源C定位 | 目录`listByResource`走`idx_frl_resource`，LEGACY/NONE同样命中；事件/head单调 |
| P20 导出范围 | 部分项目、越权项目、跨范围版本、TARGET、空版本表头均按谓词处理 |

证据写入`D:/projects/dcp-form-w4-20261026/.tmp/w4-r5-n0-20261027-r2/`（本轮专属）：`revision.sql`、`capture-settle.sql`、`probe.sql`、`probe-p2.sql`、`concurrency-*.sql`、`switch-*.sql`、`first-*.sql`、`consume-wait-*.sql`、`publish-interleave-hold.sql`、`common-fields-probe.mjs`、入口脚本、逐项日志、`cleanup.txt`与`logs/verification-*.json`；不留业务服务或共享库访问。

**本次执行结果（2026-10-27）**：`postgres:16-alpine` 隔离容器`b3f3803759343475188f591080e7203f8c527bc01a63d12367772671a07fa81a`（本机15609，唯一命名`dcp-n0-r2-normal-*`）执行public 115项、非空升级6项、真实双会话33项、共同字段93项，全部PASS；旧版在途请求等待apply约3.2秒后返回`409_STORAGE_VERSION_CHANGED`，新版请求成功；SWITCHING接管与消费交错等待约2.3秒、发布与消费交错等待约3.2秒，均在提交后按新集合结算且无`40P01`。共同字段三个突变`identity`/`no-common`/`domain`均以非零退出。容器按本轮ID删除、`docker inspect`确认不存在，无残留端口；未启动业务服务、未连接共享库、内网或真实外部。负向模式（DDL失败、就绪超时、同名冲突、端口占用）分别验证：失败DDL退出非零、超时后清理自有ID、同名创建无ID且占用者存活、端口冲突创建出的容器被删除而占用者存活；均无残留容器。

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

#### 10.2 已批准方向与剩余未决

**二审已批准，不再列为待选项**：

1. v2任务从`VALIDATING`开始；部署前仍须停止旧执行器并受控收口旧任务，新阶段不代替部署隔离。
2. 耗时只读构建在事务外；提交/结算用短事务锁范围与head，复核capture/owner/fence/租约/集合后原子公开。构建期间不停写，栅栏只覆盖`SWITCHING`的enter/apply两个短事务。

**仍需确认/后续阶段**：

1. v5承载副本若存在已删除字段的值丢失，按§3.1阻断并交审核/业务裁决，不自动合并。
2. 导出角色最终名单须按§8.2只读查询目标库实际导出授予后由用户确认；确认前不执行`form.record.export`正式迁移。默认快照只按既有导出权复制，不由view推导。
3. MR级汇总公式与真实E2规模仍按原阶段，不在本稿代拟。
4. `SWITCHING`两短事务之间是明确的有界停写窗口（enter提交到apply提交）；若进程崩溃，接管在租约到期后进行。需在N1加入该窗口的监控/告警和最大持有时间，避免异常时长期停写。

#### 10.3 审批请求

请确认：§1—§9的DDL/事务/消费/导出/验证合同、§10.1删除清单、II-01—07关闭证据与S1设计合同；§8.2导出角色清单在N1前另行确认。批准后N1按阶段实施，先提交最小DDL+目录/登记测试，再发布/消费/导出。未批准前不改业务代码、不建正式表、不改旧迁移、不合main、不出包。

## 11. S1 管理页功能设计合同（本轮只设计，不改 frontend）

> 保留已批准UI基线`94749f3a`的布局、字段卡片、常驻摘要、抽屉与主次操作；本节只定义功能候选来源、命名和最短流程。实现前另报批，前端零改动。

### 11.1 项目名称下拉（真实目录、搜索、多选）

- **候选物理来源**：按范围已绑定的代码平台（`source_config_id/source_instance/canonical_gitlab_base_url`）读取该平台已同步的项目目录：主路径为`ods_gitlab_projects`（`id,name,path,namespace_id,mirror_deleted`）连接`ods_gitlab_namespaces`（`id,parent_id,path,mirror_deleted`）得到全路径；DGM来源使用`code_review_dgm_project_options`（`gitlab_project_id,name,path,path_with_namespace,namespace_full_path,active`）。两者由后端适配为同一DTO，浏览器不直连数据库，也不把事实/统计表当项目目录。
- **服务端搜索投影**：新增只读投影`form_project_candidates(source_config_id,source_kind,source_instance,project_id,name,path,full_path,search_text,search_compact,search_spell,search_initials,active,synced_at)`，唯一键`(source_config_id,source_kind,project_id)`。它由ODS/DGM目录幂等重建，不是第二事实源；项目同步成功后刷新，读取时校验`synced_at`/目录可用性。拼音与首字母在投影构建时用现有`TextQuerySupport.buildSearchIndex`（Hutool `PinyinUtil`）生成；例如“客户问题平台”生成`kehuwentipingtai`与`khwtpt`，查询`khwt`即使目标不在当前20项也能命中。
- **跨页搜索**：`GET /api/collect-forms/admin/projects?sourceConfigId=&query=&page=1&size=20` → `{items:[{projectId:"<十进制字符串>",name,path,fullPath}],total,page,size,sourceReady,warnings:[]}`。权限沿用`form.template.manage`；`query`先trim/小写/去空白，服务端在**分页之前**对`search_compact/search_spell/search_initials`做匹配；名称、路径、全路径任一命中即可。默认按`name,project_id`稳定排序，`size`上限100，空查询返回首页。禁止只对前端已加载的20项做本地过滤，也禁止把浏览器搜索事件当作全量结果。
- **完整项目路径**：ODS用递归CTE沿`ods_gitlab_namespaces.parent_id`拼`根命名空间/…/项目path`；DGM优先`path_with_namespace`，缺失时用`namespace_full_path || '/' || path`。父命名空间/根链不可解析时不伪造路径，候选标记不可用并给出warning。示例：ODS `group/sub/project` 三段路径、DGM同名项目得到各自来源的完整路径，不互相拼接。
- **来源隔离**：`sourceConfigId`解析为唯一`source_kind`（ODS或DGM），查询只读取该来源投影；不跨来源union、不按数字ID合并。ODS项目ID与DGM `gitlab_project_id`即使数值相同也是两个不同候选，候选身份固定为`(sourceConfigId,sourceKind,projectId)`。`source_instance/canonical_gitlab_base_url`必须与范围绑定一致，不一致返回warning并拒绝保存。
- **ID边界**：候选DTO的`projectId`是GitLab项目ID的十进制字符串；不暴露`ods_gitlab_projects`行内部字段、DGM自增`id`或任何管理端数字ID。管理端`scope.id/version.id/publication.id`仍是number，只作本地管理标识，不能当项目ID使用。版本保存时把候选`projectId`转为`version_project_id`，响应同时带`sourceConfigId/sourceKind/projectId`，避免同名/同ID歧义。
- **单/多选**：代码平台单选（已有范围自动带出，唯一来源时不要求重复选择）；适用项目多选（至少1个）。版本所属项目从**已选适用项目**中单选：恰好1个项目时自动选中；多于1个时必须由用户显式选择，禁止默认第一项或按名称排序取首项。调整适用项目时，若版本所属项目仍在集合内则保留；若被移除则阻止保存/发布并提示重新选择，不静默改绑。
- **未同步/失效/失败**：目录未同步→`sourceReady=false`、`items=[]`、`warnings=["项目目录尚未同步"]`，不开放自由输入项目编号，不自动触发同步；单个项目失效→已选值保留ID/名称快照并标“已失效”，发布前要求移除或替换；接口失败→显示可重试错误并保留已选值；无匹配→明确“无匹配项目”，不返回全量兜底。
- **复用SmartSelect**：使用评审数据管理页现有`SmartSelect`的远程搜索、回显、清空、多选tag行为；远程搜索按`query/page/size`请求服务端，选中项跨页保留并按候选身份去重。不另造样式或第二套交互。N0只补合同，实施时先提交功能差异、调用/状态影响与验收方法。

### 11.2 业务命名与不可混同的概念

| 可见名称 | 业务含义 | 数据来源 | 禁止说法 |
| --- | --- | --- | --- |
| 代码平台 | 已绑定的GitLab来源实例 | `form_template_scopes.source_config_id/source_instance` | 来源、实例 |
| 适用项目 | 本范围覆盖的项目集合，多选 | 项目目录候选→`project_ids` | 项目编号输入 |
| 版本所属项目 | 版本/里程碑所属项目，单选 | 项目目录；从已选适用项目显式选择，仅1项时自动选中 | 版本成员、默认第一项 |
| 里程碑/版本 | 代码平台的项目里程碑 | `getFormMilestones`（GitLab里程碑） | 表单版本 |
| 版本分支 | 计划合并的代码分支字符串 | 版本分支候选（来源待N1核实） | 版本成员、版本号 |
| 表单结构版本 | 发布后冻结的字段/条件结构 | `form_template_versions` | 业务版本、GitLab版本 |
| 人员 | 平台账号/姓名 | LDAP/平台账号 | 版本成员 |

- 清理“版本成员”等含糊可见名；里程碑、代码分支、表单结构版本、人员各用上表名称。版本分支候选来源未核实前不编造接口，先在UI显示为“版本分支（手工填写）”并列入N1核实项；核实后再接候选。
- 自动带出：代码平台的规范地址/来源实例、版本所属项目、里程碑标题→版本名称、范围名称建议值；用户不再重复输入项目编号或URL。

### 11.3 常用任务最短流程

```text
新建范围（5步内）:
  选择表单类型 → 选择代码平台（唯一来源自动带出）→ 搜索多选适用项目
  → 仅1项自动带出版本所属项目；多项必须显式选择 → 选择/填写里程碑（自动带出版本名称）
  → 填写范围名称→保存

调整字段并发布:
  打开范围 → 直接编辑字段卡片（自动保存）→ 发布 → 确认差异 → 完成

修改范围绑定:
  打开范围 → 适用范围抽屉 → 搜索/多选项目→完成；已发布范围变更须走发布确认
```

### 11.4 D-21 无回复/非法最新计划/较早合法响应样例

规则沿用主方案§3.5与D-21，不重开业务裁定：豁免判据为“存在有效回复模板 ∨（存在响应模板 ∧ 计划解决时间合法）”；页面“最新响应”始终按`(createdAt, recordId)`取最新整份，不因计划非法而拼接较早响应；D-21的合法计划查找才按最新→较早回退。

| 样例 | 页面最新响应 | D-21延期豁免 | 说明 |
| --- | --- | --- | --- |
| E1 无有效回复、无响应 | 空 | 否 | `has_response=false`，不制造样本；不因页面空而豁免 |
| E2 无有效回复、最新响应计划缺失/非法、无较早合法响应 | 最新整份；非法/缺失计划按页面原样展示或标非法 | 否 | 不能把“有响应模板表头”当已响应；无合法计划即不豁免 |
| E3 无有效回复、最新响应计划非法、较早响应计划合法 | 仍为最新整份（非法计划不从较早模板拼接） | 是 | D-21回退检查较早合法计划；页面最新字段与延期判定使用不同选择，二者都保留 |
| E4 有有效回复、最新响应计划非法/缺失 | 仍为最新整份 | 是 | 回复模板优先，不再检查响应计划；页面最新响应不因豁免而改写 |
| E5 无有效回复、最新响应计划合法 | 最新整份 | 是 | 合法计划直接满足响应侧豁免 |
| E6 有有效回复且无响应 | 空 | 是 | 回复存在即豁免；`has_response=false`仍独立表示未按响应模板回复 |

- 三个关键样例的预期必须分别锁定：E1“无有效回复且无合法响应计划→不豁免”；E2“最新计划非法→页面仍最新整份且不豁免”；E3“较早合法→页面仍最新整份、D-21继续检查较早并豁免”。不能把E3实现成“页面最新回退到较早”或“因回复/响应存在就无条件豁免”。
- 实施验收同时覆盖：E2与E3仅差较早响应是否合法；E4与E2仅差有效回复；作废响应/回复从页面最新、首次调研、原因统计及豁免中排除。以上均为既定规则执行说明，不新增业务选择。

- 去掉重复输入、跨页跳转和非必要确认；保留现有保存/冲突/发布确认与直接重试交互。新增独立表单、版本绑定解耦、退役后恢复的上一轮具体模型仍待用户确认，本节只列影响与待确认项，不写成批准设计。

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
