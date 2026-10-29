# 统一表单 v6 — N2 原版记录服务与填写页活动计划（2026-10-28）

## 进度与中间物

- [当前复验，2026-10-29] 输入`8fe425bb`修改后重审：R01/R02/R03/R05关闭，R04的缺规格展示/哈希守卫已修，但未知结果仍有页面核验入口丢失和编辑核验错误降级两处缺口。N3未准入；本轮唯一剩余关闭条件与员工执行命令见[整改复验](#n2-rereview-20261029)。下方首次审核与员工送审记录保留其时点。
- [审核结论，2026-10-28] 输入`0310fba15777fdc9ba00e7e49a980817fd0c5d12`修改后重审，N2未通过，N3仍未准入。独立复验80项真实PG、71项前端及typecheck通过，但补充反例确认目录旁路、NONE混用新增失败、草稿覆盖和规格边界缺口；另须纠正验收启动命令及外部连接边界表述。当前唯一详细关闭条件见[首次审核与整改执行单](#n2-first-review)。N1已关闭项、160条迁移及批准管理页UI保持。
- [当前阶段] N1 输入`369194aa`通过，N1-R01—R05 全部关闭。N2继续在原批准范围整改，管理页 UI、后台壳/样式维持`94749f3a`，只允许必要填写页功能修复。N3—N5/W5/main/正式包未准入。
- [已完成] 已快进吸收审核回执`6329590a`；N1 活动计划按规则收口删除，过程可用`git show 369194aa:docs/plans/unified-form-v6-n1-implementation-20261027.md`恢复。
- [员工送审状态，2026-10-28 整改后] R01—R05 已按本执行单完成整改并送复验：后端定向83/0/0/0、前端925/925、typecheck/lint/build通过、浏览器83项全通过；RED/GREEN 与命令见下文“整改执行记录”。
- [员工实现说明] detail多版本字段、所属版编辑/作废与填写页草稿v2保持；本轮删除“先当前物理表”的重放旁路，改为目录origin唯一核验；同人扫描可按NONE只读版本判断；草稿读取以有效v2为权威且只迁移当前账号；规格缺失/哈希失配时按不可用只读。管理页、后台壳与样式未改。
- [阻塞] 等待 N2 复验；N3—N5/W5、main、正式包仍未准入。
- [员工证据] 整改证据在员工树`.tmp/w4-n2-20261028/`（`input-manifest.json`、`environment.md`、`evidence/acceptance-summary.json`、42张截图）；日志`.tmp-logs/n2-review-red-backend.log`、`n2-review-green1-backend.log`、`n2-review-red-draft.log`、`n2-review-red-session.log`、`n2-final-targeted-backend2.log`、`n2-final-default-suite.log`、`n2-final-frontend-*.log`、`n2-acceptance-recheck10.log`。专属容器已删除。一次未关闭后台作业的旧验收后端曾尝试连接内网Mongo并失败，边界按R05冻结日志表述，不沿用“全部未访问外部”。

### 恢复信息

1. **阶段与首条恢复命令**：先读 AGENTS、`docs/progress.md`、主方案与 N0 合同（`v6-n0-technical-admission-20261027.md` §2/§6）及本文件，执行 `git status --short --branch`、`git worktree list`。开发基线`6329590a`，本次审核输入`0310fba1`，本轮整改起点`db1eacc9`（已快进吸收）；现有任务分支已更名为`表单多版本与填写功能开发-20261026`，worktree仍为`D:/projects/dcp-form-w4-20261026`。审核回执分支为`表单管理界面开发-20261027`。复用已核实的现有分支，不切回历史名称。前序：N1（已通过，计划已删）。
2. **原始需求与成功标准**：按主方案 N2 行与 N0 §2/§6：当前版新建；编辑/作废按目录`storage_version_id`与`expectedStorageVersionId`定位并按该版冻结规格校验；跨版 key 重放按 origin 证据；detail 返回`currentVersionId/readableVersions/versionSpecs/sourceSetRevision/sourceSetHash`及每条`storageVersionId/originVersionId`；bigint 一律十进制字符串，`recordVersion`为 number；草稿 v1→v2 迁移；真实 PG + 浏览器验收。禁止：改管理页/后台壳/样式；N3—N5/W5；合 main、出包、黄金运行；改 160 条迁移；默认 15432/15433 与共享服务。
3. **业务/API/数据/环境约束**：编辑/作废只接受`AUTHORITATIVE`承载版本；`originVersionId`仅展示/重放；NONE/旧 Demo 缺创建人只读；退役范围只读；作废从业务读取排除；新建/编辑/作废失败全回滚，重放零新增记录/审计/head；未知结果先核验；401 不删草稿。
4. **提交、日志与根因证据**：N1 实现`4985c53c`、审核回执`6329590a`；N2 后端实现`069d14f5`、计划`c49b2d3e`；验证日志`.tmp-logs/n2-default-suite-final.log`、`environment.md`与`input-manifest.json`随最终提交留痕；MySQL 就绪竞态为已知范围外环境问题，独立记录不掩盖；一次验收后端后台调度尝试外部 Mongo 的连接失败已记录，此后调度关闭。
5. **步骤与依赖**：P1 DTO/读侧/写侧 → P2 前端会话/卡片/草稿 → P3 测试与浏览器验收。前端依赖 P1 契约；P3 依赖 P1/P2。
6. **选定/否决方案**：detail 复用`FormRecordDirectoryReader.captureSourceSet`与按版本冻结规格读取，不新增平行读源；编辑/作废新增`expectedStorageVersionId`并复用目录定位，不保留旧“当前表+current pointer”写路径（内部破坏性调整，删除旧路径与调用者）。否决：为兼容保留 `_V2` 别名或双轨。
7. **API/表结构/关键常量**：DTO 扩展至 N0 §6.1（`FormDetailResponse`增加 currentVersionId/readableVersions/versionSpecs/sourceSetRevision/sourceSetHash；`FormRecordView`增加 storageVersionId；`FormUpdateRequest/FormVoidRequest`增加 expectedStorageVersionId）。无新增迁移（如确需另行申请）。历史测试夹具`FormV6TestSupport`继续复用。
8. **风险、假设与敏感只读数据**：两个版本不同字段的历史记录必须按自身规格展示/编辑；草稿分叉与未知状态不得合并；浏览器验收端口先查归属，仅停本任务资源。无敏感数据。

## 阶段

### P0 计划与现状（任务 #60）
梳理后端 Controller/DTO/服务与前端填写页/草稿现状，确定改动清单与删除路径；本文件即恢复入口。

### P1 后端多版记录服务与 DTO（任务 #61）
- detail：范围→权威版本集合（reader.captureSourceSet）→逐版本冻结规格→按目录读取全部有效记录（含跨版本），每条带 storage/origin；bigint 字符串。
- create：保持六步事务与目录优先重放；旧版 key 仅允许重放，新提交必须=currentVersionId。
- update/void：新增`expectedStorageVersionId`；按目录定位 storage 版本，校验 AUTHORITATIVE、scope/C/创建人/CAS，使用该版规格与表；拒绝旧路径。
- 真实 PG：三模板两版不同字段、原版编辑/作废不搬表、origin≠storage、旧字段保留/新字段不补、同 key 重放、同人跨版提示、并行不同记录、同记录 CAS、栅栏/绑定/权限/回滚、大 ID、NONE/退役只读、指针变化场景。

### P2 填写页与草稿 v2（任务 #62）
- 每条记录按`versionSpecs[storageVersionId]`渲染/编辑；新建用 currentVersionId；旧链接访问原记录原值。
- 编辑/作废提交`expectedStorageVersionId`；unknown/401/分叉/离开保护语义保持。
- 草稿键前缀 v2、新增`originVersionId/baseStorageVersionId/versionSpecHash`；读取兼容 v1，写 v2 成功后删 v1；多标签只克隆最新会话。
- Vitest 覆盖大 ID、v1→v2、旧版 CAS、规格变化只读、401/换账号、未知核验。

### P3 验证与送审（任务 #63）
真实 PG 定向、一次默认后端套件、前端 typecheck/lint/Vitest/build、真实服务浏览器验收（MR、Issue 响应/回复、双用户/多标签、v1→v2 草稿、旧版编辑、401/换账号、未知核验与迟到响应）与截图；护栏；文档回填；双远端提交回读后送 N2 验收。

<a id="n2-first-review"></a>

## N2首次审核与整改执行单（2026-10-28）

### 结论、输入与证据边界

**结论：修改后重审；N2未通过，N3未准入。** 本次不重新打开N1已关闭项。允许员工立即修复下列N2缺陷及必要正式测试，不必再次申请同一范围的填写页功能修复；不允许改管理页视觉/交互、后台壳或样式。

- 输入`0310fba15777fdc9ba00e7e49a980817fd0c5d12`，员工树与origin/gitlab25任务分支回读一致。审核树快进吸收该输入，只读检查员工树，没有修改员工业务实现。
- 员工清单26个提交文件、3个验收输入的SHA-256逐项与提交及员工文件一致。frontend相对`94749f3a`恰好8个批准填写文件有差异；管理页/壳/样式不变；160条迁移与锁文件相对N1无差异。
- 审核独立运行真实PG定向80/0/0/0（记录34、登记20、可比9、管理17）、前端5文件71/71及typecheck。另以真实公共服务及独占PG运行后端反例，以真实TS模块、Vue和JSDOM运行前端反例；没有用替身替换被审算法。
- 员工默认套件2056/0/0/1、BUILD SUCCESS已核对原日志；浏览器58项/28张截图清单及其中旧版记录、草稿迁移截图已核对。审核没有重跑默认全量、浏览器或黄金；这些既有检查没有覆盖下述失败边界，不能据此批准N2。
- 审核证据归`C:/Users/admin/.codex/worktrees/form-admin-ui/data_collection_platform/.tmp/n2-review-20261028/`：`verification.json`、`directed-backend.log`、`directed-frontend.log`、`backend-probe.json`、`frontend-probe.json`及相应源文件/日志。后端探针独占PG16容器`35887ac81aebcf443d93c3133b6a85e1cdbfac0b01209947d0c87953edee6de6`、随机端口50591，结束按ID删除且inspect确认不存在；无业务服务/后台调度/共享库/外部调用。

### 逐项问题与关闭条件

| 编号 | 级别与位置（绑定0310fba1） | 独立复现与实际影响 | 必须修复及正式回归 |
| --- | --- | --- | --- |
| N2-R01 | P1，`FormRecordService.java:264,471`及写侧重放调用 | `create`仍先查当前物理表，之后才查目录。删除目录行而保留物理记录后，公开服务仍返回REPLAYED，绕过目录证据；origin=1/storage=2的合法旧v5承载形状，目录路径以原版指纹返回REPLAYED，公开服务携带同范围已知版本2却先按版本2重算而409“请求标识与本次操作不一致”。计划明确要求删除的“先v5后目录”仍在 | 统一目录优先重放；首次指纹只按目录origin冻结规格/账号/完整C核验，请求版本只作已知同范围版本证据校验。删除被替代旁路及无效调用者，事务内竞争重放也必须同一契约，不能在快速路径或锁内退回当前物理表。正式测试从公开服务/HTTP入口覆盖origin≠storage、原版/快照/承载版请求、缺目录/缺证据/异账号/C/载荷/作废，断言正确返回同一ID或明确拒绝且记录/审计/head零变化 |
| N2-R02 | P1（新增受阻），附P2元数据缺失；`FormRecordWriteService.java:302`、`FormRecordService.java:182` | 真实登记NONE旧Demo历史后，在同范围同MR启用新RECORDS版：普通新建报409“目录承载版本不含记录表，无法回表”；手工传`confirmSameReviewer=true`却能创建。原因是同人扫描先把只读NONE送入仅支持记录表的写上下文。另旧表created_at/updated_at都有值，detail却均为NULL | 同人判断使用可读版本/实际冻结人员身份契约，不以写上下文读取NONE，不从姓名伪造userId；缺权威人员ID的旧行不凭姓名推断同人，也不阻断合法新增。保留原有可证实创建/更新时间，无原值才为空，不补造时间或CAS证据。真实PG覆盖NONE历史+当前原生版正常新增、同一已知人员跨版提示、未知旧人员只读及非误报、原值与已知时间不丢失；经页面验证无需额外确认或绕过参数即可新建，旧行仍原位只读 |
| N2-R03 | P1，`draft-store.ts:234,253` | 同账号/C/会话/draftId同时存在有效v2与较旧v1：读取后字段`newer-v2`变`stale-v1`，requestKey从`newer-key`变`older-key`，submitState从unknown降为none，修订5变1。迁移还会在Alice读取时改写并删除Bob的v1。旧标签页写回或v1删除失败可形成此形状，会丢输入并破坏结果未知保护 | 严格执行N0“有效v2优先，v2不可读才按批准规则处理v1”；迁移不得覆盖任何有效v2或降级unknown/key/首次快照。只处理当前账号/C及对应会话身份，不因读取一个账号而迁移其它账号。写v2失败保留v1；删除v1失败不能导致下次覆盖有效v2。正式回归覆盖有效v2+残留v1、损坏v2、存储写入/删除异常、旧标签页重新写v1、分叉/401/换账号；真实页面覆盖unknown新版草稿与残留旧草稿共存、刷新与多标签恢复，原输入/key/状态不变 |
| N2-R04 | P2，`form-filling-session.ts:439,621`、`CollectFormView.vue:195` | versionSpecHash被持久化却未用于结构守卫：同storageVersionId下hash-A变hash-B，编辑块仍readOnly=false、structureStale=false。所属规格缺失时，`specForRecord`及页面recordSpec/blockSpec回退当前规格；反例旧记录版本10被显示成仅当前版字段currentOnly，虽编辑被禁，展示仍套错结构 | 按已批N0规格哈希/所属版本守卫。规格缺失或证据失配时明确“记录版本不可用”并保留原草稿/快照、只读；不能用当前规格渲染或归一化旧版载荷。若按目录可定位已知记录则按原合同恢复，不能无证据改绑。补会话与真实页面组件测试：同ID哈希变更、缺少版本规格、重新加载/跨标签恢复、unknown核验与显示均不套当前字段，输入和首次快照不变；正常另一版本发布不误锁仍可读的原版编辑 |
| N2-R05 | P2，验收`environment.md`及状态边界表述 | 报告与进度承认一次后台作业尝试连接内网Mongo，但环境文档仍写“未访问内网或远端”，启动命令只关闭GitLab镜像调度，没有`--platform.background-jobs.enabled=false`，照抄会重现未经授权外连；进度同时写连接尝试与未访问内网，互相矛盾 | 冻结原始日志、准确区分尝试连接/连接失败/可证实写入边界，纠正复现命令、夹具启动与状态文案；所有本轮应用启动显式关闭全部后台作业，DB/代理/端口指向自有环境且不通即退出。不得为了证明而再连该地址或查真实库。新验收日志核实后台作业未启动、无外部连接尝试；不改共享环境，不因该记录重做N1-R05已关闭取证 |

### AI员工下一步执行命令

1. 在原员工树读取最新AGENTS、本计划与progress，核对status/worktree/HEAD和实际中文分支归属。由origin或gitlab25获取审核分支`表单管理界面开发-20261027`，确认本次输入为祖先后`git merge --ff-only <本次审核回执提交>`。保留既有提交；不reset、不重命名现有分支、不操作其它worktree。先更新本计划的整改步骤、环境和恢复命令。
2. **先补会失败的正式回归，再修R01—R04**；每个失败须落在真实运行逻辑而非编译/夹具错误。R01/R02优先公共服务+真实PG；R03优先真实localStorage行为并补浏览器；R04覆盖实际页面和会话。删除旧旁路，搜索调用者，保留已批准权限、CAS、栅栏、未知核验及原版不搬表规则。
3. 同步修R05的验收命令与事实表述。仅使用自有Testcontainers或显式专属数据库；所有应用启动关闭全部后台作业。原始失败材料只冻结引用，不覆盖成成功日志；无需重跑N1共享环境取证或重复已关闭项。
4. 必要填写页功能修复已获本单授权，限原N2范围；**管理页`FormTemplateAdminView.vue`、`views/form-template-admin/`、后台壳和样式保持94749f3a**。不实施S1管理功能、N3发布器、N4事实/统计/Excel、N5/W5或真实权限/登记；160条迁移不可改，新增迁移确需先报原因与影响。
5. 最终状态完成相关真实PG定向、一次默认后端套件、前端typecheck/lint/Vitest/build、受影响真实服务页面与数据库对账。补齐原准入要求的三模板两版不同字段（现有浏览器夹具只有走查表两版，Issue各一版）；保持N2范围，用受控夹具模拟当前指针，不能提前执行N3。缺陷用例保留RED/GREEN；固定断言验证修复，不增加睡眠、重试或放宽超时。
6. 完成仓库护栏、完整差异/清单哈希、旧迁移与管理UI零差异、资源按归属清理与回读，更新本计划及当前进度。按职责提交并同步origin/gitlab25当前任务分支，回读一致后交N2复验。未获通过不进入N3、不合main、不出包、不运行或更新黄金。

### 整改执行记录（2026-10-28 至 2026-10-29，输入 `db1eacc9`）

- **吸收与边界**：审核回执`db1eacc9`已快进吸收到`表单多版本与填写功能开发-20261026`；管理页`FormTemplateAdminView.vue`、`views/form-template-admin/`、后台壳与样式相对`94749f3a`零差异；160条迁移与锁文件零差异；未新增迁移，未进入N3/N4/N5/W5，未合main、未出包、未运行或更新黄金。
- **RED（先于修复）**：后端新增回归3项在旧实现上`1 failure + 2 errors`（`.tmp-logs/n2-review-red-backend.log`）；前端把新回归跑在HEAD旧实现上得到草稿3项失败/17、会话与页面3项失败/54（`.tmp-logs/n2-review-red-draft.log`、`n2-review-red-session.log`）。失败断言分别落在缺目录证据仍REPLAYED、NONE历史阻断新增、v1覆盖有效v2/跨账号迁移、哈希失配与缺规格仍可用/套当前字段。
- **R01 目录优先重放**：`FormRecordService.create`先按范围/版本证据做目录重放，再校验当前版本；`replayIfPresent`只按目录origin冻结规格、首次账号与完整载荷核验，响应按目录承载版本回表；删除`FormRecordStore.findByRequestKey`、写服务公开重放与事务内物理表查询，插入竞争也只走目录核验。GREEN 6/0/0/0（`.tmp-logs/n2-review-green1-backend.log`）。
- **R02 NONE历史与时间**：同人扫描改用`readVersionContext`（允许NONE只读版本），不把NONE送入写上下文、不从姓名推断人员；目录读取携带旧表`created_at/updated_at`，detail按原值返回。真实PG覆盖NONE历史+当前原生版正常新增、同人提示、旧行只读与时间不丢失。
- **R03 草稿v2优先**：读取先收集有效v2身份，残留v1即使删除失败也不再参与；迁移仅处理当前账号与上下文，v2写入失败保留v1，v1删除失败不影响v2。新增17项草稿回归全部通过；浏览器覆盖unknown新版草稿与残留旧草稿共存、刷新及多标签恢复，输入/requestKey/unknown状态不变。
- **R04 规格守卫**：会话`structureStale`要求所属版本规格存在且`versionSpecHash`与`readableVersions`证据一致，否则只读并保留草稿/首次快照；`specForRecord`/`recordSpec`/`blockSpec`取消当前版回退，缺规格时记录卡与填写块提示“记录版本不可用”。浏览器真实变更数据库冻结规格后验证锁定与恢复后解锁。
- **R05 环境与边界**：`environment.md`改为冻结原始连接尝试日志、明确“尝试连接/对端关闭/无成功操作”的可证实边界；启动命令补齐`--platform.background-jobs.enabled=false`与Mongo自动配置排除；整改后日志调度线程0、外部地址0、仅`127.0.0.1:15606`。未再次连接该地址或真实库。
- **N2/N3 边界用例**：`FormTemplateAdminIntegrationTest.shouldMigrateFieldChangesAndConvergeOldRequestKey`原断言目标版字段，依赖被删除的“先当前物理表”旁路；现改为按目录承载版本返回并显式断言`storageVersionId`仍为旧版。按复审判定，N3普通发布建立空新版本、旧记录保留原版，不迁移不改绑，旧记录原位只读/可编辑仍以其承载版本为准。曾试在切换事务内改绑目录，因越过N3边界并使`shouldMarkLegacyIncompleteWhenMigrationAddsRequiredField`失败而回退；未保留该实现。
- **最终验证**：真实PG定向4类83/0/0/0`BUILD SUCCESS`（`.tmp-logs/n2-final-targeted-backend2.log`）；前端173文件925/925通过、typecheck/lint/build通过；浏览器`ALL CHECKS PASSED (83)`、34次截图记录/34张PNG（`n2-acceptance-recheck10.log`、`evidence/`）；默认后端套件2059/0/0/1`BUILD SUCCESS`（`.tmp-logs/n2-final-default-suite2.log`）。首次全量在MySQL容器`port: 3306`日志等待处超时，`CodeReviewMatchModeLegacyRefreshIntegrationTest`记1项环境错误；同代码单类复跑4/0/0/0（`n2-final-mysql-recheck.log`）后整套复跑转绿，两份全量日志均保留，不用重跑掩盖非确定性根因。护栏：160条迁移、工件位置、白空格与`git diff --check`全部通过。
- **前端差异**：相对`94749f3a`为9个填写功能文件（原8个加`components/collect-forms/FormRecordCard.vue`，仅用于缺规格记录的只读降级展示）；管理UI零差异。



本计划在N2未通过期间保留；通过后将长期有效事实归权威文档，再按AGENTS删除计划，历史由Git保留。

<a id="n2-rereview-20261029"></a>

## N2整改复验与下一步执行单（2026-10-29）

**结论：修改后重审，仅R04未关闭；N3仍未准入。** 本次输入`8fe425bbaa2b8df06d20596fb9a6af1f07c0529f`，实现为`3d3fd5c4`与`a50daddd`。审核在原专属树快进吸收输入，没有修改员工实现目录。

### 已关闭项与阶段裁定

- R01关闭：公开创建与事务内竞争均按目录origin核验首次账号、完整C与载荷，当前物理表旁路已删除；目录缺失明确拒绝，origin与承载版本不同的合法请求返回同一记录。
- R02关闭：NONE历史用只读上下文扫描，不从姓名推断人员ID；与当前原生版并存时正常新增，同人提示及旧行只读保持，创建/更新时间按旧表真实值返回。
- R03关闭：有效v2保持输入、原key、unknown和修订号，残留v1不覆盖；读取Alice不迁移Bob，写入失败保留v1，删除失败不破坏v2权威。
- R05关闭：已纠正启动命令与连接尝试边界，冻结原日志而不再次访问目标；新日志关闭后台作业、排除Mongo自动配置，仅出现本机验收库。证据不用于声称旧尝试绝无外部影响。
- 接受旧发布器测试暂按目录真实承载位置返回旧版字段，限于N2阶段，不能将其称为v6发布已通过。**N3普通发布必须建立空新版本并保留原生旧记录的目录承载位置，不迁移、不改绑旧记录。** 旧v5副本的唯一承载登记仍归N1，旧未完成任务受控收口归N3，不新增普通发布改绑路径。

### R04剩余问题（位置绑定8fe425bb）

| 编号 | 级别与位置 | 独立复现及影响 | 关闭条件 |
| --- | --- | --- | --- |
| R04-a | P1，`CollectFormView.vue:1395`及该表单内核验按钮 | 新建提交响应丢失形成unknown；刷新后当前版本23存在，但该草稿的版本22规格缺失。页面显示“记录版本不可用”，复制/丢弃禁用，却把包在`v-if="blockSpec(block)"`中的“核验提交结果”一起移除。当前会话本可携原快照发起目录核验，用户却无入口。真实Vue组件反例在最终按钮断言失败，其余16项通过 | 仅字段输入/展示依赖规格；unknown核验入口与字段规格渲染解耦，缺规格时仍可使用原提交快照核验。失败继续unknown并说明未完成，不创建新key或放开复制/丢弃。恢复规格后同一草稿能收敛；不回退当前规格、不新增弹窗 |
| R04-b | P1，`form-filling-session.ts:1205`至`:1226` | 编辑所属结构版本10、记录修订号1的记录并提交“submitted”，响应丢失；核验返回同一记录、修订号2且实际字段已是“submitted”，但结构版本10的规格缺失。实现把保存值替代为`{}`参与比较，最终从unknown转conflict，`canCopy/canDiscard`均从false变true。真实TS会话已复现；缺规格不能证明载荷不一致 | 编辑核验先确认所属规格与首次哈希证据足以比较；缺失或失配时保留unknown、原快照/输入/基础版本及禁止复制/丢弃，提示核验未完成。规格恢复后重新核验可确认成功；不能借用当前规格或用空对象/错误规格得出未写入、冲突结论 |

### 验证与证据边界

审核证据归本树`.tmp/n2-rereview-20261029/`。17个提交文件与6个验收输入共23项哈希一致，双远端均为输入提交；160条迁移及管理页/后台壳/样式无差异。审核独立真实PG83/0/0/0（记录36、登记21、可比9、管理17）、前端4文件78/78与typecheck通过；原反例重跑确认目录缺失拒绝、origin/承载版不同正常重放、NONE正常新增及时间一致、有效v2/账号隔离/哈希守卫生效。员工默认2059/0/0/1、前端925项及83项/34图浏览器材料的原日志/清单已核对，审核未重跑默认全量、浏览器或黄金。

R04-a真实组件补充反例在“核验按钮仍存在”断言失败，其余16项通过；R04-b真实TS会话从unknown转conflict且复制/丢弃均放开。临时测试文件运行后按原字节恢复，业务代码未改。后端探针首轮仅在结果JSON编码LocalDateTime时失败，容器已关闭；将探针时间值转为字符串后复跑成功，两轮材料均保留，不计产品缺陷。两个独占PG容器均按ID确认已删除；没有启动业务服务或后台作业，没有访问共享库/真实外部。最终结果与文件指纹见同目录`verification.json`。

### AI员工下一步执行命令

1. 在原员工树核对AGENTS、status/worktree/HEAD与本节，确认`8fe425bb`为祖先后快进吸收本次审核回执。保留已关闭R01/R02/R03/R05，不重做N1或新增后端/迁移工作。
2. 在安全提交点吸收已批准填写页分支`origin/codex/form-filling-ui-20261028`的`45d80c36`（包含此前`9622a93a`）。此分支与N2修复已分叉，不能要求ff或整体覆盖文件；正常合并并逐处解决功能冲突，保留双方测试及文档。**保持其紧凑页头、记录卡片/控件分组、原位编辑、新建独立区和现有样式；管理页仍保持94749f3a。** 只接入缺规格/核验功能，不进行视觉重构。
3. 先将R04-a/b的实际失败场景转为正式组件/会话回归，再修实现。字段不可显示不等于不能核验首次提交；编辑缺证据不等于载荷冲突。覆盖缺规格或首次哈希失配时unknown不降级，以及规格恢复后同一快照收敛；不扩展无关防御矩阵，不加睡眠、重试或新确认。
4. 清除文档中“普通发布改绑旧记录”的说法，按上方N3裁定保留旧记录原位；不要为使旧发布器测试返回新版字段而恢复旁路或实施迁移。架构中已删除的物理表优先重放不再写为当前实现。
5. 最终合并状态运行受影响填写组件/会话/草稿与原位编辑测试、typecheck/lint/build；真实页面基础检查原位编辑/保存取消、新建独立区及上述核验恢复即可。只修改前端和文档时沿用本次已核对的后端证据，无须重跑后端全量；不跑黄金、不更改批准视觉。按职责提交、双远端回读、清理自有资源后送R04复验；通过前不进入N3、不合main、不出包。

<a id="n2-r04-residual-20261029"></a>

## N2-R04残留修复与填写页合并（2026-10-29，送R04复验）

**输入`dc66682d`，仅R04-a/b两项整改；R01/R02/R03/R05保持关闭，N3仍未准入。** 本记录不含N3实现，不含迁移、后端行为或管理UI改动。

### 合并已批准填写页

- 以正常合并`b262c43e`吸收`origin/codex/form-filling-ui-20261028`的`45d80c36`（含`9622a93a`），逐处保留双方测试与文档：紧凑页头、记录卡片/控件分组、原位编辑替换卡片、独立新建区及`form-filling.css`全部保留；N2的缺规格只读降级并入`FormRecordCard`（`spec`为空只显示基础信息与“记录版本不可用”提示）。
- 管理页`FormTemplateAdminView.vue`、`views/form-template-admin/`、后台壳与全局样式仍与`94749f3a`零差异；本轮无视觉重构。

### R04-a 缺规格核验入口

- **RED**：`CollectFormView.test.ts`新增用例在合并后实现上“核验提交结果”断言失败（`.tmp-logs/n2-r04-residual-red2.log`，2文件2项失败、60项通过）。
- **修复**：`CollectFormView.vue`只让字段输入/展示依赖`blockSpec`，填写块底部操作区移出`v-if="blockSpec(block)"`的表单；unknown时“核验提交结果”始终可点，取消仍按`canDiscardBlock`禁用。未新增key、弹窗或确认。
- **GREEN**：真实组件断言缺规格时显示“记录版本不可用”、核验入口存在、取消禁用；点击后仍为unknown、无冲突文案、复制/丢弃保护保持。

### R04-b 编辑核验缺规格误判冲突

- **RED**：`form-filling-session.test.ts`新增用例在原实现上`expected 'conflict' to be 'unknown'`（同上日志）。
- **修复**：`form-filling-session.ts`编辑核验先取所属版本规格；规格缺失时保留unknown、首次快照与输入，`frozen`保持并提示“核验未完成：所属结构版本当前不可用”，不再以空对象参与载荷比较，也不放开复制/丢弃。规格恢复后同一快照重新核验按原证据收敛到已保存记录。
- **GREEN**：会话回归覆盖缺规格不降级、冲突记录为空、快照保留、复制/丢弃禁用，以及规格恢复后块随`记录已保存`移除、草稿清空。

### 验证与证据

- 受影响4文件87/87通过（`n2-r04-residual-green3.log`）；前端全量174文件941/941通过（`n2-r04-residual-full-vitest.log`）；typecheck、lint、生产构建通过（`n2-r04-residual-checks.log`、`n2-r04-residual-build.log`）。RED首轮因新用例语法错误未计入，修正后`red2`才作为失败证据。
- 专属环境PG15606/后端18092/Vite18185（后台作业关闭、排除Mongo自动配置），真实页面`r04-page-check.mjs`12/12通过：原位编辑替换与取消恢复、独立新建区、响应丢失进入unknown、缺规格时核验入口与取消禁用、缺规格核验不转冲突且入口保留、拦截解除后同一快照收敛到“记录已保存”。证据`.tmp/w4-n2-r04-20261029/page-check.json`及三张截图。
- 本轮只改前端与文档，后端83项证据沿用`8fe425bb`轮已核对结果，未重跑后端全量、未跑黄金、未改批准视觉；160条迁移不变。
- 后端注释与文档同步N3裁定：新版本为空表、旧记录保留原版，不迁移不改绑；旧发布器测试仍按目录承载版本返回。

### 提交与边界

- 独立提交：合并`b262c43e`、R04残留实现与回归、文档回填；双远端回读确认。
- 未进入N3/N4/N5/W5、未合main、未出包、未申请真实权限；专属容器与服务在交付后清理，管理页预览18187/18094/15612未动。
