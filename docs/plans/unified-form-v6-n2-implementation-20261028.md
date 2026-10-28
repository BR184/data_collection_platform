# 统一表单 v6 — N2 原版记录服务与填写页活动计划（2026-10-28）

## 进度与中间物

- [审核结论，2026-10-28] 输入`0310fba15777fdc9ba00e7e49a980817fd0c5d12`修改后重审，N2未通过，N3仍未准入。独立复验80项真实PG、71项前端及typecheck通过，但补充反例确认目录旁路、NONE混用新增失败、草稿覆盖和规格边界缺口；另须纠正验收启动命令及外部连接边界表述。当前唯一详细关闭条件见[首次审核与整改执行单](#n2-first-review)。N1已关闭项、160条迁移及批准管理页UI保持。
- [当前阶段] N1 输入`369194aa`通过，N1-R01—R05 全部关闭。N2继续在原批准范围整改，管理页 UI、后台壳/样式维持`94749f3a`，只允许必要填写页功能修复。N3—N5/W5/main/正式包未准入。
- [已完成] 已快进吸收审核回执`6329590a`；N1 活动计划按规则收口删除，过程可用`git show 369194aa:docs/plans/unified-form-v6-n1-implementation-20261027.md`恢复。
- [员工送审状态] P0/P1/P2/P3交付待验；员工真实PG定向80/0/0/0、默认2056/0/0/1、前端916项及58项浏览器检查的证据已核对，不代替补充边界验收。审核复验及未通过项见下文。
- [员工实现说明，仍受下文审核缺口限制] detail多版本字段、所属版编辑/作废与填写页草稿v2已交付；目录优先重放、NONE与新版本并用、草稿迁移优先级及规格守卫尚未完成验收，不能把当前实现视为满足完整合同。管理页、后台壳与样式未改。
- [阻塞] N2-R01—R05未关闭；当前不具备N3准入条件。
- [员工证据] 后端定向与浏览器证据在员工树`.tmp/w4-n2-20261028/`（`input-manifest.json`、`evidence/acceptance-summary.json`、截图、`environment.md`）；最终默认套件日志`.tmp-logs/n2-default-suite-final.log`。员工报告专属容器均已删除。一次未关闭后台调度的验收后端尝试连接内网Mongo并失败，之后报告关闭全部后台作业；可证实影响边界与复现命令须按N2-R05纠正，不沿用“全部未访问外部”的描述。N1证据历史保留。

### 恢复信息

1. **阶段与首条恢复命令**：先读 AGENTS、`docs/progress.md`、主方案与 N0 合同（`v6-n0-technical-admission-20261027.md` §2/§6）及本文件，执行 `git status --short --branch`、`git worktree list`。开发基线`6329590a`，本次审核输入`0310fba1`；现有任务分支已更名为`表单多版本与填写功能开发-20261026`，worktree仍为`D:/projects/dcp-form-w4-20261026`。审核回执分支为`表单管理界面开发-20261027`。复用已核实的现有分支，不切回历史名称。前序：N1（已通过，计划已删）。
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

本计划在N2未通过期间保留；通过后将长期有效事实归权威文档，再按AGENTS删除计划，历史由Git保留。
